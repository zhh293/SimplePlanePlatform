import test from 'node:test';
import assert from 'node:assert/strict';
import {
  parseCookies,
  createAuthState,
  getRequestIp,
  issueSessionCookie,
  clearSessionCookie,
} from '../src/auth.js';
import { hashPassword, signSession } from '../src/util/crypto.js';

test('parseCookies parses multi-cookie header', () => {
  const out = parseCookies('a=1; b=2; c=hello');
  assert.deepEqual(out, { a: '1', b: '2', c: 'hello' });
  assert.deepEqual(parseCookies(''), {});
  assert.deepEqual(parseCookies(undefined), {});
  assert.deepEqual(parseCookies('malformed'), {});
  assert.deepEqual(parseCookies('=nokey'), {});
});

test('issueSessionCookie and clearSessionCookie formats', () => {
  const cookie = issueSessionCookie({ user: 'admin' }, 'a'.repeat(32));
  assert.match(cookie.header, /^sp_admin_session=[^;]+; HttpOnly; SameSite=Lax; Path=\/; Max-Age=\d+/);
  assert.equal(cookie.SESSION_COOKIE, 'sp_admin_session');
  const clear = clearSessionCookie();
  assert.match(clear, /^sp_admin_session=; HttpOnly; SameSite=Lax; Path=\/; Max-Age=0$/);
});

test('getRequestIp reads x-forwarded-for', () => {
  assert.equal(getRequestIp({ headers: { 'x-forwarded-for': '1.2.3.4, 5.6.7.8' } }), '1.2.3.4');
  assert.equal(getRequestIp({ headers: {}, socket: { remoteAddress: '127.0.0.1' } }), '127.0.0.1');
  assert.equal(getRequestIp({ headers: {} }), 'unknown');
});

test('auth state locks after 5 failures for 15 minutes', () => {
  const state = createAuthState();
  const ip = '1.2.3.4';
  for (let i = 0; i < 4; i += 1) {
    const r = state.recordFailure(ip);
    assert.equal(r.locked, false);
  }
  const fifth = state.recordFailure(ip);
  assert.equal(fifth.locked, true);
  assert.ok(fifth.retryAfterMs > 0);

  const locked = state.isLocked(ip);
  assert.equal(locked.locked, true);

  state.recordSuccess(ip);
  assert.equal(state.isLocked(ip).locked, false);
});

test('auth state resets failure count after lockout window', () => {
  const state = createAuthState();
  state.recordFailure('1.2.3.4');
  state.recordFailure('1.2.3.4');
  assert.equal(state.isLocked('1.2.3.4').locked, false);
});
