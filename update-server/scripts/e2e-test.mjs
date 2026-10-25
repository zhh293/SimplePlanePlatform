#!/usr/bin/env node

import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { createUpdateServer } from '../src/server.js';
import {
  SRP_USERNAME, computeVerifier, computeX,
  clientGenerateEphemeral, clientComputeSecretAndProof,
} from '../src/srp.js';
import { b64urlEncode, b64urlDecode, bufferToBigint, padToBuffer } from '../src/srp-group.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PASSWORD = 'e2e-admin-pw-2026';

function log(msg) {
  console.log(`[e2e] ${msg}`);
}

async function writeVerifier(tempDir, password) {
  const salt = crypto.randomBytes(16);
  const v = computeVerifier(salt, SRP_USERNAME, password);
  const verifierFile = path.join(tempDir, 'verifier.json');
  await fs.writeFile(verifierFile, JSON.stringify({
    username: SRP_USERNAME,
    salt: salt.toString('base64'),
    verifier: b64urlEncode(padToBuffer(v)),
    kdf: 'sha256-double-h',
    created_at: new Date().toISOString(),
  }));
  return { verifierFile, salt, v };
}

async function startServer(tempDir) {
  const { verifierFile } = await writeVerifier(tempDir, PASSWORD);
  const server = createUpdateServer({
    dataDir: tempDir,
    publicBaseUrl: 'https://updates.example.test',
    sessionSecret: 'e2e-session-secret-' + 'a'.repeat(20),
    srpVerifierFile: verifierFile,
    defaultChunkSize: 8 * 1024,
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const baseUrl = `http://127.0.0.1:${server.address().port}`;
  return { server, baseUrl };
}

async function login(baseUrl, password = PASSWORD) {
  try {
    const { a, A } = clientGenerateEphemeral();
    const challengeRes = await fetch(`${baseUrl}/admin/srp/challenge`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: SRP_USERNAME, A: b64urlEncode(padToBuffer(A)) }),
    });
    if (challengeRes.status !== 200) return { ok: false, status: challengeRes.status };
    const { challenge_id, salt, B } = await challengeRes.json();
    const saltBytes = Buffer.from(salt, 'base64');
    const BBig = bufferToBigint(b64urlDecode(B));
    const x = computeX(saltBytes, SRP_USERNAME, password);
    const { M1 } = clientComputeSecretAndProof(a, x, A, BBig, SRP_USERNAME, saltBytes);
    const verifyRes = await fetch(`${baseUrl}/admin/srp/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ challenge_id, M1: b64urlEncode(padToBuffer(M1)) }),
    });
    if (verifyRes.status !== 200) return { ok: false, status: verifyRes.status };
    const cookie = verifyRes.headers.get('set-cookie');
    const match = /sp_admin_session=([^;]+)/.exec(cookie);
    return { ok: true, status: 200, cookie: match[1] };
  } catch (err) {
    return { ok: false, status: -1, error: err.message };
  }
}

function authed(cookie, options = {}) {
  return {
    ...options,
    headers: { ...(options?.headers || {}), Cookie: `sp_admin_session=${cookie}` },
  };
}

function makePackage(size, seed = 'e2e') {
  const buf = Buffer.alloc(size);
  for (let i = 0; i < size; i += 1) {
    buf[i] = (i + seed.charCodeAt(i % seed.length)) & 0xff;
  }
  return buf;
}

function fakeSignature(buf) {
  return Buffer.from(crypto.createHash('sha256').digest(buf).toString('base64'), 'utf8');
}

function sha256OfBuf(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
}

async function createSession(baseUrl, cookie, body) {
  const res = await fetch(`${baseUrl}/admin/uploads`, authed(cookie, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
  return { status: res.status, body: await res.json().catch(() => ({})) };
}

async function patchChunk(baseUrl, cookie, uploadId, field, offset, chunk) {
  const res = await fetch(`${baseUrl}/admin/uploads/${uploadId}/${field}`, authed(cookie, {
    method: 'PATCH',
    headers: { 'upload-offset': String(offset) },
    body: chunk,
  }));
  return { status: res.status, offset: Number(res.headers.get('upload-offset') || 0) };
}

async function uploadFull(baseUrl, cookie, uploadId, packageBuf, sigBuf, chunkSize = 4096) {
  let offset = 0;
  while (offset < packageBuf.length) {
    const end = Math.min(offset + chunkSize, packageBuf.length);
    const r = await patchChunk(baseUrl, cookie, uploadId, 'package', offset, packageBuf.subarray(offset, end));
    if (r.status !== 204) throw new Error(`package PATCH failed: ${r.status}`);
    offset = r.offset;
  }
  const sigR = await patchChunk(baseUrl, cookie, uploadId, 'signature', 0, sigBuf);
  if (sigR.status !== 204) throw new Error(`signature PATCH failed: ${sigR.status}`);
}

async function completeUpload(baseUrl, cookie, uploadId) {
  const res = await fetch(`${baseUrl}/admin/uploads/${uploadId}/complete`, authed(cookie, { method: 'POST' }));
  return { status: res.status, body: await res.json().catch(() => ({})) };
}

async function publishVersion(baseUrl, cookie, versionId) {
  const res = await fetch(`${baseUrl}/admin/versions/${versionId}/publish`, authed(cookie, { method: 'POST' }));
  return { status: res.status, body: await res.json().catch(() => ({})) };
}

async function getUpdate(baseUrl, target, arch, current) {
  const res = await fetch(`${baseUrl}/api/v1/updates/${target}/${arch}/${current}`);
  if (res.status === 204) return { status: 204, body: null };
  return { status: res.status, body: await res.json().catch(() => ({})) };
}

async function deleteVersion(baseUrl, cookie, id) {
  const res = await fetch(`${baseUrl}/admin/versions/${id}`, authed(cookie, { method: 'DELETE' }));
  return res.status;
}

async function patchNotes(baseUrl, cookie, id, notes) {
  const res = await fetch(`${baseUrl}/admin/versions/${id}`, authed(cookie, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ notes }),
  }));
  return { status: res.status, body: await res.json().catch(() => ({})) };
}

async function main() {
  const evidence = {
    timestamp: new Date().toISOString(),
    node_version: process.version,
    platform: process.platform,
    scenarios: {},
  };

  let failures = 0;
  const check = (scenario, condition, detail) => {
    evidence.scenarios[scenario] = { pass: !!condition, detail };
    if (condition) log(`✓ ${scenario}`);
    else { log(`✖ ${scenario}`); failures += 1; }
  };

  let server = null;
  let baseUrl;
  let tempDir = null;

  try {
    // Phase A: security scenarios on first server.
    tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'us-e2e-a-'));
    ({ server, baseUrl } = await startServer(tempDir));
    log(`server A listening at ${baseUrl}`);

    // Scenario 1: login.
    const loginOk = await login(baseUrl);
    check('login_success', loginOk.ok, `status=${loginOk.status}`);

    // Scenario 2: unauthenticated admin access blocked.
    const unauthRes = await fetch(`${baseUrl}/admin/versions`);
    check('unauth_blocked', unauthRes.status === 401, `status=${unauthRes.status}`);

    // Scenario 3: 5 wrong passwords → lockout.
    let locked = false;
    for (let i = 0; i < 5; i += 1) {
      const r = await login(baseUrl, 'wrong');
      if (r.status === 429) locked = true;
    }
    check('login_lockout_429', locked, '5 wrong attempts triggered 429');
    const lockedCorrect = await login(baseUrl, PASSWORD);
    check('login_correct_blocked_during_lockout', !lockedCorrect.ok, `status=${lockedCorrect.status}`);

    // Tear down server A.
    await new Promise((r) => server.close(r));
    server.closeDatabase();
    await fs.rm(tempDir, { recursive: true, force: true });
    server = null;
    tempDir = null;

    // Phase B: full CRUD + dual platform + monotonic on second server.
    tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'us-e2e-b-'));
    ({ server, baseUrl } = await startServer(tempDir));
    log(`server B listening at ${baseUrl}`);

    const l2 = await login(baseUrl);
    const cookie = l2.cookie;

    // Scenario 4: Windows 0.2.3 — create, partial upload, HEAD resume, complete, publish.
    const pkgWin = makePackage(50_000, 'win-0.2.3');
    const sigWin = fakeSignature(pkgWin);
    const shaWin = sha256OfBuf(pkgWin);
    const createWin = await createSession(baseUrl, cookie, {
      platform: 'windows-x86_64', version: '0.2.3',
      file_name: 'SimplePlane_0.2.3_x64-setup.nsis.zip', file_size: pkgWin.length,
      signature_name: 'SimplePlane_0.2.3_x64-setup.nsis.zip.sig', signature_size: sigWin.length,
      sha256: shaWin,
    });
    check('create_session_win_0.2.3', createWin.status === 201, `status=${createWin.status}`);

    const cs = createWin.body;
    const chunkSize = 4096;
    let offset = 0;
    for (let i = 0; i < 3 && offset < pkgWin.length; i += 1) {
      const end = Math.min(offset + chunkSize, pkgWin.length);
      const r = await patchChunk(baseUrl, cookie, cs.upload_id, 'package', offset, pkgWin.subarray(offset, end));
      offset = r.offset;
    }
    const headRes = await fetch(`${baseUrl}/admin/uploads/${cs.upload_id}/package`, authed(cookie, { method: 'HEAD' }));
    const resumeOffset = Number(headRes.headers.get('upload-offset'));
    check('resume_offset_after_partial_upload', resumeOffset === offset, `expected=${offset} actual=${resumeOffset}`);

    while (offset < pkgWin.length) {
      const end = Math.min(offset + chunkSize, pkgWin.length);
      const r = await patchChunk(baseUrl, cookie, cs.upload_id, 'package', offset, pkgWin.subarray(offset, end));
      offset = r.offset;
    }
    const sigR = await patchChunk(baseUrl, cookie, cs.upload_id, 'signature', 0, sigWin);
    check('signature_upload_complete', sigR.status === 204, `status=${sigR.status}`);

    const comp = await completeUpload(baseUrl, cookie, cs.upload_id);
    check('complete_success', comp.status === 200 && comp.body.signature_valid === true, `status=${comp.status}`);
    check('complete_sha256_matches', comp.body.file_sha256 === shaWin, `expected=${shaWin} actual=${comp.body.file_sha256}`);

    const pub1 = await publishVersion(baseUrl, cookie, comp.body.version_id);
    check('publish_win_0.2.3', pub1.status === 200 && pub1.body.is_latest === true, `status=${pub1.status}`);

    const upd = await getUpdate(baseUrl, 'windows', 'x86_64', '0.2.2');
    check('client_sees_0.2.3', upd.status === 200 && upd.body?.version === '0.2.3', `status=${upd.status}`);

    const noUpd = await getUpdate(baseUrl, 'windows', 'x86_64', '0.2.3');
    check('client_no_update_when_current', noUpd.status === 204, `status=${noUpd.status}`);

    // Scenario 5: macOS 0.2.4 independent.
    const pkgMac = makePackage(40_000, 'mac-0.2.4');
    const sigMac = fakeSignature(pkgMac);
    const createMac = await createSession(baseUrl, cookie, {
      platform: 'darwin-aarch64', version: '0.2.4',
      file_name: 'SimplePlane_0.2.4_aarch64.app.tar.gz', file_size: pkgMac.length,
      signature_name: 'SimplePlane_0.2.4_aarch64.app.tar.gz.sig', signature_size: sigMac.length,
      sha256: sha256OfBuf(pkgMac),
    });
    check('create_session_mac_0.2.4', createMac.status === 201, `status=${createMac.status}`);
    await uploadFull(baseUrl, cookie, createMac.body.upload_id, pkgMac, sigMac);
    const compMac = await completeUpload(baseUrl, cookie, createMac.body.upload_id);
    const pubMac = await publishVersion(baseUrl, cookie, compMac.body.version_id);
    check('publish_mac_0.2.4', pubMac.status === 200 && pubMac.body.platform === 'darwin-aarch64', `status=${pubMac.status}`);

    const updWin = await getUpdate(baseUrl, 'windows', 'x86_64', '0.2.2');
    const updMac = await getUpdate(baseUrl, 'darwin', 'aarch64', '0.2.3');
    check('dual_platform_independence_win', updWin.body?.version === '0.2.3', `version=${updWin.body?.version}`);
    check('dual_platform_independence_mac', updMac.body?.version === '0.2.4', `version=${updMac.body?.version}`);

    // Scenario 6: monotonic — uploading Windows 0.2.2 (≤ 0.2.3) must be rejected.
    const dupCreate = await createSession(baseUrl, cookie, {
      platform: 'windows-x86_64', version: '0.2.2',
      file_name: 'SimplePlane_0.2.2_x64-setup.nsis.zip', file_size: 1000,
      signature_name: 'SimplePlane_0.2.2_x64-setup.nsis.zip.sig', signature_size: 32,
    });
    check('monotonic_rejects_older_version', dupCreate.status === 409 && dupCreate.body.error === 'version_older_than_latest', `status=${dupCreate.status} err=${dupCreate.body?.error}`);

    // Scenario 7: delete current latest → 409.
    const delLatest = await deleteVersion(baseUrl, cookie, comp.body.version_id);
    check('delete_latest_409', delLatest === 409, `status=${delLatest}`);

    // Scenario 8: upload Windows 0.2.5 (> 0.2.3), don't publish, delete as non-latest.
    const oldPkg = makePackage(20_000, 'win-0.2.5');
    const oldSig = fakeSignature(oldPkg);
    const oldCreate = await createSession(baseUrl, cookie, {
      platform: 'windows-x86_64', version: '0.2.5',
      file_name: 'SimplePlane_0.2.5_x64-setup.nsis.zip', file_size: oldPkg.length,
      signature_name: 'SimplePlane_0.2.5_x64-setup.nsis.zip.sig', signature_size: oldSig.length,
    });
    await uploadFull(baseUrl, cookie, oldCreate.body.upload_id, oldPkg, oldSig);
    const oldComp = await completeUpload(baseUrl, cookie, oldCreate.body.upload_id);
    const delOld = await deleteVersion(baseUrl, cookie, oldComp.body.version_id);
    check('delete_non_latest_204', delOld === 204, `status=${delOld}`);

    // Scenario 9: upload + publish 0.2.6, patch notes.
    const patchPkg = makePackage(15_000, 'win-0.2.6');
    const patchSig = fakeSignature(patchPkg);
    const patchCreate = await createSession(baseUrl, cookie, {
      platform: 'windows-x86_64', version: '0.2.6',
      file_name: 'SimplePlane_0.2.6_x64-setup.nsis.zip', file_size: patchPkg.length,
      signature_name: 'SimplePlane_0.2.6_x64-setup.nsis.zip.sig', signature_size: patchSig.length,
    });
    await uploadFull(baseUrl, cookie, patchCreate.body.upload_id, patchPkg, patchSig);
    const patchComp = await completeUpload(baseUrl, cookie, patchCreate.body.upload_id);
    await publishVersion(baseUrl, cookie, patchComp.body.version_id);
    const patchRes = await patchNotes(baseUrl, cookie, patchComp.body.version_id, '修复连接问题，提升稳定性');
    check('patch_notes_success', patchRes.status === 200 && patchRes.body.notes === '修复连接问题，提升稳定性', `status=${patchRes.status}`);

    // Scenario 10: patch with disallowed field → 422.
    const patchBad = await fetch(`${baseUrl}/admin/versions/${patchComp.body.version_id}`, authed(cookie, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ version: '0.2.99' }),
    }));
    check('patch_immutable_field_422', patchBad.status === 422, `status=${patchBad.status}`);

    // Scenario 11: download endpoint serves package + correct cache headers.
    const dlRes = await fetch(`${baseUrl}/download/windows-x86_64/0.2.3/SimplePlane_0.2.3_x64-setup.nsis.zip`);
    check('download_serves_package', dlRes.status === 200 && Number(dlRes.headers.get('content-length')) === pkgWin.length, `status=${dlRes.status}`);
    check('download_cache_headers', dlRes.headers.get('cache-control') === 'public, max-age=31536000, immutable', `cache-control=${dlRes.headers.get('cache-control')}`);

    // Scenario 12: /latest.json returns both platforms.
    const latestRes = await fetch(`${baseUrl}/latest.json`);
    const latestBody = await latestRes.json();
    check('latest_json_has_both_platforms', Object.keys(latestBody.platforms || {}).length === 2, `platforms=${Object.keys(latestBody.platforms || {}).join(',')}`);

    // --- SRP-specific scenarios ---
    // Scenario 13: /admin/srp/params returns N, g, hash, username.
    const paramsRes = await fetch(`${baseUrl}/admin/srp/params`);
    const paramsBody = await paramsRes.json();
    check('srp_params_endpoint', paramsRes.status === 200 && paramsBody.g === 2 && paramsBody.hash === 'sha256' && paramsBody.username === 'admin' && typeof paramsBody.N === 'string' && paramsBody.N.length > 0, `status=${paramsRes.status}`);

    // Scenario 14: SRP challenge returns challenge_id + salt + B.
    const { a: aA, A: AA } = clientGenerateEphemeral();
    const chalRes = await fetch(`${baseUrl}/admin/srp/challenge`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: SRP_USERNAME, A: b64urlEncode(padToBuffer(AA)) }),
    });
    const chalBody = await chalRes.json();
    check('srp_challenge_returns_salt_B', chalRes.status === 200 && chalBody.challenge_id && chalBody.salt && chalBody.B, `status=${chalRes.status}`);

    // Scenario 15: replay same challenge_id → 404 (one-shot after first verify).
    // First verify with bogus M1 → consumes the challenge → 401.
    const firstVerify = await fetch(`${baseUrl}/admin/srp/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ challenge_id: chalBody.challenge_id, M1: b64urlEncode(padToBuffer(1n)) }),
    });
    // Second verify with same challenge_id → 404 (challenge was already consumed).
    const replayRes = await fetch(`${baseUrl}/admin/srp/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ challenge_id: chalBody.challenge_id, M1: b64urlEncode(padToBuffer(1n)) }),
    });
    check('srp_replay_blocked', replayRes.status === 404 && (await replayRes.json()).error === 'challenge_not_found', `status=${replayRes.status}`);

    // Scenario 16: wrong M1 (random bogus) → 401 invalid_credentials.
    const { a: aB, A: AB } = clientGenerateEphemeral();
    const chalB = await fetch(`${baseUrl}/admin/srp/challenge`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: SRP_USERNAME, A: b64urlEncode(padToBuffer(AB)) }),
    });
    const chalBBody = await chalB.json();
    const verifyBogus = await fetch(`${baseUrl}/admin/srp/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ challenge_id: chalBBody.challenge_id, M1: b64urlEncode(padToBuffer(999n)) }),
    });
    check('srp_wrong_M1_rejected_401', verifyBogus.status === 401 && (await verifyBogus.json()).error === 'invalid_credentials', `status=${verifyBogus.status}`);

    // Scenario 17: fresh A/B per handshake — two logins use different ephemerals.
    const As = [];
    const Bs = [];
    for (let i = 0; i < 2; i += 1) {
      const { a, A } = clientGenerateEphemeral();
      const c = await fetch(`${baseUrl}/admin/srp/challenge`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: SRP_USERNAME, A: b64urlEncode(padToBuffer(A)) }),
      });
      const cb = await c.json();
      As.push(A);
      Bs.push(bufferToBigint(b64urlDecode(cb.B)));
      const saltB = Buffer.from(cb.salt, 'base64');
      const x = computeX(saltB, SRP_USERNAME, PASSWORD);
      const { M1 } = clientComputeSecretAndProof(a, x, A, bufferToBigint(b64urlDecode(cb.B)), SRP_USERNAME, saltB);
      const v = await fetch(`${baseUrl}/admin/srp/verify`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ challenge_id: cb.challenge_id, M1: b64urlEncode(padToBuffer(M1)) }),
      });
      if (v.status !== 200) throw new Error(`SRP login ${i + 1} failed: ${v.status}`);
    }
    check('srp_fresh_ephemerals_per_handshake', As[0] !== As[1] && Bs[0] !== Bs[1], `A0==A1: ${As[0] === As[1]}, B0==B1: ${Bs[0] === Bs[1]}`);

    // Scenario 18: legacy POST /admin/login → 410 Gone.
    const legacyRes = await fetch(`${baseUrl}/admin/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: PASSWORD }),
    });
    check('srp_legacy_login_deprecated_410', legacyRes.status === 410 && (await legacyRes.json()).error === 'legacy_login_deprecated', `status=${legacyRes.status}`);
  } catch (err) {
    log(`unexpected error: ${err.message}`);
    console.error(err);
    failures += 1;
    evidence.fatal_error = err.message;
  } finally {
    if (server) {
      await new Promise((r) => server.close(r));
      server.closeDatabase();
    }
    if (tempDir) {
      await fs.rm(tempDir, { recursive: true, force: true });
    }
  }

  evidence.total_failures = failures;
  evidence.passed = failures === 0;
  const evidenceDir = path.resolve(__dirname, '..', '..', 'docs', 'sdd', 'update-server', 'evidence');
  await fs.mkdir(evidenceDir, { recursive: true });
  const evidenceFile = path.join(evidenceDir, 'e2e-result.json');
  await fs.writeFile(evidenceFile, JSON.stringify(evidence, null, 2));
  log(`evidence written to ${evidenceFile}`);
  log(failures === 0 ? 'all scenarios passed' : `${failures} scenario(s) failed`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error('[e2e] fatal:', err);
  process.exit(1);
});
