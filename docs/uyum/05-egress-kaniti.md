# Egress kanıtı: `local` mod dışarı veri göndermez

> Bu belge hukuki tavsiye değildir; kurumun hukuk birimi ve veri sorumlusu tarafından uyarlanmalıdır.

Amaç: kuruma, `local` modda soru, belge ve cevapların kurumun altyapısından çıkmadığını **gösterilebilir** kanıtlarla anlatmak. Bu belge var olan kontrolleri ve betikleri **referans verir**; burada hiçbir şey çalıştırılmaz. Kurulum ekibi kontrolleri kurulumun kendisinde çalıştırır ve çıktıyı VKED ekine ([01](01-vked-sablonu.md)) koyar.

Kurulum: {{KURUM_ADI}} / {{KURULUM_TARIHI}} / mod `{{AI_MODU}}` / Verso {{VERSO_SURUMU}} / kanıtı toplayan {{KANITI_TOPLAYAN}}.

> `cloud` modda bu belge uygulanmaz: o modda soru ve en çok 5 pasaj sağlayıcıya gider. Bkz. [01](01-vked-sablonu.md) Bölüm 5.2.

## 1. Kanıt katmanları

Dışa çıkışın olmaması dört bağımsız katmanda zorlanır; her biri ayrı bir kanıtla gösterilir (ADR-0006, ADR-0013, `docs/ai/llm-rules.md` 1.1):

| Katman | Ne söyler | Kanıt (repodaki kaynak) |
|---|---|---|
| A. Ağ topolojisi | Uygulama, veritabanı ve model sunucusu yalnız `internal: true` ağlardadır; yayınlanan port yalnız kenar proxy'dedir | `compose.yaml` ağ tanımları; `ComposeConfigTest` (`verso-app/src/test/java/com/verso/ComposeConfigTest.java`, ağ testleri); `scripts/prove-local-mode.sh` adım 1 |
| B. Uygulama içi filtre | Uygulamanın HTTP istemcileri yalnız loopback, link-local ve özel adreslere bağlanır | `OllamaChatClientTest` (dış adres reddedilir; local modda bulut istemcisi oluşmaz) |
| C. Açılış kontrolü | Mod ile model ayarı tutarsızsa ya da `local` modda sohbet sağlayıcısı `ollama` değilse ya da Ollama adresi IP olarak yazılmış ve iç adres değilse uygulama açılmaz | `AiModeCheckTest` (`services/qa/qa-core/src/test/java/com/verso/qa/config/AiModeCheckTest.java`), `AiModeStartupTest` |
| D. Çalışan sistemde sınama | İçeriden `1.1.1.1:443`'e bağlanılamaz, `example.com` çözülmez; aynı sınama veritabanına bağlanır (kontrol); yanıtlar `X-Rag-Mode: local` taşır | `scripts/prove-local-mode.sh` (CI'da `.github/workflows/restore-drill.yml` içinde koşar) |

Ek: embedding her modda yereldir (llm-rules 1.2); Spring AI ve sağlayıcı istemcilerinin telemetri/içerik gönderimi kapalıdır ve OTLP gibi gözlem çıktıları yerel hedeflere gider (llm-rules 1.5, `OllamaChatClientTest`; gözlem yığını `ComposeConfigTest.observability_*`, `scripts/obs-smoke.sh`).

## 2. Kurulumda toplanacak kanıt (kontrol listesi)

Kurulum ekibi sırayla; her satır için çıktı/ekran görüntüsü `{{KANIT_KLASORU}}` içine konur.

| # | Kontrol | Beklenen sonuç | Nasıl | Sonuç / tarih |
|---|---|---|---|---|
| 1 | Betik kanıtı | `prove-local-mode: OK`; ağların hepsi "internal" | `bash scripts/prove-local-mode.sh` (yığın ayaktayken; `scripts/dev-secrets.sh` ve `docker compose up -d --build --wait` sonrası; betiğin başındaki açıklamaya bakın) | {{SONUC_1}} |
| 2 | Mod başlığı | Her yanıtta `X-Rag-Mode: local`: başarı, 4xx ve 5xx yanıtlarında, kenar proxy'nin kendi yanıtlarında da | Betiğin 3. adımı; ayrıca herhangi bir yanıtın başlıkları | {{SONUC_2}} |
| 3 | Mod bilgi ucu | `/actuator/info` yönetim portunda (8081, compose ağı içinde) modu söyler | Yönetim portuna ağ içinden istek | {{SONUC_3}} |
| 4 | Ağ tanımı | `models` ve `backend` ağları `internal: true`; Ollama'nın host portu yok; `deploy/compose.local.yaml` (IDE geliştirme için, portu `127.0.0.1`'e açar) üretimde kullanılmıyor | `compose.yaml` incelemesi, `docker network inspect` | {{SONUC_4}} |
| 5 | Cloud overlay yok | `deploy/compose.cloud.yaml` uygulanmamış; `egress` ağı yok; `SECRET_CLOUD_API_KEY` yok | Çalışan yapılandırma incelemesi | {{SONUC_5}} |
| 6 | Test paketi | CI'da `ComposeConfigTest`, `OllamaChatClientTest`, `AiModeCheckTest`, `AiModeStartupTest` yeşil | Sürümün CI çıktısı / `./mvnw -B -ntp verify` sonucu | {{SONUC_6}} |
| 7 | Bağımsız ağ gözlemi | Verso sunucusunun/ağ segmentinin dışa giden trafiği kurum güvenlik duvarında incelenir; soru sorulurken dışarıya (model/Verso adı altında) trafik yok | Kurumun güvenlik duvarı / akış kayıtları: {{GUVENLIK_DUVARI_KANITI}} | {{SONUC_7}} |
| 8 | Hata yanıtı | Model kapalıyken 503 `MODEL_UNAVAILABLE`; sağlayıcı metni yanıtta ve log'da yok | Betik dışı; `docs/runbooks/model-unavailable.md` | {{SONUC_8}} |
| 9 | Bağlayıcı dış bağlantısı kapalı | `/actuator/info` `connectors.externalEgress: false`; `VERSO_CONNECTOR_EXTERNAL_EGRESS` tanımsız ya da `false` (local modda açıksa uygulama zaten açılmaz, ADR-0044); Confluence Data Center bağlayıcısı varsa adresi `VERSO_CONNECTOR_ALLOWED_NETWORKS` içinde ve `deploy/compose.confluence.yaml` ağı için güvenlik duvarı kuralı yalnız o sunucunun 443 portuna izin veriyor | Yönetim portuna istek; `.env` incelemesi; `ConnectorEgressConfigurationTest`, `ConfluenceSyncTest` (CI) | {{SONUC_9}} |

Madde 7 Verso'nun kendi kanıtı değil, **kurumun bağımsız gözlemidir**; en ikna edici kanıt budur ve kurum güvenlik ekibi yürütür.

## 3. Sınırlar (dürüst okuma)

- Betik **ağ konumunu ve erişilebilirliği** gösterir; konteynerlerin içindeki her yazılımın ne yaptığını değil. Model sunucusunun (Ollama) çalışma zamanındaki internet erişimi yoktur; ancak modeller kurulumda bir kez resmi kayıttan indirilir (`ollama-pull`, `default` ağ; llm-rules 8.2). Bu indirme içerik göndermez ama bir dış bağlantıdır; kurum güvenlik duvarı kuralına yazılır.
- Kurulumdaki **kimlik sağlayıcı** (IdP) JWKS erişimi uygulamanın izin verilen adresleri arasındadır (llm-rules 1.1). Kurumun IdP'si Verso ağının dışında ise uygulamadan IdP'ye erişim yolu ve allowlist'i kurulum ekibi ayrıca belgelemelidir: {{IDP_ERISIM_YOLU}}. Paketle gelen demo Keycloak üretimde kullanılmaz.
- Ana makine (host) düzeyi ayarlar (güvenlik duvarı, proxy, yapılandırma yönetimi, uzaktan erişim yazılımları) bu kanıtın dışındadır.
- `docker compose` yığınına yapılan sonradan değişiklikler (ağın `internal` olmaktan çıkarılması, port eklenmesi) kanıtı geçersiz kılar. Her yapılandırma değişikliğinden sonra madde 1, 4 ve 5 yeniden yapılır.
- Gözlem yığını (Prometheus, Loki, Grafana, Alloy) iç ağdadır; Grafana'nın dış paylaşım yolları kapalıdır, metriklerde id ve içerik yoktur (llm-rules 1.5, 2.1).
- Bağlayıcılar (ADR-0038, ADR-0044): SMB ve Confluence Data Center overlay'leri uygulamaya iç olmayan birer ağ ekler; internete kapalılıkları host güvenlik duvarı kuralına bağlıdır ve betiğin 1. adımı bu ağları raporlar (beklenen). Confluence Cloud bağlayıcısı internet ister ve `local` modda kullanılamaz.
- Kayıtlar: kenar proxy erişim logu kapalıdır; hata logu yalnız `crit` seviyesindedir (ADR-0013).

## 4. VKED'e nasıl konur

[01](01-vked-sablonu.md) Bölüm 5.1 ve R13 satırı bu belgeye bağlanır; "Kanıt": toplanan kontrol listesi, tarih ve imzalayan. Kanıt, yapılandırma değiştiğinde ya da Verso sürümü yükseltildiğinde yenilenir.
