import { verifyPassword, signSession, verifySession } from './util/crypto.js';
import { insertAudit } from './db.js';
import {
  SRP_USERNAME,
  computeServerSharedSecret, computeK,
  computeM1, computeM2,
  createChallenge, takeChallenge,
} from './srp.js';
import {
  N, g, padToBuffer, bufferToBigint, b64urlDecode, b64urlEncode,
  timingSafeEqualBigint, H, HBig,
} from './srp-group.js';
import { randomId } from './util/crypto.js';

const SESSION_COOKIE = 'sp_admin_session';
const SESSION_TTL_MS = 24 * 60 * 60 * 1000;
const LOCKOUT_THRESHOLD = 5;
const LOCKOUT_MS = 15 * 60 * 1000;

export function parseCookies(header) {
  const out = {};
  if (typeof header !== 'string') return out;
  for (const part of header.split(';')) {
    const idx = part.indexOf('=');
    if (idx < 0) continue;
    const key = part.slice(0, idx).trim();
    const value = part.slice(idx + 1).trim();
    if (key) out[key] = value;
  }
  return out;
}

export function createAuthState() {
  const failureCount = new Map(); // ip -> { count, firstTs }
  const lockedUntil = new Map();  // ip -> ts
  return {
    recordFailure(ip) {
      const now = Date.now();
      const until = lockedUntil.get(ip);
      if (until && until > now) return { locked: true, retryAfterMs: until - now };
      const prev = failureCount.get(ip) || { count: 0, firstTs: now };
      if (now - prev.firstTs > LOCKOUT_MS) {
        prev.count = 0;
        prev.firstTs = now;
      }
      prev.count += 1;
      failureCount.set(ip, prev);
      if (prev.count >= LOCKOUT_THRESHOLD) {
        const lockUntil = now + LOCKOUT_MS;
        lockedUntil.set(ip, lockUntil);
        failureCount.delete(ip);
        return { locked: true, retryAfterMs: LOCKOUT_MS };
      }
      return { locked: false, attemptsLeft: LOCKOUT_THRESHOLD - prev.count };
    },
    isLocked(ip) {
      const now = Date.now();
      const until = lockedUntil.get(ip);
      if (until && until > now) return { locked: true, retryAfterMs: until - now };
      if (until) lockedUntil.delete(ip);
      return { locked: false };
    },
    recordSuccess(ip) {
      failureCount.delete(ip);
      lockedUntil.delete(ip);
    },
  };
}

export function getRequestIp(request) {
  const fwd = request.headers['x-forwarded-for'];
  if (typeof fwd === 'string' && fwd.length > 0) {
    return fwd.split(',')[0].trim();
  }
  return request.socket?.remoteAddress || 'unknown';
}

export function issueSessionCookie(payload, secret) {
  const cookieValue = signSession(payload, secret);
  const maxAge = SESSION_TTL_MS / 1000;
  const header = `${SESSION_COOKIE}=${cookieValue}; HttpOnly; SameSite=Lax; Path=/; Max-Age=${maxAge}`;
  return { header, cookieValue, SESSION_COOKIE };
}

export function clearSessionCookie() {
  return `${SESSION_COOKIE}=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0`;
}

export function getSessionPayload(request, secret) {
  const cookies = parseCookies(request.headers.cookie);
  const value = cookies[SESSION_COOKIE];
  if (!value) return null;
  return verifySession(value, secret);
}

export async function authenticateAdmin(request, ctx) {
  if (!ctx.sessionSecret) {
    const error = new Error('Server not configured for admin access (missing SESSION_SECRET)');
    error.statusCode = 500;
    error.code = 'server_misconfigured';
    throw error;
  }
  const payload = getSessionPayload(request, ctx.sessionSecret);
  if (!payload) {
    const error = new Error('Unauthorized');
    error.statusCode = 401;
    error.code = 'unauthorized';
    throw error;
  }
  return payload;
}

export async function handleLegacyLogin(request, response, ctx) {
  // Old scrypt-based login is deprecated; clients must use /admin/srp/* handshake.
  const err = new Error('legacy scrypt login deprecated; use /admin/srp/challenge + /admin/srp/verify');
  err.statusCode = 410;
  err.code = 'legacy_login_deprecated';
  throw err;
}

export function handleSrpParams(response, ctx) {
  response.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'public, max-age=3600',
  });
  response.end(JSON.stringify({
    N: b64urlEncode(padToBuffer(N)),
    g: Number(g),
    hash: 'sha256',
    username: SRP_USERNAME,
    srp_version: '6a',
  }));
}

export async function handleSrpChallenge(request, response, ctx) {
  const { parseJsonBody } = await import('./util/http.js');
  if (!ctx.srp) {
    const err = new Error('SRP not configured (missing verifier)');
    err.statusCode = 503;
    err.code = 'srp_not_configured';
    throw err;
  }
  const body = await parseJsonBody(request, 64 * 1024);
  if (!body || typeof body !== 'object') {
    const err = new Error('Invalid JSON body');
    err.statusCode = 400;
    err.code = 'invalid_json';
    throw err;
  }
  if (body.username !== SRP_USERNAME) {
    const err = new Error('invalid username');
    err.statusCode = 400;
    err.code = 'invalid_username';
    throw err;
  }
  if (!body.A) {
    const err = new Error('A required');
    err.statusCode = 400;
    err.code = 'missing_A';
    throw err;
  }
  const A = bufferToBigint(b64urlDecode(body.A));
  if (A % N === 0n) {
    const err = new Error('invalid A');
    err.statusCode = 400;
    err.code = 'invalid_A';
    throw err;
  }
  const ip = getRequestIp(request);
  const lockState = ctx.auth.isLocked(ip);
  if (lockState.locked) {
    response.writeHead(429, {
      'Content-Type': 'application/json; charset=utf-8',
      'Retry-After': Math.ceil(lockState.retryAfterMs / 1000),
    });
    response.end(JSON.stringify({
      error: 'too_many_attempts',
      message: 'Too many failed login attempts',
      retry_after_seconds: Math.ceil(lockState.retryAfterMs / 1000),
    }));
    return;
  }
  try {
    const { challenge_id, B } = createChallenge(A, ctx.srp.verifier, ctx.srp.salt);
    response.writeHead(200, {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
    });
    response.end(JSON.stringify({
      challenge_id,
      salt: ctx.srp.salt.toString('base64'),
      B: b64urlEncode(padToBuffer(B)),
    }));
  } catch (err) {
    if (err.statusCode) throw err;
    const e = new Error('challenge creation failed');
    e.statusCode = 500;
    e.code = 'challenge_failed';
    throw e;
  }
}

export async function handleSrpVerify(request, response, ctx) {
  const { parseJsonBody } = await import('./util/http.js');
  if (!ctx.srp) {
    const err = new Error('SRP not configured');
    err.statusCode = 503;
    err.code = 'srp_not_configured';
    throw err;
  }
  const body = await parseJsonBody(request, 64 * 1024);
  if (!body || !body.challenge_id || !body.M1) {
    const err = new Error('challenge_id and M1 required');
    err.statusCode = 400;
    err.code = 'missing_fields';
    throw err;
  }
  const c = takeChallenge(body.challenge_id);
  if (!c) {
    const err = new Error('challenge not found or expired');
    err.statusCode = 404;
    err.code = 'challenge_not_found';
    throw err;
  }
  // Server computes its own S, K, M1 and compares with client-provided M1.
  const S = computeServerSharedSecret(c.A, c.b, c.v, c.u);
  const K = computeK(S);
  const expectedM1 = computeM1(SRP_USERNAME, c.salt, c.A, c.B, K);
  const providedM1 = bufferToBigint(b64urlDecode(body.M1));

  if (!timingSafeEqualBigint(expectedM1, providedM1)) {
    const ip = getRequestIp(request);
    const result = ctx.auth.recordFailure(ip);
    insertAudit(ctx.db, {
      actor: ip,
      action: 'admin.login.failed',
      detail: 'srp verify failed',
    });
    if (result.locked) {
      response.writeHead(429, {
        'Content-Type': 'application/json; charset=utf-8',
        'Retry-After': Math.ceil(result.retryAfterMs / 1000),
      });
      response.end(JSON.stringify({
        error: 'too_many_attempts',
        message: 'Account locked due to too many failed attempts',
        retry_after_seconds: Math.ceil(result.retryAfterMs / 1000),
      }));
      return;
    }
    const err = new Error('invalid credentials');
    err.statusCode = 401;
    err.code = 'invalid_credentials';
    throw err;
  }

  // Success: sign session, compute M2, write audit.
  const ip = getRequestIp(request);
  ctx.auth.recordSuccess(ip);
  const now = Date.now();
  const payload = { user: SRP_USERNAME, iat: now, exp: now + SESSION_TTL_MS };
  const { header } = issueSessionCookie(payload, ctx.sessionSecret);
  const M2 = computeM2(c.A, expectedM1, K);
  insertAudit(ctx.db, {
    actor: SRP_USERNAME,
    action: 'admin.login.success',
    detail: `ip=${ip}`,
  });
  response.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Set-Cookie': header,
    'Cache-Control': 'no-store',
  });
  response.end(JSON.stringify({
    user: SRP_USERNAME,
    expires_at: new Date(payload.exp).toISOString(),
    M2: b64urlEncode(padToBuffer(M2)),
  }));
}

export function handleLogout(request, response, ctx) {
  const payload = getSessionPayload(request, ctx.sessionSecret);
  if (payload) {
    insertAudit(ctx.db, { actor: payload.user || 'admin', action: 'admin.logout' });
  }
  response.writeHead(204, {
    'Set-Cookie': clearSessionCookie(),
    'Cache-Control': 'no-store',
  });
  response.end();
}

export function handleSession(request, response, ctx) {
  const payload = getSessionPayload(request, ctx.sessionSecret);
  if (!payload) {
    const err = new Error('Unauthorized');
    err.statusCode = 401;
    err.code = 'unauthorized';
    throw err;
  }
  response.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  });
  response.end(JSON.stringify({
    user: payload.user || 'admin',
    expires_at: new Date(payload.exp).toISOString(),
  }));
}
