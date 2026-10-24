# ADR-0039: Denetim kaydı için süreç içi transactional outbox, `document` modülünün kendi havuzu ve süreç rolü anahtarı

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras (mimari uyum açıkları: referans 1.1 şekil A, ADR-0026:151, ADR-0009:133); uygulama ayrıntıları bu ADR'de
- **İlgili eşik (referans Bölüm 24):**
  - `outbox_oldest_pending_age_seconds` > kira (120 sn) sık görülürse: poll aralığı, batch ya da lane ayrımı (referans 11.2).
  - Outbox'a denetim dışında bir olay türü gelirse (uygulama dışı mesaj, hesap silme saga'sı): lane'ler ve Modulith registry ile tek kaynak kararı (referans 1.1).
  - Ingestion'ın ayrı süreç olması (ADR-0001'in `verso-ingest` tetikleyicileri): `verso.ingestion.enabled` ile iki compose servisi.

## Bağlam

İki uyum açığı ve bir hazırlık:

1. **Denetim satırının dayanıklılık penceresi (ADR-0026, "Olumsuz / kabul edilen risk").** `document` ve `qa`, `AuditEvent`'i iş transaction'ı commit olduktan sonra süreç içi Spring olayı olarak yayınlıyordu; `AuditRecorder` satırı başka bir rol ve havuzla (`svc_audit`) yazıyordu. Commit ile `audit.event` INSERT'i arasında süreç ölürse satır kayboluyor ve hiçbir sayaçta görünmüyordu. Denetim veritabanı yokken de satır "kayıp" sayılıp bırakılıyordu. Referans 1.1 şekil A için **süreç içi outbox** bekler: olay kaydı iş satırıyla aynı transaction'da yazılır. Referans 11.2 tabloyu, poller'ı (SKIP LOCKED + kira + `claim_token`, backoff, DEAD, `*_STUCK`), 11.3 alıcının idempotent olmasını, 14.3 `outbox_oldest_pending_age_seconds` metriğini ve alarmını ister.
2. **`document` uygulamanın varsayılan DataSource'unu kullanıyordu (ADR-0009).** `qa` (ADR-0023) ve `audit` (ADR-0026) kendi havuzlarını ve Flyway'lerini açıkça kuruyor; `document` Boot'un `spring.datasource.*` ve `spring.flyway.*` auto-config'ine yaslanıyordu. Sonuç: "uygulamanın havuzu" ile "document'ın havuzu" aynı şeydi ama bu hiçbir yerde açık değildi.
3. **Süreç rolü.** ADR-0001 ingestion'ın ayrı bir süreç (`verso-ingest`) olacağı tetikleyicileri tanımlar. Aynı imajın yalnız API ya da yalnız worker olarak çalışabilmesi için bugünden bir anahtar gerekir.

Kısıtlar: şekil A (tek deploy birimi), modül başına şema ve rol (referans 10.1; GRANT ile "çapraz yazma" çözülmez), olay yalnız kimlik ve sabit sözcük taşır (security-rules 4/5, llm-rules 2.1), Docker'sız geliştirme makinesi (Testcontainers testleri CI'da koşar).

## Seçenekler

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| A. Spring Modulith event publication registry (`event_publication`) | Referans 1.1'in adını verdiği mekanizma; `@ApplicationModuleListener` ile at-least-once | Registry **tek bir** JDBC şemasında ve uygulamanın tek `DataSource`/transaction manager'ında yaşar. `qa`'nın havuzu bilerek bean değildir (ADR-0023); `qa` transaction'ı registry'nin transaction'ı olamaz, yani `qa` olayları için atomiklik yine olmaz. Registry'yi `document` şemasına koymak `qa`'nın satırını başka bir rolün tablosuna yazdırmak demektir (GRANT ile çözmek yasak, AGENTS.md 4). ADR-0026'nın reddettiği nedenin aynısı | Orta; mimari ihlal |
| B. Modül başına açık, küçük transactional outbox (`<şema>.outbox_event`) + tek generic relay (`platform-core`) | Her modül kendi şemasında, kendi rolüyle, kendi transaction'ında yazar: gerçek atomiklik. Referans 11.2'nin standart tablosu (Debezium uyumlu), poller sabitleri, metrik ve alarm birebir. Alıcı `event_id` ile idempotent | İki tablo, iki relay örneği (kod tek); denetim satırının zamanı teslim anı olur (aşağıda) | Düşük–orta |
| C. Durum korunur, yalnız belgelenir | İş yok | Açık kalır; referans 1.1'e aykırı | – |

Havuz için: (a) `document`'ın havuzu da `qa`/`audit` gibi bean olmayan bir tutucu, tüm repository'ler elle `DocumentDatabase`'e taşınır; (b) `document` modülü havuzunu **açıkça** tek `DataSource` bean'i olarak kendi config'inde tanımlar, Boot'un DataSource ve Flyway auto-config'i dışlanır. (a) on dört repository/servis ve onlarca testin fixture'ını değiştirir, `@Transactional`'ı ve `db` sağlık katkısını yeniden yazdırır; davranış değişmeden aynı sonuca (b) ile ulaşılır.

## Karar

**Outbox: seçenek B.** Modulith registry ayrı havuzlarla çakışır (yukarıda; ADR-0026 seçenek B ile aynı gerekçe), bu yüzden açık bir outbox seçildi.

1. **Tablo:** `document.outbox_event` (V5) ve `qa.outbox_event` (V3), referans 11.2'nin standart kolonlarıyla; `kind = 'EVENT'`, claim index'i, tablo başına `autovacuum_vacuum_scale_factor = 0.01`. Uygulama rolü şemanın varsayılan haklarıyla DML alır; yeni GRANT yok.
2. **Yazıcı (Propagation.MANDATORY):** `platform-core` `OutboxStore.append` yalnız o modülün havuzunda açık bir transaction varken yazar (`TransactionSynchronizationManager.hasResource(<modül DataSource'u>)`); `document` transaction'ı `qa` yazıcısını açmaz. Yazım yerleri: `DocumentServiceImpl` (yükleme, silme, taşıma) ve `CollectionServiceImpl` (oluşturma, güncelleme, silme, izin ekleme/kaldırma) kendi `transaction.execute` bloklarının içinde; `qa`'da turu saklayan transaction (`QUESTION_ASKED`), konuşma silme, tümünü silme (son batch), geri bildirim; yönetici metrikleri görüntüleme kendi kısa transaction'ında. Satır iş değişikliğiyle commit ya da rollback olur.
3. **Yük:** `audit-api` `AuditRecord` = olay kimliği + token `sub` + Verso rolleri + trace id + mod + `AuditEvent`. Kimlik ve iz bilgisi istek anında `AuditTrail.capture` ile alınır (relay'de istek yoktur, referans 9.2 "arka plan çağrısında kimlik"). Kurucu `audit.event`'in CHECK kurallarını tekrarlar: tablonun reddedeceği bir kayıt outbox'a girmez. Yalnız kimlik ve sabit sözcük; dosya adı, koleksiyon adı, soru, cevap yoktur.
4. **Relay:** `platform-core` `OutboxRelay`; her modülde bir örnek (`DocumentOutbox`, `QaOutbox` tipleri, birbirine tip ile verilemez) ve `worker/*OutboxRelayJob` (`verso.outbox.poll-interval`, her instance'ta). Claim: `FOR UPDATE SKIP LOCKED`, `locked_until` kirası, `claim_token`; kira payı (30 sn) içinde başlanmayan satırlar deneme sayılmadan geri verilir. Başarı: satır silinir. Geçici hata (bağlantı, zaman aşımı, kilit, transaction): `min(600, 30·2^n)` sn backoff, **asla DEAD olmaz**. Kalıcı hata (CHECK, okunamayan yük, bilinmeyen tür): 20 denemeden sonra `DEAD`; her 10 denemede `code=OUTBOX_STUCK`, vazgeçişte `code=OUTBOX_DEAD`. Log yalnız `outbox`, `eventId`, `retry`, `exceptionType`.
5. **İdempotentlik (alıcı):** `audit.event.event_id` (V3, tekil index). `AuditTrail.store` `INSERT ... ON CONFLICT (event_id) DO NOTHING` yapar; `svc_audit` yine yalnız INSERT/SELECT'tir, çift teslim ne satır çoğaltır ne var olanı değiştirir. `AuditArchitectureTest` yalnız bu tek `ON CONFLICT` biçimine izin verir.
6. **Commit sonrası hemen teslim (`verso.outbox.eager-delivery`, varsayılan açık):** yazan istek, transaction commit olunca az önce yazdığı yükü sanal bir iş parçacığında alıcıya verir ve yalnız bu kaydı en çok `eager-timeout` (3 sn) bekler; outbox satırını o iş parçacığı, istek bağlantısını bıraktıktan sonra siler (silemediğini poll tekrar teslim eder, alıcıda no-op). Teslim modül havuzundan ikinci bir bağlantı istemez: istek kendi bağlantısını tutarken aynı havuzdan ikincisini istemek küçük havuzu eşzamanlı isteklerde aç bırakıyordu (CI, `ConversationApiTest#feedback_whenRatedConcurrently_countsOnce` 503); böylece denetim satırı yanıt gönderildiğinde vardır (ADR-0026'daki davranış). Bitmiş transaction'ın bağlantısı `afterCompletion` içinde hâlâ iş parçacığına bağlı olduğu için teslim ayrı bir iş parçacığında koşar. Bir hatadan sonra `eager-pause` (10 sn) boyunca hiçbir istek denemez (kesinti istek başına bir bekleme değil, bir kez). Kaçırılan her şeyi poll alır.
7. **Saklama:** teslim edilen satır silinir (arşiv yok, referans 11.2). `DEAD` satırlar `verso.outbox.dead-retention` (30 gün) sonra `purgeDead` ile silinir; alarm ilk satırda çalar.
8. **Gözlem:** `outbox_oldest_pending_age_seconds{outbox}` (yaş, kazıma anında son görülen en eski satırdan hesaplanır: relay ya da veritabanı durursa büyümeye devam eder), `outbox_dead_rows{outbox}`, `outbox_relayed_total{outbox,outcome}`. Alarmlar `OutboxOldestPendingTooOld` (> 120 sn, 5 dk, page) ve `OutboxDeadRows` (> 0, page), runbook `docs/runbooks/outbox-stuck.md`. Kimlik yakalanamazsa (kimliksiz yol, kod hatası) `verso.audit.events.not.stored{reason="capture_failed"}` ve mevcut `AuditEventNotStored` alarmı.
9. **Doğrudan yol kalır:** erişim reddi (`AccessDeniedAuditListener`), `privacy` başvuruları ve `mcp` araç çağrıları (ADR-0031; ikisinin de şeması yok, katılacak iş transaction'ı yok) ve geçmişe yazılamamış soru (qa veritabanı arızalı) `AuditRecorder` ile eskisi gibi yazılır; her doğrudan satıra rastgele bir `event_id` verilir.

**Havuz: seçenek (b).** `document-core` `DocumentPersistenceConfiguration` havuzu açıkça kurar (`verso-document-db`, `verso.document.datasource.*`, tembel bağlantı `LazyConnectionDataSourceProxy` ile Boot 4.1'in `connection-fetch: lazy`'si gibi, Hikari metrikleri, ilk bağlantısız başlangıç) ve `DocumentMigrationConfiguration` + `DocumentSchemaMigration` kendi Flyway'ini (`verso.document.flyway.*`, `spring.flyway.enabled` tüm modül migration'larının ortak anahtarı olarak kalır). `application.yml` Boot'un `DataSourceAutoConfiguration` ve `FlywayAutoConfiguration`'ını dışlar. **Varsayılan neden kalıyor, neden açık:** Spring'in JDBC desteği (`JdbcClient`, `JdbcTemplate`, `TransactionTemplate`/`@Transactional`'ın transaction manager'ı) ve hazırlık grubundaki `db` katkısı tam bir `DataSource` bean'i ister ve bunları kullanan tek modül `document`'tır. Uygulamanın tek `DataSource` bean'i bu yüzden açıkça `document` havuzudur; başka "varsayılan" bağlantı yoktur. `qa` ve `audit` havuzları bean değildir, tip ile enjekte edilemez. Kullanıcı davranışı değişmez (aynı rol, aynı boyut 10, aynı zaman aşımları). Havuz her uygulama tipinde kurulur (Boot'unki gibi; web dışı test context'leri kullanır); `migrate` servisi onu `--verso.document.datasource.enabled=false` ile kapatır (eskiden `DataSourceAutoConfiguration`'ı dışlıyordu), yalnız üç modül migration'ı çalışır. Hazırlık grubundaki `db` artık modülün kendi `dbHealthIndicator`'ıdır (`DocumentHealthConfiguration`, `qaDb` gibi): Boot'un katkısı yalnız bir DataSource bean'i varken oluşurdu ve grup her uygulama tipinde doğrulanır. Restore provası (`scripts/restore-drill.sh`, `deploy/backup/restore-check.sh`, `restore-flyway*`) Spring anahtarlarına değil rollere ve migration klasörlerine dayanır; değişiklik gerekmedi, yeni `outbox_event` tabloları da uygulama rolünce okunabilir olduğu için kontrolden geçer.

**Süreç rolü:** `verso.ingestion.enabled` (`VERSO_INGESTION_ENABLED`, varsayılan `true`). `false` iken `@ConditionalOnIngestion` taşıyan bean'ler (worker, ingestion transaction servisi ve repository'si, ayıklayıcılar, docling istemcisi, parçalayıcı, ingestion metrikleri) hiç oluşmaz; yükleme kabul edilir ve `PENDING` kalır. Outbox relay bu anahtarı izlemez: her süreç outbox satırı yazar. `verso.document.ingestion.enabled`'dan farkı: o bean'leri bırakıp yalnız zamanlanmış poll'u durdurur (testler worker'ı kendisi sürer).

## Sonuçlar

- **Olumlu:**
  - Commit ile denetim satırı arasındaki kayıp penceresi kapandı: kayıt iş değişikliğiyle aynı transaction'da; süreç ölürse yeniden başlayan relay teslim eder, kira dolmuş yarım teslim tek satır üretir (`AuditOutboxTest`).
  - Denetim veritabanı kesintisi artık kayıp değil gecikme: kayıtlar bekler, yaş metriği ve alarm görünür kılar (`AuditFailureIsolationTest` yeniden yazıldı).
  - Üç modül şemasının üçü de kendi havuzunu ve Flyway'ini açıkça kurar; Boot'un örtük varsayılanı yok.
  - Aynı imaj API-only çalışabilir; ayrı ingestion süreci bir compose servisi işidir.
- **Olumsuz / kabul edilen risk:**
  - **`audit.event.occurred_at` teslim anıdır** (veritabanı saati, ADR-0026 kuralı korundu: uygulama zamanı göndermez). Normalde commit'ten milisaniyeler sonra; bir kesintide biriken kayıtların zamanı kesintinin bitişine kayar. Runbook kesinti aralığını olay kaydına yazdırır.
  - **Sıcak yolda ek yazım:** soru başına qa havuzunda bir outbox INSERT'i (turun transaction'ında) ve commit sonrası claim UPDATE + denetim INSERT'i + outbox DELETE (en çok 3 sn bekleme; kesintide 10 sn hiç). Uzak çağrı sayısı değişmedi (2).
  - **Outbox INSERT'i başarısızsa iş değişikliği de geri alınır** (outbox'ın anlamı). Aynı şema, aynı bağlantı: pratikte iş yazımı da başarısız olurdu.
  - **Yönetici metrikleri görüntüleme** kendi transaction'ında yazılır; outbox yazılamazsa okuma yine döner, kayıp `reason="failed"` ile sayılır.
  - **`privacy` ve erişim reddi** doğrudan yolda kalır (kayıp penceresi onlar için sürer); `privacy`'nin şeması yoktur, bir outbox için ADR gerekir.
  - **Teslim sırası garanti değil** (denetim kaydı sıra istemez; sorgu `occurred_at`'e göredir).
  - **Bağlantı bütçesi değişmedi** (22 / 100): relay modülün kendi havuzunu kullanır, havuz eklenmedi.
- **Etkilenen dosyalar:**
  - `platform-core`: `outbox/{OutboxStore,OutboxRelay,OutboxProperties}`, `pom.xml` (`spring-jdbc`, `micrometer-core`).
  - `audit-api`: `AuditTrail`, `dto/AuditRecord`. `audit-core`: `service/impl/AuditTrailServiceImpl`, `repository/AuditEventRepository` (`insertIfAbsent`, `event_id`), `service/AuditRecorder` (doğrudan yol, rastgele `event_id`), `db/migration/audit/V3__event_delivery_key.sql`.
  - `document-core`: `config/{DocumentPersistenceConfiguration,DocumentDatabaseProperties,DocumentFlywayProperties,DocumentMigrationConfiguration,DocumentSchemaMigration,DocumentOutboxConfiguration,ConditionalOnIngestion}`, `outbox/{DocumentOutbox,DocumentAuditOutbox,DocumentAuditOutboxHandler}`, `worker/DocumentOutboxRelayJob`, `service/impl/{DocumentServiceImpl,CollectionServiceImpl}`, worker bean'leri ve `IngestionRepository` (`@ConditionalOnIngestion`), `db/migration/document/V5__outbox_event.sql`, `pom.xml` (`flyway-core`).
  - `qa-core`: `config/QaOutboxConfiguration`, `outbox/{QaOutbox,QaAuditOutboxHandler}`, `worker/QaOutboxRelayJob`, `service/QaAudit`, `service/impl/{ConversationServiceImpl,FeedbackServiceImpl}`, `controller/ConversationController`, `repository/QaDatabase` (`inTransactionNow`), `db/migration/qa/V3__outbox_event.sql`.
  - Config: `application.yml` (auto-config dışlama), `config/verso.yml` (`verso.document.datasource/flyway`, `verso.outbox.*`, `verso.ingestion.enabled`), `application-local.yml`, `compose.yaml` (`migrate` komutu, `VERSO_INGESTION_ENABLED`), `deploy/prod.env.example`, `.env.example`.
  - Gözlem: `deploy/obs/prometheus/{alerts,alerts.test}.yml`, `docs/runbooks/{outbox-stuck,README}.md`.
  - Testler: yeni `OutboxRelayTest`, `AuditTrailServiceImplTest`, `IngestionRoleTest`, `AuditOutboxTest`, `DocumentDataSourceTest`, `IngestionDisabledTest`; güncellenen `AuditFailureIsolationTest`, `AuditArchitectureTest`, `ConfigDriftTest`, `ConfigProfilesTest`, `MigrateModeTest`, `DeployConfigTest`, `support/{VersoPostgres,VersoTestEnvironment,AuditTables}`.
- **Geri alma yolu:**
  - Önceki imaja dönmek yeterlidir: eski imaj `outbox_event` tablolarını ve `event_id` sütununu yok sayar. Dönmeden önce outbox'ların boşaldığını gör (`outbox_oldest_pending_age_seconds` = 0); bekleyen satır eski imajda teslim edilmez.
  - Tablolar ve sütun yeni bir migration ile kaldırılır (`DROP TABLE <şema>.outbox_event`, `DROP INDEX audit.uq_event_event_id`, `ALTER TABLE audit.event DROP COLUMN event_id`); base'e girmiş V dosyalarına dokunulmaz.
  - Config: `verso.document.datasource/flyway` yerine eski `spring.datasource/flyway` anahtarları ve `spring.autoconfigure.exclude` satırının kaldırılması.

## Yeniden değerlendirme koşulu

- Outbox'a denetim dışı bir olay türü gelirse: lane'ler (referans 11.2 "iş türü izolasyonu"), `priority`, ve Modulith registry ile "dışa gönderimin tek kaynağı" kararı.
- `privacy` bir şema alırsa ya da erişim reddi kaydının kaybı kabul edilemez olursa: doğrudan yolun da outbox'a taşınması.
- Ingestion ayrı süreç olursa: `verso-ingest` compose servisi (`VERSO_INGESTION_ENABLED=true`, API'de `false`), relay her iki süreçte.
- `outbox_event` satır sayısı günde milyonu geçerse: `created_at` bölümleme (referans 10.6; bugün beklenmiyor).
