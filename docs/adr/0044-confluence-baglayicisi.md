# ADR-0044: Confluence bağlayıcısı (Data Center/Server ve Cloud, REST, kısıtlı sayfa kuralı, bağlayıcı dış bağlantısı, kesin bekleyen silme sayısı)

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras (kurumsal yol haritası madde 5: "SMB, SharePoint/OneDrive, Confluence"); uygulama ayrıntıları bu ADR'de
- **İlgili eşik (referans Bölüm 24):** bağlayıcı başına `max-listed-entries` (100 000; Confluence'ta sayfa + ek sayısı); tam listenin süresinin eşitleme aralığını aşması.
- **Genişlettiği:** ADR-0038 (bağlayıcı modülü: ayar, eşitleme, öğe durumu, koşu geçmişi, kilit, iç ağ egress'i, %20 silme emniyeti), ADR-0039 (denetim outbox'ı), ADR-0034 (eksiksiz silme), ADR-0042 (bağlayıcı ekranı; "kesin bekleyen silme sayısı" açık noktası), ADR-0013 ve llm-rules 1.1 (egress)

> Numara: `develop`'ta son ADR 0042; 0043 başka bir dalda (reranker) alınabilir, çakışmamak için 0044.

## Bağlam

- Yaygınlaştırmada kurumların bilgisi çoğunlukla Confluence'tadır (araştırma `enterprise_features.md` madde 5). ADR-0038 bağlayıcı çerçevesini SMB ile kurdu ve "Confluence internet egress'i ve ayrı bir ADR ister" dedi.
- **İzin bulgusu:** Confluence'ın `lastmodified` CQL alanı izin değişikliğini yakalamaz: bir sayfaya kısıt konması sürüm ya da tarih oynatmaz, Atlassian bunu "Won't Fix" olarak kapattı ([CONFCLOUD-41375](https://jira.atlassian.com/browse/CONFCLOUD-41375)). Kısıtlar sayfa başına ayrı okunur; muhafazakâr yaklaşım sayfa ve üst sayfa (ata) kısıtlarının birlikte değerlendirilmesidir (araştırma notu, Vectara notu). Görüntüleme kısıtları atalardan kalıtılır.
- **Egress:** Data Center/Server kurum içidir (SMB ile aynı iç ağ kuralı yeter). Cloud (`*.atlassian.net`) internettir; local modun "internete çıkış yok" vaadiyle (llm-rules 1.1, ZG, "istisna yok") çatışır.
- **ADR-0042'nin açık noktası:** ekran bekleyen silmeyi son koşunun `deferred` sayısından "en çok" diye gösteriyordu; kesin sayı API'de yoktu ve silmeler bekletildiği hâlde `errorCode` başka bir sorun olabiliyordu (onay kutusu görünmüyordu).
- Atlassian'ın kendi MCP sunucusu (`mcp.atlassian.com/v2`, kullanıcı başına OAuth) araştırmada istemci tarafı entegrasyon için önerilir (`mcp.md`, faz 2); bu ADR onun yerine geçmez: burada amaç Confluence içeriğini bir bilgi alanına **indekslemek**, kullanıcı adına canlı araç çağrısı değil.

## Seçenekler

| Konu | Seçenek | Karar |
|---|---|---|
| Çerçeve | A. **ADR-0038'in `FileSource` sözleşmesi** (sayfa = dosya, kimlikten türeyen yol) · B. Ayrı bir senkron motoru | A: planlayıcı, öğe durumu, koşu geçmişi, kira, silme emniyeti, denetim, metrik ve panel olduğu gibi çalışır; fark kaynak sınıfındadır |
| API | A. **REST v1 (`/rest/api/content/search` CQL, `…/restriction/byOperation/read`, `?expand=body.storage`)** · B. Cloud v2 REST | A: Data Center/Server ve Cloud'da aynı uçlar; v2 yalnız Cloud'dadır ve CQL araması yoktur. Cloud v1 kullanımdan kalkarsa yeniden değerlendirilir |
| İçerik | A. **Storage format → Markdown (jsoup 1.23.2, XML kipi)** · B. `body.view` HTML · C. Export (PDF/Word) | A: başlıklar `#`, tablolar GitHub tablosu kalır (parçalayıcı başlıklara göre böler, llm-rules 6.3); makrolar kapalı listeyle. B makroları sunucuda çalıştırır (dış içerik, include), C ağır ve sürüme bağlı |
| HTML ayrıştırıcı | A. **jsoup (MIT)** · B. JDK XML ayrıştırıcı · C. Elle | A: storage formatı HTML adlı varlıklar (`&nbsp;`) içerir, XML ayrıştırıcıda tanımsızdır; jsoup XML kipi CDATA'yı (kod blokları) ve varlıkları birlikte işler. 1.23.2, GHSA-65r4-943x-97jj'nin (XmlTreeBuilder, HIGH) düzeltildiği sürümdür |
| İzinler (v1) | A. Sayfa kısıtlarını aynalamak · B. **Kısıtlı sayfayı hiç almamak (varsayılan, ayarlanabilir)** · C. Kısıtları yok saymak | B: belgeler hedef alana girer ve alanın grant'ları uygulanır (ADR-0028); aynalama (A) ayrı bir iş ve v2'dir; C kısıtlı bir İK sayfasını bütün şirkete açar |
| Kısıt değişikliği | A. **Günlük tam liste + her sayfanın (ve atalarının) kısıt okuması** · B. Her koşuda bütün sayfaların kısıtı | A: `lastmodified` kısıtı görmez; tam liste silinen ve arşivlenen sayfaları da bulur. B, büyük wikide her 15 dakikada binlerce istek demektir |
| Cloud egress | A. Hep kapalı · B. **Açık ayar + host listesi, yalnız bulut modunda; local modda açılış reddi** · C. Local modda kayıtlı istisna · D. Serbest | B: local mod sıkıdır, llm-rules 1.1'in istisnası yoktur; C bir güvenceyi tercihe çevirirdi. Bulut modunda uygulamanın internete yolu zaten vardır (ADR-0013) |

## Karar

### 1. Model ve şema (`connector` şeması, `V2__confluence.sql`)

- `connector.connector.type` `SMB | CONFLUENCE`. Confluence ayarları sütun olarak: `base_url`, `deployment` (`DATA_CENTER | CLOUD`), `space_keys`, `label`, `ancestor_id`, `include_attachments`, `skip_restricted` (varsayılan `true`), `page_size` (1–250, 50); eşitleme durumu: `sync_cursor` (uygulanmış en yeni "son değişiklik"), `full_listing_at` (son eksiksiz tam liste). SMB'ye özgü `share` ve `username` artık boş olabilir; CHECK'ler türe göre zorunluluğu veritabanında da tutar. `username` Cloud'da hesabın e-postasıdır (`+` için CHECK genişledi; SMB hesabı API'de yine `+`sız).
- `connector.sync_run` `pending_deletions` (kesin, bu migration'dan önceki koşularda `NULL`) ve `listing_mode` (`FULL | INCREMENTAL`).
- Öğe yolu kimliktir, başlık değildir: `pages/<id>`, `attachments/<sayfa id>/<ek id>`. Yeniden adlandırılan sayfa aynı öğedir. Parmak izi (id, sürüm numarası, sürüm zamanı); ekte (id, boyut, sürüm zamanı). Başlık yalnız bellekte, belge adı olarak (`<başlık>.md`) kullanılır.
- Belge alımı, silme, okuyucu satırları ve denetim satırları SMB ile aynı `DocumentIngestion` yolundan geçer (ADR-0038 bölüm 1, ADR-0034).

### 2. Eşitleme

- **Artımlı:** CQL `type = page AND space in ("A","B") [AND label = "x"] [AND ancestor = N] AND lastmodified >= now("-Nm") ORDER BY created ASC`. Pencere sunucunun saatine göredir (`now()`): mutlak tarih CQL'de API kullanıcısının saat diliminde okunur ve bağlayıcı onu bilmez. Pencere imleçten 30 dk (`cursor-overlap`) geri gider (arama indeksinin gecikmesi); iki kez görülen sayfa sürümünden tanınır ve indirilmez. Değeri istemci üretir, CQL dizgesi tırnak ve ters bölüyle kaçırılır; alan anahtarı ve etiket API'de zaten dar bir desenle sınırlıdır (iki çit, `CqlBuilder`).
- **Sayfalama:** `_links.next`, yalnız sitenin bağlamı altındaki `/rest/api/` yoluna gidiyorsa izlenir; başka yol ya da host listeyi "eksik" yapar (`LISTING_LINK_REFUSED`), silme olmaz.
- **İmleç:** koşu sonunda imleç, listede görülen en yeni değişikliğe ilerler, ama **sonraya kalan hiçbir sayfanın ötesine geçmez** (başarısız, bekleyen ya da sınır/kuyruk yüzünden ertelenen sayfanın zamanında durur): bir sonraki artımlı liste onu yeniden getirir. Ölümcül hata, kesinti ya da eksik listede imleç yerinde kalır. Ayarı değişen bağlayıcının imleci ve tam liste zamanı sıfırlanır.
- **Tam liste (günlük, `full-listing-interval` 24 sa):** imleç yoksa, süre dolduysa ya da yönetici bekleyen silmeyi onayladıysa koşu bütün sayfaları listeler. Silme yalnız **eksiksiz tam listede** olur; silinen ya da arşivlenen (aramada görünmeyen) sayfa böyle bulunur. Silme emniyeti SMB ile aynıdır: izlenenlerin %20'sinden **ve** 20'den fazlası silinecekse silme bekletilir, `PARTIAL/DELETION_SAFETY`, `pendingDeletions` kesin sayıyı verir; bekletilen silme varken tam liste zamanı kaydedilmez, onaylanan koşu yine tam listedir.
- **Hız sınırı:** 429 ve 503 `Retry-After`'a (saniye ya da HTTP tarihi) uyarak, yoksa 1 sn · 2^(n−1) ve eşit jitter'la en çok 5 deneme; 60 sn'den uzun bekleme istenirse ya da denemeler biterse koşu `RATE_LIMITED` / `SOURCE_UNAVAILABLE` ile durur ve bağlayıcı bütün olarak geri çekilir (ADR-0038: 1 dk · 2^(n−1), en çok aralık). Bekleme sırasında kira yenilenir.
- **İçerik:** sayfa `?expand=body.storage,version` ile okunur, Markdown'a çevrilir: başlık ilk `#` satırı, `h1`–`h6` aynı düzeyde, tablolar GitHub tablosu (ilk satır başlık, `|` kaçırılır, iç içe tablo düzleşir), listeler, görev listeleri, kod ve noformat makroları çitli blok (çit, içerikteki en uzun ters tırnak dizisinden uzun). Bilgi/not/uyarı/panel/expand makrolarının gövdesi alınır; makro parametreleri, `html`/`iframe`/`widget` makroları, script, style, resim, emoji ve yer tutucular **hiç** metne girmez. Görünmeyen karakterler (çift yön, sıfır genişlik, denetim) silinir. 100'den derin iç içelik düz metne indirgenir.
- **Ekler (isteğe bağlı, `includeAttachments`):** listelenen sayfaların PDF ve DOCX ekleri, aynı boyut sınırı ve belge hattının sınırlarıyla (ADR-0016). Ek, sayfasının kısıtına uyar. İndirme bağlantısı sitenin `/download/attachments/` yolunda olmalıdır. **Sınır:** Cloud ekleri medya sunucusuna (başka host) yönlendirilir; yönlendirme izlenmez, ek `SKIPPED/REDIRECT_REFUSED` olur (canlı doğrulama gerekir).

### 3. İzinler (v1) ve güvenlik kuralı

- Belgeler hedef bilgi alanına girer; okuma yetkisini alanın grant'ları verir (ADR-0028). Confluence alan ve sayfa izinleri **aynalanmaz**.
- **v1 güvenlik kuralı (varsayılan, `skipRestricted: true`):** listelenen her sayfanın kendi ve atalarının görüntüleme kısıtı `…/restriction/byOperation/read` ile okunur (ata sonuçları koşu içinde önbelleğe alınır). Bir kullanıcı ya da grup kısıtı olan sayfa **eşitlenmez** (`SKIPPED/RESTRICTED`); önceden eşitlenmiş sürümü belgesiyle birlikte silinir (denetim ayrıntısı `SOURCE_RESTRICTED`). Kısıtı kalkan sayfa bir sonraki tam listede alınır. Okunamayan kısıt sayfayı listeden çıkarır ve listeyi eksik yapar (`RESTRICTION_CHECK_FAILED`): kontrol edilmemiş sayfa alınmaz, onun yüzünden bir şey silinmez (fail closed).
- **Kısıt taraması:** `lastmodified` kısıtı görmediği için tam liste (günde bir) bütün sayfaların kısıtını yeniden okur; artımlı koşular yalnız değişen sayfalarınkini okur. Kısıtın konmasıyla belgenin silinmesi arasında en çok `full-listing-interval` (24 sa) geçebilir: kabul edilen risk, aralık kısaltılabilir.
- `skipRestricted: false` ayarlanabilir; panel bunu kırmızı uyarıyla gösterir: kısıtlı sayfa alanın bütün okuyucularına açılır.
- Alan izinleri: servis hesabının göremediği alan ve sayfa listelenmez; yönetici alanları bilgi alanının okuyucu kitlesine göre seçer (SMB'deki "alan başına bir bağlayıcı" kuralı). Yalnız okuma yetkili, yalnız eşitlenecek alanları gören bir hesap kullanılır.

### 4. Kimlik bilgisi

- Data Center/Server: kişisel erişim belirteci, `Authorization: Bearer`. Cloud: hesabın e-postası ve API belirteci, `Authorization: Basic`. Belirteç `SECRET_CONNECTOR_*` adlı secret dosyasındadır (`SECRET_CONNECTOR_CONFLUENCE_TOKEN`, `deploy/compose.confluence.yaml`), açılışta değil koşu başında config tree'den okunur, `char[]` sıfırlanır; veritabanına, log'a, metriğe, denetim kaydına ve yanıta girmez. Başlık değeri yalnız koşu boyunca istemci nesnesindedir.

### 5. Egress

- **Data Center/Server:** SMB ile aynı iç ağ izin listesi (`VERSO_CONNECTOR_ALLOWED_NETWORKS`, yalnız özel aralıklar). Site adının çözüldüğü **bütün** adresler listede olmalıdır; ayrıca HTTP istemcisinin bağlantı filtresi (Boot `InetAddressFilter`) gerçekten bağlanılan adresi yeniden denetler, ikinci bir DNS yanıtı listeden çıkamaz. Loopback, link-local ve metadata adresleri listede olsa da reddedilir. IP literal'i yönetici API'sinde de denetlenir (`14004`).
- **Cloud:** internet egress'i gerektirir ve **yalnız** şu üç koşul birlikte sağlanırsa kullanılır: (1) `verso.connector.external-egress.enabled=true` (`VERSO_CONNECTOR_EXTERNAL_EGRESS`), (2) host `allowed-hosts` listesinde (`VERSO_CONNECTOR_EXTERNAL_HOSTS`, tam ad ya da tek baştaki `*.`; `*`, `*.net` ve IP reddedilir), (3) uygulama **bulut modunda**. Local mod sıkıdır: `verso.ai.mode=local` (ya da tanınmayan değer) iken dış bağlantı açıksa uygulama **açılmaz** (`ConnectorEgressConfiguration`); çalışma anında da her yönetici değişikliği (`400 14013`) ve her koşu (`FAILED/EGRESS_DENIED`) aynı kuralı yeniden uygular. Çözülen bütün adresler genel (public) olmalıdır ve bağlantı filtresi yalnız genel adreslere izin verir: `*.atlassian.net`'e benzeyen bir ad iç ağa yönlenip bağlayıcıyı bir iç tarayıcıya çeviremez.
- **llm-rules ile ilişki:** 1.1 (local modda internet yok, istisna yok) değişmez; yeni 1.7 satırı bağlayıcı dış bağlantısını tanımlar: varsayılan kapalı, yalnız bulut modunda, yalnız listedeki host'lara ve genel adreslere, `/actuator/info`'da `connectors.externalEgress` ve `externalHosts` olarak görünür. VKED'de Atlassian yeni veri kaynağı ve yurt dışı aktarım olarak yazılır (`docs/uyum/01-vked-sablonu.md`, `05-egress-kaniti.md`): bağlayıcı Atlassian'a belirteç, CQL sorgusu (alan anahtarları, etiket) ve sayfa kimlikleri gönderir, sayfa içeriğini alır.
- **SSRF:** yönlendirme otomatik izlenmez; yalnız aynı şema, host, port ve bağlam altındaki yönlendirme (en çok 3) izlenir; başka host'a yönlendirme koşuyu durdurur (`REDIRECT_REFUSED`), belirteç o host'a hiç gitmez. Sunucunun döndürdüğü bağlantılar (`_links.next`, ek indirme) yalnız sitenin `/rest/api/` ve `/download/attachments/` yollarına çözülür; `..`, kodlanmış `.`/`/`/`\` ve başka host reddedilir. `baseUrl` https olmalıdır (kullanıcı bilgisi, sorgu, `#` yok; `14014`); `require-https=false` yalnız test sunucusu içindir.
- **Ağ:** `deploy/compose.confluence.yaml` uygulamaya sabit alt ağlı (`VERSO_CONFLUENCE_SUBNET`) ve `verso-cfl` arayüzlü bir köprü ağı ile belirtecin secret'ını ekler; host güvenlik duvarı Data Center için yalnız sunucunun 443'üne, Cloud için yalnız 443'e izin verir (SMB overlay'iyle aynı bilinçli sapma: `prove-local-mode.sh` 1. adımı bu ağı raporlar).
- **Kalan risk (güvenlik review B1):** belirteç, yöneticinin yazdığı `baseUrl`'e gider. `verso-admin` bir bağlayıcıyı izinli bloktaki kendi HTTPS sunucusuna ya da `*.atlassian.net` altındaki kendi Atlassian sitesine yöneltirse kurumun belirtecini (ya da e-posta + API belirtecini) ele geçirebilir; bu SMB'deki NTLM riskinin (ADR-0038 B2) daha ağır hâlidir, çünkü belirteç düz gider. Önlem: izin listeleri olabildiğince dar (Data Center sunucusu `/32`; Cloud'da joker yerine kurumun tam site adı, ör. `VERSO_CONNECTOR_EXTERNAL_HOSTS=kurum.atlassian.net`), belirteç yalnız okuma yetkili ve yalnız eşitlenecek alanları gören bir hesabın, süreli (Data Center PAT son kullanma tarihi) ve bağlayıcı değişiklikleri denetim kaydında (`CONNECTOR_UPDATED`). Belirteci bir host'a bağlamak (secret dosyasının adında host) v2'ye bırakıldı.

### 6. Sayfa içeriği ve model (llm-rules)

- Confluence sayfası kullanıcının yüklediği belge gibi güvenilmeyen içeriktir: aynı hattan geçer, aynı parçalama ve aynı prompt kuralları (belge metni talimat değildir) uygulanır. Dönüştürücü yalnız görünen metni taşır; gizli parametre, HTML makrosu ve görünmeyen karakter prompt'a ulaşmaz. Eşitlenen alanlarda düzenleme yetkisi olan herkes, bilgi alanının içeriğini (dolayısıyla pasajları) yazabilir hâle gelir: prompt enjeksiyonuna karşı korunma, pasajların ayraçlı ve "talimat değil" işaretli verilmesi ve atıfların sunucuda doğrulanmasıdır (llm-rules 3); yönetici yalnız güvendiği yazar kitlesinin alanlarını eşitler (LLM review notu).
- Log, metrik ve denetim yalnız id, sayı ve sabit kod taşır; sayfa başlığı, alan anahtarı, site adresi ve hesap hiçbirine girmez.

### 7. Uçlar ve sözleşme (ekleyici)

- `ConnectorRequest` + `type`, `confluence`; `ConnectorResponse` + `type`, `confluence`; `SyncRunResponse` + `listingMode`, `pendingDeletions` (ADR-0042'nin istediği kesin sayı, SMB için de). SMB istemcileri değişmeden çalışır. Tek davranış farkı: SMB isteğinde `host`/`share`/`username` eksikse kod artık `14012` (HTTP 400 aynı). Yeni hata kodları: `14012 CONNECTOR_SETTINGS_INVALID`, `14013 CONNECTOR_EXTERNAL_EGRESS_DISABLED`, `14014 CONNECTOR_URL_INVALID`, `14015 CONNECTOR_TYPE_IMMUTABLE` (409). Ayrıntı: `docs/api-connectors-integration-v1.md`.
- Panel: tür seçici (düzenlemede kilitli), Confluence alanları, kısıtlı sayfa uyarısı; onay kutusu `pendingDeletions > 0`'a bakar ve kesin sayıyı yazdırır (eski koşularda `deferred`, "en çok").

### 8. Denetim, metrik, sıcak yol

- Denetim ve metrikler SMB ile aynıdır (ADR-0038 bölüm 6 ve 8); yeni eylem yok. Kısıt yüzünden silinen sayfanın ayrıntısı `SOURCE_RESTRICTED`, atlanan sayfanın metrik nedeni `RESTRICTED`.
- Eşitleme asenkron worker'dır; soru-cevap yolu değişmedi (uzak çağrı sayısı aynı). Uzak çağrılar bağlayıcının tek iş parçacığında (bulkhead), zaman sınırlı, sınırlı denemeli ve bağlayıcı düzeyinde geri çekilmelidir (devre kesici eşdeğeri).

## Sonuçlar

- **Olumlu:** Confluence alanları elle yükleme olmadan güncel kalır; SMB'nin bütün emniyetleri (kira, idempotent alım, eksiksiz silme, %20 emniyeti, STALE alarmı) aynen geçerlidir. Kısıtlı sayfalar varsayılan olarak hiç girmez ve sonradan kısıtlananlar günlük taramada çıkar. Belirteç hiçbir yüzeye düşmez. Cloud egress'i açık, dar, görünür ve sıkı local modda imkânsızdır. Panel bekleyen silmenin kesin sayısını gösterir.
- **Olumsuz / kabul edilen risk:**
  - **İzin aynası yok:** kısıtsız bir sayfayı alanın bütün okuyucuları görür; Confluence alan izinleri aktarılmaz (bölüm 3).
  - Kısıt değişikliği en geç bir tam liste aralığında (24 sa) yansır; silinen/arşivlenen sayfa da öyle.
  - Ata kısıtı için ek istekler (koşu içinde önbellekli); büyük wikide tam liste istek sayısı sayfa sayısı kadardır. 429'da koşu durur ve geri çekilir.
  - Cloud ekleri medya host'una yönlendiği için bu sürümde alınmaz.
  - Kalıcı olarak başarısız bir sayfa imleci geride tutar (artımlı pencere büyür, maliyet artar, doğruluk değişmez).
  - Sayfa makrolarının ürettiği içerik (include, excerpt-include, Jira tablosu) alınmaz: storage formatında yalnız referans vardır.
- **Etkilenen dosyalar:**
  - `services/connector/connector-api` (`ConfluenceRequest`, `ConfluenceResponse`, `ConnectorType`, `ConfluenceDeployment`, `ListingMode`; `ConnectorRequest`, `ConnectorResponse`, `SyncRunResponse`), `connector-core` (`confluence/*`, `domain/{CqlBuilder,RetryPolicy,ConfluenceUrl,HostAllowlist,SyncPlanner}`, `worker/SyncRunner`, `smb/{FileSource,EgressGuard}`, `repository/*`, `service/*`, `config/{ConnectorProperties,ConnectorEgressConfiguration}`, `exception/ConnectorErrorCode`, `V2__confluence.sql`), `pom.xml` (jsoup 1.23.2).
  - `config/verso.yml`, `compose.yaml`, `.env.example`, `deploy/prod.env.example`, `deploy/compose.confluence.yaml`, `scripts/dev-secrets.sh`, `secrets/README.md`.
  - Panel: `panel/js/connectors.js`, `panel/js/screens/connectors.js`, `panel/js/api.js`; `scripts/panel.test.mjs`.
  - Testler: birim `StorageFormatConverterTest`, `ConfluenceSourceTest`, `ConfluenceRulesTest`, `ConnectorEgressConfigurationTest`, `SyncPlannerTest`, `SyncRunnerTest`, `ConnectorArchitectureTest`, `ConnectorApiContractTest`; CI'da sahte Confluence sunucusuyla `ConfluenceSyncTest`; `ComposeConfigTest`.
  - Dokümanlar: `docs/api-connectors-integration-v1.md`, `README.md`, `docs/decisions.md`, `docs/versions.md`, `docs/uyum/01-vked-sablonu.md`, `docs/uyum/05-egress-kaniti.md`, `docs/ai/llm-rules.md` (1.1 notu), `docs/ai/repo-context.md`, `docs/runbooks/connector-sync-stale.md`, `docs/evidence/adr-0044-dogrulama.md`, `scripts/uyum-paketi.test.js`.
- **Rollout (referans 18.4):** migration ekleyicidir (yeni sütunlar varsayılanlı, SMB satırları aynen geçerli); önce migration, sonra uygulama; eski uygulama yeni sütunları okumaz, `type = 'SMB'` satırlarıyla çalışmaya devam eder. Panel yeni alanları yoksa eski davranışa (`deferred`, "en çok") düşer.
- **Geri alma yolu:** Confluence bağlayıcılarını API ile sil (belgeleri de gider) ya da `enabled: false`; `VERSO_CONNECTOR_EXTERNAL_EGRESS=false`; overlay'i kapat. `V2` geri alınmaz (yeni sütunlar zararsız); uygulama geri alınırsa Confluence satırları claim edilip `CONFIG_INVALID` ile başarısız olmasın diye önce silinir.

## Yeniden değerlendirme koşulu

- İzin aynası talebi (sayfa kısıtı ve alan izinleri → IdP grupları, `document_principal`): ayrı ADR (v2), SMB ACL aynasıyla birlikte.
- Kısıtın daha hızlı yansıması gerekirse: `full-listing-interval` kısaltılır ya da Confluence webhook'u/denetim günlüğü (Data Center) ile kısıt olayları dinlenir.
- Atlassian Cloud v1 REST'i kaldırırsa: v2 (`/wiki/api/v2/pages`, `…/restrictions`) ve v1 CQL araması (`/wiki/rest/api/search`) ayrı ayrı değerlendirilir.
- Cloud ekleri gerekirse: medya host'u (`api.media.atlassian.com`) için ayrı, dar bir izin ve yeniden yönlendirme kuralı ADR'si.
