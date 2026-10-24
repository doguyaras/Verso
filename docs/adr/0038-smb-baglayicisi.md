# ADR-0038: SMB dosya paylaşımı bağlayıcısı (`connector` şeması, artımlı eşitleme, bilgi alanına izin, iç ağ egress'i)

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras (kurumsal yol haritası madde 5: "pilot ötesi yaygınlaştırma için elle yükleme yetmez"); uygulama ayrıntıları bu ADR'de
- **İlgili eşik (referans Bölüm 24):**
  - Bağlantı sayısının `max_connections × 0,7`'yi geçmesi (bu ADR dördüncü havuzu ekler: 22 → 25 / 100).
  - Bir kurulumda izlenen dosya sayısının bağlayıcı başına `max-listed-entries` (100 000) sınırına yaklaşması.
- **Genişlettiği:** ADR-0011 ve ADR-0016 (belge alımı ve sınırlar), ADR-0028 (bilgi alanları ve okuyucu listesi), ADR-0026 (denetim kaydı), ADR-0034 (eksiksiz silme), ADR-0013 (egress)

> Numara: `develop`'ta son ADR 0035; 0036 ve 0037 başka bir dalda (yönetim paneli ekranları) alınmış durumda. Çakışmamak için 0038.

## Bağlam

- **Alıcı kapısı.** Pilotlarda belgeler elle yüklenir; yaygınlaştırmada kurum, var olan dosya sunucusundaki klasörün Verso'da kendiliğinden güncel kalmasını ister (araştırma notları `enterprise_features.md` madde 5). İlk bağlayıcı SMB'dir: iç ağdadır, OAuth ve internet gerektirmez; Graph ve Confluence internet egress'i ve ayrı bir ADR ister.
- **Pazardaki en büyük şikâyet (Onyx):** arayüz "başarılı" derken indeksin sessizce durması. Sağlık, son koşunun rozetinden değil, son **başarılı** eşitlemenin zamanından türetilmelidir.
- **Kurallar:** modül başına şema ve iki rol (ADR-0023, ADR-0026); başka modülün tablosuna erişim yok (AGENTS.md 4); çok instance'ta `FOR UPDATE SKIP LOCKED` + kira + claim token (AGENTS.md 10); secret yalnız `/run/secrets`'ten, fallback yok (security-rules 1); SSRF kuralları (security-rules 5); local modda internete çıkış yok (ADR-0006, ADR-0013).
- **ACL aynası** (NTFS SID'lerinin IdP gruplarına eşlenmesi) ayrı ve büyük bir iştir (research madde 5: "izin aynası M"); bu ADR'nin kapsamı dışındadır, v2'dir.

## Seçenekler

**SMB istemcisi:**

| Seçenek | Lisans | Durum | Karar |
|---|---|---|---|
| **smbj 0.15.0** (`com.hierynomus:smbj`) | Apache-2.0 | Bakımda; 0.15.0 2026-08-21'de Maven Central'da. SMB2/3, imzalama, SMB3 şifreleme, NTLM; DFS kapatılabilir. OSV'de bilinen açık yok (2026-10-24: smbj 0.15.0, bcprov-jdk18on 1.85.2, mbassador 1.3.2, asn-one 0.6.0) | **Seçildi** |
| jcifs-ng 2.1.10 (`eu.agno3.jcifs:jcifs-ng`) | **LGPL** (POM'da) | Bakımda | Reddedildi: kapalı kaynak dağıtımda LGPL hukuki inceleme ister (security-rules 9); smbj aynı işi Apache-2.0 ile yapıyor |
| `mount.cifs` ile konteynere bağlama | – | – | Reddedildi: konteynere `CAP_SYS_ADMIN` gerekir (hardening: `cap_drop: ALL`), mount sunucusu uygulamanın egress kontrolünün dışında kalır |

**Modül sınırı:**

| Seçenek | Artı | Eksi |
|---|---|---|
| **A. Yeni `connector` modülü (`connector-api` + `connector-core`), kendi şeması; belge alımı ve silme `document-api`'de yeni süreç içi sözleşme `DocumentIngestion` ile** | Ayrıştırma, chunk, embedding, okuyucu satırları `document`'ta kalır; bağlayıcı yalnız bayt verir; sınır ArchUnit ve Modulith ile korunur | Bir modül çifti, bir şema, iki rol, bir havuz daha |
| B. Bağlayıcıyı `document-core`'a koymak | Daha az yüzey | `document` modülü dış sistem istemcisi, kimlik bilgisi ve zamanlayıcı taşır; SMB bağımlılığı belge modülüne girer; Graph/Confluence geldikçe büyür |

**Tutarlılık:** saga tablosu ya da idempotent adımlar (operation-consistency 1). Her adım tek şemada kısa bir işlemdir ve deterministik anahtarla tekrar güvenlidir; saga gereksizdir (aşağıda).

## Karar

### 1. Modül, şema, roller

- `services/connector/connector-api` (`ConnectorRequest`, `ConnectorResponse`, `SyncRequest`, `SyncRunResponse`, `ConnectorItemResponse`, `ConnectorPageResponse`; `ConnectorHealth`, `SyncRunStatus`, `ItemState`) ve `connector-core` (`config`, `controller/AdminConnectorController`, `service`, `domain` (saf kurallar), `smb`, `worker`, `repository`, `exception`). `@ApplicationModule(allowedDependencies = {"document :: api", "audit :: api"})`; kimse `connector`'a bağımlı değildir.
- Şema `connector`, sahibi `svc_connector_migrate`, uygulama rolü `svc_connector` (DML). `deploy/postgres/initdb/{10-roles,20-database,40-modules}.sh` ve `deploy/backup/restore-check.sh`: `MODULES=(document qa audit connector)`; restore provasında `restore-flyway-connector`. Var olan volume `40-modules.sh` ile yükseltilir (README).
- Kendi havuzu (`ConnectorDatabase`, `verso-connector-db`, 3 bağlantı, `minimum-idle` 0) ve Flyway'i (`ConnectorSchemaMigration`), `audit` ile aynı desen. Bağlantı bütçesi instance başına 22 → 25.
- `document-api` `DocumentIngestion`: `submit` (bayt, ad, hedef alan, kaynak hesabı `connector:<id>`, idempotency anahtarı, değiştirdiği belge), `delete(alan, belge)`, `present(alan, id'ler)`, `collectionExists`, `maxFileBytes`. Uygulaması `document-core` `DocumentIngestionServiceImpl`: kullanıcı yüklemesiyle aynı kilit sırası (alan satırı, alan kotası, hesap, belge), aynı format tespiti ve boyut sınırı (ADR-0016), aynı insert (alanın grant'ları okuyucu satırlarına kopyalanır, ADR-0028). Kullanıcı olmadığı için denetim olayı yayınlamaz; denetimi bağlayıcı yazar.

### 2. Tablolar

- `connector.connector`: ayarlar (ad, sunucu, port, paylaşım, normalize önek, etki alanı, hesap adı, `credential_ref` = secret dosyasının **adı**, hedef alan, include/exclude glob'ları, en büyük dosya, aralık, açık/kapalı), zamanlama ve dağıtık kilit (`next_run_at`, `sync_requested_at`, `locked_until`, `claim_token`), sonuç (`consecutive_failures`, `last_success_at`, `last_failure_at`, `last_error_code`), silme (`deleting`) ve tek koşuluk toplu silme onayı. CHECK'ler biçimleri veritabanında da zorlar (önekte `..`, `:` ve ters bölü yok; `credential_ref` yalnız `SECRET_CONNECTOR_*`).
- `connector.connector_item` (birincil anahtar bağlayıcı + yol): dosyanın parmak izi (SMB dosya kimliği, boyut, değişiklik zamanı mikrosaniyeye kesilir), içerik SHA-256'sı, belge kimliği (düz `uuid`, FK yok), durum `SYNCED | FAILED | SKIPPED`, hata kodu, deneme sayısı, sonraki deneme.
- `connector.sync_run`: koşu başına sayılar ve sabit kod; bağlayıcı başına son 100 koşu tutulur.
- Yol ve dosya adları yalnız `connector_item`'dadır (yöneticinin hata listesi için); log'a, metriğe ve denetim kaydına hiç yazılmaz.

### 3. Eşitleme

- **Zamanlama ve kilit:** her instance 30 sn'de bir yoklar; vadesi gelen bağlayıcı tek ifadeyle (`UPDATE … WHERE id = (SELECT … FOR UPDATE SKIP LOCKED)`) 10 dk'lık kirayla ve claim token'la alınır; kira, işin üçte biri geçtikçe yenilenir; sonuç ve kira bırakma yalnız token sahibinindir. Bir bağlayıcıyı aynı anda tek instance eşitler. Eşitleme kendi tek iş parçacığında koşar (bulkhead): paylaşılan zamanlayıcıyı (belge alımı, saklama işleri) uzun bir ilk eşitleme bekletmez.
- **Artımlı fark (`SyncPlanner`, saf fonksiyon):** listede olup izlenmeyen → yeni; dosya kimliği, boyut ve zaman aynı → değişmemiş (indirilmez); biri farklı → değişmiş (indirilir, içerik hash'i aynıysa yalnız parmak izi güncellenir, yeniden alınmaz); başarısız dosya geri çekilme süresi dolunca yeniden denenir; atlanan dosya değişene kadar atlanır; Verso'da silinmiş ya da taşınmış belge (`present`) kaynaktan geri alınır; izlenip listede olmayan → kaynakta silinmiş.
- **Silme yalnız tam listede:** okunamayan bir klasör, atlanan bir bağlantı, derinlik ya da kayıt sınırı listeyi "eksik" yapar; o koşuda hiçbir şey silinmez ve koşu başarı sayılmaz. Önek klasörü yoksa koşu `FAILED/PATH_NOT_FOUND` olur, yine hiçbir şey silinmez.
- **Silme emniyeti:** bir koşu izlenen dosyaların %20'sinden **ve** 20 dosyadan fazlasını silecekse silme yapılmaz, koşu `PARTIAL/DELETION_SAFETY` olur; yönetici gerçekten silindiğini `POST /sync` gövdesinde `confirmMassDeletion: true` ile tek koşu için onaylar. Yeniden adlandırılan klasör ya da kaybolan izin bir alanı tek başına boşaltamaz.
- **Silme eksiksizdir (ADR-0034):** kaynakta silinen dosyanın belgesi `DocumentIngestion.delete` ile dosyası, sayfaları, parçaları, vektörleri, arama vektörleri ve okuyucu satırlarıyla (`ON DELETE CASCADE`) silinir. Değişen dosyanın eski belgesi yenisini ekleyen işlemde silinir; artık alınamayan bir sürüm (çok büyük, boş, desteklenmeyen) eski belgeyi de götürür, çünkü kaynak o içeriği artık tutmuyor. Silinen belgeden alıntı yapan geçmiş cevaplar ADR-0034'teki gibi kendiliğinden değişmez; yönetici `answer-redaction` ile karar verir.
- **Dosya bazlı hata koşuyu durdurmaz:** okunamayan dosya `FAILED` (geri çekilme 5 dk · 2^(n−1), en çok 24 sa), alınamayan dosya `SKIPPED`; koşu devam eder. Yalnız kaynağın kendisi (egress, kimlik, bağlantı, paylaşım, önek, hedef alan) koşuyu durdurur; bağlayıcı düzeyinde geri çekilme 1 dk · 2^(n−1), en çok eşitleme aralığı.
- **Hız ve boyut sınırları:** koşu başına 500 dosya ve 2 GB; dosya başına bağlayıcının sınırı (en çok `verso.document.max-file-size`, 20 MB) boyut listeden okunarak indirmeden önce, indirirken de akış sayılarak zorlanır; dosyalar bellekte okunur, diske yazılmaz. ZIP bombası ve sayfa/karakter sınırları belge hattının kendi sınırlarıdır (ADR-0016), bağlayıcı belgesi de aynı hattan geçer. Belge kuyruğu doluysa (`QUEUE_FULL`, kaynak başına 20 bekleyen belge) indirme durur, koşu `PARTIAL/BACKLOG` olur, devamı 2 dk sonra.
- **Çökmeden sonra devam (idempotent):** belge, (bağlayıcı, yol, içerik hash'i)'nden türeyen idempotency anahtarıyla `document` şemasında kendi işleminde yazılır; öğe satırı ondan sonra `connector` şemasında yazılır. Arada süreç ölürse kira düşer, başka bir instance bağlayıcıyı alır, eski `RUNNING` koşuyu `ABANDONED/INSTANCE_STOPPED` kapatır; dosyayı yeniden indirir, aynı anahtarı üretir ve var olan belgeyi bulur (`REPLAYED`). Silme önce belgeyi, sonra öğeyi siler; tekrarı ikinci kez silmez. Kapanışta koşu dosyalar arasında durur, `PARTIAL/INTERRUPTED` yazar ve kirayı bırakır.

### 4. İzinler (v1) ve risk

- Belgeler bağlayıcının hedef bilgi alanına girer; okuma yetkisini alanın grant'ları verir (ADR-0028), retrieval aynı SQL filtresini uygular. Bağlayıcı belgesinin sahibi `connector:<id>`'dir, bir kişi değildir; KVKK kişi başvurusunda (ADR-0034) alan belgesi olarak "kurumun" sayılır.
- **Risk:** alana erişimi olan **herkes**, paylaşımdaki NTFS izinlerinden bağımsız olarak eşitlenen **her** dosyayı soru-cevapta görebilir. Paylaşımda bir dosyayı yalnız İK okuyabiliyorsa ama bağlayıcının alanı bütün şirkete açıksa, o dosya bütün şirkete açılmış olur. Bu yüzden: yönetici paylaşımı ve öneki alanın okuyucu kitlesine göre seçer (alan başına bir bağlayıcı, bağlayıcı başına tek erişim düzeyinde bir klasör); `excludeGlobs` gizli alt klasörleri dışarıda bırakır; servis hesabına yalnız eşitlenecek klasörlerde okuma verilir (hesabın göremediği dosya listelenmez). Hedef alan oluşturulduktan sonra değiştirilemez (409): taşıma, belgeleri eski grant'larla geride bırakırdı.
- **v2 (izin aynası):** smbj `DiskEntry.getSecurityInformation` ile DACL okunabilir (araştırma notunun "smbj için güvenlik tanımlayıcı dokümanı bulunamadı" maddesi kodda doğrulandı); SID → IdP grubu eşlemesi `principal_alias` tablosuyla, eşlenemeyen ilke düşürülür (fail-closed), belge başına `document_principal`. Ayrı ADR.

### 5. Güvenlik

- **Egress / SSRF:** `verso.connector.allowed-networks` (`VERSO_CONNECTOR_ALLOWED_NETWORKS`) CIDR listesi; **yalnız özel aralıklar** kabul edilir (10/8, 172.16/12, 192.168/16, fc00::/7), aksi hâlde uygulama açılmaz. Liste boşsa hiçbir bağlayıcı bağlanamaz. Sunucu adı her eşitlemede bir kez çözülür (5 sn zaman sınırı), çözülen **bütün** adresler listede olmalıdır; istemci o adrese IP olarak bağlanır (ikinci çözümleme yok, DNS rebinding yok). Loopback, link-local (169.254.169.254 dahil), joker, multicast ve yayın adresleri listede olsa da reddedilir; IPv4'ün IPv6 yazımı (`::ffff:`) IPv4 olarak değerlendirilir. IP literal'i yönetici API'sinde de kontrol edilir (400 `14004`). DFS kapalıdır: referral oturumu başka sunucuya götüremez.
- **Ağ:** temel `compose.yaml`'da uygulamanın paylaşımlara yolu yoktur. `deploy/compose.smb.yaml` yalnız uygulamaya sabit alt ağlı, `verso-smb` arayüzlü tek bir köprü ağı ve servis hesabının secret'ını ekler; ağın internete çıkmaması host güvenlik duvarının `DOCKER-USER` kuralıyla sağlanır (yalnız dosya sunucusu:445). Bu bilinçli bir sapmadır: `scripts/prove-local-mode.sh` 1. adımı overlay açıkken `smb` ağını raporlar; kanıt güvenlik duvarı kuralı ve 2. adımdır. Docker'ın gömülü DNS'i iç olmayan ağda dış adları çözer; sıkı kurulumda sunucu IP ile tanımlanır.
- **Kimlik bilgisi:** parola `SECRET_CONNECTOR_*` adlı secret dosyasındadır (`SECRET_CONNECTOR_SMB_PASSWORD`; `secrets/README.md`, `scripts/dev-secrets.sh`, `deploy/compose.smb.yaml`, `config/verso.yml` yorumu). Config tree üzerinden yalnız bağlantı anında okunur, `char[]` olarak istemciye verilir ve sıfırlanır; veritabanına, log'a, metriğe, denetim kaydına ve yanıta girmez. Yalnız `SECRET_CONNECTOR_` önekli adlar okunur: yönetici bir bağlayıcıyı veritabanı ya da IdP secret'ına yönlendirip onu bir dosya sunucusuna gönderemez. Rotasyon: dosyayı değiştir, `verso-app`'i yeniden başlat. **Kalan risk (güvenlik review B2):** `verso-admin` bir bağlayıcıyı izinli bloktaki kendi SMB sunucusuna yöneltirse servis hesabının NTLM yanıtını (parola değil, çevrimdışı kırılabilir challenge-response) toplayabilir. Önlem: izinli liste olabildiğince dar (dosya sunucusu `/32`), servis hesabı uzun rastgele parolalı ve yalnız okuma yetkili; bağlayıcı değişiklikleri denetim kaydındadır.
- **Yol ve bağlantı güvenliği:** önek normalize edilir; `.`, `..`, boş parça, `:` (sürücü, alternatif veri akışı), joker ve kontrol karakterleri, sondaki nokta/boşluk reddedilir (400 `14003`). Sunucudan gelen her ad aynı kuraldan geçer; geçmeyen ad atlanır. Yol her kullanımda yeniden doğrulanarak birleştirilir. Reparse point (sembolik bağlantı, junction, mount point) olan klasör izlenmez (liste eksik sayılır), dosya atlanır; dosyalar `FILE_OPEN_REPARSE_POINT` ile açılır ve öznitelik yeniden denetlenir. Çevrimdışı (HSM) dosyalar geri çağrılmaz. **Sınır:** sunucunun kendi çözdüğü bağlantılar (Samba `wide links = yes`) istemciye düz klasör görünür; kurulumda `wide links = no` istenir (README).
- **Salt okunur:** dosyalar yalnız `FILE_READ_DATA | FILE_READ_ATTRIBUTES` ile, başkalarını kilitlemeden açılır; servis hesabına yalnız okuma verilir. İmza zorunludur (`verso.connector.smb.signing-required`), SMB3 şifreleme isteğe bağlıdır.
- **Log:** id, sayı ve sabit kod; sunucu mesajı, ad, yol, paylaşım ve hesap asla (`SourceException` yalnız kod taşır, yığın izi tutmaz).

### 6. Denetim (ADR-0026)

| Eylem | Aktör | Hedef | Ayrıntı |
|---|---|---|---|
| `CONNECTOR_CREATED`, `CONNECTOR_UPDATED`, `CONNECTOR_DELETED` | yönetici (`sub`) | `CONNECTOR` + id | – |
| `CONNECTOR_SYNC_REQUESTED` | yönetici | `CONNECTOR` + id | `SCHEDULED` / `MASS_DELETION_CONFIRMED` |
| `CONNECTOR_SYNC_STARTED`, `CONNECTOR_SYNC_FINISHED` | `system:connector-sync`, rolsüz | `CONNECTOR` + id | bitişte durum (`SUCCEEDED`, `PARTIAL`, `FAILED`) |
| `CONNECTOR_DOCUMENT_ADDED`, `CONNECTOR_DOCUMENT_DELETED` | `system:connector-sync` | `DOCUMENT` + id | `SOURCE_NEW`, `SOURCE_CHANGED`, `SOURCE_DELETED`, `SOURCE_UNREADABLE`, `CONNECTOR_DELETED` |

- **Yol (ADR-0039 outbox deseni):** her kayıt, bildirdiği değişikliğin transaction'ında bir outbox'a yazılır ve relay onu denetim kaydına taşır. Yönetici değişiklikleri ile koşu başlangıç/bitişi `connector.outbox_event`'e (`ConnectorAudit`, `ConnectorOutbox`, `ConnectorOutboxRelayJob`; koşu bitişi, koşu satırı ve bağlayıcı sonucu tek transaction'da ve yalnız kira sahibi yazar), belge ekleme/silme olayları ise `DocumentIngestion`'ın kendi transaction'ında `document.outbox_event`'e yazılır: belge ve olayı birlikte commit olur ya da birlikte geri alınır.
- Arka plan işinin istek bağlamı yoktur: `audit-api` `AuditTrail.captureSystem(bileşen, olay)` kaydı `system:<bileşen>` aktörü ve boş rol listesiyle kurar; bileşen kodda sabit bir sözcüktür (`connector-sync`). Koşu başına ekleme/silme sayısı belge id'li satırlardan sayılır; sayılar ayrıca `sync_run`'dadır. Ad, yol ve sunucu kayda girmez.
- 403'ler `ACCESS_DENIED` olarak `ADMIN_CONNECTOR` sözcüğüyle yazılır (sınıf adından türer).
- Commit ile kayıt arasındaki kayıp penceresi yoktur (outbox). Yeniden oynatılan (`REPLAYED`) belge için ikinci olay yazılmaz: ilk deneme belgeyle birlikte kendi olayını yazmıştı.

### 7. Uçlar (`verso-admin`)

`POST /v1/admin/connectors`, `GET /v1/admin/connectors`, `GET/PUT/DELETE /v1/admin/connectors/{id}`, `POST /v1/admin/connectors/{id}/sync`, `GET /v1/admin/connectors/{id}/runs`, `GET /v1/admin/connectors/{id}/items?state=`. Ayrıntı ve hata kodları: `docs/api-connectors-integration-v1.md`. Silme 202'dir: bağlayıcı hemen durur, belgeleri arka planda bütün türevleriyle silinir, sonra satır gider. Hata bloğu **connector 14000–14999**.

### 8. Gözlem

- Metrikler: `verso_connector_sync_lag_seconds{connector}` (son başarılı eşitlemeden, hiç yoksa oluşturulmadan bu yana; kazıma anında hesaplanır, koşular hiç çalışmasa da büyür), `verso_connector_schedule_seconds`, `verso_connector_last_success_timestamp_seconds`, `verso_connector_items{state}`, `verso_connector_sync_runs_total{status}`, `verso_connector_documents_total{change}`, `verso_connector_items_failed_total{reason}`. Etiket yalnız bağlayıcı id'sidir.
- Alarm `ConnectorSyncStale` (ticket): son başarılı eşitleme üç aralıktan eski, 15 dk boyunca (`alerts.test.yml`'de iki senaryo). API aynı kuralla `health: STALE` gösterir (`HealthRule`, `verso.connector.stale-factor` = 3). Runbook `docs/runbooks/connector-sync-stale.md`.

### 9. Sıcak yol

Eşitleme sıcak yol değildir (asenkron worker). Soru-cevap yolu değişmedi (uzak çağrı sayısı 2). Bağlayıcı belgeleri belge alımı worker'ının kuyruğuna girer; model çağrılarıyla eşzamanlılık belge worker'ının kendi sınırlarıyla paylaşılır.

## Sonuçlar

- **Olumlu:**
  - Bir paylaşım klasörü elle yükleme olmadan güncel kalır; ekleme, değişiklik ve silme (türevleriyle) yayılır.
  - Arayüz ve alarm, son başarılı eşitlemeye göre konuşur; "başarılı rozet, duran indeks" durumu STALE ve alarm olarak görünür.
  - Kimlik bilgisi hiçbir yüzeye (veritabanı, log, yanıt, denetim) düşmez; egress yalnız özel ağlara ve yalnız izinli bloklara.
  - Çökme, kira kaybı ve tekrar idempotenttir; silme emniyeti yanlış yapılandırmayı durdurur.
- **Olumsuz / kabul edilen risk:**
  - **İzin aynası yok:** alana erişimi olan herkes eşitlenen her dosyayı görür (bölüm 4). Yanlış seçilmiş bir paylaşım, NTFS'te kısıtlı bir dosyayı geniş bir kitleye açar.
  - Değişen dosyanın yeni sürümü işlenene kadar (dakikalar) o dosyadan cevap gelmez: eski belge yeniyle aynı işlemde silinir.
  - Yeniden adlandırma = silme + ekleme (dosya kimliğiyle eşleme yapılmadı).
  - SMB'nin değişiklik akışı yoktur; her koşu kaynağı baştan listeler (büyük paylaşımda maliyet, `max-listed-entries` sınırı).
  - Overlay ile uygulamaya iç olmayan bir ağ eklenir; internete kapalılığı host güvenlik duvarına bağlıdır.
  - Kendi çözülen sunucu bağlantıları (Samba `wide links`) istemciden görünmez.
  - Dört havuz: bağlantı bütçesi 22'den 25'e.
- **Etkilenen dosyalar:**
  - `services/connector/**`; `document-api` (`DocumentIngestion`, `dto/Ingestion{Request,Result}`), `document-core` (`DocumentIngestionServiceImpl`, `DocumentRepository`, `CollectionRepository`); `audit-api` (`AuditTrail.captureSystem`, yeni `AuditAction`'lar, `AuditTargetType.CONNECTOR`), `audit-core` (`AuditTrailServiceImpl`); `document-core` `outbox/DocumentAuditOutbox.appendSystem`; `connector-core` `outbox/*`, `config/ConnectorOutboxConfiguration`, `worker/ConnectorOutboxRelayJob`, `connector.outbox_event`.
  - `pom.xml` (smbj 0.15.0, bcprov 1.85.2), `verso-app/pom.xml`; `config/verso.yml`, `application-local.yml`; `compose.yaml`, `deploy/compose.smb.yaml`, `.env.example`, `deploy/prod.env.example`; `deploy/postgres/initdb/*`, `deploy/backup/restore-check.sh`, `scripts/{dev-secrets,restore-drill}.sh`, `secrets/README.md`; `deploy/obs/prometheus/{alerts,alerts.test}.yml`.
  - Testler: birim `SyncPlannerTest`, `SourcePathsTest`, `GlobSetTest`, `NetworkAllowlistTest`, `HealthRuleTest`, `EgressAndCredentialsTest`, `SyncRunnerTest`, `ConnectorArchitectureTest`, `ConnectorApiContractTest`; gerçek veritabanı `ConnectorApiTest`; Samba konteyneriyle `ConnectorSyncTest`; güncellenen `ComposeConfigTest`, `ConfigDriftTest`, `DeployConfigTest`, `MigrateModeTest`, `DatabaseRolesTest`, `ModuleConsistencyTest`, `ModuleStructureTest`, `ErrorCodeUniquenessTest`.
  - Dokümanlar: `docs/api-connectors-integration-v1.md`, `README.md`, `docs/decisions.md`, `docs/ai/repo-context.md`, `docs/versions.md`, `docs/uyum/01-vked-sablonu.md`, `docs/runbooks/connector-sync-stale.md`, `docs/evidence/adr-0038-dogrulama.md`.
- **Geri alma yolu:** bağlayıcıları API ile sil (belgeleri de gider) ya da `enabled: false`; overlay'i kapat. Şema ve rollerin kaldırılması elle yapılan DBA işidir (`DROP SCHEMA connector CASCADE`, `DROP ROLE`). `DocumentIngestion` ve yeni denetim eylemleri geriye uyumludur.
- **Panel (sonradan):** yönetim ekranı artık var: "Yönetim → Bağlayıcılar" ([ADR-0042](0042-baglayici-ekrani.md)). API sözleşmesi ekran için değişmedi; ekran `health`'i, koşuları, dosyaları ve silme emniyeti onayını bu ADR'nin kurallarıyla gösterir.
- **Confluence (sonradan):** aynı çerçeve, `type` sütunu ve `confluence` paketiyle Confluence'ı da eşitler ([ADR-0044](0044-confluence-baglayicisi.md)); `FileSource.Listing` artımlı listeyi, `ListedFile` kaynak kısıtını taşır. SMB davranışı değişmedi; `SyncRunResponse.pendingDeletions` SMB koşularında da kesin bekleyen silme sayısını verir.

## Yeniden değerlendirme koşulu

- İzin aynası talebi (NTFS ACL → IdP grupları): ayrı ADR (v2).
- Graph/SharePoint ya da Confluence bağlayıcısı: internet egress'i ve llm-rules 1.1 istisnası için ayrı ADR; `connector` şeması `type` sütunuyla genişler.
- Bir bağlayıcı `max-listed-entries`'e yaklaşırsa ya da tam listeleme süresi aralığı aşarsa: SMB değişiklik bildirimi (`CHANGE_NOTIFY`) ya da haftalık tam + günlük kısmi tarama.
- Bağlantı bütçesi `max_connections × 0,7`'ye yaklaşırsa havuzlar birleştirilir ya da PgBouncer.
