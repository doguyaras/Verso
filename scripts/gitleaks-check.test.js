'use strict';
// GITLEAKS=<gitleaks ikilisi> node --test scripts/gitleaks-check.test.js
// Gercek gitleaks ikilisiyle gecici git depolarinda kosar. Ikili yoksa test ATLANMAZ, basarisiz olur
// (sessizce atlanan secret taramasi testi, kirik tarayiciyi yesil gosterir).
const { test, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync, spawnSync } = require('node:child_process');

const WRAPPER = path.join(__dirname, 'gitleaks-check.sh');
const CONFIG_LINT = path.join(__dirname, 'config-lint.js');
const BIN = process.env.GITLEAKS || 'gitleaks';

// Sahte secret'lar KAYNAKTA literal olarak durmaz, calisma aninda uretilir: aksi halde bu dosya deponun kendi
// gitleaks taramasinda bulgu olur. Rastgele govde, gitleaks'in "EXAMPLE" gibi stopword allowlist'ine takilmaz.
const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
function fakeAwsKeyId() {
  const bytes = crypto.randomBytes(16);
  return ['AK', 'IA'].join('') + Array.from(bytes, (b) => B32[b % 32]).join('');
}
function fakePrivateKeyBlock() {
  const body = crypto.randomBytes(384).toString('base64').match(/.{1,64}/g).join('\n');
  const dash = '-'.repeat(5);
  return `${dash}BEGIN ${'PRIVATE'} KEY${dash}\n${body}\n${dash}END ${'PRIVATE'} KEY${dash}\n`;
}

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
function wrapper(mode, target = repo, env = {}) {
  const r = spawnSync('bash', [WRAPPER, mode, target], {
    encoding: 'utf8', env: { ...process.env, GITLEAKS: BIN, ...env },
  });
  return { code: r.status, out: r.stdout + r.stderr };
}

beforeEach(() => {
  repo = fs.mkdtempSync(path.join(os.tmpdir(), 'gitleaks-check-'));
  git('init', '-q', '-b', 'main');
  write('README.md', '# temiz depo\n');
  write('src/main/resources/config/order.yml', 'spring:\n  datasource:\n    password: ${SECRET_DB_PASSWORD}\n');
  commitAll('init');
});

afterEach(() => {
  fs.rmSync(repo, { recursive: true, force: true });
});

test('gitleaks ikilisi mevcut (yoksa bu test kirmizi; diger testler de kosamaz)', () => {
  const r = spawnSync(BIN, ['version'], { encoding: 'utf8' });
  assert.equal(r.status, 0, `gitleaks calismadi (GITLEAKS=${BIN}): ${r.error || r.stderr}`);
  assert.match(r.stdout, /^8\./);
});

test('(1) gecmise girmis sahte AWS anahtari + private key blogu: exit 1, bulgular raporlanir, secret log\'a yazilmaz', () => {
  const aws = fakeAwsKeyId();
  write('deploy/aws.env', `AWS_ACCESS_KEY_ID=${aws}\n`);
  write('keys/order.pem', fakePrivateKeyBlock());
  commitAll('oops');
  for (const mode of ['history', 'all']) {
    const r = wrapper(mode);
    assert.equal(r.code, 1, `${mode}: ${r.out}`);
    assert.match(r.out, /"RuleID": *"aws-access-token"/);
    assert.match(r.out, /"RuleID": *"private-key"/);
    assert.match(r.out, /history IHLAL — 2 bulgu/);
    assert.ok(!r.out.includes(aws), '--redact: anahtar cikti/log\'da gorunmemeli');
  }
});

test('(2) temiz depo: history, tree ve all exit 0', () => {
  for (const mode of ['history', 'tree', 'all']) {
    const r = wrapper(mode);
    assert.equal(r.code, 0, `${mode}: ${r.out}`);
  }
});

test('commit\'lenmemis secret: tree yakalar (1), history yakalamaz (0) — iki mod birbirinin yerine gecmez', () => {
  write('deploy/aws.env', `AWS_ACCESS_KEY_ID=${fakeAwsKeyId()}\n`);
  assert.equal(wrapper('tree').code, 1);
  assert.equal(wrapper('history').code, 0);
  assert.equal(wrapper('all').code, 1);
});

test('commit\'lenip sonra silinen secret: history yakalar (1), tree temiz (0) — gecmis taramasi sart', () => {
  write('deploy/aws.env', `AWS_ACCESS_KEY_ID=${fakeAwsKeyId()}\n`);
  commitAll('oops');
  fs.rmSync(path.join(repo, 'deploy/aws.env'));
  commitAll('remove');
  assert.equal(wrapper('tree').code, 0);
  const h = wrapper('history');
  assert.equal(h.code, 1, h.out);
  assert.match(h.out, /"Commit": *"[0-9a-f]{40}"/);
});

test('GITLEAKS_LOG_OPTS ile yalniz PR araligi taranir', () => {
  write('deploy/aws.env', `AWS_ACCESS_KEY_ID=${fakeAwsKeyId()}\n`);
  commitAll('eski sizinti');
  const base = git('rev-parse', 'HEAD').trim();
  write('docs/x.md', 'temiz\n');
  commitAll('yeni temiz commit');
  assert.equal(wrapper('history', repo, { GITLEAKS_LOG_OPTS: `${base}..HEAD` }).code, 0);
  assert.equal(wrapper('history', repo, { GITLEAKS_LOG_OPTS: '' }).code, 1);
});

test('fail-closed: git deposu olmayan hedef, olmayan yol, ikili yok, bilinmeyen mod → exit 3', () => {
  const notRepo = fs.mkdtempSync(path.join(os.tmpdir(), 'gitleaks-norepo-'));
  try {
    // ham gitleaks burada exit 1 + "no leaks found in partial scan" verir; sarmalayici bunu "temiz" ya da "ihlal" saymaz
    const raw = spawnSync(BIN, ['git', '--no-banner', '--exit-code', '1', notRepo], { encoding: 'utf8' });
    assert.equal(raw.status, 1);
    const r = wrapper('history', notRepo);
    assert.equal(r.code, 3, r.out);
    assert.match(r.out, /DOGRULANAMADI/);
  } finally {
    fs.rmSync(notRepo, { recursive: true, force: true });
  }
  assert.equal(wrapper('tree', path.join(repo, 'no-such-dir')).code, 3);
  assert.equal(wrapper('all', repo, { GITLEAKS: '/nonexistent/gitleaks' }).code, 3);
  assert.equal(wrapper('bogus').code, 3);
});

test('gitleaks dusuk entropili literal fallback\'i YAKALAMAZ; config-lint yakalar (iki kontrol birbirini tamamlar)', () => {
  write('src/main/resources/config/order.yml', 'spring:\n  datasource:\n    password: ${SECRET_DB_PASSWORD:changeme}\n');
  commitAll('fallback');
  assert.equal(wrapper('all').code, 0);
  const lint = spawnSync(process.execPath, [CONFIG_LINT, path.join(repo, 'src/main/resources/config/order.yml')], { encoding: 'utf8' });
  assert.equal(lint.status, 1, lint.stderr);
});

// Verso eki (ikinci tur security review B13): pre-commit commit'e girecek ICERIGI taramali, calisma agacini degil.
test('staged modu: index\'teki secret yakalanir (1), calisma kopyasi temizlenmis olsa bile', () => {
  write('leak.txt', `AWS_ACCESS_KEY_ID=${fakeAwsKeyId()}\n`);
  git('add', 'leak.txt');
  write('leak.txt', 'clean\n');                       // calisma agaci temiz, index'te secret var
  assert.equal(wrapper('staged').code, 1);
  assert.equal(wrapper('tree').code, 0, 'tree modu bu durumu goremez (bulgunun nedeni)');
});

test('staged modu: gitignore\'lu dosyadaki secret commit\'i engellemez (0)', () => {
  write('.gitignore', '.env\n');
  commitAll('ignore');
  write('.env', `AWS_ACCESS_KEY_ID=${fakeAwsKeyId()}\n`);
  write('README.md', '# degisti\n');
  git('add', 'README.md');
  assert.equal(wrapper('staged').code, 0);
  assert.equal(wrapper('tree').code, 1, 'tree modu gitignore\'lu dosyayi da tarar (yanlis pozitif)');
});
