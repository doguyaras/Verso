// The page viewer of a citation (ADR-0035) without the DOM: page navigation within the document's bounds, the
// heading, and where the cited excerpt lies in the stored page text. The page text is document text: it is only
// written as text nodes (highlight.js) and never logged (llm-rules 2.1).
import { excerptParts } from './excerpt.js';

/** The previous and next page numbers inside 1..count, or null at the ends (an unknown count has no "next"). */
export function pageNav(number, count) {
  return {
    prev: number > 1 ? number - 1 : null,
    next: Number.isInteger(count) && number < count ? number + 1 : null,
  };
}

/** "Sayfa 3 / 12", or "Bölüm 2 / 5" for a document without pages (ADR-0016). */
export function pageHeading(unit, number, count) {
  const word = unit === 'SECTION' ? 'Bölüm' : 'Sayfa';
  return Number.isInteger(count) ? `${word} ${number} / ${count}` : `${word} ${number}`;
}

/** The text with every run of white space as one space, and for each kept character its offset in the text. */
export function collapse(text) {
  let out = '';
  const offsets = [];
  let space = false;
  for (let i = 0; i < text.length; i++) {
    if (/\s/.test(text[i])) {
      if (out.length && !space) {
        out += ' ';
        offsets.push(i);
      }
      space = true;
      continue;
    }
    space = false;
    out += text[i];
    offsets.push(i);
  }
  return { out, offsets };
}

/** Shortest prefix (in words) of an excerpt still worth looking for when the whole one is not on the page. */
const MIN_WORDS = 4;

/**
 * Where the cited excerpt lies in the page text, as { start, end } offsets of the page text, or null. The excerpt is
 * white-space collapsed and may carry the server's "…" cut marks (ADR-0020), so both are compared collapsed and the
 * match is mapped back. When the whole excerpt is not found (a page re-extracted since), its longest leading run of
 * words that is found is marked, down to a few words.
 */
export function excerptRange(pageText, excerpt) {
  const needle = collapse(excerptParts(excerpt).text.trim()).out.trim();
  if (!needle || !pageText) return null;
  const hay = collapse(String(pageText));
  const words = needle.split(' ');
  for (let n = words.length; n >= Math.min(MIN_WORDS, words.length); n--) {
    const part = words.slice(0, n).join(' ');
    const at = hay.out.indexOf(part);
    if (at >= 0) return { start: hay.offsets[at], end: hay.offsets[at + part.length - 1] + 1 };
  }
  return null;
}
