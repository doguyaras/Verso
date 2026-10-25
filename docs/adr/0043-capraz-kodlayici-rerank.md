# ADR-0043: İsteğe bağlı çapraz kodlayıcı rerank (GPU)

- **Durum:** Kabul edildi (isteğe bağlı, varsayılan kapalı)
- **Tarih:** 2026-10-25
- **Karar verenler:** doguyaras. İstek: retrieval'a GPU kurulumları için isteğe bağlı bir cross-encoder rerank eklemek ve ölçmek (yol haritası `rag_quality.md` iş 6; ADR-0022 yeniden değerlendirme koşulu).
- **İlgili eşik (referans Bölüm 24):** ADR-0008 sıcak yol bütçesi (local-GPU QA p99 15 sn); aşağıda "Yeniden değerlendirme koşulu".
- **Genişlettiği:** ADR-0022 (hibrit arama ve cevaplanabilirlik kapısı). Kapının kuralı ve eşikleri değişmez.

## Bağlam

- Hibrit aramada (ADR-0022) recall@5 0,982, ama recall@1 0,795 ve MRR 0,877. `paraphrase` ve `table` türlerinde recall@1 0,647 ve 0,731.
- Dış kanıt (araştırma notları): Çin uygulamalarında bge-reranker-v2-m3 ile ilk 50'den rerank +8–15 puan; bir Japon vakasında (Qiita) rerank doğruluğu düşürdü (sorun recall'daydı). Türkçe rerank ölçümü yayımlanmamış; Verso kendisi ölçmeli.
- Kısıtlar: soru metni ve pasajlar yerelde kalır (llm-rules 1.1, 2.1); her uzak çağrı timeout + devre kesici + bulkhead altında (AGENTS.md 5); kapı cosine okur ve ölçülmeden değişmez (ADR-0022); makine kaynakları sınırlı (tek yan konteyner, ≤ 3 GB).
- Ölçüm ve bütün sayılar: `docs/evidence/rerank-olcumu.md`.

## Seçenekler

| Seçenek | Artı | Eksi | Sonuç |
|---|---|---|---|
| **bge-reranker-v2-m3 + HF TEI 1.9.4** (GPU imajı) | Apache 2.0 (model ve sunucu); bge-m3 ile aynı tokenizer; TEI bakımda (1.9.4, Eylül 2026), `POST /rerank` | İmaj 8,2 GB, GPU kuşağına göre etiket; upstream imza/SBOM yok; TEI ≤ 1.9.4 API anahtarını log'a yazar (anahtar kullanmıyoruz) | **Seçildi** |
| Qwen3-Reranker-0.6B | Apache 2.0, MMTEB-R yüksek | TEI desteklemiyor (issue #643), Ollama'da rerank API'si yok | Ölçülmedi |
| Infinity | Çok model | Ek bir sunucu ve imaj; TEI yeterli | Seçilmedi |
| JVM içinde ONNX Runtime | Ek container yok | int8 dönüşümü kaliteyi ölçmeyi ister; JVM belleği; ONNX model yükleme CVE'si | Seçilmedi |
| CPU'da TEI (`cpu-1.9`) | GPU gerekmez | 3 GB sınırında fp32 model ilk istekte belleği aştı | Desteklenmez |
| Aday sayısı 20 / 40 | – | 40 aynı kalite, +23 ms p50 | **20** |

## Karar

**Akış** (`DocumentRetrievalServiceImpl.search`):
1. Hibrit sorgu değişmez; yalnız `LIMIT` top-k yerine `candidates` (20) olur. Terim payları aynı salt-okunur transaction'da hesaplanır.
2. Transaction bittikten sonra füzyonun ilk N pasajı ve soru TEI'ye gider (`PassageReranker` → `RerankerClient`, `POST /rerank`). Pasaj en çok 2000 karakter gönderilir.
3. Dönen sırayla top-k seçilir, şu kuralla (`reranked`): füzyonun ilk k'sı içinde cosine'i en yüksek pasaj ("kanıt") her zaman döner; ondan daha benzer ya da eşit benzer başka pasaj dönmez.
4. **Kapı aynen aynı pasajı okur**, aynı kararı verir. Saf rerank ilk 5'i kapıyı 2 cevaplanamaz soruya açıyordu (7/28 → 5/28); bu kuralla fark 0.
5. Herhangi bir hata (kapalı devre, dolu bulkhead, zaman aşımı, 5xx, bozuk cevap) füzyon sırasını döndürür: rerank'siz davranış.

**Koruma:** tüm çağrı 2 sn (sert sınır, iş parçacığı kesilir), bağlantı 500 ms; instance başına 4 eşzamanlı çağrı; arka arkaya 3 hatada 1 dk atlanır. Yönlendirme izlenmez, yalnız iç adres, cevap ≤ 64 KB; cevap 0..n-1'in bir permütasyonu değilse geçersiz. Metrikler `verso_retrieval_rerank_total{outcome}` ve `verso_retrieval_rerank_duration`; log yalnız devre açılınca sabit alanlarla.

**Ayarlar** (`verso.document.reranker.*`, `config/verso.yml`): `enabled` (`VERSO_RERANKER_ENABLED`, varsayılan `false`), `base-url` (`VERSO_RERANKER_URL`), `candidates` 20, `connect-timeout` 500ms, `timeout` 2s, `max-passage-chars` 2000, `concurrency` 4, `circuit-failures` 3, `circuit-open` 1m. Kullanıcının istediği `verso.retrieval.reranker.enabled` yerine modülün kendi ad alanı kullanıldı (ADR-0032'deki `verso.document.docling.*` gibi).

**Dağıtım** (`deploy/compose.reranker.yaml`, GPU overlay'inin üstüne):
- `reranker-pull` (curl 8.22.0, digest ile): model revision `953dc6f6…d41e`'nin beş dosyasını https ile indirir; her dosya SHA-256 ile doğrulanmadan volume'e girmez; ikinci koşuda indirmez.
- `reranker` (TEI `89-1.9@sha256:1a284d9c…`): `--model-id` yerel klasör, `HF_HUB_OFFLINE=1`, salt okunur kök ve model volume'ü, `cap_drop: ALL`, 3 GB, GPU rezervasyonu; yalnız iç `rerankers` ağında, portsuz, API anahtarsız (ağ yalnız uygulamayı alır).
- Uygulamanın `depends_on`'u yok: yan konteyner yokken retrieval füzyon sırasıyla çalışır.

**Ölçülen sonuç** (eval v2):

| Ölçüm | Hibrit (ADR-0022) | Hibrit + rerank |
|---|---|---|
| recall@1 / recall@5 / MRR | 0,795 / 0,982 / 0,877 | **0,929 / 1,00 / 0,958** |
| `paraphrase` recall@1 / @5 | 0,647 / 0,941 | 0,824 / 1,00 |
| `table` recall@1 / @5 | 0,731 / 0,962 | 0,885 / 1,00 |
| Kapı: cevaplanabilir geçen / cevaplanamaz duran | 112/112, 7/28 | 112/112, 7/28 |
| Uçtan uca atıf isabeti (aynı imaj, iki açık koşu) | 0,902 | 0,893 / 0,911 |
| Uçtan uca doğru bilgi | 0,902 | 0,902 / 0,920 |
| Uçtan uca sıkı "bulunamadı" | 16/28 | 14/28 / 14/28 |
| Uçtan uca gecikme p50 / p95 (ısınmış koşu) | 1,21 / 1,98 sn | 1,30 / 2,18 sn |
| Rerank çağrısı p50 / p95 | – | 93 / 111 ms |
| VRAM / RAM (yan konteyner) | – | +1,4–1,6 GB / 2,5–2,7 GiB |

**Karar:** kod isteğe bağlı kalır, **varsayılan kapalı; GPU kurulumlarında da varsayılan önerilmez.** gemma4:e2b ile uçtan uca atıf ve bilgi kazancı gürültü düzeyinde, sıkı "bulunamadı" 2 soru kötü. Açmak, sıralamanın kendisinin sonuç olduğu yerde anlamlı: MCP `verso_search` (ADR-0031) istemciye sıralı pasaj döndürür.

## Sonuçlar

- **Olumlu:**
  - Retrieval sıralaması belirgin biçimde iyi: recall@1 +18/−3 soru, iki kalıcı kaçak (bt-3, bt-5) bulundu.
  - Kapının kararı yapı gereği değişmez; ölçümde ve `RerankedSelectionTest`'te doğrulandı.
  - Kapalıyken hiçbir şey değişmez: istemci yoktur, sorgu `LIMIT` top-k'dır.
- **Olumsuz / kabul edilen risk:**
  - Uçtan uca kazanç yok; sıkı "bulunamadı" iki koşuda da 16/28 → 14/28 (açıkken).
  - Sıcak yolda ikinci bir uzak çağrı (embedding + rerank + chat = 3). Bütçe: p95 +0,11 sn; local-GPU p99 15 sn bütçesi içinde. AGENTS.md 5'in "en çok bir uzak senkron çağrı" varsayılanından sapma bu ADR'dir.
  - VRAM 1,4–1,6 GB ve RAM 2,5–2,7 GiB ek; 16 GB GPU'da Ollama'nın iki modeliyle toplam ~8–10 GB.
  - CPU kurulumu desteklenmez (bellek). GPU kuşağına göre farklı TEI etiketi gerekir.
  - TEI imajı 8,2 GB, upstream imza/SBOM yok; TEI ≤ 1.9.4'te API anahtarı log'a yazılır (anahtar kullanılmıyor). Digest pin'i geçerli; aylık SBOM taraması (`scripts/sbom-images.js`) bugün yalnız `compose.yaml`'ı okur, overlay imajlarını (docling gibi) kapsamaz. Overlay açılan kurulumda TEI ve curl imajları elle taranır (`docs/runbooks/aylik-guvenlik-taramasi.md`).
- **Etkilenen dosyalar ve yüzeyler:**
  - `document-core`: `config/RerankerProperties`, `config/DocumentConfiguration`, `service/{PassageReranker,RerankerClient}`, `service/impl/DocumentRetrievalServiceImpl`.
  - `config/verso.yml`, `application-local.yml`, `deploy/prod.env.example`, `.env.example`, `compose.yaml` (env varsayılanları), `deploy/compose.reranker.yaml`, `deploy/reranker/pull.sh`.
  - `document-api` sözleşmesi ve `qa` modülü değişmedi.
  - Yeni ortam değişkenleri: `VERSO_RERANKER_ENABLED`, `VERSO_RERANKER_URL`, `VERSO_RERANKER_IMAGE`, `RERANKER_MEM_LIMIT`. Secret ve yayınlanan port yok.
- **Geri alma yolu:** `VERSO_RERANKER_ENABLED=false` ve overlay'i çıkarmak; volume `reranker-models` silinebilir. Migration yok.

## Yeniden değerlendirme koşulu

- Daha güçlü bir chat modeli (cloud mod ya da daha büyük yerel model) ile uçtan uca yeniden ölçülür; atıf ya da bilgi gürültünün üstünde artarsa GPU kurulumları için varsayılan açılması önerilir.
- Kapıya rerank skoru: çapraz doğrulamada 111/112 ve 10/28 (kesinlik 0,909; bugünkü kapı 7/28). Gerçek bir müşteri setinde cevaplanabilir kayıp olmadan 3'ten fazla soru kazanılırsa ve yan konteyner yokken cosine kapısına düşüş tasarlanırsa yeniden ele alınır.
- Gerçek bir belge setinde recall@5 0,95'in altına düşerse ilk 40 ve rerank yeniden ölçülür.
- TEI Qwen3-Reranker'ı desteklediğinde karşılaştırılır.
