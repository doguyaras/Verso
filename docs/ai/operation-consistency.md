# operation-consistency.md — Servisler Arası Tutarlılık Standardı

> Tek kaynak. `verso-operation-consistency-review` ve `verso-event-design-review` buraya link verir. Referans: mimari doküman Bölüm 11–12.

## 1. Mekanizma seçimi (en basit yeterli olan)

| Gereksinim | Mekanizma |
|---|---|
| Yalnız okuma | Hiçbiri (read-model veya claim; senkron okuma sıcak yolda yasak) |
| Tüm yazımlar tek local TX'te | Local TX + constraint |
| Tek mutation'ı duplicate'ten korumak | Idempotency key `(account_id, scope, operation_key)` / domain uniqueness |
| Commit sonrası başka servisin tepki vermesi | **Domain event** (outbox `kind=EVENT`) + idempotent consumer (inbox) |
| Dış sağlayıcıya iş emri (SMS, push, mail, webhook) | **Komut** (outbox `kind=COMMAND` → queue) veya `kind=HTTP` |
| Local commit + uzak **geri alınabilir** mutation (hak/stok tüketimi) | **Local saga** (tek adım, tek katılımcı) |
| Geri alınamaz etki / global atomiklik / uzun insan onayı | Saga uygun değil → ADR + mimari karar (genelde: süreç tablosu + durum makinesi + insan adımı) |

**Kural:** "Başka servisin verisini değiştir" komutu yoktur; olay yayınlanır, sahibi karar verir.

## 2. Idempotency

- Public tekrar-güvenli işlemler `X-Idempotency-Key` (UUID) alır. Aynı niyetin retry'ları aynı key; yeni niyet yeni key.
- Tekillik `(account_id, scope, operation_key)`. Aynı key farklı body → ilk istek kazanır (fingerprint tutulmaz, belgelenir).
- Tamamlanmış istek aynı sonucu döner; süren `OPERATION_IN_PROGRESS`; iptal edilmiş `OPERATION_CANCELLED`.
- Veri seviyesinde: `INSERT … ON CONFLICT DO NOTHING`, deterministik id (`UUID.nameUUIDFromBytes(kaynak:hedef:faz)`) + unique.

## 3. Outbox (tek generic tablo: `outbox_event`)

- Yazıcı `Propagation.MANDATORY`; domain TX'i olmadan outbox yazılamaz.
- Poller `platform-messaging`'den: CTE + `FOR UPDATE SKIP LOCKED` + `locked_until` lease + `claim_token`; uzak iş TX **dışında**; sonuç yalnız claim sahibi tarafından yazılır.
- Sabitler: batch 50, lease 120 sn, güvenlik payı 30 sn, backoff `min(600, 30·2^n)`, STUCK alarmı her 10 denemede (ERROR + `outbox_oldest_pending_age_seconds` metriği).
- DEAD politikası iş türüne göre: güvenlik yan etkisi (ban, engel) **asla DEAD olmaz**; TTL'li mesaj (OTP) `expires_at` sonrası DEAD; kalıcı 4xx (401/403/408/429 hariç) DEAD.
- Payload'da gereksiz PII/secret yok; iletim sonrası hassas alan NULL.
- **Üretici tarafı sıralama:** sıra gereken `aggregate_id` için claim sorgusu aynı aggregate'in satırlarını tek worker'a `created_at` sırasıyla verir; bir satır başarısız olursa sonrakiler bekletilir. Single-active-consumer tek başına yeterli değildir.
- **Lane izolasyonu:** `kind` (EVENT/COMMAND/HTTP) başına ayrı claim döngüsü ve worker havuzu; `priority` kolonu (güvenlik kararları en yüksek); toplu işler ayrı grup/sınırlı concurrency. Yavaş HTTP hedefi event yayınını bekletemez. Metrikler lane bazında.
- `claim_token` yalnız poller'ın yazma yarışını çözer; uzak hedefe çift teslimi engellemez → hedef idempotent. Publisher confirm ≠ tüketici işledi; "tamamlandı" bilgisi tüketicinin kendi olayıyla gelir.
- Sıra numaralı durum senkronu: karar DB sequence'ından (aggregate başına monoton) sıra alır; alıcı küçük/eşit sırayı yok sayar (`applied=false` ile başarı döner).
- Superseded kontrolü: göndermeden önce daha yeni karar varsa satır gönderilmeden silinir. **Zorunlu güvence:** eski güvenlik kararı yeniden deneme yüzünden yeni kararı asla ezmez (üretici superseded + tüketici `source_revision`).

## 4. Event (CloudEvents) ve tüketici

- `id` (UUIDv7), `source` (servis), `type` (`<servis>.<aggregate>.<olay>`), `subject` (aggregate id), `time`, `dataschema`, `traceparent`.
- Tüketici (**inbox atomikliği, zorunlu güvence**): `INSERT INTO inbox_event(handler, event_id) … ON CONFLICT DO NOTHING` ve iş değişikliği **aynı TX'te**; 0 satır → duplicate, çık; ack yalnız commit'ten sonra (manual ack). Dedup kapsamı **handler**'dır. Dış yan etki inbox TX'i içinde yapılmaz; aynı TX'te outbox satırı olarak yazılır. Bilinmeyen `type` **yok sayılır** (komutlarda DLQ).
- Read-model: **kaynak başına** projeksiyon ve `source_revision` (kaynaklar arası revizyon karşılaştırılmaz); `rm_consumer_position` ile tüketim konumu (tazelik buradan ölçülür, satır yaşından değil); olay sözleşmesi yazılı: **tam durum** (küçük revizyon atlanabilir) mi **değişiklik** (hiç olay atlanamaz; sıra boşluğunda dur + uzlaştır + alarm) mi; karar başına kabul edilen eskilik T ve aşılınca davranış (fail-closed varsayılan); replay deterministik; tombstone.
- Şema evrimi: alan ekleme uyumlu; silme/yeniden adlandırma/tip değişimi → yeni `type`, bir süre çift yayın. Rollout değişiklik türüne göre (referans Bölüm 18.4) + kırıcıysa 4 hücreli uyumluluk matrisi; image rollback veri rollback'i değildir.
- Read-model kaynak değildir; dışa açılmaz; rebuild yolu belgelidir.

## 5. Local saga (tek adım)

- Koordinatör tabloları koordinatörün şemasında: `saga` (`UNIQUE(account_id, scope, operation_key)`), `saga_steps` (`next_action CONFIRM|COMPENSATE`, `lock_token`, `locked_until`).
- Akış: `begin()` ayrı TX'te (`ON CONFLICT DO NOTHING`; yeni kayıt → step `COMPENSATE/PENDING`, `next_attempt_at = now + deadline 15 sn`; varsa replay) → katılımcıya `consume` (TX dışı, aynı `operationKey`, circuit breaker altında) → domain yazımı + `success()` **aynı local TX'te**, ayrı bean (`success` compare-and-set; recovery iptal ettiyse rollback) → recovery worker: `claim` (SKIP LOCKED + `lock_token` + lease 60 sn) → `prepare` (`FOR UPDATE`) → uzak `confirm`/`compensate` → `complete` (token eşleşmesi) → hata/belirsiz sonuçta `GET` ile durum sorgusu; çelişki → `MANUAL_REVIEW`.
- `monitor` (60 sn): 15 dk'dan eski çözülmemiş → ERROR + metrik. `cleanup`: terminal kayıtlar 30 gün sonra; `MANUAL_REVIEW` silinmez.
- Katılımcı: `UNIQUE(caller_service, account_id, operation_key)`; `caller_service` JWT `act`/`iss`'ten; iki katmanlı yetki (allowlist + aktör → izinli işlem tipi); advisory lock + `FOR UPDATE`; tablo: consume/confirm/compensate sonuçları (`APPLIED/REJECTED/CONFIRMED/CANCELLED tombstone/COMPENSATED/MANUAL_REVIEW`); çift iade `original_id` ile engellenir.
- Merkezi coordinator servisi kurulmaz; çok adımlı ihtiyaç doğarsa `LocalSagaStore` genelleştirilir (step adı parametre) — ADR ile.
- Zaman kaynağı DB `now()`.

## 6. Zorunlu doğrulama matrisi

Kanıt seviyeleri: (1) unit + MVC, (2) gerçek PostgreSQL (Testcontainers), (3) owner→participant runtime, (4) release. Sonuç `PASS/FAIL/BLOCKED`. Her `PASS` bir **kanıt kaydı** ister (Bölüm 9).

Senaryolar: normal başarı ve replay · aynı key farklı body · eşzamanlı aynı key · farklı key aynı kaynak · aynı UUID farklı hesap/aktör · intent sonrası çökme · katılımcı commit + yanıt kaybı · consume commit + domain rollback · domain commit + confirm öncesi çökme · geç consume vs tombstone · confirm/compensate timeout · eşzamanlı confirm ve compensate · iki worker + expired lease · request success vs recovery cancel yarışı · tekrarlanan compensate · eksik/bozuk key · geçersiz JWT / yanlış aktör · cleanup ve monitor · migration ve restart · outbox satırı domain TX ile rollback · tüketici duplicate olay · sıra bozuk olay · bilinmeyen tip · inbox satırı + iş aynı TX (handler ortasında exception → satır yok) · commit sonrası ack öncesi çökme → duplicate yutulur · iki poller instance'ı, aynı aggregate'in sıralı iki satırı → sıra korunur · bir lane'de takılı hedef diğer lane'i durdurmuyor · eski güvenlik kararı yeni kararı ezmiyor · publisher confirm alınmış, tüketici işlememiş → "tamamlandı" sayılmıyor · delta olayında sıra boşluğu → dur + alarm · snapshot olayında küçük revizyon yok sayılır.

## 7. Bu Belgede Özellikle Taşınmayanlar

Somut servis adları ve operasyon tipleri (`repo-context.md`'de).

## 8. Net Kanıt Bulunamayan Alanlar

- (ajan ekler)

## 9. Kanıt Kaydı ve Doğrulama Kapsamı (referans Bölüm 19.6)

İki seviye karıştırılmaz: **yapısal** (ArchUnit/enforcer/drift/immutability: kural derlenir ve ihlal yakalanır) ve **davranışsal** (sistem koşarken tekrar teslim çift iş üretmez, iki worker aynı satırı işlemez, restart sonrası iş devralınır). Yapısal `PASS` davranışsal `PASS` değildir.

Her davranışsal `PASS` şu alanlarla kaydedilir: `senaryo · kanıt seviyesi · test/komut · commit SHA · ortam (CI job / Testcontainers sürümü) · sonuç (link) · tarih`. Testi olmayan senaryo `BLOCKED`; "yazılı ama koşulmamış" `PASS` sayılmaz. Review damgası (`review-gate` hook'u) kanıt değildir; zorunlu güvence CI'dır (test sayısı dahil: 0 test = başarısız).

Başlangıç noktası: referans Ek B `skeleton-example/platform-messaging` (`docs/architecture-reference.md`) — `OutboxBehaviourIT` ve `SagaBehaviourIT` matrisin 1–32 satırlarının seviye 2 (gerçek PostgreSQL) halini içerir; projeye kopyalanıp katılımcı gerçek HTTP client'ıyla (seviye 3) genişletilir.
