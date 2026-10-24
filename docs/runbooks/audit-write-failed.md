# AuditEventNotStored

**Anlamı:** Son 10 dakikada en az bir denetim olayı yazılamadı (ADR-0026). İstekler başarılı döndü (belge yüklendi, soru cevaplandı), ama "kim neyi yaptı" satırı eksik. **Kaybolan satırlar geri gelmez**; bu yüzden alarm `page`'dir ve her dakika kalıcı bir denetim boşluğudur. `verso_audit_events_not_stored_total` üç neden taşır: `failed` (yazma hata verdi), `circuit_open` (bir hatadan sonra 10 sn veritabanı denenmedi; o sürede gelen olaylar da sayılır), `invalid` (aktör yok: kimliksiz bir yoldan olay yayınlandı, kod hatasıdır).

**Kontrol:**

1. Log: `{service="verso-app"} |= "Audit event not stored"`. Satır yalnız eylemi ve istisna tipini taşır (`action=`, `exceptionType=`); kimlik, soru ya da dosya adı yoktur. `exceptionType` neyin bozulduğunu söyler: `CannotGetJdbcConnectionException`/`PSQLException` bağlantı ve kimlik, `BadSqlGrammarException` tablo yok, `DataIntegrityViolationException` bir CHECK kuralı (olay hatalı üretilmiş: kod hatası).
2. Havuz: `hikaricp_connections_timeout_total{pool="verso-audit-db"}` artıyor mu, `hikaricp_connections_active{pool="verso-audit-db"}` ne.
3. Rol ve şema var mı (yükseltilmemiş volume):

   ```bash
   docker compose exec -T -u postgres postgres psql -X -q -d verso -c "select rolname, rolcanlogin from pg_roles where rolname like 'svc_audit%'" -c "select nspname from pg_namespace where nspname = 'audit'" -c '\dt audit.*'
   ```

**Çözümler:**

| Durum | Çözüm |
|---|---|
| Rol ya da şema yok (denetim kaydından önceki bir kurulum) | README "Denetim kaydı olan bir sürüme yükseltme": `40-modules.sh`, sonra `docker compose up -d --build --wait` |
| `svc_audit` parolası yanlış (`PSQLException`, kimlik doğrulama) | `secrets/SECRET_DB_AUDIT_PASSWORD` ile rolün parolasını eşitle (`secrets/README.md` "Rotasyon"), `verso-app`'i yeniden başlat |
| `svc_audit` `NOLOGIN` | `ALTER ROLE svc_audit LOGIN;` (süper kullanıcıyla) |
| Tablo ya da fonksiyon yok (`BadSqlGrammarException`; `\dt audit.*` boş) | `migrate` çalışmamış: `docker compose up -d migrate`, log'unu kontrol et |
| Bağlantı limiti (`too many clients`) | `max_connections` bütçesi: README "Bağlantı bütçesi" (22 / 100); `verso.audit.datasource.maximum-pool-size` küçült ya da instance sayısını gözden geçir |
| Veritabanı genel olarak yok | [database-unavailable.md](database-unavailable.md) |

**Kapanış:**
- `verso_audit_events_not_stored_total` artmıyor ve `verso_audit_events_total` yeniden artıyor.
- Boşluğu belgele: arızanın başlangıç ve bitiş zamanlarını (log'daki ilk ve son `Audit event not stored` satırı) ve kayıp sayısını (`increase(verso_audit_events_not_stored_total[…])`) olay kaydına yaz. Denetçi "boşluk var mı" diye sorarsa, kaydın bu aralıkta eksik olduğunu söyleyebilmek için.
- Kaybolan satırlar yeniden üretilemez. İş verisi kendi şemasındadır (belge, konuşma), ama kimin yaptığı bilgisi buradan geri getirilemez.
