// Exercise the actual updater click handler without downloading or installing.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const elements = new Map();
function element(key) {
  if (!elements.has(key)) elements.set(key, {
    textContent: '', hidden: false, disabled: false, style: {},
    classList: { add() {}, remove() {}, toggle() {} },
    addEventListener(name, handler) { this[name] = handler; },
    remove() {},
  });
  return elements.get(key);
}
let activeOverlay = false;
let installs = 0;
let resolveInstall, rejectInstall, channel;
let timer;
let currentTime = 1000;
const context = vm.createContext({
  window: { __TAURI__: { core: {
    Channel: class { constructor() { channel = this; } },
    invoke: (command) => {
      if (command === 'install_update') {
        installs++;
        return new Promise((resolve, reject) => { resolveInstall = resolve; rejectInstall = reject; });
      }
      throw Error('Unexpected IPC: ' + command);
    },
  } } },
  document: {
    querySelector: key => key === '#updateOverlay' ? (activeOverlay ? element(key) : null) : element(key),
    createElement: () => element('overlay'),
    body: { appendChild() { activeOverlay = true; } },
    addEventListener() {},
  },
  Date: { now: () => currentTime },
  setInterval: fn => { timer = fn; return 1; }, clearInterval: () => { timer = null; },
  setTimeout() {}, console: { log() {}, error() {} },
});
const source = fs.readFileSync('desktop/web/app.js', 'utf8')
  .replace('startService, stopService, restartService,', 'showUpdateDialog, startService, stopService, restartService,')
  .replace('toast(`更新失败: ${e}`, \'error\');', '');
vm.runInContext(source, context);
(async () => {
  vm.runInContext('App.showUpdateDialog({version:"0.2.4",endpoint:"http://example.test"})', context);
  const start = element('#btnUpdateNow');
  const first = start.click();
  await start.click(); // Even a programmatic click must not start another IPC.
  assert.equal(installs, 1);
  assert.equal(start.disabled, true);
  assert.equal(element('#updateActions').hidden, true);
  channel.onmessage({ event: 'Started', data: { contentLength: 10 * 1024 * 1024 } });
  currentTime += 1000;
  channel.onmessage({ event: 'Progress', data: { chunkLength: 5 * 1024 * 1024 } });
  assert.equal(element('#updateProgressFill').style.width, '50%');
  assert.match(element('#updateProgressText').textContent, /5.0 \/ 10.0 MB/);
  assert.match(element('#updateProgressText').textContent, /KB\/s/);
  currentTime += 16000; timer();
  assert.match(element('#updateProgressText').textContent, /等待网络数据/);
  channel.onmessage({ event: 'Finished' });
  assert.match(element('#updateProgressText').textContent, /校验/);
  channel.onmessage({ event: 'Preparing' });
  assert.match(element('#updateProgressText').textContent, /释放运行库/);
  channel.onmessage({ event: 'Installing' });
  assert.match(element('#updateProgressText').textContent, /权限确认/);
  rejectInstall('test cleanup failure'); await first;
  assert.equal(timer, null);
  assert.equal(start.disabled, false);
  assert.match(element('#updateCheckStatus').textContent, /test cleanup failure/);
  const retry = start.click();
  assert.equal(installs, 2);
  channel.onmessage({ event: 'Started', data: { contentLength: null } });
  channel.onmessage({ event: 'Progress', data: { chunkLength: 1024 } });
  assert.doesNotMatch(element('#updateProgressText').textContent, /NaN|Infinity|0%/);
  resolveInstall(); await retry;
  await start.click();
  assert.equal(installs, 2, 'Successful handoff must remain locked until restart');
  assert.equal(timer, null);
  console.log('PASS updater progress, unknown size, stalls, duplicate clicks, stages, failure/retry and handoff');
})().catch(error => { console.error(error); process.exitCode = 1; });
