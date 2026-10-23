// Questions as a conversation (ADR-0017): the answer with citation buttons that preview the cited passage, source cards
// grouped by document, an honest state for each outcome, and a wait that says how long it has been and may be.
// The conversation lives in this tab's memory only (store.js); a reload starts a new one.
import { api, ApiError, lastMode } from '../api.js';
import { CONFIG } from '../config.js';
import { icon, logo } from '../icons.js';
import { answerParts, answerWithSources, el, formatSeconds, groupSources, plainName, sourceLabel } from '../render.js';
import { conversation, prefs } from '../store.js';
import { pulse, wordSpans } from '../motion.js';
import { fileIcon, toast } from '../ui.js';

const MAX_CHARS = 1000;
// Suggested questions come from the installation (config.js): each client's own systems and documents.
const SUGGESTIONS = CONFIG.deployment.suggestions;
// Nielsen's limits: nothing under 1 s, a skeleton up to 10 s, then the elapsed time and what to expect.
const SHOW_WAIT_MS = 1000;
const SLOW_MS = 10000;

/** The [n] marker as a button; hover, focus or a click shows the source and its passage (NotebookLM-style). */
function citationButton(citation, onPick, onHover) {
  const where = sourceLabel(citation).slice(citation.fileName.length + 2);
  const pop = el('span', { class: 'cite-pop', role: 'tooltip', id: `cite-pop-${citation.number}-${Math.random().toString(36).slice(2, 8)}` },
    el('span', { class: 'src' }, fileIcon(citation.fileName.split('.').pop()?.toUpperCase(), 'sm'),
      el('span', { class: 'fname' }, plainName(citation.fileName)), el('span', { class: 'where' }, where)),
    citation.excerpt ? el('span', { class: 'quote' }, citation.excerpt) : null);
  const button = el('button', { class: 'cite', type: 'button', 'aria-describedby': pop.id, 'aria-expanded': 'false',
    'aria-label': `Kaynak ${citation.number}: ${plainName(citation.fileName)}, ${where}`,
    onclick: () => {
      const open = button.getAttribute('aria-expanded') !== 'true';
      button.setAttribute('aria-expanded', String(open));
      onPick(citation);
    },
    onblur: () => {
      button.setAttribute('aria-expanded', 'false');
      onHover(citation, false);
    },
    onmouseenter: () => {
      place(button);
      onHover(citation, true);
    },
    onmouseleave: () => onHover(citation, false) }, String(citation.number), pop);
  button.addEventListener('focus', () => {
    place(button);
    onHover(citation, true);
  });
  return button;
}

/** Keeps the preview inside the column and the window: right or left near the edges, below when the top is near. */
function place(button) {
  const box = button.getBoundingClientRect();
  button.classList.toggle('pop-below', box.top < 240);
  const column = button.closest('.answer')?.getBoundingClientRect() ?? { left: 0, right: innerWidth };
  button.classList.toggle('pop-left', box.left - column.left < 170);
  button.classList.toggle('pop-right', column.right - box.right < 170 && box.left - column.left >= 170);
}

function outcomeBadge(result) {
  if (result.outcome === 'ANSWERED' || (result.outcome == null && result.found)) {
    return el('span', { class: 'pill ok' }, icon('checkCircle', 'sm'), 'Kaynaklı cevap');
  }
  if (result.outcome === 'UNCITED') return el('span', { class: 'pill wait' }, icon('alert', 'sm'), 'Kaynak gösterilemedi');
  return el('span', { class: 'pill neutral' }, icon('search', 'sm'), 'Belgelerde bulunamadı');
}

function sourceCards(citations) {
  return el('div', { class: 'sources', role: 'list', 'aria-label': 'Kaynaklar' }, groupSources(citations).map((group) =>
    el('div', { class: 'source', role: 'listitem', 'data-numbers': group.items.map((c) => c.number).join(' ') },
      fileIcon(group.fileName.split('.').pop()?.toUpperCase(), 'sm'),
      el('span', { class: 'fname', title: plainName(group.fileName) }, plainName(group.fileName)),
      el('span', { class: 'where' }, group.items.map((c) => [el('span', { class: 'n' }, String(c.number)),
        sourceLabel(c).slice(c.fileName.length + 2)]).flat()))));
}

function modeMeta(result, ms) {
  const local = (result.mode ?? lastMode) !== 'cloud';
  return el('span', { class: `meta ${local ? 'local' : ''}`, title: local ? 'Soru ve cevap bu sunucudan çıkmadı.'
    : 'Soru ve ilgili pasajlar bulut sağlayıcısına gönderildi (KVKK md. 9).' },
  icon(local ? 'shield' : 'cloud', 'sm'), `${local ? 'Yerel' : 'Bulut'} · ${result.model} · ${formatSeconds(ms)}`);
}

function answerView(entry, actions) {
  if (entry.cancelled) {
    return el('div', { class: 'answer' }, el('div', { class: 'notice neutral' }, icon('stop', 'sm'),
      el('div', {}, 'Bekleme durduruldu.', el('div', { class: 'actions' },
        el('button', { class: 'button secondary sm', type: 'button', onclick: () => actions.ask(entry.question) }, icon('refresh', 'sm'), 'Tekrar sor')))));
  }
  if (entry.error) {
    return el('div', { class: 'answer' }, el('div', { class: 'notice bad', role: 'alert' }, icon('alert', 'sm'),
      el('div', {}, entry.error, el('div', { class: 'actions' },
        el('button', { class: 'button secondary sm', type: 'button', onclick: () => actions.ask(entry.question) }, icon('refresh', 'sm'), 'Tekrar dene')))));
  }
  const { result } = entry;
  const sources = result.citations.length ? sourceCards(result.citations) : null;
  const cardOf = (citation) => sources?.querySelector(`[data-numbers~="${citation.number}"]`);
  const flash = (citation) => {
    const card = cardOf(citation);
    if (!card) return;
    card.classList.add('flash');
    card.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    pulse(card);
    setTimeout(() => card.classList.remove('flash'), 1200);
  };
  const hover = (citation, on) => cardOf(citation)?.classList.toggle('hl', on);
  // A fresh answer appears word by word; one drawn again (a later visit) appears at once.
  const counter = entry.fresh ? { i: 0 } : null;
  const text = (value) => (counter ? wordSpans(value, counter) : value);
  const notFound = result.outcome === 'NOT_FOUND' || (result.outcome == null && !result.found && !result.citations.length);
  return el('div', { class: 'answer' },
    el('h3', { class: 'sr-only' }, 'Verso cevapladı'),
    el('div', { class: 'answer-head' }, outcomeBadge(result)),
    notFound
      ? el('div', { class: 'notice neutral' }, icon('search', 'sm'), el('div', {},
        el('strong', {}, 'Yüklediğiniz belgelerde bu sorunun cevabını bulamadım.'),
        el('div', {}, 'Asistan tahmin yürütmez. Soruyu başka sözcüklerle sorabilir ya da ilgili belgeyi yükleyebilirsiniz.'),
        el('div', { class: 'actions' },
          el('button', { class: 'button secondary sm', type: 'button', onclick: () => actions.edit(entry.question) }, icon('edit', 'sm'), 'Soruyu düzenle'),
          el('button', { class: 'button secondary sm', type: 'button', onclick: () => actions.navigate('documents', { upload: true }) }, icon('upload', 'sm'), 'Belge yükle'))))
      : el('div', { class: `answer-text${counter ? ' reveal' : ''}` }, answerParts(result.answer, result.citations)
        .map((part) => (part.citation ? citationButton(part.citation, flash, hover) : text(part.text))).flat()),
    result.outcome === 'UNCITED'
      ? el('div', { class: 'notice wait' }, icon('alert', 'sm'), 'Bu cevabı belgelerinizdeki bir kaynağa bağlayamadım; kullanmadan önce doğrulayın.')
      : null,
    sources,
    el('div', { class: 'answer-foot' }, modeMeta(result, entry.ms), el('span', { class: 'spacer' }),
      notFound ? null : el('button', { class: 'button ghost sm', type: 'button', onclick: async () => {
        try {
          await navigator.clipboard.writeText(answerWithSources(result));
          toast('Cevap kaynaklarıyla kopyalandı.', 'ok');
        } catch {
          toast('Kopyalanamadı.');
        }
      } }, icon('copy', 'sm'), 'Kopyala'),
      el('button', { class: 'button ghost sm', type: 'button', onclick: () => actions.edit(entry.question) }, icon('edit', 'sm'), 'Düzenle')));
}

function userTurn(question) {
  return el('div', { class: 'turn-user' }, el('h3', { class: 'sr-only' }, 'Siz sordunuz'), el('div', { class: 'bubble' }, question));
}

function botTurn(content) {
  return el('div', { class: 'turn-bot' }, logo(30), content);
}

export function renderAsk(ctx) {
  const { main } = ctx;
  const thread = el('div', { class: 'thread', role: 'log', 'aria-live': 'off', 'aria-label': 'Soru-cevap geçmişi', tabindex: 0 });
  // Only the start and the end of an answer are announced, never the answer body (screen readers would read it twice).
  const announcer = el('div', { class: 'sr-only', role: 'status', 'aria-live': 'polite', 'aria-atomic': 'true' });
  const field = el('textarea', { rows: 1, maxlength: MAX_CHARS, placeholder: 'Belgelerinize bir soru sorun…', 'aria-label': 'Soru' });
  const counter = el('span', { class: 'tabular' }, `0 / ${MAX_CHARS}`);
  const send = el('button', { class: 'button icon-only', type: 'submit', 'aria-label': 'Gönder' }, icon('send'));
  let busy = null; // the AbortController of the question in flight
  let ticker = null;

  const resize = () => {
    field.style.height = 'auto';
    field.style.height = `${Math.min(field.scrollHeight, 200)}px`;
    counter.textContent = `${field.value.length} / ${MAX_CHARS}`;
  };
  field.addEventListener('input', resize);
  field.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      if (!busy) form.requestSubmit(); // Enter never stops a running question; only the stop button does
    }
  });

  const form = el('form', { class: 'composer', onsubmit: (event) => {
    event.preventDefault();
    if (busy) busy.abort();
    else ask(field.value.trim());
  } }, field, send,
  el('div', { class: 'meta' }, el('span', {}, icon('info', 'sm'), 'Verso hata yapabilir; cevabı kaynağından doğrulayın.'), counter));

  const actions = {
    ask: (question) => ask(question),
    navigate: ctx.navigate,
    edit: (question) => {
      field.value = question;
      resize();
      field.focus();
    },
  };

  function welcome() {
    return el('div', { class: 'welcome' },
      logo(44),
      el('h1', {}, 'Belgelerinize sorun'),
      el('p', {}, 'Cevaplar yalnız sizin belgelerinizden gelir ve her bilginin yanında kaynağı durur. Belgelerde yoksa asistan uydurmaz, "bulunamadı" der.'),
      el('div', { class: 'suggestions' }, SUGGESTIONS.map((s) =>
        el('button', { class: 'suggestion', type: 'button', onclick: () => ask(s) }, icon('chat', 'sm'), s))),
      el('span', { class: 'privacy-line' }, icon('shield', 'sm'),
        lastMode === 'cloud' ? 'Bulut modu açık: soru ve ilgili pasajlar sağlayıcıya gider.' : 'Yerel mod: soru, belge ve cevap bu sunucudan çıkmaz.'));
  }

  function drawThread() {
    if (!conversation.length) {
      thread.replaceChildren(welcome());
      return;
    }
    thread.replaceChildren(...conversation.flatMap((entry) => [userTurn(entry.question), botTurn(answerView(entry, actions))]));
  }

  function setBusy(controller) {
    busy = controller;
    send.replaceChildren(icon(controller ? 'stop' : 'send'));
    send.setAttribute('aria-label', controller ? 'Beklemeyi durdur' : 'Gönder');
    send.classList.toggle('secondary', Boolean(controller));
  }

  async function ask(question) {
    if (!question || busy) return;
    const controller = new AbortController();
    setBusy(controller);
    field.value = '';
    resize();
    if (!conversation.length) thread.replaceChildren();
    const started = Date.now();
    const elapsed = el('span', { class: 'tabular' });
    const expect = el('div', { class: 'expect' });
    const waiting = el('div', { class: 'waiting', hidden: true },
      el('div', { class: 'status' }, el('span', { class: 'spinner' }), el('span', { class: 'thinking' }, 'Cevap hazırlanıyor'), elapsed),
      el('div', { class: 'skeleton line' }), el('div', { class: 'skeleton line' }), el('div', { class: 'skeleton line' }), expect);
    const pending = botTurn(waiting);
    thread.append(userTurn(question), pending);
    pending.scrollIntoView({ behavior: 'smooth', block: 'end' });
    announcer.textContent = 'Cevap hazırlanıyor.';
    ticker = setInterval(() => {
      const ms = Date.now() - started;
      waiting.hidden = ms < SHOW_WAIT_MS;
      if (ms >= SLOW_MS) {
        elapsed.textContent = `· ${Math.round(ms / 1000)} sn`;
        expect.textContent = lastMode === 'cloud' ? 'Bulut modelinde cevap genellikle birkaç saniye sürer.'
          : 'Yerel modelde, ekran kartı yoksa cevap 20–60 sn sürebilir.';
      }
    }, 250);
    let entry;
    try {
      const result = await ctx.withAuth(() => api.ask(question, controller.signal));
      entry = { question, result, ms: Date.now() - started, at: new Date().toISOString() };
      prefs.markOnboarding({ asked: true });
    } catch (error) {
      entry = error?.name === 'AbortError'
        ? { question, cancelled: true, ms: Date.now() - started, at: new Date().toISOString() }
        : { question, error: error instanceof ApiError ? error.message : 'Bağlantı kurulamadı.', ms: Date.now() - started, at: new Date().toISOString() };
    } finally {
      clearInterval(ticker);
      setBusy(null);
      ctx.showMode();
    }
    conversation.push(entry);
    if (!ctx.isCurrent()) return;
    announcer.textContent = entry.cancelled ? 'Bekleme durduruldu.' : entry.error ? 'Cevap alınamadı.'
      : entry.result.outcome === 'NOT_FOUND' ? 'Belgelerde bulunamadı.' : 'Cevap hazır.';
    pending.replaceWith(botTurn(answerView({ ...entry, fresh: true }, actions)));
    field.focus();
  }

  main.replaceChildren(el('div', { class: 'chat' }, el('h1', { class: 'sr-only' }, 'Soru sor'), thread, announcer, form));
  drawThread();
  thread.lastElementChild?.scrollIntoView({ block: 'end' });
  if (ctx.params?.question) ask(ctx.params.question);
  else field.focus();
  // Leaving the screen does not cancel a question: its answer is kept in the conversation for the next visit.
  return () => clearInterval(ticker);
}
