# ADR-0028: Paylaşımlı bilgi alanları ve izin duyarlı retrieval

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras (kurumsal yol haritası `enterprise_features.md` madde 3; `market_needs.md` madde 1, "Z": satın alma öncesi zorunlu); uygulama ayrıntıları bu ADR'de
- **İlgili eşik (referans Bölüm 24):** ADR-0008 sıcak yol bütçesi; ADR-0022'nin "soru sorma p95'inde veritabanı payı 200 ms" koşulu; kurulum başına chunk sayısı (aşağıda "Yeniden değerlendirme koşulu")
- **Genişlettiği:** ADR-0011 (belge modeli), ADR-0012 / ADR-0022 (retrieval), ADR-0019 (gruplar), ADR-0023 (geçmiş ve metrikler), ADR-0026 (denetim kaydı)

## Bağlam

- Bugüne kadar her belge tek hesabındı. Retrieval sahiplik filtresini sorgunun içinde uyguluyordu: `document_chunk.account_id = :account` (llm-rules 4.1). Bir birimin ortak belgelerini bir gruba açmanın yolu yoktu.
- Alıcı kontrol listelerinde birinci kapı budur: belge düzeyi yetki, IdP gruplarından devralınır, getirme anında süzülür, kapalı kalır. Kanıt `market_needs.md` madde 1 ve "aşırı paylaşım" bölümü: CoreView'de kurumların %66'sı Copilot'u erteledi, %73'ü gizli verinin açığa çıkmasından korkuyor. Onyx #14981'de özele çevrilen belge herkese aranabilir kaldı.
- ADR-0019 grupları yetki olarak hazırladı: `GROUP_<değer>`, Keycloak'ta tam yol, Entra'da object id. Bozuk claim atlanır, claim başına en çok 256 değer okunur.
- Kısıtlar:
  - Ek container ya da uzantı yok.
  - Chunk'lar güncellenmez: uygulama rolünde `document_chunk` için `UPDATE` yok (V1).
  - Flyway migration'ları değişmez.
  - Retrieval tek SQL cümlesidir (ADR-0022).

## Seçenekler

**Model:**

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| A. Retrieval her soruda `collection_grant` üzerinden join eder | Yazma tarafı basit | Kişisel ve paylaşımlı belge için iki ayrı koşul (`OR`); her sorguda koleksiyon join'i | Düşük |
| **B. Belge başına okuyucu listesi (`document_principal`), tek indeksli `EXISTS`** | Kişisel ve paylaşımlı belgeler için tek koşul; grant değişikliği yalnız küçük satırlar yazar | Grant, belge ve taşıma yazımları liste ile aynı transaction'da tutarlı tutulmalı | Orta |
| C. Okuyucu listesi chunk'ta `text[]` + GIN (`c.principals && :p`) | Chunk düzeyinde indeks; küçük hesapta hızlı | Grant değişikliği bütün chunk'ları yeniden yazar (1024 boyutlu satır, HNSW girdisi); uygulama rolüne chunk `UPDATE` vermek gerekir | Yüksek |

**Ölçüm (B ve C):** `docs/evidence/paylasimli-alanlar-dogrulama.md`. Ayrı bir PostgreSQL 18 + pgvector container'ında 110 bin chunk kullanıldı.
- B, grup içeren her durumda C'den hızlı çıktı.
- Grant değişikliği C'de 1 000 chunk için saniyeler sürdü (bütün koleksiyonlarda dakikalar). B'de 10–500 satır, milisaniyeler.

**Yönetici ve okuma:**

| Seçenek | Karar |
|---|---|
| Yönetici her alanı okur | Reddedildi: yönetim yetkisi görünürlük vermez (Onyx ilkesi, en az yetki) |
| **Yönetici her alanı yönetir, içeriği soru-cevapta yalnız kendisine (grubuna ya da `sub`'ına) erişim verilmişse okur** | Seçildi |

## Karar

**B** seçildi.

### Model (`V4__collections.sql`)

- `document.collection`:
  - Alanlar: ad (1–100, büyük-küçük harf duyarsız tekil), açıklama (≤ 500), oluşturan `sub`.
  - Yalnız `verso-admin` yönetir. v1'de yönetici yetkisi dışında bir "alan yöneticisi" ya da yazma erişimi yok.
- `document.collection_grant(collection_id, principal)`:
  - `principal`, `group:<groups claim değeri>` ya da `user:<sub>` biçimindedir. CHECK: önek ve 1–256 basılabilir karakter.
  - Engelleme (deny) ve rol girdisi yoktur. Bir grant yalnız okuyucu ekler.
- `document.document.collection_id`: NULL ise belge yükleyenin kişisel alanındadır, yani bugünkü davranış. Foreign key RESTRICT: içinde belge olan alan silinemez.
- `document.document_principal(document_id, principal)`, belgenin okuyucu listesidir:
  - Kişisel belgede tek satır vardır: `user:<sahip>`.
  - Paylaşımlı belgede alanın grant'ları vardır.
  - Birincil anahtar `(document_id, principal)`; ek indeks `(principal, document_id)`.
  - Migration var olan her belgeye `user:<account_id>` satırını yazar. Davranış değişmez.
- Chunk'lara dokunulmaz. `document_chunk.account_id` yükleyeni gösterir ve artık erişim kararı vermez.

### Okuyucu ve principal kümesi

- `Reader(sub, gruplar)` (`document-api`):
  - Principal'lar: `user:<sub>` ve token'daki her grup için `group:<değer>`.
  - Grup değeri boşsa, 256 karakterden uzunsa ya da kontrol veya biçim karakteri taşıyorsa düşer. En çok 256 grup okunur.
  - `toString` grupları yazmaz.
- Gruplar `CallerGroups` (`platform-security`) ile `GROUP_` yetkilerinden okunur. Bozuk claim ADR-0019'a göre hiç yetki üretmez, burada da grup yoktur: kapalı kalma.
- Grupsuz okuyucu yalnız kişisel belgelerini ve `sub`'ına açılmış alanları görür.
- Değerler hiçbir yerde loglanmaz, yanıtta okuyucuya geri verilmez. Grant listesi yalnız `verso-admin`'e döner.

### Retrieval: aynı SQL'in içinde, sıralamadan ve sınırdan önce

- `RetrievalRepository.READABLE_CHUNK`:

  ```sql
  EXISTS (SELECT 1 FROM document.document_principal p
          WHERE p.document_id = c.document_id AND p.principal = ANY(:principals))
  ```

- Bu koşul aşağıdakilerin hepsine konur:
  - vektör aday listesi;
  - sözcüksel aday listesi;
  - füzyon sonrası son seçim (iki kez savunma);
  - özgül terim payının paydası (`searchableChunks`) ve terim sayımı;
  - "başka modelle indekslenmiş" kontrolü.

  Hepsinde `LIMIT`'ten ve sıralamadan önce uygulanır.
- Yetkisiz bir chunk aday olmaz, sıralanmaz, sayılmaz. Bellekte süzme yoktur (llm-rules 4.1).
- Bir okuyucunun bayat (başka modelle indekslenmiş) belgesi yalnız o belgeyi okuyabilenleri 409 `DOCUMENT_REINDEX_REQUIRED` ile durdurur.

### Her okuma yolu aynı kuralı uygular

| Yol | Kural |
|---|---|
| `GET /v1/documents` | Okuyucunun bütün belgeleri, kişisel ve paylaşımlı (`collectionId`, `collectionName` eklendi). `?collectionId=` ile tek alan: okuyabildiği alan ya da yönetici |
| `GET /v1/documents/{id}` | Okuyabildiği belge; yönetici için her paylaşımlı belgenin metadatası. Başkasının kişisel belgesi kimse için yoktur (404) |
| `DELETE /v1/documents/{id}` | Kendi kişisel belgesi; paylaşımlı belgeyi yalnız yönetici siler. Okuyucuya 403 `DOCUMENT_MANAGED_BY_ADMIN` (10004) döner |
| Cevaptaki atıflar | Retrieval'dan geldikleri için zaten süzülmüştür; `collectionId`, `collectionName` eklendi |
| Geçmiş (`GET /v1/conversations/{id}`) | Atıf okuma anındaki erişimle çözülür (`DocumentCatalog.access`): okunabilir (ad ve alan), **erişilemez** (`documentAccessible: false`, ad yok) ya da silinmiş (`documentDeleted: true`). Alan eklemedir |
| Yönetici metrikleri | ADR-0023'ün belge adı politikası paylaşımlı belgeleri de kapsar: `VERSO_SHOW_DOCUMENT_NAMES` kapalıyken ad yok; alan adı hiç dönmez |
| İndirme | Yok: orijinal dosya işlendikten sonra silinir (ADR-0011) |

### Uçlar

| Uç | Rol |
|---|---|
| `GET /v1/collections`, `GET /v1/collections/{id}` | Her kimlikli çağıran: okuyabildiği alanlar. Yönetici bütün alanları görür; her birinde `readable` (soru-cevapta okuyor mu) ve `manageable` vardır. Okunamayan alan 404 `COLLECTION_NOT_FOUND` (10002) |
| `POST /v1/collections`, `PUT /v1/collections/{id}`, `DELETE /v1/collections/{id}` | `verso-admin` (sınıf düzeyinde `@PreAuthorize`). Silme yalnız boş alanda; doluysa 409 `COLLECTION_NOT_EMPTY` (10041) |
| `GET/POST /v1/collections/{id}/grants`, `DELETE /v1/collections/{id}/grants/{grantId}` | `verso-admin`. Aynı grant yeniden eklenirse 200 ile var olan döner (idempotent). Başka alanın grant id'si 404 (10003) |
| `POST /v1/documents?collectionId=` | `verso-admin` (yöntem düzeyinde `@PreAuthorize`) |
| `PUT /v1/documents/{id}/collection` (`{"collectionId": … \| null}`) | `verso-admin`. Kaynak: yöneticinin kendi kişisel belgesi ya da herhangi bir paylaşımlı belge. `null` belgeyi **yükleyeninin** kişisel alanına götürür |

- Sınırlar `verso.document.collections.*` altındadır (`max-collections` 500, `max-grants-per-collection` 100, `max-documents-per-collection` 5000). Hepsi başlangıç ayarıdır, ortam değişkeni değildir. Paylaşımlı belgeler yükleyenin kişisel kotasına sayılmaz; kuyruk sınırı yükleyen başına kalır.
- Hata kodları (document bloğu) şunlardır:
  - 10002 `COLLECTION_NOT_FOUND`, 10003 `COLLECTION_GRANT_NOT_FOUND`, 10004 `DOCUMENT_MANAGED_BY_ADMIN`;
  - 10040 `COLLECTION_NAME_TAKEN`, 10041 `COLLECTION_NOT_EMPTY`, 10042 `COLLECTION_GRANT_LIMIT_REACHED`;
  - 10043 `COLLECTION_LIMIT_REACHED`, 10044 `COLLECTION_DOCUMENT_LIMIT_REACHED`.
- İstemci dokümanı: `docs/api-collections-integration-v1.md`.

### Tutarlılık ve eşzamanlılık

- Grant ekleme/kaldırma ve ilgili `document_principal` satırları tek transaction'da yazılır. Alan satırı `FOR UPDATE` ile kilitlenir.
- Yükleme, taşıma ve paylaşımlı belge silme alan satırını `FOR SHARE` ile kilitler. Bu yüzden bir belge geri alınan bir principal'ı taşıyamaz, yeni bir grant'ı da kaçıramaz.
- Kilit sırası her yerde aynıdır: alan satırları (id sırasıyla), sonra hesap ya da alan kota kilidi, sonra belge satırı. Taşıma belge satırını `FOR NO KEY UPDATE` ile kilitler; grant'ın foreign key payı kilidiyle çakışmaz.
- Sınama: `CollectionAccessTest.concurrentUploadsAndGrantChanges_keepDocumentPrincipalsExact`. Eşzamanlı yükleme ve grant değişikliğinden sonra "beklenen ve gerçek okuyucu listesi farkı" sorgusu 0 döner.

### Grup değişikliğinin yayılması

- **Verso tarafındaki grant değişikliği** commit'ten sonraki ilk istekte geçerlidir. Grant her istekte veritabanından okunur.
- **IdP'deki üyelik değişikliği** (kullanıcının gruptan çıkarılması) bir sonraki access token'da geçerlidir, en çok 300 sn. ADR-0019 ve ADR-0007 #50 bunu kabul eder; `sv` yoktur.
- Keycloak grup tam yolu grup yeniden adlandırılınca değişir. Yola verilmiş grant'lar o zaman eşleşmez ve erişim kaybolur: kapalı kalma. Yönetici grant'ı yeni yolla günceller. Entra object id'leri bu sorunu taşımaz.

### Denetim (ADR-0026)

| Eylem | Hedef | Ayrıntı |
|---|---|---|
| `COLLECTION_CREATED`, `COLLECTION_UPDATED`, `COLLECTION_DELETED` | `COLLECTION` + id | – |
| `COLLECTION_GRANT_ADDED`, `COLLECTION_GRANT_REMOVED` | `COLLECTION` + id | `GROUP` / `USER`. Grup yolu ve `sub` yazılmaz. Grant satırının kendisi `created_by` ve zamanı taşır |
| `DOCUMENT_MOVED` | `DOCUMENT` + id | `TO_COLLECTION` / `TO_PERSONAL` |
| `ACCESS_DENIED` | `ADMIN_API` | `/v1/admin` dışında `@PreAuthorize(HAS_ADMIN)` taşıyan denetleyici uçları da kaydedilir (`COLLECTION_ADMIN`, `DOCUMENT`) |

- Aynı grant'ın tekrar eklenmesi bir şey değiştirmez ve kaydedilmez.
- Audit tablosunda eylem CHECK'i yoktur, migration gerekmez.

## Sonuçlar

- **Olumlu:**
  - Kurum, bir birimin belgelerini IdP grubuna açabilir. Yetki getirme anında, aynı SQL'de, kapalı kalarak uygulanır.
  - Kişisel belgelerin davranışı değişmedi. Tek hesaplı eval aynı: recall@5 0,982.
  - Grant değişikliği chunk yazmaz.
  - Geçmişte erişimi kalkan belge "erişiminiz yok" olarak görünür; ad ve alan sızmaz.
- **Olumsuz / kabul edilen risk:**
  - **Gecikme:** erişim artık belge düzeyinde bir `EXISTS`'tir; chunk düzeyindeki hesap indeksi kullanılamaz.
    - Sözcüksel liste, yaygın sözcüklerde kurulumun bütün eşleşen chunk'larını tarayıp okuyucunun belgelerine süzer.
    - Maliyet okuyucunun değil kurulumun büyüklüğüyle artar. Sentetik ölçüm (110 bin chunk) evidence dosyasında ve repo-context'tedir.
    - `lexical-candidates: 0` sözcüksel listeyi kapatır.
  - Yönetici paylaşımlı belgelerin adlarını ve metadatasını görür (yönetim için). İçeriği soru-cevapta yalnız erişim verilirse okur.
  - Grup yolu yeniden adlandırma erişimi düşürür (yukarıda). IdP üyelik değişikliği 300 sn gecikir.
  - Grup değeri büyük-küçük harf ve boşluk dahil birebir karşılaştırılır. Yönetici yanlış yazarsa kimse okuyamaz (kapalı kalma). Panel değeri olduğu gibi gönderir.
  - Silinen paylaşımlı belgeden alıntı yapan cevap metni, saklama süresi dolana kadar o okuyucunun geçmişinde kalır (ADR-0023 L3 ile aynı).
- **Etkilenen dosyalar ve yüzeyler:**
  - `platform-security`: `CallerGroups` ve bean'i.
  - `document-api`: `Reader`, `DocumentAccess`, `Collection*` DTO'ları, `MoveDocumentRequest`, `PrincipalType`. `DocumentResponse` ve `RetrievedPassage` sona alan aldı. `DocumentRetrieval.search(Reader, …)`, `DocumentCatalog.access`. Eski imzalar varsayılan metot olarak kaldı; grupsuz okuyucu demektir.
  - `document-core`:
    - `V4__collections.sql`;
    - `CollectionRepository`, `DocumentRepository`, `RetrievalRepository`;
    - `CollectionService(Impl)`, `DocumentServiceImpl`, `DocumentCatalogServiceImpl`, `DocumentRetrievalServiceImpl`;
    - `CollectionController`, `CollectionAdminController`, `DocumentController`, `CallerResolver`;
    - `CollectionProperties`, `DocumentErrorCode`.
  - `qa-api`: `Citation` ve `HistoryCitation` sona alan aldı. `qa-core`: `QuestionServiceImpl`, `ConversationServiceImpl`, `CitationExtractor`.
  - `audit-api`: yeni eylemler ve `COLLECTION` hedefi. `audit-core`: `AccessDeniedAuditListener`.
  - `config/verso.yml` (`verso.document.collections.*`). Ortam değişkeni, secret ve port eklenmedi.
  - Panel:
    - "Bilgi alanları" ekranı (`verso-admin`);
    - yüklemede alan seçici;
    - belge ve atıf rozetleri;
    - geçmişte "Erişiminiz yok".
  - `docs/uyum/` (VKED, aydınlatma metni, kullanım politikası).
- **Geri alma yolu:**
  - Kodla: commit geri alınır.
  - V4'ün tabloları ve kolonu eski kodla zararsızdır. Eski kod `document_principal`'ı okumaz ve paylaşımlı belgeleri **yükleyenin** belgesi olarak görür. Bu yüzden geri almadan önce paylaşımlı belgeler silinmeli ya da kişisel alana taşınmalıdır.
  - Tabloları düşürmek yeni bir migration ister.

## Yeniden değerlendirme koşulu

- Soru sorma p95'inde veritabanı payı 200 ms'yi aşarsa (ADR-0022), ya da kurulum 100 bin chunk'ı geçerse:
  - sözcüksel listeye chunk düzeyinde bir alan anahtarı eklenir (`document_chunk.collection_id`; taşıma o zaman chunk yazar), ya da
  - erişilebilir belge kümesi önce hesaplanır.
- Alan yöneticisi (yönetici olmadan grant verme) ya da gruba yazma yetkisi istenirse: grant'a rol (`READER` / `CURATOR`) eklenir. Grup üyeliği yalnız IdP'den gelmeye devam eder (Onyx CVE-2026-63178 dersi).
- Grup değişikliğinin 300 sn'den hızlı yansıması gerekirse ADR-0019'un koşulu geçerlidir.
- Bağlayıcılar (SharePoint vb., yol haritası madde 5) kaynak sistemin ACL'ini getirince: `document_principal` o ayna için de kullanılır. Engelleme (deny) gerekirse ayrı ADR yazılır.
