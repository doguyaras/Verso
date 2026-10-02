'use strict';
// node --test scripts/config-lint.test.js
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const lint = require('./config-lint.js');

const SCRIPT = path.join(__dirname, 'config-lint.js');
const FIX = path.join(__dirname, 'fixtures', 'config-lint');
// Verso uyarlamasi: blueprint skeleton-example yerine projenin gercek config dosyalari (ADR-0007).
const APP_RES = path.join(__dirname, '..', 'verso-app', 'src', 'main', 'resources');

function run(...files) {
  const r = spawnSync(process.execPath, [SCRIPT, ...files], { encoding: 'utf8' });
  return { code: r.status, out: r.stdout + r.stderr };
}
function yaml(text, file = 'config/svc.yml') {
  return lint.lintText(text, file);
}
function tmpFile(name, content) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'config-lint-'));
  const p = path.join(dir, name);
  fs.writeFileSync(p, content);
  return p;
}

test('(a) secret key + literal fallback: file:line ile raporlanir, exit 1', () => {
  const v = yaml('spring:\n  datasource:\n    password: ${SECRET_DB_PASSWORD:changeme}\n');
  assert.deepEqual(v.map((x) => [x.line, x.rule, x.key]), [[3, 'secret-fallback', 'spring.datasource.password']]);
  const f = tmpFile('svc.yml', 'spring:\n  datasource:\n    password: ${SECRET_DB_PASSWORD:changeme}\n');
  const r = run(f);
  assert.equal(r.code, 1);
  assert.match(r.out, new RegExp(`${f.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}:3: \\[secret-fallback\\] spring\\.datasource\\.password`));
  assert.doesNotMatch(r.out, /changeme/, 'literal fallback CI log\'una yazilmamali');
});

test('(a) key secret gorunmese de env adi secret ise fallback yakalanir', () => {
  const v = yaml('app:\n  db-auth: ${SECRET_DB_PASSWORD:changeme}\n');
  assert.equal(v.length, 1);
  assert.equal(v[0].rule, 'secret-fallback');
});

test('(a) bos fallback ve ic ice literal fallback da fallback sayilir; ${A:${B}} serbest', () => {
  assert.equal(yaml('db:\n  password: ${SECRET_DB_PASSWORD:}\n').length, 1);
  assert.equal(yaml('db:\n  password: ${SECRET_DB_PASSWORD:${LEGACY_DB_PASSWORD:changeme}}\n').length, 1);
  assert.equal(yaml('db:\n  password: ${SECRET_DB_PASSWORD:${LEGACY_DB_PASSWORD}}\n').length, 0);
});

test('(a) rakam iceren env adi (S3) ve kucuk harfli property placeholder da yakalanir', () => {
  assert.equal(yaml('storage:\n  s3-secret-key: ${S3_SECRET_KEY:minioadmin}\n').length, 1);
  assert.equal(yaml('storage:\n  secret: ${storage.fallback-secret:minioadmin}\n').length, 1);
});

test('(a) flow mapping icindeki secret key fallback\'i da yakalanir', () => {
  const v = yaml('services:\n  sms: { base-url: "${SMS_URL}", api-key: "${SECRET_SMS_API_KEY:dev}" }\n');
  assert.deepEqual(v.map((x) => x.key), ['services.sms.api-key']);
});

test('${ENV} fallback\'siz: gecer (a ve b)', () => {
  assert.deepEqual(yaml('spring:\n  datasource:\n    password: ${SECRET_DB_PASSWORD}\n'), []);
  assert.deepEqual(yaml('service-jwt:\n  private-key-path: ${SECRET_ORDER_SIGNING_KEY_PATH}\n'), []);
});

test('/run/secrets yolu gecer: duz deger olarak ve secret dosya yolu fallback\'i olarak', () => {
  assert.deepEqual(yaml('spring:\n  datasource:\n    password: /run/secrets/db_password\n'), []);
  assert.deepEqual(yaml('jwt:\n  private-key-path: ${SECRET_KEY_PATH:/run/secrets/order_signing_key}\n'), []);
  // secret dosya yolu icin /run/secrets disi fallback: prod sessizce dev anahtariyla kalkmasin
  assert.equal(yaml('jwt:\n  private-key-path: ${SECRET_KEY_PATH:./dev-keys/order.pem}\n').length, 1);
  assert.deepEqual(lint.lintText('DB_PASSWORD=/run/secrets/db_password\n', 'deploy/prod.env'), []);
});

test('secret olmayan key + fallback gecer (timeout, url, ttl, key-store-type, monkey/keyspace)', () => {
  assert.deepEqual(yaml([
    'services:',
    '  timeout: ${SMS_TIMEOUT:2s}',
    '  inventory: { base-url: "${INVENTORY_URL:http://localhost:8086}", connect-timeout: 2s }',
    'jwt:',
    '  token-ttl: ${JWT_TOKEN_TTL:15m}',
    'server:',
    '  ssl:',
    '    key-store-type: ${KEY_STORE_TYPE:PKCS12}',
    'cassandra:',
    '  keyspace: ${KEYSPACE:orders}',
    '  monkey: ${MONKEY:yes}',
  ].join('\n')), []);
});

test('(b) local disi dosyada duz secret degeri raporlanir; local dosyada serbest', () => {
  const text = 'security:\n  pepper: not-a-real-pepper\n';
  assert.deepEqual(yaml(text, 'config/order.yml').map((x) => [x.line, x.rule]), [[2, 'literal-secret']]);
  assert.deepEqual(yaml(text, 'src/main/resources/application-local.yml'), []);
  assert.equal(lint.lintText('DB_PASSWORD=changeme\n', 'deploy/prod.env.example')[0].rule, 'literal-secret');
  assert.deepEqual(lint.lintText('DB_PASSWORD=changeme\n', 'deploy/.env.local'), []);
  assert.deepEqual(lint.lintText('DB_PASSWORD=\n', 'deploy/prod.env.example'), [], 'bos sablon degeri serbest');
});

test('(b) rotasyon listesindeki numarali anahtar (key1/key2) duz degerle raporlanir', () => {
  const v = yaml('jwt:\n  keys:\n    key1: not-a-real-signing-key\n    key2: ${JWT_KEY2}\n', 'config/order.yml');
  assert.deepEqual(v.map((x) => [x.line, x.rule, x.key]), [[3, 'literal-secret', 'jwt.keys.key1']]);
});

test('(b) YAML belgesi on-profile: local ise o belge local sayilir, digeri sayilmaz', () => {
  const v = yaml([
    'spring:',
    '  datasource:',
    '    password: prod-literal',
    '---',
    'spring:',
    '  config:',
    '    activate:',
    '      on-profile: local',
    '  datasource:',
    '    password: local-literal',
  ].join('\n'), 'application.yml');
  assert.deepEqual(v.map((x) => x.line), [3]);
});

test('(b) block scalar (PEM govdesi) literal sayilir; icerik satirlari key sanilmaz', () => {
  const v = yaml('jwt:\n  private-key: |\n    MIIEvQ: fake\n    secret: nope\n  issuer: order\n');
  assert.deepEqual(v.map((x) => [x.line, x.rule, x.key]), [[2, 'literal-secret', 'jwt.private-key']]);
});

// Ucuncu tur test review C1/C2: camelCase ayrimi ve "export " oneki testsizdi; ikisi de kaldirilinca suite yesil kaldi.
test('(b) camelCase anahtar (accessToken, signingKey) ve "export " onekli env satiri raporlanir', () => {
  const v = yaml('client:\n  accessToken: abc123\n  signingKey: k1\n  displayName: Verso\n', 'config/verso.yml');
  assert.deepEqual(v.map((x) => [x.line, x.rule, x.key]),
    [[2, 'literal-secret', 'client.accessToken'], [3, 'literal-secret', 'client.signingKey']]);
  const e = lint.lintText('export DB_PASSWORD=changeme\nexport APP_NAME=verso\n', 'deploy/prod.env.example');
  assert.deepEqual(e.map((x) => [x.line, x.rule]), [[1, 'literal-secret']]);
});

test('(b) .properties bicimi', () => {
  const v = lint.lintText('spring.datasource.password=changeme\nspring.datasource.username=app\nx.token=${TOKEN:abc}\n', 'app.properties');
  assert.deepEqual(v.map((x) => [x.line, x.rule]), [[1, 'literal-secret'], [3, 'secret-fallback']]);
});

test('(c) allow isareti + gerekce local dosyada muaf tutar', () => {
  const f = 'src/main/resources/application-local.yml';
  assert.deepEqual(yaml('sms:\n  api-key: ${SECRET_SMS_API_KEY:fake} # lint:allow-secret-fallback local sahte saglayici\n', f), []);
});

test('(c) baska bir yorum, gerekcesiz isaret veya local disi dosya muafiyet saglamaz', () => {
  const f = 'src/main/resources/application-local.yml';
  assert.equal(yaml('sms:\n  api-key: ${SECRET_SMS_API_KEY:fake} # TODO sonra bakilacak\n', f).length, 1);
  assert.equal(yaml('sms:\n  api-key: ${SECRET_SMS_API_KEY:fake} # lint:allow-secret-fallback\n', f).length, 1);
  assert.equal(yaml('sms:\n  api-key: ${SECRET_SMS_API_KEY:fake} # lint:allow-anything local\n', f).length, 1);
  const prod = yaml('sms:\n  api-key: ${SECRET_SMS_API_KEY:fake} # lint:allow-secret-fallback prod icin de\n', 'config/order.yml');
  assert.equal(prod.length, 1);
  assert.match(prod[0].detail, /yalniz local/);
});

test('fixture\'lar: coklu dosya birlestirilir, satirlar dogru, exit 1', () => {
  const r = run(
    path.join(FIX, 'service-bad.yml'), path.join(FIX, 'service-ok.yml'),
    path.join(FIX, 'application-local.yml'), path.join(FIX, 'prod.env.example'),
  );
  assert.equal(r.code, 1);
  const found = r.out.split('\n').filter((l) => /: \[/.test(l)).map((l) => l.replace(`${FIX}${path.sep}`, '').replace(/ = .*$/, ''));
  assert.deepEqual(found, [
    'service-bad.yml:5: [secret-fallback] spring.datasource.password',
    'service-bad.yml:7: [literal-secret] security.pepper',
    'service-bad.yml:9: [secret-fallback] services.sms.api-key',
    'application-local.yml:8: [secret-fallback] services.sms.webhook-secret',
    'prod.env.example:3: [literal-secret] DB_PASSWORD',
  ]);
  assert.match(r.out, /IHLAL — 5 bulgu \(4 dosya\)/);
  const { violations } = lint.lintFiles([path.join(FIX, 'service-bad.yml'), path.join(FIX, 'prod.env.example')]);
  assert.equal(new Set(violations.map((v) => v.file)).size, 2, 'iki dosyanin bulgulari tek sonucta');
});

test('cikis kodlari: 0 temiz, 1 ihlal, 3 okunamayan / desteklenmeyen / dosya yok', () => {
  assert.equal(run(path.join(FIX, 'service-ok.yml')).code, 0);
  assert.equal(run(path.join(FIX, 'service-bad.yml')).code, 1);
  const missing = run(path.join(FIX, 'service-ok.yml'), path.join(FIX, 'does-not-exist.yml'));
  assert.equal(missing.code, 3);
  assert.match(missing.out, /does-not-exist\.yml: DOGRULANAMADI — ENOENT/);
  // okunamayan dosya ihlalden once gelir: dogrulanamayan kume "uygun" ya da yalniz "ihlal" sayilmaz
  assert.equal(run(path.join(FIX, 'service-bad.yml'), path.join(FIX, 'does-not-exist.yml')).code, 3);
  assert.equal(run(FIX + path.sep + 'service-ok.yml', FIX).code, 3, 'klasor (EISDIR) okunamaz');
  assert.equal(run(tmpFile('notes.txt', 'password: x')).code, 3, 'desteklenmeyen tur');
  assert.equal(run().code, 3, 'dosya verilmedi');
});

test('okuma izni olmayan dosya: exit 3 (root degilse gercek EACCES)', () => {
  const f = tmpFile('svc.yml', 'a: b\n');
  fs.chmodSync(f, 0o000);
  try {
    let readable = true;
    try { fs.readFileSync(f); } catch (e) { readable = false; }
    const r = run(f);
    // root her dosyayi okuyabilir; o durumda icerik temiz oldugu icin 0 beklenir — iki dal da acikca dogrulanir
    assert.equal(r.code, readable ? 0 : 3);
    if (!readable) assert.match(r.out, /EACCES/);
  } finally {
    fs.chmodSync(f, 0o600);
  }
});

test('verso-app gercek config dosyalari temiz (kural mesru config\'i isaretlemez)', () => {
  const files = [
    path.join(APP_RES, 'application.yml'),
    path.join(APP_RES, 'application-local.yml'),
    ...fs.readdirSync(path.join(APP_RES, 'config')).filter((f) => f.endsWith('.yml')).map((f) => path.join(APP_RES, 'config', f)),
    path.join(__dirname, '..', 'deploy', 'prod.env.example'),
  ];
  assert.ok(files.length >= 3);
  const r = run(...files);
  assert.equal(r.code, 0, r.out);
});

test('siniflandirma', () => {
  assert.equal(lint.classify('spring.datasource.password'), 'secret');
  assert.equal(lint.classify('SECRET_DB_PASSWORD'), 'secret');
  assert.equal(lint.classify('clientSecret'), 'secret');
  assert.equal(lint.classify('security.pepper'), 'secret');
  assert.equal(lint.classify('hash.salt'), 'secret');
  assert.equal(lint.classify('github.token'), 'secret');
  assert.equal(lint.classify('jwt.keys.key1'), 'secret');
  assert.equal(lint.classify('webhook.token2'), 'secret');
  assert.equal(lint.classify('service-jwt.private-key-path'), 'secret-ref');
  assert.equal(lint.classify('server.ssl.key-store-type'), 'none');
  assert.equal(lint.classify('jwt.token-ttl'), 'none');
  assert.equal(lint.classify('cassandra.keyspace'), 'none');
  assert.equal(lint.classify('services.inventory.base-url'), 'none');
});

// Ucuncu tur review B22: pre-commit ve CI yalniz *.yml dosyalarini lint'liyordu; application-prod.yaml ve
// .properties icindeki fallback secret iki kontrolden de geciyordu. Liste artik tek dosyada (config-lint.pathspec).
test('pathspec: tum Spring config bicimleri ve deploy env dosyalari secilir, test kaynaklari secilmez', () => {
  const repo = fs.mkdtempSync(path.join(os.tmpdir(), 'config-lint-pathspec-'));
  const git = (...args) => spawnSync('git', args, { cwd: repo, encoding: 'utf8' });
  const wanted = [
    'verso-app/src/main/resources/application.yml',
    'verso-app/src/main/resources/application-prod.yaml',
    'verso-app/src/main/resources/application-prod.properties',
    'verso-app/src/main/resources/config/verso.yml',
    'platform/platform-core/src/main/resources/defaults.yml',
    'deploy/prod.env.example',
    'deploy/staging/app.env.example',
  ];
  const ignored = ['verso-app/src/test/resources/application-test.yml', 'README.md', 'docs/notes.yml'];
  for (const f of [...wanted, ...ignored]) {
    fs.mkdirSync(path.join(repo, path.dirname(f)), { recursive: true });
    fs.writeFileSync(path.join(repo, f), 'x: 1\n');
  }
  assert.equal(git('init', '-q').status, 0);
  assert.equal(git('add', '-A').status, 0);
  const specs = fs.readFileSync(path.join(__dirname, 'config-lint.pathspec'), 'utf8')
    .split(/\r?\n/).filter((l) => l && !l.startsWith('#'));
  const listed = git('ls-files', '--', ...specs).stdout.split('\n').filter(Boolean).sort();
  assert.deepEqual(listed, [...wanted].sort());
});

test('pathspec: pre-commit ve CI listeyi ortak dosyadan okur, kendi glob listesini tutmaz', () => {
  const root = path.join(__dirname, '..');
  for (const f of ['.githooks/pre-commit', '.github/workflows/ci.yml']) {
    const text = fs.readFileSync(path.join(root, f), 'utf8');
    assert.match(text, /scripts\/config-lint\.pathspec/, f);
    assert.doesNotMatch(text, /src\/main\/resources\/\*\.ya?ml/, f);
  }
});
