# ADR-0056: Sağlayıcıdan bağımsız model geçidi (kurumun kendi API anahtarı)

- **Durum:** Kabul edildi. Uygulama tamam; birim testleri yerelde geçti, Testcontainers'lı API testleri (`ModelGatewayApiTest`, `ModelGatewayLocalModeTest`) CI'da koşar. Gerçek sağlayıcılarla canlı deneme yapılmadı (sahte sunucular).
- **Tarih:** 2026-10-28
- **Karar verenler:** doguyaras
- **İlgili eşik (referans Bölüm 24):** `verso.qa.gateway.*` başlangıç ayarları (doğrulama 15 sn, önbellek 10 sn, ekip k = 5 (taban), saklama 13 ay)
- **Genişlettiği:** ADR-0006 (mod), ADR-0013 (cloud ve egress), ADR-0019 (roller), ADR-0026/0039 (denetim, outbox), ADR-0030 (secret kuralları), ADR-0050 (Güven ekranı), ADR-0051 (iç ağdaki OpenAI uyumlu sunucu, `LocalModelEgress`)
- **Dayanak araştırma:** `research_notes/Verso yapay zeka platformu/platform_privacy.md`

## Bağlam

Verso kurumlara yapay zekâ altyapısı olarak kuruluyor; kurumların çoğu kendi sağlayıcı hesabını (Azure OpenAI, Bedrock, Vertex, Anthropic, OpenAI, Mistral) ya da kendi model sunucusunu kullanmak istiyor. Bugün model `config/verso.yml` ve `/run/secrets` dosyasıyla, kurulumda bir kez seçiliyor; sağlayıcı değiştirmek yeniden kurulum demek. Araştırma (Glean BYOK, LibreChat, LiteLLM) ortak kalıbı gösteriyor: anahtar bir **yönetici** işlemidir, girişte doğrulanır, görev başına yönlendirme ve yedek, bütçe ve harcama kaydı vardır.

## Seçenekler

| Konu | Seçenekler | Karar |
|---|---|---|
| Geçit | LiteLLM yan konteyneri · Portkey · Spring AI 2.0 yerel istemciler | **Spring AI 2.0 yerel istemciler + ince iç geçit.** LiteLLM ikinci bir çalışma zamanı, ikinci bir secret deposu, ikinci bir denetim izi ve ayrı tedarik zinciri (Mart 2026 ele geçirilmiş sürümler) getirir ve sıkı yerel modla çelişir. Müşterinin zaten bir geçidi varsa "OpenAI uyumlu" türüyle bağlanır |
| İstemci kurulumu | Sağlayıcı başına statik auto-configuration · tek dinamik fabrika | **Tek fabrika (`ChatModelFactory`)**: bağlantı ve model başına istemci, bağlantının revizyonuyla önbellekte. Yerleşik model (yapılandırma) değişmeden kalır |
| Sağlayıcılar | — | OpenAI, Azure OpenAI (OpenAI SDK'nın Azure kipi), Anthropic, AWS Bedrock (Converse), Google GenAI (Gemini API ya da Vertex), Mistral (OpenAI uyumlu uç noktası, OpenAI istemcisiyle), Ollama ve **OpenAI uyumlu** (vLLM, llama.cpp, OpenRouter, kurum geçidi). Eklenen modüller: `spring-ai-anthropic`, `-ollama`, `-bedrock-converse`, `-google-genai` (auto-configuration'sız). Lisanslar Apache-2.0 (`docs/versions.md`). `spring-ai-mistral-ai` eklenmedi: RestClient'ı ayrı bir egress yolu ister, kaybedilen özellik yok |
| Anahtar saklama | Düz metin · uygulama anahtarıyla tek katman · zarf şifreleme | **Zarf şifreleme:** bağlantı ve anahtar sürümü başına rastgele veri anahtarı (DEK) AES-256-GCM ile şifreler; DEK kurulumun KEK'iyle sarılır. İkisi de `verso:model-connection:<id>:v<sürüm>` bağlamına bağlı (başka satıra kopyalanan ya da eski sürüm olarak oynatılan şifreli metin açılmaz). KEK `/run/secrets/SECRET_MODEL_GATEWAY_KEK` (HKDF-SHA256 ile türetilir), veritabanına girmez; ortam değişkeninden verilirse uygulama açılmaz. `KeyEncryptionKey` arayüzü; bugün tek uygulama yerel dosya, HashiCorp Vault Transit aynı arayüze ikinci uygulama olarak gelir |
| Modül | Yeni `gateway` modülü · `qa` içinde | **`qa` içinde** (`service.gateway`, `repository`, `qa` V8): sohbet modeli çağıran tek modül `qa`'dır; ayrı modül Spring AI tiplerini `*-api` sözleşmesine taşımayı gerektirirdi. İkinci bir modül sohbet modeli isterse ayrılır |

## Karar

1. **Bağlantılar panelden yönetilir** (`/v1/admin/model-gateway`, yalnız `verso-admin`): ad, tür, adres / bölge / dağıtım / proje, model listesi ve API anahtarı.
   - Anahtar **yalnız yazılır**: istek gövdesinde bir kez gelir (`@JsonProperty(WRITE_ONLY)`, `toString` gizler), hiçbir yanıtta dönmez; yanıt yalnız `keyStored` ve son dört karakteri (`keyHint`) verir. URL'de, log'da, denetim kaydında, metrikte anahtar yoktur.
   - **Kaydetmeden önce doğrulanır:** açık bir bağlantı ilk modeliyle küçük bir tamamlama çağrısı (en çok 8 token, 15 sn) yapar; başarısızsa `422 11095` ve hiçbir şey saklanmaz. Döndürme (`POST …/key`) yeni anahtarı aynı şekilde doğrular, sürümü artırır; eski anahtar silinir.
   - Ekleme, değiştirme, döndürme, deneme ve silme denetim kaydına **yalnız kimlik, tür ve sabit sözcükle** yazılır (`MODEL_CONNECTION_*`, `MODEL_GATEWAY_CHANGED`), değişiklikle aynı transaction'da outbox'a (ADR-0039).
2. **Yönlendirme:** her görev (`ANSWER`, `FOLLOW_UP`, `DECOMPOSITION`, `SKILL_CONDENSE`, ve beceri kütüphanesinin adımları için `SKILL_RUN`, ADR-0057: geçit `work-api`'deki `SkillModel` portunun `@Primary` uygulamasını verir, yönlendirme sözcüğü `skill:<id>:<adım>` kullanım satırında `task_detail` olarak saklanır) bir birincil model ve en çok 4 sıralı yedeğe gider. Yedeğe yalnız 5xx, 429 ve ağ hatasında geçilir; 401/403/404 rotayı durdurur (ayarlanması gereken bir hata saklanmaz). Akışta yedeğe yalnız ilk token'dan önce geçilir. Aynı modele yeniden deneme yoktur; zaman sınırı (ADR-0008) bütün rotayı kapsar. Rota boşsa yerleşik model cevaplar (eski davranış). **Embedding yönlendirilmez**: her zaman yerel modeldir (llm-rules 1.2, 6.2); rota isteği `400 11096`, veritabanında CHECK.
3. **Mod kuralları korunur:**
   - `local`: yalnız iç ağdaki kendi barındırılan türler (Ollama, OpenAI uyumlu; adresin bütün IP'leri iç ağda) kaydedilebilir; bulut türü açık ya da kapalı **kaydedilemez** (`409 11091`). Veritabanında açık bir bulut bağlantısı varsa uygulama **açılmaz** (`GatewayLifecycle`). Yönlendirici ve istemci fabrikası da her çağrıda reddeder; OpenAI uyumlu istemcide `LocalModelEgress`, Ollama'da Boot'un iç adres filtresi.
   - `cloud`: her bağlantının istemcisi yalnız kendi host'una bağlanabilir (OkHttp ve AWS SDK önleyicisi); çıkış izin listesi bağlantıların host'larıdır ve Güven ekranında (`/v1/admin/trust` → `models`) listelenir. Google GenAI SDK'sının önleyici yeri yok; orada güvence ağdır.
4. **Kullanım ve maliyet:** her çağrı `qa.model_usage`'a bağlantı, model, görev ve ekip (token gruplarından, bütçesi olan ilk grup, yoksa ilk grup) ile yazılır; **hesap kimliği yazılmaz**. Rapor (`GET …/usage`, değer panosu kartı) kişi bazında değildir; ekip satırı ancak o ay en az `min-team-users` (taban 5) farklı kullanıcısı olan ekipte gösterilir. Farklı kullanıcı sayımı `qa.model_team_reach`'te ay ve ekip başına, KEK'ten türetilmiş anahtarla HMAC özetidir (KEK olmadan hesapla eşlenemez; KEK yoksa ekip satırı hiç gösterilmez). Fiyat tablosu (milyon token başına) ve aylık bütçe (bağlantı ya da ekip) yöneticinindir: uyarı sınırı bir kez denetim olayı + metrik + panel, üst sınır yalnız **fiyatlı** modeli atlar; rotada çalışabilecek model kalmazsa `429 11003`. Birden çok instance aynı anda sınırı uçuştaki çağrılar kadar aşabilir (kabul: harcama koruması, fatura değil).
5. **Gizlilik geçidi kancası:** her dış çağrı `ModelRequestFilter` zincirinden geçer (gönderim öncesi / cevap sonrası, akışta her parça). Bugün tek süzgeç `PASS_THROUGH`; Türkçe kişisel veri takma adlandırması (TCKN, VKN, IBAN, telefon, plaka; sonra yerel NER) buraya takılır (ADR-0059 ile takıldı: varsayılan `PII_PSEUDONYMISE`, yönetici kapatırsa `PASS_THROUGH`). Her dış çağrıda **gönderilen veri türleri** (`SYSTEM_INSTRUCTIONS`, `QUESTION`, `PASSAGES`, `CONVERSATION_TURNS`, `SYSTEM_MODEL_NAMES`, `PROCEDURE_STEPS`) ve süzgeç adı kaydedilir; içerik kaydedilmez.
6. **Kullanıcı model seçimi** yalnız yönetici açarsa: `GET /v1/models` cevap rotasındaki modelleri verir, `POST /v1/questions` isteğe bağlı `model` alır (sunulmayan seçim `400 11004`).
7. **Panel:** "Yapay zekâ sağlayıcıları" (`providers`, kısayol `g z`): türe göre form, deneme, görev rotaları, kullanıcı seçimi anahtarı, fiyat ve bütçe, kullanım. Anahtar parola alanında, gönderimden önce temizlenir; `innerHTML` ve satır içi stil yok.

### Güvenceler

| Güvence | Nasıl korunur | Doğrulama |
|---|---|---|
| Local modda model çağrısı kurumdan çıkmaz (llm-rules 1.1) | Kayıtta red, açılışta red, çağrıda red; iç ağ istemci filtresi | `ModelGatewayLocalModeTest` (CI), `ProviderFactoryTest`, `ModelGatewayTest` |
| Anahtar düz metin olarak hiçbir yerde yok | Zarf şifreleme, yalnız yazılır alan, log'da yalnız tür | `EnvelopeCipherTest`, `ModelKeyJsonTest`, `ModelGatewayApiTest` (satırda, yanıtta, log'da, denetimde yok) |
| KEK veritabanında değil, literal fallback yok | Config tree; ortam değişkeni reddi | `EnvelopeCipherTest`, `ComposeConfigTest` |
| Kişi bazında rapor yok | Kullanımda hesap yok; ekip k ≥ 5 | `ModelGatewayApiTest` (rapor), `providers-panel.test.js` |
| Senkron yolda yeniden deneme yok (ADR-0008) | SDK yeniden denemeleri kapalı; yedek başka modeldir | `ProviderFactoryTest`, `RoutedChatModelTest` |

## Sonuçlar

- **Olumlu:** kurum kendi sağlayıcısını kod değiştirmeden panelden bağlar; görev başına model, yedek, bütçe ve kullanım tek yerde; gizlilik süzgeci için kanca hazır.
- **Olumsuz / kabul edilen risk:**
  - Bağımlılık yüzeyi büyüdü (AWS SDK, google-genai ve Guava): aylık SBOM/Grype taramasının kapsamında. Yakınsama için Guava 33.7.2-jre, httpclient 4.5.14, checker-qual 3.55.1 sabitlendi.
  - KEK kaybolursa kayıtlı anahtarlar açılamaz (yeniden girilir); çevrimiçi yeniden sarma yok (`secrets/README.md`).
  - Bütçe sayımı instance'lar arası kesin değildir (yukarıda).
  - Sağlayıcıların ZDR / veri bölgesi koşulları Verso'da saklanmıyor; VKED 5.6'da kurum doldurur.
  - Cevaptaki ve `QUESTION_ASKED` denetim olayındaki `model` alanı hâlâ yerleşik modelin adıdır; çağrının gerçekte hangi bağlantı ve modele gittiği `qa.model_usage`'tadır (dış çağrının veri türleriyle). Cevaba gerçek modeli taşımak ayrı bir sözleşme değişikliğidir.
  - Birincil model yanıt vermeden asılı kalırsa sınırın tamamını tüketir; yedeğe yalnız hızlı hatada (5xx/429/ağ) zaman kalır. Kesilen (interrupt) çağrıdan sonra yedeğe geçilmez: hiçbir şey ikinci bir sağlayıcıya boşuna gitmez.
  - SDK'ların kendi istek log anahtarları (`ANTHROPIC_LOG`, `OPENAI_LOG`) artık her modda ve her yerleşik sağlayıcıda açılışı durdurur (geçit, çalışma anında bu SDK'ları kurabilir). Link-local (bulut metadata servisi) ve belirtilmemiş adresler kayıtta ve her istekte reddedilir.
- **Yeni yüzeyler:** `qa` V8 (`model_connection`, `model_route`, `model_gateway_setting`, `model_price`, `model_budget`, `model_usage`, `model_team_reach`), `SECRET_MODEL_GATEWAY_KEK`, `verso.qa.gateway.*`, hata kodları 11003–11004 ve 11090–11099, denetim eylemleri `MODEL_*`, hedef türleri `MODEL_CONNECTION`, `MODEL_GATEWAY`, metrikler `verso.model.gateway.*`, `GET /v1/models`, `TrustResponse.models`, `QuestionRequest.model`.
- **Geri alma:** rotaları boşaltmak yerleşik modele döner; V8 tabloları eski imaj tarafından okunmaz.

## Yeniden değerlendirme koşulu

- Türkçe takma adlandırma süzgeci eklendiğinde (kanca, sıra ve akışta parça tamponu).
- Vault Transit ya da bulut KMS istendiğinde (ikinci `KeyEncryptionKey`, yeniden sarma işi).
- İkinci bir modül sohbet modeli çağırmak isterse (ayrı modül).
- Bütçe aşımının kesin olması gerekirse (rezervasyonlu sayım).
