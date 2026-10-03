// Pure rendering helpers. Model output, file names and error texts are untrusted: they reach the page only as text
// nodes (textContent), never as HTML (llm-rules 3.2: model output is never executed, not even by the browser).

/**
 * Splits an answer into text and citation parts: "[n]" markers that the server mapped to a citation become citation
 * parts; any other "[n]" stays text (the server already removed unknown ones; docs/api-questions-integration-v1.md).
 */
export function answerParts(answer, citations) {
  const byNumber = new Map(citations.map((c) => [c.number, c]));
  const parts = [];
  let last = 0;
  for (const match of answer.matchAll(/\[(\d+)\]/g)) {
    const citation = byNumber.get(Number(match[1]));
    if (!citation) continue;
    if (match.index > last) parts.push({ text: answer.slice(last, match.index) });
    parts.push({ citation });
    last = match.index + match[0].length;
  }
  if (last < answer.length) parts.push({ text: answer.slice(last) });
  return parts;
}

export const STATUS = Object.freeze({
  PENDING: { label: 'Sırada', tone: 'wait' },
  PROCESSING: { label: 'İşleniyor', tone: 'wait' },
  READY: { label: 'Hazır', tone: 'ok' },
  FAILED: { label: 'Hata', tone: 'bad' },
});

export const FAILURES = Object.freeze({
  NOT_A_PDF: 'PDF değil',
  ENCRYPTED: 'Parolalı PDF',
  TOO_MANY_PAGES: 'Çok fazla sayfa (en çok 500)',
  TOO_MUCH_TEXT: 'Çok fazla metin',
  UNSUPPORTED_PDF: 'Desteklenmeyen PDF',
  NO_TEXT: 'Metin yok (taranmış olabilir)',
  PROCESSING_FAILED: 'İşlenemedi',
});

export function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} MB`;
}

export function formatDate(iso) {
  return new Intl.DateTimeFormat('tr-TR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
}

/**
 * A file name for prompts and lists without control or format characters: bidi overrides (U+202E) and the like can
 * make "fatura.pdf" read as something else in a confirm() dialog or a table cell.
 */
export function plainName(name) {
  return String(name).replace(/[\p{Cc}\p{Cf}]/gu, '');
}

/** Builds an element: el('div', {class: 'x'}, 'text', child). Strings become text nodes, never HTML. */
export function el(tag, attributes = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attributes)) {
    if (value === undefined || value === null || value === false) continue;
    if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? '' : String(value));
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}
