import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {
  openDatabase,
  withTransaction,
  insertVersion,
  getLatestVersion,
  findVersionById,
  findVersionByPlatformAndVersion,
  listVersions,
  publishVersion,
  updateVersionNotes,
  deleteVersion,
  insertAudit,
  listAudit,
  createUploadSession,
  findUploadSession,
  listActiveUploadSessions,
  updateUploadOffsets,
  markUploadSessionStatus,
  findExpiredUploadSessions,
  deleteUploadSession,
  assertSupportedPlatform,
} from '../src/db.js';

async function withTempDb(fn) {
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'us-db-'));
  try {
    const db = openDatabase(tempDir);
    try {
      await fn(db, tempDir);
    } finally {
      db.close();
    }
  } finally {
    await fs.rm(tempDir, { recursive: true, force: true });
  }
}

function baseRow(overrides = {}) {
  return {
    id: 'v_test1',
    platform: 'windows-x86_64',
    version: '0.2.3',
    notes: 'first',
    file_name: 'SimplePlane_0.2.3_x64-setup.nsis.zip',
    file_size: 100,
    file_sha256: 'abc',
    signature: 'sig',
    created_at: '2026-10-22T08:00:00Z',
    ...overrides,
  };
}

test('openDatabase creates schema and is idempotent', async () => {
  await withTempDb(async (db) => {
    const tables = db.prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").all();
    const names = tables.map((t) => t.name);
    assert.ok(names.includes('versions'));
    assert.ok(names.includes('upload_sessions'));
    assert.ok(names.includes('audit_log'));
  });
});

test('assertSupportedPlatform rejects unknown platform', () => {
  assert.equal(assertSupportedPlatform('windows-x86_64'), 'windows-x86_64');
  assert.throws(() => assertSupportedPlatform('linux-x86_64'), /Unsupported platform/);
});

test('insertVersion and findVersionById roundtrip', async () => {
  await withTempDb(async (db) => {
    insertVersion(db, baseRow());
    const row = findVersionById(db, 'v_test1');
    assert.equal(row.version, '0.2.3');
    assert.equal(row.is_latest, 0);
    assert.equal(row.published_at, null);
  });
});

test('unique constraint on (platform, version)', async () => {
  await withTempDb(async (db) => {
    insertVersion(db, baseRow());
    assert.throws(() => insertVersion(db, baseRow({ id: 'v_dup' })), /UNIQUE/);
  });
});

test('findVersionByPlatformAndVersion lookup', async () => {
  await withTempDb(async (db) => {
    insertVersion(db, baseRow());
    const row = findVersionByPlatformAndVersion(db, 'windows-x86_64', '0.2.3');
    assert.equal(row.id, 'v_test1');
  });
});

test('publishVersion sets latest and demotes previous', async () => {
  await withTempDb(async (db) => {
    insertVersion(db, baseRow({ id: 'v_a', version: '0.2.2' }));
    insertVersion(db, baseRow({ id: 'v_b', version: '0.2.3' }));
    publishVersion(db, 'v_a');
    assert.equal(getLatestVersion(db, 'windows-x86_64').id, 'v_a');
    publishVersion(db, 'v_b');
    const latest = getLatestVersion(db, 'windows-x86_64');
    assert.equal(latest.id, 'v_b');
    assert.equal(latest.is_latest, 1);
    assert.equal(latest.published_at !== null, true);
    const old = findVersionById(db, 'v_a');
    assert.equal(old.is_latest, 0);
  });
});

test('publishVersion on missing id throws 404', async () => {
  await withTempDb(async (db) => {
    assert.throws(() => publishVersion(db, 'v_missing'), /not found/);
  });
});

test('publishVersion idempotent on already-latest', async () => {
  await withTempDb(async (db) => {
    insertVersion(db, baseRow({ id: 'v_a' }));
    publishVersion(db, 'v_a');
    const r = publishVersion(db, 'v_a');
    assert.equal(r.already_latest, true);
  });
});

test('updateVersionNotes updates notes; 404 on missing', async () => {
  await withTempDb(async (db) => {
    insertVersion(db, baseRow());
    updateVersionNotes(db, 'v_test1', 'new notes');
    assert.equal(findVersionById(db, 'v_test1').notes, 'new notes');
    assert.throws(() => updateVersionNotes(db, 'v_missing', 'x'), /not found/);
  });
});

test('deleteVersion deletes non-latest; refuses latest', async () => {
  await withTempDb(async (db) => {
    insertVersion(db, baseRow({ id: 'v_a', version: '0.2.2' }));
    insertVersion(db, baseRow({ id: 'v_b', version: '0.2.3' }));
    publishVersion(db, 'v_b');
    assert.throws(() => deleteVersion(db, 'v_b'), /Cannot delete/);
    deleteVersion(db, 'v_a');
    assert.equal(findVersionById(db, 'v_a'), undefined);
  });
});

test('listVersions filter by platform and order', async () => {
  await withTempDb(async (db) => {
    insertVersion(db, baseRow({ id: 'v_w1', platform: 'windows-x86_64', version: '0.2.2' }));
    insertVersion(db, baseRow({ id: 'v_m1', platform: 'darwin-aarch64', version: '0.2.2' }));
    const win = listVersions(db, 'windows-x86_64');
    const mac = listVersions(db, 'darwin-aarch64');
    assert.equal(win.length, 1);
    assert.equal(mac.length, 1);
  });
});

test('withTransaction rolls back on error', async () => {
  await withTempDb(async (db) => {
    assert.throws(
      () => withTransaction(db, () => {
        insertVersion(db, baseRow({ id: 'v_tx' }));
        throw new Error('boom');
      }),
      /boom/,
    );
    assert.equal(findVersionById(db, 'v_tx'), undefined);
  });
});

test('audit log write and read', async () => {
  await withTempDb(async (db) => {
    insertAudit(db, { action: 'admin.login.success', actor: 'admin', detail: 'ip=127.0.0.1' });
    insertAudit(db, { action: 'version.publish', actor: 'admin', platform: 'windows-x86_64', version: '0.2.3' });
    const rows = listAudit(db, 10);
    assert.equal(rows.length, 2);
    assert.equal(rows[0].action, 'version.publish');
    assert.equal(rows[0].platform, 'windows-x86_64');
  });
});

test('upload session lifecycle', async () => {
  await withTempDb(async (db) => {
    createUploadSession(db, {
      id: 'u_1',
      platform: 'windows-x86_64',
      version: '0.2.3',
      file_name: 'p.zip',
      file_size: 1000,
      file_sha256: 'abc',
      signature_name: 'p.zip.sig',
      signature_size: 200,
      created_at: '2026-10-22T08:00:00Z',
      expires_at: '2026-10-23T08:00:00Z',
    });
    const s = findUploadSession(db, 'u_1');
    assert.equal(s.status, 'active');
    assert.equal(s.file_offset, 0);
    updateUploadOffsets(db, 'u_1', 'file', 500);
    updateUploadOffsets(db, 'u_1', 'signature', 200);
    assert.equal(findUploadSession(db, 'u_1').file_offset, 500);
    markUploadSessionStatus(db, 'u_1', 'completed');
    assert.equal(findUploadSession(db, 'u_1').status, 'completed');
    assert.equal(listActiveUploadSessions(db).length, 0);
    deleteUploadSession(db, 'u_1');
    assert.equal(findUploadSession(db, 'u_1'), undefined);
  });
});

test('findExpiredUploadSessions', async () => {
  await withTempDb(async (db) => {
    const past = '2020-01-01T00:00:00Z';
    const future = '2999-01-01T00:00:00Z';
    createUploadSession(db, { id: 'u_exp', platform: 'windows-x86_64', version: '0.2.3', file_name: 'p.zip', file_size: 100, signature_name: 'p.zip.sig', signature_size: 10, created_at: past, expires_at: past });
    createUploadSession(db, { id: 'u_alive', platform: 'windows-x86_64', version: '0.2.4', file_name: 'p.zip', file_size: 100, signature_name: 'p.zip.sig', signature_size: 10, created_at: past, expires_at: future });
    const expired = findExpiredUploadSessions(db, '2026-10-22T00:00:00Z');
    assert.equal(expired.length, 1);
    assert.equal(expired[0].id, 'u_exp');
  });
});
