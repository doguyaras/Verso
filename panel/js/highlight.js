// In-text highlighting for the answer screen (ADR-0024) with the CSS Custom Highlight API: ranges are registered in
// CSS.highlights and painted by ::highlight() rules, so the text nodes stay as they are (copying, selection and screen
// readers see plain text) and nothing is written as markup. Where the API is missing, the matched words of an excerpt
// are wrapped in <mark> elements built with createElement (still no HTML strings: the CSP's Trusted Types rule holds),
// and the answer sentence of a hovered source is simply not tinted; the [n] buttons still light up.

/** ::highlight(verso-match): the excerpt words the answer shares. ::highlight(verso-cited): the citing sentence. */
export const MATCH = 'verso-match';
export const CITED = 'verso-cited';

const supported = () => typeof CSS !== 'undefined' && CSS.highlights !== undefined && typeof Highlight === 'function';

function registry(name) {
  let highlight = CSS.highlights.get(name);
  if (!highlight) {
    highlight = new Highlight();
    CSS.highlights.set(name, highlight);
  }
  return highlight;
}

/**
 * Writes `text` into `node` with the given [{ start, end }] ranges marked as matches. With the API the node gets one
 * text node and the ranges are registered; without it the matches become <mark> elements.
 */
export function writeMarked(node, text, ranges) {
  if (supported()) {
    const textNode = document.createTextNode(text);
    node.replaceChildren(textNode);
    const highlight = registry(MATCH);
    for (const { start, end } of ranges) {
      const range = new Range();
      range.setStart(textNode, start);
      range.setEnd(textNode, end);
      highlight.add(range);
    }
    return;
  }
  const parts = [];
  let last = 0;
  for (const { start, end } of ranges) {
    if (start > last) parts.push(document.createTextNode(text.slice(last, start)));
    const mark = document.createElement('mark');
    mark.className = 'match';
    mark.textContent = text.slice(start, end);
    parts.push(mark);
    last = end;
  }
  if (last < text.length) parts.push(document.createTextNode(text.slice(last)));
  node.replaceChildren(...parts);
}

/**
 * Tints the answer sentences given as [{ start, end }] offsets of the answer, over the text segments the answer was
 * drawn from ([{ node: Text, start }], in order). An empty list clears the tint. No-op without the API.
 */
export function tintSentences(segments, spans) {
  if (!supported()) return;
  const highlight = registry(CITED);
  highlight.clear();
  for (const span of spans) {
    for (const { node, start } of segments) {
      const end = start + node.data.length;
      if (end <= span.start || start >= span.end) continue;
      const range = new Range();
      range.setStart(node, Math.max(0, span.start - start));
      range.setEnd(node, Math.min(node.data.length, span.end - start));
      highlight.add(range);
    }
  }
}

/** Drops ranges whose text left the page (a redrawn thread, another screen), so the registry does not grow. */
export function prune() {
  if (!supported()) return;
  for (const name of [MATCH, CITED]) {
    const highlight = CSS.highlights.get(name);
    if (!highlight) continue;
    for (const range of [...highlight]) if (!range.startContainer.isConnected) highlight.delete(range);
  }
}
