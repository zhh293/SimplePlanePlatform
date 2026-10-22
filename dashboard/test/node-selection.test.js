const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { validateManualNodes, atomicWrite, readNodeStatus, withNodeBypass, prepareTunNodeConfig } = require('../nodes');
const { parseYaml, toYaml } = require('../yaml');

test('manual nodes preserve identity, selection and transport options through YAML', () => {
  const config = { connectionsPerNode: 1, remoteServers: [
    { id: 'a', name: '主节点', host: 'a.example', port: 9090, transport: 'http3', cipherKey: 'key', http3: { serverName: 'a.example' } },
    { id: 'b', host: 'b.example', port: 9091, transport: 'http3' },
  ], nodeSelection: { mode: 'manual', selectedNodeId: 'b' } };
  validateManualNodes(config);
  assert.throws(() => validateManualNodes({ ...config, connectionsPerNode: 2 }));
  assert.deepEqual(parseYaml(toYaml(config)), config);
  assert.throws(() => validateManualNodes({ ...config, nodeSelection: { mode: 'manual', selectedNodeId: 'missing' } }));
  assert.throws(() => validateManualNodes({ ...config, remoteServers: [config.remoteServers[0], config.remoteServers[0]] }));
  assert.throws(() => validateManualNodes({ ...config, nodeSelection: { mode: 'auto', selectedNodeId: 'b' } }));
  const automatic = { ...config, nodeSelection: { mode: 'auto', selectedNodeId: 'b', preferredNodeId: 'a' } };
  validateManualNodes(automatic);
  assert.deepEqual(parseYaml(toYaml(automatic)), automatic);
  assert.throws(() => validateManualNodes({ ...automatic, nodeSelection: { ...automatic.nodeSelection, preferredNodeId: 'missing' } }));
});

test('TUN runtime config includes every candidate address without modifying user config', async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'node-bypass-'));
  const tun = path.join(directory, 'tun.toml');
  const original = '[tun]\nname = "test"\n[bypass]\nproxy_remote_ips = ["192.0.2.1"]\ndns_bypass_ips = ["192.0.2.53"]\n[routing]\ndefault = "proxy"\n';
  try {
    fs.writeFileSync(tun, original);
    const runtime = await prepareTunNodeConfig({ remoteServers: [{ host: 'a.example' }, { host: 'b.example' }] }, tun,
      async host => [{ address: host.startsWith('a') ? '192.0.2.2' : '192.0.2.3' }]);
    const text = fs.readFileSync(runtime, 'utf8');
    assert.match(text, /192\.0\.2\.1/);
    assert.match(text, /192\.0\.2\.2/);
    assert.match(text, /192\.0\.2\.3/);
    assert.match(text, /dns_bypass_ips = \["192.0.2.53"\]/);
    assert.match(text, /\[routing\]/);
    assert.equal(fs.readFileSync(tun, 'utf8'), original);
    assert.equal(withNodeBypass('[tun]\nname = "test"\n', ['192.0.2.2']).includes('[bypass]'), true);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('atomic replacement stays readable and stale process status is rejected', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'node-selection-'));
  const config = path.join(directory, 'proxy.yml');
  try {
    atomicWrite(config, 'first');
    atomicWrite(config, 'second');
    assert.equal(fs.readFileSync(config, 'utf8'), 'second');
    assert.equal(readNodeStatus(config).ok, false);
    atomicWrite(`${config}.status.json`, JSON.stringify({ running: true, updatedAt: Date.now(), currentNodeId: 'b' }));
    assert.equal(readNodeStatus(config).data.currentNodeId, 'b');
    atomicWrite(`${config}.status.json`, JSON.stringify({ running: true, updatedAt: Date.now() - 60000 }));
    assert.equal(readNodeStatus(config).ok, false);
    assert.deepEqual(fs.readdirSync(directory).sort(), ['proxy.yml', 'proxy.yml.status.json']);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});
