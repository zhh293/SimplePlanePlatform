export function json(res, status, body, headers = {}) {
  const payload = body === undefined ? '' : JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    ...headers,
  });
  res.end(payload);
}

export function noContent(res, headers = {}) {
  res.writeHead(204, {
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    ...headers,
  });
  res.end();
}

export function errorResponse(res, error, fallbackStatus = 500) {
  const statusCode = Number.isInteger(error?.statusCode) ? error.statusCode : fallbackStatus;
  const code = error?.code || 'internal_error';
  const message = error?.message || 'internal_server_error';
  if (statusCode >= 500) console.error(`[update-server] ${message}`, error?.stack || '');
  return json(res, statusCode, { error: code, message });
}

export function readBody(req, maxBytes = 64 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    let aborted = false;
    req.on('data', (chunk) => {
      if (aborted) return;
      total += chunk.length;
      if (total > maxBytes) {
        aborted = true;
        const error = new Error('Request body too large');
        error.statusCode = 413;
        error.code = 'request_too_large';
        req.destroy();
        reject(error);
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (aborted) return;
      resolve(Buffer.concat(chunks));
    });
    req.on('error', (err) => {
      if (aborted) return;
      reject(err);
    });
  });
}

export function parseJsonBody(req, maxBytes) {
  return readBody(req, maxBytes).then((buf) => {
    if (buf.length === 0) return null;
    try {
      return JSON.parse(buf.toString('utf8'));
    } catch {
      const error = new Error('Invalid JSON body');
      error.statusCode = 400;
      error.code = 'invalid_json';
      throw error;
    }
  });
}
