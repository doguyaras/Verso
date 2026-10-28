// The KVKK data subject request screen (ADR-0034, ADR-0036) without the DOM: the sub check (the server's own
// pattern), the typed confirmations, what the summary says is deleted, kept and reported, and the export file name.
// The sub is personal data: it only lives in the screen's memory, goes to the API in a JSON body (never a URL), and no
// helper here puts it into a file name, a title or a log.

/** The token sub pattern the server checks (JwtValidation, SubjectIds; docs/api-subjects-integration-v1.md). */
export const SUB_PATTERN = /^[A-Za-z0-9._@:-]{1,255}$/;
/** A document id as the API writes it. */
export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
/** Where the operating steps are written down (the panel quotes them, the file is the authority). */
export const RUNBOOK = 'docs/runbooks/kvkk-basvurusu.md';

/** True when the value is a token sub exactly as typed: nothing is trimmed or case-folded (the server does neither). */
export function validSub(value) {
  return typeof value === 'string' && SUB_PATTERN.test(value);
}

/** Why a typed sub cannot be sent, or null; the text names the allowed characters, never the value. */
export function subProblem(value) {
  if (typeof value !== 'string' || value === '') return 'Kişinin hesap kimliğini (sub) yazın.';
  if (value.length > 255) return 'Hesap kimliği en çok 255 karakterdir.';
  if (value.trim() !== value) return 'Hesap kimliğinin başında ya da sonunda boşluk olamaz.';
  return validSub(value) ? null : 'Hesap kimliği yalnız harf, rakam ve . _ @ : - içerebilir.';
}

/** Deletion goes ahead only when the typed confirmation is the sub, byte for byte (the server checks it again). */
export function deleteConfirmed(sub, typed) {
  return validSub(sub) && typed === sub;
}

/** Answer redaction goes ahead only when the typed confirmation is the document id, as the server compares it. */
export function redactionConfirmed(documentId, typed) {
  return typeof documentId === 'string' && UUID_PATTERN.test(documentId) && typed === documentId;
}

const count = (value) => (Number.isFinite(value) ? value : 0);

/**
 * What the summary means for a deletion, as rows of { label, value } in three groups (ADR-0034 section 3), plus the
 * collection documents that stay and the flags the screen needs. Missing fields count as zero, so an older or a
 * partial answer still draws.
 */
export function summaryModel(summary) {
  const history = summary?.history ?? {};
  const documents = summary?.documents ?? {};
  const personal = count(documents.personalCount);
  const collection = Array.isArray(documents.collection) ? documents.collection : [];
  const granted = Array.isArray(documents.grantedCollections) ? documents.grantedCollections.length : 0;
  const created = Array.isArray(documents.createdCollections) ? documents.createdCollections.length : 0;
  const audit = count(summary?.auditEventsAsActor);
  const deleted = [
    { label: 'Konuşma', value: count(history.conversations) },
    { label: 'Soru ve cevap', value: count(history.turns) },
    { label: 'Puan', value: count(history.feedback) },
    { label: 'Kullanım günü', value: count(history.activeDays) },
    // ADR-0057: skill runs keep the form inputs the person typed.
    { label: 'Beceri çalıştırması (girdileriyle)', value: count(history.skillRuns) },
    { label: 'Kişisel belge (dosya, sayfa, parça ve vektörleriyle)', value: personal },
  ];
  const kept = [
    { label: 'Bilgi alanlarına yüklediği belge', value: count(documents.collectionCount ?? collection.length) },
    { label: 'Adıyla açılmış bilgi alanı erişimi', value: granted },
    { label: 'Oluşturduğu bilgi alanı', value: created },
    { label: 'Denetim kaydı satırı (saklama süresi sonunda silinir)', value: audit },
  ];
  return {
    deleted,
    kept,
    collectionDocuments: collection,
    personalDocuments: Array.isArray(documents.personal) ? documents.personal : [],
    truncated: documents.listsTruncated === true,
    nothingToDelete: deleted.every((row) => row.value === 0),
    nothingFound: deleted.every((row) => row.value === 0) && kept.every((row) => row.value === 0),
  };
}

/** The result of a deletion in words; a repeated request deletes nothing more and says so. */
export function deletionModel(result) {
  const history = result?.historyDeleted ?? {};
  const rows = [
    { label: 'Silinen konuşma', value: count(history.conversations) },
    { label: 'Silinen soru ve cevap', value: count(history.turns) },
    { label: 'Silinen beceri çalıştırması', value: count(history.skillRuns) },
    { label: 'Silinen kişisel belge', value: count(result?.personalDocumentsDeleted) },
    { label: 'Başka hesaplarda karartılan cevap', value: count(result?.answersRedacted) },
  ];
  return { rows, repeated: rows.every((row) => row.value === 0), kept: summaryModel({ documents: result?.kept?.documents,
    auditEventsAsActor: result?.kept?.auditEventsAsActor }) };
}

/**
 * The name of the downloaded export: the date and time of the download, never the sub (a file name ends up in the
 * download list, recent files and synced folders). Local time, so it matches the request file's own records.
 */
export function exportFileName(now = new Date()) {
  const two = (n) => String(n).padStart(2, '0');
  return `verso-kvkk-disa-aktarim-${now.getFullYear()}${two(now.getMonth() + 1)}${two(now.getDate())}-${two(now.getHours())}${two(now.getMinutes())}.json`;
}
