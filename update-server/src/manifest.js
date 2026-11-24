import path from 'node:path';
import fs from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import { getLatestVersion } from './db.js';
import { parseVersion, compareVersions } from './util/version.js';
import { resolveUnderRoot, safeSegment } from './util/path.js';

export function platformKey(target, arch) {
  const t = safeSegment(target, 'target');
  const a = safeSegment(arch, 'arch');
  return `${t}-${a}`;
}

export function releasesDir(dataDir, platform, version) {
  return path.join(dataDir, 'releases', platform, version);
}

export function resolveReleaseFile(dataDir, platform, version, fileName) {
  const p = safeSegment(platform, 'platform');
  const v = safeSegment(version, 'version');
  const f = safeSegment(fileName, 'file name');
  const root = releasesDir(dataDir, p, v);
  return resolveUnderRoot(root, [f]);
}

export function publicUrl(request, publicBaseUrl, platform, version, fileName) {
  const base = publicBaseUrl || `${request.headers['x-forwarded-proto'] || 'http'}://${request.headers.host}`;
  return `${base}/download/${encodeURIComponent(platform)}/${encodeURIComponent(version)}/${encodeURIComponent(fileName)}`;
}

async function artifactUrl(request, publicBaseUrl, dataDir, release) {
  const { platform, version, file_name: fileName } = release;
  if (release.github_release === 1) {
    const tag = encodeURIComponent(`v${version}`);
    return `https://github.com/zhh293/SimplePlanePlatform/releases/download/${tag}/${encodeURIComponent(fileName)}`;
  }
  await fs.access(resolveReleaseFile(dataDir, platform, version, fileName));
  return publicUrl(request, publicBaseUrl, platform, version, fileName);
}

export async function buildLatestManifestResponse(db, request, publicBaseUrl, dataDir) {
  const platforms = {};
  for (const platform of ['windows-x86_64', 'darwin-aarch64']) {
    const latest = getLatestVersion(db, platform);
    if (!latest) continue;
    try {
      platforms[platform] = {
        url: await artifactUrl(request, publicBaseUrl, dataDir, latest),
        signature: latest.signature,
      };
    } catch {
      continue;
    }
  }
  const anyLatest = Object.keys(platforms).map((p) => getLatestVersion(db, p)).filter(Boolean)[0];
  if (!anyLatest) {
    const error = new Error('No published versions available');
    error.statusCode = 404;
    error.code = 'no_versions';
    throw error;
  }
  return {
    version: anyLatest.version,
    notes: anyLatest.notes || '',
    pub_date: anyLatest.published_at || anyLatest.created_at,
    platforms,
  };
}

export async function resolvePlatformUpdate(db, request, target, arch, currentVersion, dataDir, publicBaseUrl) {
  const current = parseVersion(currentVersion);
  if (!current) {
    const error = new Error('current_version must be a valid SemVer');
    error.statusCode = 400;
    error.code = 'invalid_version_format';
    throw error;
  }

  const pkey = platformKey(target, arch);
  const latest = getLatestVersion(db, pkey);
  if (!latest) {
    const error = new Error(`No update artifact for ${pkey}`);
    error.statusCode = 404;
    error.code = 'no_artifact_for_platform';
    throw error;
  }

  const latestParsed = parseVersion(latest.version);
  if (compareVersions(latestParsed, current) <= 0) return null;

  return {
    version: latest.version,
    notes: latest.notes || '',
    pub_date: latest.published_at || latest.created_at,
    url: await artifactUrl(request, publicBaseUrl, dataDir, latest),
    signature: latest.signature,
  };
}

export async function serveDownload(request, response, dataDir, platform, version, fileName) {
  const filePath = resolveReleaseFile(dataDir, platform, version, fileName);
  const stat = await fs.stat(filePath);
  if (!stat.isFile()) {
    const error = new Error('Not a file');
    error.statusCode = 404;
    error.code = 'not_found';
    throw error;
  }

  const extension = path.extname(fileName).toLowerCase();
  const contentTypes = {
    '.json': 'application/json; charset=utf-8',
    '.zip': 'application/zip',
    '.gz': 'application/gzip',
    '.exe': 'application/vnd.microsoft.portable-executable',
    '.msi': 'application/x-msi',
    '.sig': 'application/octet-stream',
  };

  response.writeHead(200, {
    'Content-Type': contentTypes[extension] || 'application/octet-stream',
    'Content-Length': stat.size,
    'Content-Disposition': `attachment; filename="${fileName.replaceAll('"', '')}"`,
    'Cache-Control': 'public, max-age=31536000, immutable',
    'X-Content-Type-Options': 'nosniff',
    'Accept-Ranges': 'bytes',
  });
  if (request.method === 'HEAD') {
    response.end();
    return;
  }
  createReadStream(filePath).pipe(response);
}
