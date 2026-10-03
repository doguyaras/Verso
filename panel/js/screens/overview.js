// Overview: what is in the account (counts, types, latest documents), which models answer, and a quick question.
import { api } from '../api.js';
import { icon } from '../icons.js';
import { el, formatLabel, plainName } from '../render.js';
import { emptyState, fileIcon, formatNumber, relativeTime, statusPill } from '../ui.js';

const TYPES = ['PDF', 'DOCX', 'TXT', 'MD'];

function stat(label, value, note, glyph, tone = '') {
  return el('div', { class: 'card stat' },
    el('div', { class: 'stat-top' }, el('span', {}, label), el('span', { class: `stat-icon ${tone}` }, icon(glyph))),
    el('div', { class: 'stat-value' }, formatNumber(value)),
    el('div', { class: 'stat-note' }, note));
}

function typeBreakdown(docs) {
  const counts = Object.fromEntries(TYPES.map((t) => [t, docs.filter((d) => (d.format ?? 'PDF') === t).length]));
  const total = docs.length || 1;
  const bar = el('div', { class: 'stack-bar', role: 'img', 'aria-label': 'Belge türleri dağılımı' });
  for (const type of TYPES) {
    if (!counts[type]) continue;
    const part = el('span', { class: `bg-${type.toLowerCase()}`, title: `${formatLabel(type)}: ${counts[type]}` });
    part.style.width = `${(counts[type] / total) * 100}%`;
    bar.append(part);
  }
  return el('div', {}, bar, el('div', { class: 'legend' }, TYPES.map((type) =>
    el('span', {}, el('i', { class: `bg-${type.toLowerCase()}` }), `${formatLabel(type)} · ${counts[type]}`))));
}

export async function renderOverview(ctx, quiet = false) {
  const { main, guarded, navigate, identity } = ctx;
  if (!quiet) main.replaceChildren(
    el('div', { class: 'page-head' }, el('div', {}, el('h2', {}, `Merhaba${identity.preferred_username ? `, ${identity.preferred_username}` : ''}`),
      el('p', {}, 'Belgelerinizin durumu ve asistanın çalışma biçimi tek bakışta.'))),
    el('div', { class: 'grid stats' }, Array.from({ length: 4 }, () => el('div', { class: 'skeleton block' }))),
    el('div', { class: 'grid two' }, el('div', { class: 'skeleton block' }), el('div', { class: 'skeleton block' })));

  const [docs, info] = await Promise.all([guarded(() => api.listAllDocuments()), guarded(() => api.info())]);
  if (!ctx.isCurrent()) return;
  if (!docs) return;
  const ready = docs.filter((d) => d.status === 'READY');
  const waiting = docs.filter((d) => d.status === 'PENDING' || d.status === 'PROCESSING');
  const failed = docs.filter((d) => d.status === 'FAILED');
  const units = ready.reduce((n, d) => n + (d.pageCount ?? 0), 0);
  const chunks = ready.reduce((n, d) => n + (d.chunkCount ?? 0), 0);

  // While documents are being processed the counts change by themselves: look again in a few seconds.
  if (waiting.length) setTimeout(() => ctx.isCurrent() && renderOverview(ctx, true), 4000);
  const typed = main.querySelector('textarea')?.value ?? '';
  const quick = el('textarea', { class: 'textarea', rows: 3, maxlength: 1000, placeholder: 'Örnek: 25.000 TL üstü masrafları kim onaylar?' });
  quick.value = typed;
  const latest = [...docs].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 5);

  main.replaceChildren(
    el('div', { class: 'page-head' },
      el('div', {}, el('h2', {}, `Merhaba${identity.preferred_username ? `, ${identity.preferred_username}` : ''}`),
        el('p', {}, 'Belgelerinizin durumu ve asistanın çalışma biçimi tek bakışta.')),
      el('div', { class: 'page-actions' },
        el('button', { class: 'button secondary', type: 'button', onclick: () => navigate('documents') }, icon('upload'), 'Belge yükle'),
        el('button', { class: 'button', type: 'button', onclick: () => navigate('ask') }, icon('sparkles'), 'Soru sor'))),
    el('div', { class: 'grid stats' },
      stat('Belgeler', docs.length, 'Hesabınızdaki toplam belge (en çok 200)', 'files'),
      stat('Aranabilir', ready.length, `${formatNumber(units)} sayfa / bölüm · ${formatNumber(chunks)} parça`, 'checkCircle', 'ok'),
      stat('İşleniyor', waiting.length, waiting.length ? 'Birkaç saniye içinde hazır olur' : 'Bekleyen belge yok', 'clock', 'wait'),
      stat('Hata', failed.length, failed.length ? 'Ayrıntı için Belgeler ekranına bakın' : 'Sorunlu belge yok', 'alert', 'bad')),
    el('div', { class: 'grid two' },
      el('section', { class: 'card' },
        el('div', { class: 'card-head' }, el('h3', {}, 'Son yüklenenler'), el('span', { class: 'spacer' }),
          el('a', { href: '#/documents' }, 'Tümü')),
        el('div', { class: 'card-body' }, latest.length
          ? el('ul', { class: 'list' }, latest.map((doc) => el('li', {}, fileIcon(doc.format),
            el('div', { class: 'grow' }, el('span', { class: 'title' }, plainName(doc.fileName)),
              el('span', { class: 'sub', title: new Date(doc.createdAt).toLocaleString('tr-TR') },
                `${formatLabel(doc.format)} · ${relativeTime(doc.createdAt)}`)),
            statusPill(doc.status))))
          : emptyState('upload', 'Henüz belge yok', 'PDF, Word, TXT ya da Markdown yükleyerek başlayın.',
            el('button', { class: 'button', type: 'button', onclick: () => navigate('documents') }, icon('upload'), 'Belge yükle')))),
      el('div', { class: 'grid' },
        el('section', { class: 'card' },
          el('div', { class: 'card-head' }, el('h3', {}, 'Hızlı soru')),
          el('form', { class: 'card-body grid', onsubmit: (event) => {
            event.preventDefault();
            const question = quick.value.trim();
            if (question) navigate('ask', { question });
          } }, quick,
          el('button', { class: 'button block', type: 'submit', disabled: ready.length === 0 }, icon('send'),
            ready.length ? 'Sor' : 'Önce bir belge yükleyin'))),
        el('section', { class: 'card' },
          el('div', { class: 'card-head' }, el('h3', {}, 'Belge türleri')),
          el('div', { class: 'card-body' }, typeBreakdown(docs))),
        info ? el('section', { class: 'card' },
          el('div', { class: 'card-head' }, el('h3', {}, 'Asistan')),
          el('div', { class: 'card-body' }, el('dl', { class: 'facts' },
            el('dt', {}, 'Mod'), el('dd', {}, info.mode === 'local' ? 'Yerel: hiçbir şey sunucudan çıkmaz' : 'Bulut: soru ve pasajlar sağlayıcıya gider'),
            el('dt', {}, 'Sohbet modeli'), el('dd', {}, el('span', { class: 'code' }, info.chatModel)),
            el('dt', {}, 'Embedding'), el('dd', {}, el('span', { class: 'code' }, info.embeddingModel))))) : null)));
}
