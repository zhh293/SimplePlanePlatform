#!/usr/bin/env node

import fs from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { openDatabase, getLatestVersion, insertVersion, publishVersion, insertAudit } from '../src/db.js';
import { isBase64, randomId } from '../src/util/crypto.js';

function argsToObject(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const item = argv[i];
    if (!item.startsWith('--')) continue;
    const key = item.slice(2);
    args[key] = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : true;
  }
  return args;
}

function readManifest(filePath) {
  return fs.readFile(filePath, 'utf8').then((raw) => JSON.parse(raw));
}

async function migrateFileForPlatform(dataDir, manifest, platformKey, entry, manifestVersion) {
  const oldPath = path.join(dataDir, 'releases', manifestVersion, entry.file);
  const newDir = path.join(dataDir, 'releases', platformKey, manifestVersion);
  await fs.mkdir(newDir, { recursive: true });
  const newFilePath = path.join(newDir, entry.file);
  await fs.copyFile(oldPath, newFilePath);

  let signature = entry.signature;
  if (!signature && entry.signatureFile) {
    const sigPath = path.join(dataDir, 'releases', manifestVersion, entry.signatureFile);
    signature = (await fs.readFile(sigPath, 'utf8')).trim();
  }
  if (!signature || !isBase64(signature)) {
    throw new Error(`Signature for ${platformKey} is missing or not base64`);
  }
  return signature;
}

async function main() {
  const args = argsToObject(process.argv.slice(2));
  const dataDir = path.resolve(args['data-dir'] || path.join(process.cwd(), 'data'));
  const latestJsonPath = path.join(dataDir, 'latest.json');

  await fs.access(latestJsonPath).catch(() => {
    throw new Error(`latest.json not found at ${latestJsonPath}`);
  });

  const manifest = await readManifest(latestJsonPath);
  if (!manifest.version || !manifest.platforms) {
    throw new Error('latest.json is invalid: missing version or platforms');
  }

  const db = openDatabase(dataDir);
  const now = new Date().toISOString();

  for (const [platformKey, entry] of Object.entries(manifest.platforms)) {
    if (!entry || !entry.file) continue;

    const existing = getLatestVersion(db, platformKey);
    if (existing && existing.version === manifest.version) {
      console.log(`[migrate] ${platformKey} ${manifest.version} already migrated`);
      continue;
    }

    const signature = await migrateFileForPlatform(dataDir, manifest, platformKey, entry, manifest.version);
    const filePath = path.join(dataDir, 'releases', platformKey, manifest.version, entry.file);
    const stat = await fs.stat(filePath);

    const id = randomId('v');
    insertVersion(db, {
      id,
      platform: platformKey,
      version: manifest.version,
      notes: manifest.notes || '',
      file_name: entry.file,
      file_size: stat.size,
      file_sha256: '', // not known for legacy; empty acceptable for migration
      signature,
      created_at: manifest.pub_date || now,
    });
    publishVersion(db, id);
    insertAudit(db, {
      ts: now,
      actor: 'system',
      action: 'version.migrate',
      platform: platformKey,
      version: manifest.version,
      detail: 'migrated from latest.json',
    });
    console.log(`[migrate] ${platformKey} ${manifest.version} published (id=${id})`);
  }

  const backup = `${latestJsonPath}.pre-migrate.bak`;
  await fs.rename(latestJsonPath, backup);
  console.log(`[migrate] latest.json backed up to ${backup}`);
  db.close();
}

main().catch((err) => {
  console.error('[migrate] failed:', err);
  process.exit(1);
});
