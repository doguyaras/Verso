# security-rules.md — Güvenlik ve Privacy Kuralları

> Tek kaynak. Skill'ler (`verso-security-review` başta) buraya link verir, tekrar etmez. İhlal = `REQUEST CHANGES` veya `BLOCK`.

## 1. Secret ve Config

- Secret değeri (parola, token, private key, API key, salt/pepper) **hiçbir** kod, config, doküman, test, log, PR metni veya commit mesajına yazılmaz. Yalnız **adı** geçer.
- Secret'lar `/run/secrets/` (compose `secrets:` + Spring config tree) ile gelir; git'te yalnız SOPS ile şifreli (`secrets/<env>.enc.yaml`).
- `${ENV:literal-varsayılan}` biçiminde secret fallback yazılmaz; secret eksikse uygulama **açılmaz** (fail-fast).
- Config Server kullanılıyorsa secret taşımaz.
- `.dockerignore`: `.env*`, `secrets/`, `.git`, `**/target`, `node_modules`.
- Local yml'de gerçek secret bulunmaz; local için ayrı, açıkça sahte değerler (`local-only-…`) ve profil guard'ı.

## 2. Kimlik ve Yetki

- Üç JWT yüzeyi (**user**, **service**, **admin**) birbirinin yerine kabul edilmez: farklı `typ`, farklı anahtar, farklı `aud`.
- Service JWT **asimetrik** (EdDSA/ES256); her imzalayıcının kendi anahtar çifti; `iss` = imzalayan; doğrulama JWKS + `kid`. Simetrik paylaşılan secret **yasak**.
- Hesap kimliği **yalnız** doğrulanmış bağlamdan (`@CurrentAccount` ← service JWT `sub`). Path/query/body'den kimlik alınmaz (IDOR). Path'teki id yalnız hedef kaynaktır; ownership serviste kontrol edilir.
- Verso (ADR-0010): `@CurrentAccount` yalnız `AccountId` parametresinde; başka tip uygulamayı açılışta durdurur. Kullanıcı token'ı ES256, `typ: at+jwt`, `aud` `verso-api`; JWKS `https` (düz HTTP yalnız loopback veya `OIDC_JWKS_ALLOW_HTTP`). Platform güvenlik zinciri her zaman son catch-all'dur; modül zinciri `securityMatcher` + daha düşük `@Order` ile eklenir, onu değiştirmez.
- `/internal/**` uçları default-deny; `service-jwt.internal-access` allowlist'i gerçek kullanım kadar dar; dar kural catch-all'dan önce (first-match). Allowlist eşleşmesi decode+normalize edilmiş path üzerinde.
- Kullanıcı adına çalışan internal uçta JWT `sub` path'teki hesapla karşılaştırılır. Hesabı body'den alan internal uç hiçbir aktöre açılmaz.
- **Servis kimliği ≠ kullanıcı adına yetki.** A'nın imzası yalnız "A'dan geldi" demektir; A'nın token'a koyduğu `sub` adına işlem yetkisi vermez. Hedef üçünü birlikte kontrol eder: (1) `act`/`iss` allowlist'te, (2) bu **işlem** için, (3) `sub` bu **kaynakta** yetkili (ownership). Kullanıcı isteğiyle çalışan çağrı ile arka plan işi token'da ayrılır (`sub` yok / `on_behalf_of` ayrı claim); arka plan token'ıyla kullanıcı-yetkisi gerektiren işlem reddedilir. Zincirde (`A → B → C`) `act` zinciri korunur. Matris: `repo-context.md` Bölüm 3.1 (RFC 8693 delegation/impersonation ayrımı).
- Gateway: `/internal/**` → 404 (prefix kırpma sonrası da), iç header'ları (`X-Subject-Id`, `X-User-*`) temizler, `X-Forwarded-*` yalnız güvenilen proxy'den, CORS listesi açık (`*` yasak), rate limit ve timeout bütçesi var, `gateway` actuator ucu kapalı.
- Oturum sürümü (`sv`): şifre sıfırlama, logout-all, **ban**, rol değişimi → `sv + 1`; gateway ve realtime bu sürümün altını reddeder.
- Refresh token: opak, `sha256(plain:salt)` ile saklanır, aile rotasyonu, reuse → aile iptali. Mutlak süre uzamaz.
- Admin 2FA: passkey/WebAuthn veya TOTP. SMS yalnız kurtarma. Admin OTP'si de tuzlu hash + sabit zamanlı karşılaştırma.
- Roller token'da taşınıyorsa değişimde `sv` artar; panel ve sunucu aynı rol kaynağını okur.

## 3. OTP, Şifre, Abuse

- OTP: `SecureRandom`, 6 hane, `sha256(salt:code)`, sabit zamanlı karşılaştırma, TTL + deneme sınırı, enumeration koruması (bilinmeyen numaraya aynı yanıt). Outbox payload'ında OTP düz metin **kalmaz** (iletim sonrası NULL/şifreli).
- SMS pumping: ülke/prefix allowlist, `libphonenumber` doğrulama, numara/IP/cihaz başına günlük tavan, velocity alarmı, harcama kesicisi.
- Cihaz attestation (Play Integrity / App Attest) kayıt, OTP-gönder ve suistimale açık aksiyonlarda **kademeli risk sinyali** (ikili kapı değil).
- Şifre: BCrypt, 72 byte sınırı uzunluk kuralına yansır; politika parametre kataloğundan; hatalı denemede kilit (`retryAfterSeconds` details'te).
- Rate limit: Redis Lua fixed window, key `rl:<scope>:<sha256>`; sayaç JVM'de tutulmaz; scope'lar `<aksiyon>-ip|account|transaction|device`; fail politikası scope başına README tablosunda (güvenlik yüzeyleri fail-closed, iş yüzeyleri fail-open + metrik).
- Mock entegrasyonlar (mağaza makbuzu, SMS, ödeme) yalnız `@Profile("local|test")`; prod açılışında mock bean varsa uygulama **açılmaz**.

## 4. Loglama ve Hata Yanıtı

**Hiçbir seviyede loglanmaz:** OTP, access/refresh/service/admin token, şifre, secret, salt, private key; ham telefon, e-posta, kimlik no, plaka, kesin konum, IP, user-agent; şifreli içerik ve anahtar; request/response body, DTO/entity `toString()`, ham path/query/header, credential içeren URL; sağlayıcı yanıtı, ham exception mesajı, stack trace (yalnız 500'lerde sanitize edilmiş özet + trace).

- Throw öncesi tek structured log: `"<Olay> rejected: code=X reason=Y"`. Yalnız `code=`, `reason=`, `category=`, `exceptionType=` (`getSimpleName`), gerekli teknik id.
- Log'a özel neden `ServiceException.safeLogReason/safeLogCategory`'de; **asla** `details` içinde (details istemciye döner).
- `details` yalnız istemciye gösterilebilir `anahtar=değer` bilgisi taşır.
- UUID, hesap id, mask ve hash otomatik güvenli değildir; alanlar allowlist ile seçilir; trace context tercih edilir. Parameterized logging sanitization yerine geçmez.
- Structured JSON log; trace/kullanıcı id'si Loki **label** değil (structured metadata).
- Log privacy testi: sentetik hassas işaret **yok**, güvenli alan (`outcome=`) **var**.

## 5. Veri ve Privacy

- Kişisel veri envanteri ve DPIA ürünle birlikte başlar. Aydınlatma metni alanları sayar (telefon, e-posta, kimlik, plaka, konum, belge, cihaz).
- Düşük entropili kimlikler (telefon, plaka, e-posta) için düz SHA-256 **yetersiz**: secret pepper'lı **HMAC** (arama) + şifreli orijinal (gösterim).
- Kimlik sorgulama uçlarında **varlık oracle'ı** yok: kayıtlı olmayan/gizli/engelli için tek tip "sonuç yok"; hız limiti, velocity alarmı, audit.
- Konum: rastgele fuzzing **yetersiz**; kullanıcı başına deterministik grid/ofset (~1–3 km), aynı yuvarlanmış nokta geo sorgusu ve filtrelerde; mesafe aralık olarak; kesin mesafe sıralaması yok.
- Kişisel veri dönen uçlar `Cache-Control: private, no-store`.
- **SSRF:** kullanıcıdan gelen URL ile giden her istek (webhook, avatar/önizleme, içe aktarma) ayrı egress client'tan; `https` + host allowlist; DNS çözümü sonrası IP kontrolü (`InetAddressFilter.externalAddresses()` bean'i, Boot 4.1) ve redirect'te tekrar; kısa timeout + boyut sınırı. İç ağ, `169.254.169.254`, localhost, config-server/actuator portları hedef olamaz.
- Arama index'i, cache, log, yedek aynı kurallara uyar.
- Export (taşınabilirlik) keyset sayfalı internal uçlarla; **silme** silme saga'sı ile (tüm servisler, index, cache, object storage tüm versiyonlar, üçüncü taraflar); yasal saklama gerekenler anonimleştirilir; yedekler için crypto-shredding. KVKK 30 gün / GDPR 1 ay.
- Uçtan uca şifreleme iddiası varsa Signal/MLS; değilse "sunucu okuyamaz" ile sınırlı ve sınırlamalar yazılı. İstemci karşı tarafın anahtarını pin'ler. Şikayet kanıtı **message franking** ile; sohbet anahtarı panele verilmez. Kanıt erişimi audit'li, `no-store`, retention'lı.

## 6. Dosya Yükleme

- Presigned PUT → **private quarantine** bucket → worker: magic byte/boyut/pixel-bomb → **yeniden kodlama** (EXIF/GPS temizlenir) → CSAM hash eşleme + NSFW sınıflandırma → private delivery bucket → kısa ömürlü signed GET / imzalı CDN.
- Public bucket'a doğrudan yükleme **yasak**.
- Yükleme sonrası HEAD/ETag doğrulaması; yetim dosya temizliği (cron + dağıtık kilit).

## 7. Veritabanı

- Uygulama rolünde `statement_timeout`, `lock_timeout`, `idle_in_transaction_session_timeout` role bağlı (`ALTER ROLE … SET`); migration rolünde `statement_timeout` yok, `lock_timeout` kısa. PgBouncer transaction mode: `SET` yasak, `SET LOCAL`; `pg_advisory_xact_lock`. RLS kullanılıyorsa bağlam `SET LOCAL` ile, rol `BYPASSRLS` değil.
- Servis başına **iki** DB rolü: `svc_<x>_migrate` (şema sahibi, DDL; yalnız Flyway) ve `svc_<x>` (uygulama; tablo/sequence DML, `ALTER DEFAULT PRIVILEGES` ile). Uygulama rolü DDL yapamaz, audit/append-only tablolarda UPDATE/DELETE yetkisi yoktur. Başka şemaya USAGE yok. Cross-schema erişim hatası "GRANT ekleyerek" çözülmez.
- `spring.flyway.baseline-on-migrate` config'te **açık tutulmaz**; mevcut DB'yi Flyway'e alma tek seferlik belgelenmiş `baseline` prosedürüdür.
- Audit tabloları `@Immutable` + DB'de `REVOKE UPDATE, DELETE` / trigger.
- Seed/test verisi prod migration location'ında değil; bilinen parolalı admin tohumlanmaz (bootstrap runner + env + ilk girişte değiştir).
- Yedek şifreli; restore provası aylık.

## 8. Tracing

- Trace id yetki sinyali değildir. Span attribute'larına PII konmaz. Route'lar static template. Redis zarfında trace metadata HMAC kapsamında.

## 9. Tedarik Zinciri

- Image CI'da build, cosign imzalı, digest ile deploy; non-root; base image Renovate ile güncel.
- GitHub Actions: üçüncü taraf action'lar **commit SHA'ya pinli** (`uses: owner/action@<40-hex> # vX.Y.Z`); tag pin'i `REQUEST CHANGES`. Workflow `permissions` en dar; secret'lar yalnız gereken job'da.
- gitleaks pre-commit + CI; Dependabot/GitHub Advisory alarmları; yaması yalnız ticari sürümde olan CVE = upgrade tetikleyicisi.
- GPL lisanslı kütüphane kapalı kaynak uygulamaya bağlanmadan hukuki inceleme.

## 10. Bu Belgede Özellikle Taşınmayanlar

Secret değerleri, pepper/salt değerleri, üretim IP/host adları, mevcut CVE listesi (Dependabot'ta yaşar).

## 11. Net Kanıt Bulunamayan Alanlar

- (ajan ekler)
