# API istemcisi KVKK ilgili kişi başvurusu entegrasyonu [v1]

`verso-admin` rolündeki yönetici, bir kişinin (token `sub`'ı ile) Verso'da tutulan verisini bulur, JSON olarak dışa aktarır (KVKK md. 11 erişim başvurusu) ve siler (silme başvurusu). Ayrıca silinmiş bir belgeden alıntı yapan saklı cevapları sabit bir notla değiştirebilir.

> **OpenAPI doğrulanmadı:** uçlar ve alanlar `AdminSubjectController`, `AdminAnswerRedactionController`, `SubjectRequestServiceImpl` ve `privacy-api`, `qa-api` (`SubjectConversation`, `SubjectTurn`), `audit-api` (`AuditSubjectEvent`) DTO'larından çıkarıldı (ADR-0004). Generated client paketi: yok.

Bu doküman **24 Ekim 2026** tarihli `feature/doguyaras-silme-basvuru` branch'indeki koda (taban `develop` `287cbbb`) dayanır. Karar: [ADR-0034](adr/0034-silme-ve-basvuru-akisi.md). İşletim: [runbook](runbooks/kvkk-basvurusu.md).

## Değişikliklerin özeti
- Yeni yönetici uçları: `POST /v1/admin/subjects/summary`, `POST /v1/admin/subjects/export`, `POST /v1/admin/subjects/delete`, `POST /v1/admin/documents/{documentId}/answer-redaction`.
- Yeni hata kodu bloğu **privacy 13000–13999**: 13001, 13002, 13020, 13021.
- Yeni denetim eylemleri (`GET /v1/admin/audit` `action` değerleri): `SUBJECT_VIEWED`, `SUBJECT_EXPORTED`, `SUBJECT_DELETED`, `ANSWERS_REDACTED`; yeni hedef türü `SUBJECT`. Liste genişleyebilir; istemci bilinmeyen değeri ham gösterir.
- Mevcut uçların sözleşmesi **değişmedi**. Karartılmış bir turun `answer` alanı `GET /v1/conversations/{id}` yanıtında sabit not metnidir; alan, tür ve diğer alanlar aynıdır.
- Panel ve mobil için etki yok: panelde başvuru ekranı yok (kapsam dışı). İç (internal) çağıran yoktur.

## Kurallar
- **Kritik:** Dört uç da yalnız `verso-admin` rolündedir (ADR-0019). Başka her kimlikli çağıran `403` (`90101 ACCESS_DENIED`; reddin kendisi denetim kaydına `ADMIN_SUBJECT` ya da `ADMIN_ANSWER_REDACTION` olarak yazılır), kimliksiz `401` alır.
- **Kritik:** Yanıtlar kişisel veri taşır: `Cache-Control: no-store, private`. İstemci yanıtı kalıcı depoya, URL'e, log'a ya da analitiğe koymaz; dışa aktarım dosyası başvuru dosyasında, kurumun kişisel veri kurallarıyla saklanır.
- **Kritik:** Silme ve karartma geri alınamaz. İkisi de gövdede hedefi **yeniden** ister (`confirm`); eşleşmezse hiçbir şey değişmez.
- **Kritik:** `sub` yalnız JSON gövdede taşınır, URL'de asla (okumalar da `POST`): URL hata gövdesinin `path` alanında, proxy ve izleme kayıtlarında kalır. İstemci `sub`'ı query ya da path'e koymaz.
- `sub` token'ın opak `sub` değeridir ve `[A-Za-z0-9._@:-]{1,255}` biçiminde olmalıdır (oturum açmada token'a uygulanan biçim). Başka değer `400` `13001`. Gövde: `{"sub": "<sub>"}` (özet, dışa aktarım), `Content-Type: application/json`.
- Başarı yanıtları ham, hatalar zarflıdır (ADR-0004). Bilinmeyen alanlar yok sayılır.
- Kişi Verso'da **yalnız hesap kimliğiyle** bulunur. Belge metninde ad araması yoktur (ADR-0034 bölüm 7).

## Değişiklikler ve istemciye etkisi

### 1. Özet: `POST /v1/admin/subjects/summary`
```json
{
  "history": {"conversations": 2, "turns": 7, "feedback": 1, "activeDays": 3},
  "documents": {
    "personalCount": 1,
    "personal": [{"id": "0199…", "fileName": "izin.pdf", "format": "PDF", "status": "READY", "sizeBytes": 48211,
                  "collectionId": null, "createdAt": "2026-10-20T08:00:00Z"}],
    "collectionCount": 1,
    "collection": [{"id": "0199…", "fileName": "ortak.pdf", "format": "PDF", "status": "READY", "sizeBytes": 1200,
                    "collectionId": "0199…", "createdAt": "2026-10-21T08:00:00Z"}],
    "grantedCollections": ["0199…"],
    "createdCollections": [],
    "listsTruncated": false
  },
  "auditEventsAsActor": 42
}
```
- Sayılar kesindir; listeler en çok 1 000 kayıttır, kesildiyse `listsTruncated: true`.
- `collection`: kişinin bir bilgi alanına yüklediği belgeler (kurumun). `grantedCollections`: kişiye adıyla (`user:<sub>`) açılmış alanlar. `createdCollections`: kişinin (yönetici olarak) oluşturduğu alanlar.
- Belge içeriği, sayfa ya da parça metni **dönmez**.
- Her çağrı `SUBJECT_VIEWED` olarak denetim kaydına yazılır.

### 2. Dışa aktarım: `POST /v1/admin/subjects/export`
- `200`, `Content-Type: application/json;charset=UTF-8`, `Content-Disposition: attachment; filename="verso-kvkk-export.json"` (sabit ad; `sub` başlığa yazılmaz), `X-Subject-Export-Truncated: true|false`.
- Gövde tek JSON belgedir, akış halinde yazılır:
```json
{
  "format": "verso-subject-export/1",
  "subject": "<sub>",
  "exportedAt": "2026-10-24T10:00:00Z",
  "truncated": false,
  "history": {"conversations": 2, "turns": 7, "feedback": 1, "activeDays": 3},
  "auditEventsAsActorCount": 42,
  "conversations": [
    {"id": "…", "title": "…", "createdAt": "…", "updatedAt": "…",
     "turns": [{"id": "…", "createdAt": "…", "question": "…", "answer": "…", "outcome": "ANSWERED", "mode": "local",
                "model": "gemma4:e2b", "citations": [{"number": 1, "documentId": "…", "page": 3, "unit": "PAGE"}],
                "feedback": {"rating": "DOWN", "reason": "OUTDATED", "citationNumber": null, "updatedAt": "…"}}]}
  ],
  "documents": { "...": "özetteki documents nesnesi" },
  "auditEventsAsActor": [{"id": "…", "occurredAt": "…", "action": "QUESTION_ASKED", "outcome": "SUCCESS",
                          "targetType": "TURN", "targetId": "…"}]
}
```
- Sınırlar: en çok **2 000 konuşma** (her biri en çok 200 tur), en çok **100 000 denetim satırı**, belge listeleri 1 000. Sınır aşılırsa başlık ve `truncated` `true` olur; kalan için kurum sınırı yükseltmelidir (ADR-0034 "Yeniden değerlendirme").
- Atıflar yalnız referanstır (belge kimliği, sayfa); pasaj metni geçmişte tutulmaz (ADR-0023). Denetim satırlarında aktörün rolleri ve trace id yoktur.
- Dışa aktarım başlamadan **önce** `SUBJECT_EXPORTED` (`detailCode` `COMPLETE` ya da `TRUNCATED`) yazılır; yarıda kopan bir indirme de erişim sayılır. Yarıda kopan gövde geçersiz JSON'dur; yeniden indirin.

### 3. Silme: `POST /v1/admin/subjects/delete`
İstek:
```json
{"sub": "<sub>", "confirm": "<sub, birebir>"}
```
Yanıt `200`:
```json
{
  "historyDeleted": {"conversations": 2, "turns": 7, "feedback": 1, "activeDays": 3},
  "personalDocumentsDeleted": 1,
  "answersRedacted": 0,
  "kept": {
    "documents": { "...": "özetteki documents nesnesi; personalCount artık 0" },
    "auditEventsAsActor": 42
  }
}
```
- Silinir: konuşmalar, turlar, atıf referansları, puanlar, hesap × gün satırları; kişisel belgeler dosya, sayfa, parça, vektör ve okuyucu satırlarıyla. Başka hesapların bu belgelerden alıntı yapan cevapları belgeler silinmeden önce sabit notla değiştirilir (`answersRedacted`).
- Kalır ve raporlanır: kişinin bilgi alanlarına yüklediği belgeler (yönetici `DELETE /v1/documents/{id}` ile tek tek karar verir), adıyla verilmiş alan erişimleri ve oluşturduğu alanlar (`/v1/collections` ile), denetim satırları (saklama süresi sonunda silinir).
- **İdempotent:** aynı istek tekrarlanabilir. İkinci çağrı yalnız kalanı siler ve sıfır döner. Aynı kişi için eşzamanlı iki istek ikisi de `200` alır; sayılar toplamda bir kez sayılır.
- **Kısmi hata:** `503 SERVICE_UNAVAILABLE` (`Retry-After`) ya da `500`. Silinmiş olan silinmiş kalır; aynı isteği tekrarlayın. Tam başarıda `SUBJECT_DELETED` yazılır (`detailCode` `COMPLETE` ya da `COLLECTION_DOCUMENTS_KEPT`).

### 4. Cevap karartma: `POST /v1/admin/documents/{documentId}/answer-redaction`
İstek: `{"confirm": "<path'teki documentId>"}`. Yanıt `200`: `{"documentId": "…", "answersRedacted": 3}`.
- Yalnız **silinmiş** belge için: belge hâlâ varsa `409` `13021`.
- O belgeyi alıntılayan, **bütün hesaplardaki** cevap metinleri şu notla değiştirilir: "Bu cevap, alıntı yaptığı belge silindiği için yönetici tarafından kaldırıldı." Soru, atıf (`documentDeleted: true`), puan ve sayaçlar kalır.
- İdempotent: tekrarında `answersRedacted: 0`. Her çağrı `ANSWERS_REDACTED` (hedef belge) olarak kaydedilir.

## Hata kodları

| HTTP | Kod | Ad | Ne zaman |
|---|---|---|---|
| 400 | 13001 | `SUBJECT_INVALID` | `sub` token `sub` biçiminde değil |
| 400 | 13002 | `SUBJECT_CONFIRMATION_MISMATCH` | `confirm`, gövdedeki `sub` ile birebir aynı değil |
| 400 | 13020 | `REDACTION_CONFIRMATION_MISMATCH` | `confirm`, path'teki belge kimliği değil (belge kimliği kişisel veri değildir, path'te kalır) |
| 409 | 13021 | `DOCUMENT_NOT_DELETED` | Belge hâlâ var |
| 400 | 9000x | doğrulama | Gövde yok, JSON değil, `sub` ya da `confirm` boş veya 255 karakterden uzun |
| 415 | 9000x | doğrulama | `Content-Type: application/json` değil |
| 401 / 403 | 90100 / 90101 | kimlik / yetki | Token yok / `verso-admin` değil |
| 503 | 99997 | `SERVICE_UNAVAILABLE` | Veritabanı ya da kilit zaman aşımı; aynı isteği tekrarlayın |

## Örnek (curl)
```bash
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
     -d "{\"sub\":\"$SUB\"}" http://localhost:8080/v1/admin/subjects/summary
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" -o basvuru.json \
     -d "{\"sub\":\"$SUB\"}" http://localhost:8080/v1/admin/subjects/export
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
     -d "{\"sub\":\"$SUB\",\"confirm\":\"$SUB\"}" http://localhost:8080/v1/admin/subjects/delete
```
