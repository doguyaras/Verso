# Paylaşımlı bilgi alanları ve izin duyarlı retrieval: doğrulama ve kanıt kaydı (ADR-0028)

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

## Künye

- **Tarih:** 2026-10-24
- **Branch:** `feature/doguyaras-paylasimli-alanlar` (`origin/develop` `55cd858` üzerinden).
- **Ortam:** Windows 11 + Docker Desktop; JDK 25.0.4.1; PostgreSQL 18 + pgvector 0.8.7 (`pgvector/pgvector:0.8.7-pg18-trixie`).
- **Kapsam:** yol haritası `enterprise_features.md` madde 3; `market_needs.md` madde 1 ("aşırı paylaşım").
- **Davranışsal kapsam (seviye 3):**
  - Gerçek PostgreSQL ve HNSW üzerinde HTTP uçtan uca testler (`CollectionAccessTest`).
  - Ayrı bir container'da sentetik performans ölçümü.
  - Gerçek bge-m3 ile eval (`RetrievalEvalTest`).
  - Çalışan yığına dokunulmadı.

## 1. Güvenlik erişim matrisi (`CollectionAccessTest`, gerçek port ve veritabanı)

| Durum | Beklenen | Sonuç |
|---|---|---|
| A grubundaki kullanıcı (`/hukuk`) | A alanının belgesini listeler, okur, retrieval'da bulur; B'nin belgesi ve alanı 404, retrieval'da yok | PASS |
| Grupsuz kullanıcı | Yalnız kişisel belgeleri; alan listesi boş; retrieval yalnız kişisel | PASS |
| Yönetici, erişimsiz | Bütün alanları `readable: false`, `manageable: true` görür ve paylaşımlı belge metadatasını okur. Soru-cevapta alan içeriği yok: `NOT_FOUND`, model çağrısı 0. Başka kullanıcının kişisel belgesi 404 | PASS |
| Yönetici, `USER` grant'ı ile | Aynı soru `ANSWERED`, atıfta `collectionName` | PASS |
| Grant kaldırıldı | Sonraki istekte belge 404, liste boş, retrieval boş. Geçmişteki atıf `documentAccessible: false`, `documentDeleted: false`, ad ve alan `null`. Silinince `documentDeleted: true` | PASS |
| Bozuk grup claim'i | Liste değil (nesne), iç içe liste ve kontrol karakterli değer: grup yok, alan yok, belge 404. Kontrol karakterli grant `400` | PASS |
| Yetkisiz chunk birebir en iyi eşleşme | İK belgesinin metni sorunun kendisi (cosine 1, bütün terimler). İK okuyucusunda ilk sırada. A okuyucusunun 100/100 sınırlı vektör ve sözcük aday listelerinde yok; terim payının paydası yalnız A'nın chunk'larını sayıyor; API cevabında ve prompt'ta yok | PASS |
| IDOR | Yönetici olmayanın 8 yönetim çağrısı 403 `90101` ve `ACCESS_DENIED` satırı (6 `COLLECTION_ADMIN`, 2 `DOCUMENT`). Okuyucunun paylaşımlı belge silmesi 403 `10004`. Okunamayan alan/belge ve başka alanın grant'ı 404; yöneticinin başkasının kişisel belgesini taşıması ve silmesi 404 | PASS |
| Eşzamanlılık | 12 yükleme ile 12 grant ekleme/kaldırma eşzamanlı: `document_principal` ile türetilen beklenen liste arasında fark 0; kaldırılan grant'tan satır kalmadı | PASS |
| Denetim ve log | Oluşturma, güncelleme ve silme olayları; grant olayları `GROUP` ayrıntısıyla; taşıma `TO_COLLECTION` ve `TO_PERSONAL`. Hiçbir log satırında grup yolu, alan adı ya da dosya adı yok | PASS |
| Metrikler | Belge adı politikası kapalıyken paylaşımlı belgenin adı ve alan adı yanıtta yok | PASS |

Birim testleri:
- `ClaimAuthoritiesConverterTest.callerGroups_*`: gruplar yalnız `GROUP_` yetkilerinden; bozuk claim boş.
- `DocumentApiContractTest.reader_*`: kullanıcı principal'ı önce; bozuk değerler düşer; en çok 256; `toString` grup yazmaz.
- `AccessDeniedAuditListenerTest.declaresAdmin_*`.
- `scripts/panel.test.mjs` "collections".

## 2. Performans (sentetik, ayrı container)

**Düzenek:**
- Ayrı bir `pgvector/pgvector:0.8.7-pg18-trixie` container'ı (4 CPU, 4 GB, varsayılan PostgreSQL ayarları). Çalışan yığına dokunulmadı; ölçümden sonra container silindi.
- 70 bin chunk:
  - `a10` hesabı 10 bin kişisel chunk;
  - 50 alan × 10 belge × 100 chunk = 50 bin paylaşımlı chunk (yükleyen `admin`);
  - başka bir hesap 10 bin chunk.
- Chunk başına 150 sözcük. Sözcükler 3 000 sözcüklük bir dağarcıktan Zipf benzeri dağılımla seçildi; vektörler rastgele 1024 boyutlu. HNSW (m 16, ef_construction 64), `turkish` tsvector GIN.
- Grant'lar:
  - her alan kendi grubuna (`/g1`…`/g50`);
  - `/first10` ilk 10 alana (10 bin chunk);
  - `/all50` bütün alanlara (50 bin chunk).
- 60 soru: rastgele vektör ve 4 terim (2 yaygın, 2 seyrek). İlk 10 soru ısınma.
- Süre, veritabanının içinde ölçüldü (`clock_timestamp`). `hnsw.iterative_scan = relaxed_order`, `ef_search` 40.
- "Hibrit", `RetrievalRepository.hybrid` cümlesidir (vektör 20 + sözcük 20, RRF, top 5). "Tam" buna ek olarak model kontrolünü, terim payının paydasını ve terim sayımını içerir: bir sorunun bütün veritabanı işi.

**Hibrit cümle, ms (p50 / p95 / p99):**

| Okuyucu | Eski: `c.account_id = :account` | **B: `document_principal` EXISTS (seçilen)** | C: chunk `text[]` + GIN `&&` |
|---|---|---|---|
| 10 bin chunk, yalnız kişisel | 82,5 / 165,3 / 446,6 | 108,5 / 124,0 / 125,5 | 62,3 / 79,4 / 80,6 |
| 10 bin chunk, 1 grup | – | 116,2 / 134,7 / 137,4 | 83,5 / 105,2 / 109,8 |
| 10 bin chunk, 50 grup (10'u yetkili) | – | 116,5 / 139,4 / 149,6 | 90,0 / 111,5 / 121,5 |
| 50 bin chunk, 1 grup | 358,1 / 427,3 / 463,3 (50 bin kendi chunk'ı) | 290,2 / 331,6 / 347,2 | 430,9 / 518,1 / 548,8 |
| 50 bin chunk, 50 grup | – | 292,4 / 324,8 / 340,7 | 522,9 / 1 851,9 / 2 035,2 |

**Seçilen yolun tam retrieval veritabanı süresi, ms (p50 / p95 / p99):**

| Okuyucu | 1 grup | 50 grup |
|---|---|---|
| 10 bin chunk | 169,9 / 193,7 / 209,8 | 181,5 / 206,4 / 237,1 |
| 50 bin chunk | 395,4 / 493,3 / 524,5 | 387,4 / 442,4 / 487,4 |
| 10 bin chunk, grupsuz (yalnız kişisel) | 156,1 / 179,4 / 190,5 | – |

**Grant değişikliğinin yazma maliyeti:**

| Değişiklik | B (`document_principal`) | C (chunk `text[]`) |
|---|---|---|
| 1 alan (10 belge / 1 000 chunk) | 1,8 ms | 6 393 ms |
| 50 alan (500 belge / 50 bin chunk) | 2,5 ms | ölçülmedi. Bozulmuş bir koşuda 50 bin chunk 305 sn sürdü; geçerli sayılmadı |

**Okuma:**
- C, okuyucunun 10 bin chunk'ında daha hızlı: p50'de 20–45 ms fark.
- 50 bin chunk'ta ve çok grupta B daha hızlı ve daha kararlı: p99'da 341 ms'ye karşı 2 035 ms.
- C'de bir grant değişikliği her chunk'ı (1024 boyutlu satır ve HNSW girdisi) yeniden yazar. Uygulama rolüne chunk `UPDATE` hakkı da gerekir.
- B'nin maliyeti: chunk düzeyinde hesap indeksi kalmaz. Sözcüksel liste yaygın sözcüklerde kurulumun eşleşen bütün chunk'larını tarar, sonra süzer. Küçük okuyucuda eski sorgudan p50'de yaklaşık 26 ms yavaştır; eski sorgunun p99 sıçraması (447 ms) B'de görülmedi.

**Reddedilen biçim:** `c.document_id = ANY(ARRAY(SELECT … document_principal …))`. Kirli koşuda (aynı makinede başka iş vardı) B'den belirgin yavaş çıktı (10 bin kişisel p50 173 ms). Temiz koşu yapılmadı.

**Not:** İlk ölçüm denemeleri aynı Docker sunucusundaki başka işlerle (başka oturumların container'ları) çakıştı ve atıldı. Yukarıdaki sayılar ayrı ve kaynakları sınırlanmış bir container'da, sıralı tek koşudan gelir. Sayılar kurulumlar arasında değil, varyantlar arasında karşılaştırma içindir.

## 3. Eval

`RetrievalEvalTest` (tek hesap; set sürüm 2; gerçek bge-m3, CPU'daki ayrı bir Ollama container'ı, modeller yığının volume'ünden salt okunur bağlandı):

| Ölçüm | develop (ADR-0022) | Bu branch |
|---|---|---|
| recall@1 / recall@5 / MRR | 0,795 / 0,982 / 0,877 | **0,795 / 0,982 / 0,877** |
| Kapıdan geçen cevaplanabilir | 112/112 | 112/112 |
| Kapının durdurduğu cevaplanamaz | 7/28 | 7/28 |

- Sonuç dosyası (`eval/results/retrieval.json`) yalnız ölçüm zamanı ve alan sırasıyla farklıydı, commit edilmedi.
- Testin soru embedding zaman aşımı 2 sn'dir. Paylaşılan makinede embedding süreleri bunu aştı. Ölçüm için bu ayar yerelde, commit edilmeden 20 sn yapıldı (eval/README.md "CPU" notu). Retrieval sonucu zaman aşımından etkilenmez.

## 4. Makine kontrolleri

| Kontrol | Komut | Sonuç |
|---|---|---|
| Bütün Java testleri | `./mvnw -B -ntp verify` | verso-app 267 testten 1'i kırmızıydı: `QuestionApiTest`, ek alanlarla güncellenmemiş beklenti. Düzeltildi ve yeniden koşuldu (22/22). Diğer modüller yeşil. Aralarında `ArchitectureRulesTest`, `ConfigDriftTest`, `ErrorCodeUniquenessTest`, `MigrationConventionsTest`, `ModuleConsistencyTest`, `DatabaseRolesTest`, `CollectionAccessTest` (9) var |
| Migration değişmezliği | `node scripts/flyway-immutability.js check --base origin/develop` | `flyway-immutability: OK (base=origin/develop, merge-base=55cd858cacc6)` |
| Script ve panel testleri | `GITLEAKS=… node --test scripts/*.test.js scripts/panel.test.mjs` | 166 test, 0 hata (panel 77, uyum paketi 9) |
| Pre-commit | flyway-immutability, config-lint, gitleaks | OK |

İlk tam koşuda V4'ün `CHECK` düzenli ifadesi (`{1,256}`) PostgreSQL'in tekrar sınırını (255) aşıyordu. Testler bunu yakaladı. Kısıt `+` ve `char_length(principal) <= 262` olarak yeniden yazıldı.

## 5. Review skill'leri (her biri bir kez)

| Skill | Karar | Not |
|---|---|---|
| `verso-security-review` | APPROVE WITH NON-BLOCKING COMMENTS | `@CurrentAccount` platform resolver'ı ile çözülür; gruplar yalnız doğrulanmış token yetkilerinden gelir; yönetim uçlarında sınıf ya da yöntem düzeyinde `@PreAuthorize`, serviste ikinci kontrol var. MEDIUM: grant kaldırma ile aynı anda koşan bir sorgu, commit'ten önceki anlık görüntüyü milisaniyeler içinde görebilir (MVCC; sonraki istek reddedilir). LOW: geçmişte "erişilemez" durumu, okuyucunun daha önce atıf aldığı bir belgenin hâlâ var olduğunu söyler. LOW: yönetici paylaşımlı belge adlarını görür (yönetim, ADR'de) |
| `verso-db-migration-review` | APPROVE WITH NON-BLOCKING COMMENTS | Yalnız kendi şeması; V4 yeni dosya. `collection_grant` ve `document_principal` üzerinde uygulama rolünün `UPDATE` hakkı geri alındı (review'da eklendi). LOW: foreign key yerinde doğrulanır; `document` tablosu küçük ve kolon her yerde NULL |
| `verso-spring-code-review` | APPROVE WITH NON-BLOCKING COMMENTS | İnce denetleyiciler, constructor injection, `@Valid`. Kilit sırası belgeli (alan satırları → kota kilidi → belge satırı). Geniş `catch` yok. LOW: `CollectionRepository.insert` satırı yeniden okur (ek bir sorgu) |
| `verso-api-contract-review` | APPROVE WITH NON-BLOCKING COMMENTS | OpenAPI diff yok, elle çıkarıldı. Bütün yanıt alanları ek ve sonda. `GET /v1/documents` paylaşımlı belgeleri de döner; `DELETE` paylaşımlı belgede 403 `10004` döner (yeni durum). İstemci dokümanı `docs/api-collections-integration-v1.md` |
| `verso-architecture-boundary-review` | APPROVE | Bu kapsamda sınır bulgusu yok. `qa` yalnız `document-api`'yi kullanır; şema dışı erişim yok; gruplar JWT claim'i |
| `verso-environment-impact-review` | APPROVE | Bu kapsamda environment bulgusu yok. Yalnız `config/verso.yml` `verso.document.collections.*` (ortama göre değişmeyen sınırlar). Env, secret ve port yok. Grup claim yolu zaten eşleniyordu (ADR-0019) |
| `verso-test-writer` | PASS | Erişim matrisi, en iyi eşleşmenin yetkisiz olduğu durum, IDOR, binding (400/401), eşzamanlılık değişmezi, log ve denetim gizliliği, metrik adı politikası, birim testleri |
| `verso-llm-review` | APPROVE WITH NON-BLOCKING COMMENTS | 4.1 iki aday listesinde ve sayımlarda; eval değişmedi; alan adı prompt'a girmez, yalnız istemciye döner. MEDIUM: sözcüksel listenin maliyeti kurulum büyüklüğüyle artar (bölüm 2) |
| `verso-resilience-review` | APPROVE WITH NON-BLOCKING COMMENTS | Yeni uzak çağrı yok. Sıcak yol kaydı ölçülen veritabanı payıyla güncellendi. En kötü durumu 10 sn `statement_timeout` sınırlar; `lexical-candidates: 0` acil kapatmadır |

## Bilinen sınırlar

- Erişim belge düzeyindedir. Sözcüksel liste kurulumun eşleşen chunk'larını tarar ve sonra süzer; maliyet kurulumun büyüklüğüyle artar (bölüm 2). Yeniden değerlendirme koşulu ADR-0028'de.
- Grup üyeliği değişikliği token ömrü kadar (300 sn) gecikir. Keycloak grup yolu yeniden adlandırılınca erişim düşer (kapalı kalma).
- Gerçek bir Entra kiracısıyla denenmedi (ADR-0019).
- Panelde alan ekranı yönetici içindir. Alan yeniden adlandırma ve açıklama düzenleme yalnız API'de (`PUT /v1/collections/{id}`).
