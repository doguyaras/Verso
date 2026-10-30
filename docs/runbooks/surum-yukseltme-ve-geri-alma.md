# Sürüm yükseltme ve geri alma

**Anlamı:** Müşteri kurulumunda yeni bir `v*` sürümüne geçiş ve sorun çıkarsa geri dönüş (ADR-0040, referans 18.1 ve 18.4). Alarmı yok; her yükseltmede ve her geri almada elle izlenir. İmaj yalnız CI'da bir kez build edilir, imzalanır ve **digest ile** kurulur (`deploy/compose.release.yaml`); üretim sunucusunda `docker compose build` çalıştırılmaz.

**Kısa kural:** geri alma imajı geri alır, **veriyi almaz**. Yeni sürümün yazdığı satırlar, `audit.event` kayıtları ve outbox satırları kalır. Şema geri uyumluysa önceki digest'i yeniden kurmak yeter; değilse yükseltmeden önce alınan yedeğe dönülür ve o yedekten sonraki yazımlar kaybolur.

## 0. Ön kontroller (hiçbiri atlanmaz)

| Kontrol | Komut / ölçüt |
|---|---|
| Yeni ve önceki digest elde | `export VERSO_IMAGE=ghcr.io/<owner>/verso@sha256:<yeni>`; **şu an çalışanı not et**: `docker inspect $(docker compose ps -q verso-app) --format '{{.Config.Image}}'` ve `docker image inspect <imaj> --format '{{index .RepoDigests 0}}'`; çıktıyı yükseltme kaydına yaz (`ONCEKI_IMAJ`) |
| Sürüm doğrulaması | `bash scripts/verify-release.sh` (cosign imzası + CycloneDX atestasyonu, imzalayan = bu deponun `release.yml` iş akışı, `refs/tags/v*`). **Kırmızıysa yükseltme durur.** Tag kabul edilmez, yalnız `@sha256:` |
| Yedek | `docker compose run --rm backup once`; ardından dosyanın yaşına ve boyutuna bak (`backups` volume'ü: `verso-<UTC>.dump.gpg` + `.sha256` + `.manifest` + `.acl`). Yedeğin dosya adını kaydet (`YEDEK`) |
| Yedeğin geri yüklenebildiği | `bash scripts/restore-drill.sh` (geçici veritabanına geri yükler, satır sayıları, yetkiler ve Flyway `validate`). Yeşil değilse yükseltmeye başlama |
| Keycloak (paketli demo IdP ise) | `keycloak` veritabanı Verso yedeğine **girmez** (ADR-0010). Bkz. "Keycloak" aşağıda; yükseltmeden önce ayrı dökümü al |
| Boş disk | `docker system df` ve `df -h` (Docker kök dizini): yeni imaj (yüzlerce MB) + yedek (`backups` volume'ü) + model volume'ü büyümesi için en az 5 GB; Ollama modeli **değişiyorsa** ek ~5 GB (aşağıda "Ollama") |
| Migration notları | Sürüm notlarında / ADR'de "geriye uyumsuz" geçen migration var mı? Aşağıdaki liste ve `-- contract:` işaretli dosyalar: `grep -rl "^-- contract:" services/*/*/src/main/resources/db/migration` (checkout, hedef etiketteyken) |
| Mesai dışı | `CREATE INDEX` / `ADD COLUMN ... STORED` migration'ları yazmayı kilitler (örnek: document V3, audit V2/V3); migration dosyasının "Rollout" notunu oku |

## 1. Yükseltme sırası

Sıra **sabittir**: `pull → verify → migrate → app → edge → smoke → yedeği tut`. Checkout, hedef sürümün etiketindedir (`git checkout v<sürüm>`): compose dosyaları, kenar proxy ayarı, panel, realm ve init script'leri imajda değil depodadır. Her komut `-f compose.yaml -f deploy/compose.release.yaml` ile çalışır; aşağıda `DC` olarak kısaltıldı:

```bash
DC="docker compose -f compose.yaml -f deploy/compose.release.yaml"
```

1. **pull:** `$DC pull verso-app` (digest'li imaj çekilir; `migrate` aynı imajdır).
2. **verify:** `bash scripts/verify-release.sh` (ön kontrolde yapıldıysa ve `VERSO_IMAGE` değişmediyse tekrar gerekmez; pull'dan sonra yeniden çalıştırmak ucuzdur ve **önerilir**: doğrulanan şey, çalışacak digest'tir).
3. **migrate (eski uygulama durmuş):** önce `$DC stop verso-app edge`. ADR-0028 notu: aşamalı yayında eski örnek yeni şemaya eksik yazabilir (örnek: `document_principal` yazmayan eski kod principal'sız, yani kimsenin okuyamadığı belge ekler). Sonra `$DC run --rm migrate`; çıkış kodu 0 olmalı, log'da hangi sürümlerin uygulandığı yazar. Hata varsa **uygulamayı başlatma**: bkz. "Geri alma".
4. **app:** `$DC up -d --wait verso-app`. Sağlık: `docker compose ps verso-app` → `healthy`.
5. **edge:** `$DC up -d --wait edge`. Kenar proxy ve panel dosyaları depodan bağlanır; etiket değiştiyse `edge` yeniden oluşur.
6. **smoke:** `bash scripts/qa-smoke.sh` (gerçek token, yükleme, iki soru) ve `bash scripts/prove-local-mode.sh` (yalnız `local` modda). Smoke betikleri Keycloak'ın demo `verso-ci` istemcisini kullanır; kendi IdP'nizi kullanan kurulumda aynı kanıtı kendi test token'ınızla alın (`GET /v1/documents` → 401 değil 200, bir soru → `X-Rag-Mode` başlığı).
7. **yedeği tut:** yükseltme öncesi yedek (`YEDEK`) ve önceki digest kaydı **en az bir sonraki başarılı yedek döngüsü + 7 gün** silinmez. Yedek saklama (`BACKUP_RETENTION_DAYS`, varsayılan 7) en yeni yedeği silmez ama yükseltme öncesi yedek eskiyince silinebilir: dosyaları `backups` volume'ünden host dışına kopyala. Sonra yeni bir yedek al (`backup once`): yükseltmeden önceki yedekte yeni şema yoktur ve restore provası yeni şemayla tutarsız olabilir.

Aşamalı (rolling) yayın bu kurulum için **tanımlı değildir** (tek `verso-app`); durdurup başlatmak kısa kesinti demektir (migrate süresi + açılış, `start_period` 60 sn).

## 2. Migration politikası: expand/contract

Kural (referans 18.4): yeni sürümün şeması, **önceki uygulama sürümüyle çalışabilmelidir**; böylece geri alma yalnız imajı değiştirir.

- **Expand (aynı sürümde serbest):** yeni tablo, yeni nullable kolon ya da `DEFAULT`lu kolon, yeni indeks. Önceki imaj bunları yok sayar.
- **Contract (silme, yeniden adlandırma, tip değişimi, kolonu `NOT NULL` yapma):** **ayrı, sonraki sürümde**; önceki sürümün (N-1) artık hiçbir yerde çalışmayacağı bilindikten sonra. Contract'tan sonra eski imaja dönüş **yoktur**, yalnız yedeğe dönüş vardır.
- **CI zorlaması:** PR'da yeni eklenen bir `V*.sql` yorum ve metin dışında `DROP` ya da `RENAME` içeriyorsa dosyada `-- contract: <neden>` satırı olmalıdır, yoksa CI kırmızı olur (`scripts/migration-contract.js`, ADR-0040). İşaret kuralı kurtarmaz, **bilinçli olmayı** zorlar: yalnız yukarıdaki contract kuralına uyan değişikliklere konur ve sürüm notuna "geri alma = yedek" yazılır.
- Base'e girmiş migration değişmez (`AGENTS.md` 6); düzeltme yeni `V<sonraki>` ile.

### Bugünkü migration'lar ve geri alma etkisi (`V*.sql` taraması, 2026-10-24)

`DROP` / `RENAME` taraması (yorumlar hariç) yalnız **bir** dosyada sonuç verir; kalan "geriye uyumsuz" etkiler davranışsaldır, bu yüzden elle de gözden geçirildi.

| Migration | Önceki imajla uyum | Geri alma için anlamı |
|---|---|---|
| `document/V2__document_formats.sql` | **DROP içerir** (`ck_document_failure_reason`, aynı ifadede daha geniş olarak yeniden eklenir; `format` kolonu `DEFAULT 'PDF'`) | Önceki imaj yazabildiği değerlerle çalışır (genişletilmiş kısıt eskisini kapsar). Ama yeni imajın yazdığı `format` = DOCX/TXT/MD ve yeni `failure_reason` değerleri **eski imajda tanımsızdır** (eski kod yalnız PDF bilir; davranışı doğrulanmadı). Geri almadan önce yeni biçimli belgeler varsa silinmeli ya da yedeğe dönülmeli |
| `document/V4__collections.sql` (ADR-0028) | Şema olarak eklemedir, **davranış olarak geriye uyumsuz** | Eski imaj belge eklerken `document_principal` yazmaz; principal'sız belge **kimse için okunmaz** (kapalı kalma). Ayrıca `collection_grant` ve `document_principal` üzerinde `svc_document`un `UPDATE` hakkı kaldırılır. Geri alma sonrası eski imajla yüklenen belgeler paylaşımlı sürüme dönüşte okunamaz (principal'ı yok): geri almak istemiyorsan ileri düzelt |
| `audit/V3__event_delivery_key.sql` (ADR-0039) | Eskisi sütunu yok sayar; `event_id` nullable, indeks tekil | Geri alma güvenli. Ama yeni imajın `document`/`qa` outbox satırları (`V5`/`V3`) eski imaj tarafından **teslim edilmez** (eski imaj outbox'ı bilmez): bekleyen denetim kayıtları outbox'ta kalır ve yeni imaja dönülünce teslim edilir. Geri alınmış dönemde eski imaj denetimi doğrudan yazar (ADR-0039). Bu aralıkta kayıt kaybı olmaz, ama sıra karışabilir (`occurred_at` kayar, [outbox-stuck.md](outbox-stuck.md)) |
| `document/V5__outbox_event.sql`, `qa/V3__outbox_event.sql` | Yeni boş tablo; eski imaj dokunmaz | Geri alma güvenli; yukarıdaki teslim notu geçerli |
| `document/V3__chunk_search_vector.sql` | `ADD COLUMN ... GENERATED ... STORED` + GIN indeks; eski imaj sütunu yok sayar | Geri alma güvenli; eski imaj hibrit arama yapmaz (ADR-0022) |
| `document/V1`, `qa/V1`, `qa/V2`, `audit/V1`, `audit/V2` | Ekleme (tablo, indeks, fonksiyon) | Geri alma güvenli |
| `document/V7__chunk_context_header.sql` (ADR-0061) | Yeni nullable kolonlar, iki `CHECK` ve bir GIN indeks; eski imaj yok sayar | Geri alma güvenli. **Migrate süresi:** iki `CHECK` (`NOT VALID` değil) `document_chunk`'ı `ACCESS EXCLUSIVE` kilitle, indeks `SHARE` kilitle birer kez tarar. Bütün satırlar NULL olduğu için hesap yoktur, ama büyük bir külliyatta tarama dakikalar sürebilir. Bu yüzden uygulama 3. adımda durdurulmuş olmalıdır (durmuş uygulamada istek beklemez) |
| `document/V8__drop_header_vector_check.sql` | Yalnız bir `CHECK` kaldırılır (`-- contract:` gerekçeli); tarama yok, kısa kilit | Geri alma güvenli. Kaldırılan kısıt `to_tsvector` çıktısını karşılaştırıyordu; sözlüğü değişen bir PostgreSQL sürümüne geri yüklemeyi kırardı |

**ADR-0061 yeniden indekslemesinden sonra** (`ContextHeaderBackfillJob` bütün belgeleri yeni başlıkla yeniden gömer): iş, her parçayı aynı kimlikle silip yeniden yazar. Bu, HNSW indeksinde ölü kayıtlar bırakır ve vacuum'a kadar süzgeçli aramalar k'dan az sonuç dönebilir. İşin bittiği, `document.document` içinde `context_version` değeri güncel olmayan READY belge kalmamasından anlaşılır. Bittiğinde bir kez çalıştırın:

```bash
$DC exec postgres psql -U postgres -d verso -c "VACUUM (ANALYZE) document.document_chunk"
```

Sürüm notunda bir sonraki sürümün migration'ı için bu tablo güncellenir.

## 3. Geri alma

**Önce teşhis et:** hata migration'dan mı (adım 3), uygulamadan mı (adım 4-6)?

| Durum | Yol |
|---|---|
| Migrate **başarısız** oldu (Flyway tek transaction'da çalışır, kısmi şema kalmaz) | Uygulamayı başlatma. Hatayı düzelt (çoğunlukla rol/secret/disk) ya da önceki imajı `VERSO_IMAGE`'a koyup (aşağıda) başlat; şema değişmediği için bu güvenlidir |
| Migrate başarılı, **şema geri uyumlu** (yukarıdaki tabloda "güvenli"), uygulama bozuk | **İmaj geri alma:** `export VERSO_IMAGE=<ONCEKI_IMAJ>`; `bash scripts/verify-release.sh` (eski digest de imzalıdır); `$DC up -d --wait verso-app edge`; smoke. `migrate` ayrıca çalıştırılmaz (şema zaten yeni; uygulamada Flyway kapalıdır: `SPRING_FLYWAY_ENABLED=false`, şemayı yalnız `migrate` değiştirir). `up` bağımlılık olarak `migrate`i eski digest ile bir kez daha çalıştırır; eski imajın migration seti daha kısadır ve yeni sürümleri uygulamaz. Eski imajın Flyway'inin şemadaki bilmediği sürümleri nasıl karşıladığı: **net kanıt bulunamadı**, staging provasında gör |
| Şema **geriye uyumsuz** (V2 yeni biçimli belgeler, V4 principal'sız belgeler gibi) ya da veri bozuldu | **Yedekten dönüş**, aşağıda. Yükseltmeden sonraki yazımlar kaybolur (RPO = yedek anı) |

**Yedekten dönüş (canlı veritabanı).** Canlı veritabanını yedekten geri yükleyen **otomatik betik yok** (net kanıt bulunamadı: `scripts/restore-drill.sh` geçici veritabanıyla çalışır, canlıya dokunmaz; ADR-0009). Elle yol, `deploy/backup/restore-check.sh`'nin adımlarıyla aynıdır ve önce provayla doğrulanmış olmalıdır:

1. `$DC stop verso-app edge` (yazmayı durdur). Mevcut durumu kaybetmemek için önce **bozuk haliyle de bir yedek al** (`backup once`) ve host dışına kopyala.
2. Yedeği doğrula: `.sha256`, sonra `gpg --batch --pinentry-mode loopback --passphrase-file <SECRET_BACKUP_ENCRYPTION_KEY dosyası> --decrypt verso-<UTC>.dump.gpg > restore.dump` (yedek gpg simetrik AES256).
3. Boş bir veri volume'üne kurulumu yeniden yap: roller ve şemalar `deploy/postgres/initdb` ile yalnız **boş volume'de** kurulur. Eski `pgdata` volume'ünü **silme**, yeniden adlandırılmış kopya olarak tut (`docker volume` yeniden adlandırmaz: yeni volume adı ya da önce `docker run ... tar` ile kopya); sonra `docker compose up -d --wait postgres` (init yeniden çalışır).
4. `pg_restore --exit-on-error --dbname=verso restore.dump` ile (superuser olarak; `restore-check.sh` ile aynı çağrı, `--no-privileges` verme: yetkiler dökümle gelir) `verso` veritabanına geri yükle. Satır sayılarını `.manifest` ile, yetkileri `.acl` ile karşılaştır (`deploy/backup/queries.sh`).
5. **Önceki imajı** (yedeğin alındığı sürümün) `VERSO_IMAGE`'a koy, `verify-release.sh`, `$DC run --rm migrate` (şema yedektekiyle aynı, Flyway no-op), `up -d --wait`, smoke.
6. Olay kaydına yaz: yedek anı, kaybolan aralık (yedek anı → geri alma anı). Bu aralıkta yüklenen belgeler ve sorulan sorular yeniden gerekir; KVKK silme isteği bu aralıktaysa yeniden uygula ([kvkk-basvurusu.md](kvkk-basvurusu.md)).

Not: bu elle yol RTO hedefi (1 saat) için **provalı değildir**; ilk gerçek ihtiyaçtan önce staging'de "N+1 deploy → veri yaz → N'e dön → oku" provasını yap (referans 18.4).

### Keycloak (paketli demo IdP): şema yükseltmesi tek yönlüdür

Keycloak 26.8 açılışta kendi şemasını yükseltir ve **geri alınamaz**: eski Keycloak imajı yükseltilmiş `keycloak` veritabanıyla açılmaz. Verso yedeği bu veritabanını kapsamaz. Keycloak imajını değiştiren her yükseltmeden önce ayrı döküm al ve host dışına kopyala (dosya şifresizdir; saklamadan önce şifrele):

```bash
docker compose exec -T postgres pg_dump -U postgres -Fc keycloak > keycloak-$(date -u +%Y%m%dT%H%M%SZ).dump
```

Geri dönüş: eski Keycloak imajı + bu dökümün boş bir `keycloak` veritabanına `pg_restore`'u. Üretimde IdP müşterinindir (ADR-0010); bu bölüm yalnız paketli demo içindir.

### Ollama model volume'ü (ADR-0033)

- Model volume'ü (`ollama-models`) yükseltmede **silinmez** ve kurulum sonrası internetsiz çalışır; çalışan Ollama salt okunurdur, taşıma `ollama-pull` servisindedir.
- **Ollama imajı ya da model digest'i değişen** sürümde `ollama-pull` yazılabilir tek süreçtir; model katmanı (blob) aynı kalırsa yeniden indirmez, yalnız manifest yazar (v1 → v2 taşıma, ~birkaç MB). İmaj değişimi **registry'ye bağlanır**: air-gapped kurulumda önce ağdan çıkarılmış ortamda `ollama-pull` çalıştırılıp volume taşınır.
- **Geri alma:** Ollama 0.40'ın yazdığı `manifests-v2` dizinini 0.35 okumaz ama `manifests` (v1) de yazıldığı için eski imaj modeli bulur (ADR-0033 madde 2). Eski bir Ollama sürümüne dönmek gerekirse volume'ü yedeklemeden **model silme**.
- Disk: `bge-m3:567m` 1,2 GB + `gemma4:e2b` 3,5 GB; model değişen sürümde eskisi silinmez, ek yer gerekir.

### Air-gapped kurulum ve doğrulama

`verify-release.sh` cosign v3 ile **anahtarsız** doğrular: sertifika zinciri Sigstore'un Fulcio kök sertifikasına, imza kaydı Rekor şeffaflık günlüğüne bağlanır. Dışarıya çıkışı olmayan sunucuda:

- İmaj, imza ve atestasyon üç ayrı OCI nesnesidir; ağdan çıkarılmış bir makinede birlikte aynalanır (`cosign copy` ya da `cosign save` + `cosign load`) ve **aynı digest** ile doğrulanır; imza digest'i imzaladığı için ayna kayıt güvenilmeyebilir, digest ve imza yeter.
- `VERSO_VERIFY_OFFLINE=1`: Rekor sorgusu yapılmaz; imzadaki gömülü kayıt zaman damgası (SET) kontrol edilir. Rekor'da kaydın **hâlâ var** olduğunu (iptal/silme gözlemi) bu mod kanıtlamaz.
- `COSIGN_TRUSTED_ROOT=<trusted_root.json>`: Sigstore güven kökü (Fulcio/Rekor anahtarları) bağlı bir makinede alınıp taşınır; TUF güncellemesi yapılamayan makinede bu dosya gerekir. Dosya eskirse (Sigstore anahtar döngüsü) yeniden alınmalıdır.
- Bu offline yol bu depoda **gerçek bir air-gapped makinede denenmedi** (net kanıt bulunamadı); `verify-release.test.js` yalnız bayrakların geçtiğini kanıtlar. İlk müşteri kurulumundan önce bağlantısız bir makinede denenmelidir.

## 4. İleri düzeltme mi, geri alma mı

| Durum | Karar | Neden |
|---|---|---|
| Uygulama açılmıyor, migrate **çalışmadı** ya da şema geri uyumlu | **Geri al** (imaj) | Dakikalar; veri kaybı yok |
| Küçük, yerel hata (tek uç, panel metni), veri doğru | **İleri düzelt** | Yeni `v*` etiketi; CI + imza + doğrulama yolu aynı |
| Migration geriye uyumsuz uygulandı, ama yeni sürümde yazılmış veri **az** ve yeniden üretilebilir | **İleri düzelt** (yeni sürümü düzelt) | Yedeğe dönüş yazımları siler; contract sonrası eski imajla uyum yok |
| Veri **bozuldu** ya da yanlış yazılıyor (yanlış principal, silinen veri) | **Yedekten dönüş** + sorunlu sürümü yayından çek | Düzeltme verinin kendisini onarmaz |
| Denetim/outbox satırları `DEAD` ya da yaş artıyor | **İleri düzelt** ([outbox-stuck.md](outbox-stuck.md)) | Geri alma outbox teslimini durdurur |
| Güvenlik açığı (yeni sürümde) | **Geri al** (şema uyumluysa), değilse hizmeti durdur | Açık süresi öncelik; yedekten dönüş son çare |
| Keycloak/IdP yükseltmesi sorunlu | **İleri düzelt** (ya da `keycloak` dökümüne dön) | Şema yükseltmesi tek yönlü |
| Karar verilemiyor | Hizmeti **durdur** (`$DC stop verso-app edge`), yedek al, düşün | Yanlış yazılan veri geri alınamaz |

**Kapanış:** `$DC ps` hepsi `healthy`; smoke yeşil; yeni bir yedek alındı (`backup once`); yükseltme kaydında yeni ve önceki digest, yedek dosya adı ve sonuç yazılı; bir geri alma olduysa kök neden için ADR/runbook güncellemesi açıldı.
