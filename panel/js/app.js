// Verso panel (ADR-0015, design v3 ADR-0017): overview, questions with citations, documents, and the system view for
// operators. No framework and no build step; ES modules served by the edge proxy. Screens are hash routes (#/ask, …).
import { completeSignIn, currentClaims, currentIdentity, expiresAt, signIn, signOut } from './auth.js';
import { api, ApiError, lastMode } from './api.js';
import { icon, logo } from './icons.js';
import { openPalette } from './palette.js';
import { canOpen, rolesOf, screensFor, SCREENS } from './roles.js';
import { el, plainName } from './render.js';
import { prefs } from './store.js';
import { initials, openMenu, toast } from './ui.js';
import { renderAsk } from './screens/ask.js';
import { renderDocuments } from './screens/documents.js';
import { renderOverview } from './screens/overview.js';
import { renderSystem } from './screens/system.js';

// One automatic sign-in after a 401, then the sign-in screen: a token the API keeps refusing (wrong issuer or
// audience, clock skew) must not bounce the browser between the panel and the IdP forever.
const AUTO_SIGN_IN = 'verso.panel.auto';
const RENDER = { overview: renderOverview, documents: renderDocuments, ask: renderAsk, system: renderSystem };
const MAC = /Mac|iPhone|iPad/.test(globalThis.navigator?.platform ?? '');

const $ = (selector) => document.querySelector(selector);
let roles = [];
let view = 0; // bumped on every navigation: a late answer for a screen the user left is dropped
let cleanup = null;
let params = null;
const counts = {};

// ---------- theme: follow the system unless the user chose (NN/g dark mode) ----------
function applyTheme() {
  const chosen = prefs.theme();
  const dark = chosen ? chosen === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
}

function setTheme(value) {
  prefs.setTheme(value);
  applyTheme();
}

function themeItems() {
  const chosen = prefs.theme();
  return [
    { group: 'Tema' },
    { label: 'Sistemi izle', glyph: 'monitor', checked: !chosen, onSelect: () => setTheme(null) },
    { label: 'Açık', glyph: 'sun', checked: chosen === 'light', onSelect: () => setTheme('light') },
    { label: 'Koyu', glyph: 'moon', checked: chosen === 'dark', onSelect: () => setTheme('dark') },
  ];
}

// ---------- calls that need the session ----------
let redirecting = false; // parallel calls that all get 401 start one sign-in

function handleUnauthorized() {
  if (redirecting) return;
  if (sessionStorage.getItem(AUTO_SIGN_IN)) {
    showSignIn('Oturum açılamadı: servis girişinizi kabul etmedi. Yöneticinize bildirin.');
    return;
  }
  sessionStorage.setItem(AUTO_SIGN_IN, '1');
  redirecting = true;
  signIn();
}

/** Runs an API call; a 401 starts the sign-in, any other error is thrown to the caller. */
async function withAuth(action) {
  try {
    const result = await action();
    sessionStorage.removeItem(AUTO_SIGN_IN);
    return result;
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) handleUnauthorized();
    throw error;
  } finally {
    showMode();
  }
}

/** Runs an API call and shows its error as a toast; resolves to undefined on failure. */
async function guarded(action) {
  try {
    return await withAuth(action);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return undefined;
    if (error?.name === 'AbortError') return undefined;
    toast(error instanceof ApiError ? error.message : 'Bağlantı kurulamadı.');
    return undefined;
  }
}

/** The privacy card under the menu: which mode answered last, in words (llm-rules 1.3). */
function showMode() {
  if (!lastMode) return;
  const local = lastMode === 'local';
  const card = el('div', { class: `privacy-card ${local ? 'local' : 'cloud'}`,
    title: local ? 'Belgeler ve sorular bu sunucudan çıkmaz.' : 'Sorular ve seçilen pasajlar bulut sağlayıcısına gider (KVKK md. 9).' },
  el('strong', {}, icon(local ? 'shield' : 'cloud', 'sm'), local ? 'Yerel mod' : 'Bulut modu'),
  el('span', {}, local ? 'Belgeler, sorular ve cevaplar bu sunucudan çıkmaz.' : 'Soru ve seçilen pasajlar sağlayıcıya gider.'));
  const foot = $('#sidebar-foot');
  foot.querySelector('.privacy-card')?.remove();
  foot.prepend(card);
}

// ---------- shell ----------
function navigate(screen, withParams = null) {
  params = withParams;
  if (location.hash === `#/${screen}`) route();
  else location.hash = `#/${screen}`;
}

function setCount(screen, value) {
  counts[screen] = value;
  const badge = $(`#nav a[data-screen="${screen}"] .count`);
  if (badge) badge.textContent = String(value);
}

function route() {
  const wanted = location.hash.replace(/^#\//, '') || 'overview';
  const screen = canOpen(wanted, roles) ? wanted : screensFor(roles)[0];
  for (const link of document.querySelectorAll('#nav a, #tabbar a')) {
    if (link.dataset.screen === screen) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  }
  view += 1;
  const shown = view;
  if (typeof cleanup === 'function') cleanup();
  cleanup = null;
  document.title = `${SCREENS[screen].title} · Verso`;
  if (screen === 'system') prefs.markOnboarding({ sawMode: true });
  const ctx = {
    main: $('#main'), roles, identity: currentIdentity(), expiresAt, guarded, withAuth, navigate, setCount, showMode,
    params, isCurrent: () => shown === view,
  };
  params = null;
  const result = RENDER[screen](ctx);
  Promise.resolve(result).then((dispose) => {
    if (typeof dispose !== 'function') return;
    if (shown === view) cleanup = dispose;
    else dispose();
  });
  $('#main').focus({ preventScroll: true });
  scrollTo(0, 0);
}

function accountMenu(anchor, placement) {
  const identity = currentIdentity();
  const operator = roles.includes('verso-operator');
  openMenu(anchor, [
    { heading: identity.preferred_username ?? 'kullanıcı', sub: operator ? 'Operatör' : 'Kullanıcı' },
    ...themeItems(),
    'separator',
    ...(canOpen('system', roles) ? [{ label: 'Sistem', glyph: 'server', onSelect: () => navigate('system') }] : []),
    { label: 'Çıkış yap', glyph: 'logout', onSelect: signOut },
  ], { placement });
}

function railToggle() {
  const shell = $('#shell');
  const on = !shell.classList.contains('rail');
  shell.classList.toggle('rail', on);
  prefs.setRail(on);
  const button = $('#collapse');
  button.setAttribute('aria-label', on ? 'Menüyü genişlet' : 'Menüyü daralt');
  button.setAttribute('aria-expanded', String(!on));
}

function paletteSources() {
  const pages = screensFor(roles).map((id) => ({ label: SCREENS[id].title, glyph: SCREENS[id].icon, hint: `G ${SCREENS[id].key.toUpperCase()}`,
    keywords: 'sayfa ekran git', run: () => navigate(id) }));
  const actions = [
    { label: 'Yeni soru sor', glyph: 'chat', keywords: 'soru cevap sor', run: () => navigate('ask', { focus: true }) },
    { label: 'Belge yükle', glyph: 'upload', keywords: 'yükle dosya pdf word', run: () => navigate('documents', { upload: true }) },
    { label: 'Açık temaya geç', glyph: 'sun', keywords: 'tema görünüm', run: () => setTheme('light') },
    { label: 'Koyu temaya geç', glyph: 'moon', keywords: 'tema görünüm karanlık', run: () => setTheme('dark') },
    { label: 'Temada sistemi izle', glyph: 'monitor', keywords: 'tema otomatik', run: () => setTheme(null) },
    { label: 'Çıkış yap', glyph: 'logout', keywords: 'oturum kapat', run: signOut },
  ];
  const documents = () => guarded(() => api.listAllDocuments()).then((docs) => (docs ?? []).map((doc) => ({
    fileName: doc.fileName, format: doc.format, hint: 'Belge',
    run: () => navigate('documents', { open: doc.id, query: plainName(doc.fileName) }),
  })));
  return { pages, actions, documents };
}

function shortcuts() {
  let pendingG = 0;
  addEventListener('keydown', (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase('en') === 'k') {
      event.preventDefault();
      openPalette(paletteSources());
      return;
    }
    const typing = event.target instanceof HTMLElement && event.target.closest('input, textarea, select, [contenteditable], dialog');
    if (typing || event.ctrlKey || event.metaKey || event.altKey) return;
    const key = event.key.toLocaleLowerCase('tr');
    if (key === 'g') {
      pendingG = Date.now();
      return;
    }
    if (Date.now() - pendingG < 1200) {
      pendingG = 0;
      const target = screensFor(roles).find((id) => SCREENS[id].key === key);
      if (target) {
        event.preventDefault();
        navigate(target);
      }
    }
  });
}

function buildShell() {
  const identity = currentIdentity();
  const name = identity.preferred_username ?? 'kullanıcı';
  const operator = roles.includes('verso-operator');
  if (prefs.rail()) $('#shell').classList.add('rail');

  $('#brand').prepend(logo(30));
  $('#search').replaceChildren(icon('search', 'sm'), el('span', {}, 'Ara…'), el('kbd', {}, MAC ? '⌘K' : 'Ctrl K'));
  $('#search').addEventListener('click', () => openPalette(paletteSources()));

  const links = screensFor(roles).map((id) => ({ id, ...SCREENS[id] }));
  $('#nav').replaceChildren(
    ...links.map((s) => el('a', { href: `#/${s.id}`, 'data-screen': s.id, title: `${s.title} (G ${s.key.toUpperCase()})` },
      icon(s.icon), el('span', { class: 'label' }, s.title),
      s.id === 'documents' ? el('span', { class: 'count' }, String(counts.documents ?? '')) : null,
      el('kbd', { class: 'shortcut', 'aria-hidden': 'true' }, `G ${s.key.toUpperCase()}`))));
  $('#tabbar').replaceChildren(...links.map((s) => el('a', { href: `#/${s.id}`, 'data-screen': s.id }, icon(s.icon), s.short)));

  const account = el('button', { class: 'account', type: 'button', 'aria-haspopup': 'menu', 'aria-expanded': 'false',
    onclick: () => accountMenu(account, 'above') },
  el('span', { class: 'avatar' }, initials(name)),
  el('span', { class: 'who' }, el('strong', {}, name), el('span', {}, operator ? 'Operatör' : 'Kullanıcı')),
  icon('chevronRight', 'sm'));
  const collapse = el('button', { class: 'icon-button collapse-toggle', id: 'collapse', type: 'button',
    'aria-label': prefs.rail() ? 'Menüyü genişlet' : 'Menüyü daralt', 'aria-expanded': String(!prefs.rail()), onclick: railToggle }, icon('sidebar', 'sm'));
  $('#sidebar-foot').replaceChildren(account, collapse);

  const mobileAccount = el('button', { class: 'icon-button', type: 'button', 'aria-label': 'Hesap ve tema', 'aria-haspopup': 'menu',
    onclick: () => accountMenu(mobileAccount, 'below') }, el('span', { class: 'avatar' }, initials(name)));
  $('#mobilebar').replaceChildren(
    el('a', { class: 'brand', href: '#/overview', 'aria-label': 'Verso, genel bakış' }, logo(26),
      el('span', { class: 'brand-text' }, el('strong', {}, 'Verso'))),
    el('span', { class: 'spacer' }),
    el('button', { class: 'icon-button', type: 'button', 'aria-label': 'Ara', onclick: () => openPalette(paletteSources()) }, icon('search')),
    mobileAccount);
}

function showSignIn(problem) {
  view += 1;
  if (typeof cleanup === 'function') cleanup();
  cleanup = null;
  $('#shell').hidden = true;
  const gate = $('#gate');
  gate.hidden = false;
  gate.replaceChildren(el('section', { class: 'signin', 'aria-labelledby': 'signin-title' },
    el('div', { class: 'brand-panel' },
      el('div', { class: 'brand-row' }, logo(34), el('span', {}, 'Verso', el('small', {}, 'Kurumunuzun belge asistanı'))),
      el('h2', {}, 'Belgelerinize sorun, cevabı kaynağıyla alın.'),
      el('p', {}, 'Verso yönetmeliklerinizi, sözleşmelerinizi ve prosedürlerinizi kendi sunucunuzda okur; Türkçe sorulara sayfa sayfa kaynak göstererek cevap verir.'),
      el('ul', { class: 'trust' },
        el('li', {}, el('span', { class: 'trust-icon' }, icon('shield', 'sm')),
          el('div', {}, el('strong', {}, 'Veri kurum dışına çıkmaz'), el('span', {}, 'Yerel modda model de belgeler de sizin sunucunuzda.'))),
        el('li', {}, el('span', { class: 'trust-icon' }, icon('quote', 'sm')),
          el('div', {}, el('strong', {}, 'Her cevap kaynaklı'), el('span', {}, 'Belge adı ve sayfası; cevap yoksa "bulunamadı".'))),
        el('li', {}, el('span', { class: 'trust-icon' }, icon('building', 'sm')),
          el('div', {}, el('strong', {}, 'Kurumsal oturum'), el('span', {}, 'Kurumunuzun kimlik sağlayıcısıyla giriş (OIDC).')))),
      el('div', { class: 'brand-foot' }, 'KVKK md. 9 · PDF, Word, TXT, Markdown')),
    el('div', { class: 'signin-form' },
      el('h3', { id: 'signin-title' }, 'Giriş yapın'),
      el('p', {}, 'Kurumunuzun giriş sayfasına yönlendirileceksiniz; giriş sonrası buraya dönersiniz.'),
      problem ? el('div', { class: 'notice bad', role: 'alert' }, icon('alert', 'sm'), problem) : null,
      el('button', { class: 'button lg block', type: 'button', onclick: (event) => {
        event.currentTarget.setAttribute('aria-busy', 'true');
        sessionStorage.removeItem(AUTO_SIGN_IN);
        signIn();
      } }, icon('key'), 'Kurum hesabıyla giriş yap'),
      el('ol', { class: 'signin-steps', 'aria-label': 'Nasıl çalışır' },
        el('li', {}, el('b', {}, '1'), 'Belgelerinizi yükleyin (PDF, Word, TXT, MD).'),
        el('li', {}, el('b', {}, '2'), 'Türkçe sorunuzu yazın.'),
        el('li', {}, el('b', {}, '3'), 'Cevabı, kaynağına tıklayarak doğrulayın.')),
      el('p', { class: 'signin-note' }, icon('lock', 'sm'), 'Oturum bilgisi yalnız bu sekmenin belleğinde tutulur.'))));
}

async function start() {
  // The client's redirect URI and web origin name localhost (verso-realm.json); 127.0.0.1 is the same host.
  if (location.hostname === '127.0.0.1') {
    location.replace(`${location.protocol}//localhost:${location.port}${location.pathname}${location.hash}`);
    return;
  }
  applyTheme();
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme);
  let signedIn = false;
  try {
    signedIn = await completeSignIn();
  } catch {
    signedIn = false;
  }
  if (!signedIn) {
    showSignIn();
    return;
  }
  roles = rolesOf(currentClaims());
  $('#gate').hidden = true;
  $('#shell').hidden = false;
  buildShell();
  shortcuts();
  addEventListener('hashchange', route);
  route();
}

start().catch(() => toast('Panel açılamadı; sayfayı yenileyin.'));
