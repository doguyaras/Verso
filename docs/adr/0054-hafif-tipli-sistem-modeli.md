# ADR-0054: Hafif, tipli sistem modeli (öğrenen katman faz 4)

- **Durum:** Kabul edildi. Model, "Sistem haritası", arama API'si, `verso_lookup` ve bilgi sağlığı sinyalleri çalışır; arama zenginleştirme varsayılan **kapalı** (`VERSO_DOCUMENT_SYSTEM_MODEL_ENRICHMENT_ENABLED`, ölçülene kadar); yerel model öneri geçişi varsayılan **kapalı** (`VERSO_QA_SYSTEM_MODEL_SUGGESTIONS_ENABLED`).
- **Tarih:** 2026-10-26
- **Karar verenler:** doguyaras.
- **Uyguladığı:** ADR-0048 faz 4 (ilkeler, arıza biçimleri ve onay noktaları oradadır, burada tekrar edilmez). Faz 1: ADR-0049, faz 2: ADR-0052, faz 3: ADR-0053.
- **Dayanak:** `research_notes/Verso öğrenen bilgi katmanı/research.md` §4 (LazyGraphRAG'in indeksleme maliyeti tam GraphRAG'in %0,1'i; Türkçe ilişki çıkarımında her LLM İngilizceden kötü, en iyi F1 0,888; öneri: belge başına alt graf, kapalı şema, Postgres tabloları, birincil arayıcı değil gezinme/sahiplik/eskime/çelişki yönlendirmesi), `research_notes/Verso kapsamlı küresel araştırma/asia_jp_cn_kr.md` (Zhihu uygulayıcıları GraphRAG'i "belge değişince graf yeniden kurulmalı" diye reddediyor; RAGFlow'un graf modu "çok yavaş", %80-90'da takılıyor; saf vektör özel ad/numara/madde numarasında başarısız).
- **İlgili eşikler (referans Bölüm 24):** sıcak yola uzak çağrı eklenmez (ADR-0008); yeni servis, broker ya da veritabanı yok (ADR-0001, ADR-0003); graf veritabanı yok.
- **Genişlettiği:** ADR-0028 (okuyucu ilkeleri), ADR-0031 (MCP sunucusu), ADR-0039 (outbox), ADR-0047 (AnchorCheck kod biçimi ve Türkçe katlama), ADR-0049 (SHA-256 bağlama), ADR-0052 (teşhis raporu).

## Bağlam

- Danışmanlık sahasında en sık başarısızlık özel adlarda ve kodlarda: "WMS-204 ne demek?", "FEFO neydi?", "iade sürecinin sahibi kim?". Saf vektör araması bunları kaçırır (asia_jp_cn_kr.md); hibrit arama (ADR-0022) yardım eder ama "kod → tanım → sistem → ekran" bağını kurmaz.
- Tam GraphRAG (topluluk özetleri) ADR-0048'de reddedildi: Türkçe çıkarım kalitesi kanıtsız, belge değişince yeniden kurulum pahalı. Kurumun yapısal modeli yine de bir farklılaştırıcıdır: sahipsiz süreç, tanımsız kod, iki belgede farklı açılan kısaltma, danışmanlık ekibinin müşteriye göstereceği somut boşluklardır.
- Kişi adları (sahip, onaylayan) KVKK ve işyeri temsilciliği (BetrVG §87, `docs/uyum/07`) açısından hassastır: bir "kim neyden sorumlu" haritası kolayca bir çalışan izleme aracına dönüşür.

## Seçenekler

| Konu | Seçenekler | Seçilen ve neden |
|---|---|---|
| Çıkarım | LLM ilişki çıkarımı · Türkçe NER modeli · **deterministik kurallar** (+ isteğe bağlı küçük model önerisi) | Kurallar (düzenli ifade + sezgisel): aynı parçalar her zaman aynı modeli verir, maliyet sayfa başına milisaniye, Türkçe katlama AnchorCheck ile aynı. Küçük model yalnız **öneri** üretir, insan onaylar, hiçbir yerde kendiliğinden kullanılmaz |
| Kapsam | global graf · **belge başına alt graf** | Varlık satırı belgeye aittir (`document_id`); birden çok belgedeki aynı anahtar sorgu anında birleşir. Belge değişince yalnız o belge yeniden çıkarılır; global yeniden kurulum yok |
| Depolama | graf veritabanı · **Postgres tabloları** (`document` şeması V6) | Yeni altyapı yok; erişim süzgeci (ADR-0028) aynı `EXISTS document_principal` yüklemi |
| Sahip | `document` · `qa` | **`document`**: çıkarım alım (ingestion) anında, parçalarla aynı modülde; varlıklar parçalara yabancı anahtarla bağlı (ON DELETE CASCADE). `qa` yalnız `document-api` sözleşmesinden okur |
| Kişiler | PERSON varlığı · **yalnız unvan/birim** | Aşağıda "Gizlilik kuralı" |
| Kullanım | birincil arayıcı · **gezinme, arama, teşhis; zenginleştirme bayrakla** | Araştırmanın önerisi: graf gezinme/sahiplik/çelişki için; arayıcıya yalnız sınırlı ek aday, ölçülene kadar kapalı |

## Karar

### Veri modeli (`document` şeması, `V6__system_model.sql`)

- `system_entity`: belge, tip (`SYSTEM`, `SCREEN`, `CODE`, `ROLE`, `PROCESS`, `STEP`, `TERM`; DB CHECK), katlanmış anahtar (Türkçe katlama: `İK-3` → `IK-3`, `FEFO`, `depo sorumlusu`; adım: `<süreç anahtarı>#<n>`), etiket (belgede yazıldığı gibi), adım numarası. `(document_id, type, key)` tekil.
- `system_relation`: `STEP_OF`, `NEXT_STEP`, `CODE_OF`, `PART_OF` (ekran → sistem), `OWNED_BY`, `DEFINES` (terim ya da kod → tanım metni; hedef varlık yok, değer var). Her ilişki kanıt parçasına (`chunk_id`, `chunk_sha256`) bağlı.
- `system_mention` (= `MENTIONED_IN`): varlık × parça, parçanın SHA-256'sı, tanımlayıcı mı.
- `system_model_state`: belge başına çıkarıcı sürümü ve parça özetlerinin özeti (digest), sayılar, kesildi mi.
- Hepsi belgeye ve parçaya ON DELETE CASCADE ile bağlı; uygulama rolünden UPDATE geri alındı (satırlar değiştirilmez, belge başına bütünüyle değiştirilir).

### Çıkarıcılar (deterministik, `SystemModelExtractor`, sürüm 1)

- **Kod:** AnchorCheck kod biçimi (ADR-0047; artık `platform-core` `TurkishText.CODE_REGEX`, iki taraf aynı tanımı kullanır). Satır başında ayraç ve açıklamayla (`WMS-204: Stok yetersiz`, tablo satırı `İK-3 | …`) ya da "X kodu … anlamına gelir/gösterir" ile **tanımlı**. Ön eki bilinen ya da belgede geçen bir sistemse (`WMS-204` → WMS) ya da satır tek bir sistem adıyorsa `CODE_OF`.
- **Terim:** `FEFO (First Expired First Out)`, `Son Kullanma Tarihi (SKT)`, `FEFO: First Expired First Out`; yalnız açılımın baş harfleri kısaltmayı sırayla veriyorsa (bağlaçlar atlanır, en çok bir kaçak). `KDV (yüzde 20)` terim değildir. Sonraki düz kullanımlar (`FEFO'ya göre`) bahsedilme olarak bağlanır.
- **Sistem:** bilinen kısaltmalar (ERP, WMS, CRM, MES, TMS, SAP, …) ve iyelikli bileşik ad ("Depo Yönetim Sistemi", "WMS modülünde"); cümle başındaki tek büyük harfli sözcük sistem sayılmaz ("Kullanıcı sistemine …").
- **Ekran:** menü yolu (`WMS > Stok > Sayım Girişi`, `→`, `»`; bölümler büyük harfle başlar) ve "X ekranı / menüsü". Yol bilinen bir sistemle başlıyorsa `PART_OF`.
- **Süreç / adım:** kısa bir başlık satırının altında 1, 2, … diye giden en az iki numaralı satır (parça sınırını aşabilir); `STEP_OF`, `NEXT_STEP`.
- **Rol / sahip:** "X sorumludur", "X tarafından", "Sorumlu: X", "Süreç sahibi: X"; ifade `RoleNames` ile unvana indirgenir. Adım satırındaysa adım, değilse bölümün süreci `OWNED_BY` alır.
- Belge başına en çok 5 000 varlık (aşılırsa `truncated`). Ölçüm: yoğun ~2 000 karakterlik sayfada ~3 ms (`SystemModelExtractorBenchmarkTest`, `docs/evidence/sistem-modeli-dogrulama.md`); aynı sayfanın gömülmesi CPU'da ~1 sn.

### Gizlilik kuralı (KVKK, işyeri temsilciliği)

- **Kişi adı hiçbir zaman varlık değildir.** PERSON tipi yoktur (enum ve DB CHECK). Sahip ifadesi yalnız kapalı bir unvan/birim sözcük listesindeki (müdür, sorumlu, şef, uzman, departman, birim, ekip, …) **son** unvan sözcüğüne kadar alınır; ondan sonrası (adın geldiği yer) kesilir, öncesinden en çok iki büyük harfli sözcük kalır; bilinen bir ad, unvan eki (Bey, Hanım, Dr.) ya da işlev sözcüğü öncesini keser, addan sonraki sözcük soyadı sayılır. Unvan sözcüğü yoksa ifade **atılır**. Örnekler (birim testi `RoleNamesTest`): "Depo Sorumlusu Ahmet Yılmaz" → "Depo Sorumlusu"; "Ayşe Kaya Depo Müdürü" → "Depo Müdürü"; "Mehmet Bey sorumludur" → yok.
- Bilinen sınır: listede olmayan bir ad doğrudan unvanın önündeyse iki sözcüklük pencereye girebilir; liste gerçek belgelerden genişletilir. Bilgi sağlığı önerisi metni de "kişi adı değil unvan yazın" der.
- Etiketler ve tanımlar belge metnidir: hiç loglanmaz, yalnız okuyucu süzgeçli sorgularla gösterilir, MCP denetiminde yalnız kimlikler vardır.

### Bağlama ve eskime (artımlı, global yeniden kurulum yok)

- Alım: parçalar yazıldıktan hemen sonra, kendi kısa işleminde `SystemModelIndexer` (başarısızlık alımı geri almaz, loglanır, geri doldurma işi alır).
- Yeni parçalar (yeniden alım) eski modeli aynı işlemde siler (`IngestionRepository.complete`/`fail`); silinen belge her şeyi CASCADE ile götürür.
- `SystemModelPlan`: kayıtlı sürüm ve digest (parça SHA-256'larının sıralı özeti) tutuyorsa yeniden çıkarım yoktur; tek bir parçanın özeti değişirse ya da çıkarıcı sürümü artarsa yalnız o belge yeniden çıkarılır.
- `SystemModelBackfillJob` (alım süreci rolüyle, `@ConditionalOnIngestion`): güncel modeli olmayan READY belgeleri belge başına kısa işlemle, satırı `FOR NO KEY UPDATE SKIP LOCKED` ile kilitleyerek alır (çok örnekli güvenli); çıkarıcının işleyemediği belge boş model ve ERROR satırıyla kuyruğu tıkamaz.

### Kullanım

1. **Arama zenginleştirme** (bayrak, varsayılan kapalı): soru bilinen bir CODE/TERM/SYSTEM adıyorsa onu tanımlayan parçalar (en çok 2, `enrichment-max-chunks`) füzyon sonuçlarının **arkasına** eklenir, yerlerini almaz; aramanın kendi salt okunur işleminde iki indeksli okuma, model çağrısı yok, aynı okuyucu süzgeci. Reranker açıksa diğer adaylarla yarışır. Sayaç `verso.document.retrieval.enrichment`. Değerlendirme seti üzerinde ölçülmeden açılmaz.
2. **Panel "Sistem haritası"** (`verso-admin`): sayılar, tipe göre liste ve önek araması, varlık ayrıntısı (tanımlar ve kaynakları, sistem → ekranlar/kodlar, süreç → adımlar/sorumlu), "nerede geçiyor"; hepsi yöneticinin **kendi** ilkeleriyle (yönetici de yalnız okuyabildiğini görür).
3. **Bilgi sağlığı sinyalleri** (ADR-0052 raporu, `systemModel` alanı + şablonlu bulgular): tanımsız kodlar (`UNDEFINED_CODES`, INFO), iki belgede farklı açılan kısaltmalar (`TERM_CONFLICTS`, WARNING; Türkçe katlamayla aynı sayılanlar çelişki değildir), sahipsiz süreçler (`PROCESSES_WITHOUT_OWNER`, INFO). Yalnız yöneticinin okuyabildiği **paylaşılan** belgeler; kişisel belge hiç girmez. Canlı hesaplanır (raporla birlikte, gece işi gerekmez).
4. **Arama API'si ve MCP:** `GET /v1/system-model/lookup?q=` (her oturum açmış kullanıcı, kendi yetkisiyle) ve `verso_lookup` (çağıranın kimliğiyle, kaynaklar `verso_fetch` kimlikleri; denetimde `MCP_LOOKUP`, sorgu metni yok).

### İsteğe bağlı öneri geçişi (`qa` V7, varsayılan kapalı)

- Yalnız local modda ve bayrak açıkken yönetici bir belge için çalıştırır. Yerel model yalnız o belgenin deterministik varlıkları arasında, kapalı şemada (`ilişki|kaynak no|hedef no|parça no`) ilişki önerir; her satır denetlenir (bilinen ilişki, ilişkiye uyan tipler, iki varlığın da etiketi alıntılanan parçada yazılı). Kalanlar `qa.system_model_suggestion`'a **DRAFT** olarak yazılır; yönetici kabul eder ya da reddeder (denetimde kimlikler). Kabul edilen öneri hiçbir zaman arama, arama API'si ya da haritanın deterministik ilişkilerine karışmaz.
- Belge silinir ya da değiştirilirse öneriler `DocumentChangeListener` ile silinir; kanıt parçasının özeti değişmişse listelemede silinir.

### API

- `GET /v1/system-model/lookup?q=` (1-200 karakter; aksi 400 `10051`), `GET /v1/admin/system-map/entities|entity|stats` (yalnız `verso-admin`; yok/okunamaz 404 `10050`), `/v1/admin/system-map/suggestions/**` (yalnız `verso-admin`; kapalıyken 409 `11080`, yok 404 `11081`, incelenmiş 409 `11082`, belge okunamaz 404 `11083`). Gövdeler `Cache-Control: private, no-store`. Ayrıntı `docs/api-knowledge-integration-v1.md`, MCP `docs/api-mcp-integration-v1.md`.

## Sonuçlar

- Olumlu: kod/terim soruları için kaynaklı, deterministik bir cevap yüzeyi; danışmanlık raporuna üç yapısal boşluk sinyali; graf altyapısı ve LLM maliyeti olmadan; belge değişince yalnız o belge.
- Olumsuz: kurallar kaçırır (serbest metinde sahiplik, tablo dışı tanımlar, başlıksız süreçler); kısaltma tanımı yanlış eşleşebilir (baş harf kuralı bunu azaltır). Kodlar gürültülüdür (her `P1` bir kod); tanımsız kod sinyali bu yüzden INFO'dur.
- Sıcak yol: bayrak kapalıyken değişmez; açıkken aramanın işlemi içinde iki indeksli okuma (`docs/ai/repo-context.md` kritik akış kaydı).

## Yeniden değerlendirme koşulu

- Zenginleştirme: müşterinin değerlendirme setinde kod/terim sorularının recall@5'i ve cevap doğruluğu bayrakla ve bayraksız ölçülünce (ADR-0049 altın seti).
- Öneri geçişi: yöneticilerin kabul oranı %70'in üstündeyse (öneriler güvenilir) ya da %20'nin altındaysa (geçiş kaldırılır) yeniden karar.
- Türkçe NER: BERTurk sınıfı bir kodlayıcı modelin rol/sistem çıkarımı kurallardan ölçülü olarak iyiyse (kişi adı yine varlık olmaz).
