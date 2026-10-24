# OutboxOldestPendingTooOld / OutboxDeadRows

**Anlamı:** `document` ve `qa` modülleri denetim kayıtlarını iş değişikliğiyle **aynı transaction'da** kendi şemalarındaki `outbox_event` tablosuna yazar; relay bunları `audit.event`'e taşır ve satırı siler (ADR-0039).

- `OutboxOldestPendingTooOld` (`outbox_oldest_pending_age_seconds{outbox} > 120`, 5 dk): en eski kayıt relay kirasından (120 sn) uzun süredir bekliyor. **Kayıp yok**: kayıt modülün şemasında duruyor; denetim kaydı geride. Yaş, relay ya da denetim veritabanı yokken de büyümeye devam eder (son görülen satırdan hesaplanır).
- `OutboxDeadRows` (`outbox_dead_rows{outbox} > 0`): bir kayıt `verso.outbox.max-attempts` (20) kez **kalıcı** bir hatayla reddedildi ve `DEAD` oldu. Geçici hatalar (bağlantı, zaman aşımı, kilit) asla `DEAD` olmaz; sonsuza kadar en çok 10 dk aralıkla denenir. `DEAD` satır `verso.outbox.dead-retention` (30 gün) sonra silinir: o zamana kadar müdahale edilmezse denetim izinde kalıcı boşluk olur.

**Kontrol:**

1. Log: `{service="verso-app"} |= "Outbox"`. Satırlar yalnız `outbox=`, `eventId=`, `retry=` ve `exceptionType=` taşır; yük (payload), kimlik ya da içerik yoktur. Her 10 denemede bir `code=OUTBOX_STUCK` (ERROR), vazgeçişte `code=OUTBOX_DEAD`.
2. `exceptionType`:
   - `CannotGetJdbcConnectionException`, `PSQLException` (bağlantı/kimlik), `QueryTimeoutException`: denetim veritabanı ya da `svc_audit` sorunlu → [audit-write-failed.md](audit-write-failed.md) "Çözümler" tablosu. Düzelince relay kendiliğinden yetişir.
   - `DataIntegrityViolationException` (CHECK) ya da `JacksonException`/`MismatchedInputException` (yük okunamıyor): kod hatası; yeni bir `AuditEvent` alanı ya da sürüm uyumsuzluğu. `DEAD` olur.
3. Bekleyen ve ölü satırlar (süper kullanıcıyla; yük yalnız kimlik ve sabit sözcük taşır, yine de dışarı kopyalama):

   ```bash
   docker compose exec -T -u postgres postgres psql -X -q -d verso \
     -c "select 'document' o, status, count(*), min(created_at), max(retry_count), max(last_error_code) from document.outbox_event group by status" \
     -c "select 'qa' o, status, count(*), min(created_at), max(retry_count), max(last_error_code) from qa.outbox_event group by status"
   ```

4. Relay çalışıyor mu: `outbox_relayed_total{outcome="delivered"}` artıyor mu; `verso.outbox.enabled` açık mı (yalnız testlerde kapalıdır).

**Çözümler:**

| Durum | Çözüm |
|---|---|
| Denetim veritabanı / `svc_audit` sorunlu | [audit-write-failed.md](audit-write-failed.md); düzelince yaş düşer, ek iş yok |
| Relay hiç çalışmıyor (yaş artıyor, `delivered` artmıyor, hata logu yok) | `verso-app` loglarında `Outbox poll failed`; uygulamayı yeniden başlat. Başka instance'lar SKIP LOCKED ile aynı satırları alabilir |
| `PUBLISHING` satır uzun süredir duruyor | Sahibi öldü; kira (`locked_until`) bitince bir sonraki poll yeniden alır, elle müdahale gerekmez |
| `DEAD` satır (kod hatası) | Hatayı düzelten sürümü yayınla, sonra satırları yeniden denemeye aç: `update <şema>.outbox_event set status='PENDING', retry_count=0, next_retry_at=now() where status='DEAD';` Aynı kayıt iki kez teslim edilse de `audit.event.event_id` tekil olduğu için tek satır yazılır |
| `DEAD` satır düzeltilemiyor | Kaydı silmeden önce olay kaydına yaz: zaman, `outbox`, `eventId`, `last_error_code` (yükü kopyalama). Satır 30 gün sonra kendiliğinden silinir |

**Kapanış:**
- `outbox_oldest_pending_age_seconds` birkaç saniyeye indi, `outbox_dead_rows` 0.
- `audit.event.occurred_at` denetim kaydının yazıldığı andır (veritabanı saati); kesinti boyunca biriken kayıtların zamanı kesintinin bittiği ana kayar. Kesintinin başlangıç ve bitişini olay kaydına yaz: denetçi "bu aralıktaki kayıtların zamanı neden toplu" diye sorarsa cevap budur.
