# ADR-0011: Belge alımı (yükleme, ayrıştırma, chunking, embedding)

- **Durum:** Kabul edildi (faz 4). Format kapsamı ADR-0016 ile genişledi (DOCX, TXT, MD; 10010'un adı `DOCUMENT_TYPE_UNSUPPORTED` oldu).
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

İlk sürüm altı review'dan geçti. Kanıtlanmış dört HIGH bulgu kararı şekillendirdi:

- Birkaç KB'lık bir PDF API sürecini OOM ile düşürüyordu.
- Eşzamanlı yüklemeler JVM'i kapatıyordu.
- Model kesintisi kuyruktaki belgeleri kalıcı olarak `FAILED` yapıyordu.
- Restore provası öz-testi yeni tablolarla kırılıyordu.

Aşağıdaki karar düzeltilmiş halidir; kanıtlar `docs/evidence/faz-4-dogrulama.md`'dedir.

## Seçenekler

| Konu | Seçenekler | Karar |
|---|---|---|
| PDF kütüphanesi | PDFBox doğrudan · Spring AI PDF reader (PDFBox 3.0.7 üstünde) | **PDFBox 3.0.8 doğrudan**: bellek, sayfa, glif ve operatör sınırları bizde |
| Orijinal PDF | İşlenince sil · DB'de sakla · dosya volume'ü | **İşlenince sil.** Sayfa metinleri kalır; yeniden chunk ve embedding onlardan yapılır |
| Kalıcılık | Spring Data JPA (referans varsayılanı) · `JdbcClient` | **`JdbcClient`** (ADR-0007 #53) |
| Vektör tablosu | Spring AI `PgVectorStore` şeması (ADR-0002) · kendi tablomuz | **Kendi tablomuz** (ADR-0007 #54) |
| İş tetikleme | Zamanlanmış poll · Modulith olayı | **Poll** (ADR-0003) |
| Ayrıştırmanın yeri | API sürecinde, sınırlarla · ayrı süreç/container | **API sürecinde, sınırlarla.** Ayrı süreç yeniden değerlendirme koşuludur |

## Karar

**API (`/v1/documents`, ADR-0004):**

| Uç | Sonuç |
|---|---|
| `POST` (multipart, parça `file`) | 201 + `Location`, belge `PENDING`. İsteğe bağlı `X-Idempotency-Key` (ADR-0007 #55) |
| `GET` (`page`, `size` ≤ 100) | Hesabın belgeleri, en yeni önce, `PageResponse` |
| `GET /{id}` | Belge; başka hesabınki 404 `DOCUMENT_NOT_FOUND` (varlığı açığa çıkmaz) |
| `DELETE /{id}` | 204; sayfalar, chunk'lar ve vektörler aynı ifadeyle silinir (`ON DELETE CASCADE`, llm-rules 4.2) |

Yükleme kontrolleri, sırayla:

| Kontrol | Yanıt |
|---|---|
| Aynı anda işlenen yükleme sayısı (instance başına 4) | 503 `DOCUMENT_UPLOADS_BUSY` (10014) + `Retry-After: 5`. Multipart gövdesi bu kontrolden sonra okunur (`resolve-lazily`) |
| Dosya > 20 MB | 413 `PAYLOAD_TOO_LARGE` (90014). Multipart sınırı, controller'dan önce |
| Boş dosya | 400 `DOCUMENT_EMPTY` (10011) |
| İlk 1024 baytta `%PDF-` yok | 415 `DOCUMENT_NOT_PDF` (10010). Gerçekten PDF olup olmadığına worker karar verir |
| Hesapta 200 belge | 409 `DOCUMENT_LIMIT_REACHED` (10013) |
| Hesabın 20 belgesi kuyrukta (`PENDING`/`PROCESSING`) | 429 `DOCUMENT_QUEUE_FULL` (10015): tek hesap ortak kuyruğu dolduramaz |

- Son iki sayım hesap başına bir advisory lock altında yapılır; paralel yüklemeler sınırı aşamaz.
- Yükleme yalnız DB yazımıdır: ayrıştırma ve model çağrısı yoktur (repo-context §3, sıcak yolda 0 uzak çağrı).
- Dosya adı yalnız etikettir: yol parçaları, kontrol ve görünmez karakterler atılır, 255 karakterle sınırlanır. Depolama belge id'siyle yapılır. Ad hiçbir log'a yazılmaz (llm-rules 2.1).
- Yanıtlar `Cache-Control: private, no-store` taşır (ADR-0004). `API-Version` header'ı bu fazla devreye girdi: isteğe bağlı, varsayılan 1.0; bilinmeyen sürüm 400 `API_VERSION_INVALID`.
- **Veritabanı geçici olarak kullanılamıyorsa:** bağlantı yok, havuz dolu, kilit ya da sorgu zaman aşımı. Yanıt 503 `SERVICE_UNAVAILABLE` (99997, system bloğu genişledi) + `Retry-After: 5`, 500 değil.
- **Silme (KVKK):** kilit için 15 sn'ye kadar bekler. Worker'ın büyük bir belgeyi yazan transaction'ı birkaç saniye sürebilir; rolün 3 sn'lik varsayılanı silmeyi hataya çevirirdi.

**Şema (`V1__document_tables.sql`):**

| Tablo | İçerik |
|---|---|
| `document` | Metadata ve iş: `status`, `failure_reason`, `attempts`, `next_attempt_at`, `locked_until`, `claim_token`, `embedding_model` |
| `document_file` | PDF baytları; yalnız ayrıştırılana kadar. `STORAGE EXTERNAL`: zaten sıkıştırılmış veride pglz denenmez |
| `document_page` | Sayfa metni; atıfların ve chunk'ların kaynağı |
| `document_chunk` | Sayfa içi parça, `account_id` (sahiplik filtresi vektör sorgusunun içinde, llm-rules 4.1), `embedding vector(1024)`, `embedding_model` |

- Id'ler PostgreSQL 18 `uuidv7()` ile üretilir (referans 10.3: tek yöntem, elle atama yok).
- CHECK kısıtları: `FAILED` ⇔ neden var; `PROCESSING` ⇔ kira var.
- Kısmi claim index'i yalnız bekleyen ve işlenen satırları tutar. HNSW index cosine mesafesi kullanır.
- Uygulama rolünün dosya, sayfa ve chunk tablolarında `UPDATE` yetkisi yoktur; bu satırlar yalnız yazılır ve silinir.

**Worker (ADR-0003, referans 11.1):**

1. Her instance'ta 5 sn'de bir poll; poll başına en fazla 10 belge, sırayla.
2. Claim: `FOR UPDATE SKIP LOCKED`, `PENDING` ve zamanı gelmiş ya da kirası dolmuş `PROCESSING`. Yeni `claim_token`, kira 10 dk, `attempts + 1`.
3. Sayfa metni yoksa PDF ayrıştırılır. Sayfalar aynı transaction'da yazılır ve PDF silinir.
4. Chunk'lar 16'lık gruplar halinde embedding'e gider. Her gruptan sonra kira yenilenir; yenilenemezse claim kaybedilmiştir ve iş bırakılır.
5. Sonuç `claim_token` ile kilitlenen satıra yazılır (`READY`, sayılar, model adı).

- Her DB adımı kısa bir transaction'dır; model çağrısı transaction dışındadır (llm-rules 5.1, `TransactionBoundaryRulesTest`).
- **Kalıcı nedenler** (`NOT_A_PDF`, `ENCRYPTED`, `TOO_MANY_PAGES`, `TOO_MUCH_TEXT`, `UNSUPPORTED_PDF`, `NO_TEXT`): yeniden denenmez. Belge `FAILED` olur; PDF, sayfalar ve chunk'lar silinir, yalnız metadata kalır.
- **Model kesintisi (devre kesici):** model hatası belgenin suçu değildir. Belge deneme harcamadan `PENDING`'e döner ve worker tüm claim'leri durdurur.
  - Erişilemeyen model: 30 sn duraklama, WARN.
  - Düzelmeyecek hata (bilinmeyen model, yanlış vektör sayısı ya da boyutu): 5 dk duraklama, ERROR.
  - Kesinti ne kadar sürerse sürsün hiçbir belge bu yüzden `FAILED` olmaz.
- **Diğer geçici hatalar** (DB, depolama): `PENDING`'e döner, bekleme `30 sn · 2^(n-1)`, en çok 10 dk. 5. denemeden sonra `PROCESSING_FAILED` ve içerik silinir.
- **Zehirli dosya:** worker her denemede ölürse kira dolar ve belge yeniden claim edilir. `attempts` sınırı aşınca işlenmeden `PROCESSING_FAILED` olur.
- **Kapanış:** worker bir `SmartLifecycle`'dır. Kapanışta yeni claim almaz; işlediği belgeyi bir sonraki gruptan önce deneme harcamadan geri bırakır. Bırakma yazılamazsa (havuz kapandı) kira belgeyi sonraki worker'a devreder.
- **Log:** yalnız id, sayı, süre, deneme sayısı ve sabit sonuç kelimesi taşır (`outcome=READY|FAILED|retry|released|lost`). Hata için yalnız `exceptionType` yazılır.

**Ayrıştırma (PDFBox 3.0.8, güvenilmeyen girdi, API sürecinin içinde):**

| Sınır | Değer | Ne zaman uygulanır |
|---|---|---|
| Parser'ın akış önbelleği | 256 MB, yalnız bellek (container salt okunur) | Yüklemede. Yalnız PDFBox'ın akış önbelleğini sınırlar, nesne grafiğini değil |
| Sayfa sayısı | 500 | Metinden önce |
| Açılmış içerik akışları (sayfa + form XObject) | 64 MB toplam | Ayrıştırmadan önce, bayt sınırlı bir inflater ile ölçülür. FlateDecode ve filtresiz dışındaki kodlama `UNSUPPORTED_PDF` |
| Sayfa başına glif | 50 000 | Toplanırken (`processTextPosition`); sayfanın tüm konumları belleğe dolmadan |
| Toplam glif / metin | 2 milyon | Toplanırken ve normalize edildikten sonra |
| İçerik operatörü | 5 milyon | İşlenirken |
| Grafik durum yığını | 256 | Her operatörden sonra (`q` bombası) |
| Form iç içeliği | 10 | Ölçümde |
| `StackOverflowError` | → `UNSUPPORTED_PDF` | Ayrıştırırken |

- Kullanıcı parolalı PDF `ENCRYPTED` olur. Boş kullanıcı parolasıyla açılan (yalnız izin kısıtlı) PDF okunur.
- Metin temizliği: NUL ve kontrol karakterleri atılır (PostgreSQL `text` NUL tutamaz), boşluk dizileri sadeleşir.
- Ayrıştırıcının mesajı ve sebep zinciri hiçbir yere taşınmaz; hata yalnız sabit bir nedendir. PDFBox ve FontBox log'ları ERROR seviyesindedir.
- OCR kapsam dışıdır: taranmış PDF `NO_TEXT` olur.

**Chunking ve embedding (llm-rules 6):**

- Chunk sayfa sınırını aşmaz. Boyut 1000 karakter, örtüşme 150; kesim mümkünse boşlukta yapılır. `chunk_index` belge boyunca sıralıdır.
- Model: Spring AI `EmbeddingModel` → Ollama `bge-m3:567m` (1024 boyut, MIT). Saklanan model adı, istemcinin kullandığı `spring.ai.ollama.embedding.model` değerinden okunur; ikisi ayrışamaz.
- HTTP: bağlantı 2 sn, okuma 90 sn (`spring.http.clients.*`; faz 5'te local chat için 60 sn'den yükseltildi, ADR-0008). Spring AI 2.0'ın Ollama embedding istemcisinde retry yoktur; yeniden deneme yalnız worker'ındır.
- Model compose'ta tek seferlik `ollama-pull` container'ıyla indirilir ve model katmanının digest'i doğrulanır (`scripts/ollama-pull.test.js`). Model volume'de varsa indirme yapılmaz, ikinci açılış internetsiz çalışır.
- **Çalışan `ollama` servisi:** yalnız `internal: true` olan `models` ağındadır (internete ve DNS'e çıkışı yok, canlı doğrulandı). `OLLAMA_NO_CLOUD=true`; model çekmez (`pull-model-strategy: never`, llm-rules 8.2); modelleri salt okunur bağlar.
- Uygulama Ollama'yı beklemeden başlar: model yokken de yükleme kabul edilir, worker duraklar ve sonra yetişir.
- Uygulamanın kendi dış trafik kısıtı (`InetAddressFilter`, açılış kontrolü, llm-rules 1.1) faz 6'dadır.

**Migrate modu:** belge modülünün bean'leri yalnız web uygulamasında oluşur (`@ConditionalOnWebApplication`). Migrate çalıştırması embedding modelini de kapatır (`--spring.ai.model.embedding=none`). Böylece tek seferlik migrate container'ında worker çalışmaz ve model istemcisi kurulmaz (`MigrateModeTest`).

**İstemci sözleşmesi:**

- `DocumentStatus` ve `DocumentFailureReason` ileride yeni değer alabilir. İstemci bilinmeyen değeri genel bir durum olarak göstermelidir (`docs/api-documents-integration-v1.md`).
- Idempotency anahtarı yalnız belge varken geçerlidir; silinen belgeyle birlikte anahtar da silinir (KVKK) ve aynı anahtarla gelen yükleme yeni bir belge oluşturur.
- `PageResponse` şimdilik `document-api`'dedir. İkinci bir sayfalı API (faz 5+) gelince ortak bir api modülüne taşınır; `platform-core`'a değil, çünkü api modülleri platform'a bağımlı olamaz (referans 3.3).

**Kişisel veri envanteri (KVKK):**

| Veri | Nerede | Ne kadar kalır |
|---|---|---|
| Dosya adı | `document.file_name` | Belge silinene kadar; yedeklerde `BACKUP_RETENTION_DAYS` (ADR-0009) |
| PDF | `document_file` | Ayrıştırılana kadar (saniyeler); `FAILED`'da hemen silinir |
| Sayfa metni, chunk metni, vektör | `document_page`, `document_chunk` | Belge silinene kadar; yedeklerde aynı süre |
| Hesap kimliği (`sub`) | `account_id` kolonları | Belgeyle birlikte |

Hesap düzeyinde silme yoktur: IdP'de silinen bir kullanıcının belgeleri kalır. Yönetim paneli (faz 10) hesap silme ucunu getirir; o zamana kadar belgeler tek tek silinir.

## Sonuçlar

- **Olumlu:**
  - Yükleme kısa ve modelden bağımsızdır.
  - Orijinal dosya, ayrıştırıldıktan sonra sistemde yoktur.
  - Bir belgenin tüm türevleri tek `DELETE` ile silinir.
  - Çok instance'ta güvenlidir: aynı belgeyi iki worker işlemez; kirası dolan iş devralınır, eski worker hiçbir şey yazamaz (testli).
  - Model kesintisi ve deploy belge kaybettirmez.
- **Olumsuz / kabul edilen risk:**
  - **Ayrıştırma API ile aynı JVM'dedir.** Bilinen bellek tüketme yolları (glif, `q`, deflate bombaları) sınırlıdır ve testlidir; bilinmeyen bir PDFBox yolu yine OOM'a yol açabilir. Ayrıştırmanın süre sınırı yoktur. Kira ve `attempts` sınırı kötü dosyayı sonunda durdurur.
  - **Yükleme belleği:** bir yükleme ~2 × 20 MB tutar (multipart parçası ve baytları); instance başına 4 yükleme ≈ 160 MB. Kimlik doğrulanmamış istekler gövde okunmadan 401 alır.
  - **Kuyruk sırası** hesaplar arası FIFO'dur. Kuyruk sınırı (hesap başına 20) bir hesabın payını sınırlar ama tam adalet sağlamaz.
  - **Uydurma `kid` ile hız sınırı tüketilirse:** gerçek bir IdP anahtar rotasyonunda ilk 30 sn'de token'lar 401 alabilir (ADR-0010, değişmedi).
  - **Ollama imajı büyüktür** (~3,8 GB) ve model ilk açılışta 1,16 GB indirir; CI her koşuda indirir.
  - **Embedding modeli değişirse** migration ve yeniden indeksleme gerekir (llm-rules 6.2).
  - **Faz 5 notu:** küçük hesaplarda HNSW sorgusu sahiplik filtresiyle `LIMIT`'ten az satır döndürebilir. Retrieval `hnsw.iterative_scan = relaxed_order` ile çalışmalı ve iki hesaplı recall testi taşımalıdır.
  - **Kuyruk metrikleri** (bekleyen sayısı, en eski bekleyenin yaşı) ve Ollama health göstergesi faz 7'dedir; o zamana kadar durum log'dan izlenir.
- **Etkilenen dosyalar:** `services/document/document-{api,core}`, `V1__document_tables.sql`, `platform-core` (`SERVICE_UNAVAILABLE`, DB hata eşlemesi), `config/verso.yml` (multipart, API sürümü, Spring AI, `verso.document`), `compose.yaml` (ollama, ollama-pull, `models` ağı), `deploy/ollama/pull.sh`, `scripts/{ingest-smoke.sh,restore-drill-selftest.sh,ollama-pull.test.js}`.
- **Geri alma yolu:** Uçlar ve worker modülle birlikte kaldırılabilir. Şema `document` şemasının içindedir; vektörler sayfa metninden yeniden üretilebilir.

## Yeniden değerlendirme koşulu

- Bir PDF ayrıştırması API sürecini düşürürse ya da süre sınırı gerekirse: ayrıştırmayı ayrı bir süreçte veya container'da, kendi bellek sınırıyla çalıştırmak.
- Ingestion kuyruğu bekleme süresi kiradan uzun kalırsa (faz 7 alarmı): eşzamanlılık, GPU ya da hesap başına sıralama.
- Taranmış PDF ihtiyacı (OCR).
- Retrieval p99 > 300 ms (ADR-0002): HNSW parametreleri ya da bölümleme.
- Yükleme belleği sorun olursa: akışlı yükleme (`getInputStream` → `setBinaryStream`).
