# Akışlı cevap ve sayfa görünümü: doğrulama ve kanıt kaydı (ADR-0035)

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

## Künye

- **Tarih:** 2026-10-24
- **Branch:** `feature/doguyaras-akis-sayfa` (`origin/develop` üzerinden).
- **Ortam:** Windows 11, JDK 25.0.4.1, Node 24. **Docker kapalıydı** (makine aşırı yüklenmişti; görev gereği açılmadı): Testcontainers testleri ve compose yığını yerelde koşmadı, GitHub CI'da kapı olarak koşar.
- **Davranışsal kapsam:** sunucu akış mantığı birim testiyle (gerçek boru hattı parçaları, sayan sahte model) ve panel mantığı node testleriyle yerelde koşturuldu. HTTP, veritabanı, nginx ve tarayıcı davranışı yerelde koşturulmadı.

## 1. Yerelde koşturulanlar (Docker'sız)

| Komut | Sonuç |
|---|---|
| `./mvnw -B -ntp -T 1 -DskipTests install` | derleme başarılı (test kaynakları dahil) |
| `./mvnw -pl services/qa/qa-core,services/document/document-core test` | qa-core 105, document-core 95 test, hata yok (yeni `QuestionStreamTest` 11 test) |
| `./mvnw -pl platform/platform-observability,platform/platform-security test` | 39 + 43 test, hata yok (async dispatch kuralı mevcut güvenlik testlerini bozmadı) |
| `./mvnw -pl services/qa/qa-api,services/document/document-api test` | sözleşme testleri (yeni olay kayıtları ve `DocumentPageResponse` record) yeşil |
| `./mvnw -pl verso-app test -Dtest=ComposeConfigTest,ArchitectureRulesTest,TransactionBoundaryRulesTest,ModuleStructureTest,ErrorCodeUniquenessTest,ConfigDriftTest,ImageVersionsTest` | 35 test, hata yok (nginx akış konumu, CSP değişmedi, 10005 tekil) |
| `GITLEAKS=/c/Users/doguy/.local/bin/gitleaks.exe node --test scripts/*.test.js scripts/panel.test.mjs` | 190 test, 0 hata |

`QuestionStreamTest` (birim, gerçek `AnswerabilityGate`, `PromptBuilder`, `CitationExtractor`, `ModelCircuitBreaker`, `QaMetrics`; sahte model kelime sayar ve iptali görür):

- olay sırası `RETRIEVING` → `PASSAGES_FOUND 1/1` → `WRITING` → delta'lar → tamamlandı; delta'ların birleşimi ham model metni, tamamlanan cevapta geçersiz `[8]` yok;
- `[5]` gibi eşlenmeyen işaretle `UNCITED`, boş atıf; kapı kapalıyken `NOT_FOUND`, model çağrısı 0;
- istemci üçüncü delta'da gidince: `cancelled`, model aboneliği iptal, iptalden sonra yeni kelime yok, `history.record` hiç çağrılmadı, tek slot hemen boşaldı (sonraki soru `WRITING`'e ulaştı);
- model hiç yazmadan istemci gidince keep-alive denetiminde iptal (0 kelime);
- 2 sn sınırı aşılınca `11001` / `TIMEOUT` ve model iptali; sağlayıcı hatasında yalnız tip (`IllegalStateException`), mesaj yok;
- JSON yol tek slotu tutarken akış `11002` ile biter, `WRITING` hiç gelmez (ortak bulkhead);
- `WRITING` yazılamazsa (istemci gitti) slot geri verilir, model çağrılmaz; sağlayıcı akışı kurarken fırlatırsa `11001` ve slot geri; retrieval'da veritabanı hatası `99997`.

Panel (`scripts/panel.test.mjs`, ADR-0035 bölümü): SSE ayrıştırıcı her bölme noktasında (CRLF'nin ortası dahil) aynı olayları verir, karakter karakter de; çok satırlı `data`, CR satır sonu, keep-alive yorumları, sonlanmamış son olay atılır; bayt parçalarında çok baytlı harfler bütün kalır. Aşama metinleri; taslakta `[n]` ve yarım işaret gizli; `askLive` token, `Accept`, gövde ve sonuç; `error` olayı → `ApiError` (`retryAfter`), 404 `11020` geri düşmez, abort geri düşmez, JSON dönen proxy / boş akış / 404 `90010` bir kez JSON uca düşer, olaylardan sonra kopma `TypeError`. Sayfa gezinmesi sınırlar içinde, başlık sayfa/bölüm, alıntı satır sonları ve `…` işaretleri arasından bulunur ve sayfa metnine geri eşlenir. Ekran kaynağı: günlük `aria-live="off"`, delta duyurulmaz, aşama duyurulur, "Durdur", akıştan sonra kelime efekti yok, çekmecede `aria-modal`, Esc, Tab tuzağı, odak geri, `writeMarked` (CSS highlight ya da `<mark>`); yeni dosyalarda `innerHTML`, inline stil, `EventSource` yok.

## 2. Yalnız CI'da koşanlar (yazıldı, yerelde koşmadı)

| Test | Kapsam |
|---|---|
| `QuestionStreamApiTest` | gerçek port ve DB: olay sırası ve başlıklar (`text/event-stream`, `no-store, private`, `X-Accel-Buffering: no`, `X-Rag-Mode`); `citations` doğrulanmış, tur + atıf + `QUESTION_ASKED` denetim satırı; `UNCITED`; kapı kapalı `NOT_FOUND` model çağrısız; istemci gidince model iptali, 0 tur / konuşma / denetim satırı, slot boş; 8 sn sınırı; sağlayıcı hatası güvenli `error`; iki JSON soru slotları tutarken `11002`; akış öncesi 404/400/401 zarflı JSON; açık devrede 503 embedding çağrısız; log'da işaretli soru ve cevap yok |
| `DocumentPageApiTest` | sahibin sayfaları, olmayan sayfa `10005`, 0 ve 100 001 → 400, token'sız 401; yabancı hesap, olmayan id ve yabancı hesabın olmayan sayfası aynı `10001` (gövde aynı), `verso-admin` kişisel belgeyi okuyamaz; MD bölümleri `SECTION`; bilgi alanı: grant'lı grup okur, diğer grup ve grant'sız admin 404, grant kaldırılınca bir sonraki istekte 404; 60 000 kod noktası sınırı ve `truncated`; denetim satırı yok; log'da sayfa metni yok |
| `OllamaChatClientTest` | Boot'un `WebClient`'ı genel adrese bağlanmaz (akışlı egress filtresi); `dispose()` Ollama bağlantısını kapatır, sahte sunucu yazmayı bırakır, istek `"stream":true` |
| `AnthropicCloudModeTest` | cloud modda akış: sağlayıcının SSE'si aktarılır, atıflar sunucuda (`[4]` atılır), giden gövdede dosya adı yok, log temiz |
| `ComposeConfigTest` | (yerelde de koştu) yalnız akış konumunda `proxy_buffering off`, başlık temizliği, 150 sn |

## 3. Review skill'leri (her biri bir kez, bağımsız bir gözden geçirici)

| Skill | Karar | Uygulanan düzeltme |
|---|---|---|
| verso-security-review | APPROVE WITH NON-BLOCKING COMMENTS | – (ASYNC `permitAll` ve sayfa ucunun IDOR'a kapalılığı onaylandı) |
| verso-llm-review | APPROVE WITH NON-BLOCKING COMMENTS | – |
| verso-resilience-review | APPROVE WITH NON-BLOCKING COMMENTS | abonelik `subscribeOn(boundedElastic)` (abone olurken bloklayan sağlayıcı süre sınırını tutmasın); bulut iptali notu koda yazıldı |
| verso-api-contract-review | APPROVE WITH NON-BLOCKING COMMENTS | – (`error` olayına `status` eklemek sonra eklemeli yapılabilir) |
| verso-spring-code-review | APPROVE WITH NON-BLOCKING COMMENTS | yetim Javadoc, import sırası, `qa-core`'da `platform-observability` açık bağımlılık |
| verso-test-writer | APPROVE WITH NON-BLOCKING COMMENTS | `WRITING` yazılamaması, akış kurulurken hata, DB hatası (birim) ve akış ucunda 411 (entegrasyon) testleri eklendi |
| verso-environment-impact-review | APPROVE WITH NON-BLOCKING COMMENTS | – |

Açık bırakılan yorumlar: `QuestionEventStream` için ayrı birim testi yok (davranışı entegrasyon testi kapsar); `QuestionServiceImpl` ~450 satır; `delta` olayında `status`/`traceId` yok.

## 4. Doğrulanmayanlar (canlı kontrol gerekir)

- **Tarayıcıda görsel ve ekran okuyucu kontrolü yapılmadı** (Docker kapalı, yığın yok): aşama satırı, taslağın akışı, "Durdur", JSON'a geri düşme, sayfa çekmecesi, `CSS.highlights` vurgusu ve `<mark>` yedeği, odak tuzağı, dar ekran.
- Gerçek Ollama'da ilk metin süresi ve iptalin Ollama üretimini durdurduğu (mekanizma JSON yolun zaman aşımıyla aynı ve faz 8'de ölçülmüştü; akış yolu için sahte sunucuyla gösterildi).
- nginx'in gerçekten tamponlamadan aktardığı (konfigürasyon testi var, canlı `curl -N` yok).
- Bulut sağlayıcısında bağlantı kapanınca üretimin ve faturalamanın durması.
