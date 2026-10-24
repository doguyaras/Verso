# ADR-0026 doğrulaması: değiştirilemez denetim kaydı (`audit` şeması)

Tarih 2026-10-24. Dal `feature/doguyaras-denetim-kaydi`, taban `origin/develop` `c819046`. Çalışan docker yığınına dokunulmadı; gerçek veritabanı testleri Testcontainers'ın kendi PostgreSQL container'ında koştu.

## Makine kontrolleri

| Kontrol | Sonuç |
|---|---|
| `./mvnw -B -ntp verify` (JDK 25) | BUILD SUCCESS. platform-observability 39, platform-core 44, platform-security 42, document-api 4, audit-api 6, document-core 75, audit-core 18, qa-api 4, qa-core 87, verso-app 258 test; hata 0 |
| `node scripts/flyway-immutability.js check --base origin/develop` | `flyway-immutability: OK (base=origin/develop, merge-base=c81904606b6d)` |
| `node --test` (flyway-immutability, config-lint, review-gate, repo-hygiene, panel) | 118/118 |
| `node scripts/config-lint.js` (`verso.yml`, `application.yml`, `application-local.yml`, `prod.env.example`, `.env.example`) | `OK (5 dosya)` |
| `docker compose config -q` | hatasız |
| `ArchitectureRulesTest`, `ConfigDriftTest`, `ErrorCodeUniquenessTest`, `MigrationConventionsTest`, `ModuleConsistencyTest`, `ModuleStructureTest`, `TransactionBoundaryRulesTest` | yeşil (verify içinde) |

Çalıştırılmayanlar:
- **promtool:** `alerts.test.yml`'e eklenen iki `AuditEventNotStored` senaryosu Docker gerektirdiği için koşmadı. CI'daki `scripts/obs-smoke.sh` koşturur (beklenen kural sayısı 7'den 8'e çıkarıldı).
- **Restore provası:** `restore-flyway-audit` ve `restore-check.sh`'nin modül döngüsü çalışan yığın gerektirir. Haftalık iş ve `restore-drill.yml` koşturur; `ModuleConsistencyTest` adımın varlığını ve rolünü doğrular.
- **Gerçek bir eski volume'de `40-modules.sh` yükseltmesi:** audit modülüne özel bir "eski volume" testi yok. Betik her modül için ortaktır ve `QaSchemaRolesTest` aynı yolu bir deneme modülüyle ve var olan rollerle sınar.

## Yeni ve değişen testler

| Test | Ne kanıtlar |
|---|---|
| `AuditSchemaRolesTest` (6) | `svc_audit` zaman aşımları ve `search_path`; uygulama rolü gerçek girişle `UPDATE`, `DELETE`, `TRUNCATE`, `DROP`, DDL için `42501` alır, `INSERT`/`SELECT` yapar; şemalar arası her yönde `42501`; tablo CHECK'leri cümleyi, IdP grubunu, adresi ve bilinmeyen modu reddeder; `audit.purge_expired`: yakın sınır, boş sınır, büyük parça `22023`, `verso_backup` ve migrate rolü `42501`, geçerli çağrı yalnız eski satırı siler; `id` UUIDv7 ve zaman veritabanından |
| `AuditApiTest` (7) | Rolsüz 403 ve kimliksiz 401 (iki uçta), `verso-operator` 403; pencere, filtre, sayfalama ve en yeni önce; `size` 201/0/-1, `page` 10001, bilinmeyen `action`, ters ve 367 günlük aralık 400 (12001); CSV başlıkları (`no-store`, `Content-Disposition`), sıra ve pencere; satır sınırı ve `X-Audit-Export-Truncated`; CSV enjeksiyonu (`= + - @` ve sekme önek alır); reddedilen çağrı `ACCESS_DENIED` olarak yol ve parametre olmadan kaydedilir, 401 kaydedilmez |
| `AuditRecordingTest` (6) | Belge yükleme/silme, soru (tur, konuşma, model, mod, atıflı belge, trace id yanıt başlığıyla aynı), geri bildirim, konuşma silme, hepsini silme, yönetici metrik ve dışa aktarım, 403 kaydı; 404 ve tekrarlanan yükleme satır üretmez; **log privacy:** tabloda ve hiçbir log satırında soru, cevap, başlık, dosya adı, `127.0.0.1`, user-agent yok |
| `AuditFailureIsolationTest` (1) | Gerçek kesinti (`ALTER ROLE svc_audit NOLOGIN` + oturum sonlandırma): yükleme 201, soru 200, silme 204, metrik 200; dört satırın hepsi sayaçta (`failed` + `circuit_open`); log yalnız `code=AUDIT_WRITE_FAILED exceptionType=`, yığın ve istisna mesajı yok; geri gelince satır yazılır |
| `AuditRetentionTest` (2) | 365 günden eski satırlar 2'lik parçalarla silinir, 364/31 günlük ve yeni satır kalır, ikinci çalışma 0; başka instance kilidi tutarken tur atlanır, sonraki tur siler |
| `AuditRecorderTest` (5) | Başarısız yazma sayılır ve fırlatılmaz; breaker penceresinde veritabanı denenmez; pencere dolunca yeniden denenir; doğrulanmış aktör yoksa `invalid` ve yazma yok; geçersiz mod açılışta reddedilir |
| `AuditArchitectureTest` (4), `AuditApiContractTest` (6), `AuditCsvTest` (4), `AuditPropertiesTest` (3), `AccessDeniedAuditListenerTest` (2) | Katmanlar ve başka modüle bağımlılık yok; SQL yalnız repository'de; kaynakta `UPDATE/DELETE/TRUNCATE audit.` yok; olayın serbest metin alanı yok ve sembolik olmayan token reddedilir; CSV kaçışı ve alıntılama; saklama 30–3650 ve parola `toString`'de yok; yalnız `/v1/admin` denetleyicileri kaydedilir |
| `ConfigDriftTest`, `ComposeConfigTest` (alarm sayısı 8), `DeployConfigTest`, `MigrateModeTest`, `ModuleConsistencyTest`, `ModuleStructureTest`, `ErrorCodeUniquenessTest` (12000–12999), `DatabaseRolesTest`, `DocumentArchitectureTest`, `QaArchitectureTest` | Üçüncü modülün config, secret, migrate, rol ve blok yüzeyleri |

## Review skill'leri (her biri bir kez)

| Skill | Karar | Not |
|---|---|---|
| `verso-security-review` | APPROVE WITH NON-BLOCKING COMMENTS | MEDIUM: 403 şişirmesi ve 30 günden eski kaydı silebilen ele geçirilmiş uygulama; ikisi de ADR-0026'ya yazıldı |
| `verso-db-migration-review` | APPROVE WITH NON-BLOCKING COMMENTS | `DELETE` ile saklama gerekçeli sapma (ADR-0026 madde 2); autovacuum ve BRIN değerlendirmesi LOW |
| `verso-spring-code-review` | APPROVE WITH NON-BLOCKING COMMENTS | `AuditQueryService` arayüz + `service/impl` olarak düzeltildi |
| `verso-environment-impact-review` | REQUEST CHANGES → düzeltildi | `scripts/obs-smoke.sh` (7 kural), `docs/runbooks/README.md`, repo-context alarm sayısı eksikti; üçü de güncellendi, `ComposeConfigTest` aynı sayıyı da yakaladı |
| `verso-test-writer` | PASS | Eksik olan `AuditRecorder` birim testi eklendi |
| `verso-api-contract-review` | APPROVE | Breaking değişiklik yok; mevcut uçlar değişmedi |

## Bilinen sınırlar

- Commit ile satır arasındaki milisaniyelik pencerede süreç ölürse satır kaybolur ve sayaçta görünmez (ADR-0026 madde 5 ve Sonuçlar).
- Tablo sahibi ve süper kullanıcı satırı değiştirebilir; hash zinciri ve dış arşiv yok.
- Yeniden indeksleme için uç olmadığından eylem yok. Panel ekranı ve syslog/JSON sink kapsam dışı.
- Hesap kimliği (`sub`) kişisel veridir; saklama süresi ve aydınlatma metni veri sorumlusuna aittir. Bu belge hukuki tavsiye değildir.
