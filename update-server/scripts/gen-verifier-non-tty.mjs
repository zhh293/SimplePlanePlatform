#!/usr/bin/env node

// Non-interactive verifier generator: takes password from stdin or argv.
// Used by tests, e2e scripts, and CI flows where TTY isn't available.
//
// Usage:
//   node scripts/gen-verifier-non-tty.mjs <password>
//   echo "pw" | node scripts/gen-verifier-non-tty.mjs

import process from 'node:process';
import crypto from 'node:crypto';
import { computeVerifier, SRP_USERNAME } from '../src/srp.js';
import { b64urlEncode, padToBuffer } from '../src/srp-group.js';

function readStdin() {
  return new Promise((resolve, reject) => {
    let data = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (chunk) => { data += chunk; });
    process.stdin.on('end', () => resolve(data.replace(/\r?\n$/, '')));
    process.stdin.on('error', reject);
  });
}

async function main() {
  let password = process.argv[2];
  if (!password) {
    if (!process.stdin.isTTY) {
      password = await readStdin();
    } else {
      process.stderr.write('usage: gen-verifier-non-tty.mjs <password>\n');
      process.exit(1);
    }
  }
  if (!password) {
    process.stderr.write('error: empty password\n');
    process.exit(1);
  }

  const salt = crypto.randomBytes(16);
  const v = computeVerifier(salt, SRP_USERNAME, password);
  const verifier = {
    username: SRP_USERNAME,
    salt: salt.toString('base64'),
    verifier: b64urlEncode(padToBuffer(v)),
    kdf: 'sha256-double-h',
    created_at: new Date().toISOString(),
  };
  process.stdout.write(JSON.stringify(verifier));
}

main().catch((err) => {
  process.stderr.write(`error: ${err.message}\n`);
  process.exit(1);
});
