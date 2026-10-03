// Verso panel (ADR-0015): overview, documents, questions with citations, and the system view for operators. No
// framework and no build step; ES modules served by the edge proxy. Screens are hash routes (#/overview, …).
import { completeSignIn, currentClaims, currentIdentity, expiresAt, signIn, signOut } from './auth.js';
import { ApiError, lastMode } from './api.js';
import { icon } from './icons.js';
import { canOpen, rolesOf, screensFor, SCREENS } from './roles.js';
import { el } from './render.js';
import { initials, toast } from './ui.js';
import { renderAsk } from './screens/ask.js';
import { renderDocuments } from './screens/documents.js';
import { renderOverview } from './screens/overview.js';
import { renderSystem } from './screens/system.js';

// One automatic sign-in after a 401, then the sign-in screen: a token the API keeps refusing (wrong issuer or
// audience, clock skew) must not bounce the browser between the panel and the IdP forever.
const AUTO_SIGN_IN = 'verso.panel.auto';
const THEME = 'verso.panel.theme';
const RENDER = { overview: renderOverview, documents: renderDocuments, ask: renderAsk, system: renderSystem };

const $ = (selector) => document.querySelector(selector);
let roles = [];
let view = 0; // bumped on every navigation: a late answer for a screen the user left is dropped
let cleanup = null;
let params = null;
const counts = {};

// ---------- theme ----------
function storedTheme() {
  try {
    return sessionStorage.getItem(THEME);
  } catch {
    return null;
  }
}

function applyTheme(theme) {
  const dark = theme ? theme === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  $('#theme-toggle').replaceChildren(icon(dark ? 'sun' : 'moon'));
  $('#theme-toggle').setAttribute('aria-label', dark ? 'Açık temaya geç' : 'Koyu temaya geç');
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
    if (!(error instanceof ApiError && error.status === 401)) {
      toast(error instanceof ApiError ? error.message : 'Bağlantı kurulamadı.');
    }
    return undefined;
  }
}

function showMode() {
  if (!lastMode) return;
  const pill = $('#mode');
  const local = lastMode === 'local';
  pill.hidden = false;
  pill.className = `mode-pill ${local ? 'local' : 'cloud'}`;
  pill.title = local ? 'Belgeler ve sorular bu sunucudan çıkmaz.' : 'Sorular ve seçilen pasajlar bulut sağlayıcısına gider (KVKK md. 9).';
  pill.replaceChildren(el('span', { class: 'dot' }), el('span', { class: 'label' }, local ? 'Yerel mod' : 'Bulut modu'));
  const card = el('div', { class: `privacy-card ${local ? 'local' : 'cloud'}` },
    el('strong', {}, icon(local ? 'shield' : 'cloud', 'sm'), local ? 'Veriler sunucuda' : 'Bulut modu açık'),
    el('span', {}, local ? 'Belgeler, sorular ve cevaplar bu sunucudan çıkmaz.' : 'Soru ve seçilen pasajlar sağlayıcıya gider.'));
  $('#sidebar-foot').replaceChildren(card);
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

function closeMenu() {
  $('#sidebar').classList.remove('open');
  $('#scrim').hidden = true;
  $('#menu-toggle').setAttribute('aria-expanded', 'false');
}

function route() {
  const wanted = location.hash.replace(/^#\//, '') || 'overview';
  const screen = canOpen(wanted, roles) ? wanted : screensFor(roles)[0];
  for (const link of document.querySelectorAll('#nav a')) link.classList.toggle('active', link.dataset.screen === screen);
  view += 1;
  const shown = view;
  if (typeof cleanup === 'function') cleanup();
  cleanup = null;
  closeMenu();
  $('#page-title').textContent = SCREENS[screen].title;
  $('#crumb').textContent = 'Verso';
  document.title = `${SCREENS[screen].title} · Verso`;
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

function userMenu() {
  const identity = currentIdentity();
  const name = identity.preferred_username ?? 'kullanıcı';
  const holder = $('#user');
  let menu = null;
  const close = () => {
    menu?.remove();
    menu = null;
    removeEventListener('click', outside);
  };
  const outside = (event) => {
    if (!holder.contains(event.target)) close();
  };
  const button = el('button', { class: 'avatar-button', type: 'button', 'aria-haspopup': 'menu', onclick: () => {
    if (menu) return close();
    menu = el('div', { class: 'menu', role: 'menu' },
      el('div', { class: 'menu-head' }, el('strong', {}, name), el('span', {}, roles.includes('verso-operator') ? 'Operatör' : 'Kullanıcı')),
      el('button', { type: 'button', role: 'menuitem', onclick: () => { close(); navigate('system'); }, hidden: !canOpen('system', roles) },
        icon('server', 'sm'), 'Sistem'),
      el('button', { type: 'button', role: 'menuitem', onclick: signOut }, icon('logout', 'sm'), 'Çıkış yap'));
    holder.append(menu);
    setTimeout(() => addEventListener('click', outside));
  } }, el('span', { class: 'avatar' }, initials(name)), el('span', { class: 'who' }, name));
  holder.replaceChildren(button);
}

function showSignIn(problem) {
  view += 1;
  if (typeof cleanup === 'function') cleanup();
  cleanup = null;
  $('#shell').hidden = true;
  const gate = $('#gate');
  gate.hidden = false;
  gate.replaceChildren(el('section', { class: 'signin' },
    el('div', { class: 'signin-hero' },
      el('span', { class: 'brand-mark big' }, 'V'),
      el('h2', {}, 'Belgelerinize soru sorun, cevabı kaynağıyla alın.'),
      el('p', {}, 'Verso, kurumunuzun belgelerini kendi sunucunuzda okur ve Türkçe sorulara kaynak göstererek cevap verir.'),
      el('ul', {},
        el('li', {}, icon('shield'), el('span', {}, 'Yerel modda hiçbir veri sunucudan çıkmaz.')),
        el('li', {}, icon('quote'), el('span', {}, 'Her bilginin yanında belge ve sayfa ya da bölüm.')),
        el('li', {}, icon('files'), el('span', {}, 'PDF, Word, TXT ve Markdown.')))),
    el('div', { class: 'signin-form' },
      el('h3', {}, 'Giriş yapın'),
      el('p', {}, 'Kurumunuzun kimlik sağlayıcısına yönlendirileceksiniz.'),
      problem ? el('div', { class: 'notice bad', role: 'alert' }, icon('alert', 'sm'), problem) : null,
      el('button', { class: 'button block', type: 'button', onclick: () => {
        sessionStorage.removeItem(AUTO_SIGN_IN);
        signIn();
      } }, icon('key'), 'Giriş yap'),
      el('p', { class: 'signin-note' }, icon('info', 'sm'), 'Oturum bilgisi yalnız bu sekmenin belleğinde tutulur.'))));
}

async function start() {
  // The client's redirect URI and web origin name localhost (verso-realm.json); 127.0.0.1 is the same host.
  if (location.hostname === '127.0.0.1') {
    location.replace(`${location.protocol}//localhost:${location.port}${location.pathname}${location.hash}`);
    return;
  }
  applyTheme(storedTheme());
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => applyTheme(storedTheme()));
  $('#theme-toggle').addEventListener('click', () => {
    const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    try {
      sessionStorage.setItem(THEME, next);
    } catch {
      // the choice then lasts for this page only
    }
    applyTheme(next);
  });
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
  $('#menu-toggle').replaceChildren(icon('menu'));
  $('#menu-toggle').addEventListener('click', () => {
    const open = !$('#sidebar').classList.contains('open');
    $('#sidebar').classList.toggle('open', open);
    $('#scrim').hidden = !open;
    $('#menu-toggle').setAttribute('aria-expanded', String(open));
  });
  $('#scrim').addEventListener('click', closeMenu);
  $('#nav').replaceChildren(el('div', { class: 'nav-label' }, 'Çalışma alanı'),
    ...screensFor(roles).map((id) => el('a', { href: `#/${id}`, 'data-screen': id }, icon(SCREENS[id].icon), SCREENS[id].title,
      id === 'documents' ? el('span', { class: 'count' }, String(counts.documents ?? '')) : null)));
  userMenu();
  addEventListener('hashchange', route);
  route();
}

start();
