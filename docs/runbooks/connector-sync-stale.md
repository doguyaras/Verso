# ConnectorSyncStale

**Anlamı:** Bir dosya paylaşımı bağlayıcısı (ADR-0038) üç eşitleme aralığı boyunca **başarılı** bir eşitleme yapmadı. Arama hâlâ eski belgelerle cevap verir; kaynakta eklenen, değişen ya da silinen dosyalar Verso'ya yansımaz. Başarı, kaynağın tamamının listelenip değişikliklerin uygulanması demektir: yarım kalan bir liste (okunamayan klasör, sınır) başarı sayılmaz, çünkü o durumda silmeler yayılmaz. Panel ve API aynı bağlayıcıyı `health: STALE` gösterir; son koşunun "başarılı" görünmesi bu alarmı susturmaz.

Etiket: `connector=<bağlayıcı id'si>`. Ad, sunucu, paylaşım ve yol hiçbir metrikte ve log satırında yoktur.

**Kontrol:**

1. Bağlayıcının durumu ve son koşular (yalnız `verso-admin` token'ı ile):

   ```bash
   curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/v1/admin/connectors/<id>
   curl -s -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:8080/v1/admin/connectors/<id>/runs?size=5"
   ```

   `lastErrorCode`, `consecutiveFailures` ve son koşunun `status` / `errorCode` / `listingComplete` alanlarına bak.
2. Log: `{service="verso-app"} |= "Connector sync" |= "<id>"`. Satırlar yalnız id, sayı ve sabit kod taşır (`code=`, `exceptionType=`).
3. Eşitleme hiç çalışmıyorsa (koşu satırı yok): `verso.connector.sync-enabled` açık mı, bağlayıcı `enabled` mı, başka bir instance kirayı tutuyor mu (`docker compose exec -T -u postgres postgres psql -X -d verso -c "select id, locked_until, next_run_at from connector.connector"`). Ölen bir instance'ın kirası en çok `verso.connector.lease` (10 dk) sonra düşer.

**Çözümler (`errorCode`):**

| Kod | Anlamı | Çözüm |
|---|---|---|
| `EGRESS_DENIED` | Sunucu adı izinli ağların dışına çözülüyor, ya da `VERSO_CONNECTOR_ALLOWED_NETWORKS` boş | Dosya sunucusunun adresini `.env`'deki listeye ekle (yalnız özel aralıklar), `verso-app`'i yeniden başlat. Adın dışarıya çözülmesi beklenmiyorsa DNS'i incele |
| `SOURCE_UNREACHABLE` | TCP bağlantısı ya da ad çözümü yok | `deploy/compose.smb.yaml` açık mı; host güvenlik duvarında `verso-smb` arayüzünden dosya sunucusunun 445'ine izin var mı (README "SMB bağlayıcısı") |
| `SOURCE_AUTH_FAILED` | Servis hesabı reddedildi ya da paylaşıma erişemiyor | Hesabın parolası değişmiş olabilir: `secrets/SECRET_CONNECTOR_SMB_PASSWORD` dosyasını yeni değerle yaz (`printf`, satır sonu olmadan), `verso-app`'i yeniden başlat; hesabın paylaşımda okuma izni var mı |
| `CREDENTIAL_MISSING` | `credentialRef`'in adını taşıyan secret dosyası uygulamada yok | Dosyayı oluştur ve overlay'in `secrets:` listesine ekle; yeniden başlat |
| `SHARE_NOT_FOUND`, `PATH_NOT_FOUND`, `PATH_UNREADABLE` | Paylaşım ya da önek klasörü yok, taşınmış ya da okunamıyor | Paylaşımı/klasörü düzelt ya da bağlayıcıyı `PUT` ile güncelle. Bu durumda **hiçbir belge silinmez** |
| `COLLECTION_MISSING` | Hedef bilgi alanı silinmiş | Bağlayıcıyı sil ve doğru alanla yeniden oluştur (hedef alan değiştirilemez) |
| `DIRECTORY_UNREADABLE`, `LINK_NOT_FOLLOWED`, `LISTING_TOO_DEEP`, `LISTING_TOO_LARGE` | Liste yarım kaldı: bir alt klasör okunamıyor, bir bağlantı (junction/symlink) atlandı ya da sınır aşıldı | Hesabın alt klasör izinlerini düzelt, bağlantıyı kaldır ya da `excludeGlobs` ile o klasörü dışarıda bırak; önek daha dar seçilebilir |
| `DELETION_SAFETY` | Koşu izlenen dosyaların %20'sinden fazlasını silecekti; silmeler bekletildi | Gerçekten silindiyse tek koşu için onayla: `POST /v1/admin/connectors/<id>/sync` gövdesi `{"confirmMassDeletion": true}`. Paylaşım yeniden adlandırıldıysa önce bağlayıcıyı düzelt |
| `CONFIG_INVALID` | Kayıtlı bir ayar bugünkü kurallardan geçmiyor | Bağlayıcıyı `PUT` ile yeniden kaydet (doğrulama hatası hangi alanın olduğunu söyler) |

Dosya bazlı hatalar (`FILES_FAILED`) bağlayıcıyı tek başına STALE yapmaz; hangi dosyaların beklediğini `GET /v1/admin/connectors/<id>/items?state=FAILED` gösterir (`READ_FAILED`, `ACCESS_DENIED`, `COLLECTION_FULL`; geri çekilmeyle yeniden denenir). `BACKLOG` belge alımı kuyruğunun dolu olduğunu söyler: [ingestion-backlog.md](ingestion-backlog.md).

**Kapanış:** Bir sonraki koşu `SUCCEEDED` (ya da yalnız dosya hatalı `PARTIAL`, `listingComplete: true`) ve `health: OK`; `verso_connector_sync_lag_seconds` düştü.
