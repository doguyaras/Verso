// scripts/secrets-check.js finds the secret files compose would mount as directories, and dev-secrets.sh knows every
// secret the base stack mounts. Run: node --test scripts/secrets-check.test.js
'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { secretNames, check } = require('./secrets-check.js');

const ROOT = path.join(__dirname, '..');
const read = (f) => fs.readFileSync(path.join(ROOT, f), 'utf8');

test('reads the file secrets of a compose file, not the service-level secret lists', () => {
  const text = 'services:\n  app:\n    secrets:\n      - SECRET_A\nsecrets:\n  SECRET_A:\n    file: ./secrets/SECRET_A\n'
    + '  SECRET_B:\n    file: ./secrets/SECRET_B\n';
  assert.deepEqual(secretNames(text), ['SECRET_A', 'SECRET_B']);
});

test('a missing file, a directory Docker made and an empty file are each reported; a good file is not', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'verso-secrets-'));
  fs.writeFileSync(path.join(dir, 'GOOD'), 'value');
  fs.mkdirSync(path.join(dir, 'DIR'));
  fs.writeFileSync(path.join(dir, 'EMPTY'), '');
  assert.deepEqual(check(['GOOD', 'DIR', 'EMPTY', 'MISSING', 'GOOD'], dir), [
    { name: 'DIR', kind: 'directory' },
    { name: 'EMPTY', kind: 'empty' },
    { name: 'MISSING', kind: 'missing' },
  ]);
});

test('every secret of compose.yaml is one dev-secrets.sh creates, so the check always has a fix', () => {
  const script = read('scripts/dev-secrets.sh');
  const created = new Set([...script.matchAll(/^\s+(SECRET_[A-Z0-9_]+)\s/gm)].map((m) => m[1]));
  const mounted = secretNames(read('compose.yaml'));
  assert.ok(mounted.length > 10, 'compose.yaml mounts its secrets from ./secrets');
  assert.deepEqual(mounted.filter((n) => !created.has(n)), []);
});

test('the check prints names only, never a value', () => {
  const src = read('scripts/secrets-check.js');
  assert.doesNotMatch(src, /readFileSync\(file/);
});
