// Verso panel: documents, questions with citations, and the system view for operators (ADR-0015). No framework and no
// build step; ES modules served by the edge proxy. Screens are hash routes (#/documents, #/ask, #/system).
import { CONFIG } from './config.js';
import { completeSignIn, currentClaims, currentIdentity, expiresAt, signIn, signOut } from './auth.js';
import { api, ApiError, lastMode, PAGE_SIZE } from './api.js';
import { canOpen, rolesOf, screensFor, SCREENS } from './roles.js';
import { acceptedFile, answerParts, el, FAILURES, formatDate, formatSize, plainName, sourceLabel, STATUS, UPLOAD_TYPES } from './render.js';

const MAX_UPLOAD = 20 * 1024 * 1024;
const main = document.querySelector('#main');
const nav = document.querySelector('#nav');
const user = document.querySelector('#user');
const modeBadge = document.querySelector('#mode');
// One automatic sign-in after a 401, then the sign-in screen: a token the API keeps refusing (wrong issuer or
// audience, clock skew) must not bounce the browser between the panel and the IdP forever.
const AUTO_SIGN_IN = 'verso.panel.auto';
let roles = [];
let poll = null;
let view = 0; // bumped on every navigation: a late answer for a screen the user left is dropped
let documentsPage = 0;

function toast(message, tone = 'bad') {
  const node = el('div', { class: `toast ${tone}`, role: 'status' }, message);
  document.querySelector('#toasts').append(node);
  setTimeout(() => node.remove(), 6000);
}

function showMode() {
  if (!lastMode) return;
  modeBadge.hidden = false;
  modeBadge.className = `badge mode-${lastMode}`;
  modeBadge.textContent = lastMode === 'local' ? 'Yerel mod' : 'Bulut modu';
  modeBadge.title = lastMode === 'local'
    ? 'Belgeler ve sorular bu sunucudan çıkmaz.'
    : 'Sorular ve seçilen pasajlar bulut sağlayıcısına gider (KVKK md. 9).';
}

async function guarded(action) {
  try {
    const result = await action();
    sessionStorage.removeItem(AUTO_SIGN_IN);
    return result;
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      if (sessionStorage.getItem(AUTO_SIGN_IN)) return showSignIn('Oturum açılamadı: servis girişinizi kabul etmedi. Yöneticinize bildirin.');
      sessionStorage.setItem(AUTO_SIGN_IN, '1');
      return signIn();
    }
    toast(error instanceof ApiError ? error.message : 'Bağlantı kurulamadı.');
    return undefined;
  } finally {
    showMode();
  }
}

// ---------- documents ----------
function documentRow(doc) {
  const status = STATUS[doc.status] ?? { label: doc.status, tone: 'wait' };
  return el('tr', { 'data-id': doc.id },
    el('td', { class: 'name' }, plainName(doc.fileName)),
    el('td', {}, el('span', { class: 'chip' }, doc.format ?? 'PDF')),
    el('td', {}, el('span', { class: `chip ${status.tone}` }, status.label),
      doc.failureReason ? el('span', { class: 'hint' }, FAILURES[doc.failureReason] ?? doc.failureReason) : null),
    el('td', { class: 'num' }, doc.pageCount ?? '–'),
    el('td', { class: 'num' }, formatSize(doc.sizeBytes)),
    el('td', {}, formatDate(doc.createdAt)),
    el('td', { class: 'actions' }, el('button', { class: 'ghost danger', type: 'button', title: 'Sil',
      onclick: () => removeDocument(doc) }, 'Sil')));
}

async function removeDocument(doc) {
  if (!confirm(`"${plainName(doc.fileName)}" belgesi, metni ve vektörleriyle birlikte kalıcı olarak silinsin mi?`)) return;
  await guarded(async () => {
    await api.deleteDocument(doc.id);
    toast('Belge silindi.', 'ok');
    await renderDocuments();
  });
}

async function uploadFiles(files) {
  for (const file of files) {
    if (!acceptedFile(file.name)) { toast(`${plainName(file.name)}: yalnız PDF, DOCX, TXT ya da MD yüklenebilir.`); continue; }
    if (file.size > MAX_UPLOAD) { toast(`${plainName(file.name)}: 20 MB sınırını aşıyor.`); continue; }
    await guarded(async () => {
      await api.upload(file);
      toast(`${plainName(file.name)} yüklendi; işleniyor.`, 'ok');
    });
  }
  await renderDocuments();
}

async function renderDocuments() {
  const shown = view;
  clearInterval(poll);
  const page = await guarded(() => api.listDocuments(documentsPage));
  if (shown !== view) return;
  if (!page) return; // the poll stays stopped after a failure; navigating again retries
  if (documentsPage > 0 && documentsPage >= page.page.totalPages) {
    documentsPage = Math.max(0, page.page.totalPages - 1);
    return renderDocuments();
  }
  const input = el('input', { type: 'file', accept: UPLOAD_TYPES.join(','), multiple: true, hidden: true,
    onchange: (e) => uploadFiles([...e.target.files]) });
  const drop = el('div', { class: 'drop', tabindex: 0, role: 'button',
    onclick: () => input.click(), onkeydown: (e) => { if (e.key === 'Enter' || e.key === ' ') input.click(); },
    ondragover: (e) => { e.preventDefault(); drop.classList.add('over'); },
    ondragleave: () => drop.classList.remove('over'),
    ondrop: (e) => { e.preventDefault(); drop.classList.remove('over'); uploadFiles([...e.dataTransfer.files]); } },
  el('strong', {}, 'Belge yükle'), el('span', {}, 'PDF, Word (DOCX), TXT ya da Markdown · sürükleyip bırakın ya da tıklayın · en çok 20 MB'), input);
  const rows = page.data.map(documentRow);
  const table = rows.length
    ? el('table', {}, el('thead', {}, el('tr', {}, ['Ad', 'Tür', 'Durum', 'Sayfa / bölüm', 'Boyut', 'Yüklenme', ''].map((h) => el('th', {}, h)))),
      el('tbody', {}, rows))
    : el('p', { class: 'empty' }, 'Henüz belge yok. Örnekler: samples/ klasöründeki PDF\'ler.');
  main.replaceChildren(el('h1', {}, 'Belgeler'),
    el('p', { class: 'lead' }, 'Belgeleriniz bu sunucuda işlenir; orijinal dosya işlendikten sonra silinir, metni ve vektörleri kalır. Word ve metin dosyaları sayfa yerine bölümlerle kaynak gösterir.'),
    drop, table, pager(page.page));
  if (page.data.some((d) => d.status === 'PENDING' || d.status === 'PROCESSING')) poll = setInterval(renderDocuments, 4000);
}

function pager({ number, totalPages, totalElements }) {
  const go = (to) => () => { documentsPage = to; renderDocuments(); };
  return el('div', { class: 'row pager' },
    el('p', { class: 'meta' }, totalPages > 1
      ? `${totalElements} belge · sayfa ${number + 1}/${totalPages} (sayfa başına ${PAGE_SIZE})` : `${totalElements} belge`),
    totalPages > 1 ? el('span', {},
      el('button', { class: 'ghost', type: 'button', disabled: number === 0, onclick: go(number - 1) }, 'Önceki'),
      el('button', { class: 'ghost', type: 'button', disabled: number + 1 >= totalPages, onclick: go(number + 1) }, 'Sonraki'))
      : null);
}

// ---------- questions ----------
function citationChip(citation) {
  return el('span', { class: 'cite', title: sourceLabel(citation) }, String(citation.number));
}

function answerCard(question, result) {
  const body = el('p', { class: 'answer' }, answerParts(result.answer, result.citations)
    .map((part) => (part.citation ? citationChip(part.citation) : part.text)));
  const sources = result.citations.length
    ? el('ol', { class: 'sources' }, result.citations.map((c) => el('li', {}, el('strong', {}, c.fileName), sourceLabel(c).slice(c.fileName.length))))
    : null;
  const note = result.outcome === 'UNCITED'
    ? el('p', { class: 'hint' }, 'Kaynak gösterilemedi: bu cevap belgelerinize dayanmıyor olabilir.') : null;
  return el('article', { class: `card ${result.found ? '' : 'muted'}` },
    el('p', { class: 'question' }, question), body, note, sources,
    el('p', { class: 'meta' }, `${result.mode === 'local' ? 'Yerel' : 'Bulut'} model: ${result.model}`));
}

function renderAsk() {
  const history = el('section', { class: 'history', 'aria-live': 'polite' });
  const field = el('textarea', { rows: 3, maxlength: 1000, placeholder: 'Örnek: Yıllık izin kaç gün?', required: true });
  const button = el('button', { type: 'submit' }, 'Sor');
  const form = el('form', { class: 'ask', onsubmit: async (e) => {
    e.preventDefault();
    const question = field.value.trim();
    if (!question) return;
    button.disabled = true;
    button.textContent = 'Düşünüyor…';
    const pending = el('article', { class: 'card pending' }, el('p', { class: 'question' }, question),
      el('p', { class: 'hint' }, 'Cevap hazırlanıyor. Yerel modelle bu birkaç on saniye sürebilir.'));
    history.prepend(pending);
    const result = await guarded(() => api.ask(question));
    pending.replaceWith(result ? answerCard(question, result) : el('article', { class: 'card muted' },
      el('p', { class: 'question' }, question), el('p', { class: 'hint' }, 'Cevap alınamadı.')));
    if (result) field.value = '';
    button.disabled = false;
    button.textContent = 'Sor';
  } }, field, el('div', { class: 'row' }, el('span', { class: 'hint' }, 'Cevaplar yalnız sizin belgelerinizden gelir ve kaynak gösterir.'), button));
  main.replaceChildren(el('h1', {}, 'Soru sor'), form, history);
  field.focus();
}

// ---------- system (operators) ----------
async function renderSystem() {
  const shown = view;
  const info = await guarded(() => api.info());
  if (shown !== view) return;
  const left = Math.max(0, Math.round((expiresAt() - Date.now()) / 1000));
  const identity = currentIdentity();
  main.replaceChildren(el('h1', {}, 'Sistem'),
    el('dl', { class: 'facts' },
      el('dt', {}, 'Mod'), el('dd', {}, info ? (info.mode === 'local' ? 'Yerel (hiçbir model çağrısı dışarı çıkmaz)' : 'Bulut (yalnız chat çağrısı sağlayıcıya gider)') : '–'),
      el('dt', {}, 'Chat modeli'), el('dd', {}, info?.chatModel ?? '–'),
      el('dt', {}, 'Embedding modeli'), el('dd', {}, info?.embeddingModel ?? '–'),
      el('dt', {}, 'Oturum'), el('dd', {}, `${identity.preferred_username ?? '–'} · rol: ${roles.join(', ')} · token ${left} sn geçerli (yalnız bellekte)`)),
    el('h2', {}, 'Gözlem'),
    el('p', {}, 'Metrikler, alarmlar ve log\'lar Grafana\'da (gözlem profili açıksa): ',
      el('a', { href: CONFIG.grafana, target: '_blank', rel: 'noopener noreferrer' }, CONFIG.grafana), '. Runbook\'lar: docs/runbooks/.'));
}

// ---------- shell ----------
const RENDER = { documents: renderDocuments, ask: renderAsk, system: renderSystem };

function route() {
  const wanted = location.hash.replace(/^#\//, '') || 'documents';
  const screen = canOpen(wanted, roles) ? wanted : screensFor(roles)[0];
  for (const link of nav.querySelectorAll('a')) link.classList.toggle('active', link.dataset.screen === screen);
  view += 1;
  clearInterval(poll);
  RENDER[screen]();
}

function showSignIn(problem) {
  view += 1;
  clearInterval(poll);
  main.replaceChildren(el('section', { class: 'signin' }, el('h1', {}, 'Verso'),
    el('p', { class: 'lead' }, 'Kendi belgelerinize kaynak gösteren sorular sorun. Belgeler bu sunucuda kalır.'),
    problem ? el('p', { class: 'hint bad', role: 'alert' }, problem) : null,
    el('button', { type: 'button', onclick: () => { sessionStorage.removeItem(AUTO_SIGN_IN); signIn(); } }, 'Giriş yap')));
}

async function start() {
  // The client's redirect URI and web origin name localhost (verso-realm.json); 127.0.0.1 is the same host.
  if (location.hostname === '127.0.0.1') {
    location.replace(`${location.protocol}//localhost:${location.port}${location.pathname}${location.hash}`);
    return;
  }
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
  const claims = currentClaims();
  roles = rolesOf(claims);
  user.hidden = false;
  user.replaceChildren(el('span', {}, currentIdentity().preferred_username ?? 'kullanıcı'),
    el('button', { class: 'ghost', type: 'button', onclick: signOut }, 'Çıkış'));
  nav.replaceChildren(...screensFor(roles).map((id) => el('a', { href: `#/${id}`, 'data-screen': id }, SCREENS[id].title)));
  addEventListener('hashchange', route);
  route();
}

start();
