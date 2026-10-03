// Verso panel: documents, questions with citations, and the system view for operators (ADR-0015). No framework and no
// build step; ES modules served by the edge proxy. Screens are hash routes (#/documents, #/ask, #/system).
import { CONFIG } from './config.js';
import { completeSignIn, currentClaims, currentIdentity, expiresAt, signIn, signOut } from './auth.js';
import { api, ApiError, lastMode } from './api.js';
import { canOpen, rolesOf, screensFor, SCREENS } from './roles.js';
import { answerParts, el, FAILURES, formatDate, formatSize, STATUS } from './render.js';

const MAX_UPLOAD = 20 * 1024 * 1024;
const main = document.querySelector('#main');
const nav = document.querySelector('#nav');
const user = document.querySelector('#user');
const modeBadge = document.querySelector('#mode');
let roles = [];
let documentsCache = [];
let poll = null;

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
    return await action();
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return signIn();
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
    el('td', { class: 'name' }, doc.fileName),
    el('td', {}, el('span', { class: `chip ${status.tone}` }, status.label),
      doc.failureReason ? el('span', { class: 'hint' }, FAILURES[doc.failureReason] ?? doc.failureReason) : null),
    el('td', { class: 'num' }, doc.pageCount ?? '–'),
    el('td', { class: 'num' }, formatSize(doc.sizeBytes)),
    el('td', {}, formatDate(doc.createdAt)),
    el('td', { class: 'actions' }, el('button', { class: 'ghost danger', type: 'button', title: 'Sil',
      onclick: () => removeDocument(doc) }, 'Sil')));
}

async function removeDocument(doc) {
  if (!confirm(`"${doc.fileName}" belgesi, sayfaları ve vektörleriyle birlikte kalıcı olarak silinsin mi?`)) return;
  await guarded(async () => {
    await api.deleteDocument(doc.id);
    toast('Belge silindi.', 'ok');
    await renderDocuments();
  });
}

async function uploadFiles(files) {
  for (const file of files) {
    if (file.type && file.type !== 'application/pdf') { toast(`${file.name}: yalnız PDF yüklenebilir.`); continue; }
    if (file.size > MAX_UPLOAD) { toast(`${file.name}: 20 MB sınırını aşıyor.`); continue; }
    await guarded(async () => {
      await api.upload(file);
      toast(`${file.name} yüklendi; işleniyor.`, 'ok');
    });
  }
  await renderDocuments();
}

async function renderDocuments() {
  const page = await guarded(() => api.listDocuments());
  if (!page) return;
  documentsCache = page.data;
  const input = el('input', { type: 'file', accept: 'application/pdf', multiple: true, hidden: true,
    onchange: (e) => uploadFiles([...e.target.files]) });
  const drop = el('div', { class: 'drop', tabindex: 0, role: 'button',
    onclick: () => input.click(), onkeydown: (e) => { if (e.key === 'Enter' || e.key === ' ') input.click(); },
    ondragover: (e) => { e.preventDefault(); drop.classList.add('over'); },
    ondragleave: () => drop.classList.remove('over'),
    ondrop: (e) => { e.preventDefault(); drop.classList.remove('over'); uploadFiles([...e.dataTransfer.files]); } },
  el('strong', {}, 'PDF yükle'), el('span', {}, 'Sürükleyip bırakın ya da tıklayın · en çok 20 MB, 500 sayfa'), input);
  const rows = page.data.map(documentRow);
  const table = rows.length
    ? el('table', {}, el('thead', {}, el('tr', {}, ['Ad', 'Durum', 'Sayfa', 'Boyut', 'Yüklenme', ''].map((h) => el('th', {}, h)))),
      el('tbody', {}, rows))
    : el('p', { class: 'empty' }, 'Henüz belge yok. Örnekler: samples/ klasöründeki PDF\'ler.');
  main.replaceChildren(el('h1', {}, 'Belgeler'),
    el('p', { class: 'lead' }, 'Belgeleriniz bu sunucuda işlenir; orijinal PDF işlendikten sonra silinir, sayfa metinleri ve vektörler kalır.'),
    drop, table,
    el('p', { class: 'meta' }, `${page.page.totalElements} belge`));
  clearInterval(poll);
  if (page.data.some((d) => d.status === 'PENDING' || d.status === 'PROCESSING')) poll = setInterval(renderDocuments, 4000);
}

// ---------- questions ----------
function citationChip(citation) {
  return el('span', { class: 'cite', title: `${citation.fileName}, sayfa ${citation.page}` }, String(citation.number));
}

function answerCard(question, result) {
  const body = el('p', { class: 'answer' }, answerParts(result.answer, result.citations)
    .map((part) => (part.citation ? citationChip(part.citation) : part.text)));
  const sources = result.citations.length
    ? el('ol', { class: 'sources' }, result.citations.map((c) => el('li', {}, el('strong', {}, c.fileName), `, sayfa ${c.page}`)))
    : null;
  const note = result.found ? null : el('p', { class: 'hint' }, result.citations.length === 0 && result.answer
    ? 'Kaynak gösterilemedi: bu cevap belgelerinize dayanmıyor olabilir.' : 'Belgelerinizde bu sorunun cevabı bulunamadı.');
  return el('article', { class: `card ${result.found ? '' : 'muted'}` },
    el('p', { class: 'question' }, question), body, note, sources,
    el('p', { class: 'meta' }, `${result.mode === 'local' ? 'Yerel' : 'Bulut'} model: ${result.model}`));
}

function renderAsk() {
  clearInterval(poll);
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
  clearInterval(poll);
  const info = await guarded(() => api.info());
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
  RENDER[screen]();
}

async function start() {
  let signedIn = false;
  try {
    signedIn = await completeSignIn();
  } catch {
    signedIn = false;
  }
  if (!signedIn) {
    main.replaceChildren(el('section', { class: 'signin' }, el('h1', {}, 'Verso'),
      el('p', { class: 'lead' }, 'Kendi belgelerinize kaynak gösteren sorular sorun. Belgeler bu sunucuda kalır.'),
      el('button', { type: 'button', onclick: () => signIn() }, 'Giriş yap')));
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
