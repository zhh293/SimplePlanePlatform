import crypto from 'node:crypto';
import {
  N, g, GROUP_BYTE_LENGTH, H, HBig, padToBuffer, bufferToBigint,
  modPow, randomBigintBelow, timingSafeEqualBigint, b64urlEncode, b64urlDecode,
} from './srp-group.js';

// SRP-6a protocol per RFC 5054.
// Username is fixed to "admin" (single-admin scenario; multi-admin is a future extension).

export const SRP_USERNAME = 'admin';

// k = H(N | g) — SRP-6a multiplier (computed once, cached).
export const k = HBig(padToBuffer(N), padToBuffer(g));

// --- Registration (offline CLI tool) ---

/**
 * Compute x = H(salt | H(username | ":" | password)).
 * The inner H is the "username:password" hash; outer H mixes in the salt.
 * Both H use SHA-256 padded to GROUP_BYTE_LENGTH.
 */
export function computeX(salt, username, password) {
  const inner = H(`${username}:${password}`);
  return HBig(salt, inner);
}

/**
 * Compute verifier v = g^x mod N.
 * The verifier is stored server-side; it cannot be used to recover x or the password
 * without solving the discrete logarithm problem mod N.
 */
export function computeVerifier(salt, username, password) {
  const x = computeX(salt, username, password);
  return modPow(g, x, N);
}

// --- Login: server-side ephemeral & shared secret ---

/**
 * Generate server ephemeral (b, B).
 * b is the server's private ephemeral (256-bit random below N).
 * B = (k * v + g^b) mod N is the server's public ephemeral.
 * Returns { b, B, u } where u = H(A | B) is computed later when A is known.
 *
 * For the challenge response, we only need B and the server keeps b private.
 */
export function generateServerEphemeral(verifierBig) {
  const b = randomBigintBelow(N);
  const B = (k * verifierBig + modPow(g, b, N)) % N;
  if (B === 0n) return generateServerEphemeral(verifierBig); // retry on 0 (negligible probability)
  return { b, B };
}

/**
 * Compute u = H(A | B) — the scrambling parameter.
 * Must be non-zero; caller should retry if zero (negligible probability).
 */
export function computeU(A, B) {
  return HBig(padToBuffer(A), padToBuffer(B));
}

/**
 * Compute server-side shared secret S = (A * v^u)^b mod N.
 * Then K = H(S) is the session key.
 */
export function computeServerSharedSecret(A, b, v, u) {
  const S = modPow((A * modPow(v, u, N)) % N, b, N);
  return S;
}

export function computeK(S) {
  return HBig(padToBuffer(S));
}

/**
 * Compute M1 = H(H(N) | H(g) | H(username) | salt | A | B | K).
 * This is the client's proof of knowledge of the password, sent to the server.
 */
export function computeM1(username, salt, A, B, K) {
  return HBig(
    H(padToBuffer(N)),
    H(padToBuffer(g)),
    H(username),
    salt,
    padToBuffer(A),
    padToBuffer(B),
    padToBuffer(K),
  );
}

/**
 * Compute M2 = H(A | M1 | K).
 * This is the server's proof of knowledge of K, sent to the client.
 */
export function computeM2(A, M1, K) {
  return HBig(padToBuffer(A), padToBuffer(M1), padToBuffer(K));
}

// --- Challenge session store (in-memory, 5-minute TTL, one-shot) ---

const CHALLENGE_TTL_MS = 5 * 60 * 1000;
const challenges = new Map(); // challenge_id -> { A, b, v, salt, B, u, expires_at }

function cleanupChallenges() {
  const now = Date.now();
  for (const [id, c] of challenges) {
    if (now > c.expires_at) challenges.delete(id);
  }
}

const cleanupTimer = setInterval(cleanupChallenges, 60 * 1000);
cleanupTimer.unref();

export function createChallenge(A, verifierBig, salt) {
  cleanupChallenges();
  if (A % N === 0n) {
    const err = new Error('invalid A');
    err.statusCode = 400;
    err.code = 'invalid_A';
    throw err;
  }
  const { b, B } = generateServerEphemeral(verifierBig);
  const u = computeU(A, B);
  if (u === 0n) {
    // retry — u=0 is a negligible-probability event but the protocol rejects it
    return createChallenge(A, verifierBig, salt);
  }
  const id = randomId('ch');
  challenges.set(id, {
    A,
    b,
    v: verifierBig,
    salt,
    B,
    u,
    expires_at: Date.now() + CHALLENGE_TTL_MS,
  });
  return { challenge_id: id, B };
}

export function takeChallenge(id) {
  const c = challenges.get(id);
  if (!c) return null;
  challenges.delete(id); // one-shot
  if (Date.now() > c.expires_at) return null;
  return c;
}

export function peekChallenge(id) {
  return challenges.get(id) || null;
}

// --- Helpers ---

function randomId(prefix = '') {
  const rand = crypto.randomBytes(9).toString('base64url').replace(/[-_]/g, '').slice(0, 12);
  return prefix ? `${prefix}_${rand}` : rand;
}

export function randomSalt(bytes = 16) {
  return crypto.randomBytes(bytes);
}

// --- Full round-trip (for testing and the CLI tool) ---

/**
 * Simulate a full SRP-6a handshake from client side.
 * Used by tests and the CLI verifier generator (which only needs computeVerifier).
 */
export function clientHandshake(username, password, salt, verifier) {
  const a = randomBigintBelow(N);
  const A = modPow(g, a, N);
  const { b, B } = generateServerEphemeral(verifier);
  const u = computeU(A, B);
  const x = computeX(salt, username, password);
  // S = (B - k * g^x) ^ (a + u * x) mod N
  const base = (B - (k * modPow(g, x, N)) % N + N * 2n) % N; // ensure positive
  const exp = a + u * x;
  const S = modPow(base, exp, N);
  const K = computeK(S);
  const M1 = computeM1(username, salt, A, B, K);
  const M2 = computeM2(A, M1, K);
  return { a, A, b, B, u, x, S, K, M1, M2 };
}

/**
 * Client-side handshake step 1: generate ephemeral a and public A.
 * The client sends A to /admin/srp/challenge along with username.
 */
export function clientGenerateEphemeral() {
  const a = randomBigintBelow(N);
  const A = modPow(g, a, N);
  return { a, A };
}

/**
 * Client-side handshake step 2: derive shared secret K and proof M1
 * from the client's secret a, the password-derived x, and the server's public B.
 *
 * x is computed from (salt, username, password) via computeX.
 * Caller must verify B != 0 mod N before calling.
 */
export function clientComputeSecretAndProof(a, x, A, B, username, salt) {
  const u = computeU(A, B);
  const base = (B - (k * modPow(g, x, N)) % N + N * 2n) % N;
  const exp = a + u * x;
  const S = modPow(base, exp, N);
  const K = computeK(S);
  const M1 = computeM1(username, salt, A, B, K);
  const M2 = computeM2(A, M1, K);
  return { u, S, K, M1, M2 };
}

export { timingSafeEqualBigint, b64urlEncode, b64urlDecode };
