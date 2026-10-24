// The 👍/👎 rating of one answer (ADR-0023, ADR-0025) as a small state machine without the DOM. One rating per
// answer: rating again replaces the previous one on the server. After 👎 an optional reason is picked inline; "Yanlış
// kaynak" also names the wrong citation [n] when the answer has more than one. No free text (ADR-0023 §5).

/** The API's reasons in its own order; an unknown value reads "Diğer" (docs/api-conversations-integration-v1.md). */
export const REASONS = Object.freeze([
  Object.freeze({ id: 'WRONG_SOURCE', label: 'Yanlış kaynak' }),
  Object.freeze({ id: 'INCORRECT_OR_MISSING', label: 'Eksik ya da yanlış' }),
  Object.freeze({ id: 'OUTDATED', label: 'Güncel değil' }),
  Object.freeze({ id: 'OTHER', label: 'Diğer' }),
]);

export function reasonLabel(id) {
  return REASONS.find((r) => r.id === id)?.label ?? 'Diğer';
}

/** The reasons an answer can be given: "Yanlış kaynak" only when it cites something. */
export function reasonsFor(citations) {
  return citations?.length ? REASONS : REASONS.filter((r) => r.id !== 'WRONG_SOURCE');
}

/** The starting state from the server's stored rating (TurnFeedback or null). step: null | 'reason' | 'citation'. */
export function initialFeedback(saved) {
  return { rating: saved?.rating ?? null, reason: saved?.reason ?? null, citationNumber: saved?.citationNumber ?? null, step: null };
}

const numbersOf = (citations) => [...new Set((citations ?? []).map((c) => c.number))].sort((a, b) => a - b);

/**
 * One step: (state, event, citations) → { state, request }. `request` is the body to POST, or null when nothing is
 * sent. Events: up, down, reason { reason }, citation { number }, close, saved { feedback }.
 * - 👍 on an answer already rated 👍 sends nothing (the API has no "unrate").
 * - 👎 sends DOWN at once and opens the reason picker; 👎 again only opens or closes the picker.
 * - A reason replaces the DOWN with DOWN + reason; "Yanlış kaynak" asks which [n] when there are several.
 */
export function rate(state, event, citations = []) {
  switch (event.type) {
    case 'up':
      if (state.rating === 'UP') return { state: { ...state, step: null }, request: null };
      return { state: { rating: 'UP', reason: null, citationNumber: null, step: null }, request: { rating: 'UP' } };
    case 'down':
      if (state.rating === 'DOWN') return { state: { ...state, step: state.step ? null : 'reason' }, request: null };
      return { state: { rating: 'DOWN', reason: null, citationNumber: null, step: 'reason' }, request: { rating: 'DOWN' } };
    case 'reason': {
      if (state.rating !== 'DOWN' || !REASONS.some((r) => r.id === event.reason)) return { state, request: null };
      if (event.reason === 'WRONG_SOURCE') {
        const numbers = numbersOf(citations);
        if (!numbers.length) return { state, request: null };
        if (numbers.length > 1) return { state: { ...state, step: 'citation' }, request: null };
        return choose(state, numbers[0]);
      }
      return { state: { rating: 'DOWN', reason: event.reason, citationNumber: null, step: null },
        request: { rating: 'DOWN', reason: event.reason } };
    }
    case 'citation':
      if (state.rating !== 'DOWN' || !numbersOf(citations).includes(event.number)) return { state, request: null };
      return choose(state, event.number);
    case 'close':
      return { state: { ...state, step: null }, request: null };
    case 'saved':
      return { state: { ...initialFeedback(event.feedback), step: state.step }, request: null };
    default:
      return { state, request: null };
  }
}

function choose(state, number) {
  return { state: { rating: 'DOWN', reason: 'WRONG_SOURCE', citationNumber: number, step: null },
    request: { rating: 'DOWN', reason: 'WRONG_SOURCE', citationNumber: number } };
}

/** The screen-reader line after a saved rating. */
export function thanks(state) {
  if (state.rating === 'DOWN' && state.reason) return `Teşekkürler. Neden: ${reasonLabel(state.reason)}${state.citationNumber ? ` [${state.citationNumber}]` : ''}.`;
  return 'Teşekkürler.';
}
