// Which words of a citation's excerpt support the answer (ADR-0020, ADR-0024). The server picks the excerpt as the
// passage window that shares the most terms with the answer sentences citing it (qa-core ExcerptSelector); the panel
// marks those shared terms in the excerpt so the reader sees at once why the passage was shown. The matching rules are
// the server's, ported as they are: the same Turkish folding ("İZİN", "izin" and "ızın" are one word), the same
// five-letter stem, numbers by their digits only, the same stop words, and the same sentence boundaries. Pure functions
// without the DOM; the excerpt and the answer are untrusted text and stay text (llm-rules 3.2).

/** Words of at least this many letters match on their first STEM letters: Turkish suffixes vary the rest. */
export const STEM = 5;

// Folded (see fold), before stemming: function words and the model's own framing ("belgeye göre"). The server's list.
export const STOP_WORDS = new Set([
  've', 'veya', 'ile', 'ya', 'da', 'de', 'ki', 'mi', 'mu', 'bir', 'bu', 'su', 'icin', 'gibi', 'daha',
  'en', 'cok', 'her', 'ise', 'olan', 'olarak', 'kadar', 'ancak', 'ama', 'fakat', 'hem', 'ayrica', 'yani',
  'gore', 'belge', 'belgede', 'belgeye', 'belgelere', 'belgelerde', 'pasaj', 'pasajda',
  'the', 'of', 'to', 'and', 'for', 'with',
]);

// A number keeps its inner separators (25.000, 3,2) so it stays one token; a word is a run of letters.
const TOKEN = /\p{Nd}+(?:[.,]\p{Nd}+)*|\p{L}[\p{L}\p{M}]*/gu;
const DIGIT = /\p{Nd}/u;

/**
 * Lower case with the Turkish locale ("İ" → "i", "I" → "ı"), then without diacritics and with dotless i folded:
 * ş→s, ğ→g, ç→c, ö→o, ü→u, ı→i, â→a. The same steps as ExcerptSelector.fold (NFKC first, every mark removed).
 */
export function fold(word) {
  return String(word).normalize('NFKC').toLocaleLowerCase('tr').replace(/ı/g, 'i').normalize('NFD').replace(/\p{M}+/gu, '');
}

/**
 * The value of a decimal digit of any script (Java's Character.digit). Unicode assigns decimal digits only in runs that
 * start at zero, so the value is the distance from the start of the run, modulo ten (runs of runs, like the
 * mathematical digits, stay aligned).
 */
function digitValue(codePoint) {
  let start = codePoint;
  while (start > 0 && DIGIT.test(String.fromCodePoint(start - 1))) start -= 1;
  return (codePoint - start) % 10;
}

/**
 * The matching key of a token, or null when it carries no evidence. Numbers keep only their digits ("25.000" and
 * "25000" are "#25000"); a word of at least STEM letters keeps its first STEM, shorter words must be equal.
 */
export function key(token) {
  const text = String(token);
  if (!text) return null;
  if (DIGIT.test(String.fromCodePoint(text.codePointAt(0)))) {
    let digits = '#';
    for (const char of text) if (DIGIT.test(char)) digits += String(digitValue(char.codePointAt(0)));
    return digits;
  }
  const word = fold(text);
  if (word.length < 2 || STOP_WORDS.has(word)) return null;
  return word.length >= STEM ? word.slice(0, STEM) : word;
}

/** The distinct keys of a text. */
export function terms(text) {
  const keys = new Set();
  for (const match of String(text ?? '').matchAll(TOKEN)) {
    const k = key(match[0]);
    if (k) keys.add(k);
  }
  return keys;
}

// ---------- sentence boundaries (qa-core SentenceSplitter) ----------
export const ABBREVIATIONS = new Set([
  'dr', 'prof', 'doç', 'yrd', 'öğr', 'gör', 'uzm', 'av', 'müh', 'sn', 'hz',
  'vb', 'vs', 'vd', 'bkz', 'krş', 'örn', 'ör', 'akt', 'çev', 'haz', 'ed', 'yay',
  'sy', 'sf', 'no', 'nr', 'md', 'mad', 'fık', 'bnd', 'böl', 'syf',
  'ltd', 'şti', 'inc', 'co', 'tic', 'koll', 'gn', 'gnl', 'müd', 'bşk', 'başk', 'yön',
  'cad', 'sok', 'mah', 'apt', 'blv', 'tel', 'faks', 'fax', 'pk',
  'şub', 'tem', 'ağu', 'eyl', 'yy', 'mr', 'mrs', 'ms', 'jr', 'st', 'etc', 'vol', 'pp', 'fig',
]);
const ROMAN = /^[IVX]{1,4}$/;
const TERMINATORS = new Set(['.', '!', '?', '…']);
const CLOSERS = new Set(['"', '\'', '”', '’', '»', ')', ']']);
// Java's Character.isWhitespace: separators except the non-breaking ones, and the ASCII controls it names.
const isSpace = (c) => c !== undefined && (/[\t\n\v\f\r\u001c-\u001f]/.test(c) || (/[\p{Zs}\p{Zl}\p{Zp}]/u.test(c) && !/[   ]/.test(c)));

function skipSpace(text, from) {
  let i = from;
  while (i < text.length && isSpace(text[i])) i += 1;
  return i;
}

function continuesAfterDot(text, sentenceStart, dot) {
  let from = dot;
  while (from > sentenceStart && !isSpace(text[from - 1])) from -= 1;
  while (from < dot && !/[\p{L}\p{Nd}]/u.test(text[from])) from += 1;
  if (from === dot) return false;
  const word = text.slice(from, dot);
  if ([...word].length === 1 && /\p{L}/u.test(word)) return true;
  if (/^\p{Nd}+$/u.test(word)) return word.length <= 3;
  if (ROMAN.test(word)) return true;
  if (word.indexOf('.') > 0 && word.split('.').every((part) => part.length > 0 && part.length <= 3 && /^\p{L}+$/u.test(part))) return true;
  return ABBREVIATIONS.has(word.toLocaleLowerCase('tr'));
}

/**
 * Sentence spans [{ start, end }] of a text: a sentence ends at . ! ? … (closing quotes and brackets included) followed
 * by white space and a character that is not a lower-case letter, except after an abbreviation, an initial, a dotted
 * abbreviation or a short ordinal. lineBreaks: a line break also ends a sentence (the model's list items).
 */
export function sentences(text, lineBreaks = false) {
  const spans = [];
  const add = (start, end) => {
    let e = end;
    while (e > start && isSpace(text[e - 1])) e -= 1;
    if (e > start) spans.push({ start, end: e });
  };
  let start = skipSpace(text, 0);
  let i = start;
  while (i < text.length) {
    const c = text[i];
    if (lineBreaks && (c === '\n' || c === '\r')) {
      add(start, i);
      start = skipSpace(text, i);
      i = start;
      continue;
    }
    if (!TERMINATORS.has(c)) {
      i += 1;
      continue;
    }
    let end = i + 1;
    while (end < text.length && (TERMINATORS.has(text[end]) || CLOSERS.has(text[end]))) end += 1;
    const next = skipSpace(text, end);
    let closers = 0;
    for (let k = i + 1; k < end; k += 1) if (CLOSERS.has(text[k])) closers += 1;
    const loneDot = c === '.' && closers === end - i - 1;
    const boundary = end < text.length && isSpace(text[end]) && next < text.length
      && !/\p{Ll}/u.test(String.fromCodePoint(text.codePointAt(next)))
      && !(loneDot && continuesAfterDot(text, start, i));
    if (boundary) {
      add(start, end);
      start = next;
      i = next;
    } else {
      i = end;
    }
  }
  add(start, text.length);
  return spans;
}

const MARKER = /\[(\d+)\]/g;

/**
 * The answer sentences that cite `number`, as spans of the answer (what the server matched the excerpt against). A
 * marker that opens its sentence ("… on dört gündür. [1] Ayrıca …") belongs to the sentence before it.
 */
export function citingSpans(answer, number) {
  const text = String(answer ?? '');
  const spans = sentences(text, true);
  const found = [];
  if (!spans.length) return found;
  for (const match of text.matchAll(MARKER)) {
    if (Number(match[1]) !== number) continue;
    let index = 0;
    while (index + 1 < spans.length && spans[index + 1].start <= match.index) index += 1;
    const before = text.slice(spans[index].start, Math.max(spans[index].start, match.index));
    if (index > 0 && !before.replace(MARKER, '').trim()) index -= 1;
    if (!found.includes(spans[index])) found.push(spans[index]);
  }
  return found;
}

/** The citing sentences of `number` as one text, markers removed: the evidence the excerpt was matched against. */
export function citingText(answer, number) {
  const text = String(answer ?? '');
  return citingSpans(text, number).map((s) => text.slice(s.start, s.end).replace(MARKER, ' ')).join(' ');
}

/**
 * The words of `excerpt` that share a key with `evidence`, as [{ start, end }] offsets into the excerpt (UTF-16, for
 * DOM ranges). Every occurrence is marked; nothing is marked when the evidence has no terms.
 */
export function matchRanges(excerpt, evidence) {
  const wanted = terms(evidence);
  const ranges = [];
  if (!wanted.size) return ranges;
  for (const match of String(excerpt ?? '').matchAll(TOKEN)) {
    const k = key(match[0]);
    if (k && wanted.has(k)) ranges.push({ start: match.index, end: match.index + match[0].length });
  }
  return ranges;
}

/**
 * The excerpt without the ellipses the server adds where it cut the passage ("…" at the start or the end), and where
 * they were: { text, before, after }. Only the server's single "…" counts; three dots written in the document stay.
 */
export function excerptParts(excerpt) {
  let text = String(excerpt ?? '');
  const before = text.startsWith('…');
  if (before) text = text.slice(1);
  const after = text.endsWith('…');
  if (after) text = text.slice(0, -1);
  return { text, before, after };
}
