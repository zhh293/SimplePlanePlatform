// SRP-6a client for browsers. Uses native BigInt and Web Crypto API.
// Fetches group params (N, g, hash, username) from /admin/srp/params at startup
// — no hardcoded constants on the client, avoiding copy errors.

let params = null;

export async function loadSrpParams(baseUrl = '') {
  if (params) return params;
  const res = await fetch(`${baseUrl}/admin/srp/params`);
  if (!res.ok) throw new Error(`Failed to fetch SRP params: ${res.status}`);
  const body = await res.json();
  // N arrives as base64url-encoded big-endian bytes.
  const nBytes = base64urlToBytes(body.N);
  if (nBytes.length === 0) throw new Error('SRP params returned empty N');
  params = {
    N: bytesToBigint(nBytes),
    g: BigInt(body.g),
    hash: body.hash,
    username: body.username,
    byteLength: nBytes.length,
  };
  return params;
}

function base64urlToBytes(str) {
  // Convert base64url to base64, then decode.
  const base64 = str.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
  const binStr = atob(padded);
  const out = new Uint8Array(binStr.length);
  for (let i = 0; i < binStr.length; i += 1) out[i] = binStr.charCodeAt(i);
  return out;
}

function bytesToBigint(bytes) {
  let hex = '';
  for (const b of bytes) hex += b.toString(16).padStart(2, '0');
  return BigInt('0x' + (hex || '0'));
}

function bigintToBytes(big, len) {
  let hex = big.toString(16);
  if (hex.length % 2) hex = '0' + hex;
  if (hex.length > len * 2) throw new Error(`bigint exceeds ${len} bytes`);
  hex = hex.padStart(len * 2, '0');
  const out = new Uint8Array(len);
  for (let i = 0; i < len; i += 1) {
    out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  }
  return out;
}

function bigintToBase64url(big, len) {
  const bytes = bigintToBytes(big, len);
  let binStr = '';
  for (const b of bytes) binStr += String.fromCharCode(b);
  return btoa(binStr).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function modPow(base, exp, mod) {
  if (mod <= 0n) throw new Error('mod must be positive');
  if (base < 0n) base = base % mod + mod;
  let result = 1n;
  base = base % mod;
  while (exp > 0n) {
    if (exp & 1n) result = (result * base) % mod;
    exp >>= 1n;
    base = (base * base) % mod;
  }
  return result;
}

function randomBigintBelow(max, bits = 256) {
  const len = Math.ceil(bits / 8);
  while (true) {
    const buf = new Uint8Array(len);
    crypto.getRandomValues(buf);
    const val = bytesToBigint(buf) % max;
    if (val > 0n && val < max - 1n) return val;
  }
}

async function sha256(...parts) {
  // parts: array of Uint8Array | string | bigint
  const buffers = parts.map((p) => {
    if (p instanceof Uint8Array) return p;
    if (typeof p === 'string') return new TextEncoder().encode(p);
    if (typeof p === 'bigint') return bigintToBytes(p, params.byteLength);
    throw new Error('unsupported sha256 input');
  });
  const total = buffers.reduce((sum, b) => sum + b.length, 0);
  const merged = new Uint8Array(total);
  let off = 0;
  for (const b of buffers) { merged.set(b, off); off += b.length; }
  const digest = await crypto.subtle.digest('SHA-256', merged);
  return new Uint8Array(digest);
}

async function sha256Big(...parts) {
  const bytes = await sha256(...parts);
  return bytesToBigint(bytes);
}

// KDF: kept consistent with src/srp.js computeX. The server only stores
// (salt, verifier), and the verifier is computed as g^x mod N where
// x = H(salt | H(username | ":" | password)). No client-side PBKDF2 hardening
// in this iteration — adding it later requires coordinated changes in
// (a) src/srp.js computeX, (b) this client, (c) scripts/generate-srp-credentials.mjs.
// For now, rely on strong passwords (12+ chars, mixed classes) + IP lockout
// for online-attack resistance, and verifier-only storage for server-side
// compromise resistance.

export async function srpLogin(password, baseUrl = '') {
  const p = await loadSrpParams(baseUrl);
  const { N, g, username, byteLength } = p;

  // Step 1: generate client ephemeral (a, A).
  const a = randomBigintBelow(N);
  const A = modPow(g, a, N);
  if (A % N === 0n) throw new Error('Generated A ≡ 0 mod N; retry');

  // Step 2: ask server for challenge.
  const challengeRes = await fetch(`${baseUrl}/admin/srp/challenge`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'same-origin',
    body: JSON.stringify({
      username,
      A: bigintToBase64url(A, byteLength),
    }),
  });
  if (challengeRes.status !== 200) {
    const body = await challengeRes.json().catch(() => ({}));
    throw new Error(`challenge failed: ${body.message || challengeRes.status}`);
  }
  const challenge = await challengeRes.json();
  const { challenge_id, salt, B: BStr } = challenge;
  const saltBytes = base64urlToBytes(salt);
  const B = bytesToBigint(base64urlToBytes(BStr));
  if (B % N === 0n) throw new Error('Server returned B ≡ 0 mod N');

  // Step 3: compute x = H(salt | H(username | ":" | password)).
  const inner = await sha256(`${username}:${password}`);
  const x = await sha256Big(saltBytes, inner);

  // Step 4: compute shared secret K and proof M1.
  const k = await sha256Big(bigintToBytes(N, byteLength), bigintToBytes(g, byteLength));
  const u = await sha256Big(bigintToBytes(A, byteLength), bigintToBytes(B, byteLength));
  // S = (B - k * g^x) ^ (a + u * x) mod N
  const base = ((B - (k * modPow(g, x, N)) % N) % N + N * 2n) % N;
  const exp = a + u * x;
  const S = modPow(base, exp, N);
  const K = await sha256Big(bigintToBytes(S, byteLength));

  // M1 = H(H(N) | H(g) | H(username) | salt | A | B | K)
  const HN = await sha256(bigintToBytes(N, byteLength));
  const Hg = await sha256(bigintToBytes(g, byteLength));
  const Hu = await sha256(username);
  const M1 = await sha256Big(
    HN,
    Hg,
    Hu,
    saltBytes,
    bigintToBytes(A, byteLength),
    bigintToBytes(B, byteLength),
    bigintToBytes(K, byteLength),
  );

  // Step 5: send M1, receive M2 + session cookie.
  const verifyRes = await fetch(`${baseUrl}/admin/srp/verify`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'same-origin',
    body: JSON.stringify({
      challenge_id,
      M1: bigintToBase64url(M1, byteLength),
    }),
  });
  if (verifyRes.status !== 200) {
    const body = await verifyRes.json().catch(() => ({}));
    throw new Error(`verify failed: ${body.message || verifyRes.status}`);
  }
  const body = await verifyRes.json();

  // Step 6: verify server's M2.
  const expectedM2 = await sha256Big(
    bigintToBytes(A, byteLength),
    bigintToBytes(M1, byteLength),
    bigintToBytes(K, byteLength),
  );
  const serverM2 = bytesToBigint(base64urlToBytes(body.M2));
  if (expectedM2 !== serverM2) {
    throw new Error('Server M2 verification failed — possible MitM');
  }

  return { user: body.user, expires_at: body.expires_at };
}

export async function generateVerifierForRotate(password) {
  const p = await loadSrpParams();
  const saltBytes = new Uint8Array(16);
  crypto.getRandomValues(saltBytes);
  const inner = await sha256(`${p.username}:${password}`);
  const x = await sha256Big(saltBytes, inner);
  const v = modPow(p.g, x, p.N);
  return {
    username: p.username,
    salt: btoa(String.fromCharCode(...saltBytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''),
    verifier: bigintToBase64url(v, p.byteLength),
    kdf: 'sha256-double-h',
  };
}
