# ADR-0061: Bağlamsal parça başlığı (yalnız indeks için)

- **Durum:** Kabul edildi. Ölçüm bekliyor: canlı yığında `scripts/eval.mjs` ile iç set (140 soru) ve dış set (200 PDF). Eşikler değişmedi.
- **Tarih:** 2026-10-29
- **Karar verenler:** doguyaras. İstek: birbirine çok benzeyen belgeler arasında doğru belgeyi bulmak ve atıflamak.
- **Genişlettiği:** ADR-0011 (`document_chunk`, alım işçisi), ADR-0022 (hibrit arama; sözcüksel liste), ADR-0054 (sistem modeli; parça değişince yeniden çıkarılır).
- **İlgili eşik (referans Bölüm 24):** ADR-0022 cevaplanabilirlik kapısı (0,47 / 0,44 + soruya özgü terim). Bu ADR eşiği değiştirmez; kosinüs dağılımına etkisi aşağıda risk olarak yazılı.
- **Kanıt:** ölçüm yok (aşağıda "Ölçüm planı"). Önceki ilgili ölçüm: `docs/evidence/hibrit-arama-dogrulama.md` bölüm 2 (adım 1, belge adı ön eki).

## Bağlam

- Dış korpusta (200 PDF) yaklaşık 90 belge, aynı şablonla yazılmış üniversite "araştırma merkezi" yönetmelikleridir. Hepsinde "müdür üç yıl için görevlendirilir", "yönetim kurulu beş üyeden oluşur" gibi aynı cümleler geçer.
- Bugünkü ölçüm (atıf isabeti):
  - birkaç benzer belgeden doğrusunu seçmesi gereken sorular (`disambiguation`): 0,50;
  - merkez yönetmelikleri: 0,50;
  - merkezi kısaltmasıyla soran sorular (HÜBAUM, HUZEM …): 0,57;
  - genel: 0,646 (bulundu 0,885, doğru bilgi 0,738).
- Neden: bir parçanın embedding'i de sözcük vektörü (`search_vector`) de yalnız parçanın kendi metnidir. Parçada hangi belgeye ya da hangi maddeye ait olduğu yazmaz. 90 belgenin "müdür üç yıl" parçaları birbirinden ayırt edilemez. Soru ise "Hacettepe Üniversitesi Biyomühendislik Araştırma Merkezi (HÜBAUM) müdürü kaç yıl görev yapar" der; ayırt edici sözcükler yalnız belgenin başlığındadır, ilgili parçada değil.
- Atıf seçimi incelendi (`CitationExtractor`, `PromptBuilder`, `QuestionServiceImpl`):
  - `[n]` numaralandırması ile eşlemesi aynı listeyi kullanır. Geçerli işaret atılmaz, belgeye göre tekilleştirme ya da yeniden sıralama yoktur, işaretsiz cevaba yedek atıf eklenmez.
  - **Açık bir hata yok.**
  - Prompt'taki pasaj başlığı yalnız `[[BELGE n]] (sayfa p)` yazar. Model, benzer pasajları yalnız metinlerinden ayırabilir. Bu bir hata değil, bir tasarım boşluğudur. Bu ADR'de değiştirilmedi (aşağıda "Bilinçli olarak yapılmayanlar").
- Kısıtlar:
  - Uygulama rolü chunk satırını güncelleyemez (V1 `REVOKE UPDATE`). Migration'da `GRANT` yasaktır (`MigrationConventions`).
  - `qa` modülü chunk kimliklerini düz uuid olarak saklar: onaylı kart kaynakları, prosedür adımları, sistem modeli önerileri. Yeniden indeksleme bu kimlikleri değiştirmemelidir.
  - Dosya ayrıştırıldıktan sonra silinir (ADR-0011). PDF meta veri başlığı yalnız alım sırasında bilinir.
  - Generated `search_vector` sütununun ifadesini değiştirmek `document_chunk`'ı yeniden yazar ve HNSW index'ini yeniden kurar (ADR-0022: 100 bin chunk'ta 3,5 dk).

## Seçenekler

| Seçenek | Artı | Eksi | Sonuç |
|---|---|---|---|
| **Parça başına kısa bağlam başlığı (başlık + bölüm/madde), yalnız indekste: embedding girdisi ve ayrı bir sözcük vektörü** | Saklanan metin, alıntı, atıf, prompt ve API değişmez; kısaltma sözcük indeksine girer; yeniden kurulabilir | Embedding dağılımı kayar (ADR-0022 adım 1); yeniden indeksleme gerekir | **Seçildi** |
| Başlığı `search_vector`'ün generated ifadesine eklemek | Tek vektör, tek index | Tablo yeniden yazılır, HNSW yeniden kurulur; başlık yine bir sütunda saklanmalı | Seçilmedi |
| Başlığı parça metnine (`content`) yazmak | En basit | Alıntıya, atfa, prompt'a ve API'ye sızar; bulut modunda başlık/dosya adı dışarı gider (llm-rules 1.2) | Reddedildi |
| Yalnız sözcük indeksine başlık (embedding değişmez) | Kosinüs dağılımı ve kapı aynı kalır | Yoğun aramada ayırt edicilik kazancı yok | `embed-header: false` ayarıyla ölçülebilir seçenek olarak bırakıldı |
| Prompt'a belge adını eklemek (atıf seçimi için) | Model benzer pasajları adıyla ayırır | Bulut modunda dosya adı giden gövdeye girer (llm-rules 1.2); prompt değişikliği ayrı eval ister | Bu ADR'nin kapsamı dışında |
| Yeniden indekslemeyi belgeyi `PENDING`'e alıp yeniden alımla yapmak | Mevcut işçi | Belge bu sürede aranamaz; chunk kimlikleri değişir, `qa`'daki kart ve prosedür kaynakları kopar | Reddedildi |

## Karar

**Başlık** (`worker/ChunkContext`, `worker/DocumentTitle`):
- Biçim: `<belge başlığı>` ya da `<belge başlığı>\n<bölüm>`. En çok 200 karakter (`MAX_HEADER_CHARS`; veritabanı da denetler). Önce bölüm kısaltılır ya da düşürülür.
- **Belge başlığı** (en çok 150 karakter), şu sırayla:
  1. İlk sayfanın üstündeki başlık satırları:
     - Resmî Gazete, tarih, sayı, "T.C." ve "… Üniversitesinden:" satırları atlanır.
     - Tek sözcüklü büyük harf satırı atlanır ("YÖNETMELİK", "GİZLİ").
     - Ardışık büyük harf satırları birleştirilir (uzun ad birkaç satıra taşar; en çok 4 satır).
     - Büyük harfli başlık baş harfleri büyük yazıma çevrilir. Metinde Türkçe büyük harf (İ Ş Ğ Ç Ö Ü) varsa Türkçe kurallar (I → ı) uygulanır, yoksa kök kurallar (İngilizce ya da Türkçe harfsiz metin).
     - Parantez içindeki kısaltma olduğu gibi kalır: "(HÜBAUM)".
     - Karışık harfli tek bir başlık satırı da kabul edilir. Gövde metniyle ("MADDE 1", "BİRİNCİ BÖLÜM") tarama durur.
  2. PDF meta veri başlığı. "Microsoft Word - …", "Untitled", dosya uzantılı adlar atılır.
  3. Uzantısız ve ayraçları boşluğa çevrilmiş dosya adı.
  - Kısaltılan başlıkta kesilen parantezli kısaltma sona yeniden eklenir; sözcük indeksine mutlaka girer.
- **Bölüm:** parçanın ilk üçte birinde yürürlükte olan son başlık. Okunan başlık türleri:
  - "MADDE 7 –" ve üstündeki madde adı: "Madde 7 – Müdür";
  - "BİRİNCİ BÖLÜM";
  - Markdown başlığı;
  - numaralı başlık ("3.2 Parola Kuralları");
  - kısa büyük harf satırı.
  - Başlık sayfa sonundan sonraki sayfalara taşınır (madde sayfa sonunu aşar).
  - Belge başlığının parçası olan satırlar bölüm sayılmaz.
  - Cümle (".", ":" ile biten), "Madde 5'e göre" ve tablo satırı başlık değildir.
- Kurallar deterministiktir ve yalnız saklanandan çalışır (sayfa metni, parça metni, başlık). Bu sayede backfill eski belge için de alımın üreteceği başlığı üretir.

**İndeks:**
- Embedding girdisi: `başlık + "\n\n" + parça metni`. Saklanan `content` parçanın kendi metnidir.
- `verso.document.context-header.embed-header: false`: başlık yalnız sözcük indeksine girer, embedding parça metninin kendisidir. Ayarın değişmesi her belgeyi yeniden indeksletir.
- Sözcük: `document_chunk.header_vector = to_tsvector('turkish', context_header)`. Aynı `INSERT`'te, aynı parametreden yazılır; V7'deki `CHECK` ikisini bağlar.
  - Hibrit aramanın sözcüksel listesi `search_vector @@ q OR header_vector @@ q` ile eşleşir. Her taraf kendi GIN index'ini kullanır.
  - Sıralama `ts_rank_cd(header_vector || search_vector, q)` iledir.
- **Cevaplanabilirlik kapısının terim payı (`rarestSharedTermCounts`) yalnız parça metnine bakar; değişmedi.** Başlıktaki bir terim belgenin her parçasında bulunur. Onu saymak, belgeyi adıyla anan her soruda kapının alçak eşiğini (0,44) açardı. Başlık sıralamaya yardım eder, kapıyı açmaz.
- Başlık hiçbir okumada dönmez:
  - `RetrievalRepository`, `DocumentChunkRepository`, `ProvenanceRepository` ve `SystemModelRepository` sütunları adıyla seçer; `context_header` yalnız `ContextIndexRepository` yazar.
  - Alıntı, atıf, prompt, MCP, yönetici parça incelemesi ve log başlığı görmez (`AdminDocumentChunkApiTest`, `ContextHeaderTest`).
  - Başlık belge metnidir, loglanmaz (llm-rules 2.1). Embedding her modda yereldir (llm-rules 1.2); başlık dışarı gitmez.

**Şema (`V7__chunk_context_header.sql`, yalnız genişletme):**
- `document.index_title`: dosya gidince meta veri başlığı kaybolduğu için saklanır.
- `document.context_version`: `ChunkContext.indexVersion` değeridir. Kural sürümünü ve başlığın embedding'e girip girmediğini birlikte tutar; NULL, ADR-0061'den önce indekslenmiş demektir.
- `document.context_locked_until`: backfill kirası.
- `document_chunk.context_header` ve `document_chunk.header_vector`, GIN index ve `CHECK`'ler.
- Hepsi varsayılansız, NULL olabilen sütunlardır: katalog değişikliği, yeniden yazma yok. `search_vector` ve HNSW index'i değişmez.
- Kısıt ve index tabloyu bir kez okur (bütün satırlar NULL). V3 gibi uygulama açılmadan önceki migrate adımında koşar.
- `DROP` ya da `RENAME` yoktur.

**Yeniden indeksleme (`worker/ContextHeaderBackfillJob`, `repository/ContextIndexRepository`):**
- Mevcut bir yeniden indeksleme mekanizması yoktu. `DOCUMENT_REINDEX_REQUIRED` yalnız 409 hata kodudur (ADR-0026).
- Yeni iş, sistem modeli backfill'inin (ADR-0054) biçimindedir. Varsayılan olarak 30 sn'de bir, en çok 5 belge işler.
- Akış:
  1. Kısa transaction: `READY`, çalışan embedding modeliyle indekslenmiş ve `context_version` farklı olan bir belgeyi `FOR NO KEY UPDATE SKIP LOCKED` ile alır, `context_locked_until` kirasını yazar.
  2. Salt-okunur okuma: sayfalar, parçalar, başlık.
  3. Embedding transaction dışında, parti parti yapılır (referans 4.3). Her partiden sonra kira uzatılır.
  4. Son kısa transaction belgenin satır kilidi altında yeniden denetler: hâlâ `READY` mi, hâlâ bekliyor mu, parça kimlikleri aynı mı? Değişmişse dokunmaz. Değişmemişse parçaları **aynı kimlik, sayfa, sıra ve metinle** siler ve yeniden yazar (V1 `UPDATE`'e izin vermez), sürümü yazar.
  5. Sistem modeli, alım sonrasındaki gibi kendi transaction'ında yeniden çıkarılır. Parçaların silinmesi mention'larını götürür; hata olursa sistem modeli backfill'i alır.
- İdempotenttir: biten belge yeniden alınmaz, arada değişen belge atlanır. İki instance farklı belgeleri alır (`ContextHeaderTest`).
- Belge bu süre boyunca eski vektörleriyle aranabilir. Değişim tek transaction'dır: okuyucu ya eskiyi ya yeniyi görür.
- Açılışı beklemez. Yükleme kuyruğunda bekleyen belge varken ya da embedding devresi açıkken çalışmaz; yeni yüklemeler önce gelir. Model hatasında kirayı bırakır ve durur.
- **Başka embedding modeliyle indekslenmiş belgeye dokunmaz.** Model değişikliği bir migration işidir (llm-rules 6.2). Bir yapılandırma hatasında arka plan işi bütün vektörleri sessizce yeniden yazmamalıdır.
- Metrik: `verso.document.context_header.backfill{outcome=reindexed|skipped|model_failed}`. Log'da yalnız belge kimliği, sayı ve süre vardır.

**Ayarlar** (`config/verso.yml`, `verso.document.context-header.*`):
- `embed-header` (true), `backfill-enabled` (true), `backfill-interval` (30s), `backfill-batch` (5), `backfill-lease` (10m).
- İş config'idir; ortam değişkeni, secret ya da port yok.

**Eşikler:** 0,47 / 0,44 ve `specific-term-max-share` değişmedi. Değiştirmek için kanıt yok (aşağıda).

## Sonuçlar

- **Olumlu:**
  - Kısaltmayla ya da tam adla sorulan soruda, adı geçmeyen sayfadaki parça da o belgenin başlığını taşır. Sözcüksel liste doğru belgenin parçasını benzerlerinin önüne koyar (`ContextHeaderTest`: aynı metinli iki parçadan, kimlik eşitliği karşı tarafı kayırırken, başlığı soruyla eşleşen önde).
  - Saklanan metin, alıntılar, atıflar, prompt ve API değişmedi. İstemci etkisi yok.
  - Yükseltilmiş kurulum kendi kendine yeniden indekslenir. Kimlikler korunur; `qa`'daki kart, prosedür ve öneri kaynakları kopmaz.
- **Olumsuz / kabul edilen risk:**
  - **Kosinüs dağılımı kayar (en önemli risk).** ADR-0022 adım 1 iç sette "belge adı › başlık yolu + içerik" ön ekini ölçtü (`docs/evidence/hibrit-arama-dogrulama.md` bölüm 2):
    - cevaplanabilir en düşük benzerlik 0,450'den 0,419'a indi;
    - cevaplanamaz en yüksek benzerlik 0,702'den 0,639'a indi;
    - `disambiguation` recall@1 0,81'den 0,71'e düştü (aynı şirketin benzer başlıkları belgeleri birbirine yaklaştırdı);
    - hibritin üstünde recall@1 +4 soru verdi ama kapının çapraz doğrulaması kötüleşti.
    - Bu ADR'nin başlığı oradaki D varyantına benzer: belge başlığı ve bölüm.
    - Fark 1: başlık belge adından değil, ilk sayfanın başlığından gelir.
    - Fark 2: madde adı da bölüm sayılır.
    - Fark 3: kapının terim payı başlığı saymaz.
  - Beklenen yan etki: cevaplanabilir soruların bir kısmı 0,47'nin altına iner ve 0,44 + terim yoluna kalır; bazı cevaplanamazlar da 0,44–0,47 aralığına girebilir. Eşik bu ölçüm olmadan değiştirilmedi.
  - Dış sette etki ters yönde beklenir: 90 benzer belgede ayırt edici bilgi yalnız başlıktadır.
  - Ölçüm iki seti birlikte görmelidir. İç sette düşüş olursa ilk deneme `embed-header: false`'tur (yalnız sözcük indeksi).
  - Başlık sezgiseldir. Başlığı gövde metniyle başlayan bir belgede yanlış satır başlık olabilir; kapak sayfalı belgede kapak başlığı alınır. Tek sonuç, o belgenin bütün parçalarına yanlış ama zararsız bir önek eklenmesidir; alıntı etkilenmez.
  - Dosya adı yedeği "scan_0012" gibi anlamsız bir başlık verebilir: gürültü, sızıntı değil.
  - Backfill'in maliyeti alımla aynıdır: her parça yeniden embed edilir (CPU'da belge başına saniyeler–dakikalar). Yeni yüklemeler önce gelir, yani backfill yoğun bir kurulumda uzun sürebilir.
  - Yeniden indeksleme sistem modelini belge başına yeniden çıkarır (deterministik, milisaniyeler).
  - Bekleyen belgeleri seçen sorgu `document`'ı tarar (index yok). Bu, belge sayısıyla (binler) sınırlıdır ve sistem modeli backfill'iyle aynı ölçektedir.
- **Etkilenen dosyalar ve yüzeyler:**
  - `document-core`:
    - yeni: `worker/{DocumentTitle, ChunkContext, ContextHeaderBackfillJob}`, `repository/ContextIndexRepository`, `config/ContextHeaderProperties`;
    - değişen: `worker/{IngestionWorker, IngestionTransactionService, PdfTextExtractor, DocumentTextExtractor}`, `repository/{IngestionRepository, RetrievalRepository}`, `config/DocumentConfiguration`, `V7__chunk_context_header.sql`.
  - `config/verso.yml` (`verso.document.context-header.*`).
  - Testler:
    - birim: `DocumentTitleTest`, `ChunkContextTest`, `ContextHeaderBackfillJobTest`, `IngestionWorkerContextTest`, `PdfTextExtractorTest`;
    - Testcontainers (CI): `ContextHeaderTest`, `AdminDocumentChunkApiTest`.
    - Test embedding modeli başlığı atıp parça metnini embed eder; böylece birebir metin soran testler kosinüs 1'i korur. Başlığın gerçek vektöre etkisi eval'in işidir.
  - Belgeler: `docs/ai/llm-rules.md` 6.5, `docs/ai/repo-context.md`, `README.md`, `docs/decisions.md`.
  - API, DTO, hata kodu, ortam değişkeni, secret, port ya da yeni uzak çağrı yok. Sıcak yolda ek uzak çağrı yok; sözcüksel sorguda ikinci bir GIN taraması var.
- **Rollout:**
  - V7 yalnız genişletir. Eski imaj yeni sütunları okumaz.
  - Kayan dağıtımda eski imajın yazdığı parçanın başlığı yoktur; belgesinin sürümü NULL kalır ve backfill sonra alır.
  - Uyumluluk: eski imaj + V7 çalışır; yeni imaj + V7 öncesi şema yoktur (migrate adımı önce koşar).
- **Geri alma yolu:**
  - Embedding etkisi: `embed-header: false`. Belgeler başlıksız embedding ile yeniden indekslenir, sözcük başlığı kalır.
  - Backfill: `backfill-enabled: false`.
  - Kod geri alımı: eski imaj V7 şemasıyla çalışır. Başlıklı vektörler kalır; tam geri dönüş, belgelerin yeniden yüklenmesini ya da başlıksız bir indeks sürümünü ister. Sütunları düşürmek sonraki bir contract migration'ıdır.

## Ölçüm planı

Kullanıcı canlı yığında yapacak; sonuç bu ADR'ye ve `docs/evidence/`'a yazılır.
1. Taban: mevcut sayılar (dış set yukarıda; iç set ADR-0022 sonrası taban).
2. Bu dal, `embed-header: true`. Backfill bitene kadar beklenir: `verso_document_context_header_backfill_total{outcome="reindexed"}` artmayı bırakır ve `SELECT count(*) FROM document.document WHERE status = 'READY' AND context_version IS DISTINCT FROM 2` sıfırdır (`embed-header: false` için 1).
3. `scripts/eval.mjs` iç set ve dış set.
4. Bakılacaklar:
   - atıf isabeti (genel, `disambiguation`, kısaltma, merkez yönetmelikleri);
   - "bulunamadı" doğruluğu;
   - cevaplanabilir en düşük ve cevaplanamaz en yüksek kosinüs (kapının kayması).
5. İç sette gerileme varsa `embed-header: false` ile 2–4 tekrarlanır.
6. Eşik değişikliği ancak 3–5'in sayılarıyla, ADR-0022'deki çapraz doğrulama yöntemiyle önerilir.

## Bilinçli olarak yapılmayanlar

- Prompt'taki pasaj başlığına belge adı ya da başlığı eklenmedi. Atıf hatası değildir (yukarıda). Bulut modunda dosya adı giden gövdeye girer (llm-rules 1.2) ve prompt değişikliği ayrı bir eval ister. Başlık (dosya adı değil, ilk sayfadan) için ayrı bir karar olarak değerlendirilebilir.
- Embedding modeli değişikliği için genel yeniden indeksleme: backfill başka modelin belgesini almaz (llm-rules 6.2).

## Yeniden değerlendirme koşulu

- Ölçüm planının sonucu: iç sette `disambiguation` ya da "bulunamadı" belirgin düşerse varsayılan `embed-header: false` olur. Dış sette kazanç yoksa karar geri alınır.
- Kapı kayması ölçülürse eşikler ADR-0022 yöntemiyle yeniden seçilir.
- Başlık kuralları değişirse `ChunkContext.VERSION` artırılır; backfill her belgeyi yeniden indeksler.
