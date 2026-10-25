# Çapraz kodlayıcı rerank: ölçüm ve kanıt kaydı (ADR-0043)

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

## Künye

- **Tarih:** 2026-10-24/25
- **Branch:** `feature/doguyaras-reranker` (`origin/develop` üzerinden).
- **Ortam:** Windows 11 + Docker Desktop; AMD Ryzen 7 9800X3D, 31 GB RAM, NVIDIA RTX 4070 Ti SUPER 16 GB; çalışan GPU'lu yığın (`compose.yaml` + `deploy/compose.gpu.yaml`), Ollama 0.40.10, bge-m3:567m, gemma4:e2b.
- **Model ve sunucu:** `BAAI/bge-reranker-v2-m3` revision `953dc6f6…d41e` (Apache 2.0, model kartında doğrulandı); HF TEI 1.9.4, `89-1.9` (Ada) GPU imajı, fp16.
- **Aday dışı:** Qwen3-Reranker-0.6B (Apache 2.0). TEI desteklemiyor (issue #643 açık); Ollama'da rerank API'si yok. Ölçülmedi.
- **Davranışsal kapsam (seviye 3):** var. Retrieval çevrimdışı düzenekle, uçtan uca çalışan yığında, ikisi de GPU'da.

## 1. Yöntem

**Retrieval (çevrimdışı)**, `eval/embedding-olcumu/rerank.mjs`:
- Önceki ölçümlerin düzeneği (`docs/evidence/embedding-olcumu.md`): `ChunkDump` (75 chunk) → `lex.mjs` (üretimdeki sözcüksel SQL) → bge-m3 vektörleri. Sözcüksel listeler ayrı bir PostgreSQL yığını açılmadan, çalışan yığının PostgreSQL'inde oturuma özel `TEMP` tabloyla hesaplandı; hiçbir kalıcı tabloya yazılmadı.
- Füzyon üretimdeki gibi (cosine ilk 20 + sözcüksel ilk 20, RRF k = 60). Füzyonun ilk N'i (20 ve 40) TEI `POST /rerank`'e gider, en iyi 5'i tutulur.
- Uygulamanın seçim kuralı birebir uygulanır (aşağıda "kapı").
- **Düzenek doğrulaması:** rerank'siz satır resmî `RetrievalEvalTest` sonucunu üç basamakta aynen verdi (0,795 / 0,982 / 0,877; kapı 112/112 ve 7/28).
- Gecikme: soru başına bir istek, ardışık, 3 geçiş × 140 = 420 istek, host'tan HTTP ile.

**Uçtan uca**, `scripts/eval.mjs`:
- Aynı imaj iki ayarla: `VERSO_RERANKER_ENABLED=false` (taban) ve `true`. Yığının veritabanı şeması `verso:local` imajınınkiydi (`bb4bf8c`); ölçüm imajı bu commit'e bu branch'in retrieval değişikliği uygulanarak kuruldu, veritabanına migration koşulmadı.
- Yan konteyner `deploy/compose.reranker.yaml` ile, teslim edildiği gibi: `reranker-pull` modeli SHA-256 ile indirdi, sunucu iç `rerankers` ağında, portsuz, `HF_HUB_OFFLINE=1` ile 9 sn'de hazır oldu.
- CI istemci secret'ı koşu için worktree'nin git'e girmeyen `secrets/` klasörüne kopyalandı, koşudan sonra silindi.
- Rerank açık koşu iki kez yapıldı: ilk koşu uygulama yeniden oluşturulduktan hemen sonra başladı ve ilk üç soru ısınma gecikmesi aldı.

## 2. Retrieval (eval v2, 112 cevaplanabilir soru)

`eval/results/retrieval-rerank-gpu.json`.

| Alt küme | Soru | recall@1 hibrit → rerank | recall@5 hibrit → rerank | MRR hibrit → rerank |
|---|---|---|---|---|
| **Bütün set** | 112 | 0,795 → **0,929** | 0,982 → **1,00** | 0,877 → **0,958** |
| Sürüm 1 soruları | 25 | 0,84 → 1,00 | 1,00 → 1,00 | 0,92 → 1,00 |
| Sürüm 2 soruları | 87 | 0,782 → 0,908 | 0,977 → 1,00 | 0,864 → 0,946 |
| `paraphrase` | 17 | 0,647 → **0,824** | 0,941 → **1,00** | 0,752 → 0,897 |
| `abbreviation` | 20 | 0,85 → 0,95 | 1,00 → 1,00 | 0,925 → 0,967 |
| `number` | 50 | 0,80 → 0,96 | 0,98 → 1,00 | 0,875 → 0,98 |
| `table` | 26 | 0,731 → **0,885** | 0,962 → **1,00** | 0,827 → 0,926 |
| `disambiguation` | 21 | 0,81 → 0,81 | 0,952 → 1,00 | 0,869 → 0,885 |

- **Soru bazında:** recall@1 +18/−3 (kayıp: depo-11, depo-16, bt-8); recall@5 +2/−0 (bt-3, bt-5: önceki tek iki kaçak).
- **İlk 20 ile ilk 40 aynı sonucu verdi** (soru bazında fark yok). Füzyon listesinde zaten en çok 40 aday var; varsayılan 20.
- Sayılar uygulamanın seçim kuralıyla (kanıt pasajı korunur) ve saf rerank ilk 5'iyle aynı çıktı.

## 3. Cevaplanabilirlik kapısı

Kapı (ADR-0022) döndürülen pasajlar içinde cosine'i en yüksek olanı okur. Rerank ilk 5'i değiştirince bu pasaj da değişebilir:

| Seçim | Cevaplanabilir geçen | Cevaplanamaz duran | Taban kararından farklı soru |
|---|---|---|---|
| Rerank yok (taban) | 112/112 | 7/28 | – |
| Saf rerank ilk 5 | 112/112 | **5/28** | 2 (kapı iki cevaplanamaz soruyu modele açtı) |
| **Uygulamanın kuralı** (kanıt pasajı korunur) | 112/112 | 7/28 | **0** |

- **Kural** (`DocumentRetrievalServiceImpl.reranked`): füzyonun ilk k'sı içinde cosine'i en yüksek pasaj ("kanıt") her zaman döner; ondan daha benzer ya da eşit benzer başka pasaj dönmez. Bu yüzden kapının okuduğu pasaj rerank'li ve rerank'siz aynıdır. Rerank kanıtı 140 sorunun 11'inde ilk 5'in dışına itti; kural onu son sıraya koydu.
- `RerankedSelectionTest` bunu 2000 rastgele sıralamada da doğrular.
- **Yeniden kalibrasyon denemesi (gönderilmedi):** rerank'in en iyi skoru cevaplanabilirliği iyi ayırıyor (AUC 0,965). Kapıya "VE rerank skoru ≥ t" eklemek örneklem içinde 112/112 ve 10/28 verdi. Belge grubuna göre altı katlı çapraz doğrulamada 111/112 ve 10/28 (kesinlik 0,909). En düşük cevaplanabilir skorlar (0,004 ve 0,009) cevaplanamazların arasında kalıyor. Kazanç 3 soru, bedeli 1 cevaplanabilir soru; kapı ayrıca yan konteynere bağımlı olurdu. Kapı değişmedi; ADR-0043 yeniden değerlendirme koşulunda.

## 4. Uçtan uca (GPU, gemma4:e2b, 140 soru, hata yok)

`eval/results/e2e-rerank-{off,on,on2}-gpu.json`.

| Ölçüm | Rerank kapalı | Rerank açık (1. koşu) | Rerank açık (2. koşu) |
|---|---|---|---|
| Kaynak bulundu | 0,955 | 0,964 | 0,964 |
| Atıf isabeti | 0,902 | 0,893 | 0,911 |
| Atıf kesinliği | 0,922 | 0,902 | 0,911 |
| Doğru bilgi | 0,902 | 0,902 | 0,920 |
| `paraphrase` atıf isabeti / doğru bilgi | 0,765 / 0,765 | 0,824 / 0,765 | 0,824 / 0,824 |
| `table` atıf isabeti / doğru bilgi | 0,769 / 0,808 | 0,769 / 0,808 | 0,846 / 0,846 |
| "Bulunamadı", sıkı | **16/28** | **14/28** | **14/28** |
| "Bulunamadı" ya da modelin kendi reddi | 0,643 | 0,679 | 0,679 |
| Kapının durdurduğu | 7 | 7 | 7 |
| Gecikme p50 / p95 / en çok | 1,21 / 1,98 / 3,2 sn | 1,30 / 3,89 / 27,0 sn (ısınma) | 1,30 / 2,18 / 4,8 sn |

- **İki açık koşu arasındaki fark** (atıf isabeti 0,893 ↔ 0,911, doğru bilgi 0,902 ↔ 0,920) taban ile açık arasındaki farkla aynı büyüklükte. Atıf ve bilgi için uçtan uca kazanç gürültü düzeyinde.
- **"Bulunamadı" iki koşuda da 2 soru kötü** (16 → 14). Kapı aynı 7 soruyu durdurdu; modelin karar verdiği 21 sorudan doğru "bulunamadı" 9'dan 7'ye indi. Rerank, konusu belgede olan cevaplanamaz sorularda en ilgili görünen pasajları öne alıyor; model bunlardan cevap kuruyor.
- **Neden retrieval kazancı uçtan uca taşınmıyor:** recall@5 zaten 0,982 idi; model ilk 5 pasajın hepsini görüyor. Rerank sırayı ve atıf numaralarını değiştiriyor ama pasaj kümesini nadiren.

## 5. Gecikme ve kaynak

| Ölçüm | Değer |
|---|---|
| Rerank çağrısı, çevrimdışı düzenek, ilk 20 (420 istek) | p50 93 ms, p95 111 ms, en çok 430 ms (ilk istek) |
| Aynı, ilk 40 | p50 116 ms, p95 134 ms |
| Uygulama içinde (`verso_retrieval_rerank_duration`, 2. koşu, 140 çağrı) | ortalama 98 ms, en çok 189 ms; 140/140 `reranked` |
| Uygulamanın retrieval süresi, rerank'li (`verso_qa_retrieval_duration`) | ortalama 218 ms, en çok 484 ms |
| TEI tek istek iç süresi (log) | 67–114 ms (kuyruk 11–42 ms, çıkarım 43–65 ms) |
| VRAM (yan konteyner) | +1,4 GB boşta (8,2 → 6,8 GB durunca), +1,6 GB yük altında |
| RAM (yan konteyner) | 2,5–2,7 GiB / 3 GiB (ağırlıkların sayfa önbelleği dahil) |
| Hazır olma | 9 sn (çevrimdışı, yerel ağırlıklardan) |
| İmaj | 8,2 GB (`89-1.9`); model 2,27 GB |

- **CPU:** `cpu-1.9` imajı 4 CPU ve 3 GB sınırla denendi. İlk istekte bellek sınırı aşıldı (çıkış 137) ve makine o sırada yeniden başladı. CPU gecikmesi ölçülemedi; CPU kurulumu desteklenmez (ADR-0043). Araştırma notundaki dış ölçüm: bge-reranker-large'da 20 pasaj CPU'da 7–10 sn.
- **Bütçe:** QA p99 bütçesi local-GPU 15 sn; rerank p95'i 0,11 sn ekler. Darboğaz yine tek chat slotudur (`docs/evidence/yuk-olcumu.md`).
- **Log gizliliği:** TEI istek başına yalnız süreleri yazar; 291 satırda soru ya da pasaj metni yok. Uygulama tarafında rerank yalnız devre açılınca sabit alanlarla log'a yazar (`PassageRerankerTest.logs_neverCarryTheQuestionOrAPassage`).

## 6. Karar

- **Kod isteğe bağlı olarak tutuldu, varsayılan kapalı; GPU kurulumları için de varsayılan önerilmiyor.** Retrieval belirgin biçimde iyileşiyor (recall@1 +13 puan, MRR +0,08). Ama gemma4:e2b ile uçtan uca atıf ve bilgi değişmiyor, sıkı "bulunamadı" iki koşuda da 2 soru kötüleşiyor.
- Açmanın anlamlı olduğu yer: sıralamanın kendisinin sonuç olduğu yollar. MCP `verso_search` istemciye sıralı pasaj döndürür (ADR-0031); ilk sonucun doğru olma oranı 0,795'ten 0,929'a çıkar. Daha güçlü bir chat modeliyle (cloud mod) uçtan uca etki ölçülmedi.
- Kapı değişmedi.

## 7. Makine kontrolleri

| Kontrol | Komut | Sonuç |
|---|---|---|
| Birim testleri | `./mvnw -pl services/document/document-core -am test -Dtest='Reranker*Test,PassageRerankerTest,RerankedSelectionTest'` | **PASS**, 25 test |
| Mimari, config, retrieval, MCP, QA akışı | `-pl verso-app -am test -Dtest='…,RetrievalTest,CollectionAccessTest,ConfigDriftTest,ImageVersionsTest,ArchitectureRulesTest,DocumentArchitectureTest,ConfigProfilesTest,McpUnitTest,QuestionStreamTest'` (rebase sonrası) | **PASS** |
| Entegrasyon (stub rerank, Testcontainers) | `RerankerRetrievalTest` | **PASS**, 3 test |
| Script testleri | `node --test scripts/*.test.js scripts/panel.test.mjs` | **PASS**, 237/238 (1 atlanan) |
| Alarm kuralları | `promtool test rules alerts.test.yml` (`RerankerFallback` iki senaryo) | **SUCCESS** |
| Compose | `docker compose -f compose.yaml -f deploy/compose.gpu.yaml -f deploy/compose.reranker.yaml config` | geçerli; boş `VERSO_RERANKER_IMAGE` varsayılan digest'e düşer |

**Yeni testler:**
- `RerankerClientTest`: gönderilen gövde, sıra okuma, bozuk cevaplar (5xx, 429, 4xx, eksik, tekrarlanan, uydurulan, negatif, metin indeks, JSON değil, 64 KB üstü), zaman aşımı, kimse dinlemiyor, hata mesajında metin yok, dış adres ve yönlendirme yok.
- `PassageRerankerTest`: sıra geçer; kapalıyken çağrı yok; hata, zaman aşımı (200 ms sınırda < 1,5 sn), dolu bulkhead ve açık devrede füzyon sırası; 3 hatada devre açılır, süre bitince kapanır; başarı sayacı sıfırlar; uzun pasaj vekil çifti bölünmeden kesilir; log'da soru ve pasaj yok.
- `RerankedSelectionTest`: rerank sırası, kanıt pasajı korunur, kanıttan benzer dış pasaj alınmaz, 2000 rastgele sıralamada kapının okuduğu pasaj aynı.
- `RerankerPropertiesTest`: kapalıyken adres gerekmez; açıkken http(s), kimlik bilgisiz; sınırlar.
- `RerankerRetrievalTest` (gerçek veritabanı, stub TEI): rerank'in ilk seçimi öne geçer ve kapının pasajı aynı kalır; stub 500 verince füzyon sırası; başka hesabın metni yan konteynere gitmez, tek aday için çağrı yapılmaz.

## 8. Review

Skill kararları (`docs/ai/review-checklist.md`), her biri bir kez:

| Skill | Karar | Not |
|---|---|---|
| `verso-llm-review` | APPROVE WITH NON-BLOCKING COMMENTS | Soru ve okuyucunun kendi ilk 20 pasajı yalnız iç ağdaki yan konteynere gider (iç adres filtresi, yönlendirme yok; compose `rerankers` `internal`). Log'da yalnız sabit sonuç; TEI yalnız süre yazar. Çağrı transaction dışında; prompt, atıf ve kapı değişmedi. Cloud moda giden içerik aynı (top-k) |
| `verso-resilience-review` | APPROVE WITH NON-BLOCKING COMMENTS | İkinci senkron çağrı ADR-0043'te gerekçeli, `repo-context.md` sıcak yol satırında; 500 ms / 2 sn sert sınır, bulkhead 4, devre 3 / 1 dk, her hatada füzyon sırası. Eksik alarm review'da eklendi: `RerankerFallback` + `docs/runbooks/reranker-fallback.md` |
| `verso-environment-impact-review` | APPROVE WITH NON-BLOCKING COMMENTS | `verso.document.reranker.*` beş yüzeyde (verso.yml, application-local, prod.env.example, compose.yaml, overlay); `ConfigDriftTest` aynalı anahtarlar. `VERSO_RERANKER_IMAGE` review'da `.env.example`'a eklendi. Eski imaja geri dönüşte yeni anahtarlar zararsız; migration yok |
| `verso-spring-code-review` | APPROVE | Constructor injection; yeni bean'ler yalnız web uygulamasında; uzak çağrı salt-okunur transaction'dan sonra; executor sanal iş parçacığı + semaphore (embedding'deki kalıp); log ve exception'da içerik yok |
| `verso-test-writer` | PASS | Bölüm 7. Boşluk: uçtan uca kalite CI'da değil (GPU ve model ister); CPU yolu desteklenmediği için testi yok |
| `verso-security-review` | APPROVE WITH NON-BLOCKING COMMENTS | Tedarik zinciri: imajlar digest ile, model revision + dosya başına SHA-256, `--proto =https`, safetensors (pickle yok). Egress: sunucu çevrimdışı, iç ağ, portsuz, API anahtarı yok (TEI ≤ 1.9.4 anahtar log'lama açığı uygulanmaz). MEDIUM: aylık SBOM taraması overlay imajlarını kapsamıyor (docling ile aynı açık; ADR-0043'te). LOW: upstream imza/SBOM yok |

## 9. Ortam

- Ölçüm sonunda yan konteyner, model volume'ü, ağ ve imajlar silindi; yığın `verso:local` ile çalışıyor.
- Ölçüm sırasında Docker Desktop bir kez kapandı (bu iş yük altındayken değil, makinede başka bir uygulama çalışırken). Yığın aynı container kümesiyle yeniden açıldı; kendiliğinden kalkan `obs` container'ları durduruldu.
- CPU denemesi sırasında makine yeniden başladı (bölüm 5).
