'use strict';
// GITLEAKS=<gitleaks ikilisi> node --test scripts/pre-commit.test.js
// .githooks/pre-commit'i gecici bir git deposunda gercek commit'lerle calistirir. CI'daki kuru calistirma bos index
// kullandigi icin hook'un ne yaptigindan bagimsiz yesildi: "staged" yerine "tree" taramasi ya da config-lint
// adiminin silinmesi fark edilmiyordu (ucuncu tur test review N5, P1/P2). Ikili yoksa test basarisiz olur.
const { test, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync, spawnSync } = require('node:child_process');

const ROOT = path.join(__dirname, '..');
const BIN = process.env.GITLEAKS || 'gitleaks';
// Hook'un calistirdigi her sey, depodaki yerleriyle (hook script'leri repo kokune gore cagirir).
const HOOK_FILES = ['.githooks/pre-commit', 'scripts/flyway-immutability.js', 'scripts/config-lint.js',
  'scripts/config-lint.pathspec', 'scripts/gitleaks-check.sh'];

// Sahte secret kaynakta literal durmaz (bu dosya deponun kendi taramasinda bulgu olmasin): calisma aninda uretilir.
const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
function fakeAwsKeyId() {
  return ['AK', 'IA'].join('') + Array.from(crypto.randomBytes(16), (b) => B32[b % 32]).join('');
}

const V1 = 'verso-app/src/main/resources/db/migration/V1__init.sql';
let repo;
function git(...args) {
  return execFileSync('git', args, { cwd: repo, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
}
function write(rel, content) {
  const p = path.join(repo, rel);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, content);
}
function commit(msg) {
  const r = spawnSync('git', ['-c', 'user.email=t@t', '-c', 'user.name=t', 'commit', '-q', '-m', msg], {
    cwd: repo, encoding: 'utf8', env: { ...process.env, GITLEAKS: BIN, FLYWAY_BASE_REF: 'develop' },
  });
  return { code: r.status, out: r.stdout + r.stderr };
}

beforeEach(() => {
  repo = fs.mkdtempSync(path.join(os.tmpdir(), 'pre-commit-'));
  git('init', '-q', '-b', 'develop');
  for (const f of HOOK_FILES) write(f, fs.readFileSync(path.join(ROOT, f)));
  // Linux/macOS git silently skips a hook without the execute bit (Windows does not check it): the copy must keep
  // it, otherwise every commit passes and the blocking tests fail for the wrong reason (first CI run, 2026-10-02).
  fs.chmodSync(path.join(repo, '.githooks/pre-commit'), 0o755);
  write('verso-app/src/main/resources/application.yml', 'spring:\n  application:\n    name: verso\n');
  write(V1, 'CREATE SCHEMA verso;\n');
  git('add', '-A');
  git('-c', 'user.email=t@t', '-c', 'user.name=t', 'commit', '-q', '-m', 'base');
  git('config', 'core.hooksPath', '.githooks');
  git('checkout', '-q', '-b', 'feature/x');
});

afterEach(() => {
  fs.rmSync(repo, { recursive: true, force: true });
});

test('temiz staged degisiklik commit edilir (hook exit 0)', () => {
  write('README.md', '# verso\n');
  git('add', 'README.md');
  const r = commit('docs: readme');
  assert.equal(r.code, 0, r.out);
  // Positive control: the hook really ran all three checks. A skipped hook (no execute bit, wrong hooksPath) would
  // otherwise pass this test and make the blocking tests below meaningless.
  assert.match(r.out, /flyway-immutability: OK/);
  assert.match(r.out, /gitleaks-check: staged OK/);
});

test('staged secret, calisma kopyasi temizlense de commit engellenir (gitleaks staged)', () => {
  write('notes.txt', `aws_access_key_id = ${fakeAwsKeyId()}\n`);
  git('add', 'notes.txt');
  write('notes.txt', 'temiz\n');
  const r = commit('chore: notes');
  assert.notEqual(r.code, 0, r.out);
});

test('staged fallback secret (.yaml), calisma kopyasi silinse de commit engellenir (config-lint staged)', () => {
  const f = 'verso-app/src/main/resources/application-prod.yaml';
  write(f, 'spring:\n  datasource:\n    password: ${SECRET_DB_PASSWORD:changeme}\n');
  git('add', f);
  fs.rmSync(path.join(repo, f));
  const r = commit('chore: prod config');
  assert.notEqual(r.code, 0, r.out);
  assert.match(r.out, /IHLAL/);
});

test("base'teki migration stage'de degisip calisma kopyasi geri alinsa da commit engellenir (flyway --staged)", () => {
  write(V1, 'CREATE SCHEMA verso; -- changed\n');
  git('add', V1);
  write(V1, 'CREATE SCHEMA verso;\n');
  const r = commit('fix: migration');
  assert.notEqual(r.code, 0, r.out);
  assert.match(r.out, /flyway-immutability: IHLAL/);
});
