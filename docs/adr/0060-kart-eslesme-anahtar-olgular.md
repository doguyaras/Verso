# ADR-0060: Onaylı kart eşleşmesinde anahtar olgu süzgeci ve isteğe bağlı reranker kontrolü

- **Durum:** Kabul edildi. Anahtar olgu süzgeci her zaman açık (ayar değil). Reranker kontrolü isteğe bağlı, varsayılan kapalı (`verso.qa.knowledge.rerank-check.enabled`). Eşik değişmedi: 0,95.
- **Tarih:** 2026-10-29
- **Karar verenler:** doguyaras. İstek: onaylı kart eşleşmesini daha kesin yapmak ve eşiğin kanıtla inip inemeyeceğine karar vermek.
- **Genişlettiği:** ADR-0049 (onaylı bilgi kartı; sunma sırası ve eşik), ADR-0043 (reranker; aynı korumalarla ikinci bir kullanım).
- **İlgili eşik (referans Bölüm 24):** ADR-0008 sıcak yol bütçesi. Süzgeç süreç içidir, uzak çağrı eklemez. Reranker kontrolü açıksa kart başına +1 rerank çağrısı.
- **Kanıt:** `docs/evidence/kart-eslesme-olcumu.md`.

## Bağlam

- ADR-0049 kartı sorunun kosinüs benzerliğiyle eşler. 0,93'te 30 gerçek yakın ıskanın 3'ü karta düşüyordu; eşik 0,95'e çıktı. Bedeli: yeniden ifadelerin yalnız 2/64'ü karta düşer.
- Kosinüs anahtar olguyu ayıramaz. "500.000 TL üstü alım" ile "50.000 TL üstü alım"; farklı kod, yıl, madde numarası ya da birim neredeyse aynı vektörü verir. ADR-0049'da gerçek değerler: P1/P3 0,933, 0–5/5–15 yıl 0,941.
- Kart "uzman onaylı" diye sunulur. Yanlış karttan gelen cevap, kaçan bir eşleşmeden pahalıdır; kaçan soru normal RAG yoluna gider.
- Kısıtlar:
  - Soru metni log'a, metriğe ve span'e girmez (llm-rules 2.1).
  - Sıcak yolda yeni uzak çağrı varsayılan olarak eklenmez (AGENTS.md 5).
  - Çevrimdışı gerçek embedding yok; ölçüm Docker'sız koşmalı.

## Seçenekler

| Seçenek | Artı | Eksi | Sonuç |
|---|---|---|---|
| **Deterministik anahtar olgu süzgeci** | Süreç içi, < 1 ms, modelsiz; kararı açıklanabilir (neden kodu); kosinüsün kör olduğu sınıfı tam kapatır | Niyet farkını ("ne kadar sürede" / "kime") görmez; aynı değeri başka birimle yazan yeniden ifadeyi reddeder ("48 saat" / "iki gün") | **Seçildi** |
| Reranker skoru (bge-reranker-v2-m3) ikinci kontrol | Niyet farkını görebilir | GPU yan konteyneri ister; sıcak yolda +1 uzak çağrı; Türkçe kart çiftlerinde ölçülmedi | **İsteğe bağlı, varsayılan kapalı** |
| Yalnız eşiği yükseltmek (0,96+) | Kod yok | Recall daha da düşer; anahtar olgu farkı yine geçebilir | Seçilmedi |
| LLM yargıcı | Esnek | Sıcak yolda chat çağrısı; bellekten cevabın "chat yok" kazancını siler; injection yüzeyi | Seçilmedi |
| Grounding'in sayı okuyucusunu (ADR-0047 `AnchorCheck`) yeniden kullanmak | Tek tanım | "500 bin", "%18", para birimi, birim, tarih, olumsuzluk okumaz; "1,5" ile "15"i aynı sayar | Kod biçimi (`TurkishText.CODE_REGEX`) ve katlama paylaşıldı; sayı okuyucusu ayrı |

## Karar

**Sunma sırası** (`AnswerMemory.lookup`):
1. Kosinüs ≥ `match-threshold` adaylar (değişmedi).
2. Kart yeniden okunur; durumu ve süresi kontrol edilir (değişmedi).
3. **Yeni: anahtar olgu süzgeci.** Kaynak okunmadan önce çalışır, I/O yoktur. Çakışma varsa kart reddedilir, sıradaki aday denenir.
4. Okuyucunun ilkeleriyle bağ ve özet kontrolü (değişmedi).
5. **Yeni, varsayılan kapalı: reranker kontrolü.** Son adımdır, çünkü tek uzak çağrıdır. Yalnız bu çağırana zaten sunulabilecek kart için çalışır.

**Anahtar olgular** (`KeyFacts`, soru başına bir kez, aday kart başına bir kez okunur):

| Tür | Okunan | Normalleştirme |
|---|---|---|
| Sayı | "500.000", "1,5", "1.500,75", "500 bin", "1,5 milyon", sayı sözcükleri ("on beş", "iki buçuk") | Nokta binlik, virgül ondalık. "500 bin" = "500.000" |
| Tutar | Sayı + para birimi: TL, lira, ₺, USD, $, EUR, €, GBP | "50.000 TL" = "50 bin lira" |
| Yüzde | "%18", "yüzde 18" | |
| Miktar | Sayı + ölçü birimi: gün, iş günü, saat, ay, yıl, GB, kg, kişi … | "30 gün" ≠ "30 saat"; "3 iş günü" ≠ "3 gün" |
| Tarih | "01.03.2025", "1 Mart 2025", "Mart ayı", "2025 yılı", "09:00" | Yıl, ay, gün ayrı olgu olarak; "2025'te" ⊆ "1 Mart 2025" |
| Kod / atıf | `PRC-12`, `P1`, `BT-SEC-04` (ADR-0047/0054 kod tanımı), "madde 7", "7. madde", "Ek-2", "7/2" | Küçük harfli yazım da eşleşir ("prc-12") |
| Tırnaklı terim | Çift tırnak içi (`"…"`, `“…”`, `«…»`) | Kart sorusunda aynı sözcükler tırnaksız da olabilir |
| Olumsuzluk | "değil", "olmayan", "hariç" (ekleriyle) | "-me/-ma" fiil eki okunmaz; "harici" olumsuzluk değildir |

Sözcük kuralları:
- Belirsiz sayı sözcükleri tek başına sayılmaz: "bir", "on", "yüz", "bin", "altı", "yedi". Ardından birim, para birimi ya da ölçek gelirse sayılır ("on gün", "bin TL").
- Tek başına "bir" birimden önce de sayılmaz ("bir günlük", "biri yılda").
- Ölçülmüş sette "bir" sayılınca 3 gerçek yeniden ifade yanlış reddediliyordu.

**Uyum kuralı:** yeni sorunun her olgusu kartın sorusunda olmalı. Sorunun olguları kartınkilerin alt kümesi ya da eşiti olmalıdır.
- Çıplak bir sayı, kartta aynı değerin herhangi bir biçimiyle karşılanır ("3" ↔ "3 gün").
- Nitelikli bir olgu ancak kendisiyle ya da aynı çıplak değerle karşılanır.
- Olumsuzluk iki yönde de eşleşmelidir. Biri olumsuz, öteki değilse çakışmadır.
- Kart daha özelse (soru "alım onayı kimde?", kart "500.000 TL üstü alım onayı kimde?"), istenen kural gereği eşleşir. Kosinüs eşiği bu durumu çoğunlukla zaten ayırır.

**Neden kodu ve kayıt:** reddedilen her aday kart için bir kayıt düşer:
- Metrik: `verso.qa.knowledge.rejections{reason}`. Nedenler: `key_fact_number`, `key_fact_amount`, `key_fact_percent`, `key_fact_quantity`, `key_fact_date`, `key_fact_code`, `key_fact_quote`, `key_fact_negation`, `rerank_low`, `rerank_unavailable`.
- Log: `Answer memory card rejected: cardId=… reason=…`, INFO.
- `verso.qa.knowledge.lookups{outcome}` metriğine yeni bir sonuç eklendi: `rejected`. En az bir aday süzgeçten döndü ve hiçbiri sunulmadı demektir.
- Soru metni ve olgu değerleri hiçbir yere yazılmaz. Neden kodları sabit değerlerdir.
- Bilgi sağlığı ve komuta panosu bu metriği sonra gösterebilir; bu ADR ekran eklemez.

**Reranker kontrolü** (`verso.qa.knowledge.rerank-check`, `config/verso.yml`):
- Ayarlar: `enabled` (`false`), `min-score` (0,80, 0 ile 1 arası). Ortam değişkeni yok; `match-threshold` gibi iş config'idir.
- Ölçülen çift: yeni soru ile kartın sorusu. `DocumentRetrieval.relevance` → `PassageReranker.score` → TEI `POST /rerank` (`raw_scores: false`, sigmoid).
- Korumalar ADR-0043'ün aynısıdır: 2 sn sert sınır, bulkhead, devre kesici. Devre, sıralama çağrılarıyla paylaşılır.
- Skor yoksa (kapalı, atlandı, hata) kart sunulmaz (`rerank_unavailable`). Kontrol kapalı yönde başarısız olur: soru normal yola gider.
- `rerank-check.enabled=true` iken `verso.document.reranker.enabled=false` ise uygulama açılmaz. Hiç onaylayamayan bir kontrol belleği sessizce kapatırdı.
- `document-api` sözleşmesine iki varsayılan yöntem eklendi: `rerankerEnabled()`, `relevance(question, text)`. Geriye uyumludur.
- `RerankerClient` artık skoru da okur ve doğrular: sonlu, 0..1. Sıralama yolu yalnız indeksleri okur; davranışı değişmedi.

**Eşik: 0,95'te kaldı.** Görev koşulu "süzgeç yakın ıskalarda kesinliği 1,0 tutarsa indir"di. Kanıt (`docs/evidence/kart-eslesme-olcumu.md`):
- 70 çiftlik etiketli set, kosinüsler atanmış:
  - Süzgeç 30 anahtar olgu yakın ıskasının hepsini reddeder.
  - 0,90'da 3 niyet yakın ıskası (0,944, 0,92, 0,91) yine eşleşir; kesinlik 0,889.
  - 0,95'te süzgeçsiz 28 yakın ıska eşleşir, süzgeçle 0. Kesinlik 1,0, duyarlılık 0,118.
- ADR-0049'un ölçülmüş seti:
  - Süzgeç 64 gerçek yeniden ifadeden 1'ini reddeder.
  - 30 gerçek yakın ıskadan 5'ini yakalar; 0,90 üstündeki üçten ikisi bunlardandır.
  - Üçüncüsü `guvenlik-3`, 0,944: niyet farkıdır, süzgeç görmez.

Eşiği indirmenin önündeki engel niyet farkıdır; onu ancak reranker ya da ölçülmüş bir müşteri seti çözebilir.

## Sonuçlar

- **Olumlu:**
  - Kosinüsün kör olduğu yakın ıska sınıfı (tutar, sayı, kod, tarih, birim, olumsuzluk) eşikten bağımsız olarak kapanır.
  - Bellek yolunda süzgeç kaynak okumadan önce çalışır. Reddedilen kart için `document` şemasına sorgu gitmez.
  - Ret nedeni ölçülebilir: hangi tür farkın ne sıklıkla eşiği geçtiği metrikten okunur. Müşteri setiyle eşiği yeniden değerlendirmenin girdisidir.
  - Uzak çağrı sayısı varsayılan kurulumda değişmez.
- **Olumsuz / kabul edilen risk:**
  - Aynı değeri başka birimle ya da yöntemle yazan yeniden ifade reddedilir ("48 saat" / "iki gün"; "en çok iki ay mı?" / "en fazla kaç ay?"). Ölçülmüş sette 1/64, etiketli sette 1/34. Birim dönüşümü yapılmaz: yanlış bir dönüşüm yanlış kartı sunar.
  - Kart daha özelse eşleşmeye izin verilir; bu bilinçli bir kuraldır.
  - Etiketli setin kosinüsleri atanmıştır. Süzgecin kararları gerçek, eşik tablosu ise varsayımdır. Eşik kararı ölçülmüş setteki 0,944'e de dayanır.
  - Reranker kontrolü açıkken: kart başına +1 uzak çağrı (ADR-0043 ölçümü p95 111 ms). Kart reddedilirse arama yolunda bir rerank daha olur: embedding + 2 rerank + chat. `min-score` ölçülmedi.
  - Sözcük listeleri (birim, ay, atıf, olumsuzluk) Türkçe ve sınırlıdır. İngilizce sorular yalnız rakam, kod ve tırnakla korunur.
- **Etkilenen dosyalar ve yüzeyler:**
  - `qa-core`: `service/KeyFacts` (yeni), `service/AnswerMemory`, `config/QaKnowledgeProperties` (`rerank-check`), `service/impl/QuestionServiceImpl` (sorgu metni `lookup`'a geçer).
  - `document-api`: `DocumentRetrieval` (iki varsayılan yöntem).
  - `document-core`: `service/PassageReranker` (`score`), `service/RerankerClient` (`scores`), `service/impl/DocumentRetrievalServiceImpl`.
  - `config/verso.yml` (`verso.qa.knowledge.rerank-check.*`). Yeni ortam değişkeni, secret, port, migration ya da API alanı yok.
  - Testler: `KeyFactsTest`, `CardMatchEvalTest` ve test kaynağı `eval/kart-eslesme-set.json`, `AnswerMemoryTest`, `PassageRerankerTest`, `RerankerClientTest`, `KnowledgeCardRulesTest`.
  - Belgeler: `docs/ai/llm-rules.md` 3.9, `docs/ai/repo-context.md` (soru sorma satırı).
- **Geri alma yolu:**
  - Süzgeç ayar değildir. Geri almak kod geri alımıdır (`AnswerMemory`'de tek çağrı).
  - Reranker kontrolü: `rerank-check.enabled: false` (varsayılan).
  - Bellek tümüyle: `VERSO_QA_KNOWLEDGE_ENABLED=false`.
  - Migration yok.

## Yeniden değerlendirme koşulu

- `verso.qa.knowledge.rejections` bir kurulumda yeniden ifadelerin belirgin kısmını reddediyorsa (bir müşteri setinde %5'ten fazla): birim dönüşümü ya da sözcük listesi.
- Etiketli set gerçek bge-m3 kosinüsleriyle ölçülünce (`docs/evidence/kart-eslesme-olcumu.md` "Yeniden ölçüm"): eşik tablosu yeniden yazılır.
- Reranker kontrolü bir GPU kurulumunda Türkçe kart çiftleriyle ölçülünce: `min-score` ve kontrolün açık önerilip önerilmeyeceği. Niyet yakın ıskalarını reranker ayırıyorsa eşik 0,90'a inebilir.
- Altın sette ya da itirazlarda süzgeçten geçmiş yanlış eşleşme görülürse: yeni olgu türü.
