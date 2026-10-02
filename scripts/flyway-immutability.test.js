'use strict';
// node --test scripts/flyway-immutability.test.js
const { test, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const script = require('./flyway-immutability.js');

let repo;
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

beforeEach(() => {
  repo = fs.mkdtempSync(path.join(os.tmpdir(), 'flyway-imm-'));
  git('init', '-q', '-b', 'develop');
  write('svc/src/main/resources/db/migration/V1__init.sql', 'CREATE SCHEMA s;');
  write('svc/src/main/resources/db/migration/R__seed.sql', '-- seed');
  write('svc/src/main/resources/db/migration/nested/V2__nested.sql', 'SELECT 1;');
  commitAll('base');
  git('checkout', '-q', '-b', 'feature/x');
});

afterEach(() => {
  fs.rmSync(repo, { recursive: true, force: true });
});

test('regex: V dosyalari eslesir, R ve migration disi eslesmez', () => {
  assert.equal(script.isMigrationPath('a/db/migration/V1__x.sql'), true);
  assert.equal(script.isMigrationPath('a/db/migration/sub/V9__x.SQL'), true);
  assert.equal(script.isMigrationPath('a\\db\\migration\\V1__x.sql'), true); // Windows
  assert.equal(script.isMigrationPath('a/db/migration/R__x.sql'), false);
  assert.equal(script.isMigrationPath('a/db/other/V1__x.sql'), false);
  assert.equal(script.isMigrationPath('a/db/migration/V1__x.txt'), false);
});

test('base ref guvenligi', () => {
  assert.throws(() => script.resolveBase('--upload-pack=x'));
  assert.equal(script.resolveBase('develop'), 'develop');
});

test('temiz branch: OK', () => {
  const r = script.check({ baseRef: 'develop', cwd: repo });
  assert.equal(r.violations.length, 0);
});

test("base'teki V dosyasini degistirmek ihlal (working tree)", () => {
  write('svc/src/main/resources/db/migration/V1__init.sql', 'CREATE SCHEMA s; -- changed');
  const r = script.check({ baseRef: 'develop', cwd: repo });
  assert.deepEqual(r.violations.map((v) => v.status), ['M']);
});

// Ucuncu tur review N6: pre-commit calisma agacini inceliyordu. Degisiklik stage'de, calisma kopyasi geri alinmis:
// commit edilecek icerik ihlal iceriyor ama working tree karsilastirmasi temiz gorunuyordu.
test("--staged: stage'deki degisiklik calisma kopyasi geri alinsa da ihlal; stage'lenmemis degisiklik sayilmaz", () => {
  const v1 = 'svc/src/main/resources/db/migration/V1__init.sql';
  write(v1, 'CREATE SCHEMA s; -- changed');
  git('add', v1);
  write(v1, 'CREATE SCHEMA s;');
  assert.deepEqual(script.check({ baseRef: 'develop', cwd: repo, staged: true }).violations.map((v) => v.file), [v1]);
  assert.equal(script.check({ baseRef: 'develop', cwd: repo }).violations.length, 0);

  git('reset', '-q', '--', v1);
  write(v1, 'CREATE SCHEMA s; -- unstaged edit');
  assert.equal(script.check({ baseRef: 'develop', cwd: repo, staged: true }).violations.length, 0);
});

test("base'teki V dosyasini silmek ihlal (commit'li)", () => {
  fs.rmSync(path.join(repo, 'svc/src/main/resources/db/migration/V1__init.sql'));
  commitAll('delete');
  const r = script.check({ baseRef: 'develop', cwd: repo });
  assert.deepEqual(r.violations.map((v) => v.status), ['D']);
});

test('git mv ile yeniden adlandirma ihlal (D + A)', () => {
  git('mv', 'svc/src/main/resources/db/migration/V1__init.sql', 'svc/src/main/resources/db/migration/V1__renamed.sql');
  const r = script.check({ baseRef: 'develop', cwd: repo });
  assert.equal(r.violations.some((v) => v.status === 'D'), true);
});

test('ic ice klasordeki base migration da korunur', () => {
  write('svc/src/main/resources/db/migration/nested/V2__nested.sql', 'SELECT 2;');
  const r = script.check({ baseRef: 'develop', cwd: repo });
  assert.equal(r.violations.length, 1);
});

test("branch'te eklenen yeni V dosyasi serbest, sonradan duzenlenebilir", () => {
  write('svc/src/main/resources/db/migration/V3__new.sql', 'SELECT 3;');
  commitAll('add v3');
  write('svc/src/main/resources/db/migration/V3__new.sql', 'SELECT 33;');
  const r = script.check({ baseRef: 'develop', cwd: repo });
  assert.equal(r.violations.length, 0);
});

test('R__ dosyalari serbest', () => {
  write('svc/src/main/resources/db/migration/R__seed.sql', '-- seed v2');
  const r = script.check({ baseRef: 'develop', cwd: repo });
  assert.equal(r.violations.length, 0);
});

test('check-file: base dosyasi korunur, yeni dosya korunmaz, migration disi dosya korunmaz', () => {
  assert.equal(script.checkFile('svc/src/main/resources/db/migration/V1__init.sql', { baseRef: 'develop', cwd: repo }).protected, true);
  assert.equal(script.checkFile('svc/src/main/resources/db/migration/V4__later.sql', { baseRef: 'develop', cwd: repo }).protected, false);
  assert.equal(script.checkFile('svc/src/main/java/X.java', { baseRef: 'develop', cwd: repo }).protected, false);
  // mutlak yol ve ters bolu
  const abs = path.join(repo, 'svc\\src\\main\\resources\\db\\migration\\V1__init.sql');
  assert.equal(script.checkFile(abs, { baseRef: 'develop', cwd: repo }).protected, true);
});

test('proje koku repo kokunun alt klasoruyse (monorepo) base dosyasi yine korunur', () => {
  // cwd = alt klasor; ls-tree pathspec'i cwd'ye gore olsaydi dosya "yok" sanilir ve yazma serbest kalirdi (fail-open)
  const sub = path.join(repo, 'svc');
  const r = script.checkFile(path.join(repo, 'svc/src/main/resources/db/migration/V1__init.sql'), { baseRef: 'develop', cwd: sub });
  assert.equal(r.protected, true);
  const n = script.checkFile(path.join(repo, 'svc/src/main/resources/db/migration/V9__new.sql'), { baseRef: 'develop', cwd: sub });
  assert.equal(n.protected, false);
});

test('acik base yoksa aday zinciri: origin/develop yok, local main var → main kullanilir', () => {
  delete process.env.FLYWAY_BASE_REF;
  git('branch', '-m', 'develop', 'main');                       // base branch'in adi main
  write('svc/src/main/resources/db/migration/V1__init.sql', 'CREATE SCHEMA s; -- changed');
  const r = script.check({ cwd: repo });                        // ne arguman ne env
  assert.equal(r.base, 'main');
  assert.equal(r.violations.length, 1);
  const f = script.checkFile(path.join(repo, 'svc/src/main/resources/db/migration/V1__init.sql'), { cwd: repo });
  assert.equal(f.protected, true);
  assert.equal(f.base, 'main');
});

test('hicbir aday yoksa: head → base HEAD olarak raporlanir', () => {
  delete process.env.FLYWAY_BASE_REF;
  git('branch', '-m', 'develop', 'trunk');                      // adaylardan hicbiri yok
  const f = script.checkFile(path.join(repo, 'svc/src/main/resources/db/migration/V1__init.sql'), { onMissingBase: 'head', cwd: repo });
  assert.equal(f.protected, true);
  assert.equal(f.base, 'HEAD');
  assert.throws(() => script.check({ cwd: repo }));             // fail modu: exit 3'e karsilik hata
});

test('base yoksa: fail → hata; head → HEAD agacina gore', () => {
  assert.throws(() => script.check({ baseRef: 'no-such-branch', cwd: repo }));
  const r = script.checkFile('svc/src/main/resources/db/migration/V1__init.sql', {
    baseRef: 'no-such-branch', onMissingBase: 'head', cwd: repo,
  });
  assert.equal(r.protected, true);
});

test('main: cikis kodlari', () => {
  const cwd = process.cwd();
  process.chdir(repo);
  try {
    assert.equal(script.main(['check', '--base', 'develop']), 0);
    write('svc/src/main/resources/db/migration/V1__init.sql', 'x');
    assert.equal(script.main(['check', '--base', 'develop']), 1);
    assert.equal(script.main(['check', '--base', 'no-such']), 3);
    assert.equal(script.main(['bogus']), 3);
  } finally {
    process.chdir(cwd);
  }
});
