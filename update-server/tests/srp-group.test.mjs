import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import {
  N, g, GROUP_BYTE_LENGTH, HASH_NAME,
  padToBuffer, bufferToBigint, modPow, randomBigintBelow,
  H, HBig, b64urlEncode, b64urlDecode, b64urlToBigint, bigintToB64url,
  timingSafeEqualBigint,
} from '../src/srp-group.js';

test('N is 512 hex chars = 256 bytes (2048-bit modp14)', () => {
  assert.equal(N.toString(16).length, 512);
  assert.equal(GROUP_BYTE_LENGTH, 256);
});

test('g = 2', () => {
  assert.equal(g, 2n);
});

test('N is odd (safe prime must be odd)', () => {
  assert.equal(N % 2n, 1n);
});

test('(N-1)/2 is odd (Sophie Germain prime must be odd since N > 3)', () => {
  const q = (N - 1n) / 2n;
  assert.equal(q % 2n, 1n);
});

test('g=2 generates the order-q subgroup: 2^q mod N == 1', () => {
  // Critical check: if N is a safe prime and g=2 is a generator of the
  // order-q subgroup, then 2^q mod N must equal 1.
  const q = (N - 1n) / 2n;
  const result = modPow(g, q, N);
  assert.equal(result, 1n);
});

test('modPow basic cases', () => {
  assert.equal(modPow(2n, 10n, 1000n), 24n); // 2^10 = 1024, mod 1000 = 24
  assert.equal(modPow(3n, 4n, 17n), 13n);    // 3^4 = 81, mod 17 = 81 - 4*17 = 13
  assert.equal(modPow(5n, 0n, 7n), 1n);
});

test('padToBuffer pads to GROUP_BYTE_LENGTH', () => {
  const buf = padToBuffer(1n);
  assert.equal(buf.length, GROUP_BYTE_LENGTH);
  assert.equal(buf[buf.length - 1], 1);
});

test('padToBuffer throws for too-large values', () => {
  const tooLarge = 1n << BigInt(GROUP_BYTE_LENGTH * 8 + 8);
  assert.throws(() => padToBuffer(tooLarge), /exceeds/);
});

test('bufferToBigint roundtrip', () => {
  const orig = 0x12345678n;
  const buf = padToBuffer(orig);
  const back = bufferToBigint(buf);
  assert.equal(back, orig);
});

test('H returns 32 bytes for SHA-256', () => {
  const out = H('hello');
  assert.equal(out.length, 32);
  assert.equal(HASH_NAME, 'sha256');
});

test('H is deterministic', () => {
  const a = H('foo', 'bar');
  const b = H('foo', 'bar');
  assert.deepEqual(a, b);
});

test('HBig returns bigint', () => {
  const v = HBig('test');
  assert.equal(typeof v, 'bigint');
  assert.ok(v > 0n);
});

test('b64url encode/decode roundtrip preserves bytes', () => {
  const buf = crypto.randomBytes(48);
  const encoded = b64urlEncode(buf);
  const decoded = b64urlDecode(encoded);
  assert.deepEqual(decoded, buf);
});

test('bigintToB64url / b64urlToBigint roundtrip', () => {
  const orig = randomBigintBelow(N);
  const encoded = bigintToB64url(orig);
  const decoded = b64urlToBigint(encoded);
  assert.equal(decoded, orig);
});

test('timingSafeEqualBigint: equal values return true', () => {
  const a = HBig('same');
  const b = HBig('same');
  assert.equal(timingSafeEqualBigint(a, b), true);
});

test('timingSafeEqualBigint: different values return false', () => {
  const a = HBig('same');
  const b = HBig('different');
  assert.equal(timingSafeEqualBigint(a, b), false);
});

test('randomBigintBelow returns values in range', () => {
  for (let i = 0; i < 10; i += 1) {
    const r = randomBigintBelow(N);
    assert.ok(r > 0n);
    assert.ok(r < N);
  }
});

test('N matches Node crypto modp14 prime (cross-check)', () => {
  const dh = crypto.createDiffieHellmanGroup('modp14');
  const expectedN = BigInt('0x' + dh.getPrime().toString('hex'));
  assert.equal(N, expectedN);
  assert.equal(g, BigInt('0x' + dh.getGenerator().toString('hex')));
});
