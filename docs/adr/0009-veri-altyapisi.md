# ADR-0009: Veri altyapısı: compose, roller, secret'lar, yedekleme ve image

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-02
- **Karar verenler:** doguyaras
- **İlgili eşik (referans Bölüm 24):**
  - Yedek/restore süresinin RTO'yu aşması.
  - RPO ihtiyacının yedek aralığının altına inmesi (gerçek müşteri verisi, sözleşmeli SLA).
  - Bağlantı sayısının `max_connections × 0,7`'yi geçmesi.

## Bağlam

Faz 2, ADR-0002'nin (PostgreSQL 18 + pgvector) çalışan altyapısını kurar. Ürün gereksinimi: `docker compose up` ile her şey ayağa kalkar, hardcoded secret yoktur, ayarlar ortam değişkenleri ve `.env.example` ile verilir.

Referansın bu fazla ilgili kuralları:

- **10.1:** Modül başına şema ve iki rol: migration rolü şema sahibidir ve DDL yapar, uygulama rolü yalnız DML yapar. Sınır GRANT ile korunur; rol bazlı zaman aşımları vardır.
- **10.2:** Flyway; `baseline-on-migrate` kapalı; `V1` şema yaratmaz.
- **10.5:** Yedek ve otomatik restore provası ilk sprint işidir.
- **15.3:** Compose `secrets:` ve Spring config tree kullanılır; secret `.env`'e konmaz.
- **16:** Gerçek DB testleri Testcontainers ile her PR'da koşar.
- **18.1 / 18.2:** Non-root image; digest ile pinlenmiş image'lar; healthcheck; servis portları dışarı açılmaz.

## Seçenekler

Kullanıcıya sorulan iki karar (2026-10-02):

| Konu | Seçenek | Artı | Eksi |
|---|---|---|---|
| Yedek | **A. pg_dump + gpg + otomatik restore provası** | Yeni bileşen yok; mantıksal yedek sürüm atlamalarında taşınabilir; prova tek komut | RPO = yedek aralığı (varsayılan 24 saat); PITR yok |
| Yedek | B. WAL-G + MinIO (referansın birincil önerisi) | Sürekli WAL arşivi, PITR, RPO dakikalar | İki yeni bileşen (S3 uyumlu depo ve WAL-G); tek host'ta işletim yükü |
| Image | **A. Çok aşamalı Dockerfile** (referansın ikinci seçeneği) | Yeni build bağımlılığı yok; temiz klonda `docker compose up --build` çalışır | Daemon gerektirir; katman tekrarlanabilirliği Jib kadar sıkı değil |
| Image | B. Jib (referansın birinci tercihi) | Daemon'suz, tekrarlanabilir katmanlar | Yeni Maven plugin'i; compose için önce ayrı build adımı |

## Karar

İki konuda da **A** seçildi.

**Compose (`compose.yaml`, depo kökü)**

- **Servisler:** `postgres` (pgvector 0.8.7, PostgreSQL 18), `verso-app` ve `backup`. Restore provası için `drill` profilinde `restore-db`, `restore-runner` ve `restore-flyway` servisleri var.
- **Image'lar:** hepsi digest ile pinli; `ImageVersionsTest` bunu ve Testcontainers ile compose'un aynı Postgres image'ını kullandığını doğrular.
- **Erişim:** API yalnız `127.0.0.1`'e açılır. PostgreSQL ve actuator portu compose ağının dışına çıkmaz. Yerel IDE çalıştırması için Postgres'i `127.0.0.1:5432`'ye açan ayrı bir katman var: `deploy/compose.local.yaml`.
- **Sertleştirme:** uygulama, backup ve prova container'ları salt-okunur dosya sistemiyle, `cap_drop: ALL` ve `no-new-privileges` ile çalışır. Uygulama UID 10001 kullanıcısıyla koşar.
- **Java servisleri:** ortak ayarlar YAML anchor'larıyla verilir (`restart: always`, `stop_grace_period`, bellek ve CPU limitleri, `pids_limit`, `nofile` 65536). G1 açıkça seçilir.

**Secret'lar (referans 15.3, seviye 1)**

- `secrets/` altındaki dosyalar compose `secrets:` ile `/run/secrets`'a bağlanır. Spring config tree her dosyayı aynı adlı property yapar (`SECRET_DB_DOCUMENT_PASSWORD` gibi). Bu adlar `config/verso.yml`'de fallback'siz okunur.
- **Yerelde:** `scripts/dev-secrets.sh` eksik dosyaları rastgele üretir; var olanlara dokunmaz, hiçbir değeri yazdırmaz. **Üretimde:** deploy işi aynı adlı dosyaları CI secret'larından yazar.
- `.env.example` yalnız gizli olmayan ayarları taşır: port, image tag'i, profil, limitler, yedek aralığı.
- Ürün isteğindeki "`.env.example` ile ortam değişkenleri" böylece karşılanır. Referansın "secret `.env`'e konmaz" kuralı da korunur (aşağıda ADR-0007 #40).

**PostgreSQL init (`deploy/postgres/initdb`, boş volume'de bir kez)**

- `05-settings.sh`: `pg_stat_statements` yüklenir. `log_parameter_max_length` ve `log_parameter_max_length_on_error` 0'a çekilir: bind parametreleri belge metni veya soru taşıyabilir (llm-rules 2.1).
- `10-roles.sh`:
  - Roller: `svc_document_migrate`, `svc_document` ve `verso_backup` (`pg_read_all_data`).
  - Zaman aşımları: `svc_document` için `statement_timeout` 10 sn, `lock_timeout` 3 sn, `idle_in_transaction_session_timeout` 60 sn; migration rolü için `lock_timeout` 10 sn.
  - `search_path` = `<şema>, extensions`.
  - Veritabanı düzeyi yetkiler: `PUBLIC`'in bağlantı hakkı alınır, yalnız adı geçen roller bağlanır.
  - Bu script restore hedefinde de çalışır, çünkü `pg_dump` veritabanı ACL'lerini taşımaz.
  - Parolalar psql `\set` ve backtick ile dosyadan okunur; process argümanına düşmez. Oturum `log_min_error_statement = panic` ile açılır; aksi halde hata anında parola sunucu loguna yazılabilirdi.
- `20-database.sh`:
  - `public` şeması herkese kapatılır.
  - Extension'lar (`vector`, `pg_stat_statements`) superuser'a ait ayrı bir `extensions` şemasında durur.
  - `document` şemasının sahibi `svc_document_migrate`'tir. `ALTER DEFAULT PRIVILEGES` ile migration rolünün açacağı tablolarda uygulama rolüne DML verilir.
- **`afterMigrate.sql`:** default privilege'ın `flyway_schema_history`'ye verdiği DML'i her migrate sonunda geri alır.

**Flyway ve datasource**

- Flyway ayrı bağlantıyla migration rolünü kullanır. Ayarlar: `locations: classpath:db/migration/document` (modül başına klasör), `create-schemas: false`, `clean-disabled: true`, `baseline-on-migrate: false`.
- Hikari: havuz 10, `keepalive-time` 2 dk, `connection-fetch: lazy`.
- Migration'lar `services/document/document-core`'da durur. `MigrationConventionsTest` başka şema adını, `CREATE SCHEMA`/`EXTENSION`'ı, `IF NOT EXISTS`'i, migration içinde yetki değişikliğini ve sıra atlamayı reddeder; kural, kasıtlı ihlal içeren fixture'larla da denenir.

**Yedek (`deploy/backup/backup.sh`, `backup` servisi)**

- `pg_dump -Fc`, `verso_backup` rolüyle (salt okunur) çalışır. Çıktı gpg ile simetrik AES256 şifrelenir; şifreli dosyanın SHA-256'sı ayrıca yazılır.
- Tablo başına satır sayısı manifest'i, `pg_dump` ile **aynı snapshot**'ta alınır (`pg_export_snapshot()` + `--snapshot`). Böylece prova, eşzamanlı yazmalar yüzünden yanlış alarm vermez.
- Aralık `BACKUP_INTERVAL_SECONDS` (varsayılan 86400 = RPO 24 saat), saklama `BACKUP_RETENTION_DAYS` (varsayılan 7). En yeni yedek, eski de olsa silinmez.
- Log'a yalnız dosya adı, boyut ve süre yazılır.

**Restore provası (`scripts/restore-drill.sh`)**

1. Yalnız rollerle kurulmuş geçici bir Postgres açılır.
2. Yedeğin checksum'ı doğrulanır, şifresi çözülür ve geri yüklenir; satır sayıları manifest ile karşılaştırılır.
3. Uygulama rolünün okuyabildiği ama DDL yapamadığı denenir.
4. Flyway CLI (12.4.0, uygulamadaki kütüphaneyle aynı sürüm) bu checkout'un migration'larına karşı `validate` çalıştırır.
5. Geçici veritabanı her durumda silinir.

`scripts/restore-drill-selftest.sh`, provanın gerçek veride geçtiğini ve değiştirilmiş manifest, bozulmuş dosya ve eksik checksum durumlarının her birinde kırmızı verdiğini gösterir. CI bunu haftalık çalıştırır, ayrıca ilgili dosyalara dokunan her PR'da (`.github/workflows/restore-drill.yml`).

**Image (`Dockerfile`)**

- Build aşaması: JDK 25 image'ı ve Maven Wrapper; testler CI'da koştuğu için atlanır. `unzip` kurulur; aksi halde wrapper sessizce `.tar.gz` dağıtımını indirir ve pinlenmiş zip SHA'sı tutmaz (bu fazda yakalandı).
- Son aşama: layered jar, JRE 25, UID 10001.
- Healthcheck: image'da wget/curl olmadığı için bash `/dev/tcp` ile readiness ucunu sorar.
- JVM bayrakları: `-XX:+UseG1GC -XX:MaxRAMPercentage=75 -XX:+UseCompactObjectHeaders -XX:+ExitOnOutOfMemoryError`.
- AOT cache (training run) sonraya bırakıldı.

**Testler (referans 16)**

- Context testleri `@WithVersoPostgres` ile gerçek Postgres'e karşı koşar: aynı image ve aynı init script'leri, her koşuda rastgele parolalar.
- `@ServiceConnection` bilerek kullanılmadı: uygulamayı superuser olarak bağlar ve eksik GRANT'ları gizlerdi.
- `@ImportTestcontainers` + `@DynamicPropertySource` de kullanılmadı: bu değerler auto-configuration koşullarından sonra geliyor ve Flyway koşulu `DB_HOST`'u çözemiyordu.
- `DatabaseRolesTest` yetki sınırını uygulamanın gerçek rolleriyle kanıtlar. "Yetki yok" iddiaları `information_schema`/`aclexplode` üzerinden doğrulanır.

**Reddedilenler:** WAL-G + MinIO ve Jib, yukarıdaki tabloda yazan nedenlerle.

## Sonuçlar

- **Olumlu:**
  - Temiz bir klonda iki komut yeter: `bash scripts/dev-secrets.sh && docker compose up -d --build`.
  - Yetki sınırı ve yedekten geri dönüş makineyle kanıtlanıyor; CI bunu haftalık tekrarlıyor.
  - Test, compose ve prova aynı init script'lerini kullanıyor; tek kaynak var.
- **Olumsuz / kabul edilen risk:**
  - **RPO 24 saat (varsayılan).** Gerçek müşteri verisinde aralık kısaltılır ya da WAL-G'ye geçilir.
  - **Yedek aynı host'ta, `backups` volume'ünde.** Host kaybında yedek de gider. Volume'ün host dışına kopyalanması (rsync/rclone) üretim kurulumunun işidir; faz 7'deki alarm ve runbook'la birlikte yazılır.
  - **Init script'leri yalnız ilk kurulumda çalışır.** Rol eklemek ya da parola değiştirmek elle yapılan bir prosedürdür (`secrets/README.md`, rotasyon).
  - **HA yok** (referans 10.5: tek host'ta HA olmaz).
- **Bağlantı bütçesi (referans 10.5):** uygulama havuzu 10 + açılışta Flyway 1 + yedek sırasında 2 (snapshot oturumu ve `pg_dump`) = 13. PostgreSQL `max_connections` varsayılanı 100. Tek instance ve PgBouncer'sız kurulum bu fazda yeterlidir.
- **RTO hedefi:** 1 saat. Ölçülen prova süresi yüzlerce satırda saniyeler mertebesinde; büyük veride faz 8'de yeniden ölçülecek.
- **Etkilenen dosyalar:**
  - `compose.yaml`, `Dockerfile`, `.dockerignore`, `.env.example`
  - `deploy/postgres/initdb/*`, `deploy/backup/*`, `deploy/compose.local.yaml`, `secrets/README.md`
  - `scripts/dev-secrets.sh`, `scripts/restore-drill*.sh`, `.github/workflows/restore-drill.yml`
  - `services/document/document-core`, `verso-app` (pom, config, testler)
- **Geri alma yolu:**
  - WAL-G'ye geçiş `backup` servisinin değişmesidir; şema ve rol modeli aynı kalır.
  - Jib'e geçiş Dockerfile'ın yerini alır; compose `image:` alanı aynı kalır.

## Yeniden değerlendirme koşulu

- İlk gerçek müşteri verisi ya da RPO < 24 saat isteği: WAL-G + S3 uyumlu depo.
- Prova süresinin RTO'ya (1 saat) yaklaşması.
- İkinci bir modül şeması (her şemanın kendi Flyway yapılandırması gerekir).
- Image build süresinin CI'da 10 dakikayı geçmesi: Jib yeniden değerlendirilir.
