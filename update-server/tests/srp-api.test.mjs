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

const PASSWORD = 'srp-api-test-pw-2026';

async function withServer(fn, overrides = {}) {
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'us-srp-api-'));
  const salt = crypto.randomBytes(16);
  const v = computeVerifier(salt, SRP_USERNAME, PASSWORD);
  const verifierFile = path.join(tempDir, 'verifier.json');
  await fs.writeFile(verifierFile, JSON.stringify({
    username: SRP_USERNAME,
    salt: salt.toString('base64'),
    verifier: b64urlEncode(padToBuffer(v)),
    kdf: 'pbkdf2-sha256-600000',
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
    await fn(baseUrl, server, { salt, v });
  } finally {
    await new Promise((r) => server.close(r));
    server.closeDatabase();
    await fs.rm(tempDir, { recursive: true, force: true });
  }
}

async function srpChallenge(baseUrl, A) {
  const res = await fetch(`${baseUrl}/admin/srp/challenge`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: SRP_USERNAME, A: b64urlEncode(padToBuffer(A)) }),
  });
  return { status: res.status, body: res.status === 200 ? await res.json() : await res.json().catch(() => ({})) };
}

async function srpVerify(baseUrl, challenge_id, M1) {
  const res = await fetch(`${baseUrl}/admin/srp/verify`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ challenge_id, M1: b64urlEncode(padToBuffer(M1)) }),
  });
  return {
    status: res.status,
    headers: res.headers,
    body: res.status === 200 ? await res.json() : await res.json().catch(() => ({})),
  };
}

test('GET /admin/srp/params returns N, g, hash, username', async () => {
  await withServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/admin/srp/params`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.ok(typeof body.N === 'string' && body.N.length > 0);
    assert.equal(body.g, 2);
    assert.equal(body.hash, 'sha256');
    assert.equal(body.username, 'admin');
    assert.equal(body.srp_version, '6a');
    assert.match(res.headers.get('cache-control'), /public/);
  });
});

test('full SRP handshake → session cookie issued', async () => {
  await withServer(async (baseUrl) => {
    const { a, A } = clientGenerateEphemeral();
    const challenge = await srpChallenge(baseUrl, A);
    assert.equal(challenge.status, 200);
    const saltBytes = Buffer.from(challenge.body.salt, 'base64');
    const BBig = bufferToBigint(b64urlDecode(challenge.body.B));
    const x = computeX(saltBytes, SRP_USERNAME, PASSWORD);
    const { M1, M2 } = clientComputeSecretAndProof(a, x, A, BBig, SRP_USERNAME, saltBytes);
    const verify = await srpVerify(baseUrl, challenge.body.challenge_id, M1);
    assert.equal(verify.status, 200);
    assert.match(verify.headers.get('set-cookie') || '', /sp_admin_session=/);
    assert.equal(verify.body.user, 'admin');
    assert.ok(verify.body.M2);
    // M2 must equal what client computed.
    assert.equal(verify.body.M2, b64urlEncode(padToBuffer(M2)));
  });
});

test('challenge with missing A → 400 missing_A', async () => {
  await withServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/admin/srp/challenge`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: SRP_USERNAME }),
    });
    assert.equal(res.status, 400);
    const body = await res.json();
    assert.equal(body.error, 'missing_A');
  });
});

test('challenge with wrong username → 400 invalid_username', async () => {
  await withServer(async (baseUrl) => {
    const { A } = clientGenerateEphemeral();
    const res = await fetch(`${baseUrl}/admin/srp/challenge`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'root', A: b64urlEncode(padToBuffer(A)) }),
    });
    assert.equal(res.status, 400);
    const body = await res.json();
    assert.equal(body.error, 'invalid_username');
  });
});

test('challenge with A ≡ 0 mod N → 400 invalid_A', async () => {
  await withServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/admin/srp/challenge`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: SRP_USERNAME, A: b64urlEncode(padToBuffer(0n)) }),
    });
    assert.equal(res.status, 400);
    const body = await res.json();
    assert.equal(body.error, 'invalid_A');
  });
});

test('verify with unknown challenge_id → 404 challenge_not_found', async () => {
  await withServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/admin/srp/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ challenge_id: 'ch_nonexistent', M1: b64urlEncode(padToBuffer(123n)) }),
    });
    assert.equal(res.status, 404);
    const body = await res.json();
    assert.equal(body.error, 'challenge_not_found');
  });
});

test('verify with missing fields → 400 missing_fields', async () => {
  await withServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/admin/srp/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({}),
    });
    assert.equal(res.status, 400);
    const body = await res.json();
    assert.equal(body.error, 'missing_fields');
  });
});

test('challenge_id is one-shot: second verify with same id → 404', async () => {
  await withServer(async (baseUrl) => {
    const { a, A } = clientGenerateEphemeral();
    const challenge = await srpChallenge(baseUrl, A);
    const saltBytes = Buffer.from(challenge.body.salt, 'base64');
    const BBig = bufferToBigint(b64urlDecode(challenge.body.B));
    const x = computeX(saltBytes, SRP_USERNAME, PASSWORD);
    const { M1 } = clientComputeSecretAndProof(a, x, A, BBig, SRP_USERNAME, saltBytes);
    const v1 = await srpVerify(baseUrl, challenge.body.challenge_id, M1);
    assert.equal(v1.status, 200);
    const v2 = await srpVerify(baseUrl, challenge.body.challenge_id, M1);
    assert.equal(v2.status, 404);
  });
});

test('replay with wrong M1 (different M1 than expected) → 401', async () => {
  await withServer(async (baseUrl) => {
    const { A } = clientGenerateEphemeral();
    const challenge = await srpChallenge(baseUrl, A);
    // Send a bogus M1 (not the correct value).
    const v = await srpVerify(baseUrl, challenge.body.challenge_id, 987654321n);
    assert.equal(v.status, 401);
    assert.equal(v.body.error, 'invalid_credentials');
  });
});

test('handshake with wrong password produces 401', async () => {
  await withServer(async (baseUrl) => {
    const { a, A } = clientGenerateEphemeral();
    const challenge = await srpChallenge(baseUrl, A);
    const saltBytes = Buffer.from(challenge.body.salt, 'base64');
    const BBig = bufferToBigint(b64urlDecode(challenge.body.B));
    // x computed with WRONG password.
    const x = computeX(saltBytes, SRP_USERNAME, 'wrong-password');
    const { M1 } = clientComputeSecretAndProof(a, x, A, BBig, SRP_USERNAME, saltBytes);
    const v = await srpVerify(baseUrl, challenge.body.challenge_id, M1);
    assert.equal(v.status, 401);
    assert.equal(v.body.error, 'invalid_credentials');
  });
});

test('logged-in session can access /admin/versions', async () => {
  await withServer(async (baseUrl) => {
    const { a, A } = clientGenerateEphemeral();
    const challenge = await srpChallenge(baseUrl, A);
    const saltBytes = Buffer.from(challenge.body.salt, 'base64');
    const BBig = bufferToBigint(b64urlDecode(challenge.body.B));
    const x = computeX(saltBytes, SRP_USERNAME, PASSWORD);
    const { M1 } = clientComputeSecretAndProof(a, x, A, BBig, SRP_USERNAME, saltBytes);
    const v = await srpVerify(baseUrl, challenge.body.challenge_id, M1);
    const cookie = /sp_admin_session=([^;]+)/.exec(v.headers.get('set-cookie'))[1];
    const versionsRes = await fetch(`${baseUrl}/admin/versions`, {
      headers: { Cookie: `sp_admin_session=${cookie}` },
    });
    assert.equal(versionsRes.status, 200);
    const body = await versionsRes.json();
    assert.ok(Array.isArray(body.versions));
  });
});

test('SRP not configured: /admin/srp/* returns 503', async () => {
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'us-srp-none-'));
  const server = createUpdateServer({
    dataDir: tempDir,
    publicBaseUrl: 'https://x.test',
    sessionSecret: 'a'.repeat(32),
    // no srpVerifierFile
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const baseUrl = `http://127.0.0.1:${server.address().port}`;
  try {
    const { A } = clientGenerateEphemeral();
    const res = await fetch(`${baseUrl}/admin/srp/challenge`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: SRP_USERNAME, A: b64urlEncode(padToBuffer(A)) }),
    });
    assert.equal(res.status, 503);
    const body = await res.json();
    assert.equal(body.error, 'srp_not_configured');
  } finally {
    await new Promise((r) => server.close(r));
    server.closeDatabase();
    await fs.rm(tempDir, { recursive: true, force: true });
  }
});

test('legacy POST /admin/login returns 410 Gone', async () => {
  await withServer(async (baseUrl) => {
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

test('fresh A/B per handshake: two logins use different ephemerals', async () => {
  await withServer(async (baseUrl) => {
    const As = [];
    const Bs = [];
    for (let i = 0; i < 2; i += 1) {
      const { a, A } = clientGenerateEphemeral();
      const challenge = await srpChallenge(baseUrl, A);
      const saltBytes = Buffer.from(challenge.body.salt, 'base64');
      const BBig = bufferToBigint(b64urlDecode(challenge.body.B));
      const x = computeX(saltBytes, SRP_USERNAME, PASSWORD);
      const { M1 } = clientComputeSecretAndProof(a, x, A, BBig, SRP_USERNAME, saltBytes);
      const v = await srpVerify(baseUrl, challenge.body.challenge_id, M1);
      assert.equal(v.status, 200);
      As.push(A);
      Bs.push(BBig);
    }
    assert.notEqual(As[0], As[1], 'A differs across handshakes');
    assert.notEqual(Bs[0], Bs[1], 'B differs across handshakes');
  });
});
