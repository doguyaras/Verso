# ADR-0041: Issuer başına güven ve Microsoft Entra ID token'larının doğrudan kabulü

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras
- **İlgili eşik (referans Bölüm 24):** Servis kimliği satırı; ADR-0010'un token kurallarını issuer başına yapar, ADR-0019'un "Entra henüz çalışmıyor" sınırını kaldırır.

## Bağlam

- ADR-0010 tek bir global kural koydu: yalnız ES256, JOSE `typ: at+jwt`, `aud` içinde `verso-api`. RS256, `alg` karışıklığına karşı tek algoritmaya sabitlemenin yan etkisi olarak dışarıda kaldı; `at+jwt` ise Keycloak'ın aynı anahtarla imzaladığı ID token'ı ayırmak içindi.
- Entra ID v2.0 access token'ları RS256 imzalı ve `typ: JWT` başlıklıdır. ADR-0019 bunu sınır olarak yazdı: Entra'lı müşteri bugün yalnız Keycloak aracılığıyla (identity brokering) bağlanabilir. Müşteriler Entra'yı doğrudan kullanmak istiyor; aracı Keycloak işletmek, yedeklemek ve güncellemek ayrı bir yük.
- Global kuralı gevşetmek (RS256'yı ve boş `typ`'ı herkese açmak) Keycloak yolunu da zayıflatır: Keycloak ID token'ı `typ: JWT` taşır, `aud`'u `verso-api` olan bir ID token access token yerine geçebilirdi.
- Entra'ya özgü tuzaklar: `common`/`organizations` çok kiracılı issuer'lar (başka kiracının kullanıcısı), v1.0 token'ları (farklı `iss` ve `aud` biçimi), `sub`'ın uygulama başına çift yönlü (pairwise) olması, 200'den fazla grupta `groups` yerine `_claim_names`/`_claim_sources` (overage) gelmesi. Entra'nın ID token'ı da RS256, `typ: JWT`, `ver: 2.0` ve aynı `iss`'i taşır.
- Çok issuer'lı kurulumda klasik hata: token'daki `iss`'e (veya `jku`/`x5u` başlığına) bakıp anahtarları oradan çekmek. Bu hem SSRF'tir (saldırgan Verso'ya istediği adresi çağırtır) hem de saldırganın kendi anahtarıyla imzaladığı token'ı kabul ettirir.

## Seçenekler

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| A. Bugünkü gibi Keycloak aracılığı | Kod değişmez; Keycloak varsayılanları geçerli | Müşteri ikinci bir IdP işletir; roller/gruplar IdP mapper'larıyla taşınır | Müşteride yüksek |
| B. Global kuralı gevşetmek (ES256 + RS256, `typ` boş) | Az kod | Keycloak yolu da RS256'yı ve `typ: JWT`'yi kabul eder; ID token karışıklığı geri gelir; Entra'ya özgü kontrol (`tid`, `ver`) yeri yok | Düşük, güvenlik kaybı |
| **C. Issuer başına güven listesi; token'ın `iss`'i doğrulamadan önce yalnız listeden bir doğrulayıcı seçer** | Her issuer'ın algoritması, `typ`'ı, audience'ları, sabit claim'leri ve claim yolları ayrı; Keycloak kuralları değişmez; bilinmeyen issuer için ağ isteği yok | İkinci bir JWKS önbelleği; ayar yüzeyi büyür | Orta |
| D. Spring'in `JwtIssuerAuthenticationManagerResolver`'ı, issuer başına discovery | Hazır sınıf | Açılışta değil ilk token'da discovery isteği; Verso'nun JWKS kuralları (zaman aşımı, boyut sınırı, hız sınırı, kesinti toleransı) ve zarflı 401/503 davranışı yeniden kurulmalı; MCP zinciri decoder'ı ayrı kurar (ADR-0031) | Orta |
| E. Çok kiracılı Entra (`common`, `iss` deseni) | Her kiracının kullanıcısı girebilir | Kiracı denetimi `tid` allowlist'ine kalır; tek kiracılı kurum için gereksiz risk | Orta |

## Karar

**C** seçildi. B Keycloak yolunu zayıflatır; D, Spring'in deseni (önce `iss`, sonra o issuer'ın yöneticisi) korunarak decoder düzeyinde uygulandı, böylece API ve MCP zincirleri, JWKS önbelleği ve 401/503 eşlemesi aynı kaldı; E tek kiracılı kurulumda gereksiz risk. A, isteyen kurulum için geçerli kalır.

**Güven listesi (`platform-security`, `TrustedIssuers`, `TrustedIssuer`):** her güvenilen issuer şunları bildirir: tam `iss`, JWKS adresi, izinli algoritmalar, beklenen `typ`, API audience'ları, MCP audience'ları, hesap claim'i, birebir eşit olması gereken claim'ler, rol/grup claim yolları (ADR-0019). Liste açılışta config'ten kurulur; çalışma zamanında büyümez.

**Seçim (`IssuerSelectingJwtDecoder`):** token ayrıştırılır; imzasız (`alg: none`) ya da şifreli token hemen reddedilir. Doğrulanmamış `iss` yalnız listeye bakılan bir anahtardır: listede yoksa 401, **hiçbir anahtar çekilmez**. Varsa o issuer'ın kendi decoder'ı her şeyi, `iss` dahil, yeniden doğrular. `jku`, `x5u` ve token'daki anahtarlar hiçbir zaman kullanılmaz. Her ret `BadJwtException`'dır: 401, asla 503.

**Algoritma karışıklığı savunması:** algoritma issuer başına sabittir (Keycloak ES256, Entra RS256) ve token'ın `alg`'ından seçilmez. Ayar tipi Spring'in `SignatureAlgorithm`'ıdır; HMAC ve `none` bu tipte yoktur, yapılandırılamaz. Nimbus anahtar seçicisi yalnız algoritmanın türündeki anahtarı alır (ES256 için P-256 EC, RS256 için RSA): RSA anahtarının `kid`'iyle gelen ES256 token'ı ya da EC anahtarının `kid`'iyle gelen RS256 token'ı doğrulanamaz.

| Kural | Keycloak (birincil issuer, değişmedi) | Entra ID |
|---|---|---|
| `iss` | `OIDC_ISSUER`, birebir | `<authority>/<tenant-id>/v2.0`, ayardan türetilir, birebir |
| JWKS | `OIDC_JWK_SET_URI` | `<authority>/<tenant-id>/discovery/v2.0/keys` ya da `OIDC_ENTRA_JWK_SET_URI` (ayna) |
| Algoritma | `verso.security.jwt.algorithms` (ES256) | Yalnız RS256 (sabit) |
| `typ` | `at+jwt` | `JWT` (sabit) |
| `aud` (API) | `verso-api` | `OIDC_ENTRA_AUDIENCES`: API uygulamasının istemci kimliği ve/veya Application ID URI'si |
| `aud` (MCP, ADR-0031) | `verso.mcp.audience` (`verso-mcp`) | `OIDC_ENTRA_MCP_AUDIENCES`: MCP uygulamasının kimliği; API audience'larıyla kesişemez (açılışta kontrol); boşsa Entra token'ı `/mcp`'de kabul edilmez |
| Sabit claim'ler | – | `ver` = `2.0`, `tid` = ayardaki kiracı |
| Access token işareti | `typ: at+jwt` | Boş olmayan `scp` (yetkilendirilmiş kullanıcı token'ı). Entra ID token'ı aynı issuer, anahtar ve `typ`'ı taşır ama `scp` taşımaz |
| Hesap (`sub`) | `sub` | `oid` |
| Roller / gruplar | `OIDC_ROLES_CLAIM` vb. | `roles` (uygulama rolleri) / `groups` (grup object id'leri) |
| Grup overage | – | `_claim_names.groups` ya da `hasgroups: true` varsa hiç grup verilmez (yanında `groups` olsa bile); WARN log (instance başına bir kez) ve `verso.security.groups.overage{issuer="entra"}` sayacı. Microsoft Graph çağrılmaz |

Ortak kurallar her issuer'da aynıdır: `exp` zorunlu, saat kayması, `nbf`, `sub` biçimi `[A-Za-z0-9._@:-]{1,255}`, JWKS zaman aşımı, 64 KB sınırı, 5 dk önbellek, bilinmeyen `kid` için 30 sn'de bir çekim, 1 saat kesinti toleransı (ADR-0010). Her issuer'ın kendi önbelleği vardır; anahtarları yalnız o issuer'ı adlandıran token gelince çekilir.

**Kiracı:** `OIDC_ENTRA_TENANT_ID` bir GUID olmalıdır. `common`, `organizations`, `consumers` ve kişisel Microsoft hesaplarının kiracısı (`9188040d-…`) açılışta reddedilir; issuer kiracıdan türetildiği için çok kiracılı bir issuer yapılandırılamaz. `authority` https, yolsuz bir kökendir (genel bulut `https://login.microsoftonline.com`; ulusal bulutlar için değiştirilir).

**Hesap kimliği (`sub` eşlemesi):** Verso hesap kimliği olarak her yerde `sub`'ı saklar (`@CurrentAccount`, belge sahibi, `user:<sub>` principal'ı, denetim `actor_sub`, MCP okuyucusu). Entra'nın `sub`'ı uygulama başına farklıdır: aynı kullanıcı API ve MCP uygulamalarında iki ayrı hesap olurdu. Bu yüzden Entra issuer'ı için `oid` (kiracı genelinde sabit) doğrulamadan önce `sub`'ın yerine konur; `oid` yoksa token reddedilir. Bu dönüşüm yalnız Entra issuer'ının decoder'ındadır: Keycloak hesapları (`sub`) etkilenmez, veri göçü yoktur.

**Yalnız Entra kurulumu:** `OIDC_ISSUER` ve `OIDC_JWK_SET_URI` birlikte boş bırakılabilir; o zaman yalnız Entra güvenilir. İkisi de boş ve Entra kapalıysa uygulama açılmaz. Biri dolu diğeri boşsa açılmaz.

**Panel:** `panel/js/config.js` artık yetkilendirme, token ve çıkış uçlarını ve `scope`'u taşır (boşsa Keycloak yolları). `auth.js` aynı PKCE S256 akışını bu uçlarla yapar; uçlar yalnız https (ya da loopback) olabilir. Entra için `scope` API'nin kapsamını adlandırır (`api://<api>/access_as_user`). Panel rolleri `realm_access.roles`'a ek olarak `roles`'tan da okunur; `verso-admin` yine yalnız `/v1/info`'dan gelir. `innerHTML` yok.

**Kenar CSP:** `deploy/edge/nginx.conf` statiktir ve `connect-src 'self' http://localhost:8180` taşır. Entra kurulumunda yalnız o kurulumda `http://localhost:8180` yerine `https://login.microsoftonline.com` yazılır (README, `docs/entra-kurulumu.md`). Varsayılan CSP Microsoft'a açılmaz.

**Yerel mod (ADR-0013):** uygulamanın dışarı yolu yoktur; Entra anahtarları internettedir. Yerel modda `OIDC_ENTRA_JWK_SET_URI` iç ağdaki bir JWKS aynasını gösterir; bulut kaplamasında (`egress`) doğrudan çekilebilir. Ayna yalnız anahtarları taşır; issuer kontrolü değişmez.

## Sonuçlar

- **Olumlu:**
  - Entra'lı kurum Keycloak işletmeden bağlanır; roller (`verso-admin`, `verso-user`) ve gruplar ADR-0019'daki gibi Entra'da yönetilir.
  - Keycloak token kuralları bit bit aynıdır; mevcut hesaplar ve paylaşımlar değişmez.
  - Bilinmeyen issuer'lı token hiçbir ağ isteği üretmez; issuer başına algoritma ve anahtar türü eşlemesi `alg` karışıklığını kapatır.
  - MCP audience ayrımı iki issuer için de geçerlidir: API token'ı `/mcp`'de, MCP token'ı `/v1`'de 401.
- **Olumsuz / kabul edilen risk:**
  - Hesap ad alanı ortaktır: Keycloak `sub`'ı ile Entra `oid`'i aynı değeri taşırsa aynı hesaptır. İkisi de IdP'nin ürettiği rastgele UUID'lerdir; çakışma ancak bir IdP yöneticisinin kimliği bilerek seçmesiyle olur. Bu kişi zaten kendi IdP'sinde istediği kullanıcıyı üretebilir. İki issuer birlikte yalnız geçiş döneminde önerilir.
  - 200'den fazla gruplu kullanıcı hiçbir gruba dayalı erişim almaz (fail closed). Çözüm Entra'da `groupMembershipClaims: "ApplicationGroup"` ile uygulamaya atanmış gruplarla sınırlamaktır; Graph çağrısı kapsam dışıdır (sıcak yola yeni uzak çağrı ve Graph izni gerekir).
  - Yalnız kullanıcı adına (delegated) token kabul edilir; uygulama kimliğiyle (client credentials) alınan Entra token'ı `scp` taşımadığı için 401'dir.
  - Rol değişimi token ömrü kadar gecikir (Entra varsayılanı 60–90 dk; ADR-0019'daki `sv` yokluğu); Keycloak'ta 300 sn idi.
  - Entra'nın uygulamaya özel imza anahtarları (claims mapping policy ile; JWKS adresine `appid` gerekir) desteklenmez; JWKS aynası bu durumda o anahtarları sunmalıdır.
  - Entra B2C ve External ID (CIAM) issuer'ları farklı biçimdedir; desteklenmez.
  - Gerçek bir Entra kiracısıyla denenmedi: **net kanıt bulunamadı** (`docs/evidence/adr-0041-dogrulama.md`). Testler üretilen RSA/EC anahtarları ve sahte JWKS ile Entra biçimini kullanır.
- **Etkilenen dosyalar / config yüzeyleri:** `platform/platform-security/` (`jwt/{JwtValidation,VersoJwtProperties,TrustedIssuer,TrustedIssuers,IssuerSelectingJwtDecoder}`, `authority/{ClaimAuthoritiesConverter,IssuerAuthoritiesConverter}`, `PlatformSecurityAutoConfiguration`, test-jar `TestEntraProvider`), `services/mcp/mcp-core` (`McpSecurityConfiguration`), `config/verso.yml`, `application-local.yml`, `deploy/prod.env.example`, `compose.yaml`, `.env.example`, `ConfigDriftTest`, `panel/js/{config,auth,roles}.js`, `docs/entra-kurulumu.md`, README.
- **Geri alma yolu:** `OIDC_ENTRA_TENANT_ID` boş bırakılır; liste yalnız birincil issuer'dır ve davranış ADR-0010'daki gibidir. Kod geri alınırsa Entra hesaplarının (`oid`) belgeleri sahipsiz görünmez, yalnız erişilemez kalır.

## Yeniden değerlendirme koşulu

- `verso.security.groups.overage` sayacı sıfırdan büyük kalıyorsa ve kurum grupları daraltamıyorsa: Graph üzerinden grup okuma (yeni uzak çağrı, `verso-resilience-review`, ayrı ADR).
- İkinci bir Entra kiracısı ya da çok kiracılı kurulum isteği: issuer listesinin genel bir listeye dönmesi.
- Uygulama kimliğiyle (daemon) Verso çağırma ihtiyacı: `idtyp: app` ve `roles` ile ayrı bir aktör modeli.
- Okta, Auth0 gibi üçüncü bir IdP: birincil issuer ayarları yetmezse aynı `TrustedIssuer` modeliyle yeni bir profil.
