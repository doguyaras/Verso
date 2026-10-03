// The Verso API as the panel uses it (docs/api-documents-integration-v1.md, docs/api-questions-integration-v1.md).
// Success bodies are plain JSON, errors the common envelope; every error becomes an ApiError with a Turkish message
// for the screen (the table follows the integration documents). Nothing is logged to the console: questions,
// answers and file names are personal data (llm-rules 2.1).
import { CONFIG } from './config.js';
import { accessToken } from './auth.js';

export const MESSAGES = Object.freeze({
  10001: 'Belge bulunamadı.',
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

export const api = {
  listDocuments: (page = 0, size = PAGE_SIZE) => request(`/v1/documents?page=${page}&size=${size}`),
  getDocument: (id) => request(`/v1/documents/${encodeURIComponent(id)}`),
  deleteDocument: (id) => request(`/v1/documents/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  /** Every document of the account (the quota keeps it to two pages at most). */
  async listAllDocuments() {
    const first = await api.listDocuments(0);
    const rest = [];
    for (let page = 1; page < first.page.totalPages; page++) rest.push(...(await api.listDocuments(page)).data);
    return [...first.data, ...rest];
  },
  /**
   * Uploads one file and reports the bytes sent (fetch cannot report upload progress, XMLHttpRequest can). The same
   * idempotency key would make a retry return the first document instead of a second one.
   */
  async upload(file, onProgress = () => {}) {
    const token = await accessToken();
    if (!token) throw new ApiError(401, 90100);
    const form = new FormData();
    form.append('file', file, file.name);
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${CONFIG.api}/v1/documents`);
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
  ask: (question) => request('/v1/questions', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question }) }),
  info: () => request('/v1/info'),
};
