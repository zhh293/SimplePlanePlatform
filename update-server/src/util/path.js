import path from 'node:path';

export function safeSegment(value, label) {
  if (!value || value === '.' || value === '..' || value.includes('/') || value.includes('\\')) {
    const error = new Error(`Invalid ${label}`);
    error.statusCode = 400;
    error.code = 'invalid_path_segment';
    throw error;
  }
  return value;
}

export function resolveUnderRoot(rootDir, segments) {
  const resolved = path.resolve(rootDir, ...segments);
  const rootWithSep = rootDir.endsWith(path.sep) ? rootDir : `${rootDir}${path.sep}`;
  if (!resolved.startsWith(rootWithSep)) {
    const error = new Error('Invalid file path');
    error.statusCode = 400;
    error.code = 'path_traversal_blocked';
    throw error;
  }
  return resolved;
}

const FILE_NAME_RE = /^[A-Za-z0-9._-]+$/;
const ALLOWED_EXTENSIONS = new Set(['.zip', '.gz', '.sig', '.exe', '.msi']);

export function validateFileName(name, { requireExtension = true } = {}) {
  if (!name || name.length > 255) {
    const error = new Error('Invalid file name');
    error.statusCode = 400;
    error.code = 'invalid_file_name';
    throw error;
  }
  if (!FILE_NAME_RE.test(name)) {
    const error = new Error('Invalid file name');
    error.statusCode = 400;
    error.code = 'invalid_file_name';
    throw error;
  }
  if (requireExtension) {
    const ext = path.extname(name).toLowerCase();
    if (!ALLOWED_EXTENSIONS.has(ext)) {
      const error = new Error(`File extension not allowed: ${ext}`);
      error.statusCode = 400;
      error.code = 'invalid_file_extension';
      throw error;
    }
  }
  return name;
}

export function signatureNameFor(packageName) {
  const ext = ['.tar.gz', '.zip', '.exe', '.msi'].find((e) => packageName.endsWith(e));
  if (!ext) {
    const error = new Error('Package file must end with .zip/.tar.gz/.exe/.msi');
    error.statusCode = 422;
    error.code = 'invalid_package_extension';
    throw error;
  }
  return `${packageName}.sig`;
}

export function isPairedSignature(packageName, signatureName) {
  return signatureName === signatureNameFor(packageName);
}
