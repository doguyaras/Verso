# ADR-0013: Cloud modu, sağlayıcılar ve egress kanıtı

- **Durum:** Kabul edildi (faz 6). Yeni bileşenler kullanıcı onayı bekliyor (aşağıda "Onay bekleyenler").
- **Tarih:** 2026-10-03
- **Karar verenler:** doguyaras (ADR-0006); uygulama ayrıntıları faz 6
- **İlgili eşik (referans Bölüm 24):** ADR-0008 cloud bütçesi (p99 20 sn, chat timeout 30 sn).

## Bağlam

ADR-0006 iki modu belirledi:

- `local` modda Verso hiçbir harici adrese bağlanmaz. Bu, üç katmanda zorlanır ve makineyle kanıtlanır.
- `cloud` modda yalnız chat çağrısı dışarı çıkar. Varsayılan sağlayıcı Anthropic'tir; ikincisi OpenAI uyumlu bir API'dir.

Faz 5 sonunda uygulama compose'un `default` ağındaydı. Bu ağın internete çıkışı var, yani `local` modun "dışarı çıkmaz" iddiası yalnız uygulama koduna dayanıyordu. Ayrıca bulut sağlayıcılarının istemcileri yoktu.

## Seçenekler

| Konu | Seçenekler | Karar |
|---|---|---|
| Sağlayıcı istemcisi | Spring AI starter'ları · kendi HTTP istemcimiz | **Spring AI starter'ları** (`spring-ai-starter-model-anthropic`, `-openai`, 2.0.1). Altlarında sağlayıcıların resmi Java SDK'ları var (aşağıda) |
| Sağlayıcı seçimi | Profil · property | **`spring.ai.model.chat`** (`VERSO_CHAT_PROVIDER`): `ollama` · `anthropic` · `openai`. Tek image; local modda bulut istemcisi hiç oluşturulmaz |
| API anahtarı | Ortam değişkeni · `/run/secrets` | **`/run/secrets/spring.ai.<sağlayıcı>.api-key`**: compose secret'ı bu adla bağlanır, config tree dosya adını ayara çevirir. `verso.yml`'de placeholder yok, dolayısıyla fallback da yok (referans 15.3) |
| Ağ izolasyonu | Uygulama `default` ağda · uygulama yalnız iç ağda + kenar proxy | **Yalnız iç ağ + kenar proxy (nginx).** Docker, yalnız `internal: true` ağdaki bir container'ın portunu yayınlayamaz; yayınlanan port proxy'dedir |
| Uygulama içi egress | Yok · Boot 4.1 `InetAddressFilter` | **`InetAddressFilter.internalAddresses()`**: Boot'un kurduğu her HTTP istemcisi (Ollama dahil) yalnız loopback, link-local ve özel adreslere bağlanır |

## Karar

**1. Mod tutarlılığı (`AiModeCheck`, açılışta):**

| Mod | Zorunlu | Aksi hâlde |
|---|---|---|
| Her iki mod | `spring.ai.model.embedding=ollama` (llm-rules 1.2) | Uygulama başlamaz |
| `local` | `spring.ai.model.chat=ollama`. Ollama adresi IP olarak yazılmışsa iç adres olmalı | Uygulama başlamaz |
| `cloud` | Chat sağlayıcısı `anthropic` ya da `openai`; anahtar dosyası mevcut | Uygulama başlamaz |

- Hata mesajları ayar adlarını söyler, değerleri yazmaz.
- Ollama adresi bir host adıysa açılışta çözülmez, çünkü uygulama Ollama'yı beklemez (faz 4 E1). Onu bağlantı anındaki filtre kontrol eder.

**2. Ağlar (compose):**

| Ağ | `internal` | Üyeler |
|---|---|---|
| `backend` | evet | verso-app, migrate, postgres, keycloak, backup, edge |
| `models` | evet | verso-app, ollama |
| `default` | hayır | edge ve keycloak (yayınlanan portlar), ollama-pull (model indirme) |
| `egress` | hayır | yalnız cloud overlay'de, yalnız verso-app |

- `edge` (nginx) API'nin tek yayınlanan portudur. Gövde sınırı 21 MB, upload akışla geçer, okuma timeout'u 150 sn.
- Uygulama kapalıyken `edge` ortak hata zarfıyla 503 (99997) + `Retry-After` döner.
- Erişim log'u kapalıdır: istek satırları belge id'si taşır.

**3. Cloud overlay (`deploy/compose.cloud.yaml`):**

- Yalnız `verso-app`'e dokunur.
- Uygulama şunları alır: `VERSO_AI_MODE=cloud`, sağlayıcı, `egress` ağı ve anahtar dosyası.
- Anahtar dosyası (`secrets/SECRET_CLOUD_API_KEY`) başka hiçbir servise bağlanmaz.

**4. Cloud çağrısı:**

- Dışarı yalnız sistem kuralları, soru ve sahiplik filtresinden geçmiş en fazla `top-k` pasaj çıkar. Dosya adı, tam metin ve vektörler gitmez. Eşik altında hiç çağrı yapılmaz.
- SDK'ların kendi retry'ı kapalıdır (`max-retries: 0`, ADR-0008). Çağrı başına 30 sn, sıcaklık 0,1, en çok 512 token.
- Hata yerel modeldeki gibi işlenir: 503 `MODEL_UNAVAILABLE`, devre kesici, sağlayıcı metni ne yanıta ne log'a girer.

**5. Kanıt:**

- `scripts/prove-local-mode.sh` çalışan yığında şunları gösterir:
  - Uygulama ve model sunucusu yalnız iç ağlardadır.
  - Uygulamanın kendi ağ ad alanından `1.1.1.1:443`'e bağlanılamaz ve `example.com` çözülmez.
  - Aynı probe veritabanına bağlanabilir (kontrol).
  - Yanıtlar `X-Rag-Mode: local` taşır.
- Script CI'daki restore-drill iş akışında koşar.
- Testler:
  - `AiModeCheckTest`: mod tutarlılığı.
  - `OllamaChatClientTest`: filtre dış adresi reddeder; local modda bulut istemcisi yoktur.
  - `AnthropicCloudModeTest` ve `OpenAiCloudModeTest`: gerçek istemciler sahte sağlayıcıya karşı denenir. İstek gövdesi, tek deneme, `X-Rag-Mode: cloud` ve `/actuator/info` doğrulanır.
  - `ComposeConfigTest`: ağlar ve overlay.

## Onay bekleyenler (kullanıcı kuralı: yığın dışı bağımlılık)

| Bileşen | Neden | Not |
|---|---|---|
| `com.anthropic:anthropic-java-core` 2.52.0, `com.openai:openai-java-core` 4.49.0 | Spring AI 2.0'ın Anthropic ve OpenAI istemcileri bu resmi SDK'lar üzerine kurulu | Transitif. Yanlarında OkHttp 4.12, Kotlin stdlib 2.3 ve Jackson 2.21 gelir: image'a yaklaşık 15 MB eklenir. Jackson 2, Boot'un Jackson 3'üyle yan yana yaşar |
| `nginxinc/nginx-unprivileged:1.29.8-alpine` (digest pinli) | İç ağdaki uygulamanın portunu yayınlamak | Root olmayan resmi nginx image'ı, ~23 MB, salt okunur, yetkisiz |

Onay verilmezse geri alma yolu:

- SDK'lar için: iki starter çıkarılır; cloud modu olmaz, local mod etkilenmez.
- nginx için: uygulama `default` ağa geri döner ve local modun ağ katmanı kanıtı düşer. Bu durumda yalnız uygulama içi filtre ve açılış kontrolü kalır.

## Sonuçlar

- **Olumlu:**
  - "Local mod dışarı çıkmaz" iddiası artık ağ, uygulama ve açılış olmak üzere üç katmanda zorlanıyor ve CI'da kanıtlanıyor.
  - Mod, kod değişmeden yalnız overlay ile değişir.
  - Yanlış yapılandırma uygulamayı başlatmaz; yanlış bir `X-Rag-Mode` başlığı gönderilmez.
- **Olumsuz / kabul edilen risk:**
  - Bir container daha (`edge`) ve image'da iki SDK.
  - Bulut SDK'ları Boot'un HTTP istemcisini kullanmadığı için adres filtresi onlara uygulanmaz. Cloud modda uygulamanın `egress` ağı her adrese çıkabilir; sağlayıcıya özel bir egress allowlist'i (ör. proxy) faz 9 kurulum dokümanında önerilir.
  - Kenar proxy'nin kendi 502/504 sayfaları uygulamadan geçmediği için `X-Rag-Mode` taşımaz. Bunun yerine sabit zarf döner.
  - KVKK md. 9: cloud modda pasajlar yurt dışına gidebilir. Karar operatörün hukuki değerlendirmesidir (README, hukuki tavsiye değildir).
- **Etkilenen dosyalar:** `verso-app/pom.xml`, kök `pom.xml` (sürüm yakınsaması), `qa-core` (`AiModeCheck`, `QaConfiguration`), `config/verso.yml`, `application-local.yml`, `compose.yaml`, `deploy/compose.cloud.yaml`, `deploy/compose.local.yaml`, `deploy/edge/nginx.conf`, `deploy/prod.env.example`, `.env.example`, `scripts/prove-local-mode.sh`, `.github/workflows/restore-drill.yml`.
- **Geri alma yolu:** Yukarıdaki "Onay bekleyenler" bölümünde.

## Yeniden değerlendirme koşulu

- Cloud modda p99 20 sn'yi aşarsa ya da sağlayıcı hata oranı devre kesiciyi sık açarsa (faz 7 metrikleri).
- Spring AI, SDK'sız bir istemciye dönerse ya da Boot'un HTTP istemcisini kullanmaya başlarsa: o zaman filtre bulut çağrısını da kapsar.
