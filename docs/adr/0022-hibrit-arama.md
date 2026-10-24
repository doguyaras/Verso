# ADR-0022: Hibrit arama ve cevaplanabilirlik kapısı

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras. İstek: retrieval kalitesini eval seti sürüm 2 üzerinde ölçülerek iyileştirmek (yol haritası `rag_quality.md` iş 2 ve 4).
- **İlgili eşik (referans Bölüm 24):** ADR-0008 sıcak yol bütçesi; hesap başına chunk sayısı (aşağıda "Yeniden değerlendirme koşulu").
- **Genişlettiği:** ADR-0012'deki retrieval adımı 3–4 ve "Eşik" satırı. ADR-0011'deki `document_chunk` tablosu.

## Bağlam

- Eval seti sürüm 2'de (140 soru, 11 belge; `docs/evidence/eval-v2-dogrulama.md`) vektör araması ve tek 0,50 cosine eşiği iki yerde yetmiyordu:
  - **Retrieval:** recall@1 0,804, recall@5 0,955 (107/112), MRR 0,869; `paraphrase` türünde recall@5 0,76.
  - **Eşik iki yönde de iş görmüyordu:** kısa kısaltma soruları ("FEFO ne demek?", "CAB toplantısı hangi gün?") doğru sayfayı ilk sırada bulup 0,45–0,49 alıyordu, yani "bulunamadı" alıyordu. Cevaplanamaz 28 sorunun yalnız 6'sı eşiğin altındaydı; cevaplanamazların ortancası 0,547, en yükseği 0,702.
  - **Uçtan uca (GPU):** atıf isabeti 0,866, doğru bilgi 0,848, "bulunamadı" 0,536.
- Kısıtlar: ek PostgreSQL uzantısı ya da yeni container yok; embedding her modda yerel (llm-rules 1.2); sahiplik her sorgunun içinde (4.1); Flyway migration'ları değişmez (AGENTS.md 6).
- Ölçüm yöntemi ve bütün ara sonuçlar: `docs/evidence/hibrit-arama-dogrulama.md`.

## Seçenekler

Üç adım sırayla ölçüldü; her adım yalnız yardım ediyor ve diğer ölçüleri bozmuyorsa tutuldu.

| Adım | Seçenek | Ölçüm (cevaplanabilir 112 soru) | Sonuç |
|---|---|---|---|
| 1 | Embedding metnine belge adı ve başlık yolu ön eki (saklanan metin değişmez) | Yalnız: recall@1 0,795, recall@5 0,946, MRR 0,864. Soru bazında recall@1 +5/−6, recall@5 +1/−2. Yedi varyantın hiçbiri tutarlı kazanç vermedi | **Bırakıldı** |
| 1+2 | Ön ek, hibrit aramanın üstünde | recall@1 0,830, recall@5 0,982, MRR 0,896; ama cevaplanabilir en düşük benzerlik 0,45'ten 0,419'a indi ve kapının çapraz doğrulaması kötüleşti (aşağıda) | **Bırakıldı**: +4 soru recall@1 için yeniden indeksleme ve daha zayıf "bulunamadı" kararı |
| 2 | Hibrit: `to_tsvector('turkish')` + GIN, terimler VEYA ile, `ts_rank_cd`, RRF (k = 60) | recall@1 0,795, recall@5 **0,982** (110/112), MRR **0,877**. Soru bazında recall@5 +3/−0, recall@1 +4/−5 | **Tutuldu** |
| 2 | Aynı, `plainto_tsquery` / `websearch_to_tsquery` (terimler VE ile) | 140 sorudan 4'ü eşleşti; füzyon vektörle aynı | Reddedildi |
| 2 | İ/ı katlaması (`translate(…, 'İIı', 'iii')`) ya da `unaccent` | Füzyonda recall@1 ±1 soru, recall@5 aynı | Reddedildi: fark gürültü düzeyinde, generated column için ek fonksiyon gerekir |
| 2 | SQL içinde BM25 (`ts_rank_cd` yerine) | Füzyonda 0,821 / 0,964 / 0,886 | Reddedildi: recall@5 daha düşük, sorgu daha pahalı; ağırlık ayarı aynı veriyle yapılırdı |
| 3 | Kapı: cosine ≥ T1 **ya da** (cosine ≥ T2 **ve** en benzer pasaj soruya özgü bir terim içeriyor) | Cevaplanabilir 112/112 geçer, cevaplanamaz 7/28 durur; "bulunamadı" kesinliği 1,00, duyarlılığı 0,25 | **Tutuldu** |
| 3 | Kapıda füzyon skoru | – | Reddedildi: sıra bilgisidir, "yeterince iyi mi" sorusuna cevap vermez (görev tanımı) |

## Karar

**Hibrit arama (`RetrievalRepository.hybrid`, tek SQL cümlesi):**
- `document_chunk.search_vector`: `to_tsvector('turkish', content)` generated column (STORED) ve GIN index (`V3__chunk_search_vector.sql`). Migration mevcut satırları da doldurur; embedding'ler değişmez, yeniden indeksleme gerekmez.
- Vektör listesi: cosine'e göre en yakın 20 chunk. Sözcüksel liste: sorunun terimleri **VEYA** ile birleştirilir, `ts_rank_cd` ile en iyi 20 chunk. Hesap, embedding modeli ve `READY` koşulu iki listenin de içindedir (llm-rules 4.1).
- Füzyon: `Σ 1 / (60 + sıra)`; eşitlikte yüksek cosine önce. Sonuç füzyon sırasıyla döner; her pasajın cosine benzerliği ayrıca hesaplanır, yalnız sözcüksel listenin bulduğu pasajınki de.
- Ayarlar: `verso.document.retrieval.vector-candidates: 20`, `lexical-candidates: 20` (0 = yalnız vektör, acil kapatma), `rrf-k: 60`.

**Özgül terim payı (`RetrievedPassage.specificTermShare`):**
- Pasajın içerdiği en nadir soru teriminin, hesabın aranabilir chunk'larındaki payı. Kod ya da kısaltma gibi özgül bir terim düşük pay alır.
- Pay en çok %10'a kadar sayılır (`DocumentRetrieval.TERM_SHARE_LIMIT`): bundan sık geçen terim "yaygın" sayılır ve 1 döner. Büyük bir hesapta yaygın bir terimi tam saymak hesabın çoğunu taramak demektir.

**Cevaplanabilirlik kapısı (`AnswerabilityGate`, qa modülü):**
- Karar en benzer pasajın **cosine** benzerliğine bakar; füzyon skoruna bakmaz.
- Cevaplanabilir:
  - benzerlik ≥ `min-similarity` **0,47**, ya da
  - benzerlik ≥ `lexical-min-similarity` **0,44** ve aynı pasajın özgül terim payı ≤ `specific-term-max-share` **0,045**.
- Değilse chat modeli çağrılmaz, sabit "bulunamadı" döner (llm-rules 3.4).
- Prompt'a giden pasajlar (`passage-min-similarity` **0,50**): en benzer pasaj ve benzerliği 0,50 ya da üstü olan diğerleri, füzyon sırasıyla. Uçtan uca ölçümde bütün beş pasaj ya da 0,44 tabanı modelin cevaplanamaz sorularda kendi "bulunamadı" cevabını belirgin biçimde azalttı (10–11/28, bu kuralla 17/28).
- Parametrelerin yeri: `verso.qa.*` (`config/verso.yml`, `QaProperties`). Verso'da parametre kataloğu yok; eşikler yönetici iş kuralı değil, eval ile ölçülen başlangıç ayarıdır (llm-rules 3.4, sınıf BA).

**Değerlerin seçimi:**
- T1, T2 ve pay, eval setinden ızgara aramayla seçildi. Kısıt: hiçbir cevaplanabilir soru durmasın; amaç: en çok cevaplanamaz soru dursun.
- Çapraz doğrulama: belge grubuna göre altıya bölündü (sürüm 1 belgeleri, WMS, ERP, BT, CRM, İSG; cevaplanamazlar konularının grubunda), her grup bir kez dışarıda tutuldu.
  - Aynı aile çapraz doğrulamada: cevaplanabilir 110/112 geçer, cevaplanamaz 5/28 durur (kesinlik 0,714, duyarlılık 0,179).
  - Tek cosine eşiği çapraz doğrulamada: 111/112 ve 0/28.
  - Altı katın üçünde aynı değerler seçildi.
- T2, ızgaranın verdiği 0,45 yerine **0,44**: CPU ve GPU embedding'leri dördüncü basamakta ayrışıyor. erp-12, GPU'da 0,4502, CPU'da 0,4497.
- **Bilinen sınırlar:**
  - isg-10 (0,4712) T1'in 0,001 üstünde. bt-yok-3 (0,4697) altında.
  - bt-yok-2 ve bt-yok-3'ün payı 0,053, sınır 0,045.
  - Konusu belgede olan cevaplanamaz sorular hâlâ ayrışmıyor: 20 sorudan 3'ü duruyor. Bu soruları ucuz retrieval sinyalleri ayıramıyor.

**Sonuç sayıları (önce → sonra):**

| Ölçüm | Önce | Sonra |
|---|---|---|
| recall@1 / recall@5 / MRR | 0,804 / 0,955 / 0,869 | 0,795 / **0,982** / **0,877** |
| Kapıdan geçen cevaplanabilir | 107/112 | **112/112** |
| Kapının durdurduğu cevaplanamaz | 6/28 | **7/28** |
| "Bulunamadı" kesinliği / duyarlılığı (kapı) | 0,545 / 0,214 | **1,00** / **0,25** |
| Uçtan uca atıf isabeti / doğru bilgi | 0,866 / 0,848 | **0,902** / **0,902** |
| Uçtan uca "bulunamadı" (sıkı / modelin kendi reddiyle) | 0,536 / 0,643 | **0,607** / 0,643 |
| Uçtan uca gecikme p50 / p95 (GPU) | 1,37 / 3,08 sn | 1,70 / 2,81 sn |

## Sonuçlar

- **Olumlu:**
  - Kod, kısaltma ve sayı geçen sorular sözcüksel listeden geliyor. Recall@5'te 3 soru kazanıldı, kayıp yok.
  - Kısa kısaltma soruları artık modele gidiyor.
  - Kapı kararı ölçülebilir ve loglanıyor: `answerability=similarity|specific_term|none`.
- **Olumsuz / kabul edilen risk:**
  - recall@1'de net 1 soru kaybı var (0,804 → 0,795; +4/−5). Kayıplar sayı ve tablo sorularında.
  - Uçtan uca p50 0,3 sn arttı. Daha çok soru cevap üretiyor; veritabanı payı eval hesabında 1 ms.
  - Sözcüksel sorgunun maliyeti hesabın büyüklüğüyle artar (sentetik ölçüm):
    - 10 bin chunk: +20–35 ms;
    - 50 bin chunk: +185–280 ms.
    - `lexical-candidates: 0` bunu kapatır.
  - Migration `document_chunk`'ı yeniden yazar ve HNSW index'ini de yeniden kurar: 20 bin chunk'ta 14 sn, 100 bin chunk'ta 3,5 dk. Uygulama başlamadan önceki migrate adımında koşar; büyük kurulumda bakım penceresi ister.
  - DOCX ve MD'de (`FormatEvalTest`) kapının durdurduğu cevaplanamaz sorular 5/28'den 2/28'e indi; T1 PDF sayfalarıyla ölçüldü.
  - Değerler aynı setle seçildi; çapraz doğrulama sayıları daha temkinlidir.
- **Etkilenen dosyalar ve yüzeyler:**
  - `document-api`: `DocumentRetrieval`, `RetrievedPassage`. Bu modüller arası in-process sözleşmedir; eski kurucu korunur, yeni alan için varsayılan 1'dir.
  - `document-core`: `RetrievalRepository`, `DocumentRetrievalServiceImpl`, `DocumentProperties`, `V3__chunk_search_vector.sql`.
  - `qa-core`: `AnswerabilityGate`, `QaProperties`, `QuestionServiceImpl`.
  - `config/verso.yml`; `scripts/eval.mjs`.
  - Ortam değişkeni, secret ya da port eklenmedi.
- **Yeniden indeksleme:**
  - Bu karar gerektirmez. Sözcüksel sütunu migration doldurur; embedding metni değişmedi.
  - Embedding metni ileride değişirse (ör. adım 1 yeniden denenirse): hazır belgeler `PENDING`'e alınır. Worker, saklanan sayfa metninden (`document_page`) yeniden chunk'lar ve embed eder; dosya gerekmez. Bu sürede belge aranamaz.
  - Kesintisiz bir yeniden indeksleme işi (sürüm kolonu, belge `READY` kalırken yeni chunk'ların yazılması) o karar ile birlikte tasarlanır.
- **Geri alma yolu:**
  - Ayarla: `verso.document.retrieval.lexical-candidates: 0` ve `verso.qa.min-similarity: 0.50`, `lexical-min-similarity: 0.50`. Bu, önceki davranışa çok yakındır.
  - Kodla: commit geri alınır. V3 sütunu ve index'i eski kodla zararsızdır; düşürmek yeni bir migration ister.

## Yeniden değerlendirme koşulu

- Gerçek bir müşteri belge seti ile eval yapıldığında eşikler yeniden ölçülür.
- Bir hesap 20 bin chunk'ı geçtiğinde ya da soru sorma p95'inde veritabanı payı 200 ms'yi aştığında sözcüksel sorgu yeniden ele alınır: BM25 (`pg_textsearch`), terim sayısını sınırlama ya da önbellek.
- Konusu belgede olan cevaplanamaz soruları ayırmak için rerank (yol haritası iş 6) ölçüldüğünde kapı yeniden kurulur.
