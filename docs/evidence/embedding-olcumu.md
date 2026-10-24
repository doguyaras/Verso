# Embedding modeli ölçümü: bge-m3 yerine başka model? (kanıt kaydı)

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. Bu belge yalnız ölçümdür: varsayılan model, kod ve eşikler değişmedi.

## Künye

- **Tarih:** 2026-10-24
- **Branch:** `feature/doguyaras-embedding-olcumu` (`origin/develop` üzerinden).
- **Soru:** Araştırma notu (`research_notes/.../technologies_dependencies.md` bölüm 2) "Türkçede multilingual-e5-large(-instruct) ve `ytu-ce-cosmos/turkish-e5-large` bge-m3'ü geçer" diyor. Bu, Verso'nun eval setinde (sürüm 2: 140 soru, 112 cevaplanabilir, 28 cevaplanamaz; 11 belge, 75 chunk) ve üretim retrieval'ında (hibrit arama + cevaplanabilirlik kapısı, ADR-0022) doğru mu?
- **Ortam:** Windows 11 + Docker Desktop; NVIDIA RTX 4070 Ti SUPER; çalışan yığındaki `ollama/ollama:0.35.1` (GPU, modeller 100% GPU'da); PostgreSQL 18 + pgvector 0.8.7 (geçici container).
- **Karar:** **Şimdi geçme.** Hiçbir aday, üretim ayarında (hibrit) bge-m3'ü gürültü payının ötesinde geçmedi. En iyi aday `turkish-e5-large` bir sonraki değerlendirmeye aday kalıyor (aşağıda koşullar).

## Özet

| Model (en iyi ön ek biçimi) | Hibrit r@1 | Hibrit r@5 | Hibrit MRR | Vektör r@5 | `paraphrase` r@5 (vektör / hibrit) | Kapı ayrımı (AUC) | Boyut |
|---|---|---|---|---|---|---|---|
| **bge-m3** (taban, resmî sayı ile aynı) | 0,795 | 0,982 | 0,877 | 0,955 | 0,765 / 0,941 | **0,832** | 1024 |
| turkish-e5-large (`Instruct:`/`Query:`) | **0,804** | **0,991** | **0,889** | **0,991** | 0,941 / 1,000 | 0,766 | 1024 |
| multilingual-e5-large (`query:`/`passage:`) | 0,759 | 0,991 | 0,857 | 0,973 | 0,882 / 1,000 | 0,726 | 1024 |
| multilingual-e5-large-instruct (`Instruct:`/`Query:`) | 0,741 | 0,973 | 0,846 | 0,982 | 0,941 / 0,941 | 0,720 | 1024 |
| Qwen3-Embedding-0.6B (`Instruct:`/`Query:`) | 0,777 | 0,982 | 0,864 | 0,955 | 0,882 / 1,000 | 0,772 | 1024 |
| EmbeddingGemma-300m (`task: search result`) | 0,795 | 0,982 | 0,875 | 0,938 | 0,765 / 0,941 | 0,763 | 768 |

- 112 cevaplanabilir soruda **bir soru 0,009** eder; recall için %95 aralığı yaklaşık ±0,04 (ADR-0022 ile aynı not). turkish-e5-large'ın bge-m3'e farkı: hibrit recall@1 +8/−7 soru, recall@5 +1/−0 soru, MRR +0,012. Bu fark **anlamlı değil**.
- Kapı ayrımı (AUC), "en benzer pasajın cosine'i cevaplanabilir soruda mı daha yüksek?" sorusudur ve ölçekten bağımsızdır. bge-m3 en yüksek AUC'ye sahip (0,832): kapı bu model için kuruldu ve bu model ile en iyi çalışıyor. EmbeddingGemma'nın ön eksiz hali 0,849 çıktı, ama o varyant 0,351 altında kapıyı hiç ayıramıyor (0/28) ve retrieval'ı biraz düşük (hibrit 0,786 / 0,982 / 0,871).

## 1. Yöntem

**Düzenek.** Resmî `RetrievalEvalTest` Testcontainers ve Maven ister; bir model karşılaştırması için çok yavaş olurdu. `docs/evidence/hibrit-arama-dogrulama.md` bölüm 1'deki gibi, repoya `eval/embedding-olcumu/` altında küçük bir düzenek yazıldı (develop'ta önceki ajanın betiği yoktu):

| Adım | Dosya | Ne yapar |
|---|---|---|
| 1 | `ChunkDump.java` | Örnek PDF'leri üretimdeki gibi chunk'lar: PDFBox 3.0.8 sayfa metni (`setSortByPosition`), `TextNormalizer`, `PageChunker` 1000/150. 11 belge, 75 chunk. |
| 2 | `lex.mjs` | Modelden bağımsız yarı: chunk'ları geçici PostgreSQL 18'e (`pgvector/pgvector:0.8.7-pg18-trixie`) yükler; her soru için üretimdeki SQL ile sözcüksel listeyi (`to_tsvector('turkish')`, terimler VEYA, `ts_rank_cd`, ilk 20) ve en nadir ortak terimin chunk sayısını (özgül terim payı) hesaplar. |
| 3 | `olcum.mjs` | Chunk'ları ve 140 soruyu Ollama'dan embed eder; cosine ilk 20 (tam tarama), RRF k = 60, ilk 5, kapı (`AnswerabilityGate`: cosine ≥ T1 ya da cosine ≥ T2 ve pay ≤ S). Vektör-yalnız ve hibrit ayrı sayılır. |
| 4 | `rapor.mjs` | Tabloları üretir. |

- **Düzenek doğrulaması:** bge-m3 ile hibrit sonuç resmî sayıyı aynen verdi: recall@1 0,795, recall@5 0,982, MRR 0,877; vektör-yalnız 0,804 / 0,955 / 0,869; kapı 112/112 geçer, 7/28 durur (ADR-0022). Farklar: HNSW yerine tam tarama (75 chunk'ta aynı), eşit sözcüksel skorda sıra `id` yerine yükleme sırası, embedding GPU'dan (resmî ölçüm CPU'dan; sayılar yine aynı çıktı).
- **Yalıtım.** Üretim veritabanına ve resmî eval hesabına dokunulmadı: chunk'lar yalnız `verso-embmeas-pg` adlı geçici container'a (127.0.0.1:55499, tek kullanımlık parola) yüklendi, iş bitince silindi. Hiçbir kalıcı veri yazılmadı.
- **Çalıştırma** (özet; bkz. dosya başlıkları): `ChunkDump` → `lex.mjs` → `olcum.mjs` (Ollama'ya erişen bir container'da: `docker run --network verso_models -e OLLAMA_URL=http://ollama:11434 node:24 …`, çünkü Ollama ağı `internal`) → `rapor.mjs`.

**Model sunumu.** Çalışan Ollama bilerek kısıtlı: kök dosya sistemi ve model volume'ü salt okunur, internet yok (`compose.yaml` `ollama` servisi; llm-rules 8.2). `docker compose exec ollama ollama pull` bu yüzden çalışmaz. Modeller, yığının kendi `ollama-pull` kalıbıyla, aynı `verso_ollama-models` volume'üne yazan **tek kullanımlık yardımcı container'larla** eklendi (`ollama/ollama:0.35.1`, özdeş digest); ölçüm çalışan GPU'lu Ollama üzerinden yapıldı. Hiçbir container yeniden oluşturulmadı, yeni sunucu yok. İş bitince eklenen modeller `ollama rm` ile silindi, `bge-m3:567m` ve `gemma4:e2b` yeniden `keep_alive: -1` ile ısıtıldı (ölçüm sırasında Ollama bge-m3'ü bellekten atmıştı).

| Model | Kaynak | Biçim | Not |
|---|---|---|---|
| bge-m3:567m | yığındaki model (`790764642607`) | f16 | taban |
| multilingual-e5-large | `soichisumi/multilingual-e5-large-Q8_0-GGUF` (HF, `aefacc2`) | **Q8_0** (tek biçim) | Ollama kütüphanesinde yok; GGUF içe aktarıldı. Q8_0 ≈ kayıpsız ama f16 ile aynı değil |
| multilingual-e5-large-instruct | `Ralriki/multilingual-e5-large-instruct-GGUF` (HF, `f43c843`) | F16 | GGUF içe aktarıldı |
| turkish-e5-large | `ytu-ce-cosmos/turkish-e5-large` (HF, `02e2362`) | F16 | **Yayımlanmış GGUF yok.** `ghcr.io/ggml-org/llama.cpp:full` (`sha256:a22d810e…`) içindeki `convert_hf_to_gguf.py --outtype f16` ile safetensors'tan dönüştürüldü (XLM-R, ortalama havuzlama; kartın `1_Pooling` ayarıyla aynı), sonra içe aktarıldı. Dönüştürücü imajı iş bitince silindi |
| Qwen3-Embedding-0.6B | Ollama kütüphanesi `qwen3-embedding:0.6b` (`ac6da0dfba84`) | Q8_0 | HF'nin resmî GGUF'ları (f16 ve Q8_0) `ollama create` ile `cannot allocate memory` hatası verdi; kütüphane sürümü çekildi |
| EmbeddingGemma-300m | `ggml-org/embeddinggemma-300M-GGUF` (HF, `0f741b5`) | Q8_0 | GGUF içe aktarıldı |

**Ön ekler (karşılaştırmanın geçerliliği buna bağlı).** Her model için resmî model kartının ön eki ana varyant; ön eksiz ve kartın önermediği ön ekle varyantlar duyarlılık ölçümü olarak ayrı satırlarda. Ön ek `{x}` = sorunun ya da chunk'ın metni. Chunk metni her yerde saklanan metindir (belge adı ya da başlık eklenmedi, ADR-0022 adım 1 ile tutarlı).

| Model | Soru | Pasaj | Kaynak |
|---|---|---|---|
| bge-m3 | `{x}` | `{x}` | model kartı: ön ek yok |
| multilingual-e5-large | `query: {x}` | `passage: {x}` | model kartı |
| multilingual-e5-large-instruct | `Instruct: Given a web search query, retrieve relevant passages that answer the query⏎Query: {x}` | `{x}` | model kartı (retrieval görevi) |
| turkish-e5-large | `Instruct: Given a Turkish search query, retrieve relevant passages written in Turkish that best answer the query⏎Query: {x}` | `{x}` | model kartı "Recommended Instruct" ve örnek kod |
| Qwen3-Embedding-0.6B | `Instruct: Given a web search query, retrieve relevant passages that answer the query⏎Query:{x}` (iki noktadan sonra boşluk yok) | `{x}` | model kartı |
| EmbeddingGemma-300m | `task: search result \| query: {x}` | `title: none \| text: {x}` | model kartı |

Ollama `/api/embed` çıktıyı L2 normalize eder; cosine = iç çarpım. Bütün modellerde chunk (en çok 1000 karakter, yaklaşık 350 token) model bağlamına (e5: 512) sığar.

**Kapı değerlendirmesi, üç biçimde** (eşikler model ölçeğine bağlı olduğundan bge-m3'ün 0,47 / 0,44 / 0,045 değerleri başka modelde anlamsızdır):
1. Mevcut eşiklerle (yalnız "olduğu gibi geçseydik ne olurdu" için).
2. Tek eşik: hiçbir cevaplanabilir soruyu durdurmayan en yüksek T.
3. ADR-0022 ailesi (T1, T2, pay) için ızgara araması: **aynı sette seçildiği için iyimserdir**; çapraz doğrulama yapılmadı. Izgara T2'yi alt sınırına (0,20) çekebildi; yani buradaki "ızgara" sütunu üst sınırdır, bir ayar önerisi değil.

## 2. Sonuçlar

### 2.1 Retrieval

Sondaki iki sütun, bge-m3'e göre soru bazında kazanılan/kaybedilen cevaplanabilir soru sayısıdır.

| Varyant | Boyut | Vektör r@1 | Vektör r@5 | Vektör MRR | Hibrit r@1 | Hibrit r@5 | Hibrit MRR | paraphrase r@5 (vektör / hibrit) | hibrit r@1 +/- | hibrit r@5 +/- |
|---|---|---|---|---|---|---|---|---|---|---|
| bge-m3 (taban) | 1024 | 0,804 | 0,955 | 0,869 | 0,795 | 0,982 | 0,877 | 0,765 / 0,941 | +0/-0 | +0/-0 |
| multilingual-e5-large (query:/passage:) | 1024 | 0,759 | 0,973 | 0,850 | 0,759 | 0,991 | 0,857 | 0,882 / 1,000 | +5/-9 | +1/-0 |
| multilingual-e5-large-instruct (Instruct/Query) | 1024 | 0,652 | 0,982 | 0,787 | 0,741 | 0,973 | 0,846 | 0,941 / 0,941 | +3/-9 | +1/-2 |
| multilingual-e5-large-instruct (query:/passage:) | 1024 | 0,616 | 0,938 | 0,747 | 0,732 | 0,973 | 0,842 | 0,824 / 0,941 | +2/-9 | +1/-2 |
| multilingual-e5-large (onek yok) | 1024 | 0,732 | 0,955 | 0,827 | 0,759 | 0,982 | 0,856 | 0,824 / 0,941 | +5/-9 | +1/-1 |
| embeddinggemma-300m | 768 | 0,759 | 0,938 | 0,834 | 0,795 | 0,982 | 0,875 | 0,765 / 0,941 | +6/-6 | +1/-1 |
| embeddinggemma-300m (onek yok) | 768 | 0,750 | 0,946 | 0,838 | 0,786 | 0,982 | 0,871 | 0,824 / 0,941 | +6/-7 | +0/-0 |
| turkish-e5-large (Instruct/Query) | 1024 | 0,768 | 0,991 | 0,865 | 0,804 | 0,991 | 0,889 | 0,941 / 1,000 | +8/-7 | +1/-0 |
| turkish-e5-large (query:/passage:) | 1024 | 0,741 | 1,000 | 0,854 | 0,768 | 0,991 | 0,871 | 1,000 / 1,000 | +4/-7 | +1/-0 |
| turkish-e5-large (onek yok) | 1024 | 0,741 | 1,000 | 0,856 | 0,786 | 0,991 | 0,880 | 1,000 / 1,000 | +6/-7 | +1/-0 |
| Qwen3-Embedding-0.6B (Instruct/Query) | 1024 | 0,768 | 0,955 | 0,847 | 0,777 | 0,982 | 0,864 | 0,882 / 1,000 | +4/-6 | +1/-1 |
| Qwen3-Embedding-0.6B (onek yok) | 1024 | 0,723 | 0,938 | 0,810 | 0,768 | 0,973 | 0,852 | 0,882 / 1,000 | +4/-7 | +1/-2 |

### 2.2 Kapı: benzerlik dağılımları

Dağılımlar hibrit ilk 5 içindeki en benzer pasajın cosine'idir (kapının okuduğu değer): min / ortanca / maks.

| Varyant | Cevaplanabilir | Cevaplanamaz | Konu-içi cevaplanamaz | AUC | Mevcut eşiklerle (0,47 / 0,44 / 0,045): geçen / duran | Tek eşik T: geçen / duran | Izgara (kendi seti) T1 / T2 / pay: duran |
|---|---|---|---|---|---|---|---|
| bge-m3 (taban) | 0,450 / 0,620 / 0,738 | 0,431 / 0,546 / 0,702 | 0,468 / 0,559 / 0,702 | 0,832 | 112/112 / 7/28 | 0,450: 112/112 / 3/28 | 0,470 / 0,200 / 0,027: 7/28 |
| multilingual-e5-large (query:/passage:) | 0,778 / 0,851 / 0,887 | 0,770 / 0,824 / 0,888 | 0,784 / 0,833 / 0,888 | 0,726 | 112/112 / 0/28 | 0,778: 112/112 / 2/28 | 0,815 / 0,200 / 0,067: 9/28 |
| multilingual-e5-large-instruct (Instruct/Query) | 0,824 / 0,878 / 0,916 | 0,806 / 0,862 / 0,908 | 0,837 / 0,866 / 0,908 | 0,720 | 112/112 / 0/28 | 0,823: 112/112 / 3/28 | 0,825 / 0,200 / 0,027: 3/28 |
| multilingual-e5-large-instruct (query:/passage:) | 0,792 / 0,859 / 0,903 | 0,792 / 0,844 / 0,900 | 0,824 / 0,852 / 0,900 | 0,696 | 112/112 / 0/28 | 0,791: 112/112 / 0/28 | 0,200 / 0,200 / 0,000: 0/28 |
| multilingual-e5-large (onek yok) | 0,780 / 0,848 / 0,890 | 0,766 / 0,822 / 0,886 | 0,787 / 0,830 / 0,886 | 0,724 | 112/112 / 0/28 | 0,780: 112/112 / 2/28 | 0,780 / 0,200 / 0,000: 2/28 |
| embeddinggemma-300m | 0,340 / 0,534 / 0,739 | 0,303 / 0,435 / 0,657 | 0,360 / 0,489 / 0,657 | 0,763 | 98/112 / 16/28 | 0,339: 112/112 / 3/28 | 0,335 / 0,200 / 0,000: 3/28 |
| embeddinggemma-300m (onek yok) | 0,351 / 0,547 / 0,725 | 0,354 / 0,456 / 0,627 | 0,359 / 0,469 / 0,627 | 0,849 | 100/112 / 16/28 | 0,351: 112/112 / 0/28 | 0,200 / 0,200 / 0,000: 0/28 |
| turkish-e5-large (Instruct/Query) | 0,403 / 0,588 / 0,731 | 0,305 / 0,488 / 0,619 | 0,360 / 0,528 / 0,619 | 0,766 | 103/112 / 11/28 | 0,402: 112/112 / 5/28 | 0,415 / 0,200 / 0,027: 7/28 |
| turkish-e5-large (query:/passage:) | 0,430 / 0,600 / 0,723 | 0,378 / 0,526 / 0,691 | 0,420 / 0,542 / 0,691 | 0,749 | 110/112 / 6/28 | 0,430: 112/112 / 4/28 | 0,420 / 0,200 / 0,000: 4/28 |
| turkish-e5-large (onek yok) | 0,418 / 0,570 / 0,695 | 0,369 / 0,505 / 0,676 | 0,410 / 0,533 / 0,676 | 0,731 | 109/112 / 7/28 | 0,417: 112/112 / 4/28 | 0,445 / 0,415 / 0,067: 6/28 |
| Qwen3-Embedding-0.6B (Instruct/Query) | 0,281 / 0,589 / 0,778 | 0,331 / 0,464 / 0,706 | 0,374 / 0,498 / 0,706 | 0,772 | 102/112 / 15/28 | 0,281: 112/112 / 0/28 | 0,200 / 0,200 / 0,000: 0/28 |
| Qwen3-Embedding-0.6B (onek yok) | 0,281 / 0,532 / 0,728 | 0,271 / 0,398 / 0,634 | 0,346 / 0,451 / 0,634 | 0,789 | 89/112 / 18/28 | 0,281: 112/112 / 1/28 | 0,275 / 0,200 / 0,000: 1/28 |

- **bge-m3:** cevaplanabilir en düşük 0,450; cevaplanamaz en yüksek 0,702 (konusu belgede olan sorular). Ayrım zayıf ama ADR-0022'nin 0,47 / 0,44 / 0,045 kapısı bunun için ayarlı.
- **turkish-e5-large:** ölçek geniş (0,30–0,73), bge-m3'e benzer. Kapı için gereken değerler: tek eşik **T ≈ 0,40** (bge-m3'ün 0,45'i yerine; hiçbir cevaplanabilir soruyu durdurmadan 5/28 cevaplanamazı durdurur, bge-m3'te 3/28). Mevcut eşiklerle 9 cevaplanabilir soru yanlışlıkla durur (103/112). Ama ayrım (AUC 0,766) bge-m3'ten (0,832) düşük: ADR-0022 ailesinde ızgara iki modelde de **7/28** verdi.
- **multilingual-e5-large(-instruct):** ölçek 0,77–0,92'ye sıkışıyor (araştırma notundaki "0,7–1,0 kümelenir" doğrulandı). Cevaplanabilir ile cevaplanamaz neredeyse üst üste: tek eşik 2–3/28 durdurur. Kapı için gereken T1 ≈ 0,78–0,82 gibi çok dar bir aralıktır; yeni bir belge kümesinde kırılgan olur.
- **Qwen3-Embedding-0.6B:** geniş ölçek, en düşük cevaplanabilir 0,281; tek eşik 0–1/28. Kapı için kullanışsız.
- **EmbeddingGemma-300m:** ön ekli varyantta en düşük cevaplanabilir 0,340, tek eşik 3/28; ön eksiz varyant 0/28. Boyut 768 (vektör sütunu değişir).

### 2.3 Gecikme (GPU)

Sorgu: tek soru, tek `/api/embed` isteği, ardışık, 3 geçiş × 140 = 420 istek. İndeks: 11 belgenin 75 chunk'ı, 16'lık gruplar, üç koşunun en hızlısı (ilk koşu soğuk: model GPU'ya yeni yüklendi). Ön ek duyarlılığı satırlarından üçünde (multilingual-e5-large ön eksiz, multilingual-e5-large-instruct query:/passage:, EmbeddingGemma ön eksiz) zamanlama tek geçişlidir (ilk koşu, soğuk indeks dahil). İstemci Ollama'ya docker ağından HTTP ile bağlandığı için sayılar ağ ve istek maliyetini de içerir; ayrıntılı gecikme karakteri `hibrit-arama-dogrulama.md` bölüm 6'dadır.

| Varyant | Sorgu p50 (ms) | Sorgu p95 (ms) | Sorgu maks (ms) | İndeks, 75 chunk (ms; en hızlı / üç koşu) |
|---|---|---|---|---|
| bge-m3 (taban) | 56,24 | 101,873 | 4090 | 3613 / 12409, 3880, 3613 |
| multilingual-e5-large (query:/passage:) | 59,287 | 94,951 | 3444 | 3874 / 13124, 3874, 3896 |
| multilingual-e5-large-instruct (Instruct/Query) | 68,252 | 96,637 | 178 | 3617 / 13808, 3617, 3710 |
| multilingual-e5-large-instruct (query:/passage:) | 59,098 | 181,33 | 4159 | 4489 / 4489 |
| multilingual-e5-large (onek yok) | 49,574 | 88,15 | 3695 | 16204 / 16204 |
| embeddinggemma-300m | 52,366 | 87,632 | 3618 | 3602 / 13971, 3602, 3807 |
| embeddinggemma-300m (onek yok) | 54,089 | 100,605 | 175 | 3403 / 3403 |
| turkish-e5-large (Instruct/Query) | 54,809 | 94,428 | 183 | 3714 / 9548, 3714, 3892 |
| turkish-e5-large (query:/passage:) | 47,44 | 95,254 | 3711 | 3558 / 4190, 3601, 3558 |
| turkish-e5-large (onek yok) | 43,473 | 90,888 | 1328 | 3510 / 4078, 3510, 4010 |
| Qwen3-Embedding-0.6B (Instruct/Query) | 131,243 | 224,764 | 3278 | 4280 / 7427, 4280, 5018 |
| Qwen3-Embedding-0.6B (onek yok) | 129,246 | 226,831 | 300 | 3886 / 4664, 5015, 3886 |

- 1024 boyutlu e5 ailesi ve bge-m3 aynı sınıfta: sorgu p50 43–68 ms, 75 chunk indeksi 3,5–4,5 sn. Aralarındaki fark gürültü düzeyinde (bge-m3 tek geçişte 18 ms, üç geçişte 56 ms ölçüldü; GPU paylaşımlı).
- Qwen3-Embedding-0.6B sorguda yaklaşık iki kat yavaş (p50 130 ms).
- Maks değerlerde 1–4 sn sıçrama çoğu koşuda görüldü, model farkından bağımsız. `eval/README.md`'deki "GPU'da tek tük 1–4 sn" notuyla tutarlı.
- Bellek: e5-large 618 MB (`ollama ps`, bağlam 512), bge-m3 664 MB. Model tarafında kapasite farkı yok.

### 2.4 Soru türüne göre hibrit recall@5 / recall@1

| Varyant | version1 | version2 | abbreviation | number | table | disambiguation | paraphrase |
|---|---|---|---|---|---|---|---|
| bge-m3 (taban) | 1,000 / 0,840 | 0,977 / 0,782 | 1,000 / 0,850 | 0,980 / 0,800 | 0,962 / 0,731 | 0,952 / 0,810 | 0,941 / 0,647 |
| multilingual-e5-large (query:/passage:) | 1,000 / 0,800 | 0,989 / 0,747 | 1,000 / 0,750 | 1,000 / 0,840 | 0,962 / 0,769 | 0,952 / 0,762 | 1,000 / 0,412 |
| multilingual-e5-large-instruct (Instruct/Query) | 1,000 / 0,800 | 0,966 / 0,724 | 0,950 / 0,750 | 1,000 / 0,780 | 0,962 / 0,769 | 0,952 / 0,810 | 0,941 / 0,471 |
| multilingual-e5-large-instruct (query:/passage:) | 1,000 / 0,800 | 0,966 / 0,713 | 0,950 / 0,750 | 1,000 / 0,780 | 0,923 / 0,692 | 0,905 / 0,762 | 0,941 / 0,471 |
| multilingual-e5-large (onek yok) | 1,000 / 0,840 | 0,977 / 0,736 | 1,000 / 0,750 | 1,000 / 0,820 | 0,923 / 0,769 | 0,905 / 0,762 | 0,941 / 0,412 |
| embeddinggemma-300m | 1,000 / 0,840 | 0,977 / 0,782 | 1,000 / 0,800 | 0,980 / 0,840 | 0,962 / 0,731 | 0,952 / 0,762 | 0,941 / 0,529 |
| embeddinggemma-300m (onek yok) | 1,000 / 0,920 | 0,977 / 0,747 | 1,000 / 0,800 | 0,980 / 0,800 | 0,962 / 0,731 | 0,952 / 0,714 | 0,941 / 0,471 |
| turkish-e5-large (Instruct/Query) | 1,000 / 0,880 | 0,989 / 0,782 | 1,000 / 0,950 | 1,000 / 0,800 | 0,962 / 0,846 | 0,952 / 0,762 | 1,000 / 0,412 |
| turkish-e5-large (query:/passage:) | 1,000 / 0,840 | 0,989 / 0,747 | 1,000 / 0,800 | 1,000 / 0,800 | 0,962 / 0,769 | 0,952 / 0,762 | 1,000 / 0,471 |
| turkish-e5-large (onek yok) | 1,000 / 0,880 | 0,989 / 0,759 | 1,000 / 0,800 | 1,000 / 0,820 | 0,962 / 0,769 | 0,952 / 0,762 | 1,000 / 0,471 |
| Qwen3-Embedding-0.6B (Instruct/Query) | 1,000 / 0,840 | 0,977 / 0,759 | 1,000 / 0,850 | 0,980 / 0,800 | 0,962 / 0,731 | 0,952 / 0,810 | 1,000 / 0,471 |
| Qwen3-Embedding-0.6B (onek yok) | 1,000 / 0,840 | 0,966 / 0,747 | 1,000 / 0,850 | 0,960 / 0,780 | 0,923 / 0,692 | 0,952 / 0,810 | 1,000 / 0,471 |

## 3. Değerlendirme

- **Araştırma iddiası, Verso'nun setinde.**
  - *turkish-e5-large* retrieval'da bge-m3'e eşit ya da biraz üstün: vektör-yalnız recall@5 0,991 (bge-m3 0,955, +4 soru), `paraphrase` vektör-yalnız recall@5 0,941 (0,765, +3 soru). Bu, iddianın doğru yanı. Ama üretim ayarında (hibrit) sözcüksel liste bu farkın çoğunu zaten kapatıyor: hibrit recall@5 0,991 / 0,982, recall@1 +1 soru, MRR +0,012. `paraphrase` recall@1'de ise **kötü**: 0,412 (bge-m3 0,647).
  - *multilingual-e5-large(-instruct)* hibritte bge-m3'ü **geçmiyor**: recall@1 0,759 / 0,741 (0,795), MRR 0,857 / 0,846 (0,877). Yalnız recall@5 e5-large'da +1 soru. İddia bu sette doğrulanmadı.
  - *Qwen3-Embedding-0.6B* ve *EmbeddingGemma-300m* hibritte bge-m3 ile aynı ya da altında.
- **Ön ek etkisi.** Ön ek yanlış olunca kayıp küçük ama tutarlı: e5-instruct'ta `query:`/`passage:` kullanmak recall@1'i 0,741 → 0,732, vektör-yalnız MRR'yi 0,787 → 0,747 düşürdü; turkish-e5'te kartın `Instruct` biçimi hibrit recall@1'de `query:`/`passage:` biçiminden +4 soru iyi (0,804 / 0,768). Ön eksizin etkisi modele göre değişiyor. Bu yüzden her model kendi kartındaki biçimle ölçüldü.
- **Kapı asıl bağlayıcı.** Kullanıcıya görünen "bulunamadı" kararını kapı verir (ADR-0022). Yeni bir modelde kapı yeniden kurulmalı ve hiçbir aday kapının ayrımını artırmadı (AUC: bge-m3 0,832, turkish-e5 0,766, diğerleri ≤ 0,772; EmbeddingGemma ön eksizi 0,849 ama o varyantın retrieval'ı biraz düşük ve kapısı işlemiyor).

## 4. Öneri: şimdi geçme

**Geçme.** Gerekçe:
1. Kazanç yok: en iyi aday hibritte +1 soru (recall@1 ve @5), aralığın (±0,04) çok içinde. Kapı ayrımı bge-m3'ten kötü.
2. Maliyet var: yeniden embedding (tüm hesapların bütün belgeleri), eşiklerin yeniden ölçümü (`min-similarity`, `lexical-min-similarity`, `specific-term-max-share`, `passage-min-similarity`), uçtan uca eval (`scripts/eval.mjs`), ve e5 ailesi **asimetrik ön ek** ister: soru ve pasaj farklı metinle embed edilir. Bugünkü `EmbeddingModel` kullanımı (işçi ve soru yolu) ön ek bilmiyor; küçük ama iki yerde kod değişikliği.
3. Tedarik zinciri: turkish-e5-large için yayımlanmış GGUF yok. `deploy/ollama/pull.sh` her modeli sha256 ile sabitler; bu model için kendi dönüştürdüğümüz bir GGUF'u barındırmak ve sabitlemek gerekir. Kartta eğitim verisi lisansı sıralanmamış (aşağıda).

**Sonraya bırak (turkish-e5-large aday).** Yeniden değerlendirme koşulu (hepsi olunca):
- Gerçek bir müşteri belge kümesiyle ölçüm. Bu set sentetik ve 112 cevaplanabilir soru. Fark 5–10 puan olmadan model değiştirmeye değmez.
- `paraphrase` türü gerçek kullanımda baskınsa. Burada vektör-yalnız fark en büyük orada (+3 soru) ve hibrit recall@1'de tersine döndü.
- turkish-e5-large için digest ile sabitlenebilir, barındırılabilir bir GGUF (ya da Ollama kütüphanesinde bir sürümü).
- Eğitim verisinin lisansı kontrol edildi.

**Yeniden kalibrasyon adımları** (geçiş kararı verilirse):
1. Bu düzeneği yeni modelle çalıştır (`olcum.mjs`, tek değişken `variants.json`). Resmî `RetrievalEvalTest`'i yeni model ve ön ekle koştur (`spring.ai.ollama.embedding.model`).
2. Eşikleri belge grubuna göre altıya bölen çapraz doğrulamayla seç (ADR-0022 "Değerlerin seçimi"): önce tek eşik T (turkish-e5 için ≈ 0,40), sonra T1/T2/pay ızgarası. `passage-min-similarity`'yi de (0,50) yeniden seç: model ölçeğine bağlı.
3. `scripts/eval.mjs` ile uçtan uca: atıf isabeti, doğru bilgi, "bulunamadı" (sıkı), p50/p95.
4. Gerekirse CPU'da (CI/laptop) ve GPU'da embedding'lerin dördüncü basamakta ayrıştığını hesaba kat (ADR-0022'de T2 için 0,45 yerine 0,44 seçilmişti).

**Geçiş yolu** (yalnız tarif; uygulanmadı):
- *Boyut 1024 kalıyorsa* (turkish-e5-large, multilingual-e5-large, Qwen3-Embedding-0.6B): `document_chunk.embedding` sütunu (`vector(1024)`) ve HNSW index'i değişmez; şema göçü yok. `spring.ai.ollama.embedding.model` (`verso.document.embedding-model` ona bağlı) ve ön ek ayarları değişir. Yeni model adı `embedding_model` sütununa yazılır.
- *Boyut değişiyorsa* (EmbeddingGemma 768): yeni Flyway sürümü ile sütun tipi değişir; `document_chunk` yeniden yazılır, HNSW yeniden kurulur (ADR-0022: 20 bin chunk'ta 14 sn, 100 bin'de 3,5 dk). Flyway migration'ları değişmez (AGENTS.md 6); eski sürüm düzenlenmez.
- *Yeniden embedding:* hazır belgeler `PENDING`'e alınır; işçi saklanan sayfa metninden (`document_page`) yeniden chunk'lar ve embed eder, dosya gerekmez (ADR-0022 "Yeniden indeksleme"). Bu sırada belge aranamaz. Hesapta başka modelin chunk'ı varken retrieval `hasChunksOfAnotherModel` korumasıyla hata verir (llm-rules 6.1): karışık durum yoktur ama geçiş penceresi vardır. Kesintisiz geçiş (iki model yan yana, belge `READY` kalırken yeni chunk'lar) ayrı tasarım ister.
- *Ollama:* `OLLAMA_PULL` listesine digest ile eklenir; `pull.sh` yalnız resmî kayıt modellerini çeker, GGUF içe aktarma için yeni bir adım gerekir.
- *Geri alma:* eski model adına dönmek yeniden embedding gerektirir (bge-m3 chunk'ları silinmediyse gerekmez); ayrı bir göç planı ister.

## 5. Lisanslar

Model kartlarından (Hugging Face, 2026-10-24); ticari olmayan lisanslı model yok, bu nedenle lisans nedeniyle elenen aday olmadı.

| Model | Lisans | Not |
|---|---|---|
| bge-m3 | MIT | taban; bu çalışmada yeniden okunmadı |
| multilingual-e5-large, -instruct | MIT | GGUF depoları (`soichisumi`, `Ralriki`) MIT etiketli; kaynak `intfloat/multilingual-e5-large(-instruct)` |
| turkish-e5-large | MIT | Temeli e5-large-instruct. Kart eğitim verisini "çeşitli Türkçe veri kümeleri" diye anıyor, tek tek lisans vermiyor: üretimde kullanılmadan önce hukuki gözden geçirme gerekir |
| Qwen3-Embedding-0.6B | Apache-2.0 | temel Qwen3-0.6B-Base |
| EmbeddingGemma-300m | Gemma Kullanım Şartları (HF etiketi `gemma`) | Ticari kullanıma izin var ama OSI lisansı değil: kabul edilebilir kullanım politikası ve alt kullanıcılara iletme yükümlülüğü var. Ölçüldü; üretim için ayrı hukuki onay şart. `ggml-org` GGUF deposunda lisans etiketi yok |

## 6. Yapılmayanlar ve sınırlar

- **Resmî `RetrievalEvalTest` / `scripts/eval.mjs` bu modellerle koşulmadı.** Düzenek bge-m3'te resmî sayıyı birebir verdi; ama uçtan uca (chat modeli, atıf) ölçülmedi. Bir geçiş kararı için gerekir.
- **Kapı eşikleri çapraz doğrulanmadı;** ızgara sütunları iyimser.
- **Tek bir set:** sentetik, 112 cevaplanabilir; `paraphrase` türünde 17 soru. Tür başına farklar 1–3 soruluk.
- **Nicemleme farkı.** multilingual-e5-large yalnız Q8_0 bulundu (diğerleri f16 ya da, Qwen3 ve EmbeddingGemma için, Q8_0). Küçük bir kalite farkı olası; sıralamayı değiştirmesi beklenmez ama ölçülmedi.
- **Ollama'nın GGUF yolu.** Modeller Ollama'nın llama.cpp tabanlı çalıştırıcısıyla ölçüldü; EmbeddingGemma'nın `sentence-transformers` yoğun katmanlarının bu yolda uygulandığı doğrulanmadı. Sonuçlar mantıklı (AUC 0,849 ön eksiz), ama bu modelin Ollama dışı referansıyla karşılaştırılmadı. Qwen3 için HF GGUF'ları `ollama create`te hata verdi, kütüphane sürümü kullanıldı.
- **Ölçülmeyen adaylar:** `nomic-embed-text-v2-moe`, `snowflake-arctic-embed2` (araştırma notunda Ollama kütüphanesinde; görev kapsamı dışı), `multilingual-e5-base/small`.
- **Gecikme** HTTP ile docker ağından ölçüldü ve GPU paylaşımlıydı; model farkı için ince bir ölçüm değildir.
- **Mevcut varsayılan, kod ve eşikler değişmedi.**
