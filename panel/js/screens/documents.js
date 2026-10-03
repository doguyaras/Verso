// Documents: upload (with progress), search and filter, the table, and a detail drawer with deletion.
import { api, ApiError } from '../api.js';
import { icon } from '../icons.js';
import { el, formatDate, formatLabel, formatSize, plainName, UPLOAD_TYPES } from '../render.js';
import { confirmDialog, emptyState, failureText, fileIcon, formatNumber, openDrawer, relativeTime, statusPill, toast } from '../ui.js';

const MAX_UPLOAD = 20 * 1024 * 1024;
const ROWS_PER_PAGE = 25;
const FILTERS = [['all', 'Tümü'], ['READY', 'Hazır'], ['waiting', 'İşleniyor'], ['FAILED', 'Hata']];

const state = { filter: 'all', query: '', page: 0 };

function matches(doc) {
  const status = state.filter === 'all' || (state.filter === 'waiting'
    ? doc.status === 'PENDING' || doc.status === 'PROCESSING' : doc.status === state.filter);
  return status && plainName(doc.fileName).toLocaleLowerCase('tr').includes(state.query.toLocaleLowerCase('tr'));
}

function unitLabel(doc) {
  if (doc.pageCount == null) return '–';
  return `${formatNumber(doc.pageCount)} ${(doc.format ?? 'PDF') === 'PDF' ? 'sayfa' : 'bölüm'}`;
}

export function renderDocuments(ctx) {
  const { main, guarded } = ctx;
  let docs = null;
  let poll = null;
  const input = el('input', { type: 'file', accept: UPLOAD_TYPES.join(','), multiple: true, hidden: true,
    onchange: (event) => {
      upload([...event.target.files]);
      event.target.value = '';
    } });
  const uploads = el('div', { class: 'uploads' });
  const search = el('input', { class: 'input', type: 'search', placeholder: 'Belge adında ara', value: state.query,
    'aria-label': 'Belge adında ara', oninput: (event) => {
      state.query = event.target.value;
      state.page = 0;
      draw();
    } });
  const segmented = el('div', { class: 'segmented', role: 'tablist', 'aria-label': 'Durum' });
  const body = el('div', {}, el('div', { class: 'card-body grid' }, Array.from({ length: 6 }, () => el('div', { class: 'skeleton line' }))));
  const count = el('span', { class: 'muted' });

  const dropzone = el('div', { class: 'dropzone', role: 'button', tabindex: 0, 'aria-label': 'Belge yükle',
    onclick: () => input.click(), onkeydown: (event) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        input.click();
      }
    } },
  el('span', { class: 'drop-icon' }, icon('upload', 'lg')),
  el('div', {}, el('strong', {}, 'Belge yükleyin ya da buraya sürükleyin'),
    el('span', {}, 'PDF, Word (DOCX), TXT ya da Markdown · en çok 20 MB · orijinal dosya işlendikten sonra silinir')),
  el('div', { class: 'types' }, ['PDF', 'DOCX', 'TXT', 'MD'].map((type) => fileIcon(type))), input);

  main.replaceChildren(
    el('div', { class: 'page-head' },
      el('div', {}, el('h2', {}, 'Belgeler'),
        el('p', {}, 'Belgeleriniz bu sunucuda işlenir ve yalnız size görünür. Word ve metin dosyaları sayfa yerine bölümlerle kaynak gösterir.')),
      el('div', { class: 'page-actions' },
        el('button', { class: 'button secondary', type: 'button', onclick: () => refresh() }, icon('refresh'), 'Yenile'),
        el('button', { class: 'button', type: 'button', onclick: () => input.click() }, icon('upload'), 'Yükle'))),
    dropzone, uploads,
    el('section', { class: 'card' },
      el('div', { class: 'toolbar' }, el('div', { class: 'input-icon' }, icon('search', 'sm'), search), segmented,
        el('span', { class: 'spacer' }), count),
      body));

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
      const n = docs ? docs.filter((d) => (id === 'all' ? true : id === 'waiting'
        ? d.status === 'PENDING' || d.status === 'PROCESSING' : d.status === id)).length : 0;
      return el('button', { type: 'button', role: 'tab', class: state.filter === id ? 'active' : '', 'aria-selected': String(state.filter === id),
        onclick: () => {
          state.filter = id;
          state.page = 0;
          draw();
        } }, label, el('span', { class: 'n' }, String(n)));
    }));
  }

  function draw() {
    if (!docs) return;
    drawFilters();
    const visible = docs.filter(matches);
    const pages = Math.max(1, Math.ceil(visible.length / ROWS_PER_PAGE));
    state.page = Math.min(state.page, pages - 1);
    const slice = visible.slice(state.page * ROWS_PER_PAGE, (state.page + 1) * ROWS_PER_PAGE);
    count.textContent = `${formatNumber(visible.length)} / ${formatNumber(docs.length)} belge`;
    if (!docs.length) {
      body.replaceChildren(emptyState('files', 'Henüz belge yok',
        'Yukarıdaki alana bir PDF, Word, TXT ya da Markdown dosyası bırakın. Örnekler depodaki samples/ klasöründe.'));
      return;
    }
    if (!visible.length) {
      body.replaceChildren(emptyState('search', 'Eşleşen belge yok', 'Aramayı ya da durum filtresini değiştirin.'));
      return;
    }
    const rows = slice.map((doc) => el('tr', { onclick: () => details(doc) },
      el('td', {}, el('div', { class: 'doc-cell' }, fileIcon(doc.format),
        el('div', {}, el('div', { class: 'name' }, plainName(doc.fileName)),
          el('div', { class: 'sub' }, doc.status === 'FAILED' ? failureText(doc.failureReason)
            : doc.pageCount == null ? formatLabel(doc.format) : `${formatLabel(doc.format)} · ${unitLabel(doc)}`)))),
      el('td', {}, statusPill(doc.status)),
      el('td', { class: 'num hide-sm' }, unitLabel(doc)),
      el('td', { class: 'num hide-sm' }, formatSize(doc.sizeBytes)),
      el('td', { class: 'muted hide-sm', title: formatDate(doc.createdAt) }, relativeTime(doc.createdAt)),
      el('td', {}, el('div', { class: 'row-actions' },
        el('button', { class: 'icon-button', type: 'button', 'aria-label': 'Sil', title: 'Sil',
          onclick: (event) => {
            event.stopPropagation();
            remove(doc);
          } }, icon('trash', 'sm'))))));
    body.replaceChildren(
      el('div', { class: 'table-wrap' }, el('table', { class: 'table' },
        el('thead', {}, el('tr', {}, ['Belge', 'Durum', 'Sayfa / bölüm', 'Boyut', 'Yüklenme', ''].map((h, i) =>
          el('th', { class: [i === 2 || i === 3 ? 'num' : '', i >= 2 && i <= 4 ? 'hide-sm' : ''].join(' ').trim() }, h)))),
        el('tbody', {}, rows))),
      pages > 1 ? el('div', { class: 'table-foot' }, el('span', {}, `Sayfa ${state.page + 1} / ${pages}`),
        el('div', { class: 'row-actions' },
          el('button', { class: 'button secondary small', type: 'button', disabled: state.page === 0, onclick: () => { state.page--; draw(); } }, 'Önceki'),
          el('button', { class: 'button secondary small', type: 'button', disabled: state.page + 1 >= pages, onclick: () => { state.page++; draw(); } }, 'Sonraki')))
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
    if (docs.some((d) => d.status === 'PENDING' || d.status === 'PROCESSING')) poll = setTimeout(refresh, 3000);
  }

  async function upload(files) {
    for (const file of files) {
      const bar = el('span');
      const label = el('span', { class: 'muted' }, 'Yükleniyor');
      const item = el('div', { class: 'upload-item' }, fileIcon(file.name.split('.').pop()?.toUpperCase()),
        el('div', { class: 'name' }, plainName(file.name)), label, el('div', { class: 'bar' }, bar));
      uploads.prepend(item);
      if (file.size > MAX_UPLOAD) {
        item.classList.add('failed');
        label.textContent = '20 MB sınırını aşıyor';
        bar.style.width = '100%';
        continue;
      }
      try {
        await ctx.withAuth(() => api.upload(file, (ratio) => {
          bar.style.width = `${Math.round(ratio * 100)}%`;
        }));
        item.classList.add('done');
        bar.style.width = '100%';
        label.textContent = 'Yüklendi · işleniyor';
        setTimeout(() => item.remove(), 6000);
      } catch (error) {
        item.classList.add('failed');
        bar.style.width = '100%';
        label.textContent = error instanceof ApiError ? error.message : 'Bağlantı kurulamadı';
      }
    }
    refresh();
  }

  async function remove(doc, closeDrawer) {
    const ok = await confirmDialog({ title: 'Belge silinsin mi?', confirm: 'Kalıcı olarak sil',
      message: `"${plainName(doc.fileName)}" belgesi metni ve vektörleriyle birlikte kalıcı olarak silinecek. Bu işlem geri alınamaz.` });
    if (!ok) return;
    const done = await guarded(async () => {
      await api.deleteDocument(doc.id);
      return true;
    });
    if (!done) return;
    closeDrawer?.();
    toast('Belge silindi.', 'ok');
    refresh();
  }

  async function details(doc) {
    const fresh = (await guarded(() => api.getDocument(doc.id))) ?? doc;
    const close = openDrawer({
      title: plainName(fresh.fileName),
      subtitle: [fileIcon(fresh.format), formatLabel(fresh.format), statusPill(fresh.status)],
      body: [
        fresh.status === 'FAILED' ? el('div', { class: 'notice bad' }, icon('alert', 'sm'),
          el('div', {}, el('strong', {}, failureText(fresh.failureReason)), el('div', {}, 'Dosyayı kontrol edip yeniden yükleyin.'))) : null,
        fresh.status === 'PENDING' || fresh.status === 'PROCESSING' ? el('div', { class: 'notice wait' }, icon('clock', 'sm'),
          'Belge işleniyor; birkaç saniye içinde aranabilir olur.') : null,
        el('dl', { class: 'facts' },
          el('dt', {}, 'Tür'), el('dd', {}, formatLabel(fresh.format)),
          el('dt', {}, (fresh.format ?? 'PDF') === 'PDF' ? 'Sayfa' : 'Bölüm'), el('dd', {}, fresh.pageCount == null ? '–' : formatNumber(fresh.pageCount)),
          el('dt', {}, 'Parça'), el('dd', {}, fresh.chunkCount == null ? '–' : formatNumber(fresh.chunkCount)),
          el('dt', {}, 'Boyut'), el('dd', {}, formatSize(fresh.sizeBytes)),
          el('dt', {}, 'Yüklenme'), el('dd', {}, formatDate(fresh.createdAt)),
          el('dt', {}, 'Son değişiklik'), el('dd', {}, formatDate(fresh.updatedAt)),
          el('dt', {}, 'Kimlik'), el('dd', {}, el('span', { class: 'code' }, fresh.id))),
        el('div', { class: 'notice info' }, icon('shield', 'sm'),
          'Orijinal dosya işlendikten sonra silinir; yalnız metni ve vektörleri kalır. Silme, bunların hepsini kaldırır (KVKK).'),
      ],
      actions: [el('button', { class: 'button danger', type: 'button', onclick: () => remove(fresh, close) }, icon('trash'), 'Sil')],
    });
  }

  refresh();
  return () => {
    clearTimeout(poll);
    overlay.remove();
    removeEventListener('dragenter', onEnter);
    removeEventListener('dragleave', onLeave);
    removeEventListener('dragover', onOver);
    removeEventListener('drop', onDrop);
  };
}
