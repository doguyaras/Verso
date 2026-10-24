# ADR-0038 doğrulaması: SMB dosya paylaşımı bağlayıcısı

Tarih 2026-10-24. Dal `feature/doguyaras-smb-baglayici`, taban `origin/develop` `86e73d1`. İlk turda Docker kapalıydı ve CI'da `ConnectorSyncTest` düştü. Ardından dal `origin/develop`'a (ADR-0039 outbox) taşındı ve Docker açıkken `ConnectorSyncTest` ile `ConnectorApiTest` yerelde Testcontainers ile koşturuldu.

## CI hatasının nedeni ve düzeltme

- **Neden:** test dosyaları paylaşımın dizinine değil kök dizine yazıyordu. `dockurr/samba` etkin yapılandırmayı `/etc/samba/smb.tmp`'ye yazıyor (`smbd --configfile`), `/etc/samba/smb.conf` yok; test yolu oradan okuyordu, boş kalınca dosyalar `/<önek>` altına gitti. Bağlayıcı doğru davrandı: `FAILED/PATH_NOT_FOUND`, hiçbir şey eklenmedi ve silinmedi.
- **Düzeltme:** yol `smb.tmp`'den okunur, boşsa komut başarısız olur. Test sunucuya, bir kurulumun dosya sunucusuna ulaştığı gibi, host'un özel (RFC 1918) adresi ve yayınlanan port üzerinden bağlanır (köprü adresi Docker Desktop'ta yönlendirilmez; loopback bilerek reddedilir).
- **Bulunan ek gizlilik açığı:** smbj kendi WARN satırlarına UNC yolunu (sunucu, paylaşım, klasör adı) yazıyordu. `logging.level.com.hierynomus: OFF` (`config/verso.yml`); test her koşudan sonra log'da yol ve klasör adı olmadığını doğrular.
- **Denetim yolu ADR-0039'a taşındı:** yönetici ve koşu kayıtları `connector.outbox_event`, belge olayları `DocumentIngestion` transaction'ında `document.outbox_event`; `SystemAuditEvent` kaldırıldı, yerine `AuditTrail.captureSystem`.

## Makine kontrolleri (yerel)

| Kontrol | Sonuç |
|---|---|
| `./mvnw -B -ntp -T 1 -DskipTests install` (JDK 25; enforcer, `dependencyConvergence` ve core→core yasağı dahil) | BUILD SUCCESS |
| `connector-core` birim testleri: `SyncPlannerTest`, `SourcePathsTest`, `GlobSetTest`, `NetworkAllowlistTest`, `HealthRuleTest`, `EgressAndCredentialsTest`, `SyncRunnerTest` (Mockito; dosya hatası koşuyu durdurmaz, kaynak düşünce ve yarım listede silme yok, aynı içerik yeniden alınmaz, kuyruk doluyken indirme durur, silme emniyeti, log'da kaynak/parola yok), `ConnectorArchitectureTest` | 80 test, yeşil |
| `connector-api` `ConnectorApiContractTest` (parola alanı yok, `credentialRef` yalnız `SECRET_CONNECTOR_*`) | yeşil |
| `ArchitectureRulesTest`, `ConfigDriftTest`, `ErrorCodeUniquenessTest`, `ModuleStructureTest` (Modulith: `document`, `qa`, `audit`, `privacy`, `connector`), `ModuleConsistencyTest`, `ComposeConfigTest` (SMB overlay testi dahil), `ImageVersionsTest`, `TransactionBoundaryRulesTest`, `MigrationConventionsTest`, `DocumentArchitectureTest`, `AuditArchitectureTest`, `AuditRecorderTest`, `AuditApiContractTest` | yeşil |
| `node scripts/flyway-immutability.js check --base origin/develop` | `flyway-immutability: OK (base=origin/develop, merge-base=86e73d166f19)` |
| `GITLEAKS=… node --test scripts/*.test.js scripts/panel.test.mjs` | 190 test, 189 geçti, 0 kaldı (1 atlandı) |
| `node --test scripts/uyum-paketi.test.js` | 9/9 (VKED'e yeni veri kaynağı ve R14) |
| smbj ve bağımlılıkları OSV querybatch (2026-10-24) | kayıt yok |

## CI'da koşacak gerçek veritabanı ve Samba testleri (seviye 2)

| Senaryo | Test | Durum |
|---|---|---|
| Yalnız `verso-admin`: 8 uç × kullanıcı/operatör 403 (denetimde `ACCESS_DENIED`/`ADMIN_CONNECTOR`), kimliksiz 401 | `ConnectorApiTest#endpoints_whenCallerIsNotAnAdmin_areDeniedAndAudited` | yerelde yeşil (Docker), CI bekliyor |
| Oluşturma: normalize önek, `no-store`, `Location`, parola hiçbir yanıtta yok, `CONNECTOR_CREATED` | `ConnectorApiTest#create_whenValid_…` | yerelde yeşil (Docker), CI bekliyor |
| Traversal (`..`, sürücü, akış), dış/loopback/metadata IP, başka secret adı, eğik çizgili paylaşım, büyük dosya, kötü glob, olmayan alan reddedilir; veritabanına satır girmez | `ConnectorApiTest#create_whenAFieldCouldLeaveTheShareOrTheNetwork_isRefused` | yerelde yeşil (Docker), CI bekliyor |
| Hedef alan değişmez, ad tekil, `PUT` ayarları değiştirir | `ConnectorApiTest#update_…` | yerelde yeşil (Docker), CI bekliyor |
| Silme 202 ve idempotent, silinen eşitlenemez, sayfa sınırı | `ConnectorApiTest#deleteAndSync_…` | yerelde yeşil (Docker), CI bekliyor |
| İki instance aynı anda ayrık bağlayıcı alır; tutulan kira alınmaz; kirası kaybolan sahip yenileyemez ve sonuç yazamaz | `ConnectorApiTest#claimDue_whenTwoInstancesClaimAtOnce_…` | yerelde yeşil (Docker), CI bekliyor |
| Samba: ekleme, değişiklik (eski belge gider), silme (bütün `document.*` tablolarında iz yok), tekrar koşu değişiklik yapmaz; exclude glob, boyut sınırı, desteklenmeyen uzantı; okuyucu satırı alanın grant'ı; `system:connector-sync` denetim satırları; `health: OK` | `ConnectorSyncTest#sync_whenFilesAreAddedChangedAndDeleted_…` | yerelde yeşil (Docker), CI bekliyor |
| Çökmeden sonra çift belgesiz devam, `ABANDONED` koşu, tutulan kira başka koşuyu engeller | `ConnectorSyncTest#sync_whenTheLastRunDiedMidway_…` | yerelde yeşil (Docker), CI bekliyor |
| İzinli ağ dışına çözülen ad (`localhost`) hiç bağlanılmadan `FAILED/EGRESS_DENIED`, `health: FAILING` | `ConnectorSyncTest#sync_whenTheHostResolvesOutsideTheAllowlist_…` | yerelde yeşil (Docker), CI bekliyor |
| Önek klasörü silinince `FAILED/PATH_NOT_FOUND`, hiçbir belge silinmez | `ConnectorSyncTest#sync_whenThePrefixFolderIsGone_…` | yerelde yeşil (Docker), CI bekliyor |
| Bağlayıcı silinince belgeleri ve öğeleri gider | `ConnectorSyncTest#delete_whenTheConnectorIsRemoved_…` | yerelde yeşil (Docker), CI bekliyor |
| Samba parolası hiçbir log satırında ve yanıtta yok (her test) | `ConnectorSyncTest#tearDown` | yerelde yeşil (Docker), CI bekliyor |
| `ConnectorSyncStale` alarmı ateşler / susar | `deploy/obs/prometheus/alerts.test.yml` (promtool, `scripts/obs-smoke.sh`) | gözlem yığınıyla |

## Canlı kontrol gerekenler (kurulumda)

- Gerçek bir Windows dosya sunucusunda NTLM, SMB imzası ve (açılırsa) SMB3 şifrelemesi; etki alanı hesabıyla oturum.
- `deploy/compose.smb.yaml` + host `DOCKER-USER` kuralı ile uygulamanın yalnız dosya sunucusu:445'e çıkabildiği (`scripts/prove-local-mode.sh` 2. adım ve elle `curl` denemesi).
- Büyük paylaşımda (on binlerce dosya) tam listeleme süresi ve bellek.
- Windows'ta junction ve sembolik bağlantının reparse point olarak atlandığı.
