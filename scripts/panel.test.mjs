// The panel's pure logic (ADR-0015): role matrix, PKCE, token reading, answer and citation splitting, error texts;
// since ADR-0025 also the conversation id flow, the history filter, the rating state machine and the value dashboard's
// date ranges, number formats and chart paths. The sign-in flow and the API calls run here with the browser globals
// stubbed; the screens are checked against the running stack or a fixture (docs/evidence/).
import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { ICON_NAMES } from '../panel/js/icons.js';
import { ROLES, SCREENS, canOpen, rolesOf, screensFor, withServerRoles } from '../panel/js/roles.js';
import { askBody, continueWith, DELETE_ALL_WORD, deleteAllConfirmed, DELETED_DOCUMENT, filterConversations, hasMore, historyCitation,
  restoreEntries, startOverNote, startsOver, TITLE_MAX, titleProblem, turnCountLabel } from '../panel/js/conversations.js';
import { initialFeedback, rate, reasonLabel, REASONS, reasonsFor, thanks } from '../panel/js/feedback.js';
import { addDays, dailySeries, dayLabel, daysBetween, documentLabel, formatCount, formatLatency, formatPercent, isEmpty, kpis,
  MAX_RANGE_DAYS, PRESETS, rangeFor, rangeLabel, reasonRows, upRatio, validRange } from '../panel/js/metrics.js';
import { areaPath, axisDays, isolatedPoints, linePath, nearestIndex, niceScale, tickValues, xAt, yAt } from '../panel/js/chart.js';
import { conversation as openConversation, restoreConversation, startNewConversation, thread } from '../panel/js/store.js';
import { api } from '../panel/js/api.js';
import { accessToken, base64Url, challengeOf, claimsOf, completeSignIn, currentClaims, currentIdentity, endpointsOf,
  randomString, signIn, signOut } from '../panel/js/auth.js';
import { answerParts, answerSegments, answerWithSources, FAILURES, fill, foldTr, formatLabel, formatSeconds, fuzzyScore, greeting, groupSources, plainName,
  sourceLabel, UPLOAD_TYPES } from '../panel/js/render.js';
import { MESSAGES, messageOf, PAGE_SIZE } from '../panel/js/api.js';
import { SIGN_MS, SIGNATURE, timeline } from '../panel/js/signature-data.js';
import { CONNECTORS, PHASES, setupSteps } from '../panel/js/screens/setup.js';
import { isDark, prefs } from '../panel/js/store.js';
import { disclosureLine, disclosureNotice, generatedFooter } from '../panel/js/disclosure.js';
import { ACCENT_TOKENS, applyAccent, contrast, LIMITS, loadBranding, LOGO_PATTERN, MAX_BYTES, MAX_SUGGESTIONS, THEME_COLOURS,
  validateBranding } from '../panel/js/branding.js';
import { CONFIG, DEPLOYMENT_DEFAULTS, useDeployment } from '../panel/js/config.js';
import { ABBREVIATIONS, citingSpans, citingText, excerptParts, fold, key, matchRanges, sentences, STEM, STOP_WORDS, terms } from '../panel/js/excerpt.js';
import { answerMarkdown, chipLabel, CHIP_NAME_MAX, clip, contextQueryNote, MAX_CONTEXT_QUERY, errorState, escapeMarkdown, fitQuestion, FOLLOW_UPS, followUps, markdownFileName, MAX_QUESTION, SELECTION_MAX,
  selectionQuestion } from '../panel/js/answer.js';
import { ApiError } from '../panel/js/api.js';
// ask.js touches no DOM at module load (only inside its functions), so node can import it for its constants.
import { FLASH_MS } from '../panel/js/screens/ask.js';
import { CITED as CITED_HIGHLIGHT, MATCH, prune, tintSentences, writeMarked } from '../panel/js/highlight.js';
import { createSseParser, noStream, provisionalText, readEvents, resultOf, stageText } from '../panel/js/stream.js';
import { collapse, excerptRange, pageHeading, pageNav } from '../panel/js/pageview.js';

test('roles: a user without Verso roles is a verso-user and sees no system screen', () => {
  const roles = rolesOf({ realm_access: { roles: ['offline_access', 'default-roles-verso'] } });
  assert.deepEqual(roles, [ROLES.USER]);
  assert.deepEqual(screensFor(roles), ['overview', 'ask', 'documents', 'setup']);
  assert.equal(canOpen('system', roles), false);
});

test('roles: an operator also sees the system screen; unknown screens are closed', () => {
  const roles = rolesOf({ realm_access: { roles: ['verso-operator'] } });
  assert.deepEqual(screensFor(roles), ['overview', 'ask', 'documents', 'setup', 'system']);
  assert.ok(screensFor(roles).length <= 5, 'at most five screens (ADR-0015)');
  assert.ok(screensFor(roles).filter((id) => SCREENS[id].tab !== false).length <= 4, 'four tabs at most on mobile');
  assert.equal(canOpen('nothing', roles), false);
  for (const screen of Object.values(SCREENS)) assert.ok(screen.roles.length > 0);
});

test('roles: Entra ID app roles (ADR-0041) count like realm roles; verso-admin still only from /v1/info', () => {
  assert.deepEqual(rolesOf({ roles: ['verso-operator'] }), [ROLES.OPERATOR]);
  assert.deepEqual(rolesOf({ roles: ['verso-admin'] }), [ROLES.USER]);
  assert.deepEqual(rolesOf({ roles: 'verso-operator', realm_access: { roles: 'x' } }), [ROLES.USER], 'not a list: nothing');
});

test('oidc: Keycloak paths by default; configured endpoints (Entra ID v2.0) win; only https or a loopback IdP', () => {
  assert.deepEqual(endpointsOf(CONFIG), {
    auth: 'http://localhost:8180/realms/verso/protocol/openid-connect/auth',
    token: 'http://localhost:8180/realms/verso/protocol/openid-connect/token',
    logout: 'http://localhost:8180/realms/verso/protocol/openid-connect/logout',
  });
  const base = 'https://login.microsoftonline.com/6f1c2a4e-3b5d-4c7e-9f80-1a2b3c4d5e6f/oauth2/v2.0';
  const entra = { issuer: 'https://login.microsoftonline.com/6f1c2a4e-3b5d-4c7e-9f80-1a2b3c4d5e6f/v2.0',
    authorizationEndpoint: `${base}/authorize`, tokenEndpoint: `${base}/token`, endSessionEndpoint: `${base}/logout` };
  assert.deepEqual(endpointsOf(entra), { auth: `${base}/authorize`, token: `${base}/token`, logout: `${base}/logout` });
  assert.throws(() => endpointsOf({ ...entra, tokenEndpoint: 'http://idp.example/token' }), /https/);
  assert.equal(CONFIG.scope, 'openid', 'Keycloak: the realm adds the API audience itself');
  assert.match(readFileSync('panel/js/auth.js', 'utf8'), /scope: CONFIG\.scope/);
});

test('pkce: the challenge is base64url(SHA-256(verifier)) (RFC 7636 appendix B)', async () => {
  assert.equal(await challengeOf('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'), 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  const verifier = randomString(48);
  assert.match(verifier, /^[A-Za-z0-9_-]{64}$/);
  assert.notEqual(verifier, randomString(48));
  assert.equal(base64Url(new Uint8Array([251, 255])), '-_8');
});

test('claims: the payload of a JWT is read, Turkish characters included', () => {
  const payload = base64Url(new TextEncoder().encode(JSON.stringify({ preferred_username: 'çağrı', exp: 1 })));
  assert.deepEqual(claimsOf(`h.${payload}.s`), { preferred_username: 'çağrı', exp: 1 });
});

test('answer: markers of server citations become citation parts, other text stays text', () => {
  const citations = [{ number: 1, fileName: 'a.pdf', page: 2 }, { number: 3, fileName: 'b.pdf', page: 1 }];
  const parts = answerParts('Yirmi gün [1]. Bkz. [2] ve [3]', citations);
  assert.deepEqual(parts, [
    { text: 'Yirmi gün ' }, { citation: citations[0] }, { text: '. Bkz. [2] ve ' }, { citation: citations[1] },
  ]);
  assert.deepEqual(answerParts('<img src=x onerror=alert(1)>', []), [{ text: '<img src=x onerror=alert(1)>' }]);
});

test('errors: every code of the integration documents has a Turkish message', () => {
  const documented = new Set();
  for (const file of ['docs/api-documents-integration-v1.md', 'docs/api-questions-integration-v1.md', 'docs/api-conversations-integration-v1.md']) {
    for (const m of readFileSync(file, 'utf8').matchAll(/^\| (1\d{4}) \|/gm)) documented.add(Number(m[1]));
  }
  assert.ok(documented.size >= 8);
  for (const code of documented) assert.ok(MESSAGES[code], `no message for ${code}`);
});

test('safety: the panel never writes HTML from data and never stores tokens', () => {
  for (const file of readdirSync('panel/js', { recursive: true }).filter((f) => String(f).endsWith('.js'))) {
    const source = readFileSync(`panel/js/${file}`, 'utf8');
    assert.doesNotMatch(source, /innerHTML|outerHTML|insertAdjacentHTML|document\.write/, file);
    assert.doesNotMatch(source, /localStorage\.|document\.cookie\s*=/, file);
    assert.doesNotMatch(source, /console\.(log|info|debug)/, file);
  }
  const auth = readFileSync('panel/js/auth.js', 'utf8');
  for (const m of auth.matchAll(/sessionStorage\.setItem\(([^,]+),/g)) assert.equal(m[1], 'PENDING');
});

// ---------- sign-in flow, with the browser globals stubbed (phase 10 review: completeSignIn and refresh untested) ----------
function browser({ search = '', pending } = {}) {
  const store = new Map(pending ? [['verso.panel.pkce', JSON.stringify(pending)]] : []);
  const calls = { assigned: [], replaced: [], tokenForms: [] };
  globalThis.location = { search, origin: 'http://localhost:8080', assign: (url) => calls.assigned.push(url) };
  globalThis.history = { replaceState: (_s, _t, url) => calls.replaced.push(url) };
  globalThis.sessionStorage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, v),
    removeItem: (k) => store.delete(k) };
  return { store, calls };
}

function jwt(claims) {
  return `h.${base64Url(new TextEncoder().encode(JSON.stringify(claims)))}.s`;
}

function tokenEndpoint(calls, { status = 200, expiresIn = 300, delayMs = 0 } = {}) {
  let n = 0;
  globalThis.fetch = async (url, init) => {
    calls.tokenForms.push(Object.fromEntries(new URLSearchParams(init.body)));
    await new Promise((r) => setTimeout(r, delayMs));
    n += 1;
    return { ok: status === 200, status, json: async () => ({ access_token: jwt({ n, realm_access: { roles: [] } }),
      refresh_token: `refresh-${n}`, id_token: jwt({ preferred_username: 'demo' }), expires_in: expiresIn }) };
  };
}

test('sign-in: a redirect with the right state exchanges the code with the verifier and cleans the URL', async () => {
  const { store, calls } = browser({ search: '?code=c1&state=s1', pending: { verifier: 'v1', state: 's1' } });
  tokenEndpoint(calls);
  assert.equal(await completeSignIn(), true);
  assert.deepEqual(calls.tokenForms[0], { client_id: 'verso-panel', grant_type: 'authorization_code', code: 'c1',
    redirect_uri: 'http://localhost:8080/panel/', code_verifier: 'v1' });
  assert.deepEqual(calls.replaced, ['http://localhost:8080/panel/']);
  assert.equal(store.size, 0, 'the verifier is gone');
  assert.equal(currentIdentity().preferred_username, 'demo');
});

test('sign-in: a wrong state, a missing pending record or an IdP error establish no session', async () => {
  for (const [search, pending] of [['?code=c&state=evil', { verifier: 'v', state: 's' }], ['?code=c&state=s', undefined],
    ['?error=access_denied&state=s', { verifier: 'v', state: 's' }]]) {
    const { store, calls } = browser({ search, pending });
    tokenEndpoint(calls);
    assert.equal(await completeSignIn(), false, search);
    assert.equal(calls.tokenForms.length, 0, `${search}: no code exchange`);
    assert.equal(store.size, 0, `${search}: the pending record is removed`);
    assert.equal(calls.replaced.length, 1, `${search}: the URL is cleaned`);
  }
  const { calls } = browser({ search: '' });
  assert.equal(await completeSignIn(), false);
  assert.equal(calls.replaced.length, 0, 'a plain visit is left alone');
});

test('sign-in: the redirect to the IdP asks for a code with an S256 challenge and stores only the verifier and state', async () => {
  const { store, calls } = browser();
  await signIn();
  const url = new URL(calls.assigned[0]);
  assert.equal(url.origin + url.pathname, 'http://localhost:8180/realms/verso/protocol/openid-connect/auth');
  const { verifier, state } = JSON.parse(store.get('verso.panel.pkce'));
  assert.equal(url.searchParams.get('state'), state);
  assert.equal(url.searchParams.get('code_challenge'), await challengeOf(verifier));
  assert.equal(url.searchParams.get('code_challenge_method'), 'S256');
  assert.equal(url.searchParams.get('scope'), 'openid');
  assert.equal(url.searchParams.has('prompt'), false, 'the automatic sign-in after a 401 reuses the IdP session');
  calls.assigned.length = 0;
  await signIn({ prompt: 'login' });
  assert.equal(new URL(calls.assigned[0]).searchParams.get('prompt'), 'login', 'the intro button always shows the sign-in form');
});

test('refresh: concurrent callers near expiry share one refresh (the realm revokes a used refresh token)', async () => {
  const { calls } = browser({ search: '?code=c&state=s', pending: { verifier: 'v', state: 's' } });
  tokenEndpoint(calls, { expiresIn: 10, delayMs: 20 }); // inside the 30 s refresh window
  await completeSignIn();
  const tokens = await Promise.all([accessToken(), accessToken(), accessToken()]);
  const refreshes = calls.tokenForms.filter((f) => f.grant_type === 'refresh_token');
  assert.equal(refreshes.length, 1);
  assert.equal(refreshes[0].refresh_token, 'refresh-1');
  assert.equal(new Set(tokens).size, 1);
  assert.equal(claimsOf(tokens[0]).n, 2);
});

test('refresh: a failed refresh signs out instead of sending an expired token', async () => {
  const { calls } = browser({ search: '?code=c&state=s', pending: { verifier: 'v', state: 's' } });
  tokenEndpoint(calls, { expiresIn: 10 });
  await completeSignIn();
  tokenEndpoint(calls, { status: 400 });
  assert.equal(await accessToken(), null);
  assert.equal(currentClaims(), null);
});

test('sign-out: the IdP logout gets the client, the panel as the way back and the ID token as the hint', async () => {
  const { calls } = browser({ search: '?code=c&state=s', pending: { verifier: 'v', state: 's' } });
  tokenEndpoint(calls);
  await completeSignIn();
  signOut();
  const url = new URL(calls.assigned.at(-1));
  assert.equal(url.pathname, '/realms/verso/protocol/openid-connect/logout');
  assert.equal(url.searchParams.get('post_logout_redirect_uri'), 'http://localhost:8080/panel/');
  assert.ok(url.searchParams.get('id_token_hint'));
  assert.equal(currentClaims(), null);
});

test('text: file names lose control and bidi characters; errors name the Retry-After', () => {
  assert.equal(plainName('fatura‮fdp.exe\u0007'), 'faturafdp.exe');
  assert.equal(messageOf(503, 11002, 5), 'Asistan meşgul; birkaç saniye sonra tekrar deneyin. (5 sn sonra tekrar deneyebilirsiniz.)');
  assert.equal(messageOf(503, 12345), 'Beklenmeyen bir sunucu hatası oluştu.');
  assert.equal(PAGE_SIZE, 100, 'the API maximum, so the 200-document quota fits in two pages');
});

// ---------- formats (ADR-0016) ----------
test('formats: the picker offers the API types; unknown formats are just documents', () => {
  assert.deepEqual([...UPLOAD_TYPES], ['.pdf', '.docx', '.txt', '.md', '.markdown']);
  assert.equal(formatLabel('DOCX'), 'Word');
  assert.equal(formatLabel('MD'), 'Markdown');
  assert.equal(formatLabel('XLSX'), 'Belge');
  assert.equal(formatLabel(undefined), 'Belge');
});

test('formats: a section is cited as a section, a page as a page', () => {
  assert.equal(sourceLabel({ fileName: 'izin.docx', page: 2, unit: 'SECTION' }), 'izin.docx, bölüm 2');
  assert.equal(sourceLabel({ fileName: 'izin.pdf', page: 3, unit: 'PAGE' }), 'izin.pdf, sayfa 3');
  assert.equal(sourceLabel({ fileName: 'eski.pdf', page: 1 }), 'eski.pdf, sayfa 1', 'an answer without unit is a page');
});

test('formats: every failure reason of the API has a Turkish text', () => {
  const source = readFileSync('services/document/document-api/src/main/java/com/verso/document/api/enums/DocumentFailureReason.java', 'utf8');
  const reasons = [...source.matchAll(/^\s+([A-Z_]+),?\s*$/gm)].map((m) => m[1]);
  assert.ok(reasons.length >= 9, reasons.join());
  for (const reason of reasons) assert.ok(FAILURES[reason], `no text for ${reason}`);
});

// ---------- structure (panel v2) ----------
test('modules: every panel module parses as an ES module (a syntax error blanks the whole panel)', () => {
  for (const file of readdirSync('panel/js', { recursive: true }).filter((f) => String(f).endsWith('.js'))) {
    const result = spawnSync(process.execPath, ['--check', '--input-type=module'], { input: readFileSync(`panel/js/${file}`, 'utf8') });
    assert.equal(result.status, 0, `${file}: ${result.stderr}`);
  }
});

test('icons: every icon the screens and the menu use is drawn', () => {
  for (const screen of Object.values(SCREENS)) assert.ok(ICON_NAMES.includes(screen.icon), screen.icon);
  for (const file of readdirSync('panel/js', { recursive: true }).filter((f) => String(f).endsWith('.js'))) {
    for (const m of readFileSync(`panel/js/${file}`, 'utf8').matchAll(/icon\('([a-zA-Z]+)'/g)) {
      assert.ok(ICON_NAMES.includes(m[1]), `${file}: unknown icon ${m[1]}`);
    }
  }
});

// ---------- design v3 (ADR-0017) ----------
test('search: Turkish letters fold, so "izin", "İZİN" and "sirket" find "İzin" and "şirket"', () => {
  assert.equal(foldTr('İzin Yönetmeliği'), 'izin yonetmeligi');
  assert.equal(foldTr('IŞIK ÇAĞRI'), 'isik cagri');
  assert.equal(foldTr('ızgara'), 'izgara');
  assert.ok(fuzzyScore('izin', 'İzin Yönetmeliği') > 0);
  assert.ok(fuzzyScore('sirket', 'Şirket laptopu') > 0);
  assert.ok(fuzzyScore('İZİN', 'izin-yonetmeligi.pdf') > 0);
  assert.equal(fuzzyScore('xyz', 'Belgeler'), 0, 'letters that are not there do not match');
  assert.equal(fuzzyScore('', 'Belgeler'), 1, 'an empty query shows everything');
});

test('search: a prefix ranks above a word start, which ranks above scattered letters', () => {
  const prefix = fuzzyScore('bel', 'Belgeler');
  const word = fuzzyScore('bel', 'Yeni belge');
  const scattered = fuzzyScore('blg', 'Belgeler');
  assert.ok(prefix > word && word > scattered && scattered > 0, `${prefix} ${word} ${scattered}`);
});

test('sources: citations are grouped by document in order of first use', () => {
  const citations = [
    { number: 1, documentId: 'a', fileName: 'izin.pdf', page: 2, unit: 'PAGE' },
    { number: 2, documentId: 'b', fileName: 'masraf.docx', page: 1, unit: 'SECTION' },
    { number: 3, documentId: 'a', fileName: 'izin.pdf', page: 3, unit: 'PAGE' },
  ];
  const groups = groupSources(citations);
  assert.deepEqual(groups.map((g) => [g.fileName, g.items.map((c) => c.number)]), [['izin.pdf', [1, 3]], ['masraf.docx', [2]]]);
});

test('copy: the answer keeps its markers and lists its sources under it', () => {
  const result = { answer: 'Yirmi gün [1].', citations: [{ number: 1, fileName: 'izin.pdf', page: 2, unit: 'PAGE' }] };
  assert.equal(answerWithSources(result), 'Yirmi gün [1].\n\nKaynaklar:\n[1] izin.pdf, sayfa 2');
  assert.equal(answerWithSources({ answer: 'Bulunamadı.', citations: [] }), 'Bulunamadı.');
});

test('time: answers under ten seconds show one decimal, slower ones whole seconds', () => {
  assert.equal(formatSeconds(1320), '1,3 sn');
  assert.equal(formatSeconds(64873), '65 sn');
  assert.equal(greeting(8), 'Günaydın');
  assert.equal(greeting(14), 'İyi günler');
  assert.equal(greeting(23), 'İyi akşamlar');
});

test('fill: optional children that are null or false are skipped, never written as "null"', () => {
  const node = { children: null, replaceChildren(...c) { this.children = c; } };
  fill(node, 'a', null, false, undefined, ['b', null]);
  assert.deepEqual(node.children, ['a', 'b']);
});

test('screens: page content uses fill() where a child may be null (the panel showed "null" under the table)', () => {
  for (const file of readdirSync('panel/js/screens')) {
    const source = readFileSync(`panel/js/screens/${file}`, 'utf8');
    for (const m of source.matchAll(/(\w+)\.replaceChildren\(([\s\S]*?)\);\n/g)) {
      assert.doesNotMatch(m[2], /: null\b|\? null\b/, `${file}: ${m[1]}.replaceChildren with a possible null`);
    }
  }
});

test('login theme: keycloak.v2 child, CSS and markup only, Turkish texts for every key it uses', () => {
  const dir = 'deploy/keycloak/themes/verso/login';
  const props = readFileSync(`${dir}/theme.properties`, 'utf8');
  assert.match(props, /^parent=keycloak\.v2$/m);
  const ours = props.match(/^styles=css\/styles\.css css\/(verso-[a-z0-9-]+\.css)$/m);
  assert.ok(ours, 'the parent styles first, ours last, under a palette name (browsers cache theme files for 30 days)');
  readFileSync(`${dir}/resources/css/${ours[1]}`, 'utf8');
  assert.doesNotMatch(props, /^scripts=/m, 'the theme adds no scripts');
  const template = readFileSync(`${dir}/template.ftl`, 'utf8');
  const added = template.slice(template.indexOf('<div class="verso-split">'), template.indexOf('<div class="${properties.kcLogin!}">'));
  assert.ok(added.length > 0);
  assert.doesNotMatch(added, /<script|on[a-z]+=|javascript:/i, 'the brand panel is static markup');
  assert.match(template, /<nav class="verso-lang"[\s\S]*?<a href="\$\{l\.url\}"/, 'the language switch is a pair of links (TR / EN)');
  assert.doesNotMatch(template, /onchange=/, 'no inline script for the language');
  // FreeMarker fails the whole sign-in page (HTTP 500) on an unbalanced directive: count the openings and the closings.
  for (const tag of ['if', 'list', 'macro']) {
    const open = (template.match(new RegExp(`<#${tag}\\b`, 'g')) ?? []).length;
    const close = (template.match(new RegExp(`</#${tag}>`, 'g')) ?? []).length;
    assert.equal(open, close, `<#${tag}> and </#${tag}> are balanced`);
  }
  assert.match(template, /<input type="checkbox" id="verso-dark"[^>]*role="switch"/, 'the theme switch is a checkbox read by CSS');
  const used = new Set([...template.matchAll(/msg\("(verso\w+)"\)/g)].map((m) => m[1]));
  assert.ok(used.size >= 4, 'the book texts come from the message files');
  for (const lang of ['tr', 'en']) {
    const messages = readFileSync(`${dir}/messages/messages_${lang}.properties`, 'utf8');
    for (const key of used) assert.match(messages, new RegExp(`^${key}=.+`, 'm'), `${lang}: ${key}`);
  }
  const realm = JSON.parse(readFileSync('deploy/keycloak/realm/verso-realm.json', 'utf8'));
  assert.equal(realm.loginTheme, 'verso');
  assert.equal(realm.defaultLocale, 'tr');
  assert.match(readFileSync('compose.yaml', 'utf8'), /- \.\/deploy\/keycloak\/themes\/verso:\/opt\/keycloak\/themes\/verso:ro/);
});

test('sign-in: the IdP page is asked in the intro screen\'s language, Turkish unless EN was picked', () => {
  assert.match(readFileSync('panel/js/auth.js', 'utf8'), /ui_locales: prefs\.lang\(\)/);
  assert.match(readFileSync('panel/js/store.js', 'utf8'), /lang: \(\) => \(read\(KEYS\.lang\) === 'en' \? 'en' : 'tr'\)/, 'anything but "en" is Turkish');
});

test('intro: the example and the suggested questions are the installation\'s, in both languages', async () => {
  const { CONFIG } = await import('../panel/js/config.js');
  for (const lang of ['tr', 'en']) {
    const example = CONFIG.deployment.showcase[lang];
    for (const key of ['label', 'question', 'answer', 'source']) assert.ok(example[key], `${lang}.${key}`);
  }
  assert.match(CONFIG.deployment.showcase.tr.label, /^Örnek/, 'the intro says it is an example');
  assert.ok(CONFIG.deployment.suggestions.length >= 3);
  assert.match(readFileSync('panel/js/screens/ask.js', 'utf8'), /CONFIG\.deployment\.suggestions/);
});

test('signature: the Keycloak markup, its CSS animation and the favicon are generated from the same pen data', () => {
  const run = spawnSync(process.execPath, ['scripts/signature-theme.mjs', '--check'], { encoding: 'utf8' });
  assert.equal(run.status, 0, run.stderr);
  const strokes = timeline();
  assert.equal(strokes.length, SIGNATURE.pen.length);
  const end = strokes.at(-1).delay + strokes.at(-1).duration;
  assert.ok(Math.abs(end - SIGN_MS) < 1 && SIGN_MS <= 1200, `the word is signed quickly (${end} ms)`);
  for (let i = 1; i < strokes.length; i++) assert.ok(strokes[i].delay > strokes[i - 1].delay, 'strokes follow each other');
  const template = readFileSync('deploy/keycloak/themes/verso/login/template.ftl', 'utf8');
  const block = template.slice(template.indexOf('<#-- signature:start -->'), template.indexOf('<#-- signature:end -->'));
  assert.equal((block.match(/class="vs-s\d+"/g) ?? []).length, SIGNATURE.pen.length);
  assert.match(block, /maskUnits="userSpaceOnUse" x="16" y="81" width="425" height="169"/, 'the mask region is explicit (the default cut the letters)');
  // Before a stroke is drawn the path [0, L] must lie inside the gap of its dash pattern with at least half the stroke
  // width (the round cap) to spare on both sides; otherwise stray marks show at the ends (seen on the login page).
  const half = (SIGNATURE.thickness * 1.25) / 2;
  for (const [, dash, gap, offset] of block.matchAll(/stroke-dasharray="([\d.]+) ([\d.]+)" stroke-dashoffset="([\d.]+)"/g)) {
    const [L, G, O] = [+dash, +gap, +offset];
    assert.ok(O - L >= half && L + G - (O + L) >= half, `stroke of ${L}: gap ${G}, offset ${O}`);
  }
  assert.match(readFileSync('panel/js/signature.js', 'utf8'), /stroke-dasharray', `\$\{s\.real\} \$\{s\.real \+ 2 \* cap\}`/);
  assert.match(readFileSync('panel/index.html', 'utf8'), /<link rel="icon" href="img\/favicon\.svg" type="image\/svg\+xml">/);
  assert.match(readFileSync('deploy/edge/nginx.conf', 'utf8'), /image\/svg\+xml svg;/, 'the favicon is served as SVG');
  assert.match(readFileSync('panel/js/signature-data.js', 'utf8'), /Allura .*SIL Open Font License 1\.1/, 'the font licence travels with the outlines');
});

test('motion: every animation stops under "reduce motion" and styles are set through the CSSOM only (CSP)', () => {
  const css = readFileSync('panel/css/panel.css', 'utf8');
  assert.match(css, /@media \(prefers-reduced-motion: reduce\) \{\s*\*, \*::before, \*::after \{ animation-duration: \.01ms !important;/);
  const theme = readFileSync('deploy/keycloak/themes/verso/login/resources/css/verso-giris.css', 'utf8');
  assert.ok((theme.match(/prefers-reduced-motion: reduce/g) ?? []).length >= 2, 'the login theme and its signature stand still too');
  // WCAG 2.2.2: the sign-in ambience starts by itself, so it ends by itself (a few rounds, then the painting stands still).
  assert.doesNotMatch(theme, /infinite/, 'no endless motion on the login page');
  const intro = css.slice(css.indexOf('/* The intro before sign-in'), css.indexOf('/* ---------- the switches at the top right'));
  assert.ok(intro.length > 500, 'the intro block');
  assert.doesNotMatch(intro, /infinite/, 'the intro animates once and stands still');
  for (const file of ['motion.js', 'signature.js', 'app.js', 'screens/ask.js', 'screens/overview.js']) {
    const code = readFileSync(`panel/js/${file}`, 'utf8');
    assert.doesNotMatch(code, /setAttribute\(\s*'style'|\.cssText|style:\s*'/, `${file}: no style attributes`);
  }
  assert.match(readFileSync('panel/js/motion.js', 'utf8'), /export const reducedMotion/);
});

test('palette: the token pairs the screens use keep their contrast in both themes (WCAG 2.2 AA)', () => {
  const css = readFileSync('panel/css/panel.css', 'utf8');
  const block = (selector) => {
    const start = css.indexOf(`${selector} {`);
    return Object.fromEntries([...css.slice(start, css.indexOf('\n}', start)).matchAll(/--([\w-]+):\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]));
  };
  const light = block(':root');
  const dark = { ...light, ...block(':root[data-theme="dark"]') };
  const resolve = (tokens, value) => {
    for (let i = 0; i < 10 && value.startsWith('var('); i++) value = tokens[value.slice(6, -1)];
    return value;
  };
  const luminance = (hex) => {
    const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
      .map((c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };
  const ratio = (a, b) => {
    const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p);
    return (x + 0.05) / (y + 0.05);
  };
  const pairs = [['fg-default', 'bg-canvas', 4.5], ['fg-muted', 'bg-canvas', 4.5], ['fg-muted', 'bg-surface', 4.5],
    ['fg-subtle', 'bg-surface', 4.5], ['fg-accent', 'bg-canvas', 4.5], ['fg-on-emphasis', 'accent-emphasis', 4.5],
    ['border-control', 'bg-surface', 3], ['ink-muted', 'ink-1', 4.5], ['ink-gold-solid', 'ink-1', 4.5], ['signature-ink', 'bg-canvas', 3],
    ['fg-default', 'bg-sidebar', 4.5], ['fg-muted', 'bg-sidebar', 4.5], ['fg-subtle', 'bg-sidebar', 4.5], ['fg-accent', 'accent-bg-subtle', 4.5],
    ['success-text', 'success-bg', 4.5], ['warning-text', 'warning-bg', 4.5], ['danger-text', 'danger-bg', 4.5], ['info-text', 'info-bg', 4.5],
    // the answer screen (ADR-0024): marked excerpt words, the tinted citing sentence, the excerpt text, quiet actions
    ['fg-default', 'accent-bg-hover', 4.5], ['fg-default', 'accent-bg-subtle', 4.5], ['fg-muted', 'bg-subtle', 4.5], ['fg-subtle', 'bg-subtle', 4.5],
    ['fg-subtle', 'bg-canvas', 4.5], ['fg-accent', 'bg-surface', 4.5], ['fg-accent-strong', 'accent-bg-subtle', 4.5],
    // history, rating and the value dashboard (ADR-0025): chart marks and the quiet row icons of the history drawer are
    // UI parts (3:1); a pressed 👍/👎, the "Tümünü sil" warning and the drawer's and tooltip's muted text are text (4.5:1)
    ['chart-1', 'bg-surface', 3], ['chart-2', 'bg-surface', 3], ['fg-subtle', 'bg-raised', 3], ['fg-accent', 'accent-bg-subtle', 4.5],
    ['danger-text', 'bg-raised', 4.5], ['fg-muted', 'bg-raised', 4.5], ['fg-default', 'bg-raised', 4.5]];
  for (const [name, tokens] of [['light', light], ['dark', dark]]) {
    for (const [fg, bg, min] of pairs) {
      const value = ratio(resolve(tokens, `var(--${fg})`), resolve(tokens, `var(--${bg})`));
      assert.ok(value >= min, `${name}: ${fg} on ${bg} is ${value.toFixed(2)}:1, needs ${min}:1`);
    }
  }
});

test('setup: every step reflects the running system or the installation config, nothing is claimed by default', () => {
  const none = setupSteps({ signedIn: true, mode: null, ready: 0, cited: false, pilotGroup: false });
  assert.deepEqual(none.map((s) => s.done), [true, false, false, false, false]);
  const all = setupSteps({ signedIn: true, mode: 'local', ready: 3, cited: true, pilotGroup: true });
  assert.ok(all.every((s) => s.done));
  assert.match(setupSteps({ signedIn: true, mode: 'cloud', ready: 0, cited: false, pilotGroup: false })[1].note, /sağlayıcıya gider/);
  assert.deepEqual(PHASES.map((p) => p.id), ['discovery', 'pilot', 'rollout', 'integrations']);
  for (const connector of CONNECTORS) assert.ok(connector.title && connector.text);
  const setup = readFileSync('panel/js/screens/setup.js', 'utf8');
  assert.match(setup, /'Planlandı'/, 'connectors are shown as planned, not as working');
});

test('fonts: Fraunces is served from the panel itself with its licence, for Turkish letters too, and the CSP allows only that', () => {
  const css = readFileSync('panel/css/panel.css', 'utf8');
  const faces = [...css.matchAll(/url\(\.\.\/fonts\/([\w-]+\.woff2)\)/g)].map((m) => m[1]);
  assert.equal(faces.length, 4);
  for (const file of faces) assert.ok(readFileSync(`panel/fonts/${file}`).length > 10000, file);
  assert.ok(faces.some((f) => f.includes('latin-ext')), 'ğ, ş and İ live in latin-ext');
  assert.match(readFileSync('panel/fonts/OFL.txt', 'utf8'), /SIL Open Font License, Version 1\.1/);
  const nginx = readFileSync('deploy/edge/nginx.conf', 'utf8');
  assert.match(nginx, /font-src 'self';/);
  assert.match(nginx, /font\/woff2 woff2;/);
  assert.doesNotMatch(nginx, /font-src[^;]*(data:|https?:)/, 'no inline or remote fonts');
  const theme = 'deploy/keycloak/themes/verso/login/resources';
  for (const file of [...faces, 'OFL.txt']) {
    assert.ok(readFileSync(`${theme}/fonts/${file}`).equals(readFileSync(`panel/fonts/${file}`)), `the login theme serves the same ${file}`);
  }
  assert.match(readFileSync(`${theme}/css/verso-giris.css`, 'utf8'), /url\(\.\.\/fonts\/fraunces-latin-ext-standard-normal\.woff2\)/);
});

test('theme: the ivory light theme is the default even on a dark system; dark only when chosen', () => {
  assert.equal(isDark(null, true), false);
  assert.equal(isDark('light', true), false);
  assert.equal(isDark('dark', false), true);
  assert.equal(isDark('system', true), true);
  assert.equal(isDark('system', false), false);
  assert.match(readFileSync('deploy/keycloak/themes/verso/login/theme.properties', 'utf8'), /^darkMode=false$/m, 'the login page is light too');
});

// ---------- the installation's branding file (ADR-0021) ----------
const EXAMPLE = 'deploy/branding/branding.example.json';
const example = () => JSON.parse(readFileSync(EXAMPLE, 'utf8'));
const errorsOf = (raw) => validateBranding(raw).errors ?? [];

test('branding: the example file is valid and is merged over the defaults; keys left out keep the defaults', () => {
  const { deployment, errors } = validateBranding(example());
  assert.equal(errors, undefined, String(errors));
  assert.equal(deployment.productName, 'Bilgi Asistanı');
  assert.equal(deployment.client, 'Örnek Lojistik A.Ş.');
  assert.equal(deployment.logo, '/branding/logo.svg');
  assert.equal(deployment.accent.light['fg-accent'], '#1d5a85');
  assert.ok(Object.isFrozen(deployment) && Object.isFrozen(deployment.showcase.tr) && Object.isFrozen(deployment.suggestions));
  const partial = validateBranding({ client: '  Acme A.Ş. ', suggestions: ['Acme stok sayımı nasıl yapılır?'] }).deployment;
  assert.equal(partial.client, 'Acme A.Ş.', 'trimmed');
  assert.deepEqual([...partial.suggestions], ['Acme stok sayımı nasıl yapılır?']);
  for (const key of ['productName', 'logo', 'phase', 'pilotGroup', 'team', 'accent', 'showcase']) {
    assert.deepEqual(partial[key], DEPLOYMENT_DEFAULTS[key], key);
  }
  assert.equal(validateBranding({}).deployment.productName, 'Verso', 'an empty file is the defaults');
  assert.equal(validateBranding({ client: null, logo: null, accent: null }).deployment.client, null, 'null clears an optional field');
  assert.equal(DEPLOYMENT_DEFAULTS.productName, 'Verso');
});

test('branding: unknown keys, wrong types and a wrong showcase shape are refused, each named by its path', () => {
  const cases = [
    [[], 'the file must hold a JSON object'], [null, 'the file must hold a JSON object'], ['Verso', 'the file must hold a JSON object'],
    [{ colour: '#000000' }, 'colour is not a known key'],
    [JSON.parse('{"__proto__": {"client": "x"}}'), '__proto__ is not a known key'],
    [{ productName: 42 }, 'productName must be a string'],
    [{ productName: '   ' }, 'productName is empty'],
    [{ client: 'Acme‮gnp.exe' }, 'client contains control or bidi characters'],
    [{ pilotGroup: 'yes' }, 'pilotGroup must be true or false'],
    [{ phase: 'done' }, 'phase must be one of discovery, pilot, rollout, integrations'],
    [{ suggestions: 'Soru?' }, 'suggestions must be a list of questions'],
    [{ suggestions: [] }, 'suggestions is empty'],
    [{ suggestions: ['Soru?', 7] }, 'suggestions[1] must be a string'],
    [{ showcase: { tr: example().showcase.tr } }, 'showcase.en must be an object with label, question, answer, source'],
    [{ showcase: { ...example().showcase, de: example().showcase.en } }, 'showcase.de is not a known key'],
    [{ showcase: { ...example().showcase, en: { ...example().showcase.en, link: 'x' } } }, 'showcase.en.link is not a known key'],
    [{ showcase: { ...example().showcase, tr: { label: 'x', question: 'y', answer: 'z' } } }, 'showcase.tr.source must be a string'],
    [{ accent: '#1d5a85' }, 'accent must be an object with "light" and/or "dark", or null'],
    [{ accent: { light: { 'fg-default': '#000000' } } }, 'accent.light.fg-default is not a known key'],
    [{ accent: { light: { 'fg-accent': '#15a' } } }, 'accent.light.fg-accent must be a colour written as #rrggbb'],
    [{ accent: { light: { 'fg-accent': 'red; background: url(x)' } } }, 'accent.light.fg-accent must be a colour written as #rrggbb'],
    [{ accent: { sepia: {} } }, 'accent.sepia is not a known key'],
  ];
  for (const [raw, expected] of cases) assert.ok(errorsOf(raw).includes(expected), `${JSON.stringify(raw)}: ${errorsOf(raw).join(' | ')}`);
  for (const logo of ['/branding/../secrets/x.svg', 'https://cdn.example.com/logo.svg', '//evil.example/logo.svg', '/panel/img/favicon.svg',
    '/branding/logo.svg?x=1', '/branding/logo.gif', '/branding/a/logo.png', 'javascript:alert(1)', 'data:image/svg+xml,<svg/>', 3]) {
    assert.ok(errorsOf({ logo }).some((e) => e.startsWith('logo must be a file under /branding/')), String(logo));
  }
  assert.ok(LOGO_PATTERN.test('/branding/acme_logo-2.webp'));
  assert.equal(validateBranding({ client: 'Acme', phase: 'done' }).deployment, undefined, 'one error rejects the whole file');
});

/** A stub of fetch for /branding.json: the body, a content type and an optional declared length. */
function served(body, { status = 200, type = 'application/json; charset=utf-8', length } = {}) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    const headers = { 'content-type': type, 'content-length': length };
    return { ok: status >= 200 && status < 300, status, headers: { get: (name) => headers[name.toLowerCase()] ?? null }, text: async () => body };
  };
  fetchImpl.calls = calls;
  return fetchImpl;
}

test('branding: oversized input is refused (strings over their cap, more than six suggestions, a file over 16 KB)', async () => {
  const long = (n) => 'a'.repeat(n);
  assert.equal(errorsOf({ productName: long(LIMITS.productName) }).length, 0, 'the cap itself is allowed');
  assert.ok(errorsOf({ productName: long(LIMITS.productName + 1) }).includes(`productName is longer than ${LIMITS.productName} characters`));
  assert.ok(errorsOf({ client: long(LIMITS.client + 1) }).includes(`client is longer than ${LIMITS.client} characters`));
  const showcase = { ...example().showcase, tr: { ...example().showcase.tr, answer: long(LIMITS.answer + 1) } };
  assert.ok(errorsOf({ showcase }).includes(`showcase.tr.answer is longer than ${LIMITS.answer} characters`));
  assert.equal(MAX_SUGGESTIONS, 6);
  assert.equal(errorsOf({ suggestions: Array.from({ length: 6 }, (_, i) => `Soru ${i}?`) }).length, 0);
  assert.ok(errorsOf({ suggestions: Array.from({ length: 7 }, (_, i) => `Soru ${i}?`) }).includes('suggestions has 7 questions, at most 6'));
  assert.ok(errorsOf({ suggestions: [long(LIMITS.suggestion + 1)] }).includes(`suggestions[0] is longer than ${LIMITS.suggestion} characters`));
  try {
    const warnings = [];
    const big = JSON.stringify({ client: 'Acme', team: ' '.repeat(MAX_BYTES) });
    assert.equal((await loadBranding({ fetchImpl: served(big), warn: (m) => warnings.push(m) })).client, null);
    assert.equal(warnings.length, 1);
    assert.match(warnings[0], /larger than 16384 bytes/);
    const declared = [];
    await loadBranding({ fetchImpl: served('{}', { length: String(MAX_BYTES + 1) }), warn: (m) => declared.push(m) });
    assert.match(declared[0], /larger than 16384 bytes/, 'a declared length over the cap is not even read');
  } finally {
    useDeployment(DEPLOYMENT_DEFAULTS);
  }
});

test('branding: an accent colour without enough contrast is refused (3:1 for UI parts, 4.5:1 for text and labels)', () => {
  // The gold fill is 2:1 on the ivory page: fine as a hairline in panel.css, refused as an installation's text colour.
  const gold = errorsOf({ accent: { light: { 'fg-accent': '#c9a45c' } } });
  assert.equal(gold.length, ACCENT_TOKENS['fg-accent'].against.length, gold.join(' | '));
  assert.match(gold[0], /^accent\.light\.fg-accent has 2\.\d\d:1 against bg-canvas, needs 4\.5:1$/);
  // A light grey button fails under its cream label.
  assert.ok(errorsOf({ accent: { light: { 'accent-emphasis': '#d8cdb6' } } })
    .some((e) => /accent-emphasis has [\d.]+:1 under its label \(fg-on-emphasis\), needs 4\.5:1/.test(e)));
  // Per theme: a dark ink focus ring is fine on ivory and invisible in the dark theme.
  assert.equal(errorsOf({ accent: { light: { 'focus-ring': '#1c1a17' } } }).length, 0);
  assert.ok(errorsOf({ accent: { dark: { 'focus-ring': '#1c1a17' } } }).some((e) => e.startsWith('accent.dark.focus-ring has')));
  // 3:1 is the floor for UI parts: just under it is refused, at or over it accepted.
  const canvas = THEME_COLOURS.light['bg-canvas'];
  const under = ['#8a7a63', '#958670', '#a09078'].find((c) => contrast(c, canvas) < 3 && contrast(c, canvas) > 2.5);
  const over = ['#7d6f5a', '#76684f', '#6b5e4c'].find((c) => Math.min(...ACCENT_TOKENS['accent-fill'].against
    .map((bg) => contrast(c, THEME_COLOURS.light[bg]))) >= 3);
  assert.ok(under && over, `${under} ${over}`);
  assert.ok(errorsOf({ accent: { light: { 'accent-fill': under } } }).length > 0, under);
  assert.equal(errorsOf({ accent: { light: { 'accent-fill': over } } }).length, 0, over);
  assert.equal(contrast('#000000', '#ffffff').toFixed(0), '21');
});

test('branding: a failed, refused or broken fetch keeps the defaults with one warning; a missing file with none', async () => {
  const run = async (fetchImpl, timeoutMs) => {
    const warnings = [];
    const deployment = await loadBranding({ fetchImpl, warn: (m) => warnings.push(m), timeoutMs });
    assert.equal(CONFIG.deployment, deployment, 'what the screens read');
    return { deployment, warnings };
  };
  const hanging = (_url, init) => new Promise((_resolve, reject) => init.signal.addEventListener('abort',
    () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' }))));
  try {
    const cases = [
      [async () => { throw new TypeError('Failed to fetch'); }, /could not be fetched/],
      [served('', { status: 500 }), /answered 500/],
      [served('<html>', { type: 'text/html' }), /not served as application\/json/],
      [served('{"client": "Acme",}'), /not valid JSON/],
      [served(JSON.stringify({ client: 'Acme', colour: 'red' })), /colour is not a known key/],
      [hanging, /no answer within 20 ms/],
    ];
    for (const [fetchImpl, reason] of cases) {
      const { deployment, warnings } = await run(fetchImpl, 20);
      assert.equal(deployment, DEPLOYMENT_DEFAULTS, String(reason));
      assert.equal(warnings.length, 1, String(reason));
      assert.match(warnings[0], /^Verso: \/branding\.json was ignored, the defaults are used \(ADR-0021\): /);
      assert.match(warnings[0], reason);
    }
    const missing = await run(served('', { status: 404 }));
    assert.equal(missing.deployment, DEPLOYMENT_DEFAULTS);
    assert.equal(missing.warnings.length, 0, 'no file is the normal case of an installation without branding');

    const fetchImpl = served(readFileSync(EXAMPLE, 'utf8'));
    const branded = await run(fetchImpl);
    assert.equal(branded.warnings.length, 0);
    assert.equal(CONFIG.deployment.client, 'Örnek Lojistik A.Ş.');
    assert.equal(fetchImpl.calls[0].url, '/branding.json', 'same origin: connect-src \'self\' allows it');
    assert.equal(fetchImpl.calls[0].init.credentials, 'omit');
  } finally {
    useDeployment(DEPLOYMENT_DEFAULTS);
  }
});

test('branding: the accent is set per theme through the CSSOM, the other theme\'s tokens removed', () => {
  const set = new Map();
  const style = { setProperty: (k, v) => set.set(k, v), removeProperty: (k) => set.delete(k) };
  const { accent } = validateBranding({ accent: { light: { 'fg-accent': '#1d5a85' } } }).deployment;
  applyAccent(style, 'light', accent);
  assert.deepEqual([...set], [['--fg-accent', '#1d5a85']]);
  applyAccent(style, 'dark', accent);
  assert.equal(set.size, 0, 'panel.css decides in the dark theme');
  applyAccent(style, 'light', null);
  assert.equal(set.size, 0);
  // The contrast table is the stylesheet's: resolve each background token of panel.css per theme.
  const css = readFileSync('panel/css/panel.css', 'utf8');
  const block = (selector) => {
    const start = css.indexOf(`\n${selector} {`);
    assert.ok(start >= 0, selector);
    return Object.fromEntries([...css.slice(start, css.indexOf('\n}', start + 1)).matchAll(/--([\w-]+):\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]));
  };
  const resolve = (tokens, value) => {
    for (let i = 0; i < 10 && value.startsWith('var('); i++) value = tokens[value.slice(6, -1)];
    return value;
  };
  const light = block(':root');
  const dark = { ...light, ...block(':root[data-theme="dark"]') };
  for (const [name, tokens, menu] of [['light', light, block('.sidebar')], ['dark', dark, block(':root[data-theme="dark"] .sidebar')]]) {
    const expected = {};
    for (const token of ['bg-canvas', 'bg-surface', 'bg-sidebar', 'accent-bg-subtle', 'fg-on-emphasis']) expected[token] = resolve(tokens, `var(--${token})`);
    expected['sidebar-surface'] = resolve(tokens, menu['bg-surface']);
    expected['sidebar-subtle'] = resolve(tokens, menu['accent-bg-subtle']);
    assert.deepEqual({ ...THEME_COLOURS[name] }, expected, name);
  }
  for (const token of Object.keys(ACCENT_TOKENS)) assert.ok(light[token] && dark[token], `--${token} is an accent token of panel.css`);
});

test('branding: loaded before the first screen, read at render time, served read-only and kept out of git', () => {
  const app = readFileSync('panel/js/app.js', 'utf8');
  const start = app.slice(app.indexOf('async function start()'));
  assert.ok(start.indexOf('await branding;') > 0 && start.indexOf('await branding;') < start.indexOf('showSignIn()'), 'before the intro');
  assert.ok(start.indexOf('await branding;') < start.indexOf('buildShell()'), 'before the shell');
  for (const file of ['app.js', ...readdirSync('panel/js/screens').map((f) => `screens/${f}`)]) {
    assert.doesNotMatch(readFileSync(`panel/js/${file}`, 'utf8'), /^const \w+ = CONFIG\.deployment/m, `${file}: read at module load, before the file arrived`);
  }
  const nginx = readFileSync('deploy/edge/nginx.conf', 'utf8');
  const open = nginx.indexOf('location = /branding.json {');
  const json = nginx.slice(open, nginx.indexOf('\n        }', open));
  for (const line of ['alias /usr/share/verso-branding/branding.json;', 'default_type application/json;',
    'add_header Cache-Control "no-cache" always;', 'add_header X-Content-Type-Options nosniff always;']) {
    assert.ok(json.includes(line), line);
  }
  assert.ok(nginx.includes(`location ~ "^/branding/([A-Za-z0-9][A-Za-z0-9_-]{0,63}\\.(?:svg|png|webp))$" {`), 'the logo pattern of branding.js');
  assert.equal(LOGO_PATTERN.source, '^\\/branding\\/[A-Za-z0-9][A-Za-z0-9_-]{0,63}\\.(svg|png|webp)$');
  assert.match(nginx, /location \/branding\/ \{\s*add_header X-Rag-Mode \$rag_mode always;\s*return 404;\s*\}/, 'nothing else from the directory');
  const branding = nginx.slice(open, nginx.indexOf('location / {'));
  assert.equal((branding.match(/add_header X-Rag-Mode \$rag_mode always;/g) ?? []).length, 3, 'every response says the mode (llm-rules 1.3)');
  for (const file of ['compose.yaml', 'deploy/compose.cloud.yaml']) {
    assert.match(readFileSync(file, 'utf8'), /- \.\/deploy\/branding:\/usr\/share\/verso-branding:ro/, file);
  }
  const ignored = readFileSync('.gitignore', 'utf8');
  assert.match(ignored, /^\/deploy\/branding\/\*$/m);
  assert.match(ignored, /^!\/deploy\/branding\/branding\.example\.json$/m);
});

// ---------- the answer screen (ADR-0024) ----------
// The excerpt highlight uses the server's matching rules (qa-core ExcerptSelector, SentenceSplitter; ADR-0020). The
// expected values below are what the Java code returns for the same input (compared once against it; see the evidence).
test('excerpt match: words fold the Turkish way the server does (İ/ı, marks, NFKC)', () => {
  assert.equal(fold('İZİN'), 'izin');
  assert.equal(fold('ızın'), 'izin');
  assert.equal(fold('IŞIK'), 'isik');
  assert.equal(fold('Çalışanlara'), 'calisanlara');
  assert.equal(fold('Öğrenci Ücreti'), 'ogrenci ucreti');
  assert.equal(fold('ﬁnans'), 'finans', 'NFKC first: the ligature is two letters');
  assert.equal(fold('ＡＢＣ'), 'abc', 'full-width letters');
  assert.equal(STEM, 5);
  assert.equal(key('çalışanlara'), key('Çalışanın'));
  assert.equal(key('İzinlerin'), key('izinleri'));
  assert.notEqual(key('izin'), key('izni'), 'shorter words must be equal');
  for (const stop of ['ve', 'x', 'belgeye', 'Göre', 'BU']) assert.equal(key(stop), null, stop);
});

test('excerpt match: numbers compare by their digits, in any script', () => {
  assert.equal(key('25.000'), '#25000');
  assert.equal(key('25000'), '#25000');
  assert.equal(key('3.2'), key('3,2'));
  assert.equal(key('١٢٣'), '#123', 'Arabic-Indic digits (Character.digit)');
  assert.equal(key('𝟏𝟐'), '#12', 'mathematical bold digits');
  assert.equal(key('𝟙𝟚'), '#12', 'double-struck digits: a later run of ten in the same block');
  assert.deepEqual([...terms('Avans 25.000 TL\'dir ve bu')].sort(), ['#25000', 'avans', 'dir', 'tl']);
});

test('excerpt match: sentence boundaries follow the server (abbreviations, initials, ordinals, numbers, list lines)', () => {
  const split = (text, lines = false) => sentences(text, lines).map((s) => text.slice(s.start, s.end));
  assert.deepEqual(split('Yıllık izin on dört gündür. İzin bölünebilir. Çalışan önceden bildirir.'),
    ['Yıllık izin on dört gündür.', 'İzin bölünebilir.', 'Çalışan önceden bildirir.']);
  assert.deepEqual(split('Avans 25.000 TL\'dir. Prim oranı 3.2 puandır.'), ['Avans 25.000 TL\'dir.', 'Prim oranı 3.2 puandır.']);
  assert.deepEqual(split('Kural değişti. 2. Dünya Savaşı sonrası 3. Madde yürürlüğe girdi.'),
    ['Kural değişti.', '2. Dünya Savaşı sonrası 3. Madde yürürlüğe girdi.']);
  assert.deepEqual(split('Dr. Ayşe Kaya onayladı. Bkz. Madde 4 ve Prof. Demir\'in notu. Örn. İzin formu.'),
    ['Dr. Ayşe Kaya onayladı.', 'Bkz. Madde 4 ve Prof. Demir\'in notu.', 'Örn. İzin formu.']);
  assert.deepEqual(split('Verso A.Ş. Genel Müdürü M. Kaya imzaladı. T.C. Kimlik No. 12 ile başvurulur.'),
    ['Verso A.Ş. Genel Müdürü M. Kaya imzaladı.', 'T.C. Kimlik No. 12 ile başvurulur.']);
  assert.deepEqual(split('Ne dedi? "Evet!" Sonra gitti… Bekledi... Bitti'), ['Ne dedi?', '"Evet!"', 'Sonra gitti…', 'Bekledi...', 'Bitti']);
  assert.deepEqual(split('- Yıllık izin 14 gündür [1]\n- Avans 25.000 TL\'dir [2]', true), ['- Yıllık izin 14 gündür [1]', '- Avans 25.000 TL\'dir [2]']);
  assert.equal(sentences(' \n\t ').length, 0);
});

test('excerpt match: a marker belongs to its sentence, or to the one before when it opens a sentence', () => {
  const answer = 'Yıllık izin on dört gündür [1]. Avans 25.000 TL\'dir [2]. Ek ödeme yoktur. [2] Ayrıca izin bölünebilir [1].';
  assert.deepEqual(citingSpans(answer, 1).map((s) => answer.slice(s.start, s.end)),
    ['Yıllık izin on dört gündür [1].', '[2] Ayrıca izin bölünebilir [1].']);
  assert.deepEqual(citingSpans(answer, 2).map((s) => answer.slice(s.start, s.end)), ['Avans 25.000 TL\'dir [2].', 'Ek ödeme yoktur.']);
  assert.doesNotMatch(citingText(answer, 1), /\[\d\]/, 'markers are not evidence');
  assert.equal(citingText(answer, 9), '', 'a number the answer does not carry');
  assert.deepEqual(citingSpans('[1] Baştaki işaret.', 1).map((s) => [s.start, s.end]), [[0, 19]], 'a marker at the very start');
  assert.deepEqual(citingSpans('A [10]. B [1].', 1).map((s) => [s.start, s.end]), [[8, 14]], '[10] is not [1]');
  assert.equal(citingText('[1][2]', 1).trim(), '', 'markers only: no evidence');
  assert.equal(citingText('', 1), '');
});

test('excerpt match: every word of the excerpt that shares a key with the citing sentence is marked', () => {
  const excerpt = 'Çalışanlara yılda on dört iş günü ücretli YILLIK İZİN verilir; izni bölmek mümkündür.';
  const marked = (evidence) => matchRanges(excerpt, evidence).map((r) => excerpt.slice(r.start, r.end));
  // "günü" (four letters, exact) and "günüdür" (stem "gunud") are different keys, as on the server.
  assert.deepEqual(marked('Yıllık izin on dört iş günüdür.'), ['on', 'dört', 'iş', 'YILLIK', 'İZİN']);
  assert.deepEqual(marked('iş günü'), ['iş', 'günü']);
  assert.deepEqual(marked('Yillik izin'), ['YILLIK', 'İZİN'], 'an answer typed without Turkish letters');
  assert.deepEqual(marked('çalışanın hakkı'), ['Çalışanlara'], 'the five-letter stem');
  assert.deepEqual(marked('ve bu da'), [], 'stop words are no evidence');
  assert.deepEqual(marked(''), []);
  assert.equal(matchRanges('Avans tutarı en çok 25000 TL\'dir.', 'Avans 25.000 TL.').length, 3);
});

test('excerpt: the server\'s leading and trailing "…" are cut marks, three dots of the document are text', () => {
  assert.deepEqual(excerptParts('…başı kesik, sonu da…'), { text: 'başı kesik, sonu da', before: true, after: true });
  assert.deepEqual(excerptParts('Tam cümle.'), { text: 'Tam cümle.', before: false, after: false });
  assert.deepEqual(excerptParts('Bekledi...'), { text: 'Bekledi...', before: false, after: false });
  assert.deepEqual(excerptParts(undefined), { text: '', before: false, after: false });
});

const CITED = Object.freeze({
  answer: 'Stok toplama bitince düşer [1]. Rezerve kalemler ayrılamaz [1][2]. Bkz. [7].',
  found: true, outcome: 'ANSWERED', mode: 'local', model: 'gemma4:e2b',
  citations: [
    { number: 1, documentId: 'a', fileName: 'depo-kilavuzu.pdf', page: 3, unit: 'PAGE', excerpt: 'Stok, koli kapatıldığında düşer.' },
    { number: 2, documentId: 'b', fileName: 'notlar‮.docx', page: 2, unit: 'SECTION', excerpt: '…rezerve\ngörünür…' },
  ],
});

test('markdown: markers become footnotes with the source and the excerpt; unknown markers stay text', () => {
  assert.equal(answerMarkdown(CITED), [
    'Stok toplama bitince düşer[^1]. Rezerve kalemler ayrılamaz[^1][^2]. Bkz. \\[7\\].',
    '',
    '[^1]: depo-kilavuzu.pdf, sayfa 3 — “Stok, koli kapatıldığında düşer.”',
    '[^2]: notlar.docx, bölüm 2 — “…rezerve görünür…”',
    '',
    'Yapay zekâ ile üretildi',
    '',
  ].join('\n'));
  assert.equal(answerMarkdown({ answer: 'Belgelerde yok.', citations: [] }), 'Belgelerde yok.\n\nYapay zekâ ile üretildi\n');
  const file = answerMarkdown(CITED, { question: 'Stok ne zaman\ndüşer?', productName: 'Verso', mode: 'local', model: 'gemma4:e2b', at: '2026-10-24T09:00:00Z' });
  assert.ok(file.startsWith('# Stok ne zaman düşer?\n\nStok toplama'), 'the question is one heading line');
  assert.ok(file.endsWith('---\nVerso · Yerel · gemma4:e2b · 2026-10-24\n\nYapay zekâ ile üretildi · Verso\n'));
  const noExcerpt = answerMarkdown({ answer: 'A [1].', citations: [{ number: 1, fileName: 'a.pdf', page: 1 }] });
  assert.match(noExcerpt, /^\[\^1\]: a\.pdf, sayfa 1$/m, 'an older answer without excerpt still lists the source');
});

test('markdown: links, images, HTML, fake footnotes and bidi controls from the answer, an excerpt or a file name stay inert', () => {
  const planted = {
    answer: 'Bkz. ![x](https://dis.example/?d=1) <img src=x onerror=alert(1)> `kod` [tıkla](javascript:alert(1)) [1]\n[^1]: sahte‮',
    citations: [{ number: 1, fileName: 'a](http://evil).pdf', page: 1, unit: 'PAGE', excerpt: '<script>x</script> ![](https://h/?q=1)' }],
  };
  const md = answerMarkdown(planted, { question: '<b>soru</b> [a](b)' });
  assert.doesNotMatch(md.replace(/\\[\\`[\]<>]/g, ''), /[<>`]|!\[|\]\(/, md);
  assert.equal(md.match(/^\[\^1\]:/gm).length, 1, 'one footnote definition: the planted one is escaped');
  assert.doesNotMatch(md, /‮/);
  assert.equal(escapeMarkdown('a\\b'), 'a\\\\b', 'a backslash cannot undo an escape');
  assert.equal(escapeMarkdown('100.000 TL (KDV hariç) *önemli*'), '100.000 TL (KDV hariç) *önemli*', 'ordinary text stays readable');
});

test('answer segments: every text part knows its offset, around known and unknown markers', () => {
  const answer = '[1]Baş [2] orta [7] ve [10] son [1]';
  const citations = [{ number: 1 }, { number: 2 }, { number: 10 }];
  const segments = answerSegments(answer, citations);
  for (const part of segments.filter((p) => !p.citation)) assert.equal(answer.slice(part.start, part.start + part.text.length), part.text);
  assert.deepEqual(segments.filter((p) => !p.citation).map((p) => [p.text, p.start]), [['Baş ', 3], [' orta [7] ve ', 10], [' son ', 27]]);
  assert.equal(segments.filter((p) => p.citation).length, 4);
});

test('excerpt match: the stop words and abbreviations are the server\'s own lists', () => {
  const javaSet = (file, name) => {
    const source = readFileSync(`services/qa/qa-core/src/main/java/com/verso/qa/service/${file}`, 'utf8');
    const start = source.indexOf(`${name} = Set.of(`);
    assert.ok(start > 0, `${file}: ${name}`);
    return new Set([...source.slice(start, source.indexOf(');', start)).matchAll(/"([^"]+)"/g)].map((m) => m[1]));
  };
  assert.deepEqual(new Set(STOP_WORDS), javaSet('ExcerptSelector.java', 'STOP_WORDS'));
  assert.deepEqual(new Set(ABBREVIATIONS), javaSet('SentenceSplitter.java', 'ABBREVIATIONS'));
  const selector = readFileSync('services/qa/qa-core/src/main/java/com/verso/qa/service/ExcerptSelector.java', 'utf8');
  assert.match(selector, /static final int STEM = 5;/, 'the stem length the panel uses');
});

/** Minimal DOM stand-ins for highlight.js: text nodes, elements with replaceChildren, Range and the highlight registry. */
function fakeDom({ highlights }) {
  const text = (data) => ({ nodeType: 3, data, isConnected: true });
  globalThis.document = { createTextNode: text, createElement: (tag) => ({ tag, className: '', textContent: '' }) };
  if (highlights) {
    globalThis.CSS = { highlights: new Map() };
    globalThis.Highlight = class extends Set {};
    globalThis.Range = class {
      setStart(node, offset) { this.startContainer = node; this.start = offset; }
      setEnd(node, offset) { this.end = offset; }
    };
  } else {
    delete globalThis.CSS;
    delete globalThis.Highlight;
  }
  return { text, node: () => ({ children: [], replaceChildren(...c) { this.children = c; } }) };
}

test('highlight: matches become CSS highlight ranges over one text node, or <mark> elements without the API', () => {
  try {
    let dom = fakeDom({ highlights: true });
    let box = dom.node();
    writeMarked(box, 'izin on dört gün', [{ start: 0, end: 4 }, { start: 8, end: 12 }]);
    assert.equal(box.children.length, 1, 'one text node: the text is untouched');
    assert.deepEqual([...CSS.highlights.get(MATCH)].map((r) => [r.start, r.end]), [[0, 4], [8, 12]]);
    // The citing sentence over two drawn segments is clipped to each; an empty list clears the tint.
    const a = dom.text('Yıllık izin ');
    const b = dom.text('on dört gündür.');
    tintSentences([{ node: a, start: 0 }, { node: b, start: 12 }], [{ start: 7, end: 20 }]);
    assert.deepEqual([...CSS.highlights.get(CITED_HIGHLIGHT)].map((r) => [r.startContainer.data, r.start, r.end]),
      [['Yıllık izin ', 7, 12], ['on dört gündür.', 0, 8]]);
    tintSentences([{ node: a, start: 0 }], []);
    assert.equal(CSS.highlights.get(CITED_HIGHLIGHT).size, 0);
    a.isConnected = false;
    tintSentences([{ node: a, start: 0 }], [{ start: 0, end: 3 }]);
    box.children[0].isConnected = false;
    prune();
    assert.equal(CSS.highlights.get(MATCH).size + CSS.highlights.get(CITED_HIGHLIGHT).size, 0, 'ranges of removed text are dropped');

    dom = fakeDom({ highlights: false });
    box = dom.node();
    writeMarked(box, 'izin on dört gün', [{ start: 0, end: 4 }, { start: 8, end: 12 }]);
    assert.deepEqual(box.children.map((c) => (c.tag ? `<${c.textContent}>` : c.data)), ['<izin>', ' on ', '<dört>', ' gün']);
    assert.ok(box.children.filter((c) => c.tag).every((c) => c.tag === 'mark' && c.className === 'match'));
    box = dom.node();
    writeMarked(box, 'düz metin', []);
    assert.deepEqual(box.children.map((c) => c.data), ['düz metin']);
    tintSentences([], [{ start: 0, end: 1 }]); // no API: nothing to do, nothing thrown
  } finally {
    delete globalThis.document;
    delete globalThis.CSS;
    delete globalThis.Highlight;
    delete globalThis.Range;
  }
});

test('markdown: the download name is plain ASCII from the question, never empty', () => {
  assert.equal(markdownFileName('Satın alma onay matrisi nasıl işliyor?'), 'soru-satin-alma-onay-matrisi-nasil-isliyor.md');
  assert.equal(markdownFileName('İZİN ŞARTLARI'), 'soru-izin-sartlari.md');
  assert.equal(markdownFileName('../../etc/passwd'), 'soru-etc-passwd.md');
  assert.equal(markdownFileName('???'), 'cevap.md');
  assert.ok(markdownFileName('a'.repeat(300)).length <= 'soru-.md'.length + 48);
});

test('chips: the wide-screen marker says the document and the page or section, cut to a short name', () => {
  assert.equal(chipLabel({ fileName: 'izin-yonetmeligi.pdf', page: 3, unit: 'PAGE' }), 'izin-yonetmeligi · s. 3');
  assert.equal(chipLabel({ fileName: 'notlar.docx', page: 2, unit: 'SECTION' }), 'notlar · böl. 2');
  assert.equal(chipLabel({ fileName: 'eski.pdf', page: 1 }), 'eski · s. 1', 'no unit is a page');
  const long = chipLabel({ fileName: 'erp-satin-alma-kilavuzu-2026-revizyon.pdf', page: 4, unit: 'PAGE' });
  assert.ok(long.endsWith('… · s. 4') && [...long.split(' · ')[0]].length <= CHIP_NAME_MAX, long);
  assert.equal(chipLabel({ fileName: 'fatura‮fdp.exe', page: 1 }), 'faturafdp · s. 1', 'no bidi override reaches the chip');
  assert.equal(chipLabel({ fileName: '.pdf', page: 1 }), '.pdf · s. 1');
  assert.equal(clip('kısa', 10), 'kısa');
});

test('follow-ups: three skeptical questions after an answer, none after "not found"; each fits the API limit', () => {
  assert.deepEqual(FOLLOW_UPS.map((f) => f.label), ['Bu hangi belgeye dayanıyor?', 'Aksi bir madde var mı?', 'Bunu daha kısa anlat']);
  const cited = followUps(CITED);
  assert.deepEqual(cited.map((f) => [f.id, f.local]), [['sources', true], ['contrary', false], ['shorter', false]],
    'the sources of a cited answer are shown, not asked again');
  const uncited = followUps({ ...CITED, outcome: 'UNCITED', found: false, citations: [] })[0];
  assert.deepEqual([uncited.local, uncited.label], [false, 'Kaynaklı olarak yeniden sor'], 'no sources: a sourced answer is asked for');
  assert.deepEqual(followUps({ answer: 'x', outcome: 'NOT_FOUND', found: false, citations: [] }), []);
  assert.deepEqual(followUps({ answer: 'x', found: false, citations: [] }), [], 'an older answer without outcome');
  assert.equal(cited[1].question('İzin kaç gün?'), '“İzin kaç gün?” konusunda istisna ya da sınırlama getiren bir madde var mı?');
  for (const f of cited) {
    const question = f.question(`${'ç'.repeat(999)}\n`);
    assert.ok([...question].length <= MAX_QUESTION, `${f.id}: ${[...question].length}`);
    assert.ok(question.includes('…'), `${f.id}: the long question is cut, not the template`);
  }
  // At the limit exactly: a question that just fits is not cut, one more character is.
  const template = FOLLOW_UPS[2].template;
  const room = MAX_QUESTION - [...template('')].length;
  assert.equal([...fitQuestion(template, 'a'.repeat(room))].length, MAX_QUESTION);
  assert.doesNotMatch(fitQuestion(template, 'a'.repeat(room)), /…/);
  assert.match(fitQuestion(template, 'a'.repeat(room + 1)), /…/);
});

test('selection: "Bunu sor" quotes the selection on one line, capped, without control characters', () => {
  assert.equal(selectionQuestion('  yılda on dört\n iş günü '), '“yılda on dört iş günü” ');
  assert.equal(selectionQuestion('“alıntı”‮'), '“"alıntı"” ');
  assert.equal(selectionQuestion(' \n '), '');
  const long = selectionQuestion('kelime '.repeat(100));
  assert.ok([...long].length <= SELECTION_MAX + 3 && long.endsWith('…” '), long.length);
});

test('errors: each failure of the question API has an honest state and the right next step', () => {
  const of = (status, code, retryAfter) => errorState(new ApiError(status, code, retryAfter));
  assert.deepEqual(of(503, 11001, 5), { kind: 'down', title: 'Dil modeli yanıt vermiyor', text: MESSAGES[11001], retry: true, retryAfter: 5 });
  assert.equal(of(503, 10030, 10).title, 'Arama modeli yanıt vermiyor');
  assert.equal(of(503, 99997, 5).kind, 'down');
  assert.equal(of(502).kind, 'down', 'the edge proxy without the application');
  assert.deepEqual([of(503, 11002, 3).kind, of(503, 11002, 3).retryAfter], ['busy', 3]);
  assert.equal(of(429, 90102).kind, 'busy');
  assert.deepEqual([of(409, 10031).kind, of(409, 10031).retry], ['reindex', false]);
  assert.deepEqual([of(400, 90000).kind, of(400, 90000).retry], ['invalid', false]);
  assert.equal(of(401, 90100).kind, 'auth');
  assert.deepEqual([of(413, 11010).kind, of(411, 11011).kind], ['invalid', 'invalid']);
  assert.deepEqual([of(503, 90103).kind, of(502, 99998).kind], ['down', 'down']);
  assert.deepEqual([of(404, 12345).kind, of(404, 12345).title, of(404, 12345).retry], ['other', 'Cevap alınamadı', true]);
  assert.match(of(409, 10031).text, /yöneticinize bildirin\. Bu sırada soru sorulamaz\.$/);
  assert.deepEqual(errorState(new TypeError('Failed to fetch')), { kind: 'offline', title: 'Sunucuya ulaşılamadı',
    text: 'Bağlantınızı kontrol edip tekrar deneyin. Sorunuz gönderilmemiş olabilir.', retry: true });
  assert.doesNotMatch(of(503, 11001, 5).text, /sn sonra/, 'the retry button counts the wait down; the text does not repeat it');
});

test('answer screen: product name from the branding, highlights and motion rules, no new CSP directive', () => {
  const ask = readFileSync('panel/js/screens/ask.js', 'utf8');
  assert.doesNotMatch(ask, /['`"][^'`"\n]*\bVerso\b[^'`"\n]*['`"]/, 'no "Verso" in a screen text (ADR-0021)');
  assert.match(ask, /\$\{productName\} cevap verdi/);
  assert.match(ask, /disclosureLine\(productName\)/, 'the always-visible AI line uses the product name');
  assert.equal(FLASH_MS, 600);
  assert.match(readFileSync('panel/js/motion.js', 'utf8'), /duration: 600,/, 'the pulse lasts as long as the flash');
  const css = readFileSync('panel/css/panel.css', 'utf8');
  assert.match(css, /::highlight\(verso-match\) \{ background-color: var\(--accent-bg-hover\); color: var\(--fg-default\); \}/);
  assert.match(css, /::highlight\(verso-cited\) \{ background-color: var\(--accent-bg-subtle\); color: var\(--fg-default\); \}/);
  assert.match(css, /\.quote mark\.match \{ background: var\(--accent-bg-hover\); color: var\(--fg-default\);/, 'the fallback looks the same');
  const wide = css.slice(css.indexOf('@media (min-width: 1200px)'));
  assert.match(wide, /position: sticky;/, 'the source column sticks');
  assert.match(wide, /\.cite \.cite-n \{ display: none; \}/, 'the chip replaces the number');
  const touch = css.slice(css.indexOf('@media (pointer: coarse)'));
  assert.match(touch, /min-height: 44px;/);
  assert.match(touch, /width: 44px; height: 44px;/);
  assert.match(css, /:root\.keyboard-open \.composer \{ bottom: calc\(var\(--kb, 0px\) \+ 8px\); \}/);
  assert.match(css, /\.cite\.dismissed \.cite-pop \{ display: none !important; \}/, 'Esc hides the preview (WCAG 1.4.13)');
  const highlight = readFileSync('panel/js/highlight.js', 'utf8');
  assert.match(highlight, /CSS\.highlights/);
  assert.match(highlight, /document\.createElement\('mark'\)/, 'the fallback builds elements, not HTML');
  // The .md download is a Blob URL on an <a download> in this document: no fetch, no frame, no new CSP source.
  assert.match(ask, /URL\.createObjectURL\(new Blob\(\[text\], \{ type: 'text\/markdown;charset=utf-8' \}\)\)/);
  assert.match(ask, /URL\.revokeObjectURL/);
  const nginx = readFileSync('deploy/edge/nginx.conf', 'utf8');
  assert.ok(nginx.includes('add_header Content-Security-Policy "default-src \'none\'; script-src \'self\'; style-src \'self\'; img-src \'self\' data:; '
    + 'font-src \'self\'; connect-src \'self\' http://localhost:8180; form-action \'none\'; frame-ancestors \'none\'; base-uri \'none\'; '
    + 'require-trusted-types-for \'script\'; trusted-types \'none\'" always;'), 'the panel CSP is unchanged');
  assert.doesNotMatch(nginx, /blob:/);
});

test('answer screen: keyboard and focus (turn keys, Esc, focus back to the box after "Durdur")', () => {
  const ask = readFileSync('panel/js/screens/ask.js', 'utf8');
  assert.match(ask, /el\('article', \{ class: 'turn', tabindex: -1, 'aria-labelledby': id \}/, 'turns focusable, not tab stops');
  assert.match(ask, /event\.altKey && vertical/);
  assert.match(ask, /event\.key === 'PageUp' \|\| event\.key === 'PageDown'/);
  assert.match(ask, /busy\.abort\(\);\n\s+field\.focus\(\);/);
  assert.match(ask, /event\.key === 'Escape'/);
  assert.match(ask, /visualViewport/);
  assert.match(ask, /'aria-label': `Kaynak \$\{citation\.number\}: \$\{name\}, \$\{where\}`/, 'the chip keeps the full accessible name');
});

// ---------- history, rating and the value dashboard (ADR-0025) ----------
test('roles: the dashboard is gated on /v1/info roles only; a verso-admin realm role in the token alone does not open it', () => {
  const user = rolesOf({ realm_access: { roles: [] } });
  assert.deepEqual(withServerRoles(user, { roles: ['verso-user'] }), [ROLES.USER]);
  assert.equal(canOpen('insights', withServerRoles(user, { roles: ['verso-user'] })), false);
  const fromToken = rolesOf({ realm_access: { roles: ['verso-admin'] } });
  assert.deepEqual(fromToken, [ROLES.USER], 'the token is not the source of the admin role');
  assert.equal(canOpen('insights', fromToken), false);
  const admin = withServerRoles(user, { roles: ['verso-admin', 'verso-user'] });
  assert.deepEqual(admin, [ROLES.USER, ROLES.ADMIN]);
  assert.deepEqual(screensFor(admin), ['overview', 'ask', 'documents', 'setup', 'insights', 'collections', 'chunks', 'subjects', 'connectors']);
  assert.equal(SCREENS.insights.tab, false, 'phones reach it from the account menu, the tab bar keeps four tabs');
  const both = withServerRoles(rolesOf({ realm_access: { roles: ['verso-operator'] } }), { roles: ['verso-admin'] });
  assert.ok(screensFor(both).filter((id) => SCREENS[id].tab !== false).length <= 4);
  for (const info of [null, undefined, {}, { roles: 'verso-admin' }, { roles: [{ name: 'verso-admin' }] }, { roles: ['VERSO-ADMIN'] }]) {
    assert.equal(canOpen('insights', withServerRoles(user, info)), false, JSON.stringify(info));
  }
  assert.deepEqual(withServerRoles(admin, { roles: ['verso-admin'] }), admin, 'no duplicate role');
  const app = readFileSync('panel/js/app.js', 'utf8');
  assert.match(app, /roles = withServerRoles\(rolesOf\(currentClaims\(\)\), info\);/);
  assert.match(app, /setTimeout\(\(\) => resolve\(null\), INFO_WAIT_MS\)/, 'a slow /v1/info does not hold the panel');
  assert.match(app, /canOpen\('insights', roles\) \? \[\{ label: 'Değer panosu'/, 'the account menu leads to the dashboard');
});

test('conversation ids: a follow-up carries the id, the answer\'s own id wins, a refused conversation starts over once', () => {
  assert.deepEqual(askBody('Soru?', null), { question: 'Soru?' });
  assert.deepEqual(askBody('Soru?', 'c1'), { question: 'Soru?', conversationId: 'c1' });
  assert.equal(continueWith(null, { conversationId: 'c1', turnId: 't1' }), 'c1', 'the first answer opens the conversation');
  assert.equal(continueWith('c1', { conversationId: 'c1', turnId: 't2' }), 'c1');
  assert.equal(continueWith('c1', { conversationId: 'c9', turnId: 't3' }), 'c9', 'deleted or expired meanwhile: the server\'s new one');
  assert.equal(continueWith('c1', { conversationId: null, turnId: null }), 'c1', 'a turn that was not stored keeps the conversation');
  assert.equal(continueWith(null, { conversationId: null, turnId: null }), null);
  assert.equal(startsOver(new ApiError(404, 11020)), true);
  assert.equal(startsOver(new ApiError(409, 11022)), true);
  for (const error of [new ApiError(503, 11001), new ApiError(404, 11021), new ApiError(400, 90000), new TypeError('x'), null]) {
    assert.equal(startsOver(error), false);
  }
  assert.match(startOverNote(new ApiError(409, 11022)), /dolduğu için/);
  assert.match(startOverNote(new ApiError(404, 11020)), /artık yok/);
  const ask = readFileSync('panel/js/screens/ask.js', 'utf8');
  assert.match(ask, /api\.askLive\(question, controller\.signal, sentTo, live\)/, 'the question continues the open conversation');
  assert.match(ask, /if \(!\(sentTo && startsOver\(error\)\)\) throw error;[\s\S]*?api\.askLive\(question, controller\.signal, null, live\)/,
    'one retry without the id, and only for a follow-up');
  assert.match(ask, /if \(current\.generation !== generation\) return;\n\s+conversation\.push\(entry\);/,
    'an answer for a conversation that was left is not appended to the new one');
  // The answer log element of renderAsk is called "thread": the store's thread is imported under another name, or
  // its ids would be written onto the DOM node (seen in the fixture: the conversation bar read "null").
  assert.match(ask, /import \{ conversation, prefs, startNewConversation, thread as current \} from '\.\.\/store\.js';/);
  assert.doesNotMatch(ask, /\bthread\.(conversationId|title|generation)\b/);
});

test('conversation ids: "Yeni konuşma" and a conversation from the history replace the open one', () => {
  startNewConversation();
  const g0 = thread.generation;
  assert.deepEqual([thread.conversationId, thread.title, openConversation.length], [null, null, 0]);
  const stored = { id: 'c1', title: 'İzin', turns: [
    { id: 't1', question: 'İzin kaç gün?', answer: 'Yirmi gün [1] [2].', outcome: 'ANSWERED', mode: 'local', model: 'm',
      citations: [{ number: 1, documentId: 'd1', fileName: 'izin.pdf', page: 3, unit: 'PAGE', documentDeleted: false },
        { number: 2, documentId: 'd2', fileName: null, page: 1, unit: 'SECTION', documentDeleted: true }],
      feedback: { rating: 'DOWN', reason: 'OUTDATED', citationNumber: null, updatedAt: '2026-10-24T09:00:00Z' },
      createdAt: '2026-10-24T09:00:00Z', durationMs: 1420 },
    { id: 't2', question: 'Ya mazeret?', answer: 'Belgelerde bu sorunun cevabı bulunamadı.', outcome: 'NOT_FOUND', mode: 'local',
      model: 'm', citations: [], feedback: null, createdAt: '2026-10-24T09:01:00Z', durationMs: 300 }] };
  const entries = restoreEntries(stored);
  assert.equal(entries.length, 2);
  assert.deepEqual(entries[0].result.citations, [
    { number: 1, documentId: 'd1', fileName: 'izin.pdf', page: 3, unit: 'PAGE', deleted: false, inaccessible: false, collectionName: null },
    { number: 2, documentId: 'd2', fileName: DELETED_DOCUMENT, page: 1, unit: 'SECTION', deleted: true, inaccessible: false, collectionName: null }]);
  assert.equal(DELETED_DOCUMENT, 'Belge silinmiş');
  assert.deepEqual([entries[0].result.conversationId, entries[0].result.turnId, entries[0].restored, entries[0].ms], ['c1', 't1', true, 1420]);
  assert.equal(entries[0].feedback.reason, 'OUTDATED', 'the stored rating comes back');
  assert.deepEqual([entries[1].result.found, entries[1].result.outcome], [false, 'NOT_FOUND']);
  assert.equal(historyCitation({ number: 4, documentId: 'd', fileName: undefined, page: 2, documentDeleted: false }).fileName, DELETED_DOCUMENT,
    'a citation without a name never shows "undefined"');
  restoreConversation(stored.id, stored.title, entries);
  assert.deepEqual([thread.conversationId, thread.title, openConversation.length], ['c1', 'İzin', 2]);
  assert.ok(thread.generation > g0);
  const g1 = thread.generation;
  startNewConversation();
  assert.deepEqual([thread.conversationId, openConversation.length], [null, 0]);
  assert.ok(thread.generation > g1);
  assert.equal(turnCountLabel(3), '3 soru');
  assert.equal(hasMore({ number: 0, size: 20, totalElements: 21, totalPages: 2 }), true);
  assert.equal(hasMore({ number: 1, size: 20, totalElements: 21, totalPages: 2 }), false);
  assert.equal(hasMore(null), false);
});

test('history filter: Turkish letters fold both ways, case does not matter, only loaded titles are searched', () => {
  const items = [{ id: 1, title: 'İzin talebi nasıl yapılır?' }, { id: 2, title: 'IŞIK ve ısıtma bakımı' }, { id: 3, title: 'Şirket aracı' },
    { id: 4, title: 'Satın alma  onay matrisi' }, { id: 5, title: null }];
  const ids = (q) => filterConversations(items, q).map((c) => c.id);
  assert.deepEqual(ids('izin'), [1]);
  assert.deepEqual(ids('İZİN'), [1]);
  assert.deepEqual(ids('ızın'), [1], 'dotless ı matches İ');
  assert.deepEqual(ids('ışık'), [2], 'lower-case ışık finds IŞIK');
  assert.deepEqual(ids('sirket'), [3], 'ş without its cedilla');
  assert.deepEqual(ids('  onay   matrisi '), [4], 'spaces collapse on both sides');
  assert.deepEqual(ids(''), [1, 2, 3, 4, 5], 'an empty query shows everything');
  assert.deepEqual(ids('yok'), []);
});

test('history: a title is checked like the server does; "Tümünü sil" needs the typed word', () => {
  assert.equal(titleProblem('  İzin notlarım  '), null);
  assert.match(titleProblem('   '), /boş/);
  assert.equal(titleProblem('a'.repeat(TITLE_MAX)), null);
  assert.match(titleProblem('a'.repeat(TITLE_MAX + 1)), /120/);
  assert.match(titleProblem('fatura‮fdp'), /yön/);
  assert.match(titleProblem('satır\u0007'), /kontrol/);
  assert.equal(DELETE_ALL_WORD, 'SİL');
  for (const typed of ['SİL', 'sil', ' Sil ', 'SIL']) assert.equal(deleteAllConfirmed(typed), true, typed);
  for (const typed of ['', 's', 'sill', 'evet', null]) assert.equal(deleteAllConfirmed(typed), false, String(typed));
  const history = readFileSync('panel/js/screens/history.js', 'utf8');
  assert.match(history, /confirmDialog\(/, 'one conversation: the panel\'s own dialog');
  assert.match(history, /'aria-disabled': 'true'/, 'all: disabled until the word is typed');
  assert.match(history, /if \(go\.getAttribute\('aria-disabled'\) !== 'true'\) removeAll\(\);/);
  assert.match(history, /Konuşmalarınız yalnız size görünür ve kurulumun saklama süresi dolunca kendiliğinden silinir\./,
    'the API does not expose the retention: generic wording (ADR-0025)');
  for (const file of readdirSync('panel/js', { recursive: true }).filter((f) => String(f).endsWith('.js'))) {
    const code = readFileSync(`panel/js/${file}`, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '').replace(/\s\/\/ .*$/gm, '');
    assert.doesNotMatch(code, /(^|[^.\w])(window\.)?(confirm|alert|prompt)\(/m, `${file}: no native dialogs`);
  }
  assert.match(readFileSync('panel/js/ui.js', 'utf8'), /event\.key === 'Escape' && !document\.querySelector\('dialog\[open\]'\)/,
    'Esc in the confirmation dialog does not also close the drawer');
});

test('rating: 👍/👎 replace each other, 👎 opens the reasons, "Yanlış kaynak" asks which [n] when there are several', () => {
  const two = [{ number: 1 }, { number: 2 }, { number: 1 }];
  let s = initialFeedback(null);
  assert.deepEqual(s, { rating: null, reason: null, citationNumber: null, step: null });
  let out = rate(s, { type: 'up' }, two);
  assert.deepEqual(out.request, { rating: 'UP' });
  s = out.state;
  assert.equal(rate(s, { type: 'up' }, two).request, null, '👍 again sends nothing (no "unrate" in the API)');
  out = rate(s, { type: 'down' }, two);
  assert.deepEqual(out.request, { rating: 'DOWN' }, 're-rating replaces the previous one at once');
  assert.equal(out.state.step, 'reason');
  s = out.state;
  out = rate(s, { type: 'reason', reason: 'WRONG_SOURCE' }, two);
  assert.equal(out.request, null);
  assert.equal(out.state.step, 'citation', 'two citations: which one?');
  assert.equal(rate(out.state, { type: 'citation', number: 7 }, two).request, null, 'only a number of this answer');
  out = rate(out.state, { type: 'citation', number: 2 }, two);
  assert.deepEqual(out.request, { rating: 'DOWN', reason: 'WRONG_SOURCE', citationNumber: 2 });
  assert.equal(out.state.step, null);
  s = out.state;
  out = rate(s, { type: 'reason', reason: 'OUTDATED' }, two);
  assert.deepEqual(out.request, { rating: 'DOWN', reason: 'OUTDATED' }, 'another reason replaces the citation');
  assert.equal(out.state.citationNumber, null);
  const one = rate({ ...initialFeedback(null), rating: 'DOWN', step: 'reason' }, { type: 'reason', reason: 'WRONG_SOURCE' }, [{ number: 3 }]);
  assert.deepEqual(one.request, { rating: 'DOWN', reason: 'WRONG_SOURCE', citationNumber: 3 }, 'one citation: no question');
  assert.equal(rate({ ...initialFeedback(null), rating: 'DOWN' }, { type: 'reason', reason: 'WRONG_SOURCE' }, []).request, null);
  assert.equal(rate(initialFeedback(null), { type: 'reason', reason: 'OTHER' }, two).request, null, 'a reason only after 👎');
  assert.equal(rate({ ...initialFeedback(null), rating: 'DOWN' }, { type: 'reason', reason: 'RUDE' }, two).request, null, 'only the API\'s reasons');
  const open = rate({ rating: 'DOWN', reason: null, citationNumber: null, step: null }, { type: 'down' }, two);
  assert.deepEqual([open.request, open.state.step], [null, 'reason'], '👎 again only reopens the picker');
  assert.equal(rate(open.state, { type: 'close' }, two).state.step, null);
  const saved = rate({ ...open.state }, { type: 'saved', feedback: { rating: 'DOWN', reason: 'OTHER', citationNumber: null } }, two);
  assert.deepEqual([saved.state.reason, saved.state.step], ['OTHER', 'reason'], 'the server\'s answer is the state; the picker stays');
  assert.deepEqual(REASONS.map((r) => r.id), ['WRONG_SOURCE', 'INCORRECT_OR_MISSING', 'OUTDATED', 'OTHER']);
  assert.equal(reasonLabel('SOMETHING_NEW'), 'Diğer', 'an unknown reason reads "Diğer"');
  assert.deepEqual(reasonsFor([]).map((r) => r.id), ['INCORRECT_OR_MISSING', 'OUTDATED', 'OTHER']);
  assert.equal(thanks({ rating: 'UP' }), 'Teşekkürler.');
  assert.equal(thanks({ rating: 'DOWN', reason: 'WRONG_SOURCE', citationNumber: 2 }), 'Teşekkürler. Neden: Yanlış kaynak [2].');
  const enumSource = readFileSync('services/qa/qa-api/src/main/java/com/verso/qa/api/dto/FeedbackReason.java', 'utf8');
  const enumValues = [...enumSource.matchAll(/^\s+([A-Z_]+)[,;]?\s*$/gm)].map((m) => m[1]);
  assert.deepEqual(enumValues, REASONS.map((r) => r.id), 'the panel offers exactly the API\'s reasons');
  const rating = readFileSync('panel/js/rating.js', 'utf8');
  assert.match(rating, /'aria-pressed', String\(state\.rating === 'UP'\)/);
  assert.match(rating, /role: 'status', 'aria-live': 'polite'/, '"Teşekkürler" is announced politely');
  assert.match(rating, /if \(!result\.conversationId \|\| !result\.turnId\) return null;/, 'no rating without a stored turn');
});

test('dashboard range: presets end today, both ends included, across months, leap days and years', () => {
  assert.deepEqual([...PRESETS], [7, 30, 90]);
  assert.deepEqual(rangeFor(7, '2026-10-24'), { from: '2026-10-18', to: '2026-10-24' });
  assert.deepEqual(rangeFor(30, '2026-03-01'), { from: '2026-01-31', to: '2026-03-01' });
  assert.deepEqual(rangeFor(90, '2024-03-01'), { from: '2023-12-03', to: '2024-03-01' }, '2024 is a leap year');
  for (const days of PRESETS) {
    const range = rangeFor(days, '2026-01-05');
    assert.equal(daysBetween(range.from, range.to), days);
    assert.ok(validRange(range));
  }
  assert.deepEqual(rangeFor(7, new Date(2026, 9, 24, 23, 59)), { from: '2026-10-18', to: '2026-10-24' }, 'the browser\'s calendar day, not UTC');
  assert.equal(addDays('2026-12-31', 1), '2027-01-01');
  assert.equal(addDays('2026-03-29', 1), '2026-03-30', 'no DST jump');
  assert.equal(validRange({ from: '2026-10-25', to: '2026-10-24' }), false);
  assert.equal(validRange({ from: '2025-10-24', to: '2026-10-24' }), true, '366 days');
  assert.equal(validRange({ from: '2025-10-23', to: '2026-10-24' }), false, `more than ${MAX_RANGE_DAYS} days`);
  assert.equal(validRange({ from: '2026-1-1', to: '2026-10-24' }), false);
  assert.equal(rangeLabel({ from: '2026-09-25', to: '2026-10-24' }), '25 Eyl – 24 Eki 2026');
  assert.equal(rangeLabel({ from: '2025-12-28', to: '2026-01-03' }), '28 Ara 2025 – 3 Oca 2026');
  assert.equal(dayLabel('2026-10-24'), '24 Eki');
});

const METRICS = { from: '2026-10-01', to: '2026-10-03', timeZone: 'Europe/Istanbul',
  totals: { questions: 412, activeUsers: 37, activeUsersComplete: true, outcomes: { answered: 301, notFound: 74, uncited: 37 },
    outcomeRates: { answered: 0.7306, notFound: 0.1796, uncited: 0.0898 }, latency: { p50Ms: 2000, p95Ms: 5000 },
    modes: { local: 412, cloud: 0 }, feedback: { up: 58, down: 9, downReasons: { WRONG_SOURCE: 3, INCORRECT_OR_MISSING: 2, OUTDATED: 1, OTHER: 0 }, downWithoutReason: 3 } },
  daily: [{ day: '2026-10-01', questions: 12, activeUsers: 5, outcomes: { answered: 9, notFound: 2, uncited: 1 } },
    { day: '2026-10-02', questions: 0, activeUsers: 0, outcomes: { answered: 0, notFound: 0, uncited: 0 } },
    { day: '2026-10-03', questions: 4, activeUsers: 2, outcomes: { answered: 4, notFound: 0, uncited: 0 } }],
  documentNamesShown: false, topCitedDocuments: [], neverCitedDocuments: { total: 0, complete: true, documents: [] },
  unansweredQuestions: { enabled: false, minDistinctUsers: 3, questions: [] } };

test('dashboard numbers: tr-TR counts and percents, latency, 👍 ratio, "–" for what the API did not return', () => {
  assert.equal(formatCount(1284), '1.284');
  assert.equal(formatCount(0), '0');
  assert.equal(formatCount(null), '–');
  assert.equal(formatPercent(0.7306), '%73,1');
  assert.equal(formatPercent(1), '%100');
  assert.equal(formatPercent(0), '%0');
  assert.equal(formatPercent(null), '–', 'no question in the range: no rate');
  assert.equal(formatLatency(250), '250 ms');
  assert.equal(formatLatency(2000), '2 sn');
  assert.equal(formatLatency(2500), '2,5 sn');
  assert.equal(formatLatency(180000), '3 dk');
  assert.equal(formatLatency(null), '–');
  assert.equal(upRatio({ up: 58, down: 9 }).toFixed(4), '0.8657');
  assert.equal(upRatio({ up: 0, down: 0 }), null);
  const tiles = kpis(METRICS.totals);
  assert.deepEqual(tiles.map((t) => [t.id, t.value]), [['questions', '412'], ['users', '37'], ['cited', '%73,1'], ['notFound', '%18'],
    ['uncited', '%9'], ['latency', '2 sn / 5 sn'], ['up', '%86,6']]);
  assert.equal(tiles[6].note, '67 oy');
  assert.match(kpis({ ...METRICS.totals, activeUsersComplete: false })[1].note, /saklama süresinden eski/);
  const empty = kpis({ questions: 0, activeUsers: 0, activeUsersComplete: true, outcomes: { answered: 0, notFound: 0, uncited: 0 },
    outcomeRates: { answered: null, notFound: null, uncited: null }, latency: { p50Ms: null, p95Ms: null }, modes: { local: 0, cloud: 0 },
    feedback: { up: 0, down: 0, downReasons: {}, downWithoutReason: 0 } });
  assert.deepEqual(empty.map((t) => t.value), ['0', '0', '–', '–', '–', '– / –', '–']);
  assert.equal(empty[6].note, 'Henüz oy yok');
  assert.equal(isEmpty(METRICS), false);
  assert.equal(isEmpty({ ...METRICS, totals: { ...METRICS.totals, questions: 0 } }), true);
  assert.deepEqual(dailySeries(METRICS.daily), { days: ['2026-10-01', '2026-10-02', '2026-10-03'], questions: [12, 0, 4],
    cited: [0.75, null, 1], notFound: [2 / 12, null, 0] }, 'a day without questions has no rate, not 0 %');
  assert.deepEqual(reasonRows(METRICS.totals.feedback).map((r) => [r.label, r.value]), [['Yanlış kaynak', 3], ['Eksik ya da yanlış', 2],
    ['Güncel değil', 1], ['Diğer', 0], ['Neden belirtilmedi', 3]]);
  const doc = { documentId: '0199a3b4-1111-2222-3333-444455556666', fileName: 'izin‮fdp.pdf', documentDeleted: false };
  assert.equal(documentLabel(doc, false), 'Belge #0199a3b4', 'names hidden by the installation\'s policy');
  assert.equal(documentLabel({ ...doc, fileName: null }, true), 'Belge #0199a3b4', 'the API gave no name');
  assert.equal(documentLabel(doc, true), 'izinfdp.pdf', 'a shown name loses its bidi controls');
  assert.equal(documentLabel({ ...doc, documentDeleted: true, fileName: null }, true), 'Belge silinmiş');
  const insights = readFileSync('panel/js/screens/insights.js', 'utf8');
  assert.doesNotMatch(insights.replace(/^\/\/.*$/gm, ''), /kazan|tasarruf|saved/i, 'no "estimated time saved": nothing to derive it from honestly');
  assert.match(insights, /if \(!list\?\.enabled\) return null;/, 'unanswered questions only when the installation turned them on');
  assert.match(insights, /k-anonimlik/);
  assert.match(insights, /status === 403/, 'a refused role gets a calm screen, not an error toast');
  assert.match(insights, /CONFIG\.deployment\.productName/);
  for (const file of ['screens/insights.js', 'screens/history.js', 'rating.js', 'chart.js']) {
    assert.doesNotMatch(readFileSync(`panel/js/${file}`, 'utf8'), /['`"][^'`"\n]*\bVerso\b[^'`"\n]*['`"]/, `${file}: the product name comes from the branding`);
  }
});

test('charts: clean scales, gaps for days without data, the wash closes on the baseline, the crosshair snaps to a day', () => {
  assert.deepEqual(niceScale(412), { top: 500, step: 100 });
  assert.deepEqual(niceScale(7), { top: 8, step: 2 });
  assert.deepEqual(niceScale(3), { top: 3, step: 1 });
  assert.deepEqual(niceScale(2), { top: 2, step: 1 }, 'counts never get fractional ticks');
  assert.deepEqual(niceScale(2, 5, { integer: false }), { top: 2, step: 0.5 });
  assert.deepEqual(niceScale(0), { top: 5, step: 1 }, 'an empty range still has an axis');
  assert.deepEqual(tickValues(niceScale(412)), [0, 100, 200, 300, 400, 500]);
  assert.deepEqual(tickValues({ top: 1, step: 0.25 }), [0, 0.25, 0.5, 0.75, 1], 'shares on a fixed 0–100 % scale');
  const frame = { left: 0, right: 100, top: 0, bottom: 100, max: 10 };
  assert.equal(linePath([0, 5, null, 10, 2], frame), 'M0,100L25,50M75,0L100,80', 'a null breaks the line instead of dropping to 0');
  assert.equal(linePath([null, null], frame), '');
  assert.equal(areaPath([0, 5, null, 10, 2], frame), 'M0,100L0,100L25,50L25,100ZM75,100L75,0L100,80L100,100Z');
  assert.equal(areaPath([1, null, 3], frame), '', 'single points have no area');
  assert.deepEqual(isolatedPoints([1, null, 3, null, null, 4, 5]), [0, 2], 'lone points become dots');
  assert.equal(xAt(0, 1, 40, 200), 120, 'one day sits in the middle');
  assert.equal(yAt(0, 0, 10, 90), 90, 'a zero scale stays on the baseline');
  assert.equal(nearestIndex(-20, 30, 0, 290), 0);
  assert.equal(nearestIndex(146, 30, 0, 290), 15);
  assert.equal(nearestIndex(999, 30, 0, 290), 29);
  assert.deepEqual(axisDays(90), [0, 44, 89]);
  assert.deepEqual(axisDays(1), [0]);
  const chart = readFileSync('panel/js/chart.js', 'utf8');
  assert.match(chart, /document\.createElementNS\(SVG, tag\)/, 'SVG nodes, no markup strings');
  assert.match(chart, /'Tablo olarak göster'/, 'every chart has a table twin');
  assert.match(chart, /ArrowLeft: -1, ArrowRight: 1/, 'the crosshair moves with the keyboard too');
  assert.doesNotMatch(chart, /setAttribute\(\s*'style'|\.cssText/);
  const css = readFileSync('panel/css/panel.css', 'utf8');
  assert.match(css, /\.chart-line \{ fill: none; stroke-width: 2;/, '2 px lines');
  assert.match(css, /\.chart-grid line \{ stroke: var\(--border-muted\); stroke-width: 1;/, 'hairline solid grid');
  assert.doesNotMatch(css.slice(css.indexOf('value dashboard (ADR-0025)')), /stroke-dasharray/, 'no dashed gridlines');
  assert.match(css, /\.chart-area \{ stroke: none; opacity: \.1; \}/, 'a 10 % wash');
});

test('api: history, rating and metrics calls go to the documented paths with JSON bodies; ids are path-encoded', async () => {
  const { calls } = browser({ search: '?code=c&state=s', pending: { verifier: 'v', state: 's' } });
  tokenEndpoint(calls);
  await completeSignIn();
  const sent = [];
  globalThis.fetch = async (url, init = {}) => {
    sent.push({ url, method: init.method ?? 'GET', body: init.body, type: init.headers?.['Content-Type'] });
    const status = init.method === 'DELETE' ? 204 : 200;
    return { ok: true, status, headers: { get: () => null }, json: async () => ({ ok: true }) };
  };
  await api.ask('Soru?', undefined, 'c1');
  await api.ask('Soru?', undefined);
  await api.listConversations(2);
  await api.getConversation('a/b');
  await api.renameConversation('c1', 'Yeni ad');
  assert.equal(await api.deleteConversation('c1'), null);
  assert.equal(await api.deleteAllConversations(), null);
  await api.rate('c1', 't 1', { rating: 'DOWN', reason: 'WRONG_SOURCE', citationNumber: 2 });
  await api.metrics('2026-10-01', '2026-10-24');
  assert.deepEqual(sent.map((r) => [r.method, r.url, r.body ?? null]), [
    ['POST', '/v1/questions', '{"question":"Soru?","conversationId":"c1"}'],
    ['POST', '/v1/questions', '{"question":"Soru?"}'],
    ['GET', '/v1/conversations?page=2&size=20', null],
    ['GET', '/v1/conversations/a%2Fb', null],
    ['PATCH', '/v1/conversations/c1', '{"title":"Yeni ad"}'],
    ['DELETE', '/v1/conversations/c1', null],
    ['DELETE', '/v1/conversations', null],
    ['POST', '/v1/conversations/c1/turns/t%201/feedback', '{"rating":"DOWN","reason":"WRONG_SOURCE","citationNumber":2}'],
    ['GET', '/v1/admin/metrics?from=2026-10-01&to=2026-10-24', null]]);
  for (const r of sent.filter((x) => x.body)) assert.equal(r.type, 'application/json');
  signOut();
});

test('privacy: history and dashboard text stays in memory; nothing personal goes to sessionStorage, the URL or the console', () => {
  for (const file of ['conversations.js', 'feedback.js', 'metrics.js', 'chart.js', 'rating.js', 'screens/history.js', 'screens/insights.js']) {
    const source = readFileSync(`panel/js/${file}`, 'utf8');
    assert.doesNotMatch(source, /sessionStorage|localStorage|indexedDB/, `${file}: no browser storage`);
    assert.doesNotMatch(source, /location\.hash\s*=(?!=)|history\.(push|replace)State|URLSearchParams/, `${file}: no titles or questions in the URL`);
  }
  const store = readFileSync('panel/js/store.js', 'utf8');
  assert.match(store, /export const thread = \{ conversationId: null, title: null, generation: 0 \};/);
  assert.deepEqual([...store.matchAll(/^\s+(\w+): 'verso\.panel\.\w+'/gm)].map((m) => m[1]), ['theme', 'rail', 'onboarding', 'lang'],
    'sessionStorage keys are preferences only');
  const app = readFileSync('panel/js/app.js', 'utf8');
  assert.match(app, /class: 'nav-action', type: 'button', 'aria-haspopup': 'dialog'/, '"Geçmiş" in the menu');
  assert.match(app, /\{ label: 'Konuşma geçmişi', glyph: 'history', onSelect: showHistory \}/, 'phones reach the history from the account menu');
  assert.match(readFileSync('panel/js/screens/ask.js', 'utf8'), /onclick: \(\) => openHistory\(ctx\) \}, icon\('history', 'sm'\), 'Geçmiş'\)/);
});

// ---------- AI-interaction disclosure (EU AI Act Art. 50, ADR-0027) ----------
test('disclosure: the always-visible line and the first-use notice say it is an AI, with the product name and no case suffix', () => {
  const line = disclosureLine('Bilgi Asistanı');
  assert.equal(line, 'Yapay zekâ ile yazışıyorsunuz (Bilgi Asistanı); cevaplar hatalı olabilir, kaynağını kontrol edin.');
  const notice = disclosureNotice('Bilgi Asistanı');
  assert.equal(notice.title, 'Yapay zekâ ile yazışıyorsunuz');
  assert.match(notice.body, /^Bilgi Asistanı bir yapay zekâ asistanıdır, bir kişi değildir\./);
  assert.match(notice.body, /kaynağından kontrol edin/);
  assert.equal(notice.dismiss, 'Anladım');
  assert.doesNotMatch(`${line}${notice.body}`, /Bilgi Asistanı(?:nın|nin|'|’|ı |yı |ya )/, 'no Turkish suffix after the name (ADR-0021)');
});

test('disclosure: the ask screen shows the line next to the input and the notice in the page, not in a dialog', () => {
  const ask = readFileSync('panel/js/screens/ask.js', 'utf8');
  assert.match(ask, /el\('div', \{ class: 'meta' \}, el\('span', \{\}, icon\('info', 'sm'\), disclosureLine\(productName\)\), counter\)\)/,
    'the line sits in the composer meta row, under the question box');
  assert.match(ask, /disclosureNotice\(productName\)/);
  assert.match(ask, /role: 'note'/);
  assert.doesNotMatch(ask, /\balert\(|\bconfirm\(|\bprompt\(|showModal|<dialog/, 'no native or modal dialog for the notice');
  assert.doesNotMatch(readFileSync('panel/js/disclosure.js', 'utf8'), /innerHTML|insertAdjacentHTML|\.style\b|setAttribute\('style'/);
});

test('disclosure: a generated answer carries data-ai-generated, the fixed "not found" text does not', () => {
  const ask = readFileSync('panel/js/screens/ask.js', 'utf8');
  assert.match(ask, /'data-ai-generated': notFound \? null : 'true'/);
  assert.equal(ask.match(/data-ai-generated/g).length, 1, 'set on the answer element only');
});

test('disclosure: the notice flag is a preference in the existing onboarding key, shown until dismissed, per browser session', () => {
  const { store } = browser();
  assert.equal(prefs.onboarding().aiNoticeSeen, false, 'a new session shows the notice');
  prefs.markOnboarding({ aiNoticeSeen: true });
  assert.equal(prefs.onboarding().aiNoticeSeen, true);
  assert.deepEqual([...store.keys()], ['verso.panel.onboarding'], 'no new sessionStorage key');
  assert.deepEqual(JSON.parse(store.get('verso.panel.onboarding')), { asked: false, sawMode: false, dismissed: false, aiNoticeSeen: true },
    'booleans only: nothing the user wrote');
  assert.equal(prefs.onboarding().dismissed, false, 'dismissing the overview checklist is separate');
  store.clear();
  assert.equal(prefs.onboarding().aiNoticeSeen, false, 'a new session (empty sessionStorage) shows it again');
  globalThis.sessionStorage = { getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); }, removeItem() {} };
  assert.equal(prefs.onboarding().aiNoticeSeen, false, 'blocked storage: shown, never throws');
  assert.doesNotThrow(() => prefs.markOnboarding({ aiNoticeSeen: true }));
  delete globalThis.sessionStorage;
});

test('disclosure: the Markdown export ends with the AI footer, with the product name escaped; the CSP is unchanged', () => {
  assert.equal(generatedFooter('Verso'), 'Yapay zekâ ile üretildi · Verso');
  assert.equal(generatedFooter(null), 'Yapay zekâ ile üretildi');
  const copied = answerMarkdown(CITED, { productName: 'Bilgi Asistanı' });
  assert.ok(copied.endsWith('\n\nYapay zekâ ile üretildi · Bilgi Asistanı\n'), 'the clipboard copy carries it too');
  const planted = answerMarkdown(CITED, { productName: '[x](http://dis.example) <b>' });
  assert.doesNotMatch(planted.split('\n').at(-2).replace(/\\[\\`[\]<>]/g, ''), /[<>[\]]/, 'a branding name cannot inject a link or HTML into the footer');
  const ask = readFileSync('panel/js/screens/ask.js', 'utf8');
  assert.match(ask, /answerMarkdown\(result, \{ productName \}\)/, 'the copy button passes the product name');
  const nginx = readFileSync('deploy/edge/nginx.conf', 'utf8');
  assert.ok(nginx.includes('require-trusted-types-for \'script\'; trusted-types \'none\''), 'Trusted Types stay on');
  assert.doesNotMatch(readFileSync('panel/css/panel.css', 'utf8'), /\.ai-notice/, 'the notice reuses the .notice styles; no new rule, no inline style');
});

// ---------- shared collections (ADR-0028) ----------
test('collections: admin-only screen, grant input, history states and the collection badge', async () => {
  const { grantInput, grantLabel } = await import('../panel/js/screens/collections.js');
  const { canDelete, spaceLabel } = await import('../panel/js/screens/documents.js');
  const { INACCESSIBLE_DOCUMENT } = await import('../panel/js/conversations.js');
  const user = rolesOf({ realm_access: { roles: ['verso-operator'] } });
  assert.equal(canOpen('collections', user), false, 'only verso-admin manages collections');
  assert.equal(canOpen('collections', withServerRoles(user, { roles: ['verso-admin'] })), true);
  assert.equal(SCREENS.collections.tab, false);
  assert.match(readFileSync('panel/js/app.js', 'utf8'), /canOpen\('collections', roles\) \? \[\{ label: 'Bilgi alanları'/);

  assert.deepEqual(grantInput('GROUP', '/hukuk/sozlesmeler'), { principalType: 'GROUP', principal: '/hukuk/sozlesmeler' });
  assert.deepEqual(grantInput('USER', ' sub-1'), { principalType: 'USER', principal: ' sub-1' }, 'sent as typed: the server compares exactly');
  for (const [type, value] of [['ROLE', 'x'], ['GROUP', '  '], ['GROUP', 'g'.repeat(257)], ['USER', null]]) {
    assert.equal(grantInput(type, value), null, `${type} ${value}`);
  }
  assert.equal(grantLabel({ principalType: 'GROUP', principal: '/ik‮fdp' }), 'Grup: /ikfdp', 'no bidi override');

  // History: readable, lost access, deleted; an older server without documentAccessible still works.
  const base = { number: 1, documentId: 'd', page: 2, unit: 'PAGE' };
  assert.deepEqual(historyCitation({ ...base, fileName: 'a.pdf', documentDeleted: false, documentAccessible: true, collectionName: 'Hukuk' }),
    { ...base, fileName: 'a.pdf', deleted: false, inaccessible: false, collectionName: 'Hukuk' });
  const lost = historyCitation({ ...base, fileName: null, documentDeleted: false, documentAccessible: false, collectionName: null });
  assert.deepEqual([lost.fileName, lost.deleted, lost.inaccessible], [INACCESSIBLE_DOCUMENT, false, true]);
  assert.equal(INACCESSIBLE_DOCUMENT, 'Erişiminiz yok');
  const gone = historyCitation({ ...base, fileName: null, documentDeleted: true, documentAccessible: false });
  assert.deepEqual([gone.fileName, gone.deleted, gone.inaccessible], [DELETED_DOCUMENT, true, false]);
  assert.equal(historyCitation({ ...base, fileName: 'b.pdf', documentDeleted: false }).inaccessible, false);
  assert.equal(groupSources([{ documentId: 'd', fileName: 'a.pdf', collectionName: 'Hukuk' }])[0].collectionName, 'Hukuk');

  // Documents: shared documents carry a badge; only an admin deletes them (the server answers 403 10004 otherwise).
  assert.equal(canDelete({ collectionId: null }, false), true);
  assert.equal(canDelete({ collectionId: 'c' }, false), false);
  assert.equal(canDelete({ collectionId: 'c' }, true), true);
  assert.equal(spaceLabel({ collectionId: 'c', collectionName: 'Hukuk' }), 'Hukuk');
  assert.equal(spaceLabel({ collectionId: null }), 'Kişisel alan');
  for (const code of [10002, 10003, 10004, 10040, 10041, 10042, 10043, 10044]) assert.ok(MESSAGES[code], String(code));
  const source = readFileSync('panel/js/screens/collections.js', 'utf8');
  assert.doesNotMatch(source, /innerHTML|insertAdjacentHTML|\.style\b|setAttribute\('style'|window\.(alert|confirm|prompt)/);
  assert.match(source, /confirmDialog/, 'removals ask in the panel\'s own dialog, never a native one');
});

// ---------- streamed answers and the page viewer (ADR-0035) ----------
function sseParse(chunks) {
  const events = [];
  const parser = createSseParser((e) => events.push(e));
  for (const chunk of chunks) parser.push(chunk);
  parser.end();
  return events;
}

test('sse: events survive any chunk boundary, CRLF and CR line ends, multi-line data and keep-alive comments', () => {
  const body = 'event:stage\ndata:{"stage":"RETRIEVING"}\n\n:keep-alive\n\nevent: delta\ndata: {"text":"a"}\r\n\r\n'
    + 'event:citations\ndata:{"answer":"x",\ndata:"citations":[]}\r\rdata:no name\n\n';
  const whole = sseParse([body]);
  assert.deepEqual(whole, [
    { event: 'stage', data: '{"stage":"RETRIEVING"}' },
    { event: 'delta', data: '{"text":"a"}' },
    { event: 'citations', data: '{"answer":"x",\n"citations":[]}' },
    { event: 'message', data: 'no name' }]);
  // Every split point, also inside "\r\n" and inside a field name, gives the same events.
  for (let i = 1; i < body.length; i++) assert.deepEqual(sseParse([body.slice(0, i), body.slice(i)]), whole, `split at ${i}`);
  assert.deepEqual(sseParse(body.split('')), whole, 'one character at a time');
  assert.deepEqual(sseParse([':keep-alive\n\n', ':keep-alive\n\n']), [], 'heartbeats are no events');
  assert.deepEqual(sseParse(['event:done\ndata:{}']), [], 'an unterminated last event is dropped');
});

test('sse: a body read in byte chunks keeps multi-byte letters whole and parses the JSON data', async () => {
  const bytes = new TextEncoder().encode('event:delta\ndata:{"text":"Yıllık iş günü"}\n\nevent:bad\ndata:not json\n\n');
  const body = new ReadableStream({ start(c) {
    for (let i = 0; i < bytes.length; i += 3) c.enqueue(bytes.slice(i, i + 3));
    c.close();
  } });
  const seen = [];
  const count = await readEvents(body, (event, value) => seen.push([event, value]));
  assert.equal(count, 1);
  assert.deepEqual(seen, [['delta', { text: 'Yıllık iş günü' }]]);
});

test('stream: stages say only what the server did; the draft hides markers until they are validated', () => {
  assert.equal(stageText({ stage: 'RETRIEVING' }), 'Belgelerinizde aranıyor');
  assert.equal(stageText({ stage: 'PASSAGES_FOUND', retrieved: 5, relevant: 2 }), '2 ilgili pasaj bulundu');
  assert.equal(stageText({ stage: 'PASSAGES_FOUND', retrieved: 5, relevant: 0 }), 'İlgili pasaj bulunamadı');
  assert.equal(stageText({ stage: 'WRITING' }), 'Cevap yazılıyor');
  assert.equal(stageText({ stage: 'SOMETHING_NEW' }), 'Cevap hazırlanıyor');
  assert.equal(provisionalText('İzin yirmi gündür [1]. Ek izin [2, 3] vardır [9'), 'İzin yirmi gündür. Ek izin vardır ',
    'the space before a cut marker stays: the next word follows');
  assert.equal(provisionalText('Liste [a] kalır.'), 'Liste [a] kalır.');
  assert.deepEqual(resultOf({ answer: 'A [1].', citations: [{ number: 1 }] },
    { outcome: 'ANSWERED', found: true, mode: 'local', model: 'm', conversationId: 'c', turnId: 't' }),
  { answer: 'A [1].', citations: [{ number: 1 }], outcome: 'ANSWERED', found: true, mode: 'local', model: 'm', conversationId: 'c', turnId: 't',
    contextQuery: null });
});

test('follow-up (ADR-0046): the rewrite stage, the query from "done" and the "searched with context" note', () => {
  assert.equal(stageText({ stage: 'REWRITING' }), 'Soru bağlamla yeniden yazılıyor');
  assert.equal(resultOf({ answer: 'A', citations: [] }, { outcome: 'ANSWERED', found: true, contextQuery: 'BT P2 ilk yanıt süresi?' })
    .contextQuery, 'BT P2 ilk yanıt süresi?');
  assert.equal(contextQueryNote({ contextQuery: 'BT P2 olayında ilk yanıt süresi nedir?' }),
    'Bağlamla arandı: “BT P2 olayında ilk yanıt süresi nedir?”');
  assert.equal(contextQueryNote({ contextQuery: null }), null);
  assert.equal(contextQueryNote({}), null);
  assert.equal(contextQueryNote({ contextQuery: '  ‮ \n ' }), null, 'only control and bidi characters: no note');
  // Model text: one line, bidi overrides and line breaks gone, clipped; the screen sets it as text, never as markup.
  const note = contextQueryNote({ contextQuery: `ilk\nsatır ‮ters ${'uzun '.repeat(80)}` });
  assert.ok(note.startsWith('Bağlamla arandı: “ilk satır ters'));
  assert.ok(!/[\n‮]/.test(note));
  assert.ok([...note].length <= 'Bağlamla arandı: “”'.length + MAX_CONTEXT_QUERY);
  const ask = readFileSync(new URL('../panel/js/screens/ask.js', import.meta.url), 'utf8');
  assert.match(ask, /el\('p', \{ class: 'context-query'[^)]*\},\s*icon\('search', 'sm'\), contextNote\)/,
    'the note is a text child of el(), not innerHTML');
});

test('stream: falling back to JSON only when no stream gets through, never for the API\'s own errors', () => {
  assert.equal(noStream(200, 'text/event-stream;charset=UTF-8'), false);
  assert.equal(noStream(200, 'application/json'), true, 'a proxy or an old server answered');
  assert.equal(noStream(404, 'application/json', 90010), true, 'no such endpoint');
  assert.equal(noStream(404, 'text/html'), true);
  assert.equal(noStream(405, 'application/json', 90011), true);
  assert.equal(noStream(404, 'application/json', 11020), false, 'a conversation that is gone is a real answer');
  assert.equal(noStream(503, 'application/json', 11001), false);
  assert.equal(noStream(401, 'application/json', 90100), false);
});

function sseResponse(text, { status = 200, type = 'text/event-stream', headers = {} } = {}) {
  const body = new ReadableStream({ start(c) {
    c.enqueue(new TextEncoder().encode(text));
    c.close();
  } });
  return new Response(body, { status, headers: { 'Content-Type': type, 'X-Rag-Mode': 'local', ...headers } });
}

const STREAMED = 'event:stage\ndata:{"stage":"RETRIEVING"}\n\nevent:stage\ndata:{"stage":"PASSAGES_FOUND","retrieved":5,"relevant":1}\n\n'
  + 'event:stage\ndata:{"stage":"WRITING"}\n\nevent:delta\ndata:{"text":"Yirmi "}\n\n:keep-alive\n\nevent:delta\ndata:{"text":"gün [1] [7]."}\n\n'
  + 'event:citations\ndata:{"answer":"Yirmi gün [1].","citations":[{"number":1,"documentId":"d1","fileName":"a.pdf","page":3,"unit":"PAGE","excerpt":"x"}]}\n\n'
  + 'event:done\ndata:{"outcome":"ANSWERED","found":true,"mode":"local","model":"m","conversationId":"c1","turnId":"t1"}\n\n';

test('api: askLive streams with the token, reports stages and text, and ends with the validated answer', async () => {
  const { calls } = browser({ search: '?code=c&state=s', pending: { verifier: 'v', state: 's' } });
  tokenEndpoint(calls);
  await completeSignIn();
  const sent = [];
  globalThis.fetch = async (url, init = {}) => {
    sent.push({ url, init });
    return sseResponse(STREAMED);
  };
  const stages = [];
  const deltas = [];
  const result = await api.askLive('Soru?', undefined, 'c1', { stage: (s) => stages.push(s.stage), delta: (t) => deltas.push(t) });
  assert.equal(sent.length, 1);
  assert.equal(sent[0].url, '/v1/questions/stream');
  assert.equal(sent[0].init.method, 'POST');
  assert.equal(sent[0].init.body, '{"question":"Soru?","conversationId":"c1"}');
  assert.match(sent[0].init.headers.Authorization, /^Bearer h\./);
  assert.equal(sent[0].init.headers.Accept, 'text/event-stream, application/json');
  assert.deepEqual(stages, ['RETRIEVING', 'PASSAGES_FOUND', 'WRITING']);
  assert.deepEqual(deltas, ['Yirmi ', 'gün [1] [7].']);
  assert.equal(result.answer, 'Yirmi gün [1].', 'the validated text replaces the draft');
  assert.equal(result.citations.length, 1);
  assert.equal(result.outcome, 'ANSWERED');
  assert.equal(result.turnId, 't1');
  signOut();
});

test('api: an error event, an API error and an abort end the question; no stream falls back to JSON once', async () => {
  const { calls } = browser({ search: '?code=c&state=s', pending: { verifier: 'v', state: 's' } });
  tokenEndpoint(calls);
  await completeSignIn();
  const urls = [];
  let reply;
  globalThis.fetch = async (url, init = {}) => {
    urls.push(url);
    if (init.signal?.aborted) throw new DOMException('aborted', 'AbortError');
    return reply(url);
  };
  // An "error" event: the safe code, the Retry-After it carries.
  reply = () => sseResponse('event:stage\ndata:{"stage":"RETRIEVING"}\n\nevent:error\ndata:{"code":11002,"message":"busy","retryAfter":5}\n\n');
  await assert.rejects(api.askLive('Soru?', undefined), (e) => e instanceof ApiError && e.code === 11002 && e.retryAfter === 5);
  // A refusal before the stream (a conversation that is gone) is an ApiError, not a fallback.
  urls.length = 0;
  reply = () => new Response(JSON.stringify({ ok: false, error: { code: 11020 } }), { status: 404, headers: { 'Content-Type': 'application/json' } });
  await assert.rejects(api.askLive('Soru?', undefined, 'gone'), (e) => e instanceof ApiError && e.code === 11020);
  assert.deepEqual(urls, ['/v1/questions/stream']);
  // An abort is the user's: no fallback, no second request.
  urls.length = 0;
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(api.askLive('Soru?', controller.signal), { name: 'AbortError' });
  assert.deepEqual(urls, ['/v1/questions/stream']);
  // A proxy that answers JSON, an empty body, or no such endpoint: the JSON endpoint once.
  for (const first of [() => new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    () => sseResponse(':keep-alive\n\n'),
    () => new Response(JSON.stringify({ ok: false, error: { code: 90010 } }), { status: 404, headers: { 'Content-Type': 'application/json' } })]) {
    urls.length = 0;
    let fellBack = false;
    reply = (url) => (url === '/v1/questions/stream' ? first()
      : new Response(JSON.stringify({ answer: 'A', citations: [], outcome: 'UNCITED', found: false }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const result = await api.askLive('Soru?', undefined, null, { fallback: () => { fellBack = true; } });
    assert.deepEqual(urls, ['/v1/questions/stream', '/v1/questions']);
    assert.equal(result.outcome, 'UNCITED');
    assert.ok(fellBack);
  }
  // Events and then a cut connection: a failure, not a second question.
  urls.length = 0;
  reply = () => sseResponse('event:stage\ndata:{"stage":"WRITING"}\n\nevent:delta\ndata:{"text":"Yar"}\n\n');
  await assert.rejects(api.askLive('Soru?', undefined), TypeError);
  assert.deepEqual(urls, ['/v1/questions/stream']);
  signOut();
});

test('ask screen: stages and the end are announced, the draft is not; "Durdur" aborts; no reveal after a stream', () => {
  const ask = readFileSync('panel/js/screens/ask.js', 'utf8');
  assert.match(ask, /role: 'log', 'aria-live': 'off'/, 'the thread (and the draft in it) is no live region');
  assert.match(ask, /stage: \(event\) => \{[\s\S]*?announcer\.textContent = /, 'stage changes are announced');
  assert.doesNotMatch(ask, /delta: \(text\) => \{[^}]*announcer/, 'the text is not announced');
  assert.match(ask, /draft\.textContent = provisionalText\(written\)/, 'the draft is text, markers left out');
  assert.match(ask, /controller \? 'Durdur' : 'Gönder'/);
  assert.match(ask, /fresh: !entry\.streamed/, 'no word-by-word effect after a stream');
  assert.match(ask, /closePageDrawer\(\);/, 'leaving the screen closes the page viewer');
  const css = readFileSync('panel/css/panel.css', 'utf8');
  assert.doesNotMatch(css, /\.answer-text\.draft \{[^}]*animation/, 'the draft never animates');
  for (const file of ['stream.js', 'pageview.js', 'drawer.js']) {
    const source = readFileSync(`panel/js/${file}`, 'utf8');
    assert.doesNotMatch(source, /innerHTML|insertAdjacentHTML|\.style\b|setAttribute\('style'|EventSource\(|console\./, file);
  }
});

test('page viewer: navigation stays inside the document; sections are named as sections', () => {
  assert.deepEqual(pageNav(1, 3), { prev: null, next: 2 });
  assert.deepEqual(pageNav(2, 3), { prev: 1, next: 3 });
  assert.deepEqual(pageNav(3, 3), { prev: 2, next: null });
  assert.deepEqual(pageNav(2, null), { prev: 1, next: null }, 'an unknown count has no next');
  assert.equal(pageHeading('PAGE', 3, 12), 'Sayfa 3 / 12');
  assert.equal(pageHeading('SECTION', 2, 5), 'Bölüm 2 / 5');
  assert.equal(pageHeading('PAGE', 1, null), 'Sayfa 1');
});

test('page viewer: the excerpt is found across line breaks and cut marks, mapped back to the page text', () => {
  const page = 'Başlık\n\nYıllık izin süresi\nyirmi iş  günüdür. Ek izin\tyoktur.\n';
  const range = excerptRange(page, '…izin süresi yirmi iş günüdür. Ek…');
  assert.ok(range);
  assert.equal(page.slice(range.start, range.end), 'izin süresi\nyirmi iş  günüdür. Ek');
  // A page re-extracted since: the longest leading run of words that is still there.
  const partial = excerptRange(page, 'Yıllık izin süresi yirmi iş günüdür ama değişti');
  assert.equal(page.slice(partial.start, partial.end), 'Yıllık izin süresi\nyirmi iş  günüdür');
  assert.equal(excerptRange(page, 'hiç geçmeyen bir cümle burada'), null);
  assert.equal(excerptRange(page, ''), null);
  assert.equal(collapse(' a \n b ').out, 'a b ');
});

test('page viewer: a modal drawer that traps the focus, closes on Esc, gives the focus back and only writes text', () => {
  const drawer = readFileSync('panel/js/drawer.js', 'utf8');
  assert.match(drawer, /role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': titleId/);
  assert.match(drawer, /event\.key === 'Escape'/);
  assert.match(drawer, /event\.key !== 'Tab'/);
  assert.match(drawer, /last\.focus\(\)/);
  assert.match(drawer, /first\.focus\(\)/);
  assert.match(drawer, /opener\.focus\(\)/, 'the focus goes back to the opener');
  assert.match(drawer, /writeMarked\(text, page\.text, range \? \[range\] : \[\]\)/, 'CSS highlight or <mark>, text nodes only');
  assert.match(drawer, /prune\(\)/, 'the highlight ranges go with the drawer');
  const ask = readFileSync('panel/js/screens/ask.js', 'utf8');
  assert.match(ask, /c\.deleted \|\| c\.inaccessible \|\| !openPage \? null/, 'no viewer for a deleted or unreadable document');
  assert.match(ask, /openPageDrawer\(citation, \{ withAuth: ctx\.withAuth, opener \}\)/);
});

test('api: the page call goes to the documented path with encoded ids', async () => {
  const { calls } = browser({ search: '?code=c&state=s', pending: { verifier: 'v', state: 's' } });
  tokenEndpoint(calls);
  await completeSignIn();
  const sent = [];
  globalThis.fetch = async (url) => {
    sent.push(url);
    return { ok: true, status: 200, headers: { get: () => null }, json: async () => ({ text: 'x' }) };
  };
  await api.getPage('a/b', 3);
  assert.deepEqual(sent, ['/v1/documents/a%2Fb/pages/3']);
  assert.ok(MESSAGES[10005]);
  signOut();
});

// ---------- the "Yönetim" screens: KVKK requests, collection edits, the chunk inspector (ADR-0036) ----------
test('admin group: only verso-admin (from /v1/info) sees the "Yönetim" screens; users and operators see no change', async () => {
  const { ADMIN_GROUP, adminScreens } = await import('../panel/js/roles.js');
  const user = rolesOf({ realm_access: { roles: [] } });
  const operator = rolesOf({ realm_access: { roles: ['verso-operator'] } });
  assert.deepEqual(screensFor(user), ['overview', 'ask', 'documents', 'setup']);
  assert.deepEqual(screensFor(operator), ['overview', 'ask', 'documents', 'setup', 'system']);
  for (const roles of [user, operator, rolesOf({ realm_access: { roles: ['verso-admin'] } })]) {
    assert.deepEqual(adminScreens(roles), [], 'the token alone opens no admin screen');
    for (const id of ['subjects', 'chunks', 'connectors']) assert.equal(canOpen(id, roles), false, id);
  }
  const admin = withServerRoles(user, { roles: ['verso-admin'] });
  assert.deepEqual(adminScreens(admin), ['insights', 'collections', 'chunks', 'subjects', 'connectors']);
  assert.equal(ADMIN_GROUP, 'Yönetim');
  for (const id of adminScreens(admin)) {
    assert.equal(SCREENS[id].tab, false, `${id}: the tab bar keeps the user's four tabs`);
    assert.deepEqual(SCREENS[id].roles, [ROLES.ADMIN], id);
  }
  const keys = Object.values(SCREENS).map((s) => s.key);
  assert.equal(new Set(keys).size, keys.length, 'every "g" shortcut key is unique');
  const app = readFileSync('panel/js/app.js', 'utf8');
  assert.match(app, /return items\.length \? \[\{ group: ADMIN_GROUP \}, \.\.\.items\] : \[\];/, 'no empty "Yönetim" heading in the account menu');
  assert.match(app, /\.\.\.\(s\.id === firstAdmin \? \[adminHeading\] : \[\]\)/, 'the menu column heading only before the first admin screen');
  for (const id of ['chunks', 'subjects', 'connectors']) assert.match(app, new RegExp(`canOpen\\('${id}', roles\\) \\? \\[\\{ label: '${SCREENS[id].title}'`));
});

test('kvkk: the sub is checked with the server\'s pattern, exactly as typed; the confirmations match byte for byte', async () => {
  const { SUB_PATTERN, subProblem, validSub, deleteConfirmed, redactionConfirmed } = await import('../panel/js/subjects.js');
  const java = readFileSync('services/privacy/privacy-core/src/main/java/com/verso/privacy/service/SubjectIds.java', 'utf8');
  const server = /Pattern\.compile\("([^"]+)"\)/.exec(java)[1];
  assert.equal(SUB_PATTERN.source, `^${server}$`, 'the same character class and length as SubjectIds');
  for (const ok of ['f81d4fae-7dec-11d0-a765-00a0c91e6bf6', 'user@kurum.com.tr', 'a', 'x'.repeat(255), 'urn:a.b_c-d']) {
    assert.equal(validSub(ok), true, ok);
    assert.equal(subProblem(ok), null, ok);
  }
  for (const bad of ['', ' a', 'a ', 'a b', 'ç', 'a/b', 'a%2F', 'x'.repeat(256), 'a\nb', 'a‮b', null, undefined, 42]) {
    assert.equal(validSub(bad), false, String(bad));
    assert.ok(subProblem(bad), String(bad));
  }
  assert.doesNotMatch(subProblem('gizli kişi'), /gizli/, 'the problem text never repeats the value');
  assert.equal(deleteConfirmed('Abc-1', 'Abc-1'), true);
  for (const typed of ['abc-1', 'Abc-1 ', ' Abc-1', '', undefined, 'Abc-']) assert.equal(deleteConfirmed('Abc-1', typed), false, String(typed));
  assert.equal(deleteConfirmed('a b', 'a b'), false, 'an invalid sub is never confirmed');
  const id = '0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b';
  assert.equal(redactionConfirmed(id, id), true);
  for (const typed of [id.toUpperCase(), ` ${id}`, '', id.slice(1)]) assert.equal(redactionConfirmed(id, typed), false, typed);
  assert.equal(redactionConfirmed('not-a-uuid', 'not-a-uuid'), false);
});

test('kvkk: the summary says what a deletion deletes, keeps and reports; missing fields count as zero', async () => {
  const { summaryModel, deletionModel } = await import('../panel/js/subjects.js');
  const summary = {
    history: { conversations: 2, turns: 7, feedback: 1, activeDays: 3 },
    documents: { personalCount: 1, personal: [{ id: 'p1', fileName: 'izin.pdf' }], collectionCount: 1,
      collection: [{ id: 'c1', fileName: 'ortak.pdf', collectionId: 'k' }], grantedCollections: ['k'], createdCollections: [], listsTruncated: false },
    auditEventsAsActor: 42,
  };
  const model = summaryModel(summary);
  assert.deepEqual(model.deleted.map((r) => r.value), [2, 7, 1, 3, 1]);
  assert.deepEqual(model.kept.map((r) => r.value), [1, 1, 0, 42]);
  assert.match(model.deleted[4].label, /Kişisel belge/);
  assert.match(model.kept[3].label, /Denetim kaydı/);
  assert.deepEqual(model.collectionDocuments.map((d) => d.id), ['c1'], 'the kept collection documents are listed');
  assert.deepEqual([model.truncated, model.nothingToDelete, model.nothingFound], [false, false, false]);
  const empty = summaryModel({});
  assert.deepEqual([empty.nothingToDelete, empty.nothingFound, empty.collectionDocuments.length], [true, true, 0]);
  assert.equal(summaryModel({ documents: { listsTruncated: true } }).truncated, true);
  assert.equal(summaryModel({ history: { turns: 'x' } }).deleted[1].value, 0, 'a non-number is not shown as a count');

  const result = deletionModel({ historyDeleted: { conversations: 2, turns: 7 }, personalDocumentsDeleted: 1, answersRedacted: 3,
    kept: { documents: { collectionCount: 1, collection: [{ id: 'c1' }] }, auditEventsAsActor: 42 } });
  assert.deepEqual(result.rows.map((r) => r.value), [2, 7, 1, 3]);
  assert.equal(result.repeated, false);
  assert.deepEqual(result.kept.kept.map((r) => r.value), [1, 0, 0, 42]);
  assert.equal(deletionModel({ historyDeleted: {}, personalDocumentsDeleted: 0, answersRedacted: 0 }).repeated, true,
    'a repeated request deletes nothing more and says so');
});

test('kvkk: the export file name carries the time of the download, never the sub', async () => {
  const { exportFileName } = await import('../panel/js/subjects.js');
  assert.equal(exportFileName(new Date(2026, 9, 4, 9, 5)), 'verso-kvkk-disa-aktarim-20261004-0905.json');
  assert.match(exportFileName(), /^verso-kvkk-disa-aktarim-\d{8}-\d{4}\.json$/);
  const screen = readFileSync('panel/js/screens/subjects.js', 'utf8');
  assert.match(screen, /download: exportFileName\(\)/, 'the download uses the sub-free name');
  assert.match(screen, /URL\.revokeObjectURL\(url\)/, 'the Blob URL is released');
});

test('kvkk: the sub stays in memory: never in a URL, the hash, browser storage or the console, and is wiped on leaving', () => {
  for (const file of ['subjects.js', 'screens/subjects.js', 'chunks.js', 'screens/chunks.js']) {
    const source = readFileSync(`panel/js/${file}`, 'utf8');
    assert.doesNotMatch(source, /sessionStorage|localStorage|indexedDB|document\.cookie/, `${file}: no browser storage`);
    assert.doesNotMatch(source, /location\.(hash|search|href)\s*=(?!=)|history\.(push|replace)State|URLSearchParams|navigate\(/,
      `${file}: nothing in the URL`);
    assert.doesNotMatch(source, /console\./, `${file}: no console`);
    assert.doesNotMatch(source, /innerHTML|outerHTML|insertAdjacentHTML|\.style\b|setAttribute\('style'|style:\s*'|window\.(alert|confirm|prompt)/, file);
  }
  const screen = readFileSync('panel/js/screens/subjects.js', 'utf8');
  assert.match(screen, /return \(\) => \{\n\s+subject = null;/, 'leaving the screen drops the sub');
  assert.match(screen, /input\.value = ''/, 'and empties the fields that held it');
  assert.match(screen, /autocomplete: 'off'/, 'no autofill history for the sub');
  const api = readFileSync('panel/js/api.js', 'utf8');
  for (const m of api.matchAll(/request(?:File)?\('(\/v1\/admin\/subjects\/[^']*)'/g)) assert.doesNotMatch(m[1], /\$\{/, 'the sub never goes into the path');
  assert.equal([...api.matchAll(/'\/v1\/admin\/subjects\/(summary|export|delete)'/g)].length, 3);
});

test('api: KVKK, collection edit and chunk calls go to the documented paths; the sub only in the JSON body', async () => {
  const { calls } = browser({ search: '?code=c&state=s', pending: { verifier: 'v', state: 's' } });
  tokenEndpoint(calls);
  await completeSignIn();
  const sent = [];
  const sub = 'user@kurum.com.tr';
  globalThis.fetch = async (url, init = {}) => {
    sent.push({ url, method: init.method ?? 'GET', body: init.body ?? null, type: init.headers?.['Content-Type'] });
    const truncated = url.endsWith('/export') ? 'true' : null;
    return { ok: true, status: 200, headers: { get: (h) => (h === 'X-Subject-Export-Truncated' ? truncated : null) },
      json: async () => ({ ok: true }), blob: async () => new Blob(['{}'], { type: 'application/json' }) };
  };
  await api.subjectSummary(sub);
  const exported = await api.exportSubject(sub);
  await api.deleteSubject(sub, sub);
  await api.redactAnswers('d/1', 'd/1');
  await api.updateCollection('k 1', 'Hukuk', '');
  await api.listChunks('a/b', 2, 50);
  assert.equal(exported.truncated, true);
  assert.equal(await exported.blob.text(), '{}');
  assert.deepEqual(sent.map((r) => [r.method, r.url, r.body]), [
    ['POST', '/v1/admin/subjects/summary', '{"sub":"user@kurum.com.tr"}'],
    ['POST', '/v1/admin/subjects/export', '{"sub":"user@kurum.com.tr"}'],
    ['POST', '/v1/admin/subjects/delete', '{"sub":"user@kurum.com.tr","confirm":"user@kurum.com.tr"}'],
    ['POST', '/v1/admin/documents/d%2F1/answer-redaction', '{"confirm":"d/1"}'],
    ['PUT', '/v1/collections/k%201', '{"name":"Hukuk","description":null}'],
    ['GET', '/v1/admin/documents/a%2Fb/chunks?page=2&size=50', null]]);
  for (const r of sent) assert.ok(!r.url.includes('kurum'), `${r.url}: no sub in a URL`);
  for (const r of sent.filter((x) => x.body)) assert.equal(r.type, 'application/json');

  globalThis.fetch = async () => ({ ok: false, status: 403, headers: { get: () => null }, json: async () => ({ error: { code: 90101 } }) });
  await assert.rejects(api.exportSubject(sub), (error) => error instanceof ApiError && error.status === 403 && error.code === 90101);
  await assert.rejects(api.listChunks('x'), (error) => error.status === 403);
  for (const code of [13001, 13002, 13020, 13021]) assert.ok(MESSAGES[code], String(code));
  signOut();
});

test('collections: rename and description follow the server rules; an unchanged form is not sent', async () => {
  const { collectionEdit } = await import('../panel/js/screens/collections.js');
  const original = { name: 'Hukuk', description: 'Sözleşmeler' };
  assert.deepEqual(collectionEdit(original, '  Hukuk birimi ', 'Sözleşmeler'), { problem: null, body: { name: 'Hukuk birimi', description: 'Sözleşmeler' } });
  assert.deepEqual(collectionEdit(original, 'Hukuk', '  ').body, { name: 'Hukuk', description: null }, 'an empty description clears it');
  assert.equal(collectionEdit(original, 'Hukuk', 'Sözleşmeler').body, null, 'unchanged');
  assert.equal(collectionEdit({ name: 'A', description: null }, 'A', '').body, null, 'unchanged, no description');
  for (const [name, description] of [['', 'x'], ['   ', ''], ['x'.repeat(101), ''], ['ok', 'd'.repeat(501)], ['a‮b', ''], ['ok', 'satır\u0007']]) {
    const edit = collectionEdit(original, name, description);
    assert.equal(edit.body, null, `${name}/${description}`);
    assert.ok(edit.problem);
  }
  const source = readFileSync('panel/js/screens/collections.js', 'utf8');
  assert.match(source, /api\.updateCollection\(collection\.id, edit\.body\.name, edit\.body\.description\)/);
  assert.doesNotMatch(source, /innerHTML|\.style\b|setAttribute\('style'/);
});

test('chunk inspector: headings, previews, labels, paging without duplicates and a Turkish-folded search', async () => {
  const { CHUNK_PAGE_SIZE, chunkHeading, chunkLabel, chunkPreview, filterChunks, mergeChunks, PREVIEW_CHARS } = await import('../panel/js/chunks.js');
  assert.equal(chunkHeading('## İzin hakları ##\nYıllık izin…'), 'İzin hakları');
  assert.equal(chunkHeading('Madde 4 Yıllık izin\nÇalışan her yıl…'), 'Madde 4 Yıllık izin');
  assert.equal(chunkHeading('Bu bir cümledir.\nDevamı'), null, 'a sentence is no heading');
  assert.equal(chunkHeading('Tek satır'), null, 'nothing follows: no heading');
  assert.equal(chunkHeading(`${'u'.repeat(91)}\nx`), null, 'too long for a heading');
  assert.equal(chunkHeading(''), null);
  assert.equal(chunkPreview('a  b\n\nc'), 'a b c');
  const long = chunkPreview('kelime '.repeat(100));
  assert.ok(long.length <= PREVIEW_CHARS + 1 && long.endsWith('…') && !long.endsWith(' …'), long);
  assert.equal(chunkLabel({ unit: 'PAGE', number: 3, charCount: 1234 }), 'Sayfa 3 · 1.234 karakter');
  assert.equal(chunkLabel({ unit: 'SECTION', number: 2, charCount: 5 }), 'Bölüm 2 · 5 karakter');

  const c = (index, text = `parça ${index}`) => ({ index, number: 1, unit: 'PAGE', charCount: text.length, text });
  const first = mergeChunks([], { data: [c(0), c(1)], page: { number: 0, size: 2, totalElements: 5, totalPages: 3 } });
  assert.deepEqual([first.chunks.map((x) => x.index), first.more, first.nextPage, first.total], [[0, 1], true, 1, 5]);
  const again = mergeChunks(first.chunks, { data: [c(1), c(2), c(3)], page: { number: 1, size: 2, totalElements: 5, totalPages: 3 } });
  assert.deepEqual(again.chunks.map((x) => x.index), [0, 1, 2, 3], 'each index once, in order');
  const last = mergeChunks(again.chunks, { data: [c(4)], page: { number: 2, size: 2, totalElements: 5, totalPages: 3 } });
  assert.deepEqual([last.more, last.nextPage], [false, null]);
  assert.equal(mergeChunks([], { data: [], page: { number: 0, size: 50, totalElements: 0, totalPages: 0 } }).more, false);
  assert.ok(CHUNK_PAGE_SIZE <= 100, 'within the API cap');

  const chunks = [c(0, 'Yıllık İZİN hakkı'), c(1, 'Şirket aracı kullanımı'), c(2, 'izin ve şirket')];
  assert.deepEqual(filterChunks(chunks, 'izin').map((x) => x.index), [0, 2]);
  assert.deepEqual(filterChunks(chunks, 'SIRKET').map((x) => x.index), [1, 2]);
  assert.deepEqual(filterChunks(chunks, 'izin sirket').map((x) => x.index), [2], 'every word must appear');
  assert.equal(filterChunks(chunks, '  ').length, 3);
  const screen = readFileSync('panel/js/screens/chunks.js', 'utf8');
  assert.match(screen, /openPageDrawer\(\{\s*documentId: state\.document\.id/, 'the full page opens in the reused page viewer');
  assert.match(screen, /closePageDrawer\(\);\n\s+state = \{ document: null/, 'leaving the screen drops the loaded text');
  assert.match(readFileSync('panel/js/screens/documents.js', 'utf8'), /admin && fresh\.status === 'READY' \? el\('button'/, 'the document drawer leads admins here');
});

test('admin screens: 403 is a calm admin-only state, touch targets and reduced motion, no product-name suffix', () => {
  for (const file of ['screens/subjects.js', 'screens/chunks.js']) {
    const source = readFileSync(`panel/js/${file}`, 'utf8');
    assert.match(source, /error\.status === 403/, `${file}: a 403 is handled on the screen`);
    assert.match(source, /'Bu ekran yöneticiler içindir'/, file);
    assert.doesNotMatch(source, /Verso['’]|productName/, `${file}: the product name is not hard-coded with a suffix`);
    assert.doesNotMatch(source, /animate\(|requestAnimationFrame|transition:/, `${file}: no motion of its own`);
  }
  const css = readFileSync('panel/css/panel.css', 'utf8');
  assert.match(css, /@media \(pointer: coarse\) \{[\s\S]*?\.main\[data-group="admin"\] :is\(\.input, \.button, \.icon-button\), \.menu button \{ min-height: 44px; \}/);
  assert.match(readFileSync('panel/js/app.js', 'utf8'), /\$\('#main'\)\.dataset\.group = SCREENS\[screen\]\.group \?\? '';/);
  const block = css.slice(css.indexOf('/* ---------- the "Yönetim" group'), css.indexOf('/* screens cross-fade'));
  assert.ok(block.length > 200, 'the admin block');
  assert.doesNotMatch(block, /#[0-9a-f]{3,8}\b|rgb\(/i, 'only design tokens: the contrast pairs stay the tested ones');
  assert.doesNotMatch(block, /animation|transition/, 'no new motion');
});

// ---------- "Bağlayıcılar": the SMB connector screen (ADR-0038, ADR-0042) ----------
test('connectors: only verso-admin (from /v1/info) opens the screen; it sits in the "Yönetim" group, never in the tab bar', async () => {
  const { adminScreens } = await import('../panel/js/roles.js');
  for (const roles of [rolesOf({ realm_access: { roles: [] } }), rolesOf({ realm_access: { roles: ['verso-operator'] } }),
    rolesOf({ realm_access: { roles: ['verso-admin'] } }), rolesOf({ roles: ['verso-admin'] })]) {
    assert.equal(canOpen('connectors', roles), false, 'the token alone does not open it');
    assert.ok(!screensFor(roles).includes('connectors'));
  }
  const admin = withServerRoles(rolesOf({ realm_access: { roles: [] } }), { roles: ['verso-admin'] });
  assert.equal(canOpen('connectors', admin), true);
  assert.ok(adminScreens(admin).includes('connectors'));
  assert.deepEqual(SCREENS.connectors.roles, [ROLES.ADMIN]);
  assert.equal(SCREENS.connectors.group, 'admin');
  assert.equal(SCREENS.connectors.tab, false);
  assert.equal(SCREENS.connectors.title, 'Bağlayıcılar');
  assert.match(readFileSync('panel/js/app.js', 'utf8'), /connectors: renderConnectors/);
});

test('connectors: every code of the integration document has a Turkish message, and form codes land under their field', async () => {
  const { errorField, ERROR_FIELDS } = await import('../panel/js/connectors.js');
  const documented = [...readFileSync('docs/api-connectors-integration-v1.md', 'utf8').matchAll(/^\| (14\d{3}) \|/gm)].map((m) => Number(m[1]));
  assert.deepEqual(documented, [14001, 14002, 14003, 14004, 14005, 14006, 14007, 14008, 14009, 14010, 14011, 14012, 14013,
    14014, 14015]);
  for (const code of documented) {
    assert.ok(MESSAGES[code], `no message for ${code}`);
    assert.doesNotMatch(MESSAGES[code], /CONNECTOR_/, `${code}: words, not the constant`);
    assert.equal(new ApiError(400, code).message, MESSAGES[code]);
  }
  assert.equal(errorField(14002), 'name');
  assert.equal(errorField(14003), 'pathPrefix');
  assert.equal(errorField(14004), 'host');
  assert.equal(errorField(14005), 'collectionId');
  assert.equal(errorField(14006), 'collectionId');
  assert.equal(errorField(14009), 'includeGlobs');
  assert.equal(errorField(14010), 'maxFileMb');
  assert.equal(errorField(14013, 'CONFLUENCE'), 'baseUrl');
  assert.equal(errorField(14014, 'CONFLUENCE'), 'baseUrl');
  for (const code of [14001, 14007, 14008, 14011, 14012, 14015, 90002, undefined]) assert.equal(errorField(code), null, String(code));
  const screen = readFileSync('panel/js/screens/connectors.js', 'utf8');
  for (const field of Object.values(ERROR_FIELDS)) assert.match(screen, new RegExp(`\\b${field}: field\\(`), `${field} is a form field`);
});

test('connectors: health is a badge with words and a tone; STALE is not "OK"; unknown values are shown raw', async () => {
  const { healthBadge, HEALTH } = await import('../panel/js/connectors.js');
  assert.deepEqual(Object.keys(HEALTH), ['OK', 'STALE', 'FAILING', 'NEVER_SYNCED', 'DISABLED', 'DELETING'], 'the documented enum');
  assert.equal(healthBadge('OK').tone, 'ok');
  assert.equal(healthBadge('STALE').tone, 'wait');
  assert.equal(healthBadge('FAILING').tone, 'bad');
  assert.notEqual(healthBadge('STALE').label, healthBadge('OK').label);
  assert.match(healthBadge('STALE').help, /üç eşitleme aralığından eski/);
  for (const value of Object.keys(HEALTH)) {
    const badge = healthBadge(value);
    assert.ok(badge.label && badge.help, value);
    assert.ok(['ok', 'wait', 'bad', 'info', 'neutral'].includes(badge.tone), `${value}: a pill tone of panel.css`);
  }
  assert.deepEqual(healthBadge('NEW_STATE'), { label: 'NEW_STATE', tone: 'neutral', help: '' });
  assert.equal(healthBadge(null).label, 'Bilinmiyor');
  const css = readFileSync('panel/css/panel.css', 'utf8');
  for (const tone of ['ok', 'wait', 'bad', 'neutral']) assert.match(css, new RegExp(`\\.pill\\.${tone} \\{`));
});

test('connectors: the form mirrors the server rules and builds the documented request body', async () => {
  const { connectorRequest, connectorForm, hostProblem, globProblem, normalizePrefix, parseGlobs } = await import('../panel/js/connectors.js');
  const id = '0192a3b4-c5d6-7e8f-9a0b-1c2d3e4f5a6b';
  const good = { ...connectorForm(null), name: ' İK politikaları ', host: '10.20.30.40', share: 'Kurumsal', pathPrefix: '\\IK\\Politikalar\\',
    domain: 'CORP', username: 'svc-verso', collectionId: id, includeGlobs: '*.pdf\n\n {a,b}.docx ', excludeGlobs: 'arsiv/**', maxFileMb: '10',
    scheduleMinutes: '60', enabled: true };
  const { problems, body } = connectorRequest(good);
  assert.deepEqual(problems, {});
  assert.deepEqual(body, { name: 'İK politikaları', host: '10.20.30.40', port: 445, share: 'Kurumsal', pathPrefix: 'IK/Politikalar', domain: 'CORP',
    username: 'svc-verso', credentialRef: 'SECRET_CONNECTOR_SMB_PASSWORD', collectionId: id, includeGlobs: ['*.pdf', '{a,b}.docx'],
    excludeGlobs: ['arsiv/**'], maxFileBytes: 10485760, scheduleMinutes: 60, enabled: true });
  assert.ok(!Object.keys(body).some((key) => /password|secret/i.test(key)), 'no password field, ever');
  assert.equal(connectorRequest({ ...good, maxFileMb: '' }).body.maxFileBytes, null, 'empty: the server default (20 MB)');
  assert.equal(connectorRequest({ ...good, domain: '' }).body.domain, null);
  assert.equal(connectorRequest({ ...good, enabled: false }).body.enabled, false);

  const bad = (patch, field) => {
    const result = connectorRequest({ ...good, ...patch });
    assert.equal(result.body, null, JSON.stringify(patch));
    assert.ok(result.problems[field], `${JSON.stringify(patch)} → ${field}`);
  };
  bad({ name: '  ' }, 'name');
  bad({ name: 'x'.repeat(101) }, 'name');
  bad({ name: 'a\u202Eb' }, 'name');
  for (const host of ['', 'smb://dosya01', '\\\\dosya01', '8.8.8.8', '127.0.0.1', '169.254.169.254', 'localhost', '::1', '999.1.1.1']) bad({ host }, 'host');
  bad({ port: '0' }, 'port');
  bad({ port: '70000' }, 'port');
  bad({ share: 'Kurumsal/IK' }, 'share');
  bad({ share: '' }, 'share');
  for (const prefix of ['IK/../Muhasebe', 'C:/IK', 'IK//x', '//sunucu/pay', 'IK/a.', 'IK/*']) bad({ pathPrefix: prefix }, 'pathPrefix');
  bad({ domain: 'CORP\\x' }, 'domain');
  bad({ username: '' }, 'username');
  bad({ username: 'CORP\\svc' }, 'username');
  for (const ref of ['', 'hunter2', 'SECRET_DB_APP_PASSWORD', 'SECRET_CONNECTOR_', 'secret_connector_smb', `SECRET_CONNECTOR_${'A'.repeat(61)}`]) {
    bad({ credentialRef: ref }, 'credentialRef');
  }
  bad({ collectionId: '' }, 'collectionId');
  bad({ includeGlobs: Array.from({ length: 21 }, (_, i) => `*.${i}`).join('\n') }, 'includeGlobs');
  bad({ excludeGlobs: '../x' }, 'excludeGlobs');
  for (const mb of ['21', '0', 'çok']) bad({ maxFileMb: mb }, 'maxFileMb');
  for (const minutes of ['4', '10081', '7.5', '']) bad({ scheduleMinutes: minutes }, 'scheduleMinutes');

  for (const host of ['10.0.0.1', '172.16.0.1', '172.31.255.254', '192.168.1.10', 'dosya01.kurum.local', 'fd12:3456::1', '::ffff:10.1.2.3']) {
    assert.equal(hostProblem(host), null, host);
  }
  for (const host of ['172.32.0.1', '11.0.0.1', '::ffff:8.8.8.8', '2001:db8::1']) assert.ok(hostProblem(host), host);
  assert.equal(globProblem('*.pdf'), null);
  assert.equal(globProblem('raporlar/**/*.{pdf,docx}'), null);
  for (const glob of ['{a,b', 'a}', '{a,{b}}', '..', '*'.repeat(7), 'x'.repeat(201), '/']) assert.ok(globProblem(glob), glob);
  assert.deepEqual(normalizePrefix('/IK/Politikalar/'), { value: 'IK/Politikalar', problem: null });
  assert.deepEqual(normalizePrefix('  '), { value: '', problem: null });
  assert.deepEqual(parseGlobs('a\r\n\n b \n'), ['a', 'b']);

  // Editing keeps the target collection: the server refuses a change (409 14006), so the body carries the original.
  const original = { id: 'c1', ...body };
  const edited = connectorRequest({ ...connectorForm(original), collectionId: '0192a3b4-c5d6-7e8f-9a0b-000000000000', name: 'Yeni ad' }, original);
  assert.equal(edited.body.collectionId, id);
  assert.equal(edited.body.name, 'Yeni ad');
  assert.equal(connectorForm(original).maxFileMb, '10');
  assert.equal(connectorForm(original).includeGlobs, '*.pdf\n{a,b}.docx');
});

test('connectors: the panel rules are the server\'s (ConnectorRequest, ConnectorProperties): a contract read from the Java sources', async () => {
  const { CREDENTIAL_REF, DELETION_SAFETY, MAX_GLOBS, MAX_GLOB_LENGTH, SCHEDULE_MAX, SCHEDULE_MIN } = await import('../panel/js/connectors.js');
  const root = 'services/connector/connector-api/src/main/java/com/verso/connector/api/dto/ConnectorRequest.java';
  const request = readFileSync(root, 'utf8');
  assert.equal(`^${request.match(/regexp = "(SECRET_CONNECTOR_[^"]+)"/)[1]}$`, CREDENTIAL_REF.source, 'credentialRef pattern');
  assert.match(request, new RegExp(`@NotNull @Min\\(${SCHEDULE_MIN}\\) @Max\\(${SCHEDULE_MAX}\\) Integer scheduleMinutes`));
  assert.match(request, new RegExp(`@Size\\(max = ${MAX_GLOBS}\\) List<@NotBlank @Size\\(max = ${MAX_GLOB_LENGTH}\\) String> includeGlobs`));
  const properties = readFileSync('services/connector/connector-core/src/main/java/com/verso/connector/config/ConnectorProperties.java', 'utf8');
  assert.match(properties, new RegExp(`@DefaultValue\\("${DELETION_SAFETY.percent}"\\) int deletionSafetyPercent`));
  assert.match(properties, new RegExp(`@DefaultValue\\("${DELETION_SAFETY.minimum}"\\) int deletionSafetyMinimum`));
  const health = readFileSync('services/connector/connector-api/src/main/java/com/verso/connector/api/enums/ConnectorHealth.java', 'utf8');
  const { HEALTH } = await import('../panel/js/connectors.js');
  for (const value of Object.keys(HEALTH)) assert.match(health, new RegExp(`\\b${value}\\b`), `${value} is a server health value`);
});

test('connectors: the deletion-safety approval shows count and share and needs the count typed', async () => {
  const { approvalConfirmed, deleteConfirmed, deletionNeedsApproval, pendingApproval } = await import('../panel/js/connectors.js');
  // The server's rule (SyncPlanner.deletionAllowed): more than 20% and more than 20 files.
  assert.equal(deletionNeedsApproval(20, 50), false, '20 files never need approval');
  assert.equal(deletionNeedsApproval(21, 100), true);
  assert.equal(deletionNeedsApproval(21, 105), false, 'exactly 20% is allowed');
  assert.equal(deletionNeedsApproval(30, 1000), false);
  const connector = { id: 'c1', health: 'OK', items: { synced: 90, failed: 6, skipped: 4 } };
  const held = { id: 'r1', status: 'PARTIAL', errorCode: 'DELETION_SAFETY', deferred: 40 };
  assert.deepEqual(pendingApproval(connector, held), { count: 40, exact: false, tracked: 100, percent: 40, runId: 'r1' });
  assert.equal(pendingApproval(connector, { ...held, errorCode: 'FILES_FAILED' }), null);
  assert.equal(pendingApproval(connector, { ...held, status: 'SUCCEEDED' }), null);
  assert.equal(pendingApproval(connector, null), null);
  assert.equal(pendingApproval({ ...connector, health: 'DELETING' }, held), null);
  assert.equal(pendingApproval(connector, { ...held, deferred: 0 }), null);
  assert.equal(pendingApproval({ ...connector, items: {} }, held).percent, 100, 'never above 100%');
  const pending = pendingApproval(connector, held);
  assert.equal(approvalConfirmed(pending, '40'), true);
  assert.equal(approvalConfirmed(pending, ' 40 '), true);
  for (const typed of ['', '4', '400', 'evet', null]) assert.equal(approvalConfirmed(pending, typed), false, String(typed));
  assert.equal(approvalConfirmed(null, '0'), false);
  assert.equal(deleteConfirmed({ name: 'İK politikaları' }, 'İK politikaları'), true);
  for (const typed of ['ik politikaları', 'İK politikaları ', '']) assert.equal(deleteConfirmed({ name: 'İK politikaları' }, typed), false, typed);
  assert.equal(deleteConfirmed({ name: '' }, ''), false);
  const screen = readFileSync('panel/js/screens/connectors.js', 'utf8');
  assert.match(screen, /api\.syncConnector\(connector\.id, true\)/, 'the approval is the documented confirmMassDeletion');
  assert.match(screen, /if \(!approvalConfirmed\(pending, typed\.value\)\) return;/);
  assert.match(screen, /if \(!deleteConfirmed\(connector, typed\.value\)\) return;/);
  assert.match(screen, /api\.syncConnector\(connector\.id, false\)/, '"Şimdi eşitle" never approves deletions');
  assert.equal([...screen.matchAll(/api\.syncConnector\(connector\.id, true\)/g)].length, 1, 'only the approval form sends true');
});

test('connectors: pendingDeletions (ADR-0044) gives the exact count; old runs fall back to "deferred" as an upper bound', async () => {
  const { approvalConfirmed, pendingApproval } = await import('../panel/js/connectors.js');
  const connector = { id: 'c1', health: 'OK', items: { synced: 90, failed: 6, skipped: 4 } };
  const exact = { id: 'r2', status: 'PARTIAL', errorCode: 'FILES_FAILED', deferred: 45, pendingDeletions: 30 };
  assert.deepEqual(pendingApproval(connector, exact), { count: 30, exact: true, tracked: 100, percent: 30, runId: 'r2' },
    'held deletions show even when the first problem was another one (ADR-0042 known limit)');
  assert.equal(approvalConfirmed(pendingApproval(connector, exact), '30'), true);
  assert.equal(approvalConfirmed(pendingApproval(connector, exact), '45'), false, 'the exact number, not deferred');
  assert.equal(pendingApproval(connector, { ...exact, pendingDeletions: 0 }), null, 'nothing held');
  assert.equal(pendingApproval(connector, { ...exact, status: 'SUCCEEDED' }), null);
  assert.equal(pendingApproval({ ...connector, health: 'DELETING' }, exact), null);
  const legacy = { id: 'r1', status: 'PARTIAL', errorCode: 'DELETION_SAFETY', deferred: 40, pendingDeletions: null };
  assert.equal(pendingApproval(connector, legacy).exact, false);
  assert.equal(pendingApproval(connector, legacy).count, 40);
  const screen = readFileSync('panel/js/screens/connectors.js', 'utf8');
  assert.match(screen, /pending\.exact \? '' : 'en çok '/, 'the screen says "en çok" only for the upper bound');
  const run = readFileSync('services/connector/connector-api/src/main/java/com/verso/connector/api/dto/SyncRunResponse.java', 'utf8');
  assert.match(run, /Integer pendingDeletions\)/, 'the field the panel reads exists in the API');
});

test('connectors: the SharePoint form (ADR-0045) mirrors SharePointRequest, recommends Sites.Selected and never asks for a key', async () => {
  const { AUTH_METHODS, connectorForm, connectorRequest, DEFAULT_CREDENTIAL, errorField, MAX_DRIVES, parseDrives, siteUrlProblem, TYPES,
    codeText, ITEM_FAILURES } = await import('../panel/js/connectors.js');
  const id = '0192a3b4-c5d6-7e8f-9a0b-1c2d3e4f5a6b';
  const app = '9e8d7c6b-5a49-4837-a625-14f3e2d1c0b9';
  const form = { ...connectorForm(null, 'SHAREPOINT'), name: 'IK sitesi', siteUrl: 'https://kurum.sharepoint.com/sites/ik/', drives: 'Belgeler\nPolitikalar\nBelgeler',
    pathPrefix: '/2026/', includeGlobs: '*.pdf', clientId: app, collectionId: id, scheduleMinutes: '30' };
  assert.equal(form.type, 'SHAREPOINT');
  assert.equal(form.credentialRef, DEFAULT_CREDENTIAL.SHAREPOINT);
  assert.equal(form.authMethod, 'CERTIFICATE', 'the certificate is the default (recommended)');
  assert.equal(form.skipUnique, true, 'skipping files with unique permissions is the default (v1 safety rule)');
  const { problems, body } = connectorRequest(form);
  assert.deepEqual(problems, {});
  assert.deepEqual(body, { type: 'SHAREPOINT', name: 'IK sitesi', pathPrefix: '2026', includeGlobs: ['*.pdf'], excludeGlobs: [],
    credentialRef: 'SECRET_CONNECTOR_SHAREPOINT_CERT', collectionId: id, maxFileBytes: null, scheduleMinutes: 30, enabled: true,
    sharePoint: { siteUrl: 'https://kurum.sharepoint.com/sites/ik', siteId: null, drives: ['Belgeler', 'Politikalar'], tenantId: null,
      clientId: app, authMethod: 'CERTIFICATE', skipUniquePermissions: true } });
  assert.ok(![...Object.keys(body), ...Object.keys(body.sharePoint)].some((k) => /password|token|secret|key/i.test(k)), 'no secret field, ever');
  assert.ok(!('host' in body) && !('share' in body) && !('username' in body) && !('confluence' in body), 'no other type\'s field');
  assert.equal(connectorRequest({ ...form, skipUnique: false }).body.sharePoint.skipUniquePermissions, false);
  assert.equal(connectorRequest({ ...form, authMethod: 'CLIENT_SECRET' }).body.sharePoint.authMethod, 'CLIENT_SECRET');
  const byId = connectorRequest({ ...form, siteUrl: '', siteId: 'kurum.sharepoint.com,0f0e0d0c-0b0a-0908-0706-050403020100,00010203-0405-0607-0809-0a0b0c0d0e0f' });
  assert.equal(byId.body.sharePoint.siteUrl, null);

  const bad = (patch, field) => {
    const result = connectorRequest({ ...form, ...patch });
    assert.equal(result.body, null, JSON.stringify(patch));
    assert.ok(result.problems[field], `${JSON.stringify(patch)} → ${field}`);
  };
  for (const url of ['http://kurum.sharepoint.com/sites/ik', 'https://u:p@kurum.sharepoint.com', 'https://kurum.sharepoint.com:8443/sites/ik',
    'https://kurum.sharepoint.com/sites/ik?x=1', 'https://kurum.example.com/sites/ik', 'https://kurum.sharepoint.com/sites/ik%20x',
    'kurum.sharepoint.com']) bad({ siteUrl: url }, 'siteUrl');
  bad({ siteUrl: '', siteId: '' }, 'siteUrl');
  bad({ siteId: 'kurum.sharepoint.com' }, 'siteId');
  bad({ drives: Array.from({ length: MAX_DRIVES + 1 }, (_, i) => `K${i}`).join('\n') }, 'drives');
  bad({ clientId: '' }, 'clientId');
  bad({ clientId: 'not-a-guid' }, 'clientId');
  bad({ tenantId: 'common' }, 'tenantId');
  bad({ authMethod: 'PASSWORD' }, 'authMethod');
  bad({ pathPrefix: '../x' }, 'pathPrefix');
  bad({ credentialRef: 'SECRET_DB_APP_PASSWORD' }, 'credentialRef');
  assert.equal(siteUrlProblem('https://kurum-my.sharepoint.com/personal/ayse_kurum_com'), null, 'a OneDrive');
  assert.deepEqual(parseDrives(' a \r\nb\n\na'), ['a', 'b']);
  for (const code of [14004, 14013, 14014]) assert.equal(errorField(code, 'SHAREPOINT'), 'siteUrl');
  assert.equal(errorField(14003, 'SHAREPOINT'), 'pathPrefix');
  assert.ok(TYPES.SHAREPOINT && AUTH_METHODS.CERTIFICATE && AUTH_METHODS.CLIENT_SECRET);
  for (const code of ['DRIVE_NOT_FOUND', 'CREDENTIAL_INVALID', 'CREDENTIAL_EXPIRED', 'PARENT_UNKNOWN']) assert.notEqual(codeText(code), code, code);
  assert.notEqual(codeText('RESTRICTION_CHECK_FAILED', ITEM_FAILURES), 'RESTRICTION_CHECK_FAILED');

  // Editing: type and collection stay; the stored SharePoint settings come back into the form.
  const original = { id: 'c3', type: 'SHAREPOINT', ...body, sharePoint: { ...body.sharePoint, tenantId: '0a1b2c3d-4e5f-4061-8273-94a5b6c7d8e9',
    lastFullListingAt: null } };
  const values = connectorForm(original);
  assert.equal(values.type, 'SHAREPOINT');
  assert.equal(values.drives, 'Belgeler\nPolitikalar');
  assert.equal(values.tenantId, '0a1b2c3d-4e5f-4061-8273-94a5b6c7d8e9');
  const edited = connectorRequest({ ...values, type: 'SMB', collectionId: '0192a3b4-c5d6-7e8f-9a0b-000000000000' }, original);
  assert.equal(edited.body.type, 'SHAREPOINT', 'the type of an existing connector cannot change (409 14015)');
  assert.equal(edited.body.collectionId, id);

  // The rules are the server's (SharePointRequest, SharePointAuthMethod), and the screen explains Sites.Selected.
  const request = readFileSync('services/connector/connector-api/src/main/java/com/verso/connector/api/dto/SharePointRequest.java', 'utf8');
  assert.match(request, new RegExp(`@Size\\(max = ${MAX_DRIVES}\\) List`));
  const methods = readFileSync('services/connector/connector-api/src/main/java/com/verso/connector/api/enums/SharePointAuthMethod.java', 'utf8');
  for (const value of Object.keys(AUTH_METHODS)) assert.match(methods, new RegExp(`\\b${value}\\b`));
  const screen = readFileSync('panel/js/screens/connectors.js', 'utf8');
  assert.match(screen, /Sites\.Selected/);
  assert.match(screen, /type === 'SHAREPOINT' \? skipUnique\.node/);
  assert.match(screen, /aşırı paylaşım/);
  for (const field of ['siteUrl', 'siteId', 'drives', 'clientId', 'tenantId', 'authMethod']) {
    assert.match(screen, new RegExp(`\\b${field}: field\\(`), `${field} is a form field`);
  }
});

test('connectors: the Confluence form (ADR-0044) has its own fields, mirrors ConfluenceRequest and never asks for a token', async () => {
  const { baseUrlProblem, connectorForm, connectorRequest, DEFAULT_CREDENTIAL, DEPLOYMENTS, errorField, MAX_SPACES, PAGE_SIZE_MAX,
    parseSpaceKeys, TYPES } = await import('../panel/js/connectors.js');
  const id = '0192a3b4-c5d6-7e8f-9a0b-1c2d3e4f5a6b';
  const form = { ...connectorForm(null, 'CONFLUENCE'), name: 'Wiki', baseUrl: 'https://wiki.kurum.local/confluence/', spaceKeys: 'IK, POLITIKA\nIK',
    label: 'yayinda', ancestorId: '123456', pageSize: '25', collectionId: id, scheduleMinutes: '30', includeAttachments: true };
  assert.equal(form.type, 'CONFLUENCE');
  assert.equal(form.credentialRef, DEFAULT_CREDENTIAL.CONFLUENCE);
  assert.equal(form.skipRestricted, true, 'skipping restricted pages is the default (v1 safety rule)');
  assert.equal(form.deployment, 'DATA_CENTER');
  const { problems, body } = connectorRequest(form);
  assert.deepEqual(problems, {});
  assert.deepEqual(body, { type: 'CONFLUENCE', name: 'Wiki', username: null, credentialRef: 'SECRET_CONNECTOR_CONFLUENCE_TOKEN', collectionId: id,
    maxFileBytes: null, scheduleMinutes: 30, enabled: true,
    confluence: { baseUrl: 'https://wiki.kurum.local/confluence', deployment: 'DATA_CENTER', spaceKeys: ['IK', 'POLITIKA'], label: 'yayinda',
      ancestorId: 123456, includeAttachments: true, skipRestricted: true, pageSize: 25 } });
  assert.ok(!JSON.stringify(Object.keys(body)).match(/password|token|secret/i) && !Object.keys(body.confluence).some((k) => /password|token|secret/i.test(k)),
    'no token field, ever');
  assert.ok(!('host' in body) && !('share' in body), 'no SMB field in a Confluence body');
  const cloud = connectorRequest({ ...form, deployment: 'CLOUD', baseUrl: 'https://kurum.atlassian.net/wiki', username: 'okuyucu@kurum.com' });
  assert.equal(cloud.body.username, 'okuyucu@kurum.com');
  assert.equal(cloud.body.confluence.deployment, 'CLOUD');
  assert.equal(connectorRequest({ ...form, skipRestricted: false }).body.confluence.skipRestricted, false);

  const bad = (patch, field) => {
    const result = connectorRequest({ ...form, ...patch });
    assert.equal(result.body, null, JSON.stringify(patch));
    assert.ok(result.problems[field], `${JSON.stringify(patch)} → ${field}`);
  };
  for (const url of ['', 'http://wiki.kurum.local', 'https://u:p@wiki.kurum.local', 'https://wiki.kurum.local/?x=1', 'https://wiki.kurum.local/#a',
    'wiki.kurum.local', 'https://localhost', 'ftp://wiki']) bad({ baseUrl: url }, 'baseUrl');
  bad({ deployment: 'X' }, 'deployment');
  bad({ deployment: 'CLOUD', username: '' }, 'username');
  bad({ deployment: 'CLOUD', username: 'okuyucu' }, 'username');
  bad({ username: 'svc@kurum.com' }, 'username');
  bad({ spaceKeys: '' }, 'spaceKeys');
  bad({ spaceKeys: 'IK") OR space = ("X' }, 'spaceKeys');
  bad({ spaceKeys: Array.from({ length: MAX_SPACES + 1 }, (_, i) => `S${i}`).join(',') }, 'spaceKeys');
  bad({ label: 'iki sözcük' }, 'label');
  for (const ancestor of ['0', '-1', 'abc', '1.5']) bad({ ancestorId: ancestor }, 'ancestorId');
  for (const size of ['0', String(PAGE_SIZE_MAX + 1), 'x']) bad({ pageSize: size }, 'pageSize');
  bad({ credentialRef: 'SECRET_DB_APP_PASSWORD' }, 'credentialRef');
  assert.deepEqual(parseSpaceKeys(' a, b\r\nb\n\n'), ['a', 'b']);
  assert.equal(baseUrlProblem('https://kurum.atlassian.net/wiki'), null);
  assert.equal(errorField(14004, 'CONFLUENCE'), 'baseUrl', 'a Confluence connector has no host field');
  assert.equal(errorField(14004), 'host');
  assert.equal(errorField(14013, 'CONFLUENCE'), 'baseUrl');

  // Editing: type and collection stay; the form shows the stored Confluence settings back.
  const original = { id: 'c2', type: 'CONFLUENCE', ...body, confluence: { ...body.confluence, lastFullListingAt: null } };
  const values = connectorForm(original);
  assert.equal(values.type, 'CONFLUENCE');
  assert.equal(values.spaceKeys, 'IK\nPOLITIKA');
  assert.equal(values.ancestorId, '123456');
  assert.equal(values.pageSize, '25');
  const edited = connectorRequest({ ...values, type: 'SMB', collectionId: '0192a3b4-c5d6-7e8f-9a0b-000000000000' }, original);
  assert.equal(edited.body.type, 'CONFLUENCE', 'the type of an existing connector cannot change (409 14015)');
  assert.equal(edited.body.collectionId, id);

  // The rules are the server's (ConfluenceRequest, ConnectorType, ConfluenceDeployment).
  const request = readFileSync('services/connector/connector-api/src/main/java/com/verso/connector/api/dto/ConfluenceRequest.java', 'utf8');
  assert.match(request, new RegExp(`@NotEmpty @Size\\(max = ${MAX_SPACES}\\)`));
  assert.match(request, new RegExp(`@Min\\(1\\) @Max\\(${PAGE_SIZE_MAX}\\) Integer pageSize`));
  assert.match(request, /regexp = "\[A-Za-z0-9_~\.-\]\{1,100\}"/);
  const types = readFileSync('services/connector/connector-api/src/main/java/com/verso/connector/api/enums/ConnectorType.java', 'utf8');
  for (const value of Object.keys(TYPES)) assert.match(types, new RegExp(`\\b${value}\\b`));
  const deployments = readFileSync('services/connector/connector-api/src/main/java/com/verso/connector/api/enums/ConfluenceDeployment.java', 'utf8');
  for (const value of Object.keys(DEPLOYMENTS)) assert.match(deployments, new RegExp(`\\b${value}\\b`));

  const screen = readFileSync('panel/js/screens/connectors.js', 'utf8');
  assert.match(screen, /id: 'connector-type', disabled: Boolean\(original\)/, 'the type selector is locked when editing');
  assert.match(screen, /Belirteç bu ekrana yazılmaz/, 'the form says where the token lives');
  assert.match(screen, /Confluence alan ve sayfa izinleri aktarılmaz/, 'the risk is named next to the collection picker');
  assert.match(screen, /VERSO_CONNECTOR_EXTERNAL_EGRESS/, 'the help names the Cloud egress setting');
  assert.doesNotMatch(screen, /type: 'password'/);
  for (const name of ['deploy/compose.confluence.yaml', 'docs/adr/0044-confluence-baglayicisi.md']) {
    assert.ok(readFileSync('panel/js/connectors.js', 'utf8').includes(name), `the help names ${name}`);
    assert.ok(existsSync(name), `${name} exists`);
  }
});

test('connectors: run codes, file codes, summaries and paging texts', async () => {
  const { codeText, ITEM_FAILURES, pageState, runSummary, RUN_CODES, scheduleText } = await import('../panel/js/connectors.js');
  const doc = readFileSync('docs/api-connectors-integration-v1.md', 'utf8');
  const runSection = doc.slice(doc.indexOf('### `SyncRunResponse`'), doc.indexOf('### `ConnectorItemResponse`'));
  for (const m of runSection.matchAll(/`([A-Z][A-Z_]{3,})`/g)) {
    if (['RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'ABANDONED'].includes(m[1])) continue;
    assert.ok(RUN_CODES[m[1]], `no text for run code ${m[1]}`);
  }
  const itemSection = doc.slice(doc.indexOf('### `ConnectorItemResponse`'), doc.indexOf('## Hata kodları'));
  for (const m of itemSection.match(/`failureCode` \(([^)]*)\)/)[1].matchAll(/`([A-Z_]+)`/g)) assert.ok(ITEM_FAILURES[m[1]], m[1]);
  assert.equal(codeText('NEW_CODE'), 'NEW_CODE', 'unknown codes are shown raw');
  assert.equal(codeText(null), '');
  assert.equal(runSummary(null), 'Henüz koşu yok');
  assert.equal(runSummary({ status: 'SUCCEEDED', added: 42, updated: 1, deleted: 0, failed: 0, deferred: 0 }), 'Başarılı · 42 yeni, 1 değişen, 0 silinen');
  assert.equal(runSummary({ status: 'PARTIAL', added: 0, updated: 0, deleted: 0, failed: 2, deferred: 40 }),
    'Kısmen · 0 yeni, 0 değişen, 0 silinen, 2 başarısız, 40 bekleyen');
  assert.deepEqual(pageState({ number: 0, totalPages: 3 }), { number: 0, total: 3, prev: false, next: true, label: 'Sayfa 1 / 3' });
  assert.deepEqual(pageState({ number: 2, totalPages: 3 }), { number: 2, total: 3, prev: true, next: false, label: 'Sayfa 3 / 3' });
  assert.equal(pageState(undefined).label, 'Kayıt yok');
  assert.equal(scheduleText(60), '1 saatte bir');
  assert.equal(scheduleText(1440), 'günde bir');
  assert.equal(scheduleText(10080), '7 günde bir');
  assert.equal(scheduleText(45), '45 dakikada bir');
});

test('api: connector calls go to the documented paths; ids are encoded, the body is JSON, the state filter is fixed', async () => {
  const { calls } = browser({ search: '?code=c&state=s', pending: { verifier: 'v', state: 's' } });
  tokenEndpoint(calls);
  await completeSignIn();
  const sent = [];
  globalThis.fetch = async (url, init = {}) => {
    sent.push([init.method ?? 'GET', url, init.body ?? null]);
    return { ok: true, status: 202, headers: { get: () => null }, json: async () => ({}) };
  };
  const body = { name: 'A', credentialRef: 'SECRET_CONNECTOR_SMB_PASSWORD' };
  await api.listConnectors(1, 50);
  await api.getConnector('a/b');
  await api.createConnector(body);
  await api.updateConnector('a/b', body);
  await api.deleteConnector('a/b');
  await api.syncConnector('a/b');
  await api.syncConnector('a/b', true);
  await api.listConnectorRuns('a/b', 2, 20);
  await api.listConnectorItems('a/b', 'FAILED', 0, 50);
  await api.listConnectorItems('a/b', '', 1, 50);
  await api.listConnectorItems('a/b', 'x&state=SYNCED', 0, 50);
  const json = JSON.stringify(body);
  assert.deepEqual(sent, [
    ['GET', '/v1/admin/connectors?page=1&size=50', null],
    ['GET', '/v1/admin/connectors/a%2Fb', null],
    ['POST', '/v1/admin/connectors', json],
    ['PUT', '/v1/admin/connectors/a%2Fb', json],
    ['DELETE', '/v1/admin/connectors/a%2Fb', null],
    ['POST', '/v1/admin/connectors/a%2Fb/sync', '{"confirmMassDeletion":false}'],
    ['POST', '/v1/admin/connectors/a%2Fb/sync', '{"confirmMassDeletion":true}'],
    ['GET', '/v1/admin/connectors/a%2Fb/runs?page=2&size=20', null],
    ['GET', '/v1/admin/connectors/a%2Fb/items?state=FAILED&page=0&size=50', null],
    ['GET', '/v1/admin/connectors/a%2Fb/items?page=1&size=50', null],
    ['GET', '/v1/admin/connectors/a%2Fb/items?page=0&size=50', null]]);
  globalThis.fetch = async () => ({ ok: false, status: 409, headers: { get: () => null }, json: async () => ({ error: { code: 14002 } }) });
  await assert.rejects(api.createConnector(body), (error) => error instanceof ApiError && error.code === 14002 && error.message === MESSAGES[14002]);
  signOut();
});

test('connectors screen: no markup, inline style, storage, URL state or password field; 403, touch targets, no motion', () => {
  for (const file of ['connectors.js', 'screens/connectors.js']) {
    const source = readFileSync(`panel/js/${file}`, 'utf8');
    assert.doesNotMatch(source, /innerHTML|outerHTML|insertAdjacentHTML|\.style\b|setAttribute\('style'|style:\s*'|window\.(alert|confirm|prompt)/, file);
    assert.doesNotMatch(source, /sessionStorage|localStorage|indexedDB|document\.cookie/, `${file}: no browser storage`);
    assert.doesNotMatch(source, /location\.(hash|search|href)\s*=(?!=)|history\.(push|replace)State|URLSearchParams/, `${file}: nothing in the URL`);
    assert.doesNotMatch(source, /console\./, file);
    assert.doesNotMatch(source, /type: 'password'|-password'/, `${file}: no password input`);
    assert.doesNotMatch(source, /animate\(|requestAnimationFrame|transition:/, `${file}: no motion of its own`);
  }
  const screen = readFileSync('panel/js/screens/connectors.js', 'utf8');
  assert.match(screen, /error\.status === 403/);
  assert.match(screen, /'Bu ekran yöneticiler içindir'/);
  assert.doesNotMatch(screen, /Verso['’]|productName/, 'the product name is not hard-coded');
  assert.match(screen, /Parola bu ekrana yazılmaz/, 'the form says where the password lives');
  assert.match(screen, /NTFS izinleri aktarılmaz/, 'the risk is named next to the collection picker (integration document)');
  const logic = readFileSync('panel/js/connectors.js', 'utf8');
  for (const name of ['README.md', 'deploy/compose.smb.yaml', 'docs/api-connectors-integration-v1.md', 'docs/adr/0038-smb-baglayicisi.md']) {
    assert.ok(logic.includes(name), `the help names ${name}`);
  }
  assert.match(screen, /VERSO_CONNECTOR_ALLOWED_NETWORKS/);
  assert.match(screen, /DOCKER-USER/);
  assert.match(screen, /return \(\) => \{\n\s+state = \{/, 'leaving the screen drops what it loaded');
  const css = readFileSync('panel/css/panel.css', 'utf8');
  assert.match(css, /\.main\[data-group="admin"\] :is\(\.textarea, \.connector-check\) \{ min-height: 44px; \}/);
  const block = css.slice(css.indexOf('/* ---------- the "Yönetim" group'), css.indexOf('/* screens cross-fade'));
  assert.ok(block.includes('.connector-form'), 'the connector rules sit in the admin block');
  assert.doesNotMatch(block, /#[0-9a-f]{3,8}\b|rgb\(/i, 'only design tokens: the tested contrast pairs');
  assert.doesNotMatch(block, /animation|transition/, 'no new motion');
});
