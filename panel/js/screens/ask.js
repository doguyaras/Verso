// Questions as a conversation: the answer with its citations, the sources, and what kind of answer it is (outcome).
// The conversation lives in this module's memory only; a reload starts a new one.
import { api, ApiError } from '../api.js';
import { icon } from '../icons.js';
import { answerParts, el, sourceLabel } from '../render.js';
import { toast } from '../ui.js';

const MAX_CHARS = 1000;
const SUGGESTIONS = [
  'Beş yıldan az hizmeti olan çalışan yılda kaç gün izin kullanır?',
  'Şirket laptopu kaybolursa ne kadar sürede bildirmem gerekir?',
  '25.000 TL üstü masrafları kim onaylar?',
  'Uzaktan çalışmada çekirdek saatler nedir?',
];
const conversation = []; // { question, result | error, ms }

function citationChip(citation) {
  return el('span', { class: 'cite', title: sourceLabel(citation) }, String(citation.number));
}

function outcomeBadge(result) {
  if (result.outcome === 'ANSWERED' || (result.outcome == null && result.found)) {
    return el('span', { class: 'pill ok' }, icon('checkCircle', 'sm'), 'Kaynaklı cevap');
  }
  if (result.outcome === 'UNCITED') return el('span', { class: 'pill wait' }, icon('alert', 'sm'), 'Kaynak gösterilemedi');
  return el('span', { class: 'pill neutral' }, icon('search', 'sm'), 'Belgelerde bulunamadı');
}

function answerCard(entry, retry) {
  if (entry.error) {
    return el('div', { class: 'answer-card' },
      el('div', { class: 'notice bad' }, icon('alert', 'sm'), el('div', {}, entry.error)),
      el('div', { class: 'answer-foot' }, el('span', { class: 'spacer' }),
        el('button', { class: 'button secondary small', type: 'button', onclick: () => retry(entry.question) }, icon('refresh', 'sm'), 'Tekrar dene')));
  }
  const { result } = entry;
  const text = el('div', { class: 'answer-text' }, answerParts(result.answer, result.citations)
    .map((part) => (part.citation ? citationChip(part.citation) : part.text)));
  const sources = result.citations.length
    ? el('ol', { class: 'sources' }, result.citations.map((c) => el('li', {}, el('span', { class: 'n' }, String(c.number)),
      el('span', { class: 'grow' }, c.fileName), el('span', { class: 'where' }, sourceLabel(c).slice(c.fileName.length + 2)))))
    : null;
  const notice = result.outcome === 'UNCITED'
    ? el('div', { class: 'notice wait' }, icon('alert', 'sm'), 'Bu cevap belgelerinizdeki bir kaynağa bağlanamadı; doğruluğunu kontrol edin.')
    : result.outcome === 'NOT_FOUND'
      ? el('div', { class: 'notice info' }, icon('info', 'sm'), 'İlgili bir pasaj bulunamadı. Soruyu farklı sözcüklerle sormayı ya da belgeyi yüklemeyi deneyin.')
      : null;
  return el('div', { class: 'answer-card' },
    el('div', { class: 'answer-head' }, outcomeBadge(result)), text, notice, sources,
    el('div', { class: 'answer-foot' },
      el('span', {}, `${result.mode === 'cloud' ? 'Bulut' : 'Yerel'} · ${result.model}`),
      el('span', {}, `· ${Math.round(entry.ms / 1000)} sn`), el('span', { class: 'spacer' }),
      el('button', { class: 'button ghost small', type: 'button', onclick: async () => {
        try {
          await navigator.clipboard.writeText(result.answer);
          toast('Cevap kopyalandı.', 'ok');
        } catch {
          toast('Kopyalanamadı.');
        }
      } }, icon('copy', 'sm'), 'Kopyala')));
}

function botMessage(content) {
  return el('div', { class: 'msg bot' }, el('span', { class: 'avatar-bot' }, icon('sparkles', 'sm')), content);
}

function userMessage(question) {
  return el('div', { class: 'msg user' }, el('div', { class: 'bubble' }, question));
}

export function renderAsk(ctx) {
  const { main } = ctx;
  const thread = el('div', { class: 'thread', 'aria-live': 'polite' });
  const field = el('textarea', { rows: 1, maxlength: MAX_CHARS, placeholder: 'Belgelerinize bir soru sorun…', 'aria-label': 'Soru' });
  const counter = el('span', {}, `0 / ${MAX_CHARS}`);
  const send = el('button', { class: 'button', type: 'submit', 'aria-label': 'Gönder' }, icon('send'), 'Sor');
  let busy = false;
  let ticker = null;

  const resize = () => {
    field.style.height = 'auto';
    field.style.height = `${Math.min(field.scrollHeight, 200)}px`;
    counter.textContent = `${field.value.length} / ${MAX_CHARS}`;
  };
  field.addEventListener('input', resize);
  field.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      form.requestSubmit();
    }
  });

  const form = el('form', { class: 'composer', onsubmit: (event) => {
    event.preventDefault();
    ask(field.value.trim());
  } }, field, send,
  el('div', { class: 'meta' }, el('span', {}, 'Enter gönderir · Shift+Enter yeni satır'), counter));

  function welcome() {
    return el('div', { class: 'welcome' },
      el('span', { class: 'empty-art' }, icon('sparkles')),
      el('h2', {}, 'Belgelerinize sorun'),
      el('p', {}, 'Cevaplar yalnız sizin belgelerinizden gelir ve her bilginin yanında kaynağı (belge, sayfa ya da bölüm) durur. Belgelerde yoksa asistan uydurmaz, "bulunamadı" der.'),
      el('div', { class: 'suggestions' }, SUGGESTIONS.map((s) =>
        el('button', { class: 'suggestion', type: 'button', onclick: () => ask(s) }, icon('chat', 'sm'), s))));
  }

  function drawThread() {
    if (!conversation.length) {
      thread.replaceChildren(welcome());
      return;
    }
    thread.replaceChildren(...conversation.flatMap((entry) => [userMessage(entry.question), botMessage(answerCard(entry, ask))]));
  }

  async function ask(question) {
    if (!question || busy) return;
    busy = true;
    send.disabled = true;
    field.value = '';
    resize();
    if (!conversation.length) thread.replaceChildren();
    const started = Date.now();
    const status = el('span', {}, 'Düşünüyor…');
    const pending = botMessage(el('div', { class: 'answer-card' }, el('div', { class: 'typing' }, el('i'), el('i'), el('i'), status)));
    thread.append(userMessage(question), pending);
    pending.scrollIntoView({ behavior: 'smooth', block: 'end' });
    ticker = setInterval(() => {
      const seconds = Math.round((Date.now() - started) / 1000);
      status.textContent = seconds < 12 ? `Düşünüyor… ${seconds} sn` : `Düşünüyor… ${seconds} sn · yerel modelde cevap 30 sn'yi bulabilir`;
    }, 1000);
    let entry;
    try {
      const result = await ctx.withAuth(() => api.ask(question));
      entry = { question, result, ms: Date.now() - started };
    } catch (error) {
      entry = { question, error: error instanceof ApiError ? error.message : 'Bağlantı kurulamadı.', ms: Date.now() - started };
    } finally {
      clearInterval(ticker);
      busy = false;
      send.disabled = false;
      ctx.showMode();
    }
    if (!entry) return;
    conversation.push(entry);
    if (!ctx.isCurrent()) return;
    pending.replaceWith(botMessage(answerCard(entry, ask)));
    field.focus();
  }

  main.replaceChildren(el('div', { class: 'chat' }, thread, form));
  drawThread();
  if (ctx.params?.question) ask(ctx.params.question);
  else field.focus();
  return () => clearInterval(ticker);
}
