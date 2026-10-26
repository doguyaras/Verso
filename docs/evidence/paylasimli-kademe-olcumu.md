# Paylaşımlı kademe: sohbet yuvası darboğazı (ADR-0051)

- **Tarih:** 2026-10-26
- **Dal:** `feature/doguyaras-esz-model` (uygulama), `feature/doguyaras-esz-model-olcum` (ölçüm)
- **Durum:** Ollama paralelliği (N = 1 / 2 / 4) ölçüldü (bölüm 3.1). llama-server canlıda yüklendi ve cevap verdi, ama yük ölçümü Docker Desktop kapatılınca yarıda kaldı (3.2). Kalite (eval) ve b11889 taraması yapılmadı.

## 1. İlk oturum: neden ölçülmedi

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

## 3. Ölçüm (2026-10-26, ikinci oturum)

- **Yığın:** `compose.yaml` + `deploy/compose.gpu.yaml`, uygulama imajı `origin/develop` `49f6d9f`'den yeniden derlendi (ADR-0051 dahil). Gözlem profili kapalı. Makinede başka işler de açıktı (tarayıcı, Discord, başka projenin 4 container'ı).
- **Yedek:** ölçümden önce `backup once`: `verso-20261026T181736Z.dump.gpg`, ok (29 tablo).
- **Yük:** `SCENARIOS=qa QA_CLIENTS=1,4,8 QA_INGEST=0 QA_SECONDS=120 node scripts/load-test.mjs`. CI hesabı, 11 PDF'lik külliyat, eval v2 karışımı (her beşinci soru cevaplanamaz, modele gitmez). Kapalı döngü, 503'te `Retry-After` kadar bekleme. Her koşu yaklaşık 2 dk.
- **VRAM:** koşu boyunca 2 sn'de bir `nvidia-smi memory.used`. Bu, **bütün GPU'nun** kullanımıdır: masaüstü, tarayıcı ve Discord da içindedir (boşta 1,5–4,7 GB arasında değişti). Bu yüzden yalnız kaba bir üst sınırdır.
- **Ham sonuçlar:** `eval/results/paylasimli-ollama-p{1,2,4}.json`.

### 3.1 Ollama, `gemma4:e2b`, `OLLAMA_NUM_PARALLEL` = `VERSO_QA_CHAT_CONCURRENCY` = N

"Cevap/sn" = 200 dönen cevap / koşu süresi. Gecikme yalnız 200 dönenler içindir.

| N | İstemci | İstek | Cevap/sn | p50 | p95 | p99 | 503 (11002 / 10030) | GPU kullanımı, en çok |
|---|---|---|---|---|---|---|---|---|
| 1 | 1 | 59 | 0,49 | 1,38 sn | 4,31 sn | 26,6 sn¹ | 0 | 7,5 GB |
| 1 | 4 | 115 | 0,89 | 3,87 sn | 5,82 sn | 7,31 sn | 6 / 0 | |
| 1 | 8 | 169 | 1,02 | 5,29 sn | 6,29 sn | 7,11 sn | 33 / 5 | |
| 2 | 1 | 78 | 0,65 | 1,12 sn | 2,42 sn | 21,6 sn¹ | 0 | 6,9 GB |
| 2 | 4 | 174 | 1,40 | 2,51 sn | 5,61 sn | 8,02 sn | 3 / 0 | |
| 2 | 8 | 209 | 1,56 | 4,20 sn | 6,82 sn | 9,11 sn | 15 / 4 | |
| **4** | 1 | 80 | 0,66 | 1,13 sn | 2,47 sn | 22,1 sn¹ | 0 | 6,3 GB |
| **4** | **4** | 193 | **1,60** | **2,01 sn** | 6,21 sn | 10,4 sn | **0 / 0** | |
| **4** | 8 | 188 | 1,45 | 5,19 sn | 8,99 sn | 13,2 sn | 3 / 4 | |

¹ Tek istemcide her N'de bir cevap 21–27 sn sürdü; 60–80 istekte p99 fiilen bu tek cevaptır. Aynı soru karışımı her koşuda aynı sırayla sorulur; uzun bir cevap ya da koşu başındaki ilk sohbet çağrısı olabilir (ayrıştırılmadı). p95 1. satırda 4,3 sn, N ≥ 2'de 2,4–2,5 sn.

Okuma:
- **4 istemcide:** N=1'den 4'e verim 0,89'dan 1,60 cevap/sn'ye çıkıyor (+%80), p50 3,9 sn'den 2,0 sn'ye iniyor, `503` 6'dan 0'a düşüyor. p99 7,3 sn'den 10,4 sn'ye çıkıyor: aynı anda dört üretim GPU'yu paylaşıyor. Bütçe (local-GPU p99 15 sn, ADR-0008) tutuyor.
- **8 istemcide:** N=2 en yüksek verimi veriyor (1,56). N=4'te `503` 38'den 7'ye iniyor ama p99 13,2 sn, bütçeye yakın. Kalan `503 10030`'lar soru embedding bulkhead'inden (4) geliyor: sohbet yuvası artınca darboğaz embedding'e kayıyor.
- **GPU belleği:** N ile ölçülebilir bir artış görülmedi (6,3–7,5 GB arası farklar masaüstü kullanımındaki oynamadan büyük). `gemma4:e2b`'nin yuva başına KV önbelleği bu ölçümün çözünürlüğünün altında kaldı.

### 3.2 llama-server (`deploy/compose.llamacpp.yaml`), `LLAMA_PARALLEL`

Canlıda doğrulanan:
- İmaj (`server-cuda-b11561`) çekildi, `deploy/llamacpp/serve.sh` Ollama'nın `gemma4:e2b` blob'unu 4 sn'de SHA-256 ile doğruladı.
- llama.cpp upstream bu GGUF'u yükledi: `n_slots = 2, n_ctx_slot = 4096`, model 3 sn'de yüklendi. İki "control-looking token" uyarısı var; yükleme etkilenmedi.
- Container healthy oldu, `ollama ps` yalnız `bge-m3:567m`'i gösterdi (sohbet modeli Ollama'da yüklü değil).
- Uygulama `VERSO_CHAT_PROVIDER=openai`, `VERSO_QA_CHAT_CONCURRENCY=2` ile healthy oldu ve llama-server'dan cevap aldı.

Ölçüm **tamamlanmadı.** Tek istemcili ilk koşunun ortasında (22:17 yerel saat) Docker Desktop tepsi menüsünden kapatıldı (`com.docker.backend.exe.log`: `POST /app/quit`, `actionMenuQuit`). O ana kadar 53 cevap geldi: p50 1,76 sn, p95 5,68 sn, en uzun 8,17 sn. Koşu tamamlanmadığı için bu sayılar karşılaştırmaya girmez. Bu sırada GPU kullanımı en çok 5,2 GB'tı (bge-m3 Ollama'da, `gemma4:e2b` llama-server'da, 2 yuva). LLAMA_PARALLEL 4 ve 8 koşulmadı.

| N | İstemci | Cevap/sn | p50 | p95 | p99 | 503 | GPU kullanımı |
|---|---|---|---|---|---|---|---|
| 2 / 4 / 8 | 1, 4, 8 | ölçülmedi (Docker kapatıldı) | | | | | |

### 3.3 Kalite

Ölçülmedi: eval, llama-server koşularından sonra yapılacaktı. Ollama'da `OLLAMA_NUM_PARALLEL` modeli ve prompt'u değiştirmez; toplu işleme sayısal küçük farklar getirebilir. Bunun cevaplara etkisi ölçülmedi.

### 3.4 llama.cpp b11889 taraması

Yapılmadı: Docker kapatıldı. `docs/versions.md`'deki not geçerli: b11889 yeniden taranacak; allowlist'teki bulguları temizlerse ona geçilecek.

## 4. Yığının durumu

Docker Desktop kullanıcı tarafından kapatıldığı için yığın normal duruma döndürülemedi. Kapanırken container'lar **llama-server düzeninde** kaldı: `verso-llama-1` var, Ollama yalnız embedding'i ısıtıyor, uygulama sohbeti llama-server'a gönderiyor. Container'ların `restart: always` ayarı var. Docker yeniden açıldığında bu düzen geri gelir. Normal yığına dönmek için `C:\verso`'da:

```bash
docker compose -f compose.yaml -f deploy/compose.gpu.yaml up -d --wait --remove-orphans
docker exec verso-ollama-1 ollama ps        # bge-m3:567m ve gemma4:e2b
```

`--remove-orphans` `verso-llama-1`'i kaldırır; Ollama ve uygulama varsayılan ayarlarla (1 yuva, Ollama sohbeti) yeniden oluşturulur. Uygulama imajı `verso:local` develop'tan (`49f6d9f`) derlendi. `secrets/SECRET_LOCAL_MODEL_API_KEY` `C:\verso\secrets`'ta oluşturuldu (dev-secrets.sh). Worktree'ye kopyalanan CI istemci sırrı silindi. Verilere dokunulmadı.
