# Paylaşımlı kademe: sohbet yuvası darboğazı (ADR-0051)

- **Tarih:** 2026-10-26
- **Dal:** `feature/doguyaras-esz-model`
- **Durum:** uygulama tarafı testli; **canlı ölçüm yapılmadı.** Aşağıdaki ölçüm tabloları boştur ve ölçüm yapılınca doldurulacaktır.

## 1. Neden ölçülmedi

| Saat (UTC) | Olay | Kaynak |
|---|---|---|
| 16:51:49 | Docker Desktop 4.80.0 açılırken arka uç çöktü: `starting services: initializing Inference manager: listening on unix://…/Docker/run/dockerInference: remove …/dockerInference` (2026-10-25 tarihli eski soket dosyası) | `%LOCALAPPDATA%\Docker\log\host\com.docker.backend.exe.log` |
| 16:51:53 | Hata penceresi: "Quit" / "Reset to factory defaults" | aynı |
| 16:52:28 | Pencerede "Reset to factory defaults" seçildi (ajan arayüze tıklamadı); uygulama ayarları sıfırlandı (`%APPDATA%\Docker\settings-store.json`), motor zaten durmuştu | aynı |
| sonrası | Veri diski yerinde: `%LOCALAPPDATA%\Docker\wsl\disk\docker_data.vhdx` 109,75 GB, son yazma 19:03 yerel saat (sıfırlamadan önce). Docker Desktop yeniden açılınca lisans onayı ister; ajan onay veremez | dosya sistemi |

Bu yüzden yığın (uygulama, Ollama) çalışmıyordu; GPU'da yalnız masaüstü vardı (1,9 / 16 GB). Boş RAM 19–20 GB idi.

## 2. Doğrulanan (Docker'sız)

| Kanıt | Sonuç |
|---|---|
| `AiModeCheckTest` (qa-core) | 12/12: local + iç ağdaki OpenAI uyumlu sunucu açılır; genel IP, URL'siz, anahtarsız, anahtarı ortam değişkeninden ya da `OPENAI_LOG` açık olan açılmaz; mesajda adres yok |
| `LocalModelEgressTest` (qa-core) | 7/7: iç adresler geçer; 203.0.113.9, 8.8.8.8, 2001:db8::1 ve çözülmeyen ad istek gönderilmeden reddedilir; Spring AI'ın gerçek istemcisi (customizer ile) genel adrese bağlanmadan reddeder; yönlendiren sunucunun cevabı reddedilir; cloud modda koruyucu eklenmez |
| `OpenAiCompatibleCancellationTest` (verso-app, Spring'siz) | 2/2: akış aboneliği bırakılınca ve JSON çağrısını bekleyen sanal iş parçacığı kesilince sahte sunucu bağlantının kapandığını görür (yazma hata verir) — llama-server'ın üretimi bırakmasının ön koşulu |
| `ComposeConfigTest` (21), `ConfigDriftTest` (3), `ConfigProfilesTest` (6), `ModuleConsistencyTest` (3) | Geçti; yeni overlay testi dahil (`llamacppOverlay_whenComposed_staysLocalInternalVerifiedAndMatchesTheSlots`) |
| `scripts/llamacpp-serve.test.js` (3), `scripts/ollama-serve.test.js` (4 + 1 Windows'ta atlanır) | Geçti: doğru digest'te sunucu yuva, bağlam, anahtar dosyası ve web arayüzsüz başlar; yanlış dosya, bozuk digest, eksik anahtar başlatmaz; `OLLAMA_WARM_CHAT=none` yalnız embedding'i ısıtır |
| `qa-core` birim testleri | 170/170 |
| `LocalOpenAiModeTest` (Testcontainers: JSON, akış, akıştan çıkış, local sınırda 503 ve bağlantı kapanışı) | Yerelde koşulmadı (Docker yok); CI'da koşar |

llama.cpp imajı: `server-cuda-b11561`, indeks `sha256:20c6e722e2fcc5ca9c15401d3fd8e35408aaf8f9c54a12f32920f637c57959bb`, amd64 manifest `sha256:55764f5cd96e0aef92cd74df31e85b133706d34ee305792fb185cdf7d201b0c0`, oluşturma 2026-10-12T06:16:02Z, katmanlar 2,59 GB, giriş noktası `/app/llama-server`, kullanıcı tanımsız (root; overlay 65534 verir). Registry API'den okundu; imaj indirilmedi.

## 3. Ölçüm tabloları (yapılacak)

Komut: `SCENARIOS=qa QA_CLIENTS=1,2,4,8 QA_INGEST=0 QA_SECONDS=120 node scripts/load-test.mjs`; her koşudan önce `(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1MB` ≥ 5 GB; VRAM `nvidia-smi --query-gpu=memory.used --format=csv` (koşu sırasında en yüksek).

### 3.1 Ollama, `gemma4:e2b`, `OLLAMA_NUM_PARALLEL` = `VERSO_QA_CHAT_CONCURRENCY`

| N | İstemci | Cevap/sn | p50 | p95 | p99 | 503 | VRAM |
|---|---|---|---|---|---|---|---|
| 1 (taban, 2026-10-24) | 4 | ≈1,1 | 3,32 sn | 5,68 sn | 6,40 sn | 0 / 139 | ölçülmedi |
| 2 / 4 / 8 | 1, 2, 4, 8 | ölçülmedi | | | | | |

### 3.2 llama-server (`deploy/compose.llamacpp.yaml`), `LLAMA_PARALLEL`

| N | İstemci | Cevap/sn | p50 | p95 | p99 | 503 | VRAM |
|---|---|---|---|---|---|---|---|
| 2 / 4 / 8 | 1, 2, 4, 8 | ölçülmedi | | | | | |

### 3.3 Kalite (eval alt kümesi; seçilen yapılandırma ve taban)

| Yapılandırma | Atıf | Bilgi | "Bulunamadı" |
|---|---|---|---|
| Taban (Ollama, 1 yuva) | ölçülmedi | | |
| Seçilen | ölçülmedi | | |

## 4. Yığının durumu

Ölçüme başlanmadığı için yığına dokunulmadı: container, volume, model ve yedek değişmedi. Docker Desktop kullanıcı tarafından yeniden açılmalı (lisans onayı; gerekirse `%LOCALAPPDATA%\Docker\run\dockerInference` eski soket dosyası silinerek), ardından yığın `docker compose -f compose.yaml -f deploy/compose.gpu.yaml up -d --wait` ile normal durumuna döner.
