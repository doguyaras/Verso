// Overview (ADR-0017, home as "ask" in ADR-0018): the signature and a large question box in the middle of the screen
// with the installation's suggested questions, three small cards (searchable documents, setup phase, mode), the
// first-run checklist until it is done, then the latest documents and the open conversation's questions (earlier ones
// are in "Geçmiş", ADR-0025).
import { api } from '../api.js';
import { CONFIG } from '../config.js';
import { icon } from '../icons.js';
import { countUp } from '../motion.js';
import { el, fill, formatLabel, greeting, plainName } from '../render.js';
import { signature } from '../signature.js';
import { conversation, prefs } from '../store.js';
import { PHASES } from './setup.js';
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
    el('div', {}, el('h2', { id: 'onboarding-title' }, `${CONFIG.deployment.productName} ile başlayın`),
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

function recentQuestions(ctx) {
  const latest = conversation.filter((e) => e.result).slice(-4).reverse();
  if (!latest.length) {
    return el('p', { class: 'muted' }, 'Bu oturumda henüz soru sorulmadı. Önceki konuşmalarınız ',
      el('button', { class: 'link-button', type: 'button', onclick: ctx.openHistory }, 'Geçmiş'), '’te.');
  }
  return el('ul', { class: 'list' }, latest.map((entry) => el('li', {},
    el('span', { class: 'file-icon sm', 'aria-hidden': 'true' }, icon('chat', 'sm')),
    el('div', { class: 'grow' }, el('a', { class: 'title', href: '#/ask' }, entry.question),
      el('span', { class: 'sub' }, `${entry.result.citations.length ? `${entry.result.citations.length} kaynak` : 'kaynak yok'} · ${relativeTime(entry.at)}`)),
    entry.result.outcome === 'ANSWERED' ? el('span', { class: 'pill ok' }, 'Kaynaklı')
      : entry.result.outcome === 'UNCITED' ? el('span', { class: 'pill wait' }, 'Kaynaksız') : el('span', { class: 'pill neutral' }, 'Bulunamadı'))));
}

function hero(ctx, ready) {
  const field = el('textarea', { class: 'hero-input', id: 'quick-question', rows: 1, maxlength: 1000,
    placeholder: 'Sisteminizle ilgili bir soru sorun…', 'aria-label': 'Soru' });
  const submit = () => {
    const question = field.value.trim();
    if (question) ctx.navigate('ask', { question });
  };
  field.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      submit();
    }
  });
  field.addEventListener('input', () => {
    field.style.height = 'auto';
    field.style.height = `${Math.min(field.scrollHeight, 160)}px`;
  });
  return el('section', { class: 'hero', 'aria-labelledby': 'hero-title' },
    el('h1', { id: 'hero-title', class: 'sr-only' }, 'Genel bakış'),
    signature({ height: 86, ink: 'var(--signature-ink)' }),
    el('p', { class: 'hero-caption' }, `${greeting(new Date().getHours())}${ctx.identity.preferred_username ? `, ${ctx.identity.preferred_username}` : ''}. Kurumunuza uyum sağlayan yapay zekâ.`),
    el('form', { class: 'hero-ask', onsubmit: (event) => {
      event.preventDefault();
      submit();
    } }, field,
    el('button', { class: 'button icon-only', type: 'submit', 'aria-label': 'Sor', disabled: ready === 0 }, icon('send'))),
    ready === 0
      ? el('p', { class: 'hero-hint' }, 'Soru sormak için önce bir belge ekleyin.')
      : el('div', { class: 'hero-chips' }, CONFIG.deployment.suggestions.map((question) =>
        el('button', { class: 'chip', type: 'button', onclick: () => ctx.navigate('ask', { question }) }, question))));
}

export async function renderOverview(ctx, quiet = false) {
  const { main, guarded, navigate } = ctx;
  if (!quiet) fill(main, el('div', { class: 'hero' }, el('div', { class: 'skeleton block' })),
    el('div', { class: 'grid stats three' }, Array.from({ length: 3 }, () => el('div', { class: 'skeleton block' }))));

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
  const latest = [...docs].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 5);
  const local = info?.mode !== 'cloud';
  const index = Math.max(0, PHASES.findIndex((phase) => phase.id === CONFIG.deployment.phase));

  fill(main,
    hero(ctx, ready.length),
    el('div', { class: 'grid stats three' },
      stat('Sorulabilir belge', ready.length, failed.length ? `${failed.length} belgede hata var` : waiting.length ? `${waiting.length} belge işleniyor` : `${formatNumber(units)} sayfa / bölüm`, failed.length ? 'bad' : 'ok', '#/documents'),
      el('a', { class: 'card stat stat-link', href: '#/setup' },
        el('span', { class: 'stat-label' }, el('span', { class: 'dot accent' }), 'Kurulum'),
        el('span', { class: 'stat-value small' }, PHASES[index].title),
        el('div', { class: 'phase-line', 'aria-hidden': 'true' }, PHASES.map((phase, i) =>
          el('span', { class: `phase-dot ${i < index ? 'done' : i === index ? 'now' : ''}` })))),
      el('div', { class: 'card stat' },
        el('span', { class: 'stat-label' }, el('span', { class: `dot ${local ? 'ok' : 'wait'}` }), 'Çalışma modu'),
        el('span', { class: 'stat-value small' }, local ? 'Yerel' : 'Bulut'),
        el('span', { class: 'stat-note' }, info ? `${info.chatModel} · ${local ? 'veri bu sunucuda kalır' : 'pasajlar sağlayıcıya gider'}` : '–'))),
    onboarding(ctx, docs, () => renderOverview(ctx, true)),
    el('div', { class: 'grid halves' },
      el('section', { class: 'card', 'aria-labelledby': 'recent-docs' },
        el('div', { class: 'card-head' }, el('h2', { id: 'recent-docs' }, 'Son eklenen belgeler'), el('span', { class: 'spacer' }),
          el('a', { href: '#/documents' }, 'Tümünü gör')),
        el('div', { class: 'card-body' }, latest.length
          ? el('ul', { class: 'list' }, latest.map((doc) => el('li', {}, fileIcon(doc.format),
            el('div', { class: 'grow' }, el('span', { class: 'title' }, plainName(doc.fileName)),
              el('span', { class: 'sub', title: new Date(doc.createdAt).toLocaleString('tr-TR') },
                `${formatLabel(doc.format)} · ${relativeTime(doc.createdAt)}`)),
            statusPill(doc.status))))
          : emptyState('upload', 'Henüz belge yok', 'PDF, Word, TXT ya da Markdown ekleyin; içerik yalnız bu sunucuda işlenir.',
            el('button', { class: 'button', type: 'button', onclick: () => navigate('documents', { upload: true }) }, icon('upload'), 'İlk belgenizi ekleyin')))),
      el('section', { class: 'card', 'aria-labelledby': 'recent-questions' },
        el('div', { class: 'card-head' }, el('h2', { id: 'recent-questions' }, 'Bu oturumdaki sorular'), el('span', { class: 'spacer' }),
          el('a', { href: '#/ask' }, 'Sohbete git')),
        el('div', { class: 'card-body' }, recentQuestions(ctx)))));
  const field = main.querySelector('#quick-question');
  field.value = typed;
}
