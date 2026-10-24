# API istemcisi bağlayıcı entegrasyonu (SMB, Confluence ve SharePoint Online / OneDrive) [v1]

Verso bir SMB (Windows / Samba) paylaşımındaki klasörü, Confluence (Data Center/Server ya da Cloud) alanlarındaki sayfaları ya da SharePoint Online / OneDrive belge kitaplıklarındaki dosyaları bir bilgi alanına eşitler: yeni ve değişen dosyalar belge olarak alınır, kaynakta silinen dosya Verso'da bütün türevleriyle silinir. `verso-admin` rolündeki yönetici bağlayıcıları oluşturur, değiştirir, siler, eşitlemeyi tetikler ve koşuları izler. Eşitlenen belgeleri, hedef bilgi alanına erişimi olan herkes okur (ADR-0028).

> **OpenAPI doğrulanmadı:** uçlar ve alanlar `AdminConnectorController`, `ConnectorAdminServiceImpl` ve `connector-api` DTO'larından çıkarıldı (ADR-0004). Generated client paketi: yok.

Bu doküman **24 Ekim 2026** tarihli `feature/doguyaras-smb-baglayici` branch'indeki koda (taban `develop` `86e73d1`) ve Confluence için `feature/doguyaras-confluence`, SharePoint için `feature/doguyaras-sharepoint` (25 Ekim 2026) branch'ine dayanır. Kararlar: [ADR-0038](adr/0038-smb-baglayicisi.md) (SMB), [ADR-0044](adr/0044-confluence-baglayicisi.md) (Confluence, `pendingDeletions`), [ADR-0045](adr/0045-sharepoint-baglayicisi.md) (SharePoint).

## Değişikliklerin özeti
- Yeni yönetici uçları: `/v1/admin/connectors` altında oluşturma, liste, okuma, değiştirme, silme, eşitleme isteği, koşu geçmişi ve izlenen dosyalar.
- Yeni hata kodları: 14001–14011 (`connector` bloğu).
- Yeni denetim eylemleri: `CONNECTOR_CREATED`, `CONNECTOR_UPDATED`, `CONNECTOR_DELETED`, `CONNECTOR_SYNC_REQUESTED`, `CONNECTOR_SYNC_STARTED`, `CONNECTOR_SYNC_FINISHED`, `CONNECTOR_DOCUMENT_ADDED`, `CONNECTOR_DOCUMENT_DELETED`; yeni hedef türü `CONNECTOR`. `GET /v1/admin/audit` istemcisi bilinmeyen eylemi ham göstermelidir (zaten kural). Eşitleme satırlarının aktörü `system:connector-sync`'tir ve rolü yoktur.
- Mevcut uçların sözleşmesi **değişmedi**. Bağlayıcı belgeleri `/v1/documents?collectionId=` listesinde sıradan bilgi alanı belgesi olarak görünür.
- Mobil için etki yok. Panelde "Yönetim → Bağlayıcılar" ekranı bu dokümana dayanır ([ADR-0042](adr/0042-baglayici-ekrani.md)).

### ADR-0044 ile gelen değişiklikler (ekleyici)
- `ConnectorRequest`'e `type` (`SMB` varsayılan, `CONFLUENCE`) ve `confluence` nesnesi eklendi. SMB istekleri değişmeden çalışır.
- `ConnectorResponse`'a `type` ve `confluence` (SMB'de `null`) eklendi; Confluence bağlayıcısında `share`, `domain` `null`, `pathPrefix` boş, `username` yalnız Cloud'da doludur.
- `SyncRunResponse`'a `listingMode` ve **`pendingDeletions`** eklendi: silme emniyetinin o koşuda beklettiği silmelerin **kesin** sayısı (ADR-0042'nin açık noktası). Bu alanın eklenmesinden önceki koşularda `null`'dır; istemci o zaman `deferred`'ı "en çok" diye göstermeye devam eder.
- Yeni hata kodları 14012–14015. Bir değişiklik: SMB isteğinde `host`, `share` ya da `username` eksikse yanıt artık genel doğrulama kodu yerine `400 14012`'dir (HTTP durumu aynı).
- Yeni koşu kodları ve dosya kodları aşağıdaki listelerdedir; bilinmeyen kodu ham gösteren istemci etkilenmez.
- Yeni denetim eylemi yok; Confluence de aynı eylemleri yazar. Kısıtlandığı için kaldırılan sayfanın `CONNECTOR_DOCUMENT_DELETED` ayrıntısı `SOURCE_RESTRICTED`'tır.

### ADR-0045 ile gelen değişiklikler (ekleyici)
- `type` için yeni değer `SHAREPOINT`; `ConnectorRequest`'e `sharePoint` nesnesi, `ConnectorResponse`'a `sharePoint` (başka türde `null`) eklendi. SMB ve Confluence istek ve yanıtları değişmedi (yalnız yanıtta yeni `sharePoint: null` alanı).
- **Yeni hata kodu yok**; 14003, 14009, 14012, 14013 ve 14014 SharePoint için de kullanılır (tabloda).
- Yeni koşu kodları: `DRIVE_NOT_FOUND`, `PARENT_UNKNOWN`, `CREDENTIAL_INVALID`, `CREDENTIAL_EXPIRED`; yeni dosya kodu `RESTRICTION_CHECK_FAILED`. Bilinmeyen kodu ham gösteren istemci etkilenmez.
- Bir artımlı (`INCREMENTAL`) SharePoint koşusu silebilir: Graph delta'sının `deleted` facet'i ve kapsamdan çıkan dosyalar silinir (aynı %20 emniyetiyle). Confluence'ta artımlı koşu silmez (değişmedi).
- Yeni denetim eylemi yok; farklı izinli olduğu için kaldırılan dosyanın `CONNECTOR_DOCUMENT_DELETED` ayrıntısı `SOURCE_RESTRICTED`'tır.

## Kurallar
- **Kritik:** Bütün uçlar yalnız `verso-admin` rolündedir (ADR-0019). Başka her kimlikli çağıran `403` (`90101 ACCESS_DENIED`; denetim kaydına `ACCESS_DENIED` / `ADMIN_CONNECTOR` olarak yazılır), kimliksiz `401` alır.
- **Kritik:** Parola hiçbir istekte ve yanıtta yoktur. `credentialRef` kurulumdaki bir secret dosyasının **adıdır** (`SECRET_CONNECTOR_` ile başlamalıdır), değeri değildir. İstemci parola alanı göstermez ve istemez.
- **Kritik:** Eşitlenen her dosyayı hedef alanın bütün okuyucuları görür; NTFS izinleri ve Confluence alan/sayfa izinleri aktarılmaz. Ekran bunu alan seçiminin yanında söylemelidir.
- **Kritik (Confluence v1 güvenlik kuralı):** `skipRestricted` varsayılan `true`'dur: kendisinde ya da bir üst sayfasında okuma kısıtı olan sayfa eşitlenmez, kısıt sonradan konduysa belgesi silinir (günlük tam liste ve kısıt taraması). `false` yapan yönetici kısıtlı sayfaları da bilgi alanının bütün okuyucularına açar; ekran bunu uyarı olarak göstermelidir.
- **Kritik:** Confluence belirteci de hiçbir istekte ve yanıtta yoktur: `credentialRef` (ör. `SECRET_CONNECTOR_CONFLUENCE_TOKEN`) yalnız dosyanın adıdır.
- Bütün yanıtlar `Cache-Control: no-store, private`'tır. Başarı yanıtları ham, hatalar zarflıdır (ADR-0004). Bilinmeyen alanlar ve enum değerleri yok sayılır ya da ham gösterilir.
- `health`, son başarılı eşitlemenin zamanından türetilir; son koşunun durumu tek başına "sağlıklı" demek değildir. Ekran `health`'i göstermelidir.

## Uçlar

| Uç | Gövde | Başarı | Not |
|---|---|---|---|
| `POST /v1/admin/connectors` | `ConnectorRequest` | `201` + `Location` + `ConnectorResponse` | ilk eşitleme hemen vadededir |
| `GET /v1/admin/connectors?page=0&size=50` | – | `200` sayfa | ada göre; `size` 1–200, `page` 0–10 000 |
| `GET /v1/admin/connectors/{id}` | – | `200` `ConnectorResponse` | |
| `PUT /v1/admin/connectors/{id}` | `ConnectorRequest` (tam) | `200` | `collectionId` ve `type` değiştirilemez; sonraki eşitleme hemen (Confluence: tam liste) |
| `DELETE /v1/admin/connectors/{id}` | – | `202` | bağlayıcı durur, belgeleri arka planda silinir, sonra `404`; tekrar güvenli |
| `POST /v1/admin/connectors/{id}/sync` | isteğe bağlı `{"confirmMassDeletion": false}` | `202` | koşan bir eşitleme varsa o bitince yeniden koşar |
| `GET /v1/admin/connectors/{id}/runs?page=0&size=20` | – | `200` sayfa `SyncRunResponse` | en yeni önce; bağlayıcı başına son 100 |
| `GET /v1/admin/connectors/{id}/items?state=FAILED&page=0&size=50` | – | `200` sayfa `ConnectorItemResponse` | `state`: `SYNCED`, `FAILED`, `SKIPPED` ya da yok (hepsi); yola göre |

### `ConnectorRequest`

```json
{
  "name": "İK politikaları",
  "host": "10.20.30.40",
  "port": 445,
  "share": "Kurumsal",
  "pathPrefix": "IK/Politikalar",
  "domain": "CORP",
  "username": "svc-verso",
  "credentialRef": "SECRET_CONNECTOR_SMB_PASSWORD",
  "collectionId": "0192...",
  "includeGlobs": ["*.pdf", "*.docx"],
  "excludeGlobs": ["arsiv/**", "*taslak*"],
  "maxFileBytes": 10485760,
  "scheduleMinutes": 60,
  "enabled": true
}
```

- `name` 1–100 karakter, kontrol karakteri yok, büyük-küçük harf duyarsız tekil. `host` DNS adı ya da IP (şema, port, yol yok). IP verilirse kurulumun izinli ağlarında olmalıdır; ad ise her eşitlemede çözülüp denetlenir. `port` varsayılan 445.
- `share` eğik çizgisiz paylaşım adı. `pathPrefix` göreli klasör (`/` ya da `\`); `.`, `..`, `:` ve boş parça reddedilir; boş: bütün paylaşım.
- `includeGlobs` / `excludeGlobs` en çok 20'şer, 200 karakter: `*`, `**`, `?`, `{a,b}`; eğik çizgisiz desen her klasördeki dosya adına, eğik çizgili desen önekten göreli yola uyar; büyük-küçük harf duyarsız. Desen ne olursa olsun yalnız PDF, DOCX, TXT ve MD alınır.
- `maxFileBytes` varsayılan ve üst sınır belge sınırıdır (20 MB). `scheduleMinutes` 5–10080.

### `ConnectorRequest` (Confluence)

```json
{
  "type": "CONFLUENCE",
  "name": "İK wiki",
  "username": null,
  "credentialRef": "SECRET_CONNECTOR_CONFLUENCE_TOKEN",
  "collectionId": "0192...",
  "maxFileBytes": 10485760,
  "scheduleMinutes": 30,
  "enabled": true,
  "confluence": {
    "baseUrl": "https://wiki.kurum.local",
    "deployment": "DATA_CENTER",
    "spaceKeys": ["IK", "POLITIKA"],
    "label": "yayinda",
    "ancestorId": null,
    "includeAttachments": false,
    "skipRestricted": true,
    "pageSize": 50
  }
}
```

- `host`, `port`, `share`, `pathPrefix`, `domain`, `includeGlobs`, `excludeGlobs` gönderilmez (`400 14012`). `confluence` zorunludur. `PUT` tam gövdedir: Confluence bağlayıcısında `type: "CONFLUENCE"` yeniden gönderilir (yoksa `SMB` sayılır ve `409 14015`).
- `baseUrl`: `https://host[:port][/bağlam]`; kullanıcı bilgisi, sorgu, `#` ve `..` yok (`400 14014`). Data Center'da IP verilirse izinli iç ağlarda olmalıdır (`400 14004`); ad her eşitlemede çözülür, bütün adresleri izinli ağlarda olmalıdır. Cloud'da host kurulumun dış bağlantı listesinde olmalıdır (`VERSO_CONNECTOR_EXTERNAL_HOSTS`, ör. `*.atlassian.net`), yoksa ya da sıkı local moddaysa `400 14013`.
- `deployment`: `DATA_CENTER` (kişisel erişim belirteci, Bearer; `username` boş olmalı) ya da `CLOUD` (`username` hesabın e-postası, belirteç API token; Basic).
- `spaceKeys` 1–20 anahtar (`[A-Za-z0-9_~.-]`); `label` tek etiket; `ancestorId` bir sayfa kimliği (altındaki sayfalar); `pageSize` 1–250 (varsayılan 50; 429 alınıyorsa küçült).
- `includeAttachments` (varsayılan `false`): sayfaların PDF ve DOCX ekleri, aynı boyut sınırıyla; ek sayfasının kısıtına uyar. Cloud ekleri medya sunucusuna yönlendirildiği için bu sürümde atlanır (`REDIRECT_REFUSED`).
- Sayfa belgesinin adı sayfa başlığı + `.md`'dir; içerik storage formatından Markdown'a çevrilir (başlıklar ve tablolar korunur, makro parametreleri ve HTML makroları alınmaz).

### `ConnectorRequest` (SharePoint Online / OneDrive)

```json
{
  "type": "SHAREPOINT",
  "name": "İK sitesi",
  "pathPrefix": "Politikalar",
  "includeGlobs": ["*.pdf", "*.docx"],
  "excludeGlobs": ["arsiv/**"],
  "credentialRef": "SECRET_CONNECTOR_SHAREPOINT_CERT",
  "collectionId": "0192...",
  "maxFileBytes": 10485760,
  "scheduleMinutes": 60,
  "enabled": true,
  "sharePoint": {
    "siteUrl": "https://kurum.sharepoint.com/sites/ik",
    "siteId": null,
    "drives": ["Belgeler"],
    "tenantId": null,
    "clientId": "9e8d7c6b-5a49-4837-a625-14f3e2d1c0b9",
    "authMethod": "CERTIFICATE",
    "skipUniquePermissions": true
  }
}
```

- `host`, `port`, `share`, `domain`, `username` ve `confluence` gönderilmez (`400 14012`). `sharePoint` zorunludur. `PUT` tam gövdedir ve `type: "SHAREPOINT"`'i yeniden gönderir (yoksa `409 14015`).
- `siteUrl`: `https://<kiracı>.sharepoint.com[/sites/…]` ya da OneDrive `https://<kiracı>-my.sharepoint.com/personal/…`; https, port, kullanıcı bilgisi, sorgu, `#`, `..` ve kodlanmış karakter yok (`400 14014`). Yerine ya da yanında `siteId` (`host,guid,guid`); ikisi verilirse aynı host'u göstermeli (`14014`). Site host'u kurulumun dış bağlantı listesinde ve SharePoint host'u olmalı, Graph ve Entra da listede olmalı; dış bağlantı kapalıysa ya da sıkı local moddaysa `400 14013`.
- `drives`: kitaplık adları ya da sürücü kimlikleri, en çok 10 (boş: varsayılan kitaplık). `pathPrefix`: her kitaplığın içindeki klasör (`400 14003`); `includeGlobs`/`excludeGlobs` o klasöre göre göreli yolda (`400 14009`). Yalnız PDF, DOCX, TXT, MD.
- `tenantId`: dizin GUID'i; boşsa kurulumun Entra kiracısı (`OIDC_ENTRA_TENANT_ID`), o da yoksa `400 14012`. `clientId`: bağlayıcının **kendi** uygulama kaydı. `authMethod`: `CERTIFICATE` (önerilen; gizli dosya PKCS#8 özel anahtar + sertifika PEM) ya da `CLIENT_SECRET`. Önerilen Graph uygulama izni `Sites.Selected` + site başına `read` (README).
- `skipUniquePermissions` (varsayılan `true`): kitaplık kökünden miras almayan izinli dosya (benzersiz izin, doğrudan yetki, paylaşım bağlantısı) alınmaz, önceden alınmışsa silinir; `false` bu dosyaları alanın bütün okuyucularına açar.
- Belge adı dosya adıdır; `path` `drives/<sürücü id>/items/<öğe id>`'dir (ad değil).

### `ConnectorResponse` (alanlar)

`id`, `name`, `host`, `port`, `share`, `pathPrefix` (normalize: `IK/Politikalar`), `domain`, `username`, `credentialRef`, `collectionId`, `includeGlobs`, `excludeGlobs`, `maxFileBytes`, `scheduleMinutes`, `enabled`, `health` (`OK`, `FAILING`, `STALE`, `NEVER_SYNCED`, `DISABLED`, `DELETING`), `lastSuccessAt`, `lastFailureAt`, `consecutiveFailures`, `lastErrorCode`, `nextRunAt`, `items` (`synced`, `failed`, `skipped`), `createdAt`, `updatedAt`, `type`, `confluence` (`baseUrl`, `deployment`, `spaceKeys`, `label`, `ancestorId`, `includeAttachments`, `skipRestricted`, `pageSize`, `lastFullListingAt`; başka türde `null`), `sharePoint` (`siteUrl`, `siteId`, `drives`, `tenantId`, `clientId`, `authMethod`, `skipUniquePermissions`, `lastFullListingAt`; başka türde `null`; SharePoint'te `host` site host'u, `pathPrefix` kitaplık içindeki klasördür). Zamanlar UTC ISO-8601.

### `SyncRunResponse`

`id`, `status` (`RUNNING`, `SUCCEEDED`, `PARTIAL`, `FAILED`, `ABANDONED`), `startedAt`, `finishedAt`, `errorCode`, `listingComplete`, `listed`, `added`, `updated`, `deleted`, `unchanged`, `failed`, `skipped`, `deferred`, `listingMode`, `pendingDeletions`. Dosya adı yoktur.

- `listingMode`: "FULL" (kaynağın tamamı; silme yalnız bunda) ya da "INCREMENTAL" (Confluence: yalnız imleçten bu yana değişen sayfalar; hiçbir şey silmez). SMB her zaman "FULL"'dur.
- `pendingDeletions`: silme emniyetinin bu koşuda beklettiği silmelerin kesin sayısı (yoksa 0). Onay (`confirmMassDeletion: true`) tam bu sayıdaki belgeyi siler; Confluence'ta onaylı koşu tam listedir. Bu alandan önceki koşularda `null`. `errorCode` ilk sorunu taşıdığı için silmeler bekletildiği hâlde kod başka olabilir; onay kutusu `pendingDeletions > 0`'a bakmalıdır.
- `listingComplete`: listede boşluk yok (okunamayan klasör, kesilen liste, okunamayan bir kısıt).

- `PARTIAL`: kaynak listelendi ama bazı dosyalar başarısız (`FILES_FAILED`), sınıra ya da kuyruğa takıldı (`RUN_LIMIT`, `BACKLOG`; devamı birkaç dakika sonra), silmeler emniyetten bekletildi (`DELETION_SAFETY`), liste yarım kaldı (`DIRECTORY_UNREADABLE`, `LINK_NOT_FOLLOWED`, `LISTING_TOO_DEEP`, `LISTING_TOO_LARGE`) ya da uygulama kapandı (`INTERRUPTED`). Confluence'a özgü liste sorunları: bir sayfanın kısıtları okunamadı (`RESTRICTION_CHECK_FAILED`; o sayfa alınmaz, hiçbir şey silinmez), devam bağlantısı başka bir yere gidiyordu (`LISTING_LINK_REFUSED`), bir sayfanın ekleri listelenemedi (`ATTACHMENTS_UNREADABLE`). SharePoint: bir dosyanın izinleri okunamadı (`RESTRICTION_CHECK_FAILED`), sayfa bağlantısı Graph dışına gidiyordu (`LISTING_LINK_REFUSED`), bir dosyanın klasörü listede yoktu (`PARENT_UNKNOWN`).
- `FAILED`: kaynağa ulaşılamadı; **hiçbir belge silinmez**. Kodlar: `EGRESS_DENIED`, `SOURCE_UNREACHABLE`, `SOURCE_AUTH_FAILED`, `CREDENTIAL_MISSING`, `SHARE_NOT_FOUND`, `PATH_NOT_FOUND`, `PATH_UNREADABLE`, `COLLECTION_MISSING`, `CONFIG_INVALID`, `SYNC_ERROR`. Confluence: `RATE_LIMITED` (429, `Retry-After` ve geri çekilmeyle denendikten sonra), `SOURCE_UNAVAILABLE` (503), `REDIRECT_REFUSED` (başka sunucuya yönlendirme; belirteç gönderilmez), `SITE_NOT_FOUND` (arama ucu 403/404), `INVALID_RESPONSE`, `SOURCE_ERROR`. SharePoint: aynı kodlar (429/503/504 Graph ve Entra için) ve `SITE_NOT_FOUND`, `DRIVE_NOT_FOUND` (çoğunlukla `Sites.Selected` izni verilmemiş), `SOURCE_AUTH_FAILED` (Entra uygulamayı, sertifikayı ya da gizliyi reddetti), `CREDENTIAL_INVALID` (anahtar ve sertifika okunamadı ya da eşleşmiyor), `CREDENTIAL_EXPIRED`.
- `ABANDONED` / `INSTANCE_STOPPED`: koşan instance durdu; iş başka bir koşuda kaldığı yerden, çift belge üretmeden tamamlandı.

### `ConnectorItemResponse`

`path` (önekten göreli; Confluence'ta `pages/<sayfa id>` ya da `attachments/<sayfa id>/<ek id>`, başlık değil; SharePoint'te `drives/<sürücü id>/items/<öğe id>`, ad değil), `state`, `failureCode` (`READ_FAILED`, `READ_TIMEOUT`, `ACCESS_DENIED`, `FILE_VANISHED`, `COLLECTION_FULL`, `TOO_LARGE`, `EMPTY`, `UNSUPPORTED`, `UNSAFE_LINK`, `RESTRICTED`, `RESTRICTION_CHECK_FAILED`, `NOT_FOUND`, `REDIRECT_REFUSED`), `attempts`, `nextAttemptAt`, `sizeBytes`, `modifiedAt`, `documentId`, `updatedAt`. Yollar kişisel veri içerebilir: yalnız yöneticiye gösterilir, URL'e ya da analitiğe konmaz.

## Hata kodları

| Kod | HTTP | Ad | Ne zaman |
|---|---|---|---|
| 14001 | 404 | `CONNECTOR_NOT_FOUND` | id yok |
| 14002 | 409 | `CONNECTOR_NAME_TAKEN` | ad başka bir bağlayıcıda |
| 14003 | 400 | `CONNECTOR_PATH_INVALID` | önek paylaşımın dışına çıkabilir ya da geçersiz |
| 14004 | 400 | `CONNECTOR_HOST_NOT_ALLOWED` | IP izinli iç ağların dışında (ya da loopback, link-local, metadata) |
| 14005 | 400 | `CONNECTOR_COLLECTION_NOT_FOUND` | hedef bilgi alanı yok |
| 14006 | 409 | `CONNECTOR_COLLECTION_IMMUTABLE` | `PUT` hedef alanı değiştirmeye çalıştı |
| 14007 | 409 | `CONNECTOR_DELETING` | silinmekte olan bağlayıcı değiştirilemez ya da eşitlenemez |
| 14008 | 409 | `CONNECTOR_LIMIT_REACHED` | kurulumda en çok 50 bağlayıcı |
| 14009 | 400 | `CONNECTOR_GLOB_INVALID` | desen kullanılamaz (kapanmamış `{`, `..`) |
| 14010 | 400 | `CONNECTOR_FILE_SIZE_INVALID` | `maxFileBytes` belge sınırından büyük |
| 14011 | 400 | `CONNECTOR_PAGE_INVALID` | `page` / `size` aralık dışı |
| 14012 | 400 | `CONNECTOR_SETTINGS_INVALID` | alanlar türe uymuyor (SMB'de `host`/`share`/`username` eksik ya da `confluence` var; Confluence'ta SMB alanı var, Data Center'da `username` var, Cloud'da e-posta yok; SharePoint'te SMB/Confluence alanı var ya da tenant yok) |
| 14013 | 400 | `CONNECTOR_EXTERNAL_EGRESS_DISABLED` | Cloud ya da SharePoint host'u (SharePoint'te Graph ve Entra da) kurulumun dış bağlantı listesinde değil, dış bağlantı kapalı ya da sıkı local mod |
| 14014 | 400 | `CONNECTOR_URL_INVALID` | `baseUrl` ya da SharePoint `siteUrl`/`siteId` geçersiz: https değil, kullanıcı bilgisi, sorgu, `#`, `..` içeriyor (SharePoint: port ya da kodlanmış karakter; adres ve kimlik farklı host) |
| 14015 | 409 | `CONNECTOR_TYPE_IMMUTABLE` | `PUT` türü değiştirmeye çalıştı |
| 90001 vb. | 400 | doğrulama | alan biçimi (`credentialRef` `SECRET_CONNECTOR_*` değil, `share` eğik çizgi içeriyor …) |

## Örnek akış

1. `POST /v1/collections` + `POST /v1/collections/{id}/grants` (ADR-0028) ile alanı ve okuyucularını hazırla.
2. `POST /v1/admin/connectors` → `201`, `health: NEVER_SYNCED`.
3. Bir yoklama aralığı (30 sn) içinde ilk koşu başlar. `GET /v1/admin/connectors/{id}/runs` → `SUCCEEDED`, `added: 42`; `health: OK`.
4. Hatalı dosyalar: `GET …/items?state=FAILED`.
5. Paylaşım yeniden adlandırıldıysa koşu `FAILED/PATH_NOT_FOUND` olur ve hiçbir şey silinmez; `PUT` ile önek düzeltilir.
6. Bir klasör bilinçli olarak boşaltıldıysa koşu `PARTIAL/DELETION_SAFETY` olur ve `pendingDeletions` kaç belgenin bekletildiğini söyler; `POST …/sync` gövdesi `{"confirmMassDeletion": true}` ile tek koşu onaylanır.

### Confluence akışı

1. Kurulum: `SECRET_CONNECTOR_CONFLUENCE_TOKEN` dosyası, `deploy/compose.confluence.yaml`; Data Center için `VERSO_CONNECTOR_ALLOWED_NETWORKS`, Cloud için `VERSO_CONNECTOR_EXTERNAL_EGRESS=true` + `VERSO_CONNECTOR_EXTERNAL_HOSTS=*.atlassian.net` (sıkı local modda açılmaz; README).
2. `POST /v1/admin/connectors` (`type: CONFLUENCE`) → `201`. İlk koşu tam listedir ("FULL"): bütün sayfalar, her birinin kısıtı okunur.
3. Sonraki koşular "INCREMENTAL": yalnız imleçten (son uygulanan "son değişiklik") bu yana değişen sayfalar. Silinen, arşivlenen ya da sonradan kısıtlanan sayfa ancak günde bir yapılan tam listede görülür (`verso.connector.confluence.full-listing-interval`, 24 saat).
4. `GET …/items?state=SKIPPED` → `failureCode: RESTRICTED` olan sayfalar kısıt yüzünden alınmadı.

### SharePoint Online / OneDrive akışı

1. Kurulum (README "SharePoint Online / OneDrive bağlayıcısı"): Entra'da bağlayıcıya ayrı uygulama kaydı, sertifika, Graph uygulama izni `Sites.Selected` + yönetici onayı + sitenin `read` izni; `SECRET_CONNECTOR_SHAREPOINT_CERT`, `deploy/compose.sharepoint.yaml`, bulut modu, `VERSO_CONNECTOR_EXTERNAL_EGRESS=true`, `VERSO_CONNECTOR_EXTERNAL_HOSTS=graph.microsoft.com,login.microsoftonline.com,<kiracı>.sharepoint.com`.
2. `POST /v1/admin/connectors` (`type: SHAREPOINT`) → `201`. İlk koşu "FULL": kitaplığın bütün öğeleri delta'dan; indirilen her dosyanın izni okunur.
3. Sonraki koşular "INCREMENTAL": saklanan delta bağlantısından yalnız değişenler; silinen ya da kapsamdan çıkan dosya **bu koşuda** silinir. Günde bir tam liste (`verso.connector.sharepoint.full-listing-interval`) bütün dosyaların iznini yeniden okur (sonradan farklılaşan izin) ve yokluktan siler. Delta bağlantısının süresi dolduysa (410) koşu kendiliğinden "FULL" olur.
4. `GET …/items?state=SKIPPED` → `failureCode: RESTRICTED` olan dosyalar kitaplıktan farklı izinleri yüzünden alınmadı; `REDIRECT_REFUSED` olanlar izinli olmayan bir host'a yönlendirildi.
