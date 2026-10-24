// The Verso API as the panel uses it (docs/api-documents-integration-v1.md, docs/api-questions-integration-v1.md,
// docs/api-conversations-integration-v1.md). Success bodies are plain JSON, errors the common envelope; every error
// becomes an ApiError with a Turkish message for the screen (the table follows the integration documents). Nothing is
// logged to the console: questions, answers, titles and file names are personal data (llm-rules 2.1).
import { CONFIG } from './config.js';
import { accessToken } from './auth.js';
import { askBody, HISTORY_PAGE_SIZE } from './conversations.js';

export const MESSAGES = Object.freeze({
  10001: 'Belge bulunamadı.',
  10002: 'Bilgi alanı bulunamadı.',
  10003: 'Bu erişim kaydı artık yok.',
  10004: 'Bu belge bir bilgi alanında; yalnız yönetici değiştirebilir.',
  10040: 'Bu adla bir bilgi alanı zaten var.',
  10041: 'Bilgi alanında hâlâ belge var; önce belgeleri taşıyın ya da silin.',
  10042: 'Bu bilgi alanının erişim sınırı dolu.',
  10043: 'Bilgi alanı sınırı dolu.',
  10044: 'Bu bilgi alanının belge sınırı dolu.',
  10010: 'Bu dosya türü desteklenmiyor: PDF, DOCX, TXT ya da MD yükleyin.',
  10011: 'Dosya boş.',
  10013: 'Belge kotası dolu (200). Önce eski belgeleri silin.',
  10014: 'Şu an çok fazla yükleme var; birkaç saniye sonra tekrar deneyin.',
  10015: 'İşlenmeyi bekleyen belge sayısı sınırda (20). Biraz bekleyin.',
  10030: 'Arama modeli şu an yanıt vermiyor; birazdan tekrar deneyin.',
  10031: 'Belgeleriniz yeni modelle yeniden indekslenmeli; yöneticinize bildirin.',
  11001: 'Asistan şu an yanıt veremiyor; birazdan tekrar deneyin.',
  11002: 'Asistan meşgul; birkaç saniye sonra tekrar deneyin.',
  11010: 'Soru çok uzun.',
  11011: 'İstek gövdesinin uzunluğu bildirilmeli.',
  11020: 'Konuşma bulunamadı; silinmiş ya da saklama süresi dolmuş olabilir.',
  11021: 'Bu cevap artık geçmişte yok.',
  11022: 'Konuşma dolu (200 soru); yeni bir konuşma başlatın.',
  11023: 'Seçilen kaynak bu cevapta yok.',
  11030: 'Tarih aralığı geçersiz (en çok 366 gün).',
  90000: 'Soru boş ya da 1000 karakterden uzun.',
  90001: 'İstek okunamadı.',
  90002: 'İstekteki bir değer geçersiz.',
  90003: 'İstekte zorunlu bir alan eksik.',
  90004: 'İstek reddedildi.',
  90010: 'Aranan kayıt bulunamadı.',
  90011: 'Bu işlem desteklenmiyor.',
  90012: 'Bu dosya türü desteklenmiyor.',
  90013: 'Yanıt biçimi desteklenmiyor.',
  90014: 'Dosya ya da istek çok büyük (en çok 20 MB).',
  90015: 'İşlem, kaydın şu anki durumuyla çakışıyor; sayfayı yenileyin.',
  90020: 'API sürümü desteklenmiyor.',
  90100: 'Oturumunuz sona erdi; yeniden giriş yapın.',
  90101: 'Bu işlem için yetkiniz yok.',
  90102: 'Çok fazla istek gönderildi; biraz bekleyin.',
  90103: 'Giriş servisine şu an ulaşılamıyor.',
  99997: 'Servis geçici olarak kullanılamıyor.',
  99998: 'Bağlı bir servis hata verdi.',
  99999: 'Beklenmeyen bir sunucu hatası oluştu.',
});

/** The screen text of an error; a Retry-After the server sent is named (docs/api-questions-integration-v1.md). */
export function messageOf(status, code, retryAfter) {
  const text = MESSAGES[code] ?? (status >= 500 ? 'Beklenmeyen bir sunucu hatası oluştu.' : 'İstek tamamlanamadı.');
  return retryAfter ? `${text} (${retryAfter} sn sonra tekrar deneyebilirsiniz.)` : text;
}

export class ApiError extends Error {
  constructor(status, code, retryAfter) {
    super(messageOf(status, code, retryAfter));
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
  }
}

/** The mode the last response reported (X-Rag-Mode, llm-rules 1.3): "local", "cloud" or null. */
export let lastMode = null;

async function request(path, init = {}) {
  const token = await accessToken();
  if (!token) throw new ApiError(401, 90100);
  const res = await fetch(CONFIG.api + path, { ...init, headers: { Authorization: `Bearer ${token}`, ...(init.headers ?? {}) } });
  lastMode = res.headers.get('X-Rag-Mode') ?? lastMode;
  if (res.status === 204) return null;
  const body = await res.json().catch(() => null);
  if (!res.ok) throw new ApiError(res.status, body?.error?.code, Number(res.headers.get('Retry-After')) || undefined);
  return body;
}

/** Documents per page: the API's upper bound (docs/api-documents-integration-v1.md); the quota is 200. */
export const PAGE_SIZE = 100;
/** Pages of documents the screen loads at most: 500 documents. */
export const MAX_DOCUMENT_PAGES = 5;

export const api = {
  listDocuments: (page = 0, size = PAGE_SIZE) => request(`/v1/documents?page=${page}&size=${size}`),
  getDocument: (id) => request(`/v1/documents/${encodeURIComponent(id)}`),
  deleteDocument: (id) => request(`/v1/documents/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  /**
   * The documents the user reads, personal and shared (ADR-0028), newest first. The personal quota keeps those to two
   * pages; shared collections can hold more, so at most MAX_DOCUMENT_PAGES pages are loaded (newest first).
   */
  async listAllDocuments() {
    const first = await api.listDocuments(0);
    const rest = [];
    const last = Math.min(first.page.totalPages, MAX_DOCUMENT_PAGES);
    for (let page = 1; page < last; page++) rest.push(...(await api.listDocuments(page)).data);
    return [...first.data, ...rest];
  },
  /** Collections the user reads; for verso-admin every collection, each with readable/manageable (ADR-0028). */
  listCollections: (page = 0, size = 100) => request(`/v1/collections?page=${page}&size=${size}`),
  createCollection: (name, description) => request('/v1/collections', { method: 'POST',
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name, description: description || null }) }),
  deleteCollection: (id) => request(`/v1/collections/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  listGrants: (id) => request(`/v1/collections/${encodeURIComponent(id)}/grants`),
  /** principalType GROUP (the group claim value, e.g. a Keycloak full path) or USER (the token sub). */
  addGrant: (id, principalType, principal) => request(`/v1/collections/${encodeURIComponent(id)}/grants`, { method: 'POST',
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ principalType, principal }) }),
  removeGrant: (id, grantId) => request(`/v1/collections/${encodeURIComponent(id)}/grants/${encodeURIComponent(grantId)}`,
    { method: 'DELETE' }),
  /** verso-admin: collectionId null moves the document back to its uploader's personal space. */
  moveDocument: (id, collectionId) => request(`/v1/documents/${encodeURIComponent(id)}/collection`, { method: 'PUT',
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ collectionId }) }),
  /**
   * Uploads one file and reports the bytes sent (fetch cannot report upload progress, XMLHttpRequest can). The same
   * idempotency key would make a retry return the first document instead of a second one.
   */
  async upload(file, onProgress = () => {}, collectionId = null) {
    const token = await accessToken();
    if (!token) throw new ApiError(401, 90100);
    const form = new FormData();
    form.append('file', file, file.name);
    // A shared collection (verso-admin only, ADR-0028); without it the file goes to the personal space.
    const target = collectionId ? `?collectionId=${encodeURIComponent(collectionId)}` : '';
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${CONFIG.api}/v1/documents${target}`);
      xhr.setRequestHeader('Authorization', `Bearer ${token}`);
      xhr.setRequestHeader('X-Idempotency-Key', crypto.randomUUID());
      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable) onProgress(event.loaded / event.total);
      };
      xhr.onerror = () => reject(new TypeError('network'));
      xhr.onload = () => {
        lastMode = xhr.getResponseHeader('X-Rag-Mode') ?? lastMode;
        let body = null;
        try {
          body = JSON.parse(xhr.responseText);
        } catch {
          body = null;
        }
        if (xhr.status >= 200 && xhr.status < 300) resolve(body);
        else reject(new ApiError(xhr.status, body?.error?.code, Number(xhr.getResponseHeader('Retry-After')) || undefined));
      };
      xhr.send(form);
    });
  },
  /**
   * signal: an AbortSignal; aborting stops waiting for the answer (the server finishes its own call). conversationId:
   * the conversation the question continues, or none for a new one (ADR-0023).
   */
  ask: (question, signal, conversationId = null) => request('/v1/questions', { method: 'POST',
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(askBody(question, conversationId)), signal }),
  info: () => request('/v1/info'),
  listConversations: (page = 0, size = HISTORY_PAGE_SIZE) => request(`/v1/conversations?page=${page}&size=${size}`),
  getConversation: (id) => request(`/v1/conversations/${encodeURIComponent(id)}`),
  renameConversation: (id, title) => request(`/v1/conversations/${encodeURIComponent(id)}`, { method: 'PATCH',
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title }) }),
  deleteConversation: (id) => request(`/v1/conversations/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  deleteAllConversations: () => request('/v1/conversations', { method: 'DELETE' }),
  /** One rating per answer; a new one replaces the old (docs/api-conversations-integration-v1.md). */
  rate: (conversationId, turnId, body) => request(`/v1/conversations/${encodeURIComponent(conversationId)}/turns/${encodeURIComponent(turnId)}/feedback`,
    { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  /** verso-admin only; from and to are inclusive calendar days (YYYY-MM-DD). */
  metrics: (from, to) => request(`/v1/admin/metrics?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
};
