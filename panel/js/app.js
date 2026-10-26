// Verso panel (ADR-0015, design v3 ADR-0017): overview, questions with citations, documents, the system view for
// operators, the conversation history drawer (ADR-0025) and the value dashboard for admins. No framework and no build
// step; ES modules served by the edge proxy. Screens are hash routes (#/ask, …).
import { completeSignIn, currentClaims, currentIdentity, expiresAt, signIn, signOut } from './auth.js';
import { api, ApiError, lastMode } from './api.js';
import { applyAccent, loadBranding } from './branding.js';
import { CONFIG } from './config.js';
import { icon, logo } from './icons.js';
import { openPalette } from './palette.js';
import { sign, signature } from './signature.js';
import { langSwitch, themeSwitch } from './toggles.js';
import { ADMIN_GROUP, adminScreens, canOpen, ROLES, rolesOf, screensFor, SCREENS, withServerRoles } from './roles.js';
import { el, plainName } from './render.js';
import { isDark, prefs, startNewConversation } from './store.js';
import { cardSpotlight, navIndicator, transition } from './motion.js';
import { initials, openMenu, toast } from './ui.js';
import { renderAsk } from './screens/ask.js';
import { renderDocuments } from './screens/documents.js';
import { openHistory } from './screens/history.js';
import { renderChunks } from './screens/chunks.js';
import { renderCollections } from './screens/collections.js';
import { renderInsights } from './screens/insights.js';
import { renderSubjects } from './screens/subjects.js';
import { renderConnectors } from './screens/connectors.js';
import { renderOverview } from './screens/overview.js';
import { renderSetup } from './screens/setup.js';
import { renderSystem } from './screens/system.js';
import { renderTrust } from './screens/trust.js';

// One automatic sign-in after a 401, then the sign-in screen: a token the API keeps refusing (wrong issuer or
// audience, clock skew) must not bounce the browser between the panel and the IdP forever.
const AUTO_SIGN_IN = 'verso.panel.auto';
const RENDER = { overview: renderOverview, documents: renderDocuments, ask: renderAsk, setup: renderSetup, system: renderSystem,
  insights: renderInsights, collections: renderCollections, chunks: renderChunks, subjects: renderSubjects,
  connectors: renderConnectors, trust: renderTrust };
/** How long the first screen waits for /v1/info's roles; without them the admin screen stays hidden (server: 403). */
const INFO_WAIT_MS = 3000;
const MAC = /Mac|iPhone|iPad/.test(globalThis.navigator?.platform ?? '');

const $ = (selector) => document.querySelector(selector);
let roles = [];
let view = 0; // bumped on every navigation: a late answer for a screen the user left is dropped
let cleanup = null;
let params = null;
const counts = {};
let moveIndicator = () => {};
let routed = false; // the first screen appears without a transition

// ---------- theme: the ivory light theme unless the user picks dark or "follow the system" (ADR-0018) ----------
function applyTheme() {
  const chosen = prefs.theme();
  const dark = isDark(chosen, matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  // The installation's accent, when its branding file sets one for this theme (ADR-0021).
  applyAccent(document.documentElement.style, dark ? 'dark' : 'light', CONFIG.deployment.accent);
  for (const toggle of document.querySelectorAll('.theme-switch')) toggle.setAttribute('aria-checked', String(dark));
}

function setTheme(value) {
  prefs.setTheme(value);
  applyTheme();
}

function themeItems() {
  const chosen = prefs.theme();
  return [
    { group: 'Tema' },
    { label: 'Açık (Fildişi)', glyph: 'sun', checked: chosen !== 'dark' && chosen !== 'system', onSelect: () => setTheme('light') },
    { label: 'Koyu (Obscur)', glyph: 'moon', checked: chosen === 'dark', onSelect: () => setTheme('dark') },
    { label: 'Sistemi izle', glyph: 'monitor', checked: chosen === 'system', onSelect: () => setTheme('system') },
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
  moveIndicator();
  if (routed) transition(() => show(screen));
  else show(screen);
  routed = true;
}

function show(screen) {
  view += 1;
  const shown = view;
  if (typeof cleanup === 'function') cleanup();
  cleanup = null;
  document.title = `${SCREENS[screen].title} · ${CONFIG.deployment.productName}`;
  if (screen === 'system') prefs.markOnboarding({ sawMode: true });
  // The "Yönetim" screens get the 44 px touch targets on phones (panel.css, ADR-0036).
  $('#main').dataset.group = SCREENS[screen].group ?? '';
  const ctx = {
    main: $('#main'), roles, identity: currentIdentity(), expiresAt, guarded, withAuth, navigate, setCount, showMode,
    openHistory: showHistory, params, isCurrent: () => shown === view,
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

/** The history drawer, from the menu, "g g", the palette or a screen. */
function showHistory() {
  openHistory({ guarded, withAuth, navigate });
}

/** The role under the user's name: the highest of admin, operator, user. */
function roleLabel() {
  if (roles.includes(ROLES.ADMIN)) return 'Yönetici';
  return roles.includes(ROLES.OPERATOR) ? 'Operatör' : 'Kullanıcı';
}

function accountMenu(anchor, placement) {
  const identity = currentIdentity();
  openMenu(anchor, [
    { heading: identity.preferred_username ?? 'kullanıcı', sub: roleLabel() },
    ...themeItems(),
    'separator',
    // Phones have no menu column: the history and the screens kept out of the tab bar are reached from here.
    { label: 'Konuşma geçmişi', glyph: 'history', onSelect: showHistory },
    ...(canOpen('system', roles) ? [{ label: 'Sistem ve güvenlik', glyph: 'server', onSelect: () => navigate('system') }] : []),
    ...adminItems(),
    'separator',
    { label: 'Çıkış yap', glyph: 'logout', onSelect: signOut },
  ], { placement });
}

/** The "Yönetim" group of the account menu (ADR-0036); nothing at all without verso-admin. */
function adminItems() {
  const items = [
    ...(canOpen('insights', roles) ? [{ label: 'Değer panosu', glyph: 'chart', onSelect: () => navigate('insights') }] : []),
    ...(canOpen('collections', roles) ? [{ label: 'Bilgi alanları', glyph: 'layers', onSelect: () => navigate('collections') }] : []),
    ...(canOpen('chunks', roles) ? [{ label: 'Parçaları incele', glyph: 'search', onSelect: () => navigate('chunks') }] : []),
    ...(canOpen('subjects', roles) ? [{ label: 'KVKK başvuruları', glyph: 'shield', onSelect: () => navigate('subjects') }] : []),
    ...(canOpen('connectors', roles) ? [{ label: 'Bağlayıcılar', glyph: 'plug', onSelect: () => navigate('connectors') }] : []),
    ...(canOpen('trust', roles) ? [{ label: 'Güven', glyph: 'lock', onSelect: () => navigate('trust') }] : []),
  ];
  return items.length ? [{ group: ADMIN_GROUP }, ...items] : [];
}

function railToggle() {
  const shell = $('#shell');
  const on = !shell.classList.contains('rail');
  shell.classList.toggle('rail', on);
  prefs.setRail(on);
  moveIndicator();
  const button = $('#collapse');
  button.setAttribute('aria-label', on ? 'Menüyü genişlet' : 'Menüyü daralt');
  button.setAttribute('aria-expanded', String(!on));
}

function paletteSources() {
  const pages = screensFor(roles).map((id) => ({ label: SCREENS[id].title, glyph: SCREENS[id].icon, hint: `G ${SCREENS[id].key.toUpperCase()}`,
    keywords: 'sayfa ekran git', run: () => navigate(id) }));
  const actions = [
    { label: 'Yeni soru sor', glyph: 'chat', keywords: 'soru cevap sor', run: () => navigate('ask', { focus: true }) },
    { label: 'Konuşma geçmişi', glyph: 'history', hint: 'G G', keywords: 'geçmiş konuşma sohbet eski', run: showHistory },
    { label: 'Yeni konuşma', glyph: 'plus', keywords: 'konuşma sohbet temiz başlat', run: () => {
      startNewConversation();
      navigate('ask', { focus: true });
    } },
    { label: 'Belge yükle', glyph: 'upload', keywords: 'yükle dosya pdf word', run: () => navigate('documents', { upload: true }) },
    { label: 'Açık temaya geç', glyph: 'sun', keywords: 'tema görünüm', run: () => setTheme('light') },
    { label: 'Koyu temaya geç', glyph: 'moon', keywords: 'tema görünüm karanlık', run: () => setTheme('dark') },
    { label: 'Temada sistemi izle', glyph: 'monitor', keywords: 'tema otomatik', run: () => setTheme('system') },
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
      // "g g": the history drawer; "g" then a screen's key: that screen.
      if (Date.now() - pendingG < 1200) {
        pendingG = 0;
        event.preventDefault();
        showHistory();
      } else pendingG = Date.now();
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
  const { client, logo: clientLogo, productName } = CONFIG.deployment;
  const identity = currentIdentity();
  const name = identity.preferred_username ?? 'kullanıcı';
  if (prefs.rail()) $('#shell').classList.add('rail');

  $('#brand').prepend(logo(30));
  $('#brand').setAttribute('aria-label', `${productName}, genel bakış`);
  $('#brand .brand-text').prepend(signature({ height: 42, ink: 'var(--signature-ink)' }));
  // Under the signature only the client's name and logo, when the installation sets them (ADR-0018, ADR-0021). The
  // logo is a same-origin file (CSP img-src 'self'); one that fails to load is removed rather than shown broken.
  if (client || clientLogo) {
    $('#brand .brand-text').append(el('span', { class: 'brand-client' },
      clientLogo ? el('img', { class: 'client-logo', src: clientLogo, alt: client ? '' : 'Kurum logosu', height: 20, decoding: 'async',
        onerror: (event) => event.currentTarget.remove() }) : null,
      client ? el('small', {}, client) : null));
  }
  document.querySelector('.workspace').prepend(el('div', { class: 'toggles app-toggles' },
    themeSwitch({ dark: document.documentElement.dataset.theme === 'dark', label: 'Koyu tema', onChange: (dark) => setTheme(dark ? 'dark' : 'light') })));
  $('#search').replaceChildren(icon('search', 'sm'), el('span', {}, 'Ara…'), el('kbd', {}, MAC ? '⌘K' : 'Ctrl K'));
  $('#search').addEventListener('click', () => openPalette(paletteSources()));

  const links = screensFor(roles).map((id) => ({ id, ...SCREENS[id] }));
  // "Geçmiş" sits right under "Soru sor": it opens a drawer over the current screen, so it is a button, not a route.
  const history = el('button', { class: 'nav-action', type: 'button', 'aria-haspopup': 'dialog', title: 'Geçmiş (G G)', onclick: showHistory },
    icon('history'), el('span', { class: 'label' }, 'Geçmiş'), el('kbd', { class: 'shortcut', 'aria-hidden': 'true' }, 'G G'));
  // The admin screens sit under their own "Yönetim" heading (ADR-0036); without verso-admin there is none.
  const firstAdmin = adminScreens(roles)[0];
  const adminHeading = el('div', { class: 'nav-label nav-group', id: 'nav-admin' }, ADMIN_GROUP);
  $('#nav').replaceChildren(
    ...links.flatMap((s) => [...(s.id === firstAdmin ? [adminHeading] : []), el('a',{ href: `#/${s.id}`, 'data-screen': s.id, title: `${s.title} (G ${s.key.toUpperCase()})` },
      icon(s.icon), el('span', { class: 'label' }, s.title),
      s.id === 'documents' ? el('span', { class: 'count' }, String(counts.documents ?? '')) : null,
      el('kbd', { class: 'shortcut', 'aria-hidden': 'true' }, `G ${s.key.toUpperCase()}`)), ...(s.id === 'ask' ? [history] : [])]));
  moveIndicator = navIndicator($('#nav'));
  cardSpotlight($('#main'));
  $('#tabbar').replaceChildren(...links.filter((s) => s.tab !== false).map((s) => el('a', { href: `#/${s.id}`, 'data-screen': s.id }, icon(s.icon), s.short)));

  const account = el('button', { class: 'account', type: 'button', 'aria-haspopup': 'menu', 'aria-expanded': 'false',
    onclick: () => accountMenu(account, 'above') },
  el('span', { class: 'avatar' }, initials(name)),
  el('span', { class: 'who' }, el('strong', {}, name), el('span', {}, roleLabel())),
  icon('chevronRight', 'sm'));
  const collapse = el('button', { class: 'icon-button collapse-toggle', id: 'collapse', type: 'button',
    'aria-label': prefs.rail() ? 'Menüyü genişlet' : 'Menüyü daralt', 'aria-expanded': String(!prefs.rail()), onclick: railToggle }, icon('sidebar', 'sm'));
  $('#sidebar-foot').replaceChildren(account, collapse);

  const mobileAccount = el('button', { class: 'icon-button', type: 'button', 'aria-label': 'Hesap ve tema', 'aria-haspopup': 'menu',
    onclick: () => accountMenu(mobileAccount, 'below') }, el('span', { class: 'avatar' }, initials(name)));
  $('#mobilebar').replaceChildren(
    el('a', { class: 'brand', href: '#/overview', 'aria-label': `${productName}, genel bakış` },
      signature({ height: 26, ink: 'var(--signature-ink)' })),
    el('span', { class: 'spacer' }),
    el('button', { class: 'icon-button', type: 'button', 'aria-label': 'Ara', onclick: () => openPalette(paletteSources()) }, icon('search')),
    mobileAccount);
}

// The intro before sign-in (ADR-0018): the signature above an open book with one example question on the left page and
// its answer with the source on the right; the example is the installation's (config.js). Its few words follow the
// TR / EN switch, which also sets the language of the IdP's sign-in page; the rest of the panel is Turkish.
const INTRO = Object.freeze({
  tr: { caption: 'Kurumunuza uyum sağlayan yapay zekâ', question: 'Soru', answer: 'Cevap', button: 'Başlayın',
    assure: ['Veriniz kurum içinde kalır', 'Her yanıt kaynağıyla'], theme: 'Koyu tema', lang: 'Dil' },
  en: { caption: 'The AI that fits your organisation', question: 'Question', answer: 'Answer', button: 'Get started',
    assure: ['Your data stays inside', 'Every answer with its source'], theme: 'Dark theme', lang: 'Language' },
});

function showSignIn(problem, { quiet = false } = {}) {
  view += 1;
  if (typeof cleanup === 'function') cleanup();
  cleanup = null;
  $('#shell').hidden = true;
  const gate = $('#gate');
  gate.hidden = false;
  const lang = prefs.lang();
  const text = INTRO[lang];
  const example = CONFIG.deployment.showcase[lang];
  document.documentElement.lang = lang;
  document.title = CONFIG.deployment.productName;
  const mark = signature({ height: 104, ink: 'var(--signature-ink)', tip: 'var(--accent-fill)', animated: !quiet });
  gate.replaceChildren(el('section', { class: `intro${quiet ? ' quiet' : ''}`, 'aria-labelledby': 'intro-title' },
    el('div', { class: 'toggles' },
      themeSwitch({ dark: document.documentElement.dataset.theme === 'dark', label: text.theme, onChange: (dark) => setTheme(dark ? 'dark' : 'light') }),
      langSwitch({ lang, label: text.lang, onChange: (value) => {
        prefs.setLang(value);
        setTimeout(() => showSignIn(problem, { quiet: true }), 320); // after the marker has slid
      } })),
    el('header', { class: 'intro-head' },
      el('h1', { id: 'intro-title', class: 'sr-only' }, CONFIG.deployment.productName), mark, el('p', { class: 'intro-caption' }, text.caption)),
    el('p', { class: 'example-label' }, example.label),
    el('div', { class: 'book' },
      el('div', { class: 'leaf left' }, el('span', { class: 'eyebrow' }, text.question),
        el('p', { class: 'intro-question' }, `“${example.question}”`)),
      el('div', { class: 'leaf right' }, el('span', { class: 'eyebrow' }, text.answer),
        el('p', { class: 'intro-answer' }, example.answer, ' ', el('span', { class: 'cite-mark', 'aria-label': '1' }, '1')),
        el('p', { class: 'margin-note' }, `¹ ${example.source}`))),
    el('div', { class: 'intro-foot' },
      problem ? el('div', { class: 'notice bad', role: 'alert' }, icon('alert', 'sm'), problem) : null,
      el('button', { class: 'button lg sheen', type: 'button', onclick: (event) => {
        event.currentTarget.setAttribute('aria-busy', 'true');
        sessionStorage.removeItem(AUTO_SIGN_IN);
        signIn({ prompt: 'login' }); // the sign-in form every time, even with an IdP session
      } }, text.button, icon('arrowRight')),
      el('div', { class: 'assure' }, ...text.assure.map((t) => el('span', {}, t))))));
  if (!quiet) sign(mark, { delay: 250 });
}

async function start() {
  // The client's redirect URI and web origin name localhost (verso-realm.json); 127.0.0.1 is the same host.
  if (location.hostname === '127.0.0.1') {
    location.replace(`${location.protocol}//localhost:${location.port}${location.pathname}${location.hash}`);
    return;
  }
  // The branding file is fetched while the sign-in completes; both come before the first screen (ADR-0021).
  const branding = loadBranding();
  applyTheme();
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme);
  let signedIn = false;
  try {
    signedIn = await completeSignIn();
  } catch {
    signedIn = false;
  }
  await branding;
  applyTheme(); // again, now with the installation's accent
  if (!signedIn) {
    showSignIn();
    return;
  }
  // The admin role is the server's word (/v1/info, ADR-0019): a slow or failed answer only keeps the dashboard hidden.
  const info = await Promise.race([withAuth(() => api.info()).catch(() => null),
    new Promise((resolve) => setTimeout(() => resolve(null), INFO_WAIT_MS))]);
  roles = withServerRoles(rolesOf(currentClaims()), info);
  $('#gate').hidden = true;
  $('#shell').hidden = false;
  buildShell();
  shortcuts();
  addEventListener('hashchange', route);
  route();
}

start().catch(() => toast('Panel açılamadı; sayfayı yenileyin.'));
