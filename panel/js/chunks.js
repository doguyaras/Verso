// The chunk inspector (ADR-0036) without the DOM: paging the API's chunk pages, the search over the loaded chunks, the
// heading a chunk starts with and its short preview. Chunk text is document text: it is only ever written as text
// nodes and never logged (llm-rules 2.1).
import { foldTr } from './render.js';
import { pageHeading } from './pageview.js';

/** Chunks per request; the API's cap is 100 (docs/api-documents-integration-v1.md). */
export const CHUNK_PAGE_SIZE = 50;
/** Characters of a chunk shown in the list; the whole page opens in the page viewer. */
export const PREVIEW_CHARS = 280;
/** A first line up to this length, without closing punctuation, reads as a heading. */
const HEADING_MAX = 90;

/**
 * The heading a chunk opens with, when it has one: a Markdown heading line, or a short first line that does not end
 * like a sentence and is followed by more text (DOCX and PDF headings are lines of their own). Null otherwise.
 */
export function chunkHeading(text) {
  const lines = String(text ?? '').split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  if (!lines.length) return null;
  const markdown = /^#{1,6}\s+(.+)$/.exec(lines[0]);
  if (markdown) return markdown[1].replace(/\s+#*$/, '').trim() || null;
  const first = lines[0];
  if (lines.length > 1 && first.length <= HEADING_MAX && !/[.,;:!?…)"”’]$/.test(first)) return first;
  return null;
}

/** The chunk's first characters on one line, cut on a word with "…" when longer. */
export function chunkPreview(text, max = PREVIEW_CHARS) {
  const flat = String(text ?? '').replace(/\s+/g, ' ').trim();
  if (flat.length <= max) return flat;
  const cut = flat.slice(0, max);
  const space = cut.lastIndexOf(' ');
  return `${(space > max * 0.6 ? cut.slice(0, space) : cut).trimEnd()}…`;
}

/** "Sayfa 3 · 1.234 karakter", or "Bölüm 2 · …" for a document without pages (ADR-0016). */
export function chunkLabel(chunk) {
  const chars = new Intl.NumberFormat('tr-TR').format(chunk?.charCount ?? 0);
  return `${pageHeading(chunk?.unit, chunk?.number, null)} · ${chars} karakter`;
}

/**
 * The loaded list after one more page from the API: chunks in their order, each index once (a page asked twice, or a
 * document re-indexed meanwhile, does not repeat rows), and whether the API has more pages.
 */
export function mergeChunks(loaded, page) {
  const byIndex = new Map((loaded ?? []).map((chunk) => [chunk.index, chunk]));
  for (const chunk of page?.data ?? []) byIndex.set(chunk.index, chunk);
  const chunks = [...byIndex.values()].sort((a, b) => a.index - b.index);
  const info = page?.page ?? {};
  const more = Number.isInteger(info.number) && Number.isInteger(info.totalPages) && info.number + 1 < info.totalPages;
  return { chunks, more, nextPage: more ? info.number + 1 : null, total: Number.isFinite(info.totalElements) ? info.totalElements : chunks.length };
}

/**
 * The loaded chunks that contain every word of the query, Turkish letters folded both ways (İ/i, ı/I, ş/s, …) and case
 * ignored; an empty query keeps them all. Only loaded chunks are searched; the screen says so.
 */
export function filterChunks(chunks, query) {
  const words = foldTr(String(query ?? '')).split(/\s+/).filter(Boolean);
  if (!words.length) return [...(chunks ?? [])];
  return (chunks ?? []).filter((chunk) => {
    const hay = foldTr(chunk.text ?? '');
    return words.every((word) => hay.includes(word));
  });
}
