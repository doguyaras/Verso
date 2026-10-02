# secrets/

Compose bu klasördeki dosyaları container içinde `/run/secrets/<AD>` olarak bağlar (referans 15.3, seviye 1). Bu dosya dışında hiçbir şey git'e girmez (`.gitignore`).

- **Yerelde:** `bash scripts/dev-secrets.sh` eksik dosyaları rastgele değerlerle üretir. Var olan dosyaya dokunmaz ve hiçbir değeri ekrana yazmaz.
- **Üretimde:** deploy işi aynı adlı dosyaları CI secret'larından yazar (`chmod 600`).

| Dosya | Kim kullanır | Ne için |
|---|---|---|
| `SECRET_POSTGRES_SUPERUSER_PASSWORD` | postgres container'ı | Init script'leri ve restore provası; uygulama bu rolü hiç görmez |
| `SECRET_DB_DOCUMENT_MIGRATE_PASSWORD` | uygulama (Flyway) | `svc_document_migrate`: `document` şemasının sahibi, DDL |
| `SECRET_DB_DOCUMENT_PASSWORD` | uygulama | `svc_document`: yalnız DML, DDL yetkisi yok |
| `SECRET_DB_BACKUP_PASSWORD` | backup servisi | `verso_backup`: `pg_read_all_data`, yalnız okuma |
| `SECRET_BACKUP_ENCRYPTION_KEY` | backup servisi, restore provası | Yedek dosyalarının gpg (AES256) parolası |

Dosya adı Spring'de aynı adlı property olur (config tree). Bu yüzden `config/verso.yml` `${SECRET_DB_DOCUMENT_PASSWORD}` yazar ve fallback kullanmaz.

**Rotasyon:** dosyayı değiştir → ilgili rolün parolasını `ALTER ROLE ... PASSWORD` ile güncelle → servisi yeniden başlat. Postgres init script'leri yalnız ilk kurulumda (boş veri volume'ünde) çalışır; sonradan parola değiştirmek bu script'leri yeniden çalıştırmaz. Prosedür: `docs/adr/0009-veri-altyapisi.md`.
