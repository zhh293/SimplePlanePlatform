import test from 'node:test';
import assert from 'node:assert/strict';
import { parseVersion, compareVersions, isStrictlyGreater } from '../src/util/version.js';

test('parseVersion returns null for invalid input', () => {
  assert.equal(parseVersion(''), null);
  assert.equal(parseVersion('1.2'), null);
  assert.equal(parseVersion('1.2.3.4'), null);
  assert.equal(parseVersion('abc'), null);
  assert.equal(parseVersion(null), null);
  assert.equal(parseVersion(undefined), null);
});

test('parseVersion accepts plain and v-prefixed semver', () => {
  assert.deepEqual(parseVersion('0.2.3'), { major: 0, minor: 2, patch: 3, prerelease: [] });
  assert.deepEqual(parseVersion('v1.2.3'), { major: 1, minor: 2, patch: 3, prerelease: [] });
  assert.deepEqual(parseVersion('1.0.0-alpha.1'), {
    major: 1, minor: 0, patch: 0, prerelease: ['alpha', '1'],
  });
});

test('parseVersion strips build metadata', () => {
  const parsed = parseVersion('1.0.0+build.123');
  assert.deepEqual(parsed, { major: 1, minor: 0, patch: 0, prerelease: [] });
});

test('compareVersions: basic ordering', () => {
  assert.equal(compareVersions(parseVersion('0.0.1'), parseVersion('0.0.2')), -1);
  assert.equal(compareVersions(parseVersion('0.1.0'), parseVersion('0.0.9')), 1);
  assert.equal(compareVersions(parseVersion('1.0.0'), parseVersion('0.99.99')), 1);
  assert.equal(compareVersions(parseVersion('1.2.3'), parseVersion('1.2.3')), 0);
});

test('compareVersions: prerelease lower than release', () => {
  assert.equal(compareVersions(parseVersion('1.0.0-alpha'), parseVersion('1.0.0')), -1);
  assert.equal(compareVersions(parseVersion('1.0.0'), parseVersion('1.0.0-alpha')), 1);
  assert.equal(compareVersions(parseVersion('1.0.0-alpha'), parseVersion('1.0.0-beta')), -1);
  assert.equal(compareVersions(parseVersion('1.0.0-alpha.1'), parseVersion('1.0.0-alpha.2')), -1);
  assert.equal(compareVersions(parseVersion('1.0.0-1'), parseVersion('1.0.0-2')), -1);
});

test('isStrictlyGreater accepts only strictly greater', () => {
  assert.equal(isStrictlyGreater('0.2.3', '0.2.2'), true);
  assert.equal(isStrictlyGreater('0.2.2', '0.2.2'), false);
  assert.equal(isStrictlyGreater('0.2.1', '0.2.2'), false);
  assert.equal(isStrictlyGreater('1.0.0', '0.99.99'), true);
  assert.equal(isStrictlyGreater('1.0.0-alpha', '1.0.0-alpha'), false);
  assert.equal(isStrictlyGreater('1.0.0', '1.0.0-alpha'), true);
  assert.equal(isStrictlyGreater('garbage', '0.2.2'), false);
});
