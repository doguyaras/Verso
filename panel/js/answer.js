// What the answer screen derives from an answer, without the DOM (ADR-0024): the Markdown export with the sources as
// footnotes, the short source chips of wide screens, the skeptical follow-up questions, the "ask about the selection"
// prefill and the honest state of a failed question. Answer, excerpt and file names are untrusted text: here they only
// become plain strings, escaped where they go into Markdown (no link, image or HTML can come from them), and nothing
// here is logged (llm-rules 2.1, 3.2).
import { ApiError, messageOf } from './api.js';
import { generatedFooter } from './disclosure.js';
import { plainName, sourceLabel } from './render.js';

/** The API's limit for a question (docs/api-questions-integration-v1.md); prefills and follow-ups stay under it. */
export const MAX_QUESTION = 1000;

/** Collapses white space and drops control and format characters (bidi overrides) from a one-line value. */
function oneLine(value) {
  return String(value ?? '').replace(/[\p{Cc}\p{Cf}]/gu, ' ').replace(/\s+/g, ' ').trim();
}

/** Cuts a text to `max` code points with a trailing ellipsis, at a word boundary when one is near. */
export function clip(text, max) {
  const chars = [...text];
  if (chars.length <= max) return text;
  const kept = chars.slice(0, max - 1).join('');
  const space = kept.lastIndexOf(' ');
  return `${(space > kept.length / 2 ? kept.slice(0, space) : kept).trimEnd()}…`;
}

// Bidi controls (embeddings, overrides, isolates, marks) could make a line read as something else; line breaks stay.
const BIDI = /[؜‎‏‪-‮⁦-⁩]/g;

/**
 * Untrusted text as inert Markdown: the characters that start a link, an image, a footnote, inline HTML, an autolink
 * or code are escaped, so a planted "![](https://…)" or "<img …>" in a document or an answer stays text in any viewer
 * that opens the export (and loads no remote image). Emphasis and line-start marks are left: at worst they format.
 */
export function escapeMarkdown(text) {
  return String(text ?? '').replace(BIDI, '').replace(/[\\`[\]<>]/g, '\\$&');
}

/**
 * The answer as Markdown: each server citation marker "[n]" becomes a footnote reference "[^n]", and the footnotes list
 * the file, the page or section and the excerpt. Everything else of the answer, the excerpts, the file names and the
 * question is escaped (escapeMarkdown), markers the server did not map included. With a question, the export starts
 * with it as a heading and ends with a line saying where it came from (the .md download); without, it is the answer and
 * its footnotes only (the clipboard). Both end with the machine-readable AI footer (EU AI Act Art. 50, ADR-0027).
 */
export function answerMarkdown(result, { question, productName, mode, model, at } = {}) {
  const numbers = new Set((result.citations ?? []).map((c) => c.number));
  const answer = String(result.answer ?? '');
  let body = '';
  let last = 0;
  for (const match of answer.matchAll(/\[(\d+)\]/g)) {
    if (!numbers.has(Number(match[1]))) continue;
    // A footnote reference sits right after its word: "onaylar [1]." becomes "onaylar[^1].".
    body += `${escapeMarkdown(answer.slice(last, match.index)).replace(/[ \t]+$/, '')}[^${match[1]}]`;
    last = match.index + match[0].length;
  }
  body += escapeMarkdown(answer.slice(last));
  const notes = [...(result.citations ?? [])].sort((a, b) => a.number - b.number).map((c) => {
    const where = escapeMarkdown(oneLine(sourceLabel({ ...c, fileName: plainName(c.fileName) })));
    const excerpt = escapeMarkdown(oneLine(c.excerpt));
    return `[^${c.number}]: ${where}${excerpt ? ` — “${excerpt}”` : ''}`;
  });
  const parts = [];
  if (question) parts.push(`# ${escapeMarkdown(oneLine(question))}`);
  parts.push(body.trim());
  if (notes.length) parts.push(notes.join('\n'));
  if (question) {
    const origin = [productName, mode === 'cloud' ? 'Bulut' : mode === 'local' ? 'Yerel' : null, model,
      at ? new Date(at).toISOString().slice(0, 10) : null].filter(Boolean).map((v) => escapeMarkdown(oneLine(v))).join(' · ');
    if (origin) parts.push(`---\n${origin}`);
  }
  parts.push(generatedFooter(productName ? escapeMarkdown(oneLine(productName)) : null));
  return `${parts.join('\n\n')}\n`;
}

/** The download name: "soru-<first words>.md", ASCII letters and digits only, never empty. */
export function markdownFileName(question) {
  const slug = oneLine(question).normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase().replace(/ı/g, 'i')
    .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 48).replace(/-+$/, '');
  return `${slug ? `soru-${slug}` : 'cevap'}.md`;
}

/** Longest file name shown on a source chip, in characters; the full name stays in the tooltip and the label. */
export const CHIP_NAME_MAX = 22;

/** The compact source chip of wide screens: "izin-yonetmeligi · s. 3", or "notlar · böl. 2" for a section. */
export function chipLabel(citation) {
  const name = plainName(citation.fileName ?? '');
  const dot = name.lastIndexOf('.');
  const stem = oneLine(dot > 0 ? name.slice(0, dot) : name) || 'belge';
  const where = citation.unit === 'SECTION' ? 'böl.' : 's.';
  return `${clip(stem, CHIP_NAME_MAX)} · ${where} ${citation.page}`;
}

/**
 * The skeptical follow-ups after an answer (NN/g: ask what supports it, not just "go on"). The service answers each
 * question on its own, without the conversation, so every follow-up is a whole new question that names the one it
 * follows and never says "this" or "that"; the chip's tooltip shows the question as it will be sent. "sources" with
 * citations is answered here, without a model call: the answer already says which documents it rests on. Without
 * citations (UNCITED) it asks again for a sourced answer instead: a new answer's sources are not the old one's.
 */
export const FOLLOW_UPS = Object.freeze([
  Object.freeze({ id: 'sources', label: 'Bu hangi belgeye dayanıyor?', uncitedLabel: 'Kaynaklı olarak yeniden sor',
    template: (q) => `“${q}” sorusunu yalnız belgelere dayanarak, kaynağını göstererek cevapla.` }),
  Object.freeze({ id: 'contrary', label: 'Aksi bir madde var mı?', template: (q) => `“${q}” konusunda istisna ya da sınırlama getiren bir madde var mı?` }),
  Object.freeze({ id: 'shorter', label: 'Bunu daha kısa anlat', template: (q) => `“${q}” sorusunu en fazla iki cümleyle, kısaca cevapla.` }),
]);

/**
 * The follow-ups an answer offers: [{ id, label, question, local }]. None after "not found" (asking around it would
 * invite a guess) or a failed question. `local` marks the one the panel answers itself (the sources it already has).
 */
export function followUps(result) {
  if (!result || result.outcome === 'NOT_FOUND' || (result.outcome == null && !result.found && !result.citations?.length)) return [];
  const cited = (result.citations?.length ?? 0) > 0;
  return FOLLOW_UPS.map((f) => ({ id: f.id, label: !cited && f.uncitedLabel ? f.uncitedLabel : f.label, local: f.id === 'sources' && cited,
    question: (q) => fitQuestion(f.template, q) }));
}

/** A template filled with the previous question, cut so the whole question stays within MAX_QUESTION. */
export function fitQuestion(template, question) {
  const q = oneLine(question);
  const room = MAX_QUESTION - [...template('')].length;
  return template(clip(q, Math.max(1, room)));
}

/** Longest selection quoted into the input, in characters. */
export const SELECTION_MAX = 300;

/** The input's prefill for "Bunu sor": the selected text quoted on one line, then the cursor. Empty for blank text. */
export function selectionQuestion(selected) {
  const text = oneLine(selected).replace(/[“”]/g, '"');
  return text ? `“${clip(text, SELECTION_MAX)}” ` : '';
}

/**
 * What a failed question shows: { kind, title, text, retry }. The kinds follow the API's codes
 * (docs/api-questions-integration-v1.md): the model or a service is down (try again in a moment), busy, the documents
 * need re-indexing (not the user's to fix), the question itself was refused, the session ended, or no connection.
 */
export function errorState(error) {
  const retryAfter = error instanceof ApiError ? error.retryAfter : undefined;
  if (!(error instanceof ApiError)) {
    return { kind: 'offline', title: 'Sunucuya ulaşılamadı', text: 'Bağlantınızı kontrol edip tekrar deneyin. Sorunuz gönderilmemiş olabilir.', retry: true };
  }
  const { code, status } = error;
  // The message without its Retry-After note: the retry button counts the wait down itself.
  const text = messageOf(status, code);
  if (status === 401) return { kind: 'auth', title: 'Oturumunuz sona erdi', text: 'Yeniden giriş yaptıktan sonra soruyu tekrar sorabilirsiniz.', retry: false };
  if (code === 11002 || code === 90102) {
    return { kind: 'busy', title: 'Asistan şu an yoğun', text, retry: true, retryAfter };
  }
  if (code === 10031) {
    return { kind: 'reindex', title: 'Belgeler yeniden hazırlanmalı', text: `${text} Bu sırada soru sorulamaz.`, retry: false };
  }
  if (code === 90000 || code === 11010 || code === 11011) {
    return { kind: 'invalid', title: 'Soru gönderilemedi', text, retry: false };
  }
  if (code === 11001 || code === 10030 || code === 90103 || code === 99997 || code === 99998 || status >= 500) {
    return { kind: 'down', title: code === 10030 ? 'Arama modeli yanıt vermiyor' : code === 11001 ? 'Dil modeli yanıt vermiyor'
      : 'Servis geçici olarak kullanılamıyor', text, retry: true, retryAfter };
  }
  return { kind: 'other', title: 'Cevap alınamadı', text, retry: true, retryAfter };
}
