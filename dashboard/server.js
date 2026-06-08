const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn, execSync } = require('child_process');
const { parseYaml, toYaml } = require('./yaml');

const PORT = process.env.DASHBOARD_PORT || 3000;
const PROJECT_ROOT = path.resolve(__dirname, '..');
const LOCAL_CONFIG = path.join(PROJECT_ROOT, 'proxy-local', 'src', 'main', 'resources', 'proxy.yml');
const REMOTE_CONFIG = path.join(PROJECT_ROOT, 'proxy-remote', 'src', 'main', 'resources', 'remote.yml');
const TUN_CONFIG = path.join(PROJECT_ROOT, 'tun-adapter', 'config', 'tun.toml');
const PUBLIC_DIR = path.join(__dirname, 'public');
const PRESETS_DIR = path.join(__dirname, 'presets');

if (!fs.existsSync(PRESETS_DIR)) fs.mkdirSync(PRESETS_DIR, { recursive: true });

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
};

// ============================================================
// Process Management
// ============================================================
const processes = {
  'proxy-local': { proc: null, status: 'stopped', logs: [], startedAt: null },
  'tun-adapter': { proc: null, status: 'stopped', logs: [], startedAt: null },
};
const MAX_LOG_LINES = 2000;

function addLog(name, line, stream = 'stdout') {
  const entry = { time: Date.now(), text: line, stream };
  processes[name].logs.push(entry);
  if (processes[name].logs.length > MAX_LOG_LINES) {
    processes[name].logs = processes[name].logs.slice(-MAX_LOG_LINES);
  }
  broadcastSSE('log', { service: name, ...entry });
}

function getJarPath() {
  const jar = path.join(PROJECT_ROOT, 'proxy-local', 'target', 'proxy-local-1.0.0-SNAPSHOT.jar');
  return fs.existsSync(jar) ? jar : null;
}

function getTunBinaryPath() {
  const bin = path.join(PROJECT_ROOT, 'tun-adapter', 'target', 'release', 'tun-adapter');
  return fs.existsSync(bin) ? bin : null;
}

function startProxyLocal() {
  // If we still have a lingering proc reference, clean it up
  if (processes['proxy-local'].proc) {
    try {
      // Check if process is actually alive
      process.kill(processes['proxy-local'].proc.pid, 0);
      return { ok: false, error: 'Already running' };
    } catch {
      // Process is dead but exit event hasn't fired — force cleanup
      processes['proxy-local'].proc = null;
      processes['proxy-local'].status = 'stopped';
      processes['proxy-local'].startedAt = null;
    }
  }
  const jar = getJarPath();
  if (!jar) return { ok: false, error: 'JAR not found. Click Build first.' };

  // Also kill any leftover java process on port 1080
  try { execSync('lsof -ti :1080 | xargs kill -9', { stdio: 'ignore' }); } catch {}

  const args = ['-Dproxy.dns.nameservers=114.114.114.114,223.5.5.5', '-jar', jar];
  const proc = spawn('java', args, { cwd: PROJECT_ROOT, stdio: ['ignore', 'pipe', 'pipe'], detached: false });

  processes['proxy-local'].proc = proc;
  processes['proxy-local'].status = 'starting';
  processes['proxy-local'].startedAt = Date.now();
  processes['proxy-local'].logs = [];
  addLog('proxy-local', `[dashboard] Starting proxy-local (PID: ${proc.pid})...`);

  proc.stdout.on('data', (data) => {
    data.toString().split('\n').filter(l => l.trim()).forEach(l => {
      addLog('proxy-local', l, 'stdout');
      if (l.includes('started on port') || l.includes('Listening') || l.includes('Started') || l.includes('JVM running')) {
        processes['proxy-local'].status = 'running';
        broadcastSSE('status', getStatusAll());
      }
    });
  });
  proc.stderr.on('data', (data) => {
    data.toString().split('\n').filter(l => l.trim()).forEach(l => {
      addLog('proxy-local', l, 'stderr');
      // Spring Boot prints startup info on stderr too sometimes
      if (l.includes('started on port') || l.includes('Started') || l.includes('JVM running')) {
        processes['proxy-local'].status = 'running';
        broadcastSSE('status', getStatusAll());
      }
    });
  });
  proc.on('close', (code) => {
    addLog('proxy-local', `[dashboard] Exited (code ${code})`);
    processes['proxy-local'].proc = null;
    processes['proxy-local'].status = 'stopped';
    processes['proxy-local'].startedAt = null;
    broadcastSSE('status', getStatusAll());
  });
  proc.on('error', (err) => {
    addLog('proxy-local', `[dashboard] Error: ${err.message}`, 'stderr');
    processes['proxy-local'].proc = null;
    processes['proxy-local'].status = 'stopped';
    broadcastSSE('status', getStatusAll());
  });

  setTimeout(() => {
    if (processes['proxy-local'].status === 'starting') {
      try { execSync('lsof -i :1080 -sTCP:LISTEN', { stdio: 'ignore' }); processes['proxy-local'].status = 'running'; } catch {}
      broadcastSSE('status', getStatusAll());
    }
  }, 5000);

  broadcastSSE('status', getStatusAll());
  return { ok: true, pid: proc.pid };
}

function stopProxyLocal() {
  const proc = processes['proxy-local'].proc;
  if (!proc) {
    // Maybe there's a leftover process on port 1080 not managed by us
    try {
      execSync('lsof -ti :1080 | xargs kill -9', { stdio: 'ignore' });
      addLog('proxy-local', '[dashboard] Killed orphan process on :1080');
      broadcastSSE('status', getStatusAll());
      return { ok: true };
    } catch {}
    return { ok: false, error: 'Not running' };
  }
  addLog('proxy-local', '[dashboard] Stopping...');
  proc.kill('SIGTERM');
  // Force kill after 3 seconds if it doesn't exit
  const killTimer = setTimeout(() => {
    if (processes['proxy-local'].proc) {
      addLog('proxy-local', '[dashboard] Force killing (SIGKILL)...');
      try { process.kill(proc.pid, 'SIGKILL'); } catch {}
      // Also kill by port in case tree processes survived
      try { execSync('lsof -ti :1080 | xargs kill -9', { stdio: 'ignore' }); } catch {}
      processes['proxy-local'].proc = null;
      processes['proxy-local'].status = 'stopped';
      processes['proxy-local'].startedAt = null;
      broadcastSSE('status', getStatusAll());
    }
  }, 3000);
  killTimer.unref();
  return { ok: true };
}

// Check if tun-adapter binary has setuid root permission
function tunHasSetuid() {
  const bin = getTunBinaryPath();
  if (!bin) return false;
  try {
    const stat = fs.statSync(bin);
    // Check owner is root (uid 0) and setuid bit is set (mode & 0o4000)
    return stat.uid === 0 && (stat.mode & 0o4000) !== 0;
  } catch { return false; }
}

// TUN log file for reading output from the background root process
const TUN_LOG_FILE = path.join(__dirname, '.tun-adapter.log');
const TUN_PID_FILE = path.join(__dirname, '.tun-adapter.pid');
let tunLogTail = null;

function startTunAdapter() {
  // Check if already running (by PID file or by interface)
  if (isTunRunning()) {
    processes['tun-adapter'].status = 'running';
    processes['tun-adapter'].startedAt = processes['tun-adapter'].startedAt || Date.now();
    broadcastSSE('status', getStatusAll());
    return { ok: true, message: 'Already running (detected externally)' };
  }

  const bin = getTunBinaryPath();
  if (!bin) return { ok: false, error: 'Binary not found. Click Build first.' };

  processes['tun-adapter'].status = 'starting';
  processes['tun-adapter'].startedAt = Date.now();
  processes['tun-adapter'].logs = [];
  broadcastSSE('status', getStatusAll());
  addLog('tun-adapter', '[dashboard] Starting tun-adapter...');

  // Clear log file
  try { fs.writeFileSync(TUN_LOG_FILE, '', 'utf-8'); } catch {}

  // On macOS, setuid binaries cannot be spawned directly from Node.js due to
  // SIP restrictions (EPERM). We use osascript to request admin privileges
  // via the system authorization dialog, then run the binary as a background
  // process with output redirected to our log file.
  const launchScript = `"${bin}" -c "${TUN_CONFIG}" > "${TUN_LOG_FILE}" 2>&1 & echo $!`;

  let proc;
  try {
    // First try direct spawn (works if SIP is off or binary is in trusted path)
    proc = spawn(bin, ['-c', TUN_CONFIG], {
      cwd: PROJECT_ROOT,
      stdio: ['ignore', 'pipe', 'pipe'],
      detached: true,
    });
  } catch (spawnErr) {
    // Direct spawn failed (EPERM) — fall back to osascript with admin privileges
    addLog('tun-adapter', `[dashboard] Direct spawn failed (${spawnErr.code || spawnErr.message}), requesting admin privileges...`);
    return startTunViaSudo(bin);
  }

  // Handle the case where spawn succeeds initially but the process errors out
  // (e.g., EPERM is delivered asynchronously via the 'error' event)
  let spawnFailed = false;

  proc.on('error', (err) => {
    spawnFailed = true;
    addLog('tun-adapter', `[dashboard] Spawn failed: ${err.message}, trying osascript fallback...`, 'stderr');
    processes['tun-adapter'].proc = null;
    // Try osascript fallback
    startTunViaSudo(bin);
  });

  // Save PID
  try { fs.writeFileSync(TUN_PID_FILE, String(proc.pid), 'utf-8'); } catch {}

  processes['tun-adapter'].proc = proc;
  addLog('tun-adapter', `[dashboard] Spawned tun-adapter (PID: ${proc.pid})`);
  broadcastSSE('status', getStatusAll());

  // Pipe stdout/stderr to logs and detect running state from output
  proc.stdout.on('data', (data) => {
    const text = data.toString();
    try { fs.appendFileSync(TUN_LOG_FILE, text); } catch {}
    text.split('\n').filter(l => l.trim()).forEach(l => {
      addLog('tun-adapter', l, 'stdout');
      if (processes['tun-adapter'].status === 'starting') {
        processes['tun-adapter'].status = 'running';
        broadcastSSE('status', getStatusAll());
      }
    });
  });
  proc.stderr.on('data', (data) => {
    const text = data.toString();
    try { fs.appendFileSync(TUN_LOG_FILE, text); } catch {}
    text.split('\n').filter(l => l.trim()).forEach(l => {
      addLog('tun-adapter', l, 'stderr');
      // stderr output also means the process is alive and producing output
      if (processes['tun-adapter'].status === 'starting') {
        processes['tun-adapter'].status = 'running';
        broadcastSSE('status', getStatusAll());
      }
    });
  });

  proc.on('close', (code) => {
    addLog('tun-adapter', `[dashboard] tun-adapter exited (code ${code})`);
    processes['tun-adapter'].proc = null;
    processes['tun-adapter'].status = 'stopped';
    processes['tun-adapter'].startedAt = null;
    try { fs.unlinkSync(TUN_PID_FILE); } catch {}
    broadcastSSE('status', getStatusAll());
  });

  // Unref so it doesn't block Node exit, but we keep the reference for management
  proc.unref();

  // Check status after a few seconds
  setTimeout(() => {
    if (spawnFailed) return; // already handled
    if (processes['tun-adapter'].status === 'starting') {
      // If we get here, no output was produced but let's check PID
      if (isTunRunning()) {
        processes['tun-adapter'].status = 'running';
        addLog('tun-adapter', '[dashboard] Process confirmed alive.');
      } else {
        processes['tun-adapter'].status = 'stopped';
        processes['tun-adapter'].proc = null;
        processes['tun-adapter'].startedAt = null;
        addLog('tun-adapter', '[dashboard] Process exited unexpectedly. Check logs.');
      }
      broadcastSSE('status', getStatusAll());
    }
  }, 4000);

  return { ok: true, pid: proc.pid };
}

// Fallback: use sudo (NOPASSWD) to launch tun-adapter with root privileges.
// Requires one-time setup: run `dashboard/setup-tun-permissions.sh` to configure sudoers.
function startTunViaSudo(bin) {
  addLog('tun-adapter', '[dashboard] Launching via sudo...');

  // Use sudo to run tun-adapter. This requires NOPASSWD sudoers entry.
  let proc;
  try {
    proc = spawn('sudo', ['-n', bin, '-c', TUN_CONFIG], {
      cwd: PROJECT_ROOT,
      stdio: ['ignore', 'pipe', 'pipe'],
      detached: true,
    });
  } catch (e) {
    // spawn itself can throw EPERM in sandboxed environments (sudo is setuid)
    const msg = `无法启动 sudo (${e.code || e.message})。请在真实终端中启动 Dashboard，或手动运行:\n  sudo ${bin} -c ${TUN_CONFIG}`;
    addLog('tun-adapter', `[dashboard] ${msg}`, 'stderr');
    processes['tun-adapter'].status = 'stopped';
    processes['tun-adapter'].startedAt = null;
    broadcastSSE('status', getStatusAll());
    return { ok: false, error: msg };
  }

  let spawnOk = true;
  proc.on('error', (err) => {
    spawnOk = false;
    addLog('tun-adapter', `[dashboard] sudo spawn error: ${err.message}`, 'stderr');
    processes['tun-adapter'].status = 'stopped';
    processes['tun-adapter'].startedAt = null;
    processes['tun-adapter'].proc = null;
    broadcastSSE('status', getStatusAll());
  });

  // Save PID (this is the sudo process PID; the actual tun-adapter runs as child)
  try { fs.writeFileSync(TUN_PID_FILE, String(proc.pid), 'utf-8'); } catch {}
  processes['tun-adapter'].proc = proc;

  proc.stdout.on('data', (data) => {
    const text = data.toString();
    try { fs.appendFileSync(TUN_LOG_FILE, text); } catch {}
    text.split('\n').filter(l => l.trim()).forEach(l => {
      addLog('tun-adapter', l, 'stdout');
      if (processes['tun-adapter'].status === 'starting') {
        processes['tun-adapter'].status = 'running';
        broadcastSSE('status', getStatusAll());
      }
    });
  });

  proc.stderr.on('data', (data) => {
    const text = data.toString();
    try { fs.appendFileSync(TUN_LOG_FILE, text); } catch {}
    text.split('\n').filter(l => l.trim()).forEach(l => {
      addLog('tun-adapter', l, 'stderr');
      // "sudo: a password is required" means sudoers not configured
      if (l.includes('password is required') || l.includes('sudo:')) {
        processes['tun-adapter'].status = 'stopped';
        processes['tun-adapter'].startedAt = null;
        processes['tun-adapter'].proc = null;
        addLog('tun-adapter', '[dashboard] sudo 需要密码。请先运行一次 setup-tun-permissions.sh 配置免密启动。', 'stderr');
        broadcastSSE('status', getStatusAll());
      } else if (processes['tun-adapter'].status === 'starting') {
        processes['tun-adapter'].status = 'running';
        broadcastSSE('status', getStatusAll());
      }
    });
  });

  proc.on('close', (code) => {
    if (code !== 0 && processes['tun-adapter'].status === 'starting') {
      addLog('tun-adapter', `[dashboard] tun-adapter exited immediately (code ${code}). Run setup-tun-permissions.sh first.`, 'stderr');
    } else {
      addLog('tun-adapter', `[dashboard] tun-adapter exited (code ${code})`);
    }
    processes['tun-adapter'].proc = null;
    processes['tun-adapter'].status = 'stopped';
    processes['tun-adapter'].startedAt = null;
    try { fs.unlinkSync(TUN_PID_FILE); } catch {}
    broadcastSSE('status', getStatusAll());
  });

  proc.unref();

  // Check after delay
  setTimeout(() => {
    if (!spawnOk) return;
    if (processes['tun-adapter'].status === 'starting') {
      if (isTunRunning()) {
        processes['tun-adapter'].status = 'running';
        addLog('tun-adapter', '[dashboard] tun-adapter confirmed running (via sudo).');
      } else {
        processes['tun-adapter'].status = 'stopped';
        processes['tun-adapter'].proc = null;
        processes['tun-adapter'].startedAt = null;
        addLog('tun-adapter', '[dashboard] tun-adapter failed to start. Check logs or run setup-tun-permissions.sh.', 'stderr');
      }
      broadcastSSE('status', getStatusAll());
    }
  }, 4000);

  return { ok: true, message: 'Starting via sudo (NOPASSWD)...' };
}

// Write a convenience launch script
function writeTunLaunchScript(bin) {
  const script = `#!/bin/bash
# Run tun-adapter with root privileges
# This script is auto-generated by the dashboard
cd "${PROJECT_ROOT}"
echo "Starting tun-adapter..."
sudo "${bin}" -c "${TUN_CONFIG}"
`;
  const scriptPath = path.join(__dirname, 'start-tun.sh');
  try {
    fs.writeFileSync(scriptPath, script, { mode: 0o755 });
    addLog('tun-adapter', `[dashboard] Launch script written: ${scriptPath}`);
  } catch {}
}

function stopTunAdapter() {
  addLog('tun-adapter', '[dashboard] Stopping...');

  // Try to kill by managed proc reference first
  if (processes['tun-adapter'].proc) {
    try {
      process.kill(processes['tun-adapter'].proc.pid, 'SIGTERM');
    } catch {}
  }

  // Also try to kill by PID file (the process runs as root, so use sudo -n kill)
  try {
    const pidStr = fs.readFileSync(TUN_PID_FILE, 'utf-8').trim();
    const pid = parseInt(pidStr, 10);
    if (!isNaN(pid)) {
      // First try normal kill (works if we own the process)
      try { process.kill(pid, 'SIGTERM'); } catch (e) {
        if (e.code === 'EPERM') {
          // Process is owned by root — use sudo kill (NOPASSWD configured)
          try { execSync(`sudo -n kill ${pid}`, { stdio: 'ignore', timeout: 5000 }); } catch {}
        }
      }
      // Give it a moment then force kill
      setTimeout(() => {
        try { process.kill(pid, 'SIGKILL'); } catch (e) {
          if (e.code === 'EPERM') {
            try { execSync(`sudo -n kill -9 ${pid}`, { stdio: 'ignore', timeout: 5000 }); } catch {}
          }
        }
      }, 1000);
    }
  } catch {}

  // Also pkill as fallback (sudo -n for root-owned processes)
  try { execSync("pkill -f 'tun-adapter' 2>/dev/null || true", { stdio: 'ignore' }); } catch {}
  try { execSync("sudo -n pkill -f tun-adapter 2>/dev/null || true", { stdio: 'ignore', timeout: 5000 }); } catch {}

  // Clean up
  stopTunLogTail();
  try { fs.unlinkSync(TUN_PID_FILE); } catch {}
  processes['tun-adapter'].proc = null;
  processes['tun-adapter'].status = 'stopped';
  processes['tun-adapter'].startedAt = null;
  broadcastSSE('status', getStatusAll());
  addLog('tun-adapter', '[dashboard] Stopped.');
  return { ok: true };
}

function isTunRunning() {
  // Method 1: Check PID file
  try {
    const pidStr = fs.readFileSync(TUN_PID_FILE, 'utf-8').trim();
    const pid = parseInt(pidStr, 10);
    if (!isNaN(pid)) {
      try {
        process.kill(pid, 0);
        return true;
      } catch (e) {
        if (e.code === 'EPERM') {
          // EPERM means process exists but we don't have permission — it IS running (as root)
          return true;
        }
        // ESRCH = no such process, PID file is stale
      }
    }
  } catch {}

  // Method 2: Check if any utun interface with our expected IP exists
  // (tun-adapter creates a utun with the configured address, e.g. 10.0.0.1)
  try {
    const ifout = execSync('ifconfig 2>/dev/null', { encoding: 'utf-8' });
    // Look for utun interfaces with our TUN IP (configured in tun.toml)
    if (ifout.includes('198.18.0.1')) {
      return true;
    }
  } catch {}

  // Method 3: Check if tun-adapter binary process is running (exclude node/grep/pgrep itself)
  try {
    const result = execSync("pgrep -x tun-adapter", { stdio: 'pipe', encoding: 'utf-8' });
    if (result.trim()) return true;
  } catch {}

  return false;
}

function startTunLogTail() {
  stopTunLogTail();
  // Ensure log file exists
  if (!fs.existsSync(TUN_LOG_FILE)) fs.writeFileSync(TUN_LOG_FILE, '', 'utf-8');

  let lastSize = 0;
  tunLogTail = setInterval(() => {
    try {
      const stat = fs.statSync(TUN_LOG_FILE);
      if (stat.size > lastSize) {
        const fd = fs.openSync(TUN_LOG_FILE, 'r');
        const buf = Buffer.alloc(stat.size - lastSize);
        fs.readSync(fd, buf, 0, buf.length, lastSize);
        fs.closeSync(fd);
        lastSize = stat.size;
        const text = buf.toString('utf-8');
        text.split('\n').filter(l => l.trim()).forEach(l => {
          addLog('tun-adapter', l, 'stdout');
          if (l.includes('TUN') || l.includes('tun') || l.includes('stack_loop') ||
              l.includes('ready') || l.includes('listening') || l.includes('Listening') ||
              l.includes('running') || l.includes('Started')) {
            processes['tun-adapter'].status = 'running';
            broadcastSSE('status', getStatusAll());
          }
        });
      }
      // Also check if process died
      if (!isTunRunning() && processes['tun-adapter'].status !== 'stopped') {
        processes['tun-adapter'].proc = null;
        processes['tun-adapter'].status = 'stopped';
        processes['tun-adapter'].startedAt = null;
        addLog('tun-adapter', '[dashboard] Process exited.');
        broadcastSSE('status', getStatusAll());
        stopTunLogTail();
      }
    } catch {}
  }, 1000);
}

function stopTunLogTail() {
  if (tunLogTail) { clearInterval(tunLogTail); tunLogTail = null; }
}

function getStatusAll() {
  const result = {};
  for (const [name, info] of Object.entries(processes)) {
    result[name] = {
      status: info.status,
      pid: info.proc ? info.proc.pid : null,
      startedAt: info.startedAt,
      uptime: info.startedAt ? Date.now() - info.startedAt : 0,
    };
  }
  if (!processes['proxy-local'].proc) {
    try { execSync('lsof -i :1080 -sTCP:LISTEN', { stdio: 'ignore' }); result['proxy-local'].status = 'running (external)'; } catch {}
  }
  if (!processes['tun-adapter'].proc) {
    if (isTunRunning()) { result['tun-adapter'].status = 'running (external)'; }
  }
  return result;
}

// ============================================================
// Build
// ============================================================
function buildService(name) {
  return new Promise((resolve) => {
    addLog(name, '[dashboard] Building...');
    let cmd, args, cwd;
    if (name === 'proxy-local') {
      cmd = 'mvn'; args = ['package', '-pl', 'proxy-local', '-am', '-DskipTests']; cwd = PROJECT_ROOT;
    } else {
      cmd = 'cargo'; args = ['build', '--release']; cwd = path.join(PROJECT_ROOT, 'tun-adapter');
    }
    const proc = spawn(cmd, args, { cwd, stdio: ['ignore', 'pipe', 'pipe'], shell: true });
    proc.stdout.on('data', d => d.toString().split('\n').filter(l => l.trim()).forEach(l => addLog(name, l)));
    proc.stderr.on('data', d => d.toString().split('\n').filter(l => l.trim()).forEach(l => addLog(name, l, 'stderr')));
    proc.on('exit', code => {
      if (code === 0) { addLog(name, '[dashboard] Build successful'); resolve({ ok: true }); }
      else { addLog(name, `[dashboard] Build failed (code ${code})`, 'stderr'); resolve({ ok: false, error: `exit ${code}` }); }
    });
    proc.on('error', err => resolve({ ok: false, error: err.message }));
  });
}

// ============================================================
// System Proxy (macOS)
// ============================================================
function getSystemProxy() {
  try {
    const lines = execSync('networksetup -listallnetworkservices', { encoding: 'utf-8' }).split('\n');
    const services = lines.filter(l => l.trim() && !l.includes('asterisk') && !l.startsWith('*'));
    const service = services.find(s => s.includes('Wi-Fi')) || services[0] || 'Wi-Fi';
    const socks = execSync(`networksetup -getsocksfirewallproxy "${service}"`, { encoding: 'utf-8' });
    const enabled = socks.includes('Enabled: Yes');
    return { enabled, service };
  } catch { return { enabled: false, service: 'Wi-Fi' }; }
}

function setSystemProxy(enable) {
  try {
    const lines = execSync('networksetup -listallnetworkservices', { encoding: 'utf-8' }).split('\n');
    const services = lines.filter(l => l.trim() && !l.includes('asterisk') && !l.startsWith('*'));
    const service = services.find(s => s.includes('Wi-Fi')) || services[0] || 'Wi-Fi';
    if (enable) {
      execSync(`networksetup -setsocksfirewallproxy "${service}" 127.0.0.1 1080`, { stdio: 'ignore' });
      execSync(`networksetup -setsocksfirewallproxystate "${service}" on`, { stdio: 'ignore' });
      execSync(`networksetup -setwebproxy "${service}" 127.0.0.1 1080`, { stdio: 'ignore' });
      execSync(`networksetup -setsecurewebproxy "${service}" 127.0.0.1 1080`, { stdio: 'ignore' });
    } else {
      execSync(`networksetup -setsocksfirewallproxystate "${service}" off`, { stdio: 'ignore' });
      execSync(`networksetup -setwebproxystate "${service}" off`, { stdio: 'ignore' });
      execSync(`networksetup -setsecurewebproxystate "${service}" off`, { stdio: 'ignore' });
    }
    return { ok: true, enabled: enable };
  } catch (e) { return { ok: false, error: e.message }; }
}

// ============================================================
// Config helpers
// ============================================================
let localConfigMtime = 0, remoteConfigMtime = 0, tunConfigMtime = 0;

function getConfig(reqType) {
  const filePath = reqType === 'remote' ? REMOTE_CONFIG : LOCAL_CONFIG;
  try {
    const text = fs.readFileSync(filePath, 'utf-8');
    const mtime = fs.statSync(filePath).mtimeMs;
    return { ok: true, data: parseYaml(text), raw: text, mtime };
  } catch (e) { return { ok: false, error: e.message }; }
}

function saveConfig(reqType, newData) {
  const filePath = reqType === 'remote' ? REMOTE_CONFIG : LOCAL_CONFIG;
  try {
    const yamlText = toYaml(newData);
    fs.writeFileSync(filePath, yamlText + '\n', 'utf-8');
    return { ok: true };
  } catch (e) { return { ok: false, error: e.message }; }
}

function readTunConfig() {
  try {
    const text = fs.readFileSync(TUN_CONFIG, 'utf-8');
    return { ok: true, raw: text };
  } catch (e) { return { ok: false, error: e.message }; }
}

function saveTunConfig(text) {
  try {
    fs.writeFileSync(TUN_CONFIG, text, 'utf-8');
    return { ok: true };
  } catch (e) { return { ok: false, error: e.message }; }
}

// ============================================================
// HTTP Router
// ============================================================
function parseBody(req) {
  return new Promise((resolve) => {
    let body = '';
    req.on('data', chunk => { body += chunk; if (body.length > 2e6) req.destroy(); });
    req.on('end', () => { try { resolve(JSON.parse(body)); } catch { resolve(body || null); } });
    req.on('error', () => resolve(null));
  });
}

function json(res, code, data) {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(data));
}

function serveStatic(req, res) {
  let urlPath = req.url.split('?')[0];
  if (urlPath === '/') urlPath = '/index.html';
  const filePath = path.join(PUBLIC_DIR, urlPath);
  if (!filePath.startsWith(PUBLIC_DIR)) return json(res, 403, { error: 'forbidden' });
  const ext = path.extname(filePath);
  fs.readFile(filePath, (err, data) => {
    if (err) return json(res, err.code === 'ENOENT' ? 404 : 500, { error: err.message });
    res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
    res.end(data);
  });
}

const sseClients = new Set();
function broadcastSSE(event, data) {
  const msg = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const client of sseClients) { try { client.write(msg); } catch { sseClients.delete(client); } }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const pathname = url.pathname;
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,PUT,DELETE,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }

  // --- Service Control ---
  if (pathname === '/api/status') return json(res, 200, { ok: true, services: getStatusAll(), systemProxy: getSystemProxy() });
  if (pathname === '/api/service/start' && req.method === 'POST') {
    const b = await parseBody(req); if (!b || !b.name) return json(res, 400, { error: 'missing name' });
    const r = b.name === 'proxy-local' ? startProxyLocal() : b.name === 'tun-adapter' ? startTunAdapter() : { ok: false, error: 'unknown' };
    return json(res, r.ok ? 200 : 400, r);
  }
  if (pathname === '/api/service/stop' && req.method === 'POST') {
    const b = await parseBody(req); if (!b || !b.name) return json(res, 400, { error: 'missing name' });
    const r = b.name === 'proxy-local' ? stopProxyLocal() : b.name === 'tun-adapter' ? stopTunAdapter() : { ok: false, error: 'unknown' };
    return json(res, r.ok ? 200 : 400, r);
  }
  if (pathname === '/api/service/restart' && req.method === 'POST') {
    const b = await parseBody(req); if (!b || !b.name) return json(res, 400, { error: 'missing name' });
    if (b.name === 'proxy-local') {
      stopProxyLocal();
      // Wait for process to actually die
      await new Promise(resolve => {
        let checks = 0;
        const interval = setInterval(() => {
          checks++;
          if (!processes['proxy-local'].proc || checks >= 20) { clearInterval(interval); resolve(); }
        }, 200);
      });
      return json(res, 200, startProxyLocal());
    }
    if (b.name === 'tun-adapter') {
      stopTunAdapter();
      await new Promise(resolve => {
        let checks = 0;
        const interval = setInterval(() => {
          checks++;
          if (!processes['tun-adapter'].proc || checks >= 25) { clearInterval(interval); resolve(); }
        }, 200);
      });
      return json(res, 200, startTunAdapter());
    }
    return json(res, 400, { error: 'unknown' });
  }
  if (pathname === '/api/service/build' && req.method === 'POST') {
    const b = await parseBody(req); if (!b || !b.name) return json(res, 400, { error: 'missing name' });
    if (b.name !== 'proxy-local' && b.name !== 'tun-adapter') return json(res, 400, { error: 'unknown' });
    const r = await buildService(b.name);
    return json(res, r.ok ? 200 : 500, r);
  }
  if (pathname === '/api/logs') {
    const name = url.searchParams.get('service');
    if (!name || !processes[name]) return json(res, 400, { error: 'invalid service' });
    return json(res, 200, { ok: true, logs: processes[name].logs.slice(-500) });
  }

  // --- System Proxy ---
  if (pathname === '/api/system-proxy' && req.method === 'GET') return json(res, 200, { ok: true, ...getSystemProxy() });
  if (pathname === '/api/system-proxy' && req.method === 'POST') {
    const b = await parseBody(req);
    return json(res, 200, setSystemProxy(b && b.enabled));
  }

  // --- Config ---
  if (pathname === '/api/config/local' && req.method === 'GET') { const c = getConfig('local'); return json(res, c.ok ? 200 : 500, c); }
  if (pathname === '/api/config/remote' && req.method === 'GET') { const c = getConfig('remote'); return json(res, c.ok ? 200 : 500, c); }
  if (pathname === '/api/config/local' && req.method === 'POST') {
    const b = await parseBody(req); if (!b || !b.data) return json(res, 400, { error: 'missing data' });
    const r = saveConfig('local', b.data);
    if (r.ok) broadcastSSE('config-changed', { type: 'local' });
    return json(res, r.ok ? 200 : 500, r);
  }
  if (pathname === '/api/config/remote' && req.method === 'POST') {
    const b = await parseBody(req); if (!b || !b.data) return json(res, 400, { error: 'missing data' });
    const r = saveConfig('remote', b.data);
    if (r.ok) broadcastSSE('config-changed', { type: 'remote' });
    return json(res, r.ok ? 200 : 500, r);
  }
  if (pathname === '/api/config/tun' && req.method === 'GET') return json(res, 200, readTunConfig());
  if (pathname === '/api/config/tun' && req.method === 'POST') {
    const b = await parseBody(req);
    const text = typeof b === 'string' ? b : (b && b.raw);
    if (!text) return json(res, 400, { error: 'missing raw' });
    const r = saveTunConfig(text);
    if (r.ok) broadcastSSE('config-changed', { type: 'tun' });
    return json(res, r.ok ? 200 : 500, r);
  }

  // --- YAML parse helper ---
  if (pathname === '/api/yaml/parse' && req.method === 'POST') {
    const b = await parseBody(req);
    if (!b || !b.yaml) return json(res, 400, { error: 'missing yaml' });
    try { return json(res, 200, { ok: true, data: parseYaml(b.yaml) }); }
    catch (e) { return json(res, 400, { ok: false, error: e.message }); }
  }

  // --- Presets ---
  if (pathname === '/api/presets' && req.method === 'GET') {
    try {
      const files = fs.readdirSync(PRESETS_DIR).filter(f => f.endsWith('.yml'));
      const presets = files.map(f => ({ name: f.replace('.yml', ''), ...parseYaml(fs.readFileSync(path.join(PRESETS_DIR, f), 'utf-8')) }));
      return json(res, 200, { ok: true, presets });
    } catch (e) { return json(res, 500, { error: e.message }); }
  }
  if (pathname === '/api/presets/save' && req.method === 'POST') {
    const b = await parseBody(req);
    if (!b || !b.name || !b.data) return json(res, 400, { error: 'missing fields' });
    const safe = b.name.replace(/[^a-zA-Z0-9_-]/g, '');
    fs.writeFileSync(path.join(PRESETS_DIR, safe + '.yml'), toYaml(b.data) + '\n', 'utf-8');
    return json(res, 200, { ok: true });
  }
  if (pathname === '/api/presets/delete' && req.method === 'POST') {
    const b = await parseBody(req);
    if (!b || !b.name) return json(res, 400, { error: 'missing name' });
    try { fs.unlinkSync(path.join(PRESETS_DIR, b.name.replace(/[^a-zA-Z0-9_-]/g, '') + '.yml')); } catch {}
    return json(res, 200, { ok: true });
  }

  // --- SSE ---
  if (pathname === '/api/events') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
    res.write(`event: connected\ndata: ${JSON.stringify(getStatusAll())}\n\n`);
    sseClients.add(res);
    req.on('close', () => sseClients.delete(res));
    return;
  }

  // --- Static ---
  serveStatic(req, res);
});

// Init
try { localConfigMtime = fs.statSync(LOCAL_CONFIG).mtimeMs; } catch {}
try { remoteConfigMtime = fs.statSync(REMOTE_CONFIG).mtimeMs; } catch {}
try { tunConfigMtime = fs.statSync(TUN_CONFIG).mtimeMs; } catch {}

server.listen(PORT, () => {
  console.log(`\x1b[32m✓\x1b[0m SimplePlane Dashboard running at \x1b[36mhttp://localhost:${PORT}\x1b[0m`);
  console.log(`  Configs: proxy.yml | remote.yml | tun.toml`);
});

// Graceful shutdown
process.on('SIGINT', () => {
  console.log('\n[dashboard] Shutting down...');
  if (processes['proxy-local'].proc) processes['proxy-local'].proc.kill('SIGTERM');
  if (processes['tun-adapter'].proc) {
    try { execSync(`sudo kill ${processes['tun-adapter'].proc.pid}`, { stdio: 'ignore' }); } catch {}
  }
  setTimeout(() => process.exit(0), 2000);
});
