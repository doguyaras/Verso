// How a multi-step answer was researched (ADR-0055), without the DOM: the sub-questions for the collapsible
// "Nasıl araştırıldı" box, the parts the documents held nothing for, and the contradictions Verso already knew between
// two cited passages. Sub-questions are model text: here they only become plain, single-line, clipped strings; the
// screen puts them in as text content, never as markup, and nothing here is logged (llm-rules 2.1, 3.2).
import { sourceLabel } from './render.js';

/** Longest sub-question shown, in code points; the server's own bound is 200 characters. */
export const MAX_SUB_QUESTION = 160;
/** The server never sends more than three sub-questions; a client shows no more than that either. */
export const MAX_STEPS = 3;

function oneLine(value) {
  return String(value ?? '').replace(/[\p{Cc}\p{Cf}]/gu, ' ').replace(/\s+/g, ' ').trim();
}

function clipped(text, max) {
  const chars = [...text];
  return chars.length <= max ? text : `${chars.slice(0, max - 1).join('').trimEnd()}…`;
}

/** "“X” için belgelerde bilgi bulunamadı." as the server writes it, from the sub-question without its final mark. */
export function missingText(question) {
  const topic = oneLine(question).replace(/[?.\s]+$/u, '');
  return `“${clipped(topic, MAX_SUB_QUESTION)}” için belgelerde bilgi bulunamadı.`;
}

/**
 * The view model of `result.reasoning`, or null for a single-pass answer:
 *   { title, summary, steps: [{ number, text, found, sources }], missing: [string], contradictions: [{ text, sources }] }.
 * A step's sources are the citation numbers it brought ("[1], [2]"); a contradiction names both cited sources by file
 * and page. Citation numbers that the answer does not list are left out (the server validated them; a stale client
 * state must not invent a source).
 */
export function reasoningModel(result) {
  const trace = result?.reasoning;
  if (!trace || !Array.isArray(trace.steps) || trace.steps.length === 0) return null;
  const citations = new Map((result.citations ?? []).map((c) => [c.number, c]));
  const steps = trace.steps.slice(0, MAX_STEPS).map((step, index) => {
    const number = Number.isInteger(step.number) ? step.number : index + 1;
    const cited = (Array.isArray(step.citations) ? step.citations : []).filter((n) => citations.has(n));
    return { number, text: clipped(oneLine(step.question), MAX_SUB_QUESTION), found: step.found === true,
      sources: cited.map((n) => `[${n}]`).join(', ') };
  });
  const missing = trace.steps.slice(0, MAX_STEPS).filter((step) => step.found !== true).map((step) => missingText(step.question));
  const contradictions = (Array.isArray(trace.contradictions) ? trace.contradictions : [])
    .filter((note) => citations.has(note.first) && citations.has(note.second) && note.first !== note.second)
    .map((note) => {
      const first = citations.get(note.first);
      const second = citations.get(note.second);
      return { text: `[${note.first}] ile [${note.second}] aynı konuda farklı bilgi veriyor; ikisini de kontrol edin.`,
        sources: [`[${note.first}] ${sourceLabel(first)}`, `[${note.second}] ${sourceLabel(second)}`],
        origin: note.source === 'SYSTEM_MODEL' ? 'Sistem modeli: aynı terimin iki farklı tanımı' : 'Bilgi sağlığı taraması' };
    });
  const found = steps.filter((s) => s.found).length;
  return { title: 'Nasıl araştırıldı',
    summary: `Soru ${steps.length} alt soruya ayrıldı; ${found} tanesi için belgelerde bilgi bulundu.`,
    steps, missing, contradictions };
}
