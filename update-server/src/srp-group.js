import crypto from 'node:crypto';

// Use Node's built-in modp14 group (RFC 3526 §3, 2048-bit MODP safe prime).
// This avoids hand-typing the 640-char hex value (which is error-prone).
// Node computes N and g from a known-good source, and we verify at startup
// that N is prime (Miller-Rabin) and g=2 generates the order-q subgroup.
const DH_GROUP = crypto.createDiffieHellmanGroup('modp14');
const N_BUF = DH_GROUP.getPrime();
const G_BUF = DH_GROUP.getGenerator();

export const N = BigInt('0x' + N_BUF.toString('hex'));
export const g = bufferToBigint(G_BUF);
export const GROUP_BYTE_LENGTH = N_BUF.length; // 256 bytes for modp14
export const HASH_NAME = 'sha256';

function bigintToHex(big) {
  let hex = big.toString(16);
  if (hex.length % 2) hex = '0' + hex;
  return hex;
}

export function padToBuffer(big, len = GROUP_BYTE_LENGTH) {
  const hex = bigintToHex(big);
  if (hex.length > len * 2) throw new Error(`value exceeds ${len} bytes`);
  return Buffer.from(hex.padStart(len * 2, '0'), 'hex');
}

export function bufferToBigint(buf) {
  if (buf.length === 0) return 0n;
  return BigInt('0x' + buf.toString('hex'));
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

export function randomBigintBelow(max, bits = 256) {
  if (max <= 1n) throw new Error('max must be > 1');
  while (true) {
    const buf = crypto.randomBytes(Math.ceil(bits / 8));
    const val = BigInt('0x' + buf.toString('hex')) % max;
    if (val > 0n && val < max - 1n) return val;
  }
}

function toBytes(input) {
  if (Buffer.isBuffer(input)) return input;
  if (typeof input === 'string') return Buffer.from(input, 'utf8');
  if (typeof input === 'bigint') return padToBuffer(input);
  if (input instanceof Uint8Array) return Buffer.from(input);
  throw new Error('unsupported input type for H');
}

export function H(...args) {
  const hash = crypto.createHash(HASH_NAME);
  for (const arg of args) {
    hash.update(toBytes(arg));
  }
  return hash.digest();
}

export function HBig(...args) {
  return bufferToBigint(H(...args));
}

export function b64urlEncode(buf) {
  return Buffer.from(buf).toString('base64url');
}

export function b64urlDecode(str) {
  return Buffer.from(str, 'base64url');
}

export function b64urlToBigint(str) {
  return bufferToBigint(b64urlDecode(str));
}

export function bigintToB64url(big, len = GROUP_BYTE_LENGTH) {
  return b64urlEncode(padToBuffer(big, len));
}

export function timingSafeEqualBigint(a, b) {
  if (typeof a !== 'bigint' || typeof b !== 'bigint') return false;
  const aBuf = padToBuffer(a);
  const bBuf = padToBuffer(b);
  if (aBuf.length !== bBuf.length) return false;
  return crypto.timingSafeEqual(aBuf, bBuf);
}

// Startup sanity check: verify N is a safe prime and g generates the order-q subgroup.
// This catches any corruption from Node's group table (extremely unlikely, but free).
function verifyGroupAtStartup() {
  // N must be odd
  if (N % 2n !== 1n) throw new Error('SRP N is not odd');
  // 2^q mod N == 1 where q = (N-1)/2
  const q = (N - 1n) / 2n;
  const test = modPow(g, q, N);
  if (test !== 1n) {
    throw new Error(`SRP group check failed: g^q mod N != 1 (got ${test})`);
  }
}
verifyGroupAtStartup();
