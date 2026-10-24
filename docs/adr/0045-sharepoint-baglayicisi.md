# ADR-0045: SharePoint Online / OneDrive bağlayıcısı (Microsoft Graph, istemci kimlik bilgileri, driveItem/delta, farklı izinli dosya kuralı)

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-25
- **Karar verenler:** doguyaras (kurumsal yol haritası madde 5: "SMB, SharePoint/OneDrive, Confluence"); uygulama ayrıntıları bu ADR'de
- **İlgili eşik (referans Bölüm 24):** bağlayıcı başına `max-listed-entries` (100 000; SharePoint'te delta'daki dosya + klasör sayısı); tam enumerasyonun süresinin eşitleme aralığını aşması; tam listedeki dosya başına izin isteği.
- **Genişlettiği:** ADR-0038 (bağlayıcı çerçevesi, %20 silme emniyeti, kira), ADR-0044 (dış bağlantı kuralı, `pendingDeletions`, kısıt kuralı), ADR-0042 (ekran), ADR-0039 (denetim outbox'ı), ADR-0034 (eksiksiz silme), ADR-0041 (Entra kiracısı), llm-rules 1.1 ve 1.7

> Numara: `develop`'ta son ADR 0044; 0043 başka bir dalda (reranker) alınabilir, bu yüzden 0045.

## Bağlam

- Kurumların belgeleri çoğunlukla SharePoint Online kitaplıklarında ve OneDrive'dadır (araştırma `enterprise_features.md` madde 5). Araştırma bulguları: Graph `driveItem/delta` `@odata.nextLink` / `@odata.deltaLink` ile sayfalanır, silinen öğe `deleted` facet'iyle gelir, `410 Gone` tam yeniden eşitleme ister, öğeler **id** ile izlenir; `Prefer: hierarchicalsharing` ve `deltashowsharingchanges` ile izin değişimleri işaretlenir ama `Sites.FullControl.All` ister; izin aynası (Onyx örneği) geniş dizin izinleri ister.
- **Aşırı paylaşım alıcıların bir numaralı kaygısıdır** (`global_products_english.md`): Copilot "mevcut izinleri yansıtır", suçlu kırık miras, EEEU grupları ve süresiz paylaşım bağlantılarıdır; iş açısından kritik verinin %16'sı aşırı paylaşılmış; lisanslar aşırı paylaşım yüzünden geri alındı. Çıkarım: izin denetimi bir kurulum adımıdır.
- SharePoint Online yalnız buluttadır. Local modun "internete çıkış yok" vaadi (llm-rules 1.1, ZG, istisna yok) değişmez; ADR-0044'ün dış bağlantı kuralı (bulut modu + `VERSO_CONNECTOR_EXTERNAL_EGRESS` + host listesi) burada da geçerlidir.
- Entra kiracısı ADR-0041'de oturum açma için yapılandırılmıştır (`OIDC_ENTRA_TENANT_ID`); bağlayıcı ise kullanıcı adına değil, **kendi uygulama kaydıyla** (istemci kimlik bilgileri) okur.

## Seçenekler

| Konu | Seçenek | Karar |
|---|---|---|
| Çerçeve | A. **ADR-0038'in `FileSource` sözleşmesi** · B. Ayrı motor | A: planlayıcı, öğe durumu, koşu geçmişi, kira, silme emniyeti, denetim, metrik ve panel aynen çalışır |
| İstemci | A. **JDK/Boot HTTP istemcisi + Nimbus JOSE+JWT** (Spring Security'den zaten sınıf yolunda) · B. MSAL4J + Graph SDK | A: yeni bağımlılık yok; adres filtresi (`InetAddressFilter`), yönlendirme kuralı ve yanıt sınırı Confluence'la aynı yerde. B büyük bir bağımlılık ağacı getirir, yönlendirmeyi kendisi izler ve egress kuralını dışarıda bırakırdı |
| Kimlik | A. **İstemci kimlik bilgileri; sertifika (private_key_jwt) önerilen, istemci gizlisi desteklenir** · B. Kullanıcı adına (delegated) | A: eşitleme arka plan işidir, oturum yoktur. Sertifikada Entra yalnız açık anahtarı bilir; gizli tekrar oynatılabilir paylaşılan bir paroladır |
| Yetki | A. **`Sites.Selected` + yöneticinin site başına `read` izni** · B. `Sites.Read.All` · C. `Sites.FullControl.All` | A: en az yetki; uygulama yalnız açıkça verilen siteyi görür. B bütün siteleri okur (belgelenir, önerilmez). C yalnız izin aynası için gerekir (v2) |
| Değişiklik takibi | A. **`/drives/{d}/root/delta` + saklanan deltaLink** · B. Her koşuda tam gezinme · C. Webhook | A: yalnız değişen gelir; silme `deleted` facet'iyle açıkça gelir. C kamuya açık bir uç ister (yok) |
| İzin sinyali (v1 güvenlik kuralı) | A. **Dosyanın izin listesi: miras almayan ya da kök dışındaki bir atadan miras alan izin varsa "farklı"** · B. `shared` facet + `Prefer: hierarchicalsharing` · C. İzinleri yok saymak · D. İzin aynası | A: `Sites.Selected` ile okunabilen en ucuz güvenilir sinyal (dosya başına bir istek). B `Sites.FullControl.All` olmadan güvenilir değil ve kırık mirası değil paylaşım bağlantısını raporlar. C aşırı paylaşımı Verso'ya taşır. D v2 |
| Klasör yolu | A. **Klasör ağacını saklamak (`connector.sharepoint_folder`)** · B. Değişen her dosya için yol isteği | A: delta `parentReference.path` döndürmez ve yeniden adlandırılan klasörün çocuklarını raporlamaz; ağaç saklanınca bilinen bir klasörün değişmesi fark edilir ve kitaplık baştan listelenir |
| Egress | A. **ADR-0044 kuralı aynen: bulut modu + dış bağlantı + host listesi; local modda açılış reddi** · B. Local modda kayıtlı istisna | A: llm-rules 1.1'in istisnası yoktur |
| Ulusal bulutlar | A. **Kapsam dışı (yalnız küresel uçlar)** · B. Yapılandırılabilir uçlar | A: `graph-url` ve `authority` https zorunluyken değiştirilemez; yalnız test sahtesi değiştirir |

## Karar

### 1. Model ve şema (`connector` şeması, `V3__sharepoint.sql`)

- `connector.connector.type` `SMB | CONFLUENCE | SHAREPOINT` (CHECK genişledi, `-- contract:` gerekçesiyle). SharePoint ayarları: `base_url` (site adresi, Confluence'la ortak sütun), `site_id` (Graph site kimliği, isteğe bağlı), `drive_names` (kitaplık adları ya da sürücü kimlikleri, en çok 10; boş: varsayılan kitaplık), `tenant_id`, `client_id` (GUID), `auth_method` (`CERTIFICATE | CLIENT_SECRET`); klasör `path_prefix`, filtreler `include_globs`/`exclude_globs`, v1 kuralı `skip_restricted` (ortak sütunlar). Gizli hiçbir değer saklanmaz: `credential_ref` `SECRET_CONNECTOR_*` dosyasının adıdır.
- `connector.sharepoint_drive(connector_id, drive_id, root_id, delta_link)`: kitaplık başına devam bağlantısı (opak Graph durumu, kimlik bilgisi değil, en çok 8192 karakter). `connector.sharepoint_folder(connector_id, drive_id, item_id, parent_id, name)`: klasör ağacı (adlar yalnız yol hesabı için; log'a ve API'ye girmez). İkisi de bağlayıcıyla cascade silinir.
- Öğe yolu kimliktir: `drives/<drive id>/items/<item id>`. Parmak izi: cTag'in (yoksa eTag'in) 64 bit özeti, boyut, son değişiklik zamanı; içerik ayrıca SHA-256 ile karşılaştırılır (dokunulmuş ama aynı dosya yeniden alınmaz). Dosya adı yalnız bellekte, belge adı olarak kullanılır.

### 2. Kimlik ve Entra

- Token: `POST <authority>/<tenant>/oauth2/v2.0/token`, `grant_type=client_credentials`, `scope=https://graph.microsoft.com/.default`. Sertifikada istemci beyanı (RFC 7523): başlık `alg` RS256 (ayarla PS256), `typ` JWT, `x5t#S256` ve `x5t`; iddialar `aud` = token ucu, `iss` = `sub` = istemci kimliği, rastgele `jti`, `nbf` = `iat` = şimdi, `exp` = +5 dk; imza Nimbus `RSASSASigner`. Gizli dosya PKCS#8 özel anahtar (RSA ≥ 2048) ve sertifikayı PEM olarak tutar; anahtar sertifikaya ait değilse `CREDENTIAL_INVALID`, sertifika geçerli değilse `CREDENTIAL_EXPIRED`.
- Tenant: bağlayıcı isteğinde yoksa kurulumun Entra kiracısı (`OIDC_ENTRA_TENANT_ID`, ADR-0041); ikisi de yoksa `400 14012`. Uygulama kaydı oturum açma uygulamasından **ayrıdır**.
- Token koşu boyunca bellekte durur, bitimine 5 dk kala ya da bir 401'den sonra bir kez yenilenir; ikinci 401 `SOURCE_AUTH_FAILED`. 400/401/403 token yanıtı `SOURCE_AUTH_FAILED`. Token, gizli ve anahtar hiçbir log'a, metriğe, denetim kaydına, veritabanına ve yanıta girmez; koşu sonunda gizli `char[]` sıfırlanır.
- **Önerilen yetki — `Sites.Selected`:** uygulamaya Microsoft Graph **uygulama** izni `Sites.Selected` verilir, yönetici onayı alınır, sonra yalnız eşitlenecek siteye `read` rolü tanımlanır (README'de Graph PowerShell adımları: `New-MgSitePermission -SiteId … -BodyParameter @{roles=@("read"); grantedToIdentities=@(@{application=@{id=…; displayName=…}})}`). `Sites.Read.All` da çalışır ama kiracının bütün sitelerini okur; belgelenir, önerilmez.

### 3. Eşitleme

- **Çözümleme:** site (`/sites/{host}:{yol}` ya da `/sites/{id}`), kitaplıklar (`/sites/{id}/drive` ya da `/drives` içinde ad/kimlik eşleşmesi), kök kimliği (`/drives/{d}/root`). Bulunamayan site `SITE_NOT_FOUND`, kitaplık `DRIVE_NOT_FOUND` (ikisi de çoğunlukla `Sites.Selected` izninin verilmediğini gösterir).
- **Tam liste:** bağlantısız `/drives/{d}/root/delta?$select=…&$top=200`: bütün öğeler; sayfalama yalnız Graph'ın kendi origin'indeki `/v1.0/drives/` ya da `/v1.0/sites/` yollarına giden `@odata.nextLink` ile; tur `@odata.deltaLink` ile biter. Silme yalnız **eksiksiz tam listede**, yokluktan; %20 ve 20'den fazla silme bekletilir (`PARTIAL/DELETION_SAFETY`, `pendingDeletions` kesin sayı, onay sonraki koşuyu tam liste yapar).
- **Artımlı:** saklanan deltaLink'ten: yalnız değişenler. Silme `deleted` facet'inden ve kapsamdan çıkan (taşınan, filtreye uymayan) dosyalardan gelir; yalnız tur eksiksizse ve **aynı silme emniyetinden** geçerek uygulanır. Aynı öğe bir turda birden çok gelirse son hâli geçerlidir.
- **410 Gone:** devam bağlantısının süresi dolmuştur; aynı koşu kitaplıkları baştan listeler (izin taramasıyla) ve "yalnız eksiksiz listeden sonra sil" kuralı ve %20 emniyeti geçerlidir.
- **Klasör değişikliği:** artımlı turda bilinen bir klasör yeniden adlandırılır, taşınır ya da silinirse (çocukları tek tek raporlanmaz) ya da bir dosyanın üst klasörü bilinmiyorsa tur bırakılır ve kitaplık baştan listelenir.
- **Devam bağlantısı (checkpoint):** delta yarıda bırakılamaz; bağlantı ve klasör değişiklikleri yalnız koşu listelediği her değişikliği uyguladıysa (başarısız, ertelenen, kuyruk/sınır yüzünden kalan ya da bekletilen silme yoksa), koşunun sonucuyla **aynı transaction'da** ve yalnız kira sahibince yazılır. Aksi hâlde sonraki koşu aynı delta'yı önceki bağlantıdan yeniden oynatır (Graph buna izin verir; değişmemiş öğe indirilmez, idempotent).
- **Hız sınırı:** 429, 503 ve 504 `Retry-After`'a (saniye ya da HTTP tarihi) uyarak, yoksa 1 sn · 2^(n−1) ve eşit jitter'la en çok 5 deneme; 60 sn'den uzun bekleme istenirse ya da denemeler biterse koşu `RATE_LIMITED`/`SOURCE_UNAVAILABLE` ile durur ve bağlayıcı bütün olarak geri çekilir. Sayfa sayfa gidildiği için her sayfa ayrı korunur; bekleme sırasında kira yenilenir. Token isteği de aynı kurala tabidir.
- **İndirme:** `/drives/{d}/items/{i}/content`; Graph'ın 302'si yalnız https, port'suz, kullanıcı bilgisiz ve hem `verso.connector.sharepoint.download-hosts` (`*.sharepoint.com`) hem dış bağlantı listesinde olan bir host'a izlenir, **Authorization başlığı olmadan** (URL önceden yetkilidir). Başka host'a yönlendirme `SKIPPED/REDIRECT_REFUSED`, ikinci yönlendirme de aynı.
- **Biçimler:** ADR-0016 (PDF, DOCX, TXT, MD); docling açıksa PDF yolu ADR-0032'deki gibi. Kapsam: `pathPrefix` (büyük/küçük harf duyarsız, SharePoint gibi), `includeGlobs`/`excludeGlobs` klasöre göre göreli yolda.

### 4. İzinler (v1) ve aşırı paylaşım

- Belgeler hedef bilgi alanına girer; okuma yetkisini alanın grant'ları verir (ADR-0028). SharePoint izinleri **aynalanmaz**.
- **v1 güvenlik kuralı (varsayılan, `skipUniquePermissions: true`):** dosyanın `GET /drives/{d}/items/{i}/permissions` listesinde `inheritedFrom` taşımayan (dosyaya doğrudan verilmiş: kırık miras, doğrudan yetki, paylaşım bağlantısı) ya da kitaplık kökü dışındaki bir atadan miras alınan (benzersiz izinli bir klasör) bir izin varsa dosya **alınmaz** (`SKIPPED/RESTRICTED`); önceden alınmış sürümü belgesiyle silinir (denetim ayrıntısı `SOURCE_RESTRICTED`). Boş ya da okunamayan liste "bilinmiyor"dur: tam taramada dosya listeden çıkar ve liste eksik olur (`RESTRICTION_CHECK_FAILED`, silme yok), indirmede dosya alınmaz ve sonra yeniden denenir (kapalı başarısızlık).
- **Ne zaman bakılır:** indirilecek her dosyada (yeni, değişen, yeniden denenen) ve periyodik tam listede (günde bir, `full-listing-interval`) listelenen her dosyada (tarama). Delta yalnız izin değişikliğini (`Sites.FullControl.All` olmadan) raporlamaz; bu yüzden sonradan farklılaşan izin **en geç bir tam liste aralığında** (24 sa) yansır.
- **Sınırlar (açıkça):** (1) kitaplığın kendi izinleri (site üyeleri, "Herkes", EEEU) karşılaştırılmaz: kitaplıkta geniş açık her dosya alanın okuyucularına da açılır; kitaplık alanın okuyucu kitlesine göre seçilir ve **kurulumdan önce izin denetimi yapılır**; (2) paylaşım bağlantısı erişimi daraltmasa da (genişletse de) dosya alınmaz (muhafazakâr); (3) dosya başına bir istek: büyük kitaplıkta tam tarama istek sayısı dosya sayısı kadardır; (4) `inheritedFrom`'un `Sites.Selected` izniyle döndüğü ve kökten mirasın kök kimliğiyle raporlandığı gerçek kiracıda doğrulanmalıdır (doğrulanmazsa her dosya "farklı" sayılır ve hiçbir şey alınmaz: güvenli yönde başarısızlık).
- `skipUniquePermissions: false` ayarlanabilir; panel kırmızı uyarıyla "aşırı paylaşım" der.

### 5. Egress

- Yalnız bulut: (1) `VERSO_CONNECTOR_EXTERNAL_EGRESS=true`, (2) `VERSO_CONNECTOR_EXTERNAL_HOSTS` içinde `graph.microsoft.com`, `login.microsoftonline.com` ve kiracının SharePoint host'u (ya da `*.sharepoint.com`; OneDrive için `<kiracı>-my.sharepoint.com`), (3) bulut modu. Strict local mod: dış bağlantı açıksa uygulama **açılmaz** (`ConnectorEgressConfiguration`, mesaj SharePoint'i de anar); API (`400 14013`) ve her koşu (`FAILED/EGRESS_DENIED`, hiçbir istek atılmadan) kuralı yeniden uygular.
- Graph ve Entra adlarının çözülen bütün adresleri genel olmalıdır ve bağlantı filtresi yalnız genel adreslere izin verir. Site host'u `download-hosts` listesine de uymalıdır.
- **Ulusal bulutlar** (ABD kamu, 21Vianet) kapsam dışıdır: `graph-url` ve `authority` https zorunluyken küresel uçlardan başka değer alamaz (uygulama açılmaz). `require-https=false` yalnız test sahtesi içindir ve o zaman uçlar özel izin listesindeki bir IP'de olabilir.
- `deploy/compose.sharepoint.yaml`: `sharepoint` köprü ağı (`verso-spo`, `VERSO_SHAREPOINT_SUBNET`) ve `SECRET_CONNECTOR_SHAREPOINT_CERT`; güvenlik duvarında yalnız 443. Microsoft 365 adresleri değiştiği için FQDN farkında bir proxy daha sıkı seçenektir.
- **Kalan risk:** `verso-admin` kendi kiracısındaki bir siteyi ya da `Sites.Read.All` verilmiş bir uygulamayla başka siteleri seçebilir; önlem `Sites.Selected` (uygulama yalnız izin verilen siteyi görür), dar host listesi (joker yerine kiracının tam adı) ve denetim kaydı (`CONNECTOR_UPDATED`).

### 6. Belge içeriği ve model (llm-rules)

- SharePoint dosyası, yüklenen belge gibi güvenilmeyen içeriktir: aynı hattan, aynı parçalama ve prompt kurallarıyla (belge metni talimat değildir, pasajlar ayraçlı, atıflar sunucuda doğrulanır; llm-rules 3). Kitaplıkta düzenleme yetkisi olan herkes alanın içeriğini (dolayısıyla pasajları) yazabilir hâle gelir; yönetici yalnız güvendiği yazar kitlesinin kitaplıklarını eşitler.
- Log, metrik ve denetim yalnız id, sayı ve sabit kod taşır; site yolu, kitaplık, klasör ve dosya adı, token ve delta bağlantısı hiçbirine girmez.

### 7. Uçlar ve sözleşme (ekleyici)

- `ConnectorType.SHAREPOINT`; `ConnectorRequest.sharePoint` (`SharePointRequest`: `siteUrl`/`siteId`, `drives`, `tenantId`, `clientId`, `authMethod`, `skipUniquePermissions`), `ConnectorResponse.sharePoint` (`SharePointResponse`, `lastFullListingAt`). Klasör `pathPrefix`, filtreler globlar. SMB ve Confluence istemcileri değişmeden çalışır (eski kurucular korunur). **Yeni hata kodu yok:** `14012` (alan/tür uyuşmazlığı, tenant yok), `14013` (dış bağlantı ya da host izni yok, local mod), `14014` (site adresi/kimliği geçersiz), `14003` (klasör), `14009` (glob). Ayrıntı: `docs/api-connectors-integration-v1.md`.
- Panel: tür seçicide "SharePoint Online / OneDrive", alanlar ve `Sites.Selected` yardım metni, farklı izinli dosya uyarısı.

### 8. Denetim, metrik, sıcak yol

- Denetim ve metrikler SMB/Confluence ile aynı (yeni eylem yok). Eşitleme asenkron worker'dır; soru-cevap yolu değişmedi. Uzak çağrılar bağlayıcının tek iş parçacığında (bulkhead), zaman sınırlı (bağlantı 10 sn, okuma 60 sn), sınırlı denemeli ve bağlayıcı düzeyinde geri çekilmeli (devre kesici eşdeğeri).

## Sonuçlar

- **Olumlu:** SharePoint/OneDrive kitaplıkları elle yükleme olmadan güncel kalır; silmeler delta'dan açıkça gelir, 410 kendini onarır; çerçevenin bütün emniyetleri geçerlidir. Kitaplıktan farklı izinli dosyalar varsayılan olarak hiç girmez. En az yetki (`Sites.Selected`) ve sertifika varsayılandır. Yeni bağımlılık yok.
- **Olumsuz / kabul edilen risk:**
  - **İzin aynası yok**; kitaplığın kendi geniş izinleri görülmez (bölüm 4); izin değişikliği en geç 24 sa'te yansır.
  - Tam taramada dosya başına bir izin isteği; büyük kitaplıkta 429 olasılığı artar (koşu durur, geri çekilir).
  - Kalıcı olarak başarısız bir dosya devam bağlantısını tutar (her koşu aynı delta'yı yeniden oynatır; maliyet artar, doğruluk değişmez; bağlantının süresi dolunca 410 ile tam liste).
  - Klasör adları `connector` şemasında saklanır (yalnız yol hesabı).
  - İlk eşitleme koşu sınırına (500 dosya / 2 GB) takılırsa her devam koşusu kitaplığı baştan listeler (izin taraması olmadan), ta ki bir koşu her şeyi uygulayana kadar.
- **Etkilenen dosyalar:** `connector-api` (`SharePointRequest`, `SharePointResponse`, `SharePointAuthMethod`, `ConnectorType`, `ConnectorRequest`, `ConnectorResponse`); `connector-core` (`sharepoint/*`, `domain/{SharePointSite,DriveTree,DeltaCheckpoint}`, `smb/FileSource` (`deleted`, `checkpoint`), `worker/SyncRunner`, `repository/{ConnectorRow,ConnectorRepository,SharePointStateRepository}`, `service/*`, `config/{ConnectorProperties,ConnectorEgressConfiguration}`, `V3__sharepoint.sql`); `config/verso.yml`, `.env.example`, `deploy/prod.env.example`, `deploy/compose.sharepoint.yaml`, `scripts/dev-secrets.sh`, `secrets/README.md`; panel `connectors.js`, `screens/connectors.js`, `scripts/panel.test.mjs`; testler `SharePointSourceTest`, `SharePointRulesTest`, `SyncRunnerTest`, `ConnectorArchitectureTest`, `ConnectorApiContractTest`, CI'da sahte Graph ile `SharePointSyncTest` ve `SharePointLocalModeTest`, `ComposeConfigTest`; dokümanlar `README.md`, `docs/api-connectors-integration-v1.md`, `docs/decisions.md`, `docs/versions.md`, `docs/uyum/01-vked-sablonu.md`, `docs/uyum/05-egress-kaniti.md`, `docs/ai/llm-rules.md` (1.7), `docs/evidence/adr-0045-dogrulama.md`.
- **Rollout (referans 18.4):** migration ekleyicidir (yeni sütunlar boş/varsayılanlı, yeni tablolar boş); önce migration, sonra uygulama. Eski uygulama yeni sütunları okumaz; SHAREPOINT satırı yoksa davranışı aynıdır. Panelin eski sürümü SharePoint bağlayıcısını "SHAREPOINT" türüyle ham gösterir.
- **Geri alma yolu:** SharePoint bağlayıcılarını API ile sil (belgeleri de gider) ya da `enabled: false`; `VERSO_CONNECTOR_EXTERNAL_EGRESS=false`; overlay'i kapat; Entra'da uygulamanın site iznini (`Remove-MgSitePermission`) ve sertifikasını kaldır. `V3` geri alınmaz; uygulama geri alınırsa SHAREPOINT satırları claim edilip `CONFIG_INVALID`/tanınmayan türle başarısız olmasın diye önce silinir.

## Yeniden değerlendirme koşulu

- İzin aynası talebi (SharePoint grupları/Entra grupları → `document_principal`): ayrı ADR (v2), SMB ACL ve Confluence kısıt aynasıyla birlikte; `Sites.FullControl.All` ve dizin okuma izinleri gerekir.
- İzin değişikliğinin daha hızlı yansıması gerekirse: `full-listing-interval` kısaltılır ya da `Prefer: deltashowsharingchanges` (FullControl) değerlendirilir.
- Ulusal bulut talebi: uç ve host listesi ayrı ADR.
- Gerçek kiracıda `inheritedFrom` davranışı beklenenden farklıysa: izin sinyali (bölüm 4) yeniden seçilir.
