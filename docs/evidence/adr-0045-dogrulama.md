# ADR-0045 doğrulaması: SharePoint Online / OneDrive bağlayıcısı

Tarih 2026-10-25. Dal `feature/doguyaras-sharepoint`, taban `origin/develop`. Bu turda Docker **konteyner başlatılmadı** (yığın bir reranker ölçümünde meşguldü): Testcontainers isteyen testler (gerçek veritabanı, `SharePointSyncTest`, `SharePointLocalModeTest`, `ConnectorApiTest`) yalnız CI'da koşar. Gerçek bir Microsoft 365 kiracısıyla hiçbir şey denenmedi.

## Makine kontrolleri (yerel)

| Kontrol | Sonuç |
|---|---|
| `./mvnw -B -ntp -T 1 -DskipTests install` (bütün reactor, enforcer dahil) | yeşil |
| `connector-api` + `connector-core` birim testleri (`ConnectorApiContractTest`, `ConnectorArchitectureTest`, `SharePointSourceTest`, `SharePointRulesTest`, `SyncRunnerTest`, `ConnectorEgressConfigurationTest`, mevcut SMB ve Confluence testleri) | 131 + 4 test, yeşil |
| `verso-app`: `ArchitectureRulesTest`, `ConfigDriftTest`, `ErrorCodeUniquenessTest`, `ComposeConfigTest` (SharePoint overlay testi dahil), `ModuleConsistencyTest`; `document-core` `MigrationConventionsTest` | yeşil |
| `node scripts/flyway-immutability.js check --base origin/develop` | `flyway-immutability: OK` |
| `node scripts/migration-contract.js check --base origin/develop` | `migration-contract: OK (1 yeni migration)`; V3'teki tek DROP (`ck_connector_type`) `-- contract:` gerekçeli |
| `GITLEAKS=… node --test scripts/*.test.js scripts/panel.test.mjs` (panel ve uyum paketi dahil) | 238 test, 237 geçti, 0 hata |
| `docker compose -f compose.yaml -f deploy/compose.cloud.yaml -f deploy/compose.sharepoint.yaml config --quiet` (yalnız ayrıştırma, konteyner yok) | yeşil |

## Birim testlerinin kanıtladıkları

- **Token:** istemci gizliyle form (`grant_type`, `client_id`, `scope=https://graph.microsoft.com/.default`, `client_secret`); sertifikayla RS256 istemci beyanı, sertifikanın açık anahtarıyla doğrulanır, `x5t#S256`, `aud` = token ucu, `iss` = `sub` = istemci kimliği, `jti`, `exp` = +5 dk; anahtar başka sertifikaya aitse `CREDENTIAL_INVALID`, süresi dolmuşsa `CREDENTIAL_EXPIRED`; koşu başına tek token; Graph 401 → bir yenileme, ikinci 401 ve token ucu 401 → `SOURCE_AUTH_FAILED`; `toString` gizliyi göstermez. Anahtarlar her koşuda JDK keytool ile üretilir, depoya özel anahtar girmez.
- **Delta:** tam liste (kök ilk, `@odata.nextLink` sayfalaması, `$top`), klasör ağacından yol, klasör ve glob kapsamı (klasör büyük/küçük harf duyarsız); artımlı turda yalnız değişenler, `deleted` facet'i ve klasör dışına taşınan dosya `deleted` listesinde; 410 → aynı koşuda tam liste ve izin taraması; bilinen klasör yeniden adlandırılır ya da silinirse baştan liste; Graph dışına giden `nextLink` → liste eksik, checkpoint yok, o adrese istek yok.
- **429:** `Retry-After` (7 sn) iki kez beklenir, koşu sürer; 3600 sn istenirse koşu `RATE_LIMITED` ile durur.
- **İndirme:** Graph 302'si `*.sharepoint.com`'a izlenir ve o istekte `Authorization` **yoktur**; başka host'a yönlendirme `REDIRECT_REFUSED` (dosya düzeyi) ve o host'a hiç istek gitmez; boyut sınırı `TOO_LARGE`; geçersiz öğe yolu `NOT_FOUND`.
- **Egress kuralı:** local mod, dış bağlantı kapalı, SharePoint olmayan host ya da IP → site reddedilir; indirme yalnız https, port'suz, kullanıcı bilgisiz, izinli host; Graph bağlantısı yalnız Graph origin'inin `drives/`/`sites/` yollarında, kodlanmış `.`/`/` reddedilir.
- **İzin kuralı:** yalnız kökten miras → alınır; doğrudan izin, paylaşım bağlantısı ya da kök dışı atadan miras → `RESTRICTED`; boş ya da okunamayan liste → tam taramada liste eksik (`RESTRICTION_CHECK_FAILED`), indirmede dosya alınmaz ve sonra yeniden denenir; tarama dışında izin indirme anında okunur ve farklı izinli dosyanın içeriği hiç indirilmez.
- **Koşucu:** artımlı listede bildirilen silme, izlenen öğeye uygulanır (silme emniyetiyle), checkpoint sonuçla aynı transaction'da yazılır, imleç koşu başlangıcına gelir; başarısız dosya varken checkpoint yazılmaz ama tam liste zamanı kaydedilir; indirme anında `RESTRICTED` önceki belgeyi `SOURCE_RESTRICTED` ile siler.
- **Alan kuralları:** site adresi/kimliği normalizasyonu ve reddi (http, kullanıcı bilgisi, sorgu, `#`, port, `..`, kodlanmış karakter, IP, farklı host), klasör ağacında derinlik, döngü, bilinmeyen üst ve güvensiz ad.
- **Panel:** form gövdesi `SharePointRequest`'i aynalar, sertifika ve `skipUniquePermissions` varsayılan, gizli alanı yok, tür ve hedef alan düzenlemede kilitli, `14004/14013/14014` site alanına düşer, ekranda `Sites.Selected` ve aşırı paylaşım uyarısı.

## CI'da koşacak entegrasyon testleri

- `SharePointSyncTest` (bulut modu, dış bağlantı açık, `require-https=false` yalnız sahte sunucu için): bu JVM içinde, host'un özel adresinde sahte Graph + Entra token ucu. Ekleme/güncelleme/silme (silme artımlı turda `deleted` facet'iyle, türevleriyle), farklı izinli dosyanın atlanması (içeriği hiç istenmez), 410'da tam liste, iki 429'dan sonra tamamlanan koşu, izinsiz host'a indirme yönlendirmesinde `SKIPPED:REDIRECT_REFUSED`, API'de `14013/14014/14012/14003` ve geçersiz `authMethod`, user/operator `403` ve anonim `401` matrisi (DELETE dahil), istemci gizlisinin ve erişim belirtecinin hiçbir log satırında ve yanıtta olmaması, dosya adı/içeriği ve site yolunun log'da olmaması, Graph isteklerinin `Bearer` belirteçle, indirme isteklerinin belirteçsiz gitmesi, `sharepoint_drive` checkpoint satırı.
- `SharePointLocalModeTest` (local mod): API `400 14013`; başka yoldan yazılmış SHAREPOINT satırının koşusu hiçbir istek atmadan `FAILED/EGRESS_DENIED`.
- **Yerelde koşturulmadı**; ilk CI koşusu kanıt olacak.

## Gerçek kiracıyla doğrulanması gerekenler

1. `Sites.Selected` + `New-MgSitePermission … roles=read` ile `/sites/{host}:{yol}`, `/sites/{id}/drive(s)`, `/drives/{id}/root`, `root/delta`, `items/{id}/permissions` ve `items/{id}/content` uçlarının hepsinin açık olduğu (özellikle izin listesi okumanın `read` rolüyle yapılabildiği).
2. İzin listesinde `inheritedFrom`'un SharePoint Online'da doldurulduğu ve kökten mirasın kök öğe kimliğiyle (ya da `/drives/{id}/root:` yoluyla) raporlandığı; benzersiz izinli klasörün çocuklarında `inheritedFrom.id`'nin o klasör olduğu. Değilse her dosya "farklı" sayılır ve hiçbir şey alınmaz (güvenli yönde).
3. Entra'nın RS256 ve `x5t`+`x5t#S256` başlıklı istemci beyanını kabul ettiği (gerekirse `assertion-algorithm: PS256`).
4. `delta` üzerinde `$select` (özellikle `deleted`, `root`, `parentReference`) ve `$top=200`'ün kabul edildiği; deltaLink/nextLink biçiminin `https://graph.microsoft.com/v1.0/drives/…` olduğu (değilse `LISTING_LINK_REFUSED`).
5. İçerik yönlendirmesinin `*.sharepoint.com` host'una gittiği (bazı kiracılarda farklı bir indirme host'u olabilir; o zaman `download-hosts` ve dış bağlantı listesi genişletilir).
6. Klasör yeniden adlandırma/taşıma/silmenin delta'da klasör öğesi olarak geldiği ve çocuk değişikliklerinin üst klasörü yeniden raporladığında (aynı ad/üst) gereksiz tam liste tetiklenmediği.
7. Delta bağlantısının süresi dolduğunda 410 (ve `resyncRequired`) döndüğü.
8. Büyük kitaplıkta (10 000+ dosya) tam tarama süresi ve 429 sıklığı; `full-listing-interval` ve `page-size` ayarı.
9. OneDrive (`-my.sharepoint.com/personal/...`) sitesinin aynı yolla çözüldüğü.

## Review skill'leri (birer kez)

| Skill | Karar | Not |
|---|---|---|
| verso-security-review | APPROVE WITH NON-BLOCKING COMMENTS | LOW: `require-https=false` üretimde de ayarlanabilir (Confluence'taki gibi; yalnız IP-literal özel uçlara izin verir); PEM gövdesi bir an değişmez String'den geçer; yönetici başka bir `SECRET_CONNECTOR_*` dosyasını seçebilir (yalnız Entra'ya gider) |
| verso-architecture-boundary-review | APPROVE | yalnız `connector` şeması; `document-api` üzerinden; ArchUnit katmanları genişledi (HTTP istemcisi ve Nimbus paket içinde) |
| verso-db-migration-review | APPROVE | ekleyici V3; CHECK genişletmesi `-- contract:`; PK'ler sorguları karşılar; cascade bilinçli |
| verso-resilience-review | APPROVE WITH NON-BLOCKING COMMENTS | sıcak yol dışı; timeout, sınırlı deneme, `Retry-After` tavanı, bağlayıcı düzeyinde geri çekilme; LOW: koşu başına yeni HTTP istemcisi |
| verso-environment-impact-review | APPROVE | `verso.yml`, `.env.example`, `prod.env.example` notu, overlay, secrets README, `dev-secrets.sh`; yeni env yalnız `VERSO_SHAREPOINT_SUBNET` |
| verso-api-contract-review | APPROVE WITH NON-BLOCKING COMMENTS | ekleyici; yeni hata kodu yok; LOW: dış istemciler yeni `type` değerini görür |
| verso-spring-code-review | APPROVE WITH NON-BLOCKING COMMENTS | LOW: 410/klasör nedeniyle baştan liste yalnız `incremental=false` ile görünür |
| verso-test-writer | PASS (yerel) | CI: gerçek DB'de checkpoint ve Boot istemcisi |
| verso-llm-review | APPROVE | local modda çıkış yok; içerik log'a girmez; prompt yolu değişmedi |
