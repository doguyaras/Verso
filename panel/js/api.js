// The Verso API as the panel uses it (docs/api-documents-integration-v1.md, docs/api-questions-integration-v1.md).
// Success bodies are plain JSON, errors the common envelope; every error becomes an ApiError with a Turkish message
// for the screen (the table follows the integration documents). Nothing is logged to the console: questions,
// answers and file names are personal data (llm-rules 2.1).
import { CONFIG } from './config.js';
import { accessToken } from './auth.js';

export const MESSAGES = Object.freeze({
  10001: 'Belge bulunamadı.',
  10010: 'Bu dosya bir PDF değil.',
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
  90004: 'İstek reddedildi.',
  90012: 'Bu dosya türü desteklenmiyor.',
  90020: 'API sürümü desteklenmiyor.',
  90014: 'Dosya ya da istek çok büyük (en çok 20 MB).',
  90100: 'Oturumunuz sona erdi; yeniden giriş yapın.',
  90103: 'Giriş servisine şu an ulaşılamıyor.',
  99997: 'Servis geçici olarak kullanılamıyor.',
});

export class ApiError extends Error {
  constructor(status, code, retryAfter) {
    super(MESSAGES[code] ?? (status >= 500 ? 'Beklenmeyen bir sunucu hatası oluştu.' : 'İstek tamamlanamadı.'));
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

export const api = {
  listDocuments: (page = 0, size = 50) => request(`/v1/documents?page=${page}&size=${size}`),
  getDocument: (id) => request(`/v1/documents/${encodeURIComponent(id)}`),
  deleteDocument: (id) => request(`/v1/documents/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  upload(file) {
    const form = new FormData();
    form.append('file', file, file.name);
    return request('/v1/documents', { method: 'POST', body: form, headers: { 'X-Idempotency-Key': crypto.randomUUID() } });
  },
  ask: (question) => request('/v1/questions', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question }) }),
  info: () => request('/v1/info'),
};
