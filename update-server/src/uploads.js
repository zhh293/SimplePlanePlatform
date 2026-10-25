import fs from 'node:fs/promises';
import path from 'node:path';
import { json, noContent, errorResponse, readBody } from './util/http.js';
import { authenticateAdmin } from './auth.js';
import {
  assertSupportedPlatform,
  createUploadSession,
  findUploadSession,
  listActiveUploadSessions,
  updateUploadOffsets,
  markUploadSessionStatus,
  insertVersion,
  findVersionById,
  findVersionByPlatformAndVersion,
  getLatestVersion,
  publishVersion,
  insertAudit,
  findExpiredUploadSessions,
  deleteUploadSession,
  withTransaction,
} from './db.js';
import {
  safeSegment,
  validateFileName,
  signatureNameFor,
  isPairedSignature,
} from './util/path.js';
import {
  isBase64,
  sha256File,
  randomId,
} from './util/crypto.js';
import { parseVersion, isStrictlyGreater } from './util/version.js';
import { resolveReleaseFile } from './manifest.js';

const FILE_FIELD = 'file';
const SIG_FIELD = 'signature';
const CHUNK_HEADER = 'upload-offset';
const LENGTH_HEADER = 'upload-length';

async function ensureMonotonic(db, platform, newVersion) {
  const parsed = parseVersion(newVersion);
  if (!parsed) {
    const err = new Error(`Invalid version format: ${newVersion}`);
    err.statusCode = 400;
    err.code = 'invalid_version_format';
    throw err;
  }
  const existing = findVersionByPlatformAndVersion(db, platform, newVersion);
  if (existing) {
    const err = new Error(`Version ${newVersion} already exists for ${platform}`);
    err.statusCode = 409;
    err.code = 'version_already_exists';
    throw err;
  }
  const latest = getLatestVersion(db, platform);
  if (latest && !isStrictlyGreater(parsed, parseVersion(latest.version))) {
    const err = new Error(
      `new version ${newVersion} must be strictly greater than current latest ${latest.version} for platform ${platform}`,
    );
    err.statusCode = 409;
    err.code = 'version_older_than_latest';
    err.currentLatest = latest.version;
    err.attempted = newVersion;
    throw err;
  }
}

async function createUploadLayout(ctx, session) {
  const dir = path.join(ctx.uploadRoot, session.id);
  await fs.mkdir(dir, { recursive: true });
  await fs.writeFile(path.join(dir, 'package.part'), '');
  await fs.writeFile(path.join(dir, 'signature.part'), '');
}

async function appendChunk(filePath, offset, chunk) {
  const handle = await fs.open(filePath, 'r+');
  try {
    await handle.truncate(offset);
    await handle.write(chunk, 0, chunk.length, offset);
    await handle.sync();
  } finally {
    await handle.close();
  }
  return offset + chunk.length;
}

function isUploadRoute(segments) {
  return segments[0] === 'admin' && segments[1] === 'uploads';
}

async function cleanupExpiredSessions(ctx) {
  const now = new Date().toISOString();
  const expired = findExpiredUploadSessions(ctx.db, now);
  for (const row of expired) {
    try {
      await fs.rm(path.join(ctx.uploadRoot, row.id), { recursive: true, force: true });
    } catch (err) {
      console.warn(`[uploads] failed to cleanup ${row.id}:`, err.message);
    }
    markUploadSessionStatus(ctx.db, row.id, 'expired');
    insertAudit(ctx.db, { action: 'upload.session.expire', detail: `id=${row.id}` });
  }
}

export async function handleUploadRoutes(request, response, ctx) {
  const url = new URL(request.url, `http://${request.headers.host || 'localhost'}`);
  const segments = url.pathname.split('/').filter(Boolean).map(decodeURIComponent);
  if (!isUploadRoute(segments)) return false;

  let session;
  try {
    session = await authenticateAdmin(request, ctx);
  } catch (err) {
    errorResponse(response, err, 401);
    return true;
  }

  try {
    if (request.method === 'POST' && segments.length === 2) {
      return await handleCreateSession(request, response, ctx, session);
    }
    if (request.method === 'GET' && segments.length === 2) {
      const rows = listActiveUploadSessions(ctx.db);
      json(response, 200, { uploads: rows });
      return true;
    }

    if (request.method === 'POST' && segments.length === 4 && segments[3] === 'complete') {
      return await handleComplete(response, ctx, session, segments[2]);
    }

    if (segments.length === 4) {
      const [,, uploadId, fieldRaw] = segments;
      const field = fieldRaw === 'package' ? FILE_FIELD : fieldRaw === 'signature' ? SIG_FIELD : null;
      if (!field) {
        const err = new Error('not_found');
        err.statusCode = 404;
        err.code = 'not_found';
        throw err;
      }

      if (request.method === 'HEAD') {
        return await handleHeadOffset(response, ctx, uploadId, field);
      }
      if (request.method === 'PATCH') {
        return await handlePatchChunk(request, response, ctx, uploadId, field);
      }
    }

    const err = new Error('not_found');
    err.statusCode = 404;
    err.code = 'not_found';
    throw err;
  } catch (err) {
    errorResponse(response, err);
    return true;
  }
}

async function handleCreateSession(request, response, ctx, session) {
  const { parseJsonBody } = await import('./util/http.js');
  const body = await parseJsonBody(request, 64 * 1024);
  if (!body || typeof body !== 'object') {
    const err = new Error('Invalid JSON body');
    err.statusCode = 400;
    err.code = 'invalid_json';
    throw err;
  }
  const platform = assertSupportedPlatform(body.platform);
  const version = safeSegment(body.version, 'version');
  validateFileName(body.file_name, { requireExtension: true });
  if (typeof body.file_size !== 'number' || body.file_size <= 0 || body.file_size > ctx.maxFileSize) {
    const err = new Error(`file_size out of range (max ${ctx.maxFileSize})`);
    err.statusCode = 400;
    err.code = 'invalid_file_size';
    throw err;
  }
  const signatureName = body.signature_name || signatureNameFor(body.file_name);
  validateFileName(signatureName, { requireExtension: true });
  if (!isPairedSignature(body.file_name, signatureName)) {
    const err = new Error(`signature file name ${signatureName} must match ${body.file_name}.sig`);
    err.statusCode = 422;
    err.code = 'signature_mismatch';
    throw err;
  }
  if (typeof body.signature_size !== 'number' || body.signature_size <= 0 || body.signature_size > 64 * 1024) {
    const err = new Error('signature_size out of range (max 64 KiB)');
    err.statusCode = 400;
    err.code = 'invalid_signature_size';
    throw err;
  }
  if (body.sha256 !== undefined && (typeof body.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(body.sha256))) {
    const err = new Error('sha256 must be a 64-char hex string');
    err.statusCode = 400;
    err.code = 'invalid_sha256';
    throw err;
  }

  await cleanupExpiredSessions(ctx);

  await ensureMonotonic(ctx.db, platform, version);

  const active = listActiveUploadSessions(ctx.db);
  if (active.length >= ctx.maxActiveUploads) {
    const err = new Error(`Too many active upload sessions (max ${ctx.maxActiveUploads})`);
    err.statusCode = 429;
    err.code = 'too_many_uploads';
    throw err;
  }

  const id = randomId('u');
  const now = new Date();
  const expiresAt = new Date(now.getTime() + ctx.uploadSessionTtlMs);
  createUploadSession(ctx.db, {
    id,
    platform,
    version,
    file_name: body.file_name,
    file_size: body.file_size,
    file_sha256: body.sha256 || null,
    signature_name: signatureName,
    signature_size: body.signature_size,
    created_at: now.toISOString(),
    expires_at: expiresAt.toISOString(),
  });
  await createUploadLayout(ctx, { id });
  insertAudit(ctx.db, {
    actor: session.user || 'admin',
    action: 'upload.session.create',
    platform,
    version,
    detail: `id=${id}`,
  });

  json(response, 201, {
    upload_id: id,
    package_chunk_size: ctx.defaultChunkSize,
    package_offset: 0,
    signature_offset: 0,
    expires_at: expiresAt.toISOString(),
  });
  return true;
}

async function handleHeadOffset(response, ctx, uploadId, field) {
  const s = findUploadSession(ctx.db, uploadId);
  if (!s || s.status !== 'active') {
    const err = new Error('Upload session not found');
    err.statusCode = 404;
    err.code = 'upload_not_found';
    throw err;
  }
  const offset = field === FILE_FIELD ? s.file_offset : s.signature_offset;
  const length = field === FILE_FIELD ? s.file_size : s.signature_size;
  response.writeHead(200, {
    [CHUNK_HEADER]: String(offset),
    [LENGTH_HEADER]: String(length),
    'Cache-Control': 'no-store',
  });
  response.end();
  return true;
}

async function handlePatchChunk(request, response, ctx, uploadId, field) {
  const s = findUploadSession(ctx.db, uploadId);
  if (!s || s.status !== 'active') {
    const err = new Error('Upload session not found');
    err.statusCode = 404;
    err.code = 'upload_not_found';
    throw err;
  }
  const offsetHeader = Number.parseInt(request.headers[CHUNK_HEADER] || '', 10);
  const currentOffset = field === FILE_FIELD ? s.file_offset : s.signature_offset;
  if (!Number.isFinite(offsetHeader) || offsetHeader !== currentOffset) {
    const err = new Error(`Offset mismatch: client sent ${offsetHeader}, server has ${currentOffset}`);
    err.statusCode = 416;
    err.code = 'offset_mismatch';
    throw err;
  }
  const maxLength = field === FILE_FIELD ? ctx.maxChunkSize : 64 * 1024;
  const chunk = await readBody(request, maxLength);
  const filePath = path.join(ctx.uploadRoot, uploadId, field === FILE_FIELD ? 'package.part' : 'signature.part');
  const newOffset = await appendChunk(filePath, currentOffset, chunk);
  updateUploadOffsets(ctx.db, uploadId, field, newOffset);
  const length = field === FILE_FIELD ? s.file_size : s.signature_size;
  response.writeHead(204, {
    [CHUNK_HEADER]: String(newOffset),
    [LENGTH_HEADER]: String(length),
    'Cache-Control': 'no-store',
  });
  response.end();
  return true;
}

async function handleComplete(response, ctx, session, uploadId) {
  const s = findUploadSession(ctx.db, uploadId);
  if (!s) {
    const err = new Error('Upload session not found');
    err.statusCode = 404;
    err.code = 'upload_not_found';
    throw err;
  }
  if (s.status === 'completed') {
    const existing = findVersionByPlatformAndVersion(ctx.db, s.platform, s.version);
    if (existing) {
      json(response, 200, {
        version_id: existing.id,
        file_sha256: existing.file_sha256,
        signature_valid: true,
        published: existing.is_latest === 1,
      });
      return true;
    }
  }
  if (s.status !== 'active') {
    const err = new Error(`Upload session status is ${s.status}`);
    err.statusCode = 409;
    err.code = 'upload_not_active';
    throw err;
  }
  if (s.file_offset !== s.file_size || s.signature_offset !== s.signature_size) {
    const err = new Error(
      `Upload incomplete: package ${s.file_offset}/${s.file_size}, signature ${s.signature_offset}/${s.signature_size}`,
    );
    err.statusCode = 409;
    err.code = 'upload_incomplete';
    throw err;
  }

  const packagePath = path.join(ctx.uploadRoot, uploadId, 'package.part');
  const signaturePath = path.join(ctx.uploadRoot, uploadId, 'signature.part');
  const fileSha256 = await sha256File(packagePath);
  if (s.file_sha256 && s.file_sha256 !== fileSha256) {
    const err = new Error(`File sha256 mismatch: expected ${s.file_sha256}, got ${fileSha256}`);
    err.statusCode = 422;
    err.code = 'file_checksum_mismatch';
    throw err;
  }
  const sigContent = (await fs.readFile(signaturePath, 'utf8')).trim();
  if (!isBase64(sigContent)) {
    const err = new Error('Signature file is not valid base64');
    err.statusCode = 422;
    err.code = 'signature_mismatch';
    throw err;
  }
  if (!isPairedSignature(s.file_name, s.signature_name)) {
    const err = new Error(`Signature ${s.signature_name} does not match package ${s.file_name}`);
    err.statusCode = 422;
    err.code = 'signature_mismatch';
    throw err;
  }

  const versionId = randomId('v');
  const stat = await fs.stat(packagePath);
  const releaseDir = path.dirname(resolveReleaseFile(ctx.dataDir, s.platform, s.version, s.file_name));
  await fs.mkdir(releaseDir, { recursive: true });
  const finalPackagePath = path.join(releaseDir, s.file_name);
  const finalSignaturePath = path.join(releaseDir, s.signature_name);
  await fs.rename(packagePath, finalPackagePath);
  await fs.rename(signaturePath, finalSignaturePath);
  await fs.rm(path.join(ctx.uploadRoot, uploadId), { recursive: true, force: true });

  withTransaction(ctx.db, () => {
    insertVersion(ctx.db, {
      id: versionId,
      platform: s.platform,
      version: s.version,
      notes: '',
      file_name: s.file_name,
      file_size: stat.size,
      file_sha256: fileSha256,
      signature: sigContent,
      created_at: new Date().toISOString(),
    });
    markUploadSessionStatus(ctx.db, uploadId, 'completed');
    insertAudit(ctx.db, {
      actor: session.user || 'admin',
      action: 'version.upload',
      platform: s.platform,
      version: s.version,
      detail: `id=${versionId} sha256=${fileSha256}`,
    });
  });

  json(response, 200, {
    version_id: versionId,
    file_sha256: fileSha256,
    signature_valid: true,
    published: false,
  });
  return true;
}

export async function handlePublish(request, response, ctx, session, versionId) {
  const target = findVersionById(ctx.db, versionId);
  if (!target) {
    const err = new Error(`Version not found: ${versionId}`);
    err.statusCode = 404;
    err.code = 'version_not_found';
    throw err;
  }
  // Re-check monotonic at publish time (defends against latest changing between complete and publish).
  const latest = getLatestVersion(ctx.db, target.platform);
  if (latest && latest.id !== target.id && !isStrictlyGreater(parseVersion(target.version), parseVersion(latest.version))) {
    const err = new Error(
      `Publish blocked: version ${target.version} is not greater than current latest ${latest.version}`,
    );
    err.statusCode = 409;
    err.code = 'version_older_than_latest';
    err.currentLatest = latest.version;
    err.attempted = target.version;
    throw err;
  }
  if (latest && latest.id === target.id) {
    json(response, 200, {
      platform: target.platform,
      version: target.version,
      is_latest: true,
      previous_latest: null,
      already_latest: true,
    });
    return true;
  }

  const result = withTransaction(ctx.db, () => publishVersion(ctx.db, versionId));
  insertAudit(ctx.db, {
    actor: session.user || 'admin',
    action: 'version.publish',
    platform: result.platform,
    version: result.version,
    detail: `previous_latest=${result.previous_latest || 'none'}`,
  });
  json(response, 200, {
    platform: result.platform,
    version: result.version,
    is_latest: true,
    previous_latest: result.previous_latest,
    already_latest: result.already_latest === true,
  });
  return true;
}
