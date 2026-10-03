# llm-rules.md — LLM, RAG ve Model Çağrısı Kuralları

> **Tek kaynak.** Model çağrısı, prompt, retrieval, embedding, chunking ve çalışma modu (local/cloud) kuralları burada yaşar. `verso-llm-review` skill'i buraya link verir, kuralları tekrar etmez. Genel güvenlik kuralları `security-rules.md`'dedir; bu dosya onları LLM'e özgü durumlar için **genişletir**, yerine geçmez.
>
> Mimari referans bu alanı kapsamaz. Bu dosya referansın kural biçimini izler (Bölüm 1.4): her kuralın sınıfı, gerekçesi, uygulanma koşulu, istisnası, doğrulaması ve yeniden değerlendirme koşulu vardır. Bir kural "doğrulama" veremiyorsa dilektir (referans 19.5); doğrulaması henüz yazılmamış kurallar Bölüm 10'da listelenir.

Sınıflar: **ZG** = zorunlu güvence (ihlal `BLOCK`), **VT** = varsayılan tercih (sapma ADR ister), **BA** = başlangıç ayarı (ölçümle değişir).

## 1. Veri yerelliği ve dışa giden trafik

| # | Kural | Sınıf | Gerekçe | Uygulanma / istisna | Doğrulama | Yeniden değerlendirme |
|---|---|---|---|---|---|---|
| 1.1 | `verso.ai.mode=local` iken uygulama yapılandırılmış model host'u, kendi veritabanı ve iç ağdaki kimlik sağlayıcısı (JWKS) dışında **hiçbir** adrese bağlantı açmaz. | ZG | Ürünün temel vaadi; KVKK md. 9 yurt dışına aktarım riski | Her ortam; istisna yok | (a) Compose: app ve Ollama yalnız `internal: true` ağda; (b) `InetAddressFilter` allowlist; (c) açılış kontrolü; (d) dış adrese istek reddi testi; (e) `scripts/prove-local-mode.sh` | Yeni bir dış bağımlılık (IdP dahil) eklendiğinde allowlist bilinçli güncellenir |
| 1.2 | Embedding her modda yereldir. Cloud chat'e yalnız soru, sahiplik filtresinden geçmiş en fazla `top-k` pasaj ve sistem talimatı gider; dosya, tam metin ve vektör gitmez. | ZG | Dışarı çıkan veriyi en aza indirmek; mod değişince vektörler geçersiz kalmamalı | Cloud mod | Prompt kurucu birim testi: gönderilen içerik yalnız seçilen pasajlar; tam belge işareti yok | – |
| 1.3 | Aktif mod her HTTP yanıtında `X-Rag-Mode` ile ve `/actuator/info`'da görünür. | ZG | Demo ve denetim kanıtı; gizli mod değişimi olmamalı | Hata yanıtları ve filtre redleri dahil | Smoke testi: başarı, 4xx ve 5xx yanıtlarında header | – |
| 1.4 | Cloud API anahtarı yalnız `/run/secrets` ile gelir; literal fallback yok; local modda anahtar beklenmez. | ZG | `security-rules.md` 1 | – | `ConfigDriftTest`, `config-lint` | – |
| 1.5 | Spring AI ve sağlayıcı istemcilerinin telemetri/içerik gönderimi kapalıdır; OTLP gibi gözlem çıktıları local modda yalnız yerel hedeflere gider. | ZG | Gözlem kanalı da bir sızıntı kanalıdır | – | Config testi: içerik loglama ve harici exporter kapalı | Gözlem yığını eklenince (faz 7) |

## 2. Loglama, gözlem ve hata

| # | Kural | Sınıf | Gerekçe | Uygulanma / istisna | Doğrulama | Yeniden değerlendirme |
|---|---|---|---|---|---|---|
| 2.1 | Belge metni, dosya adı, soru, prompt, model cevabı ve pasaj içeriği **hiçbir seviyede** loglanmaz; span attribute'una ve metrik etiketine konmaz. Loglanabilenler: id'ler, süreler, boyutlar (byte, sayfa, chunk, token sayısı), sonuç kodları. | ZG | Belgeler kişisel veri içerir; dosya adı bile içerebilir ("Ahmet_maas.pdf") | DEBUG dahil her seviye | Log privacy testleri (`ListAppender`, referans 8.5): sentetik işaretli belge/soru ile mesaj, argüman, MDC ve throwable zincirinde işaret yok | – |
| 2.2 | Spring AI'ın prompt/completion içerik loglama ve gözlem seçenekleri kapalıdır (`SimpleLoggerAdvisor` ve içerik içeren observation ayarları prod config'inde yok). | ZG | Framework varsayılanları ile 2.1 ihlal edilebilir | Test profili hariç değil | Config testi: ilgili property'ler `false` ya da tanımsız; ArchUnit: `SimpleLoggerAdvisor` kullanımı yok | Spring AI sürüm yükseltmesinde |
| 2.3 | Model ve sağlayıcı hataları yanıta ham metinle dönmez. Model erişilemezse 503 `MODEL_UNAVAILABLE`; log'a yalnız `exceptionType` ve sanitize özet yazılır. | ZG | Sağlayıcı hata metni prompt parçası veya anahtar taşıyabilir | – | Handler testi; model hata senaryosu testi | – |

## 3. Prompt injection ve çıktı işleme

| # | Kural | Sınıf | Gerekçe | Uygulanma / istisna | Doğrulama | Yeniden değerlendirme |
|---|---|---|---|---|---|---|
| 3.1 | Belge içeriği **güvenilmeyen veridir**. Pasajlar prompt'ta sabit ayraçlarla, numaralı ve sistem talimatından ayrı bir bölümde verilir; talimat, bağlamdaki yönergelerin uygulanmayacağını söyler. | ZG | Yüklenen bir belge "önceki talimatları yok say" içerebilir | – | Prompt kurucu testi: ayraçlar ve talimat sırası; ayraç taklidi içeren pasaj kaçışlanır | Yeni prompt şablonunda |
| 3.2 | Model çıktısı hiçbir yerde **çalıştırılmaz**: araç çağrısı, SQL, URL getirme, dosya erişimi yok. Cevap düz metin olarak döner. | ZG | Injection'ın etkisini cevap metniyle sınırlamak | Araç çağrısı eklenecekse ayrı ADR ve tehdit modeli | ArchUnit: `qa` modülünde tool/function callback kullanımı yok | Araç kullanımı istenirse |
| 3.3 | Atıflar **sunucuda** üretilir. Model yalnız `[n]` işaretleri kullanır; işaretler, o istekte gerçekten getirilmiş pasajlara (belge id + sayfa) eşlenir. Getirilmemiş kaynağa işaret eden ya da var olmayan numara atılır. | ZG | Uydurma atıf, ürünün güvenilirlik iddiasını bozar | – | Birim testi: geçersiz `[9]` atılır; her atıf getirilen bir chunk'a eşleşir | – |
| 3.4 | En iyi pasajın benzerliği eşiğin altındaysa chat modeli çağrılmaz; sabit "belgelerde bulunamadı" cevabı döner. | VT | Uydurma ve gereksiz maliyeti önlemek | Eşik değeri BA | Retrieval testi: alakasız soru, model çağrılmadan bulunamadı cevabı | Eval sonuçlarına göre (bölüm 7) |
| 3.5 | Soru uzunluğu, `top-k`, pasaj başına karakter ve cevap token sınırı yapılandırmayla sınırlıdır. | BA | Kaynak tüketimi ve prompt şişmesi | – | Validation testi (400) ve config testi | Ölçümle |

## 4. Sahiplik ve erişim

| # | Kural | Sınıf | Gerekçe | Uygulanma / istisna | Doğrulama | Yeniden değerlendirme |
|---|---|---|---|---|---|---|
| 4.1 | Retrieval, sahiplik filtresini **vektör sorgusunun içinde** uygular; sonuçlar bellekte süzülmez. Başka hesaba ait bir chunk asla prompt'a girmez. | ZG | Bellekte süzme `top-k`'yı başka hesabın sonuçlarıyla doldurur ve bir hata ile sızıntıya döner (IDOR) | – | Gerçek PGVector testi: iki hesap, benzer belgeler; A'nın sorusu B'nin chunk'ını hiç getirmez | Çok kiracılık eklenirse |
| 4.2 | Belge silme; metadata, chunk'lar, vektörler ve (saklanıyorsa) orijinal dosyayı tek işlemde siler. Silinen belge sonraki retrieval'da görünmez. | ZG | KVKK silme hakkı | – | Entegrasyon testi: sil → ara → yok | – |

## 5. Model çağrısı bir uzak çağrıdır

| # | Kural | Sınıf | Gerekçe | Uygulanma / istisna | Doğrulama | Yeniden değerlendirme |
|---|---|---|---|---|---|---|
| 5.1 | `ChatModel`, `ChatClient` ve `EmbeddingModel` çağrıları DB transaction'ı, satır kilidi veya advisory lock tutulurken yapılmaz. | ZG | Referans 4.3; uzun model süresi bağlantı havuzunu ve kilitleri tutar | – | `TransactionBoundaryRulesTest` (Spring AI tipleri uzak çağrı sayılır) + negatif fixture | – |
| 5.2 | Her model çağrısının timeout'u ve eşzamanlılık sınırı vardır; senkron yolda retry yoktur. Değerler ADR-0008 ve `repo-context.md` Bölüm 3 ile tutarlıdır. | ZG / BA | Referans 4.7 | Ingestion worker'ında backoff'lu retry serbest | Config testi; "model yanıt vermiyor" testi (timeout ve 503) | Ölçümle |
| 5.3 | Model istemcileri Boot'un auto-configured builder'larından kurulur; statik `RestClient.create()` kullanılmaz. | VT | Egress filtresi ve tracing yalnız auto-configured builder'a uygulanır (referans 6.8, 9.11) | – | Kod araması + egress testi | – |

## 6. Embedding, chunking ve sürümleme

| # | Kural | Sınıf | Gerekçe | Uygulanma / istisna | Doğrulama | Yeniden değerlendirme |
|---|---|---|---|---|---|---|
| 6.1 | Her chunk, kendisini üreten embedding modelinin adı ve boyutuyla saklanır. Sorgu anındaki model saklanan modelle uyuşmazsa retrieval fail-closed davranır (hata kodu, boş sonuç değil). | ZG | Farklı modelin vektörleri karşılaştırılamaz; sessizce yanlış sonuç üretir | – | Entegrasyon testi: farklı model adıyla sorgu reddedilir | – |
| 6.2 | Embedding modelini değiştirmek bir migration ve yeniden indeksleme işidir; ADR ister. | VT | Vektör boyutu ve anlam uzayı değişir | – | `verso-llm-review` + `verso-db-migration-review` | – |
| 6.3 | Chunk'lar sayfa sınırını aşmaz; her chunk kaynak sayfa numarasını taşır. | ZG | Atıf kesinliği (belge adı + sayfa) | – | Chunker birim testi | – |
| 6.4 | Chunk boyutu ve örtüşme karakter bazlıdır ve yapılandırılabilir (başlangıç: 1000 / 150). | BA | Türkçe eklemeli bir dildir; OpenAI tokenizer'ına dayalı token sayımı bge-m3'ün tokenizer'ıyla uyuşmaz | – | Chunker testleri; eval | Eval sonuçlarına göre |

## 7. Ölçüm (eval)

| # | Kural | Sınıf | Gerekçe | Uygulanma / istisna | Doğrulama | Yeniden değerlendirme |
|---|---|---|---|---|---|---|
| 7.1 | Chunking, embedding modeli, retrieval parametreleri, prompt şablonu veya chat modeli değişikliği, Türkçe eval setinin (sentetik belgeler + soru/beklenen kaynak çiftleri) koşturulmasını ister. Sonuç (recall@k, atıf kesinliği, "bulunamadı" doğruluğu) PR'a yazılır. | VT | "Daha iyi görünüyor" kanıt değildir | Faz 8'de kurulur; o zamana kadar `needs verification` | Eval komutu ve sonuç tablosu | Eşikler faz 8'de |

## 8. Model tedarik zinciri

| # | Kural | Sınıf | Gerekçe | Uygulanma / istisna | Doğrulama | Yeniden değerlendirme |
|---|---|---|---|---|---|---|
| 8.1 | Kullanılan model adları ve sürümleri (Ollama etiketi ve digest) `docs/versions.md`'de tarihli tutulur; lisansları not edilir. | VT | Tekrarlanabilirlik; lisans uyumu (referans 25) | – | `verso-release-readiness-review` | Çeyreklik |
| 8.2 | Modeller yalnız resmi kayıttan (Ollama registry) ve tek seferlik indirme container'ı ile alınır; çalışma zamanındaki Ollama'nın internet erişimi yoktur (1.1). | ZG | Çalışma zamanında model değişmesin | – | Compose ağ tanımı; kanıt script'i | – |

## 9. KVKK ve kullanıcıya açıklık

| # | Kural | Sınıf | Gerekçe | Uygulanma / istisna | Doğrulama | Yeniden değerlendirme |
|---|---|---|---|---|---|---|
| 9.1 | README ve API dokümanı, cloud modda hangi verinin hangi sağlayıcıya gittiğini açıkça yazar ve bunun hukuki tavsiye olmadığını belirtir. | ZG | Bilinçli seçim ancak açık bilgiyle mümkündür | – | README incelemesi (`verso-llm-review`) | Sağlayıcı değişince |

## 10. Bu Belgede Özellikle Taşınmayanlar

Model sağlayıcı anahtarları, gerçek belge örnekleri, kişisel veri içeren prompt örnekleri. Örneklerde yalnız sentetik ve kişisel veri içermeyen metin kullanılır.

## 11. Net Kanıt Bulunamayan Alanlar

- Gemma 4 küçük modellerinin Türkçe soru-cevap kalitesi: üretici 140+ dil diyor; Türkçe için ölçüm faz 8'deki eval ile yapılacak.
- Spring AI 2.0.x'te prompt/completion içerik loglamasını kontrol eden property adları: faz 5'te kaynak koddan doğrulanacak.
- Kısmen doğrulanan: 2.1. Global handler ve hata sayfası yalnız istisna tiplerini loglar; ham path hiçbir framework log'una düşmez (`GlobalServiceExceptionHandlerTest`, `VersoAppSmokeTest`, mutasyonlar M12, M29). Belge/soru/cevap içeriği için testler faz 4–5'te.
- Faz 5 itibarıyla doğrulananlar: 1.3 (`QuestionApiTest`: 200/401/404/503'te `X-Rag-Mode`), 2.1 (worker, yükleme ve soru-cevap log testleri), 2.2 (ArchUnit: `SimpleLoggerAdvisor` yok), 2.3 (503, sağlayıcı metni yok), 3.1–3.5 (`PromptBuilderTest`, `CitationExtractorTest`, `QuestionApiTest`), 4.1–4.2 (`RetrievalTest`: iki hesap, silme), 5.1 (`TransactionBoundaryRulesTest`), 6.1 (`RetrievalTest`: başka model → 409), 6.3–6.4 (`PageChunkerTest`), 8.1–8.2 (`ollama-pull.test.js`, `ComposeConfigTest`).
- Faz 6 itibarıyla doğrulananlar (ADR-0013):
  - 1.1: `ComposeConfigTest.networks_*` (uygulama yalnız iç ağlarda), `OllamaChatClientTest` (filtre dış adresi reddeder, local modda bulut istemcisi yok), `AiModeCheckTest` (açılış kontrolü), `scripts/prove-local-mode.sh` (CI'da).
  - 1.2: `AnthropicCloudModeTest` (giden gövdede dosya adı yok, eşik altında hiç istek yok), `AiModeCheckTest` (embedding her modda Ollama).
  - 1.3: cloud modda da (`AnthropicCloudModeTest`, `OpenAiCloudModeTest`).
  - 1.4: anahtar yalnız config tree dosyası (`ComposeConfigTest`, `AiModeCheckTest`).
  - 1.5: `OllamaChatClientTest` (içerik loglama kapalı, OTLP exporter yok); faz 7'de gözlem çıktıları da: Grafana ve Loki iç ağda, Grafana'nın dış paylaşım yolları kapalı, metriklerde id ve içerik yok (`MetricsTest`), Loki'de soru metni yok (`scripts/obs-smoke.sh`), `ComposeConfigTest.observability_*`.
- Faz 8 itibarıyla 7.1: eval seti `eval/eval-set.json` (33 soru, 6 sentetik belge). Ölçümler:
  - `RetrievalEvalTest` (tag `eval`): recall@1 0,88, recall@5 1,00, MRR 0,94.
  - `scripts/eval.mjs`, gemma4:e2b ile (review sonrası sıkı tanımlarla): atıf isabeti 1,00, sayfa düzeyinde atıf kesinliği 1,00, cevapta doğru bilgi 0,96, "bulunamadı" doğruluğu 0,875 (eşiğin durdurduğu 4/4, modelin karar verdiği 3/4; kaçan soruda model doğru bir reddi kendi cümlesiyle yazdı).
  - Sınırlar: set küçük, eşik aynı veriyle ayarlandı (`eval/README.md` "Sınırlar").
  - Sonuçlar `eval/results/` altında; komutlar `eval/README.md`'de.
- Doğrulaması henüz yazılmamış kurallar: 9.1 (faz 9).
