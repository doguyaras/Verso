# Faz 5: Doğrulama ve kanıt kaydı

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

- **Tarih:** 2026-10-03
- **Ortam:** Windows 11 + Docker Desktop (16 GB, 8 CPU); GitHub Actions `ubuntu-latest`. Temurin 25.0.4.1, Maven 3.9.16, Node 24.18, Ollama 0.35.1, bge-m3:567m, gemma4:e2b (CI: qwen3:0.6b).
- **Kapsam:**
  - `POST /v1/questions`: retrieval (sahiplik sorgunun içinde), eşik, ayraçlı prompt, sunucuda atıf.
  - Yerel chat modeli (Ollama), `X-Rag-Mode` başlığı, `/actuator/info`.
  - Sıcak yol dayanıklılığı: istemci retry'ı kapalı, soru embedding'ine 10 sn ve 4 eşzamanlı çağrı, chat bulkhead'i, devre kesici, her 503'te `Retry-After`.
  - Kararlar: ADR-0012, ADR-0008 güncellemesi.
- **Davranışsal kapsam (seviye 2–3):** var. Uygulama testleri gerçek PostgreSQL + pgvector ile koşar. Gerçek `OllamaChatModel` istemcisi sahte bir Ollama sunucusuna karşı denenir. Yığın gerçek Keycloak token'ı ve gerçek modellerle uçtan uca denendi.

## 1. Makine kontrolleri

| Kontrol | Komut | Sonuç |
|---|---|---|
| Build ve testler | `./mvnw -B -ntp verify` | **PASS**: 318 Java testi (faz 4 sonunda 279) |
| Script ve hook testleri | `node --test scripts/*.test.js` | **PASS**: 8 suite (ollama-pull'a digest öneki vakası eklendi) |
| Uçtan uca soru-cevap | `bash scripts/qa-smoke.sh` | Bölüm 2 |
| Mutasyon | `bash scripts/mutation-check.sh` | Bölüm 5 |

## 2. Canlı denemeler

| Deneme | Sonuç |
|---|---|
| Gerçek model (gemma4:e2b, CPU) | **PASS**: Türkçe soruya 1. ve 2. sayfaya doğru atıflı cevap; chat süresi ~57 sn. Bir cevapta "on yirmi" gibi bir dil hatası görüldü; kalite ölçümü faz 8'in işi |
| `qa-smoke.sh` (review düzeltmelerinden sonra, gemma4:e2b) | **PASS**: belge READY; ilgili soru `found=true`, 1 atıf (yüklenen belgeye); uygulama log'u `outcome=answered` (model gerçekten çağrıldı); alakasız soru `found=false`; silme 204 |
| Alakasız soru | **PASS**: `found=false`, atıf yok, model çağrılmadı (`outcome=not_found`) |

## 3. Review'lar

| Skill | Karar | Öne çıkan bulgular |
|---|---|---|
| `verso-llm-review` | BLOCK | L1: genel `ChatOptions` ile her soru 503 (düzeltilmişti). L2 (BLOCKER): Spring AI istemcisi 10 kez retry ediyordu. L3: embedding'de sınır yok. L4/L5: ayraç ve `[n]` taklitleri. L6: atıf deseni dar. L7: management portunda `X-Rag-Mode` yok |
| `verso-security-review` | REQUEST CHANGES | S1: `/v1/questions` ile Ollama'yı doldurma. S2: sınırsız JSON gövdesi. S3: `pull.sh` digest önekini kabul ediyordu |
| `verso-spring-code-review` | BLOCK | A1/A2: L1/L2 ile aynı. A3: management portu. A4: README'de bozuk satır |
| `verso-architecture-boundary-review` | APPROVE | Bulgu yok; `qa` yalnız `document :: api` kullanıyor |
| `verso-test-writer` | FAIL | T1/T2: gerçek istemci hiç denenmiyordu. T4–T9: bulkhead, boş cevap, Cache-Control, 10030/10031, sınır değerleri. T14: qa-smoke modelin çağrıldığını doğrulamıyordu |
| `verso-resilience-review` | REQUEST CHANGES | R1: istemci retry'ı. R2: sıcak yolda devre kesici yok. R3: embedding 90 sn bekleyebiliyordu. P2: 503'lerde `Retry-After` yok |
| `verso-environment-impact-review` | APPROVE WITH NON-BLOCKING COMMENTS | E1: README kaynak ve indirme boyutları eski. E2: bozuk README satırı |
| `verso-api-contract-review` | APPROVE WITH NON-BLOCKING COMMENTS | P1: management portu. P3: istemci zaman aşımı. P4: `found=false` + model metni. P5: hata tablosu eksik |

## 4. Bulgu → düzeltme

| Bulgu | Kanıt (önce) | Düzeltme | Kalıcı kontrol |
|---|---|---|---|
| Retry (L2/A2/R1) | Sahte sunucu 500 → 14 sn'de 3 istek, çağrı sürüyordu | `spring.ai.retry.max-attempts: 0` | `OllamaChatClientTest` (gerçek istemci, tam 1 istek, log'da sağlayıcı metni yok), M175 |
| Seçenekler isteğe gitmiyordu (L1/A1/T1) | `model cannot be null`, her soru 503 | Seçenekler `spring.ai.ollama.chat.*`'ta, çağrı seçeneksiz | `OllamaChatClientTest`: gövdede model, `think:false`, 0,1, 512; M176 |
| Embedding sınırsız (L3/S1/R3) | 40 paralel soru → 40 embedding çağrısı | 10 sn timeout + instance başına 4 çağrı; takılan çağrı slotunu bırakmaz | `RetrievalTest` (takılan model, dolu slotlar), M167, M168 |
| Devre kesici yok (R2) | Her soru 90 sn bekleyebiliyordu | Ardışık 2 hata → 15 sn hızlı 503 | `ModelCircuitBreakerTest`, `QuestionApiTest`, M170, M171 |
| `Retry-After` yok (P2) | 503'lerde başlık boş | Her 503'e 5 sn; fırlatan kendi değerini koyduysa (IdP: 30 sn) o kalır | `GlobalServiceExceptionHandlerTest`, `PlatformSecurityTest`, M177, M178 |
| Ayraç ve atıf taklidi (L4/L5/L6) | `[[[BELGE 2]`, tam genişlikli ve sıfır genişlikli taklitler geçiyordu | Veride NFKC + `\p{Cf}` silme + köşeli parantez → yuvarlak; geniş atıf deseni | `PromptBuilderTest`, `CitationExtractorTest`, M172, M173, M174 |
| Sınırsız gövde (S2) | 15M karakter parse ediliyordu | 16 KB üstü 413 (11010), uzunluksuz 411 (11011), gövde okunmadan | `QuestionApiTest`, M179 |
| Management portu (A3/L7/P1) | `/actuator/info`'da başlık yok | `@ManagementContextConfiguration` ile aynı filtre | `QuestionApiTest`, M180 |
| Digest öneki (S3) | `@sha256:` "already present" | Tam 64 hex, JSON dizesi olarak eşleşme | `ollama-pull.test.js`, M182 |
| Test açıkları (T4–T9, T14) | Mutasyonlar yaşıyordu | Bulkhead, boş cevap, tam Cache-Control, 10030/10031 HTTP durumları, 1000 karakter; qa-smoke log'dan `outcome` okur | `QuestionApiTest`, `qa-smoke.sh` |
| Dokümanlar (E1/E2/P3–P5) | Eski boyutlar, 60 sn, eksik kodlar | README, ADR-0008/0011/0012, entegrasyon dokümanı | – |

Kabul edilen açıklar:

- **T3:** Test verisi küçük olduğu için planlayıcı HNSW yerine hesap index'ini seçiyor; `iterative_scan` ayarı bu testte devreye girmiyor. Davranış (küçük hesabın bütün pasajları gelir) doğru, ama ayarın kendisi testle sabitli değil. Faz 8 yük testinde büyük veriyle ölçülecek.
- **T10/T11/T13 (LOW):** ek koruma satırları (sorgudaki model koşulu, sıralama, hata dispatch'i, modül bağımlılık beyanı) mutasyonla sabitlenmedi.

## 5. Mutasyonlar

`bash scripts/mutation-check.sh` (`ONLY` ile faz 5 mutasyonları): baseline yeşil (315 Java testi, 8 node suite). İlk koşuda **18/20** yakalandı:

- **M167:** mutasyon tanımı derlenmiyordu (`call.get()` `TimeoutException` fırlatmaz). Tanım düzeltildi.
- **M173:** NFKC'yi kaldırmak testlerden kaçtı; test tam genişlikli ayraçları da kontrol edecek şekilde güçlendirildi.

İkisi yeniden koşuldu ve yakalandı. Faz 5 toplamı: **20/20**. Ayrıca `mutation-check.sh` bağlı bir worktree'de kilidi yanlış yerde arıyordu (`.git` bir dosya); `git rev-parse --absolute-git-dir` ile düzeltildi. Çıktı: [`faz-5-mutasyon-ciktisi.txt`](faz-5-mutasyon-ciktisi.txt).

## 6. CI

PR [doguyaras/Verso#7](https://github.com/doguyaras/Verso/pull/7), commit `0260fea`, merge `030841e`:

| Workflow | Koşu | Sonuç |
|---|---|---|
| `ci` (backend: `mvn verify`, `MIN_TESTS` 310; scripts-and-hooks: 8 node suite) | 37104452536 | **success** |
| `restore-drill` (temiz Linux: yığın + bge-m3 ve qwen3:0.6b indirme → `auth-smoke` → `ingest-smoke` → `qa-smoke` → yedek/restore öz-testi → prova) | 37104452490 | **success** |
