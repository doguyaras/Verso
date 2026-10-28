# Model geçidi: doğrulama (ADR-0056)

- **Tarih:** 2026-10-28
- **Durum:** Derleme, birim testleri, mimari ve yapılandırma testleri ve panel testleri yerelde yeşil. **Testcontainers'lı API testleri yerelde koşturulmadı** (aynı makinede başka bir ajan Docker yığınında ölçüm yapıyordu; konteyner başlatılmadı); GitHub CI'da koşar. Gerçek sağlayıcılarla (OpenAI, Azure, Anthropic, Bedrock, Vertex, Mistral) canlı deneme yapılmadı: istemciler sahte sunuculara karşı ve kurulum düzeyinde sınandı.

## Yapılanlar (lokal, Docker yok)

| Kontrol | Komut | Sonuç |
|---|---|---|
| Derleme (enforcer, yakınsama dahil) | `./mvnw -B -ntp -T 1 -DskipTests install` | başarılı |
| qa birim testleri | `./mvnw -B -ntp -T 1 -pl services/qa/qa-core test` | yeşil; yeni: `EnvelopeCipherTest` (gidiş-dönüş, kurcalama, başka bağlam/sürüm/KEK, KEK'in ortam değişkeninden reddi), `ModelKeyJsonTest` (Jackson: anahtar okunur, yazılmaz; yanıtta ve `toString`'de yok), `RoutedChatModelTest` (5xx/429'da yedek, 401'de durma, bütçe atlama ve 429, akışta ilk token'dan önce yedek, süzgeç yalnız dış çağrıda, veri türleri), `ProviderFactoryTest` (8 türün yerel Spring AI istemcisi, local modda bulut istemcisi kurulmaz, açılış reddi, form kuralları), `ModelGatewayTest` (rota, local modda dış bağlantı düşer, kullanıcı seçimi, bütçe yalnız fiyatlı modelde, maliyet, ayda bir bildirim) |
| Mimari ve yapılandırma | `ArchitectureRulesTest`, `QaArchitectureTest`, `ConfigDriftTest`, `ErrorCodeUniquenessTest`, `ComposeConfigTest`, `TransactionBoundaryRulesTest`, `ModuleStructureTest`, `ModuleConsistencyTest`, `DeployConfigTest`, `MigrationConventionsTest` | yeşil |
| Migration | `node scripts/flyway-immutability.js check --base origin/develop`, `node scripts/migration-contract.js check --base origin/develop` | OK |
| Panel ve script'ler | `GITLEAKS=… node --test scripts/*.test.js scripts/panel.test.mjs` | yeşil; yeni `providers-panel.test.js` (rol, türler, istek gövdesi, anahtar yalnız son dört karakter, rota, kullanım kartı, Güven yolları, model seçimi, `innerHTML`/satır içi stil yok) |

## CI'da (Testcontainers, sahte sağlayıcı sunucuları)

- `ModelGatewayApiTest` (cloud mod; sahte OpenAI uyumlu ve Anthropic sunucusu): kayıt doğrulaması ve anahtarın yalnız sağlayıcıya gitmesi; satırda düz metin yok (bayt araması), liste ve yanıtta yok; 401'de `422 11095` ve kayıt yok; cevap rotasında 503 ve 429'da yedeğe geçiş ve token sayımı (`model_usage`, dış çağrıda veri türleri ve `PASS_THROUGH`); döndürmede yeni anahtarın doğrulanması, `keyVersion` 2 ve sonraki çağrıda yeni anahtar; üst sınır 0'da `429 11003` ve sağlayıcıya istek yok; kullanıcı seçimi kapalıyken `400`, açıkken seçilen model önce; Güven ekranında bağlantı ve host; 12 yönetici ucunda kullanıcı ve operatöre `403`, kimliksize `401`; embedding rotası reddi; denetim satırları yalnız kimlik ve sabit sözcük; log'da anahtar ve soru metni yok.
- `ModelGatewayLocalModeTest` (local mod): 6 bulut türü açık ya da kapalı `409 11091` ve sağlayıcı çağrısı yok; iç ağ dışı OpenAI uyumlu adres `409`; 127.0.0.1'deki sunucu doğrulanır, `LOCAL` olarak kaydedilir ve yönlendirilir.

## Review sonuçları (her skill bir kez)

| Skill | Karar | Düzeltilen |
|---|---|---|
| verso-security-review | APPROVE WITH NON-BLOCKING COMMENTS (düzeltmelerden sonra) | B1 HIGH: SDK log anahtarları yalnız bulut yerleşik sağlayıcıda reddediliyordu, geçidin kurduğu SDK'lar istemleri loglayabilirdi → her modda red (`AiModeCheckTest`). B2 MEDIUM: yönetici formu metadata servisini (169.254.169.254) yoklayabilirdi → link-local/belirtilmemiş adres kayıtta ve her istekte red (`ProviderFactoryTest`) |
| verso-llm-review | APPROVE WITH NON-BLOCKING COMMENTS | L1: zaman aşımıyla kesilen çağrı yedeğe geçip ikinci sağlayıcıya veri gönderebiliyordu → kesintide rota durur (`RoutedChatModelTest`). Açık: cevaptaki `model` alanı yerleşik model adı (ADR) |
| verso-environment-impact-review | APPROVE WITH NON-BLOCKING COMMENTS | – (`docker compose config` geçerli; yeni metrik için alarm eklenmedi, alarm sayısı 15) |
| verso-db-migration-review | APPROVE WITH NON-BLOCKING COMMENTS | – (`model_usage` büyümesi: çağrı başına bir satır, 13 ay saklama, DELETE ile; bölümleme gerekirse sonra) |
| verso-api-contract-review | APPROVE WITH NON-BLOCKING COMMENTS | `@PathVariable`/`@RequestParam` adları açık yazıldı |
| verso-spring-code-review | APPROVE WITH NON-BLOCKING COMMENTS | – |
| verso-resilience-review | APPROVE WITH NON-BLOCKING COMMENTS | R1: ayar yeniden okunurken bütün sorular yavaş bir veritabanının arkasında kuyruğa giriyordu → tek iş parçacığı okur, diğerleri son görüntüyle devam eder |
| verso-test-writer | PASS | `ModelGatewayControllerBindingTest` (gerçek MVC binding: geçersiz görev, id ve gövde 400, servis çağrılmaz, 400 anahtarı yansıtmaz) |
| verso-architecture-boundary-review | APPROVE | – (yalnız `qa` şeması, FK'ler şema içi, `ArchitectureRulesTest` ve `QaArchitectureTest` yeşil) |

## Açık kalanlar

- Gerçek sağlayıcı hesaplarıyla canlı deneme (özellikle Bedrock ve Vertex kimlik doğrulaması, Azure dağıtım adresi) ve akışta kullanım bilgisinin (son parça) her sağlayıcıda geldiği.
- Bulut sağlayıcısında zaman aşımında üretimin durduğu ölçülmedi (ADR-0035'teki açık nokta aynen geçerli).
- Google GenAI SDK'sında host kısıtı uygulama içinde yok; güvence ağdır (ADR-0056).
