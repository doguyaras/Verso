# secrets/

Compose bu klasördeki dosyaları container içinde `/run/secrets/<AD>` olarak bağlar (referans 15.3, seviye 1). Bu dosya dışında hiçbir şey git'e girmez (`.gitignore`).

- **Yerelde:** `bash scripts/dev-secrets.sh` eksik dosyaları rastgele değerlerle üretir. Var olan dosyaya dokunmaz ve hiçbir değeri ekrana yazmaz.
- **Üretimde:** deploy işi aynı adlı dosyaları CI secret'larından yazar.
- **İzinler:** klasör `0700`, dosyalar `0644`. Klasör, host'taki diğer kullanıcıları dışarıda tutar. Compose dosya secret'larını host'taki sahip ve izinle bind mount eder; container'lar onları root olmayan kullanıcılarla okur (postgres 999, uygulama 10001). Bu yüzden `0600` bir dosya yığını başlatmaz (ADR-0009).

| Dosya | Kim kullanır | Ne için |
|---|---|---|
| `SECRET_POSTGRES_SUPERUSER_PASSWORD` | postgres container'ı | Init script'leri ve restore provası; uygulama bu rolü hiç görmez |
| `SECRET_DB_DOCUMENT_MIGRATE_PASSWORD` | uygulama (Flyway) | `svc_document_migrate`: `document` şemasının sahibi, DDL |
| `SECRET_DB_DOCUMENT_PASSWORD` | uygulama | `svc_document`: yalnız DML, DDL yetkisi yok |
| `SECRET_DB_QA_MIGRATE_PASSWORD` | postgres (init), `migrate` | `svc_qa_migrate`: `qa` şemasının sahibi, DDL (ADR-0023) |
| `SECRET_DB_QA_PASSWORD` | postgres (init), uygulama | `svc_qa`: `qa` şemasında yalnız DML; geçmiş, geri bildirim ve kullanım sayaçları (ADR-0023) |
| `SECRET_DB_AUDIT_MIGRATE_PASSWORD` | postgres (init), `migrate` | `svc_audit_migrate`: `audit` şemasının sahibi, DDL (ADR-0026) |
| `SECRET_DB_AUDIT_PASSWORD` | postgres (init), uygulama | `svc_audit`: `audit` şemasında yalnız INSERT ve SELECT; denetim kaydı (ADR-0026) |
| `SECRET_DB_CONNECTOR_MIGRATE_PASSWORD` | postgres (init), `migrate` | `svc_connector_migrate`: `connector` şemasının sahibi, DDL (ADR-0038) |
| `SECRET_DB_CONNECTOR_PASSWORD` | postgres (init), uygulama | `svc_connector`: `connector` şemasında yalnız DML; bağlayıcı ayarları ve dosya eşitleme durumu (ADR-0038) |
| `SECRET_DB_WORK_MIGRATE_PASSWORD` | postgres (init), `migrate` | `svc_work_migrate`: `work` şemasının sahibi, DDL (ADR-0057) |
| `SECRET_DB_WORK_PASSWORD` | postgres (init), uygulama | `svc_work`: `work` şemasında yalnız DML; beceri çalıştırmaları, girdileri ve kuruma özel beceriler (ADR-0057) |
| `SECRET_DB_BACKUP_PASSWORD` | backup servisi | `verso_backup`: `pg_read_all_data`, yalnız okuma |
| `SECRET_BACKUP_ENCRYPTION_KEY` | backup servisi, restore provası | Yedek dosyalarının gpg (AES256) parolası |
| `SECRET_DB_KEYCLOAK_PASSWORD` | postgres (init), keycloak | `keycloak` rolü: demo IdP'nin kendi veritabanı (ADR-0010) |
| `SECRET_KEYCLOAK_ADMIN_PASSWORD` | keycloak | master realm'in açılış yöneticisi `admin` (`127.0.0.1:8180/admin`) |
| `SECRET_KEYCLOAK_CI_CLIENT_SECRET` | keycloak, `scripts/auth-smoke.sh` | `verso-ci` istemcisinin secret'ı (client credentials) |
| `SECRET_KEYCLOAK_DEMO_USER_PASSWORD` | keycloak | `verso` realm'indeki `demo` kullanıcısının parolası |
| `SECRET_KEYCLOAK_PANEL_ADMIN_PASSWORD` | keycloak | `verso` realm'indeki `admin` kullanıcısının (panel operatörü) parolası |
| `SECRET_GRAFANA_ADMIN_PASSWORD` | grafana (`obs` profili) | Grafana'nın `admin` kullanıcısı (`127.0.0.1:3000`, ADR-0014) |
| `SECRET_CONNECTOR_SMB_PASSWORD` | uygulama, yalnız SMB overlay'i (`deploy/compose.smb.yaml`) | SMB bağlayıcısının salt okunur servis hesabının parolası; bağlayıcı bu adı `credentialRef` ile anar, değer veritabanına, log'a ve yanıta hiç girmez. Script yalnız yer tutucu üretir, gerçek parolayı sen yazarsın (`printf`, satır sonu olmadan). Başka hesaplar için `SECRET_CONNECTOR_<AD>` biçiminde dosya eklenir (ADR-0038) |
| `SECRET_CONNECTOR_SHAREPOINT_CERT` | uygulama, yalnız SharePoint overlay'i (`deploy/compose.sharepoint.yaml`) | SharePoint Online / OneDrive bağlayıcısının Entra uygulama kaydının kimliği: PKCS#8 özel anahtar (`-----BEGIN PRIVATE KEY-----`, RSA ≥ 2048) ve sertifika (`-----BEGIN CERTIFICATE-----`) tek PEM dosyasında; Entra'ya yalnız sertifikanın açık kısmı yüklenir. İstemci gizlisi kullanılırsa onun yerine `SECRET_CONNECTOR_SHAREPOINT_SECRET` (`authMethod: CLIENT_SECRET`). Bağlayıcı bu adı `credentialRef` ile anar; değer veritabanına, log'a, denetim kaydına ve yanıta hiç girmez. Script yalnız yer tutucu üretir, gerçek anahtarı sen yazarsın (ADR-0045) |
| `SECRET_CONNECTOR_CONFLUENCE_TOKEN` | uygulama, yalnız Confluence overlay'i (`deploy/compose.confluence.yaml`) | Confluence bağlayıcısının belirteci: Data Center'da kişisel erişim belirteci (Bearer), Cloud'da hesabın API belirteci (e-posta ile Basic). Bağlayıcı bu adı `credentialRef` ile anar; değer veritabanına, log'a, denetim kaydına ve yanıta hiç girmez. Script yalnız yer tutucu üretir, gerçek belirteci sen yazarsın (`printf`, satır sonu olmadan). Salt okuma yetkili, yalnız eşitlenecek alanları gören bir hesabın belirteci olmalı (ADR-0044) |
| `SECRET_CLOUD_API_KEY` | uygulama, yalnız cloud overlay'i | Bulut sağlayıcısının API anahtarı; script üretmez, sen yazarsın (ADR-0013) |

Keycloak secret'larını `deploy/keycloak/start.sh` okur ve yalnız Keycloak sürecinin ortamına verir; compose ortamına girmezler.

Dosya adı Spring'de aynı adlı property olur (config tree). Bu yüzden `config/verso.yml` `${SECRET_DB_DOCUMENT_PASSWORD}` yazar ve fallback kullanmaz.

## Rotasyon

Postgres init script'leri yalnız ilk kurulumda (boş veri volume'ünde) çalışır. Dosyayı değiştirmek, var olan rolün parolasını değiştirmez. Prosedür yalnız burada yazılıdır.

**Veritabanı parolaları** (`SECRET_DB_*`):

1. Yeni değeri dosyaya yaz (eski dosyanın yedeğini al).
2. Parolayı, logda ve istatistikte iz bırakmadan değiştir. Düz bir `ALTER ROLE ... PASSWORD '...'` komutu, metni `pg_stat_statements`'e ve PGDATA'ya yazar; başarısız olursa da sunucu loguna düşer (faz 2 güvenlik review S3).

   Değer host'taki dosyadan stdin ile gider; komut satırına düşmez. Container'daki `/run/secrets` bind mount'u, dosya yeni inode ile değiştirildiyse eski değeri gösterebilir; o yüzden kullanılmaz.

   ```bash
   { echo "SET pg_stat_statements.track_utility = off; SET log_min_error_statement = panic;"; printf "\\\\set pw '%s'\n" "$(cat secrets/SECRET_DB_DOCUMENT_PASSWORD)"; echo "ALTER ROLE svc_document PASSWORD :'pw';"; } | docker compose exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U postgres -d verso
   ```

3. Rolü kullanan servisi yeniden başlat: `docker compose up -d --force-recreate verso-app` (migration rolü için `migrate`; backup rolü için `backup`). Backup servisi parolayı yalnız açılışta okur.

**Yedek şifreleme anahtarı** (`SECRET_BACKUP_ENCRYPTION_KEY`): eski yedekler eski anahtarla şifrelidir. Restore provası yalnız güncel anahtarı kullandığından, anahtarı değiştirmek eski yedekleri okunamaz yapar (ortam review E10).

1. Eski anahtarı `secrets/` dışında, erişimi kısıtlı bir yerde sakla. Eski yedekler saklama süresi (`BACKUP_RETENTION_DAYS`) boyunca yalnız onunla açılır.
2. Yeni anahtarı yaz, `backup` servisini yeniden başlat, `docker compose run --rm backup once` ile yeni bir yedek al ve `bash scripts/restore-drill.sh` ile doğrula.
3. Saklama süresi dolunca eski anahtarı imha et.

**Keycloak** (`SECRET_DB_KEYCLOAK_PASSWORD` dışındakiler): realm dosyası yalnız ilk açılışta, `keycloak` veritabanı boşken içe aktarılır. Sonradan dosyayı değiştirmek realm'deki değeri değiştirmez.

- **Demo yığını:** en basit yol IdP'yi sıfırdan kurmaktır; Verso verisi etkilenmez. Yeni değeri dosyaya yaz, sonra:

  ```bash
  docker compose stop keycloak && docker compose exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U postgres -d verso -c 'DROP DATABASE keycloak WITH (FORCE)' -c 'CREATE DATABASE keycloak OWNER keycloak' -c 'REVOKE ALL ON DATABASE keycloak FROM PUBLIC' -c 'GRANT CONNECT ON DATABASE keycloak TO keycloak' && docker compose up -d --wait keycloak
  ```

- **Kalıcı kurulum:** değeri yönetim konsolundan değiştir (istemci → Credentials → Regenerate; kullanıcı → Credentials → Reset password), sonra aynı değeri dosyaya yaz.

`SECRET_DB_KEYCLOAK_PASSWORD` veritabanı parolalarıyla aynı psql kalıbıyla (`ALTER ROLE keycloak`) değişir; sonra `docker compose up -d --force-recreate keycloak`.

**Superuser parolası:** yalnız ilk kurulumda ve restore provasında kullanılır. Rotasyonu aynı psql kalıbıyla `ALTER ROLE postgres` olarak yapılır.
