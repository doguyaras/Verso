# Bilgi sağlığı teşhisi ve kancalar: doğrulama (ADR-0048 faz 2, ADR-0052)

- **Tarih:** 2026-10-26
- **Branch:** `feature/doguyaras-ogrenen-katman-2` (taban `develop` `49f6d9f`)
- **Ortam:** Windows 11, JDK 25.0.4.1, Maven `-T 1`. **Docker çalıştırılmadı** (kullanıcının makinesinde Docker kapalı): Testcontainers'lı entegrasyon testleri yerelde koşmadı; kapı GitHub CI'dır.

## Yerelde koşanlar

| Komut | Sonuç |
|---|---|
| `./mvnw -B -ntp -T 1 -DskipTests install` | başarılı (test derlemesi dahil) |
| `./mvnw -B -ntp -T 1 -pl services/qa/qa-core test` | 229 test, 0 hata. Yeni: `QuestionClustererTest` (sıra ne olursa olsun aynı kümeler, konuların ayrılması, eşik), `DiagnosticsRulesTest` (k < 5 hiçbir ayarla geçmez, `min-askers=4` açılışı reddeder, eğilim, önceki koşuyla eşleşme), `ContradictionCheckTest` (farklı sayı ve kod bulunur; "on dört" = "14", "SAT-Ü1" = "SAT-U1" fark sayılmaz; farklı bağlamdaki sayılar eşleşmez), `HookEngineTest` (eylemler tam olarak NOTIFY/FLAG/DEMOTE/RUN_EVAL; PROMOTE/VERIFY/ARCHIVE/DELETE/GRANT yok; motorun terfi/silme/ACL bağımlılığı yok; bildirim olay başına bir kez ve denetim yalnız kimlikle; düşürme yalnız çelişkide ve yalnız canlı karta; eval isteği yalnız altın vaka belgeyi alıntılıyorsa; bir eylem düşerse diğerleri çalışır ve olay yeniden teslim edilir), `KnowledgeHealthReportTest` (şablonlu Türkçe bulgular, önem sırası, deterministik; Markdown'da ad ve başlık kaçışlanır, okunamayan değer "gizli") |
| `./mvnw -B -ntp -T 1 -pl services/document/document-core,services/qa/qa-api,services/mcp/mcp-core,services/audit/audit-api test` | başarılı |
| `./mvnw -B -ntp -T 1 -pl verso-app test -Dtest=ArchitectureRulesTest,ConfigDriftTest,ErrorCodeUniquenessTest,ComposeConfigTest,ModuleConsistencyTest,ModuleStructureTest,TransactionBoundaryRulesTest` | 39 test geçti (V5 göç kuralları `ModuleConsistencyTest` ile; uyarı sayısı değişmedi, 15) |
| `node scripts/flyway-immutability.js check --base origin/develop` | `flyway-immutability: OK (base=origin/develop, merge-base=49f6d9fee435)` |
| `node scripts/migration-contract.js check --base origin/develop` | `migration-contract: OK (1 yeni migration, base=origin/develop)` (V5'teki iki CHECK değişimi `-- contract:` satırıyla gerekçeli) |
| `GITLEAKS=/c/Users/doguy/.local/bin/gitleaks.exe node --test scripts/*.test.js scripts/panel.test.mjs` | 264 test, 263 geçti, 1 atlandı, 0 hata (panel: rol matrisinde `health`, `health.js` sunucu enum'larıyla sözleşme, dört izinli eylem, çeyrek aralıkları, dışa aktarım adı, zil rozeti, ekranın metin-yalnız ve satır içi stilsiz olması, indirmenin yalnız düğmeyle olması, API yolları, 11060–11063 Türkçe mesajları; uyum paketi: VKED ve BetrVG eki uç ve dosya referansları) |

## CI'da koşacak entegrasyon testleri (`KnowledgeHealthApiTest`, gerçek PostgreSQL + outbox)

- `clusters_needFiveDistinctAskers_carryNoQuestionText_andTrendAcrossRuns`: 5 kişinin sorusu bir küme olur, 4 kişininki (8 soru) hiç görünmez; politika kapalıyken soru metni yok, anahtar sözcükler sorunun sözcükleri; CLUSTER_EMERGED ve ikinci koşuda CLUSTER_GROWING bildirimi qa outbox'ından gelir; vektörler bir kez gömülür; Markdown ve JSON dışa aktarımı ek dosya, `no-store`; denetimde metin yok; log gizliliği.
- `run_whileAnotherInstanceHoldsTheLock_is409_andAnExpiredLeaseIsTakenOver` ve `run_whenTwoInstancesStartAtOnce_onlyOneHoldsTheLock`: koşu satırı kilidi, kiranın devralınması, kirası düşmüş örneğin sonuç yazamaması, eşzamanlı iki koşuda tek `RUNNING`.
- `contradictions_showValuesOnlyToAnAdminWhoReadsBothDocuments`: iki belgeyi okuyan yönetici değerleri ve adları görür; hiçbirini okumayan yalnız kimlik ve sayfa; birini okuyan yalnız o tarafın adını, değer yok; CONTRADICTION_FLAG işareti ve okundu; belge silinince aday silinir.
- `hooks_fireOnADocumentChangeAndADispute_andAdminsSwitchRules`: itiraz → CARD_DISPUTED_FLAG (kullanıcı adı yok); kural kapatma denetlenir; belge silme → kart STALE (kural kapalıyken bildirim yok) ve birleştirilmiş eval isteği; sessiz dönemden sonra `HOOK` koşusu sistem aktörüyle.
- `everyEndpoint_isAdminOnly_andBindingIsChecked`: 8 uç × (kullanıcı 403 `90101`, token'sız 401); 11060 (ters ve 400 günden uzun aralık), 11062, 11063, biçimsiz anahtar, eksik `enabled`, bilinmeyen format 400; koşu yokken `NO_RUN`.

## CI düzeltmesi (6a646b5 sonrası)

- CI'da `KnowledgeHealthApiTest` 3 hata verdi. (1) Anahtar sözcükler Türkçe snowball kökleriydi ("baglantis", ASCII "topic" → "topiç"): artık insanların yazdığı biçim gösterilir (kökün en çok kullanılan biçimi), yalnız tam o sözcüğü ≥ k kişi yazdıysa. (2, 3) Kanca olayları yalnız poll ile teslim ediliyordu; testlerde poll kapalı olduğundan satırlar bekledi ve paylaşılan veritabanında başka bir test bağlamının relay'i onları kendi bağlamında işledi (CI'daki `IllegalStateException`). Kanca olayları artık denetim kayıtları gibi commit sonrası eager teslim edilir, poll emniyet ağıdır.
- Docker açıkken yerelde: `./mvnw -B -ntp -T 1 -pl verso-app test -Dtest=KnowledgeHealthApiTest` 6/6 geçti; `-Dtest=KnowledgeCardApiTest` 9/9 geçti.

## Review skill'leri (her biri bir kez)

| Skill | Karar |
|---|---|
| verso-security-review | APPROVE WITH NON-BLOCKING COMMENTS (LOW: `DocumentDiagnostics.similarChunkPairs` okuyucu süzgeçsiz metin döner; sözleşmede yalnız bellek içi karşılaştırma, yalnız qa çağırır) |
| verso-llm-review | APPROVE (yeni model çağrısı yok; yalnız yerel embedding, işlem dışında; içerik log'a gitmez) |
| verso-db-migration-review | APPROVE WITH NON-BLOCKING COMMENTS (MEDIUM: `idx_turn_uncertain_day` CONCURRENTLY değil, tablo saklama süresiyle sınırlı; LOW: `hook_rule` UPDATE bütün kolonlarda, CHECK sınırı tutar) |
| verso-operation-consistency-review | saga gereksiz; uygulama bulgusu yok; senaryo kanıtı CI'da (yerelde Docker yok) |
| verso-event-design-review | APPROVE WITH NON-BLOCKING COMMENTS (LOW: olay adı `knowledge.hook.triggered` `<servis>.<aggregate>.<olay>` kalıbına tam uymuyor; tek tüketici aynı modül) |
| verso-api-contract-review | APPROVE WITH NON-BLOCKING COMMENTS (ekleyici; `CONTRADICTION` ve `HOOK` enum değerleri panelde; MockMvc bağlama testi yok, gerçek sunucu matrisi var) |
| verso-spring-code-review | iki MEDIUM düzeltildi (dışa aktarım üretimi controller'dan servise; 11060/11061 öncesi `rejected` log satırı); sonra APPROVE WITH NON-BLOCKING COMMENTS |
| verso-test-writer | iki eşzamanlılık testi eklendi; birim ve panel PASS, entegrasyon CI'da |
| verso-architecture-boundary-review | APPROVE (qa yalnız `document-api`; yabancı şema yok) |

## Net kanıt bulunamayan alanlar

- Entegrasyon testlerinin sonucu: CI çalışması bekleniyor.
- Gerçek bir müşteri verisinde küme eşiği (0,80) ve çelişki benzerliği (0,90) ölçülmedi; başlangıç ayarlarıdır.
- OpenAPI farkı üretilmedi (repo'da OpenAPI üretimi yok, ADR-0004).
