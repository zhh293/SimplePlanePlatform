import { json, noContent, errorResponse, parseJsonBody } from './util/http.js';
import fs from 'node:fs/promises';
import path from 'node:path';
import {
  authenticateAdmin,
  handleLegacyLogin,
  handleLogout,
  handleSession,
  handleSrpChallenge,
  handleSrpVerify,
  handleSrpParams,
} from './auth.js';
import {
  listVersions,
  findVersionById,
  updateVersionNotes,
  deleteVersion,
  listAudit,
  insertAudit,
} from './db.js';
import { handlePublish } from './uploads.js';

const STATIC_CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.png': 'image/png',
};

async function serveStatic(response, staticRoot, relPath, fallback) {
  const safe = path.normalize(relPath).replace(/^(\.\.[/\\])+/, '');
  const filePath = path.resolve(staticRoot, safe);
  if (!filePath.startsWith(path.resolve(staticRoot))) {
    const err = new Error('forbidden');
    err.statusCode = 403;
    err.code = 'forbidden';
    throw err;
  }
  try {
    const buf = await fs.readFile(filePath);
    const ext = path.extname(filePath).toLowerCase();
    const contentType = STATIC_CONTENT_TYPES[ext] || 'application/octet-stream';
    response.writeHead(200, {
      'Content-Type': contentType,
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    });
    response.end(buf);
    return true;
  } catch (err) {
    if (err.code === 'ENOENT' && fallback) {
      const fallbackPath = path.resolve(staticRoot, fallback);
      const buf = await fs.readFile(fallbackPath);
      response.writeHead(200, {
        'Content-Type': STATIC_CONTENT_TYPES['.html'],
        'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff',
      });
      response.end(buf);
      return true;
    }
    const e = new Error('not_found');
    e.statusCode = 404;
    e.code = 'not_found';
    throw e;
  }
}

function matchVersionDetail(method, segments) {
  if (segments.length !== 3 || segments[0] !== 'admin' || segments[1] !== 'versions') return null;
  return segments[2];
}

async function withAuth(request, response, ctx, fn) {
  await authenticateAdmin(request, ctx);
  return fn();
}

export async function handleAdminRoutes(request, response, ctx) {
  const url = new URL(request.url, `http://${request.headers.host || 'localhost'}`);
  const segments = url.pathname.split('/').filter(Boolean).map(decodeURIComponent);
  if (segments[0] !== 'admin') return false;

  // Public admin routes: SRP handshake + params + legacy login (deprecated).
  if (request.method === 'GET' && segments.length === 3 && segments[1] === 'srp' && segments[2] === 'params') {
    try {
      handleSrpParams(response, ctx);
    } catch (err) {
      errorResponse(response, err);
    }
    return true;
  }
  if (request.method === 'POST' && segments.length === 3 && segments[1] === 'srp' && segments[2] === 'challenge') {
    try {
      await handleSrpChallenge(request, response, ctx);
    } catch (err) {
      errorResponse(response, err);
    }
    return true;
  }
  if (request.method === 'POST' && segments.length === 3 && segments[1] === 'srp' && segments[2] === 'verify') {
    try {
      await handleSrpVerify(request, response, ctx);
    } catch (err) {
      errorResponse(response, err);
    }
    return true;
  }
  if (request.method === 'POST' && segments.length === 2 && segments[1] === 'login') {
    try {
      await handleLegacyLogin(request, response, ctx);
    } catch (err) {
      errorResponse(response, err);
    }
    return true;
  }

  // Everything else under /admin/* requires a valid session.
  let session;
  try {
    session = await authenticateAdmin(request, ctx);
  } catch (err) {
    errorResponse(response, err, 401);
    return true;
  }

  try {
    if (request.method === 'POST' && segments.length === 2 && segments[1] === 'logout') {
      handleLogout(request, response, ctx);
      return true;
    }

    if (request.method === 'GET' && segments.length === 2 && segments[1] === 'session') {
      handleSession(request, response, ctx);
      return true;
    }

    if (request.method === 'GET' && segments.length === 2 && segments[1] === 'versions') {
      const platform = url.searchParams.get('platform') || undefined;
      const rows = listVersions(ctx.db, platform);
      json(response, 200, { versions: rows });
      return true;
    }

    if (segments.length === 4 && segments[1] === 'versions' && segments[3] === 'publish' && request.method === 'POST') {
      return await handlePublish(request, response, ctx, session, segments[2]);
    }

    if (request.method === 'GET' && segments.length === 3 && segments[1] === 'static') {
      return await serveStatic(response, ctx.staticRoot, segments[2]);
    }

    if (request.method === 'GET' && segments.length === 1) {
      return await serveStatic(response, ctx.staticRoot, 'index.html');
    }

    const versionId = matchVersionDetail(request.method, segments);
    if (versionId) {
      if (request.method === 'GET') {
        const row = findVersionById(ctx.db, versionId);
        if (!row) {
          const err = new Error('Version not found');
          err.statusCode = 404;
          err.code = 'version_not_found';
          throw err;
        }
        json(response, 200, row);
        return true;
      }
      if (request.method === 'PATCH') {
        const body = await parseJsonBody(request, 64 * 1024);
        if (!body || typeof body !== 'object') {
          const err = new Error('Invalid JSON body');
          err.statusCode = 400;
          err.code = 'invalid_json';
          throw err;
        }
        const allowed = new Set(['notes']);
        const attempted = Object.keys(body).filter((k) => !allowed.has(k));
        if (attempted.length > 0) {
          const err = new Error(`Cannot modify fields: ${attempted.join(', ')}`);
          err.statusCode = 422;
          err.code = 'immutable_field';
          throw err;
        }
        if (typeof body.notes !== 'string') {
          const err = new Error('notes must be a string');
          err.statusCode = 400;
          err.code = 'invalid_notes';
          throw err;
        }
        updateVersionNotes(ctx.db, versionId, body.notes);
        insertAudit(ctx.db, {
          actor: session.user || 'admin',
          action: 'version.patch',
          detail: `id=${versionId} field=notes`,
        });
        const row = findVersionById(ctx.db, versionId);
        json(response, 200, row);
        return true;
      }
      if (request.method === 'DELETE') {
        const row = deleteVersion(ctx.db, versionId);
        insertAudit(ctx.db, {
          actor: session.user || 'admin',
          action: 'version.delete',
          platform: row.platform,
          version: row.version,
          detail: `id=${versionId}`,
        });
        noContent(response);
        return true;
      }
    }

    if (request.method === 'GET' && segments.length === 2 && segments[1] === 'audit') {
      const limitRaw = Number.parseInt(url.searchParams.get('limit') || '100', 10);
      const limit = Number.isFinite(limitRaw) && limitRaw > 0 ? Math.min(limitRaw, 500) : 100;
      const rows = listAudit(ctx.db, limit);
      json(response, 200, { audit: rows });
      return true;
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
