// Conversation history as the panel uses it (ADR-0023, ADR-0025), without the DOM: which conversation a question
// continues, what to do when the server refuses it, how a stored conversation comes back into the ask screen, the
// Turkish-aware filter of the history list and the checks before a rename or "Tümünü sil". Titles, questions and
// answers are personal data: they stay in this tab's memory, are shown as text only and are never logged.
import { foldTr } from './render.js';

/** Conversations per page of the history list (the API allows 1–50). */
export const HISTORY_PAGE_SIZE = 20;
/** The API's limit for a title (RenameConversationRequest). */
export const TITLE_MAX = 120;
/** What a citation of a document deleted since shows instead of its name. */
export const DELETED_DOCUMENT = 'Belge silinmiş';
export const INACCESSIBLE_DOCUMENT = 'Erişiminiz yok';
/** The word typed to confirm deleting every conversation; compared Turkish-aware (sil, SİL, Sil). */
export const DELETE_ALL_WORD = 'SİL';

/** The body of POST /v1/questions: a conversation id only when the question continues one. */
export function askBody(question, conversationId, model = null) {
  const body = conversationId ? { question, conversationId } : { question };
  // ADR-0056: a model choice only when the admin offers one (GET /v1/models); otherwise the field is not sent.
  if (typeof model === 'string' && model) body.model = model;
  return body;
}

/**
 * The conversation to continue after an answer. Always the answer's own id: the server starts a new conversation when
 * the one sent was deleted meanwhile or has outlived the retention (docs/api-conversations-integration-v1.md §1). An
 * answer whose turn could not be stored (both ids null) keeps the current conversation.
 */
export function continueWith(current, result) {
  return result?.conversationId ?? current ?? null;
}

/**
 * Whether a refused follow-up is asked again in a new conversation: 11020 (the conversation is gone, foreign or
 * expired) and 11022 (it is full) are refused before any model call, so asking again costs no second answer.
 */
export function startsOver(error) {
  return Boolean(error) && (error.code === 11020 || error.code === 11022);
}

/** The note shown above an answer that had to start a new conversation. */
export function startOverNote(error) {
  return error?.code === 11022 ? 'Konuşma dolduğu için soru yeni bir konuşmada soruldu.'
    : 'Önceki konuşma artık yok; soru yeni bir konuşmada soruldu.';
}

/**
 * A stored citation for the answer screen: a deleted document keeps its place and reads "Belge silinmiş"; one the user
 * can no longer read (a shared collection's grant was removed, ADR-0028) reads "Erişiminiz yok" without its name. An
 * older server sends no documentAccessible: a citation with a name is then readable.
 */
export function historyCitation(citation) {
  const deleted = Boolean(citation.documentDeleted) || (citation.fileName == null && citation.documentAccessible !== false);
  const inaccessible = !deleted && (citation.documentAccessible === false || citation.fileName == null);
  const fileName = deleted ? DELETED_DOCUMENT : inaccessible ? INACCESSIBLE_DOCUMENT : citation.fileName;
  return { number: citation.number, documentId: citation.documentId, fileName, page: citation.page, unit: citation.unit,
    deleted, inaccessible, collectionName: inaccessible || deleted ? null : (citation.collectionName ?? null) };
}

/**
 * GET /v1/conversations/{id} as entries of the ask screen, oldest first. They are read-only (no excerpt is stored, so
 * no follow-ups or edits); their ratings and the ids that let them be rated come along.
 */
export function restoreEntries(conversation) {
  return (conversation.turns ?? []).map((turn) => ({
    question: turn.question,
    result: { answer: turn.answer ?? '', outcome: turn.outcome, found: turn.outcome === 'ANSWERED',
      citations: (turn.citations ?? []).map(historyCitation), mode: turn.mode, model: turn.model,
      conversationId: conversation.id, turnId: turn.id },
    feedback: turn.feedback ?? null,
    ms: turn.durationMs ?? 0,
    at: turn.createdAt,
    restored: true,
  }));
}

/**
 * The loaded conversations whose title contains the query, both folded the Turkish way (render.js foldTr): "izin",
 * "İZİN" and "ızın" find "İzin talebi", "sirket" finds "Şirket". Only the loaded pages are searched.
 */
export function filterConversations(items, query) {
  const wanted = foldTr(query ?? '').replace(/\s+/g, ' ').trim();
  if (!wanted) return items;
  return items.filter((item) => foldTr(item.title ?? '').replace(/\s+/g, ' ').includes(wanted));
}

// The server refuses control characters and bidirectional overrides in a title (RenameConversationRequest).
const TITLE_REFUSED = /[\p{Cc}‪-‮⁦-⁩]/u;

/** Why a new title cannot be saved, or null. The server trims it and checks the same rules. */
export function titleProblem(value) {
  const title = String(value ?? '').trim();
  if (!title) return 'Başlık boş olamaz.';
  if (title.length > TITLE_MAX) return `Başlık en çok ${TITLE_MAX} karakter olabilir.`;
  if (TITLE_REFUSED.test(title)) return 'Başlıkta kontrol ya da yön karakteri olamaz.';
  return null;
}

/** "Tümünü sil" goes ahead only when the confirmation word was typed (any case, Turkish letters folded). */
export function deleteAllConfirmed(typed) {
  return foldTr(String(typed ?? '').trim()) === foldTr(DELETE_ALL_WORD);
}

/** The second line of a history row: "3 soru". */
export function turnCountLabel(count) {
  return `${Number(count) || 0} soru`;
}

/** Older pages exist when fewer rows are loaded than the server counts. */
export function hasMore(page) {
  return Boolean(page) && page.number + 1 < page.totalPages;
}
