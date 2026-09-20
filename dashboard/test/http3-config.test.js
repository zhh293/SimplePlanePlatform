const { test } = require('node:test');
const assert = require('node:assert/strict');
const { parseYaml, toYaml } = require('../yaml');

test('dashboard YAML round-trip preserves HTTP/3 transport and nested options', () => {
  const config = {
    localPort: 1080,
    remoteServers: [{
      host: '54.172.101.190',
      port: 9090,
      transport: 'http3',
      cipher: 'chacha20',
      cipherKey: 'your-cipher-key',
      ssl: false,
      http3: {
        serverName: '54.172.101.190',
        caFile: 'http3-remote-ca.crt',
        certificatePin: '',
        handshakeTimeoutMs: 5000,
        idleTimeoutMs: 60000,
        maxStreams: 1000,
      },
    }],
  };

  const parsed = parseYaml(toYaml(config));
  assert.deepEqual(parsed, config);
});

test('dashboard YAML parser reads block-style HTTP/3 server entries', () => {
  const parsed = parseYaml([
    'remoteServers:',
    '  - host: 54.172.101.190',
    '    port: 9090',
    '    transport: http3',
    '    http3:',
    '      caFile: http3-remote-ca.crt',
  ].join('\n'));

  assert.equal(parsed.remoteServers[0].transport, 'http3');
  assert.equal(parsed.remoteServers[0].http3.caFile, 'http3-remote-ca.crt');
});
