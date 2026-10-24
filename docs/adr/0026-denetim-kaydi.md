# ADR-0026: Değiştirilemez denetim kaydı (`audit` şeması, uygulama olayları, yönetici sorgusu ve CSV, saklama)

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras (kurumsal yol haritası, maddeler Ö3 ve 4); uygulama ayrıntıları bu ADR'de
- **İlgili eşik (referans Bölüm 24):**
  - `audit.event` tablosunun sunucu RAM'ine yaklaşması (referans 10.6: partition eşiği).
  - Bağlantı sayısının `max_connections × 0,7`'yi geçmesi (bu ADR üçüncü havuzu ekler: 22 / 100).
  - Modulith event registry'nin başka bir nedenle gelmesi (hesap silme saga'sı, uygulama dışı mesaj).

## Bağlam

- **Alıcı kapısı.** Kurumsal ve KVKK satın almasında "kim neyi yaptı" sorusunun cevabı, değiştirilemez bir kayıtla istenir (araştırma notları `enterprise_features.md` madde 4; `pazar_ve_fiyatlandirma.md`: korumalı denetim günlükleri). Bugün yalnız uygulama log'u vardır: saklama süresi, sorgu ve dışa aktarım yoktur, ve log'a kimlik düşürmemek zaten kuraldır (`security-rules.md` bölüm 4).
- **Kural:** denetim tabloları `@Immutable` / `REVOKE UPDATE, DELETE` ile korunur (`security-rules.md` bölüm 7). Hiçbir modül başkasının şemasına yazmaz (AGENTS.md bölüm 4), bu yüzden `@Audited` türü bir aspect ile "ortak tabloya yazım" seçilmedi (`enterprise_features.md` madde 4).
- **ADR-0003 ve ADR-0023'ün bıraktığı karar.** İkisi de "modüller arası ilk olay" ya da "audit kaydı" geldiğinde Modulith event registry için ayrı ADR öngörmüştü. Bu ADR o karardır: olay evet, **registry hayır** (aşağıda).
- **İkinci şemanın deseni hazırdır** (ADR-0023): modül başına şema, iki rol, kendi havuzu ve Flyway'i, `40-modules.sh` yükseltmesi, advisory kilitli saklama işi.

## Seçenekler

**Kayıt yolu:**

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| **A. Süreç içi Spring uygulama olayı (`AuditEvent`), iş işlemi commit edildikten sonra, istek iş parçacığında eşzamanlı dinleyici** | Ek tablo yok; kimlik ve trace id istek bağlamında hazır; satır yanıttan önce yazılmış olur; hata izole edilir | Commit ile satır arasındaki milisaniyelik pencerede süreç ölürse satır kaybolur (sayaç yok, çünkü olay hiç görülmedi) | Düşük |
| B. Spring Modulith event publication registry (`event_publication`) | At-least-once yeniden gönderim | Registry tablosu uygulamanın DataSource'unda (`svc_document`) ya da `qa`'nın bean olmayan havuzunda yaşardı: yeni bir tablo sahipliği sorusu. Asıl nokta: denetim satırı **başka bir rol ve havuzla** yazılır, iş işlemiyle aynı transaction'a girmesi imkânsızdır; registry atomiklik vermez, yalnız yeniden deneme verir. Yeniden deneme de "istek bozulmaz, kayıp görünür olsun" kuralına ters, çünkü sessiz bir birikim yaratır | Orta |
| C. Eşzamansız kuyruk (bounded executor) | İstek gecikmez | Çökmede ve kapanışta kuyruk gider; birikim görünmez; istek bağlamı (güvenlik bağlamı, trace) taşınmalı | Orta |
| D. Aspect / `@Audited` ile doğrudan yazım | Az kod | Modül sınırı: başka şemaya yazım. Reddedildi (`enterprise_features.md` madde 4) | – |

**Tablo biçimi:**

| Seçenek | Artı | Eksi |
|---|---|---|
| **A. Tek tablo, `(occurred_at DESC, id DESC)` indeksi, saklama için `SECURITY DEFINER` fonksiyonu** | Migration kurallarına uyar; basit; uygulama rolünde DELETE yok | `DELETE` ile saklama (şişme, vacuum); referans 10.6 `DROP PARTITION` der |
| B. Aylık declarative partition | `DROP PARTITION` ile saklama | Aşağıdaki gerekçe: bugünkü migration kurallarıyla çalışma zamanında bölüm oluşturulamaz |
| C. `pg_partman` | Hazır bölüm yönetimi | İmajda yok (`pgvector/pgvector:0.8.7-pg18-trixie`, `docs/versions.md`); eklemek yeni bir altyapı bileşenidir. Bölüm yönetimi eklentisinin imajda varlığı için net kanıt bulunamadı |

## Karar

### 1. Modül, şema ve roller

- `audit` modülü: `services/audit/audit-api` (olay ve yanıt DTO'ları, enum'lar) ve `audit-core`. Başka hiçbir uygulama modülüne bağımlı değildir; `document` ve `qa` yalnız `audit :: api`'ye bağımlıdır (Modulith döngüsü olmasın diye `audit`, `document-api`'nin `PageResponse`'una da bağlanmaz; kendi sayfa tipi vardır, JSON aynıdır).
- Şema `audit`, sahibi `svc_audit_migrate`; uygulama rolü `svc_audit`. Rol zaman aşımları diğer modüllerle aynı (10 sn / 3 sn / 60 sn; migration rolünde 10 sn kilit).
- `deploy/postgres/initdb/{10-roles,20-database,40-modules}.sh` ve `deploy/backup/restore-check.sh`: `MODULES=(document qa audit)`. **Var olan volume için yükseltme** ADR-0023'teki gibi `40-modules.sh` ile (idempotent; var olan rolün parolasına dokunmaz). `ModuleConsistencyTest` her modülün secret'ını, migration klasörünü, callback'lerini ve restore adımını denetler.
- **Uygulama tarafı (`audit-core`):** `AuditDatabase` (kendi Hikari havuzu `verso-audit-db`, 4 bağlantı, tembel; bilerek `DataSource`/`JdbcClient`/transaction manager bean'i değil), `AuditSchemaMigration` (bilerek `Flyway` bean'i değil), `spring.flyway.enabled`'ı izler. `AuditArchitectureTest` bağımlılık yönünü, SQL'in yalnız repository'de olduğunu ve kaynak kodda `UPDATE/DELETE/TRUNCATE audit.` bulunmadığını zorlar.
- **Config yüzeyleri birlikte:** `config/verso.yml` (`verso.audit.*`), `application-local.yml`, `compose.yaml` (`verso-app` yalnız `SECRET_DB_AUDIT_PASSWORD`, `migrate` yalnız `SECRET_DB_AUDIT_MIGRATE_PASSWORD`, `VERSO_AUDIT_RETENTION_DAYS`, `restore-flyway-audit`), `deploy/prod.env.example`, `.env.example`, `scripts/{dev-secrets,restore-drill,restore-drill-selftest}.sh`, `secrets/README.md`. `ConfigDriftTest` ve `ComposeConfigTest` güncellendi.
- **Bağlantı bütçesi (instance başına):** uygulama 10 + `qa` 4 + `audit` 4 + migrate anında Flyway 2 + yedek 2 = 22; `max_connections` 100. İkinci instance 18 daha ekler (36 + ... < 70).

### 2. Tablo: `audit.event`

- Alanlar: `id` (uuidv7), `occurred_at` (veritabanının saati; uygulama göndermez), `actor_sub` (token `sub`), `actor_roles` (yalnız `verso-admin` / `verso-user`; CHECK ile; IdP grupları ve başka roller asla), `action`, `outcome` (`SUCCESS` / `DENIED`), `target_type`, `target_id`, `conversation_id`, `detail_code`, `model_id`, `citation_document_ids`, `trace_id`, `mode` (`local` / `cloud`).
- **Hiç saklanmaz:** soru ve cevap metni, konuşma başlığı, dosya adı, IP, user-agent, e-posta, serbest metin. "Ne" yalnız kimliktir. Bu üç katmanda zorlanır:
  1. `AuditEvent` kaydında serbest metin alanı yoktur; `detailCode` `[A-Z0-9_]{1,40}`, `modelId` model kimliği biçimindedir, kurucu dışını reddeder (`AuditApiContractTest`).
  2. Tablo CHECK'leri aynısını yapar: bir yayıncı hatası bile cümle ya da adres sokamaz (`AuditSchemaRolesTest`).
  3. Log'a yalnız eylem ve istisna tipi yazılır (`AuditRecordingTest`, `AuditFailureIsolationTest`).
- `actor_sub` yine de **kişisel veridir**: IdP'de bir kişiye bağlanabilir. Bu yüzden tabloya saklama süresi vardır (aşağıda) ve dışa aktarım yalnız `verso-admin`'edir.
- **Bölümlenmedi, gerekçesiyle.** `MigrationConventions` migration'da `EXECUTE` (dinamik SQL), `GRANT`, `SET search_path` ve `ALTER DEFAULT PRIVILEGES`'ı yasaklar. Aylık bölüm oluşturmak ve eskisini düşürmek çalışma zamanında dinamik SQL ister; migration'a sabit bölümler yazmak ise sonunda tükenir. Uygulama rolüne `CREATE` vermek ya da uygulamaya migrate parolasını vermek AGENTS.md ve ADR-0009'a aykırıdır. `pg_partman` imajda yoktur. Bu yüzden tek tablo ve indeks (`idx_event_occurred`, `idx_event_action_occurred`) seçildi; saklama toplu `DELETE` ile yapılır (referans 10.6 ve db review kuralı `DROP PARTITION` der; sapma bu gerekçeyle).
- **Hacim tahmini (ölçülmedi):** bir satır yaklaşık 250 bayt, iki indeksle yaklaşık 600 bayt. Günde 2 000 soru soran bir kurumda yılda yaklaşık 0,9 milyon satır, yaklaşık 0,5 GB; günde 10 000 soruda yaklaşık 2,5 GB. 365 günlük saklama bunu sınırlar.

### 3. Yetki: yalnız INSERT ve SELECT

- `20-database.sh`'ın varsayılan yetkileri `svc_audit`'e DML verir. `V1__audit_event.sql` `REVOKE UPDATE, DELETE, TRUNCATE ON audit.event FROM svc_audit` yapar. `afterMigrate.sql` ve `afterMigrateError.sql` Flyway geçmiş tablosunu geri alır.
- **Saklamanın tek yolu `audit.purge_expired(cutoff, batch_size)`** (`SECURITY DEFINER`, sahibi migrate rolü). Dar tutuldu:
  - yalnız `svc_audit` oturumu çağırabilir (`session_user`); `PUBLIC` EXECUTE hakkı olsa da `verso_backup` (şemayı görebilir) ve migrate rolünün kendi oturumu reddedilir;
  - **30 günden yeni bir sınırı reddeder** (`22023`): ele geçirilen bir uygulama son 30 günün kaydını silemez, `retention-days` değerini düşürse de;
  - çağrı başına en çok 5 000 satır, en eskiden başlayarak.
- `search_path` fonksiyonda sabitlenmedi (kurallar `SET search_path`'i yasaklar); her nesne şema adıyla yazıldı ve `svc_audit`'in `audit`, `extensions` üzerinde `CREATE` hakkı olmadığı için ad gölgelenemez.
- **Kalan risk (security review):** ele geçirilen uygulama, 30 günden eski bütün kaydı bu fonksiyonla silebilir (`retention-days` ayarını beklemez); yalnız son 30 gün korunur. Bu pencere bir kurcalamayı fark etme süresi olarak seçildi; daha uzun koruma dış arşiv ister (yeniden değerlendirme koşulu).
- **Şişirme:** kimlikli herhangi bir kullanıcı admin ucuna tekrar tekrar gelerek `ACCESS_DENIED` satırı üretebilir (her 403 bir satır). Bugün Verso'da istek hız sınırı yoktur ve soru sormak da satır üretir; sınır 365 günlük saklama ve sayaçlardır. Hız sınırı eklendiğinde (ADR-0010 sonrası) bu uç da kapsanmalıdır.
- **Sınırı:** tablo sahibi (`svc_audit_migrate`) ve süper kullanıcı hâlâ değiştirebilir; migrate parolası yalnız tek seferlik `migrate` container'ındadır. Bu bir **kurcalamaya dayanıklılık** önlemidir, WORM değil. Hash zinciri ve dış imza/arşiv takip işidir (aşağıda).

### 4. Ne kaydedilir

| Eylem | Nerede yayınlanır | Hedef | Ayrıntı |
|---|---|---|---|
| `DOCUMENT_UPLOADED` | `DocumentServiceImpl.upload`, commit sonrası; aynı `X-Idempotency-Key` yinelemesi sayılmaz | `DOCUMENT` + belge id | – |
| `DOCUMENT_DELETED` | `DocumentServiceImpl.delete` | `DOCUMENT` + belge id | – |
| `QUESTION_ASKED` | `QuestionController`, cevap hazır ve tur saklandıktan sonra | `TURN` + tur id (tur saklanamadıysa boş) | konuşma id, cevap türü (`ANSWERED` / `NOT_FOUND` / `UNCITED`), atıf yapılan belge id'leri, model kimliği, cevabın modu |
| `CONVERSATION_DELETED`, `CONVERSATIONS_DELETED_ALL` | `ConversationController` | `CONVERSATION` (+ id) | – |
| `FEEDBACK_GIVEN` | `ConversationController.rate` | `TURN` + tur id | konuşma id, puan (`UP` / `DOWN`) |
| `ADMIN_METRICS_VIEWED` | `AdminMetricsController` | `METRICS` | – |
| `AUDIT_EXPORTED` | `AdminAuditController.export`, akış başlamadan | `AUDIT_LOG` | – |
| `ACCESS_DENIED` | `@PreAuthorize` reddi, yalnız `/v1/admin` altındaki denetleyicilerde | `ADMIN_API` | uç adı sabit sözcük (`ADMIN_METRICS`, `ADMIN_AUDIT`): sınıf adından türer, yol ve parametre asla |

- **Kaydedilmeyenler:** değişiklik yapmayan başarısız işlemler (404, 400), okumalar (liste, detay), 401 (doğrulanmış aktör yok; oturum açma günlüğü IdP'dedir). Kimlik doğrulaması geçmiş ama rolü olmayanın admin ucuna gelmesi 403'tür ve kaydedilir.
- **Yeniden indeksleme:** bugün böyle bir uç yoktur (`DOCUMENT_REINDEX_REQUIRED` yalnız 409 hata kodudur, yeniden indeksleme eylemi kodda yok). `DOCUMENT_REINDEXED` eylemi, uç eklendiği değişiklikle `AuditAction`'a girer; hayali bir eylem eklenmedi.
- **`AUDIT_VIEWED` yok:** her sayfa isteğinde satır üretmek kaydı kendi okunmasıyla şişirirdi; dışa aktarım, veriyi dışarı taşıyan işlem olduğu için kaydedilir.
- 403 yakalama: `AuthorizationEventPublisher` bean'i method security'nin yayınladığı `AuthorizationDeniedEvent`'i açar; platformun güvenlik zinciri değişmez (ADR-0010: son catch-all).

### 5. Eşzamanlı mı, commit sonrası mı

- **Commit sonrası, eşzamanlı.** Yayıncı olayı kendi işlemi commit ettikten sonra yayınlar (servis `TransactionTemplate.execute` döndükten sonra; denetleyici servis döndükten sonra). Dinleyici (`AuditRecorder`) istek iş parçacığında çalışır.
- Neden commit sonrası: işlem geri alınmışsa "yapıldı" kaydı yanlış olurdu. `@TransactionalEventListener(AFTER_COMMIT)` kullanılmadı çünkü `qa`nın işlemleri uygulamanın transaction manager'ında değildir (ADR-0023); açık "commit'ten sonra yayınla" kuralı iki modülde de aynıdır.
- Neden eşzamanlı: güvenlik bağlamı ve trace id istekte hazırdır; satır yanıt dönmeden vardır (test ve denetçi için kesin); eşzamansız kuyruk seçenek C'nin eksilerini getirir. Bedel sınırlıdır: tek `INSERT`, bağlantı beklemesi en çok 1 sn (`connection-timeout`), sorgu zaman aşımı 3 sn.
- **Kesinti maliyeti sınırlıdır:** bir yazma başarısız olunca `verso.audit.breaker-open` (10 sn) boyunca veritabanı denenmez; bir kesinti isteklere istek başına değil, bir kez bekleme maliyeti getirir. JVM'e özgü bu duraklama bilinçlidir (instance başına koruyucu).

### 6. Hata izolasyonu

- Denetim yazımı hiçbir koşulda isteği bozmaz: `AuditRecorder` her `RuntimeException`'ı yakalar, `QaAudit` olay kurma ve yayınlama hatalarını yutar, `DocumentServiceImpl` yalnız UUID taşıyan (kurulamaz-hata vermeyen) olay yayınlar.
- **Görünür kayıp:** `verso.audit.events.not.stored{reason}` (`failed`, `circuit_open`, `invalid`) sayacı ve `AuditEventNotStored` alarmı (**page**: kayıp satırlar geri gelmez, her dakika kalıcı bir boşluktur). Runbook `docs/runbooks/audit-write-failed.md`. Başarılı yazımlar `verso.audit.events{action}` sayacındadır.
- **Log:** tek yapılandırılmış satır, `code=AUDIT_WRITE_FAILED reason=DATABASE action=… exceptionType=…`; istisna mesajı ve yığın yok (kısıt mesajı satırı alıntılayabilir). Kalıp `HistoryNotStored` ve `safeLogReason` ile aynıdır.
- **Hazırlık sağlığına eklenmedi** (`qaDb`'nin tersine): denetim veritabanı çökünce uygulamanın trafikten çıkması, "istek başarılı olur" kararıyla çelişirdi. Yanlış parola ya da eksik şema ilk yazmada sayaçla ve alarmla görünür; `docs/runbooks/audit-write-failed.md` kontrol adımlarını verir.
- Test: `AuditFailureIsolationTest` (gerçek `ALTER ROLE svc_audit NOLOGIN` + oturumları sonlandırma; upload, soru, silme ve metrik 2xx; sayaç; log; geri gelince yeniden yazım).

### 7. Uçlar ve roller

| Uç | Rol | Açıklama |
|---|---|---|
| `GET /v1/admin/audit?from&to&action&page&size` | `verso-admin` (`@PreAuthorize(VersoRoles.HAS_ADMIN)`, ADR-0019) | `from`/`to` dahil takvim günleri (ISO, `verso.audit.time-zone`, varsayılan `Europe/Istanbul`), aralık en çok 366 gün (aşılırsa 400 `AUDIT_RANGE_INVALID` 12001); `action` isteğe bağlı enum; `page` 0–10 000; **`size` 1–200, varsayılan 50** (aşılırsa 400). En yeni önce. `Cache-Control: no-store, private` |
| `GET /v1/admin/audit/export?from&to` | `verso-admin` | CSV (RFC 4180, UTF-8), en eski önce, `Cache-Control: no-store, private`, `Content-Disposition: attachment`. **En çok `verso.audit.export-max-rows` (100 000) satır**; kesiliyorsa akış başlamadan `X-Audit-Export-Truncated: true` (aralığı daralt). Satırlar 1 000'lik keyset gruplarıyla okunur: uzun transaction ve imleç yok. İstek iş parçacığında yazılır (async dispatch'te güvenlik bağlamı kaybolur) |

- **CSV enjeksiyonu:** `=`, `+`, `-`, `@` (ve sekme/CR) ile başlayan her hücrenin başına `'` eklenir (OWASP). Kimlik doğrulayıcının verdiği `sub` dışarıdan etkilenebilen tek alandır; yine de her hücre aynı korumadan geçer (`AuditCsvTest`, `AuditApiTest`).
- Diğer herkes 403 `ACCESS_DENIED` (90101), kimliksiz 401. Hesap kimliği hiçbir yanıtta dışarıdan alınmaz: yanıttaki `actor` kaydın kendi aktörüdür.
- **Panel arayüzü bu ADR'nin kapsamında değildir.**
- İsteğe bağlı **syslog/JSON log sink** kapsam dışıdır. Takip işi: kayıt yazılırken ayrıca yapılandırılmış bir JSON satırı (ya da SIEM'e gönderim) açıp kapatan bir ayar; SIEM'e göndermek ayrıca egress ve ADR-0013 gözden geçirmesi ister.

### 8. Saklama (KVKK)

- **Parametre:** `verso.audit.retention-days`, ortam değişkeni `VERSO_AUDIT_RETENTION_DAYS`. Varsayılan **365 gün**, aralık **30–3650**. Admin ekranından değil ortam değişkeniyle verilir (parametre kataloğu Verso'da yok, ADR-0023).
- **Neden 365:**
  - KVKK md. 4/2-d: veri, amaç için gerekli süre kadar tutulur. Denetim kaydının amacı, yıllık bir denetim döngüsünde (ISO 27001 / SOC 2 türü bir yıllık kanıt dönemi) "kim neyi yaptı"yı gösterebilmektir; 365 gün bir dönemi tam kapsar.
  - Kayıt yalnız kimlik ve sabit sözcük taşır; soru ve cevap içeriği konuşma geçmişinde 90 gün sonra silinir (ADR-0023). Denetim kaydı bunu aşan uzunluktadır, çünkü içerik taşımaz: silinmiş bir turn'e işaret eden `target_id` içeriğe geri bağlanamaz.
  - KVKK md. 12 (veri güvenliği) ve Kişisel Verileri Koruma Kurulu'nun 2018/10 sayılı kararı (özel nitelikli kişisel veri işleyen veri sorumlularının alması gereken önlemler) erişim ve işlemlerin kayıt altına alınmasını bekler. Karar metni repoda yoktur; **kararda bir saklama sayısı geçtiğini ya da 365'in o sayıyla örtüştüğünü iddia etmiyoruz (net kanıt bulunamadı)**. 365 bir **başlangıç ayarıdır**, mevzuat sayısı değildir.
  - Süreyi ve hukuki dayanağı veri sorumlusu kurum belirler (kendi saklama ve imha politikası, gerekirse daha uzun yasal saklama yükümlülüğü: üst sınır 3650). Bu ADR hukuki tavsiye değildir.
  - Alt sınır 30 gün hem yapılandırma doğrulamasında hem veritabanı fonksiyonundadır (madde 3).
- **İş (`AuditRetentionJob`)** ADR-0023'teki kilit deseniyle: her instance zamanlar (`purge-interval` 6 saat, ilk çalışma 10 dk sonra), her toplu silme kendi kısa transaction'ında `pg_try_advisory_xact_lock` altında çalışır (anahtar `audit.retention`); kilidi başka instance tutuyorsa o tur atlanır. Silme idempotenttir. 1 000 satırlık parçalar, çalışma başına en çok 200 parça. Silme `audit.purge_expired` üzerinden yapılır.
- **Yedek:** yedekler `BACKUP_RETENTION_DAYS` (7 gün) daha tutar. Geri yüklenen yedekteki süresi dolmuş satırları bir sonraki çalışma yeniden siler.
- **Kişisel veri envanterine ek:**

| Veri | Nerede | Ne kadar kalır |
|---|---|---|
| Token `sub` (IdP'de kişiye bağlanabilen opak kimlik), roller, eylem, hedef id'ler, zaman, trace id | `audit.event` | `VERSO_AUDIT_RETENTION_DAYS` (365); yedeklerde +7 gün |

- **Silme hakkı:** denetim kaydı, veri güvenliği ve hesap verilebilirlik yükümlülüğü için tutulur; kullanıcının "geçmişimi sil" işlemi (`DELETE /v1/conversations`) denetim satırlarını silmez (içerik zaten yoktur). Aydınlatma metni bu amacı ve süreyi söylemelidir.

### 9. Gözlem

- `deploy/obs/prometheus/alerts.yml`: `AuditEventNotStored` (page) ve `alerts.test.yml` testi. Runbook `docs/runbooks/audit-write-failed.md`.
- Hikaricp metrikleri `verso-audit-db` havuz adıyla çıkar.

## Sonuçlar

- **Olumlu:**
  - Denetçiye "kim neyi yaptı" kaydı: yalnız kimlik ve sabit sözcük; serbest metin tabloya girmez (CHECK).
  - Uygulama rolü gerçek rollerle testli olarak yalnız INSERT/SELECT yapabilir; saklama bile son 30 günü silemez (`AuditSchemaRolesTest`).
  - Denetim arızası istekleri düşürmez ama görünür (sayaç + page alarmı).
  - İlk modüller arası olay, registry olmadan geldi; ileride registry'ye geçiş yalnız yayıncıyı etkiler.
- **Olumsuz / kabul edilen risk:**
  - **Commit ile satır arasında süreç ölürse satır kaybolur** (milisaniyelik pencere) ve bu sayaçta görünmez. Gerçek at-least-once için registry ya da outbox gerekir; "istek bozulmaz, kayıp görünür" kararıyla seçilmedi.
  - **Sıcak yolda ek DB yazımı:** soru başına bir `INSERT` (≤ 1 sn bağlantı beklemesi, kesintide breaker ile istek başına bir kez). Uzak çağrı sayısı değişmedi (2).
  - **Bölümsüz tablo, `DELETE` ile saklama:** şişme ve vacuum yükü; hacim tahmini küçük (madde 2), eşik aşılırsa bölümlemeye geçilir.
  - **Sahip ve süper kullanıcı hâlâ değiştirebilir**; hash zinciri ve dış arşiv yok.
  - **Yeniden indeksleme eylemi yok** (uç yok).
  - **Kapsam dışı:** panel ekranı, syslog/JSON sink, SIEM gönderimi, hesap silme akışı.
  - **Üç havuz:** bağlantı bütçesi 18'den 22'ye çıktı.
- **Etkilenen dosyalar:**
  - `services/audit/audit-api`, `services/audit/audit-core` (`config`, `controller`, `exception`, `repository`, `service`, `worker`, `db/migration/audit`).
  - `document-core` (`DocumentServiceImpl`, `package-info`), `qa-core` (`QaAudit`, `QuestionController`, `ConversationController`, `AdminMetricsController`, `package-info`); kök `pom.xml`, `verso-app/pom.xml`.
  - `deploy/postgres/initdb/{10-roles,20-database,40-modules}.sh`, `deploy/backup/restore-check.sh`, `compose.yaml`, `.env.example`, `deploy/prod.env.example`, `config/verso.yml`, `application-local.yml`, `scripts/{dev-secrets,restore-drill,restore-drill-selftest}.sh`, `secrets/README.md`.
  - Gözlem: `deploy/obs/prometheus/{alerts,alerts.test}.yml`, `docs/runbooks/audit-write-failed.md`.
  - Testler: `AuditSchemaRolesTest`, `AuditApiTest`, `AuditRecordingTest`, `AuditFailureIsolationTest`, `AuditRetentionTest`, `AuditArchitectureTest`, `AuditApiContractTest`, `AuditCsvTest`, `AuditPropertiesTest`, `AccessDeniedAuditListenerTest`; `ConfigDriftTest`, `ComposeConfigTest`, `DeployConfigTest`, `MigrateModeTest`, `ModuleConsistencyTest`, `ModuleStructureTest`, `ErrorCodeUniquenessTest`, `DatabaseRolesTest` güncellendi.
  - Dokümanlar: `docs/api-audit-integration-v1.md`, `README.md`, `docs/decisions.md`, `docs/ai/repo-context.md`, `docs/evidence/adr-0026-dogrulama.md`.
- **Geri alma yolu:**
  - `AuditEvent` yayınları (`DocumentServiceImpl`, `QaAudit`) ve uçlar kaldırılır; `audit` şeması veriyle birlikte kalabilir.
  - Rollerin ve şemanın kaldırılması elle yapılan bir DBA işidir (`DROP SCHEMA audit CASCADE`, `DROP ROLE`), migration değildir.
  - Eklenen config anahtarları ve secret'lar yok sayılır; yanıtlara alan eklenmedi (yeni uçlar).

## Yeniden değerlendirme koşulu

- `audit.event` sunucu RAM'ine yaklaşırsa ya da toplu `DELETE` vacuum yükü ölçülürse: aylık bölüm ve `DROP PARTITION`; bölüm yönetimi için migrate rolüyle çalışan ayrı bir iş ve ADR.
- Modulith event registry başka bir nedenle gelirse (hesap silme saga'sı, uygulama dışı mesaj): denetim yayıncıları `@ApplicationModuleListener` ile at-least-once olur; o zaman `event_id` için UNIQUE eklenir.
- Alıcı değiştirilemezliğin kanıtını isterse: hash zinciri, imzalı dışa aktarım ya da yalnız-ekleme (WORM) bir hedefe kopya; syslog/JSON log sink'i.
- Yeniden indeksleme ya da koleksiyon izin değişikliği uçları eklenirse: yeni `AuditAction` değerleri ve bu ADR'nin tablosu.
- Panel denetim ekranı: ayrı ADR (arayüz bu işin kapsamında değildi).
