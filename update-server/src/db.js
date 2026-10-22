import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';

const SCHEMA = `
CREATE TABLE IF NOT EXISTS versions (
  id              TEXT PRIMARY KEY,
  platform        TEXT NOT NULL,
  version         TEXT NOT NULL,
  notes           TEXT NOT NULL DEFAULT '',
  file_name       TEXT NOT NULL,
  file_size       INTEGER NOT NULL,
  file_sha256     TEXT NOT NULL,
  signature       TEXT NOT NULL,
  is_latest       INTEGER NOT NULL DEFAULT 0,
  created_at      TEXT NOT NULL,
  published_at    TEXT,
  UNIQUE (platform, version)
);

CREATE INDEX IF NOT EXISTS idx_versions_platform_latest
  ON versions(platform, is_latest);

CREATE INDEX IF NOT EXISTS idx_versions_platform_version
  ON versions(platform, version);

CREATE TABLE IF NOT EXISTS upload_sessions (
  id              TEXT PRIMARY KEY,
  platform        TEXT NOT NULL,
  version         TEXT NOT NULL,
  file_name       TEXT NOT NULL,
  file_size       INTEGER NOT NULL,
  file_offset     INTEGER NOT NULL DEFAULT 0,
  file_sha256     TEXT,
  signature_name  TEXT NOT NULL,
  signature_size  INTEGER NOT NULL,
  signature_offset INTEGER NOT NULL DEFAULT 0,
  status          TEXT NOT NULL DEFAULT 'active',
  created_at      TEXT NOT NULL,
  expires_at      TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_upload_sessions_status
  ON upload_sessions(status);

CREATE INDEX IF NOT EXISTS idx_upload_sessions_expires
  ON upload_sessions(expires_at);

CREATE TABLE IF NOT EXISTS audit_log (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  ts              TEXT NOT NULL,
  actor           TEXT NOT NULL,
  action          TEXT NOT NULL,
  platform        TEXT,
  version         TEXT,
  detail          TEXT
);

CREATE INDEX IF NOT EXISTS idx_audit_ts
  ON audit_log(ts);
`;

const SUPPORTED_PLATFORMS = new Set(['windows-x86_64', 'darwin-aarch64']);

export function assertSupportedPlatform(platform) {
  if (!SUPPORTED_PLATFORMS.has(platform)) {
    const error = new Error(`Unsupported platform: ${platform}`);
    error.statusCode = 400;
    error.code = 'unsupported_platform';
    throw error;
  }
  return platform;
}

export function listSupportedPlatforms() {
  return [...SUPPORTED_PLATFORMS];
}

export function openDatabase(dataDir) {
  const resolvedDataDir = path.resolve(dataDir);
  fs.mkdirSync(resolvedDataDir, { recursive: true });
  const dbPath = path.join(resolvedDataDir, 'update-server.db');
  const db = new DatabaseSync(dbPath);
  db.exec('PRAGMA journal_mode = WAL');
  db.exec('PRAGMA foreign_keys = ON');
  db.exec('PRAGMA busy_timeout = 5000');
  db.exec(SCHEMA);
  return db;
}

export function withTransaction(db, fn) {
  db.exec('BEGIN');
  try {
    const result = fn();
    db.exec('COMMIT');
    return result;
  } catch (err) {
    try { db.exec('ROLLBACK'); } catch { /* ignore rollback failure */ }
    throw err;
  }
}

export function getLatestVersion(db, platform) {
  return db.prepare(
    'SELECT id, platform, version, notes, file_name, file_size, file_sha256, signature, is_latest, created_at, published_at FROM versions WHERE platform = ? AND is_latest = 1 LIMIT 1',
  ).get(platform);
}

export function findVersionById(db, id) {
  return db.prepare(
    'SELECT id, platform, version, notes, file_name, file_size, file_sha256, signature, is_latest, created_at, published_at FROM versions WHERE id = ?',
  ).get(id);
}

export function findVersionByPlatformAndVersion(db, platform, version) {
  return db.prepare(
    'SELECT id, platform, version, notes, file_name, file_size, file_sha256, signature, is_latest, created_at, published_at FROM versions WHERE platform = ? AND version = ?',
  ).get(platform, version);
}

export function listVersions(db, platform) {
  if (platform) {
    return db.prepare(
      'SELECT id, platform, version, notes, file_name, file_size, file_sha256, is_latest, created_at, published_at FROM versions WHERE platform = ? ORDER BY rowid DESC',
    ).all(platform);
  }
  return db.prepare(
    'SELECT id, platform, version, notes, file_name, file_size, file_sha256, is_latest, created_at, published_at FROM versions ORDER BY rowid DESC',
  ).all();
}

export function insertVersion(db, row) {
  db.prepare(
    `INSERT INTO versions (id, platform, version, notes, file_name, file_size, file_sha256, signature, is_latest, created_at, published_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, NULL)`,
  ).run(
    row.id,
    row.platform,
    row.version,
    row.notes || '',
    row.file_name,
    row.file_size,
    row.file_sha256,
    row.signature,
    row.created_at,
  );
}

export function publishVersion(db, id) {
  const target = findVersionById(db, id);
  if (!target) {
    const error = new Error(`Version not found: ${id}`);
    error.statusCode = 404;
    error.code = 'version_not_found';
    throw error;
  }
  const previousLatest = getLatestVersion(db, target.platform);
  if (previousLatest && previousLatest.id === id) {
    return { platform: target.platform, version: target.version, previous_latest: null, already_latest: true };
  }
  db.prepare('UPDATE versions SET is_latest = 0 WHERE platform = ?').run(target.platform);
  db.prepare('UPDATE versions SET is_latest = 1, published_at = ? WHERE id = ?').run(new Date().toISOString(), id);
  return {
    platform: target.platform,
    version: target.version,
    previous_latest: previousLatest ? previousLatest.version : null,
    already_latest: false,
  };
}

export function updateVersionNotes(db, id, notes) {
  const result = db.prepare('UPDATE versions SET notes = ? WHERE id = ?').run(notes ?? '', id);
  if (result.changes === 0) {
    const error = new Error(`Version not found: ${id}`);
    error.statusCode = 404;
    error.code = 'version_not_found';
    throw error;
  }
}

export function deleteVersion(db, id) {
  const target = findVersionById(db, id);
  if (!target) {
    const error = new Error(`Version not found: ${id}`);
    error.statusCode = 404;
    error.code = 'version_not_found';
    throw error;
  }
  if (target.is_latest === 1) {
    const error = new Error('Cannot delete current latest version');
    error.statusCode = 409;
    error.code = 'cannot_delete_latest';
    throw error;
  }
  db.prepare('DELETE FROM versions WHERE id = ?').run(id);
  return target;
}

export function insertAudit(db, entry) {
  db.prepare(
    'INSERT INTO audit_log (ts, actor, action, platform, version, detail) VALUES (?, ?, ?, ?, ?, ?)',
  ).run(
    entry.ts || new Date().toISOString(),
    entry.actor || 'system',
    entry.action,
    entry.platform || null,
    entry.version || null,
    entry.detail || null,
  );
}

export function listAudit(db, limit = 100) {
  return db.prepare('SELECT id, ts, actor, action, platform, version, detail FROM audit_log ORDER BY id DESC LIMIT ?').all(limit);
}

export function createUploadSession(db, row) {
  db.prepare(
    `INSERT INTO upload_sessions
      (id, platform, version, file_name, file_size, file_offset, file_sha256, signature_name, signature_size, signature_offset, status, created_at, expires_at)
     VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, 0, 'active', ?, ?)`,
  ).run(
    row.id,
    row.platform,
    row.version,
    row.file_name,
    row.file_size,
    row.file_sha256 || null,
    row.signature_name,
    row.signature_size,
    row.created_at,
    row.expires_at,
  );
}

export function findUploadSession(db, id) {
  return db.prepare(
    `SELECT id, platform, version, file_name, file_size, file_offset, file_sha256,
            signature_name, signature_size, signature_offset, status, created_at, expires_at
     FROM upload_sessions WHERE id = ?`,
  ).get(id);
}

export function listActiveUploadSessions(db) {
  return db.prepare(
    `SELECT id, platform, version, file_name, file_size, file_offset, signature_name, signature_size, signature_offset, status, created_at, expires_at
     FROM upload_sessions WHERE status = 'active' ORDER BY created_at DESC`,
  ).all();
}

export function updateUploadOffsets(db, id, field, offset) {
  if (field !== 'file' && field !== 'signature') {
    throw new Error(`Invalid offset field: ${field}`);
  }
  const column = field === 'file' ? 'file_offset' : 'signature_offset';
  const stmt = db.prepare(`UPDATE upload_sessions SET ${column} = ? WHERE id = ?`);
  return stmt.run(offset, id);
}

export function markUploadSessionStatus(db, id, status) {
  return db.prepare('UPDATE upload_sessions SET status = ? WHERE id = ?').run(status, id);
}

export function findExpiredUploadSessions(db, now = new Date().toISOString()) {
  return db.prepare("SELECT id FROM upload_sessions WHERE status = 'active' AND expires_at < ?").all(now);
}

export function listUploadSessionIdsForCleanup(db, now = new Date().toISOString()) {
  return db.prepare("SELECT id FROM upload_sessions WHERE status = 'active' AND expires_at < ?").all(now);
}

export function deleteUploadSession(db, id) {
  return db.prepare('DELETE FROM upload_sessions WHERE id = ?').run(id);
}
