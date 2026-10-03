// The panel's pure logic (ADR-0015): role matrix, PKCE, token reading, answer and citation splitting, error texts.
// The sign-in flow runs here with the browser globals stubbed; the screens are checked against the running stack
// (docs/evidence/faz-10-dogrulama.md).
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { ROLES, SCREENS, canOpen, rolesOf, screensFor } from '../panel/js/roles.js';
import { accessToken, base64Url, challengeOf, claimsOf, completeSignIn, currentClaims, currentIdentity, randomString,
  signIn, signOut } from '../panel/js/auth.js';
import { acceptedFile, answerParts, FAILURES, plainName, sourceLabel } from '../panel/js/render.js';
import { MESSAGES, messageOf, PAGE_SIZE } from '../panel/js/api.js';

test('roles: a user without Verso roles is a verso-user and sees no system screen', () => {
  const roles = rolesOf({ realm_access: { roles: ['offline_access', 'default-roles-verso'] } });
  assert.deepEqual(roles, [ROLES.USER]);
  assert.deepEqual(screensFor(roles), ['documents', 'ask']);
  assert.equal(canOpen('system', roles), false);
});

test('roles: an operator also sees the system screen; unknown screens are closed', () => {
  const roles = rolesOf({ realm_access: { roles: ['verso-operator'] } });
  assert.deepEqual(screensFor(roles), ['documents', 'ask', 'system']);
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
  for (const file of readdirSync('panel/js')) {
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
test('formats: the picker and the check accept exactly what the API accepts', () => {
  for (const name of ['a.pdf', 'B.DOCX', 'notlar.txt', 'README.md', 'x.markdown']) assert.ok(acceptedFile(name), name);
  for (const name of ['a.doc', 'a.exe', 'a.zip', 'a.pdf.exe', 'a']) assert.ok(!acceptedFile(name), name);
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
