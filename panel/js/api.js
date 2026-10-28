// The Verso API as the panel uses it (docs/api-documents-integration-v1.md, docs/api-questions-integration-v1.md,
// docs/api-conversations-integration-v1.md). Success bodies are plain JSON, errors the common envelope; every error
// becomes an ApiError with a Turkish message for the screen (the table follows the integration documents). Nothing is
// logged to the console: questions, answers, titles and file names are personal data (llm-rules 2.1).
import { CONFIG } from './config.js';
import { accessToken } from './auth.js';
import { askBody, HISTORY_PAGE_SIZE } from './conversations.js';
import { noStream, readEvents, resultOf, StreamUnavailable } from './stream.js';
import { entitiesPath, entityPath, lookupPath, suggestionsPath } from './systemmap.js';

export const MESSAGES = Object.freeze({
  10001: 'Belge bulunamadı.',
  10002: 'Bilgi alanı bulunamadı.',
  10003: 'Bu erişim kaydı artık yok.',
  10004: 'Bu belge bir bilgi alanında; yalnız yönetici değiştirebilir.',
  10005: 'Bu sayfa belgede yok ya da belge henüz işleniyor.',
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
  10016: 'Bu yükleme anahtarı daha önce kullanıldı; yüklemeyi yeni bir anahtarla tekrarlayın.',
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
  // ADR-0049: the answer memory.
  11040: 'Onaylı bilgi kartı bulunamadı.',
  11041: 'Cevabın kaynaklarından biri artık yok ya da size kapalı; onaylanamaz.',
  11042: 'Yalnız kaynak gösteren bir cevap onaylanabilir.',
  11043: 'Kartın durumu bu işleme izin vermiyor; sayfayı yenileyin.',
  11044: 'Kaynak metin değişmiş; güncel bir cevaptan yeni kart oluşturun.',
  11045: 'Bu kartı zaten onayladınız; ikinci onay başka bir yöneticiden gelmeli.',
  11046: 'Kişisel bir belgeye dayanan cevap onaylanamaz; belgeyi bir bilgi alanına taşıyın.',
  11047: 'Bu soru için onaylı bir kart zaten var.',
  11048: 'Bir kontrol zaten çalışıyor; biraz sonra yeniden deneyin.',
  11060: 'Tarih aralığı geçersiz (en çok 400 gün).',
  11061: 'Bir teşhis zaten çalışıyor; biraz sonra yeniden deneyin.',
  11062: 'Kanca kuralı bulunamadı.',
  11063: 'Bildirim bulunamadı.',
  // ADR-0053: procedures (skills).
  11070: 'Prosedür bulunamadı.',
  11071: 'Prosedürün durumu bu işleme izin vermiyor; sayfayı yenileyin.',
  11072: 'Taslak için okuyabildiğiniz onaylı bir kaynak yok (onaylı kart ya da kendi kaynaklı cevabınız).',
  11073: 'Prosedürün bir kaynağı değişmiş ya da size kapalı; yeni bir taslak gerekir.',
  11074: 'Prosedür eksik ya da geçersiz: onay için 2-5 kontrol sorusu, en az bir örnek soru ve kaynak sayfaları gerekir.',
  11075: 'Bir adım, kaynaklarında olmayan bir sayı ya da kod içeriyor; adımı düzeltin.',
  11076: 'Bu prosedürü zaten onayladınız; ikinci onay başka bir yöneticiden gelmeli.',
  11077: 'Bu adla bir prosedür zaten var.',
  11078: 'Soru kümesi bulunamadı; teşhis yeniden çalışmış olabilir.',
  11079: 'Yalnız onaylı bir prosedür indirilebilir.',
  // ADR-0054: the system map and its relation suggestions.
  10050: 'Bu kayıt okuyabildiğiniz belgelerde geçmiyor.',
  10051: 'Aranan metin 1-200 karakter olmalı.',
  11080: 'Öneri geçişi kapalı (VERSO_QA_SYSTEM_MODEL_SUGGESTIONS_ENABLED).',
  11081: 'Öneri bulunamadı.',
  11082: 'Bu öneri zaten incelenmiş; sayfayı yenileyin.',
  11083: 'Belge bulunamadı.',
  13001: 'Hesap kimliği (sub) geçersiz: yalnız harf, rakam ve . _ @ : - içerebilir.',
  13002: 'Onay, hesap kimliğiyle birebir aynı değil; hiçbir şey silinmedi.',
  13020: 'Onay, belge kimliğiyle birebir aynı değil; hiçbir cevap değişmedi.',
  13021: 'Bu belge hâlâ duruyor; cevaplar yalnız belge silindikten sonra karartılır.',
  14001: 'Bağlayıcı bulunamadı; silinmiş olabilir.',
  14002: 'Bu adla bir bağlayıcı zaten var.',
  14003: 'Klasör yolu geçersiz: paylaşımın içinde göreli bir klasör yazın (boş parça, ".." ve ":" olamaz).',
  14004: 'Sunucu adresi kurulumun izinli iç ağlarının dışında (ya da loopback, link-local veya metadata adresi).',
  14005: 'Hedef bilgi alanı bulunamadı.',
  14006: 'Hedef bilgi alanı sonradan değiştirilemez; yeni bir bağlayıcı oluşturun.',
  14007: 'Bağlayıcı siliniyor; değiştirilemez ve eşitlenemez.',
  14008: 'Bağlayıcı sınırı dolu (kurulumda en çok 50).',
  14009: 'Dosya deseni kullanılamaz (kapanmamış "{", ".." ya da çok fazla "*").',
  14010: 'En büyük dosya boyu belge sınırını (20 MB) aşıyor.',
  14011: 'Sayfa numarası ya da sayfa boyutu aralık dışında.',
  14012: 'Ayarlar bu kaynak türüne uymuyor (ör. Confluence bağlayıcısında paylaşım alanı, Data Center için hesap adı).',
  14013: 'Bu kurulum bağlayıcıların bu adrese (internete) çıkmasına izin vermiyor; Confluence Cloud için dış bağlantı ayarı gerekir.',
  14014: 'Confluence adresi geçersiz: yalnız https, kullanıcı bilgisi, soru işareti ve # olmadan.',
  14015: 'Bağlayıcının türü sonradan değiştirilemez; yeni bağlayıcı oluşturun.',
  // ADR-0057: the task-skill library ("Beceriler").
  15001: 'Beceri bulunamadı.',
  15002: 'Bu beceri bu kurulumda kapalı.',
  15003: 'Formdaki bir değer geçersiz; zorunlu alanları ve uzunlukları kontrol edin.',
  15004: 'Çalışma bulunamadı; silinmiş ya da saklama süresi dolmuş olabilir.',
  15005: 'Çalışma henüz bitmedi.',
  15006: 'Düzenlenen çıktı geçersiz: her satırın kaynağı olmalı ya da "değerlendirilemez" denmeli.',
  15007: 'Beceri tanımı geçersiz; doğrulama hatalarını düzeltin.',
  15008: 'Kuruma özel beceri bulunamadı.',
  15009: 'Bu kimlikle bir beceri zaten var.',
  15010: 'Becerinin durumu bu işleme izin vermiyor; sayfayı yenileyin.',
  15011: 'Zaten çalışan bir beceriniz var; bitmesini bekleyin.',
  15012: 'Model şu an meşgul; birkaç dakika sonra yeniden deneyin.',
  15013: 'Sayfa numarası ya da sayfa boyutu aralık dışında.',
  15014: 'Dışa aktarma biçimi desteklenmiyor.',
  15015: 'Gönderilen içerik çok büyük. Metni kısaltıp yeniden deneyin.',
  15016: 'İstek eksik gönderildi. Sayfayı yenileyip yeniden deneyin.',
  15017: 'Şu anda çok fazla ilerleme izleniyor; durum birkaç saniyede bir yenilenecek.',
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

/**
 * POST /v1/questions/stream (ADR-0035) with fetch and a ReadableStream, so the Authorization header goes along (an
 * EventSource cannot send it). live.stage(event) and live.delta(text) see the stream as it comes; the result is the
 * same answer as POST /v1/questions. An HTTP error or an "error" event is an ApiError, an abort an AbortError (the
 * server then cancels the model call and stores nothing), and a response that is no stream a StreamUnavailable.
 */
async function streamQuestion(question, signal, conversationId, live) {
  const token = await accessToken();
  if (!token) throw new ApiError(401, 90100);
  let res;
  try {
    res = await fetch(`${CONFIG.api}/v1/questions/stream`, { method: 'POST', signal, body: JSON.stringify(askBody(question, conversationId, live?.model)),
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', Accept: 'text/event-stream, application/json' } });
  } catch (error) {
    if (error?.name === 'AbortError') throw error;
    throw new StreamUnavailable('network');
  }
  lastMode = res.headers.get('X-Rag-Mode') ?? lastMode;
  const type = res.headers.get('Content-Type');
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    if (noStream(res.status, type, body?.error?.code)) throw new StreamUnavailable(`status ${res.status}`);
    throw new ApiError(res.status, body?.error?.code, Number(res.headers.get('Retry-After')) || undefined);
  }
  if (noStream(res.status, type) || !res.body) throw new StreamUnavailable('not a stream');
  let citations = null;
  let done = null;
  let failure = null;
  const count = await readEvents(res.body, (event, value) => {
    if (event === 'stage') live.stage?.(value);
    else if (event === 'delta') live.delta?.(String(value.text ?? ''));
    else if (event === 'citations') citations = value;
    else if (event === 'done') done = value;
    else if (event === 'error') failure = value;
  });
  if (failure) throw new ApiError(failure.retryAfter ? 503 : 500, failure.code, failure.retryAfter || undefined);
  if (citations && done) return resultOf(citations, done);
  // Nothing came through at all: something on the way does not pass the stream. After events it is a cut connection.
  if (count === 0) throw new StreamUnavailable('empty');
  throw new TypeError('stream ended early');
}

/**
 * GET whose answer is a file (the knowledge-health export, ADR-0052): the body as a Blob. Errors are the usual envelope.
 */
async function getFile(path) {
  const token = await accessToken();
  if (!token) throw new ApiError(401, 90100);
  const res = await fetch(CONFIG.api + path, { headers: { Authorization: `Bearer ${token}` } });
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new ApiError(res.status, body?.error?.code, Number(res.headers.get('Retry-After')) || undefined);
  }
  return res.blob();
}

/**
 * POST with a JSON body whose answer is a file (the KVKK export, ADR-0034): the body as a Blob and the response headers.
 * Errors are the usual envelope. The body may hold personal data; nothing of it is kept beyond the caller's use.
 */
async function requestFile(path, payload) {
  const token = await accessToken();
  if (!token) throw new ApiError(401, 90100);
  const res = await fetch(CONFIG.api + path, { method: 'POST', body: JSON.stringify(payload),
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } });
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new ApiError(res.status, body?.error?.code, Number(res.headers.get('Retry-After')) || undefined);
  }
  return { blob: await res.blob(), headers: res.headers };
}

/**
 * GET /v1/skill-runs/{id}/events (ADR-0057) with fetch, so the Authorization header goes along: onEvent(name, value)
 * for every "stage" and "done" event. Throws StreamUnavailable when no stream gets through; the caller then polls.
 */
async function streamRunEvents(runId, signal, onEvent) {
  const token = await accessToken();
  if (!token) throw new ApiError(401, 90100);
  let res;
  try {
    res = await fetch(`${CONFIG.api}/v1/skill-runs/${encodeURIComponent(runId)}/events`, { signal,
      headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' } });
  } catch (error) {
    if (error?.name === 'AbortError') throw error;
    throw new StreamUnavailable('network');
  }
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    if (noStream(res.status, res.headers.get('Content-Type'), body?.error?.code)) throw new StreamUnavailable(`status ${res.status}`);
    throw new ApiError(res.status, body?.error?.code, Number(res.headers.get('Retry-After')) || undefined);
  }
  if (noStream(res.status, res.headers.get('Content-Type')) || !res.body) throw new StreamUnavailable('not a stream');
  return readEvents(res.body, onEvent);
}

const json = (method, payload) => ({ method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });

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
  /** verso-admin: renames a collection and changes its description (empty clears it); the rules of create apply. */
  updateCollection: (id, name, description) => request(`/v1/collections/${encodeURIComponent(id)}`,
    json('PUT', { name, description: description || null })),
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
  ask: (question, signal, conversationId = null, model = null) => request('/v1/questions', { method: 'POST',
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(askBody(question, conversationId, model)), signal }),
  /**
   * The answer as a stream (ADR-0035), or from POST /v1/questions when no stream gets through (an older proxy or
   * server); live.fallback() says so. Aborting a stream stops the server's model call; a JSON answer is still
   * finished and stored on the server.
   */
  async askLive(question, signal, conversationId = null, live = {}) {
    try {
      return await streamQuestion(question, signal, conversationId, live);
    } catch (error) {
      if (!(error instanceof StreamUnavailable)) throw error;
      live.fallback?.();
      return api.ask(question, signal, conversationId, live.model ?? null);
    }
  },
  /** The stored text of one page or section of a readable document (ADR-0035, docs/api-documents-integration-v1.md). */
  getPage: (id, number) => request(`/v1/documents/${encodeURIComponent(id)}/pages/${encodeURIComponent(number)}`),
  info: () => request('/v1/info'),
  // The trust report (ADR-0050), verso-admin only.
  trust: () => request('/v1/admin/trust'),
  // ADR-0058: the home feed (any user) and the activity centre (verso-admin). The query string is built by
  // activity.js from fixed words, days and a UUID only; an entry id is "{source}.{uuid}", encoded anyway.
  home: () => request('/v1/home'),
  activity: (query) => request(`/v1/admin/activity?${query}`),
  activitySummary: () => request('/v1/admin/activity/summary'),
  activityDetail: (id) => request(`/v1/admin/activity/${encodeURIComponent(id)}`),
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
  // KVKK data subject requests (ADR-0034), verso-admin only. The sub travels in the JSON body only, never in a URL: a
  // URL stays in proxy and trace records and in an error body's path.
  subjectSummary: (sub) => request('/v1/admin/subjects/summary', json('POST', { sub })),
  /** The export as a Blob; truncated when the server hit its limits (X-Subject-Export-Truncated). */
  async exportSubject(sub) {
    const { blob, headers } = await requestFile('/v1/admin/subjects/export', { sub });
    return { blob, truncated: headers.get('X-Subject-Export-Truncated') === 'true' };
  },
  deleteSubject: (sub, confirm) => request('/v1/admin/subjects/delete', json('POST', { sub, confirm })),
  /** Replaces, in every account's history, the answers that cite a deleted document; the body repeats its id. */
  redactAnswers: (documentId, confirm) => request(`/v1/admin/documents/${encodeURIComponent(documentId)}/answer-redaction`,
    json('POST', { confirm })),
  /** verso-admin: one page of the chunks of a document the admin reads (ADR-0036). */
  listChunks: (documentId, page = 0, size = 50) =>
    request(`/v1/admin/documents/${encodeURIComponent(documentId)}/chunks?page=${page}&size=${size}`),
  // Verified answer cards (ADR-0049), verso-admin only. Only ids, the state filter and page numbers go into a URL.
  /** state: DRAFT, VERIFIED, STALE, DISPUTED, ARCHIVED, or anything else for every card. */
  listKnowledgeCards: (state = '', page = 0, size = 20) => request(`/v1/admin/knowledge-cards?${
    ['DRAFT', 'VERIFIED', 'STALE', 'DISPUTED', 'ARCHIVED'].includes(state) ? `state=${state}&` : ''}page=${page}&size=${size}`),
  getKnowledgeCard: (id) => request(`/v1/admin/knowledge-cards/${encodeURIComponent(id)}`),
  /** From one of the admin's own answers; verify: true records the admin's verification at once ("Onayla"). */
  createKnowledgeCard: (conversationId, turnId, verify = true) => request('/v1/admin/knowledge-cards',
    json('POST', { conversationId, turnId, verify: verify === true })),
  verifyKnowledgeCard: (id) => request(`/v1/admin/knowledge-cards/${encodeURIComponent(id)}/verify`, { method: 'POST' }),
  archiveKnowledgeCard: (id) => request(`/v1/admin/knowledge-cards/${encodeURIComponent(id)}/archive`, { method: 'POST' }),
  listKnowledgeRuns: (page = 0, size = 10) => request(`/v1/admin/knowledge-eval/runs?page=${page}&size=${size}`),
  runKnowledgeEval: () => request('/v1/admin/knowledge-eval/runs', { method: 'POST' }),
  // Knowledge health and hooks (ADR-0052), verso-admin only. Only dates, ids, the format, page numbers and the unread
  // switch go into a URL.
  knowledgeHealth: (from, to) => request(`/v1/admin/knowledge-health?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
  /** The report as a file (MARKDOWN or JSON) for the consulting team. */
  exportKnowledgeHealth: (from, to, format = 'MARKDOWN') => getFile(`/v1/admin/knowledge-health/export?from=${
    encodeURIComponent(from)}&to=${encodeURIComponent(to)}&format=${format === 'JSON' ? 'JSON' : 'MARKDOWN'}`),
  listKnowledgeHealthRuns: (page = 0, size = 5) => request(`/v1/admin/knowledge-health/runs?page=${page}&size=${size}`),
  runKnowledgeHealth: () => request('/v1/admin/knowledge-health/runs', { method: 'POST' }),
  listHookRules: () => request('/v1/admin/knowledge-hooks'),
  setHookRule: (key, enabled) => request(`/v1/admin/knowledge-hooks/${encodeURIComponent(key)}`,
    json('PUT', { enabled: enabled === true })),
  listNotifications: (unread = false, page = 0, size = 20) =>
    request(`/v1/admin/notifications?unread=${unread === true}&page=${page}&size=${size}`),
  readNotification: (id) => request(`/v1/admin/notifications/${encodeURIComponent(id)}/read`, { method: 'POST' }),
  // Procedures / skills (ADR-0053), verso-admin only. Only ids, the state filter, version numbers and page numbers go
  // into a URL; drafts and versions travel in the JSON body.
  /** state: DRAFT, APPROVED, STALE, ARCHIVED, or anything else for every skill. */
  listSkills: (state = '', page = 0, size = 20) => request(`/v1/admin/skills?${
    ['DRAFT', 'APPROVED', 'STALE', 'ARCHIVED'].includes(state) ? `state=${state}&` : ''}page=${page}&size=${size}`),
  skillSuggestions: () => request('/v1/admin/skills/suggestions'),
  getSkill: (id) => request(`/v1/admin/skills/${encodeURIComponent(id)}`),
  /** { clusterId } or { cardIds, turns }; name optional; condense asks the local model to order and shorten. */
  draftSkill: (draft) => request('/v1/admin/skills', json('POST', draft)),
  updateSkill: (id, version) => request(`/v1/admin/skills/${encodeURIComponent(id)}`, json('PUT', version)),
  approveSkill: (id) => request(`/v1/admin/skills/${encodeURIComponent(id)}/approve`, { method: 'POST' }),
  archiveSkill: (id) => request(`/v1/admin/skills/${encodeURIComponent(id)}/archive`, { method: 'POST' }),
  skillDiff: (id, from, to) => request(`/v1/admin/skills/${encodeURIComponent(id)}/diff?from=${
    Math.max(1, Math.trunc(Number(from)) || 1)}&to=${Math.max(1, Math.trunc(Number(to)) || 1)}`),
  /** The SKILL.md bundle (zip) as a Blob for the admin's own download click. */
  exportSkill: (id) => getFile(`/v1/admin/skills/${encodeURIComponent(id)}/export`),
  // System map (ADR-0054). Read with the caller's own access: an entity appears only through documents this user may
  // read. Types, keys and prefixes are encoded by systemmap.js; a value it cannot use never reaches the server.
  systemMapStats: () => request('/v1/admin/system-map/stats'),
  /** type: a tab type or '' for every type but STEP; prefix: the start of a key or label. */
  systemEntities: (type = '', prefix = '', limit = 100) => request(entitiesPath(type, prefix, limit)),
  systemEntity: (type, key) => {
    const path = entityPath(type, key);
    return path ? request(path) : Promise.reject(new ApiError(400, 90002));
  },
  /** Any signed-in user: entity details matching a code, an abbreviation or a system name. */
  lookupSystemModel: (query) => {
    const path = lookupPath(query);
    return path ? request(path) : Promise.reject(new ApiError(400, 10051));
  },
  systemSuggestionSettings: () => request('/v1/admin/system-map/suggestions/settings'),
  systemSuggestions: (state = 'DRAFT') => request(suggestionsPath(state)),
  /** Asks the local model for relation suggestions on one document (409 11080 while the pass is off). */
  suggestSystemRelations: (documentId) =>
    request(`/v1/admin/system-map/suggestions/documents/${encodeURIComponent(documentId)}`, { method: 'POST' }),
  acceptSystemSuggestion: (id) => request(`/v1/admin/system-map/suggestions/${encodeURIComponent(id)}/accept`, { method: 'POST' }),
  rejectSystemSuggestion: (id) => request(`/v1/admin/system-map/suggestions/${encodeURIComponent(id)}/reject`, { method: 'POST' }),
  // Task skills, "Beceriler" (ADR-0057). Not the procedures above (ADR-0053). Only ids, the format and page numbers go
  // into a URL; form inputs and outputs travel in the JSON body (personal data, never in a URL).
  listTaskSkills: () => request('/v1/skills'),
  getTaskSkill: (skillId) => request(`/v1/skills/${encodeURIComponent(skillId)}`),
  startSkillRun: (skillId, body) => request(`/v1/skills/${encodeURIComponent(skillId)}/runs`, json('POST', body)),
  listSkillRuns: (page = 0, size = 20) => request(`/v1/skill-runs?page=${Math.max(0, Math.trunc(Number(page)) || 0)}&size=${
    Math.min(100, Math.max(1, Math.trunc(Number(size)) || 20))}`),
  getSkillRun: (runId) => request(`/v1/skill-runs/${encodeURIComponent(runId)}`),
  /** The progress events of a running run; StreamUnavailable when the caller should poll getSkillRun instead. */
  skillRunEvents: (runId, signal, onEvent) => streamRunEvents(runId, signal, onEvent),
  editSkillRun: (runId, body) => request(`/v1/skill-runs/${encodeURIComponent(runId)}/output`, json('PUT', body)),
  approveSkillRun: (runId) => request(`/v1/skill-runs/${encodeURIComponent(runId)}/approve`, { method: 'POST' }),
  rateSkillRun: (runId, body) => request(`/v1/skill-runs/${encodeURIComponent(runId)}/rating`, json('POST', body)),
  /** The run as a file (MARKDOWN or JSON) for the user's own download click. */
  exportSkillRun: (runId, format = 'MARKDOWN') => getFile(`/v1/skill-runs/${encodeURIComponent(runId)}/export?format=${
    format === 'JSON' ? 'JSON' : 'MARKDOWN'}`),
  deleteSkillRun: (runId) => request(`/v1/skill-runs/${encodeURIComponent(runId)}`, { method: 'DELETE' }),
  // verso-admin: the installation's switch per skill and the customer-authored skills (draft → approved).
  listAdminTaskSkills: () => request('/v1/admin/task-skills'),
  setTaskSkillEnabled: (skillId, enabled) => request(`/v1/admin/task-skills/${encodeURIComponent(skillId)}/enabled`,
    json('PUT', { enabled: enabled === true })),
  listCustomSkills: () => request('/v1/admin/custom-skills'),
  createCustomSkill: (definition) => request('/v1/admin/custom-skills', json('POST', { definition })),
  updateCustomSkill: (id, definition) => request(`/v1/admin/custom-skills/${encodeURIComponent(id)}`, json('PUT', { definition })),
  approveCustomSkill: (id) => request(`/v1/admin/custom-skills/${encodeURIComponent(id)}/approve`, { method: 'POST' }),
  archiveCustomSkill: (id) => request(`/v1/admin/custom-skills/${encodeURIComponent(id)}/archive`, { method: 'POST' }),
  validateCustomSkill: (definition) => request('/v1/admin/custom-skills/validate', json('POST', { definition })),
  // SMB connectors (ADR-0038, ADR-0042), verso-admin only. No call carries a password: credentialRef is the name of a
  // secret file on the server, and the API never returns a password. Only ids, page numbers and the state filter go
  // into a URL; everything else travels in the JSON body.
  listConnectors: (page = 0, size = 50) => request(`/v1/admin/connectors?page=${page}&size=${size}`),
  getConnector: (id) => request(`/v1/admin/connectors/${encodeURIComponent(id)}`),
  createConnector: (body) => request('/v1/admin/connectors', json('POST', body)),
  updateConnector: (id, body) => request(`/v1/admin/connectors/${encodeURIComponent(id)}`, json('PUT', body)),
  deleteConnector: (id) => request(`/v1/admin/connectors/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  /** Makes the connector due now; confirmMassDeletion lets that one run delete what the deletion safety held back. */
  syncConnector: (id, confirmMassDeletion = false) => request(`/v1/admin/connectors/${encodeURIComponent(id)}/sync`,
    json('POST', { confirmMassDeletion: confirmMassDeletion === true })),
  listConnectorRuns: (id, page = 0, size = 20) =>
    request(`/v1/admin/connectors/${encodeURIComponent(id)}/runs?page=${page}&size=${size}`),
  /** state: SYNCED, FAILED, SKIPPED, or anything else for every file. */
  listConnectorItems: (id, state = '', page = 0, size = 50) => request(`/v1/admin/connectors/${encodeURIComponent(id)}/items?${
    ['SYNCED', 'FAILED', 'SKIPPED'].includes(state) ? `state=${state}&` : ''}page=${page}&size=${size}`),
  // The model gateway (ADR-0056), verso-admin only. The API key travels once, in a JSON body (never a URL), and no
  // response ever carries it back: only keyStored and the last four characters (keyHint).
  modelConnections: () => request('/v1/admin/model-gateway/connections'),
  createModelConnection: (body) => request('/v1/admin/model-gateway/connections', json('POST', body)),
  updateModelConnection: (id, body) => request(`/v1/admin/model-gateway/connections/${encodeURIComponent(id)}`, json('PUT', body)),
  rotateModelKey: (id, apiKey) => request(`/v1/admin/model-gateway/connections/${encodeURIComponent(id)}/key`, json('POST', { apiKey })),
  testModelConnection: (id) => request(`/v1/admin/model-gateway/connections/${encodeURIComponent(id)}/test`, { method: 'POST' }),
  deleteModelConnection: (id) => request(`/v1/admin/model-gateway/connections/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  modelRoutes: () => request('/v1/admin/model-gateway/routes'),
  setModelRoute: (task, entries) => request(`/v1/admin/model-gateway/routes/${encodeURIComponent(task)}`, json('PUT', { entries })),
  setModelSettings: (userModelChoice) => request('/v1/admin/model-gateway/settings', json('PUT', { userModelChoice: userModelChoice === true })),
  modelPrices: () => request('/v1/admin/model-gateway/prices'),
  setModelPrice: (price) => request('/v1/admin/model-gateway/prices', json('PUT', price)),
  modelBudgets: () => request('/v1/admin/model-gateway/budgets'),
  setModelBudget: (budget) => request('/v1/admin/model-gateway/budgets', json('PUT', budget)),
  modelUsage: (month = '') => request(`/v1/admin/model-gateway/usage${/^\d{4}-\d{2}$/.test(month) ? `?month=${month}` : ''}`),
  /** Any signed-in user: the answer models offered, when the admin allows a choice (enabled=false otherwise). */
  modelChoices: () => request('/v1/models'),
};
