# ADR-0044 doğrulaması: Confluence bağlayıcısı ve kesin bekleyen silme sayısı

Tarih 2026-10-24. Dal `feature/doguyaras-confluence`, taban `origin/develop`. Bu turda Docker **kullanılmadı** (yığın başka bir ölçümde meşguldü): Testcontainers isteyen testler (gerçek veritabanı, `ConfluenceSyncTest`, `ConnectorApiTest`, `DeployConfigTest`) yalnız CI'da koşar. Gerçek bir Atlassian hesabıyla hiçbir şey denenmedi.

## Makine kontrolleri (yerel)

| Kontrol | Sonuç |
|---|---|
| `./mvnw -B -ntp -T 1 -DskipTests install` (bütün reactor, enforcer dahil) | yeşil |
| `connector-api` + `connector-core` birim testleri (`ConnectorApiContractTest`, `ConnectorArchitectureTest`, `StorageFormatConverterTest`, `ConfluenceSourceTest`, `ConfluenceRulesTest`, `ConnectorEgressConfigurationTest`, `SyncPlannerTest`, `SyncRunnerTest`, mevcut SMB testleri) | 109 + 4 test, yeşil |
| `verso-app`: `ArchitectureRulesTest`, `ConfigDriftTest`, `ErrorCodeUniquenessTest`, `ComposeConfigTest`, `ModuleStructureTest`, `ModuleConsistencyTest` (migration kuralları bütün modüllere); `document-core` `MigrationConventionsTest` | yeşil |
| `node scripts/flyway-immutability.js check --base origin/develop` | `flyway-immutability: OK` |
| `GITLEAKS=… node --test scripts/*.test.js scripts/panel.test.mjs` (panel ve uyum paketi dahil) | 237 test, 0 hata |
| `docker compose -f compose.yaml -f deploy/compose.confluence.yaml config --quiet` (yalnız ayrıştırma, konteyner yok) | yeşil |

## Birim testlerinin kanıtladıkları

- **Storage format → Markdown:** başlık düzeyleri, GitHub tablosu (başlık satırı, `|` kaçışı, hücre içi liste), varlıklar (`&nbsp;`, `&rsquo;`, sayısal), görünmeyen karakterlerin silinmesi, makrolar (info gövdesi alınır; code çitli blok, içteki ters tırnaktan uzun çit; toc, html, parametre, script, style, resim alınmaz), görev listesi, 5000 kat iç içe `div`'in sonlanması.
- **CQL:** alan anahtarı ve etiket kaçışı; `") OR space = ("` reddi; artımlı pencere `now("-Nm")`, yukarı yuvarlama, saat kayması.
- **429:** `Retry-After` (saniye ve HTTP tarihi) uygulanır; yoksa üstel geri çekilme ve eşit jitter; 60 sn'den uzun istek ya da biten denemeler koşuyu `RATE_LIMITED` ile durdurur; bekleme sırasında kira yenilenir.
- **Kısıt:** sayfanın kendisi ve atası (kalıtım) kısıtlı işaretlenir, ata sonucu önbellekte; kısıt okunamazsa sayfa listede yok ve liste eksik (`RESTRICTION_CHECK_FAILED`); planlayıcı yalnız kısıtı değişen sayfayı yeniden ele alır; koşucu kısıtlı sayfayı okumaz, önceki belgesini `SOURCE_RESTRICTED` ile siler.
- **Artımlı koşu:** listelenmeyeni silmez, başarı sayılır, imleci en yeni değişikliğe taşır; başarısız sayfa imleci kendi zamanında tutar; tam liste zamanı yalnız bekletilen silme yokken yazılır; `pendingDeletions` silme emniyetinin beklettiği kesin sayıdır (SMB'de de).
- **Egress:** site URL'i (https, kullanıcı bilgisi/sorgu/`#`/`..` yok), sunucu bağlantıları yalnız bağlam altındaki REST ve ek yollarına, yönlendirme yalnız aynı siteye (başka host'a hiç istek gitmez; Cloud ek yönlendirmesi dosya düzeyinde atlanır); host listesi (`*.net`, `*`, IP reddi); local modda dış bağlantı açıkken açılış reddi, politika ikinci çit. Gerçek istemci (Boot `InetAddressFilter`) reddedilen adrese hiç bağlanmaz, yönlendirme izlemez, gövde sınırını aşınca okumayı keser.
- **Review sırasında testin bulduğu kusur:** Boot'un filtresi reddi `FilteredHostException` (RuntimeException, mesajında host) olarak fırlatıyordu; istemci bunu IOException sanmadığı için koşu `SYNC_ERROR` olacaktı. Düzeltme: `ConfluenceTransport.Refused` → koşu `FAILED/EGRESS_DENIED`, host mesajı atılır (`ConfluenceSourceTest.http_whenTheAddressIsRefusedOrTheBodyTooLarge_failsWithoutReachingOrBuffering`).

## CI'da koşacak entegrasyon testi (`ConfluenceSyncTest`)

Bu JVM içinde, host'un özel (RFC 1918) adresinde dinleyen sahte bir Confluence REST sunucusu (CQL araması ve göreli pencere, kısıt API'si, storage gövdeleri): ekleme/güncelleme/silme (silme yalnız tam listede, türevleriyle), kısıtlı sayfanın atlanması, sonradan kısıtlanan sayfanın tam liste taramasında silinmesi (artımlı koşu görmez), Cloud'un dış bağlantı kapalıyken API'de `14013` ve eşitlemede `EGRESS_DENIED` (sahte sunucuya hiç istek gitmez), başka host'a yönlendirmede `REDIRECT_REFUSED` (tek istek), tür/alan uyuşmazlığı `14012`/`14014`/`14004`, tür değişikliği `409 14015`, user/operator `403` ve anonim `401` matrisi, belirtecin hiçbir log satırında ve yanıtta olmaması, sayfa başlığı ve gövdesinin log'da olmaması, her isteğin `Bearer` belirteçle gitmesi, koşu yanıtında `listingMode` ve `pendingDeletions`. **Yerelde koşturulmadı** (Docker kullanılmadı); ilk CI koşusu kanıt olacak.

## Canlı Confluence ile doğrulanması gerekenler

- CQL `lastmodified >= now("-Nm")` sözdiziminin ve `ORDER BY created ASC` ile `_links.next` sayfalamasının Data Center (7.x/8.x/9.x) ve Cloud'da beklenen sonucu vermesi; var olmayan alan anahtarının boş sonuç mu 400 mü döndüğü.
- `GET …/restriction/byOperation/read?expand=restrictions.user,restrictions.group` yanıt biçimi (`size` / `results`) her iki kurulumda; Cloud v1 REST'in kullanımdan kalkma takvimi.
- Data Center kişisel erişim belirteciyle `Bearer`, Cloud'da e-posta + API belirteciyle `Basic` kimlik doğrulaması; kurum içi CA sertifikasının Java güven deposunda olması.
- Gerçek sayfalarda storage formatı dönüşümünün kalitesi (birleştirilmiş tablo hücreleri, layout'lar, Türkçe karakterli başlıklar) ve parçalama.
- Cloud eklerinin medya host'una yönlendirilmesi (beklenen: `SKIPPED/REDIRECT_REFUSED`); Data Center ek indirmesinin aynı sitede kalması.
- 429 davranışı ve `Retry-After` değerleri, büyük alanlarda tam listenin süresi.
