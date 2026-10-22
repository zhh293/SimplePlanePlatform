import http from 'node:http';
import { mkdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { openDatabase } from './db.js';
import { handlePublicRoutes } from './public.js';
import { handleAdminRoutes } from './admin.js';
import { handleUploadRoutes } from './uploads.js';
import { createAuthState } from './auth.js';
import { errorResponse } from './util/http.js';
import { bufferToBigint } from './srp-group.js';
import { SRP_USERNAME } from './srp.js';

const ROOT_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const DEFAULT_DATA_DIR = path.join(ROOT_DIR, 'data');

function readEnvInt(name, fallback) {
  const raw = process.env[name];
  if (!raw) return fallback;
  const v = Number.parseInt(raw, 10);
  return Number.isFinite(v) ? v : fallback;
}

function loadSrpVerifier(options) {
  const verifierPath = options.srpVerifierFile || process.env.SRP_VERIFIER_FILE || process.env.SRP_VERIFIER_PATH;
  if (!verifierPath) return null;
  try {
    const raw = readFileSync(verifierPath, 'utf8');
    const parsed = JSON.parse(raw);
    if (!parsed.salt || !parsed.verifier) {
      console.error(`[update-server] verifier file ${verifierPath} missing salt/verifier fields`);
      return null;
    }
    return {
      username: parsed.username || SRP_USERNAME,
      salt: Buffer.from(parsed.salt, 'base64'),
      verifier: bufferToBigint(Buffer.from(parsed.verifier, 'base64')),
      kdf: parsed.kdf || 'pbkdf2-sha256-600000',
      createdAt: parsed.created_at || null,
    };
  } catch (err) {
    console.error(`[update-server] failed to load SRP verifier from ${verifierPath}:`, err.message);
    return null;
  }
}

export function createUpdateServer(options = {}) {
  const dataDir = path.resolve(options.dataDir || process.env.DATA_DIR || DEFAULT_DATA_DIR);
  const host = options.host || process.env.HOST || '0.0.0.0';
  const port = options.port || Number.parseInt(process.env.PORT || '8787', 10);
  const publicBaseUrl = (options.publicBaseUrl || process.env.PUBLIC_BASE_URL || '').replace(/\/$/, '');
  const sessionSecret = options.sessionSecret || process.env.SESSION_SECRET || '';
  const uploadRoot = path.join(dataDir, 'uploads');

  mkdirSync(path.join(dataDir, 'releases'), { recursive: true });
  mkdirSync(uploadRoot, { recursive: true });

  const db = openDatabase(dataDir);

  const srp = loadSrpVerifier(options);
  if (srp) {
    console.log(`[update-server] SRP verifier loaded for user "${srp.username}" (created ${srp.createdAt || 'unknown'})`);
  } else {
    console.warn('[update-server] SRP verifier not configured — admin login disabled (POST /admin/* will return 503)');
  }

  const ctx = {
    db,
    dataDir,
    releasesDir: path.join(dataDir, 'releases'),
    uploadRoot,
    publicBaseUrl,
    sessionSecret,
    srp,
    auth: createAuthState(),
    maxFileSize: options.maxFileSize || readEnvInt('MAX_FILE_SIZE', 1024 * 1024 * 1024),
    maxChunkSize: options.maxChunkSize || readEnvInt('MAX_CHUNK_SIZE', 32 * 1024 * 1024),
    defaultChunkSize: options.defaultChunkSize || readEnvInt('DEFAULT_CHUNK_SIZE', 8 * 1024 * 1024),
    uploadSessionTtlMs: readEnvInt('UPLOAD_SESSION_TTL_HOURS', 24) * 60 * 60 * 1000,
    maxActiveUploads: options.maxActiveUploads || readEnvInt('MAX_ACTIVE_UPLOADS', 5),
    staticRoot: options.staticRoot || path.join(ROOT_DIR, 'public'),
  };

  const server = http.createServer((request, response) => {
    const start = Date.now();
    const handler = async () => {
      if (await handlePublicRoutes(request, response, ctx)) return;
      if (await handleUploadRoutes(request, response, ctx)) return;
      if (await handleAdminRoutes(request, response, ctx)) return;
      errorResponse(response, Object.assign(new Error('not_found'), { statusCode: 404, code: 'not_found' }));
    };
    handler().catch((error) => {
      errorResponse(response, error);
    }).finally(() => {
      const url = request.url;
      const status = response.statusCode || 500;
      const ms = Date.now() - start;
      if (status >= 400) {
        console.error(`[update-server] ${request.method} ${url} → ${status} (${ms}ms)`);
      } else {
        console.log(`[update-server] ${request.method} ${url} → ${status} (${ms}ms)`);
      }
    });
  });

  server.ctx = ctx;
  server.closeDatabase = () => {
    try { db.close(); } catch { /* ignore */ }
  };
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const server = createUpdateServer();
  const port = Number.parseInt(process.env.PORT || '8787', 10);
  const host = process.env.HOST || '0.0.0.0';
  server.listen(port, host, () => {
    console.log(`[update-server] listening on http://${host}:${port}`);
    console.log(`[update-server] data directory: ${server.ctx.dataDir}`);
    console.log(`[update-server] public base URL: ${server.ctx.publicBaseUrl || '(derived from request)'}`);
  });
}
