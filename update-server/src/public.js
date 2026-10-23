import { json, noContent } from './util/http.js';
import {
  buildLatestManifestResponse,
  resolvePlatformUpdate,
  serveDownload,
} from './manifest.js';

export async function handlePublicRoutes(request, response, ctx) {
  const url = new URL(request.url, `http://${request.headers.host || 'localhost'}`);
  const segments = url.pathname.split('/').filter(Boolean).map(decodeURIComponent);

  if (request.method === 'GET' && url.pathname === '/healthz') {
    json(response, 200, { status: 'ok', service: 'simpleplane-update-server' });
    return true;
  }

  if ((request.method === 'GET' || request.method === 'HEAD') && url.pathname === '/latest.json') {
    const payload = await buildLatestManifestResponse(ctx.db, request, ctx.publicBaseUrl, ctx.dataDir);
    json(response, 200, payload, { 'Cache-Control': 'no-store' });
    return true;
  }

  if ((request.method === 'GET' || request.method === 'HEAD')
      && segments[0] === 'download' && segments.length === 4) {
    await serveDownload(request, response, ctx.dataDir, segments[1], segments[2], segments[3]);
    return true;
  }

  if (request.method === 'GET'
      && segments[0] === 'api' && segments[1] === 'v1' && segments[2] === 'updates'
      && segments.length === 6) {
    const update = await resolvePlatformUpdate(
      ctx.db,
      request,
      segments[3],
      segments[4],
      segments[5],
      ctx.dataDir,
      ctx.publicBaseUrl,
    );
    if (!update) {
      noContent(response);
      return true;
    }
    json(response, 200, update, { 'Cache-Control': 'no-store' });
    return true;
  }

  return false;
}
