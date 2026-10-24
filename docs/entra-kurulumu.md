# Microsoft Entra ID ile kurulum (Keycloak'sız)

Bu rehber, kurumun Microsoft Entra ID kiracısını Verso'ya doğrudan bağlar (ADR-0041). Verso yalnız token doğrular; kullanıcı, rol ve grup Entra'da yönetilir (ADR-0005, ADR-0019). Keycloak aracılığıyla bağlanmak (identity brokering) isteyen kurulum bu rehberi uygulamaz.

Değerler kuruma özeldir; aşağıda yer tutucu olarak geçer: `<tenant>` dizin (kiracı) kimliği, `<api-app>` API uygulamasının istemci kimliği, `<panel-app>` panel uygulamasının istemci kimliği, `<mcp-app>` MCP uygulamasının istemci kimliği, `<verso>` Verso'nun genel adresi. Secret yoktur: panel ve MCP istemcileri genel (public) istemcidir, PKCE kullanır.

## 1. API uygulaması (kaynak)

Entra yönetim merkezi → **App registrations** → **New registration**:

1. Ad: `Verso API`. Desteklenen hesap türü: **Accounts in this organizational directory only** (tek kiracı). Çok kiracılı seçenekler Verso'da açılışta reddedilir.
2. **Manifest**: `"requestedAccessTokenVersion": 2` (yeni manifestte `api.requestedAccessTokenVersion`). v1.0 token'ları reddedilir (`ver` = `2.0` zorunlu).
3. **Expose an API**: Application ID URI (`api://<api-app>` önerilir) ve bir kapsam: `access_as_user` (yönetici ve kullanıcı onaylayabilir).
4. **App roles**: iki rol, ikisi de **Users/Groups** için:
   - Değer `verso-admin`: yönetim ekranları ve uçları.
   - Değer `verso-user`: isteğe bağlı; rolü olmayan her kimlikli kullanıcı zaten `verso-user`'dır.
   - Panelde Sistem ekranı isteniyorsa `verso-operator` da eklenebilir (yalnız panel görünümü).
5. **Enterprise applications** → `Verso API` → **Users and groups**: rolleri kullanıcılara veya gruplara atayın. "Bir gruba admin yetkisi" burada verilir; Verso'da ikinci bir eşleme yoktur.
6. **Token configuration** → **Add groups claim**: **Groups assigned to the application** (`groupMembershipClaims: "ApplicationGroup"`), access token için **Group ID**. Bilgi alanı erişimleri (ADR-0028) grup object id'siyle verilir.
   - 200'den fazla grupta Entra `groups` yerine bir Graph bağlantısı (overage) koyar. Verso o kullanıcıya **hiç grup vermez** ve `verso.security.groups.overage` sayacını artırır; Graph'a gitmez. Uygulamaya atanmış gruplarla sınırlamak bunu önler.
7. Uygulama imza anahtarı özelleştirmesi (claims mapping policy, özel imza anahtarı) yapmayın: Verso kiracının ortak anahtarlarını kullanır.

## 2. Panel uygulaması (SPA istemcisi)

1. Yeni kayıt: `Verso Panel`, tek kiracı.
2. **Authentication** → **Add a platform** → **Single-page application**; yönlendirme adresi `https://<verso>/panel/`, çıkış sonrası adres de aynı. (SPA türü token ucunda CORS'u açar ve PKCE ister.)
3. **API permissions** → **Add a permission** → **My APIs** → `Verso API` → `access_as_user` (delegated). Kurum için yönetici onayı verin.
4. Panel ayrı bir kayıttır: panelin ID token'ının `aud`'u `<panel-app>` olur ve API audience'ına girmez. Verso ayrıca `scp` ister; ID token'da `scp` yoktur.

`panel/js/config.js` içindeki `CONFIG`:

```js
issuer: 'https://login.microsoftonline.com/<tenant>/v2.0',
clientId: '<panel-app>',
authorizationEndpoint: 'https://login.microsoftonline.com/<tenant>/oauth2/v2.0/authorize',
tokenEndpoint: 'https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token',
endSessionEndpoint: 'https://login.microsoftonline.com/<tenant>/oauth2/v2.0/logout',
scope: 'openid profile offline_access api://<api-app>/access_as_user',
```

Kenar CSP'si (`deploy/edge/nginx.conf`, panel bloğu) statiktir: bu kurulumda `connect-src 'self' http://localhost:8180` yerine `connect-src 'self' https://login.microsoftonline.com` yazın (README, "Microsoft Entra ID"). Panel token ucunu tarayıcıdan çağırır; yetkilendirme ve çıkış birer sayfa geçişidir, CSP'ye girmez.

## 3. MCP uygulaması (isteğe bağlı, ADR-0031)

MCP için ayrı bir kaynak gerekir; böylece panel token'ı `/mcp`'de, MCP token'ı `/v1`'de geçmez.

1. Yeni kayıt: `Verso MCP`, tek kiracı, `requestedAccessTokenVersion: 2`, **Expose an API** ile `access_as_user` kapsamı, aynı grup claim ayarı. Rol tanımlamayın (MCP salt okunur, rol istemez).
2. MCP istemcisini (ör. kurumun yapay zekâ istemcisi) ayrı bir genel istemci olarak kaydedin ve bu kapsama izin verin. Dinamik istemci kaydı yoktur.
3. `OIDC_ENTRA_MCP_AUDIENCES=<mcp-app>`. API audience'larıyla aynı değer verilirse uygulama açılmaz.

## 4. Verso ayarları

`deploy/prod.env.example` (compose'ta `.env`):

| Değişken | Değer |
|---|---|
| `OIDC_ENTRA_TENANT_ID` | `<tenant>` (GUID; `common`/`organizations` kabul edilmez) |
| `OIDC_ENTRA_AUTHORITY` | `https://login.microsoftonline.com` (ulusal bulutlarda o bulutun giriş adresi) |
| `OIDC_ENTRA_AUDIENCES` | `<api-app>` (v2.0 token'larında `aud` istemci kimliğidir); istenirse `,api://<api-app>` |
| `OIDC_ENTRA_MCP_AUDIENCES` | `<mcp-app>` ya da boş |
| `OIDC_ENTRA_JWK_SET_URI` | boş (Entra'nın kendi anahtar adresi) ya da iç ağdaki ayna |
| `OIDC_ISSUER`, `OIDC_JWK_SET_URI` | yalnız Entra kullanılacaksa ikisi de boş; Keycloak'tan geçişte Keycloak değerleri kalır |

Rol ve grup claim'leri Entra için sabittir (`roles`, `groups`); `OIDC_ROLES_CLAIM` vb. yalnız birincil issuer'ı etkiler.

**Yerel mod (ADR-0013):** uygulamanın internete yolu yoktur. Anahtarlar için `OIDC_ENTRA_JWK_SET_URI` iç ağdan erişilen bir JWKS aynasını göstermelidir (`https://login.microsoftonline.com/<tenant>/discovery/v2.0/keys` içeriğini düzenli olarak yansıtan bir uç). Ayna anahtar rotasyonunu izlemelidir: Entra yeni anahtarı kullanmadan önce yayınlar; Verso bilinmeyen `kid` için en fazla 30 sn'de bir yeniden çeker. Bulut modunda (`egress` ağı) ayna gerekmez.

## 5. Doğrulama

1. Panelde giriş yapın; `GET /v1/info` yanıtının `roles` alanı atanan rolü göstermelidir.
2. Kabul edilen token: `iss` = `https://login.microsoftonline.com/<tenant>/v2.0`, `ver` = `2.0`, `tid` = `<tenant>`, `aud` = `<api-app>`, `scp` dolu, başlıkta `alg: RS256`, `typ: JWT`. Hesap kimliği `oid`'dir; Verso'daki sahiplik ve denetim kayıtları bu değeri taşır.
3. Ret nedeni yanıtta yer almaz (ADR-0010); log yalnız `INVALID_TOKEN` yazar. Sık nedenler: v1.0 token (`requestedAccessTokenVersion` ayarlanmamış), yanlış audience (kapsam `api://<api-app>/...` değil, Graph token'ı alınmış), başka kiracı.
4. Grup erişimi çalışmıyorsa `verso_security_groups_overage_total` metriğine bakın.

## Kapsam dışı

Graph üzerinden grup okuma (overage), çok kiracılı kurulum, uygulama kimliğiyle (client credentials) Verso çağırma, Entra B2C / External ID. Gerekçeler ADR-0041'dedir.
