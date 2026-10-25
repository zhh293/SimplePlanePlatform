import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { createUpdateServer } from '../src/server.js';
import { insertVersion, publishVersion as publishInDb, getLatestVersion } from '../src/db.js';
import {
  SRP_USERNAME, computeVerifier, computeX,
  clientGenerateEphemeral, clientComputeSecretAndProof,
} from '../src/srp.js';
import { b64urlEncode, b64urlDecode, bufferToBigint, padToBuffer } from '../src/srp-group.js';

const PASSWORD = 'test-admin-pw-2026';

async function withUploadServer(fn, overrides = {}) {
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'us-up-'));
  // Generate SRP verifier before starting server.
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
    defaultChunkSize: 1024,
    ...overrides,
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const baseUrl = `http://127.0.0.1:${server.address().port}`;
  try {
    await fn(baseUrl, server, tempDir);
  } finally {
    await new Promise((resolve) => server.close(resolve));
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
  const match = /sp_admin_session=([^;]+)/.exec(cookie);
  return match[1];
}

function authed(cookie, options) {
  return { ...options, headers: { ...(options?.headers || {}), Cookie: `sp_admin_session=${cookie}` } };
}

async function createSession(baseUrl, cookie, body) {
  return fetch(`${baseUrl}/admin/uploads`, authed(cookie, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}

async function patchChunk(baseUrl, cookie, uploadId, field, offset, data) {
  return fetch(`${baseUrl}/admin/uploads/${uploadId}/${field}`, authed(cookie, {
    method: 'PATCH',
    headers: { 'upload-offset': String(offset), 'Content-Type': 'application/octet-stream' },
    body: data,
  }));
}

async function headOffset(baseUrl, cookie, uploadId, field) {
  return fetch(`${baseUrl}/admin/uploads/${uploadId}/${field}`, authed(cookie, { method: 'HEAD' }));
}

function makePackage(size, seed = 'simpleplane-test') {
  const buf = Buffer.alloc(size);
  for (let i = 0; i < size; i += 1) {
    buf[i] = (i + seed.charCodeAt(i % seed.length)) & 0xff;
  }
  return buf;
}

function fakeSignature(buf) {
  return Buffer.from(crypto.createHash('sha256').update(buf).digest()).toString('base64');
}

async function uploadFullPackage(baseUrl, cookie, session, packageBuf, sigBuf) {
  const pkgOffset = 0;
  let sigOffset = 0;
  let offset = pkgOffset;
  const chunkSize = 4096;
  while (offset < packageBuf.length) {
    const end = Math.min(offset + chunkSize, packageBuf.length);
    const res = await patchChunk(baseUrl, cookie, session.upload_id, 'package', offset, packageBuf.subarray(offset, end));
    assert.equal(res.status, 204);
    offset = Number(res.headers.get('upload-offset'));
  }
  const sigRes = await patchChunk(baseUrl, cookie, session.upload_id, 'signature', sigOffset, sigBuf);
  assert.equal(sigRes.status, 204);
  return offset;
}

async function completeUpload(baseUrl, cookie, uploadId) {
  return fetch(`${baseUrl}/admin/uploads/${uploadId}/complete`, authed(cookie, { method: 'POST' }));
}

async function publishVersion(baseUrl, cookie, versionId) {
  return fetch(`${baseUrl}/admin/versions/${versionId}/publish`, authed(cookie, { method: 'POST' }));
}

function defaultSessionBody(overrides = {}) {
  const fileBuf = overrides.packageBuf ?? makePackage(10_000);
  const sigBuf = overrides.signatureBuf ?? Buffer.from(fakeSignature(fileBuf), 'utf8');
  const version = overrides.version || '0.2.3';
  const platform = overrides.platform || 'windows-x86_64';
  const fileName = overrides.file_name || (platform === 'darwin-aarch64'
    ? `SimplePlane_${version}_aarch64.app.tar.gz`
    : `SimplePlane_${version}_x64-setup.nsis.zip`);
  const signatureName = overrides.signature_name || `${fileName}.sig`;
  return {
    body: {
      platform,
      version,
      file_name: fileName,
      file_size: overrides.file_size ?? fileBuf.length,
      signature_name: signatureName,
      signature_size: overrides.signature_size ?? sigBuf.length,
      ...(overrides.sha256 ? { sha256: overrides.sha256 } : {}),
    },
    packageBuf: fileBuf,
    signatureBuf: sigBuf,
  };
}

test('create upload session returns 201 with chunk size', async () => {
  await withUploadServer(async (baseUrl, server) => {
    const cookie = await login(baseUrl, server);
    const { body } = defaultSessionBody();
    const res = await createSession(baseUrl, cookie, body);
    assert.equal(res.status, 201);
    const json = await res.json();
    assert.equal(json.package_chunk_size, 1024);
    assert.equal(json.package_offset, 0);
    assert.match(json.upload_id, /^u_/);
  });
});

test('create upload session rejects version ≤ current latest with 409', async () => {
  await withUploadServer(async (baseUrl, server) => {
    insertVersion(server.ctx.db, {
      id: 'v_existing', platform: 'windows-x86_64', version: '0.2.5',
      file_name: 'SimplePlane_0.2.5_x64-setup.nsis.zip', file_size: 100,
      file_sha256: 'abc', signature: 'sig', created_at: '2026-10-22T00:00:00Z',
    });
    publishInDb(server.ctx.db, 'v_existing');
    const cookie = await login(baseUrl, server);
    const { body } = defaultSessionBody({ version: '0.2.3' });
    const res = await createSession(baseUrl, cookie, body);
    assert.equal(res.status, 409);
    const json = await res.json();
    assert.equal(json.error, 'version_older_than_latest');
  });
});

test('create upload session rejects duplicate (platform, version) with 409', async () => {
  await withUploadServer(async (baseUrl, server) => {
    insertVersion(server.ctx.db, {
      id: 'v_existing', platform: 'windows-x86_64', version: '0.2.4',
      file_name: 'SimplePlane_0.2.4_x64-setup.nsis.zip', file_size: 100,
      file_sha256: 'abc', signature: 'sig', created_at: '2026-10-22T00:00:00Z',
    });
    publishInDb(server.ctx.db, 'v_existing');
    const cookie = await login(baseUrl, server);
    // Same version 0.2.4 → duplicate (even though equal to latest)
    const { body } = defaultSessionBody({ version: '0.2.4', file_name: 'SimplePlane_0.2.4_x64-setup.nsis.zip' });
    const res = await createSession(baseUrl, cookie, body);
    assert.equal(res.status, 409);
    const json = await res.json();
    assert.equal(json.error, 'version_already_exists');
  });
});

test('create upload session rejects unsupported platform with 400', async () => {
  await withUploadServer(async (baseUrl) => {
    const cookie = await login(baseUrl);
    const { body } = defaultSessionBody({ platform: 'linux-x86_64' });
    const res = await createSession(baseUrl, cookie, body);
    assert.equal(res.status, 400);
  });
});

test('create upload session rejects signature name not paired with package with 422', async () => {
  await withUploadServer(async (baseUrl) => {
    const cookie = await login(baseUrl);
    const { body } = defaultSessionBody({ signature_name: 'mismatch.sig', signature_size: 32 });
    const res = await createSession(baseUrl, cookie, body);
    assert.equal(res.status, 422);
  });
});

test('HEAD returns current offsets', async () => {
  await withUploadServer(async (baseUrl, server) => {
    const cookie = await login(baseUrl, server);
    const { body, packageBuf } = defaultSessionBody();
    const createRes = await createSession(baseUrl, cookie, body);
    const session = await createRes.json();
    const head = await headOffset(baseUrl, cookie, session.upload_id, 'package');
    assert.equal(head.status, 200);
    assert.equal(head.headers.get('upload-offset'), '0');
    assert.equal(head.headers.get('upload-length'), String(packageBuf.length));
  });
});

test('PATCH with wrong offset returns 416', async () => {
  await withUploadServer(async (baseUrl) => {
    const cookie = await login(baseUrl);
    const { body, packageBuf } = defaultSessionBody();
    const createRes = await createSession(baseUrl, cookie, body);
    const session = await createRes.json();
    const res = await patchChunk(baseUrl, cookie, session.upload_id, 'package', 999, packageBuf.subarray(0, 10));
    assert.equal(res.status, 416);
  });
});

test('full upload → complete → publish flow', async () => {
  await withUploadServer(async (baseUrl, server) => {
    const cookie = await login(baseUrl, server);
    const { body, packageBuf, signatureBuf } = defaultSessionBody();
    const createRes = await createSession(baseUrl, cookie, body);
    const session = await createRes.json();
    await uploadFullPackage(baseUrl, cookie, session, packageBuf, signatureBuf);
    const completeRes = await completeUpload(baseUrl, cookie, session.upload_id);
    assert.equal(completeRes.status, 200);
    const complete = await completeRes.json();
    assert.equal(complete.signature_valid, true);
    assert.equal(complete.published, false);

    const pubRes = await publishVersion(baseUrl, cookie, complete.version_id);
    assert.equal(pubRes.status, 200);
    const pub = await pubRes.json();
    assert.equal(pub.is_latest, true);
    assert.equal(pub.platform, 'windows-x86_64');
    assert.equal(pub.version, '0.2.3');

    // Public endpoint should now see it.
    const update = await fetch(`${baseUrl}/api/v1/updates/windows/x86_64/0.2.2`);
    assert.equal(update.status, 200);
    const updateBody = await update.json();
    assert.equal(updateBody.version, '0.2.3');

    const noUpdate = await fetch(`${baseUrl}/api/v1/updates/windows/x86_64/0.2.3`);
    assert.equal(noUpdate.status, 204);
  });
});

test('resume after interruption (HEAD offset → continue PATCH)', async () => {
  await withUploadServer(async (baseUrl, server) => {
    const cookie = await login(baseUrl, server);
    const { body, packageBuf, signatureBuf } = defaultSessionBody();
    const createRes = await createSession(baseUrl, cookie, body);
    const session = await createRes.json();

    // Send first 3 chunks
    const chunkSize = 4096;
    let offset = 0;
    for (let i = 0; i < 3 && offset < packageBuf.length; i += 1) {
      const end = Math.min(offset + chunkSize, packageBuf.length);
      const res = await patchChunk(baseUrl, cookie, session.upload_id, 'package', offset, packageBuf.subarray(offset, end));
      assert.equal(res.status, 204);
      offset = Number(res.headers.get('upload-offset'));
    }

    // Simulate interruption: query HEAD to get current offset
    const head = await headOffset(baseUrl, cookie, session.upload_id, 'package');
    const resumeOffset = Number(head.headers.get('upload-offset'));
    assert.equal(resumeOffset, offset);

    // Continue from resumeOffset
    while (offset < packageBuf.length) {
      const end = Math.min(offset + chunkSize, packageBuf.length);
      const res = await patchChunk(baseUrl, cookie, session.upload_id, 'package', offset, packageBuf.subarray(offset, end));
      assert.equal(res.status, 204);
      offset = Number(res.headers.get('upload-offset'));
    }

    // Upload signature
    const sigRes = await patchChunk(baseUrl, cookie, session.upload_id, 'signature', 0, signatureBuf);
    assert.equal(sigRes.status, 204);

    const completeRes = await completeUpload(baseUrl, cookie, session.upload_id);
    assert.equal(completeRes.status, 200);
    const complete = await completeRes.json();
    assert.equal(complete.signature_valid, true);
  });
});

test('complete without uploading returns 409 upload_incomplete', async () => {
  await withUploadServer(async (baseUrl, server) => {
    const cookie = await login(baseUrl, server);
    const { body } = defaultSessionBody();
    const createRes = await createSession(baseUrl, cookie, body);
    const session = await createRes.json();
    const res = await completeUpload(baseUrl, cookie, session.upload_id);
    assert.equal(res.status, 409);
    const json = await res.json();
    assert.equal(json.error, 'upload_incomplete');
  });
});

test('complete with wrong sha256 returns 422 file_checksum_mismatch', async () => {
  await withUploadServer(async (baseUrl, server) => {
    const cookie = await login(baseUrl, server);
    const { body, packageBuf, signatureBuf } = defaultSessionBody();
    body.sha256 = 'a'.repeat(64); // wrong sha256
    const createRes = await createSession(baseUrl, cookie, body);
    const session = await createRes.json();
    await uploadFullPackage(baseUrl, cookie, session, packageBuf, signatureBuf);
    const res = await completeUpload(baseUrl, cookie, session.upload_id);
    assert.equal(res.status, 422);
    const json = await res.json();
    assert.equal(json.error, 'file_checksum_mismatch');
  });
});

test('publish on already-latest is idempotent', async () => {
  await withUploadServer(async (baseUrl, server) => {
    const cookie = await login(baseUrl, server);
    const { body, packageBuf, signatureBuf } = defaultSessionBody();
    const createRes = await createSession(baseUrl, cookie, body);
    const session = await createRes.json();
    await uploadFullPackage(baseUrl, cookie, session, packageBuf, signatureBuf);
    const complete = await completeUpload(baseUrl, cookie, session.upload_id).then((r) => r.json());
    const pub1 = await publishVersion(baseUrl, cookie, complete.version_id).then((r) => r.json());
    assert.equal(pub1.already_latest, false);
    const pub2 = await publishVersion(baseUrl, cookie, complete.version_id).then((r) => r.json());
    assert.equal(pub2.already_latest, true);
  });
});

test('publish blocks version ≤ current latest', async () => {
  await withUploadServer(async (baseUrl, server) => {
    const cookie = await login(baseUrl, server);
    // Publish 0.2.5 first
    const { body: b25, packageBuf: p25, signatureBuf: s25 } = defaultSessionBody({ body: { version: '0.2.5' } });
    b25.version = '0.2.5';
    const sess25 = await createSession(baseUrl, cookie, b25).then((r) => r.json());
    await uploadFullPackage(baseUrl, cookie, sess25, p25, s25);
    const c25 = await completeUpload(baseUrl, cookie, sess25.upload_id).then((r) => r.json());
    await publishVersion(baseUrl, cookie, c25.version_id);

    // Now upload 0.2.3 (less than 0.2.5) - should fail at session create
    const { body: b23, packageBuf: p23, signatureBuf: s23 } = defaultSessionBody({ body: { version: '0.2.3' } });
    b23.version = '0.2.3';
    const res = await createSession(baseUrl, cookie, b23);
    assert.equal(res.status, 409);
    assert.equal((await res.json()).error, 'version_older_than_latest');
  });
});

test('active upload sessions listed', async () => {
  await withUploadServer(async (baseUrl, server) => {
    const cookie = await login(baseUrl, server);
    const { body } = defaultSessionBody();
    await createSession(baseUrl, cookie, body);
    const res = await fetch(`${baseUrl}/admin/uploads`, authed(cookie, {}));
    assert.equal(res.status, 200);
    const json = await res.json();
    assert.ok(Array.isArray(json.uploads));
    assert.equal(json.uploads.length, 1);
  });
});
