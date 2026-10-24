'use strict';
// node --test scripts/uyum-paketi.test.js
// The compliance pack (docs/uyum, ADR-0027) states facts about Verso: retention defaults, setting names, endpoints, file
// paths. These checks keep those facts from drifting away from the config and the code. They cannot check the legal
// sentences; those carry a source tag and "kurum hukuk birimi teyit etmeli" instead.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '..');
const PACK = path.join(ROOT, 'docs', 'uyum');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const HEADER = 'Bu belge hukuki tavsiye değildir; kurumun hukuk birimi ve veri sorumlusu tarafından uyarlanmalıdır.';
const files = fs.readdirSync(PACK).filter((f) => f.endsWith('.md')).sort();
const texts = Object.fromEntries(files.map((f) => [f, fs.readFileSync(path.join(PACK, f), 'utf8')]));

test('pack: the seven templates and the README exist', () => {
  assert.deepEqual(files, ['01-vked-sablonu.md', '02-aydinlatma-metni-calisan.md', '03-uretken-yz-kullanim-politikasi.md',
    '04-veri-isleyen-sozlesmesi.md', '05-egress-kaniti.md', '06-sektor-notlari.md', '07-almanya-eki-betrvg.md', 'README.md']);
});

test('pack: every file carries the legal-disclaimer line near the top', () => {
  for (const [name, text] of Object.entries(texts)) {
    assert.ok(text.split('\n').slice(0, 4).some((line) => line === `> ${HEADER}`), `${name}: header line`);
  }
});

test('pack: placeholders are {{UPPER_CASE_NAMES}} and every {{ is closed', () => {
  for (const [name, text] of Object.entries(texts)) {
    assert.equal((text.match(/\{\{/g) ?? []).length, (text.match(/\}\}/g) ?? []).length, `${name}: balanced braces`);
    for (const [, inner] of text.matchAll(/\{\{([^}]*)\}\}/g)) {
      assert.match(inner, /^[A-ZÇĞİÖŞÜ0-9_]+$/, `${name}: placeholder "${inner}"`);
    }
  }
});

test('pack: retention defaults, k and the cloud limits equal the configuration', () => {
  const yml = read('verso-app/src/main/resources/config/verso.yml');
  const history = yml.match(/retention-days: \$\{VERSO_HISTORY_RETENTION_DAYS:(\d+)\}/)[1];
  const audit = yml.match(/retention-days: \$\{VERSO_AUDIT_RETENTION_DAYS:(\d+)\}/)[1];
  const backup = read('compose.yaml').match(/BACKUP_RETENTION_DAYS: \$\{BACKUP_RETENTION_DAYS:-(\d+)\}/)[1];
  const minUsers = yml.match(/unanswered-min-users: (\d+)/)[1];
  const topK = yml.match(/top-k: (\d+)/)[1];
  const passage = yml.match(/max-passage-chars: (\d+)/)[1];
  const vked = texts['01-vked-sablonu.md'];
  assert.match(vked, new RegExp(`\`VERSO_HISTORY_RETENTION_DAYS\` gün \\(varsayılan \\*\\*${history}\\*\\*`));
  assert.match(vked, new RegExp(`\`VERSO_AUDIT_RETENTION_DAYS\` gün \\(varsayılan \\*\\*${audit}\\*\\*`));
  assert.match(vked, new RegExp(`\`BACKUP_RETENTION_DAYS\` \\(varsayılan \\*\\*${backup}\\*\\*\\) gün`));
  assert.match(vked, new RegExp(`k = ${minUsers} farklı hesabın`));
  assert.match(vked, new RegExp(`\`verso\\.qa\\.top-k: ${topK}\``));
  assert.match(vked, new RegExp(`\`max-passage-chars\``));
  assert.match(vked, new RegExp(`pasaj başına en çok ${passage} karakter`));
  assert.match(texts['02-aydinlatma-metni-calisan.md'], new RegExp(`varsayılan ${history}\\)`));
  assert.match(texts['02-aydinlatma-metni-calisan.md'], new RegExp(`varsayılan ${audit}\\)`));
  assert.match(yml, /show-unanswered-questions: \$\{VERSO_SHOW_UNANSWERED_QUESTIONS:false\}/);
  assert.match(yml, /show-document-names: \$\{VERSO_SHOW_DOCUMENT_NAMES:false\}/);
  assert.match(yml, /retention-days/);
  assert.match(read('deploy/obs/loki/loki.yml'), /retention_period: 168h/, 'the pack says Loki keeps 7 days');
});

test('pack: every VERSO_* / BACKUP_* setting named in the pack exists in the configuration or the compose files', () => {
  const sources = ['verso-app/src/main/resources/config/verso.yml', 'compose.yaml', 'deploy/prod.env.example',
    'deploy/compose.cloud.yaml'].map(read).join('\n');
  const names = new Set();
  for (const text of Object.values(texts)) for (const m of text.replace(/\{\{[^}]*\}\}/g, '').matchAll(/\b(?:VERSO|BACKUP|SECRET)_[A-Z0-9_]+\b/g)) names.add(m[0]);
  assert.ok(names.size >= 6, 'the pack names its settings');
  for (const name of names) assert.ok(sources.includes(name) || fs.existsSync(path.join(ROOT, 'secrets', name)) || name === 'SECRET_CLOUD_API_KEY',
    `${name} is not defined anywhere`);
  assert.ok(read('secrets/README.md').includes('SECRET_CLOUD_API_KEY') || sources.includes('SECRET_CLOUD_API_KEY'));
});

// Metric names (not settings) the pack mentions.
const METRICS = new Set(['verso.audit.events.not.stored']);

test('pack: configuration keys named in the pack exist in verso.yml', () => {
  const yml = read('verso-app/src/main/resources/config/verso.yml');
  const keys = { 'verso.qa.top-k': /^ {2}qa:\n {4}top-k:/m, 'verso.qa.metrics.unanswered-min-users': /unanswered-min-users:/,
    'verso.qa.history.retention-days': /history:\n {6}retention-days:/, 'verso.audit.retention-days': /audit:[\s\S]*?\n {4}retention-days:/,
    'verso.qa.metrics.show-unanswered-questions': /show-unanswered-questions:/, 'verso.qa.metrics.show-document-names': /show-document-names:/ };
  const named = new Set();
  for (const text of Object.values(texts)) for (const m of text.matchAll(/`(verso\.[a-z.-]+)(?::[^`]*)?`/g)) named.add(m[1]);
  for (const key of named) assert.ok(key in keys || METRICS.has(key), `${key}: add it to this test's key table or fix the pack`);
  for (const [key, pattern] of Object.entries(keys)) assert.match(yml, pattern, key);
});

test('pack: endpoints named in the pack are mapped by a controller', () => {
  const controllers = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (entry.name === 'target' || entry.name === 'node_modules' || entry.name === '.git') continue;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('Controller.java') && full.includes(`${path.sep}main${path.sep}`)) controllers.push(fs.readFileSync(full, 'utf8'));
    }
  };
  for (const dir of ['services', 'verso-app', 'platform']) walk(path.join(ROOT, dir));
  const mappings = controllers.join('\n');
  const bases = new Set();
  for (const text of Object.values(texts)) for (const m of text.matchAll(/`(?:GET |POST |DELETE |PATCH )?(\/v1\/[a-z]+(?:\/[a-z]+)?)/g)) bases.add(m[1]);
  assert.ok(bases.size >= 4);
  for (const base of bases) assert.ok(mappings.includes(`"${base}"`), `${base} is not a @RequestMapping base`);
});

// Files an installation owns and the repository ignores (ADR-0021).
const CUSTOMER_OWNED = new Set(['deploy/branding/branding.json']);

test('pack: file paths and relative links named in the pack exist', () => {
  for (const [name, text] of Object.entries(texts)) {
    for (const m of text.matchAll(/`((?:scripts|deploy|docs|services|verso-app|platform|panel|eval|\.github)\/[^`\s*{}]+)`/g)) {
      const rel = m[1].replace(/[.,;:]+$/, '');
      if (CUSTOMER_OWNED.has(rel)) continue;
      assert.ok(fs.existsSync(path.join(ROOT, rel)), `${name}: ${rel} does not exist`);
    }
    for (const m of text.matchAll(/\]\((?!https?:|#|mailto:)([^)\s]+)\)/g)) {
      const target = m[1].split('#')[0];
      if (!target || target.startsWith('{{')) continue;
      assert.ok(fs.existsSync(path.join(PACK, target)), `${name}: link ${target} is broken`);
    }
  }
});

test('pack: no secret value, pepper or real host appears (names only)', () => {
  for (const [name, text] of Object.entries(texts)) {
    assert.doesNotMatch(text, /-----BEGIN|api[_-]?key\s*[:=]\s*\S{12,}|sk-[A-Za-z0-9]{16,}/i, name);
  }
});
