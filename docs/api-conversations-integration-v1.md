# API istemcisi konuşma geçmişi, geri bildirim ve kullanım metrikleri entegrasyonu [v1]

Her cevap artık sunucuda kullanıcının konuşma geçmişine yazılır: istemci konuşmaları listeler, açar, yeniden adlandırır, siler ve tek tek cevapları 👍/👎 ile puanlar. `verso-admin` rolündeki yönetici, kurulumun kullanım ve kalite sayılarını tek uçtan alır (değer panosu). Geçmiş henüz prompt'a girmez: her soru tek başına cevaplanır (ADR-0023).

> **OpenAPI doğrulanmadı:** uçlar ve alanlar `ConversationController`, `AdminMetricsController`, `QuestionController` ve `qa-api` DTO'larından çıkarıldı (ADR-0004). Generated client paketi: yok.

Bu doküman **24 Ekim 2026** tarihli `feature/doguyaras-gecmis-geri-bildirim` branch'indeki koda (taban `develop` `5d6ac96`) dayanır.

## Değişikliklerin özeti
- `POST /v1/questions`: istekte isteğe bağlı `conversationId`; yanıtta `conversationId` ve `turnId` (geriye uyumlu ek alanlar; `docs/api-questions-integration-v1.md`).
- Yeni uçlar: `GET /v1/conversations`, `GET|PATCH|DELETE /v1/conversations/{conversationId}`, `DELETE /v1/conversations`.
- Yeni uç: `POST /v1/conversations/{conversationId}/turns/{turnId}/feedback`.
- Yeni yönetici ucu: `GET /v1/admin/metrics?from&to` (yalnız `verso-admin`).
- Yeni hata kodları: 11020, 11021, 11022, 11023, 11030.

## Kurallar
- **Kritik:** Kimlik her zaman token'dan gelir. Path'teki kimlik yalnız hedeftir; başka hesabın konuşması ya da cevabı her işlemde `404` döner (varlığı söylenmez). İstemci "yetkin yok" diye ayrı bir ekran göstermez.
- **Kritik:** Soru, cevap, başlık ve dosya adları kişisel veri içerebilir: istemci log'una, analitiğe ve URL'e yazılmaz. Yanıtlar `Cache-Control: no-store, private` taşır; istemci de kalıcı depoya (localStorage) yazmaz.
- Başarı yanıtları ham, hatalar zarflıdır (ADR-0004).
- Bilinmeyen alanlar yok sayılır. Bilinmeyen `outcome` → `found`'a göre; bilinmeyen `reason` → "Diğer"; bilinmeyen `unit` → sayfa.
- Bir konuşma, başladığı andan itibaren saklama süresi kadar yaşar (varsayılan 90 gün, kurulum ayarı `VERSO_HISTORY_RETENTION_DAYS`); sonra bütün turları ve başlığıyla kendiliğinden silinir, devam edilmiş olsa bile. İstemci listede kaybolan bir konuşmayı hata saymaz.

## Değişiklikler ve istemciye etkisi
### 1. Her cevap geçmişe yazılır
`POST /v1/questions` yanıtındaki `conversationId` ve `turnId` cevabın nereye kaydedildiğini söyler. Aynı konuşmaya devam etmek için sonraki soruda `conversationId` gönderilir; gönderilmezse yeni konuşma açılır. **İstemci her zaman yanıttaki `conversationId` ile devam eder:** gönderilen konuşma bu arada silindiyse (başka sekme) ya da saklama süresini doldurduysa sunucu cevabı yeni bir konuşmaya yazar ve yeni kimliği döner. Kayıt sunucuda başarısız olursa cevap yine gelir, iki alan `null` olur: istemci cevabı gösterir, puanlama düğmesini gizler. Eski istemciler yeni alanları yok sayar; her sorusu ayrı bir konuşma olur.

### 2. Geçmiş ekranı
Liste son etkinliğe göre sıralıdır (`updatedAt`); yeniden adlandırma sırayı değiştirmez. Bir konuşma açılınca turlar eskiden yeniye gelir. Atıfların alıntı metni (`excerpt`) geçmişte yoktur: kart dosya adı ve sayfayla gösterilir. Atıf yapılan belge silindiyse `documentDeleted: true`, `fileName: null`: kart "Belge silindi" der, bağlantı vermez.

### 3. Geri bildirim
Her cevap bir kez puanlanır; yeniden puanlamak öncekinin yerine geçer (aynı istek tekrarlanabilir). 👎 için isteğe bağlı neden: `WRONG_SOURCE` (hangi `[n]`), `INCORRECT_OR_MISSING`, `OUTDATED`, `OTHER`. Serbest metin yoktur.

### 4. Yönetici metrikleri
Yalnız `verso-admin` rolünde görünür (panel rolü `/v1/info` `roles`'tan okur; asıl kontrol sunucuda). Sayılar günlük sayaçlardan gelir ve geçmiş silinse de kalır. Cevaplanamayan sorular yalnız kurulum açtıysa (`VERSO_SHOW_UNANSWERED_QUESTIONS`) ve en az 3 farklı kullanıcı aynı soruyu sorduysa görünür. Belge adları da yalnız kurulum açtıysa (`VERSO_SHOW_DOCUMENT_NAMES`) gelir; kapalıyken belgeler kimlik ve sayıyla gösterilir (`documentNamesShown: false`).

## Kullanılan endpoint'ler
| Method | Path | Auth | Idempotency | Sınır | Zarf |
|---|---|---|---|---|---|
| POST | `/v1/questions` | user JWT | yok | gövde ≤ 16 KB, `Content-Length` zorunlu | başarı ham, hata zarflı |
| GET | `/v1/conversations?page&size` | user JWT | – | `size` 1–50 (varsayılan 20), `page` 0–100000 | ham |
| GET | `/v1/conversations/{conversationId}` | user JWT | – | konuşma başına en çok 200 tur | ham |
| PATCH | `/v1/conversations/{conversationId}` | user JWT | doğal (aynı başlık) | gövde ≤ 16 KB | ham |
| DELETE | `/v1/conversations/{conversationId}` | user JWT | ikinci çağrı `404` | – | boş `204` |
| DELETE | `/v1/conversations` | user JWT | doğal (hep `204`) | – | boş `204` |
| POST | `/v1/conversations/{conversationId}/turns/{turnId}/feedback` | user JWT | doğal: tur başına tek puan, yenisi eskisinin yerine | gövde ≤ 16 KB | ham |
| GET | `/v1/admin/metrics?from&to` | user JWT + `verso-admin` | – | aralık ≤ 366 gün | ham |

Rate limit: yok (net kanıt bulunamadı; Verso'da rate limit katmanı yok).

## API sözleşmesi
### Soru sor (ek alanlar)
İstek: `{"question": "Yıllık izin kaç gün?", "conversationId": "0199…"}`; `conversationId` isteğe bağlıdır.

#### Senaryo: cevap kaydedildi
`HTTP 200`, ham: `docs/api-questions-integration-v1.md`'deki gövde + `"conversationId": "0199…", "turnId": "0199…"`.
#### Senaryo: cevap geldi, kayıt yapılamadı
`HTTP 200`, aynı gövde, `"conversationId": null, "turnId": null`.
#### Senaryo: konuşma başkasının ya da silinmiş
`HTTP 404`, zarflı, `"code": 11020`. Model çağrılmaz. İstemci `conversationId`'siz yeniden sorar (yeni konuşma).
#### Senaryo: konuşma dolu (200 tur)
`HTTP 409`, zarflı, `"code": 11022`. İstemci yeni konuşma başlatır.

### Konuşmaları listele
`HTTP 200`:
```json
{ "data": [ { "id": "0199…", "title": "Yıllık izin kaç gün?", "createdAt": "2026-10-24T09:00:00Z",
              "updatedAt": "2026-10-24T09:05:00Z", "turnCount": 2 } ],
  "page": { "number": 0, "size": 20, "totalElements": 1, "totalPages": 1 } }
```

### Konuşmayı aç
`HTTP 200`:
```json
{ "id": "0199…", "title": "Yıllık izin kaç gün?", "createdAt": "…", "updatedAt": "…",
  "turns": [ { "id": "0199…", "question": "Yıllık izin kaç gün?", "answer": "Yirmi iş günüdür [1].",
               "outcome": "ANSWERED", "mode": "local", "model": "gemma4:e2b",
               "citations": [ { "number": 1, "documentId": "0199…", "fileName": "izin.pdf", "page": 3,
                                "unit": "PAGE", "documentDeleted": false } ],
               "feedback": null, "createdAt": "…", "durationMs": 1420 } ] }
```

### Yeniden adlandır
`PATCH`, gövde `{"title": "İzin notlarım"}`: 1–120 karakter, baştaki ve sondaki boşluk atılır; kontrol karakteri ve yön değiştirme işareti (U+202A–202E, U+2066–2069) `400`. Yanıt: liste öğesiyle aynı biçim (`ConversationSummary`).

### Sil
`DELETE /v1/conversations/{id}` → `204`; turlar, atıflar ve puanlar gider. `DELETE /v1/conversations` → `204`; kullanıcının bütün geçmişi ve gün bazlı etkinlik kaydı silinir. Anonim günlük sayılar kalır.

### Puanla
`POST …/feedback`, gövde:

| Alan | Tip | Zorunlu | Kural |
|---|---|---|---|
| `rating` | `UP` \| `DOWN` | evet | |
| `reason` | `WRONG_SOURCE` \| `INCORRECT_OR_MISSING` \| `OUTDATED` \| `OTHER` | hayır | yalnız `DOWN` ile |
| `citationNumber` | number (1–100) | `WRONG_SOURCE` ile evet | cevaptaki bir `[n]` olmalı, yoksa `400` `11023` |

`HTTP 200`: `{"rating":"DOWN","reason":"WRONG_SOURCE","citationNumber":1,"updatedAt":"…"}`. Bilinmeyen alanlar (örn. bir yorum metni) yok sayılır ve saklanmaz.

#### fetch örneği
```js
async function rate(conversationId, turnId, rating, reason, citationNumber) {
  const res = await fetch(`/v1/conversations/${conversationId}/turns/${turnId}/feedback`, {
    method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ rating, reason, citationNumber }) });
  if (res.ok) return res.json();                       // { rating, reason, citationNumber, updatedAt }
  const { error } = await res.json();
  if (error.code === 11021) hideRating(turnId);        // cevap silinmiş ya da süresi dolmuş
  throw error;
}
const reasonLabel = r => ({ WRONG_SOURCE: 'Yanlış kaynak', INCORRECT_OR_MISSING: 'Eksik ya da yanlış',
                            OUTDATED: 'Güncel değil' })[r] ?? 'Diğer';   // bilinmeyen → Diğer
```

### Yönetici metrikleri
`GET /v1/admin/metrics?from=2026-10-01&to=2026-10-24` (ISO tarih, iki uç dahil, kurulumun saat dilimi; varsayılan `Europe/Istanbul`). `HTTP 200`:
```json
{ "from": "2026-10-01", "to": "2026-10-24", "timeZone": "Europe/Istanbul",
  "totals": { "questions": 412, "activeUsers": 37, "activeUsersComplete": true,
              "outcomes": { "answered": 301, "notFound": 74, "uncited": 37 },
              "outcomeRates": { "answered": 0.7306, "notFound": 0.1796, "uncited": 0.0898 },
              "latency": { "p50Ms": 2000, "p95Ms": 5000 }, "modes": { "local": 412, "cloud": 0 },
              "feedback": { "up": 58, "down": 9,
                            "downReasons": { "WRONG_SOURCE": 3, "INCORRECT_OR_MISSING": 2, "OUTDATED": 1, "OTHER": 0 },
                            "downWithoutReason": 3 } },
  "daily": [ { "day": "2026-10-01", "questions": 12, "activeUsers": 5,
               "outcomes": { "answered": 9, "notFound": 2, "uncited": 1 }, "latency": { "p50Ms": 2000, "p95Ms": 3000 },
               "modes": { "local": 12, "cloud": 0 }, "feedbackUp": 2, "feedbackDown": 0 } ],
  "documentNamesShown": true,
  "topCitedDocuments": [ { "documentId": "0199…", "fileName": "izin-yonetmeligi.pdf", "documentDeleted": false, "citations": 120 } ],
  "neverCitedDocuments": { "total": 4, "complete": true, "documents": [ { "documentId": "0199…", "fileName": "eski-el-kitabi.pdf", "createdAt": "…" } ] },
  "unansweredQuestions": { "enabled": false, "minDistinctUsers": 3, "questions": [] } }
```

| Alan | Not |
|---|---|
| `daily` | Aralığın her günü, boş günler sıfırla; grafik doğrudan çizilir |
| `totals.activeUsers` | Aralıkta en az bir soru soran farklı hesap sayısı. `activeUsersComplete: false` ise aralığın bir kısmı saklama süresinden eskidir; o günler için `daily[].activeUsers` kullanılır. Geçmişini tamamen silen kullanıcı aralık sayısından düşer (`activeUsersComplete` yine true olabilir) |
| `outcomeRates.*` | 0–1, dört ondalık; soru yoksa `null` |
| `latency.p50Ms`, `p95Ms` | Sunucudaki cevap süresi; sabit kovanın üst sınırı (250 ms … 180 sn), yaklaşık. Cevap yoksa `null` |
| `feedback` | Cevabın sorulduğu güne sayılır; yeniden puanlama sayıyı taşır |
| `documentNamesShown` | Kurulumun politikası (`VERSO_SHOW_DOCUMENT_NAMES`, varsayılan kapalı). `false` iken aşağıdaki bütün `fileName` alanları `null`; ekran belgeyi "Belge 0199…" gibi kısa kimlikle gösterir |
| `topCitedDocuments` | En çok 10; silinmiş belge `fileName: null`, `documentDeleted: true` |
| `neverCitedDocuments` | Hiç atıf almamış `READY` belgeler; en eski 50 ve toplam. `complete: false` ise kurulum 10 000'den fazla belgeye atıf yapmıştır ve liste atıf almış belgeleri de içerebilir |
| `unansweredQuestions` | `enabled: false` iken her zaman boş. Açıkken "bulunamadı" sorularından, en az `minDistinctUsers` (≥ 3) farklı hesabın sorduğu en çok 20 soru; metin küçük harfe ve tek boşluğa indirilmiş hâlidir |

Başarısız sorular (`503`) saklanmaz ve bu sayılarda yoktur; hesap kimliği ve cevap metni hiçbir alanda dönmez.

## Hata kodları ve ekran davranışı
| code | HTTP | Anlam | Ekran aksiyonu | Retry? |
|---|---|---|---|---|
| 11020 | 404 | Konuşma yok (başkasının, silinmiş ya da süresi dolmuş) | Listeyi yenile; soruysa yeni konuşmada sor | hayır |
| 11021 | 404 | Cevap yok (başka konuşmanın ya da silinmiş) | Puan düğmesini gizle | hayır |
| 11022 | 409 | Konuşma dolu | "Yeni konuşma başlat" | hayır |
| 11023 | 400 | `citationNumber` cevapta yok | istemci hatası; atıf seçimini yenile | hayır |
| 11030 | 400 | Tarih aralığı ters ya da 366 günden uzun | tarih seçiciyi vurgula | hayır |
| 90000 | 400 | Doğrulama: boş/uzun başlık, kontrol karakteri, neden–puan uyuşmazlığı, `size` > 50 | alanı vurgula | hayır |
| 90001 | 400 | Gövde okunamadı ya da bilinmeyen enum değeri | istemci hatası | hayır |
| 90002 / 90003 | 400 | `from`/`to` eksik ya da tarih değil, path kimliği UUID değil | istemci hatası | hayır |
| 11010 / 11011 | 413 / 411 | Gövde 16 KB'tan büyük / `Content-Length` yok | istemci hatası | hayır |
| 90100 | 401 | Oturum yok | yeniden giriş | – |
| 90101 | 403 | `verso-admin` değil (yalnız metrikler) | Pano menüsünü gizle | hayır |
| 99997 | 503 | Veritabanı geçici olarak yok | "Geçici sorun" | evet, `Retry-After` |

## Güvenlik ve log kuralları
- Token yalnız bellekte (ADR-0015); geçmiş içeriği `sessionStorage`/`localStorage`'a yazılmaz, sunucudan okunur.
- Soru, cevap, başlık, dosya adı ve yönetici ekranındaki soru metinleri istemci log'una, hata raporlayıcıya ve analitiğe gitmez; kimlikler (UUID) gidebilir.
- Başlık, cevap ve dosya adı düz metin olarak gösterilir, HTML olarak yorumlanmaz.

## Önceki sürüme göre farklar
- Önceki doküman yok. `POST /v1/questions`'ın ek alanları `docs/api-questions-integration-v1.md`'de de yazılı (geriye uyumlu ek).

## Manuel test akışı
### Uygulama üzerinden
Panel bu dalda değişmedi; geçmiş ve puanlama ekranı ayrı bir işte gelir. Şimdilik API ile denenir.
### API koleksiyonu ile (curl)
1. Token al (README "curl ile").
2. `curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"question":"Yıllık izin kaç gün?"}' http://127.0.0.1:8080/v1/questions` → `conversationId`, `turnId`.
3. `curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/v1/conversations`
4. `curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"rating":"UP"}' http://127.0.0.1:8080/v1/conversations/$CID/turns/$TID/feedback`
5. Yönetici token'ıyla (`verso-admin` rolü, demo realm'de `admin` kullanıcısı): `curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "http://127.0.0.1:8080/v1/admin/metrics?from=2026-10-01&to=2026-10-24"`; `verso-ci` token'ıyla `403`.
6. `curl -s -X DELETE -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/v1/conversations` → `204`.

## Deploy ve uyumluluk
- Var olan bir kurulumda önce `qa` şeması ve rolleri eklenir (README "Yükseltme": `dev-secrets.sh`, `40-modules.sh`), sonra `migrate` ve uygulama. Sıra bozulursa uygulama açılmaz (`svc_qa` yok) ya da `migrate` hata verir; veri kaybı olmaz.
- Eski istemciler etkilenmez: yeni alanlar ek, yeni uçlar kullanılmadıkça görünmez. Sunset yok.

## Açık sorular
- Panelde geçmiş arama (başlık + mesaj, Türkçe harf katlamalı) sunucuda mı yapılsın, yoksa açılan konuşmalarda istemcide mi?
- Takip soruları (geçmişin prompt'a girmesi) ne zaman? Ayrı `verso-llm-review` ister.
- Ortak bilgi alanları gelince dosya adları politika parametresi olmadan gösterilebilir mi (bugün her belge tek hesabındır)?
