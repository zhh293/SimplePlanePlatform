// Real Windows processes, scoped fixture directories; never touches installed Java.
const fs = require('node:fs');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve('.build', `updater installer's test-${process.pid}`);
const owned = path.join(root, 'install', 'resources', 'jre', 'bin');
const other = path.join(root, 'unrelated');
const script = path.resolve('desktop/src-tauri/windows/prepare-update.ps1');
const children = [];
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function runtime(directory) {
  fs.mkdirSync(directory, { recursive: true });
  const exe = path.join(directory, 'java.exe');
  fs.copyFileSync(path.join(process.env.SystemRoot, 'System32', 'cmd.exe'), exe);
  const child = spawn(exe, ['/D', '/Q', '/K'], { windowsHide: true, stdio: ['pipe', 'ignore', 'ignore'] });
  children.push(child);
  return child;
}
function prepare() {
  return spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', script,
    '-InstallDir', path.join(root, 'install')], { windowsHide: true, encoding: 'utf8', timeout: 30000 });
}
(async () => {
  const ours = runtime(owned);
  const unrelated = runtime(other);
  await pause(600);
  let result = prepare();
  assert.equal(result.status, 0, result.stdout + result.stderr);
  await pause(200);
  assert.notEqual(ours.exitCode, null, 'Owned Java should exit');
  assert.equal(unrelated.exitCode, null, 'Unrelated Java must stay alive');
  const dll = path.join(owned, 'java.dll');
  fs.writeFileSync(dll, 'fixture');
  const lockCode = `$f=[IO.File]::Open($env:TEST_DLL,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None); Write-Output ready; Start-Sleep 60`;
  const locker = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', lockCode],
    { windowsHide: true, env: { ...process.env, TEST_DLL: dll }, stdio: ['ignore', 'pipe', 'pipe'] });
  children.push(locker);
  await new Promise((resolve, reject) => {
    locker.stdout.once('data', resolve);
    locker.once('error', reject);
    locker.once('exit', () => reject(Error('DLL lock fixture exited early')));
  });
  result = prepare();
  assert.equal(result.status, 1, 'A locked Java DLL must abort before replacement');
  assert.match(result.stdout, /Installation preparation failed/);
  locker.kill(); await pause(300);
  result = prepare();
  assert.equal(result.status, 0, result.stdout + result.stderr);
  console.log('PASS installer: owned Java exits, unrelated Java survives, locked DLL aborts, retry succeeds');
})().catch(error => { console.error(error); process.exitCode = 1; }).finally(async () => {
  for (const child of children) if (child.exitCode === null) child.kill();
  await pause(300);
  fs.rmSync(root, { recursive: true, force: true });
});
