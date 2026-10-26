# API istemcisi onaylı bilgi entegrasyonu (cevap belleği ve altın set) [v1]

Verso'da `verso-admin` rolündeki yönetici, kendi sorduğu ve kaynaklı cevaplanan bir soruyu **onaylı bilgi kartına** çevirir. Aynı soru yeniden sorulduğunda, kartın alıntıladığı **bütün** belgeleri okuyabilen kişiye model yerine kartın cevabı "uzman onaylı" olarak gösterilir. Kaynak belge silinir, değişir ya da taşınırsa, onay süresi dolarsa ya da birkaç kullanıcı 👎 verirse kart kendiliğinden kullanımdan çıkar. Onaylanan her kart müşteriye özel altın sete girer ve her gece aramanın onu hâlâ bulup bulmadığı ölçülür.

> **OpenAPI doğrulanmadı:** uçlar ve alanlar `KnowledgeCardController`, `KnowledgeCardServiceImpl`, `KnowledgeEval` ve `qa-api` DTO'larından çıkarıldı (ADR-0004). Generated client paketi: yok.

Bu doküman **26 Ekim 2026** tarihli `feature/doguyaras-ogrenen-katman-1` branch'indeki koda (taban `develop` `0ed1ede`) dayanır. Kararlar: [ADR-0048](adr/0048-ogrenen-bilgi-katmani.md) (mimari), [ADR-0049](adr/0049-onayli-bilgi-karti-ve-altin-set.md) (faz 1).

## Değişikliklerin özeti

- Yeni yönetici uçları: `/v1/admin/knowledge-cards` (oluşturma, liste, okuma, onay, arşiv) ve `/v1/admin/knowledge-eval/runs` (liste, şimdi çalıştır).
- `POST /v1/questions` yanıtına ve akışın `done` olayına `memory` alanı; akışa `MEMORY_FOUND` aşaması; `model` için yeni değer `verified-answer` (ekleyici; ayrıntı: [api-questions-integration-v1.md](api-questions-integration-v1.md)).
- Yeni hata kodları: 11040–11048 (`qa` bloğu).
- Yeni denetim eylemleri: `KNOWLEDGE_CARD_CREATED`, `KNOWLEDGE_CARD_VERIFIED`, `KNOWLEDGE_CARD_STALE`, `KNOWLEDGE_CARD_DISPUTED`, `KNOWLEDGE_CARD_ARCHIVED`, `KNOWLEDGE_EVAL_RUN`; yeni hedef türleri `KNOWLEDGE_CARD`, `KNOWLEDGE_EVAL`. Sistem geçişlerinin aktörü `system:knowledge-memory`'dir.
- Mobil için etki yok (yönetici ekranı yalnız panelde). Mobil istemci `memory` alanını görmezden gelebilir; gösterirse "Uzman onaylı" rozeti önerilir.

## Kimlik ve yetki

Bütün uçlar `Authorization: Bearer <access token>` ister ve yalnız `verso-admin` içindir: başka herkes `403 90101`, token'sız istek `401 90100`. Yönetici kimliği token'dan okunur; istekte hesap kimliği yoktur. Yanıtlar `Cache-Control: no-store, private`.

## Uçlar

### `POST /v1/admin/knowledge-cards`

Yöneticinin **kendi** konuşmasındaki bir turdan kart oluşturur.

| Alan | Tip | Zorunlu | Not |
|---|---|---|---|
| `conversationId` | uuid | evet | |
| `turnId` | uuid | evet | |
| `question` | string (1–1000) | hayır | Eşleşmede kullanılacak kanonik soru; yoksa turun sorusu |
| `keyFacts` | string[] (≤ 10, her biri ≤ 200) | hayır | Altın setin ana bilgileri |
| `verify` | boolean | hayır (`false`) | `true`: yöneticinin onayı hemen kaydedilir (panelin "Onayla" düğmesi) |

Cevap metni ve atıflar turdan alınır, istekle değiştirilemez. Yanıt `201` ve kart (aşağıda). İki kişi kuralı açıksa `verify: true` kartı `DRAFT` bırakır (ikinci yöneticinin onayı gerekir).

### `GET /v1/admin/knowledge-cards?state=&page=&size=`

`state`: `DRAFT`, `VERIFIED`, `STALE`, `DISPUTED`, `ARCHIVED` ya da yok (hepsi). `page` ≥ 0, `size` 1–100 (20). Son değişiklik önce. Yanıt `{data: [...], page: {number, size, totalElements, totalPages}}`; her öğe:

| Alan | Tip | Not |
|---|---|---|
| `id` | uuid | |
| `question` | string | Kanonik soru; düz metin olarak gösterin |
| `state` | enum | yukarıdaki beş değer |
| `staleReason` | enum \| null | `SOURCE_DELETED`, `SOURCE_REPLACED`, `SOURCE_MOVED`, `SOURCE_CHANGED`, `EXPIRED`; yalnız `STALE`'de |
| `verifiers` / `requiredVerifiers` | number | geçerli döngüdeki onay sayısı / gereken (1 ya da 2) |
| `disputes` | number | geçerli döngüde 👎 veren farklı kullanıcı sayısı |
| `verifiedAt`, `expiresAt`, `createdAt`, `updatedAt` | ISO-8601 \| null | |

### `GET /v1/admin/knowledge-cards/{id}`

Listedeki alanlara ek olarak: `answer` (onaylı metin, `[n]` işaretleriyle), `keyFacts`, `citations` (geçmiş atıfıyla aynı biçim: `number`, `documentId`, `fileName`, `page`, `unit`, `documentDeleted`, `documentAccessible`, `collectionId`, `collectionName`; yöneticinin o anki erişimiyle çözülür), `createdByYou`, `verifiedByYou`, `disputesByDay` (`[{day, disputes}]`, kullanıcı yok), `events` (`[{fromState, toState, reason, at}]`; `reason`: `CREATED`, `VERIFICATION_ADDED`, `VERIFIED`, `NEW_CYCLE`, `DISPUTE_THRESHOLD`, `ARCHIVED` ya da bir `staleReason`). Hiçbir alan bir kullanıcının ya da yöneticinin kimliğini taşımaz (kim yaptı: denetim kaydı).

### `POST /v1/admin/knowledge-cards/{id}/verify`

Gövde yok. `DRAFT` kartta onayı kaydeder; `DISPUTED` ya da süresi dolmuş (`STALE` + `EXPIRED`) kartta yeni bir onay döngüsü açar. Onaylayan bütün kaynakları okuyabilmeli ve kaynak metin değişmemiş olmalı. Yanıt `200` ve kart.

### `POST /v1/admin/knowledge-cards/{id}/archive`

Gövde yok. Kart artık hiçbir soruya gösterilmez ve altın setten çıkar; 365 gün sonra silinir. Yanıt `200` ve kart.

### `GET /v1/admin/knowledge-eval/runs?page=&size=`

Altın set kontrolleri, yeniden eskiye. Her koşu: `id`, `trigger` (`SCHEDULED` gece, `MANUAL`), `status` (`RUNNING`, `SUCCEEDED`, `FAILED`), `startedAt`, `finishedAt`, `cases`, `found`, `missed`, `sourceLost`, `skipped`, `topK`, `recallAtK` (0–1 \| null), `baselineRecall` (önceki başarılı koşu), `sourceDrift` (0–1 \| null), `alert` (boolean).

### `POST /v1/admin/knowledge-eval/runs`

Kontrolü hemen çalıştırır (model çağrılmaz; en çok 1 000 vaka, eşzamanlı). Yanıt `201` ve koşu. Başka bir koşu sürüyorsa `409 11048`.

## Hata kodları

| Kod | HTTP | Anlam | İstemci davranışı | Yeniden dene |
|---|---|---|---|---|
| 11021 | 404 | Tur yok ya da yöneticinin değil | "Bu cevap artık geçmişte yok" | hayır |
| 11040 | 404 | Kart yok | listeyi yenile | hayır |
| 11041 | 409 | Bir kaynak yok ya da yöneticiye kapalı | kaynak listesini göster | hayır |
| 11042 | 409 | Cevap kaynaklı değil (`ANSWERED` + atıf değil ya da karartılmış) | düğmeyi gösterme | hayır |
| 11043 | 409 | Durum işleme izin vermiyor (zaten onaylı, arşivde, kaynağı değişmiş) | kartı yeniden oku | hayır |
| 11044 | 409 | Kaynak metin değişmiş | "yeni cevaptan kart oluşturun" | hayır |
| 11045 | 409 | Bu yönetici bu döngüde zaten onayladı (iki kişi kuralı) | ikinci yöneticiyi bekle | hayır |
| 11046 | 409 | Kişisel belgeye dayanıyor | belgeyi bir bilgi alanına taşı | hayır |
| 11047 | 409 | Aynı soru için canlı kart var | mevcut kartı göster | hayır |
| 11048 | 409 | Kontrol zaten çalışıyor | biraz sonra | evet |
| 90101 | 403 | `verso-admin` değil | yönetici ekranını gösterme | hayır |

## Gizlilik

- Kartın soru ve cevap metni yöneticinin kendi turundandır; başka kullanıcının sorusu karta çevrilemez.
- İtiraz eden kullanıcı saklanmaz (kart başına takma ad); uç yalnız gün başına sayıyı verir.
- Metinler hiçbir log satırına ve denetim kaydına yazılmaz; denetimde yalnız kimlikler ve sabit sözcükler vardır.

## Net kanıt bulunamayan alanlar

- OpenAPI farkı üretilmedi (repo'da OpenAPI üretimi yok, ADR-0004).
