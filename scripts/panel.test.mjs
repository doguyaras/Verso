// The panel's pure logic (ADR-0015): role matrix, PKCE, token reading, answer and citation splitting, error texts.
// The sign-in flow runs here with the browser globals stubbed; the screens are checked against the running stack
// (docs/evidence/faz-10-dogrulama.md).
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { ICON_NAMES } from '../panel/js/icons.js';
import { ROLES, SCREENS, canOpen, rolesOf, screensFor } from '../panel/js/roles.js';
import { accessToken, base64Url, challengeOf, claimsOf, completeSignIn, currentClaims, currentIdentity, randomString,
  signIn, signOut } from '../panel/js/auth.js';
import { answerParts, answerSegments, answerWithSources, FAILURES, fill, foldTr, formatLabel, formatSeconds, fuzzyScore, greeting, groupSources, plainName,
  sourceLabel, UPLOAD_TYPES } from '../panel/js/render.js';
import { MESSAGES, messageOf, PAGE_SIZE } from '../panel/js/api.js';
import { SIGN_MS, SIGNATURE, timeline } from '../panel/js/signature-data.js';
import { CONNECTORS, PHASES, setupSteps } from '../panel/js/screens/setup.js';
import { isDark } from '../panel/js/store.js';
import { ACCENT_TOKENS, applyAccent, contrast, LIMITS, loadBranding, LOGO_PATTERN, MAX_BYTES, MAX_SUGGESTIONS, THEME_COLOURS,
  validateBranding } from '../panel/js/branding.js';
import { CONFIG, DEPLOYMENT_DEFAULTS, useDeployment } from '../panel/js/config.js';
import { ABBREVIATIONS, citingSpans, citingText, excerptParts, fold, key, matchRanges, sentences, STEM, STOP_WORDS, terms } from '../panel/js/excerpt.js';
import { answerMarkdown, chipLabel, CHIP_NAME_MAX, clip, errorState, escapeMarkdown, fitQuestion, FOLLOW_UPS, followUps, markdownFileName, MAX_QUESTION, SELECTION_MAX,
  selectionQuestion } from '../panel/js/answer.js';
import { ApiError } from '../panel/js/api.js';
// ask.js touches no DOM at module load (only inside its functions), so node can import it for its constants.
import { FLASH_MS } from '../panel/js/screens/ask.js';
import { CITED as CITED_HIGHLIGHT, MATCH, prune, tintSentences, writeMarked } from '../panel/js/highlight.js';

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
  for (const file of ['docs/api-documents-integration-v1.md', 'docs/api-questions-integration-v1.md']) {
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
    ['fg-subtle', 'bg-canvas', 4.5], ['fg-accent', 'bg-surface', 4.5], ['fg-accent-strong', 'accent-bg-subtle', 4.5]];
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
  ].join('\n'));
  assert.equal(answerMarkdown({ answer: 'Belgelerde yok.', citations: [] }), 'Belgelerde yok.\n');
  const file = answerMarkdown(CITED, { question: 'Stok ne zaman\ndüşer?', productName: 'Verso', mode: 'local', model: 'gemma4:e2b', at: '2026-10-24T09:00:00Z' });
  assert.ok(file.startsWith('# Stok ne zaman düşer?\n\nStok toplama'), 'the question is one heading line');
  assert.ok(file.endsWith('---\nVerso · Yerel · gemma4:e2b · 2026-10-24\n'));
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
  assert.match(ask, /\$\{productName\} hata yapabilir/);
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
