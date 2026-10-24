// The 👍/👎 controls under an answer (ADR-0025) around the state machine of feedback.js: two icon buttons with
// aria-pressed, an inline reason picker after 👎 (and, for "Yanlış kaynak", which [n]), a short "Teşekkürler" in a
// polite live region. Requests go out one after the other, so the last click is the rating the server keeps. Built with
// createElement; the reason labels are the panel's own text, the citation chips plain text.
import { api, ApiError } from './api.js';
import { chipLabel } from './answer.js';
import { initialFeedback, rate, reasonsFor, thanks } from './feedback.js';
import { icon } from './icons.js';
import { el } from './render.js';
import { toast } from './ui.js';

/** How long "Teşekkürler" stays (it is also announced politely). */
export const THANKS_MS = 2500;

let ids = 0;

/**
 * The rating of one answer: { controls, picker }, or null when the answer cannot be rated (the server could not store
 * its turn, so it has no turnId). entry.feedback follows the saved rating, so a redraw of the thread shows it.
 */
export function ratingControls(entry, { withAuth }) {
  const result = entry.result ?? {};
  if (!result.conversationId || !result.turnId) return null;
  const citations = result.citations ?? [];
  let state = initialFeedback(entry.feedback);
  let queue = Promise.resolve();
  let timer = 0;
  const pickerId = `reasons-${++ids}`;

  const up = el('button', { class: 'icon-button rate', type: 'button', 'aria-label': 'Faydalı', title: 'Faydalı',
    onclick: () => step({ type: 'up' }) }, icon('thumbUp', 'sm'));
  const down = el('button', { class: 'icon-button rate', type: 'button', 'aria-label': 'Faydalı değil', title: 'Faydalı değil',
    'aria-controls': pickerId, onclick: () => step({ type: 'down' }) }, icon('thumbDown', 'sm'));
  const said = el('span', { class: 'rate-thanks', role: 'status', 'aria-live': 'polite' });
  const controls = el('div', { class: 'rating', role: 'group', 'aria-label': 'Cevabı değerlendirin' }, up, down, said);
  const picker = el('div', { class: 'reasons', id: pickerId, role: 'group', hidden: true });

  function draw() {
    up.setAttribute('aria-pressed', String(state.rating === 'UP'));
    down.setAttribute('aria-pressed', String(state.rating === 'DOWN'));
    picker.hidden = !state.step;
    if (state.step === 'citation') {
      picker.setAttribute('aria-label', 'Hangi kaynak yanlış?');
      picker.replaceChildren(el('span', { class: 'reasons-label' }, 'Hangi kaynak yanlış?'),
        ...[...new Map(citations.map((c) => [c.number, c])).values()].sort((a, b) => a.number - b.number).map((c) =>
          el('button', { class: 'chip', type: 'button', 'aria-pressed': String(state.citationNumber === c.number),
            onclick: () => step({ type: 'citation', number: c.number }) }, `[${c.number}] ${chipLabel(c)}`)),
        el('button', { class: 'button ghost sm', type: 'button', onclick: () => step({ type: 'down' }) }, 'Vazgeç'));
    } else if (state.step === 'reason') {
      picker.setAttribute('aria-label', 'Neden faydalı değil?');
      picker.replaceChildren(el('span', { class: 'reasons-label' }, 'Neden?'),
        ...reasonsFor(citations).map((r) => el('button', { class: 'chip', type: 'button', 'aria-pressed': String(state.reason === r.id),
          onclick: () => step({ type: 'reason', reason: r.id }) }, r.label)),
        el('button', { class: 'icon-button', type: 'button', 'aria-label': 'Nedeni kapat', title: 'Kapat',
          onclick: () => {
            step({ type: 'close' });
            down.focus();
          } }, icon('x', 'sm')));
    }
  }

  function say(text) {
    said.textContent = text;
    clearTimeout(timer);
    timer = setTimeout(() => {
      said.textContent = '';
    }, THANKS_MS);
  }

  function step(event) {
    const before = state;
    const next = rate(state, event, citations);
    state = next.state;
    draw();
    if (next.request) send(next.request, before);
  }

  function send(body, before) {
    queue = queue.then(async () => {
      try {
        const saved = await withAuth(() => api.rate(result.conversationId, result.turnId, body));
        entry.feedback = saved;
        state = rate(state, { type: 'saved', feedback: saved }).state;
        draw();
        say(thanks(state));
      } catch (error) {
        if (error instanceof ApiError && error.code === 11021) {
          // The answer was deleted (another tab, retention): it can no longer be rated.
          result.turnId = null;
          controls.remove();
          picker.remove();
          toast('Bu cevap artık geçmişte yok; puanlanamaz.', 'info');
          return;
        }
        state = { ...before, step: null };
        draw();
        if (!(error instanceof ApiError && error.status === 401)) toast(error instanceof ApiError ? error.message : 'Puan gönderilemedi; bağlantınızı kontrol edin.');
      }
    });
  }

  draw();
  return { controls, picker };
}
