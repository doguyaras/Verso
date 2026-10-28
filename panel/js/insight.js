// In-answer insight cards (ADR-0058, concept C): the insight appears inside the answer it concerns instead of waiting
// on a dashboard. "Bu kaynak eskimiş" when a cited document is older than the knowledge-health stale threshold (the
// server's value from /v1/info) or the verified answer's verification ran out; "Belgeler arasında çelişki" from the
// contradictions the server already knew (ADR-0055). "Onaylı cevap mevcut" is not shown: knowing that a card exists
// below the serving threshold needs a second lookup on the hot path, which ADR-0058 leaves out on purpose.
import { reasoningModel } from './reasoning.js';
import { sourceLabel } from './render.js';

/** The threshold when the server does not say (QaDiagnosticsProperties.staleAfter's default). */
export const DEFAULT_STALE_DAYS = 180;
export const MAX_CARDS = 3;
const DAY_MS = 86_400_000;

/** The stale threshold in days from /v1/info, or the default for an older server. */
export function staleDays(info) {
  const days = info?.staleAfterDays;
  return Number.isInteger(days) && days > 0 && days <= 3650 ? days : DEFAULT_STALE_DAYS;
}

/** Months between two instants, rounded down, at least 1 (for "N aydır"). */
function monthsBetween(then, now) {
  return Math.max(1, Math.floor((now - then) / (30 * DAY_MS)));
}

/**
 * The cited documents worth a look-up: distinct, not deleted and still readable (only those have a date the caller
 * may see), at most five, in citation order.
 */
export function citedDocuments(result) {
  const ids = [];
  for (const c of Array.isArray(result?.citations) ? result.citations : []) {
    if (!c?.documentId || c.deleted || c.inaccessible || ids.includes(c.documentId)) continue;
    ids.push(c.documentId);
  }
  return ids.slice(0, 5);
}

/**
 * The cards of one answer. `documents` maps a document id to what GET /v1/documents/{id} returned for this caller
 * (missing: not looked up or not readable, then no card). Each card: { kind: 'stale' | 'conflict', title, text,
 * sources: [string], note }.
 */
export function insightCards(result, { documents = new Map(), staleAfterDays = DEFAULT_STALE_DAYS, now = Date.now() } = {}) {
  const cards = [];
  const limit = staleAfterDays * DAY_MS;
  const citations = Array.isArray(result?.citations) ? result.citations : [];
  for (const id of citedDocuments(result)) {
    const doc = documents.get(id);
    const changed = new Date(doc?.updatedAt ?? doc?.createdAt).getTime();
    if (!Number.isFinite(changed) || now - changed < limit) continue;
    const cited = citations.filter((c) => c.documentId === id).map((c) => c.number);
    const first = citations.find((c) => c.documentId === id);
    cards.push({ kind: 'stale', title: 'Bu kaynak eskimiş',
      text: `Kaynak ${cited.map((n) => `[${n}]`).join(', ')} ${monthsBetween(changed, now)} aydır güncellenmedi; `
        + 'cevap eski bir bilgiye dayanıyor olabilir.',
      sources: [sourceLabel(first)], note: 'Belgenin sahibine güncel olup olmadığını sorun.' });
  }
  const expires = new Date(result?.memory?.expiresAt).getTime();
  if (Number.isFinite(expires) && expires < now) {
    cards.push({ kind: 'stale', title: 'Onaylı cevabın süresi dolmuş',
      text: 'Bu cevap uzmanların doğruladığı bir karttan geldi, ama doğrulamanın süresi doldu.', sources: [],
      note: 'Bilgi sahibi yeniden doğrulayana kadar dikkatle kullanın.' });
  }
  for (const note of reasoningModel(result)?.contradictions ?? []) {
    cards.push({ kind: 'conflict', title: 'Belgeler arasında çelişki', text: note.text, sources: note.sources,
      note: note.origin });
  }
  return cards.slice(0, MAX_CARDS);
}
