# ADR-0062: Model takılması yalıtımı (bir sohbet üretimi servisi düşürmesin)

- **Durum:** Önerildi. Compose topolojisi, uygulama tarafı ve testler uygulandı. Birim, yapılandırma ve script testleri yerelde geçti. Spring bağlamlı testler (`OllamaChatClientTest`, `MetricsTest`) ve `promtool` testleri CI'da koşar. Canlı yığında doğrulama bekleniyor (aşağıda "Doğrulama planı").
- **Tarih:** 2026-10-30
- **Karar verenler:** doguyaras
- **İlgili eşik (referans Bölüm 24):** `OLLAMA_MEM_LIMIT` (2g), `OLLAMA_CHAT_MEM_LIMIT` (5g), `verso.qa.first-token-timeout` (60 sn). Üçü de başlangıç ayarıdır.
- **Genişlettiği:** ADR-0006 (local modda dışarı yol yok), ADR-0008 (sıcak yol bütçesi, tek çağrı, devre kesici), ADR-0011 (embedding ve ingestion), ADR-0033 (model deposu), ADR-0035 (akış ve iptal), ADR-0051 (llama-server kademesi)

## Bağlam

**Olay (2026-10-29, canlı yığın, RTX 4070 Ti SUPER, iki model de %100 GPU, 350 soruluk eval):**

- Bir `/api/chat` isteği Ollama içinde 4 dk 10 sn asılı kaldı ve 500 döndü. Ardından Ollama logu `llama-server process no longer running ... signal: killed` yazdı.
- Hemen öncesinde llama.cpp `restored context checkpoint` ve `erased invalidated context checkpoint` satırları yazdı (`n_swa = 512`: gemma'nın kayan pencereli dikkati ve istem önbelleği).
- Takılma süresince **4 dakika boyunca hiçbir `/api/embed` isteği cevaplanmadı.**
  - Uygulamanın sohbet çağrısı 90 sn'de kesildi (`verso.qa.local-chat-timeout`): 503 `MODEL_UNAVAILABLE`/`TIMEOUT`.
  - Ardından soru embedding'leri 10 sn bütçede düştü (`10030 EMBEDDING_MODEL_UNAVAILABLE`). Sonraki 33 soru 10 sn sonra 503 aldı.
- Önceki bir koşuda aynı desen 3 soruda görüldü (bir 90 sn zaman aşımı, ardından iki 10 sn'lik 503).
- `num-predict` zaten 512. Yani uzun cevap değil, çalıştırıcı (runner) duruyor.

**Kök neden analizi.** Kanıt ve kaynaklar aşağıda; "çıkarım" diye işaretlenenler kodla gösterilemedi.

1. **Ollama, sohbet modeline llama.cpp varsayılanlarını bırakıyor.** Ollama her GGUF modelini ayrı bir `llama-server` alt süreciyle çalıştırır. Bu sürece `-c`, `-np`, `--flash-attn` ve KV türünü geçirir; `--cache-ram` ve `--ctx-checkpoints` geçirmez. `--cache-ram 0` yalnız "decision" modellerine verilir ([ollama `llm/llama_server.go`](https://raw.githubusercontent.com/ollama/ollama/main/llm/llama_server.go)).
   - Sonuç: gemma4 için llama-server varsayılanları geçerlidir. Bunlar ana bellekte (host RAM) 8192 MiB'a kadar istem önbelleği ve yuva başına 32 context checkpoint'tir ([llama.cpp server README](https://raw.githubusercontent.com/ggml-org/llama.cpp/master/tools/server/README.md), [PR 16391](https://github.com/ggml-org/llama.cpp/pull/16391)).
2. **Gemma'nın SWA checkpoint'leri belleği büyütüyor ve takılmaya yol açıyor.**
   - [llama.cpp 21690](https://github.com/ggml-org/llama.cpp/issues/21690): Gemma 4 checkpoint'leri zamanla RAM'i bitene kadar büyütür. Geçici çözüm `--ctx-checkpoints 0/1` ve `--cache-ram 0`; kalıcı düzeltme yok.
   - [llama.cpp 24265](https://github.com/ggml-org/llama.cpp/issues/24265): istem önbelleği ve checkpoint'lerle Gemma'da istem işleme duruyor, istemci zaman aşımına düşene kadar. Rapor 2026-08, Docker'da. İkisi de kapalıyken ilk raporlayan için kararlı. Konu düzeltilmeden kapandı.
   - [llama.cpp 21769](https://github.com/ggml-org/llama.cpp/issues/21769): Gemma 4 SWA checkpoint geri yüklemesinden sonra model döngüye giriyor.
   - [llama.cpp 22629](https://github.com/ggml-org/llama.cpp/issues/22629): Linux'ta `--cache-ram` sınırı uygulanmıyor, süreci çekirdeğin OOM öldürücüsü alıyor.
   - [PR 20671](https://github.com/ggml-org/llama.cpp/pull/20671) (b8393, 2026-03) bir checkpoint geçersizleştirme hatasını düzeltti. Gemma takılmalarını kapatan bir düzeltme bulunamadı.
3. **Aynı container'ın bellek sınırı ikisini birden boğuyor.** `docs/capacity.md` ("Bellek") iki model yüklüyken Ollama container'ının 6 GiB sınırına dayandığını ölçmüştü (`memory.peak` 6 GiB).
   - Bu sınırın üstüne istem önbelleği ve checkpoint büyümesi eklenince cgroup belleği dolar. Aynı cgroup'taki iki runner da (bge-m3 dahil) bellek geri kazanımına takılır. Sonunda çekirdek ya da Ollama, chat runner'ını SIGKILL ile öldürür.
   - `signal: killed` ve 4 dakikalık tam embedding kesintisi bu tabloyla tutarlıdır. Bu bir **çıkarımdır**: olay anında `memory.events`/`oom_kill` sayacı okunmadı.
   - Ollama'da üretim sırasında takılan runner'ı öldüren bir bekçi yoktur; süreç yalnız boşaltma, `unexpected EOF` ya da OOM ile ölür ([`llama_server.go`](https://raw.githubusercontent.com/ollama/ollama/main/llm/llama_server.go)).
   - Zamanlayıcı ikinci bir yol da sunar: yüklü olmayan bir model, yer açmak için meşgul runner'ın bitmesini bekler ([`server/sched.go`](https://raw.githubusercontent.com/ollama/ollama/main/server/sched.go)). `OLLAMA_KEEP_ALIVE=-1` ile bge-m3 hep yüklü olduğu için bu yolun olayda etkili olduğu **doğrulanmadı**.
4. **İstemci kopunca ne oluyor?**
   - Ollama `c.Request.Context()`'i llama-server isteğine geçirir: bağlantı kapanınca alt istek iptal edilir.
   - llama-server bağlantıyı saniyede bir yoklar ve kopan isteği iptal eder ([PR 11285](https://github.com/ggml-org/llama.cpp/pull/11285); karşıt rapor [24496](https://github.com/ggml-org/llama.cpp/issues/24496), bakımcı "çalışıyor" dedi).
   - Uygulama zaman aşımında çağrıyı zaten kesiyordu (faz 8, `docs/capacity.md`). Ama **takılmış bir runner iptali işleyemez.** 90 sn'de kopan istemciye rağmen Ollama isteği 4 dk 10 sn sonra kapattı. Yani iptal gereklidir ama yeterli değildir; asıl çare yalıtımdır.
5. **Uygulama tarafında paylaşılan bir kesici yok.**
   - Sohbet devre kesicisi (`ModelCircuitBreaker`) yalnız sohbet hatalarını sayar. Soru embedding'inin kendi bulkhead'i ve 10 sn sınırı vardır (`DocumentRetrievalServiceImpl`), ingestion'ın kendi duraklaması vardır. Olaydaki 33 hata, Ollama sunucusu embedding'e cevap vermediği için oldu, bir kesicinin açılmasından değil.
   - Paylaşılan tek şey **model sunucusunun kendisiydi**: aynı süreç, aynı bellek sınırı, aynı `OllamaApi`.

**Ayarlar ve sürüm.**

- Ollama'da: `OLLAMA_NUM_PARALLEL` (runner başına), `OLLAMA_MAX_LOADED_MODELS`, `OLLAMA_KEEP_ALIVE`, `OLLAMA_CONTEXT_LENGTH`, `OLLAMA_FLASH_ATTENTION`, `OLLAMA_KV_CACHE_TYPE`, `OLLAMA_LOAD_TIMEOUT` (yalnız yükleme). Hiçbiri istem önbelleğini ya da checkpoint'leri ayarlamaz ([`envconfig/config.go`](https://raw.githubusercontent.com/ollama/ollama/main/envconfig/config.go)).
- `OLLAMA_FLASH_ATTENTION=0` başka bir gemma4 takılmasının geçici çözümüydü ([ollama 15350](https://github.com/ollama/ollama/issues/15350)). O takılma uzun istemlerdeydi ve v0.20 civarında kapandı.
- llama-server ayarları `LLAMA_ARG_*` ortam değişkenlerinden de okunur (`LLAMA_ARG_CACHE_RAM`, `LLAMA_ARG_CTX_CHECKPOINTS`, `LLAMA_ARG_SWA_FULL`, ...; [`common/arg.cpp`](https://raw.githubusercontent.com/ggml-org/llama.cpp/master/common/arg.cpp)). Ollama alt süreci `cmd.Env = os.Environ()` ile başlatır, yani container ortamı runner'a geçer. Bu **koddan çıkarımdır**, canlıda doğrulanacak.
- Ollama 0.40.10 için bu sorunu kapatan bir yama sürümü bulunamadı (**net kanıt bulunamadı**).

## Seçenekler

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| **A. Embedding ve sohbet için iki Ollama container'ı** (`ollama`, `ollama-chat`) | Aynı imaj, aynı salt okunur model volume'ü, aynı `ollama-pull`. Hava boşluklu (air-gapped) pakete yeni imaj, sır ya da indirme eklenmez. CPU ve GPU'da aynı topoloji. Bellek sınırı, süreç ve OOM ayrı | Bir container daha. Toplam bellek sınırı 6 → 7 GiB. GPU'da iki CUDA bağlamı (~0,3–0,5 GB VRAM, ölçülmedi) | Compose, uygulamada sohbet istemcisinin ayrı adresi |
| B. GPU overlay'inde llama-server varsayılan olsun (ADR-0051), Ollama yalnız embedding | Ölçüldü: 8 istemcide 1,93 / 1,02 cevap/sn | Yalnız GPU: CPU varsayılanı açık kalır. Air-gapped pakete ~2,6 GB CUDA imajı ve `SECRET_LOCAL_MODEL_API_KEY` eklenir. llama-server aynı gemma checkpoint sorununu taşır (24265) | Overlay değişikliği, kurulum adımı |
| C. Tek Ollama, yalnız ayar (`OLLAMA_MEM_LIMIT` artırma, önbelleği kapatma) | Yeni container yok | Bir sonraki takılma (bilinen ya da bilinmeyen) yine embedding'i durdurur. Yalıtım yok | Düşük |
| D. Takılan runner'ı yeniden başlatan bekçi | Kendini onarır | Ollama'da kanca yok; dışarıdan `docker restart` için Docker soketi gerekir (güvenlik), embedding yine aynı süreçte | Yüksek |

## Karar

1. **A: iki Ollama container'ı, varsayılan yığının kendisinde** (CPU ve GPU).
   - **`ollama`** yalnız embedding'i tutar:
     - `OLLAMA_WARM_CHAT=none`, `OLLAMA_MAX_LOADED_MODELS=1`;
     - sağlık kontrolü yalnız `bge-m3:567m`;
     - `mem_limit ${OLLAMA_MEM_LIMIT:-2g}`.
   - **`ollama-chat`** yalnız sohbet modelini tutar:
     - aynı imaj, `models` ağı, salt okunur volume ve sertleştirme;
     - `OLLAMA_WARM_EMBEDDING=none`;
     - `mem_limit ${OLLAMA_CHAT_MEM_LIMIT:-5g}`.
   - Bir model diğerinin sunucusuna istek atsa bile, `MAX_LOADED_MODELS=1` olduğu için embedding modeli sohbet yüzünden bellekten atılmaz.
   - `deploy/ollama/serve.sh` her iki tarafta da `none`'ı kabul eder.
   - GPU overlay'i iki container'a da GPU ayırır.
2. **Uygulamanın sohbet modeli kendi sunucusuna gider.**
   - Yeni ayar: `verso.ai.ollama-chat-base-url` (`OLLAMA_CHAT_BASE_URL`, compose'da `http://ollama-chat:11434`).
   - Spring AI tek bir `OllamaApi` kurar. `OllamaChatIsolation`, otomatik kurulan `OllamaChatModel`'i aynı seçeneklerle kendi `OllamaApi`'si üzerinde yeniden kurar. Bunu Boot'un builder'larıyla yapar, böylece yalnız-iç-adres filtresi, zaman aşımları ve Reactor Netty korunur. İstemci tekrar denemesi yoktur (ADR-0008).
   - Boş ayar paylaşılan sunucuyu bırakır (bir IDE koşusu, tek Ollama).
   - `AiModeCheck`, IP literal'i olan bir sohbet adresini embedding adresiyle aynı kurala bağlar: iç adres değilse uygulama başlamaz.
3. **Runner belleği sınırlı tutulur.** `ollama-chat` ve llama overlay'i `LLAMA_ARG_CACHE_RAM=0` ve `LLAMA_ARG_CTX_CHECKPOINTS=0` ile çalışır.
   - Her Verso istemi farklı pasajlar taşır, bu yüzden istem önbelleğinin kazancı küçüktür.
   - Bedeli: sistem istemi önekinin yeniden işlenmesi. Bu canlıda ölçülecek.
4. **llama-server kademesi (ADR-0051) seçimlik kalır.** Overlay `ollama-chat`'i etkinleştirilmeyen bir profille (`replaced-by-llama`) durdurur. Sohbet modeli VRAM'e iki kez yüklenmez; `ollama` zaten yalnız embedding'i tutar.
5. **Uygulama tarafı:**
   - **İptal (var olan, test edildi).** Zaman aşımında sohbeti bekleyen sanal iş parçacığı kesilir; Reactor Netty isteği iptal eder, bağlantı kapanır. Ollama akışsız cevapta hiçbir baytı üretim bitmeden göndermez, yani bekleyen istemci yanıt başlıklarını bekler; bu durumda kesme bağlantıyı kapatır (`OllamaChatIsolationTest`).
   - **Ayrı bulkhead'ler (var olan, test edildi).** Embedding hataları sohbet devre kesicisini açmaz. Takılan sohbet yalnız kendi yuvasını tutar: sonraki soru yine embedding'e ve aramaya gider, sohbet yuvası için `11002` alır. Zaman aşımında yuva geri gelir (`ChatStallTest`). Model sunucusu düzeyindeki yalıtım madde 1'dir.
   - **İlk token sınırı (yeni).** Akışta model `verso.qa.first-token-timeout` (60 sn) içinde ilk token'ı göndermezse çağrı bırakılır. Abonelik iptal edilir, bağlantı kapanır, yuva 90 sn beklemeden boşalır.
     - Kullanıcıya giden hata aynıdır: `11001 MODEL_UNAVAILABLE`, log nedeni `FIRST_TOKEN_TIMEOUT`.
     - 60 sn, CPU'da ölçülen en uzun tam cevabın (67 sn, çoğu üretim) ilk token payının üstündedir.
     - JSON (akışsız) yolda ilk token görünmez; orada sınır 90 sn kalır.
   - **Takılma sinyali (yeni, ucuz).** `verso_qa_chat_stalls_total{phase="first_token"|"timeout"}` sayacı (yalnız cevap çağrısı, açılışta 0 ile kayıtlı). Alarm **`ChatStalled`** (page): 10 dk'da en az 2 takılma ve hiç cevap yoksa ateşlenir; runbook `docs/runbooks/model-unavailable.md`.
6. **Dokunulmayanlar:** belge parçalama ve indeksleme kodu (ADR-0061 ayrı dalda), embedding bütçesi (10 sn, 4 eşzamanlı), sohbet zaman aşımı (90 / 30 sn), kullanıcıya giden hata kodları.

## Sonuçlar

- **Olumlu:**
  - Bir sohbet takılması, bellek taşması ya da OOM öldürmesi artık embedding sunucusuna ulaşmaz. Arama, MCP `verso_search`, onaylı kart eşleşmesi ve belge işleme sürer; yalnız cevaplar 503 alır.
  - Runner belleği container sınırının altında kalır.
  - Takılma Prometheus'ta adıyla görünür.
- **Olumsuz / kabul edilen risk:**
  - Toplam bellek sınırı 6 → 7 GiB (2 + 5). Küçük bir CPU makinesinde `OLLAMA_CHAT_MEM_LIMIT` ölçülerek düşürülebilir.
  - GPU'da ikinci süreç ek VRAM ayırır (ölçülecek).
  - `LLAMA_ARG_*` değişkenlerinin Ollama 0.40.10'un runner'ına ulaştığı koddan çıkarımdır. Ulaşmazsa önbellek açık kalır, ama yalıtım yine geçerlidir.
  - Önbellek kapalıyken her istek sistem istemini yeniden işler; GPU'da ihmal edilebilir, CPU'da ölçülecek.
  - Sunucu yanıt gövdesini göndermeye başladıktan sonra gelen kesme (yalnız akışsız yol), yerel testte bağlantıyı 10 sn içinde kapatmadı. Ollama akışsız cevabı tek parça gönderdiği için bugün etkisi yoktur. Gövdeyi parça parça gönderen bir sunucuda bu bir boşluktur.
  - Takılan runner'ı kendiliğinden yeniden başlatan bir mekanizma yok. Runbook, `docker compose restart ollama-chat` der.
- **Etkilenen dosyalar / servisler / config yüzeyleri:**
  - Compose: `compose.yaml` (`ollama`, yeni `ollama-chat`, `OLLAMA_CHAT_BASE_URL`), `deploy/compose.gpu.yaml`, `deploy/compose.llamacpp.yaml`, `deploy/compose.local.yaml`, `deploy/ollama/serve.sh`.
  - Ortam ve yapılandırma: `.env.example` (`OLLAMA_MEM_LIMIT` 2g, `OLLAMA_CHAT_MEM_LIMIT`, `VERSO_OLLAMA_CHAT_LOCAL_PORT`), `deploy/prod.env.example`, `config/verso.yml` (`verso.ai.ollama-chat-base-url`, `verso.qa.first-token-timeout`), `application-local.yml`.
  - Kod: `OllamaChatIsolation`, `AiModeCheck`, `QaProperties`, `QaMetrics`, `QuestionServiceImpl`.
  - Gözlem ve script'ler: `deploy/obs/prometheus/alerts.yml`, `scripts/prove-local-mode.sh`.
- **Geri alma yolu:**
  - `OLLAMA_CHAT_BASE_URL=http://ollama:11434` ve `ollama`'da `OLLAMA_WARM_CHAT`'i sohbet modeline geri almak, eski tek-sunucu düzenine döner. Kod değişmeden kalabilir; ayar boş da olabilir.
  - İlk token sınırı `verso.qa.first-token-timeout` ile 90 sn'ye çekilirse etkisiz olur.
  - **Image ve compose birlikte geri alınır.** Eski image yeni compose ile çalışırsa sohbet `ollama`'ya gider; orada `OLLAMA_MAX_LOADED_MODELS=1` olduğu için sohbet modeli embedding modelini bellekten atar. Yeni image eski compose ile çalışırsa `OLLAMA_CHAT_BASE_URL` yer tutucusu çözülemez ve uygulama başlamaz (fail-fast).

## Doğrulama planı (canlı yığın)

1. Topolojiye geçiş: README "Model sunucuları (ADR-0062)" komutları. `docker compose ps`: `ollama` ve `ollama-chat` healthy.
2. `docker compose exec ollama ollama ps` yalnız `bge-m3:567m` göstermeli. `docker compose exec ollama-chat ollama ps` yalnız `gemma4:e2b` göstermeli.
3. `LLAMA_ARG_*` runner'a ulaşıyor mu: `ollama-chat` logunda llama-server açılış satırları (`cache_ram`/checkpoint değerleri) ve uzun eval boyunca `docker stats` belleği sınırın altında, düz kalmalı.
4. Yalıtım: eval sürerken `docker compose kill -s STOP ollama-chat` (takılmayı taklit eder). Arama (`/v1/search` ya da `scripts/eval.mjs` retrieval kısmı) cevap vermeye devam etmeli. Sorular 90 sn (JSON) ya da 60 sn (akış) sonra `11001` almalı; `verso_qa_chat_stalls_total` artmalı. Sonra `docker compose kill -s CONT ollama-chat`.
5. 350 soruluk eval yeniden: 33'lük `503` dizisi görülmemeli. `verso_qa_chat_stalls_total` ve `oom_kill` (`docker inspect -f '{{.State.OOMKilled}}'`) kaydedilmeli.
6. `bash scripts/prove-local-mode.sh`: `ollama-chat` için de "internal" ve "1.1.1.1:443 unreachable" satırları.

## Yeniden değerlendirme koşulu

- `ChatStalled` ayda birden çok ateşlenirse ya da llama.cpp gemma checkpoint sorununu kapatan bir sürüm Ollama'ya girerse, `LLAMA_ARG_*` ayarları ve ilk token sınırı yeniden ölçülür.
- GPU kurulumlarında llama-server kademesi (B) eval kalitesinde gürültü payı içinde çıkarsa, GPU varsayılanı olarak yeniden ele alınır (ADR-0051).
- Toplam bellek sınırı bir müşteri makinesine sığmazsa `OLLAMA_CHAT_MEM_LIMIT` ölçülerek küçültülür.
