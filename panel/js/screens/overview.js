// Overview (ADR-0017): a first-run checklist until it is done, four counts that link to their list, the latest
// documents, this tab's latest questions, a quick question and the assistant's mode. It fits one screen.
import { api } from '../api.js';
import { icon } from '../icons.js';
import { countUp } from '../motion.js';
import { el, fill, formatLabel, greeting, plainName } from '../render.js';
import { conversation, prefs } from '../store.js';
import { emptyState, fileIcon, formatNumber, relativeTime, statusPill } from '../ui.js';

const TYPES = ['PDF', 'DOCX', 'TXT', 'MD'];
const SVG = 'http://www.w3.org/2000/svg';

function countUpValue(value) {
  const node = el('span', { class: 'stat-value' });
  countUp(node, value, formatNumber);
  return node;
}

function stat(label, value, note, tone, href) {
  return el('a', { class: 'card stat stat-link', href },
    el('span', { class: 'stat-label' }, el('span', { class: `dot ${tone}` }), label),
    countUpValue(value),
    el('span', { class: 'stat-note' }, note));
}

/** The progress ring of the checklist (SVG built with createElementNS: no markup strings under Trusted Types). */
function ring(done, total) {
  const svg = document.createElementNS(SVG, 'svg');
  svg.setAttribute('viewBox', '0 0 52 52');
  svg.setAttribute('aria-hidden', 'true');
  const length = 2 * Math.PI * 22;
  for (const [cls, offset] of [['track', 0], ['value', length * (1 - done / total)]]) {
    const circle = document.createElementNS(SVG, 'circle');
    for (const [k, v] of Object.entries({ class: cls, cx: 26, cy: 26, r: 22, 'stroke-dasharray': length, 'stroke-dashoffset': offset })) {
      circle.setAttribute(k, String(v));
    }
    svg.append(circle);
  }
  return el('div', { class: 'ring' }, svg, el('b', {}, `${done}/${total}`));
}

function onboarding(ctx, docs, rerender) {
  const state = prefs.onboarding();
  const operator = ctx.roles.includes('verso-operator');
  const steps = [
    { done: docs.length > 0, label: 'Bir belge yükleyin', run: () => ctx.navigate('documents', { upload: true }) },
    { done: state.asked, label: 'İlk sorunuzu sorun', run: () => ctx.navigate('ask', { focus: true }) },
    { done: state.sawMode, label: 'Gizlilik modunu inceleyin', run: () => (operator ? ctx.navigate('system') : (prefs.markOnboarding({ sawMode: true }), rerender())) },
  ];
  const done = steps.filter((s) => s.done).length;
  if (state.dismissed || done === steps.length) return null;
  return el('section', { class: 'card onboarding', 'aria-labelledby': 'onboarding-title' },
    ring(done, steps.length),
    el('div', {}, el('h2', { id: 'onboarding-title' }, 'Verso\'yu kullanmaya başlayın'),
      el('p', {}, 'Üç kısa adımda belgelerinize kaynaklı cevap almaya hazır olursunuz.'),
      el('ol', { class: 'steps' }, steps.map((s) => el('li', { class: s.done ? 'done' : '' },
        el('button', { type: 'button', onclick: s.run, 'aria-label': `${s.label}${s.done ? ' (tamamlandı)' : ''}` },
          el('span', { class: 'check' }, s.done ? icon('check') : null), s.label))))),
    el('button', { class: 'icon-button', type: 'button', 'aria-label': 'Kontrol listesini gizle', title: 'Gizle',
      onclick: () => {
        prefs.markOnboarding({ dismissed: true });
        rerender();
      } }, icon('x', 'sm')));
}

function typeBreakdown(docs) {
  const counts = Object.fromEntries(TYPES.map((t) => [t, docs.filter((d) => (d.format ?? 'PDF') === t).length]));
  const total = docs.length || 1;
  const bar = el('div', { class: 'stack-bar', role: 'img', 'aria-label': TYPES.map((t) => `${formatLabel(t)} ${counts[t]}`).join(', ') });
  for (const type of TYPES) {
    if (!counts[type]) continue;
    const part = el('span', { class: `bg-${type.toLowerCase()}`, title: `${formatLabel(type)}: ${counts[type]}` });
    part.style.width = `${(counts[type] / total) * 100}%`;
    bar.append(part);
  }
  return el('div', {}, bar, el('div', { class: 'legend' }, TYPES.map((type) =>
    el('span', {}, el('i', { class: `bg-${type.toLowerCase()}` }), `${formatLabel(type)} · ${counts[type]}`))));
}

function recentQuestions(ctx) {
  const latest = conversation.filter((e) => e.result).slice(-4).reverse();
  if (!latest.length) {
    return el('p', { class: 'muted' }, 'Bu oturumda henüz soru sorulmadı. Sorular yalnız bu sekmenin belleğinde durur.');
  }
  return el('ul', { class: 'list' }, latest.map((entry) => el('li', {},
    el('span', { class: 'file-icon sm', 'aria-hidden': 'true' }, icon('chat', 'sm')),
    el('div', { class: 'grow' }, el('a', { class: 'title', href: '#/ask' }, entry.question),
      el('span', { class: 'sub' }, `${entry.result.citations.length ? `${entry.result.citations.length} kaynak` : 'kaynak yok'} · ${relativeTime(entry.at)}`)),
    entry.result.outcome === 'ANSWERED' ? el('span', { class: 'pill ok' }, 'Kaynaklı')
      : entry.result.outcome === 'UNCITED' ? el('span', { class: 'pill wait' }, 'Kaynaksız') : el('span', { class: 'pill neutral' }, 'Bulunamadı'))));
}

export async function renderOverview(ctx, quiet = false) {
  const { main, guarded, navigate, identity } = ctx;
  const name = identity.preferred_username;
  const head = (actions) => el('div', { class: 'page-head' },
    el('div', {}, el('h1', {}, `${greeting(new Date().getHours())}${name ? `, ${name}` : ''}`),
      el('p', {}, 'Belgelerinizin durumu, son sorularınız ve asistanın çalışma biçimi.')),
    actions);
  if (!quiet) fill(main, head(null),
    el('div', { class: 'grid stats' }, Array.from({ length: 4 }, () => el('div', { class: 'skeleton block' }))),
    el('div', { class: 'grid split' }, el('div', { class: 'skeleton block' }), el('div', { class: 'skeleton block' })));

  const [docs, info] = await Promise.all([guarded(() => api.listAllDocuments()), guarded(() => api.info())]);
  if (!ctx.isCurrent()) return;
  if (!docs) return;
  ctx.setCount('documents', docs.length);
  const ready = docs.filter((d) => d.status === 'READY');
  const waiting = docs.filter((d) => d.status === 'PENDING' || d.status === 'PROCESSING');
  const failed = docs.filter((d) => d.status === 'FAILED');
  const units = ready.reduce((n, d) => n + (d.pageCount ?? 0), 0);

  // While documents are being processed the counts change by themselves: look again in a few seconds.
  if (waiting.length) setTimeout(() => ctx.isCurrent() && renderOverview(ctx, true), 4000);
  const typed = main.querySelector('#quick-question')?.value ?? '';
  const quick = el('textarea', { class: 'textarea', id: 'quick-question', rows: 3, maxlength: 1000,
    placeholder: 'Örnek: 25.000 TL üstü masrafları kim onaylar?', 'aria-label': 'Hızlı soru' });
  quick.value = typed;
  const latest = [...docs].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 5);
  const local = info?.mode !== 'cloud';

  fill(main,
    head(el('div', { class: 'page-actions' },
      el('button', { class: 'button secondary', type: 'button', onclick: () => navigate('documents', { upload: true }) }, icon('upload'), 'Belge yükle'),
      el('button', { class: 'button', type: 'button', onclick: () => navigate('ask', { focus: true }) }, icon('chat'), 'Soru sor'))),
    onboarding(ctx, docs, () => renderOverview(ctx, true)),
    el('div', { class: 'grid stats' },
      stat('Belgeler', docs.length, 'Hesabınızdaki toplam (en çok 200)', 'accent', '#/documents'),
      stat('Aranabilir', ready.length, `${formatNumber(units)} sayfa / bölüm`, 'ok', '#/documents'),
      stat('İşleniyor', waiting.length, waiting.length ? 'Birkaç saniye içinde hazır' : 'Bekleyen belge yok', 'wait', '#/documents'),
      stat('Hata', failed.length, failed.length ? 'Ayrıntı Belgeler ekranında' : 'Sorunlu belge yok', failed.length ? 'bad' : '', '#/documents')),
    el('div', { class: 'grid split' },
      el('div', { class: 'grid' },
        el('section', { class: 'card', 'aria-labelledby': 'recent-docs' },
          el('div', { class: 'card-head' }, el('h2', { id: 'recent-docs' }, 'Son yüklenenler'), el('span', { class: 'spacer' }),
            el('a', { href: '#/documents' }, 'Tümünü gör')),
          el('div', { class: 'card-body' }, latest.length
            ? el('ul', { class: 'list' }, latest.map((doc) => el('li', {}, fileIcon(doc.format),
              el('div', { class: 'grow' }, el('span', { class: 'title' }, plainName(doc.fileName)),
                el('span', { class: 'sub', title: new Date(doc.createdAt).toLocaleString('tr-TR') },
                  `${formatLabel(doc.format)} · ${relativeTime(doc.createdAt)}`)),
              statusPill(doc.status))))
            : emptyState('upload', 'Henüz belge yok', 'PDF, Word, TXT ya da Markdown yükleyin; içerik yalnız bu sunucuda işlenir.',
              el('button', { class: 'button', type: 'button', onclick: () => navigate('documents', { upload: true }) }, icon('upload'), 'İlk belgenizi yükleyin')))),
        el('section', { class: 'card', 'aria-labelledby': 'recent-questions' },
          el('div', { class: 'card-head' }, el('h2', { id: 'recent-questions' }, 'Bu oturumdaki sorular'), el('span', { class: 'spacer' }),
            el('a', { href: '#/ask' }, 'Sohbete git')),
          el('div', { class: 'card-body' }, recentQuestions(ctx)))),
      el('div', { class: 'grid' },
        el('section', { class: 'card', 'aria-labelledby': 'quick-title' },
          el('div', { class: 'card-head' }, el('h2', { id: 'quick-title' }, 'Hızlı soru')),
          el('form', { class: 'card-body grid', onsubmit: (event) => {
            event.preventDefault();
            const question = quick.value.trim();
            if (question) navigate('ask', { question });
          } }, quick,
          el('button', { class: 'button block', type: 'submit', disabled: ready.length === 0 }, icon('send'),
            ready.length ? 'Sor' : 'Önce bir belge yükleyin'))),
        info ? el('section', { class: 'card', 'aria-labelledby': 'assistant-title' },
          el('div', { class: 'card-head' }, el('h2', { id: 'assistant-title' }, 'Asistan'), el('span', { class: 'spacer' }),
            el('span', { class: `pill ${local ? 'ok' : 'wait'}` }, icon(local ? 'shield' : 'cloud', 'sm'), local ? 'Yerel mod' : 'Bulut modu')),
          el('div', { class: 'card-body grid' },
            el('p', { class: 'muted' }, local ? 'Belgeler, sorular ve cevaplar bu sunucudan çıkmaz.' : 'Soru ve ilgili pasajlar bulut sağlayıcısına gider (KVKK md. 9).'),
            el('dl', { class: 'facts' },
              el('dt', {}, 'Sohbet modeli'), el('dd', {}, el('span', { class: 'code' }, info.chatModel)),
              el('dt', {}, 'Embedding'), el('dd', {}, el('span', { class: 'code' }, info.embeddingModel))))) : null,
        docs.length ? el('section', { class: 'card', 'aria-labelledby': 'types-title' },
          el('div', { class: 'card-head' }, el('h2', { id: 'types-title' }, 'Belge türleri')),
          el('div', { class: 'card-body' }, typeBreakdown(docs))) : null)));
}
