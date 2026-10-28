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

## CI'nın ilk koşusunda düzeltilenler (30f78e2)

- `KnowledgeCardApiTest`'te 9 test kırmızıydı. Nedeni test verisiydi: taklit modelin cevabı "on dört" diyordu, test PDF'leri ASCII'ye katlandığı için ("dort") dayanak kontrolü (ADR-0047) cümleyi desteksiz buldu ve tur `UNCITED` oldu. `UNCITED` tur onaylanamaz (11042), yani ürün davranışı doğruydu. Testlerin cevapları artık sayı ve kod içermiyor, yardımcı da önce `ANSWERED` olduğunu doğruluyor.
- `AuditFailureIsolationTest`: silme artık aynı işlemde bir `document.changed` satırı da yazıyor. Denetim veritabanı yokken başarısız teslim, bütün outbox için hemen teslimi duraklatır, bu satır da poll'u bekler. Test denetim satırlarını `event_type` ile sayıyor ve bekleyen değişiklik bildirimini ayrıca doğruluyor; sonunda outbox yine boş.

## Dayanak kontrolünde Türkçe katlama (ADR-0047 düzeltmesi)

- Bulgu ürün hatasıydı: aksanını yitirmiş belgede (bozuk kodlanmış PDF, OCR, ASCII dışa aktarım) "on dort", cevaptaki "on dört"ü desteklemiyordu.
- `AnchorCheck` artık sayı sözcüklerini ve ekleri `ExcerptSelector.fold` ile katlanmış biçimde okur (ı/i, ş/s, ğ/g, ç/c, ö/o, ü/u, Türkçe küçük harf). Kodlar da katlanır (`SAT-Ü1` = `SAT-U1`, `İK-3` = `IK-3`). Komşu terimler zaten katlanıyordu.
- Kesinlik için: cevap cümlesinde aksansız yazılmış bir kök ("ucu" = uç, "dort" tek başına) belirsiz sayılır. Yalnız bileşik sayıda ("on dort") ya da sıra sayısında çapa olur. Pasaj tarafı cömert kalır.
- Testler: `AnchorCheckTest`'e üç yeni test (dört/dort, altı/alti, üç/uc, değişen sayı yine işaretlenir, "ucu" çapa değil, Ü/U ve İ/I kodları). `GroundingEvalTest` (`eval/grounding-set.json`, 192 etiketli cümle, Docker'sız) yeşil: yanlış işaret 0 (kesinlik 1,00 korundu), bulunan desteksiz cümle ≥ 26/57 ve sayıda ≥ 18 eşikleri tuttu. Uçtan uca gerçek model ölçümü (`docs/evidence/dayanaklilik-olcumu.md`) GPU ve Ollama ister, yeniden koşulmadı.

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
- Eşleşme eşiği eval v2 sorularıyla ölçüldü (aşağıda); müşterinin gerçek soru kaydıyla henüz ölçülmedi.

## Eşleşme eşiği ölçümü (2026-10-28, gerçek bge-m3)

- Set: `eval/embedding-olcumu/kart-esik-set.json`, eval v2'nin 11 belgesinden 30 kart kaynak sorusu; her birine 2–3 yeniden ifade (64; kart sunulmalı) ve 1 yakın ıska (30; aynı konu, farklı bilgi, ör. P1 → P3, 0–5 yıl → 5–15 yıl; kart sunulmamalı).
- Embedding: çalışan yığının Ollama'sı, `bge-m3:567m`, öneksiz (soru yolunun vektörü), ağ içinden tek `/api/embed` isteği (geçici curl kapsayıcısı, uygulama yeniden başlatılmadı). Analiz: `node eval/embedding-olcumu/kart-esik.mjs <emb.json>` (en yakın kart, tam kosinüs).
- "Yanlış karta düşen yeniden ifade" bütün eşiklerde 0.

| Eşik | Doğru kart (recall) | Yakın ıska eşleşmesi | Precision |
|---|---|---|---|
| 0,88 | 23/64 (0,359) | 4/30 | 0,852 |
| 0,89 | 21/64 (0,328) | 4/30 | 0,840 |
| 0,90 | 16/64 (0,250) | 3/30 | 0,842 |
| 0,91 | 10/64 (0,156) | 3/30 | 0,769 |
| 0,92 | 9/64 (0,141) | 3/30 | 0,750 |
| 0,93 (eski) | 5/64 (0,078) | 3/30 | 0,625 |
| 0,94 | 4/64 (0,063) | 2/30 | 0,667 |
| **0,95 (yeni)** | **2/64 (0,031)** | **0/30** | **1,000** |
| 0,96 | 2/64 (0,031) | 0/30 | 1,000 |

- Dağılımlar örtüşüyor: yeniden ifade → kendi kartı min 0,601, medyan 0,855, maks 0,966; yakın ıska → en yakın kart medyan 0,813, maks 0,944 (`guvenlik-3` "ne kadar sürede" → "kime"), 0,941 (`izin-1` 0–5 yıl → 5–15 yıl), 0,933 (`bt-1` P1 → P3).
- Karar: varsayılan 0,93 → **0,95** (`QaKnowledgeProperties`, `config/verso.yml`). 0,93 farklı bilgiyi "uzman onaylı" diye sunuyordu; onaylı kartta yanlış cevap, kaçan bir eşleşmeden pahalıdır (kaçan soru normal RAG yoluna gider). Pay dar (0,944 → 0,95); müşteri setinde yeniden ölçülmeli.
- Sonuç: kosinüs tek başına yeniden ifadeyi yakın ıskadan ayıramıyor; kart bugün "aynı ya da neredeyse aynı soru" içindir. Recall gerekirse ikinci kontrol gerekir (ADR-0049 yeniden değerlendirme).
