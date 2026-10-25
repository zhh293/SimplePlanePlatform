import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { createUpdateServer } from '../src/server.js';
import { insertVersion, publishVersion } from '../src/db.js';
import {
  SRP_USERNAME, computeVerifier, computeX,
  clientGenerateEphemeral, clientComputeSecretAndProof,
} from '../src/srp.js';
import { b64urlEncode, b64urlDecode, bufferToBigint, padToBuffer } from '../src/srp-group.js';

const PASSWORD = 'test-admin-pw-2026';

async function writeVerifier(server, password) {
  const salt = crypto.randomBytes(16);
  const v = computeVerifier(salt, SRP_USERNAME, password);
  const verifierFile = path.join(server.ctx.dataDir, 'verifier.json');
  await fs.writeFile(verifierFile, JSON.stringify({
    username: SRP_USERNAME,
    salt: salt.toString('base64'),
    verifier: b64urlEncode(padToBuffer(v)),
    kdf: 'pbkdf2-sha256-600000',
    created_at: new Date().toISOString(),
  }));
  server.ctx.srp = {
    username: SRP_USERNAME,
    salt,
    verifier: v,
    kdf: 'pbkdf2-sha256-600000',
    createdAt: new Date().toISOString(),
  };
  return verifierFile;
}

async function srpLogin(baseUrl, password = PASSWORD) {
  // Client-side SRP-6a handshake against the live server.
  const { a, A } = clientGenerateEphemeral();
  const challengeRes = await fetch(`${baseUrl}/admin/srp/challenge`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: SRP_USERNAME,
      A: b64urlEncode(padToBuffer(A)),
    }),
  });
  if (challengeRes.status !== 200) {
    throw new Error(`challenge failed: ${challengeRes.status}`);
  }
  const { challenge_id, salt, B } = await challengeRes.json();
  const saltBytes = Buffer.from(salt, 'base64');
  const BBig = bufferToBigint(b64urlDecode(B));
  const x = computeX(saltBytes, SRP_USERNAME, password);
  const { M1 } = clientComputeSecretAndProof(a, x, A, BBig, SRP_USERNAME, saltBytes);
  const verifyRes = await fetch(`${baseUrl}/admin/srp/verify`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      challenge_id,
      M1: b64urlEncode(padToBuffer(M1)),
    }),
  });
  if (verifyRes.status !== 200) {
    throw new Error(`verify failed: ${verifyRes.status}`);
  }
  const cookie = verifyRes.headers.get('set-cookie');
  const match = /sp_admin_session=([^;]+)/.exec(cookie);
  return match[1];
}

async function withAuthedServer(fn, overrides = {}) {
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'us-admin-'));
  const server = createUpdateServer({
    dataDir: tempDir,
    publicBaseUrl: 'https://updates.example.test',
    sessionSecret: 'a'.repeat(32),
    ...overrides,
  });
  await writeVerifier(server, PASSWORD);

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const baseUrl = `http://127.0.0.1:${server.address().port}`;

  try {
    await fn(baseUrl, server);
  } finally {
    await new Promise((resolve) => server.close(resolve));
    server.closeDatabase();
    await fs.rm(tempDir, { recursive: true, force: true });
  }
}

function baseVersion(overrides = {}) {
  return {
    id: undefined,
    platform: 'windows-x86_64',
    version: '0.2.2',
    notes: 'initial',
    file_name: 'SimplePlane_0.2.2_x64-setup.nsis.zip',
    file_size: 100,
    file_sha256: 'abc',
    signature: 'sig',
    created_at: '2026-10-20T00:00:00Z',
    ...overrides,
  };
}

function seedVersion(server, overrides = {}) {
  const id = overrides.id || `v_${Math.random().toString(36).slice(2, 10)}`;
  insertVersion(server.ctx.db, baseVersion({ id, ...overrides }));
  if (overrides.publish ?? true) publishVersion(server.ctx.db, id);
  return id;
}

async function login(baseUrl) {
  return srpLogin(baseUrl, PASSWORD);
}

async function adminFetch(baseUrl, cookie, path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (cookie) headers.Cookie = `sp_admin_session=${cookie}`;
  return fetch(`${baseUrl}${path}`, { ...options, headers });
}

test('login returns 200 + cookie with correct password', async () => {
  await withAuthedServer(async (baseUrl, server) => {
    const cookie = await login(baseUrl, server);
    assert.ok(cookie.length > 10);
  });
});

test('legacy POST /admin/login is deprecated (410 Gone)', async () => {
  await withAuthedServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/admin/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: PASSWORD }),
    });
    assert.equal(res.status, 410);
    const body = await res.json();
    assert.equal(body.error, 'legacy_login_deprecated');
  });
});

test('SRP verify with wrong password → 401 invalid_credentials', async () => {
  await withAuthedServer(async (baseUrl) => {
    const { a, A } = clientGenerateEphemeral();
    const challengeRes = await fetch(`${baseUrl}/admin/srp/challenge`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: SRP_USERNAME, A: b64urlEncode(padToBuffer(A)) }),
    });
    const { challenge_id, salt, B } = await challengeRes.json();
    const saltBytes = Buffer.from(salt, 'base64');
    const BBig = bufferToBigint(b64urlDecode(B));
    // Compute x with WRONG password.
    const x = computeX(saltBytes, SRP_USERNAME, 'definitely-wrong-password');
    const { M1 } = clientComputeSecretAndProof(a, x, A, BBig, SRP_USERNAME, saltBytes);
    const verifyRes = await fetch(`${baseUrl}/admin/srp/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ challenge_id, M1: b64urlEncode(padToBuffer(M1)) }),
    });
    assert.equal(verifyRes.status, 401);
    const body = await verifyRes.json();
    assert.equal(body.error, 'invalid_credentials');
  });
});

test('admin routes return 401 without cookie', async () => {
  await withAuthedServer(async (baseUrl) => {
    const res = await adminFetch(baseUrl, null, '/admin/versions');
    assert.equal(res.status, 401);
  });
});

test('5 wrong passwords → 429 lockout via SRP verify', async () => {
  await withAuthedServer(async (baseUrl) => {
    for (let i = 0; i < 4; i += 1) {
      const { a, A } = clientGenerateEphemeral();
      const challengeRes = await fetch(`${baseUrl}/admin/srp/challenge`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: SRP_USERNAME, A: b64urlEncode(padToBuffer(A)) }),
      });
      const { challenge_id, salt, B } = await challengeRes.json();
      const saltBytes = Buffer.from(salt, 'base64');
      const BBig = bufferToBigint(b64urlDecode(B));
      const x = computeX(saltBytes, SRP_USERNAME, 'wrong');
      const { M1 } = clientComputeSecretAndProof(a, x, A, BBig, SRP_USERNAME, saltBytes);
      const r = await fetch(`${baseUrl}/admin/srp/verify`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ challenge_id, M1: b64urlEncode(padToBuffer(M1)) }),
      });
      assert.equal(r.status, 401);
    }
    // 5th wrong attempt should trigger lockout (429).
    const { a, A } = clientGenerateEphemeral();
    const challengeRes = await fetch(`${baseUrl}/admin/srp/challenge`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: SRP_USERNAME, A: b64urlEncode(padToBuffer(A)) }),
    });
    const { challenge_id, salt, B } = await challengeRes.json();
    const saltBytes = Buffer.from(salt, 'base64');
    const BBig = bufferToBigint(b64urlDecode(B));
    const x = computeX(saltBytes, SRP_USERNAME, 'wrong');
    const { M1 } = clientComputeSecretAndProof(a, x, A, BBig, SRP_USERNAME, saltBytes);
    const locked = await fetch(`${baseUrl}/admin/srp/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ challenge_id, M1: b64urlEncode(padToBuffer(M1)) }),
    });
    assert.equal(locked.status, 429);
    const body = await locked.json();
    assert.equal(body.error, 'too_many_attempts');

    // Even correct password should fail while locked.
    const correct = await login(baseUrl).catch((e) => e.message);
    assert.match(String(correct), /locked|429|too many/i);
  });
});

test('GET /admin/session returns user + expires_at', async () => {
  await withAuthedServer(async (baseUrl) => {
    const cookie = await login(baseUrl);
    const res = await adminFetch(baseUrl, cookie, '/admin/session');
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.user, 'admin');
    assert.ok(typeof body.expires_at === 'string');
  });
});

test('POST /admin/logout clears cookie', async () => {
  await withAuthedServer(async (baseUrl) => {
    const cookie = await login(baseUrl);
    const res = await adminFetch(baseUrl, cookie, '/admin/logout', { method: 'POST' });
    assert.equal(res.status, 204);
    const setCookie = res.headers.get('set-cookie');
    assert.match(setCookie || '', /Max-Age=0/);
  });
});

test('GET /admin/versions returns list', async () => {
  await withAuthedServer(async (baseUrl, server) => {
    seedVersion(server, { version: '0.2.2', file_name: 'SimplePlane_0.2.2_x64-setup.nsis.zip' });
    const cookie = await login(baseUrl);
    const res = await adminFetch(baseUrl, cookie, '/admin/versions');
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.ok(Array.isArray(body.versions));
    assert.equal(body.versions.length, 1);
    assert.equal(body.versions[0].version, '0.2.2');
  });
});

test('GET /admin/versions?platform= filters', async () => {
  await withAuthedServer(async (baseUrl, server) => {
    seedVersion(server, { platform: 'windows-x86_64', version: '0.2.2', file_name: 'SimplePlane_0.2.2_x64-setup.nsis.zip' });
    seedVersion(server, { platform: 'darwin-aarch64', version: '0.2.2', file_name: 'SimplePlane_0.2.2_aarch64.app.tar.gz' });
    const cookie = await login(baseUrl);
    const res = await adminFetch(baseUrl, cookie, '/admin/versions?platform=darwin-aarch64');
    const body = await res.json();
    assert.equal(body.versions.length, 1);
    assert.equal(body.versions[0].platform, 'darwin-aarch64');
  });
});

test('GET /admin/versions/{id} returns detail', async () => {
  await withAuthedServer(async (baseUrl, server) => {
    const id = seedVersion(server, { version: '0.2.2' });
    const cookie = await login(baseUrl);
    const res = await adminFetch(baseUrl, cookie, `/admin/versions/${id}`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.id, id);
    assert.equal(body.signature, 'sig');
  });
});

test('GET /admin/versions/{id} 404 for missing', async () => {
  await withAuthedServer(async (baseUrl) => {
    const cookie = await login(baseUrl);
    const res = await adminFetch(baseUrl, cookie, '/admin/versions/v_does_not_exist');
    assert.equal(res.status, 404);
  });
});

test('PATCH /admin/versions/{id} updates notes only', async () => {
  await withAuthedServer(async (baseUrl, server) => {
    const id = seedVersion(server, { version: '0.2.2', notes: 'old' });
    const cookie = await login(baseUrl);
    const res = await adminFetch(baseUrl, cookie, `/admin/versions/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ notes: 'new notes' }),
    });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.notes, 'new notes');
  });
});

test('PATCH with disallowed field → 422', async () => {
  await withAuthedServer(async (baseUrl, server) => {
    const id = seedVersion(server, { version: '0.2.2' });
    const cookie = await login(baseUrl);
    const res = await adminFetch(baseUrl, cookie, `/admin/versions/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ version: '0.2.99' }),
    });
    assert.equal(res.status, 422);
    const body = await res.json();
    assert.equal(body.error, 'immutable_field');
  });
});

test('DELETE non-latest version succeeds', async () => {
  await withAuthedServer(async (baseUrl, server) => {
    const oldId = seedVersion(server, { version: '0.2.2', publish: true });
    const newId = seedVersion(server, { version: '0.2.3', publish: true });
    const cookie = await login(baseUrl);
    const res = await adminFetch(baseUrl, cookie, `/admin/versions/${oldId}`, { method: 'DELETE' });
    assert.equal(res.status, 204);
  });
});

test('DELETE current latest → 409 cannot_delete_latest', async () => {
  await withAuthedServer(async (baseUrl, server) => {
    const id = seedVersion(server, { version: '0.2.3' });
    const cookie = await login(baseUrl);
    const res = await adminFetch(baseUrl, cookie, `/admin/versions/${id}`, { method: 'DELETE' });
    assert.equal(res.status, 409);
    const body = await res.json();
    assert.equal(body.error, 'cannot_delete_latest');
  });
});

test('GET /admin/audit returns recent entries', async () => {
  await withAuthedServer(async (baseUrl) => {
    const cookie = await login(baseUrl); // creates audit entry
    const res = await adminFetch(baseUrl, cookie, '/admin/audit?limit=5');
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.ok(Array.isArray(body.audit));
    assert.ok(body.audit.length >= 1);
    assert.equal(body.audit[0].action, 'admin.login.success');
  });
});
