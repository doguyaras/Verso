# Kapasite (faz 8)

Ölçümler tek bir geliştirici makinesinde, varsayılan compose yığınıyla yapıldı. Sonuçlar bir üretim taahhüdü değildir; darboğazın nerede olduğunu ve hangi ayarın neyi değiştirdiğini gösterir. Ham sonuçlar `eval/results/` altındadır. Yeniden ölçmek için `node scripts/load-test.mjs` (ayarlar dosyanın başında).

- **Makine:** Windows 11, Docker Desktop (Docker Engine 29.6.1), 8 CPU, 16 GB RAM, GPU yok.
- **Yığın:** uygulama 2 CPU / 1,5 GB, Ollama 2 CPU / 6 GB (varsayılanlar), gözlem profili açık.
- **Modeller:** bge-m3:567m (embedding), gemma4:e2b (chat).
- **Belgeler:** `samples/` altındaki altı sentetik belge (2–3 sayfa).

## Sonuçlar

| Senaryo | Yük | Sonuç |
|---|---|---|
| Belge listesi (`GET /v1/documents`) | 20 eşzamanlı istemci, 30 sn | **841 istek/sn**, p50 14 ms, p95 49 ms, p99 55 ms, hata yok. Hesapta belge yok ya da çok azdı: bu bir üst sınırdır, dolu bir sayfanın maliyeti değil |
| Yükleme | 12 eşzamanlı yükleme | Yükleme sınırlayıcısı (instance başına 4) 7'sini kabul etti, 5'i hemen 503 10014 + `Retry-After` aldı. Kabul edilenler 14 sn'de READY oldu: **~29 belge/dk** (2–3 sayfalık belgeler) |
| Yalnız retrieval (eşik altı sorular, chat çağrısı yok) | 20 istemci, 503'te `Retry-After` kadar bekleyerek | 30 sn'de **232 cevap (~7,7 soru/sn)**, p50 0,49 sn, p95 0,8 sn. 96 istek soru embedding bulkhead'inde (4) 503 10030 aldı |
| Chat, sıralı (eval seti, tek istemci) | 33 soru art arda | İki koşu: **p50 28 / 21 sn, p95 45 / 37 sn, en çok 67 / 43 sn**, hata yok. İkinci koşu `eval/results/e2e-gemma4_e2b.json`; ilki bu dosyanın git geçmişinde. Fark koşular arası gürültüdür (makinede başka yük) |
| Chat, eşzamanlı | 2 istemci, 6 soru | Her seferinde yalnız bir soru cevaplandı (84 sn). Diğerleri 503 aldı: 11002 (chat slotu dolu) ya da 10030 (üretim sürerken soru embedding'i 10 sn'ye sığmadı) |
| Chat, eşzamanlı, Ollama 6 CPU | 2 istemci, 6 soru | Tablo değişmedi: Ollama bir modelde aynı anda tek üretim yapar. Üretim sürerken bir soru embedding'i 7,5 sn sürdü (boşta 0,25 sn) |

## Bulgular ve yapılanlar

1. **Vazgeçilen üretimler Ollama'yı dakikalarca meşgul ediyordu.** Servis bir chat çağrısında zaman aşımına düşüp 503 dönünce, alttaki HTTP isteği açık kalıyordu. Model de cevabı sonuna kadar üretiyordu. Ölçüm (Ollama'ya 6 CPU verilen koşuda): istemci gittikten sonra Ollama'nın CPU'su dakikalarca %600'deydi; bu sırada boşta 0,25 sn süren bir embedding 10 sn'yi aştı ve ardından gelen bütün sorular düştü.
   - **Düzeltme:** zaman aşımında çağrı kesiliyor (soru embedding'i ve chat). Ollama istemcisi (Reactor Netty) isteği iptal ediyor, Ollama üretimi bırakıyor. Bulut SDK'larının (OkHttp) iptali sağlayıcıya ilettiği doğrulanmadı; etkisi yalnız maliyettir.
   - **Review düzeltmesi (RS1):** iptal, görev daha başlamadan gelirse görev hiç çalışmaz ve slotunu bırakamazdı; slot kalıcı olarak kaybolurdu (chat'te tek slot olduğu için her soru 11002 alırdı). Şimdi slotu, görevi başlatan ya da başlamamış görevi iptal eden taraf bırakır. Test: `RetrievalTest.search_whenTheCallerIsInterrupted_keepsTheEmbeddingSlots`.
   - **Canlı doğrulama:** 503'ten 2 sn sonra Ollama CPU'su %0.
   - **Testler:** `QuestionApiTest.ask_whenTheModelHangs_*`, `RetrievalTest.search_whenTheEmbeddingTimesOut_interruptsTheModelCall`.
2. **Chat eşzamanlılığı 2'den 1'e indi** (`verso.qa.chat-concurrency`). Ollama bir modelde aynı anda tek istek işliyor; ikinci eşzamanlı cevap yalnız kuyrukta bekleyip iki kullanıcının da süresini uzatıyordu. Şimdi ikinci kullanıcı 5 sn içinde 503 11002 ("asistan meşgul", `Retry-After: 5`) alıyor.
   - **Çözmediği:** üretim sürerken gelen sorunun embedding'i de aynı Ollama'yı bekler ve 10 sn'ye sığmayabilir; o kullanıcı 11002 yerine 10030 alır. Eşzamanlı ölçümde reddedilen 5 isteğin 3'ü buydu. Çözümü ayrı bir embedding sunucusu (Ollama instance'ı) ya da GPU'dur.
3. **Benzerlik eşiği 0,45'ten 0,50'ye çıktı** (`eval/README.md`). Cevaplanamaz soruların yarısı modele hiç gitmeden "bulunamadı" alıyor. Bu, CPU'da her soru için yaklaşık 30 sn tasarruf demek.

## GPU (2026-10-23)

Aynı makine ve yığın; tek fark Ollama'nın GPU'yu kullanması: `docker compose -f compose.yaml -f deploy/compose.gpu.yaml up -d --wait`. Overlay yalnız GPU'yu ayırır; ağlar, model volume'ü ve bellek sınırı değişmez, local modun dışarı yolu yine yoktur (`ComposeConfigTest.gpuOverlay_*`).

- **GPU:** NVIDIA GeForce RTX 4070 Ti SUPER, 16 GB; sürücü 610.88; Docker Desktop (WSL 2). Ollama 0.35.1 CUDA ile buldu; iki model de `100% GPU`.
- **Uygulama ayarları değişmedi:** `chat-concurrency` 1, chat bekleme 5 sn, embedding bulkhead'i 4, eşik 0,50 (`config/verso.yml`).
- **Commit:** `fe906876615f`. Ham sonuçlar: `eval/results/e2e-gemma4_e2b-gpu.json`, `load-gpu.json`, `load-gpu-chat-c6.json`.

| Senaryo | CPU (faz 8) | GPU |
|---|---|---|
| Eval seti, sıralı (33 soru) | p50 21–28 sn, p95 37–45 sn | **p50 1,3 sn, p95 5,4 sn**; en çok 65 sn (ilk soru: chat modelinin diskten GPU'ya yüklenmesi) |
| Eval kalitesi | atıf isabeti 1,00, doğru bilgi 0,96, "bulunamadı" 0,875 | **Aynı** (bütün metrikler birebir) |
| Yükleme (12 eşzamanlı) | 7 kabul, ~29 belge/dk | 10 kabul, 8 sn'de READY: **~73 belge/dk** |
| Yalnız retrieval (20 istemci, 30 sn) | ~7,7 soru/sn, p50 0,49 sn | **~38 soru/sn** (1151 cevap), p50 0,10 sn, p95 0,18 sn; 96 istek embedding bulkhead'inde 503 10030 |
| Chat, 3 eşzamanlı istemci, 6 soru | (2 istemcide) 6 sorunun 1'i cevaplandı, 84 sn | **6/6 cevaplandı**, p50 3,8 sn, p95 5,8 sn (sıra beklemesi dahil) |
| Chat, 6 eşzamanlı istemci, 18 soru | – | 5 cevap; 8 × 503 11002 (chat slotu), 5 × 503 10030 (embedding bulkhead'i). Bir cevap 15,5 sn sürdü |

**Bulgular:**

1. **Tek cevabın medyan süresi 15–20 kat kısaldı; ADR-0008'in local-GPU bütçesi (15 sn) p95'te rahat tutuyor.** İstisna soğuk açılıştı: Ollama açıldıktan ya da 30 dk boş kaldıktan sonra ilk soru modeli diskten yüklüyordu, bu ölçümde 65 sn. Modeller artık açılışta yükleniyor ve bellekte kalıyor (aşağıdaki "Soğuk açılış" bölümü).
2. **Kalite değişmedi.** GPU yalnız hızı değiştirir; aynı model aynı cevapları verdi (33 sorunun metrikleri birebir).
3. **GPU'da darboğaz model değil, uygulamanın bulkhead'leri.** 3 istemcide her soru cevaplandı, çünkü bir cevap 1–5 sn sürüyor ve sıradaki soru 5 sn'lik chat beklemesine sığıyor. 6 istemcide aynı anda bir chat (`chat-concurrency` 1) ve dört soru embedding'i sınırı reddetmeye başladı. Bu ayarlar CPU için ölçülmüştü (bulgu 2); GPU'da `chat-concurrency` ve Ollama'nın `OLLAMA_NUM_PARALLEL` değerini birlikte artırmak sonraki adım. Ölçülmeden değiştirilmedi.

**Sınırlar:** her senaryo tek koşudur. Eval koşusunun başında aynı makinede bir Maven testi çalışıyordu; ilk soruların süresini etkilemiş olabilir. 6 istemcili koşuda 6 yüklemenin 2'si yükleme sınırına (503 10014) takıldı; o belgelere ait sorular "bulunamadı" döndü. Bu yüzden o satır yalnız doygunluğun nerede başladığını gösterir.

## Soğuk açılış (2026-10-23)

**Sorun:** Ollama, `OLLAMA_KEEP_ALIVE=30m` ile boşta 30 dk kalan modeli bellekten çıkarıyordu. Ardından gelen ilk soruda embedding modeli yeniden yükleniyordu; soru embedding'inin bütçesi ise 10 sn (ADR-0008). Canlıda kullanıcı 503 10030 ("Arama modeli şu an yanıt vermiyor") aldı; "Tekrar dene" ile ikinci deneme cevaplandı.

**Ölçüm:** Modeller `ollama stop` ile boşaltıldı, sonra Ollama API'sine container içinden ilk ve ikinci istek gönderildi. Betik `/dev/tcp` kullanıyor, çünkü imajda curl yok.

| Durum | İlk embedding | Sıcak embedding | İlk sohbet (1 token) | Sıcak sohbet |
|---|---|---|---|---|
| GPU, 30 dk boşta kaldıktan sonra | 9,4 sn | 14 ms | 60,5 sn | 10,1 sn |
| CPU, container yeni açılmış (dosyalar sayfa önbelleğinde) | 2,1 sn | 190 ms | 13,6 sn | 1,1 sn |

Yükleme süresi, model dosyasının o an işletim sisteminin sayfa önbelleğinde olup olmamasına göre 2 ile 60 sn arasında değişiyor. 10 sn'lik bütçe bu yüzden ilk soruda tutmayabilir. Bütçeyi büyütmek her soruyu etkilerdi; sorun yüklemenin kendisi.

**Düzeltme:**
- `deploy/ollama/serve.sh` Ollama'yı başlatıyor, ardından iki modeli bir kez yüklüyor: embedding'e kısa bir girdi, sohbete boş prompt, ikisi de `keep_alive: -1` ile.
- `OLLAMA_KEEP_ALIVE=-1` olduğu için modeller bir daha bellekten çıkmıyor.
- Sağlık kontrolü `ollama ps` ile iki modelin de bellekte olduğunu soruyor; yani "healthy", "sıcak" demek.
- Isınma başarısız olursa üç kez deneniyor ama sunucu yine çalışıyor; modeller o zaman eskisi gibi ilk kullanımda yükleniyor.
- Durdurma sinyali sunucuya iletiliyor; `docker compose stop ollama` 1 sn sürdü.

**Sonuç (CPU, varsayılan kurulum):**
- Ollama yeniden oluşturulunca ısınma log'u: embedding 2,0 sn, sohbet 11,7 sn. Container 16–18 sn'de healthy oldu.
- `ollama ps` iki model için de `UNTIL Forever` gösteriyor.
- Yeniden başlatmanın hemen ardından API üzerinden sorulan ilk üç soru 0,3–0,6 sn'de cevaplandı (yalnız embedding ve arama; hesapta belge yoktu).
- 4 belgeyle sohbet senaryosu: 4/4 cevap, p50 25 sn (CPU).

**Bellek:** İki model yüklüyken Ollama container'ı 6 GiB sınırına dayanıyor (anonim bellek 5,9 GiB; `memory.peak` 6 GiB). Önceki ayarda da iki model birlikte yüklüyken aynısı oluyordu; değişen, bunun kalıcı olması. Isınma ve sohbet yükü boyunca `oom_kill` 0 kaldı. Sınır, bellek bütçesindeki gibi 6 GiB'da bırakıldı. Taşma görülürse `OLLAMA_MEM_LIMIT` artırılır ([model-unavailable runbook](runbooks/model-unavailable.md)).

## Hibrit arama (2026-10-24, ADR-0022)

Soru başına sözcüksel bir liste ve terim sayımı eklendi. İkisi de veritabanında, soru embedding'iyle aynı salt-okunur transaction'da çalışır; yeni uzak çağrı yoktur. Kanıt: `docs/evidence/hibrit-arama-dogrulama.md` bölüm 6.

| Hesap başına chunk | Yalnız vektör | Hibrit + terim sayımı | Not |
|---|---|---|---|
| 75 (eval seti) | 12,7 ms p50 | 13,7 ms p50 | Testcontainers; soru embedding'i CPU'da 194 ms p50 |
| 10 bin (sentetik) | 23–26 ms | 47–70 ms | |
| 50 bin (sentetik) | 2–19 ms | 195–390 ms | VEYA sorgusu yaygın sözcüklerle chunk'ların çoğunu eşler ve sıralar |

- **Uçtan uca, GPU, aynı develop tabanına karşı:** p50 1,40 sn'den 1,50 sn'ye, p95 2,88 sn'den 3,32 sn'ye çıktı. Artışın kaynağı veritabanı değil. Önce "bulunamadı" alan 7 cevaplanabilir soru artık modelden cevap alıyor.
- **Büyük hesapta:** `verso.document.retrieval.lexical-candidates: 0` sözcüksel listeyi ve terim sayımını kapatır (yalnız vektör).
- **Migration V3:** `document_chunk`'ı ve HNSW index'ini yeniden kurar. 20 bin chunk'ta 14 sn, 100 bin chunk'ta 3,5 dk sürdü; uygulama başlamadan önceki migrate adımında koşar.

## Soru-cevap yük ölçümü, ingestion eşzamanlı (2026-10-24)

Tam kayıt, yöntem ve ham sayılar: `docs/evidence/yuk-olcumu.md`. Donanım: AMD Ryzen 7 9800X3D (8 çekirdek), 31 GB RAM, NVIDIA GeForce RTX 4070 Ti SUPER 16 GB; GPU'lu yığın (`deploy/compose.gpu.yaml`), `chat-concurrency` 1, ayarlar değişmedi. Soru karışımı eval v2'den (her beşinci soru cevaplanamaz), 11 PDF'lik külliyat, 2 dk'lık kapalı döngü koşular.

| Eşzamanlı istemci | Yalnız soru: p50 / p95 / p99 | 30 belge ingestion sürerken (koşu geneli): p50 / p95 / p99 | 503 11002 (yalnız soru / ingestion'lı) |
|---|---|---|---|
| 1 | 1,2 / 2,7 / 3,0 sn | 1,4 / 2,1 / 2,8 sn | 0 / 0 |
| 2 | 1,9 / 3,5 / 5,8 sn | 2,8 / 5,8 / 10,7 sn | 1 / 1 |
| 4 | 3,3 / 5,7 / 6,4 sn | 4,6 / 6,9 / 8,0 sn | 0 / 7 (100 istekte) |

- **Bütçe (local-GPU p99 15 sn) her koşuda tutuyor;** ADR-0001'in bölme tetiği karşılanmıyor. Hata ve 429 yok; reddedilenler tasarım gereği chat slotu (11002).
- **Ingestion soru gecikmesini artırıyor** (aynı Ollama): 4 istemcide p50 3,3 → 5,5 sn (ingestion sürerken), ama bütçe altında. 30 belge 33–35 sn'de READY (≈52–55 belge/dk).
- **Akışlı uç:** ilk olay (`stage`) 12 ms, ilk metin (TTFT) p50 1,2 sn (1 istemci), 3,0–4,5 sn (4 istemci). Yük altında TTFT, sohbet slotu beklemesidir.
- **Süre kimde:** bir sorunun ortalama retrieval süresi (embedding + DB) 135–311 ms, chat 1,2–4,6 sn; DB bağlantısı soru başına ≈5 ms tutulur, havuz zaman aşımı 0. Darboğaz tek chat slotu ve modeldir, veritabanı değil.
- **Kapasite (bu donanım):** ≈1,1 cevap/sn (≈65 cevap/dk) 4 istemcide, ingestion'sız; ingestion'lı ≈0,8–0,9 cevap/sn. Daha fazla eşzamanlı kullanıcı için `chat-concurrency` ve `OLLAMA_NUM_PARALLEL` birlikte artırılıp yeniden ölçülmeli (ölçülmedi).
- **Alarm:** `QaP99OverBudget` (soru isteği p99, son 24 saat penceresi, 3 gün üst üste 15 sn üstü) tetiği üretimde izler.

Yeniden ölçüm: `SCENARIOS=qa node scripts/load-test.mjs` (`QA_CLIENTS`, `QA_INGEST`, `QA_STREAM`, `QA_SECONDS`; başlıktaki açıklamaya bakın). Yığın GPU'lu ve çalışır durumda olmalı; makinede boş RAM düşükse (< 5 GB) çalıştırmayın.

## Rerank yan konteyneri (2026-10-25, ADR-0043; isteğe bağlı, varsayılan kapalı)

`deploy/compose.reranker.yaml`, yalnız GPU kurulumları. Kanıt: `docs/evidence/rerank-olcumu.md`.

| Kaynak | Ek maliyet |
|---|---|
| VRAM | +1,4 GB boşta, +1,6 GB yük altında (bge-reranker-v2-m3, fp16). Ollama'nın iki modeliyle toplam ~8–10 GB / 16 GB |
| RAM | 2,5–2,7 GiB, sınır 3 GB (`RERANKER_MEM_LIMIT`; ağırlıkların sayfa önbelleği dahil) |
| Disk | imaj 8,2 GB (`89-1.9`), model 2,27 GB (`reranker-models` volume'ü) |
| Soru başına gecikme | rerank çağrısı p50 93 ms, p95 111 ms (ilk 20 aday); uçtan uca p50 1,21 → 1,30 sn, p95 1,98 → 2,18 sn |

- **CPU desteklenmez:** `cpu-1.9` 3 GB sınırında ilk istekte belleği aştı; CPU gecikmesi ölçülemedi.
- Yan konteyner düşerse ya da 2 sn'de cevap vermezse soru füzyon sırasıyla cevaplanır; arka arkaya 3 hatada 1 dk atlanır (`verso_retrieval_rerank_total{outcome}`).

## Ölçümün sınırları

- Yük kapalı döngüdür: her istemci cevabını bekleyip sonraki isteği gönderir. Aşırı yükte gecikmeler, açık bir geliş hızının göstereceğinden iyi görünür.
- Her senaryo tek koşudur; makinede başka işler de vardı.
- Sonuç dosyalarının eskileri sunucu ayarını kaydetmiyor. `-c1` ekli dosyalar `verso.qa.chat-concurrency=1` ile, ekisizler 2 ile alındı. Yeni koşular ayarı ve commit'i `settings` altında kaydeder.

## Kapasite özeti (varsayılan kurulum, GPU'suz 8 CPU'lu makine)

| İş | Kapasite |
|---|---|
| Belge listeleme, okuma, silme | Yüzlerce istek/sn. Darboğaz değil |
| Belge işleme | ~25–30 kısa belge/dk; büyük belgelerde sayfa ve chunk sayısıyla orantılı |
| Cevabı belgelerde olmayan sorular | ~7 soru/sn |
| Cevaplanan sorular | **Aynı anda bir soru**, cevap başına ~30 sn (p95 45 sn): dakikada ~2 cevap. Aynı anda soran ikinci kullanıcı `503` "meşgul" (11002, `Retry-After: 5`) alır; yeniden denemek istemcinin işidir |

Bu kurulum bir demo ve küçük bir ekip için yeterlidir. Aynı anda soru soran birkaç kullanıcıyı kaldırmaz.

GPU'lu aynı makinede (`deploy/compose.gpu.yaml`, yukarıdaki bölüm): cevap başına ~1–5 sn, dakikada ~50 cevap; 3 eşzamanlı kullanıcı reddedilmeden cevap alır, 6'da bulkhead'ler reddetmeye başlar.

RTX 4070 Ti SUPER'lı makinede ingestion sürerken bile 4 eşzamanlı istemci p99 8 sn altında cevap alır (yukarıdaki "Soru-cevap yük ölçümü" bölümü): ~65 cevap/dk.

## Ölçeklemek için

| Seçenek | Ne değişir | Bedeli |
|---|---|---|
| GPU'lu host (`deploy/compose.gpu.yaml`) | Ölçüldü: chat p50 1,3 sn, p95 5,4 sn; kalite aynı (yukarıdaki GPU bölümü). Sonraki adım: `chat-concurrency` ve Ollama'nın `OLLAMA_NUM_PARALLEL` değerini birlikte artırıp ölçmek | Donanım; NVIDIA sürücüsü ve Docker GPU desteği |
| Embedding için ayrı Ollama | Soru embedding'leri chat üretimiyle CPU paylaşmaz; 10030'lar kalkar | Bir container daha, bellek |
| Ollama'ya daha çok CPU (`OLLAMA_CPUS`) | Tek cevabın süresi kısalır; eşzamanlılık değişmez | Uygulama ve veritabanıyla CPU paylaşımı |
| Daha küçük chat modeli (`VERSO_CHAT_MODEL`) | Süre kısalır | Türkçe kalite düşer; eval setiyle yeniden ölçülmeli |
| Cloud modu (ADR-0013) | Saniyeler, yüksek eşzamanlılık | Pasajlar sağlayıcıya gider (KVKK md. 9) |
| Birden çok uygulama instance'ı | Okuma ve yükleme ölçeklenir; worker'lar `SKIP LOCKED` ile güvenle paylaşır | Bulkhead'ler instance başınadır; darboğaz yine model sunucusudur |

## Yeniden ölçüm

Eşikler ve bulkhead'ler bu tablodaki ölçümlere dayanır. Model, donanım ya da belge profili değişince `RetrievalEvalTest`, `scripts/eval.mjs` ve `scripts/load-test.mjs` yeniden koşulur ve bu dosya güncellenir.
