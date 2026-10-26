# Onaylı bilgi kartı ve altın set: doğrulama (ADR-0048, ADR-0049)

- **Tarih:** 2026-10-26
- **Branch:** `feature/doguyaras-ogrenen-katman-1` (taban `develop` `0ed1ede`)
- **Ortam:** Windows 11, JDK 25.0.4.1, Maven `-T 1`. **Docker çalıştırılmadı** (makine kaynakları): Testcontainers'lı entegrasyon testleri yerelde koşmadı; kapı GitHub CI'dır.

## Yerelde koşanlar

| Komut | Sonuç |
|---|---|
| `./mvnw -B -ntp -T 1 -DskipTests install` | başarılı |
| `./mvnw -B -ntp -T 1 -pl services/qa/qa-core,services/qa/qa-api,services/mcp/mcp-core,services/document/document-core,services/document/document-api,services/audit/audit-api test` | qa-core 187 test, hepsi geçti (yeni: `KnowledgeCardRulesTest` 17, `AnswerMemoryTest` 5, `KnowledgeEvalAlertTest` 3; `QaArchitectureTest` katmanları yeşil); diğer modüller yeşil |
| `./mvnw -B -ntp -T 1 -pl verso-app test -Dtest=ArchitectureRulesTest,ConfigDriftTest,ErrorCodeUniquenessTest,ComposeConfigTest,ModuleConsistencyTest,ModuleStructureTest,TransactionBoundaryRulesTest,ConfigProfilesTest` | 44 test, hepsi geçti (`ComposeConfigTest` alarm sayısı 15) |
| `node scripts/flyway-immutability.js check --base origin/develop` | `flyway-immutability: OK` |
| `node scripts/migration-contract.js check --base origin/develop` | `migration-contract: OK` (V4 yalnız yeni tablo ve REVOKE; DROP/RENAME yok) |
| `GITLEAKS=… node --test scripts/*.test.js scripts/panel.test.mjs` | 244 test, 243 geçti, 1 atlandı, 0 hata (panel: rol matrisi, `knowledge.js` durum makinesi, rozet ve "Onayla" kuralı, API yolları, 11040–11048 Türkçe mesajları, `MEMORY_FOUND` aşama metni, metin-yalnız ekran) |
| `node --test scripts/uyum-paketi.test.js` | 11 test geçti (VKED satırları ve ayar adları yapılandırmayla eşleşiyor) |

## CI'da koşacak entegrasyon testleri (`KnowledgeCardApiTest`, gerçek PostgreSQL + outbox)

| Senaryo | Test |
|---|---|
| Kart yalnız alıntıladığı **bütün** belgeleri okuyan çağırana sunulur (iki alan: ikisi / biri / hiçbiri); eşik altı soru eşleşmez; chat çağrısı yok; akışta `MEMORY_FOUND`, `done.memory`; log'da içerik yok | `card_isServedOnlyToCallersWhoReadEveryCitedDocument` |
| Belge silinince outbox ile `STALE(SOURCE_DELETED)`, sunulmaz, sistem aktörlü denetim satırı, yeniden onay reddi | `deletingACitedDocument_makesTheCardStale_andItIsNotServed` |
| Bağlayıcının yeni sürümü (`replaces`) → `SOURCE_REPLACED`; taşıma → `SOURCE_MOVED` | `replacingOrMovingACitedDocument_makesTheCardStale` |
| Süresi dolan kart sunulmaz, gece bakımında `STALE(EXPIRED)`, yeniden onaylanır ve sunulur | `anExpiredCard_isNotServed_goesStaleNightly_andCanBeVerifiedAgain` |
| 3 farklı kullanıcının 👎'ü (aynı kullanıcı bir kez) → `DISPUTED`, sunulmaz; kullanıcı kimliği yok; KVKK silmesi itirazları siler, sayı kalır | `downRatingsOfDistinctUsers_pastTheThreshold_makeTheCardDisputed` |
| Gecelik kontrol: recall 1,0, kayma 0; kaynak metin kaybolunca kayma 1,0, uyarı, `STALE(SOURCE_CHANGED)`, gösterge 1; model ve embedding çağrısı yok; kilit (canlı kira 409, süresi geçmiş kira devralınır) | `goldenSetCheck_recordsRecallAndDrift_andAlertsWhenSourcesAreLost` |
| Başkasının turu 404, kaynaksız cevap 409, kişisel belge 409, aynı soru 409 | `create_refusesForeignTurnsUnsourcedAnswersPersonalSourcesAndDuplicates` |
| Taslak sunulmaz, ikinci yönetici onaylar, tekrar onay 409, arşiv; altın set; yetkisiz yönetici metni görmez (ADR-0028); denetim satırları | `verifyAndArchive_followTheStateMachine_withAuditRowsOfIdsOnly` |
| Yedi ucun hepsi `verso-user`'a 403 90101, token'sıza 401; geçersiz parametre ve gövde 400 | `everyEndpoint_isAdminOnly` |
| Alarm `KnowledgeRecallDropped` | `deploy/obs/prometheus/alerts.test.yml` (promtool, `scripts/obs-smoke.sh`) |

## Review skill kararları

| Skill | Karar | Not |
|---|---|---|
| verso-security-review | APPROVE WITH NON-BLOCKING COMMENTS | B1 (HIGH, düzeltildi): kaynaklarına grant'i olmayan yönetici kart metnini görüyordu; artık `readable=false` ve metin yok (ADR-0028), testli. Kalan MEDIUM: itiraz takma adı biberli HMAC değil SHA-256 (sub bilinirse yeniden hesaplanabilir; KVKK silmesi buna dayanır, VKED'de yazılı). LOW: elle kontrol eşzamanlı (≤ 1 000 vaka, yalnız yönetici) |
| verso-llm-review | APPROVE WITH NON-BLOCKING COMMENTS | Yeni dış bağlantı yok; içerik log'a gitmiyor (testli); atıflar sunucuda; görünürlük sorgu içinde. MEDIUM: eşik 0,93 ölçülmedi (ADR-0049 yeniden değerlendirme); LOW: bellek açıkken yeniden indeksleme gerektiren okuyucuda bir embedding boşa gider |
| verso-db-migration-review | APPROVE WITH NON-BLOCKING COMMENTS | Yeni tablolar, mevcut veriye dokunmaz. LOW: kart listesinde onay sayısı kart başına sorgu (≤ 100); `scope_collections` yalnız anlık görüntü |
| verso-operation-consistency-review | saga unnecessary; APPROVE (CI kanıtına bağlı) | Commit sonrası tepki: `document.changed` outbox (MANDATORY, aynı işlem), tüketici durum-idempotent (koşullu UPDATE, inbox tablosu yok: kaynağı değişen kart yeniden onaylanamadığı için tekrar teslim zararsız). Davranış kanıtı CI'daki entegrasyon testleri |
| verso-api-contract-review | APPROVE WITH NON-BLOCKING COMMENTS | Ekleyici alanlar sonda; yeni enum değeri (`MEMORY_FOUND`) için bilinmeyen aşama kuralı belgede; OpenAPI farkı yok, elle çıkarıldı |
| verso-spring-code-review | APPROVE WITH NON-BLOCKING COMMENTS | Zamanlama `service` paketinde (`KnowledgeEval`): qa katman kuralı worker'ın servise erişimini yasaklıyor, elle koşu aynı kodu paylaşıyor (ADR-0049) |
| verso-test-writer | PASS (birim), BLOCKED (entegrasyon yerelde; CI) | Kalan boşluk: iki kişi kuralının entegrasyon testi (kural birim testinde), eşzamanlı iki gece koşusu yarışı (kilit DB tekil indeksiyle) |
| verso-architecture-boundary-review | APPROVE | `qa` → `document` yalnız `document-api`; `document-core` qa'yı bilmez (arayüz `document-api`'de) |
| verso-environment-impact-review | APPROVE WITH NON-BLOCKING COMMENTS | İki yeni değişken beş yüzeyde; gece zamanı ve eşikler yalnız `config/verso.yml`'de (iş config'i) |

## Net kanıt bulunamayan alanlar

- Entegrasyon testlerinin sonucu bu belgede yok (Docker kullanılmadı); CI koşusu kanıttır.
- Eşleşme eşiğinin gerçek Türkçe sorularda yanlış eşleşme oranı ölçülmedi.
