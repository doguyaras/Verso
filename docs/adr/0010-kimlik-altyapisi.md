# ADR-0010: Kimlik altyapısı (demo IdP, token kuralları, istemci akışları)

- **Durum:** Kabul edildi (faz 3)
- **Tarih:** 2026-10-02
- **Karar verenler:** doguyaras
- **İlgili eşik (referans Bölüm 24):** Servis kimliği satırı; ADR-0005'in uygulama ayrıntısıdır.

## Bağlam

ADR-0005, Verso'nun yalnız token doğrulayan bir resource server olmasını ve kimliğin harici bir OIDC IdP'de kalmasını seçti. Faz 3 bunu çalışır hale getirir. Açık kalan sorular şunlardı:

- `docker compose up` ile gelen demo IdP nasıl çalışacak, verisini nerede tutacak?
- API hangi token'ı kabul edecek? Referans 9.2 algoritmayı, `iss`/`aud`/`exp`'i ve `alg` karışıklığını kapsar; IdP'nin aynı anahtarla imzaladığı ID token'ı ve diğer JWT'leri kapsamaz.
- Arayüz yokken bir kullanıcı ve CI token'ı nasıl alır?
- Reddedilen istekler ortak zarfı (ADR-0004) ve log kurallarını bozmadan nasıl yanıtlanır?

## Seçenekler

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| A. Keycloak prod modda (`start`), aynı PostgreSQL'de kendi veritabanı ve rolü | Gerçek kurulumla aynı mod; tek Postgres; ayrı veritabanı, referans 10.1'in ayrım merdiveninin 2. basamağı | Postgres'e bir init script'i ve bir secret eklenir | Orta |
| B. Keycloak `start-dev` (gömülü H2) | En basit | Üretimle aynı değil; H2 dosyası yedeklenmez; dev modu sertleştirmeleri kapatır | Düşük |
| C. Keycloak için ayrı PostgreSQL container'ı | Tam izolasyon | Ek bellek ve bir container daha; demo için gereksiz | Orta |
| D. Keycloak tabloları Verso veritabanında, ayrı şemada | Tek veritabanı | Aynı veritabanında ortak patlama alanı; yedek ve ACL parmak izi karışır | Düşük |

## Karar

**A** seçildi.

**Demo IdP (compose):**

- `quay.io/keycloak/keycloak:26.7.5`, digest ile pinli; `start --import-realm` (prod modu).
- Veritabanı: `deploy/postgres/initdb/30-keycloak.sh` `keycloak` rolünü ve `keycloak` veritabanını oluşturur. `PUBLIC`'in `CONNECT` yetkisi kaldırılır; Verso rolleri bu veritabanına, `keycloak` rolü de Verso veritabanına bağlanamaz. Parolasız rol varsa script başarısız olur.
- Secret'lar dosyadır (`SECRET_DB_KEYCLOAK_PASSWORD`, `SECRET_KEYCLOAK_ADMIN_PASSWORD`, `SECRET_KEYCLOAK_CI_CLIENT_SECRET`, `SECRET_KEYCLOAK_DEMO_USER_PASSWORD`). `deploy/keycloak/start.sh` onları yalnız kendi sürecinin ortamına okur ve `kc.sh`'ı `exec` eder. Compose ortamına ve argüman listesine secret girmez. Realm dosyası (`deploy/keycloak/realm/verso-realm.json`) yalnız `${VERSO_*}` yer tutucuları taşır.
- Port yalnız `127.0.0.1:${VERSO_KEYCLOAK_PORT:-8180}`. `KC_HOSTNAME=http://localhost:8180` sabittir; böylece her token aynı `iss`'i taşır. Uygulama anahtarları compose ağı içinden (`http://keycloak:8080/.../certs`) alır (`KC_HOSTNAME_BACKCHANNEL_DYNAMIC`).
- Container `cap_drop: ALL` ve `no-new-privileges` ile çalışır. `read_only` değildir, çünkü Keycloak açılışta `/opt/keycloak` altına derlenmiş sunucuyu ve önbellekleri yazar.

**Token kuralları (`platform-security`, `JwtValidation`):**

| Kural | Neden |
|---|---|
| Yalnız ES256; JWKS'ten `kid` ile; `alg` başlığına göre doğrulayıcı seçilmez | Referans 9.2. RS256, HS256 (açık anahtarla imza, `alg` karışıklığı) ve `none` reddedilir |
| `iss` birebir eşit (sondaki `/` dahil) | Başka realm ve IdP'lerin token'ları |
| `aud` içinde `verso-api` | Aynı IdP'nin başka uygulamalar için verdiği token'lar |
| `exp` zorunlu, saat kayması 30 sn (en fazla 2 dk), `nbf` uygulanır | Süresiz token kabul edilmez |
| `sub` zorunlu ve `[A-Za-z0-9._@:-]{1,255}` | Hesap kimliği yalnız `sub`'dır (ADR-0005); yol ayırıcı veya kontrol karakteri taşıyamaz |
| JOSE `typ` başlığı `at+jwt` (RFC 9068) | Keycloak ID token'ını da aynı anahtarla imzalar ve `typ: JWT` yazar. `typ` kontrolü olmadan, `aud`'u `verso-api` içeren herhangi bir JWT access token yerine geçebilirdi |
| Token yalnız `Authorization: Bearer` başlığında | Query parametresi (`access_token=`) ve `Basic` kabul edilmez; URL'ler loglanır |
| JWKS: 2 sn bağlantı/okuma zaman aşımı, 64 KB boyut sınırı, 5 dk önbellek, bilinmeyen `kid` için en fazla 30 sn'de bir yeniden çekim, IdP kesintisinde son bilinen anahtarlar 1 saat | IdP yavaşsa iş parçacıkları beklemez. Uydurma `kid`'li token'lar IdP'ye istek yağdıramaz (faz 3 review'ları C3/S2/R1: önce her token bir JWKS isteğiydi); hız sınırına takılan bilinmeyen `kid` geçersiz token'dır (401) |
| JWKS adresi `https`; düz `http` yalnız loopback'te ya da açık izinle (`OIDC_JWKS_ALLOW_HTTP`, compose ağı) | Anahtar çekimine araya giren biri her hesap için token üretebilirdi (S6) |

EdDSA şimdilik kapalıdır: Nimbus'un Ed25519 doğrulayıcısı ya yeni bir bağımlılık (Google Tink; stack dışı, kullanıcı onayı gerekir) ya da Nimbus'a özel bir JDK doğrulayıcı fabrikası ister. Token'ı harici IdP imzaladığından ES256 yeterlidir (ADR-0007 #49). Referans 9.2'deki `typ` claim'i ve `sv` iptali yerine JOSE `typ: at+jwt` ve kısa token ömrü kullanılır (ADR-0007 #50).

**İstemciler (realm `verso`):**

- `verso-cli`: public istemci; yalnız device authorization grant (RFC 8628), PKCE S256 zorunlu. Onay ekranı açıktır (`consentRequired`): kullanıcı hangi istemciye izin verdiğini görür (device code oltalamasına karşı). Kapsamlar yalnız `basic` (`sub`); `offline_access` istenemez, istenirse yok sayılır (canlı doğrulandı: refresh token türü `Refresh`, `Offline` değil). `scripts/demo-token.sh` bu akışı çalıştırır; device code ve PKCE verifier komut satırına değil, özel bir geçici klasördeki dosyalara yazılır.
- `verso-ci`: gizli istemci; yalnız client credentials, CI smoke testi için (`scripts/auth-smoke.sh`).
- Her iki istemci: `access.token.header.type.rfc9068=true`, audience mapper `verso-api`, varsayılan kapsam yalnız `basic`. Password grant (ROPC), implicit ve standard flow kapalıdır. Keycloak'ın her realm'e koyduğu `admin-cli` istemcisi (password grant açık gelir) bu realm'de kapalıdır. Web arayüzü (faz 10) authorization code + PKCE istemcisini kendisi ekler.
- Realm: imza `ES256` (P-256, `ecdsa-generated`), access token 300 sn, brute-force koruması, kayıt ve parola sıfırlama kapalı.

**Hata yanıtları (ADR-0004 zarfı):**

| Durum | HTTP | Kod | Başlık |
|---|---|---|---|
| Token yok | 401 | `UNAUTHENTICATED` 90100 | `WWW-Authenticate: Bearer` |
| Token reddedildi | 401 | 90100 | `WWW-Authenticate: Bearer error="invalid_token"` |
| Yetki yok (`AccessDeniedException`, `@PreAuthorize`'ın `AuthorizationDeniedException`'ı dahil) | 403 | `ACCESS_DENIED` 90101 | `WWW-Authenticate: Bearer error="insufficient_scope"` (RFC 6750 3.1) |
| Token denetlenemedi: IdP anahtarlarına ulaşılamıyor ve önbellek boş | 503 | `IDP_UNAVAILABLE` 90103 | `Retry-After: 30` |
| Firewall (normalleşmemiş yol, nokta segmentleri) | 400 | `REQUEST_REJECTED` 90004 | – |

Yanıt gövdesi reddin nedenini taşımaz. Log yalnız kaba nedeni (`NO_TOKEN`, `INVALID_TOKEN`) ve trace id'yi yazar; token, `sub` ve doğrulayıcının ayrıntılı mesajı hiçbir yere yazılmaz. Global handler güvenlik istisnalarını 500'e çevirmez; Spring Security'ye geri fırlatır (`ErrorClassifier.isSecurityException`).

Health uçları yönetim portunda token istemez (compose healthcheck, orkestratör); `/actuator` ve `/actuator/info` ister. Yönetim portu yayınlanmaz.

**Filtre zinciri ve hesap kimliği:**

- API zinciri her isteği yakalayan son zincirdir (`@Order(LOWEST_PRECEDENCE - 10)`) ve her zaman vardır. Başka kurallar isteyen bir modül (webhook, panel) kendi zincirini `securityMatcher` ve daha düşük bir `@Order` ile önüne ekler. Önceki `@ConditionalOnMissingBean(SecurityFilterChain.class)`, ilk ek zincirde API korumasını tamamen kapatıyordu (faz 3 review'ları C1/S4: token'sız istek controller'a ulaştı).
- `@CurrentAccount` yalnız `AccountId` tipinde olabilir. Başka tipte bir parametre uygulamayı açılışta durdurur; resolver her `@CurrentAccount` parametresini üstlenir. Önceden `@CurrentAccount String account` sessizce query'den bağlanıyordu (S1: `?account=victim` ile başka hesap).

**Dayanıklılık (AGENTS.md §5):** JWKS çağrısında ayrı bir circuit breaker ve bulkhead yoktur. Eşdeğerini anahtar kaynağı sağlar: önbellek (istek başına çağrı yok), yeniden çekim hız sınırı (açık devre gibi IdP'yi korur), kesinti toleransı (son bilinen anahtarlar) ve zaman aşımı. Bir CB kütüphanesi eklemek bu çağrı için yeni bağımlılık olurdu (ADR-0007 #52).

**Reddedilenler:** B üretimle aynı modda çalışmaz ve verisini yedeksiz bir dosyada tutar. C demo için ek bellek ister. D iki sistemin verisini ve yetkilerini aynı veritabanında karıştırır.

## Sonuçlar

- **Olumlu:**
  - `docker compose up` ile gerçek bir OIDC akışı gelir; CI her ilgili PR'da token alıp API'yi dener.
  - Verso kodu IdP'ye özgü bir şey bilmez: üretimde `OIDC_ISSUER` ve `OIDC_JWK_SET_URI` müşterinin IdP'sini gösterir.
- **Olumsuz / kabul edilen risk:**
  - `keycloak` veritabanı yedeklenmez ve restore provasına girmez. Demo realm'i dosyadan yeniden kurulur; üretimde IdP müşterinindir.
  - Faz 2'de oluşmuş bir veri volume'ünde init script'leri yeniden çalışmaz. `30-keycloak.sh` idempotenttir; README'deki yükseltme adımı onu elle çalıştırır (canlı denendi: rol ve veritabanı yokken kurdu, ikinci koşu değişiklik yapmadı, parola `pg_stat_statements`'e girmedi).
  - Uydurma `kid`'lerle hız sınırı tüketilirse, IdP'nin gerçekten yeni bir anahtara geçtiği ilk 30 sn'de yeni anahtarlı token'lar 401 alabilir. Rotasyonda IdP yeni anahtarı imzada kullanmadan önce JWKS'te yayınladığı için pratikte görülmez.
  - IdP 1 saatten uzun kapalı kalırsa son bilinen anahtarlar da bırakılır ve istekler 503 alır. Bir anahtarın IdP'den kaldırılması, IdP kapalıyken en geç bu süre sonra etkili olur.
  - Realm içe aktarma yalnız ilk açılışta çalışır (var olan realm korunur). Secret değişince yeni volume veya yönetim konsolu gerekir (`secrets/README.md`).
  - Master realm yönetim konsolu `127.0.0.1:8180/admin`'de açıktır (yalnız loopback; parola secret dosyasında).
  - IdP'nin TLS'i yoktur; yalnız loopback ve compose ağı içindir. Üretimde TLS sonlandıran bir ters vekil ve `https` issuer beklenir. Uygulama JWKS adresinde bunu zorlar: `http` yalnız loopback'te veya `OIDC_JWKS_ALLOW_HTTP=true` ile kabul edilir (`VersoJwtProperties`); compose bu izni yalnız kendi iç ağı için verir.
- **Etkilenen dosyalar:** `platform/platform-security/`, `platform-core` (`ErrorClassifier`, `GlobalServiceExceptionHandler`), `compose.yaml` (keycloak), `deploy/keycloak/`, `deploy/postgres/initdb/30-keycloak.sh`, `config/verso.yml` (`verso.security.jwt`), `scripts/{auth-smoke,demo-token,dev-secrets}.sh`, `.github/workflows/restore-drill.yml`.
- **Geri alma yolu:** IdP'yi değiştirmek `OIDC_ISSUER`/`OIDC_JWK_SET_URI` ve gerekirse `verso.security.jwt.type-header` ayarıdır. Keycloak servisi compose'tan kaldırılabilir; uygulama ona bağlı değildir.

## Yeniden değerlendirme koşulu

- Müşteri IdP'si `at+jwt` yazmıyorsa: `type-header` boş bırakılır ve yerine başka bir ayırt edici kural (ör. `token_use`/`scope`) ADR ile seçilir.
- EdDSA isteyen bir IdP: Tink bağımlılığı onaylanır ve ES256 + EdDSA birlikte açılır.
- Web arayüzü (faz 10): tarayıcı istemcisi, oturum ve CSRF kararı.
- IdP kesintisi 1 saati aşıyorsa veya 503 `IDP_UNAVAILABLE` alarmı görülürse: kesinti toleransı ve JWKS sağlık göstergesi (faz 7).
