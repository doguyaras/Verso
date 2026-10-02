# Verification — Yapısal Kontroller ve Senaryo Matrisi

## 1. Yapısal kontroller (statik, kod okuyarak)

- [ ] `begin()` ayrı TX'te; consume TX dışında; domain + `success()` aynı TX'te ayrı bean.
- [ ] `success()` compare-and-set; başarısızlıkta exception ve rollback.
- [ ] Recovery `complete` yalnız `lock_token` eşleşince yazıyor.
- [ ] Katılımcıda advisory lock + `FOR UPDATE`; tombstone; `UNIQUE(caller_service, account_id, operation_key)`.
- [ ] Outbox yazıcı MANDATORY; handler idempotent; inbox dedup.
- [ ] `MANUAL_REVIEW` cleanup'ta silinmiyor; monitor metriği var.
- [ ] Loglarda hesap kimliği yok; `sagaId`/`operationKey` var.
- [ ] Config key'leri local + deploy.

## 2. Senaryo matrisi

| # | Senaryo | Beklenen | Kanıt seviyesi | Test · commit · ortam · tarih | Sonuç (link) |
|---|---|---|---|---|---|
| 1 | Normal başarı | saga CONFIRMED, hak tüketildi, domain yazıldı | 2 | | |
| 2 | Aynı key ile replay | aynı sonuç, ikinci consume yok | 2 | | |
| 3 | Aynı key farklı body | ilk istek kazanır | 1 | | |
| 4 | Eşzamanlı aynı key | tek saga, tek consume | 2 | | |
| 5 | Farklı key aynı kaynak | domain uniqueness reddeder, saga compensate | 2 | | |
| 6 | Aynı UUID farklı hesap/aktör | ayrı saga; yetki reddi | 1 | | |
| 7 | begin sonrası, consume öncesi çökme | deadline → otomatik compensate (no-op tombstone) | 2 | | |
| 8 | Katılımcı commit + yanıt kaybı | GET ile durum; consume replay aynı sonucu döner | 3 | | |
| 9 | Consume commit + domain rollback | recovery compensate → iade | 2 | | |
| 10 | Domain commit + confirm öncesi çökme | recovery confirm | 2 | | |
| 11 | Geç gelen consume, tombstone var | consume uygulanmaz (`CANCELLED`) | 2 | | |
| 12 | confirm/compensate timeout | retry backoff; belirsizlikte GET | 1 | | |
| 13 | Eşzamanlı confirm ve compensate | tek sonuç; çelişki `MANUAL_REVIEW` | 2 | | |
| 14 | İki worker + expired lease | eski worker complete edemez | 2 | | |
| 15 | Request success vs recovery cancel yarışı | CAS kaybeden rollback | 2 | | |
| 16 | Tekrarlanan compensate | idempotent, çift iade yok | 2 | | |
| 17 | Eksik/bozuk key | 400, servise ulaşmaz | 1 | | |
| 18 | Geçersiz JWT / yanlış aktör | 401/403, saga yok | 1 | | |
| 19 | Cleanup ve monitor | terminal silinir, MANUAL_REVIEW kalır, metrik artar | 2 | | |
| 20 | Migration + restart | tablo/index uyumlu, in-flight saga kurtarılır | 2/4 | | |
| 21 | Outbox satırı domain TX ile rollback | satır yok | 2 | | |
| 22 | Tüketici duplicate olay | tek etki | 2 | | |
| 23 | Sıra bozuk olay (eski revision) | yok sayılır | 2 | | |
| 24 | Bilinmeyen event type | ack + log, DLQ değil | 1 | | |
| 25 | Handler ortasında exception (inbox atomikliği) | inbox satırı yok; yeniden teslimde iş yapılır | 2 | | |
| 26 | Commit sonrası, ack öncesi çökme | yeniden teslim duplicate olarak yutulur, tek etki | 3 | | |
| 27 | İki poller instance + aynı aggregate'in sıralı iki satırı | tek worker, sıra korunur; ilk satır başarısızsa ikincisi beklet | 2 | | |
| 28 | Bir lane'de takılı hedef (HTTP 30 sn timeout) | EVENT lane'i gecikmeden yayınlıyor | 2 | | |
| 29 | Eski güvenlik kararı yeniden denemede | yeni kararı ezmiyor (superseded + `source_revision`) | 2 | | |
| 30 | Publisher confirm var, tüketici işlemedi | üretici "tamamlandı" saymıyor; tamamlanma tüketici olayıyla | 3 | | |
| 31 | Delta olayında sıra boşluğu | uygulama durur, `readmodel_gap_total` artar, uzlaştırma | 2 | | |
| 32 | Süreç öldürme: PUBLISHING satır + kira dolumu | ikinci instance devralır; ilk instance geri gelince yazamaz | 3 | | |

Kanıt seviyeleri: 1 unit/MVC · 2 gerçek PostgreSQL (Testcontainers veya gömülü PG) · 3 owner→participant runtime (iki servis ayakta) · 4 release/staging.

Seviye 2 referans uygulaması: `skeleton-example/platform-messaging` (`SagaBehaviourIT` satır 1–20, `OutboxBehaviourIT` satır 21–32); senaryo → test adı eşlemesi test metotlarının `// #n` yorumlarında.

## 3. Sonuç

- Tüm satırlar `PASS` → **PASS**.
- Herhangi bir satır `FAIL` → **FAIL** (liste).
- Test/kanıt olmayan satır → **BLOCKED** (liste; "test yok" = geçmiş sayılmaz).
- `PASS` yazılan her satırda commit SHA ve sonuç linki dolu; boşsa `BLOCKED`. Yapısal testler (ArchUnit) bu tablo için kanıt değildir.
