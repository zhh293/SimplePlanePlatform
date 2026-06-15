// ============================================================
// SimplePlane Dashboard — App Logic
// ============================================================
const App = (function () {
  'use strict';

  let localConfig = null;
  let remoteConfig = null;
  let originalLocalJson = '';
  let originalRemoteJson = '';
  let hasChanges = false;
  let currentSection = 'dashboard';
  let currentLogService = 'proxy-local';
  let eventSource = null;
  let platformInfo = { isWindows: false, isMacOS: true, platform: 'darwin' };

  const $ = (sel, ctx = document) => ctx.querySelector(sel);
  const $$ = (sel, ctx = document) => [...ctx.querySelectorAll(sel)];

  // ---- API ----
  async function api(path, method = 'GET', body = null) {
    const opts = { method, headers: {} };
    if (body) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
    const res = await fetch(`/api${path}`, opts);
    return res.json();
  }

  // ---- Init ----
  async function init() {
    bindNavigation();
    bindActions();
    bindGlobalKeys();
    await loadPlatformInfo();
    await checkSetup();
    await loadConfigs();
    await loadStatus();
    await loadTunConfig();
    connectSSE();
    startUptimeTimer();
  }

  // ---- One-time setup onboarding ----
  // On macOS, TUN / system-proxy / DNS-restore all need a one-time passwordless
  // sudoers rule. If it isn't configured yet, surface a prominent modal up front
  // (with the exact command + copy button) instead of letting buttons fail later.
  async function checkSetup() {
    try {
      const s = await api('/setup-status');
      if (s && s.ok && s.needsSetup) {
        showSetupModal(s);
      }
    } catch {}
  }

  function showSetupModal(s) {
    if (document.getElementById('setupModal')) return; // already shown
    const cmd = `cd ${dirOf(s.setupScript)} && ./setup-tun-permissions.sh`;
    const overlay = document.createElement('div');
    overlay.id = 'setupModal';
    overlay.className = 'setup-modal-overlay';
    overlay.innerHTML = `
      <div class="setup-modal" role="dialog" aria-modal="true">
        <div class="setup-modal-icon">🔐</div>
        <h2>首次使用：需要一次性授权</h2>
        <p>TUN 模式、系统代理开关、以及「恢复网络」都需要管理员权限。
        请在<strong>终端</strong>中运行下面这条命令完成<strong>一次性</strong>免密授权（只需运行一次，之后无需再输密码）：</p>
        <div class="setup-cmd-box">
          <code id="setupCmd">${esc(cmd)}</code>
          <button class="btn btn-sm btn-primary" id="setupCopyBtn">复制命令</button>
        </div>
        <p class="setup-modal-note">
          这条命令会写入一条 sudoers 免密规则（<code>${esc(s.sudoersFile)}</code>），
          授权 Dashboard 免密执行：启动/停止 tun-adapter、开关系统代理、还原 DNS。<br>
          如需撤销：<code>sudo rm ${esc(s.sudoersFile)}</code>
        </p>
        <div class="setup-modal-actions">
          <button class="btn btn-ghost" id="setupRecheckBtn">我已运行，重新检测</button>
          <button class="btn btn-ghost" id="setupLaterBtn">稍后再说</button>
        </div>
      </div>`;
    document.body.appendChild(overlay);

    $('#setupCopyBtn', overlay).addEventListener('click', () => {
      copyText(cmd);
      $('#setupCopyBtn', overlay).textContent = '已复制 ✓';
      setTimeout(() => { const b = $('#setupCopyBtn', overlay); if (b) b.textContent = '复制命令'; }, 1500);
    });
    $('#setupRecheckBtn', overlay).addEventListener('click', async () => {
      const s2 = await api('/setup-status');
      if (s2 && s2.ok && !s2.needsSetup) {
        overlay.remove();
        toast('授权已配置完成 ✓', 'success');
      } else {
        toast('仍未检测到授权，请确认命令已在终端成功执行', 'warning');
      }
    });
    $('#setupLaterBtn', overlay).addEventListener('click', () => overlay.remove());
  }

  function dirOf(p) { return String(p || '').replace(/\/[^/]*$/, ''); }
  function copyText(t) {
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) { navigator.clipboard.writeText(t); return; }
    } catch {}
    const ta = document.createElement('textarea');
    ta.value = t; document.body.appendChild(ta); ta.select();
    try { document.execCommand('copy'); } catch {}
    ta.remove();
  }

  // ---- Platform ----
  async function loadPlatformInfo() {
    try {
      const res = await api('/platform');
      if (res.ok) {
        platformInfo = res;
        updatePlatformHints();
      }
    } catch {}
  }

  function updatePlatformHints() {
    // Update TUN permission hint based on platform
    const hint = $('#tunPermissionHint');
    if (hint) {
      if (platformInfo.isWindows) {
        hint.innerHTML = '<strong>Windows:</strong> Dashboard 需要以管理员身份运行才能启动 TUN 模式（右键 CMD/PowerShell → 以管理员身份运行 → <code>node server.js</code>）';
      } else {
        hint.innerHTML = '<strong>macOS:</strong> 首次使用 TUN 需运行 <code>./setup-tun-permissions.sh</code> 配置免密权限，且 Dashboard 需从真实终端启动';
      }
    }
    // Update TUN device name
    const devName = $('#tun-device-name');
    if (devName) {
      devName.textContent = platformInfo.isWindows ? '设备: SimplePlane' : '设备: utun9';
    }
  }

  // ---- Navigation ----
  function bindNavigation() {
    $$('.nav-item').forEach(item => {
      item.addEventListener('click', (e) => { e.preventDefault(); switchSection(item.dataset.section); });
    });
  }

  function switchSection(name) {
    currentSection = name;
    $$('.nav-item').forEach(n => n.classList.toggle('active', n.dataset.section === name));
    $$('.section').forEach(s => { s.hidden = s.dataset.section !== name; });
    const titles = { dashboard: '控制面板', 'proxy-config': '代理配置', 'tun-config': 'TUN 模式', route: '路由规则', logs: '运行日志' };
    $('#topbarTitle').textContent = titles[name] || name;
    $('#sidebar').classList.remove('open');
    if (name === 'logs') loadLogs(currentLogService);
  }

  // ---- Status ----
  async function loadStatus() {
    const res = await api('/status');
    if (res.ok) {
      updateServiceUI('proxy-local', res.services['proxy-local']);
      updateServiceUI('tun-adapter', res.services['tun-adapter']);
      if (res.systemProxy) {
        $('#systemProxyToggle').checked = res.systemProxy.enabled;
        $('#systemProxyStatus').textContent = res.systemProxy.enabled ? '已开启' : '已关闭';
      }
    }
  }

  function updateServiceUI(name, info) {
    if (!info) return;
    const statusEl = $(`#status-${name}`);
    const indicator = $('.status-indicator', statusEl);
    const label = $('.status-label', statusEl);
    const card = $(`#card-${name}`);

    indicator.className = 'status-indicator ' + (info.status.includes('running') ? 'running' : info.status === 'starting' ? 'starting' : 'stopped');
    const labels = { running: '运行中', 'running (external)': '运行中(外部)', starting: '启动中', stopped: '已停止' };
    label.textContent = labels[info.status] || info.status;
    card.className = 'service-card ' + (info.status.includes('running') ? 'card-running' : info.status === 'starting' ? 'card-starting' : '');

    const uptimeEl = $(`#uptime-${name}`);
    if (uptimeEl) uptimeEl.textContent = info.uptime > 0 ? `运行: ${formatUptime(info.uptime)}` : '';
  }

  function formatUptime(ms) {
    const s = Math.floor(ms / 1000);
    if (s < 60) return `${s}s`;
    if (s < 3600) return `${Math.floor(s / 60)}m ${s % 60}s`;
    return `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`;
  }

  function startUptimeTimer() {
    setInterval(async () => { await loadStatus(); }, 10000);
  }

  // ---- Service Control ----
  async function startService(name) {
    toast(`正在启动 ${name}...`, 'info');
    const res = await api('/service/start', 'POST', { name });
    if (res.ok) {
      const msg = res.message || `${name} 启动指令已发送`;
      toast(msg, 'success');
    } else {
      // Show platform-specific hint for TUN permission errors
      if (name === 'tun-adapter' && res.error && (res.error.includes('管理员') || res.error.includes('sudo') || res.error.includes('EPERM'))) {
        if (platformInfo.isWindows) {
          toast('需要管理员权限。请以管理员身份重新启动 Dashboard。', 'error');
        } else {
          toast('权限不足。请运行 setup-tun-permissions.sh 或在真实终端中启动 Dashboard。', 'error');
        }
      } else {
        toast(`启动失败: ${res.error}`, 'error');
      }
    }
    await loadStatus();
    setTimeout(loadStatus, 3000);
  }

  function showTerminalHint(cmd) {
    // Show a modal/overlay with the command to copy
    let overlay = $('#terminalHintOverlay');
    if (!overlay) {
      overlay = document.createElement('div');
      overlay.id = 'terminalHintOverlay';
      const title = platformInfo.isWindows ? '需要以管理员身份运行' : '需要在终端中运行';
      const desc = platformInfo.isWindows
        ? '由于 Windows 安全限制，TUN 模式需要管理员权限。请以管理员身份打开 CMD 或 PowerShell 运行以下命令：'
        : '由于 macOS 安全限制，TUN 模式需要 root 权限，请在真实终端中运行以下命令：';
      overlay.innerHTML = `
        <div class="terminal-hint-modal">
          <h3>${title}</h3>
          <p>${desc}</p>
          <div class="terminal-hint-cmd"><code id="terminalHintCmd"></code></div>
          <div class="terminal-hint-actions">
            <button class="btn btn-primary" id="btnCopyCmd">复制命令</button>
            <button class="btn" id="btnCloseHint">关闭</button>
          </div>
          <p class="terminal-hint-note">启动后 Dashboard 会自动检测到运行状态</p>
        </div>`;
      document.body.appendChild(overlay);
      $('#btnCloseHint').addEventListener('click', () => overlay.hidden = true);
      $('#btnCopyCmd').addEventListener('click', () => {
        navigator.clipboard.writeText($('#terminalHintCmd').textContent);
        toast('命令已复制到剪贴板', 'success');
      });
      overlay.addEventListener('click', (e) => { if (e.target === overlay) overlay.hidden = true; });
    }
    $('#terminalHintCmd').textContent = cmd;
    overlay.hidden = false;
  }

  async function stopService(name) {
    toast(`正在停止 ${name}...`, 'info');
    const res = await api('/service/stop', 'POST', { name });
    if (res.ok) toast(`${name} 已停止`, 'success');
    else toast(`停止失败: ${res.error}`, 'error');
    setTimeout(loadStatus, 2000);
  }

  async function restartService(name) {
    toast(`正在重启 ${name}...`, 'info');
    const res = await api('/service/restart', 'POST', { name });
    if (res.ok) toast(`${name} 重启中`, 'success');
    else toast(`重启失败: ${res.error}`, 'error');
    setTimeout(loadStatus, 4000);
  }

  async function buildService(name) {
    toast(`正在编译 ${name}（可能需要几十秒）...`, 'info');
    const res = await api('/service/build', 'POST', { name });
    if (res.ok) toast(`${name} 编译成功`, 'success');
    else toast(`编译失败: ${res.error}`, 'error');
  }

  // ---- Quick Actions ----
  async function quickStartProxy() {
    toast('一键代理模式: 启动 proxy-local + 开启系统代理...', 'info');
    await api('/service/start', 'POST', { name: 'proxy-local' });
    await new Promise(r => setTimeout(r, 3000));
    await api('/system-proxy', 'POST', { enabled: true });
    await loadStatus();
    toast('代理模式已激活', 'success');
  }

  async function quickStartTun() {
    toast('一键 TUN 模式: 启动 proxy-local + tun-adapter...', 'info');
    const r1 = await api('/service/start', 'POST', { name: 'proxy-local' });
    if (!r1.ok && !r1.error?.includes('Already')) { toast('proxy-local 启动失败: ' + (r1.error || ''), 'error'); return; }
    await new Promise(r => setTimeout(r, 3000));
    const r2 = await api('/service/start', 'POST', { name: 'tun-adapter' });
    if (!r2.ok) {
      toast('tun-adapter 启动失败: ' + (r2.error || ''), 'error');
      await loadStatus(); return;
    }
    // r2.ok only means "launch was initiated" — the tun-adapter binary may still
    // crash immediately (e.g. failed to create its log file, missing privileges).
    // So we must verify the REAL state by polling /api/status, not blindly claim success.
    if (r2.message && r2.message.includes('Authorization dialog')) {
      toast('系统授权对话框已弹出，请输入密码以启动 TUN', 'info');
    }
    toast('正在确认 TUN 是否真正启动...', 'info');
    const running = await waitForServiceRunning('tun-adapter', 12000);
    await loadStatus();
    if (running) {
      toast('TUN 模式已激活', 'success');
    } else {
      const reason = await fetchServiceFailReason('tun-adapter');
      toast('TUN 启动失败：tun-adapter 未能运行。' + (reason ? '原因: ' + reason : '请查看运行日志。'), 'error');
    }
  }

  // Poll /api/status until the given service reports a running state, or timeout.
  async function waitForServiceRunning(name, timeoutMs) {
    const deadline = Date.now() + (timeoutMs || 10000);
    while (Date.now() < deadline) {
      try {
        const res = await api('/status');
        const st = res && res.ok && res.services[name] && res.services[name].status;
        if (st && st.includes('running')) return true;
      } catch {}
      await new Promise(r => setTimeout(r, 1000));
    }
    return false;
  }

  // Pull the most relevant error line from a service's recent logs, so failures
  // surface the real cause (e.g. a Rust panic) instead of a vague message.
  async function fetchServiceFailReason(name) {
    try {
      const res = await api(`/logs?service=${name}`);
      if (!res || !res.ok || !Array.isArray(res.logs)) return '';
      const lines = res.logs.map(e => e.text).filter(Boolean);
      // Prefer panic / permission / error lines, newest first.
      const key = lines.reverse().find(t => /panic|permission denied|os \{ code|error|failed|sudo/i.test(t));
      const pick = key || lines[0] || '';
      return pick.replace(/\s+/g, ' ').trim().slice(0, 200);
    } catch { return ''; }
  }

  async function quickStopAll() {
    toast('停止所有服务...', 'info');
    await api('/system-proxy', 'POST', { enabled: false });
    await api('/service/stop', 'POST', { name: 'tun-adapter' });
    await api('/service/stop', 'POST', { name: 'proxy-local' });
    await new Promise(r => setTimeout(r, 2000));
    await loadStatus();
    toast('所有服务已停止', 'success');
  }

  // Emergency recovery: force-restore DNS/routes and turn the system proxy off.
  // For use when something was force-killed / the dashboard exited abnormally
  // and the machine is left without working network.
  async function resetNetwork() {
    if (!confirm('恢复网络？将强制还原 DNS/路由并关闭系统代理，用于异常退出后无法上网的情况。')) return;
    toast('正在恢复网络...', 'info');
    const res = await api('/reset-network', 'POST', {});
    if (res.ok) {
      await loadStatus();
      toast('网络已恢复' + (res.steps ? '：' + res.steps.join('；') : ''), 'success');
    } else {
      toast('恢复失败: ' + (res.error || ''), 'error');
    }
  }

  async function toggleSystemProxy(enabled) {
    const res = await api('/system-proxy', 'POST', { enabled });
    if (res.ok) {
      $('#systemProxyStatus').textContent = enabled ? '已开启' : '已关闭';
      toast(enabled ? '系统代理已开启' : '系统代理已关闭', 'success');
    } else {
      toast('操作失败: ' + (res.error || ''), 'error');
      $('#systemProxyToggle').checked = !enabled;
    }
  }

  // ---- Config Loading ----
  async function loadConfigs() {
    const [localRes, remoteRes] = await Promise.all([api('/config/local'), api('/config/remote')]);
    if (localRes.ok) { localConfig = localRes.data; originalLocalJson = JSON.stringify(localConfig); populateLocalForm(); }
    else toast('加载 proxy.yml 失败', 'error');
    if (remoteRes.ok) { remoteConfig = remoteRes.data; originalRemoteJson = JSON.stringify(remoteConfig); }
    else toast('加载 remote.yml 失败', 'error');
  }

  function populateLocalForm() {
    const c = localConfig; if (!c) return;
    setVal('#localPort', c.localPort);
    setVal('#cluster', c.cluster);
    setVal('#loadBalance', c.loadBalance);
    setVal('#timeoutMs', c.timeoutMs);
    setVal('#connectionsPerNode', c.connectionsPerNode);
    setChecked('#httpProxyEnabled', c.httpProxyEnabled);
    renderServerCards(c.remoteServers || []);
    if (c.route) {
      setVal('#defaultRoute', c.route.defaultRoute);
      $('#proxyList').value = (c.route.proxyList || []).join('\n');
      $('#directList').value = (c.route.directList || []).join('\n');
      updateRouteCounts();
    }
  }

  // ---- Server Cards ----
  function renderServerCards(servers) {
    const container = $('#remoteServers'); container.innerHTML = '';
    servers.forEach((srv, i) => container.appendChild(createServerCard(srv, i)));
  }

  function createServerCard(srv, index) {
    const card = document.createElement('div');
    card.className = 'server-card';
    card.innerHTML = `
      <div class="server-card-header">
        <span class="server-card-title"><span class="server-index">#${index + 1}</span> ${esc(srv.host || '?')}:${srv.port || '?'}</span>
        <button class="btn btn-danger btn-sm btn-remove" title="删除">×</button>
      </div>
      <div class="server-fields">
        <div class="server-field"><label>Host</label><input class="input" value="${escAttr(srv.host || '')}" data-key="host"></div>
        <div class="server-field"><label>Port</label><input class="input" type="number" value="${srv.port || 9090}" data-key="port"></div>
        <div class="server-field"><label>Cipher</label>
          <select class="select" data-key="cipher">
            <option value="none" ${srv.cipher === 'none' ? 'selected' : ''}>none</option>
            <option value="aes-gcm" ${srv.cipher === 'aes-gcm' ? 'selected' : ''}>aes-gcm</option>
            <option value="chacha20" ${srv.cipher === 'chacha20' ? 'selected' : ''}>chacha20</option>
            <option value="aes-ctr-hmac" ${srv.cipher === 'aes-ctr-hmac' ? 'selected' : ''}>aes-ctr-hmac</option>
          </select>
        </div>
        <div class="server-field"><label>Key</label><input class="input" value="${escAttr(srv.cipherKey || '')}" data-key="cipherKey"></div>
        <div class="server-field"><label>SSL</label>
          <label class="toggle"><input type="checkbox" ${srv.ssl ? 'checked' : ''} data-key="ssl"><span class="toggle-track"><span class="toggle-thumb"></span></span></label>
        </div>
      </div>`;
    $$('input, select', card).forEach(el => { el.addEventListener('input', markChanged); el.addEventListener('change', markChanged); });
    $('.btn-remove', card).addEventListener('click', () => { card.remove(); collectAndMark(); });
    return card;
  }

  // ---- TUN Config ----
  async function loadTunConfig() {
    const res = await api('/config/tun');
    if (res.ok) $('#tunConfigEditor').value = res.raw;
    else $('#tunConfigEditor').value = '# Failed to load tun.toml: ' + (res.error || '');
  }

  async function saveTunConfig() {
    const text = $('#tunConfigEditor').value;
    const res = await api('/config/tun', 'POST', { raw: text });
    if (res.ok) toast('tun.toml 已保存', 'success');
    else toast('保存失败: ' + (res.error || ''), 'error');
  }

  // ---- Logs ----
  async function loadLogs(service) {
    currentLogService = service;
    const res = await api(`/logs?service=${service}`);
    if (res.ok) {
      const container = $('#logContent');
      container.innerHTML = '';
      res.logs.forEach(entry => appendLogLine(entry));
      autoScrollLog();
    }
  }

  function appendLogLine(entry) {
    const container = $('#logContent');
    const line = document.createElement('div');
    line.className = 'log-line ' + (entry.stream === 'stderr' ? 'log-stderr' : '');
    const time = new Date(entry.time).toLocaleTimeString();
    line.innerHTML = `<span class="log-time">${time}</span><span class="log-text">${esc(entry.text)}</span>`;
    container.appendChild(line);
  }

  function autoScrollLog() {
    if ($('#logAutoScroll').checked) {
      const term = $('#logTerminal');
      term.scrollTop = term.scrollHeight;
    }
  }

  function switchLogService(service) { loadLogs(service); }
  function clearLogs() { $('#logContent').innerHTML = ''; }

  // ---- SSE ----
  function connectSSE() {
    if (eventSource) eventSource.close();
    eventSource = new EventSource('/api/events');
    eventSource.addEventListener('connected', () => {
      $('#statusBadge').classList.remove('disconnected');
      $('.status-text', $('#statusBadge')).textContent = 'Connected';
    });
    eventSource.addEventListener('status', (e) => {
      try {
        const data = JSON.parse(e.data);
        updateServiceUI('proxy-local', data['proxy-local']);
        updateServiceUI('tun-adapter', data['tun-adapter']);
      } catch {}
    });
    eventSource.addEventListener('log', (e) => {
      try {
        const entry = JSON.parse(e.data);
        if (entry.service === currentLogService && currentSection === 'logs') {
          appendLogLine(entry);
          autoScrollLog();
        }
      } catch {}
    });
    eventSource.onerror = () => {
      $('#statusBadge').classList.add('disconnected');
      $('.status-text', $('#statusBadge')).textContent = 'Disconnected';
    };
  }

  // ---- Save All ----
  async function saveAll() {
    collectLocalConfig();
    const res = await api('/config/local', 'POST', { data: localConfig });
    if (res.ok) {
      originalLocalJson = JSON.stringify(localConfig);
      updateChangeState();
      toast('配置已保存', 'success');
    } else toast('保存失败: ' + (res.error || ''), 'error');
  }

  function collectLocalConfig() {
    if (!localConfig) return;
    localConfig.localPort = intVal('#localPort', 1080);
    localConfig.httpProxyEnabled = $('#httpProxyEnabled').checked;
    localConfig.cluster = val('#cluster');
    localConfig.loadBalance = val('#loadBalance');
    localConfig.timeoutMs = intVal('#timeoutMs', 30000);
    localConfig.connectionsPerNode = intVal('#connectionsPerNode', 1);
    if (!localConfig.route) localConfig.route = {};
    localConfig.route.defaultRoute = val('#defaultRoute');
    localConfig.route.proxyList = parseLines('#proxyList');
    localConfig.route.directList = parseLines('#directList');
    collectServers();
  }

  function collectServers() {
    const servers = [];
    $$('.server-card', $('#remoteServers')).forEach(card => {
      servers.push({
        host: val($('[data-key="host"]', card)),
        port: parseInt(val($('[data-key="port"]', card))) || 9090,
        cipher: val($('[data-key="cipher"]', card)),
        cipherKey: val($('[data-key="cipherKey"]', card)),
        ssl: $('[data-key="ssl"]', card).checked,
      });
    });
    if (localConfig) localConfig.remoteServers = servers;
  }

  function collectAndMark() { collectServers(); markChanged(); }

  // ---- Change Detection ----
  function markChanged() {
    collectLocalConfig();
    updateChangeState();
  }

  function updateChangeState() {
    hasChanges = JSON.stringify(localConfig) !== originalLocalJson;
    $('#btnSave').disabled = !hasChanges;
    $('#changeIndicator').hidden = !hasChanges;
  }

  function updateRouteCounts() {
    const p = parseLines('#proxyList').length, d = parseLines('#directList').length;
    $('#proxyListCount').textContent = `${p} 条`;
    $('#directListCount').textContent = `${d} 条`;
  }

  // ---- Bindings ----
  function bindActions() {
    $('#btnSave').addEventListener('click', saveAll);
    $('#btnAddServer').addEventListener('click', () => {
      const idx = $$('.server-card', $('#remoteServers')).length;
      $('#remoteServers').appendChild(createServerCard({ host: '', port: 9090, cipher: 'none', cipherKey: '', ssl: false }, idx));
      markChanged();
    });
    $('#sidebarToggle').addEventListener('click', () => $('#sidebar').classList.toggle('open'));
    ['#proxyList', '#directList'].forEach(sel => { $(sel).addEventListener('input', () => { updateRouteCounts(); markChanged(); }); });
    document.addEventListener('input', (e) => { if (e.target.closest('.section[data-section="proxy-config"]') || e.target.closest('.section[data-section="route"]')) markChanged(); });
    document.addEventListener('change', (e) => { if (e.target.closest('.section[data-section="proxy-config"]') || e.target.closest('.section[data-section="route"]')) markChanged(); });
  }

  function bindGlobalKeys() {
    document.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 's') { e.preventDefault(); if (hasChanges) saveAll(); }
    });
  }

  // ---- Toast ----
  function toast(message, type = 'info') {
    const container = $('#toastContainer');
    const el = document.createElement('div');
    el.className = `toast ${type}`;
    el.textContent = message;
    container.appendChild(el);
    setTimeout(() => { el.classList.add('leaving'); setTimeout(() => el.remove(), 200); }, 4000);
  }

  // ---- Helpers ----
  function val(sel) { const el = typeof sel === 'string' ? $(sel) : sel; return el ? el.value : ''; }
  function intVal(sel, fb) { const v = parseInt(val(sel), 10); return isNaN(v) ? fb : v; }
  function setVal(sel, v) { const el = $(sel); if (el) el.value = v ?? ''; }
  function setChecked(sel, v) { const el = $(sel); if (el) el.checked = !!v; }
  function esc(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
  function escAttr(s) { return String(s).replace(/"/g, '&quot;'); }
  function parseLines(sel) { return val(sel).split('\n').map(s => s.trim()).filter(s => s && !s.startsWith('#')); }

  document.addEventListener('DOMContentLoaded', init);

  // Public API (for inline onclick handlers)
  return {
    startService, stopService, restartService, buildService,
    quickStartProxy, quickStartTun, quickStopAll, resetNetwork, toggleSystemProxy,
    loadTunConfig, saveTunConfig, switchLogService, clearLogs,
  };
})();
