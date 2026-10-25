import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { createUpdateServer } from '../src/server.js';
import {
  SRP_USERNAME, computeVerifier, computeX,
  clientGenerateEphemeral, clientComputeSecretAndProof,
} from '../src/srp.js';
import { b64urlEncode, b64urlDecode, bufferToBigint, padToBuffer } from '../src/srp-group.js';

const PASSWORD = 'test-admin-pw-2026';

async function withAuthedServer(fn, overrides = {}) {
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'us-static-'));
  const salt = crypto.randomBytes(16);
  const v = computeVerifier(salt, SRP_USERNAME, PASSWORD);
  const verifierFile = path.join(tempDir, 'verifier.json');
  await fs.writeFile(verifierFile, JSON.stringify({
    username: SRP_USERNAME,
    salt: salt.toString('base64'),
    verifier: b64urlEncode(padToBuffer(v)),
    kdf: 'sha256-double-h',
    created_at: new Date().toISOString(),
  }));
  const server = createUpdateServer({
    dataDir: tempDir,
    publicBaseUrl: 'https://updates.example.test',
    sessionSecret: 'a'.repeat(32),
    srpVerifierFile: verifierFile,
    ...overrides,
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const baseUrl = `http://127.0.0.1:${server.address().port}`;
  try {
    await fn(baseUrl, server);
  } finally {
    await new Promise((r) => server.close(r));
    server.closeDatabase();
    await fs.rm(tempDir, { recursive: true, force: true });
  }
}

async function login(baseUrl) {
  const { a, A } = clientGenerateEphemeral();
  const challengeRes = await fetch(`${baseUrl}/admin/srp/challenge`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: SRP_USERNAME, A: b64urlEncode(padToBuffer(A)) }),
  });
  if (challengeRes.status !== 200) throw new Error(`SRP challenge failed: ${challengeRes.status}`);
  const { challenge_id, salt, B } = await challengeRes.json();
  const saltBytes = Buffer.from(salt, 'base64');
  const BBig = bufferToBigint(b64urlDecode(B));
  const x = computeX(saltBytes, SRP_USERNAME, PASSWORD);
  const { M1 } = clientComputeSecretAndProof(a, x, A, BBig, SRP_USERNAME, saltBytes);
  const verifyRes = await fetch(`${baseUrl}/admin/srp/verify`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ challenge_id, M1: b64urlEncode(padToBuffer(M1)) }),
  });
  if (verifyRes.status !== 200) throw new Error(`SRP verify failed: ${verifyRes.status}`);
  const cookie = verifyRes.headers.get('set-cookie');
  return /sp_admin_session=([^;]+)/.exec(cookie)[1];
}

test('GET /admin without session returns 401, not the panel', async () => {
  await withAuthedServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/admin`);
    assert.equal(res.status, 401);
  });
});

test('GET /admin with session returns index.html', async () => {
  await withAuthedServer(async (baseUrl) => {
    const cookie = await login(baseUrl);
    const res = await fetch(`${baseUrl}/admin`, { headers: { Cookie: `sp_admin_session=${cookie}` } });
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type'), /^text\/html/);
    const body = await res.text();
    assert.match(body, /SimplePlane/);
    assert.match(body, /Update Console/);
  });
});

test('GET /admin/static/app.js returns javascript', async () => {
  await withAuthedServer(async (baseUrl) => {
    const cookie = await login(baseUrl);
    const res = await fetch(`${baseUrl}/admin/static/app.js`, { headers: { Cookie: `sp_admin_session=${cookie}` } });
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type'), /^application\/javascript/);
    const body = await res.text();
    assert.match(body, /checkSession/);
  });
});

test('GET /admin/static/style.css returns css', async () => {
  await withAuthedServer(async (baseUrl) => {
    const cookie = await login(baseUrl);
    const res = await fetch(`${baseUrl}/admin/static/style.css`, { headers: { Cookie: `sp_admin_session=${cookie}` } });
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type'), /^text\/css/);
  });
});

test('GET /admin/static/../../etc/passwd returns 403 or 404', async () => {
  await withAuthedServer(async (baseUrl) => {
    const cookie = await login(baseUrl);
    const res = await fetch(`${baseUrl}/admin/static/..%2F..%2F..%2Fetc%2Fpasswd`, { headers: { Cookie: `sp_admin_session=${cookie}` } });
    assert.ok(res.status === 403 || res.status === 404);
  });
});

test('GET /admin/static/nonexistent.js returns 404', async () => {
  await withAuthedServer(async (baseUrl) => {
    const cookie = await login(baseUrl);
    const res = await fetch(`${baseUrl}/admin/static/nonexistent.js`, { headers: { Cookie: `sp_admin_session=${cookie}` } });
    assert.equal(res.status, 404);
  });
});
