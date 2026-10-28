'use strict';
// "Yapay zekâ sağlayıcıları" (ADR-0056): the panel side of the model gateway. The form's rules and request, the key
// handling (sent once, never shown), the trust screen's model connections, the value dashboard's usage card and the
// optional model choice of the ask screen. No browser: the models are pure functions, the screens are checked as source.
const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const ROOT = path.resolve(__dirname, '..');
const load = (file) => import(pathToFileURL(path.join(ROOT, file)).href);
const read = (file) => readFileSync(path.join(ROOT, file), 'utf8');
const KEY = 'sk-SYNTHETIC-panel-key-7777';

test('providers: an admin-only screen in the Yönetim group, with its own shortcut', async () => {
  const { ROLES, SCREENS, adminScreens, canOpen, screensFor } = await load('panel/js/roles.js');
  assert.deepEqual(SCREENS.providers.roles, [ROLES.ADMIN]);
  assert.equal(SCREENS.providers.group, 'admin');
  assert.ok(adminScreens([ROLES.ADMIN]).includes('providers'));
  assert.ok(!screensFor([ROLES.USER, ROLES.OPERATOR]).includes('providers'));
  assert.equal(canOpen('providers', [ROLES.OPERATOR]), false);
  const keys = Object.values(SCREENS).map((s) => s.key);
  assert.equal(new Set(keys).size, keys.length, 'shortcut keys stay unique');
});

test('providers: local mode offers only the self-hosted types; every type has a label, fields and a hint', async () => {
  const { PROVIDER_TYPES, typesFor } = await load('panel/js/providers.js');
  assert.deepEqual(typesFor('local'), ['OLLAMA', 'OPENAI_COMPATIBLE']);
  assert.deepEqual(typesFor('cloud'), ['OPENAI', 'AZURE_OPENAI', 'ANTHROPIC', 'BEDROCK', 'GOOGLE_GENAI', 'MISTRAL', 'OLLAMA',
    'OPENAI_COMPATIBLE']);
  for (const [type, meta] of Object.entries(PROVIDER_TYPES)) {
    assert.ok(meta.label && meta.hint && Array.isArray(meta.fields), type);
  }
});

test('providers: the request carries only the type\'s fields, the key only when typed, and never for Ollama', async () => {
  const { connectionRequest, parseModels } = await load('panel/js/providers.js');
  const azure = connectionRequest({ type: 'AZURE_OPENAI', name: ' Azure ', models: 'gpt-a, gpt-b\ngpt-a', baseUrl: 'https://x.openai.azure.com',
    deployment: 'dep', region: 'ignored', project: 'ignored', apiKey: ` ${KEY} `, enabled: true });
  assert.deepEqual(azure, { name: 'Azure', type: 'AZURE_OPENAI', models: ['gpt-a', 'gpt-b'], enabled: true,
    baseUrl: 'https://x.openai.azure.com', deployment: 'dep', apiKey: KEY });
  const change = connectionRequest({ type: 'OPENAI', name: 'O', models: 'm', apiKey: '', enabled: false });
  assert.equal('apiKey' in change, false, 'an empty key field keeps the stored key');
  assert.equal(change.enabled, false);
  assert.equal('apiKey' in connectionRequest({ type: 'OLLAMA', name: 'L', models: 'm', apiKey: KEY }), false);
  assert.equal(parseModels(Array.from({ length: 30 }, (_, i) => `m${i}`).join(' ')).length, 20);
});

test('providers: form problems before the round trip', async () => {
  const { formProblems } = await load('panel/js/providers.js');
  assert.deepEqual(Object.keys(formProblems({ type: 'ANTHROPIC', name: '', models: '' })).sort(), ['apiKey', 'models', 'name']);
  assert.deepEqual(Object.keys(formProblems({ type: 'ANTHROPIC', name: 'a', models: 'm' }, { creating: false })), [],
    'a change may keep the stored key');
  assert.ok(formProblems({ type: 'BEDROCK', name: 'b', models: 'm', apiKey: 'a:b' }).region);
  assert.ok(formProblems({ type: 'OPENAI_COMPATIBLE', name: 'c', models: 'm' }).baseUrl);
  assert.deepEqual(formProblems({ type: 'OPENAI_COMPATIBLE', name: 'c', models: 'm', baseUrl: 'http://llama:8080/v1' }), {});
});

test('providers: the key is shown only as stored-or-not and its last four characters', async () => {
  const { keyText, localityText, statusText } = await load('panel/js/providers.js');
  assert.equal(keyText({ type: 'OPENAI', keyStored: true, keyHint: '…7777', keyVersion: 2 }), 'Anahtar kayıtlı (…7777, sürüm 2)');
  assert.equal(keyText({ type: 'OPENAI', keyStored: false }), 'Anahtar yok');
  assert.equal(keyText({ type: 'OLLAMA', keyStored: false }), 'Anahtar kullanılmaz');
  assert.match(localityText({ locality: 'LOCAL' }), /veri kurumdan çıkmaz/);
  assert.match(localityText({ locality: 'EXTERNAL', egressHost: 'api.anthropic.com' }), /api\.anthropic\.com/);
  assert.equal(statusText('AUTH_FAILED').tone, 'bad');
  assert.equal(statusText(null).label, 'Denenmedi');
});

test('providers: route options and entries; the built-in model is the empty first choice', async () => {
  const { routeEntries, routeOptions } = await load('panel/js/providers.js');
  const options = routeOptions([{ id: 'c1', name: 'A', enabled: true, models: ['m1', 'm2'] },
    { id: 'c2', name: 'Off', enabled: false, models: ['x'] }], 'gemma');
  assert.deepEqual(options.map((o) => o.value), ['', 'c1/m1', 'c1/m2']);
  assert.deepEqual(routeEntries(['c1/m1', 'c1/models/with/slash'], 'gemma'),
    [{ connectionId: 'c1', model: 'm1' }, { connectionId: 'c1', model: 'models/with/slash' }]);
  assert.deepEqual(routeEntries(['', 'c1/m2'], 'gemma'), [{ connectionId: null, model: 'gemma' }, { connectionId: 'c1', model: 'm2' }]);
});

test('providers: the usage card never names a person and says why teams are hidden', async () => {
  const { usageSummary } = await load('panel/js/providers.js');
  const summary = usageSummary({ month: '2026-10', requests: 12, promptTokens: 1000, completionTokens: 200, cost: 0.5,
    rows: [{ connectionId: 'c1', connectionName: 'Anthropic', model: 'claude', task: 'ANSWER', requests: 12, promptTokens: 1000,
      completionTokens: 200, cost: 0.5, failures: 1 }],
    teams: [], hiddenTeams: 2, minTeamUsers: 5,
    budgets: [{ scope: 'CONNECTION', scopeKey: 'c1', hardLimit: 10, softLimit: 5, spent: 6, state: 'SOFT' }] });
  assert.equal(summary.rows[0].task, 'Cevap');
  assert.match(summary.rows[0].text, /12 çağrı, 1200 token/);
  assert.match(summary.teamNote, /2 ekip 5 kişiden az/);
  assert.equal(summary.budgets[0].tone, 'warn');
  assert.equal(usageSummary(null), null);
});

test('trust: enabled external connections are open paths; an unreadable list is not "none"', async () => {
  const { externalPaths, gatewayLine } = await load('panel/js/trust.js');
  const t = { ai: { mode: 'cloud', chatModel: 'x' }, models: { known: true, connections: [
    { name: 'Claude', type: 'ANTHROPIC', locality: 'EXTERNAL', host: 'api.anthropic.com', region: null },
    { name: 'llama', type: 'OPENAI_COMPATIBLE', locality: 'LOCAL', host: 'llama', region: null }] } };
  const paths = externalPaths(t);
  assert.ok(paths.some((p) => p.id === 'gateway-0' && /api\.anthropic\.com/.test(p.detail)));
  assert.ok(!paths.some((p) => /llama/.test(p.detail)), 'an internal connection is not a path out');
  assert.match(gatewayLine(t.models)[0], /2 açık \(1 dış, 1 iç ağ\)/);
  assert.match(gatewayLine({ known: false, connections: [] })[0], /okunamadı/);
  assert.deepEqual(gatewayLine(undefined), []);
});

test('ask: the model choice is sent only when picked; the key field is a cleared password field; no markup', async () => {
  const { askBody } = await load('panel/js/conversations.js');
  assert.deepEqual(askBody('q', null), { question: 'q' });
  assert.deepEqual(askBody('q', 'c', 'id/m'), { question: 'q', conversationId: 'c', model: 'id/m' });
  assert.deepEqual(askBody('q', null, ''), { question: 'q' });
  const screen = read('panel/js/screens/providers.js');
  assert.match(screen, /apiKey: el\('input', \{ type: 'password', autocomplete: 'new-password'/);
  assert.match(screen, /inputs\.apiKey\.value = '';/, 'the typed key is cleared before the request goes out');
  for (const file of ['panel/js/screens/providers.js', 'panel/js/providers.js']) {
    const source = read(file);
    assert.doesNotMatch(source, /innerHTML|insertAdjacentHTML|outerHTML/, file);
    assert.doesNotMatch(source, /\.style\.|style=|setAttribute\('style'/, file);
    assert.doesNotMatch(source, /localStorage|sessionStorage/, file);
    assert.doesNotMatch(source.replace(/\/\/.*$/gm, ''), /Verso/, `${file}: the product name comes from the branding`);
  }
  const api = read('panel/js/api.js');
  assert.match(api, /rotateModelKey: \(id, apiKey\) => request\(`[^`]*\/key`, json\('POST', \{ apiKey \}\)\)/,
    'the key travels in a JSON body, never in a URL');
});
