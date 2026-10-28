# ADR-0057 doğrulaması: beceri kütüphanesi

Tarih: 2026-10-28. Dal: `feature/doguyaras-beceri-kutuphanesi` (taban `develop` `2628365`). Makinede başka bir ölçüm yığını çalıştığı için **yerelde konteyner başlatılmadı**; Testcontainers isteyen testler (ör. `SkillLibraryApiTest`) GitHub CI'da koşar.

## Yerelde koşulanlar

| Komut | Sonuç |
|---|---|
| `./mvnw -B -ntp -T 1 -DskipTests install` | BUILD SUCCESS |
| `./mvnw -B -ntp -T 1 install -pl verso-app -am -Dtest=ArchitectureRulesTest,ConfigDriftTest,ErrorCodeUniquenessTest,ModuleStructureTest,ModuleConsistencyTest,ComposeConfigTest,TransactionBoundaryRulesTest,ConfigProfilesTest,*ContractTest,RepoSkillsTest,SkillRunnerTest,SkillRulesTest,WorkArchitectureTest,InputRulesTest,OutputSchemaTest,PromptAndExportTest,TaskSkillControllerBindingTest,SubjectRequestServiceImplTest,PrivacyArchitectureTest,MigrationConventionsTest …` | BUILD SUCCESS; work-core 50 test, verso-app yapısal testler 45 test, 0 hata |
| `node scripts/flyway-immutability.js check --base origin/develop` | `flyway-immutability: OK (base=origin/develop, merge-base=2628365fe169)` |
| `node scripts/migration-contract.js check --base origin/develop` | `migration-contract: OK (1 yeni migration, base=origin/develop)` |
| `GITLEAKS=… node --test scripts/*.test.js scripts/panel.test.mjs` | 338 test, 338 geçti (panel 143, `skill-library.test.js` 30) |

`node scripts/config-lint.js verso-app/src/main/resources/config/verso.yml verso-app/src/main/resources/application-local.yml`: yml dosyalarında bulgu yok (yeni secret'lar `${SECRET_DB_WORK_*}`, fallback yok).

## Testlerin kanıtladıkları

- **Tanımlar:** `RepoSkillsTest` 10 repo becerisini katı okuyucuyla yükler, kurallardan geçirir, uyum becerisinin etiketini ve yasak ifadelerini, atıf lisanslarını, her becerinin altın setini denetler; her beceriyi istem → stub cevap → ayrıştırma → denetim zincirinden (kaynaklı ve kaynaksız) geçirir; bilinmeyen alanlı dosya başlangıcı durdurur.
- **Kurallar:** `SkillRulesTest` (zorunlu alanlar, değer yazmayan hata satırları, en çok 4 adım, uzunluk tavanları, uyum etiketi, `anthropics/skills` reddi, repo becerisinde kapsam yasağı, `{{id girdisi}}` reddi, rubrik zorunlu boyutları, eğik çizgi tekilliği).
- **Form:** `InputRulesTest` (normalleştirme, zorunluluk, uzunluk, seçenek, kimlik listesi, değer sızdırmayan hatalar, girdi parçalarının ofsetleri).
- **Çıktı:** `OutputSchemaTest` (kod çitli JSON, `değerlendirilemez` yazımları, eksik/var olmayan kanıt, kapalı değerler, satır sınırı, yasak ifade, ADR-0047 sayı/kod dayanağı: ayırıcıdan bağımsız sayı, kullanıcının yazdığı sayı serbest, değerlendirilemez satır denetlenmez).
- **İstem:** `PromptAndExportTest` (ara adım sınırlı not, son adım şemadan sözleşme, ayraç taklidi etkisiz, dosya adı modele gitmez; Markdown kaçışı, uyarı etiketi iki kez).
- **Çalıştırıcı:** `SkillRunnerTest` stub model ve stub retrieval ile (bir onarım, onarım da bozuksa `OUTPUT_INVALID`, `MODEL_TIMEOUT`, retrieval hatasında model çağrısı yok, silinen çalıştırma durur, belge süzgeci, okunamayan atıf sayımı, yuva her durumda bırakılır, log'da girdi/pasaj/çıktı yok).
- **Bağlama:** `TaskSkillControllerBindingTest` gerçek MVC (hesap yalnız token'dan, bozuk kimlik/puan/gövde servise ulaşmaz, kimliksiz 401, dışa aktarma ek dosya ve `nosniff`).
- **Mimari:** `WorkArchitectureTest` (katmanlar, yalnız `document-api`/`audit-api`, Spring AI yalnız `model` paketinde, JDBC yalnız repository'de, başka şemaya SQL yok); `ModuleStructureTest` (Modulith: `work` modülü, `privacy` → `work :: api`).

## CI'da koşacaklar (Testcontainers)

`SkillLibraryApiTest`: çağıranın okuyabildiği belgelerle dayanak (başka alanın pasajı ve dosya adı istemde yok), DRAFT → düzenle (yeni sayı `unverified`) → onayla → puan → Markdown/JSON dışa aktarma → sil; SSE `done`; bir onarım; meşgul modelde 503 + Retry-After; süren çalıştırmada 409 ve yuvanın geri verilmesi; kirası geçen çalıştırmanın `INTERRUPTED` olması; 1 MB üstü gövde 413; yönetici anahtarı; kuruma özel beceri DRAFT → APPROVED → yeni sürüm → arşiv, hazır id'yi alamaz; başkasının çalıştırması 404; yönetici uçlarında 403 + `ACCESS_DENIED`/`ADMIN_TASK_SKILL`, kimliksiz 401; denetim satırları kimlik ve sabit sözcük; log'da girdi ve çıktı işareti yok. Ayrıca `DatabaseRolesTest`, `DeployConfigTest`, `MigrateModeTest` `work` şeması ve rolleriyle.

## Gerçek modelle değerlendirme

Henüz koşulmadı (yığın başka ölçümde). Komut: `VERSO_TOKEN=… node scripts/skill-eval.mjs --base http://localhost:8080 [--judge]`; sonuç `eval/results/skills-<zaman>.json`.
