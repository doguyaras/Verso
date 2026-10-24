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

/**
 * answerParts with the offset of each text part in the answer ({ text, start }): the answer screen tints sentences by
 * their offsets over the text it drew (ADR-0024). Text parts are exact slices, so answer.slice(start, …) === text.
 */
export function answerSegments(answer, citations) {
  const marker = /\[(\d+)\]/y;
  let cursor = 0;
  return answerParts(answer, citations).map((part) => {
    if (part.citation) {
      marker.lastIndex = cursor;
      cursor += marker.exec(answer)?.[0].length ?? 0;
      return part;
    }
    const start = cursor;
    cursor += part.text.length;
    return { text: part.text, start };
  });
}

export const STATUS = Object.freeze({
  PENDING: { label: 'Sırada', tone: 'wait' },
  PROCESSING: { label: 'İşleniyor', tone: 'wait' },
  READY: { label: 'Hazır', tone: 'ok' },
  FAILED: { label: 'Hata', tone: 'bad' },
});

export const FAILURES = Object.freeze({
  NOT_A_PDF: 'PDF değil',
  INVALID_FILE: 'Dosya okunamadı (bozuk ya da türü yanlış)',
  UNSUPPORTED_FILE: 'Desteklenmeyen Word yapısı',
  ENCRYPTED: 'Parolalı PDF',
  TOO_MANY_PAGES: 'Çok fazla sayfa ya da bölüm (en çok 500)',
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

/** What the file picker offers (ADR-0016). Only a hint: the server decides from the name and the first bytes. */
export const UPLOAD_TYPES = Object.freeze(['.pdf', '.docx', '.txt', '.md', '.markdown']);

const FORMAT_LABELS = Object.freeze({ PDF: 'PDF', DOCX: 'Word', TXT: 'Metin', MD: 'Markdown' });

/** The document type for the table; a value this panel does not know is just "Belge" (integration document). */
export function formatLabel(format) {
  return FORMAT_LABELS[format] ?? 'Belge';
}

/** "dosya.pdf, sayfa 3" or "notlar.docx, bölüm 2": a file without pages is cited by section (ADR-0016). */
export function sourceLabel(citation) {
  return `${citation.fileName}, ${citation.unit === 'SECTION' ? 'bölüm' : 'sayfa'} ${citation.page}`;
}

/**
 * Text folded for searching Turkish: "İzin", "izin", "IZIN" and "ızın" all match, and so do "şirket" and "sirket".
 * Dotted and dotless i collapse to "i", the other letters lose their marks (ç→c, ğ→g, ö→o, ş→s, ü→u).
 */
export function foldTr(text) {
  return String(text).toLocaleLowerCase('tr').replace(/ı/g, 'i').normalize('NFD').replace(/\p{Mn}/gu, '');
}

/**
 * A fuzzy match score for the command palette: 0 when the query's letters do not appear in order, higher for a
 * prefix, a word start or consecutive letters. Both sides are folded (foldTr).
 */
export function fuzzyScore(query, text) {
  const q = foldTr(query).replace(/\s+/g, '');
  if (!q) return 1;
  const t = foldTr(text);
  if (t.startsWith(q)) return 100 + q.length;
  const at = t.indexOf(q);
  if (at >= 0) return (t[at - 1] === ' ' ? 80 : 60) + q.length;
  let score = 0;
  let from = 0;
  let previous = -2;
  for (const letter of q) {
    const found = t.indexOf(letter, from);
    if (found < 0) return 0;
    score += found === previous + 1 ? 3 : found === 0 || t[found - 1] === ' ' ? 2 : 1;
    previous = found;
    from = found + 1;
  }
  return score;
}

/** Seconds for the answer footer: one decimal below ten ("1,3 sn"), whole seconds above ("23 sn"). */
export function formatSeconds(ms) {
  const seconds = ms / 1000;
  return seconds < 10 ? `${seconds.toFixed(1).replace('.', ',')} sn` : `${Math.round(seconds)} sn`;
}

/**
 * The answer as plain text for the clipboard, with its sources listed under it: "[1] izin.pdf, sayfa 3". Markers
 * stay as they are, so the copied text still points to its sources (NotebookLM-style copy).
 */
export function answerWithSources(result) {
  if (!result.citations?.length) return result.answer;
  return `${result.answer}\n\nKaynaklar:\n${result.citations.map((c) => `[${c.number}] ${sourceLabel(c)}`).join('\n')}`;
}

/** Citations grouped by document for the source cards, in order of first use: [{ fileName, items: [citation] }]. */
export function groupSources(citations) {
  const groups = new Map();
  for (const citation of citations) {
    const key = citation.documentId ?? citation.fileName;
    if (!groups.has(key)) {
      groups.set(key, { documentId: citation.documentId, fileName: citation.fileName,
        collectionName: citation.collectionName ?? null, items: [] });
    }
    groups.get(key).items.push(citation);
  }
  return [...groups.values()];
}

/** The time-of-day greeting for the overview. */
export function greeting(hour) {
  if (hour >= 5 && hour < 12) return 'Günaydın';
  if (hour >= 12 && hour < 18) return 'İyi günler';
  return 'İyi akşamlar';
}

/**
 * Replaces a node's children, skipping null and false like el() does. Plain replaceChildren() would turn a null
 * (an optional card that is not shown) into the text "null".
 */
export function fill(node, ...children) {
  node.replaceChildren(...children.flat().filter((child) => child !== null && child !== undefined && child !== false));
  return node;
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
