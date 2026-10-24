# API istemcisi denetim kaydı entegrasyonu [v1]

Verso her önemli işlemi (belge yükleme ve silme, soru, geri bildirim, konuşma silme, yönetici metriklerini görüntüleme, denetim dışa aktarımı, yönetici uçlarına yetkisiz erişim) `audit` şemasına değiştirilemez bir satır olarak yazar. `verso-admin` rolündeki yönetici bu kaydı sayfalı okur ya da CSV olarak alır. Satırlar yalnız kimlik (id) ve sabit sözcük taşır; soru, cevap, dosya adı, IP ve serbest metin hiç içermez.

> **OpenAPI doğrulanmadı:** uçlar ve alanlar `AdminAuditController`, `AuditQueryService`, `AuditCsv` ve `audit-api` DTO'larından çıkarıldı (ADR-0004). Generated client paketi: yok.

Bu doküman **24 Ekim 2026** tarihli `feature/doguyaras-denetim-kaydi` branch'indeki koda (taban `develop` `c819046`) dayanır. Karar: [ADR-0026](adr/0026-denetim-kaydi.md).

## Değişikliklerin özeti
- Yeni yönetici ucu: `GET /v1/admin/audit?from&to&action&page&size` (sayfalı JSON).
- Yeni yönetici ucu: `GET /v1/admin/audit/export?from&to` (CSV).
- Yeni hata kodu: 12001 `AUDIT_RANGE_INVALID`.
- Mevcut uçların sözleşmesi **değişmedi**: yanıtlara alan eklenmedi, yeni başlık yok. Denetim yazımı bir isteği hiçbir koşulda başarısız yapmaz.
- Mobil ve panel için etki yok: panel bu sürümde denetim ekranı göstermez (kapsam dışı). İç (internal) çağıran yoktur.

## Kurallar
- **Kritik:** İki uç da yalnız `verso-admin` rolündedir (ADR-0019). Başka her kimlikli çağıran `403` (`90101 ACCESS_DENIED`), kimliksiz `401` alır. İstemci rolü `GET /v1/info` `roles`'tan okuyup ekranı gizleyebilir; asıl kontrol sunucudadır.
- **Kritik:** Yanıtlar kimin ne yaptığını söyler: `Cache-Control: no-store, private`. İstemci kalıcı depoya yazmaz, URL'e ya da analitiğe koymaz.
- `actor` token'ın opak `sub` değeridir (IdP'de bir kişiye bağlanabilir, bu yüzden kişisel veri sayılır). Ad, e-posta ya da IP yoktur.
- Başarı yanıtları ham, hatalar zarflıdır (ADR-0004). Bilinmeyen alanlar yok sayılır; bilinmeyen `action` değeri ekranda ham gösterilir (liste ileride genişleyebilir).
- Kayıt, `VERSO_AUDIT_RETENTION_DAYS` gün (varsayılan 365) tutulur; daha eskisi sorgulanmaz.

## Değişiklikler ve istemciye etkisi
### 1. Sayfalı sorgu
`from` ve `to` **dahil** takvim günleridir (ISO `2026-10-01`), kurulumun saat diliminde (`Europe/Istanbul`); `occurredAt` ise UTC'dir. Aralık en çok 366 gündür. `action` isteğe bağlıdır. En yeni satır önce gelir. `size` en çok 200'dür; 200'ü aşan değer sessizce kısılmaz, `400` olur.

### 2. CSV dışa aktarım
Aynı pencere, en eski satır önce, UTF-8, CRLF satır sonu (RFC 4180). En çok 100 000 satır döner. Pencere bundan büyükse yanıt başlığı `X-Audit-Export-Truncated: true` olur ve ilk 100 000 satır gelir; kalanı için pencereyi daralt. Dışa aktarım başlamadan önce kendisi de `AUDIT_EXPORTED` olarak kaydedilir.

CSV enjeksiyonuna karşı `=`, `+`, `-`, `@` (ve sekme/CR) ile başlayan her hücrenin başına `'` eklenir. Bu bilinçli bir kaçıştır: tabloda `'-evil` gördüğünüzde değer `-evil`'dir. Programatik tüketici `'`yi, ardından bu karakterlerden biri geliyorsa, çıkarıp okumalıdır.

### 3. Hangi eylemler kaydedilir
| `action` | Ne zaman | `targetType` / `targetId` | Ek alanlar |
|---|---|---|---|
| `DOCUMENT_UPLOADED` | belge kabul edildi (aynı `X-Idempotency-Key` tekrarı sayılmaz) | `DOCUMENT` / belge id | – |
| `DOCUMENT_DELETED` | belge silindi | `DOCUMENT` / belge id | – |
| `QUESTION_ASKED` | soru cevaplandı | `TURN` / tur id (tur saklanamadıysa `null`) | `conversationId`, `detailCode` = `ANSWERED` / `NOT_FOUND` / `UNCITED`, `citationDocumentIds`, `modelId`, `mode` |
| `FEEDBACK_GIVEN` | cevap puanlandı | `TURN` / tur id | `conversationId`, `detailCode` = `UP` / `DOWN` |
| `CONVERSATION_DELETED` | tek konuşma silindi | `CONVERSATION` / konuşma id | – |
| `CONVERSATIONS_DELETED_ALL` | çağıranın bütün konuşmaları silindi | `CONVERSATION` / `null` | – |
| `ADMIN_METRICS_VIEWED` | `GET /v1/admin/metrics` | `METRICS` / `null` | – |
| `AUDIT_EXPORTED` | CSV alındı | `AUDIT_LOG` / `null` | – |
| `ACCESS_DENIED` | kimlikli ama rolsüz çağıran bir yönetici ucuna geldi (`outcome` = `DENIED`) | `ADMIN_API` / `null` | `detailCode` = `ADMIN_METRICS` / `ADMIN_AUDIT` |

Kaydedilmeyenler: değişiklik yapmayan başarısız işlemler (404, 400), okumalar, kimliksiz istekler (401). Yeniden indeksleme eylemi yoktur (böyle bir uç henüz yok).

## Kullanılan endpoint'ler
| Method | Path | Auth | Idempotency | Sınır | Zarf |
|---|---|---|---|---|---|
| GET | `/v1/admin/audit?from&to&action&page&size` | user JWT, `verso-admin` | – (salt okunur) | aralık ≤ 366 gün, `size` 1–200 (varsayılan 50), `page` 0–10000 | ham |
| GET | `/v1/admin/audit/export?from&to` | user JWT, `verso-admin` | – | aralık ≤ 366 gün, ≤ 100 000 satır | CSV (`text/csv`); hata zarflı |

## API sözleşmesi
### GET /v1/admin/audit
Sorgu: `from` ve `to` zorunlu (`yyyy-MM-dd`), `action` isteğe bağlı (`AuditAction` değerlerinden biri), `page` (varsayılan 0), `size` (varsayılan 50).

```json
{
  "data": [
    {
      "id": "01a16da4-4c18-7db5-9eb0-1e8cf00d54c7",
      "occurredAt": "2026-10-24T11:20:43.016238Z",
      "actor": "f3c1b8e2-…",
      "actorRoles": ["verso-user"],
      "action": "QUESTION_ASKED",
      "outcome": "SUCCESS",
      "targetType": "TURN",
      "targetId": "01a16da4-4c18-7db5-9eb0-1e8cf00d54c7",
      "conversationId": "01a16da4-4c17-7ac4-8a76-2e0201c93efd",
      "detailCode": "ANSWERED",
      "modelId": "gemma4:e2b",
      "citationDocumentIds": ["01a16da4-4bf8-7b90-8682-7ae5a2a14c65"],
      "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
      "mode": "local"
    }
  ],
  "page": { "number": 0, "size": 50, "totalElements": 1, "totalPages": 1 }
}
```

| Alan | Tip | Not |
|---|---|---|
| `id` | UUID | satırın kimliği (UUIDv7, zamana göre sıralanır) |
| `occurredAt` | ISO-8601 UTC | veritabanının saati |
| `actor` | string | token `sub` |
| `actorRoles` | string[] | yalnız `verso-admin`, `verso-user` |
| `action` | enum | yukarıdaki tablo |
| `outcome` | `SUCCESS` \| `DENIED` | – |
| `targetType` | enum | `DOCUMENT`, `CONVERSATION`, `TURN`, `METRICS`, `AUDIT_LOG`, `ADMIN_API` |
| `targetId`, `conversationId` | UUID \| null | – |
| `detailCode` | string \| null | sabit UPPER_SNAKE sözcük |
| `modelId` | string \| null | cevabı üreten model (yapılandırmadan) |
| `citationDocumentIds` | UUID[] | atıf yapılan belgeler (en çok 50) |
| `traceId` | string | isteğin 32 haneli trace kimliği; yanıttaki `X-Trace-Id` ile aynıdır |
| `mode` | `local` \| `cloud` | kurulumun/cevabın modu |

### GET /v1/admin/audit/export
Sorgu: `from`, `to` zorunlu. Yanıt başlıkları: `Content-Type: text/csv;charset=UTF-8`, `Cache-Control: no-store, private`, `Content-Disposition: attachment; filename="audit-<from>_<to>.csv"`, `X-Audit-Export-Truncated: true|false`, `X-Audit-Export-Max-Rows`. Sütunlar (sırayla): `id, occurred_at, actor, actor_roles, action, outcome, target_type, target_id, conversation_id, detail_code, model_id, citation_document_ids, trace_id, mode`. Dizi alanları boşlukla ayrılır.

```text
id,occurred_at,actor,actor_roles,action,outcome,target_type,target_id,conversation_id,detail_code,model_id,citation_document_ids,trace_id,mode
01a16da4-…,2026-10-24T11:20:43.016238Z,f3c1b8e2-…,verso-user,DOCUMENT_UPLOADED,SUCCESS,DOCUMENT,01a16da4-…,,,,,4bf92f35…,local
```

## Hata kodları ve ekran davranışı
| HTTP | Kod | Ne zaman | Ekran |
|---|---|---|---|
| 400 | 12001 `AUDIT_RANGE_INVALID` | `to`, `from`'dan önce ya da aralık 366 günden uzun | alanı vurgula, düzeltmeyi iste |
| 400 | 90000 serisi | `size` 1–200 dışında, `page` 0–10000 dışında, bozuk tarih, bilinmeyen `action`, eksik `from`/`to` | aynı |
| 401 | 90100 | token yok ya da geçersiz | oturumu yenile |
| 403 | 90101 `ACCESS_DENIED` | `verso-admin` değil | ekranı gösterme |
| 503 | 99997 `SERVICE_UNAVAILABLE` | denetim veritabanına ulaşılamıyor; `Retry-After` var | yeniden dene düğmesi |

Kayıt yazımındaki bir arıza istemciye **hiçbir yerde** yansımaz: işlem başarılı döner, kayıp sunucuda sayılır ve alarm üretir.

## Güvenlik ve log kuralları
- Yanıt ve CSV'de soru, cevap, başlık, dosya adı, IP ve user-agent yoktur; kimlik ve sabit sözcük vardır.
- Kayıt değiştirilemez: uygulama rolü yalnız ekleyebilir ve okuyabilir. Saklama süresi dolanlar tek bir veritabanı fonksiyonuyla silinir; 30 günden yeni kayıt hiçbir yolla silinemez.
- Sunucu log'u yalnız eylemi ve istisna tipini yazar. İstemci de kimliği (`actor`) log'lamaz.

## Önceki sürüme göre farklar
Önceki sürümde denetim kaydı ve bu uçlar yoktu. Mevcut hiçbir uç ya da alan değişmedi; istemci güncellemesi gerekmez.

## Manuel test akışı
```bash
TOKEN=$(bash scripts/demo-token.sh)   # verso-admin rolü olan kullanıcı
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/v1/admin/audit?from=2026-10-01&to=2026-10-31&action=QUESTION_ASKED&size=20"
curl -s -D - -H "Authorization: Bearer $TOKEN" -o audit.csv \
  "http://localhost:8080/v1/admin/audit/export?from=2026-10-01&to=2026-10-31"
```
Rolsüz bir kullanıcıyla aynı istek `403` verir ve `ACCESS_DENIED` olarak kaydedilir.

## Deploy ve uyumluluk
- Yeni secret'lar: `SECRET_DB_AUDIT_PASSWORD`, `SECRET_DB_AUDIT_MIGRATE_PASSWORD`; yeni ayar `VERSO_AUDIT_RETENTION_DAYS`. Var olan kurulumlar için `40-modules.sh` yükseltmesi README "Denetim kaydı olan bir sürüme yükseltme" bölümündedir.
- Geri alma: uçlar ve yayıncılar kaldırılır; `audit` şeması veriyle kalabilir.

## Açık sorular
- Panel denetim ekranı (kapsam dışı).
- Syslog/JSON log sink ve SIEM gönderimi (ADR-0026 takip işi).
