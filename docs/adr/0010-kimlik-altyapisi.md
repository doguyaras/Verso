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
| JWKS çağrısı 2 sn bağlantı/okuma zaman aşımıyla | IdP yavaşsa istek iş parçacıkları beklemez |

EdDSA şimdilik kapalıdır: Nimbus'un Ed25519 doğrulayıcısı ya yeni bir bağımlılık (Google Tink; stack dışı, kullanıcı onayı gerekir) ya da Nimbus'a özel bir JDK doğrulayıcı fabrikası ister. Token'ı harici IdP imzaladığından ES256 yeterlidir (ADR-0007 #49). Referans 9.2'deki `typ` claim'i ve `sv` iptali yerine JOSE `typ: at+jwt` ve kısa token ömrü kullanılır (ADR-0007 #50).

**İstemciler (realm `verso`):**

- `verso-cli`: public istemci; yalnız device authorization grant (RFC 8628), PKCE S256 zorunlu. Onay ekranı açık kalır: kullanıcı hangi istemciye izin verdiğini görür (device code oltalamasına karşı). `scripts/demo-token.sh` bu akışı çalıştırır.
- `verso-ci`: gizli istemci; yalnız client credentials, CI smoke testi için (`scripts/auth-smoke.sh`).
- Her iki istemci: `access.token.header.type.rfc9068=true`, audience mapper `verso-api`. Password grant (ROPC), implicit ve standard flow kapalıdır. Web arayüzü (faz 10) authorization code + PKCE istemcisini kendisi ekler.
- Realm: imza `ES256` (P-256, `ecdsa-generated`), access token 300 sn, brute-force koruması, kayıt ve parola sıfırlama kapalı.

**Hata yanıtları (ADR-0004 zarfı):**

| Durum | HTTP | Kod | Başlık |
|---|---|---|---|
| Token yok | 401 | `UNAUTHENTICATED` 90100 | `WWW-Authenticate: Bearer` |
| Token reddedildi | 401 | 90100 | `WWW-Authenticate: Bearer error="invalid_token"` |
| Yetki yok (`AccessDeniedException`) | 403 | `ACCESS_DENIED` 90101 | – |
| Firewall (normalleşmemiş yol, nokta segmentleri) | 400 | `REQUEST_REJECTED` 90004 | – |

Yanıt gövdesi reddin nedenini taşımaz. Log yalnız kaba nedeni (`NO_TOKEN`, `INVALID_TOKEN`) ve trace id'yi yazar; token, `sub` ve doğrulayıcının ayrıntılı mesajı hiçbir yere yazılmaz. Global handler güvenlik istisnalarını 500'e çevirmez; Spring Security'ye geri fırlatır (`ErrorClassifier.isSecurityException`).

Health uçları yönetim portunda token istemez (compose healthcheck, orkestratör). Yönetim portu yayınlanmaz.

**Reddedilenler:** B üretimle aynı modda çalışmaz ve verisini yedeksiz bir dosyada tutar. C demo için ek bellek ister. D iki sistemin verisini ve yetkilerini aynı veritabanında karıştırır.

## Sonuçlar

- **Olumlu:**
  - `docker compose up` ile gerçek bir OIDC akışı gelir; CI her ilgili PR'da token alıp API'yi dener.
  - Verso kodu IdP'ye özgü bir şey bilmez: üretimde `OIDC_ISSUER` ve `OIDC_JWK_SET_URI` müşterinin IdP'sini gösterir.
- **Olumsuz / kabul edilen risk:**
  - `keycloak` veritabanı yedeklenmez ve restore provasına girmez. Demo realm'i dosyadan yeniden kurulur; üretimde IdP müşterinindir.
  - Realm içe aktarma yalnız ilk açılışta çalışır (var olan realm korunur). Secret değişince yeni volume veya yönetim konsolu gerekir (`secrets/README.md`).
  - Master realm yönetim konsolu `127.0.0.1:8180/admin`'de açıktır (yalnız loopback; parola secret dosyasında).
  - IdP'nin TLS'i yoktur; yalnız loopback ve compose ağı içindir. Üretimde TLS sonlandıran bir ters vekil ve `https` issuer beklenir. Uygulama bunu zorlamaz (`VersoJwtProperties` `http` ve `https` kabul eder), çünkü compose'taki iç JWKS adresi de düz HTTP'dir; üretim kontrol listesinin işidir.
- **Etkilenen dosyalar:** `platform/platform-security/`, `platform-core` (`ErrorClassifier`, `GlobalServiceExceptionHandler`), `compose.yaml` (keycloak), `deploy/keycloak/`, `deploy/postgres/initdb/30-keycloak.sh`, `config/verso.yml` (`verso.security.jwt`), `scripts/{auth-smoke,demo-token,dev-secrets}.sh`, `.github/workflows/restore-drill.yml`.
- **Geri alma yolu:** IdP'yi değiştirmek `OIDC_ISSUER`/`OIDC_JWK_SET_URI` ve gerekirse `verso.security.jwt.type-header` ayarıdır. Keycloak servisi compose'tan kaldırılabilir; uygulama ona bağlı değildir.

## Yeniden değerlendirme koşulu

- Müşteri IdP'si `at+jwt` yazmıyorsa: `type-header` boş bırakılır ve yerine başka bir ayırt edici kural (ör. `token_use`/`scope`) ADR ile seçilir.
- EdDSA isteyen bir IdP: Tink bağımlılığı onaylanır ve ES256 + EdDSA birlikte açılır.
- Web arayüzü (faz 10): tarayıcı istemcisi, oturum ve CSRF kararı.
