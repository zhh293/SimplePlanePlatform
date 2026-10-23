#!/usr/bin/env node

import fs from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

function argsToObject(argv) {
  const args = {};
  for (let index = 0; index < argv.length; index += 1) {
    const item = argv[index];
    if (!item.startsWith('--')) continue;
    const key = item.slice(2);
    args[key] = argv[index + 1] && !argv[index + 1].startsWith('--') ? argv[++index] : true;
  }
  return args;
}

function required(args, name) {
  if (!args[name]) throw new Error(`Missing --${name}`);
  return args[name];
}

function validVersion(value) {
  return /^v?\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/.test(value);
}

function basename(value, name) {
  const file = path.basename(value);
  if (file !== value || file === '.' || file === '..') throw new Error(`Invalid ${name} path`);
  return file;
}

async function copyArtifact(source, destination) {
  await fs.access(source);
  await fs.copyFile(source, destination);
}

const args = argsToObject(process.argv.slice(2));
const version = required(args, 'version').replace(/^v/, '');
const dataDir = path.resolve(args['data-dir'] || path.join(process.cwd(), 'data'));
const notes = args.notes || '';
const pubDate = args['pub-date'] || new Date().toISOString();

if (!validVersion(version)) throw new Error(`Invalid version: ${version}`);
if (!args.windows || !args['windows-sig'] || !args.macos || !args['macos-sig']) {
  throw new Error('Both Windows and macOS artifacts are required for a complete release');
}

const releaseDir = path.join(dataDir, 'releases', version);
await fs.mkdir(releaseDir, { recursive: true });

const windowsFile = basename(args.windows, 'windows artifact');
const windowsSigFile = basename(args['windows-sig'], 'windows signature');
const macosFile = basename(args.macos, 'macOS artifact');
const macosSigFile = basename(args['macos-sig'], 'macOS signature');

await copyArtifact(path.resolve(args.windows), path.join(releaseDir, windowsFile));
await copyArtifact(path.resolve(args['windows-sig']), path.join(releaseDir, windowsSigFile));
await copyArtifact(path.resolve(args.macos), path.join(releaseDir, macosFile));
await copyArtifact(path.resolve(args.macos-sig), path.join(releaseDir, macosSigFile));

const manifest = {
  version,
  notes,
  pub_date: pubDate,
  platforms: {
    'windows-x86_64': { file: windowsFile, signatureFile: windowsSigFile },
    'darwin-aarch64': { file: macosFile, signatureFile: macosSigFile },
  },
};

const latestPath = path.join(dataDir, 'latest.json');
const temporaryPath = `${latestPath}.tmp-${process.pid}`;
await fs.mkdir(dataDir, { recursive: true });
await fs.writeFile(temporaryPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
await fs.rename(temporaryPath, latestPath);

console.log(`Published SimplePlane ${version}`);
console.log(`Release files: ${releaseDir}`);
console.log(`Manifest: ${latestPath}`);
