// Run: node desktop/tests/settings-ui.cjs [path-to-Chromium-browser]
// Real DOM/layout/interaction regression checks with an isolated, mocked Tauri IPC.
const { readFileSync, writeFileSync, mkdirSync } = require('node:fs');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');
const { spawnSync } = require('node:child_process');
const assert = require('node:assert/strict');
const browser = process.argv[2] || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const output = resolve('.build/settings-ui');
mkdirSync(output, { recursive: true });
const mock = () => {
  window.calls = [];
  window.config = { override_url: '', using_override: false, last_bootstrap_url: '' };
  window.__TAURI__ = { core: { invoke: async (command, args) => {
    calls.push({ command, args });
    if (command === 'get_update_config') return structuredClone(config);
    if (command === 'set_update_config') {
      await new Promise(resolve => setTimeout(resolve, 50));
      if (window.failSave) throw Error('disk unavailable');
      config.override_url = args.overrideUrl;
      config.using_override = !!args.overrideUrl;
      return structuredClone(config);
    }
    if (command === 'get_update_bootstrap_urls') return ['https://bootstrap.example.com'];
    if (command === 'check_update_via_bootstrap') {
      if (window.failCheck) throw Error('network unavailable');
      return window.updateResult || { status: 'NoUpdate' };
    }
    if (command === 'get_presets') return [];
    if (command === 'get_config') return null;
    if (command === 'get_tun_config_raw') return '';
    if (command === 'status') return { proxy_status: 'stopped', tun_status: 'stopped' };
    return [];
  } } };
};
const checks = async () => {
  const $ = selector => document.querySelector(selector);
  const expect = (value, message) => { if (!value) throw Error(message); };
  const tick = () => new Promise(resolve => setTimeout(resolve, 100));
  const edit = value => { $('#updateServerUrlInput').value = value; $('#updateServerUrlInput').dispatchEvent(new Event('input', { bubbles: true })); };
  const saves = () => calls.filter(call => call.command === 'set_update_config').length;
  try {
    await tick();
    $('.nav-item[data-section="settings"]').click();
    await tick();
    expect($('#sec-settings').classList.contains('active'), 'Settings navigation failed');
    for (const selector of ['#updateServerUrlInput', '#btnSaveUpdateServer', '#btnResetUpdateServer', '#btnCheckUpdate']) {
      const element = $(selector);
      element.scrollIntoView({ block: 'nearest' });
      const rect = element.getBoundingClientRect();
      expect(rect.width > 30 && rect.right <= innerWidth && rect.left >= 200, `Overflow: ${selector}`);
      expect(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2) === element, `Control obscured: ${selector}`);
    }
    edit('https://');
    $('#btnSaveUpdateServer').click();
    expect(!$('#updateServerError').hidden && saves() === 0, 'Malformed URL was saved');
    edit('https://updates.example.com/');
    expect(!$('#updateServerDirty').hidden, 'Missing dirty indicator');
    $('#updateServerForm').requestSubmit();
    $('#btnSaveUpdateServer').click();
    expect($('#btnSaveUpdateServer').disabled, 'Save not locked during request');
    await tick();
    expect(saves() === 1 && config.override_url === 'https://updates.example.com', 'Save or duplicate suppression failed');
    expect($('#updateServerDirty').hidden, 'Dirty state not cleared');
    edit('https://unsaved.example.com');
    $('.nav-item[data-section="dashboard"]').click();
    $('.nav-item[data-section="settings"]').click();
    await tick();
    expect($('#updateServerUrlInput').value.includes('unsaved'), 'Navigation discarded draft');
    window.failSave = true;
    $('#btnSaveUpdateServer').click();
    await tick();
    expect(!$('#updateServerError').hidden && !$('#btnSaveUpdateServer').disabled, 'Save failure cannot be retried');
    window.failSave = false;
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 's', ctrlKey: true, bubbles: true }));
    await tick();
    expect(config.override_url.includes('unsaved'), 'Ctrl+S did not save settings');
    $('#btnResetUpdateServer').click();
    await tick();
    expect(!config.override_url && !$('#updateServerUrlInput').value, 'Reset failed');
    $('#btnCheckUpdate').click();
    await tick();
    expect(calls.some(call => call.command === 'check_update_via_bootstrap' && call.args.bootstrapUrls.length === 1), 'Updater did not receive bootstrap config');
    expect($('#updateCheckStatus').textContent.includes('最新'), 'No-update feedback missing');
    window.failCheck = true;
    $('#btnCheckUpdate').click();
    await tick();
    expect($('#updateCheckStatus').textContent.includes('network unavailable') && !$('#btnCheckUpdate').disabled, 'Update failure feedback missing');
    window.failCheck = false;
    window.updateResult = { status: 'UpdateAvailable', version: '9.0.0', notes: 'Test update', endpoint: 'https://updates.example.com' };
    $('#btnCheckUpdate').click();
    await tick();
    expect(!!$('#updateOverlay'), 'Update dialog missing');
    $('#btnUpdateLater').click();
    expect(!$('#updateOverlay'), 'Update dialog cannot be dismissed');
    window.updateResult = null;
    $('#toastContainer').replaceChildren();
    document.activeElement.blur();
    $('.content').scrollTop = 0;
    $('#updateCheckStatus').textContent = '使用已保存的更新服务器检查可用版本。';
    document.body.dataset.settingsTest = 'passed';
  } catch (error) {
    document.body.dataset.settingsTest = 'failed';
    document.body.dataset.failure = error.message;
  }
};
const html = readFileSync(resolve('desktop/web/index.html'), 'utf8')
  .replace('<head>', `<head><base href="${pathToFileURL(resolve('desktop/web')).href}/"><script>(${mock})();</script>`)
  .replace('</body>', `<script>document.addEventListener('DOMContentLoaded', ${checks});</script></body>`);
const fixture = resolve(output, 'settings.html');
writeFileSync(fixture, html);
for (const size of ['1100,720', '900,600', '1440,900']) {
  const result = spawnSync(browser, ['--headless', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--run-all-compositor-stages-before-draw',
    `--user-data-dir=${resolve(output, `profile-${size.replace(',', '-')}`)}`, `--window-size=${size}`,
    '--virtual-time-budget=4000', '--dump-dom', `--screenshot=${resolve(output, `settings-${size.replace(',', '-')}.png`)}`, pathToFileURL(fixture).href],
    { encoding: 'utf8', timeout: 45000, windowsHide: true, maxBuffer: 4 * 1024 * 1024 });
  if (result.error) throw result.error;
  writeFileSync(resolve(output, `result-${size.replace(',', '-')}.html`), result.stdout);
  assert.ok(/data-settings-test="passed"/.test(result.stdout), `${size}: ${result.stdout.match(/data-failure="([^"]*)"/)?.[1] || result.stderr}`);
  console.log(`PASS ${size}: layout, clicks, validation, save/reset, retry, draft, shortcut and updater`);
}
