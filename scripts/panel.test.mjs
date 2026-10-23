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
import { answerParts, answerWithSources, FAILURES, fill, foldTr, formatLabel, formatSeconds, fuzzyScore, greeting, groupSources, plainName,
  sourceLabel, UPLOAD_TYPES } from '../panel/js/render.js';
import { MESSAGES, messageOf, PAGE_SIZE } from '../panel/js/api.js';
import { SIGN_MS, SIGNATURE, timeline } from '../panel/js/signature-data.js';
import { CONNECTORS, PHASES, setupSteps } from '../panel/js/screens/setup.js';
import { isDark } from '../panel/js/store.js';

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
  assert.equal(url.searchParams.has('prompt'), false);
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
    ['success-text', 'success-bg', 4.5], ['warning-text', 'warning-bg', 4.5], ['danger-text', 'danger-bg', 4.5], ['info-text', 'info-bg', 4.5]];
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
