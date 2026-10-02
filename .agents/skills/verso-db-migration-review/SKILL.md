---
name: verso-db-migration-review
description: Use this skill when reviewing PostgreSQL schema changes, Flyway migrations, indexes, constraints, partitioning, retention, seed data, module ownership or production migration risk.
---

`db/migration/` değişikliklerini mimari referans Bölüm 10 ve `docs/ai/security-rules.md` Bölüm 7'ye göre incele. Her bulgu için **çalıştırılabilir doğrulama SQL'i** ver. Sorun yoksa: **"Bu kapsamda migration bulgusu yok."** Girdi: diff = `git diff <base>...HEAD` + `git diff --stat` (base = hedef branch; PR diff'i verilmişse o); izlenmeyen (untracked) dosyalar da kapsamdadır. Bulgular yalnız bu PR'ın değiştirdiği/eklediği satırlar içindir; PR öncesinden kalan veya repoda kanıtlanamayan rol/timeout/`ALTER DEFAULT PRIVILEGES` maddeleri bulgu değil, "Doğrulanamayan maddeler" altında listelenir.

İncelenecek dosyalar: `git diff --name-status <base>...HEAD -- '**/db/migration/**'` + bu migration'lara dokunan Java/SQL/config dosyaları.

Önce çalıştır (proje kökünden): `node scripts/flyway-immutability.js check --base origin/<hedef>` — çıktıdaki `OK` / `IHLAL` / `DOGRULANAMADI` satırını kanıt olarak aynen yaz (çıkış kodunu ayrıca yakalama; kod 0/1/3'tür). `IHLAL` = `BLOCKER` ve `git diff <base>...HEAD -- <dosya>` farkı kanıta eklenir; `DOGRULANAMADI` = base'i düzeltip yeniden çalıştır, olmazsa `needs verification`.

Kontrol et:

## Flyway güvenliği
- Base'teki `V*.sql` değişmemiş/silinmemiş/yeniden adlandırılmamış. Düzeltme yeni `V<sonraki>` ile.
- Sürüm sıralı; `out-of-order`, `V9999`, "temp" adlı migration yok.
- `R__*` yalnız idempotent referans verisi/view; iş verisi veya parola içermiyor.
- Seed/test verisi prod location'ında değil (`db/seed-<env>`, yalnız local/test profili).
- Migration **migration rolüyle** (`svc_<x>_migrate`, şema sahibi) koşuyor; uygulama **ayrı rolle** (`svc_<x>`, yalnız DML) çalışıyor; `ALTER DEFAULT PRIVILEGES` uygulama rolüne yeni tablolarda DML veriyor; uygulama rolüne DDL/`OWNER` verilmemiş. `GRANT` başka şemaya erişim açmıyor (varsa `BLOCKER`). Kesin GRANT listesi gözlenen ihtiyaca dayalı ("her ihtimale karşı" yok). Migration rolü için config'te `spring.flyway.user`/datasource kullanıcısına bak; yoksa bulgu değil, "Doğrulanamayan maddeler".
- Uygulama rolünde `statement_timeout`/`lock_timeout`/`idle_in_transaction_session_timeout` role bağlı; migration rolünde `lock_timeout` kısa, `statement_timeout` yok. Yeni yüksek churn tablo (outbox/inbox/log) için tablo bazlı autovacuum ayarı (`autovacuum_vacuum_scale_factor`) veya partition.
- `spring.flyway.baseline-on-migrate: true` config'te kalıcı olarak yok (varsa `HIGH`); mevcut DB'yi Flyway'e alma tek seferlik belgelenmiş `baseline` adımı.

## Modül sahipliği
- Dosyada yalnız kendi şeması; tam nitelikli adlar; başka şema adı geçmiyor. Ayrıca `grep -rn '"<başka_şema>"\.' src/main/java` ile native SQL/JdbcTemplate sorgularında yabancı şema okuması ara; bulunursa ayrı bulgu (`HIGH`) — düzeltme kendi şemada `rm_` read-model (referans Bölüm 4.6).
- Cross-schema FK yok; başka servisin kimliği düz UUID.
- Read-model tablosu tüketicinin kendi şemasında; **kaynak başına** ayrı tablo ve `source_revision`; tek `revision` kolonlu birleşik tablo yok; `rm_consumer_position` var (referans Bölüm 4.6). Inbox tablosu `(handler, event_id)` PK.

## Mevcut veri ve expand/contract
- Yeni `NOT NULL`, unique, FK öncesi mevcut veri kontrolü SQL'i verilmiş (`SELECT count(*) … WHERE … IS NULL`, duplicate sorgusu).
- Büyük tabloda: `NOT VALID` + ayrı `VALIDATE`; `CREATE INDEX CONCURRENTLY` (transaction dışı: aynı ada sahip `V<n>__x.sql.conf` sidecar dosyasında `executeInTransaction=false`); prod satır sayısı bilinmiyorsa `CONCURRENTLY` / `NOT VALID` varsayılan; DDL / backfill / validate ayrı dosyalar.
- Yıkıcı değişiklik expand → backfill → contract; guard `DO $$ … RAISE EXCEPTION`; kolon silme uygulama deploy'undan **sonraki** release'te.
- Tablo rewrite'ı tetikleyen değişiklik (tip değişimi, default'lu NOT NULL eski PG'de) işaretlenmiş; lock süresi tahmini var.

## Tip ve constraint
- Zaman `TIMESTAMPTZ`; enum `TEXT + CHECK`; id UUID (v7; uygulamada üretim veya PG18 `uuidv7()` default, ikisi de kabul — hedef PG sürümünü compose/README'den oku, yoksa `needs verification`); isimli `uq_/ck_/fk_/idx_`.
- Eşzamanlılık kuralı DB'de: unique / partial unique (`WHERE status = 'ACTIVE'`); repository sorgusu aynı predicate'i kullanıyor.
- Soft delete'te unique kural partial index ile.
- `ON DELETE CASCADE` bilinçli; audit, ödeme, yasal kayıt, moderasyon kanıtı cascade ile silinmiyor.
- Append-only tablolar trigger ile korunuyor; audit `REVOKE UPDATE, DELETE`.
- Blob `BYTEA` (+ `STORAGE EXTERNAL` şifreli veri için); base64 `TEXT` yok.

## Index gerekçesi
- Her index gerçek bir sorguya dayanıyor (yorumla yazılmış); duplicate/prefix-redundant index yok.
- Poll edilen tablolarda claim index'i `(status, next_retry_at, locked_until, created_at)`.
- Keyset export index'i deterministik tie-breaker ile `(owner, created_at DESC, id DESC)`.
- Append-only büyük tabloda `created_at` BRIN değerlendirilmiş.

## Büyüme ve retention
- Sürekli büyüyen tablo (outbox, log, audit, olay, mesaj) için retention politikası ve gerekirse partition (pg_partman) tanımlı; `DELETE` ile retention yerine `DROP PARTITION`.
- Tahmini satır/boyut büyümesi ve eşik (referans Bölüm 24 — Ölçek Eşikleri ve Evrim Yolu) yorumda.

## Privacy
- Kişisel veri kolonu: HMAC+pepper/şifreleme kararı; envanter güncel; silme saga'sı kapsamına alınmış.
- Kişisel veri veya gerçek kullanıcı verisi migration'da yok.

## Dinamik parametre migration'ı
- Yeni `system_parameter` satırı: kolon sırası registry/testin beklediği gibi; `data_type`, `criticality`, `usage_status` doğru; enum sabiti api modülüne eklenmiş.

## Entity uyumu
- JPA varsa `ddl-auto: validate` + JPQL testi; JPA yoksa (JdbcTemplate/native SQL) SQL string'lerindeki kolon adlarını migration ile karşılaştır; yeni kolonun uygulamada gerçekten yazıldığını/okunduğunu grep ile doğrula (ölü kolon = `MEDIUM`).

Çıktı:
1. **Risk:** `BLOCKER` / `HIGH` / `MEDIUM` / `LOW` / `OK`.
2. **İncelenen kapsam:** dosya listesi + immutability komut çıktısı.
3. **Bulgular:** `severity · dosya:satır · kanıt · etki (lock, veri kaybı, sahiplik, privacy) · düzeltme` (SQL bloğu gerektiren düzeltme çok satırlı yazılabilir).
4. **Doğrulama SQL'leri:** production'da migration öncesi çalıştırılacak sorgular. **Doğrulanamayan maddeler:** repo dışı rol/timeout/`ALTER DEFAULT PRIVILEGES`/autovacuum için `pg_roles` / `pg_default_acl` sorgusu; checksum için `flyway_schema_history` sorgusu.
5. **Nihai karar:** `APPROVE` / `APPROVE WITH NON-BLOCKING COMMENTS` / `REQUEST CHANGES` / `BLOCK`. Herhangi bir `BLOCKER` → `BLOCK`; `HIGH` → `REQUEST CHANGES`; yalnız `MEDIUM`/`LOW` → `APPROVE WITH NON-BLOCKING COMMENTS`; bulgu yok → `APPROVE`.
