# API istemcisi dosya paylaşımı bağlayıcısı entegrasyonu [v1]

Verso bir SMB (Windows / Samba) paylaşımındaki klasörü bir bilgi alanına eşitler: yeni ve değişen dosyalar belge olarak alınır, kaynakta silinen dosya Verso'da bütün türevleriyle silinir. `verso-admin` rolündeki yönetici bağlayıcıları oluşturur, değiştirir, siler, eşitlemeyi tetikler ve koşuları izler. Eşitlenen belgeleri, hedef bilgi alanına erişimi olan herkes okur (ADR-0028).

> **OpenAPI doğrulanmadı:** uçlar ve alanlar `AdminConnectorController`, `ConnectorAdminServiceImpl` ve `connector-api` DTO'larından çıkarıldı (ADR-0004). Generated client paketi: yok.

Bu doküman **24 Ekim 2026** tarihli `feature/doguyaras-smb-baglayici` branch'indeki koda (taban `develop` `86e73d1`) dayanır. Karar: [ADR-0038](adr/0038-smb-baglayicisi.md).

## Değişikliklerin özeti
- Yeni yönetici uçları: `/v1/admin/connectors` altında oluşturma, liste, okuma, değiştirme, silme, eşitleme isteği, koşu geçmişi ve izlenen dosyalar.
- Yeni hata kodları: 14001–14011 (`connector` bloğu).
- Yeni denetim eylemleri: `CONNECTOR_CREATED`, `CONNECTOR_UPDATED`, `CONNECTOR_DELETED`, `CONNECTOR_SYNC_REQUESTED`, `CONNECTOR_SYNC_STARTED`, `CONNECTOR_SYNC_FINISHED`, `CONNECTOR_DOCUMENT_ADDED`, `CONNECTOR_DOCUMENT_DELETED`; yeni hedef türü `CONNECTOR`. `GET /v1/admin/audit` istemcisi bilinmeyen eylemi ham göstermelidir (zaten kural). Eşitleme satırlarının aktörü `system:connector-sync`'tir ve rolü yoktur.
- Mevcut uçların sözleşmesi **değişmedi**. Bağlayıcı belgeleri `/v1/documents?collectionId=` listesinde sıradan bilgi alanı belgesi olarak görünür.
- Mobil için etki yok. Panelde "Yönetim → Bağlayıcılar" ekranı bu dokümana dayanır ([ADR-0042](adr/0042-baglayici-ekrani.md)); sözleşme ekran için değişmedi.

## Kurallar
- **Kritik:** Bütün uçlar yalnız `verso-admin` rolündedir (ADR-0019). Başka her kimlikli çağıran `403` (`90101 ACCESS_DENIED`; denetim kaydına `ACCESS_DENIED` / `ADMIN_CONNECTOR` olarak yazılır), kimliksiz `401` alır.
- **Kritik:** Parola hiçbir istekte ve yanıtta yoktur. `credentialRef` kurulumdaki bir secret dosyasının **adıdır** (`SECRET_CONNECTOR_` ile başlamalıdır), değeri değildir. İstemci parola alanı göstermez ve istemez.
- **Kritik:** Eşitlenen her dosyayı hedef alanın bütün okuyucuları görür; NTFS izinleri aktarılmaz. Ekran bunu alan seçiminin yanında söylemelidir.
- Bütün yanıtlar `Cache-Control: no-store, private`'tır. Başarı yanıtları ham, hatalar zarflıdır (ADR-0004). Bilinmeyen alanlar ve enum değerleri yok sayılır ya da ham gösterilir.
- `health`, son başarılı eşitlemenin zamanından türetilir; son koşunun durumu tek başına "sağlıklı" demek değildir. Ekran `health`'i göstermelidir.

## Uçlar

| Uç | Gövde | Başarı | Not |
|---|---|---|---|
| `POST /v1/admin/connectors` | `ConnectorRequest` | `201` + `Location` + `ConnectorResponse` | ilk eşitleme hemen vadededir |
| `GET /v1/admin/connectors?page=0&size=50` | – | `200` sayfa | ada göre; `size` 1–200, `page` 0–10 000 |
| `GET /v1/admin/connectors/{id}` | – | `200` `ConnectorResponse` | |
| `PUT /v1/admin/connectors/{id}` | `ConnectorRequest` (tam) | `200` | `collectionId` değiştirilemez; sonraki eşitleme hemen |
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

### `ConnectorResponse` (alanlar)

`id`, `name`, `host`, `port`, `share`, `pathPrefix` (normalize: `IK/Politikalar`), `domain`, `username`, `credentialRef`, `collectionId`, `includeGlobs`, `excludeGlobs`, `maxFileBytes`, `scheduleMinutes`, `enabled`, `health` (`OK`, `FAILING`, `STALE`, `NEVER_SYNCED`, `DISABLED`, `DELETING`), `lastSuccessAt`, `lastFailureAt`, `consecutiveFailures`, `lastErrorCode`, `nextRunAt`, `items` (`synced`, `failed`, `skipped`), `createdAt`, `updatedAt`. Zamanlar UTC ISO-8601.

### `SyncRunResponse`

`id`, `status` (`RUNNING`, `SUCCEEDED`, `PARTIAL`, `FAILED`, `ABANDONED`), `startedAt`, `finishedAt`, `errorCode`, `listingComplete`, `listed`, `added`, `updated`, `deleted`, `unchanged`, `failed`, `skipped`, `deferred`. Dosya adı yoktur.

- `PARTIAL`: kaynak listelendi ama bazı dosyalar başarısız (`FILES_FAILED`), sınıra ya da kuyruğa takıldı (`RUN_LIMIT`, `BACKLOG`; devamı birkaç dakika sonra), silmeler emniyetten bekletildi (`DELETION_SAFETY`), liste yarım kaldı (`DIRECTORY_UNREADABLE`, `LINK_NOT_FOLLOWED`, `LISTING_TOO_DEEP`, `LISTING_TOO_LARGE`) ya da uygulama kapandı (`INTERRUPTED`).
- `FAILED`: kaynağa ulaşılamadı; **hiçbir belge silinmez**. Kodlar: `EGRESS_DENIED`, `SOURCE_UNREACHABLE`, `SOURCE_AUTH_FAILED`, `CREDENTIAL_MISSING`, `SHARE_NOT_FOUND`, `PATH_NOT_FOUND`, `PATH_UNREADABLE`, `COLLECTION_MISSING`, `CONFIG_INVALID`, `SYNC_ERROR`.
- `ABANDONED` / `INSTANCE_STOPPED`: koşan instance durdu; iş başka bir koşuda kaldığı yerden, çift belge üretmeden tamamlandı.

### `ConnectorItemResponse`

`path` (önekten göreli), `state`, `failureCode` (`READ_FAILED`, `READ_TIMEOUT`, `ACCESS_DENIED`, `FILE_VANISHED`, `COLLECTION_FULL`, `TOO_LARGE`, `EMPTY`, `UNSUPPORTED`, `UNSAFE_LINK`), `attempts`, `nextAttemptAt`, `sizeBytes`, `modifiedAt`, `documentId`, `updatedAt`. Yollar kişisel veri içerebilir: yalnız yöneticiye gösterilir, URL'e ya da analitiğe konmaz.

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
| 90001 vb. | 400 | doğrulama | alan biçimi (`credentialRef` `SECRET_CONNECTOR_*` değil, `share` eğik çizgi içeriyor …) |

## Örnek akış

1. `POST /v1/collections` + `POST /v1/collections/{id}/grants` (ADR-0028) ile alanı ve okuyucularını hazırla.
2. `POST /v1/admin/connectors` → `201`, `health: NEVER_SYNCED`.
3. Bir yoklama aralığı (30 sn) içinde ilk koşu başlar. `GET /v1/admin/connectors/{id}/runs` → `SUCCEEDED`, `added: 42`; `health: OK`.
4. Hatalı dosyalar: `GET …/items?state=FAILED`.
5. Paylaşım yeniden adlandırıldıysa koşu `FAILED/PATH_NOT_FOUND` olur ve hiçbir şey silinmez; `PUT` ile önek düzeltilir.
6. Bir klasör bilinçli olarak boşaltıldıysa koşu `PARTIAL/DELETION_SAFETY` olur; `POST …/sync` gövdesi `{"confirmMassDeletion": true}` ile tek koşu onaylanır.
