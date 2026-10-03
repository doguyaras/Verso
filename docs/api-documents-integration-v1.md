# API istemcisi belge yükleme entegrasyonu [v1]

Verso'ya belge (PDF, DOCX, TXT, MD; ADR-0016) yükleme, listeleme ve silme uçları. Yüklenen belge arka planda sayfa sayfa (PDF) ya da bölüm bölüm (diğerleri) okunur ve arama için hazırlanır; soru sorma faz 5'te gelir. İstemci (müşteri sistemi, demo betiği, ileride yönetim paneli) belgeyi yükler ve durumunu `PENDING → READY` ya da `FAILED` olana kadar izler.

> **OpenAPI doğrulanmadı:** projede henüz OpenAPI üretimi yok (ADR-0004, P1). Uç ve alan listesi `DocumentController` imzalarından ve `document-api` DTO'larından çıkarıldı. Generated client paketi: yok.

Bu doküman **3 Ekim 2026** tarihli `feature/doguyaras-faz-4-belge-alimi` branch'indeki koda dayanır (ADR-0011).

## Değişikliklerin özeti
- Yeni uçlar: `POST /v1/documents`, `GET /v1/documents`, `GET /v1/documents/{documentId}`, `DELETE /v1/documents/{documentId}`.
- Yükleme asenkrondur: 201 yanıtındaki belge `PENDING`'dir; istemci durumu sorgular.
- Yeni hata kodları: 10001, 10010, 10011, 10013, 10014, 10015. Ortak koda eklenen: 99997 (geçici olarak kullanılamıyor).
- `API-Version` header'ı artık değerlendirilir: isteğe bağlı, varsayılan `1.0`; bilinmeyen sürüm 400 `90020`.
- `X-Idempotency-Key` ile tekrar edilen yükleme ikinci belge oluşturmaz.

## Kurallar
- **Kritik:** Başka hesabın belgesi her uçta **404** döner (403 değil). İstemci "yetkiniz yok" değil "bulunamadı" göstermelidir; varlık açığa çıkmaz.
- **Kritik:** `DELETE` geri alınamaz. Belge, sayfa metinleri ve vektörler kalıcı olarak silinir (KVKK); yalnız yedeklerde saklama süresi boyunca kalır (ADR-0009).
- Her istek `Authorization: Bearer <access token>` taşır (ADR-0010). Token yalnız bellekte tutulur, log'a yazılmaz.
- Başarı yanıtları **ham**dır (zarf yok). Hata yanıtları her zaman zarflıdır: `{ok:false, data:null, error:{code, message, service, path, timestamp, traceId, details}}` (ADR-0004).
- İstemci hata **koduna** göre davranır, `message` metnine göre değil.
- Bilinmeyen enum değeri (yeni `status` ya da `failureReason`) hata değildir: genel bir "işleniyor" ya da "işlenemedi" durumu gösterilir.

## Kullanılan endpoint'ler
| Method | Path | Auth | Idempotency | Sınır (429/503) | Zarf |
|---|---|---|---|---|---|
| POST | `/v1/documents` | user JWT | `X-Idempotency-Key` (isteğe bağlı, UUID) | 503 `10014` (aynı anda çok yükleme), 429 `10015` (kuyruk dolu) | başarı ham, hata zarflı |
| GET | `/v1/documents?page=&size=` | user JWT | – | – | başarı ham, hata zarflı |
| GET | `/v1/documents/{documentId}` | user JWT | – | – | başarı ham, hata zarflı |
| DELETE | `/v1/documents/{documentId}` | user JWT | doğal olarak idempotent (ikinci çağrı 404) | – | 204 gövdesiz, hata zarflı |

Tüm yanıtlar `Cache-Control: private, no-store` ve `X-Trace-Id` taşır. Destek talebinde `traceId` iletilir.

## API sözleşmesi

### Belge yükleme
`multipart/form-data`, tek parça `file`. Dosya en fazla 20 MB. Tür, dosya adı ve ilk baytlardan anlaşılır; gönderilen media type'a bakılmaz:

| Tür | Koşul |
|---|---|
| PDF | İlk 1024 baytta `%PDF-` (adı `.docx`/`.txt`/`.md` olan ve o türe uyan dosya o türdür) |
| DOCX | `.docx` adı ve ZIP dosyası (parolalı DOCX ve eski `.doc` desteklenmez) |
| TXT, MD | `.txt`, `.md` ya da `.markdown` adı ve ilk 8 KB'ta NUL bayt yok (sonrasında NUL varsa işlenirken `INVALID_FILE`). UTF-8, UTF-16 (BOM ile) ya da Windows-1254 |

#### Senaryo: kabul edildi
`HTTP 201`, ham, `Location: /v1/documents/{id}`:
```json
{ "id": "01a0fe8e-333d-71db-a698-635e9161ce9a", "fileName": "izin-yonetmeligi.pdf", "format": "PDF", "status": "PENDING",
  "sizeBytes": 1212, "pageCount": null, "chunkCount": null, "failureReason": null,
  "createdAt": "2026-10-03T09:00:00Z", "updatedAt": "2026-10-03T09:00:00Z" }
```
Aynı `X-Idempotency-Key` ile tekrar: yine `201` ve **ilk** belgenin güncel hali; ikinci dosya yok sayılır (ilk istek kazanır).

#### Senaryo: desteklenmeyen tür
`HTTP 415`, zarflı:
```json
{ "ok": false, "data": null, "error": { "code": 10010, "message": "The file is not a supported document (PDF, DOCX, TXT or MD).", "service": "document",
  "path": "/v1/documents", "timestamp": 1790935200000, "traceId": "…", "details": [] } }
```

#### Senaryo: sunucu meşgul
`HTTP 503` + `Retry-After: 5`, kod `10014`. `Retry-After` saniye bekleyip aynı `X-Idempotency-Key` ile tekrar dene.

#### Alan eşleme (`DocumentResponse`)
| Alan | Tip | Her zaman var | Not |
|---|---|---|---|
| `id` | UUID | evet | Belge kimliği |
| `fileName` | string | evet | Yüklenen ad; yol parçaları ve görünmez karakterler atılmış, en çok 255 karakter |
| `format` | enum | evet | `PDF`, `DOCX`, `TXT`, `MD` (ADR-0016'da eklendi); **bilinmeyen → "belge"** |
| `status` | enum | evet | `PENDING`, `PROCESSING`, `READY`, `FAILED`; **bilinmeyen → "işleniyor"** |
| `sizeBytes` | number | evet | |
| `pageCount` | number \| null | hayır | `READY`'den önce null. PDF'te sayfa, diğer türlerde bölüm sayısı |
| `chunkCount` | number \| null | hayır | `READY`'den önce null |
| `failureReason` | enum \| null | yalnız `FAILED`'da | `NOT_A_PDF`, `ENCRYPTED`, `TOO_MANY_PAGES`, `TOO_MUCH_TEXT`, `UNSUPPORTED_PDF`, `INVALID_FILE`, `UNSUPPORTED_FILE`, `NO_TEXT`, `PROCESSING_FAILED`; **bilinmeyen → "belge işlenemedi"** |
| `createdAt`, `updatedAt` | ISO-8601 | evet | UTC |

#### fetch örneği
```js
const form = new FormData();
form.append('file', file, file.name);
const res = await fetch('/v1/documents', {
  method: 'POST', body: form,
  headers: { Authorization: `Bearer ${token}`, 'X-Idempotency-Key': crypto.randomUUID() },
});
if (res.status === 201) return await res.json();          // ham DocumentResponse
const { error } = await res.json();                       // zarf
if (res.status === 503 || res.status === 429) retryAfter(res.headers.get('Retry-After') ?? 30);
throw new ApiError(error.code, error.traceId);

const STATUS = { PENDING: 'Sırada', PROCESSING: 'İşleniyor', READY: 'Hazır', FAILED: 'İşlenemedi' };
const label = STATUS[doc.status] ?? 'İşleniyor';           // bilinmeyen enum fallback
```

### Durum izleme
`GET /v1/documents/{id}` ile 3–5 sn aralıkla sorgula; `READY` ya da `FAILED` gelince dur. Model kapalıyken belge bir süre `PENDING` kalabilir; bu hata değildir, worker model dönünce işler.

### Listeleme
`GET /v1/documents?page=0&size=20`, `size` 1–100, `page` ≥ 0, en yeni önce:
```json
{ "data": [ { "id": "…", "fileName": "…", "status": "READY", "…": "…" } ],
  "page": { "number": 0, "size": 20, "totalElements": 1, "totalPages": 1 } }
```

### Silme
`DELETE /v1/documents/{id}` → `204`. Başka hesabın ya da olmayan belge → `404 10001`.

## Hata kodları ve ekran davranışı
| code | HTTP | Anlam | Ekran aksiyonu | Retry? |
|---|---|---|---|---|
| 10001 | 404 | Belge yok (ya da başka hesabın) | "Belge bulunamadı", listeye dön | hayır |
| 10010 | 415 | Desteklenmeyen tür | "PDF, DOCX, TXT ya da MD yükleyebilirsiniz" | hayır |
| 10011 | 400 | Boş dosya | "Dosya boş" | hayır |
| 10013 | 409 | Hesabın belge sınırı (200) | "Belge sınırına ulaştınız, eski belgeleri silin" | hayır |
| 10014 | 503 | Aynı anda çok yükleme | Sessizce `Retry-After` kadar bekle, tekrar dene | evet |
| 10015 | 429 | Kuyrukta çok belge (20) | "Önceki belgeleriniz işleniyor, sonra deneyin" | evet, birkaç dakika sonra |
| 90002 | 400 | Geçersiz id ya da `X-Idempotency-Key` | istemci hatası; logla | hayır |
| 90003 | 400 | `file` parçası yok | istemci hatası | hayır |
| 90014 | 413 | Dosya 20 MB'tan büyük | "Dosya en fazla 20 MB olabilir" | hayır |
| 90020 | 400 | Desteklenmeyen `API-Version` | istemci hatası | hayır |
| 90100 | 401 | Token yok ya da geçersiz | Yeniden giriş | token yenilendikten sonra |
| 90103 | 503 | Kimlik sağlayıcısına ulaşılamıyor | "Geçici sorun" | evet, `Retry-After` |
| 99997 | 503 | Veritabanı geçici olarak kullanılamıyor | "Geçici sorun" | evet, `Retry-After` |
| 99999 | 500 | Beklenmeyen hata | "Bir sorun oluştu", `traceId` göster | hayır |

`FAILED` belgede `failureReason` → mesaj: `ENCRYPTED` "Parolalı PDF desteklenmiyor", `NO_TEXT` "Belgede okunabilir metin yok (taranmış PDF olabilir)", `TOO_MANY_PAGES` "En fazla 500 sayfa", `TOO_MUCH_TEXT` "Belge çok büyük", `UNSUPPORTED_PDF`/`NOT_A_PDF`/`INVALID_FILE` "Belge okunamadı", `UNSUPPORTED_FILE` "Bu Word dosyasının yapısı desteklenmiyor", `PROCESSING_FAILED` "Belge işlenemedi, tekrar yükleyin".

## Güvenlik ve log kuralları
- Dosya adı ve belge içeriği kişisel veri olabilir: istemci log'una, analitiğe ve hata raporuna yazılmaz (llm-rules 2.1). Loglanabilenler: belge id'si, durum, `traceId`.
- Yanıtlar önbelleğe alınmaz (`no-store`).

## Önceki sürüme göre farklar
- Önceki doküman yok.

## Manuel test akışı
### API ile (compose yığını)
1. `TOKEN="$(bash scripts/demo-token.sh)"` (tarayıcıda `demo` ile giriş).
2. `curl -s -H "Authorization: Bearer $TOKEN" -F "file=@scripts/fixtures/smoke.pdf;type=application/pdf" http://127.0.0.1:8080/v1/documents` → 201, `PENDING`.
3. `curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/v1/documents/<id>` → birkaç saniye içinde `READY`, `pageCount: 2`.
4. `curl -s -X DELETE -H "Authorization: Bearer $TOKEN" -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/v1/documents/<id>` → 204; tekrar GET → 404.
5. Otomatik: `bash scripts/ingest-smoke.sh`.

## Deploy ve uyumluluk
Yeni uçlar; eski istemci yok. `API-Version` gönderilmezse `1.0` sayılır. Enum'lar genişleyebilir (fallback zorunlu). Sunset yok.

## Açık sorular
- Yönetim paneli (faz 10) bu uçları mı kullanacak, yoksa ayrı bir yönetim API'si mi?
- Hesap silme (IdP'de silinen kullanıcının belgeleri) için uç ne zaman gelecek? (ADR-0011: faz 10)
