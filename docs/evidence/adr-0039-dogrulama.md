# ADR-0039 doğrulaması: denetim outbox'ı, `document` havuzu, süreç rolü anahtarı

Tarih 2026-10-24. Dal `feature/doguyaras-outbox-datasource`, taban `origin/develop` `9a67969`. Geliştirme makinesinde Docker container'ı başlatılmadı (makine yükü); Testcontainers testleri CI'da (`backend` işi) koşar ve kapı odur.

## Makine kontrolleri (yerel, JDK 25)

| Kontrol | Sonuç |
|---|---|
| `./mvnw -B -ntp -T 1 -DskipTests install` | BUILD SUCCESS (tüm modüller, test kaynakları derlendi) |
| `./mvnw test -pl platform-core,audit-api,audit-core,document-core,qa-core,privacy-core,mcp-core` | BUILD SUCCESS: 55 / 6 / 23 / 105 / 101 / 36 / 7 test, 0 hata (`OutboxRelayTest` 11, `AuditTrailServiceImplTest` 4, `IngestionRoleTest` 3, `MigrationConventionsTest`, `AuditArchitectureTest` dahil) |
| `verso-app`: `ArchitectureRulesTest`, `ConfigDriftTest`, `ErrorCodeUniquenessTest`, `ConfigProfilesTest`, `ComposeConfigTest`, `ModuleStructureTest` (Modulith verify), `ModuleConsistencyTest`, `TransactionBoundaryRulesTest`, `ImageVersionsTest`, `KeycloakRealmTest` | 53/53 yeşil |
| `node scripts/flyway-immutability.js check --base origin/develop` | `flyway-immutability: OK (base=origin/develop, merge-base=9a6796991e15)` |
| `GITLEAKS=… node --test scripts/*.test.js scripts/panel.test.mjs` | 200 test, 199 geçti, 0 kaldı, 1 atlandı |
| `docker compose config -q` (yalnız CLI, container yok) | hatasız |
| pre-commit (flyway-immutability, config-lint, gitleaks) | OK |

Yerelde koşmayanlar (Docker gerektirir, CI kapısı): `AuditOutboxTest`, `DocumentDataSourceTest`, `IngestionDisabledTest`, yeniden yazılan `AuditFailureIsolationTest`, `MigrateModeTest`, `DeployConfigTest` ve var olan tüm Testcontainers testleri; `promtool test rules` (`scripts/obs-smoke.sh`, beklenen kural sayısı 11; taban dosyada 9 kural varken betik 8 bekliyordu, bu da düzeltildi); restore provası.

## Yeni ve değişen testler

| Test | Ne kanıtlar | Seviye |
|---|---|---|
| `OutboxRelayTest` (11) | Başarıda claim token'ıyla silme; geçici hatada (bağlantı) 600 sn tavanlı backoff, asla DEAD; kalıcı hatada `max-attempts`'ta DEAD; bilinmeyen tür kalıcı; kira payında satır deneme sayılmadan geri verilir; yaş göstergesi veritabanı okunamazken büyümeye devam eder, boşken 0, birim `seconds`; eager teslim hatadan sonra herkes için durur; poller'ın aldığı satıra dokunmaz; özellik sınırları; yazıcı transaction dışında reddeder, tablo adı doğrulanır | 1 (unit) |
| `AuditTrailServiceImplTest` (4) | `capture` sub, rol, trace id, mod; kimliksiz çağıran reddedilir; `store` iki kez aynı kayıtla yalnız yazdığı satırı sayar; `AuditRecord` tablonun reddedeceği değeri (rol, trace, boş sub) outbox'a sokmaz | 1 |
| `IngestionRoleTest` (3) | `verso.ingestion.enabled=false` iken 11 worker bean'i yok; anahtar yokken/true iken var; relay işi anahtara bağlı değil | 1 (context runner) |
| `AuditArchitectureTest` (güncellendi) | Yalnız `ON CONFLICT (event_id) DO NOTHING` izinli; `DO UPDATE` ve hedefsiz `ON CONFLICT` hâlâ yakalanır | 1 (statik) |
| `AuditOutboxTest` (7) | Rollback outbox satırı bırakmaz (document ve qa); satır transaction içinde görünür, commit'ten önce dışarıdan görünmez; yazıcı transaction dışında ve başka modülün transaction'ında reddeder; aynı olay iki kez teslim edilince tek `audit.event` satırı; denetim INSERT'i ile outbox DELETE arasında ölen relay'den sonra kira dolunca yeni relay tek satırla bitirir; commit ile relay arasında ölünce sonraki poll teslim eder; iki paralel claim ayrık satır alır, kirası dolmuş eski worker silemez/işaretleyemez; yaş göstergesi bekleyen satırda ≥ 119 sn, teslimden sonra 0 | 2 (gerçek PG, CI) |
| `AuditFailureIsolationTest` (yeniden yazıldı) | Gerçek kesinti (`svc_audit NOLOGIN`): dört istek başarılı, 15 sn altında; hiçbir kayıt kaybolmaz (`not.stored` artmaz), 2 + 2 satır outbox'ta bekler, relay logları yalnız tip taşır; giriş açılınca dört kayıt birer kez teslim edilir, outbox'lar boşalır | 2 (CI) |
| `DocumentDataSourceTest` (3) | Tek `DataSource` bean'i `documentDataSource`, havuz `verso-document-db` / `svc_document`; Boot Flyway bean'i yok, `DocumentSchemaMigration` koştu; repository'nin `JdbcClient`'ı uygulamanınki, sorgu `svc_document` olarak bu havuzda | 2 (CI) |
| `IngestionDisabledTest` (1) | API-only süreç açılır, worker bean'leri yok, outbox relay var, yükleme 201 ve `PENDING` | 2 (CI) |
| `MigrateModeTest`, `ConfigProfilesTest`, `ConfigDriftTest`, `DeployConfigTest`, `ComposeConfigTest` (güncellendi) | Anahtarlar `verso.document.datasource/flyway`; migrate modunda Flyway bean'i yok, `DocumentSchemaMigration` var, havuz yok; auto-config dışlaması her profilde; 11 alarm, runbook'ları var | 1–2 |

## İnceleme skill'leri (her biri bir kez)

| Skill | Karar | Bulgular |
|---|---|---|
| `verso-operation-consistency-review` | Uygunluk: `saga unnecessary` (tek modül yazımı + commit sonrası olay). Uygulama bulgusu yok (BLOCK/HIGH yok). Doğrulama durumu yerelde `BLOCKED` (seviye 2 senaryolar CI'da); CI yeşil olunca `PASS` | MEDIUM: kirası dolan `PUBLISHING` satır yeniden alınırken `retry_count` artmaz; JVM'i düşüren zehirli bir yük sonsuz döner (bugün yalnız JSON okuma + INSERT; kabul edildi, runbook "PUBLISHING uzun süredir" satırı) |
| `verso-db-migration-review` | APPROVE WITH NON-BLOCKING COMMENTS | MEDIUM: `uq_event_event_id` CONCURRENTLY değil (V2 ile aynı ölçü, mesai dışı migrate, migration yorumunda). LOW: standart tablonun `headers`, `priority`, `dead_policy`, `expires_at` kolonları bugün yazılmıyor (referans 11.2 / Debezium uyumu için bilinçli). LOW: `DEAD` satırların yükü token `sub`'ı en çok 30 gün tutar (ADR-0039 saklama) |
| `verso-spring-code-review` | APPROVE WITH NON-BLOCKING COMMENTS | MEDIUM: outbox satırında `traceparent` yok; trace id yükte (`AuditRecord.traceId`) taşınır, relay span'i devam ettirmez. LOW: `OutboxRelay.deliver` geniş `catch (Exception)` (handler sözleşmesi `throws Exception`; sınıflandırır, yalnız tip loglar) |
| `verso-environment-impact-review` | APPROVE | Yüzeyler: `verso.yml`, `application-local.yml` (URL/kullanıcı), `prod.env.example` + `.env.example` + compose (`VERSO_INGESTION_ENABLED`), `ConfigDriftTest` yeni prefix'ler; secret adları değişmedi, fallback yok; havuz bütçesi 22/100 değişmedi |
| `verso-architecture-boundary-review` | APPROVE | Bu kapsamda sınır bulgusu yok: her modül yalnız kendi şemasına yazar, `audit.event`'e yalnız `audit-core` kendi havuzuyla yazar (`AuditTrail`, `audit :: api`); `platform-core` servis modülüne bağlı değil (`ArchitectureRulesTest`, `ModuleStructureTest` yeşil) |
| `verso-test-writer` | PASS (yerel), CI bekleniyor | Eksik bulunan eşzamanlılık case'i eklendi (`AuditOutboxTest#claim_whenTwoRelaysPollAtOnce…`). LOW: `OutboxRelayTest` kira payı case'i 1,1 sn `Thread.sleep` kullanır (relay `System.nanoTime` ile ölçer) |
| `verso-resilience-review` | APPROVE WITH NON-BLOCKING COMMENTS | Uzak HTTP çağrısı eklenmedi. MEDIUM: denetim DB'si yavaş ama başarılıysa eager teslim isteği en çok 3 sn bekletebilir (sorgu zaman aşımı 3 sn, hata sonrası 10 sn ara). Kritik akış kaydı güncellendi (`repo-context.md` Bölüm 3, denetim satırı) |

## Restore ve migrate betikleri

`scripts/restore-drill.sh`, `scripts/restore-drill-selftest.sh`, `deploy/backup/restore-check.sh` ve compose `restore-flyway*` servisleri okundu: Spring DataSource/Flyway anahtarlarına değil rollere, secret dosyalarına ve migration klasörlerine dayanırlar; değişiklik gerekmedi. Yeni `outbox_event` tabloları varsayılan haklarla uygulama rolünce okunur, `restore-check.sh` döngüsünden geçer. Compose `migrate` komutundaki `--spring.autoconfigure.exclude` satırı kaldırıldı (dışlama artık `application.yml`'de, her profil için).
