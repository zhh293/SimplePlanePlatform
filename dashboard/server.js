const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
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

// ============================================================
// Platform Detection
// ============================================================
const IS_WIN = process.platform === 'win32';
const IS_MAC = process.platform === 'darwin';

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
  if (IS_WIN) {
    const bin = path.join(PROJECT_ROOT, 'tun-adapter', 'target', 'release', 'tun-adapter.exe');
    return fs.existsSync(bin) ? bin : null;
  }
  const bin = path.join(PROJECT_ROOT, 'tun-adapter', 'target', 'release', 'tun-adapter');
  return fs.existsSync(bin) ? bin : null;
}

// ============================================================
// Cross-platform helper: kill process on specific port
// ============================================================
function killProcessOnPort(port) {
  try {
    if (IS_WIN) {
      // Find PID listening on port and kill it
      const out = execSync(`netstat -ano | findstr ":${port}" | findstr "LISTENING"`, { encoding: 'utf-8', stdio: 'pipe' });
      const lines = out.trim().split('\n');
      const pids = new Set();
      for (const line of lines) {
        const parts = line.trim().split(/\s+/);
        const pid = parts[parts.length - 1];
        if (pid && !isNaN(parseInt(pid))) pids.add(pid);
      }
      for (const pid of pids) {
        try { execSync(`taskkill /F /PID ${pid}`, { stdio: 'ignore' }); } catch {}
      }
    } else {
      execSync(`lsof -ti :${port} | xargs kill -9`, { stdio: 'ignore' });
    }
  } catch {}
}

// Cross-platform helper: check if port is listening
function isPortListening(port) {
  try {
    if (IS_WIN) {
      execSync(`netstat -ano | findstr ":${port}" | findstr "LISTENING"`, { stdio: 'pipe' });
      return true;
    } else {
      execSync(`lsof -i :${port} -sTCP:LISTEN`, { stdio: 'ignore' });
      return true;
    }
  } catch { return false; }
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
  killProcessOnPort(1080);

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
      if (isPortListening(1080)) { processes['proxy-local'].status = 'running'; }
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
    if (isPortListening(1080)) {
      killProcessOnPort(1080);
      addLog('proxy-local', '[dashboard] Killed orphan process on :1080');
      broadcastSSE('status', getStatusAll());
      return { ok: true };
    }
    return { ok: false, error: 'Not running' };
  }
  addLog('proxy-local', '[dashboard] Stopping...');

  if (IS_WIN) {
    // On Windows, SIGTERM is not well supported; use taskkill
    try { execSync(`taskkill /F /PID ${proc.pid} /T`, { stdio: 'ignore' }); } catch {}
  } else {
    proc.kill('SIGTERM');
  }

  // Force kill after 3 seconds if it doesn't exit
  const killTimer = setTimeout(() => {
    if (processes['proxy-local'].proc) {
      addLog('proxy-local', '[dashboard] Force killing...');
      if (IS_WIN) {
        try { execSync(`taskkill /F /PID ${proc.pid} /T`, { stdio: 'ignore' }); } catch {}
      } else {
        try { process.kill(proc.pid, 'SIGKILL'); } catch {}
        killProcessOnPort(1080);
      }
      processes['proxy-local'].proc = null;
      processes['proxy-local'].status = 'stopped';
      processes['proxy-local'].startedAt = null;
      broadcastSSE('status', getStatusAll());
    }
  }, 3000);
  killTimer.unref();
  return { ok: true };
}

// Check if tun-adapter binary has setuid root permission (macOS only)
function tunHasSetuid() {
  if (IS_WIN) return false; // Not applicable on Windows
  const bin = getTunBinaryPath();
  if (!bin) return false;
  try {
    const stat = fs.statSync(bin);
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

  if (IS_WIN) {
    return startTunWindows(bin);
  } else {
    return startTunMacOS(bin);
  }
}

// ============================================================
// TUN Start — macOS (sudo -n with NOPASSWD)
// ============================================================
function startTunMacOS(bin) {
  let proc;
  try {
    // First try direct spawn (works if SIP is off or binary is in trusted path)
    proc = spawn(bin, ['-c', TUN_CONFIG], {
      cwd: PROJECT_ROOT,
      stdio: ['ignore', 'pipe', 'pipe'],
      detached: true,
    });
  } catch (spawnErr) {
    // Direct spawn failed (EPERM) — fall back to sudo
    addLog('tun-adapter', `[dashboard] Direct spawn failed (${spawnErr.code || spawnErr.message}), trying sudo...`);
    return startTunViaSudo(bin);
  }

  // Handle the case where spawn succeeds initially but the process errors out
  let spawnFailed = false;

  proc.on('error', (err) => {
    spawnFailed = true;
    addLog('tun-adapter', `[dashboard] Spawn failed: ${err.message}, trying sudo fallback...`, 'stderr');
    processes['tun-adapter'].proc = null;
    startTunViaSudo(bin);
  });

  // Save PID
  try { fs.writeFileSync(TUN_PID_FILE, String(proc.pid), 'utf-8'); } catch {}

  processes['tun-adapter'].proc = proc;
  addLog('tun-adapter', `[dashboard] Spawned tun-adapter (PID: ${proc.pid})`);
  broadcastSSE('status', getStatusAll());

  bindTunProcEvents(proc, spawnFailed);

  return { ok: true, pid: proc.pid };
}

// Fallback: use sudo (NOPASSWD) to launch tun-adapter with root privileges.
// Requires one-time setup: run `dashboard/setup-tun-permissions.sh` to configure sudoers.
function startTunViaSudo(bin) {
  addLog('tun-adapter', '[dashboard] Launching via sudo...');

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

// ============================================================
// TUN Start — Windows (requires "Run as Administrator")
// ============================================================
function startTunWindows(bin) {
  // On Windows, TUN requires administrator privileges.
  // The Dashboard itself must be launched from an elevated terminal (Run as Administrator).
  // We try to spawn the binary directly — if it fails, we inform the user.

  let proc;
  try {
    proc = spawn(bin, ['-c', TUN_CONFIG], {
      cwd: PROJECT_ROOT,
      stdio: ['ignore', 'pipe', 'pipe'],
      detached: true,
    });
  } catch (e) {
    const msg = `无法启动 tun-adapter (${e.code || e.message})。请以管理员身份运行 Dashboard（右键 → 以管理员身份运行）。`;
    addLog('tun-adapter', `[dashboard] ${msg}`, 'stderr');
    processes['tun-adapter'].status = 'stopped';
    processes['tun-adapter'].startedAt = null;
    broadcastSSE('status', getStatusAll());
    return { ok: false, error: msg };
  }

  // Save PID
  try { fs.writeFileSync(TUN_PID_FILE, String(proc.pid), 'utf-8'); } catch {}
  processes['tun-adapter'].proc = proc;
  addLog('tun-adapter', `[dashboard] Spawned tun-adapter (PID: ${proc.pid})`);
  broadcastSSE('status', getStatusAll());

  let spawnFailed = false;
  proc.on('error', (err) => {
    spawnFailed = true;
    addLog('tun-adapter', `[dashboard] Spawn error: ${err.message}`, 'stderr');
    if (err.message.includes('EPERM') || err.message.includes('elevation') || err.code === 'EPERM') {
      addLog('tun-adapter', '[dashboard] 需要管理员权限。请以管理员身份运行 Dashboard。', 'stderr');
    }
    processes['tun-adapter'].status = 'stopped';
    processes['tun-adapter'].startedAt = null;
    processes['tun-adapter'].proc = null;
    broadcastSSE('status', getStatusAll());
  });

  bindTunProcEvents(proc, spawnFailed);

  return { ok: true, pid: proc.pid };
}

// Common event binding for tun-adapter process (stdout/stderr/close)
function bindTunProcEvents(proc, spawnFailedRef) {
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

  proc.unref();

  // Check status after a few seconds
  setTimeout(() => {
    if (spawnFailedRef) return;
    if (processes['tun-adapter'].status === 'starting') {
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
}

function stopTunAdapter() {
  addLog('tun-adapter', '[dashboard] Stopping...');

  // Try to kill by managed proc reference first
  if (processes['tun-adapter'].proc) {
    const pid = processes['tun-adapter'].proc.pid;
    if (IS_WIN) {
      try { execSync(`taskkill /F /PID ${pid} /T`, { stdio: 'ignore' }); } catch {}
    } else {
      try { process.kill(pid, 'SIGTERM'); } catch {}
    }
  }

  // Also try to kill by PID file
  try {
    const pidStr = fs.readFileSync(TUN_PID_FILE, 'utf-8').trim();
    const pid = parseInt(pidStr, 10);
    if (!isNaN(pid)) {
      if (IS_WIN) {
        try { execSync(`taskkill /F /PID ${pid} /T`, { stdio: 'ignore' }); } catch {}
      } else {
        // Process is owned by root — use sudo kill (NOPASSWD configured)
        try { process.kill(pid, 'SIGTERM'); } catch (e) {
          if (e.code === 'EPERM') {
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
    }
  } catch {}

  // Also pkill/taskkill by name as fallback
  if (IS_WIN) {
    try { execSync('taskkill /F /IM tun-adapter.exe 2>NUL', { stdio: 'ignore' }); } catch {}
  } else {
    try { execSync("pkill -f 'tun-adapter' 2>/dev/null || true", { stdio: 'ignore' }); } catch {}
    try { execSync("sudo -n pkill -f tun-adapter 2>/dev/null || true", { stdio: 'ignore', timeout: 5000 }); } catch {}
  }

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
      if (IS_WIN) {
        // On Windows, check if PID exists via tasklist
        try {
          const out = execSync(`tasklist /FI "PID eq ${pid}" /NH`, { encoding: 'utf-8', stdio: 'pipe' });
          if (out.includes(String(pid))) return true;
        } catch {}
      } else {
        try {
          process.kill(pid, 0);
          return true;
        } catch (e) {
          if (e.code === 'EPERM') return true; // Process exists but we don't own it
        }
      }
    }
  } catch {}

  // Method 2: Check if TUN interface exists
  if (IS_WIN) {
    // Check for our WinTUN adapter via PowerShell
    try {
      const out = execSync('powershell -NoProfile -NonInteractive -Command "Get-NetAdapter | Where-Object { $_.InterfaceDescription -like \'*Wintun*\' -or $_.Name -eq \'SimplePlane\' } | Select-Object -ExpandProperty Status"', { encoding: 'utf-8', stdio: 'pipe' });
      if (out.trim() === 'Up') return true;
    } catch {}
  } else {
    try {
      const ifout = execSync('ifconfig 2>/dev/null', { encoding: 'utf-8' });
      if (ifout.includes('198.18.0.1')) return true;
    } catch {}
  }

  // Method 3: Check by process name
  if (IS_WIN) {
    try {
      const out = execSync('tasklist /FI "IMAGENAME eq tun-adapter.exe" /NH', { encoding: 'utf-8', stdio: 'pipe' });
      if (out.includes('tun-adapter.exe')) return true;
    } catch {}
  } else {
    try {
      const result = execSync("pgrep -x tun-adapter", { stdio: 'pipe', encoding: 'utf-8' });
      if (result.trim()) return true;
    } catch {}
  }

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
    if (isPortListening(1080)) { result['proxy-local'].status = 'running (external)'; }
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
      if (IS_WIN) {
        cmd = 'mvn.cmd'; args = ['package', '-pl', 'proxy-local', '-am', '-DskipTests']; cwd = PROJECT_ROOT;
      } else {
        cmd = 'mvn'; args = ['package', '-pl', 'proxy-local', '-am', '-DskipTests']; cwd = PROJECT_ROOT;
      }
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
// System Proxy — cross-platform
// ============================================================
function getSystemProxy() {
  if (IS_WIN) {
    return getSystemProxyWindows();
  } else {
    return getSystemProxyMacOS();
  }
}

function setSystemProxy(enable) {
  if (IS_WIN) {
    return setSystemProxyWindows(enable);
  } else {
    return setSystemProxyMacOS(enable);
  }
}

// ---- macOS System Proxy ----
function getSystemProxyMacOS() {
  try {
    const lines = execSync('networksetup -listallnetworkservices', { encoding: 'utf-8' }).split('\n');
    const services = lines.filter(l => l.trim() && !l.includes('asterisk') && !l.startsWith('*'));
    const service = services.find(s => s.includes('Wi-Fi')) || services[0] || 'Wi-Fi';
    const socks = execSync(`networksetup -getsocksfirewallproxy "${service}"`, { encoding: 'utf-8' });
    const enabled = socks.includes('Enabled: Yes');
    return { enabled, service };
  } catch { return { enabled: false, service: 'Wi-Fi' }; }
}

function setSystemProxyMacOS(enable) {
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

// ---- Windows System Proxy ----
function getSystemProxyWindows() {
  try {
    // Read from registry: HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings
    const out = execSync('reg query "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings" /v ProxyEnable', { encoding: 'utf-8' });
    const enabled = out.includes('0x1');
    return { enabled, service: 'Internet Settings' };
  } catch { return { enabled: false, service: 'Internet Settings' }; }
}

function setSystemProxyWindows(enable) {
  try {
    if (enable) {
      // Set proxy server and enable
      execSync('reg add "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings" /v ProxyServer /t REG_SZ /d "socks=127.0.0.1:1080" /f', { stdio: 'ignore' });
      execSync('reg add "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings" /v ProxyEnable /t REG_DWORD /d 1 /f', { stdio: 'ignore' });
    } else {
      execSync('reg add "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings" /v ProxyEnable /t REG_DWORD /d 0 /f', { stdio: 'ignore' });
    }
    // Notify WinInet that settings changed (via PowerShell)
    try {
      execSync('powershell -NoProfile -NonInteractive -Command "[System.Net.WebRequest]::DefaultWebProxy = [System.Net.WebRequest]::GetSystemWebProxy()"', { stdio: 'ignore' });
    } catch {}
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
// Platform Info API (for frontend to display correct hints)
// ============================================================
function getPlatformInfo() {
  return {
    platform: process.platform,    // 'win32', 'darwin', 'linux'
    isWindows: IS_WIN,
    isMacOS: IS_MAC,
    arch: process.arch,
    nodeVersion: process.version,
  };
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

  // --- Platform Info ---
  if (pathname === '/api/platform') return json(res, 200, { ok: true, ...getPlatformInfo() });

  // --- Service Control ---
  if (pathname === '/api/status') return json(res, 200, { ok: true, services: getStatusAll(), systemProxy: getSystemProxy(), platform: getPlatformInfo() });
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
  const platformLabel = IS_WIN ? 'Windows' : IS_MAC ? 'macOS' : 'Linux';
  console.log(`\x1b[32m✓\x1b[0m SimplePlane Dashboard running at \x1b[36mhttp://localhost:${PORT}\x1b[0m (${platformLabel})`);
  console.log(`  Configs: proxy.yml | remote.yml | tun.toml`);
});

// Graceful shutdown
process.on('SIGINT', () => {
  console.log('\n[dashboard] Shutting down...');
  if (processes['proxy-local'].proc) {
    if (IS_WIN) {
      try { execSync(`taskkill /F /PID ${processes['proxy-local'].proc.pid} /T`, { stdio: 'ignore' }); } catch {}
    } else {
      processes['proxy-local'].proc.kill('SIGTERM');
    }
  }
  if (processes['tun-adapter'].proc) {
    const pid = processes['tun-adapter'].proc.pid;
    if (IS_WIN) {
      try { execSync(`taskkill /F /PID ${pid} /T`, { stdio: 'ignore' }); } catch {}
    } else {
      try { execSync(`sudo -n kill ${pid}`, { stdio: 'ignore' }); } catch {}
    }
  }
  setTimeout(() => process.exit(0), 2000);
});
