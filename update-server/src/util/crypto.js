import crypto from 'node:crypto';
import { createReadStream } from 'node:fs';

const SCRYPT_N = 16384;
const SCRYPT_R = 8;
const SCRYPT_P = 1;
const SCRYPT_KEYLEN = 32;

export function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(password, salt, SCRYPT_KEYLEN, { N: SCRYPT_N, r: SCRYPT_R, p: SCRYPT_P });
  return `scrypt$${SCRYPT_N}$${SCRYPT_R}$${SCRYPT_P}$${salt.toString('base64')}$${hash.toString('base64')}`;
}

export function verifyPassword(password, encoded) {
  if (typeof encoded !== 'string') return false;
  const parts = encoded.split('$');
  if (parts.length !== 6 || parts[0] !== 'scrypt') return false;
  const N = Number(parts[1]);
  const r = Number(parts[2]);
  const p = Number(parts[3]);
  const salt = Buffer.from(parts[4], 'base64');
  const expected = Buffer.from(parts[5], 'base64');
  if (!Number.isFinite(N) || !Number.isFinite(r) || !Number.isFinite(p)) return false;
  const actual = crypto.scryptSync(password, salt, expected.length, { N, r, p });
  return crypto.timingSafeEqual(actual, expected);
}

export function signSession(payload, secret) {
  const data = Buffer.from(JSON.stringify(payload)).toString('base64url');
  const sig = crypto.createHmac('sha256', secret).update(data).digest('base64url');
  return `${data}.${sig}`;
}

export function verifySession(cookie, secret) {
  if (!cookie || typeof cookie !== 'string') return null;
  const sep = cookie.lastIndexOf('.');
  if (sep < 1) return null;
  const data = cookie.slice(0, sep);
  const sig = cookie.slice(sep + 1);
  const expected = crypto.createHmac('sha256', secret).update(data).digest('base64url');
  const sigBuf = Buffer.from(sig);
  const expectedBuf = Buffer.from(expected);
  if (sigBuf.length !== expectedBuf.length) return null;
  if (!crypto.timingSafeEqual(sigBuf, expectedBuf)) return null;
  try {
    const json = Buffer.from(data, 'base64url').toString('utf8');
    const payload = JSON.parse(json);
    if (typeof payload.exp !== 'number' || Date.now() > payload.exp) return null;
    return payload;
  } catch {
    return null;
  }
}

export function sha256Buffer(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
}

export function sha256Stream(readStream) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    readStream.on('data', (chunk) => hash.update(chunk));
    readStream.on('end', () => resolve(hash.digest('hex')));
    readStream.on('error', reject);
  });
}

export function sha256File(filePath) {
  return sha256Stream(createReadStream(filePath));
}

const BASE64_RE = /^[A-Za-z0-9+/]+={0,2}$/;

export function isBase64(value) {
  if (typeof value !== 'string' || value.length === 0) return false;
  if (value.length % 4 !== 0) return false;
  if (!BASE64_RE.test(value)) return false;
  try {
    const buf = Buffer.from(value, 'base64');
    return buf.length > 0 && Buffer.from(buf.toString('base64'), 'base64').equals(buf);
  } catch {
    return false;
  }
}

export function randomId(prefix = '') {
  const rand = crypto.randomBytes(9).toString('base64url').replace(/[-_]/g, '').slice(0, 12);
  return prefix ? `${prefix}_${rand}` : rand;
}
