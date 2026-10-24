# ADR-0023: İkinci modül şeması (`qa`), konuşma geçmişi, cevap geri bildirimi ve kullanım metrikleri

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras (kurumsal yol haritası, maddeler Ö2, Ö3, 1, 2a, 2b); uygulama ayrıntıları bu ADR'de
- **İlgili eşik (referans Bölüm 24):**
  - Bağlantı sayısının `max_connections × 0,7`'yi geçmesi (bu ADR iki havuz ekler).
  - `qa.turn` tablosunun sunucu RAM'ine yaklaşması (referans 10.6: partition eşiği).
  - Geçmişin prompt'a girmesi (takip soruları) ya da ilk modüller arası olay ihtiyacı.

## Bağlam

- **Tek DataSource sorunu (ADR-0009, review A1, açık karar).** Uygulama tek havuzla `svc_document` olarak bağlanıyordu. Modül başına şema ve iki rol kuralı (referans 10.1, ADR-0002) ikinci bir şemayı bu havuzla kullanmayı yasaklar: `svc_document`'a `qa` üzerinde yetki vermek AGENTS.md §4'e aykırıdır. ADR-0009 seçenekleri saydı ama karar vermedi: modül başına DataSource, tek login rolü + `SET LOCAL ROLE`, ortak rol.
- **Alıcıların pilotta istediği üç şey** (araştırma notları `enterprise_features.md` 1, 2a, 2b; `market_needs.md` 3–5; `ui_ux.md` 4, 8):
  - Sunucuda konuşma geçmişi; saklama süresi ve silme hakkıyla (KVKK Üretken YZ Rehberi, Örnek 10: sohbet kayıtlarını süresiz tutmak ihlaldir).
  - Cevaba 👍/👎 ve neden ("yanlış kaynak" hangi atıf).
  - Değer panosu: soru sayısı, aktif kullanıcı, kaynaklı / bulunamadı / kaynaksız oranları, süre, mod, geri bildirim, en çok ve hiç atıf alan belgeler, cevaplanamayan sorular.
- `qa` modülünün bugüne kadar tablosu yoktu (ADR-0012). Belgeler `document` şemasındadır; atıflar belge kimliği taşır.

## Seçenekler

**İkinci şema:**

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| **A. Modül başına havuz + Flyway (`svc_qa`, `svc_qa_migrate`)** | Referans 10.1 olduğu gibi: GRANT sınırı iki yönde, şema ayrı instance'a taşınabilir | İki havuz (bağlantı bütçesi), ikinci Flyway Boot'un otomatik Flyway'ini kapatmamalı | Orta |
| B. Tek login rolü + işlem başına `SET LOCAL ROLE` | Tek havuz | Login rolü her iki rolün üyesi: ele geçirilen süreç rol değiştirir; unutulan `SET LOCAL` sessizce yanlış şemaya yazar; PgBouncer'da dikkat ister | Orta |
| C. Ortak rol, iki şema | En basit | Kuralın kendisini gevşetir (referans 10.1'den sapma) | Düşük |

**Metriklerin kaynağı:**

| Seçenek | Artı | Eksi |
|---|---|---|
| **A. Günlük sayaç tabloları, turn ile aynı transaction'da atomik upsert** | Saklama süresinden ve kullanıcının silmesinden bağımsız; ucuz okuma; iş yok | p50/p95 yaklaşık (histogram kovası) |
| B. Her istekte `qa.turn` üzerinden sorgu | Kesin yüzdelik | Metrikler saklama süresi dolunca ya da kullanıcı silince kaybolur |
| C. Gece toplayan iş | Kesin yüzdelik | Dağıtık kilit, geç gelen geri bildirim, iş düşerse veri kaybı |

## Karar

### 1. Şema ve roller: A

- `qa` şeması, sahibi `svc_qa_migrate`; uygulama `svc_qa` (yalnız DML), rol bazlı zaman aşımları `svc_document` ile aynı (10 sn / 3 sn / 60 sn; migration rolünde 10 sn kilit).
- `deploy/postgres/initdb/10-roles.sh` ve `20-database.sh`: `MODULES=(document qa)`.
- **Var olan volume için yükseltme:** `40-modules.sh`. Eksik modül rollerini ve şemasını ekler, var olana dokunmaz (rol parolasını korur). Boş volume'de 10/20'den sonra çalışır ve hiçbir şey değiştirmez. Elle çalıştırma README "Yükseltme" bölümündedir.
- **Uygulama tarafı (`qa-core`):**
  - `QaDatabase`: kendi Hikari havuzu (`verso-qa-db`, 4 bağlantı, tembel bağlantı), `JdbcClient` ve `TransactionTemplate`. Bilerek `DataSource`, `JdbcClient` ya da transaction manager **bean'i değildir**: tip ile enjeksiyonda uygulamanın havuzuyla yarışır ya da Boot'un otomatik yapılandırmasını kapatırdı. `document` modülü bu bağlantıyı hiçbir yolla alamaz (`QaArchitectureTest`).
  - `QaSchemaMigration`: `Flyway.configure()` ile `qa` şemasını `svc_qa_migrate` olarak taşır. Bilerek `Flyway` bean'i değildir (ADR-0009: ikinci Flyway bean'i Boot'un `document` Flyway'ini kapatır). Açık/kapalı `spring.flyway.enabled`'ı izler: compose'da yalnız tek seferlik `migrate` container'ı çalıştırır, uzun yaşayan uygulama iki migration parolasını da almaz.
  - Migration'lar `qa-core/src/main/resources/db/migration/qa`; `afterMigrate.sql` / `afterMigrateError.sql` geçmiş tablosunu `svc_qa`'dan geri alır.
- **Config yüzeyleri birlikte:** `config/verso.yml` (`verso.qa.datasource.*`, `verso.qa.flyway.*`), `application-local.yml` (aynı anahtarlar, `ConfigDriftTest`), `compose.yaml` (secret'lar: `verso-app` yalnız `SECRET_DB_QA_PASSWORD`, `migrate` yalnız `SECRET_DB_QA_MIGRATE_PASSWORD`), `scripts/dev-secrets.sh`, `secrets/README.md`.
- **Restore provası:** modül başına bir Flyway `validate` adımı (`restore-flyway-qa`); `restore-check.sh` her modülün uygulama rolüyle okuma, geçmişe erişememe ve DDL yapamama denetimini yapar. ADR-0009'daki "modül başına validate" açığı kapandı (`ModuleConsistencyTest`).
- **Bağlantı bütçesi (instance başına):** uygulama 10 + `qa` 4 + migrate anında Flyway 2 + yedek 2 = 18; `max_connections` 100. İkinci instance 14 daha ekler. Paketteki demo Keycloak aynı sunucuda kendi veritabanına bağlanır ve bu hesaba girmez (havuzu ayarlanmamış; üretimde kurumun IdP'si kullanılır).
- **Sağlık ve alarm:** `qaDb` sağlık bileşeni hazırlık grubundadır (`application.yml`): yanlış `svc_qa` parolası ya da eksik şema container'ı sağlıksız gösterir. Yazılamayan turlar `HistoryNotStored` alarmını (ticket) çalar; runbook `docs/runbooks/history-not-stored.md`.
- **Havuz davranışı:**
  - Bağlantı beklemesi 2 sn (`connection-timeout`): tur cevaptan sonra yazılır, uzun bekleme yalnız cevabı geciktirirdi.
  - Havuz bağlantısız başlar (`initializationFailTimeout=-1`) ve transaction açılırken bağlantı almaz. Veritabanı yoksa uçlar 503 `SERVICE_UNAVAILABLE` döner (`QaDatabaseUnavailableTest`).
- **Migration kuralları:** `MigrationConventions` (document-core test-jar) şema ve rol parametreli oldu. `MigrationConventionsTest` `document`'ı kötü fixture'larla, `ModuleConsistencyTest` her modülü aynı kurallarla denetler.
- **Reddedilenler:**
  - B: rol değiştirme yetkisi GRANT sınırını sürecin iyi niyetine bağlar.
  - C: kuralın kendisini gevşetir.

### 2. Modüller arası olay (Ö3): şimdilik yok

- Belge silinince geçmişteki atıf kırılmamalı. Bu, olayla değil **okumada** çözülür. `document-api`'ye `DocumentCatalog` eklendi: `fileNames(accountId, ids)` yalnız hâlâ var olan ve çağırana ait belgeleri döner. Yoksa atıf `documentDeleted: true`, `fileName: null` ile gösterilir; referans kaybolmaz.
- Böylece `event_publication` tablosu, yeniden deneme ve idempotent dinleyici gerekmez (ADR-0003 korunur).
- **Yeniden değerlendirme:** audit kaydı ya da hesap silme orkestrasyonu ilk olayı gerektirdiğinde Modulith event registry ayrı ADR ile gelir.

### 3. Konuşma geçmişi

- **Tablolar** (`V1__qa_history.sql`):
  - `qa.conversation`: `account_id`, `title`, `created_at`, `updated_at` (son turun zamanı).
  - `qa.turn`: soru, cevap, sonuç türü (`ANSWERED` / `NOT_FOUND` / `UNCITED`), mod, model, süreler (retrieval, chat, toplam; toplam metriklere ve API'ye gider, diğer ikisi teşhis içindir), metrik günü.
  - `qa.turn_citation`: atıf numarası, belge kimliği, sayfa/bölüm, birim. **Pasaj metni saklanmaz.** Belge kimliği düz `uuid`'dir; şemalar arası FK yoktur.
- **Değişmezlik:** turn ve atıflar yazılır ve silinir, güncellenmez (`svc_qa`'da `UPDATE` yok). Atıf ve puan satırları yalnız turla birlikte silinir: `svc_qa`'da `DELETE` de yoktur, cascade tablo sahibinin yetkisiyle çalışır.
- **Yazma:** `POST /v1/questions` cevaptan sonra, model çağrısı dışında, tek kısa transaction'da yazar: konuşma (kilitli ya da yeni), turn, atıflar ve günlük sayaçlar.
  - Yazma başarısız olursa cevap yine döner, `conversationId`/`turnId` boş kalır. Log'a yalnız tip yazılır; `verso.qa.history.not.stored{reason}` sayacı artar. Gerekçe: cevap üretilmiştir, bir DB hatası kullanıcıdan cevabı almamalıdır. En kötü durumda yazma cevabı yaklaşık 2 sn (bağlantı beklemesi) ya da kilit/ifade zaman aşımı kadar geciktirir.
  - İstekte `conversationId` varsa, model çağrılmadan önce sahiplik ve doluluk denetlenir: başkasının ya da silinmiş konuşma 404 `CONVERSATION_NOT_FOUND`, dolu konuşma 409 `CONVERSATION_FULL`. Sınır (200 turn) konuşma satırı kilidi altında kesindir.
- **Geçmiş prompt'a girmez.** Takip soruları ayrı bir `verso-llm-review` ile gelir.
- **Uçlar:** `GET /v1/conversations` (sayfalı, en çok 50), `GET/PATCH/DELETE /v1/conversations/{id}`, `DELETE /v1/conversations`.
  - Her sorgu `account_id` taşır; başkasının kimliği 404'tür (varlık oracle'ı yok).
  - Yeniden adlandırma `updated_at`'i değiştirmez: liste sırası ve saklama süresi yalnız turlara bağlıdır.
- **Sayfa tipi:** liste `document-api`'deki `PageResponse`'u kullanır. ADR-0011, ikinci sayfalı API gelince ortak bir api modülüne taşımayı öngörmüştü. Taşıma yalnız Java paket adını değiştirir, JSON aynıdır; ayrı iş olarak bırakıldı.

### 4. Saklama (KVKK)

- **Parametre:** `verso.qa.history.retention-days`, ortam değişkeni `VERSO_HISTORY_RETENTION_DAYS`. Varsayılan **90 gün**, aralık 1–3650.
- **Neden 90:**
  - KVKK md. 4/2-(d): veri, amaç için gerekli süre kadar tutulur.
  - KVKK Üretken YZ Rehberi (Kasım 2025), Örnek 10: sohbet kayıtlarının süresiz tutulması ihlaldir.
  - 90 gün bir pilotun raporlama dönemini (bir çeyrek) kapsar. Silme Yönetmeliği'nin periyodik imha aralığı (en geç 6 ay; `market_needs.md` §4) içinde kalır; saatlik iş bu aralıktan çok daha sık siler.
  - Süreyi veri sorumlusu kurum belirler; bu ADR hukuki tavsiye değildir.
  - Parametre kataloğu Verso'da yok (referans 14). Kuruluma özgü bu değer bu yüzden ortam değişkeniyle verilir, admin ekranından değiştirilmez.
- **Süre konuşmanın başlangıcından sayılır.** Konuşma, başladıktan N gün sonra bütün turları ve başlığıyla silinir, dün devam edilmiş olsa bile.
  - Neden: başlık ilk sorunun başıdır. Süre son tura göre sayılsaydı, sürekli devam eden bir konuşmanın başlığı ilk soruyu yıllarca saklardı (security review B1).
  - Süresi dolmuş bir konuşmaya gelen soru yeni bir konuşma açar; yanıttaki `conversationId` yenisidir.
- **İş (`HistoryRetentionJob`):**
  - Her saat çalışır (`purge-interval`; ilk çalışma 5 dk sonra, `purge-initial-delay`).
  - Sırayla siler: süresi dolan konuşmaların turları (atıf ve puanlarıyla, `ON DELETE CASCADE`), boşalan konuşmalar, o günlerin hesap-gün etkinlik satırları.
  - Turlar önce ve parça parça silinir: 200 turluk bir konuşma tek bir cascade ifadesiyle rolün 10 sn ifade zaman aşımına takılmaz (db review 1).
  - **Çok instance:** her toplu silme kendi kısa transaction'ında `pg_try_advisory_xact_lock` altında çalışır (referans 10.4). Kilidi başka instance tutuyorsa o tur atlanır. Silme idempotenttir.
  - 500 satırlık parçalar; her silme türü için çalışma başına en çok 200 parça.
- **Yedek:** yedekler `BACKUP_RETENTION_DAYS` (7 gün) daha tutar. Geri yüklenen bir yedekteki süresi dolmuş satırları bir sonraki çalışma yeniden siler.

### 5. Cevap geri bildirimi

- `POST /v1/conversations/{conversationId}/turns/{turnId}/feedback`, gövde `{rating: UP|DOWN, reason?, citationNumber?}`.
- **Gerekçeler:** `WRONG_SOURCE` (hangi atıf), `INCORRECT_OR_MISSING`, `OUTDATED`, `OTHER`.
  - Gerekçe yalnız `DOWN` ile verilir.
  - `citationNumber` yalnız `WRONG_SOURCE` ile verilir ve cevapta gerçekten olan bir atıf olmalıdır (aksi 400 `FEEDBACK_CITATION_UNKNOWN`).
  - v1'de serbest metin yok: kişisel veri taşıyabilir ve ayrı saklama ile inceleme ister.
- **Turn başına tek satır:** yeniden puanlama öncekinin yerine geçer. Aynı transaction'da günlük sayaç eski puandan yenisine bir adım kayar.
  - Turn başına advisory kilit, eşzamanlı iki puanlamanın ikisinin de sayılmasını önler.
  - İki sayaç satırı her zaman aynı anahtar sırasıyla güncellenir: ters yönde puanlanan iki farklı turn birbirini kilitlemez (deadlock).
  - Puanlanırken silinen turn 404 `TURN_NOT_FOUND` döner.
- Yalnız turn'ün sahibi puanlar; başka konuşmanın ya da hesabın turn'ü 404 `TURN_NOT_FOUND`.

### 6. Kullanım metrikleri

- `GET /v1/admin/metrics?from=YYYY-MM-DD&to=YYYY-MM-DD`, yalnız `verso-admin` (`@PreAuthorize(VersoRoles.HAS_ADMIN)`, ADR-0019). Diğer herkes 403 `ACCESS_DENIED`. Aralık en çok 366 gün; ters ya da uzun aralık 400 `METRICS_RANGE_INVALID`.
- **Yol:** referansın `/backoffice/...` yolu ayrı bir yönetim servisi içindir. Şekil A'da yönetim uçları aynı uygulamada, `/v1` sürümüyle (ADR-0004) `/v1/admin/...` altında durur.
- **Sayaçlar (seçenek A):** `qa.usage_daily` (soru, sonuç türleri, local/cloud, aktif kullanıcı), `usage_daily_latency` (sabit kovalar, 250 ms – 180 sn), `usage_daily_document` (belge başına atıf), `usage_daily_feedback` (puan × gerekçe).
  - Gün, `verso.qa.metrics.time-zone` (varsayılan `Europe/Istanbul`) ile hesaplanır.
  - Sayaçlar hesap ve metin taşımaz; saklama süresinden ve kullanıcının silmesinden etkilenmez. Uygulama rolünün bu tablolarda `DELETE` yetkisi yoktur.
  - Başarısız sorular (503) saklanmaz ve burada sayılmaz; onlar Prometheus'tadır (ADR-0014).
- **Aktif kullanıcı:** günlük sayı kesindir.
  - Aralık için tekil sayı `qa.active_account_day`'den gelir. Bu tablo hesap kimliği taşır: geçmişle aynı sürede silinir, "tümünü sil" ile de silinir.
  - Aralığın bir kısmı saklama süresinden eskiyse `activeUsersComplete: false` döner.
  - Kullanıcı geçmişinin tamamını silerse onun hesap-gün satırları da gider: aralık sayısı düşer, `activeUsersComplete` yine true kalabilir. Aynı gün yeniden soru sorarsa günlük `activeUsers` bir kez daha artar; günlük sayı bu durumda bir üst sınırdır (operation review 5).
  - Yanıtta hesap kimliği hiç yoktur.
- **Belgeler:**
  - En çok atıf alan 10 belge (kimlik, ad, atıf sayısı); silinmiş belge `documentDeleted: true`.
  - Hiç atıf almamış `READY` belgeler (toplam + en eski 50). "Hiç" sayaçların başladığı günden beri demektir.
  - **Dosya adları ikinci bir politika parametresine bağlıdır:** `verso.qa.metrics.show-document-names`, ortam değişkeni `VERSO_SHOW_DOCUMENT_NAMES`, **varsayılan kapalı**.
    - Neden: bugün her belge tek bir hesabındır ve retrieval sahiplik filtrelidir; bir belgenin atıfları yalnız sahibinden gelir. Tek günlük aralıkta "Ayse_K_istifa.pdf: 7 atıf" bir çalışanın ne aradığını gösterirdi (security review B2).
    - Kapalıyken belgeler yalnız kimlik ve sayıyla döner (`documentNamesShown: false`). Kurum ortak bir bilgi tabanı işletiyorsa veri sorumlusu açar. Sahip hiçbir durumda gösterilmez.
  - "Hiç atıf almamış" listesi en çok 10 000 atıf almış belgeyle karşılaştırılır; daha fazlasında `complete: false` döner.
  - `DocumentCatalog`'un hesaplar arası iki metodu `document-core`'da da `@PreAuthorize(HAS_ADMIN)` taşır: hangi modül çağırırsa çağırsın admin olmayana kapalıdır.
- **Cevaplanamayan sorular (k-anonimlik):**
  - **Politika parametresi:** `verso.qa.metrics.show-unanswered-questions`, ortam değişkeni `VERSO_SHOW_UNANSWERED_QUESTIONS`, **varsayılan kapalı**. Kapalıyken soru metni hiçbir koşulda dönmez (`enabled: false`, boş liste). Açma kararı veri sorumlusunundur: çalışan aydınlatma metni bu amacı ("bilgi tabanındaki boşlukları bulmak") kapsamalıdır.
  - **Kural:** açıkken yalnız `NOT_FOUND` sonuçlu sorular, normalleştirilmiş biçimleri aynıysa gruplanır. Normalleştirme: NFKC, Türkçe küçük harf, tek boşluk, sondaki noktalama yok; grup anahtarı SHA-256.
  - Grup ancak **en az k = 3 farklı hesap** sormuşsa listelenir. k ayarla yükseltilebilir, 3'ün altına indirilemez (açılış kontrolü).
  - Eşik sorgunun içindedir (`HAVING count(DISTINCT account_id) >= k`): küçük gruplar veritabanından çıkmaz.
  - Gösterilen metin normalleştirilmiş biçimdir, tek bir kullanıcının yazımı değildir. En çok 20 soru.
  - Liste saklanan geçmişten okunur. Bir kullanıcı geçmişini silerse grubu k'nın altına düşebilir ve liste o soruyu artık göstermez.

### 7. Gizlilik ve log

- Soru, cevap, başlık ve dosya adı hiçbir log satırına, metrik etiketine ya da span'a girmez (llm-rules 2.1). Log'a id, sayı, süre ve sabit nedenler yazılır. Log privacy testleri: `ConversationApiTest`, `QuestionApiTest`.
- Geçmiş ve metrik yanıtları `Cache-Control: no-store, private`.
- Başlıkta kontrol karakteri ve yön değiştirme işareti (bidi) reddedilir. İlk başlık sorudan bunlar temizlenerek üretilir.
- **Silinen belge, kalan cevap:** belge silinince geçmişteki atıf işaretlenir. O belgeden alıntı yapan cevap metni ise saklama süresi dolana ya da kullanıcı silene kadar turda kalır (llm review L3). llm-rules 4.2'nin kapsamı (chunk, vektör, dosya) ihlal edilmez; aydınlatma metni bunu söylemelidir. Belge silinince turların da silinmesi, ortak bilgi alanları ADR'sinde yeniden değerlendirilir.
- **Hesap silme:** Verso'da hesap silme akışı yoktur (ADR-0011). IdP'de silinen bir kullanıcının geçmişi saklama süresi dolunca kendiliğinden silinir.
  - Kullanıcı kendi geçmişini `DELETE /v1/conversations` ile hemen silebilir. Bu turları, atıfları, puanları ve hesap-gün satırlarını siler; anonim sayaçlar kalır.
  - Yönetim panelinin hesap silme ucu (silme saga'sı, referans 21.9) geldiğinde, `qa` modülünün kapsamı bu ADR'deki tablolarla aynıdır.
- **Kişisel veri envanteri (ek):**

| Veri | Nerede | Ne kadar kalır |
|---|---|---|
| Soru, cevap, konuşma başlığı | `qa.turn`, `qa.conversation` | Konuşmanın başlangıcından `VERSO_HISTORY_RETENTION_DAYS` (90) gün ya da kullanıcı silene kadar; yedeklerde +7 gün |
| Atıf referansı (belge kimliği, sayfa) | `qa.turn_citation` | Turn ile |
| Puan ve gerekçe | `qa.turn_feedback` | Turn ile |
| Hesap × gün | `qa.active_account_day` | Geçmişle aynı süre; "tümünü sil" ile hemen |
| Günlük sayaçlar | `qa.usage_daily*` | Süresiz; hesap ve metin yok |

## Sonuçlar

- **Olumlu:**
  - İkinci şema referansın kuralıyla geldi: GRANT sınırı iki yönde, gerçek rollerle testli (`QaSchemaRolesTest`).
  - Var olan kurulumlar tek komutla yükseltilir.
  - Pilot raporunun sayıları (soru, kullanıcı, oranlar, süre, 👍/👎, ölü içerik) tek uçta ve saklama süresinden bağımsız.
  - Geçmiş KVKK saklama süresine bağlı; kullanıcı kendi geçmişini silebilir.
- **Olumsuz / kabul edilen risk:**
  - **Sıcak yolda ek DB yazımı.** Uzak çağrı sayısı değişmedi (2), ama soru başına bir kısa `qa` transaction'ı eklendi; yazma hatası turn'ü kaybettirir, cevabı değil.
  - **p50/p95 yaklaşık:** kova üst sınırıdır.
  - **Admin, politika açıksa dosya adlarını görür** (sahibini görmez). Kişisel yüklemeler varsa adlar kişisel veri taşıyabilir; bu yüzden varsayılan kapalıdır.
  - **Panel bu dalda değişmedi.** Sürümden sonra paneldeki her soru geçmişe yazılır, ama panelde geçmiş ekranı yoktur; kullanıcı geçmişini yalnız API ile görür ve siler. Geçmiş ve puanlama ekranı takip işidir; o gelene kadar kurulumlar bunu aydınlatma metnine yazmalıdır. Ekranlar [ADR-0025](0025-gecmis-geri-bildirim-deger-panosu.md) ile geldi.
  - **k-anonimlik sahte hesaplara karşı korumaz:** IdP'de çok hesap açabilen biri k eşiğini doldurabilir. Bu, kurulumun hesap yönetimine bağlıdır.
  - **Geri bildirim sayacı turn'ün gününe yazılır.** Eski bir cevaba bugün verilen puan o günün sayısını değiştirir.
  - **İki havuz:** bağlantı bütçesi 13'ten 18'e çıktı.
  - Konuşma başına 200 turn ve sayfa başına 50 konuşma sınırı; konuşma içindeki turlar sayfalanmaz.
- **Etkilenen dosyalar:**
  - `services/qa/qa-api` (DTO'lar), `services/qa/qa-core` (`config`, `controller`, `service`, `repository`, `worker`, `db/migration/qa`).
  - `services/document/document-api` (`DocumentCatalog`, `DocumentSummary`, `DocumentInventory`), `document-core` (`DocumentCatalogServiceImpl`, `DocumentRepository`, test-jar'da `MigrationConventions`).
  - `deploy/postgres/initdb/{10-roles,20-database,40-modules}.sh`, `deploy/backup/restore-check.sh`, `compose.yaml`, `.env.example`, `deploy/prod.env.example`, `config/verso.yml`, `application-local.yml`.
  - `scripts/{dev-secrets,restore-drill,restore-drill-selftest,mutation-check}.sh`, `secrets/README.md`.
  - Gözlem: `deploy/obs/prometheus/{alerts,alerts.test}.yml` (`HistoryNotStored`), `docs/runbooks/history-not-stored.md`, `application.yml` (hazırlık grubunda `qaDb`).
  - Testler: `ConversationApiTest`, `AdminMetricsApiTest`, `UnansweredQuestionsTest`, `HistoryRetentionTest`, `QaSchemaRolesTest`, `QaDatabaseUnavailableTest`; `DatabaseRolesTest`, `DeployConfigTest`, `MigrateModeTest`, `ModuleConsistencyTest`, `ComposeConfigTest`, `ConfigDriftTest` güncellendi.
- **Geri alma yolu:**
  - Uçlar ve `QuestionServiceImpl`'deki kayıt çağrısı kaldırılır; `qa` şeması veriyle birlikte kalabilir.
  - Rollerin ve şemanın kaldırılması elle yapılan bir DBA işidir (`DROP SCHEMA qa CASCADE`, `DROP ROLE`), migration değildir.
  - Yanıtlara eklenen alanlar (`conversationId`, `turnId`) eklemedir; istemciler yok sayar.

## Yeniden değerlendirme koşulu

- Geçmişin prompt'a girmesi (takip soruları): ayrı `verso-llm-review` ve bu ADR'ye ek.
- İlk modüller arası olay (audit, hesap silme): Modulith event registry ADR'si. Audit ile geldi: [ADR-0026](0026-denetim-kaydi.md) olayı süreç içi yayınlar, registry'yi eklemez.
- `qa.turn` büyüklüğü sunucu RAM'ine yaklaşırsa: zaman bazlı partition ve `DROP PARTITION` ile saklama (referans 10.6).
- Ortak bilgi alanları ya da kişisel yüklemelerin admin panosunda ad olarak görünmesine itiraz: dosya adı yerine sayı ya da maskeleme.
- Bir müşteri serbest metinli geri bildirim isterse: ayrı saklama süresi ve inceleme akışı.
