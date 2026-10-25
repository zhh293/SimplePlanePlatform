import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import {
  SRP_USERNAME, k,
  computeX, computeVerifier,
  generateServerEphemeral, computeU,
  computeServerSharedSecret, computeK,
  computeM1, computeM2,
  createChallenge, takeChallenge, peekChallenge,
  randomSalt, clientHandshake,
  timingSafeEqualBigint,
} from '../src/srp.js';
import { N, g, modPow, padToBuffer, randomBigintBelow } from '../src/srp-group.js';

const TEST_PASSWORD = 'correct horse battery staple';
const TEST_USERNAME = SRP_USERNAME;

test('k is a 256-bit bigint (SHA-256 output)', () => {
  assert.equal(typeof k, 'bigint');
  assert.ok(k > 0n);
  assert.ok(k < N);
});

test('computeX is deterministic for same (salt, username, password)', () => {
  const salt = randomSalt();
  const x1 = computeX(salt, TEST_USERNAME, TEST_PASSWORD);
  const x2 = computeX(salt, TEST_USERNAME, TEST_PASSWORD);
  assert.equal(x1, x2);
});

test('computeX differs for different passwords', () => {
  const salt = randomSalt();
  const x1 = computeX(salt, TEST_USERNAME, 'password1');
  const x2 = computeX(salt, TEST_USERNAME, 'password2');
  assert.notEqual(x1, x2);
});

test('computeVerifier: v = g^x mod N', () => {
  const salt = randomSalt();
  const x = computeX(salt, TEST_USERNAME, TEST_PASSWORD);
  const v = computeVerifier(salt, TEST_USERNAME, TEST_PASSWORD);
  const expected = modPow(g, x, N);
  assert.equal(v, expected);
  assert.ok(v > 1n && v < N - 1n);
});

test('full handshake roundtrip: client and server derive same K and M1', () => {
  const salt = randomSalt();
  const v = computeVerifier(salt, TEST_USERNAME, TEST_PASSWORD);
  const h = clientHandshake(TEST_USERNAME, TEST_PASSWORD, salt, v);
  // Server side: compute its own S, K, M1 from (A, b, v, u) and compare to client's
  const S_server = computeServerSharedSecret(h.A, h.b, v, h.u);
  const K_server = computeK(S_server);
  assert.equal(K_server, h.K, 'server K matches client K');
  const M1_server = computeM1(TEST_USERNAME, salt, h.A, h.B, K_server);
  assert.equal(M1_server, h.M1, 'server M1 matches client M1');
  const M2_server = computeM2(h.A, h.M1, K_server);
  assert.equal(M2_server, h.M2, 'server M2 matches client M2');
});

test('handshake fails with wrong password (M1 mismatch)', () => {
  const salt = randomSalt();
  const v = computeVerifier(salt, TEST_USERNAME, TEST_PASSWORD);
  // Client uses wrong password
  const h = clientHandshake(TEST_USERNAME, 'wrong password', salt, v);
  // Server computes its own S using v (which is for the correct password)
  const S_server = computeServerSharedSecret(h.A, h.b, v, h.u);
  const K_server = computeK(S_server);
  const M1_server = computeM1(TEST_USERNAME, salt, h.A, h.B, K_server);
  assert.notEqual(M1_server, h.M1, 'M1 must differ when password is wrong');
});

test('createChallenge stores challenge with TTL', () => {
  const salt = randomSalt();
  const v = computeVerifier(salt, TEST_USERNAME, TEST_PASSWORD);
  const a = randomBigintBelow(N);
  const A = modPow(g, a, N);
  const { challenge_id, B } = createChallenge(A, v, salt);
  assert.match(challenge_id, /^ch_/);
  assert.ok(B > 0n && B < N);
  const peeked = peekChallenge(challenge_id);
  assert.ok(peeked, 'peek should find the challenge');
  assert.equal(peeked.A, A);
});

test('takeChallenge is one-shot (second call returns null)', () => {
  const salt = randomSalt();
  const v = computeVerifier(salt, TEST_USERNAME, TEST_PASSWORD);
  const a = randomBigintBelow(N);
  const A = modPow(g, a, N);
  const { challenge_id } = createChallenge(A, v, salt);
  const c1 = takeChallenge(challenge_id);
  assert.ok(c1, 'first take returns challenge');
  assert.equal(c1.A, A);
  const c2 = takeChallenge(challenge_id);
  assert.equal(c2, null, 'second take returns null');
});

test('takeChallenge returns null for unknown id', () => {
  assert.equal(takeChallenge('ch_does_not_exist'), null);
});

test('createChallenge rejects A == 0 mod N', () => {
  const salt = randomSalt();
  const v = computeVerifier(salt, TEST_USERNAME, TEST_PASSWORD);
  assert.throws(() => createChallenge(N, v, salt), /invalid A/); // A = N ≡ 0 mod N
});

test('computeU is non-zero for typical inputs', () => {
  const a = randomBigintBelow(N);
  const b = randomBigintBelow(N);
  const A = modPow(g, a, N);
  const B = modPow(g, b, N);
  const u = computeU(A, B);
  assert.ok(u > 0n, 'u should be non-zero');
});

test('verifier for same password but different salts differs', () => {
  const salt1 = randomSalt();
  const salt2 = randomSalt();
  const v1 = computeVerifier(salt1, TEST_USERNAME, TEST_PASSWORD);
  const v2 = computeVerifier(salt2, TEST_USERNAME, TEST_PASSWORD);
  assert.notEqual(v1, v2);
});

test('timingSafeEqualBigint: equal bigints return true', () => {
  const a = computeVerifier(randomSalt(), TEST_USERNAME, TEST_PASSWORD);
  assert.equal(timingSafeEqualBigint(a, a), true);
});

test('client/server handshakes with fresh per-session ephemerals stay consistent', () => {
  // Run 5 roundtrips with fresh random salts and ephemerals; each must derive matching K.
  for (let i = 0; i < 5; i += 1) {
    const salt = randomSalt();
    const v = computeVerifier(salt, TEST_USERNAME, TEST_PASSWORD);
    const h = clientHandshake(TEST_USERNAME, TEST_PASSWORD, salt, v);
    const S_server = computeServerSharedSecret(h.A, h.b, v, h.u);
    assert.equal(computeK(S_server), h.K);
  }
});
