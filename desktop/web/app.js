/**
 * SimplePlane Desktop — Full Frontend Application
 * 通过 Tauri IPC (window.__TAURI__.core.invoke) 与 Rust 后端通信
 */
const App = (function () {
  'use strict';

  const { invoke } = window.__TAURI__.core;

  let localConfig = null;
  let originalConfigJson = '';
  let hasChanges = false;
  let currentSection = 'dashboard';
  let currentLogService = 'proxy-local';
  let statusInterval = null;
  let logs = { 'proxy-local': [], 'tun-adapter': [], 'system': [] };
  let presets = [];

  const $ = (sel, ctx = document) => ctx.querySelector(sel);
  const $$ = (sel, ctx = document) => [...ctx.querySelectorAll(sel)];

  // ============================================================
  // Init
  // ============================================================
  async function init() {
    bindNavigation();
    bindActions();
    bindGlobalKeys();
    await loadConfig();
    await loadTunConfig();
    await loadPresets();
    startStatusPolling();
    addLog('info', '应用已启动，等待连接...');
    setTimeout(checkForUpdates, 3000);
  }

  // ============================================================
  // Navigation
  // ============================================================
  function bindNavigation() {
    $$('.nav-item').forEach(item => {
      item.addEventListener('click', () => {
        const section = item.dataset.section;
        switchSection(section);
      });
    });
  }

  function switchSection(name) {
    currentSection = name;
    $$('.nav-item').forEach(n => n.classList.toggle('active', n.dataset.section === name));
    $$('.section').forEach(s => {
      s.classList.toggle('active', s.dataset.section === name);
    });
    // 切换到日志页时自动加载
    if (name === 'logs') refreshLogView();
  }

  // ============================================================
  // Status Polling
  // ============================================================
  function startStatusPolling() {
    pollStatus();
    statusInterval = setInterval(pollStatus, 3000);
  }

  async function pollStatus() {
    try {
      const st = await invoke('status');
      updateServiceUI('proxy-local', st.proxy_status, st.proxy_port_listening);
      updateServiceUI('tun-adapter', st.tun_status, false);
      $('#info-proxy-port').textContent = st.proxy_port;
      $('#info-http-port').textContent = st.http_port;
      $('#info-mode').textContent = st.proxy_mode === 'tun' ? 'TUN 模式' : '系统代理';
      $('#info-port-status').textContent = st.proxy_port_listening ? '监听中' : '未监听';

      // 系统代理状态
      const proxyRunning = st.proxy_status === 'running';
      $('#systemProxyToggle').checked = proxyRunning && st.proxy_mode === 'system';
      $('#systemProxyStatus').textContent = proxyRunning ? '已开启' : '已关闭';
    } catch (e) {
      // 静默
    }
  }

  function updateServiceUI(name, status, portListening) {
    const statusEl = $(`#status-${name}`);
    if (!statusEl) return;
    const indicator = $('.status-indicator', statusEl);
    const label = $('.status-label', statusEl);
    const card = $(`#card-${name}`);

    indicator.className = 'status-indicator ' + (status === 'running' ? 'running' : status === 'starting' ? 'starting' : 'stopped');
    const labels = { running: '运行中', starting: '启动中', stopped: '已停止', stopping: '停止中', error: '异常' };
    label.textContent = labels[status] || status;
    card.className = 'service-card ' + (status === 'running' ? 'card-running' : status === 'starting' ? 'card-starting' : status === 'error' ? 'card-error' : '');
  }

  // ============================================================
  // Service Control
  // ============================================================
  async function startService(name) {
    toast(`正在启动 ${name}...`, 'info');
    addLog('info', `启动 ${name}...`);
    try {
      if (name === 'proxy-local') {
        await invoke('connect', { mode: 'system' });
        toast(`${name} 已启动`, 'success');
        addLog('success', `${name} 已启动`);
      } else if (name === 'tun-adapter') {
        const result = await invoke('connect', { mode: 'tun' });
        if (result && result !== 'connected') {
          toast(result, 'warning');
          addLog('warning', result);
        } else {
          toast(`${name} 已启动`, 'success');
          addLog('success', `${name} 已启动`);
        }
      }
    } catch (err) {
      toast(`启动失败: ${err}`, 'error');
      addLog('error', `${name} 启动失败: ${err}`);
    }
    await pollStatus();
  }

  async function stopService(name) {
    toast(`正在停止 ${name}...`, 'info');
    addLog('info', `停止 ${name}...`);
    try {
      await invoke('disconnect');
      toast(`${name} 已停止`, 'success');
      addLog('info', `${name} 已停止`);
    } catch (err) {
      toast(`停止失败: ${err}`, 'error');
      addLog('error', `${name} 停止失败: ${err}`);
    }
    setTimeout(pollStatus, 1000);
  }

  async function restartService(name) {
    toast(`正在重启 ${name}...`, 'info');
    addLog('info', `重启 ${name}...`);
    try {
      await invoke('disconnect');
      await sleep(1000);
      await invoke('connect', { mode: 'system' });
      toast(`${name} 已重启`, 'success');
      addLog('success', `${name} 已重启`);
    } catch (err) {
      toast(`重启失败: ${err}`, 'error');
      addLog('error', `${name} 重启失败: ${err}`);
    }
    setTimeout(pollStatus, 2000);
  }

  // ============================================================
  // Quick Actions
  // ============================================================
  async function quickStartProxy() {
    toast('一键代理模式: 启动 proxy-local + 开启系统代理...', 'info');
    addLog('info', '一键代理模式启动...');
    try {
      await invoke('connect', { mode: 'system' });
      toast('代理模式已激活', 'success');
      addLog('success', '一键代理模式已激活');
    } catch (err) {
      toast(`启动失败: ${err}`, 'error');
      addLog('error', `一键代理失败: ${err}`);
    }
    await pollStatus();
  }

  async function quickStartTun() {
    toast('一键 TUN 模式: 启动 proxy-local + tun-adapter...', 'info');
    addLog('info', '一键 TUN 模式启动...');
    try {
      const result = await invoke('connect', { mode: 'tun' });
      if (result && result !== 'connected') {
        toast(result, 'warning');
        addLog('warning', result);
      } else {
        toast('TUN 模式已激活', 'success');
        addLog('success', 'TUN 模式已激活');
      }
    } catch (err) {
      toast(`TUN 启动失败: ${err}`, 'error');
      addLog('error', `一键 TUN 失败: ${err}`);
    }
    await pollStatus();
  }

  async function quickStopAll() {
    toast('停止所有服务...', 'info');
    addLog('info', '停止所有服务...');
    try {
      await invoke('disconnect');
      toast('所有服务已停止', 'success');
      addLog('info', '所有服务已停止');
    } catch (err) {
      toast(`停止失败: ${err}`, 'error');
    }
    await sleep(1000);
    await pollStatus();
  }

  async function resetNetwork() {
    if (!confirm('确认恢复网络？将强制还原 DNS/路由并关闭系统代理。')) return;
    toast('正在恢复网络...', 'info');
    addLog('info', '恢复网络...');
    try {
      await invoke('reset_network');
      toast('网络已恢复', 'success');
      addLog('success', '网络已恢复');
    } catch (err) {
      toast(`恢复失败: ${err}`, 'error');
      addLog('error', `网络恢复失败: ${err}`);
    }
    await pollStatus();
  }

  async function toggleSystemProxy(enabled) {
    try {
      if (enabled) {
        await invoke('connect', { mode: 'system' });
        toast('系统代理已开启', 'success');
      } else {
        await invoke('disconnect');
        toast('系统代理已关闭', 'success');
      }
    } catch (err) {
      toast(`操作失败: ${err}`, 'error');
      $('#systemProxyToggle').checked = !enabled;
    }
    await pollStatus();
  }

  // ============================================================
  // Config
  // ============================================================
  async function loadConfig() {
    try {
      const config = await invoke('get_config');
      localConfig = config;
      originalConfigJson = JSON.stringify(config);
      populateConfigForm(config);
    } catch (e) {
      addLog('warning', `加载配置失败: ${e}`);
    }
  }

  function populateConfigForm(c) {
    if (!c) return;
    setVal('#localPort', c.local.port);
    $('#httpProxyEnabled').checked = c.local.http_proxy_enabled;

    // 远端服务器
    renderServerCards([c.remote]);

    // 路由
    if (c.route) {
      setVal('#defaultRoute', c.route.default_route);
      $('#proxyList').value = (c.route.proxy_list || []).join('\n');
      $('#directList').value = (c.route.direct_list || []).join('\n');
      updateRouteCounts();
    }
  }

  function collectConfig() {
    if (!localConfig) localConfig = {};
    localConfig.local = {
      port: intVal('#localPort', 1080),
      http_proxy_enabled: $('#httpProxyEnabled').checked,
      http_proxy_port: intVal('#localPort', 1080), // HTTP 与 SOCKS5 共用端口
    };

    // 收集服务器卡片
    const cards = $$('.server-card', $('#remoteServers'));
    if (cards.length > 0) {
      const card = cards[0];
      localConfig.remote = {
        host: val($('[data-key="host"]', card)),
        port: parseInt(val($('[data-key="port"]', card))) || 9090,
        cipher: val($('[data-key="cipher"]', card)),
        key: val($('[data-key="key"]', card)),
      };
    }

    localConfig.route = {
      default_route: val('#defaultRoute'),
      proxy_list: parseLines('#proxyList'),
      direct_list: parseLines('#directList'),
    };

    return localConfig;
  }

  async function saveAll() {
    const configData = collectConfig();
    try {
      await invoke('save_config', { configData });
      originalConfigJson = JSON.stringify(configData);
      updateChangeState();
      toast('配置已保存', 'success');
      addLog('success', '配置已保存');
    } catch (e) {
      toast(`保存失败: ${e}`, 'error');
      addLog('error', `保存配置失败: ${e}`);
    }
  }

  // ============================================================
  // Server Cards
  // ============================================================
  function renderServerCards(servers) {
    const container = $('#remoteServers');
    container.innerHTML = '';
    (servers || []).forEach((srv, i) => container.appendChild(createServerCard(srv, i)));
  }

  function createServerCard(srv, index) {
    const card = document.createElement('div');
    card.className = 'server-card';
    card.innerHTML = `
      <div class="server-card-header">
        <span class="server-card-title"><span class="server-index">#${index + 1}</span> ${esc(srv.host || '?')}:${srv.port || '?'}</span>
        <button class="btn-sm btn-danger btn-remove" title="删除">×</button>
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
        <div class="server-field"><label>Key</label><input class="input" value="${escAttr(srv.key || '')}" data-key="key" type="password"></div>
      </div>`;
    $$('input, select', card).forEach(el => {
      el.addEventListener('input', markChanged);
      el.addEventListener('change', markChanged);
    });
    $('.btn-remove', card).addEventListener('click', () => { card.remove(); markChanged(); });
    return card;
  }

  // ============================================================
  // TUN Config (Raw TOML Editor)
  // ============================================================
  async function loadTunConfig() {
    try {
      const text = await invoke('get_tun_config_raw');
      $('#tunConfigEditor').value = text;
    } catch (e) {
      // 降级：从结构化接口生成文本
      try {
        const config = await invoke('get_tun_config');
        $('#tunConfigEditor').value = tunConfigToToml(config);
      } catch (e2) {
        $('#tunConfigEditor').value = '# 加载 TUN 配置失败: ' + e2;
      }
    }
  }

  function tunConfigToToml(c) {
    let t = '# TUN Adapter 配置\n\n[tun]\n';
    t += `name = "${c.tun.name}"\n`;
    t += `address = "${c.tun.address}"\n`;
    if (c.tun.netmask) t += `netmask = "${c.tun.netmask}"\n`;
    t += `mtu = ${c.tun.mtu}\n`;
    t += `enabled = ${c.tun.enabled}\n\n`;
    t += '[dns]\n';
    t += `listen = "${c.dns.listen}"\n`;
    t += `upstream = "${c.dns.upstream}"\n\n`;
    t += '[proxy]\n';
    if (c.proxy.socks5) t += `socks5 = "${c.proxy.socks5}"\n`;
    if (c.proxy.socks5_addr) t += `socks5_addr = "${c.proxy.socks5_addr}"\n`;
    if (c.routing) {
      t += '\n[routing]\n';
      t += `default_action = "${c.routing.default_action}"\n`;
      if (c.routing.rules && c.routing.rules.length > 0) {
        t += '\n';
        c.routing.rules.forEach(r => {
          t += `[[routing.rules]]\ntype = "${r.rule_type || r.type}"\nvalue = "${r.value}"\naction = "${r.action}"\n\n`;
        });
      }
    }
    if (c.bypass) {
      t += '[bypass]\n';
      if (c.bypass.proxy_remote_ips && c.bypass.proxy_remote_ips.length > 0) {
        t += `proxy_remote_ips = [${c.bypass.proxy_remote_ips.map(s => `"${s}"`).join(', ')}]\n`;
      }
      if (c.bypass.extra_cidrs && c.bypass.extra_cidrs.length > 0) {
        t += `extra_cidrs = [${c.bypass.extra_cidrs.map(s => `"${s}"`).join(', ')}]\n`;
      }
      if (c.bypass.dns_bypass_ips && c.bypass.dns_bypass_ips.length > 0) {
        t += `dns_bypass_ips = [${c.bypass.dns_bypass_ips.map(s => `"${s}"`).join(', ')}]\n`;
      }
    }
    return t;
  }

  async function saveTunConfig() {
    const content = $('#tunConfigEditor').value;
    try {
      await invoke('save_tun_config_raw', { content });
      toast('TUN 配置已保存', 'success');
      addLog('success', 'TUN 配置已保存');
    } catch (e) {
      toast(`保存失败: ${e}`, 'error');
      addLog('error', `TUN 配置保存失败: ${e}`);
    }
  }

  // ============================================================
  // Presets (Backend-managed)
  // ============================================================
  async function loadPresets() {
    try {
      presets = await invoke('get_presets');
      renderPresets();
    } catch (e) {
      // 降级到本地存储
      try {
        const stored = localStorage.getItem('simpleplane-presets');
        if (stored) presets = JSON.parse(stored);
      } catch {}
      renderPresets();
    }
  }

  function renderPresets() {
    const container = $('#presetList');
    if (!container) return;
    if (presets.length === 0) {
      container.innerHTML = '<div class="preset-empty">暂无预设，点击上方按钮保存当前配置</div>';
      return;
    }
    container.innerHTML = '';
    presets.forEach((p, i) => {
      const el = document.createElement('div');
      el.className = 'preset-item';
      const cfg = p.config || p;
      const remote = cfg.remote || {};
      el.innerHTML = `
        <div class="preset-info">
          <span class="preset-name">${esc(p.name)}</span>
          <span class="preset-desc">${esc(p.description || '')}</span>
          <span class="preset-meta">${remote.host || ''}:${remote.port || ''} | ${remote.cipher || 'chacha20'}</span>
        </div>
        <div class="preset-actions">
          <button class="btn-sm btn-start" data-action="apply" data-index="${i}">应用</button>
          <button class="btn-sm btn-danger" data-action="delete" data-index="${i}">删除</button>
        </div>`;
      container.appendChild(el);
    });
    // 绑定按钮事件
    $$('[data-action="apply"]', container).forEach(btn => {
      btn.addEventListener('click', () => applyPreset(parseInt(btn.dataset.index)));
    });
    $$('[data-action="delete"]', container).forEach(btn => {
      btn.addEventListener('click', () => deletePreset(parseInt(btn.dataset.index)));
    });
  }

  async function savePreset() {
    const name = prompt('预设名称:', `预设 ${presets.length + 1}`);
    if (!name) return;
    const description = prompt('预设描述 (可选):', '') || '';
    const config = collectConfig();
    const preset = { name, description, config };
    try {
      await invoke('save_preset', { preset });
      toast(`预设 "${name}" 已保存`, 'success');
      addLog('success', `预设已保存: ${name}`);
      await loadPresets();
    } catch (e) {
      // 降级到本地存储
      presets.push(preset);
      localStorage.setItem('simpleplane-presets', JSON.stringify(presets));
      renderPresets();
      toast(`预设 "${name}" 已保存(本地)`, 'success');
    }
  }

  async function applyPreset(index) {
    const p = presets[index];
    if (!p) return;
    try {
      await invoke('apply_preset', { name: p.name });
      await loadConfig();
      toast(`已加载预设 "${p.name}"`, 'success');
      addLog('info', `已加载预设: ${p.name}`);
    } catch (e) {
      // 降级到直接应用
      const cfg = p.config || p;
      localConfig = { local: cfg.local, remote: cfg.remote, route: cfg.route };
      populateConfigForm(localConfig);
      markChanged();
      toast(`已加载预设 "${p.name}" (本地)`, 'success');
    }
  }

  async function deletePreset(index) {
    const p = presets[index];
    if (!p) return;
    if (!confirm(`删除预设 "${p.name}"？`)) return;
    try {
      await invoke('delete_preset', { name: p.name });
      toast('预设已删除', 'success');
      await loadPresets();
    } catch (e) {
      presets.splice(index, 1);
      localStorage.setItem('simpleplane-presets', JSON.stringify(presets));
      renderPresets();
      toast('预设已删除 (本地)', 'success');
    }
  }

  // ============================================================
  // Route
  // ============================================================
  function updateRouteCounts() {
    const p = parseLines('#proxyList').length;
    const d = parseLines('#directList').length;
    const pc = $('#proxyListCount');
    const dc = $('#directListCount');
    if (pc) pc.textContent = `${p} 条`;
    if (dc) dc.textContent = `${d} 条`;
  }

  // ============================================================
  // Logs (实时日志流 —— 通过后端环形缓冲区增量拉取)
  // ============================================================
  let lastLogTimestamp = 0;
  let logPollInterval = null;

  function addLog(level, message) {
    const entry = { timestamp: Date.now(), level, message, service: 'system' };
    appendLogLine(entry);
  }

  function appendLogLine(entry) {
    const container = $('#logContent');
    if (!container) return;
    const line = document.createElement('div');
    line.className = 'log-line';
    const time = new Date(entry.timestamp).toLocaleTimeString();
    const levelClass = entry.level === 'error' ? 'log-error' : entry.level === 'warning' ? 'log-warning' : entry.level === 'success' ? 'log-success' : 'log-info';
    line.innerHTML = `<span class="log-time">${time}</span><span class="log-level ${levelClass}">[${entry.service || 'system'}]</span><span class="log-text ${levelClass}">${esc(entry.message)}</span>`;
    container.appendChild(line);
    autoScrollLog();

    // 限制显示条数
    while (container.children.length > 2000) container.removeChild(container.firstChild);
  }

  function autoScrollLog() {
    if ($('#logAutoScroll') && $('#logAutoScroll').checked) {
      const term = $('#logTerminal');
      if (term) term.scrollTop = term.scrollHeight;
    }
  }

  function switchLogService(service) {
    currentLogService = service;
    refreshLogView();
  }

  async function refreshLogView() {
    const container = $('#logContent');
    if (!container) return;
    container.innerHTML = '';
    lastLogTimestamp = 0;

    try {
      const svc = currentLogService === 'all' ? null : currentLogService;
      const entries = await invoke('get_logs', { service: svc, count: 500 });
      entries.forEach(e => appendLogLine(e));
      if (entries.length > 0) {
        lastLogTimestamp = entries[entries.length - 1].timestamp;
      }
    } catch (e) {
      // 静默
    }

    // 启动增量轮询
    startLogPolling();
  }

  function startLogPolling() {
    if (logPollInterval) clearInterval(logPollInterval);
    logPollInterval = setInterval(pollNewLogs, 1000);
  }

  async function pollNewLogs() {
    if (currentSection !== 'logs') return;
    try {
      const svc = currentLogService === 'all' ? null : currentLogService;
      const entries = await invoke('get_logs_stream', { since: lastLogTimestamp, service: svc });
      if (entries && entries.length > 0) {
        entries.forEach(e => appendLogLine(e));
        lastLogTimestamp = entries[entries.length - 1].timestamp;
      }
    } catch (e) {
      // 静默
    }
  }

  async function clearLogs() {
    try {
      const svc = currentLogService === 'all' ? null : currentLogService;
      await invoke('clear_logs', { service: svc });
    } catch (e) {}
    const container = $('#logContent');
    if (container) container.innerHTML = '';
    lastLogTimestamp = 0;
    addLog('info', '日志已清空');
  }

  async function runDiagnose() {
    try {
      const results = await invoke('diagnose_tun');
      const container = $('#logContent');
      if (container) {
        results.forEach(line => {
          appendLogLine({ timestamp: Date.now(), level: 'info', service: 'diagnose', message: line });
        });
      }
      toast('诊断完成，结果见日志面板', 'info');
    } catch (e) {
      toast(`诊断失败: ${e}`, 'error');
    }
  }

  // ============================================================
  // Change Detection
  // ============================================================
  function markChanged() {
    collectConfig();
    updateChangeState();
  }

  function updateChangeState() {
    hasChanges = JSON.stringify(localConfig) !== originalConfigJson;
    const btn = $('#btnSave');
    const ind = $('#changeIndicator');
    if (btn) btn.disabled = !hasChanges;
    if (ind) ind.hidden = !hasChanges;
  }

  // ============================================================
  // Bindings
  // ============================================================
  function bindActions() {
    $('#btnSave')?.addEventListener('click', saveAll);
    $('#btnAddServer')?.addEventListener('click', () => {
      const idx = $$('.server-card', $('#remoteServers')).length;
      $('#remoteServers').appendChild(createServerCard({ host: '', port: 9090, cipher: 'chacha20', key: '' }, idx));
      markChanged();
    });

    // 导入服务器
    $('#btnImportServers')?.addEventListener('click', openImportModal);
    $('#importFileInput')?.addEventListener('change', handleImportFile);

    ['#proxyList', '#directList'].forEach(sel => {
      $(sel)?.addEventListener('input', () => { updateRouteCounts(); markChanged(); });
    });

    // 全局表单变更监测
    document.addEventListener('input', (e) => {
      if (e.target.closest('[data-section="proxy-config"]') || e.target.closest('[data-section="route"]')) markChanged();
    });
    document.addEventListener('change', (e) => {
      if (e.target.closest('[data-section="proxy-config"]') || e.target.closest('[data-section="route"]')) markChanged();
    });
  }

  function bindGlobalKeys() {
    document.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 's') {
        e.preventDefault();
        if (hasChanges) saveAll();
      }
    });
  }

  // ============================================================
  // Toast
  // ============================================================
  function toast(message, type = 'info') {
    const container = $('#toastContainer');
    if (!container) return;
    const el = document.createElement('div');
    el.className = `toast ${type}`;
    el.textContent = message;
    container.appendChild(el);
    setTimeout(() => { el.classList.add('leaving'); setTimeout(() => el.remove(), 200); }, 4000);
  }

  // ============================================================
  // Import Servers
  // ============================================================
  function openImportModal() {
    $('#importModal').hidden = false;
    $('#importYamlInput').value = '';
  }

  function closeImportModal() {
    $('#importModal').hidden = true;
  }

  function handleImportFile(e) {
    const file = e.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (ev) => {
      $('#importYamlInput').value = ev.target.result;
    };
    reader.readAsText(file);
    e.target.value = ''; // 重置以允许重复选择同一文件
  }

  async function doImportServers() {
    const yamlContent = val('#importYamlInput').trim();
    if (!yamlContent) {
      toast('请粘贴 YAML 内容或选择文件', 'warning');
      return;
    }
    try {
      const servers = await invoke('import_servers', { yamlContent });
      if (!servers || servers.length === 0) {
        toast('未解析到服务器配置', 'warning');
        return;
      }
      // 渲染导入的服务器卡片
      renderServerCards(servers);
      markChanged();
      closeImportModal();
      toast(`成功导入 ${servers.length} 个服务器节点`, 'success');
      addLog('success', `导入了 ${servers.length} 个服务器节点`);
    } catch (err) {
      toast(`导入失败: ${err}`, 'error');
      addLog('error', `服务器导入失败: ${err}`);
    }
  }

  // ============================================================
  // Auto Updater
  // ============================================================
  let isCheckingUpdate = false;

  async function checkForUpdates() {
    if (isCheckingUpdate) return;
    isCheckingUpdate = true;
    try {
      const { check } = window.__TAURI__.updater;
      const update = await check();
      if (update) {
        showUpdateDialog(update);
      }
    } catch (e) {
      console.log('[updater] Check failed (silent):', e);
    } finally {
      isCheckingUpdate = false;
    }
  }

  function showUpdateDialog(update) {
    // 防止重复弹出
    if ($('#updateOverlay')) return;

    const overlay = document.createElement('div');
    overlay.id = 'updateOverlay';
    overlay.className = 'update-overlay';

    const version = esc(update.version || '未知');
    const body = esc(update.body || '无更新说明');

    overlay.innerHTML = `
      <div class="update-dialog">
        <div class="update-dialog-header">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
            <polyline points="7 10 12 15 17 10"/>
            <line x1="12" y1="15" x2="12" y2="3"/>
          </svg>
          <h3>发现新版本 v${version}</h3>
        </div>
        <div class="update-dialog-body">
          <p class="update-notes">${body}</p>
        </div>
        <div class="update-dialog-progress" id="updateProgress" hidden>
          <div class="update-progress-bar">
            <div class="update-progress-fill" id="updateProgressFill"></div>
          </div>
          <span class="update-progress-text" id="updateProgressText">0%</span>
        </div>
        <div class="update-dialog-actions" id="updateActions">
          <button class="btn-sm" id="btnUpdateLater">稍后提醒</button>
          <button class="btn-save" id="btnUpdateNow">立即更新</button>
        </div>
      </div>
    `;

    document.body.appendChild(overlay);

    // 绑定按钮
    $('#btnUpdateLater').addEventListener('click', () => {
      overlay.remove();
    });

    $('#btnUpdateNow').addEventListener('click', async () => {
      $('#updateActions').hidden = true;
      $('#updateProgress').hidden = false;

      try {
        let downloaded = 0;
        let contentLength = 0;

        await update.downloadAndInstall((event) => {
          if (event.event === 'Started') {
            contentLength = event.data.contentLength || 0;
            $('#updateProgressText').textContent = '开始下载...';
          } else if (event.event === 'Progress') {
            downloaded += event.data.chunkLength || 0;
            const percent = contentLength > 0 ? Math.round((downloaded / contentLength) * 100) : 0;
            $('#updateProgressFill').style.width = percent + '%';
            $('#updateProgressText').textContent = percent + '%';
          } else if (event.event === 'Finished') {
            $('#updateProgressFill').style.width = '100%';
            $('#updateProgressText').textContent = '下载完成，准备安装...';
          }
        });

        // 安装完成后重启
        const { relaunch } = window.__TAURI__.process;
        await relaunch();
      } catch (e) {
        console.error('[updater] Download/install failed:', e);
        $('#updateProgress').hidden = true;
        $('#updateActions').hidden = false;
        toast(`更新失败: ${e}`, 'error');
      }
    });
  }

  // ============================================================
  // Helpers
  // ============================================================
  function val(sel) { const el = typeof sel === 'string' ? $(sel) : sel; return el ? el.value : ''; }
  function intVal(sel, fb) { const v = parseInt(val(sel), 10); return isNaN(v) ? fb : v; }
  function setVal(sel, v) { const el = $(sel); if (el) el.value = v ?? ''; }
  function esc(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
  function escAttr(s) { return String(s).replace(/"/g, '&quot;'); }
  function parseLines(sel) { return val(sel).split('\n').map(s => s.trim()).filter(s => s && !s.startsWith('#')); }
  function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

  // ============================================================
  // Boot
  // ============================================================
  document.addEventListener('DOMContentLoaded', init);

  // Public API
  return {
    startService, stopService, restartService,
    quickStartProxy, quickStartTun, quickStopAll, resetNetwork, toggleSystemProxy,
    saveTunConfig, switchLogService, clearLogs, runDiagnose,
    savePreset, applyPreset, deletePreset,
    closeImportModal, doImportServers,
    checkForUpdates,
  };
})();
