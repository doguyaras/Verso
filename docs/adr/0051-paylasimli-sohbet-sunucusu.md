# ADR-0051: Paylaşımlı kademe için sohbet sunucusu (tek sohbet yuvası darboğazı)

- **Durum:** Önerildi. Uygulama tarafı (sağlayıcı seçimi, yapılandırma denetimi, iç ağ kuralı, akış ve iptal) uygulandı ve testli. **Canlı ölçüm ve kalite karşılaştırması yapılmadı:** 2026-10-26'da Docker Desktop açılışta çöktü (aşağıda "Ölçüm durumu"). Varsayılanlar bu yüzden değişmedi.
- **Tarih:** 2026-10-26
- **Karar verenler:** doguyaras
- **İlgili eşik (referans Bölüm 24):** `verso.qa.chat-concurrency` (başlangıç ayarı, 1); `OLLAMA_NUM_PARALLEL` (1); `LLAMA_PARALLEL` (4, yalnız overlay)
- **Genişlettiği:** ADR-0006 (local modda dışarı yol yok), ADR-0008 (sıcak yol bütçesi), ADR-0012 (bulkhead), ADR-0013 (OpenAI uyumlu istemci, anahtar yalnız `/run/secrets`), ADR-0033 (model deposu ve digest), ADR-0035 (akış ve iptal)

## Bağlam

- Yük ölçümü (`docs/evidence/yuk-olcumu.md`): darboğaz veritabanı değil, tek sohbet yuvası. 4 istemcide p99 bütçe içinde (≤ 11 sn / 15 sn) ama isteklerin %0–10'u `503 11002` alıyor; TTFT'nin büyük kısmı yuva beklemesi.
- `chat-concurrency` 1'in gerekçesi CPU'ydu (faz 8): Ollama bir modelde aynı anda tek istek işliyordu, ikinci cevap yalnız kuyrukta bekliyordu. GPU'da bu gerekçe zayıfladı (`docs/capacity.md` "GPU" bulgu 3).
- Araştırma notları (`technologies_dependencies.md`, `regulation_markets_hardware.md`): "Ollama yalnız tek kullanıcılı pilot için; paylaşımlı kullanımda vLLM ya da `llama-server --parallel`". Eşzamanlılık kullanıcıların %1–3'ü (katı %5) varsayılır: 50 kullanıcı → 1–2 (3) eşzamanlı, 200 → 4–6 (10), 1.000 → 15–30 (50). Bu bir çıkarımdır (`[Inference]`), ölçüm değil.

## Seçenekler

| Seçenek | Artı | Eksi |
|---|---|---|
| A. Ollama'nın paralelliği (`OLLAMA_NUM_PARALLEL` + `chat-concurrency`) | Yeni bileşen yok; tek ayar | Ollama'nın toplu işleme verimi llama-server'ınkinden düşük bildirilir (ölçülmedi); paylaşımlı kullanım için önerilmiyor (araştırma notu) |
| **B. llama.cpp `llama-server` (MIT), OpenAI uyumlu API, sürekli toplu işleme** | Aynı GGUF dosyası (yeni model indirmesi ve yeni tedarik zinciri yok); yuva sayısı açık; `--api-key-file`; CUDA imajı ~2,6 GB | Yeni imaj; GGUF ayrıştırıcıda geçmiş heap taşmaları ve `--rpc` RCE danışmaları (araştırma notu): `--rpc` kapalı, iç ağ |
| C. vLLM | En yüksek toplu verim (büyük GPU'da) | Pratikte yalnız GPU; HF biçimli ayrı model ve ayrı doğrulama zinciri; VRAM'in çoğunu önceden ayırır (`gpu_memory_utilization`), aynı 16 GB GPU'daki bge-m3 ve masaüstü kullanımıyla çakışır; imaj ≫ 5 GB; dağıtık bileşenlerde 9,8–10,0 CVE geçmişi. Araştırma: "Assess (multi-GPU only)" |
| D. SGLang | Yüksek verim | Araştırmada doğrulanmadı; C ile aynı sakıncalar |

## Karar

1. **Uygulama, sohbeti OpenAI uyumlu bir yerel sunucuya da gönderebilir; mod `local` kalır.** `VERSO_CHAT_PROVIDER=openai` local modda artık geçerlidir (`AiModeCheck`):
   - `spring.ai.openai.base-url` IP ise iç adres olmalıdır (Ollama'nın kuralı); ad ise her istekte çözülür ve tüm adresleri iç değilse istek gönderilmeden reddedilir (`LocalModelEgress`, OpenAI istemcisinin kendi OkHttp yığınına eklenir; Boot'un `InetAddressFilter` bean'i bu yığını kapsamaz). Asıl güvence ağdır: sunucu `models` ağında (`internal: true`), uygulamanın local modda dışarı yolu yoktur.
   - Anahtar bulut anahtarıyla aynı kuraldadır: yalnız `/run/secrets` (`SECRET_LOCAL_MODEL_API_KEY` → `spring.ai.openai.api-key`), ortam değişkeni ya da sistem özelliği reddedilir; `OPENAI_LOG` açıksa açılmaz.
   - Zaman sınırı local bütçedir (`verso.qa.local-chat-timeout` 90 sn; istemci `OPENAI_CHAT_TIMEOUT` 90s). Tek sohbet çağrısı, devre kesici, bulkhead, atıf doğrulaması ve akış değişmedi.
2. **Paylaşımlı kademe overlay'i: `deploy/compose.llamacpp.yaml` (B).** `ghcr.io/ggml-org/llama.cpp:server-cuda-b11561@sha256:20c6e722…59bb` (2026-10-12 derlemesi), `models` ağında, portsuz, salt okunur, `cap_drop: ALL`, root'suz (65534), bellek ve süreç sınırlı, GPU ayrılmış. Model indirmez: `ollama-models` volume'ünü salt okunur bağlar, `VERSO_CHAT_MODEL_DIGEST` ile adlanan blob'u (`ollama-pull`'ın doğruladığı aynı sabit digest, ADR-0033) açılışta SHA-256 ile yeniden doğrular, eşleşmezse başlamaz (`deploy/llamacpp/serve.sh`). `--parallel N`, bağlam `N × LLAMA_CTX_PER_SLOT`, tüm katmanlar GPU'da, web arayüzü ve RPC kapalı, `--reasoning-budget 0` (Ollama'daki `think: false`). Ollama yalnız embedding modelini ısıtır (`OLLAMA_WARM_CHAT=none`; sohbet modeli VRAM'e iki kez yüklenmez). Uygulamanın sohbet sınırı yuva sayısına eşitlenir (`VERSO_QA_CHAT_CONCURRENCY=${LLAMA_PARALLEL}`): fazla istek sunucunun içinde görünmeden kuyruğa girmez, 5 sn içinde `503 11002` alır.
3. **Ollama'nın paralelliği ayarlanabilir oldu (A):** `OLLAMA_NUM_PARALLEL` (compose, varsayılan 1) ve `VERSO_QA_CHAT_CONCURRENCY` (varsayılan 1). İkisi birlikte artırılır; uygulama sınırı sunucununkini aşmamalıdır.
4. **Varsayılanlar değişmez** (1 yuva, Ollama). Ölçüm olmadan sayı değiştirmek AGENTS.md'nin "başlangıç ayarı ölçümle değişir" kuralına aykırıdır.

### Güvenceler

| Güvence | Nasıl korunur |
|---|---|
| Local modda model çağrısı makineden çıkmaz (ADR-0006) | Sunucu `models` (iç) ağında; uygulama overlay'de yeni ağ almaz; `AiModeCheck` IP literalini, `LocalModelEgress` her istekte çözülen adresi denetler; mesajlarda adres yok |
| Model sabit digest ile doğrulanır (ADR-0033) | Aynı pin; `serve.sh` dosya içeriğinin SHA-256'sını karşılaştırır; çalışan sunucu depoyu yazamaz (`:ro`) |
| Anahtar yalnız `/run/secrets` (llm-rules 1.4) | `AiModeCheck.requireSecretKey`; `--api-key-file` (argümanda anahtar yok) |
| Tek çağrı, sınır, iptal (ADR-0008, ADR-0035) | Zaman aşımında çağrıyı bekleyen sanal iş parçacığı kesilir, OkHttp soketi kapanır; akışta abonelik bırakılır, bağlantı kapanır. İkisi de sahte sunucuyla test edildi (`OpenAiCompatibleCancellationTest`, `LocalOpenAiModeTest`); llama-server'ın kapanan bağlantıda üretimi bıraktığı canlı ölçülmedi |
| İçerik log'a gitmez (llm-rules 2.1) | Değişmedi: yalnız istisna türü loglanır; llama-server varsayılan ayrıntı düzeyinde istem içeriği yazmaz (canlı doğrulanmadı) |

## Ölçüm durumu ve plan

2026-10-26: Docker Desktop 4.80.0 açılışta `initializing Inference manager … dockerInference` hatasıyla çöktü (eski bir soket dosyası); hata penceresinde "Reset to factory defaults" seçildi, ayarlar sıfırlandı (`settings-store.json`), veri diski (`docker_data.vhdx`, 110 GB) yerinde. Yeniden açılış lisans onayı ister; ajan onu veremez. Bu yüzden şunlar **yapılmadı**:

1. Ollama, `gemma4:e2b`, `OLLAMA_NUM_PARALLEL` = `chat-concurrency` = 2 / 4 / 8: `SCENARIOS=qa QA_CLIENTS=1,2,4,8 QA_INGEST=0 QA_SECONDS=120 node scripts/load-test.mjs` (en çok 8 istemci, koşu başına ≤ 5 dk); verim, p50/p95/p99, 503 sayısı, `nvidia-smi` VRAM.
2. Aynısı `deploy/compose.llamacpp.yaml` ile `LLAMA_PARALLEL` = 2 / 4 / 8.
3. Seçilen yapılandırma ile taban arasında `scripts/eval.mjs` alt kümesi (atıf, bilgi, "bulunamadı").
4. Canlıda: llama-server'ın Ollama blob'unu (Ollama'nın `gemma4:e2b` GGUF'u) yükleyip yüklemediği, sohbet şablonu ve `--reasoning-budget 0` davranışı, kapanan bağlantıda üretimi bırakması, root'suz kullanıcıyla anahtar dosyasını okuyabilmesi.

Kabul ölçütü: 4 istemcide `503 11002` ≈ 0 ve p99 ≤ 15 sn (ADR-0008), eval metrikleri tabanın gürültü payı içinde. Hangisi kazanırsa paylaşımlı kademe odur; sonuç `docs/capacity.md`'ye, varsayılanlar ancak o zaman değişir.

## Kademeler (öneri; donanım araştırmadan, Verso'da ölçülmedi)

| Kademe | Eşzamanlı soru (%1–3, katı %5) | Sunucu | Donanım (araştırma notu) |
|---|---|---|---|
| Tek kullanıcı / küçük ekip | 1 (varsayılan) | Ollama, 1 yuva | CPU (cevap 20–60 sn) ya da tek tüketici GPU'su |
| 50 kullanıcı | 1–2 (3) | Ollama 2–4 yuva ya da llama-server | Tek tüketici GPU'su (ör. 16 GB) ya da birleşik bellekli kutu (DGX Spark, Strix Halo), 8–14B ya da 30B-A3B MoE |
| 200 kullanıcı | 4–6 (10) | llama-server ya da vLLM | Bir RTX PRO 6000 (96 GB) ya da 2× RTX 5090, 14–32B FP8/INT4, ~16 yuva |
| 1.000 kullanıcı | 15–30 (50) | vLLM | İki 96 GB GPU'lu sunucu (aktif/aktif) ya da H100/H200 düğümü; bge-m3 küçük GPU ya da CPU'da |

## Sonuçlar

- Yeni yüzeyler: `deploy/compose.llamacpp.yaml`, `deploy/llamacpp/serve.sh`, `SECRET_LOCAL_MODEL_API_KEY` (`scripts/dev-secrets.sh`), `OLLAMA_NUM_PARALLEL`, `VERSO_QA_CHAT_CONCURRENCY`, `OPENAI_CHAT_TIMEOUT`, `LLAMA_PARALLEL`, `LLAMA_CTX_PER_SLOT`, `LLAMA_MEM_LIMIT`.
- Overlay'siz kurulumun davranışı değişmez (aynı değerler, aynı sağlayıcı).
- Geri dönüş: overlay'i çıkarmak; Ollama sohbet modelini yeniden ısıtır.
- llama.cpp imajı Syft/Grype ile taranmadı (ADR-0030 geçidi); overlay ölçümden önce taranacak.

## Yeniden değerlendirme koşulu

- Ölçüm yapıldığında (bu ADR'nin durumu ve varsayılanlar ona göre güncellenir).
- Gemma4'ün Ollama GGUF'u upstream llama.cpp'de yüklenmezse: aynı model için upstream GGUF'u ayrı bir digest ve doğrulama ile getirmek ya da A'da kalmak.
- 200+ kullanıcılı bir kurulum ya da çoklu GPU: vLLM yeniden değerlendirilir.
