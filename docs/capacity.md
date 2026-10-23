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

1. **Tek cevabın medyan süresi 15–20 kat kısaldı; ADR-0008'in local-GPU bütçesi (15 sn) p95'te rahat tutuyor.** İstisna soğuk açılıştır: Ollama açıldıktan ya da 30 dk boş kaldıktan sonra (`OLLAMA_KEEP_ALIVE`) ilk soru modeli diskten yükler, bu ölçümde 65 sn.
2. **Kalite değişmedi.** GPU yalnız hızı değiştirir; aynı model aynı cevapları verdi (33 sorunun metrikleri birebir).
3. **GPU'da darboğaz model değil, uygulamanın bulkhead'leri.** 3 istemcide her soru cevaplandı, çünkü bir cevap 1–5 sn sürüyor ve sıradaki soru 5 sn'lik chat beklemesine sığıyor. 6 istemcide aynı anda bir chat (`chat-concurrency` 1) ve dört soru embedding'i sınırı reddetmeye başladı. Bu ayarlar CPU için ölçülmüştü (bulgu 2); GPU'da `chat-concurrency` ve Ollama'nın `OLLAMA_NUM_PARALLEL` değerini birlikte artırmak sonraki adım. Ölçülmeden değiştirilmedi.

**Sınırlar:** her senaryo tek koşudur. Eval koşusunun başında aynı makinede bir Maven testi çalışıyordu; ilk soruların süresini etkilemiş olabilir. 6 istemcili koşuda 6 yüklemenin 2'si yükleme sınırına (503 10014) takıldı; o belgelere ait sorular "bulunamadı" döndü. Bu yüzden o satır yalnız doygunluğun nerede başladığını gösterir.

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
