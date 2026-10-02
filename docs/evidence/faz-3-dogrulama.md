# Faz 3: Doğrulama ve kanıt kaydı

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

- **Tarih:** 2026-10-02
- **Ortam:** Windows 11 + Docker Desktop 29.6.1 (Linux engine); GitHub Actions `ubuntu-latest`. Temurin 25.0.4.1, Maven 3.9.16, Node 24.18, gitleaks 8.24.3.
- **Kapsam:**
  - `platform-security` starter'ı: OAuth2 resource server, ES256, `iss`/`aud`/`exp`/`sub`/`typ at+jwt`, `@CurrentAccount AccountId`, zarflı 401/403/400/503.
  - JWKS anahtar kaynağı: önbellek, yeniden çekim hız sınırı, kesinti toleransı, timeout, `https` zorunluluğu.
  - Compose'ta demo Keycloak 26.7.5 (prod modu, kendi veritabanı ve rolü, realm `verso`), device flow + PKCE ve client credentials istemcileri.
  - `scripts/auth-smoke.sh` (CI), `scripts/demo-token.sh`.
  - Kararlar: ADR-0010, ADR-0005 güncellemesi, ADR-0007 #49–#52.
- **Davranışsal kapsam (seviye 2):** var. Resource server testleri gerçek HTTP portunda, üretim decoder'ıyla ve gerçek bir JWKS sunucusuna karşı koşar. Uygulama testleri `verso.yml`'deki üretim token kurallarını kullanır (test IdP'si yalnız `OIDC_ISSUER` ve `OIDC_JWK_SET_URI` verir). Keycloak'lı yığın yerelde ve CI'da (Linux) ayağa kaldırılıp gerçek token'larla denendi.

## 1. Makine kontrolleri

| Kontrol | Komut | Sonuç |
|---|---|---|
| Build ve testler | `./mvnw -B -ntp verify` | **PASS**: 194 Java testi (faz 2 sonunda 160) |
| Script ve hook testleri | `node --test scripts/*.test.js` | **PASS**: 67 test (config-lint 23, flyway 16, gitleaks 10, keycloak-start 6, pre-commit 5, review-gate 5, repo-hygiene 2) |
| Kimlik smoke testi (yerel ve CI) | `bash scripts/auth-smoke.sh` | **PASS**: token başlığı `alg ES256`, `typ at+jwt`; token'sız 401; geçerli token 404 (uygulamaya ulaştı); imzası bozulmuş token 401; password grant `unauthorized_client` |
| Restore provası (Keycloak'lı yığın) | `docker compose run --rm backup once && bash scripts/restore-drill.sh` | **PASS** |
| Mutasyon (negatif doğrulama) | `bash scripts/mutation-check.sh` | Bölüm 5'te |

## 2. Canlı denemeler (yerel yığın)

| Deneme | Sonuç |
|---|---|
| Device flow (`scripts/demo-token.sh`): tarayıcıda `demo` kullanıcısı, onay ekranı | **PASS**: token `aud verso-api`, `azp verso-cli`, `sub` UUID; API 404. Device code ve verifier yalnız özel geçici klasördeki dosyalarda |
| `offline_access` istenerek device flow | **PASS**: verilen kapsam yalnız `openid`, refresh token türü `Refresh` (`Offline` değil) |
| `admin-cli` ile password grant | **PASS**: `invalid_client` |
| IdP kesintisi, anahtar önbelleği sıcak | **PASS**: istekler etkilenmedi (404) |
| IdP kesintisi, önbellek soğuk (uygulama yeniden başlatıldı) | **PASS**: 503 `IDP_UNAVAILABLE`, `Retry-After: 30`; iki ardışık istek de 503; IdP dönünce 404. Loglarda token yok |
| Keycloak readiness'i | Bulgu: `/health/ready`, realm içe aktarımı bitmeden 8 sn önce UP dedi; ilk smoke 503 aldı. Healthcheck realm discovery belgesine çevrildi; boş IdP veritabanıyla tekrarlandı: healthy ancak bootstrap bittikten sonra |
| Faz 2 volume'ünden yükseltme | **PASS**: rol ve veritabanı silinip README adımları uygulandı; script kurdu, ikinci koşu değişiklik yapmadı, Keycloak yeniden kuruldu, smoke geçti. Dört veritabanı parolası `pg_stat_statements`'te 0 kez geçiyor |
| Keycloak secret rotasyonu (`secrets/README.md`) | **PASS**: CI istemci secret'ı değiştirildi, IdP veritabanı sıfırlandı, smoke yeni secret'la geçti |

## 3. Review'lar

Altı review, her biri ayrı bir worktree'de, commit `4b1bac2` üzerinde koştu; `C:\verso`'ya ve çalışan yığına dokunmadılar.

| Skill | Karar | Öne çıkan bulgular |
|---|---|---|
| `verso-security-review` | REQUEST CHANGES | S1 (HIGH): `@CurrentAccount String` query'den bağlanıyordu (IDOR, kanıtlandı). S2 (MEDIUM): uydurma `kid` başına JWKS isteği. S4 (MEDIUM): ek zincir API'yi açıyordu. S3, S5, S6 (LOW): IdP kesintisi 500, `demo-token.sh` argv, düz HTTP JWKS |
| `verso-spring-code-review` | REQUEST CHANGES | C1, C2 (HIGH): S4 ve S1 ile aynı, bağımsız bulundu. C3 (MEDIUM): JWKS hız sınırı. C4–C8 (LOW): `application/at+jwt`, `jwks-timeout` sınırı, ölü kod, 403 başlığı, actuator sınıf kontrolü |
| `verso-test-writer` | REQUEST CHANGES | T1 (HIGH): `@PreAuthorize`'ın `AuthorizationDeniedException`'ı test edilmiyordu. T2 (HIGH): JWKS timeout sabitlenmemişti. T3–T8 (MEDIUM): log gizliliği (MDC, cause zinciri), stateless, saat kayması ve enjekte saat, yalnız health açık, realm limitleri, secret fail-closed. T9–T13 (LOW) |
| `verso-environment-impact-review` | REQUEST CHANGES | E1 (HIGH): faz 2 volume'ünde Keycloak veritabanı oluşmuyordu. E2–E5 (LOW) |
| `verso-architecture-boundary-review` | REQUEST CHANGES | A1 (MEDIUM): S1 ile aynı. A2 (LOW): ArchUnit `verify`'da test-jar'ı üretim kodu sayıyordu. A3 (LOW) |
| `verso-resilience-review` | REQUEST CHANGES | R1, R2 (HIGH): hız sınırı ve IdP kesintisi. R3 (HIGH): kritik akış kaydında JWKS yoktu. R4 (MEDIUM), R5, R6 (LOW) |

## 4. Bulgu → düzeltme

| Bulgu | Kanıt (önce) | Düzeltme | Kalıcı kontrol |
|---|---|---|---|
| Ek zincir API'yi açıyor (C1/S4) | Reviewer: `/hooks/**` zinciri eklenince token'sız `/v1/...` 200 | API zinciri koşulsuz, `@Order(LOWEST_PRECEDENCE - 10)` | `api_whenAnotherSecurityChainIsAdded_staysProtected`, M107 |
| `@CurrentAccount` IDOR (S1/C2/A1) | `?account=victim` → `200 account=victim` | Resolver her anotasyonlu parametreyi üstlenir; açılış kontrolü | `startup_whenCurrentAccountAnnotatesAnotherType_fails`, `currentAccount_whenParameterIsNotAnAccountId_*`, M108, M109 |
| JWKS hız sınırı (S2/C3/R1) | 20 uydurma `kid` = 20 JWKS isteği | `JWKSourceBuilder`: önbellek, `rateLimited(30 sn)`; hız sınırına takılan bilinmeyen `kid` 401 | `request_whenManyTokensCarryUnknownKeyIds_*` (≤ 1 istek), M110, M111 |
| IdP kesintisi 500 (S3/R2) | Kapalı JWKS → 500 99999 | `outageTolerant(1 saat)`; entry point 503 `IDP_UNAVAILABLE` + `Retry-After`; soğuk önbellekte ikinci istek de 503 | `request_whenIdpKeysAreUnreachable_isRejectedWith503`, M112, M113; canlı deneme |
| JWKS timeout sabit değil (T2/C5) | Timeout satırı silinince testler yeşil | `DefaultResourceRetriever(timeout, …)`; `jwks-timeout` 0 < t ≤ 10 sn | `decoder_whenJwksDoesNotAnswer_*` (< 4 sn), M114, M118 |
| `AuthorizationDeniedException` (T1) | Üst sınıf taraması kapatılınca testler yeşil | — (davranış doğruydu, test eksikti) | `request_whenControllerThrowsAuthorizationDeniedException_*`, M119 |
| Düz HTTP JWKS (S6/E3) | Prod örneği `http://keycloak:8080` | `https` zorunlu; `http` yalnız loopback veya `OIDC_JWKS_ALLOW_HTTP` | `properties_whenIncompleteOrUnsafe_*`, M117 |
| Eski volume'de Keycloak yok (E1) | Init script'leri yalnız boş volume'de | `30-keycloak.sh` idempotent; README yükseltme adımı | `keycloakScript_whenRunAgainOrWithoutItsSecret_*`, M123; canlı deneme |
| `admin-cli` password grant, `offline_access` (S, needs verification) | Realm'de tanımsız; Keycloak varsayılanı | `admin-cli` kapalı, kapsamlar yalnız `basic` | `builtInAndCliClients_*`, M124; canlı deneme |
| `demo-token.sh` argv (S5) | Device code ve verifier curl argümanında | Özel geçici klasör, `--data-urlencode name@file` | Canlı deneme |
| `application/at+jwt` (C4) | Reddediliyordu | Medya türü normalize | `validator_whenTypeHeaderIsTheFullMediaType_*`, M116 |
| Log gizliliği testi zayıf (T3) | MDC ve cause zinciri bakılmıyordu | `assertLogsFreeOf`: mesaj, argümanlar, MDC, tüm zincir; "log var" pozitif kontrolü | PlatformSecurityTest |
| Stateless, saat, actuator, realm, secret listesi (T4–T8, T12) | Mutasyonlar yeşil kaldı | Testler eklendi | M115, M121, M122, M125, M126, M127 |
| ArchUnit test-jar (A2) | `verify`'da test IdP üretim kodu sayıldı | `NO_TEST_JARS` | Üç ArchUnit testi |
| Kritik akış kaydı (R3), CB sapması (R4), açık config (R6) | Kayıt eksik | repo-context satırı, ADR-0007 #52, `verso.yml` | — |

## 5. Mutasyonlar

`bash scripts/mutation-check.sh`: baseline yeşil (194 Java testi, 7 node suite), **128/128 yakalandı** (faz 2 sonunda 90). Faz 3 yenileri: M89–M106 (token kuralları, realm, IdP veritabanı izolasyonu, compose) ve M107–M127 (review düzeltmeleri). Her mutasyon yalnız beklenen test **metodu** kırmızıysa "caught" sayılır. Çıktı: [`faz-3-mutasyon-ciktisi.txt`](faz-3-mutasyon-ciktisi.txt).

İlk tam koşuda M112 yaşadı: soğuk önbellek testi tek yeniden deneme yapıyordu, ama Nimbus'un hız sınırı aralık başına iki isteğe izin verdiği için ikinci istek hâlâ gerçek bir çekimdi. Test üç denemeye çıkarıldı; M110–M113 yeniden koşuldu, dördü de yakalandı.

Süreç notu: ilk koşu durdurulduğunda alt bash süreci yaşamaya devam etti ve kilidi elle silinince ikinci bir koşuyla çakıştı (bir dosya geçici olarak mutasyonlu kaldı). Süreç SIGTERM ile durduruldu, trap ağacı geri yükledi, `git status` temizdi ve commit etkilenmedi. Betiğin geri yükleme adımı artık ajan worktree'lerini (`.claude/worktrees/`) taramıyor.

## 6. CI

PR [doguyaras/Verso#5](https://github.com/doguyaras/Verso/pull/5), commit `f89ce58`:

| Workflow | Koşu | Sonuç |
|---|---|---|
| `ci` (backend: `mvn verify`, `MIN_TESTS` 190; scripts-and-hooks: 7 node suite, gitleaks, config-lint, immutability) | 37058256485 | **success** |
| `restore-drill` (temiz Linux checkout: `dev-secrets.sh` → `docker compose up --build --wait` → `auth-smoke.sh` → yedek/restore öz-testi → prova) | 37058256490 | **success** |
