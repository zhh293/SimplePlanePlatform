#!/usr/bin/env node

// Generates an SRP-6a verifier for the admin password.
// Reads the password from stdin (no echo) twice for confirmation,
// performs basic strength checks, then writes the verifier JSON to stdout.
//
// Usage:
//   node scripts/generate-srp-credentials.mjs [--insecure-weak] > verifier.json
//
// Then:
//   sudo install -m 0400 -o simpleplane -g simpleplane verifier.json /etc/simpleplane-update/verifier
//   sudo systemctl restart simpleplane-update

import process from 'node:process';
import crypto from 'node:crypto';
import { computeVerifier, SRP_USERNAME } from '../src/srp.js';
import { b64urlEncode, padToBuffer } from '../src/srp-group.js';

const WEAK_PASSWORDS = new Set([
  'password', 'admin', '12345678', 'qwerty12', 'letmein!',
  'changeme', 'simpleplane', 'admin123', 'password123',
]);

function readPassword(prompt) {
  process.stderr.write(prompt);
  // Read stdin in raw mode, no echo.
  const fd = process.stdin.fd;
  const wasRaw = process.stdin.isTTY;
  process.stdin.setRawMode(true);
  let line = '';
  try {
    const buf = Buffer.alloc(1);
    while (true) {
      const n = require('fs').readSync(fd, buf, 0, 1);
      if (n === 0) break;
      const ch = buf[0];
      if (ch === 0x0d || ch === 0x0a) break; // CR/LF
      if (ch === 0x03) { // Ctrl-C
        process.stderr.write('\n');
        process.exit(1);
      }
      if (ch === 0x7f || ch === 0x08) { // DEL/BS
        if (line.length > 0) {
          line = line.slice(0, -1);
          process.stderr.write('\b \b');
        }
        continue;
      }
      line += String.fromCharCode(ch);
      process.stderr.write('*');
    }
  } finally {
    process.stdin.setRawMode(wasRaw);
    process.stderr.write('\n');
  }
  return line;
}

function checkStrength(password, allowWeak) {
  const issues = [];
  if (password.length < 12) issues.push('password must be at least 12 characters');
  if (!/[A-Z]/.test(password)) issues.push('must contain at least one uppercase letter');
  if (!/[a-z]/.test(password)) issues.push('must contain at least one lowercase letter');
  if (!/[0-9]/.test(password)) issues.push('must contain at least one digit');
  if (!/[^A-Za-z0-9]/.test(password)) issues.push('must contain at least one symbol');
  if (WEAK_PASSWORDS.has(password.toLowerCase())) issues.push('password is in the common-weak list');
  if (issues.length > 0 && !allowWeak) {
    throw new Error(`Weak password: ${issues.join('; ')}. Use --insecure-weak to override.`);
  }
  if (issues.length > 0) {
    process.stderr.write(`[warn] weak password: ${issues.join('; ')}\n`);
  }
}

function main() {
  const args = process.argv.slice(2);
  const allowWeak = args.includes('--insecure-weak');

  if (!process.stdin.isTTY) {
    process.stderr.write('error: this script must run in a TTY (interactive password input)\n');
    process.stderr.write('if running non-interactively, pipe a password via: scripts/gen-verifier-non-tty.mjs\n');
    process.exit(1);
  }

  const pw1 = readPassword('Enter admin password: ');
  const pw2 = readPassword('Confirm admin password: ');
  if (pw1 !== pw2) {
    process.stderr.write('error: passwords do not match\n');
    process.exit(1);
  }
  if (pw1.length === 0) {
    process.stderr.write('error: empty password\n');
    process.exit(1);
  }
  checkStrength(pw1, allowWeak);

  const salt = crypto.randomBytes(16);
  const v = computeVerifier(salt, SRP_USERNAME, pw1);
  const verifier = {
    username: SRP_USERNAME,
    salt: salt.toString('base64'),
    verifier: b64urlEncode(padToBuffer(v)),
    kdf: 'sha256-double-h',
    created_at: new Date().toISOString(),
  };

  // Output JSON to stdout (single line, no trailing newline) for redirect to file.
  process.stdout.write(JSON.stringify(verifier));
  process.stderr.write('[ok] verifier generated. Write to /etc/simpleplane-update/verifier with mode 0400.\n');
}

try {
  main();
} catch (err) {
  process.stderr.write(`error: ${err.message}\n`);
  process.exit(1);
}
