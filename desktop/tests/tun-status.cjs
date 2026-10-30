// Exercise the real status renderer with mocked IPC and DOM; no network changes.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const elements = new Map();
function element(key) {
  if (!elements.has(key)) elements.set(key, { textContent: '', className: '', checked: false,
    querySelector: child => element(`${key} ${child}`) });
  return elements.get(key);
}
let status;
const context = vm.createContext({
  window: { __TAURI__: { core: { invoke: async () => status } } },
  document: { querySelector: element, addEventListener() {} },
  console,
});
const source = fs.readFileSync('desktop/web/app.js', 'utf8')
  .replace('startService, stopService, restartService,', 'pollStatus, startService, stopService, restartService,');
vm.runInContext(source, context);
(async () => {
  status = { proxy_status: 'running', tun_status: 'running', proxy_mode: 'tun', proxy_port_listening: true };
  await vm.runInContext('App.pollStatus()', context);
  assert.equal(element('#status-tun-adapter .status-label').textContent, '运行中');
  status.tun_status = 'error';
  await vm.runInContext('App.pollStatus()', context);
  assert.equal(element('#status-tun-adapter .status-label').textContent, '异常');
  assert.equal(element('#card-tun-adapter').className, 'service-card card-error');
  assert.equal(element('#systemProxyToggle').checked, false);
  assert.equal(element('#systemProxyStatus').textContent, '已关闭');
  status.proxy_mode = 'system';
  await vm.runInContext('App.pollStatus()', context);
  assert.equal(element('#systemProxyToggle').checked, true);
  assert.equal(element('#systemProxyStatus').textContent, '已开启');
  console.log('PASS: TUN running -> error is visible; system proxy toggle and label agree with mode');
})().catch(error => { console.error(error); process.exitCode = 1; });
