'use strict';
// node --test scripts/migration-contract.test.js
// migration-contract.js (ADR-0040): a new Flyway migration with DROP or RENAME needs a "-- contract:" marker.
const { test, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const { inspect, check } = require('./migration-contract.js');

let repo;
const DIR = 'svc/src/main/resources/db/migration/';
function git(...args) {
  return execFileSync('git', args, { cwd: repo, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
}
function write(rel, content) {
  const p = path.join(repo, rel);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, content);
}
function commitAll(msg) {
  git('add', '-A');
  git('-c', 'user.email=t@t', '-c', 'user.name=t', 'commit', '-q', '-m', msg);
}
function run() {
  return check({ baseRef: 'develop', cwd: repo });
}

beforeEach(() => {
  repo = fs.mkdtempSync(path.join(os.tmpdir(), 'migration-contract-'));
  git('init', '-q', '-b', 'develop');
  // A base migration with a DROP is not this check's business (it is already released).
  write(`${DIR}V1__init.sql`, 'CREATE TABLE a (x int);\nALTER TABLE a DROP COLUMN x;\n');
  commitAll('base');
  git('checkout', '-q', '-b', 'feature/x');
});

afterEach(() => {
  fs.rmSync(repo, { recursive: true, force: true });
});

test('inspect: yorum ve tirnakli metindeki DROP/RENAME sayilmaz', () => {
  const sql = "-- Rollback: DROP INDEX i;\n/* RENAME */ INSERT INTO t VALUES ('drop table x');\nCREATE INDEX i ON t (c);";
  assert.deepEqual(inspect(sql), { destructive: [], marked: false });
});

test('inspect: DROP ve RENAME bulunur, kucuk harf ve cok satir dahil', () => {
  assert.deepEqual(inspect('alter table t\n  drop column c;\nALTER TABLE t RENAME TO u;').destructive, ['DROP', 'RENAME']);
});

test('inspect: isaret gerekcesiz ya da yorum disinda ise gecersiz', () => {
  assert.equal(inspect('-- contract: \nDROP TABLE t;').marked, false);
  assert.equal(inspect('-- contract: kisa\nDROP TABLE t;').marked, false);
  assert.equal(inspect('-- contract: v7 uygulamasi artik c kolonunu okumuyor\nDROP TABLE t;').marked, true);
});

test('check: yeni migration yalniz ekleme ise temiz', () => {
  write(`${DIR}V2__add.sql`, '-- Rollback: DROP TABLE b (only with the old image).\nCREATE TABLE b (y int);\n');
  commitAll('add');
  const r = run();
  assert.equal(r.scanned, 1);
  assert.deepEqual(r.violations, []);
});

test('check: isaretsiz DROP yeni migration ihlal, dosya adi ve kelime raporlanir', () => {
  write(`${DIR}V2__drop.sql`, 'ALTER TABLE a DROP COLUMN x;\n');
  write(`${DIR}V3__rename.sql`, 'ALTER TABLE a RENAME COLUMN y TO z;\n');
  commitAll('destructive');
  const r = run();
  assert.deepEqual(r.violations.map((v) => [v.file, v.words]), [
    [`${DIR}V2__drop.sql`, ['DROP']],
    [`${DIR}V3__rename.sql`, ['RENAME']],
  ]);
});

test('check: isaretli DROP gecer', () => {
  write(`${DIR}V2__drop.sql`, '-- contract: v7 artik x kolonunu yazmiyor, v8 ile birlikte yayinla\nALTER TABLE a DROP COLUMN x;\n');
  commitAll('marked');
  assert.deepEqual(run().violations, []);
});

test('check: base migration ve migration olmayan dosyadaki DROP taranmaz', () => {
  write('docs/notes.sql', 'DROP TABLE t;');
  write('svc/src/main/resources/db/other/V9__x.sql', 'DROP TABLE t;');
  commitAll('other');
  const r = run();
  assert.equal(r.scanned, 0);
  assert.deepEqual(r.violations, []);
});
