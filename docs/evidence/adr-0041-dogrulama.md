# ADR-0041 doğrulaması: issuer başına güven ve Microsoft Entra ID

Tarih 2026-10-24. Dal `feature/doguyaras-entra-rs256`, taban `origin/develop` `d5315fa`. Geliştirme makinesinde Docker container'ı başlatılmadı (makinede yük testi koşuyordu); Testcontainers testleri CI'da (`backend` işi) koşar ve kapı odur.

## Makine kontrolleri (yerel, JDK 25)

| Kontrol | Sonuç |
|---|---|
| `./mvnw -B -ntp -T 1 -DskipTests install` | BUILD SUCCESS (tüm modüller, test kaynakları derlendi) |
| `./mvnw test -pl platform/platform-security` | 56 test, 0 hata (`TrustedIssuersTest` 12, `PlatformSecurityTest` 20, `SecurityUnitTest` 10, `ClaimAuthoritiesConverterTest` 14) |
| `./mvnw test -pl services/mcp/mcp-core` (`McpUnitTest`, `McpModeCheckTest`) | 7/7 |
| `verso-app`: `ConfigDriftTest`, `ComposeConfigTest`, `ArchitectureRulesTest`, `ErrorCodeUniquenessTest`, `ModuleStructureTest`, `ModuleConsistencyTest`, `ConfigProfilesTest`, `KeycloakRealmTest` | 48/48 yeşil |
| `GITLEAKS=… node --test scripts/*.test.js scripts/panel.test.mjs` | 220 test, 219 geçti, 0 kaldı, 1 atlandı |
| `node scripts/config-lint.js` (değişen yml ve env şablonları) | OK (4 dosya) |
| `docker compose config -q` (yalnız CLI) | hatasız |

Yerelde koşmayan (Docker gerektirir, CI kapısı): yeni `EntraIssuerTest` (gerçek PostgreSQL, uygulamanın kendi `config/verso.yml`'i ve `OIDC_ENTRA_*` değişkenleri, MCP açık) ve var olan Testcontainers testleri (`McpServerTest`, `DeployConfigTest` dahil).

## Test matrisi

Anahtarlar testte üretilir (RSA 2048, EC P-256); JWKS JDK HTTP sunucusundan (`TestEntraProvider`, `TestIdentityProvider`).

| Durum | Beklenen | Test | Sonuç |
|---|---|---|---|
| Entra v2.0 RS256 token, doğru kiracı, `aud` = istemci kimliği ya da `api://` URI | kabul; `sub` = `oid`; `roles` ve `groups` okunur | `TrustedIssuersTest.entraToken_whenV2RS256…`, `PlatformSecurityTest.entraIssuer_*`, `EntraIssuerTest.api_*` | geçti (son satır CI) |
| Başka `tid`, `tid` yok | ret | `entraToken_whenAnyRuleIsBroken_isRefused` | geçti |
| Başka `aud` (Graph), MCP audience'ı API'de | ret | aynı | geçti |
| v1.0 (`ver` 1.0; v1 issuer `sts.windows.net`) | ret | aynı | geçti |
| Çok kiracılı issuer (`/common/v2.0`) | ret, anahtar çekilmez | aynı + `unknownIssuer_*` | geçti |
| Entra issuer'ı altında ES256 (JWKS'te yayımlanmış EC anahtarıyla) | ret | aynı | geçti |
| RS256 imza, EC anahtarının `kid`'i (anahtar türü uyuşmazlığı) | ret | aynı | geçti |
| HS256 (açık anahtar baytlarıyla), `alg: none` | ret | aynı | geçti |
| `typ` `at+jwt` ya da `typ` yok | ret | aynı | geçti |
| Süresi dolmuş | ret | aynı | geçti |
| ID token biçimi (`scp` yok), `oid` yok, `oid` hesap biçimine uymuyor | ret | aynı | geçti |
| Bilinmeyen issuer (yabancı URL, JWKS adresleri, sonda `/`) | ret, iki JWKS sunucusuna da istek **yok** | `unknownIssuer_whenTokenArrives_isRefusedWithoutAnyKeyFetch` | geçti |
| Çapraz issuer (Entra anahtarı + Keycloak `iss`; Keycloak anahtarı + Entra `iss`) | ret | `crossIssuer_*` | geçti |
| Grup overage (`_claim_names.groups`, `hasgroups`) | hiç grup, roller kalır, sayaç 2; Keycloak token'ında işaret okunmaz | `groupsOverage_whenEntraLeavesTheGroupsOut_*` | geçti |
| Overage log'u | yalnız `code=GROUPS_OVERAGE reason=GROUPS_NOT_IN_TOKEN claim=groups`; grup, `oid`, token yok | `groupsOverage_whenLogged_carriesNoClaimValue` | geçti |
| Keycloak token'ları | değişmedi: ES256 kabul, RS256 ve `typ: JWT` ret, `realm_access.roles` okunur, `roles` okunmaz | `keycloakToken_whenEntraIsAlsoTrusted_isUnchanged`, var olan `PlatformSecurityTest`/`SecurityUnitTest` | geçti |
| MCP audience ayrımı, iki issuer | API token'ı `/mcp`'de, MCP token'ı `/v1`'de ret; bir issuer'ın MCP audience'ı ötekinde geçmez; metadata iki issuer'ı listeler | `mcpAudience_whenTokensCrossSurfaces_*`, `EntraIssuerTest.mcp_*` | geçti (son test CI) |
| Yalnız Entra kurulumu, ayar hataları | birincil issuer boşken yalnız Entra; `common`/`organizations`/`consumers`, http authority, boş audience, kesişen MCP audience, http JWKS açılışı durdurur | `entraOnly_*`, `entraSettings_*` | geçti |
| Panel | Keycloak yolları varsayılan; yapılandırılmış Entra v2.0 uçları kullanılır; https olmayan uç reddedilir; `roles` panel rolü olarak okunur | `panel.test.mjs` `oidc:` ve `roles: Entra…` | geçti |

## Review skill'leri

| Skill | Karar |
|---|---|
| `verso-security-review` | APPROVE WITH NON-BLOCKING COMMENTS (MEDIUM: Keycloak `sub` ile Entra `oid` ortak hesap ad alanı, ADR-0041'de kabul edilen risk; LOW: grup ad alanı, Keycloak tam yol `/` ile ayrışır) |
| `verso-environment-impact-review` | APPROVE WITH NON-BLOCKING COMMENTS (LOW: overage sayacı için alarm kuralı yok; CSP elle) |
| `verso-spring-code-review` | APPROVE WITH NON-BLOCKING COMMENTS (LOW: `JwtValidation.primary()` yardımcı kaydında `authorities` null, yalnız decoder yolunda) |
| `verso-test-writer` | PASS (overage log privacy testi eklendi) |
| `verso-api-contract-review` | APPROVE (DTO/controller değişmedi; metadata listesi yalnız uzar) |

## Net kanıt bulunamadı (gerçek kiracı gerekir)

- Gerçek bir Entra kiracısının v2.0 access token'ı: başlıkta `typ: JWT`, `ver`, `tid`, `scp`, `oid` biçimi ve JWKS (`x5c`'li RSA anahtarları) testteki biçimle aynı varsayıldı.
- Panelin Entra SPA akışı: PKCE ile token ucu CORS'u, `offline_access` ile yenileme, `ui_locales`, çıkışta `id_token_hint` ve `post_logout_redirect_uri`.
- Grup overage'ının gerçek token'daki biçimi; ulusal bulut issuer'ları; JWKS aynası ile yerel mod.
