// Questions as a conversation (ADR-0017, answer screen ADR-0024): the answer with citation buttons that preview the
// cited passage, source cards that show the excerpt with the words the answer shares marked, an honest state for each
// outcome and each failure, skeptical follow-ups, and a wait that says how long it has been and may be. On wide screens
// the cards sit in a sticky column next to the answer and the markers become short "belge · s. 3" chips.
// Every answer is stored in the user's server-side history (ADR-0023): a follow-up carries the conversation's id, the
// answer can be rated 👍/👎, and a conversation opened from "Geçmiş" comes back here read-only and goes on (ADR-0025).
// In the browser the open conversation lives in this tab's memory only (store.js); a reload starts a new one.
import { api, lastMode } from '../api.js';
import { disclosureLine, disclosureNotice } from '../disclosure.js';
import { answerMarkdown, chipLabel, contextQueryNote, errorState, followUps, markdownFileName, selectionQuestion, unverifiedNote,
  unverifiedSentences } from '../answer.js';
import { CONFIG } from '../config.js';
import { continueWith, startOverNote, startsOver } from '../conversations.js';
import { citingSpans, citingText, excerptParts, matchRanges } from '../excerpt.js';
import { markUnverified, prune, tintSentences, writeMarked } from '../highlight.js';
import { icon, logo } from '../icons.js';
import { canApprove, memoryBadge } from '../knowledge.js';
import { procedureModel } from '../skills.js';
import { slashMatches, slashTarget } from '../skilllib.js';
import { reasoningModel } from '../reasoning.js';
import { citedDocuments, DEFAULT_STALE_DAYS, insightCards, staleDays } from '../insight.js';
import { ROLES } from '../roles.js';
import { answerSegments, el, formatSeconds, groupSources, plainName, sourceLabel } from '../render.js';
import { ratingControls } from '../rating.js';
// The store's thread (which conversation is open) is "current" here: "thread" is the answer log element below.
import { conversation, prefs, startNewConversation, thread as current } from '../store.js';
import { pulse, reducedMotion, wordSpans } from '../motion.js';
import { closePageDrawer, openPageDrawer } from '../drawer.js';
import { provisionalText, stageText } from '../stream.js';
import { fileIcon, toast } from '../ui.js';
import { openHistory } from './history.js';

const MAX_CHARS = 1000;
// Nielsen's limits: nothing under 1 s, a skeleton up to 10 s, then the elapsed time and what to expect.
const SHOW_WAIT_MS = 1000;
const SLOW_MS = 10000;
/** How long a source card stays lit after its marker was clicked (ADR-0024; was 1200 ms). */
export const FLASH_MS = 600;
/** Keys that move between turns (APG feed-like): PageUp/PageDown, or Alt+↑/↓ also from the question box. */
const TURN_KEYS = 'Alt+ArrowUp Alt+ArrowDown PageUp PageDown';

let ids = 0;
const nextId = (prefix) => `${prefix}-${++ids}`;
const extension = (fileName) => String(fileName).split('.').pop()?.toUpperCase();
const whereOf = (citation) => sourceLabel(citation).slice(citation.fileName.length + 2);
const isNotFound = (result) => result.outcome === 'NOT_FOUND' || (result.outcome == null && !result.found && !result.citations.length);

/** The excerpt with the server's cut marks shown as such and the words the answer shares marked (CSS highlights). */
function quote(citation, evidence) {
  const { text, before, after } = excerptParts(citation.excerpt);
  const body = el('span', { class: 'quote-text' });
  writeMarked(body, text, matchRanges(text, evidence));
  // The body is what a closed card clamps; the padding stays on the frame, so no half line shows under the clamp.
  return el('span', { class: 'quote' }, el('span', { class: 'quote-body' },
    before ? el('span', { class: 'cut', title: 'Alıntının başı kısaltıldı' }, '…') : null,
    body,
    after ? el('span', { class: 'cut', title: 'Alıntının sonu kısaltıldı' }, '…') : null));
}

/**
 * The [n] marker as a button: a number, or on wide screens a "belge · s. 3" chip; its accessible name is always the
 * full source. Hover, focus or a click shows the source and its passage (NotebookLM-style) and lights the source card.
 */
function citationButton(citation, evidence, link) {
  const where = whereOf(citation);
  const name = plainName(citation.fileName);
  const pop = el('span', { class: 'cite-pop', role: 'tooltip', id: nextId('cite-pop') },
    el('span', { class: 'src' }, fileIcon(extension(citation.fileName), 'sm'), el('span', { class: 'fname' }, name), el('span', { class: 'where' }, where)),
    citation.excerpt ? quote(citation, evidence) : null);
  const on = (value) => link.hover(citation.number, value);
  const button = el('button', { class: `cite${citation.deleted ? ' deleted' : ''}`, type: 'button', 'data-number': citation.number, 'aria-describedby': pop.id,
    'aria-expanded': 'false', 'aria-label': `Kaynak ${citation.number}: ${name}, ${where}`,
    onclick: () => {
      const open = button.getAttribute('aria-expanded') !== 'true';
      button.classList.remove('dismissed');
      button.setAttribute('aria-expanded', String(open));
      if (open) link.open(citation.number);
    },
    onfocus: () => {
      place(button);
      on(true);
    },
    onblur: () => {
      button.setAttribute('aria-expanded', 'false');
      button.classList.remove('dismissed');
      on(false);
    },
    onmouseenter: () => {
      place(button);
      on(true);
    },
    onmouseleave: () => {
      button.classList.remove('dismissed');
      on(false);
    } },
  el('span', { class: 'cite-n', 'aria-hidden': 'true' }, String(citation.number)),
  el('span', { class: 'cite-chip', 'aria-hidden': 'true' }, chipLabel(citation)),
  pop);
  return button;
}

/** Keeps the preview inside the column and the window: right or left near the edges, below when the top is near. */
function place(button) {
  const box = button.getBoundingClientRect();
  button.classList.toggle('pop-below', box.top < 240);
  const column = button.closest('.answer-text')?.getBoundingClientRect() ?? { left: 0, right: innerWidth };
  button.classList.toggle('pop-left', box.left - column.left < 170);
  button.classList.toggle('pop-right', column.right - box.right < 170 && box.left - column.left >= 170);
}

/** "Bu bir prosedür: <ad>" with its steps and their sources (ADR-0053); null when no approved procedure matched. */
function procedureBox(result) {
  const model = procedureModel(result);
  if (!model) return null;
  return el('section', { class: 'notice info procedure', 'aria-label': model.title }, icon('flag', 'sm'),
    el('div', { class: 'grow' }, el('strong', {}, model.title),
      model.description ? el('div', {}, model.description) : null,
      el('ol', { class: 'steps-list' }, model.steps.map((step) => el('li', {}, step.text,
        step.sources.length ? el('span', { class: 'sub' }, ` (${step.sources.join('; ')})`) : null)))));
}

/**
 * ADR-0055: a multi-step answer's parts the documents held nothing for, the contradictions Verso already knew between
 * two cited sources, and the collapsible "Nasıl araştırıldı" with the sub-questions. Null for a single-pass answer.
 * Sub-questions are model text: text content only.
 */
function reasoningBox(result) {
  const model = reasoningModel(result);
  if (!model) return null;
  return el('div', { class: 'reasoning' },
    model.missing.length ? el('div', { class: 'notice neutral reasoning-missing', role: 'note' }, icon('search', 'sm'),
      el('div', { class: 'grow' }, el('strong', {}, 'Belgelerde bulunamayan kısım'),
        el('ul', { class: 'reasoning-list' }, model.missing.map((line) => el('li', {}, line))))) : null,
    // The known contradictions are insight cards since ADR-0058 (insightBox), in the same style as "Bu kaynak eskimiş".
    el('details', { class: 'reasoning-steps' },
      el('summary', {}, icon('layers', 'sm'), model.title),
      el('p', { class: 'sub' }, model.summary),
      el('ol', { class: 'steps-list' }, model.steps.map((step) => el('li', { class: step.found ? null : 'missing' },
        step.text,
        el('span', { class: 'sub' }, step.found ? (step.sources ? ` (${step.sources})` : ' (kaynak gösterilmedi)') : ' (bulunamadı)'))))));
}

/** The stale threshold of /v1/info, asked once per page load (ADR-0058); the default until it answers. */
let staleAfter = null;
function staleThreshold() {
  if (staleAfter === null) {
    staleAfter = DEFAULT_STALE_DAYS;
    api.info().then((info) => { staleAfter = staleDays(info); }).catch(() => {});
  }
  return staleAfter;
}

/**
 * ADR-0058 (concept C): insight cards inside the answer: a stale cited source, an expired verification, a known
 * contradiction. Drawn at once from the answer; the stale check follows when the cited documents' dates arrive (the
 * caller's own GET /v1/documents/{id}, so only documents the caller may read). Text only, never markup.
 */
function insightBox(result) {
  const box = el('div', { class: 'insight-cards', role: 'group', 'aria-label': 'Bu cevapla ilgili uyarılar' });
  const draw = (documents) => {
    const cards = insightCards(result, { documents, staleAfterDays: staleThreshold(), now: Date.now() }).map(insightCard);
    box.replaceChildren(...cards);
  };
  draw(new Map());
  const ids = citedDocuments(result);
  if (ids.length) {
    Promise.all(ids.map((id) => documentDate(id).then((doc) => (doc ? [id, doc] : null))))
      .then((pairs) => draw(new Map(pairs.filter(Boolean))));
  }
  return box;
}

/** One insight card (insight.js), text only. */
function insightCard(card) {
  return el('div', { class: `insight-card ${card.kind}`, role: 'note' },
    icon(card.kind === 'conflict' ? 'alert' : 'clock', 'sm'),
    el('div', { class: 'grow' }, el('strong', {}, card.title), el('div', {}, card.text),
      card.sources.length ? el('ul', { class: 'reasoning-list' }, card.sources.map((source) => el('li', {}, source))) : null,
      card.note ? el('div', { class: 'sub' }, card.note) : null));
}

/** One look-up per document and page load: a thread drawn again does not ask again. Only the dates are kept. */
const documentDates = new Map();
function documentDate(id) {
  if (!documentDates.has(id)) {
    documentDates.set(id, api.getDocument(id).then((doc) => ({ createdAt: doc?.createdAt, updatedAt: doc?.updatedAt }))
      .catch(() => null));
  }
  return documentDates.get(id);
}

function outcomeBadge(result) {
  // ADR-0049: an answer from an expert-verified answer card, not written by the model.
  const memory = memoryBadge(result);
  if (memory) return el('span', { class: 'pill ok', title: memory.title }, icon('shield', 'sm'), memory.label);
  if (result.outcome === 'ANSWERED' || (result.outcome == null && result.found)) {
    return el('span', { class: 'pill ok' }, icon('checkCircle', 'sm'), 'Kaynaklı cevap');
  }
  // ADR-0047: UNCITED with citations: sources were cited, but none of the cited sentences is supported by them.
  if (result.outcome === 'UNCITED') {
    return el('span', { class: 'pill wait' }, icon('alert', 'sm'), result.citations?.length ? 'Dayanağı doğrulanamadı' : 'Kaynak gösterilemedi');
  }
  return el('span', { class: 'pill neutral' }, icon('search', 'sm'), 'Belgelerde bulunamadı');
}

/** Opens or closes a card: a closed card clamps each excerpt to a few lines. */
function setOpen(card, open) {
  card.classList.toggle('open', open);
  const more = card.querySelector('.more');
  if (!more) return;
  more.setAttribute('aria-expanded', String(open));
  more.firstChild.textContent = open ? 'Daralt' : 'Tamamını göster';
}

/** Shows the "Tamamını göster" button only on cards whose excerpts are clamped (measured after layout). */
function measure(sources) {
  for (const card of sources.querySelectorAll('.source')) {
    const clamped = [...card.querySelectorAll('.quote-body')].some((q) => q.scrollHeight > q.clientHeight + 1);
    card.querySelector('.more').hidden = !clamped && !card.classList.contains('open');
  }
}

/** Why a citation row has no excerpt: the history keeps none, a deleted document has none to show. */
function noExcerpt(citation, restored) {
  if (citation.deleted) return 'Belge silinmiş; alıntı gösterilemez.';
  if (citation.inaccessible) return 'Bu belgeye artık erişiminiz yok; alıntı gösterilemez.';
  return restored ? 'Geçmişte alıntı saklanmaz.' : 'Bu kaynak için alıntı gelmedi.';
}

/** Source cards grouped by document; each citation row shows its page and its excerpt. */
function sourceCards(citations, evidence, link, restored, openPage) {
  const sources = el('div', { class: 'sources', role: 'list', 'aria-label': 'Kaynaklar' }, groupSources(citations).map((group) => {
    const name = plainName(group.fileName);
    const deleted = group.items.some((c) => c.deleted || c.inaccessible);
    const card = el('div', { class: `source${deleted ? ' deleted' : ''}`, role: 'listitem', id: nextId('source'), 'data-numbers': group.items.map((c) => c.number).join(' ') },
      el('div', { class: 'source-head' }, fileIcon(extension(group.fileName), 'sm'), el('span', { class: 'fname', title: name }, name),
        // The shared collection the document lives in (ADR-0028); plain text, never markup.
        group.collectionName ? el('span', { class: 'pill neutral', title: 'Bilgi alanı' }, plainName(group.collectionName)) : null),
      group.items.map((c) => el('div', { class: 'cite-row', 'data-number': c.number,
        onmouseenter: () => link.hover(c.number, true), onmouseleave: () => link.hover(c.number, false) },
      el('button', { class: 'cite-ref', type: 'button', 'aria-label': `Cevapta göster: kaynak ${c.number}, ${whereOf(c)}`,
        onclick: () => link.reveal(c.number), onfocus: () => link.hover(c.number, true), onblur: () => link.hover(c.number, false) },
      el('span', { class: 'n' }, String(c.number)), whereOf(c)),
      c.excerpt ? quote(c, evidence.get(c.number)) : el('span', { class: 'quote none' }, noExcerpt(c, restored)),
      // The stored page with the excerpt marked (ADR-0035); not for a deleted or no longer readable document.
      c.deleted || c.inaccessible || !openPage ? null : el('button', { class: 'button ghost sm page-open', type: 'button',
        'aria-haspopup': 'dialog', 'aria-label': `${c.unit === 'SECTION' ? 'Bölümü' : 'Sayfayı'} aç: ${name}, ${whereOf(c)}`,
        onclick: (event) => openPage(c, event.currentTarget) }, icon('book', 'sm'), c.unit === 'SECTION' ? 'Bölümü aç' : 'Sayfayı aç'))));
    card.append(el('button', { class: 'more', type: 'button', hidden: true, 'aria-expanded': 'false', 'aria-controls': card.id,
      onclick: () => setOpen(card, !card.classList.contains('open')) }, el('span', {}, 'Tamamını göster'), icon('chevronDown', 'sm')));
    return card;
  }));
  if (typeof ResizeObserver === 'function') new ResizeObserver(() => measure(sources)).observe(sources);
  return sources;
}

function modeMeta(result, ms) {
  const local = (result.mode ?? lastMode) !== 'cloud';
  return el('span', { class: `meta ${local ? 'local' : ''}`, title: local ? 'Soru ve cevap bu sunucudan çıkmadı.'
    : 'Soru ve ilgili pasajlar bulut sağlayıcısına gönderildi (KVKK md. 9).' },
  icon(local ? 'shield' : 'cloud', 'sm'), `${local ? 'Yerel' : 'Bulut'} · ${result.model} · ${formatSeconds(ms)}`);
}

/** The answer as a Markdown file (Blob + <a download>: same document, no new CSP directive; ADR-0024). */
function download(entry) {
  const text = answerMarkdown(entry.result, { question: entry.question, productName: CONFIG.deployment.productName,
    mode: entry.result.mode, model: entry.result.model, at: entry.at });
  const url = URL.createObjectURL(new Blob([text], { type: 'text/markdown;charset=utf-8' }));
  const anchor = el('a', { href: url, download: markdownFileName(entry.question), hidden: true });
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

const TONES = Object.freeze({ down: ['bad', 'alert'], offline: ['bad', 'alert'], other: ['bad', 'alert'], busy: ['wait', 'clock'],
  invalid: ['wait', 'alert'], reindex: ['info', 'info'], auth: ['neutral', 'lock'] });

/** A failed question: what happened in plain words, and the one thing to do next. */
function failureView(entry, actions) {
  const state = entry.error;
  const [tone, glyph] = TONES[state.kind] ?? TONES.other;
  const buttons = [];
  if (state.retry) {
    const retry = el('button', { class: 'button secondary sm', type: 'button', onclick: () => {
      if (retry.getAttribute('aria-disabled') !== 'true') actions.ask(entry.question);
    } }, icon('refresh', 'sm'), el('span', {}, 'Tekrar dene'));
    // With Retry-After the button counts down first: asking again earlier only gets the same answer.
    const until = Date.parse(entry.at) + (state.retryAfter ?? 0) * 1000;
    const tick = () => {
      const left = Math.ceil((until - Date.now()) / 1000);
      retry.setAttribute('aria-disabled', String(left > 0));
      retry.lastChild.textContent = left > 0 ? `Tekrar dene (${left} sn)` : 'Tekrar dene';
      return left > 0;
    };
    if (tick()) {
      const timer = setInterval(() => {
        if (!tick() || !document.contains(retry)) clearInterval(timer);
      }, 1000);
    }
    buttons.push(retry);
  }
  if (state.kind === 'invalid') {
    buttons.push(el('button', { class: 'button secondary sm', type: 'button', onclick: () => actions.edit(entry.question) }, icon('edit', 'sm'), 'Soruyu düzenle'));
  }
  return el('div', { class: 'answer' }, el('div', { class: `notice ${tone}`, role: entry.fresh ? 'alert' : null }, icon(glyph, 'sm'),
    el('div', { class: 'grow' }, el('strong', {}, state.title), el('div', {}, state.text),
      buttons.length ? el('div', { class: 'actions' }, buttons) : null)));
}

/**
 * Links an answer's markers, its source cards and its sentences: hovering or focusing either side lights both and
 * tints the answer sentence that cites the source; a click on a marker opens its card, one on a card's page shows the
 * marker in the answer.
 */
function linker(answer, segments) {
  let box = null;
  const cites = (n) => box.querySelectorAll(`.cite[data-number="${n}"]`);
  const rows = (n) => box.querySelectorAll(`.cite-row[data-number="${n}"]`);
  const cardOf = (n) => box.querySelector(`.source[data-numbers~="${n}"]`);
  return {
    attach(node) {
      box = node;
    },
    hover(n, on) {
      if (!box) return;
      for (const node of cites(n)) node.classList.toggle('hl', on);
      for (const node of rows(n)) node.classList.toggle('hl', on);
      cardOf(n)?.classList.toggle('hl', on);
      tintSentences(segments, on ? citingSpans(answer, n) : []);
    },
    open(n) {
      const card = cardOf(n);
      if (!card) return;
      setOpen(card, true);
      card.classList.add('flash');
      card.scrollIntoView({ block: 'nearest', behavior: reducedMotion() ? 'auto' : 'smooth' });
      pulse(card);
      setTimeout(() => card.classList.remove('flash'), FLASH_MS);
    },
    reveal(n) {
      const marker = cites(n)[0];
      if (!marker) return;
      marker.scrollIntoView({ block: 'nearest', behavior: reducedMotion() ? 'auto' : 'smooth' });
      marker.focus({ preventScroll: true });
    },
    all() {
      for (const card of box?.querySelectorAll('.source') ?? []) {
        card.classList.add('flash');
        pulse(card);
        setTimeout(() => card.classList.remove('flash'), FLASH_MS);
      }
      box?.querySelector('.sources')?.scrollIntoView({ block: 'nearest', behavior: reducedMotion() ? 'auto' : 'smooth' });
    },
  };
}

/** The answer text: plain text nodes (or one span per word when fresh) and the marker buttons, with each text's offset. */
function answerText(result, evidence, link, counter) {
  const box = el('div', { class: `answer-text${counter ? ' reveal' : ''}` });
  const segments = [];
  for (const part of answerSegments(result.answer, result.citations)) {
    if (part.citation) {
      box.append(citationButton(part.citation, evidence.get(part.citation.number), link));
      continue;
    }
    let offset = part.start;
    for (const piece of counter ? wordSpans(part.text, counter) : [part.text]) {
      const node = typeof piece === 'string' ? document.createTextNode(piece) : piece;
      const text = node.nodeType === Node.TEXT_NODE ? node : node.firstChild;
      segments.push({ node: text, start: offset });
      offset += text.data.length;
      box.append(node);
    }
  }
  return { box, segments };
}

function answerView(entry, actions) {
  if (entry.cancelled) {
    return el('div', { class: 'answer' }, el('div', { class: 'notice neutral' }, icon('stop', 'sm'),
      el('div', { class: 'grow' }, el('strong', {}, entry.streamed ? 'Cevap durduruldu.' : 'Bekleme durduruldu.'),
        el('div', {}, entry.streamed ? 'Sunucu cevabı yazmayı bıraktı; bu soru geçmişe kaydedilmedi. Aynı soruyu yeniden sorabilirsiniz.'
          : 'Cevap bu ekrana gelmeyecek. Aynı soruyu yeniden sorabilirsiniz.'),
        el('div', { class: 'actions' },
          el('button', { class: 'button secondary sm', type: 'button', onclick: () => actions.ask(entry.question) }, icon('refresh', 'sm'), 'Tekrar sor')))));
  }
  if (entry.error) return failureView(entry, actions);
  const { result } = entry;
  const { productName } = CONFIG.deployment;
  const notFound = isNotFound(result);
  // A turn opened from the history is read-only: no excerpt was stored, so no follow-ups and no edits; it can be rated.
  const restored = Boolean(entry.restored);
  const rating = ratingControls(entry, actions);
  const evidence = new Map(result.citations.map((c) => [c.number, citingText(result.answer, c.number)]));
  // A fresh answer appears word by word; one drawn again (a later visit) appears at once.
  const counter = entry.fresh && !reducedMotion() ? { i: 0 } : null;
  const segments = [];
  const link = linker(result.answer, segments);
  const text = notFound ? null : answerText(result, evidence, link, counter);
  if (text) segments.push(...text.segments);
  const unverified = notFound ? null : unverifiedNote(result);
  if (text && unverified) markUnverified(text.segments, unverifiedSentences(result));
  const sources = result.citations.length ? sourceCards(result.citations, evidence, link, restored, actions.page) : null;
  const chips = restored ? [] : followUps(result);
  // ADR-0046: a follow-up searched with the conversation says how it was understood (not stored: fresh answers only).
  const contextNote = contextQueryNote(result);
  // EU AI Act Art. 50 (ADR-0027): a generated answer is machine-readable as such. The fixed "not found" text is not generated.
  const view = el('div', { class: `answer${sources ? ' has-sources' : ''}`, 'data-ai-generated': notFound ? null : 'true' },
    // ADR-0021: the installation's product name, never followed by a Turkish case suffix.
    el('h3', { class: 'sr-only' }, `${productName} cevap verdi`),
    el('div', { class: 'answer-head' }, outcomeBadge(result),
      restored ? el('span', { class: 'pill neutral', title: 'Geçmişten açıldı; alıntılar saklanmaz.' }, icon('history', 'sm'), 'Geçmişten') : null,
      contextNote ? el('p', { class: 'context-query', title: 'Soru, konuşmanın önceki sorularıyla birlikte bu sorguyla arandı.' },
        icon('search', 'sm'), contextNote) : null),
    // ADR-0053: an approved procedure matching the question, above the answer (never instead of it).
    procedureBox(result),
    entry.notice ? el('div', { class: 'notice info start-over' }, icon('info', 'sm'), el('div', { class: 'grow' }, entry.notice)) : null,
    notFound
      ? el('div', { class: 'notice neutral answer-empty' }, icon('search', 'sm'), el('div', { class: 'grow' },
        el('strong', {}, 'Yüklediğiniz belgelerde bu sorunun cevabını bulamadım.'),
        el('div', {}, 'Belgelerde dayanak bulunamadığı için tahmin yürütülmedi. Soruyu başka sözcüklerle sorabilir ya da ilgili belgeyi yükleyebilirsiniz.'),
        restored ? null : el('div', { class: 'actions' },
          el('button', { class: 'button secondary sm', type: 'button', onclick: () => actions.edit(entry.question) }, icon('edit', 'sm'), 'Soruyu düzenle'),
          el('button', { class: 'button secondary sm', type: 'button', onclick: () => actions.navigate('documents', { upload: true }) }, icon('upload', 'sm'), 'Belge yükle'))))
      : text.box,
    // ADR-0055: how a multi-step answer was researched, what was not found, known contradictions.
    reasoningBox(result),
    notFound ? null : insightBox(result),
    result.outcome === 'UNCITED' && !unverified
      ? el('div', { class: 'notice wait' }, icon('alert', 'sm'), el('div', { class: 'grow' },
        // A turn from the history keeps its outcome but not the sentences (ADR-0047): with citations, say why.
        el('strong', {}, result.citations.length ? 'Bu cevabın dayanağı doğrulanamadı.' : 'Bu cevap bir kaynağa bağlanamadı.'),
        el('div', {}, result.citations.length
          ? 'Cevaptaki sayı ya da kodlar gösterilen kaynaklarda aynı bağlamda geçmiyordu; kullanmadan önce kaynakla karşılaştırın.'
          : 'Doğru olabilir ama belgelerinizden doğrulanamadı; kullanmadan önce ilgili belgeye bakın.')))
      : null,
    // ADR-0047: the sentences quoted as text; the answer itself is shown as the model wrote it.
    unverified
      ? el('div', { class: 'notice wait unverified-note', role: 'note' }, icon('alert', 'sm'), el('div', { class: 'grow' },
        el('strong', {}, unverified.title), el('div', {}, unverified.text),
        el('ul', { class: 'unverified-list' }, unverified.quotes.map((q) => el('li', {}, q)),
          unverified.more ? el('li', { class: 'muted' }, `ve ${unverified.more} cümle daha`) : null)))
      : null,
    sources,
    chips.length ? el('div', { class: 'follow-ups', role: 'group', 'aria-label': 'Takip soruları' }, chips.map((f) =>
      // The tooltip is the question as it will be sent: a standalone question (the follow-up rewrite of ADR-0046 skips it).
      el('button', { class: 'chip follow', type: 'button', title: f.local ? 'Kaynaklar bu cevapta gösteriliyor; yeni soru sorulmaz.' : f.question(entry.question), onclick: () => (f.local ? actions.sources(link, result) : actions.ask(f.question(entry.question))) },
        icon(f.local ? 'book' : 'chat', 'sm'), f.label))) : null,
    el('div', { class: 'answer-foot' }, modeMeta(result, entry.ms), el('span', { class: 'spacer' }),
      el('div', { class: 'answer-actions', role: 'group', 'aria-label': 'Cevap eylemleri' },
        notFound ? null : el('button', { class: 'button ghost sm', type: 'button', title: 'Markdown olarak, kaynaklar dipnotta', onclick: async () => {
          try {
            await navigator.clipboard.writeText(answerMarkdown(result, { productName }));
            toast('Cevap kaynaklarıyla kopyalandı (Markdown).', 'ok');
          } catch {
            toast('Kopyalanamadı.');
          }
        } }, icon('copy', 'sm'), 'Kopyala'),
        notFound || restored ? null : el('button', { class: 'button ghost sm', type: 'button', onclick: () => download(entry) }, icon('download', 'sm'), '.md indir'),
        restored ? null : el('button', { class: 'button ghost sm', type: 'button', onclick: () => actions.edit(entry.question) }, icon('edit', 'sm'), 'Düzenle'),
        // ADR-0049: an admin turns their own sourced answer into an expert-verified answer card.
        !restored && canApprove(result, actions.isAdmin) ? el('button', { class: 'button ghost sm', type: 'button',
          title: 'Bu cevabı onaylı bilgi kartı yapar: aynı soru yeniden sorulunca, kaynakları okuyabilenlere bu cevap gösterilir.',
          onclick: (event) => actions.approve(result, event.currentTarget) }, icon('checkCircle', 'sm'), 'Onayla') : null),
      rating?.controls ?? null),
    rating?.picker ?? null);
  link.attach(view);
  return view;
}

function userTurn(question, id) {
  return el('div', { class: 'turn-user' }, el('h3', { class: 'sr-only' }, 'Siz sordunuz'), el('div', { class: 'bubble', id }, question));
}

function botTurn(content) {
  return el('div', { class: 'turn-bot' }, logo(30), content);
}

/** One question and its answer; focusable (not tabbable) so the turn keys can move to it. */
function turn(question, content) {
  const id = nextId('question');
  return el('article', { class: 'turn', tabindex: -1, 'aria-labelledby': id }, userTurn(question, id), botTurn(content));
}

/**
 * The "/" commands of the question box (ADR-0057): a text that starts with "/" opens a listbox of the task skills
 * ("Beceriler") whose slash command matches what follows; ArrowUp/ArrowDown move, Enter or a click opens the chosen
 * skill's form with the rest of the text prefilled, Escape closes the list. Decision: while the list shows a match,
 * Enter takes the highlighted skill; after Escape, or when no skill matches, Enter sends the text as a question
 * exactly as before. The skill list is read once per visit, only after a "/" is typed.
 */
export function slashPicker(field, ctx) {
  let skills = null;
  let matches = [];
  let active = 0;
  let dismissed = false;
  const list = el('ul', { class: 'slash-picker', role: 'listbox', id: 'slash-picker', 'aria-label': 'Beceriler', hidden: true });
  field.setAttribute('aria-controls', 'slash-picker');
  const open = () => !list.hidden && matches.length > 0;
  const close = () => {
    list.hidden = true;
    field.removeAttribute('aria-activedescendant');
  };
  const choose = (skill) => {
    const target = slashTarget(skill, field.value);
    close();
    ctx.navigate('beceriler', target);
  };
  const draw = () => {
    if (!matches.length) {
      close();
      return;
    }
    list.replaceChildren(...matches.map((skill, index) => el('li', { role: 'option', id: `slash-option-${index}`,
      'aria-selected': String(index === active), onmousedown: (event) => event.preventDefault(), onclick: () => choose(skill) },
    el('code', {}, `/${skill.slash}`), el('span', {}, skill.name))));
    list.hidden = false;
    field.setAttribute('aria-activedescendant', `slash-option-${active}`);
  };
  field.addEventListener('input', async () => {
    if (!field.value.startsWith('/')) {
      dismissed = false;
      close();
      return;
    }
    if (dismissed) return;
    if (skills === null) {
      skills = [];
      skills = (await ctx.withAuth(() => api.listTaskSkills()).catch(() => [])) ?? [];
    }
    if (!field.value.startsWith('/') || dismissed) return;
    matches = slashMatches(skills, field.value);
    active = 0;
    draw();
  });
  return {
    list,
    /** True when the key belonged to the open list (the caller then does nothing else with it). */
    handleKey(event) {
      if (!open()) return false;
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        active = (active + (event.key === 'ArrowDown' ? 1 : matches.length - 1)) % matches.length;
        draw();
        return true;
      }
      if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
        event.preventDefault();
        choose(matches[active]);
        return true;
      }
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        dismissed = true;
        close();
        return true;
      }
      return false;
    },
  };
}

export function renderAsk(ctx) {
  const { main } = ctx;
  const { productName } = CONFIG.deployment;
  const thread = el('div', { class: 'thread', role: 'log', 'aria-live': 'off', 'aria-label': 'Soru-cevap geçmişi', tabindex: 0,
    'aria-keyshortcuts': TURN_KEYS });
  // Only the start and the end of an answer are announced, never the answer body (screen readers would read it twice).
  const announcer = el('div', { class: 'sr-only', role: 'status', 'aria-live': 'polite', 'aria-atomic': 'true' });
  const field = el('textarea', { rows: 1, maxlength: MAX_CHARS, placeholder: 'Belgelerinize bir soru sorun…', 'aria-label': 'Soru',
    'aria-keyshortcuts': 'Alt+ArrowUp' });
  const counter = el('span', { class: 'tabular' }, `0 / ${MAX_CHARS}`);
  const send = el('button', { class: 'button icon-only', type: 'submit', 'aria-label': 'Gönder' }, icon('send'));
  let busy = null; // the AbortController of the question in flight
  let ticker = null;
  let picked = '';

  const resize = () => {
    field.style.height = 'auto';
    field.style.height = `${Math.min(field.scrollHeight, 200)}px`;
    counter.textContent = `${field.value.length} / ${MAX_CHARS}`;
  };
  field.addEventListener('input', resize);
  const slash = slashPicker(field, ctx);
  field.addEventListener('keydown', (event) => {
    if (slash.handleKey(event)) return;
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      if (!busy) form.requestSubmit(); // Enter never stops a running question; only the stop button does
    }
  });

  // ADR-0056: a model choice, shown only when the admin allows it (GET /v1/models says enabled); hidden otherwise, and a
  // failed call leaves it hidden (the server's own route answers).
  const modelPicker = el('select', { class: 'model-picker', 'aria-label': 'Cevap modeli', hidden: true },
    el('option', { value: '' }, 'Varsayılan model'));
  ctx.withAuth(() => api.modelChoices()).then((offer) => {
    if (ctx.isCurrent && !ctx.isCurrent()) return;
    if (!offer?.enabled || !Array.isArray(offer.choices) || !offer.choices.length) return;
    modelPicker.append(...offer.choices.map((c) => el('option', { value: c.id },
      c.external ? `${c.label} (dış sağlayıcı)` : c.label)));
    modelPicker.hidden = false;
  }).catch(() => {});

  const form = el('form', { class: 'composer', onsubmit: (event) => {
    event.preventDefault();
    if (busy) {
      busy.abort();
      field.focus(); // the stop button turns back into "send": the focus goes where the next question is typed
    } else ask(field.value.trim());
  } }, field, send,
  el('div', { class: 'meta' }, el('span', {}, icon('info', 'sm'), disclosureLine(productName)), modelPicker, counter));
  form.append(slash.list); // positioned above the composer (panel.css .slash-picker)

  const prefill = (value) => {
    field.value = value;
    resize();
    field.focus();
    field.setSelectionRange(field.value.length, field.value.length);
  };

  const actions = {
    ask: (question) => ask(question),
    navigate: ctx.navigate,
    withAuth: ctx.withAuth,
    edit: prefill,
    // ADR-0049: the server checks the role again (403); the button only shows for an admin.
    isAdmin: (ctx.roles ?? []).includes(ROLES.ADMIN),
    approve: async (result, button) => {
      button.disabled = true;
      try {
        const card = await ctx.withAuth(() => api.createKnowledgeCard(result.conversationId, result.turnId, true));
        toast(card?.state === 'VERIFIED' ? 'Cevap onaylı bilgi oldu.' : 'Onayınız kaydedildi; ikinci bir yöneticinin onayı bekleniyor.', 'ok');
        button.remove();
      } catch (error) {
        button.disabled = false;
        toast(error?.message ?? 'Onaylanamadı.');
      }
    },
    // The page viewer of a source (ADR-0035); the focus comes back to the button that opened it.
    page: (citation, opener) => openPageDrawer(citation, { withAuth: ctx.withAuth, opener }),
    sources: (link, result) => {
      // Answered here: the answer already names its documents; asking the model would only guess again.
      link.all();
      const names = groupSources(result.citations).map((g) => `${plainName(g.fileName)} (${g.items.map(whereOf).join(', ')})`);
      toast(`Bu cevap ${names.length} belgeye dayanıyor: ${names.join('; ')}.`, 'info');
    },
  };

  // "Bunu sor": selecting text in an answer or an excerpt offers to ask about it (NN/g); the button prefills the box.
  const pick = el('button', { class: 'button sm pick-ask', type: 'button', hidden: true, 'aria-label': 'Seçili metni sor',
    onpointerdown: (event) => event.preventDefault(), // keep the selection while the button is pressed
    onclick: () => {
      const value = picked;
      hidePick();
      getSelection()?.removeAllRanges();
      prefill(value);
    } }, icon('chat', 'sm'), 'Bunu sor');
  function hidePick() {
    pick.hidden = true;
    picked = '';
  }
  let frame = 0;
  const onSelection = () => {
    cancelAnimationFrame(frame);
    frame = requestAnimationFrame(() => {
      const selection = getSelection();
      if (!selection || selection.isCollapsed || !selection.rangeCount) {
        if (!pick.contains(document.activeElement)) hidePick();
        return;
      }
      const range = selection.getRangeAt(0);
      const host = (node) => (node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement)?.closest('.answer-text, .source .quote');
      const start = host(range.startContainer);
      const value = selectionQuestion(selection.toString());
      if (!start || start !== host(range.endContainer) || !thread.contains(start) || !value) {
        hidePick();
        return;
      }
      picked = value;
      const box = range.getBoundingClientRect();
      pick.style.setProperty('--x', `${Math.round(Math.min(innerWidth - 124, Math.max(8, box.left + box.width / 2 - 56)))}px`);
      pick.style.setProperty('--y', `${Math.round(box.top < 64 ? box.bottom + 8 : box.top - 48)}px`);
      pick.hidden = false;
    });
  };
  document.addEventListener('selectionchange', onSelection);
  const onScroll = () => {
    if (!pick.hidden) hidePick();
  };
  addEventListener('scroll', onScroll, { passive: true });

  // The conversation bar: the title of a conversation opened from the history, "Geçmiş" (also the way to the history
  // on phones, where there is no menu) and "Yeni konuşma", which makes the next question start a new conversation.
  const barTitle = el('span', { class: 'chat-title' });
  const fresh = el('button', { class: 'button ghost sm', type: 'button', onclick: () => {
    busy?.abort(); // an answer still on its way belongs to the conversation being left; it is dropped (current.generation)
    startNewConversation();
    drawThread();
    updateBar();
    announcer.textContent = 'Yeni konuşma başladı.';
    field.focus();
  } }, icon('plus', 'sm'), 'Yeni konuşma');
  const bar = el('div', { class: 'chat-bar' }, barTitle, el('span', { class: 'spacer' }),
    el('button', { class: 'button ghost sm', type: 'button', 'aria-haspopup': 'dialog', onclick: () => openHistory(ctx) }, icon('history', 'sm'), 'Geçmiş'),
    fresh);
  function updateBar() {
    barTitle.textContent = current.title ? plainName(current.title) : '';
    barTitle.title = barTitle.textContent;
    fresh.hidden = !conversation.length && !current.conversationId;
  }

  // Turns: PageUp/PageDown (outside a multi-line question) and Alt+↑/↓ move between them; Esc closes what is open.
  function moveTurn(step) {
    const turns = [...thread.querySelectorAll('.turn')];
    if (!turns.length) return;
    const at = turns.findIndex((t) => t.contains(document.activeElement));
    const next = at < 0 ? (step < 0 ? turns.length - 1 : -1) : at + step;
    if (next >= turns.length) {
      field.focus();
      return;
    }
    if (next < 0) return;
    turns[next].focus({ preventScroll: true });
    turns[next].scrollIntoView({ block: 'start', behavior: reducedMotion() ? 'auto' : 'smooth' });
  }
  function escape() {
    if (!pick.hidden) {
      hidePick();
      return true;
    }
    const active = document.activeElement;
    const open = thread.querySelector('.cite[aria-expanded="true"]');
    if (open) {
      open.setAttribute('aria-expanded', 'false');
      open.classList.add('dismissed');
      return true;
    }
    const shown = active?.classList?.contains('cite') && !active.classList.contains('dismissed') ? active : thread.querySelector('.cite:hover:not(.dismissed)');
    if (shown) {
      shown.classList.add('dismissed'); // WCAG 1.4.13: the preview can be dismissed without moving the pointer or focus
      return true;
    }
    const card = active?.closest?.('.source.open') ?? thread.querySelector('.source.open');
    if (card) {
      setOpen(card, false);
      if (card.contains(active)) card.querySelector('.more:not([hidden])')?.focus();
      return true;
    }
    return false;
  }
  const chat = el('div', { class: 'chat', onkeydown: (event) => {
    if (event.key === 'Escape') {
      if (escape()) event.preventDefault();
      return;
    }
    const inField = event.target === field;
    const vertical = event.key === 'ArrowUp' || event.key === 'ArrowDown';
    if (event.altKey && vertical) {
      event.preventDefault();
      moveTurn(event.key === 'ArrowUp' ? -1 : 1);
    } else if ((event.key === 'PageUp' || event.key === 'PageDown') && !event.altKey && !event.ctrlKey && !event.metaKey
      && !(inField && field.value.includes('\n'))) {
      event.preventDefault();
      moveTurn(event.key === 'PageUp' ? -1 : 1);
    }
  } }, el('h1', { class: 'sr-only' }, 'Asistan'), bar, thread, announcer, pick, form);

  // Phones: the visual viewport shrinks when the keyboard opens while the layout viewport does not, so the composer
  // is lifted by the keyboard's height (and the tab bar steps aside) while the question box has the focus.
  const viewport = globalThis.visualViewport;
  const onViewport = () => {
    const keyboard = document.activeElement === field ? Math.max(0, Math.round(innerHeight - viewport.height - viewport.offsetTop)) : 0;
    chat.style.setProperty('--kb', `${keyboard}px`);
    document.documentElement.classList.toggle('keyboard-open', keyboard > 80);
  };
  viewport?.addEventListener('resize', onViewport);
  viewport?.addEventListener('scroll', onViewport);
  field.addEventListener('focus', onViewport);
  field.addEventListener('blur', onViewport);

  function welcome() {
    return el('div', { class: 'welcome' },
      logo(44),
      el('h1', {}, 'Belgelerinize sorun'),
      el('p', {}, 'Cevaplar yalnız sizin belgelerinizden gelir ve her bilginin yanında kaynağı durur. Belgelerde yoksa asistan uydurmaz, "bulunamadı" der.'),
      // The installation's questions (config.js, merged with branding.json at startup), read when the screen renders.
      el('div', { class: 'suggestions' }, CONFIG.deployment.suggestions.map((s) =>
        el('button', { class: 'suggestion', type: 'button', onclick: () => ask(s) }, icon('chat', 'sm'), s))),
      el('span', { class: 'privacy-line' }, icon('shield', 'sm'),
        lastMode === 'cloud' ? 'Bulut modu açık: soru ve ilgili pasajlar sağlayıcıya gider.' : 'Yerel mod: soru, belge ve cevap bu sunucudan çıkmaz.'));
  }

  // The first-use AI notice (EU AI Act Art. 50, ADR-0027): an in-page card, not a dialog; the flag is a preference, so it
  // lives in sessionStorage with the others and returns with a new browser session.
  function aiNotice() {
    if (prefs.onboarding().aiNoticeSeen) return null;
    const text = disclosureNotice(productName);
    const card = el('div', { class: 'notice info ai-notice', role: 'note' }, icon('info', 'sm'), el('div', { class: 'grow' },
      el('strong', {}, text.title), el('div', {}, text.body),
      el('div', { class: 'actions' }, el('button', { class: 'button secondary sm', type: 'button', onclick: () => {
        prefs.markOnboarding({ aiNoticeSeen: true });
        card.remove();
        field.focus();
      } }, text.dismiss))));
    return card;
  }

  function drawThread() {
    prune();
    const notice = aiNotice();
    if (!conversation.length) {
      thread.replaceChildren(...(notice ? [notice] : []), welcome());
      return;
    }
    thread.replaceChildren(...(notice ? [notice] : []), ...conversation.map((entry) => turn(entry.question, answerView(entry, actions))));
  }

  function setBusy(controller) {
    busy = controller;
    send.replaceChildren(icon(controller ? 'stop' : 'send'));
    send.setAttribute('aria-label', controller ? 'Durdur' : 'Gönder');
    send.classList.toggle('secondary', Boolean(controller));
  }

  async function ask(question) {
    if (!question || busy) return;
    const controller = new AbortController();
    const generation = current.generation;
    const sentTo = current.conversationId;
    setBusy(controller);
    fresh.hidden = false;
    field.value = '';
    resize();
    hidePick();
    if (!conversation.length) thread.replaceChildren();
    const started = Date.now();
    const elapsed = el('span', { class: 'tabular' });
    const expect = el('div', { class: 'expect' });
    // ADR-0035: the stage the server reports, and the model text as it arrives. The draft is plain text without its
    // [n] markers (they are validated only at the end) and sits outside every live region: only stages and the end
    // are announced. It is replaced by the validated answer; no word-by-word effect, whatever the motion setting.
    const stageLine = el('span', { class: 'thinking' }, 'Cevap hazırlanıyor');
    const skeleton = el('div', { class: 'skeletons' }, el('div', { class: 'skeleton line' }), el('div', { class: 'skeleton line' }),
      el('div', { class: 'skeleton line' }));
    const draft = el('div', { class: 'answer-text draft', hidden: true });
    let written = '';
    let streamed = false;
    const live = {
      // ADR-0056: the model the user picked, only when the admin offers a choice; otherwise the field is not sent.
      model: modelPicker.value || null,
      stage: (event) => {
        streamed = true;
        const now = stageText(event);
        stageLine.textContent = now;
        announcer.textContent = `${now}.`;
      },
      delta: (text) => {
        written += text;
        draft.textContent = provisionalText(written);
        if (draft.hidden) {
          draft.hidden = false;
          skeleton.hidden = true;
          waiting.hidden = false;
        }
      },
      fallback: () => {
        streamed = false;
      },
    };
    const waiting = el('div', { class: 'waiting', hidden: true },
      el('div', { class: 'status' }, el('span', { class: 'spinner' }), stageLine, elapsed),
      skeleton, draft, expect);
    const pending = turn(question, waiting);
    const bot = pending.querySelector('.turn-bot');
    thread.append(pending);
    pending.scrollIntoView({ behavior: reducedMotion() ? 'auto' : 'smooth', block: 'end' });
    announcer.textContent = 'Cevap hazırlanıyor.';
    ticker = setInterval(() => {
      const ms = Date.now() - started;
      waiting.hidden = ms < SHOW_WAIT_MS && draft.hidden;
      if (ms >= SLOW_MS) {
        elapsed.textContent = `· ${Math.round(ms / 1000)} sn`;
        expect.textContent = lastMode === 'cloud' ? 'Bulut modelinde cevap genellikle birkaç saniye sürer.'
          : 'Yerel modelde, ekran kartı yoksa cevap 20–60 sn sürebilir.';
      }
    }, 250);
    let entry;
    try {
      let result;
      let notice = null;
      try {
        result = await ctx.withAuth(() => api.askLive(question, controller.signal, sentTo, live));
      } catch (error) {
        if (!(sentTo && startsOver(error))) throw error;
        // The conversation is gone or full: refused before any model call, so the question goes to a new one.
        notice = startOverNote(error);
        result = await ctx.withAuth(() => api.askLive(question, controller.signal, null, live));
      }
      entry = { question, result, ms: Date.now() - started, at: new Date().toISOString(), feedback: null, notice, streamed };
      if (current.generation === generation) {
        // Always the answer's own id: the server may have started a new conversation (deleted meanwhile, expired).
        const next = continueWith(notice ? null : sentTo, result);
        if (next !== sentTo) current.title = null;
        current.conversationId = next;
      }
      prefs.markOnboarding({ asked: true });
    } catch (error) {
      entry = error?.name === 'AbortError'
        ? { question, cancelled: true, streamed, ms: Date.now() - started, at: new Date().toISOString() }
        : { question, error: errorState(error), ms: Date.now() - started, at: new Date().toISOString() };
    } finally {
      clearInterval(ticker);
      setBusy(null);
      ctx.showMode();
    }
    // Another conversation was put in place meanwhile ("Yeni konuşma", "Geçmiş"): this answer is stored in its own
    // conversation on the server and is not appended to the one now open.
    if (current.generation !== generation) return;
    conversation.push(entry);
    if (!ctx.isCurrent()) return;
    updateBar();
    announcer.textContent = entry.cancelled ? (entry.streamed ? 'Cevap durduruldu.' : 'Bekleme durduruldu.') : entry.error ? `Cevap alınamadı: ${entry.error.title}.`
      : isNotFound(entry.result) ? 'Belgelerde bulunamadı.' : entry.result.outcome === 'UNCITED' ? 'Cevap hazır, kaynak gösterilemedi.' : 'Cevap hazır.';
    bot.replaceWith(botTurn(answerView({ ...entry, fresh: !entry.streamed }, actions)));
    field.focus();
  }

  main.replaceChildren(chat);
  drawThread();
  updateBar();
  thread.lastElementChild?.scrollIntoView({ block: 'end' });
  if (ctx.params?.question) ask(ctx.params.question);
  else field.focus();
  // Leaving the screen does not cancel a question: its answer is kept in the conversation for the next visit.
  return () => {
    clearInterval(ticker);
    closePageDrawer();
    document.removeEventListener('selectionchange', onSelection);
    removeEventListener('scroll', onScroll);
    viewport?.removeEventListener('resize', onViewport);
    viewport?.removeEventListener('scroll', onViewport);
    document.documentElement.classList.remove('keyboard-open');
    setTimeout(prune, 1000); // after the next screen replaced this one (view transitions swap a frame later)
  };
}
