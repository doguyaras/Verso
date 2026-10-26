'use strict';
// "Güven" screen (ADR-0050): rendering model, report builder, download seams. No browser: the screen's DOM is not
// exercised here (no browser check is available); the model and the report are pure functions.
const test = require('node:test');
const assert = require('node:assert/strict');
const { existsSync, readFileSync } = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const ROOT = path.resolve(__dirname, '..');
const load = (file) => import(pathToFileURL(path.join(ROOT, file)).href);

const LOCAL = {
  generatedAt: '2026-10-26T09:00:00Z',
  ai: { mode: 'local', chatProvider: 'ollama', chatModel: 'gemma4:e2b', embeddingModel: 'bge-m3' },
  egress: { externalConnectorEgress: false, allowedHosts: [] },
  connectors: { types: ['SMB'], cloudConnectorPresent: false },
  mcp: { enabled: false, audience: null },
  identity: { issuers: [{ name: 'primary', host: 'idp.firma.example' }] },
  build: { version: '1.2.0', builtAt: '2026-10-25T08:00:00Z', imageDigest: null },
  audit: { lastEventAt: '2026-10-26T08:59:00Z', outboxOldestAgeSeconds: null },
  backup: { lastSuccessAt: null, verification: 'kurulum betiğiyle doğrulanır' },
  guarantee: 'Yerel mod: soru, belge ve cevaplar bu sunucudan çıkmaz.',
};
const CLOUD = {
  ...LOCAL,
  ai: { mode: 'cloud', chatProvider: 'anthropic', chatModel: 'claude-x', embeddingModel: 'bge-m3' },
  egress: { externalConnectorEgress: true, allowedHosts: ['*.atlassian.net'] },
  connectors: { types: ['CONFLUENCE', 'SHAREPOINT'], cloudConnectorPresent: true },
  mcp: { enabled: true, audience: 'verso-mcp' },
  guarantee: 'Bulut modu: soru ve seçilen en çok 5 pasaj model sağlayıcısına gider.',
};

test('trust: only an admin has the screen, in the Yönetim group', async () => {
  const { ROLES, SCREENS, adminScreens, canOpen, screensFor } = await load('panel/js/roles.js');
  assert.deepEqual(SCREENS.trust.roles, [ROLES.ADMIN]);
  assert.ok(adminScreens([ROLES.ADMIN]).includes('trust'));
  assert.ok(!screensFor([ROLES.USER, ROLES.OPERATOR]).includes('trust'));
  assert.equal(canOpen('trust', [ROLES.OPERATOR]), false);
});

test('trust: local mode says nothing leaves, lists what cloud mode would change and has no open path', async () => {
  const { trustModel, externalPaths } = await load('panel/js/trust.js');
  const m = trustModel(LOCAL, 'Bilgi Asistanı');
  assert.equal(m.local, true);
  assert.match(m.hero.title, /Yerel.*bu sunucudan çıkmaz/);
  assert.equal(m.modeChanges.length, 4);
  assert.match(m.modeChangesTitle, /Bilgi Asistanı kurulumunda/);
  assert.deepEqual(externalPaths(LOCAL), []);
  assert.match(m.pathsText, /dışarı açık bir bağlantı yok/);
  assert.match(m.audit.text, /son olay 2026-10-26 08:59:00 UTC/);
  assert.equal(m.audit.outbox, 'Teslim bekleyen kayıt yok');
  assert.match(m.backup, /kurulum betiğiyle doğrulanır/);
  assert.equal(trustModel(null, 'x'), null);
});

test('trust: cloud mode lists every open path and no "what would change" block', async () => {
  const { trustModel } = await load('panel/js/trust.js');
  const m = trustModel(CLOUD, 'Asistan');
  assert.equal(m.local, false);
  assert.deepEqual(m.paths.map((p) => p.id), ['model', 'connectors', 'cloud-connector', 'mcp']);
  assert.match(m.paths[1].detail, /\*\.atlassian\.net/);
  assert.deepEqual(m.modeChanges, []);
});

test('trust: an unreadable connector list is "unknown", never "no connector"', async () => {
  const { trustModel } = await load('panel/js/trust.js');
  const m = trustModel({ ...LOCAL, connectors: { types: [], cloudConnectorPresent: false, known: false } }, 'x');
  assert.match(m.connectorText, /okunamadı/);
  assert.doesNotMatch(m.connectorText, /Tanımlı bağlayıcı yok/);
});

test('trust: age text; an old outbox row flags the audit card', async () => {
  const { ageText, trustModel } = await load('panel/js/trust.js');
  assert.deepEqual([ageText(5), ageText(120), ageText(7200), ageText(172800), ageText(-1), ageText(null)],
    ['5 sn', '2 dk', '2 sa', '2 gün', null, null]);
  const stuck = trustModel({ ...LOCAL, audit: { lastEventAt: null, outboxOldestAgeSeconds: 900 } }, 'x');
  assert.equal(stuck.audit.healthy, false);
  assert.match(stuck.audit.text, /henüz olay yok/);
});

test('trust: the report holds the server facts and the verification scripts; documents named exist', async () => {
  const { COMPLIANCE_DOCS, VERIFY_STEPS, reportFileName, reportJson, reportMarkdown } = await load('panel/js/trust.js');
  const md = reportMarkdown(CLOUD, { productName: 'Bilgi Asistanı', at: '2026-10-26T09:30:00Z' });
  assert.match(md, /^# Bilgi Asistanı güven raporu/);
  assert.match(md, /anthropic \(claude-x\)/);
  assert.match(md, /\*\.atlassian\.net/);
  assert.match(md, /prove-local-mode\.sh --report/);
  assert.equal(JSON.parse(reportJson(LOCAL, 'T')).trust.ai.mode, 'local');
  assert.equal(JSON.parse(reportJson(LOCAL, 'T')).savedAt, 'T');
  assert.equal(reportFileName('md', '2026-10-26T09:30:00Z'), 'guven-raporu-2026-10-26.md');
  assert.equal(reportFileName('x', '2026-10-26T09:30:00Z'), 'guven-raporu-2026-10-26.md');
  assert.deepEqual(VERIFY_STEPS.map((s) => s.script), ['scripts/prove-local-mode.sh', 'scripts/verify-release.sh']);
  for (const doc of COMPLIANCE_DOCS) assert.ok(existsSync(path.join(ROOT, doc.file)), doc.file);
  assert.equal(reportMarkdown(null), '');
});

test('trust: no hard-coded product name, no inline style; the download is a stubbed Blob click', async () => {
  const source = readFileSync(path.join(ROOT, 'panel/js/screens/trust.js'), 'utf8')
    + readFileSync(path.join(ROOT, 'panel/js/trust.js'), 'utf8');
  assert.doesNotMatch(source.replace(/\/\/.*$/gm, ''), /Verso/, 'the product name comes from the branding');
  assert.doesNotMatch(source, /\.style\.|style=|setAttribute\('style'/);
  assert.doesNotMatch(source, /innerHTML|insertAdjacentHTML/);
  const { saveReport } = await load('panel/js/screens/trust.js');
  const calls = { appended: 0, clicked: 0, revoked: [] };
  const anchor = { setAttribute() {}, addEventListener() {}, append() {}, click: () => { calls.clicked += 1; }, remove() {} };
  globalThis.document = { createElement: () => anchor, createTextNode: (t) => t, body: { append: () => { calls.appended += 1; } } };
  const blobs = [];
  try {
    saveReport('metin', 'guven-raporu-2026-10-26.md', 'text/markdown', {
      urls: { createObjectURL: (b) => { blobs.push(b); return 'blob:stub'; }, revokeObjectURL: (u) => calls.revoked.push(u) },
      wait: (fn) => fn() });
  } finally {
    delete globalThis.document;
  }
  assert.equal(calls.clicked, 1);
  assert.equal(calls.appended, 1);
  assert.deepEqual(calls.revoked, ['blob:stub']);
  assert.equal(await blobs[0].text(), 'metin');
});
