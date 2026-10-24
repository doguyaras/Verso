# MCP sunucusu doğrulaması (ADR-0031)

Tarih: 2026-10-24. Branch: `feature/doguyaras-mcp-sunucusu` (taban `develop` `a24cc0c`). Makine: tek geliştirici makinesi (Windows 11, JDK 25.0.4).

## Sürümler

| Bileşen | Sürüm | Kaynak |
|---|---|---|
| Spring AI MCP sunucu starter'ı | `spring-ai-starter-mcp-server-webmvc` 2.0.1 | `spring-ai-bom` 2.0.1 |
| MCP Java SDK | `io.modelcontextprotocol.sdk:mcp` / `mcp-core` 2.0.0 | starter'ın bağımlılığı |
| Desteklenen spesifikasyon | **2025-11-25** (en yeni), geriye 2025-06-18, 2025-03-26, 2024-11-05 | SDK `ProtocolVersions`; `McpServerTest`: `initialize` → `2025-11-25` |
| `com.networknt:json-schema-validator` | 3.0.1 (yakınsama: SDK 3.0.0 ister) | kök `pom.xml` |

OSV (`api.osv.dev/v1/querybatch`, 2026-10-24): `mcp-core` 2.0.0, `mcp-json-jackson3` 2.0.0, `mcp-spring-webmvc` 2.0.1, `spring-ai-autoconfigure-mcp-server-common` 2.0.1, `json-schema-validator` 3.0.1, `jackson-dataformat-yaml` 3.1.7, `snakeyaml-engine` 3.0.1, `itu` 1.14.0: bilinen zafiyet yok. Lisanslar: MIT (MCP SDK), Apache-2.0 (diğerleri).

## Otomatik testler

| Test | Sonuç | Ne kanıtlar |
|---|---|---|
| `./mvnw -B -ntp verify` (rebase öncesi, B1 düzeltmesinden önce) | 279 test, BUILD SUCCESS | Bütün modüller, mimari testler, Testcontainers |
| `McpServerTest` (9) | geçti (B1 düzeltmesinden önce, gerçek port + PostgreSQL) | protokol, güvenlik matrisi, sınırlar, 429, log privacy, denetim |
| `McpDisabledTest` (2) | geçti | kapalıyken 404, bean yok, kenar dosyaları |
| `McpUnitTest` (5), `McpModeCheckTest` (2) | geçti (Docker'sız, son kod) | karakter temizliği, kesme, `WWW-Authenticate`, ayar sınırları, beklenmeyen hata metni sızmaz, anahtar uyumu |
| `ArchitectureRulesTest`, `ModuleStructureTest`, `ConfigDriftTest`, `ErrorCodeUniquenessTest`, `KeycloakRealmTest`, `ComposeConfigTest`, `PlatformSecurityTest`, `SecurityUnitTest` | geçti (Docker'sız) | sınırlar, yeni modül, yansıtılan anahtar, realm, edge |
| `node --test scripts/*.test.js scripts/panel.test.mjs` | 179 geçti, 0 kaldı | `uyum-paketi.test.js`: VKED 5.3 sayıları `verso.yml` ile eşleşir |

Testcontainers sınıfları son koddan sonra yerelde yeniden koşulmadı (makinede Docker kullanıcı tarafından kapatıldı); bütünleşik kapı GitHub CI'daki tam takımdır.

## Güvenlik matrisi (`McpServerTest`)

| Durum | Beklenen | Sonuç |
|---|---|---|
| Token yok | 401, `WWW-Authenticate: Bearer resource_metadata="…/.well-known/oauth-protected-resource/mcp", scope="verso-mcp"`, `X-Rag-Mode` | geçti |
| Panel token'ı (`aud` `verso-api`) `/mcp`'de | 401 `invalid_token` + `resource_metadata` | geçti |
| MCP token'ı (`aud` `verso-mcp`) `/v1/documents`'te | 401 | geçti |
| Yabancı issuer, doğru audience | 401 | geçti |
| Grup A (`/hukuk`) aynı konudaki B (`/ik`) pasajını | hiç almaz; grupsuz kullanıcı ikisini de almaz | geçti |
| B'nin pasaj kimliğini A çeker | olmayan ve bozuk kimlikle **aynı** hata; denetimde `NOT_FOUND`, B'nin belge kimliği yok | geçti |
| `Origin` başlığı | 403 | geçti |
| MCP kapalı (`McpDisabledTest`) | geçerli API token'ıyla `/mcp` ve metadata 404; MCP token'ı 401 | geçti |
| Boyut | sorgu > 1000 → `isError`, `top_k` 0/11/2.5/"3" → `isError`, gövde > 32 KB → 413 `90014`, alıntı ≤ 600 ve `…` | geçti |
| Eşzamanlılık | 2 çağrı modelde beklerken üçüncüsü 429 `90102` + `Retry-After: 1`; sonra yeniden 200 | geçti |
| Log privacy | işaretli sorgu, dosya adı, pasaj, bozuk JSON-RPC: hiçbir log olayında (mesaj, MDC, throwable) yok; `outcome=` satırı var | geçti |

## Elle kontrol (kısmi)

Dal uygulaması `127.0.0.1:18090`'da MCP açık, dal realm'iyle dev modda geçici Keycloak `127.0.0.1:18181`'de (commit edilmeyen yerel ayar). Docker Desktop yük altında durmadan önce görülenler:

- İlk realm sürümünde Keycloak'ın `scopes_supported`'ı yalnız `openid, basic, verso-mcp, offline_access` idi: realm'de `clientScopes` tanımlamak yerleşik scope'ların (profile, email, roles, web-origins, service_account …) oluşturulmasını engelliyordu. Düzeltme ve karşılaştırma aşağıda ("Realm regresyonu").
- `verso-mcp-inspector` ile code + PKCE: onay ekranı göründü (`OAUTH_GRANT`); token `typ at+jwt`, `ES256`, `aud` yalnız `verso-mcp`, realm rolü yok.
- `verso-panel` ile code + PKCE aynı realm'de çalışmaya devam etti: `aud` `verso-api`, roller `verso-operator`, `verso-user`.
- `POST /mcp` token'sız: `401`, `WWW-Authenticate: Bearer resource_metadata="http://localhost:18090/.well-known/oauth-protected-resource/mcp", scope="verso-mcp"`.
- `GET /.well-known/oauth-protected-resource/mcp`: `200`, `{"resource":"http://localhost:18090/mcp","bearer_methods_supported":["header"],"authorization_servers":["http://localhost:18181/realms/verso"],"scopes_supported":["verso-mcp"],"resource_name":"Verso"}`.
- Sonraki adımlar (token'lı çağrılar, yükleme, `tools/list`, `tools/call`) `503` döndü: yığının PostgreSQL'i ve Keycloak konteyneri Docker ile birlikte düştü. Uygulama log'unda sorgu metni 0 kez.
- Yapılmadı: MCP Inspector CLI (`npx`) ve bulut istemcileri; Docker döndüğünde `docs/api-mcp-integration-v1.md` "curl ile deneme" adımlarıyla tekrarlanır. Geride kalan konteyner: `verso-mcp-kc-scratch` (silinmeli).

## Realm regresyonu ve düzeltmesi

Tek bir geçici Keycloak 26.8.1 konteyneri (1 GB, `127.0.0.1:18181`, dev modu) iki realm'i birlikte içe aktardı: `develop`'taki realm dosyası (`verso-dev` adıyla, `clientScopes` yok) ve bu dalın dosyası. Admin API ile karşılaştırma, konteyner sonra silindi:

| Kontrol | `develop` | Dal (düzeltmeden sonra) |
|---|---|---|
| Realm scope'ları | 15 yerleşik scope | aynı 15 + `verso-mcp` |
| Realm varsayılan / isteğe bağlı scope listeleri | 9 / 5 | aynı |
| `verso-panel`, `verso-cli` istemci scope'ları | `basic` | aynı |
| `verso-ci` istemci scope'ları | `basic`, `service_account` | aynı (ilk sürümde `service_account` yoktu) |
| `verso-panel` token claim'leri (code + PKCE) | `aud,auth_time,azp,exp,iat,iss,jti,realm_access,scope,sid,sub,typ`; roller `verso-operator`, `verso-user` | aynı |
| Token ucunun CORS cevabı (`Origin: http://localhost:8080`) | `Access-Control-Allow-Origin: http://localhost:8080` | aynı |
| `verso-ci` token claim'leri | `aud,azp,clientAddress,clientHost,client_id,exp,iat,iss,jti,scope,sub,typ` | aynı |
| MCP istemcisi başka origin'den token ucu | – | `Invalid origin` (beklenen: `webOrigins` yalnız `localhost:6274`) |

Scope tanımları Keycloak'ın admin API'sinin `develop` realm'i için verdiği haliyle kopyalandı. İçe aktarmada Keycloak beş mapper'a eksik `userinfo.token.claim` anahtarını `true` olarak ekliyor (`acr`, `basic`, `microprofile-jwt`, `organization`, `service_account`). Anahtar yokken de Keycloak, ID token'a giren claim'i userinfo'ya koyar. Etkisi aynıdır, Verso userinfo okumaz. Gerileme testi: `KeycloakRealmTest.clientScopes_whenDeclared_keepKeycloaksBuiltInSetAndDefaults`.

## Review kararları

| Skill | Karar | Bulgular |
|---|---|---|
| `verso-security-review` | APPROVE WITH NON-BLOCKING COMMENTS | B1 MEDIUM (düzeltildi): DB kesintisi gibi beklenmeyen bir hata SDK'da JSON-RPC hata mesajı olarak istemcinin modeline gidiyordu → sabit `isError` metni, log'a yalnız tip (`McpUnitTest`). B2 MEDIUM (kabul): kullanıcı başına hız sınırı yok (Redis yok); kenarda adres başına `limit_req` + instance başına bulkhead. B3 LOW (düzeltildi): Spring Security 7.1 API zincirinde Host'tan türeyen RFC 9728 metadata'sını kimliksiz sunuyordu → `versoWellKnownSecurity`, 404. B4 HIGH (düzeltildi): realm dosyasında `clientScopes` tanımı Keycloak.ın yerleşik scope.larını (web-origins, roles, profile, service_account …) kaldırıyordu → yerleşik set ve varsayılan listeler realm.de; panel ve CI token.ları ile CORS develop ile aynı ("Realm regresyonu") |
| `verso-api-contract-review` | APPROVE WITH NON-BLOCKING COMMENTS | OpenAPI diff yok, elle çıkarıldı. `RetrievedPassage.passageId` sona eklendi + eski kurucu; `DocumentRetrieval.passage` yeni (süreç içi, tek uygulayıcı); `AuditAction`/`AuditTargetType` yeni değerler (istemci bilinmeyen değeri ham gösterir; `api-audit-integration-v1.md` güncellendi). Kırılma yok |
| `verso-architecture-boundary-review` | APPROVE | `mcp` yalnız `document :: api` ve `audit :: api`'ye bağlı; tablo yok; enforcer ve ArchUnit yeşil |
| `verso-environment-impact-review` | APPROVE WITH NON-BLOCKING COMMENTS | Yüzeyler: `verso.yml`, `application-local.yml` (`verso.mcp.resource`, `ConfigDriftTest`'te aynalı), `prod.env.example`, `.env.example`, `compose.yaml` (x-db-env + edge), `compose.cloud.yaml`; `docker compose config` iki değerle doğru dosyayı seçiyor. LOW: `migrate` konteyneri de `VERSO_MCP_*` alır (web değil; zararsız, CI'da doğrulanacak) |
| `verso-spring-code-review` | APPROVE WITH NON-BLOCKING COMMENTS | `@Autowired`/`catch (Exception)` yok. LOW: araçlarda `RuntimeException` yakalanır (gerekçe B1); `McpRequestGuard` semaforu instance başına bulkhead (gerekçeli) |
| `verso-test-writer` | PASS | Eklenen: `McpServerTest` (9), `McpDisabledTest` (2), `McpUnitTest` (5), `McpModeCheckTest` (2), `KeycloakRealmTest#mcpClient_*`. Boşluk: READY olmayan belgenin pasajı (`passage` sorgusu `status = 'READY'`), MCP zincirinde IdP kesintisi (platformla aynı işleyici), kenar nginx'i çalıştırılarak değil dosyadan doğrulandı |
| `verso-llm-review` | APPROVE WITH NON-BLOCKING COMMENTS | Yeni çıkış yolu `llm-rules.md` 1.6 ile kural oldu; README mod bölümüne not. Prompt/model değişmedi, eval gerekmez; `min-score` 0,30 ölçülmedi (izleme) |
| `verso-resilience-review` | APPROVE WITH NON-BLOCKING COMMENTS | Kritik akış kaydına MCP satırı (1 embedding çağrısı, mevcut 10 sn ve 4'lü havuz; MCP en çok 2). LOW: `verso.mcp.requests.refused` için alarm yok; ikinci JWKS önbelleği (aynı IdP, 5 dk) |

## Açık işler

- Elle kontrolün kalanı ve MCP Inspector CLI (Docker döndüğünde).
- Claude Desktop / ChatGPT / Copilot Studio'nun ön kayıtlı istemci ayarları ve geri dönüş adresleri birincil kaynaktan doğrulanmadı.
- Kullanıcı başına hız sınırı (paylaşılan sayaç deposu gerekir); `min-score` için eval; panelde pasaja derin bağlantı (`url` şimdilik `verso://`).
- 2026-07-28 spesifikasyonu (Spring AI 2.1).
