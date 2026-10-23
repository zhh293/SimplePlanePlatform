import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import {
  safeSegment,
  resolveUnderRoot,
  validateFileName,
  signatureNameFor,
  isPairedSignature,
} from '../src/util/path.js';
import { isBase64, randomId, hashPassword, verifyPassword, signSession, verifySession } from '../src/util/crypto.js';

test('safeSegment rejects dangerous segments', () => {
  assert.equal(safeSegment('foo'), 'foo');
  assert.throws(() => safeSegment(''), /Invalid/);
  assert.throws(() => safeSegment('.'), /Invalid/);
  assert.throws(() => safeSegment('..'), /Invalid/);
  assert.throws(() => safeSegment('a/b'), /Invalid/);
  assert.throws(() => safeSegment('a\\b'), /Invalid/);
});

test('resolveUnderRoot blocks path traversal', () => {
  const root = path.resolve('/tmp/update-server-test-root');
  assert.doesNotThrow(() => resolveUnderRoot(root, ['0.2.3', 'foo.zip']));
  assert.throws(() => resolveUnderRoot(root, ['..', 'etc', 'passwd']), /Invalid file path/);
  assert.throws(() => resolveUnderRoot(root, ['..']), /Invalid file path/);
});

test('validateFileName enforces whitelist', () => {
  assert.equal(validateFileName('SimplePlane_0.2.3_x64-setup.nsis.zip'), 'SimplePlane_0.2.3_x64-setup.nsis.zip');
  assert.throws(() => validateFileName('foo/bar.zip'), /Invalid file name/);
  assert.throws(() => validateFileName(''), /Invalid file name/);
  assert.throws(() => validateFileName('foo.txt'), /extension not allowed/);
  assert.throws(() => validateFileName('foo'), /extension not allowed/);
  assert.equal(validateFileName('foo', { requireExtension: false }), 'foo');
});

test('signatureNameFor pairs with package', () => {
  assert.equal(signatureNameFor('foo.zip'), 'foo.zip.sig');
  assert.equal(signatureNameFor('foo.tar.gz'), 'foo.tar.gz.sig');
  assert.equal(signatureNameFor('foo.exe'), 'foo.exe.sig');
  assert.throws(() => signatureNameFor('foo.dmg'), /must end with/);
});

test('isPairedSignature enforces naming convention', () => {
  assert.equal(isPairedSignature('foo.zip', 'foo.zip.sig'), true);
  assert.equal(isPairedSignature('foo.zip', 'foo.sig'), false);
  assert.equal(isPairedSignature('foo.zip', 'bar.zip.sig'), false);
  assert.equal(isPairedSignature('foo.tar.gz', 'foo.tar.gz.sig'), true);
});

test('isBase64 rejects garbage and accepts valid base64', () => {
  assert.equal(isBase64('dW50cnVzdGVkIGNvbW1lbnQ='), true);
  assert.equal(isBase64(''), false);
  assert.equal(isBase64('not base64!!!'), false);
  assert.equal(isBase64(undefined), false);
  assert.equal(isBase64(123), false);
});

test('randomId produces unique prefixed ids', () => {
  const a = randomId('v');
  const b = randomId('v');
  assert.notEqual(a, b);
  assert.match(a, /^v_/);
  assert.ok(a.length > 5);
});

test('hashPassword and verifyPassword roundtrip', () => {
  const encoded = hashPassword('my-secret-pw');
  assert.match(encoded, /^scrypt\$/);
  assert.equal(verifyPassword('my-secret-pw', encoded), true);
  assert.equal(verifyPassword('wrong-pw', encoded), false);
  assert.equal(verifyPassword('my-secret-pw', 'garbage'), false);
});

test('signSession and verifySession roundtrip with expiry', () => {
  const secret = 'a'.repeat(32);
  const payload = { user: 'admin', exp: Date.now() + 60_000 };
  const cookie = signSession(payload, secret);
  assert.deepEqual(verifySession(cookie, secret), payload);
});

test('verifySession rejects expired cookie', () => {
  const secret = 'a'.repeat(32);
  const payload = { user: 'admin', exp: Date.now() - 1000 };
  const cookie = signSession(payload, secret);
  assert.equal(verifySession(cookie, secret), null);
});

test('verifySession rejects tampered cookie', () => {
  const secret = 'a'.repeat(32);
  const payload = { user: 'admin', exp: Date.now() + 60_000 };
  const cookie = signSession(payload, secret);
  const tampered = `${cookie.slice(0, -3)}xxx`;
  assert.equal(verifySession(tampered, secret), null);
});

test('verifySession rejects wrong secret', () => {
  const cookie = signSession({ user: 'admin', exp: Date.now() + 60_000 }, 'secret-a');
  assert.equal(verifySession(cookie, 'secret-b'), null);
});
