// "Parçaları incele" (ADR-0036): verso-admin sees how a document was cut for retrieval: each chunk's page or section,
// its length, the heading it opens with and its first characters, searchable, with the whole page one click away in the
// page viewer (ADR-0035). Only documents the admin reads are offered and served (ADR-0028: managing a collection is not
// reading it); the server answers 404 for any other and 403 for a non-admin. Chunk text is document text: written as
// text nodes only, kept in this screen's memory and dropped when the screen is left.
import { api, ApiError } from '../api.js';
import { CHUNK_PAGE_SIZE, chunkHeading, chunkLabel, chunkPreview, filterChunks, mergeChunks } from '../chunks.js';
import { closePageDrawer, openPageDrawer } from '../drawer.js';
import { icon } from '../icons.js';
import { el, fill, plainName } from '../render.js';
import { emptyState, formatNumber, toast } from '../ui.js';

export function renderChunks(ctx) {
  const { main, guarded, withAuth } = ctx;
  let state = { document: null, chunks: [], more: false, nextPage: null, total: 0 };
  let generation = 0; // a late page for a document the admin has left is dropped

  const picker = el('select', { class: 'input', 'aria-label': 'İncelenecek belge' }, el('option', { value: '' }, 'Belgeler yükleniyor…'));
  const search = el('input', { class: 'input', type: 'search', maxlength: 200, 'aria-label': 'Parçalarda ara',
    placeholder: 'Yüklenen parçalarda ara', autocomplete: 'off', disabled: true, oninput: () => draw() });
  const status = el('p', { class: 'muted chunk-status', role: 'status', 'aria-live': 'polite' });
  const list = el('ol', { class: 'chunk-list', 'aria-label': 'Parçalar' });
  const more = el('button', { class: 'button secondary', type: 'button', hidden: true, onclick: () => load(false) },
    icon('chevronDown'), 'Daha fazla parça yükle');
  const result = el('section', { class: 'card', 'aria-labelledby': 'chunks-title', hidden: true },
    el('div', { class: 'card-head' }, el('h2', { id: 'chunks-title' }, 'Parçalar')),
    el('div', { class: 'card-body grid' }, search, status, list, more));
  const blocked = el('div', {});

  main.replaceChildren(
    el('div', { class: 'page-head' },
      el('div', {}, el('h1', {}, 'Parçaları incele'),
        el('p', {}, 'Bir belgenin soru-cevapta kullanılan parçalarını görün: her parçanın sayfası ya da bölümü, uzunluğu, başlığı '
          + 've ilk satırları. Yalnız sizin okuyabildiğiniz belgeler listelenir; bir bilgi alanını yönetmek onu okumak değildir. '
          + 'Bir alanın belgelerini incelemek için o alanda kendinize erişim verin.'))),
    blocked,
    el('section', { class: 'card', 'aria-labelledby': 'chunks-pick' },
      el('div', { class: 'card-head' }, el('h2', { id: 'chunks-pick' }, 'Belge seçin')),
      el('form', { class: 'card-body page-actions', onsubmit: (event) => {
        event.preventDefault();
        const option = picker.selectedOptions[0];
        if (!option?.value) return;
        open({ id: option.value, fileName: option.textContent });
      } }, picker, el('button', { class: 'button', type: 'submit' }, icon('search'), 'İncele'))),
    result);

  function draw() {
    const shown = filterChunks(state.chunks, search.value);
    fill(list, shown.map(item));
    const loaded = `${formatNumber(state.chunks.length)} / ${formatNumber(state.total)} parça yüklendi`;
    status.textContent = search.value.trim()
      ? `${formatNumber(shown.length)} parça eşleşiyor (${loaded}; arama yalnız yüklenenlerde yapılır).`
      : `${loaded}.`;
    if (!state.chunks.length) fill(list, el('li', { class: 'muted' }, 'Bu belgenin parçası yok; belge henüz işleniyor olabilir.'));
    more.hidden = !state.more;
  }

  function item(chunk) {
    const heading = chunkHeading(chunk.text);
    return el('li', { class: 'chunk' },
      el('div', { class: 'chunk-head' },
        el('strong', { class: 'tabular' }, `Parça ${formatNumber(chunk.index + 1)}`),
        el('span', { class: 'muted tabular' }, chunkLabel(chunk)),
        chunk.truncated ? el('span', { class: 'pill neutral', title: 'Liste parçanın başını taşır; tamamı sayfa görünümünde' }, 'Kısaltıldı') : null),
      heading ? el('p', { class: 'chunk-heading' }, plainName(heading)) : null,
      el('p', { class: 'chunk-text' }, chunkPreview(chunk.text)),
      el('div', { class: 'page-actions' },
        el('button', { class: 'button ghost sm', type: 'button', onclick: (event) => openPageDrawer({
          documentId: state.document.id, fileName: state.document.fileName, page: chunk.number, unit: chunk.unit,
          excerpt: chunk.text }, { withAuth, opener: event.currentTarget }) },
        icon('book', 'sm'), chunk.unit === 'SECTION' ? 'Bölümü aç' : 'Sayfayı aç')));
  }

  function deny(error) {
    if (error instanceof ApiError && error.status === 403) {
      fill(blocked, emptyState('lock', 'Bu ekran yöneticiler içindir', 'Parçaları yalnız verso-admin rolü inceler.'));
      result.hidden = true;
      return true;
    }
    if (error instanceof ApiError && error.status === 404) {
      fill(list, el('li', {}, emptyState('lock', 'Bu belgeyi okuyamıyorsunuz',
        'Belge silinmiş ya da size açık olmayan bir alanda. Bir bilgi alanını yönetmek onu okumak değildir.')));
      status.textContent = '';
      more.hidden = true;
      return true;
    }
    return false;
  }

  async function load(first) {
    const asked = generation;
    const page = first ? 0 : state.nextPage;
    if (page === null) return;
    more.disabled = true;
    let answer;
    try {
      answer = await withAuth(() => api.listChunks(state.document.id, page, CHUNK_PAGE_SIZE));
    } catch (error) {
      more.disabled = false;
      if (asked !== generation || !ctx.isCurrent()) return;
      if (!deny(error) && !(error instanceof ApiError && error.status === 401)) {
        toast(error instanceof ApiError ? error.message : 'Bağlantı kurulamadı.');
      }
      return;
    }
    more.disabled = false;
    if (asked !== generation || !ctx.isCurrent()) return;
    const merged = mergeChunks(first ? [] : state.chunks, answer);
    state = { ...state, ...merged };
    search.disabled = false;
    draw();
  }

  function open(document) {
    generation += 1;
    closePageDrawer();
    state = { document: { id: document.id, fileName: plainName(document.fileName) }, chunks: [], more: false, nextPage: null, total: 0 };
    search.value = '';
    search.disabled = true;
    result.hidden = false;
    result.querySelector('h2').textContent = state.document.fileName;
    fill(list, el('li', {}, el('div', { class: 'skeleton line' })));
    status.textContent = 'Parçalar yükleniyor…';
    more.hidden = true;
    load(true);
  }

  async function documents() {
    const docs = await guarded(() => api.listAllDocuments());
    if (!ctx.isCurrent()) return;
    const ready = (docs ?? []).filter((doc) => doc.status === 'READY');
    fill(picker, ready.length
      ? [el('option', { value: '' }, 'Bir belge seçin'), ...ready.map((doc) => el('option', { value: doc.id }, plainName(doc.fileName)))]
      : el('option', { value: '' }, 'Okuyabildiğiniz hazır belge yok'));
    const wanted = ctx.params?.document;
    if (wanted && ready.some((doc) => doc.id === wanted.id)) picker.value = wanted.id;
  }

  documents();
  if (ctx.params?.document) open(ctx.params.document);
  return () => {
    generation += 1;
    closePageDrawer();
    state = { document: null, chunks: [], more: false, nextPage: null, total: 0 };
  };
}
