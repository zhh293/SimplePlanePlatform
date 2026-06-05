// ============================================================
// Netty-Proxy Dashboard — Application logic
// ============================================================

(function () {
  'use strict';

  // State
  let localConfig = null;
  let remoteConfig = null;
  let originalLocalJson = '';
  let originalRemoteJson = '';
  let hasChanges = false;
  let currentSection = 'local';
  let eventSource = null;
  let importMode = 'servers'; // 'servers' or 'remote'

  const $ = (sel, ctx = document) => ctx.querySelector(sel);
  const $$ = (sel, ctx = document) => [...ctx.querySelectorAll(sel)];

  // ---- API ----
  async function api(path, method = 'GET', body = null) {
    const opts = { method, headers: {} };
    if (body) {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }
    const res = await fetch(`/api${path}`, opts);
    return res.json();
  }

  // ---- Init ----
  async function init() {
    bindNavigation();
    bindActions();
    bindImportModal();
    bindPresetModal();
    bindGlobalKeys();
    await loadConfigs();
    await loadPresets();
    connectSSE();
    updateChangeState();
  }

  // ---- Load configs ----
  async function loadConfigs() {
    const [localRes, remoteRes] = await Promise.all([
      api('/config/local'),
      api('/config/remote'),
    ]);

    if (localRes.ok) {
      localConfig = localRes.data;
      originalLocalJson = JSON.stringify(localConfig);
      populateLocalForm();
    } else {
      toast('Failed to load proxy.yml: ' + (localRes.error || 'unknown'), 'error');
    }

    if (remoteRes.ok) {
      remoteConfig = remoteRes.data;
      originalRemoteJson = JSON.stringify(remoteConfig);
      populateRemoteForm();
    } else {
      toast('Failed to load remote.yml: ' + (remoteRes.error || 'unknown'), 'error');
    }
  }

  // ---- Populate local form ----
  function populateLocalForm() {
    const c = localConfig;
    if (!c) return;

    setVal('#localPort', c.localPort);
    setChecked('#httpProxyEnabled', c.httpProxyEnabled);
    setVal('#cluster', c.cluster);
    setVal('#loadBalance', c.loadBalance);
    setVal('#timeoutMs', c.timeoutMs);
    setVal('#connectionsPerNode', c.connectionsPerNode);

    if (c.systemProxy) {
      setChecked('#systemProxyEnabled', c.systemProxy.enabled);
      setVal('#systemProxyHost', c.systemProxy.host);
    }

    renderServerCards(c.remoteServers || []);

    if (c.route) {
      setVal('#defaultRoute', c.route.defaultRoute);
      $('#proxyList').value = (c.route.proxyList || []).join('\n');
      $('#directList').value = (c.route.directList || []).join('\n');
      updateRouteCounts();
    }
  }

  // ---- Populate remote form ----
  function populateRemoteForm() {
    const c = remoteConfig;
    if (!c) return;

    setVal('#remoteHost', c.host);
    setVal('#remotePort', c.port);
    setVal('#remoteCipher', c.cipher);
    setVal('#remoteCipherKey', c.cipherKey);
    setVal('#remoteBizThreads', c.bizThreads);
    setVal('#remoteWorkerThreads', c.workerThreads);
    setVal('#remoteBossThreads', c.bossThreads);
    setVal('#remoteMaxStreams', c.maxStreams);
    setVal('#remoteReadIdleTimeout', c.readIdleTimeout);
    setVal('#remoteBacklog', c.backlog);

    if (c.outbound) {
      setVal('#outboundConnectTimeout', c.outbound.connectTimeoutMs);
      setVal('#outboundActiveWait', c.outbound.activeWaitTimeoutMs);
      setChecked('#outboundKeepAlive', c.outbound.keepAlive);
      setChecked('#outboundTcpNoDelay', c.outbound.tcpNoDelay);
    }
  }

  // ---- Server cards ----
  function renderServerCards(servers) {
    const container = $('#remoteServers');
    container.innerHTML = '';
    servers.forEach((srv, idx) => {
      container.appendChild(createServerCard(srv, idx));
    });
  }

  function createServerCard(srv, index) {
    const card = document.createElement('div');
    card.className = 'server-card';
    card.dataset.index = index;

    card.innerHTML = `
      <div class="server-card-header">
        <div class="server-card-title">
          <span class="server-index">#${index + 1}</span>
          <span class="server-host">${escHtml(srv.host || 'unnamed')} : ${srv.port || '?'}</span>
        </div>
        <button class="btn btn-danger btn-sm btn-remove-server" title="Remove server">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          Remove
        </button>
      </div>
      <div class="server-fields">
        <div class="server-field">
          <label>Host</label>
          <input type="text" class="input" value="${escAttr(srv.host || '')}" data-key="host">
        </div>
        <div class="server-field">
          <label>Port</label>
          <input type="number" class="input" value="${srv.port || 9090}" min="1" max="65535" data-key="port">
        </div>
        <div class="server-field">
          <label>SSL</label>
          <label class="toggle">
            <input type="checkbox" ${srv.ssl ? 'checked' : ''} data-key="ssl">
            <span class="toggle-track"><span class="toggle-thumb"></span></span>
          </label>
        </div>
        <div class="server-field">
          <label>Cipher</label>
          <select class="select" data-key="cipher">
            <option value="none" ${srv.cipher === 'none' ? 'selected' : ''}>none</option>
            <option value="aes-gcm" ${srv.cipher === 'aes-gcm' ? 'selected' : ''}>aes-gcm</option>
            <option value="chacha20" ${srv.cipher === 'chacha20' ? 'selected' : ''}>chacha20</option>
            <option value="aes-ctr-hmac" ${srv.cipher === 'aes-ctr-hmac' ? 'selected' : ''}>aes-ctr-hmac</option>
          </select>
        </div>
        <div class="server-field">
          <label>Cipher Key</label>
          <input type="text" class="input" value="${escAttr(srv.cipherKey || '')}" data-key="cipherKey">
        </div>
      </div>
    `;

    $$('input, select', card).forEach(el => {
      el.addEventListener('input', () => { collectServerData(); markChanged(); });
      el.addEventListener('change', () => { collectServerData(); markChanged(); });
    });

    $('.btn-remove-server', card).addEventListener('click', () => {
      card.remove();
      reindexServerCards();
      collectServerData();
      markChanged();
    });

    return card;
  }

  function reindexServerCards() {
    $$('.server-card', $('#remoteServers')).forEach((card, i) => {
      card.dataset.index = i;
      const idx = $('.server-index', card);
      if (idx) idx.textContent = `#${i + 1}`;
    });
  }

  function collectServerData() {
    const servers = [];
    $$('.server-card', $('#remoteServers')).forEach(card => {
      const srv = {};
      srv.host = val($('[data-key="host"]', card));
      srv.port = parseInt(val($('[data-key="port"]', card)), 10) || 9090;
      srv.ssl = $('[data-key="ssl"]', card).checked;
      srv.cipher = val($('[data-key="cipher"]', card));
      srv.cipherKey = val($('[data-key="cipherKey"]', card));
      servers.push(srv);
    });
    if (localConfig) localConfig.remoteServers = servers;
  }

  // ---- Collect all form data ----
  function collectLocalConfig() {
    if (!localConfig) return;
    localConfig.localPort = intVal('#localPort', 1080);
    localConfig.httpProxyEnabled = $('#httpProxyEnabled').checked;
    localConfig.cluster = val('#cluster');
    localConfig.loadBalance = val('#loadBalance');
    localConfig.timeoutMs = intVal('#timeoutMs', 30000);
    localConfig.connectionsPerNode = intVal('#connectionsPerNode', 2);

    if (!localConfig.systemProxy) localConfig.systemProxy = {};
    localConfig.systemProxy.enabled = $('#systemProxyEnabled').checked;
    localConfig.systemProxy.host = val('#systemProxyHost');

    if (!localConfig.route) localConfig.route = {};
    localConfig.route.defaultRoute = val('#defaultRoute');
    localConfig.route.proxyList = parseRouteList('#proxyList');
    localConfig.route.directList = parseRouteList('#directList');

    collectServerData();
  }

  function collectRemoteConfig() {
    if (!remoteConfig) return;
    remoteConfig.host = val('#remoteHost');
    remoteConfig.port = intVal('#remotePort', 9090);
    remoteConfig.cipher = val('#remoteCipher');
    remoteConfig.cipherKey = val('#remoteCipherKey');
    remoteConfig.bizThreads = intVal('#remoteBizThreads', 200);
    remoteConfig.workerThreads = intVal('#remoteWorkerThreads', 0);
    remoteConfig.bossThreads = intVal('#remoteBossThreads', 1);
    remoteConfig.maxStreams = intVal('#remoteMaxStreams', 1000);
    remoteConfig.readIdleTimeout = intVal('#remoteReadIdleTimeout', 60);
    remoteConfig.backlog = intVal('#remoteBacklog', 1024);

    if (!remoteConfig.outbound) remoteConfig.outbound = {};
    remoteConfig.outbound.connectTimeoutMs = intVal('#outboundConnectTimeout', 5000);
    remoteConfig.outbound.activeWaitTimeoutMs = intVal('#outboundActiveWait', 5000);
    remoteConfig.outbound.keepAlive = $('#outboundKeepAlive').checked;
    remoteConfig.outbound.tcpNoDelay = $('#outboundTcpNoDelay').checked;
  }

  // ---- Change detection ----
  function markChanged() {
    collectLocalConfig();
    collectRemoteConfig();
    updateChangeState();
  }

  function updateChangeState() {
    const localChanged = JSON.stringify(localConfig) !== originalLocalJson;
    const remoteChanged = JSON.stringify(remoteConfig) !== originalRemoteJson;
    hasChanges = localChanged || remoteChanged;

    $('#btnSave').disabled = !hasChanges;
    $('#changeIndicator').hidden = !hasChanges;
  }

  // ---- Save ----
  async function saveAll() {
    collectLocalConfig();
    collectRemoteConfig();

    const localErrors = validateLocal(localConfig);
    const remoteErrors = validateRemote(remoteConfig);
    const allErrors = [...localErrors, ...remoteErrors];
    if (allErrors.length > 0) {
      toast('Validation: ' + allErrors[0], 'error');
      return;
    }

    $('#btnSave').disabled = true;
    const [localRes, remoteRes] = await Promise.all([
      api('/config/local', 'POST', { data: localConfig }),
      api('/config/remote', 'POST', { data: remoteConfig }),
    ]);

    if (localRes.ok && remoteRes.ok) {
      originalLocalJson = JSON.stringify(localConfig);
      originalRemoteJson = JSON.stringify(remoteConfig);
      updateChangeState();
      toast('All configurations saved', 'success');
    } else {
      const err = !localRes.ok ? localRes.error : remoteRes.error;
      toast('Save failed: ' + err, 'error');
      $('#btnSave').disabled = false;
    }
  }

  // ---- Reload ----
  async function reloadAll() {
    if (hasChanges && !confirm('Discard all unsaved changes?')) return;
    await loadConfigs();
    updateChangeState();
    toast('Configurations reloaded', 'success');
  }

  // ---- Validation ----
  function validateLocal(c) {
    const errors = [];
    if (!c) return errors;
    if (!c.localPort || c.localPort < 1 || c.localPort > 65535) errors.push('localPort must be 1-65535');
    if (!c.remoteServers || c.remoteServers.length === 0) errors.push('At least one remote server required');
    (c.remoteServers || []).forEach((s, i) => {
      if (!s.host) errors.push(`Server #${i + 1}: host is required`);
      if (!s.port || s.port < 1 || s.port > 65535) errors.push(`Server #${i + 1}: port must be 1-65535`);
    });
    return errors;
  }
  function validateRemote(c) {
    const errors = [];
    if (!c) return errors;
    if (!c.port || c.port < 1 || c.port > 65535) errors.push('Remote port must be 1-65535');
    return errors;
  }

  // ---- Preset Management ----
  async function loadPresets() {
    const res = await api('/presets');
    if (!res.ok) return;
    const select = $('#presetSelect');
    if (!select) return;
    // Keep the first option
    select.innerHTML = '<option value="">-- Select preset --</option>';
    res.presets.forEach(p => {
      const opt = document.createElement('option');
      opt.value = p.name;
      opt.textContent = `${p.name} (${p.host || '?'}:${p.port || '?'})`;
      select.appendChild(opt);
    });
  }

  function bindPresetModal() {
    const modal = $('#presetModal');
    const nameInput = $('#presetName');
    const saveBtn = $('#presetModalSave');
    const cancelBtn = $('#presetModalCancel');
    const closeBtn = $('#presetModalClose');

    function close() { modal.close(); nameInput.value = ''; }
    cancelBtn.addEventListener('click', close);
    closeBtn.addEventListener('click', close);
    modal.addEventListener('click', (e) => { if (e.target === modal) close(); });

    saveBtn.addEventListener('click', async () => {
      const name = nameInput.value.trim().replace(/[^a-zA-Z0-9_-]/g, '');
      if (!name) { toast('Enter a valid preset name', 'error'); return; }
      collectRemoteConfig();
      const res = await api('/presets/save', 'POST', { name, data: remoteConfig });
      if (res.ok) {
        toast(`Preset "${name}" saved`, 'success');
        await loadPresets();
        $('#presetSelect').value = name;
        updatePresetButtons();
        close();
      } else {
        toast('Failed to save preset: ' + (res.error || 'unknown'), 'error');
      }
    });
  }

  function updatePresetButtons() {
    const selected = val('#presetSelect');
    $('#btnLoadPreset').disabled = !selected;
    $('#btnDeletePreset').disabled = !selected;
  }

  // ---- Import Modal ----
  function bindImportModal() {
    const modal = $('#importModal');
    const closeBtn = $('#importModalClose');
    const cancelBtn = $('#importModalCancel');
    const applyBtn = $('#importModalApply');
    const tabs = $$('.import-tab');
    const panelFile = $('#importPanelFile');
    const panelPaste = $('#importPanelPaste');
    const dropZone = $('#dropZone');
    const fileInput = $('#fileInput');
    const yamlTextarea = $('#importYamlText');
    const preview = $('#importPreview');
    const previewContent = $('#importPreviewContent');
    const previewCount = $('#importPreviewCount');
    const errors = $('#importErrors');

    let parsedData = null;

    function closeModal() {
      modal.close();
      resetImportModal();
    }

    function resetImportModal() {
      parsedData = null;
      yamlTextarea.value = '';
      fileInput.value = '';
      preview.hidden = true;
      errors.hidden = true;
      applyBtn.disabled = true;
      $$('.import-tab', modal).forEach(t => t.classList.remove('active'));
      $$('.import-tab', modal)[0].classList.add('active');
      panelFile.hidden = false;
      panelPaste.hidden = true;
    }

    function showModal(mode) {
      importMode = mode;
      resetImportModal();
      $('#importModalTitle').textContent = mode === 'servers'
        ? 'Import Servers from YAML'
        : 'Import Remote Server Config';
      yamlTextarea.placeholder = mode === 'servers'
        ? 'Paste YAML with remoteServers list:\n\nremoteServers:\n  - host: 1.2.3.4\n    port: 9090\n    cipher: none\n  - host: 5.6.7.8\n    port: 9090\n    cipher: aes-gcm'
        : 'Paste remote.yml content:\n\nhost: 0.0.0.0\nport: 9090\ncipher: none\ncipherKey: my-key';
      modal.showModal();
    }

    closeBtn.addEventListener('click', closeModal);
    cancelBtn.addEventListener('click', closeModal);
    modal.addEventListener('click', (e) => { if (e.target === modal) closeModal(); });
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && modal.open) closeModal(); });

    // Tab switching
    tabs.forEach(tab => {
      tab.addEventListener('click', () => {
        tabs.forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
        const tabName = tab.dataset.tab;
        panelFile.hidden = tabName !== 'file';
        panelPaste.hidden = tabName !== 'paste';
        preview.hidden = true;
        errors.hidden = true;
        applyBtn.disabled = true;
        parsedData = null;
      });
    });

    // Drop zone
    dropZone.addEventListener('click', () => fileInput.click());
    dropZone.addEventListener('dragover', (e) => { e.preventDefault(); dropZone.classList.add('dragover'); });
    dropZone.addEventListener('dragleave', () => dropZone.classList.remove('dragover'));
    dropZone.addEventListener('drop', (e) => {
      e.preventDefault();
      dropZone.classList.remove('dragover');
      const file = e.dataTransfer.files[0];
      if (file) handleFile(file);
    });
    fileInput.addEventListener('change', () => {
      if (fileInput.files[0]) handleFile(fileInput.files[0]);
    });

    function handleFile(file) {
      if (!file.name.match(/\.(yml|yaml)$/i)) {
        showImportError('Please select a .yml or .yaml file');
        return;
      }
      const reader = new FileReader();
      reader.onload = (e) => {
        yamlTextarea.value = e.target.result;
        // Switch to paste tab to show content
        tabs.forEach(t => t.classList.remove('active'));
        tabs[1].classList.add('active');
        panelFile.hidden = true;
        panelPaste.hidden = false;
        parseAndPreview(e.target.result);
      };
      reader.readAsText(file);
    }

    // Textarea input
    let parseTimer = null;
    yamlTextarea.addEventListener('input', () => {
      clearTimeout(parseTimer);
      parseTimer = setTimeout(() => parseAndPreview(yamlTextarea.value), 300);
    });

    async function parseAndPreview(yamlText) {
      if (!yamlText.trim()) {
        preview.hidden = true;
        errors.hidden = true;
        applyBtn.disabled = true;
        parsedData = null;
        return;
      }

      const res = await api('/yaml/parse', 'POST', { yaml: yamlText });
      if (!res.ok) {
        showImportError(res.error || 'Parse failed');
        parsedData = null;
        applyBtn.disabled = true;
        return;
      }

      errors.hidden = true;
      parsedData = res.data;

      // Determine what we parsed
      if (importMode === 'servers') {
        // Extract servers from various formats
        const servers = extractServers(parsedData);
        if (!servers || servers.length === 0) {
          showImportError('No servers found. Expected: remoteServers list, or a single server with host/port.');
          applyBtn.disabled = true;
          return;
        }
        previewCount.textContent = `${servers.length} server${servers.length > 1 ? 's' : ''}`;
        previewContent.textContent = servers.map((s, i) =>
          `#${i + 1}  ${s.host}:${s.port}  cipher=${s.cipher || 'none'}`
        ).join('\n');
      } else {
        // Remote config — show key fields
        const keys = Object.keys(parsedData);
        if (keys.length === 0) {
          showImportError('Empty YAML content');
          applyBtn.disabled = true;
          return;
        }
        previewCount.textContent = `${keys.length} field${keys.length > 1 ? 's' : ''}`;
        previewContent.textContent = Object.entries(parsedData)
          .filter(([k]) => k !== 'outbound')
          .map(([k, v]) => `${k}: ${typeof v === 'object' ? JSON.stringify(v) : v}`)
          .join('\n');
        if (parsedData.outbound) {
          previewContent.textContent += '\noutbound:\n' +
            Object.entries(parsedData.outbound).map(([k, v]) => `  ${k}: ${v}`).join('\n');
        }
      }

      preview.hidden = false;
      applyBtn.disabled = false;
    }

    function extractServers(data) {
      if (Array.isArray(data)) return data;
      if (data.remoteServers && Array.isArray(data.remoteServers)) return data.remoteServers;
      if (data.host && data.port) return [data]; // single server
      return null;
    }

    function showImportError(msg) {
      errors.textContent = msg;
      errors.hidden = false;
      preview.hidden = true;
    }

    // Apply button
    applyBtn.addEventListener('click', () => {
      if (!parsedData) return;

      if (importMode === 'servers') {
        const servers = extractServers(parsedData);
        if (!servers) return;
        // Append to existing servers
        const existing = localConfig.remoteServers || [];
        const newServers = [...existing, ...servers];
        localConfig.remoteServers = newServers;
        renderServerCards(newServers);
        markChanged();
        toast(`Added ${servers.length} server(s)`, 'success');
      } else {
        // Replace remote config
        remoteConfig = parsedData;
        originalRemoteJson = JSON.stringify(remoteConfig);
        populateRemoteForm();
        markChanged();
        toast('Remote config imported', 'success');
      }

      closeModal();
    });
  }

  // ---- SSE ----
  function connectSSE() {
    if (eventSource) eventSource.close();
    eventSource = new EventSource('/api/events');

    eventSource.addEventListener('connected', () => {
      $('#statusBadge').classList.remove('disconnected');
      $('.status-text', $('#statusBadge')).textContent = 'Connected';
    });

    eventSource.addEventListener('config-changed', (e) => {
      try {
        const msg = JSON.parse(e.data);
        if (msg.type === 'local' && !hasChanges) {
          localConfig = msg.data;
          originalLocalJson = JSON.stringify(localConfig);
          populateLocalForm();
          toast('proxy.yml updated externally', 'warning');
        } else if (msg.type === 'remote' && !hasChanges) {
          remoteConfig = msg.data;
          originalRemoteJson = JSON.stringify(remoteConfig);
          populateRemoteForm();
          toast('remote.yml updated externally', 'warning');
        } else if (hasChanges) {
          toast(`${msg.type}.yml changed externally (you have unsaved changes)`, 'warning');
        }
      } catch {}
    });

    eventSource.onerror = () => {
      $('#statusBadge').classList.add('disconnected');
      $('.status-text', $('#statusBadge')).textContent = 'Disconnected';
    };
  }

  // ---- Navigation ----
  function bindNavigation() {
    $$('.nav-item').forEach(item => {
      item.addEventListener('click', (e) => {
        e.preventDefault();
        switchSection(item.dataset.section);
      });
    });
  }

  function switchSection(name) {
    currentSection = name;
    $$('.nav-item').forEach(n => n.classList.toggle('active', n.dataset.section === name));
    $$('.section').forEach(s => { s.hidden = s.dataset.section !== name; });

    const titles = { local: 'Local Server', remote: 'Remote Server', route: 'Route Rules', outbound: 'Outbound Settings' };
    const files = { local: 'proxy.yml', remote: 'remote.yml', route: 'proxy.yml', outbound: 'remote.yml' };
    $('#topbarTitle').textContent = titles[name] || name;
    $('#configFilePath').textContent = files[name] || '';

    $('#sidebar').classList.remove('open');
  }

  // ---- Actions ----
  function bindActions() {
    $('#btnSave').addEventListener('click', saveAll);
    $('#btnReload').addEventListener('click', reloadAll);

    $('#btnAddServer').addEventListener('click', () => {
      const newSrv = { host: '', port: 9090, ssl: false, cipher: 'none', cipherKey: '' };
      const idx = $$('.server-card', $('#remoteServers')).length;
      $('#remoteServers').appendChild(createServerCard(newSrv, idx));
      collectServerData();
      markChanged();
      const lastCard = $$('.server-card', $('#remoteServers')).pop();
      if (lastCard) $('[data-key="host"]', lastCard).focus();
    });

    // Import buttons
    $('#btnImportServers').addEventListener('click', () => {
      // Open import modal in servers mode
      const modal = $('#importModal');
      importMode = 'servers';
      $('#importModalTitle').textContent = 'Import Servers from YAML';
      $('#importYamlText').placeholder = 'Paste YAML with remoteServers list:\n\nremoteServers:\n  - host: 1.2.3.4\n    port: 9090\n    cipher: none\n  - host: 5.6.7.8\n    port: 9090\n    cipher: aes-gcm';
      resetImportUI();
      modal.showModal();
    });

    $('#btnImportRemote').addEventListener('click', () => {
      const modal = $('#importModal');
      importMode = 'remote';
      $('#importModalTitle').textContent = 'Import Remote Server Config';
      $('#importYamlText').placeholder = 'Paste remote.yml content:\n\nhost: 0.0.0.0\nport: 9090\ncipher: none\ncipherKey: my-key';
      resetImportUI();
      modal.showModal();
    });

    // Preset buttons
    $('#presetSelect').addEventListener('change', updatePresetButtons);

    $('#btnLoadPreset').addEventListener('click', async () => {
      const name = val('#presetSelect');
      if (!name) return;
      const res = await api('/presets/load', 'POST', { name });
      if (res.ok) {
        remoteConfig = res.data;
        originalRemoteJson = JSON.stringify(remoteConfig);
        populateRemoteForm();
        markChanged();
        toast(`Preset "${name}" loaded`, 'success');
      } else {
        toast('Failed to load preset: ' + (res.error || 'unknown'), 'error');
      }
    });

    $('#btnSavePreset').addEventListener('click', () => {
      $('#presetModal').showModal();
      $('#presetName').focus();
    });

    $('#btnDeletePreset').addEventListener('click', async () => {
      const name = val('#presetSelect');
      if (!name || !confirm(`Delete preset "${name}"?`)) return;
      const res = await api('/presets/delete', 'POST', { name });
      if (res.ok) {
        toast(`Preset "${name}" deleted`, 'success');
        await loadPresets();
        updatePresetButtons();
      } else {
        toast('Failed to delete: ' + (res.error || 'unknown'), 'error');
      }
    });

    // Cipher key toggle
    const toggleBtn = $('#toggleCipherKey');
    if (toggleBtn) {
      toggleBtn.addEventListener('click', () => {
        const input = $('#remoteCipherKey');
        input.type = input.type === 'password' ? 'text' : 'password';
      });
    }

    // Sidebar toggle (mobile)
    $('#sidebarToggle').addEventListener('click', () => {
      $('#sidebar').classList.toggle('open');
    });

    // Global change detection
    document.addEventListener('input', (e) => {
      if (e.target.closest('.section')) markChanged();
    });
    document.addEventListener('change', (e) => {
      if (e.target.closest('.section')) markChanged();
    });

    // Route list counts
    ['#proxyList', '#directList'].forEach(sel => {
      const el = $(sel);
      if (el) el.addEventListener('input', updateRouteCounts);
    });

    // Close sidebar on clicking outside (mobile)
    document.addEventListener('click', (e) => {
      if (window.innerWidth <= 768 && !e.target.closest('.sidebar') && !e.target.closest('#sidebarToggle')) {
        $('#sidebar').classList.remove('open');
      }
    });
  }

  function resetImportUI() {
    $('#importYamlText').value = '';
    $('#fileInput').value = '';
    $('#importPreview').hidden = true;
    $('#importErrors').hidden = true;
    $('#importModalApply').disabled = true;
    const modal = $('#importModal');
    $$('.import-tab', modal).forEach(t => t.classList.remove('active'));
    $$('.import-tab', modal)[0].classList.add('active');
    $('#importPanelFile').hidden = false;
    $('#importPanelPaste').hidden = true;
  }

  function updateRouteCounts() {
    const proxyCount = parseRouteList('#proxyList').length;
    const directCount = parseRouteList('#directList').length;
    $('#proxyListCount').textContent = `${proxyCount} rule${proxyCount !== 1 ? 's' : ''}`;
    $('#directListCount').textContent = `${directCount} rule${directCount !== 1 ? 's' : ''}`;
  }

  // ---- Keyboard shortcuts ----
  function bindGlobalKeys() {
    document.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 's') {
        e.preventDefault();
        if (hasChanges) saveAll();
      }
      if ((e.ctrlKey || e.metaKey) && e.key === 'r' && !e.shiftKey) {
        e.preventDefault();
        reloadAll();
      }
    });
  }

  // ---- Toast ----
  function toast(message, type = 'info') {
    const container = $('#toastContainer');
    const el = document.createElement('div');
    el.className = `toast ${type}`;
    el.textContent = message;
    container.appendChild(el);

    setTimeout(() => {
      el.classList.add('leaving');
      setTimeout(() => el.remove(), 200);
    }, 4000);
  }

  // ---- Helpers ----
  function val(sel) { const el = typeof sel === 'string' ? $(sel) : sel; return el ? el.value : ''; }
  function intVal(sel, fallback) { const v = parseInt(val(sel), 10); return isNaN(v) ? fallback : v; }
  function setVal(sel, v) { const el = $(sel); if (el) el.value = v ?? ''; }
  function setChecked(sel, v) { const el = $(sel); if (el) el.checked = !!v; }
  function escHtml(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'); }
  function escAttr(s) { return String(s).replace(/"/g, '&quot;').replace(/'/g, '&#39;'); }
  function parseRouteList(sel) {
    return val(sel).split('\n').map(s => s.trim()).filter(s => s.length > 0 && !s.startsWith('#'));
  }

  document.addEventListener('DOMContentLoaded', init);

})();
