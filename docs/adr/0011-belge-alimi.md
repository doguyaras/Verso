# ADR-0011: Belge alımı (yükleme, ayrıştırma, chunking, embedding)

- **Durum:** Kabul edildi (faz 4)
- **Tarih:** 2026-10-02
- **Karar verenler:** doguyaras
- **İlgili eşik (referans Bölüm 24):** Mesajlaşma/iş kuyruğu satırı (ADR-0003); arama satırı (ADR-0002).

## Bağlam

Faz 4, kullanıcının PDF yükleyip arama için hazır hale getirebilmesini ister. Yükleme isteği kısa kalmalı; ayrıştırma ve embedding uzun sürer ve bir model çağrısı içerir (ADR-0003: iş DB'den claim edilir, broker yok). Referans PDF, pgvector ve embedding konusunda sessizdir; kurallar `docs/ai/llm-rules.md` ve ADR-0002/0003/0006'dadır.

ADR-0002 iki konuyu faz 4'e bırakmıştı: orijinal PDF saklanacak mı, saklanırsa nerede.

Kullanıcı kararları (2026-10-02):

- PDF kütüphanesi Apache PDFBox 3.0.8. Stack dışı bir bağımlılık olduğu için onay istendi.
- PDF işlenene kadar PostgreSQL'de bekler, başarıyla ayrıştırılınca silinir.
- Ollama ve bge-m3 compose'a faz 4'te eklenir.

## Seçenekler

| Konu | Seçenekler | Karar |
|---|---|---|
| PDF kütüphanesi | PDFBox doğrudan · Spring AI PDF reader (PDFBox 3.0.7 üstünde) | **PDFBox 3.0.8 doğrudan**: bellek sınırı, sayfa sınırı ve hata ayrımı bizde |
| Orijinal PDF | İşlenince sil · DB'de sakla · dosya volume'ü | **İşlenince sil.** Sayfa metinleri kalır; yeniden chunk ve embedding onlardan yapılır |
| Kalıcılık | Spring Data JPA (referans varsayılanı) · `JdbcClient` | **`JdbcClient`** (ADR-0007 #53) |
| Vektör tablosu | Spring AI `PgVectorStore` şeması (ADR-0002) · kendi tablomuz | **Kendi tablomuz** (ADR-0007 #54) |
| İş tetikleme | Zamanlanmış poll · Modulith olayı | **Poll** (ADR-0003) |

## Karar

**API (`/v1/documents`, ADR-0004):**

| Uç | Sonuç |
|---|---|
| `POST` (multipart, parça `file`) | 201 + `Location`, belge `PENDING`. İsteğe bağlı `X-Idempotency-Key`: aynı hesap ve anahtarla tekrar → ilk belge döner (ADR-0007 #55) |
| `GET` (`page`, `size` ≤ 100) | Hesabın belgeleri, en yeni önce, `PageResponse` |
| `GET /{id}` | Belge; başka hesabınki 404 `DOCUMENT_NOT_FOUND` (varlığı açığa çıkmaz) |
| `DELETE /{id}` | 204; sayfalar, chunk'lar ve vektörler aynı ifadeyle silinir (`ON DELETE CASCADE`, llm-rules 4.2) |

- Yükleme yalnız DB yazımıdır: ayrıştırma ve model çağrısı yoktur (repo-context §3, sıcak yolda 0 uzak çağrı).
- Kontroller: boş dosya 400 `DOCUMENT_EMPTY`; ilk 1024 baytta `%PDF-` yok 415 `DOCUMENT_NOT_PDF`; 20 MB üstü 413 (multipart sınırı, controller'dan önce); hesap başına 200 belge 409 `DOCUMENT_LIMIT_REACHED`. İçeriğin gerçekten PDF olup olmadığına worker karar verir.
- Dosya adı yalnız etikettir: yol parçaları, kontrol ve görünmez karakterler atılır, 255 karakterle sınırlanır. Depolama belge id'siyle yapılır. Ad hiçbir log'a yazılmaz (llm-rules 2.1).
- Yanıtlar `Cache-Control: private, no-store` taşır (ADR-0004).
- `API-Version` header'ı bu fazla devreye girdi: isteğe bağlı, varsayılan 1.0; bilinmeyen sürüm 400 `API_VERSION_INVALID`.

**Şema (`V1__document_tables.sql`):**

| Tablo | İçerik |
|---|---|
| `document` | Metadata ve iş: `status`, `failure_reason`, `attempts`, `next_attempt_at`, `locked_until`, `claim_token`, `embedding_model` |
| `document_file` | PDF baytları; yalnız ayrıştırılana kadar |
| `document_page` | Sayfa metni; atıfların ve chunk'ların kaynağı |
| `document_chunk` | Sayfa içi parça, `account_id` (sahiplik filtresi vektör sorgusunun içinde, llm-rules 4.1), `embedding vector(1024)`, `embedding_model` |

- Id'ler PostgreSQL 18 `uuidv7()` ile üretilir (referans 10.3: tek yöntem, elle atama yok).
- CHECK kısıtları: `FAILED` ⇔ neden var; `PROCESSING` ⇔ kira var.
- Kısmi claim index'i yalnız bekleyen ve işlenen satırları tutar. HNSW index cosine mesafesi kullanır.

**Worker (ADR-0003, referans 11.1):**

1. Her instance'ta 5 sn'de bir poll; poll başına en fazla 10 belge, sırayla.
2. Claim: `FOR UPDATE SKIP LOCKED`, `PENDING` ve zamanı gelmiş ya da kirası dolmuş `PROCESSING`. Yeni `claim_token`, kira 10 dk, `attempts + 1`.
3. Sayfa metni yoksa PDF ayrıştırılır. Sayfalar aynı transaction'da yazılır ve PDF silinir.
4. Chunk'lar 16'lık gruplar halinde embedding'e gider. Her gruptan sonra kira yenilenir; yenilenemezse claim kaybedilmiştir ve iş bırakılır.
5. Sonuç `claim_token` ile kilitlenen satıra yazılır (`READY`, sayılar, model adı).

- Her DB adımı kısa bir transaction'dır. Model çağrısı transaction dışındadır (llm-rules 5.1, `TransactionBoundaryRulesTest`).
- **Kalıcı hatalar** (`NOT_A_PDF`, `ENCRYPTED`, `TOO_MANY_PAGES`, `TOO_MUCH_TEXT`, `NO_TEXT`): yeniden denenmez. Belge `FAILED` olur; PDF, sayfalar ve chunk'lar silinir, yalnız metadata kalır.
- **Geçici hatalar** (model, DB, bağlantı): `PENDING`'e döner, bekleme `30 sn · 2^(n-1)`, en çok 10 dk. 5. denemeden sonra `PROCESSING_FAILED`.
- **Zehirli dosya:** worker her denemede ölürse kira dolar ve belge yeniden claim edilir. `attempts` sınırı aşınca işlenmeden `PROCESSING_FAILED` olur.
- Log yalnız id, sayı, süre, deneme sayısı ve sabit sonuç kelimesi taşır (`outcome=READY|FAILED|retry|lost`). Hata için yalnız `exceptionType` yazılır.

**Ayrıştırma (PDFBox 3.0.8):**

- Bellek içi akış önbelleği 256 MB ile sınırlıdır; geçici dosya yoktur, container salt okunurdur.
- Sayfa sayısı metinden önce kontrol edilir (500). Toplam metin 2 milyon karakterle sınırlıdır.
- Kullanıcı parolalı PDF `ENCRYPTED` olur. Boş kullanıcı parolasıyla açılan (yalnız izin kısıtlı) PDF okunur.
- Metin temizliği: NUL ve kontrol karakterleri atılır (PostgreSQL `text` NUL tutamaz), boşluk dizileri sadeleşir.
- Ayrıştırıcının mesajı ve sebep zinciri hiçbir yere taşınmaz; hata yalnız sabit bir nedendir.
- OCR kapsam dışıdır: taranmış PDF `NO_TEXT` olur.
- PDFBox ve FontBox log'ları ERROR seviyesindedir; uyarıları belge içeriğini alıntılayabilir.

**Chunking ve embedding (llm-rules 6):**

- Chunk sayfa sınırını aşmaz. Boyut 1000 karakter, örtüşme 150; kesim mümkünse boşlukta yapılır. `chunk_index` belge boyunca sıralıdır.
- Model: Spring AI `EmbeddingModel` → Ollama `bge-m3:567m` (1024 boyut, MIT). Dönen vektörün sayısı ve boyutu kontrol edilir; uymazsa hiçbir şey yazılmaz.
- HTTP: bağlantı 2 sn, okuma 60 sn (`spring.http.clients.*`). Spring AI retry 2 deneme; asıl yeniden deneme worker'ın kendi backoff'udur.
- Model compose'ta tek seferlik `ollama-pull` container'ıyla indirilir ve model katmanının digest'i doğrulanır. Model volume'de varsa indirme yapılmaz; ikinci açılış internetsiz çalışır. Çalışan `ollama` servisi model çekmez (`pull-model-strategy: never`, llm-rules 8.2) ve modelleri salt okunur bağlar. Tam ağ izolasyonu faz 6'dadır (llm-rules 1.1).

**Migrate modu:** belge modülünün bean'leri yalnız web uygulamasında oluşur (`@ConditionalOnWebApplication`). Migrate çalıştırması embedding modelini de kapatır (`--spring.ai.model.embedding=none`). Böylece tek seferlik migrate container'ında worker çalışmaz ve model istemcisi kurulmaz (`MigrateModeTest`).

## Sonuçlar

- **Olumlu:**
  - Yükleme kısa ve modelden bağımsızdır; model kapalıyken de belge kabul edilir ve sonra işlenir.
  - Orijinal dosya, ayrıştırıldıktan sonra sistemde yoktur (veri minimizasyonu).
  - Bir belgenin tüm türevleri tek `DELETE` ile silinir.
  - Çok instance'ta güvenlidir: aynı belgeyi iki worker işlemez; kirası dolan iş devralınır, eski worker hiçbir şey yazamaz (testli).
- **Olumsuz / kabul edilen risk:**
  - Yüklenen PDF bellekte tutulur (eşzamanlı 10 yükleme ≈ 200 MB). Hız sınırı platformda henüz yok.
  - Hesap kotası sayımı yarışa açıktır: eşzamanlı yüklemeler sınırı birkaç belge aşabilir.
  - PDFBox ayrıştırmasının süre sınırı yoktur; boyut, sayfa ve bellek sınırları ile kira ve `attempts` sınırı kötü dosyayı sonunda durdurur.
  - Ollama imajı büyüktür (~3,8 GB) ve model ilk açılışta 1,16 GB indirir.
  - Embedding modeli değişirse migration ve yeniden indeksleme gerekir (llm-rules 6.2). Sayfa metinleri saklandığı için PDF'e ihtiyaç yoktur.
  - Belge adı kişisel veri olabilir. Yalnız sahibine döner ve log'a yazılmaz; DB yedeğine girer (ADR-0009).
- **Etkilenen dosyalar:** `services/document/document-{api,core}`, `V1__document_tables.sql`, `config/verso.yml` (multipart, API sürümü, Spring AI, `verso.document`), `compose.yaml` (ollama, ollama-pull), `deploy/ollama/pull.sh`, `scripts/ingest-smoke.sh`.
- **Geri alma yolu:** Uçlar ve worker modülle birlikte kaldırılabilir. Şema `document` şemasının içindedir; vektörler sayfa metninden yeniden üretilebilir.

## Yeniden değerlendirme koşulu

- Ingestion kuyruğu bekleme süresi kiradan uzun kalırsa (faz 7 alarmı): eşzamanlılık ya da GPU.
- Taranmış PDF ihtiyacı (OCR).
- Retrieval p99 > 300 ms (ADR-0002): HNSW parametreleri ya da bölümleme.
- Yükleme belleği sorun olursa: akışlı yükleme ya da yükleme başına eşzamanlılık sınırı.
