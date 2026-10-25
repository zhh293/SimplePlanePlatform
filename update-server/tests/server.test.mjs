import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createUpdateServer } from '../src/server.js';
import { insertVersion, publishVersion } from '../src/db.js';
import { sha256File } from '../src/util/crypto.js';

async function withTempServer(fn, overrides = {}) {
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'simpleplane-update-'));
  const releaseDir = path.join(tempDir, 'releases', 'windows-x86_64', '0.1.8');
  await fs.mkdir(releaseDir, { recursive: true });
  await fs.writeFile(path.join(releaseDir, 'windows-update.zip'), 'fake-update');
  await fs.writeFile(path.join(releaseDir, 'windows-update.zip.sig'), 'fake-signature\n');

  const server = createUpdateServer({
    dataDir: tempDir,
    publicBaseUrl: 'https://updates.example.test',
    sessionSecret: 'a'.repeat(32),
    adminPasswordHash: overrides.adminPasswordHash,
    ...overrides,
  });

  const sha = await sha256File(path.join(releaseDir, 'windows-update.zip'));
  insertVersion(server.ctx.db, {
    id: 'v_test_018',
    platform: 'windows-x86_64',
    version: '0.1.8',
    notes: 'test update',
    file_name: 'windows-update.zip',
    file_size: 11,
    file_sha256: sha,
    signature: 'fake-signature',
    created_at: '2026-09-21T10:00:00Z',
  });
  publishVersion(server.ctx.db, 'v_test_018');

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  const baseUrl = `http://127.0.0.1:${address.port}`;

  try {
    await fn(baseUrl, server);
  } finally {
    await new Promise((resolve) => server.close(resolve));
    server.closeDatabase();
    await fs.rm(tempDir, { recursive: true, force: true });
  }
}

test('serves /healthz', async () => {
  await withTempServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/healthz`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.status, 'ok');
  });
});

test('serves /latest.json with SQLite-backed manifest', async () => {
  await withTempServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/latest.json`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.version, '0.1.8');
    assert.equal(body.notes, 'test update');
    assert.ok(typeof body.pub_date === 'string' && body.pub_date.length > 0);
    assert.deepEqual(Object.keys(body.platforms), ['windows-x86_64']);
    assert.equal(body.platforms['windows-x86_64'].url, 'https://updates.example.test/download/windows-x86_64/0.1.8/windows-update.zip');
    assert.equal(body.platforms['windows-x86_64'].signature, 'fake-signature');
  });
});

test('GET /api/v1/updates returns 200 when update available', async () => {
  await withTempServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/api/v1/updates/windows/x86_64/0.1.7`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.version, '0.1.8');
    assert.equal(body.notes, 'test update');
    assert.ok(typeof body.pub_date === 'string' && body.pub_date.length > 0);
    assert.equal(body.url, 'https://updates.example.test/download/windows-x86_64/0.1.8/windows-update.zip');
    assert.equal(body.signature, 'fake-signature');
  });
});

test('GET /api/v1/updates returns 204 when no update', async () => {
  await withTempServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/api/v1/updates/windows/x86_64/0.1.8`);
    assert.equal(res.status, 204);
    assert.equal(await res.text(), '');
  });
});

test('GET /api/v1/updates returns 400 for invalid version', async () => {
  await withTempServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/api/v1/updates/windows/x86_64/garbage`);
    assert.equal(res.status, 400);
  });
});

test('GET /api/v1/updates returns 404 for unsupported platform', async () => {
  await withTempServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/api/v1/updates/linux/x86_64/0.1.7`);
    assert.equal(res.status, 404);
  });
});

test('GET /download/{platform}/{version}/{file} serves package content', async () => {
  await withTempServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/download/windows-x86_64/0.1.8/windows-update.zip`);
    assert.equal(res.status, 200);
    assert.equal(await res.text(), 'fake-update');
    assert.equal(res.headers.get('cache-control'), 'public, max-age=31536000, immutable');
    assert.equal(res.headers.get('accept-ranges'), 'bytes');
  });
});

test('HEAD /download returns headers without body', async () => {
  await withTempServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/download/windows-x86_64/0.1.8/windows-update.zip`, { method: 'HEAD' });
    assert.equal(res.status, 200);
    assert.equal(Number(res.headers.get('content-length')), 11);
    assert.equal(await res.text(), '');
  });
});

test('GET /download with traversal attempt returns 400', async () => {
  await withTempServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/download/windows-x86_64/0.1.8/..%2f..%2f..%2fetc%2fpasswd`);
    assert.ok(res.status >= 400);
  });
});

test('GET unknown path returns 404', async () => {
  await withTempServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/some-random-path`);
    assert.equal(res.status, 404);
  });
});

test('/latest.json returns 404 when no versions published', async () => {
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'simpleplane-update-empty-'));
  const server = createUpdateServer({ dataDir: tempDir, publicBaseUrl: 'https://x.test', sessionSecret: 'a'.repeat(32) });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const baseUrl = `http://127.0.0.1:${server.address().port}`;
  try {
    const res = await fetch(`${baseUrl}/latest.json`);
    assert.equal(res.status, 404);
  } finally {
    await new Promise((resolve) => server.close(resolve));
    server.closeDatabase();
    await fs.rm(tempDir, { recursive: true, force: true });
  }
});
