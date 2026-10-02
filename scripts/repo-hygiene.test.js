'use strict';
// node --test scripts/repo-hygiene.test.js
// Repository facts that Windows checkouts hide: the execute bit. The PostgreSQL entrypoint SOURCES a non-executable
// docker-entrypoint-initdb.d/*.sh instead of running it, so the script's "set -euo pipefail" would change the
// entrypoint's own shell; git hooks without the bit are skipped silently on Linux and macOS (first CI run,
// 2026-10-02). Files added from Windows get mode 100644 unless set explicitly.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const ROOT = path.join(__dirname, '..');

function trackedModes() {
  const out = execFileSync('git', ['ls-files', '-s'], { cwd: ROOT, encoding: 'utf8' });
  return out.split('\n').filter(Boolean).map((line) => {
    const [meta, file] = line.split('\t');
    return { mode: meta.split(' ')[0], file };
  });
}

test('shell scripts and git hooks are executable in git (mode 100755)', () => {
  const scripts = trackedModes().filter(({ file }) => file.endsWith('.sh') || file.startsWith('.githooks/'));
  assert.ok(scripts.length >= 10, `expected the repository's shell scripts, found ${scripts.length}`);
  const wrong = scripts.filter(({ mode }) => mode !== '100755').map(({ file, mode }) => `${mode} ${file}`);
  assert.deepEqual(wrong, [], 'fix with: git update-index --chmod=+x <file>');
});

test('the Maven wrapper is executable in git', () => {
  const mvnw = trackedModes().find(({ file }) => file === 'mvnw');
  assert.equal(mvnw && mvnw.mode, '100755');
});
