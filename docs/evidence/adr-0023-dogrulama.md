# ADR-0023 doğrulaması: `qa` şeması, konuşma geçmişi, geri bildirim ve kullanım metrikleri

Tarih 2026-10-24. Dal `feature/doguyaras-gecmis-geri-bildirim`, taban `origin/develop` `a554f87`. Çalışan docker yığınına dokunulmadı; gerçek veritabanı testleri Testcontainers'ın kendi PostgreSQL container'ıyla koştu. Panel bu dalda değişmedi.

## Makine kontrolleri

| Kontrol | Sonuç |
|---|---|
| `./mvnw -B -ntp verify` (JDK 25) | BUILD SUCCESS. platform-observability 39, platform-core 44, platform-security 42, document-api 4, document-core 74, qa-api 4, qa-core 77, verso-app 228 test; hata yok |
| `node scripts/flyway-immutability.js check --base origin/develop` | `flyway-immutability: OK (base=origin/develop, merge-base=a554f87cc226)` |
| `node --test` (flyway-immutability, config-lint, review-gate, repo-hygiene, panel) | 107/107 |
| `node scripts/config-lint.js` (`verso.yml`, `application.yml`, `application-local.yml`, `prod.env.example`, `.env.example`) | `OK (5 dosya)` |
| `ArchitectureRulesTest`, `ConfigDriftTest`, `ErrorCodeUniquenessTest`, `MigrationConventionsTest`, `ModuleStructureTest`, `TransactionBoundaryRulesTest` | yeşil (verify içinde) |

Çalıştırılmayanlar:
- **promtool:** `alerts.test.yml`'e eklenen iki `HistoryNotStored` senaryosu Docker gerektirdiği için koşmadı. CI'daki `scripts/obs-smoke.sh` koşturur.
- **Restore provası:** `restore-flyway-qa` ve `restore-check.sh`'nin modül döngüsü çalışan yığın gerektirir. Haftalık iş ve `restore-drill.yml` koşturur.

## Yeni ve değişen testler

| Test | Ne kanıtlar |
|---|---|
| `QaSchemaRolesTest` | `svc_qa` zaman aşımları ve `search_path`; iki yönde şemalar arası `42501`; `svc_qa` DDL yapamaz; turn ve atıfta `UPDATE` yok, atıf ve puanda `DELETE` yok, sayaçlarda `DELETE` yok; geçmiş tablosu `svc_qa_migrate`'in. `40-modules.sh` canlı sunucuda yeniden koşunca hiçbir şeyi değiştirmez, eksik bir modülü (deneme adı) rolleri, şeması ve varsayılan yetkileriyle ekler, parolayı log'a yazmaz, secret yoksa durur |
| `ConversationApiTest` (19) | Kayıt: atıf referansı, süreler, pasaj metni yok. Liste ve sayfa sınırı, devam, 409 dolu konuşma (model çağrılmadan). IDOR: başka hesap her fiilde 404, kendi turu başka konuşmada 404, başkasının "tümünü sil"i dokunmaz. Yeniden adlandırma ve bidi/kontrol reddi; silme; silinen belgenin atfı işaretli. Puanlama: değiştirme ve sayaç kayması, tutarsız gövdeler 400, bilinmeyen atıf 11023. Log gizliliği; geçmiş prompt'a girmez; yazma hatasında 200, id'ler boş, sayaç artar, yarım kayıt kalmaz. Eşzamanlı 8 puanlama tek sayı; son yer için yarışan iki cevaptan biri saklanır. Arada silinen ya da süresi dolmuş konuşma yeni konuşma açar |
| `AdminMetricsApiTest` (5) | `verso-user` ve `verso-operator` 403, anonim 401. Toplamlar, oranlar, p50/p95, mod, puan ve neden, günlük seri, en çok ve hiç atıf alan belgeler; politika kapalıyken dosya adı ve soru metni yok. Geçmiş silinince sayaçlar kalır, hesap-gün satırları gider. Ters ya da 367 günlük aralık 400. `DocumentCatalog`'un hesaplar arası metotları admin olmayana kapalı |
| `UnansweredQuestionsTest` (3) | Politika açık: farklı yazılmış aynı soru, 3 hesapla normalleştirilmiş tek grup. 2 hesabın (biri 5 kez) sorduğu soru gösterilmez. Bir asker geçmişini silince grup k'nın altına düşer. Belge adları politikayla görünür. Log'da soru metni yok |
| `HistoryRetentionTest` (2) | 90 günlük süre: başlangıcı eski konuşmalar (dün devam edilen dahil) bütün turları, atıfları, puanları ve başlığıyla gider, parçalar 2'li; eski günlerin hesap-gün satırları gider; anonim sayaçlar kalır; ikinci koşu boş. Kilidi başka instance tutarken hiçbir şey silinmez |
| `QaDatabaseUnavailableTest` | `svc_qa` girişi kapatılıp oturumları kesilince geçmiş uçları 503 `99997` + `Retry-After`; soru yine 200, id'ler boş. Giriş açılınca havuz kendiliğinden toparlanır |
| `DeployConfigTest`, `MigrateModeTest` | `verso.yml`'deki `qa` satırları compose gibi beslenince `svc_qa`; migrate modunda `qa` Flyway'i `svc_qa_migrate` olarak koşar, `qa` havuzu açılmaz |
| `ModuleConsistencyTest` | Modül listeleri (`10-roles`, `20-database`, `40-modules`, `restore-check`) aynı ve `document qa`. Her modülün secret'ları, migration klasörü, callback'leri ve kendi restore `validate` adımı var. Her modülün migration'ları aynı kurallardan (`MigrationConventions`) geçer |
| `MigrationConventionsTest` | Kurallar şema ve rol parametreli; `document` kötü fixture'larla; başka şemaya uygulanınca `document` yabancı sayılır |
| `ComposeConfigTest`, `ConfigDriftTest` | `verso-app` yalnız iki uygulama parolasını, `migrate` yalnız iki migration parolasını alır; yeni placeholder'lar env şablonlarında; `qa` url/user anahtarları local ve deploy'da aynı; 7 alarm ve runbook'ları |
| qa-core birim testleri | `QuestionNormalizerTest` (Türkçe küçük harf, NFKC, bidi temizliği), `LatencyBucketsTest`, `HistoryPropertiesTest` (k < 3 ve geçersiz saklama süresi açılmaz, parola `toString`'de yok), `ConversationTitleTest`, `QaArchitectureTest` (SQL yalnız repository'de, uygulama DataSource'u yok) |

## Review'lar

İlk tur, bağımsız ajanlarla:

| Skill | İlk karar | Ana bulgular → yapılan |
|---|---|---|
| `verso-security-review` | APPROVE WITH NON-BLOCKING COMMENTS | B1 başlık saklama süresini aşıyordu → süre konuşmanın başlangıcından. B2 belge adı + günlük sayı bireyi gösterir → `VERSO_SHOW_DOCUMENT_NAMES` (varsayılan kapalı). B3 katalog yetkisi testsiz → test |
| `verso-llm-review` | APPROVE WITH NON-BLOCKING COMMENTS | L1 "geçmiş prompt'a girmez" testi yok → test. L2 metrik yolunda log testi yok → test. L3 silinen belgeden alıntılı cevap → ADR'de yazılı |
| `verso-db-migration-review` | APPROVE WITH NON-BLOCKING COMMENTS | Cascade zaman aşımı → önce turlar parça parça. EvalPlanQual yarışı → `SKIP LOCKED`. `REVOKE DELETE` atıf/puan → eklendi. Keycloak havuzu ve yükseltme sonrası yedek → ADR ve README. Teşhis süre kolonları → amacı yazıldı, kaldı |
| `verso-architecture-boundary-review` | APPROVE | — |
| `verso-operation-consistency-review` | REQUEST CHANGES (doğrulama `BLOCKED`, MEDIUM saklama bulgusu); saga unnecessary | Saklama düzeltildi. Puan sayaçları anahtar sırasıyla (deadlock), FK ihlali 404. Eşzamanlılık testleri eklendi. Aktif kullanıcı üst sınırı ADR'de |
| `verso-spring-code-review` | APPROVE WITH NON-BLOCKING COMMENTS | DB yokken 500 riski → tembel proxy varsayılanları, `initializationFailTimeout=-1`, test. Yazma gecikmesi → 2 sn bekleme. `citedEver` sınırı → `complete`. "Turn stored" logu. Admin metnindeki bidi → normalleştirmede temizlik |
| `verso-api-contract-review` | APPROVE WITH NON-BLOCKING COMMENTS | Kırıcı değişiklik yok. Dönen `conversationId` ve `activeUsersComplete` anlamı → dokümanda. Panelde geçmiş ekranı yok → ADR'de kabul edilen risk, takip işi |
| `verso-environment-impact-review` | APPROVE WITH NON-BLOCKING COMMENTS | Ölü `qa` havuzu görünmüyordu → hazırlıkta `qaDb`, alarm `HistoryNotStored` + runbook. `40-modules.sh` çalıştırılabilir (100755). `purge-initial-delay` bağlandı |
| `verso-test-writer` | FAIL | Yazma hatası, eşzamanlılık, `qa` 503, katalog yetkisi → hepsi test edildi |
| `verso-client-integration-doc` | Doküman üretildi | `docs/api-conversations-integration-v1.md`; soru dokümanına geriye uyumlu ek alanlar. OpenAPI üretimi repoda yok ("OpenAPI doğrulanmadı" damgası) |

İkinci tur (güvenlik, migration, Spring) düzeltmeleri kodda doğruladı. Kararlar: güvenlik **APPROVE**, migration ve Spring **APPROVE WITH NON-BLOCKING COMMENTS**. Kalan LOW maddeler:
- **`purgeEmpty`:** iç seçime `FOR UPDATE SKIP LOCKED` eklendi.
- **Alarmın ilk hatayı kaçırması:** sayaçlar açılışta 0 ile kaydediliyor.
- **Runbook'taki log ipucu:** düzeltildi.
- **Açık kalan (yeniden incelemede kapandı):** puanlarken FK ihlalinin 404'e dönmesi için deterministik test yoktu.

### Bağımsız yeniden inceleme (`ad2b0e3` üzerine)

`verso-operation-consistency-review`, `verso-test-writer`, `verso-security-review` ve `verso-db-migration-review` yazarın bulgularına bakılmadan yeniden koştu.

- **Bulunan kusur (HIGH):** U+0000 içeren bir soru geçerli JSON'dur, ama PostgreSQL `text` bunu kabul etmez. Tur yazımı her seferinde düşüyordu: geçmiş ve sayaçlar soruyu kaybediyor, ERROR log'u ve `reason="failed"` sayacı (`HistoryNotStored` kesinti alarmı) da herhangi bir kullanıcının isteğiyle tetikleniyordu. Soru ve cevaptaki U+0000 artık U+FFFD ile saklanır (`ConversationServiceImpl.storable`; uzunluk ve CHECK'ler değişmez).
  - Regresyon testi: `ConversationApiTest#ask_whenTheQuestionHoldsANulCharacter_storesTheTurnAndRaisesNoFailure`. Düzeltme olmadan kırmızı.
  - Birim testi: `ConversationTitleTest#storable_whenTheTextHoldsNul_replacesItKeepingTheLength`.
- **Açık kalan test kapatıldı:** puanlanırken silinen tur. `ConversationApiTest#feedback_whenTheTurnIsDeletedWhileRated_answers404AndCountsNothing` silmeyi, puanlama kilit beklerken (`pg_stat_activity`) commit eder. Sonuç 404 `11021`; puan satırı, sayaç ve ERROR yok.
- **Ölçüm, bulgu değil:** "tümünü sil" bir parçada 200 konuşma × 200 tur (40 000 tur, 160 000 atıf, 40 000 puan) cascade ile 412 ms'de sildi. 10 sn'lik ifade zaman aşımının çok altında kaldı.

## Bilinen sınırlar

- p50/p95 histogram kovasının üst sınırıdır.
- Panelde geçmiş ve puanlama ekranı yok (takip işi). Sürümden sonra her panel sorusu geçmişe yazılır.
- Var olan bir kurulum `40-modules.sh` çalıştırılmadan yükseltilirse uygulama açılmaz; README "Yükseltme" bölümü adımları verir.
