# Implementation — 12 Adım (Local Saga + Outbox)

Her adım için "nerede" (dosya/paket) ve "kanıt" (satır) yazılır.

1. **Idempotency key sözleşmesi** — Controller `@RequestHeader("X-Idempotency-Key") UUID`; scope sabiti (`MATCH_SWIPE` gibi UPPER_SNAKE); tekillik `(account_id, scope, operation_key)`; replay / `OPERATION_IN_PROGRESS` / `OPERATION_CANCELLED` yanıtları tanımlı.
2. **Koordinatör tabloları** — `saga`, `saga_steps` koordinatörün şemasında; unique, CHECK, claim/expired-lease/retention index'leri; migration `verso-db-migration-review`'dan geçmiş.
3. **`begin()`** — ayrı TX (`REQUIRES_NEW` veya `TransactionTemplate`), `INSERT … ON CONFLICT DO NOTHING`; yeni kayıtta step `COMPENSATE/PENDING`, `next_attempt_at = now + deadline`; mevcutsa önceki sonucu döndür.
4. **Guard'lar** — rate limit, read-model/claim kontrolleri **TX dışında**, saga `begin()`'den sonra ve consume'dan önce; fail-closed.
5. **Consume** — katılımcı client'ı TX dışında, aynı `operationKey`, timeout + circuit breaker; `REJECTED` → domain yazılmaz, saga `CANCEL_REQUESTED`.
6. **Domain yazımı + `success()`** — `*TransactionService` içinde tek TX: lock → domain → outbox satırları (event) → `sagaStore.success()` compare-and-set (`STARTED→SUCCEEDED`, step `CONFIRM`); CAS başarısızsa exception → rollback.
7. **Katılımcı uçları** — `consume/get/confirm/compensate` `/internal/<kaynak>/operations/{operationKey}/…`; `UNIQUE(caller_service, account_id, operation_key)`; `caller_service` JWT `act`/`iss`'ten; allowlist + kod içi aktör→işlem tipi; advisory lock + `FOR UPDATE`; tombstone; iade kuralları; `original_id`.
8. **Recovery worker** — claim (SKIP LOCKED + `lock_token` + lease 60 sn), prepare (`FOR UPDATE`, sahiplik + durum uzlaştırma), uzak confirm/compensate, complete (token eşleşmesi); hata → retry backoff `min(300, 2^n)`; belirsiz → `GET`; çelişki → `MANUAL_REVIEW`.
9. **Monitor + cleanup** — monitor 60 sn: 15 dk'dan eski çözülmemiş → ERROR + `saga_unresolved_total`; cleanup cron: terminal kayıtlar 30 gün; `MANUAL_REVIEW` silinmez; admin görünürlüğü (panel listesi).
10. **Outbox/event** — domain event `outbox_event` (`kind=EVENT`) ile; handler idempotent; tüketici inbox; `traceparent` taşınıyor.
11. **Config + gözlem** — `operation-consistency.{deadline-ms,lease-ms,poll-ms,monitor-ms,cleanup-cron}` local + deploy; metrikler ve alarm kuralı; loglar `sagaId`/`operationKey` ile, hesap kimliği yok.
12. **Testler** — `references/verification.md` matrisi; gerçek PG (Testcontainers) ile paralel claim ve expired lease; MVC binding; log privacy.
