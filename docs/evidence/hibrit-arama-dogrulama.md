# Hibrit arama ve cevaplanabilirlik kapısı: doğrulama ve kanıt kaydı (ADR-0022)

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

## Künye

- **Tarih:** 2026-10-24
- **Branch:** `feature/doguyaras-hibrit-arama` (`origin/develop` üzerinden).
- **Ortam:**
  - Windows 11 + Docker Desktop; NVIDIA RTX 4070 Ti SUPER; JDK 25.0.4.1.
  - Modeller: bge-m3:567m, gemma4:e2b.
  - PostgreSQL 18 + pgvector 0.8.7 (`pgvector/pgvector:0.8.7-pg18-trixie`).
- **Kapsam:** yol haritası `rag_quality.md` iş 2 (başlık yolu ve belge adı ön eki) ve iş 4 (hibrit arama), "bulunamadı" kararı. Her adım ölçüldü; yardım etmeyen ya da başka bir ölçüyü bozan adım bırakıldı.
- **Davranışsal kapsam (seviye 3):** var.
  - Retrieval: gerçek bge-m3 ile Testcontainers PostgreSQL üzerinde, CPU'da (`RetrievalEvalTest`, `FormatEvalTest`).
  - Uçtan uca: çalışan yığında, GPU'da (`scripts/eval.mjs`).

## 1. Ölçüm yöntemi

**Resmî ölçüm:** `RetrievalEvalTest` ve `FormatEvalTest`, CPU'daki Ollama ile (`eval/README.md`; GPU'daki Ollama'nın tek tük gecikme sıçramaları testin 2 sn'lik embedding süresini aşıyor).

```bash
VERSO_DB_LOCAL_PORT=55432 docker compose -f compose.yaml -f deploy/compose.local.yaml up -d --no-deps --wait ollama
./mvnw -pl verso-app -am test -Dverso.excludedGroups=none -Dgroups=eval -Dtest=RetrievalEvalTest -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false
```

**Deney düzeneği:** varyantları hızlı karşılaştırmak için repoya girmeyen bir Node betiği kullanıldı.
- Örnek PDF'lerin sayfa metni `PdfTextExtractor` ile çıkarıldı; `PageChunker` (1000/150) birebir taşındı.
- Embedding'ler Ollama'dan alındı (bge-m3, GPU); cosine tam hesaplandı.
- Sözcüksel sıralamalar ayrı bir PostgreSQL 18 container'ında SQL ile hesaplandı.
- **Düzenek doğrulaması:** içerik-yalnız varyant resmî tabanı üç basamakta aynen verdi (0,804 / 0,955 / 0,869; benzerlik ayrımı aynı). Hibrit varyant da daha sonra resmî testte aynen çıktı (0,795 / 0,982 / 0,877).

**Soru bazında fark:** set küçük (112 cevaplanabilir; recall@5 için %95 aralığı yaklaşık ±0,04). Her adımda "kaç soru kazanıldı, kaç soru kaybedildi" sayıldı.

## 2. Adım 1: embedding metnine belge adı ve başlık yolu

- Saklanan chunk metni ve atıf değişmez; yalnız embed edilen metne ön ek eklenir.
- Başlık sezgisi: numaralı kısa satır ("3.4 Stok düşümü ve rezervasyon"), "MADDE n" ya da Markdown `#`. Yol sayfa sınırlarını geçer.

| Varyant (yalnız vektör) | recall@1 | recall@5 | MRR | Cevaplanabilir en düşük / cevaplanamaz en yüksek benzerlik |
|---|---|---|---|---|
| A. İçerik (taban) | 0,804 | 0,955 (107) | 0,869 | 0,450 / 0,702 |
| B. Belge adı + içerik | 0,804 | 0,955 (107) | 0,871 | 0,430 / 0,660 |
| C. Başlık yolu + içerik | 0,795 | 0,955 (107) | 0,868 | 0,444 / 0,667 |
| **D. Belge adı › başlık yolu + içerik** (istenen) | 0,795 | 0,946 (106) | 0,864 | 0,419 / 0,639 |
| E. Belge başlığı (ilk satır) › başlık yolu | 0,777 | 0,955 (107) | 0,857 | 0,431 / 0,649 |
| F. Belge adı › son başlık | 0,821 | 0,946 (106) | 0,880 | 0,419 / 0,640 |
| G. Son başlık | 0,804 | 0,946 (106) | 0,872 | 0,444 / 0,670 |
| H. Belge başlığı | 0,786 | 0,946 (106) | 0,859 | 0,433 / 0,657 |
| I. Ön ek yerine son ek | 0,813 | 0,946 (106) | 0,874 | 0,456 / 0,685 |

- D, soru bazında: recall@1 +5/−6, recall@5 +1/−2.
- Ön ek, `paraphrase` türünde recall@5'i 0,76'dan 0,82'ye çıkarıyor ama `disambiguation` recall@1'i 0,81'den 0,71'e indiriyor. Belgeler aynı şirkete ait ve benzer başlıklar taşıyor; ön ek onları birbirine yaklaştırıyor.
- **Karar: bırakıldı.** Hibrit aramanın üstünde ölçüldüğünde de bırakıldı (bölüm 3, son satır). Kod ve migration eklenmedi; yeniden indeksleme gerekmedi.

## 3. Adım 2: hibrit sözcüksel + vektör arama

- `to_tsvector('turkish', content)`, füzyon RRF (k = 60), aday listeleri 20 + 20.
- "Sözcüksel" satırları füzyonsuz, yalnız `ts_rank_cd` sıralamasıdır.

| Varyant | Eşleşen soru | recall@1 | recall@5 | MRR |
|---|---|---|---|---|
| Vektör (taban) | – | 0,804 | 0,955 (107) | 0,869 |
| Sözcüksel, `plainto_tsquery` (VE) | 4/140 | 0,036 | 0,036 | 0,036 |
| Sözcüksel, `websearch_to_tsquery` (VE) | 4/140 | 0,036 | 0,036 | 0,036 |
| Sözcüksel, terimler VEYA, `ts_rank_cd` | 140/140 | 0,625 | 0,938 | 0,757 |
| **Füzyon, VEYA + `ts_rank_cd`** | | **0,795** | **0,982 (110)** | **0,877** |
| Füzyon, `ts_rank_cd` normalizasyon 1 | | 0,795 | 0,973 | 0,877 |
| Füzyon, `ts_rank` | | 0,813 | 0,964 | 0,879 |
| Füzyon, İ/ı katlaması (`translate(…, 'İIı', 'iii')`) | | 0,804 | 0,982 | 0,880 |
| Füzyon, `unaccent` + `turkish` | | 0,795 | 0,982 | 0,873 |
| Füzyon, SQL içinde BM25 | | 0,821 | 0,964 | 0,886 |
| Füzyon, aday listeleri 10 + 10 | | 0,804 | 0,964 | 0,876 |
| Füzyon, aday listeleri 40 + 40 | | 0,795 | 0,982 | 0,877 |
| Füzyon + adım 1 (D ön eki) | | 0,830 | 0,982 | 0,896 |

- **VE ile sorgu işe yaramıyor:** sorunun kendi sözcükleri ("hangi", "demek", "kaç") pasajda olmadığında hiçbir şey eşleşmiyor. Türkçe stopword listesi bunları çıkarmıyor.
- **İ/ı katlaması:** fark bir soru (gürültü). Veritabanının `en_US.utf8` yereli `I` ve `İ`'yi `i`'ye indiriyor; yalnız büyük harfli Türkçe sözcüklerde (`IŞIK` → `işik`) uyuşmazlık kalıyor. Katlama tutulmadı.
- **BM25:** füzyonda recall@1'de 3 soru kazandırıyor ama recall@5'te 2 soru kaybettiriyor. Sorgu daha pahalı. Tutulmadı; ADR'de yeniden değerlendirme koşulu.
- **Adım 1 hibritin üstünde:** recall@1'i 4 soru artırıyor, ama cevaplanabilir en düşük benzerlik 0,419'a iniyor. Bu da "bulunamadı" kapısını zayıflatıyor (bölüm 4: çapraz doğrulamada 107/112 ve 3/28, içerik embedding'iyle 110/112 ve 5/28). Üstüne yeniden indeksleme gerektiriyor. Bırakıldı.

**Resmî ölçüm, son yapılandırma** (`eval/results/retrieval.json`):

| Alt küme | Soru | recall@1 önce → sonra | recall@5 önce → sonra | MRR önce → sonra |
|---|---|---|---|---|
| **Bütün set** | 112 | 0,804 → **0,795** | 0,955 → **0,982** | 0,869 → **0,877** |
| Sürüm 1 soruları | 25 | 0,88 → 0,84 | 1,00 → 1,00 | 0,94 → 0,92 |
| Sürüm 2 soruları | 87 | 0,78 → 0,782 | 0,94 → 0,977 | 0,85 → 0,864 |
| `paraphrase` | 17 | 0,65 → 0,647 | 0,76 → **0,941** | 0,68 → 0,752 |
| `abbreviation` | 20 | 0,80 → 0,85 | 1,00 → 1,00 | 0,90 → 0,925 |
| `number` | 50 | 0,84 → 0,80 | 0,96 → 0,98 | 0,89 → 0,875 |
| `table` | 26 | 0,77 → 0,731 | 0,92 → 0,962 | 0,83 → 0,827 |
| `disambiguation` | 21 | 0,81 → 0,81 | 0,90 → 0,952 | 0,85 → 0,869 |

- **Soru bazında:** recall@5 +3/−0 (erp-3, erp-17, bt-2 bulundu); recall@1 +4/−5.
- Belge düzeyinde recall@5 1,00 (değişmedi).

## 4. Adım 3: cevaplanabilirlik kapısı

**Kural ailesi:** cevaplanabilir, eğer en benzer pasajın cosine benzerliği ≥ T1, ya da benzerlik ≥ T2 ve aynı pasaj, hesabın chunk'larının en çok S payında geçen bir soru terimini içeriyor.

Denenen sözcüksel güç sinyalleri:
- en iyi pasajın terim kapsaması;
- IDF ağırlıklı kapsama;
- `ts_rank_cd` değeri;
- iki listenin uyuşması (aynı pasajın iki listedeki sırası);
- **en nadir ortak terimin payı.**

Yalnız sonuncusu, kısa kısaltma sorularını cevaplanamazlardan ayırdı.

**Seçim ve çapraz doğrulama:**
- Değerler ızgara aramayla seçildi. Kısıt: hiçbir cevaplanabilir soru durmasın; amaç: en çok cevaplanamaz soru dursun.
- Belge grubuna göre altı kat: sürüm 1 belgeleri, WMS, ERP, BT, CRM, İSG. Cevaplanamaz sorular konularının grubunda. Her grup bir kez dışarıda tutuldu.

| Kural | Cevaplanabilir geçen | Cevaplanamaz duran | "Bulunamadı" kesinliği | Duyarlılığı |
|---|---|---|---|---|
| Önce: cosine ≥ 0,50, yalnız vektör | 107/112 | 6/28 | 0,545 | 0,214 |
| Cosine ≥ 0,50, hibrit liste | 107/112 | 8/28 | 0,615 | 0,286 |
| **Sonra: T1 0,47, T2 0,44, S 0,045** (resmî ölçüm) | **112/112** | **7/28** | **1,00** | **0,25** |
| Aynı aile, çapraz doğrulama | 110/112 | 5/28 | 0,714 | 0,179 |
| Tek cosine eşiği, çapraz doğrulama | 111/112 | 0/28 | 0 | 0 |
| Kapsama / IDF kapsama / `ts_rank_cd` ile, çapraz doğrulama | 109/112 | 0–4/28 | – | – |

**Resmî ölçümde eşiğe yakın sorular:**

| Soru | Cevaplanabilir | En iyi benzerlik | Özgül terim payı | Karar |
|---|---|---|---|---|
| yok-6, yok-8, yok-1 | hayır | 0,4309 / 0,4421 / 0,4497 | 1 | durdu |
| erp-12 "MK belgesi" | evet | 0,4497 | 0,0267 | özgül terim |
| yok-7, crm-yok-3 | hayır | 0,4667 / 0,4676 | 1 | durdu |
| bt-yok-2 | hayır | 0,4677 | 0,0533 | durdu |
| bt-16 "CAB toplantısı" | evet | 0,4681 | 0,0133 | özgül terim |
| bt-yok-3 | hayır | 0,4697 | 0,0533 | durdu |
| isg-10 | evet | 0,4712 | 1 | benzerlik |
| depo-17 "A-03-02-B" | evet | 0,4741 | 0,0667 | benzerlik |
| depo-18 "FEFO" | evet | 0,4933 | 0,0267 | benzerlik |
| depo-yok-2 | hayır | 0,4961 | 0,0267 | benzerlik (geçti) |

- **T2 0,44:** ızgara 0,45'i veriyordu. erp-12 GPU embedding'inde 0,4502, CPU'da 0,4497 çıktı; 0,45 CPU'da bu soruyu durdurdu (ilk resmî koşu: 111/112). 0,44, ilk cevaplanamazla (yok-8, 0,4421, pay 1) arada sözcüksel koşul kaldığı için hiçbir cevaplanamaz soruyu içeri almıyor.
- **İnce paylar** ADR'de bilinen sınır olarak yazılı: isg-10 T1'in 0,001 üstünde, bt-yok-3 0,0003 altında.
- **Konusu belgede olan 20 cevaplanamaz sorudan 3'ü duruyor.** Bu soruları retrieval sinyalleriyle ayırmak mümkün olmadı.

**Belge türleri** (`FormatEvalTest`, `eval/results/retrieval-formats.json`):

| Tür | Bilgi ilk 5 pasajda önce → sonra | Cevaplanabilir geçen önce → sonra | Cevaplanamaz duran önce → sonra |
|---|---|---|---|
| PDF | 0,946 → **0,973** | 0,955 → **1,00** | 0,214 → **0,25** |
| DOCX | 1,00 → 0,991 | 0,973 → **0,991** | 0,179 → **0,071** |
| MD | 1,00 → 0,991 | 0,973 → **0,991** | 0,179 → **0,071** |

- DOCX ve MD bölümleri yaklaşık 3000 karakter. Bölüm uzadıkça benzerlik değerleri değişiyor; T1 PDF sayfalarıyla ölçüldü. Bu türlerde kapı 3 cevaplanamaz soruyu daha az durduruyor. ADR'de olumsuz sonuç olarak yazılı.
- Bilgi ölçüsünde tek kayıp erp-3; üç türde de aynı soru. Kelime eşleşmesi ölçüsü bu soruda yanıltıcı: sayfa ölçüsünde erp-3 artık bulunuyor.

## 5. Uçtan uca (GPU)

```bash
OUT=e2e-gemma4_e2b-gpu.json node scripts/eval.mjs
```

- **Yığın:** `compose.yaml` + `deploy/compose.gpu.yaml`, bu branch'in imajı. Migrate adımı V3'ü uyguladı (yığındaki birkaç chunk için 55 ms).
- **Secret:** betiğin okuduğu CI istemci secret'ı her koşu için worktree'nin git'e girmeyen `secrets/` klasörüne kopyalandı ve koşudan sonra silindi.
- **Tekrarlanabilirlik:** eski imajla taban yeniden koşuldu; sıkı "bulunamadı" ve atıf isabeti aynen tekrarladı. Model bu sette deterministik davranıyor. Aşağıdaki farklar gürültü değil.

| Ölçüm | Taban (`fb919db`) | Taban, tekrar | Hibrit, bütün pasajlar | Hibrit, pasaj tabanı 0,44 | **Hibrit, en benzer + ≥ 0,50 (son)** |
|---|---|---|---|---|---|
| Kaynak bulundu | 0,902 | 0,902 | 0,973 | 0,982 | **0,973** |
| Atıf isabeti | 0,866 | 0,866 | 0,911 | 0,911 | **0,902** |
| Atıf kesinliği | 0,929 | 0,920 | 0,915 | 0,908 | **0,914** |
| Doğru bilgi | 0,848 | 0,857 | 0,902 | 0,902 | **0,902** |
| "Bulunamadı", sıkı | 15/28 | 15/28 | 10/28 | 11/28 | **17/28** |
| "Bulunamadı" ya da modelin kendi atıfsız reddi | 18/28 | 18/28 | 16/28 | 16/28 | **18/28** |
| Gecikme p50 / p95 | 1,37 / 3,08 sn | 1,51 / 2,40 sn | 1,70 / 3,39 sn | 1,82 / 4,28 sn | **1,70 / 2,81 sn** |

- **Prompt'a giden pasajlar:**
  - İlk iki hibrit koşu, cevaplanamaz sorularda modele daha çok yakın pasaj verdi. Model bunlardan cevap kurdu.
  - Son kural eski pasaj filtresini (≥ 0,50) korur, ama kapıyı açan en benzer pasajı her durumda ekler. Bu olmadan özgül terim kolundan geçen bir sorunun prompt'u boş kalırdı.
- **Son koşuda kazanılanlar:**
  - Atıf isabeti: depo-17, depo-18, erp-12, erp-17, bt-16 (beşi önce "bulunamadı" ya da yanlış sayfa).
  - Cevaplanamaz sorularda: erp-yok-1 ve erp-yok-4 artık sabit cümleyi alıyor.
- **Kaybedilenler:**
  - crm-13: doğru sayfaya atıf yok.
  - erp-yok-3: model döviz kurunun yükleme saatini yazdı.
  - crm-yok-2: model reddi atıflı yazdı.
- **Gecikme:** p50 0,2–0,3 sn arttı. Önce "bulunamadı" alan 8 cevaplanabilir soru artık cevap üretiyor; cevap üretmek, sabit cümleden uzun sürüyor. Veritabanının payı aşağıda.

## 6. Gecikme ve kapasite

**Veritabanı payı, eval hesabında (75 chunk, Testcontainers):**
- Aynı koşuda, aynı soru vektörleriyle ölçüldü; ilk tur ısınma, sonraki iki tur sayıldı, 140 soru.

| Sorgu | p50 | p95 |
|---|---|---|
| Yalnız vektör (`lexical-candidates: 0` ile aynı cümle) | 12,7 ms | 19,9 ms |
| Hibrit + özgül terim sayımı | 13,7 ms | 16,9 ms |

- Soru embedding'i bu koşuda CPU'da p50 194 ms, p95 486 ms sürdü. Arama süresinin neredeyse tamamı embedding'dir.

**Büyük hesap (sentetik):**
- Ayrı bir PostgreSQL 18 + pgvector container'ı, varsayılan bellek ayarları.
- Eval setinin 75 chunk'ı çoğaltıldı (her chunk'a belge adı ve sıra eklendi); vektörler rastgele.
- İki hesap, eşit boyda; ölçüm birinde, ısınmış.

| Hesap başına chunk | Yalnız vektör | Hibrit | Terim sayımı | V3 migration (ALTER + GIN) |
|---|---|---|---|---|
| 10 bin (toplam 20 bin) | 23–26 ms | 44–62 ms | 2,5–8,5 ms | 13,8 sn + 0,2 sn |
| 50 bin (toplam 100 bin) | 2–19 ms | 185–297 ms | 8–90 ms | 206 sn + 2,2 sn |

- **Sözcüksel sorgunun maliyeti:** VEYA sorgusu yaygın sözcüklerle hesabın chunk'larının çoğunu eşliyor ve hepsini `ts_rank_cd` ile sıralıyor; maliyet hesap büyüklüğüyle doğrusal artıyor. Uygulama rolünün `statement_timeout`'u (10 sn) en kötü durumu sınırlar. `lexical-candidates: 0` sözcüksel listeyi ve terim sayımını kapatır.
- **Migration:** `ADD COLUMN … STORED` tabloyu yeniden yazar ve bütün index'leri, HNSW dahil, yeniden kurar. 100 bin chunk'ta süre neredeyse tamamen HNSW'nin yeniden kurulmasıdır (aynı tabloda `CREATE INDEX … hnsw` 205 sn). Migrate adımı uygulama başlamadan koşar. Migration rolünün `statement_timeout`'u yok, `lock_timeout` 10 sn.

## 7. Makine kontrolleri

| Kontrol | Komut | Sonuç |
|---|---|---|
| Migration değişmezliği | `node scripts/flyway-immutability.js check --base origin/develop` | `flyway-immutability: OK (base=origin/develop, …)` |
| Retrieval eval | yukarıda | **PASS**. Korumalar: recall@5 ≥ 0,97, MRR ≥ 0,86, cevaplanabilir geçen = 1,00, cevaplanamaz duran ≥ 0,20 |
| Format eval | `-Dtest=FormatEvalTest` | **PASS** |
| Build | `./mvnw -B -ntp verify` | sonuç commit notunda ve bölüm 8'de |

**Yeni ve değişen testler:**
- `AnswerabilityGateTest`: iki kol, sınırlar, en benzer pasajın okunması, sözcüksel kolun kapanması, prompt pasajları, yapılandırma hataları.
- `DocumentPropertiesTest.retrieval_*`.
- `RetrievalTest`:
  - yalnız sözcüksel bulunan pasaj füzyonla gelir ve kendi cosine'ini taşır;
  - başka hesabın aynı terimi ne döner ne sayılır;
  - yaygın terimin payı 1'dir.
- `QuestionApiTest`:
  - taban altındaki sözcüksel pasaj prompt'a girmez;
  - log'da `answerability=`.
- `ConfigProfilesTest`: yeni değerler.

## 8. Review

Skill kararları (`docs/ai/review-checklist.md`):

| Skill | Karar | Not |
|---|---|---|
| `verso-llm-review` | APPROVE WITH NON-BLOCKING COMMENTS | Soru metni SQL parametresi olarak veritabanına gider, log'a gitmez. Yeni log alanı yalnız sabit değerler taşır (`answerability=`). Sahiplik iki listede ve terim sayımında (`RetrievalTest` iki hesaplı). Cloud moda giden içerik değişmedi (en çok top-k pasaj). Yorum: DOCX/MD'de kapı daha az durduruyor (bölüm 4) |
| `verso-db-migration-review` | APPROVE WITH NON-BLOCKING COMMENTS | Yalnız kendi şeması; yeni dosya V3. MEDIUM: tablo yeniden yazımı ve HNSW'nin yeniden kurulması; süre ölçüldü (bölüm 6), migration yorumunda ve ADR'de. `CONCURRENTLY` GIN için anlamsız: aynı migration'daki yeniden yazım zaten özel kilit alıyor |
| `verso-spring-code-review` | APPROVE | Yeni bean'ler constructor injection. Model çağrısı transaction dışında, sorgular tek salt-okunur transaction'da. Yeni parametreler `verso.qa.*`, `verso.document.retrieval.*`; Verso'da parametre kataloğu yok (llm-rules 3.4 BA) |
| `verso-test-writer` | PASS | Bölüm 7. Boşluk: özgül terim kolunun HTTP ucundan testi. Test embedding modeli 0,44–0,47 aralığında benzerlik üretemiyor; kol `AnswerabilityGateTest`'te ve eval'de ölçülüyor |
| `verso-resilience-review` | APPROVE WITH NON-BLOCKING COMMENTS | Yeni uzak çağrı yok; uzak bağımlılık sayısı 2'de kaldı. MEDIUM: sözcüksel sorgu büyük hesapta pahalı (bölüm 6). Acil kapatma `lexical-candidates: 0`; `statement_timeout` 10 sn; `repo-context.md` sıcak yol satırı güncellendi |

## 9. Yapılmayanlar

- **Rerank ve konusu belgede olan cevaplanamaz sorular:** yol haritası iş 6.
- **Kesintisiz yeniden indeksleme işi:** adım 1 bırakıldığı için gerekmedi. Yöntem ADR-0022 "Yeniden indeksleme"de.
- **Uçtan uca ölçüm CPU'da koşulmadı:** yaklaşık 1 saat sürer.
