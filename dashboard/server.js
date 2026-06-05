const http = require('http');
const fs = require('fs');
const path = require('path');
const { parseYaml, toYaml } = require('./yaml');

const PORT = process.env.DASHBOARD_PORT || 3000;
const PROJECT_ROOT = path.resolve(__dirname, '..');
const LOCAL_CONFIG = path.join(PROJECT_ROOT, 'proxy-local', 'src', 'main', 'resources', 'proxy.yml');
const REMOTE_CONFIG = path.join(PROJECT_ROOT, 'proxy-remote', 'src', 'main', 'resources', 'remote.yml');
const PUBLIC_DIR = path.join(__dirname, 'public');
const PRESETS_DIR = path.join(__dirname, 'presets');

// Ensure presets directory exists
if (!fs.existsSync(PRESETS_DIR)) fs.mkdirSync(PRESETS_DIR, { recursive: true });

// MIME types for static files
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
};

// Track config state for change detection
let localConfigMtime = 0;
let remoteConfigMtime = 0;

function readConfig(filePath) {
  const text = fs.readFileSync(filePath, 'utf-8');
  const mtime = fs.statSync(filePath).mtimeMs;
  return { data: parseYaml(text), raw: text, mtime };
}

function getConfig(reqType) {
  const filePath = reqType === 'remote' ? REMOTE_CONFIG : LOCAL_CONFIG;
  try {
    const { data, raw, mtime } = readConfig(filePath);
    return { ok: true, data, raw, mtime };
  } catch (e) {
    return { ok: false, error: e.message };
  }
}

function saveConfig(reqType, newData) {
  const filePath = reqType === 'remote' ? REMOTE_CONFIG : LOCAL_CONFIG;
  try {
    const yamlText = toYaml(newData);
    fs.writeFileSync(filePath, yamlText + '\n', 'utf-8');
    return { ok: true, saved: yamlText };
  } catch (e) {
    return { ok: false, error: e.message };
  }
}

function checkChanges() {
  let changed = { local: false, remote: false };
  try {
    const lm = fs.statSync(LOCAL_CONFIG).mtimeMs;
    if (lm !== localConfigMtime) { changed.local = true; localConfigMtime = lm; }
  } catch {}
  try {
    const rm = fs.statSync(REMOTE_CONFIG).mtimeMs;
    if (rm !== remoteConfigMtime) { changed.remote = true; remoteConfigMtime = rm; }
  } catch {}
  return changed;
}

// Parse JSON body
function parseBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', chunk => { body += chunk; if (body.length > 1e6) req.destroy(); });
    req.on('end', () => {
      try { resolve(JSON.parse(body)); }
      catch { resolve(null); }
    });
    req.on('error', reject);
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
  const ext = path.extname(filePath);

  // Prevent directory traversal
  if (!filePath.startsWith(PUBLIC_DIR)) return json(res, 403, { error: 'forbidden' });

  fs.readFile(filePath, (err, data) => {
    if (err) {
      if (err.code === 'ENOENT') return json(res, 404, { error: 'not found' });
      return json(res, 500, { error: err.message });
    }
    res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
    res.end(data);
  });
}

// SSE connections for live updates
const sseClients = new Set();

function broadcastSSE(event, data) {
  const msg = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const client of sseClients) {
    try { client.write(msg); } catch { sseClients.delete(client); }
  }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const pathname = url.pathname;

  // CORS headers (same-origin, not needed, but safe)
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }

  // API routes
  if (pathname === '/api/config/local' && req.method === 'GET') {
    const cfg = getConfig('local');
    return cfg.ok ? json(res, 200, cfg) : json(res, 500, cfg);
  }
  if (pathname === '/api/config/remote' && req.method === 'GET') {
    const cfg = getConfig('remote');
    return cfg.ok ? json(res, 200, cfg) : json(res, 500, cfg);
  }
  if (pathname === '/api/config/local' && req.method === 'POST') {
    const body = await parseBody(req);
    if (!body || !body.data) return json(res, 400, { error: 'missing data' });
    const result = saveConfig('local', body.data);
    if (result.ok) {
      const cfg = getConfig('local');
      localConfigMtime = cfg.mtime;
      broadcastSSE('config-changed', { type: 'local', data: cfg.data });
    }
    return json(res, result.ok ? 200 : 500, result);
  }
  if (pathname === '/api/config/remote' && req.method === 'POST') {
    const body = await parseBody(req);
    if (!body || !body.data) return json(res, 400, { error: 'missing data' });
    const result = saveConfig('remote', body.data);
    if (result.ok) {
      const cfg = getConfig('remote');
      remoteConfigMtime = cfg.mtime;
      broadcastSSE('config-changed', { type: 'remote', data: cfg.data });
    }
    return json(res, result.ok ? 200 : 500, result);
  }
  if (pathname === '/api/status' && req.method === 'GET') {
    const changes = checkChanges();
    return json(res, 200, { ok: true, changes, timestamp: Date.now() });
  }
  if (pathname === '/api/validate' && req.method === 'POST') {
    const body = await parseBody(req);
    if (!body) return json(res, 400, { error: 'invalid body' });
    const errors = validateConfig(body.type, body.data);
    return json(res, 200, { ok: true, errors });
  }

  // Preset API
  if (pathname === '/api/presets' && req.method === 'GET') {
    try {
      const files = fs.readdirSync(PRESETS_DIR).filter(f => f.endsWith('.yml') || f.endsWith('.yaml'));
      const presets = files.map(f => {
        const name = f.replace(/\.(yml|yaml)$/, '');
        const text = fs.readFileSync(path.join(PRESETS_DIR, f), 'utf-8');
        const data = parseYaml(text);
        return { name, host: data.host, port: data.port, cipher: data.cipher };
      });
      return json(res, 200, { ok: true, presets });
    } catch (e) {
      return json(res, 500, { ok: false, error: e.message });
    }
  }

  if (pathname === '/api/presets/load' && req.method === 'POST') {
    const body = await parseBody(req);
    if (!body || !body.name) return json(res, 400, { error: 'missing preset name' });
    const safeName = body.name.replace(/[^a-zA-Z0-9_-]/g, '');
    const filePath = path.join(PRESETS_DIR, safeName + '.yml');
    try {
      const text = fs.readFileSync(filePath, 'utf-8');
      const data = parseYaml(text);
      return json(res, 200, { ok: true, data });
    } catch (e) {
      return json(res, 404, { ok: false, error: 'Preset not found' });
    }
  }

  if (pathname === '/api/presets/save' && req.method === 'POST') {
    const body = await parseBody(req);
    if (!body || !body.name || !body.data) return json(res, 400, { error: 'missing name or data' });
    const safeName = body.name.replace(/[^a-zA-Z0-9_-]/g, '');
    if (!safeName) return json(res, 400, { error: 'invalid preset name' });
    const filePath = path.join(PRESETS_DIR, safeName + '.yml');
    try {
      const yamlText = toYaml(body.data);
      fs.writeFileSync(filePath, yamlText + '\n', 'utf-8');
      return json(res, 200, { ok: true, name: safeName });
    } catch (e) {
      return json(res, 500, { ok: false, error: e.message });
    }
  }

  if (pathname === '/api/presets/delete' && req.method === 'POST') {
    const body = await parseBody(req);
    if (!body || !body.name) return json(res, 400, { error: 'missing preset name' });
    const safeName = body.name.replace(/[^a-zA-Z0-9_-]/g, '');
    const filePath = path.join(PRESETS_DIR, safeName + '.yml');
    try {
      fs.unlinkSync(filePath);
      return json(res, 200, { ok: true });
    } catch (e) {
      return json(res, 404, { ok: false, error: 'Preset not found' });
    }
  }

  // YAML import/parse endpoint
  if (pathname === '/api/yaml/parse' && req.method === 'POST') {
    const body = await parseBody(req);
    if (!body || !body.yaml) return json(res, 400, { error: 'missing yaml text' });
    try {
      const data = parseYaml(body.yaml);
      return json(res, 200, { ok: true, data });
    } catch (e) {
      return json(res, 400, { ok: false, error: 'YAML parse error: ' + e.message });
    }
  }

  // SSE endpoint
  if (pathname === '/api/events') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
    });
    res.write('event: connected\ndata: {}\n\n');
    sseClients.add(res);
    req.on('close', () => sseClients.delete(res));
    return;
  }

  // Static files
  serveStatic(req, res);
});

function validateConfig(type, data) {
  const errors = [];
  if (type === 'local') {
    if (!data.localPort || data.localPort < 1 || data.localPort > 65535) errors.push('localPort must be 1-65535');
    if (!data.remoteServers || !Array.isArray(data.remoteServers) || data.remoteServers.length === 0) {
      errors.push('At least one remoteServer is required');
    } else {
      data.remoteServers.forEach((s, i) => {
        if (!s.host) errors.push(`remoteServers[${i}].host is required`);
        if (!s.port || s.port < 1 || s.port > 65535) errors.push(`remoteServers[${i}].port must be 1-65535`);
      });
    }
    const validClusters = ['failover', 'failfast', 'forking', 'failback'];
    if (data.cluster && !validClusters.includes(data.cluster)) errors.push(`cluster must be one of: ${validClusters.join(', ')}`);
    const validLB = ['roundrobin', 'random', 'leastactive', 'consistenthash'];
    if (data.loadBalance && !validLB.includes(data.loadBalance)) errors.push(`loadBalance must be one of: ${validLB.join(', ')}`);
    if (data.route) {
      const validRoutes = ['proxy', 'direct'];
      if (data.route.defaultRoute && !validRoutes.includes(data.route.defaultRoute)) errors.push(`route.defaultRoute must be proxy or direct`);
    }
  }
  if (type === 'remote') {
    if (!data.port || data.port < 1 || data.port > 65535) errors.push('port must be 1-65535');
    if (data.bizThreads < 1) errors.push('bizThreads must be >= 1');
    if (data.maxStreams < 1) errors.push('maxStreams must be >= 1');
  }
  return errors;
}

// Initialize mtime tracking
try { localConfigMtime = fs.statSync(LOCAL_CONFIG).mtimeMs; } catch {}
try { remoteConfigMtime = fs.statSync(REMOTE_CONFIG).mtimeMs; } catch {}

// Poll for external file changes every 2s
setInterval(() => {
  const changes = checkChanges();
  if (changes.local) {
    const cfg = getConfig('local');
    if (cfg.ok) broadcastSSE('config-changed', { type: 'local', data: cfg.data });
  }
  if (changes.remote) {
    const cfg = getConfig('remote');
    if (cfg.ok) broadcastSSE('config-changed', { type: 'remote', data: cfg.data });
  }
}, 2000);

server.listen(PORT, () => {
  console.log(`\x1b[32m✓\x1b[0m Dashboard server running at \x1b[36mhttp://localhost:${PORT}\x1b[0m`);
  console.log(`  Local config:  ${LOCAL_CONFIG}`);
  console.log(`  Remote config: ${REMOTE_CONFIG}`);
});
