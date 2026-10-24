// Documents (ADR-0017): a drop zone with per-file progress, search, status filters, a sortable table with keyboard
// rows, and a detail drawer with deletion that names the file.
import { api, ApiError } from '../api.js';
import { CONFIG } from '../config.js';
import { icon } from '../icons.js';
import { ROLES } from '../roles.js';
import { el, fill, foldTr, formatDate, formatLabel, formatSize, plainName, UPLOAD_TYPES } from '../render.js';
import { CONNECTORS } from './setup.js';
import { confirmDialog, emptyState, failureText, fileIcon, formatNumber, openDrawer, relativeTime, statusPill, toast } from '../ui.js';

const MAX_UPLOAD = 20 * 1024 * 1024;
const ROWS_PER_PAGE = 25;
const FILTERS = [['all', 'Tümü'], ['READY', 'Hazır'], ['waiting', 'İşleniyor'], ['FAILED', 'Hata']];
const WAITING = (doc) => doc.status === 'PENDING' || doc.status === 'PROCESSING';
const COLUMNS = [
  { id: 'name', label: 'Belge', value: (d) => foldTr(plainName(d.fileName)) },
  { id: 'status', label: 'Durum', value: (d) => d.status },
  { id: 'units', label: 'Sayfa / bölüm', value: (d) => d.pageCount ?? -1, num: true, hideSm: true },
  { id: 'size', label: 'Boyut', value: (d) => d.sizeBytes, num: true, hideSm: true },
  { id: 'created', label: 'Yüklenme', value: (d) => d.createdAt, hideSm: true },
];

const state = { filter: 'all', query: '', page: 0, sort: 'created', dir: -1 };

function matches(doc) {
  const status = state.filter === 'all' || (state.filter === 'waiting' ? WAITING(doc) : doc.status === state.filter);
  return status && foldTr(plainName(doc.fileName)).includes(foldTr(state.query));
}

function unitLabel(doc) {
  if (doc.pageCount == null) return '–';
  return `${formatNumber(doc.pageCount)} ${(doc.format ?? 'PDF') === 'PDF' ? 'sayfa' : 'bölüm'}`;
}

/** A shared document is managed by verso-admin only (ADR-0028): readers see it, but cannot delete it. */
export function canDelete(doc, admin) {
  return !doc.collectionId || Boolean(admin);
}

/** The space a document lives in, for the row and the drawer. */
export function spaceLabel(doc) {
  return doc.collectionId ? plainName(doc.collectionName ?? 'Bilgi alanı') : 'Kişisel alan';
}

export function renderDocuments(ctx) {
  const { main, guarded } = ctx;
  const admin = (ctx.roles ?? []).includes(ROLES.ADMIN);
  let docs = null;
  let poll = null;
  let collections = [];
  // Where new uploads go: '' is the personal space; an admin may pick a shared collection (ADR-0028).
  let target = '';
  const picker = el('select', { class: 'input', 'aria-label': 'Yükleme yeri', hidden: !admin,
    onchange: (event) => { target = event.target.value; } });
  if (ctx.params?.query !== undefined) {
    state.query = ctx.params.query;
    state.filter = 'all';
  }
  const input = el('input', { type: 'file', accept: UPLOAD_TYPES.join(','), multiple: true, hidden: true,
    onchange: (event) => {
      upload([...event.target.files]);
      event.target.value = '';
    } });
  const uploads = el('div', { class: 'uploads', 'aria-live': 'polite' });
  const search = el('input', { class: 'input', type: 'search', placeholder: 'Belge adında ara', value: state.query,
    'aria-label': 'Belge adında ara', oninput: (event) => {
      state.query = event.target.value;
      state.page = 0;
      draw();
    } });
  const segmented = el('div', { class: 'segmented', role: 'group', 'aria-label': 'Durum filtresi' });
  const body = el('div', {}, el('div', { class: 'card-body grid' }, Array.from({ length: 6 }, () => el('div', { class: 'skeleton line' }))));
  const count = el('span', { class: 'muted tabular' });

  const dropzone = el('div', { class: 'dropzone', role: 'button', tabindex: 0, 'aria-label': 'Belge yükle: dosya seçin ya da sürükleyip bırakın',
    onclick: () => input.click(), onkeydown: (event) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        input.click();
      }
    } },
  el('span', { class: 'drop-icon' }, icon('upload', 'lg')),
  el('strong', {}, 'Dosyaları buraya bırakın ya da ', el('u', {}, 'bilgisayarınızdan seçin')),
  el('span', { class: 'hint' }, 'PDF, Word (DOCX), TXT, Markdown · dosya başına en çok 20 MB · orijinal dosya işlendikten sonra silinir'),
  el('div', { class: 'types' }, ['PDF', 'DOCX', 'TXT', 'MD'].map((type) => fileIcon(type, 'sm'))), input);

  main.replaceChildren(
    el('div', { class: 'page-head' },
      el('div', {}, el('h1', {}, 'Bilgi kaynakları'),
        el('p', {}, `${CONFIG.deployment.productName} cevap verirken bu belgeleri okur. Belgeler bu sunucuda işlenir; kişisel alanınızdakiler yalnız size, bilgi alanlarındakiler o alana yetkili gruplara görünür. Word ve metin dosyaları sayfa yerine bölümlerle kaynak gösterir.`)),
      el('div', { class: 'page-actions' }, picker,
        el('button', { class: 'button secondary', type: 'button', onclick: () => refresh() }, icon('refresh'), 'Yenile'),
        el('button', { class: 'button', type: 'button', onclick: () => input.click() }, icon('upload'), 'Belge yükle'))),
    dropzone, uploads,
    el('section', { class: 'card', 'aria-label': 'Belge listesi' },
      el('div', { class: 'toolbar' }, el('div', { class: 'input-icon' }, icon('search', 'sm'), search), segmented,
        el('span', { class: 'spacer' }), count),
      body),
    el('section', { class: 'card', 'aria-labelledby': 'connectors-title' },
      el('div', { class: 'card-head' }, el('h2', { id: 'connectors-title' }, 'Bağlantılar'), el('span', { class: 'spacer' }),
        el('a', { href: '#/setup' }, 'Kurulum durumu')),
      el('div', { class: 'card-body' }, el('p', { class: 'muted' }, 'Şirket sistemlerinden otomatik belge alma, kurulumun entegrasyon aşamasında eklenir.'),
        el('div', { class: 'connectors' }, CONNECTORS.map((c) => el('div', { class: 'connector' },
          el('span', { class: 'connector-icon' }, icon('plug', 'sm')),
          el('div', { class: 'grow' }, el('strong', {}, c.title), el('span', { class: 'sub' }, c.text)),
          el('span', { class: 'pill neutral' }, 'Planlandı')))))));

  // The whole page accepts a drop, not only the drop zone.
  const overlay = el('div', { class: 'drag-overlay', hidden: true }, el('div', {}, icon('upload', 'lg'), 'Yüklemek için bırakın'));
  document.body.append(overlay);
  let depth = 0;
  const onEnter = (event) => {
    if (![...(event.dataTransfer?.types ?? [])].includes('Files')) return;
    depth++;
    overlay.hidden = false;
  };
  const onLeave = () => {
    depth = Math.max(0, depth - 1);
    if (depth === 0) overlay.hidden = true;
  };
  const onOver = (event) => event.preventDefault();
  const onDrop = (event) => {
    event.preventDefault();
    depth = 0;
    overlay.hidden = true;
    if (event.dataTransfer?.files?.length) upload([...event.dataTransfer.files]);
  };
  addEventListener('dragenter', onEnter);
  addEventListener('dragleave', onLeave);
  addEventListener('dragover', onOver);
  addEventListener('drop', onDrop);

  function drawFilters() {
    segmented.replaceChildren(...FILTERS.map(([id, label]) => {
      const n = docs ? docs.filter((d) => (id === 'all' ? true : id === 'waiting' ? WAITING(d) : d.status === id)).length : 0;
      return el('button', { type: 'button', 'aria-pressed': String(state.filter === id),
        onclick: () => {
          state.filter = id;
          state.page = 0;
          draw();
        } }, label, el('span', { class: 'n' }, String(n)));
    }));
  }

  function header(column) {
    const active = state.sort === column.id;
    return el('th', { class: [column.num ? 'num' : '', column.hideSm ? 'hide-sm' : ''].join(' ').trim(),
      'aria-sort': active ? (state.dir > 0 ? 'ascending' : 'descending') : undefined, scope: 'col' },
    el('button', { type: 'button', onclick: () => {
      state.dir = active ? -state.dir : column.num || column.id === 'created' ? -1 : 1;
      state.sort = column.id;
      draw();
    } }, column.label, el('span', { class: 'sort-arrow', 'aria-hidden': 'true' }, active ? (state.dir > 0 ? '▲' : '▼') : '')));
  }

  function draw() {
    if (!docs) return;
    drawFilters();
    const column = COLUMNS.find((c) => c.id === state.sort) ?? COLUMNS[4];
    const visible = docs.filter(matches).sort((a, b) => {
      const x = column.value(a);
      const y = column.value(b);
      return (x < y ? -1 : x > y ? 1 : 0) * state.dir;
    });
    const pages = Math.max(1, Math.ceil(visible.length / ROWS_PER_PAGE));
    state.page = Math.min(state.page, pages - 1);
    const slice = visible.slice(state.page * ROWS_PER_PAGE, (state.page + 1) * ROWS_PER_PAGE);
    count.textContent = `${formatNumber(visible.length)} / ${formatNumber(docs.length)} belge`;
    if (!docs.length) {
      body.replaceChildren(emptyState('files', 'Henüz belge yok',
        'Yukarıdaki alana bir PDF, Word, TXT ya da Markdown dosyası bırakın. İçerik yalnız bu sunucuda işlenir.',
        el('button', { class: 'button', type: 'button', onclick: () => input.click() }, icon('upload'), 'İlk belgenizi yükleyin')));
      return;
    }
    if (!visible.length) {
      body.replaceChildren(emptyState('search', 'Eşleşen belge yok', 'Aramayı ya da durum filtresini değiştirin.',
        el('button', { class: 'button secondary', type: 'button', onclick: () => {
          state.query = '';
          state.filter = 'all';
          search.value = '';
          draw();
        } }, 'Filtreleri temizle')));
      return;
    }
    const rows = slice.map((doc) => el('tr', { tabindex: 0, 'aria-label': `${plainName(doc.fileName)}, ayrıntılar`,
      onclick: () => details(doc), onkeydown: (event) => {
        if (event.key === 'Enter' && event.target === event.currentTarget) details(doc);
      } },
    el('td', {}, el('div', { class: 'doc-cell' }, fileIcon(doc.format),
      el('div', {}, el('div', { class: 'name' }, plainName(doc.fileName),
        doc.collectionId ? el('span', { class: 'pill neutral', title: 'Bilgi alanı' }, spaceLabel(doc)) : null),
        el('div', { class: 'sub' }, doc.status === 'FAILED' ? failureText(doc.failureReason)
          : doc.pageCount == null ? formatLabel(doc.format) : `${formatLabel(doc.format)} · ${unitLabel(doc)}`)))),
    el('td', {}, statusPill(doc.status)),
    el('td', { class: 'num hide-sm' }, unitLabel(doc)),
    el('td', { class: 'num hide-sm' }, formatSize(doc.sizeBytes)),
    el('td', { class: 'when hide-sm', title: formatDate(doc.createdAt) }, relativeTime(doc.createdAt)),
    el('td', {}, el('div', { class: 'row-actions' },
      canDelete(doc, admin) ? el('button', { class: 'icon-button', type: 'button', 'aria-label': `${plainName(doc.fileName)} belgesini sil`, title: 'Sil',
        onclick: (event) => {
          event.stopPropagation();
          remove(doc);
        } }, icon('trash', 'sm')) : null))));
    fill(body,
      el('div', { class: 'table-wrap' }, el('table', { class: 'table' },
        el('thead', {}, el('tr', {}, COLUMNS.map(header), el('th', {}, el('span', { class: 'sr-only' }, 'İşlemler')))),
        el('tbody', {}, rows))),
      pages > 1 ? el('div', { class: 'table-foot' }, el('span', { class: 'tabular' }, `Sayfa ${state.page + 1} / ${pages}`),
        el('div', { class: 'page-actions' },
          el('button', { class: 'button secondary sm', type: 'button', disabled: state.page === 0, onclick: () => { state.page--; draw(); } }, 'Önceki'),
          el('button', { class: 'button secondary sm', type: 'button', disabled: state.page + 1 >= pages, onclick: () => { state.page++; draw(); } }, 'Sonraki')))
        : null);
  }

  async function refresh() {
    const loaded = await guarded(() => api.listAllDocuments());
    if (!ctx.isCurrent()) return;
    clearTimeout(poll);
    if (!loaded) return; // after a failure the poll stays stopped; "Yenile" tries again
    docs = loaded;
    ctx.setCount('documents', docs.length);
    draw();
    if (docs.some(WAITING)) poll = setTimeout(refresh, 3000);
  }

  /** One row per file: progress while sending, then the outcome; a failed upload can be retried from its row. */
  async function uploadOne(file, item) {
    const bar = el('span');
    const label = el('span', { class: 'muted' }, 'Yükleniyor');
    const percent = el('span', { class: 'size' }, '0%');
    item.replaceChildren(fileIcon(file.name.split('.').pop()?.toUpperCase()),
      el('div', { class: 'name', title: plainName(file.name) }, plainName(file.name)), label, percent, el('div', { class: 'bar' }, bar));
    item.className = 'upload-item';
    const fail = (text) => {
      item.classList.add('failed');
      bar.style.width = '100%';
      label.textContent = text;
      percent.replaceChildren(el('button', { class: 'button ghost sm', type: 'button', onclick: () => uploadOne(file, item).then(refresh) },
        icon('refresh', 'sm'), 'Tekrar dene'));
    };
    if (file.size > MAX_UPLOAD) {
      fail('20 MB sınırını aşıyor');
      percent.replaceChildren(el('span', { class: 'size' }, formatSize(file.size)));
      return;
    }
    try {
      await ctx.withAuth(() => api.upload(file, (ratio) => {
        bar.style.width = `${Math.round(ratio * 100)}%`;
        percent.textContent = `${Math.round(ratio * 100)}%`;
      }, target || null));
      item.classList.add('done');
      bar.style.width = '100%';
      label.textContent = 'Yüklendi · işleniyor';
      percent.textContent = formatSize(file.size);
      setTimeout(() => item.remove(), 6000);
    } catch (error) {
      fail(error instanceof ApiError ? error.message : 'Bağlantı kurulamadı');
    }
  }

  async function upload(files) {
    for (const file of files) {
      const item = el('div', { class: 'upload-item' });
      uploads.prepend(item);
      await uploadOne(file, item);
    }
    refresh();
  }

  async function remove(doc, closeDrawer) {
    const name = plainName(doc.fileName);
    const ok = await confirmDialog({ title: `"${name}" silinsin mi?`, confirm: 'Belgeyi sil',
      message: 'Belgenin metni ve vektörleri kalıcı olarak silinir; eski cevaplardaki bu belgeye ait kaynaklar açılamaz. Bu işlem geri alınamaz.' });
    if (!ok) return;
    const done = await guarded(async () => {
      await api.deleteDocument(doc.id);
      return true;
    });
    if (!done) return;
    closeDrawer?.();
    toast(`"${name}" silindi.`, 'ok');
    refresh();
  }

  async function details(doc) {
    const fresh = (await guarded(() => api.getDocument(doc.id))) ?? doc;
    const close = openDrawer({
      title: plainName(fresh.fileName),
      subtitle: [fileIcon(fresh.format, 'sm'), formatLabel(fresh.format), statusPill(fresh.status)],
      body: [
        fresh.status === 'FAILED' ? el('div', { class: 'notice bad' }, icon('alert', 'sm'),
          el('div', {}, el('strong', {}, failureText(fresh.failureReason)), el('div', {}, 'Dosyayı kontrol edip yeniden yükleyin.'))) : null,
        WAITING(fresh) ? el('div', { class: 'notice wait' }, icon('clock', 'sm'), 'Belge işleniyor; birkaç saniye içinde aranabilir olur.') : null,
        el('dl', { class: 'facts' },
          el('dt', {}, 'Alan'), el('dd', {}, spaceLabel(fresh)),
          el('dt', {}, 'Tür'), el('dd', {}, formatLabel(fresh.format)),
          el('dt', {}, (fresh.format ?? 'PDF') === 'PDF' ? 'Sayfa' : 'Bölüm'), el('dd', { class: 'tabular' }, fresh.pageCount == null ? '–' : formatNumber(fresh.pageCount)),
          el('dt', {}, 'Parça'), el('dd', { class: 'tabular' }, fresh.chunkCount == null ? '–' : formatNumber(fresh.chunkCount)),
          el('dt', {}, 'Boyut'), el('dd', { class: 'tabular' }, formatSize(fresh.sizeBytes)),
          el('dt', {}, 'Yüklenme'), el('dd', {}, formatDate(fresh.createdAt)),
          el('dt', {}, 'Son değişiklik'), el('dd', {}, formatDate(fresh.updatedAt)),
          el('dt', {}, 'Kimlik'), el('dd', {}, el('span', { class: 'code' }, fresh.id))),
        el('div', { class: 'notice info' }, icon('shield', 'sm'),
          'Orijinal dosya işlendikten sonra silinir; yalnız metni ve vektörleri kalır. Silme bunların hepsini kaldırır (KVKK).'),
      ],
      actions: [
        fresh.status === 'READY' ? el('button', { class: 'button secondary', type: 'button', onclick: () => {
          close();
          ctx.navigate('ask', { focus: true });
        } }, icon('chat'), 'Bu belgelere sor') : null,
        admin ? moveControl(fresh, close) : null,
        canDelete(fresh, admin)
          ? el('button', { class: 'button danger', type: 'button', onclick: () => remove(fresh, close) }, icon('trash'), 'Belgeyi sil') : null,
      ].filter(Boolean),
    });
  }

  /** verso-admin: move a document between the personal space and the collections (ADR-0028). */
  function moveControl(doc, close) {
    const select = el('select', { class: 'input', 'aria-label': 'Taşınacak yer' },
      el('option', { value: '' }, 'Yükleyenin kişisel alanı'),
      collections.map((c) => el('option', { value: c.id, selected: c.id === doc.collectionId }, plainName(c.name))));
    return el('div', { class: 'page-actions' }, select,
      el('button', { class: 'button secondary', type: 'button', onclick: async () => {
        const destination = select.value || null;
        if (destination === (doc.collectionId ?? null)) return;
        const moved = await guarded(() => api.moveDocument(doc.id, destination));
        if (!moved) return;
        close();
        toast('Belge taşındı.', 'ok');
        refresh();
      } }, icon('refresh'), 'Taşı'));
  }

  async function loadCollections() {
    if (!admin) return;
    const page = await guarded(() => api.listCollections());
    if (!page || !ctx.isCurrent()) return;
    collections = page.data ?? [];
    picker.replaceChildren(el('option', { value: '' }, 'Kişisel alanım'),
      ...collections.map((c) => el('option', { value: c.id }, `Bilgi alanı: ${plainName(c.name)}`)));
  }
  loadCollections();

  refresh().then(() => {
    if (ctx.params?.open && docs) {
      const doc = docs.find((d) => d.id === ctx.params.open);
      if (doc) details(doc);
    }
  });
  if (ctx.params?.upload) dropzone.focus();
  return () => {
    clearTimeout(poll);
    overlay.remove();
    removeEventListener('dragenter', onEnter);
    removeEventListener('dragleave', onLeave);
    removeEventListener('dragover', onOver);
    removeEventListener('drop', onDrop);
  };
}
