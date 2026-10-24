# ADR-0019: Token rolleri ve grupları (`verso-admin`, `verso-user`)

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras (kurumsal yol haritası, madde Ö1); uygulama ayrıntıları bu ADR'de
- **İlgili eşik (referans Bölüm 24):** yok (yetki modeli). Rol değişikliğinin token ömrü kadar gecikmesi kabul edilemez hale gelirse ya da Verso kendi kullanıcı yönetimini isterse yeniden açılır.

## Bağlam

- Bugüne kadar sunucu rol okumuyordu. `platform-security` içinde `realm_access`, `GrantedAuthority` ve `hasRole` geçmiyordu. Rolleri yalnız panel, ekran göstermek için okuyordu (`panel/js/roles.js`, `verso-operator`, ADR-0015).
- Sıradaki kurumsal özellikler sunucuda rol ister: değer panosu, audit, paylaşılan koleksiyonlar. Bu özellikler bir kullanıcının kendi hesabı dışında bir şey görmesini gerektirir.
- Kimlik IdP'dedir (ADR-0005). IdP'ler rolleri farklı yerlere koyar:
  - Keycloak realm rollerini `realm_access.roles`'a, istemci rollerini `resource_access.<istemci>.roles`'a koyar. Grupları ancak bir `groups` protocol mapper'ı varsa koyar.
  - Entra ID uygulama rollerini `roles`'a, grup object id'lerini `groups`'a koyar.
- Referans 9.8 yetkiyi `@PreAuthorize("hasRole(...)")` ile verir. Rol token'da taşınıyorsa değişiklik token ömrü kadar gecikir. ADR-0007 #50 bunu kabul etti: `sv` yok, access token 300 sn.

## Seçenekler

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| A. Spring'in `JwtGrantedAuthoritiesConverter`'ı (`authorities-claim-name`) | Kod yok | Tek bir üst düzey claim okur. İç içe yol (`realm_access.roles`) ve aynı anda rol + grup okuyamaz | Düşük |
| B. Keycloak'a özgü dönüştürücü (`realm_access` sabit) | Basit | Entra ya da başka IdP'li müşteri için kod değişikliği gerekir | Düşük |
| **C. Ayarla yönetilen dönüştürücü: claim yolları ve önekler property'den** | IdP'den bağımsız. Keycloak, Entra ve benzerleri yalnız config farkıdır | Yollar nokta ile ayrıldığı için adında nokta geçen claim okunamaz | Düşük |
| D. Roller Verso veritabanında, her istekte okunur | Rol değişikliği anında etkili olur | Verso'ya kullanıcı yönetimi gelir; kaynak IdP iken ikinci bir kaynak olur (ADR-0005). Her istekte bir sorgu daha | Yüksek |

## Karar

**C** seçildi. B, Entra müşterisinde kodu değiştirmeyi gerektirirdi. A iç içe yolu okuyamaz. D, ADR-0005'in "kimlik IdP'de" kararıyla çelişir.

### Roller

| Rol | Kim | Anlamı |
|---|---|---|
| `verso-user` | Kimliği doğrulanmış **her** çağıran; token rol taşımasa da | Bugünkü haklar: kendi belgeleri, soru sorma, `/v1/info` |
| `verso-admin` | Yalnız token bu rolü açıkça taşıyorsa | Yönetim uçları: herkesin belgelerini yönetme, panolar, audit (gelecek uçlar). Admin aynı zamanda kullanıcıdır |

- Verso rolü olmayan kullanıcı normal kullanıcıdır. Bugünkü kullanıcıların hakları değişmez; panel de rolsüz oturumu `verso-user` sayıyordu (ADR-0015).
- `verso-operator` yalnız panelin Sistem ekranını gösteren rol olarak kalır ve API'de anlamı yoktur. İki rolü birleştirmek ayrı bir panel değişikliğidir (aşağıda yeniden değerlendirme).
- Rol adları IdP'nin verdiği değerlerdir: Keycloak realm (ya da istemci) rolü, Entra uygulama rolü değeri. "Bir gruba admin yetkisi" IdP'de verilir: Keycloak'ta role gruba atanır, Entra'da uygulama rolü gruba atanır. Verso'da ikinci bir grup → rol eşlemesi yoktur; tek kaynak IdP'dir.

### Uçlar ve gereken roller

| Uç | Gereken | Not |
|---|---|---|
| `POST /v1/documents`, `GET /v1/documents`, `GET /v1/documents/{id}`, `DELETE /v1/documents/{id}` | `verso-user` (her kimlikli çağıran) | Sahiplik değişmedi: yalnız kendi belgeleri, sorguda `account_id` (ADR-0011, llm-rules 4.1). `verso-admin` **bugün başkasının belgesini görmez**; bu, kendi ucuyla ve review'uyla gelir |
| `POST /v1/questions` | `verso-user` | Retrieval yine yalnız çağıranın belgelerinde |
| `GET /v1/info` | `verso-user` | Yanıta çağıranın Verso rolleri eklendi (`roles`) |
| Health, yönetim portundaki Prometheus | token yok | Değişmedi (ADR-0010, ADR-0014) |
| `/actuator`, `/actuator/info` | kimlikli çağıran | Değişmedi |
| Gelecek yönetim uçları (değer panosu, audit, herkesin belgeleri) | `verso-admin` | `@PreAuthorize(VersoRoles.HAS_ADMIN)` |

Bugün yalnız-admin bir uç yoktur; mekanizma `PlatformSecurityTest`'teki bir deneme ucuyla sınanır. Catch-all zincir `authenticated()` olarak kaldı: her kimlikli çağıran `verso-user` olduğu için ikisi eşdeğerdir.

### Yetkiler (authority)

`ClaimAuthoritiesConverter` (`platform-security`) her access token'dan şunları üretir:

| Yetki | Kaynak |
|---|---|
| `SCOPE_*` | `scope` / `scp` (önceki davranış korunur) |
| `ROLE_verso-user` | her token |
| `ROLE_<rol>` | `roles-claim` ve (varsa) `client-roles-claim` değerleri |
| `GROUP_<grup>` | `groups-claim` değerleri (Keycloak'ta tam yol `/hukuk/sozlesmeler`, Entra'da object id) |

- `hasRole(...)`, filtre zincirinde ve `@PreAuthorize`'da aynı öneki kullanır (`GrantedAuthorityDefaults`).
- Principal adı yine `sub`'dır; `@CurrentAccount` değişmedi (ADR-0005).
- Yöntem güvenliği (`@EnableMethodSecurity`) starter'da açıktır. Ret, `AuthorizationDeniedException` olarak 403 `ACCESS_DENIED` (90101) zarfına gider (ADR-0010).

**Kapalı kalma (fail closed):**

- Eksik claim hiçbir yetki vermez.
- Bozuk claim token'ı reddetmez, yalnız atlanır. Bozuk sayılanlar: liste ya da metin olmayan değer, nesne olmayan bir ara düğüm. Bir IdP tuhaflığı kullanıcıya fazladan yetkiyi kaybettirir, oturumu değil.
- Bir değer boşsa, 256 karakterden uzunsa ya da kontrol veya biçim karakteri (bidi override gibi) taşıyorsa atlanır. Bir claim'den en çok 256 değer okunur; Entra zaten JWT'ye en çok 200 grup koyar.
- Bozuk claim, claim türü ve neden başına bir kez WARN olarak loglanır: `Authority claim rejected: code=MALFORMED_CLAIM reason=NOT_A_LIST claim=roles`. Değerler (rol ve grup adları) hiçbir yere yazılmaz.
- Rol ve grup önekleri açılışta denetlenir. Büyük harf, rakam ve `_` olmalı, `_` ile bitmelidir. Önekler biri diğerinin başı olamaz, `SCOPE_` ve `FACTOR_` ile çakışamaz. Böylece adı `ROLE_verso-admin` olan bir grup rol olamaz. Kural ihlalinde uygulama açılmaz.

### Ayarlar

| Key (`verso.security.authorities.*`) | Ortam değişkeni | Varsayılan | Keycloak | Entra ID |
|---|---|---|---|---|
| `roles-claim` (zorunlu) | `OIDC_ROLES_CLAIM` | `realm_access.roles` | `realm_access.roles` | `roles` |
| `client-roles-claim` (opsiyonel) | `OIDC_CLIENT_ROLES_CLAIM` | boş | istemci rolleri kullanılıyorsa `resource_access.verso-api.roles` | boş |
| `groups-claim` (opsiyonel) | `OIDC_GROUPS_CLAIM` | `groups` | `groups` | `groups` |
| `role-prefix` | – (her ortamda aynı) | `ROLE_` | | |
| `group-prefix` | – (her ortamda aynı) | `GROUP_` | | |

- Claim yolları IdP'ye göre değiştiği için ortama özgüdür. `config/verso.yml`, `application-local.yml`, `deploy/prod.env.example` ve `compose.yaml`'da birlikte durur; `ConfigDriftTest` bunu sabitler.
- Varsayılanlar Keycloak'ındır. Bu yüzden yeni değişkenleri tanımlamayan eski bir ortam dosyası açılışı bozmaz.
- Yanlış yol hiçbir rol okumaz: herkes `verso-user` olur, kimse admin olmaz.

### Entra ID eşlemesi

1. Uygulama kaydında iki uygulama rolü açılır: değerleri `verso-admin` ve `verso-user`. Kullanıcılara ya da gruplara atanır; gruba atanan rol o grubun üyelerinin token'ına girer.
2. `OIDC_ROLES_CLAIM=roles`, `OIDC_GROUPS_CLAIM=groups`, `OIDC_CLIENT_ROLES_CLAIM=` boş.
3. Grup claim'i için `groupMembershipClaims: "ApplicationGroup"` önerilir. 200'den fazla grupta Entra `groups`'u koymaz, yerine `_claim_names` (overage) yazar. Verso o zaman grup görmez (fail closed) ve Graph'a gitmez.
4. **Sınır:** Entra access token'ları RS256 imzalı ve `typ: JWT` başlıklıdır. ADR-0010'un token kuralları (yalnız ES256, `at+jwt`) bunları bugün reddeder. İki yol var:
   - Keycloak'ı Entra'ya aracı yapmak (identity brokering). Keycloak ES256 `at+jwt` token üretir; Entra rolleri ve grupları IdP mapper'larıyla Keycloak rollerine taşınır, Keycloak varsayılanları geçerli olur.
   - Ayrı bir ADR ile RS256'yı ve boş `type-header`'ı açmak.

   **Güncelleme (ADR-0041):** ikinci yol, global kuralı gevşetmeden, issuer başına güven listesiyle seçildi. Entra ID ikinci bir güvenilen issuer'dır (`OIDC_ENTRA_*`): yalnız RS256, `typ: JWT`, v2.0, kiracı `tid`, dolu `scp`; roller `roles`, gruplar `groups`, hesap `oid`; overage'da grup verilmez. Keycloak aracılığı isteyen kurulum için geçerli kalır. Panel rolü artık `roles`'tan da okur.

   Gerçek bir Entra kiracısıyla denenmedi: **net kanıt bulunamadı**. Dönüştürücü Entra biçimini birim ve uçtan uca testlerde okur.

Başka IdP'ler: Okta'da `groups` claim'i ve özel bir `roles` claim'i tanımlanır. Auth0 özel claim'lere ad alanı (`https://…/roles`) koyar. Adında nokta geçtiği için bu claim bu yol sözdizimiyle okunamaz (yeniden değerlendirme koşulu).

### Panel ve demo realm

- `GET /v1/info` yanıtı `roles` alanını taşır. Örnek: `["verso-user"]` ya da `["verso-admin","verso-user"]`.
- Yanıtta yalnız Verso rolleri olur. IdP'nin diğer rolleri ve gruplar müşterinin dizin verisidir; yanıta girmez.
- Yanıt `no-store, private` kalır. Değişiklik eklemedir; panel ve diğer istemciler bilmedikleri alanı yok sayar (referans 17).
- Panel değişmedi: menüyü hâlâ token'daki `realm_access.roles`'tan seçer. İleride yalnız-admin arayüzü bu alandan gizleyebilir.
- Realm (`deploy/keycloak/realm/verso-realm.json`):
  - `verso-admin` ve `verso-user` realm rolleri eklendi.
  - `admin` kullanıcısı `verso-admin` (ve panel için `verso-operator`), `demo` kullanıcısı `verso-user` (ve `verso-operator`) aldı.
  - `verso-panel` ve `verso-cli` istemcilerine `groups` mapper'ı eklendi: `oidc-group-membership-mapper`, tam yol, yalnız access token.
  - `verso-cli`'ye realm rol mapper'ı da eklendi: komut satırı panelle aynı yetkiyi alır.
  - `verso-ci` (client credentials, kişi değil) mapper almadı; yalnız `verso-user`'dır.
- Var olan bir yığın realm'i yeniden içe aktarmadan yeni rolleri görmez: `bash scripts/keycloak-reimport.sh` (ADR-0015).

## Sonuçlar

- **Olumlu:**
  - Yönetim uçları tek satırla korunur: `@PreAuthorize(VersoRoles.HAS_ADMIN)`.
  - IdP değişimi kod değil, config değişikliğidir.
  - Bugünkü kullanıcıların davranışı değişmedi.
  - Gruplar yetki olarak hazır. Koleksiyon ACL'inin principal kümesi (`user:<sub>`, `group:<id>`, `role:<ad>`; yol haritası Ö1, madde 3) bu yetkilerden türetilecek; `@CurrentPrincipals` o işle gelir.
- **Olumsuz / kabul edilen risk:**
  - Rol değişikliği access token ömrü kadar (300 sn) gecikir; `sv` yok (ADR-0007 #50). Rolü alınan admin bu süre boyunca admin kalır.
  - Grup claim'i token'ı büyütür ve kullanıcının gruplarını taşır. Token loglanmaz (ADR-0010); `/v1/info` grupları geri vermez.
  - Keycloak grup tam yolu, grup yeniden adlandırılınca değişir. Gruba yol ile verilen izinler (gelecek koleksiyonlar) bayatlar. Entra'nın object id'leri bu sorunu taşımaz. Koleksiyon ADR'si bunu ele almalıdır.
  - Panel rolü hâlâ `realm_access.roles`'tan okur. Entra'yı doğrudan kullanan bir kurulumda panel Sistem ekranını göstermez; sunucu yetkisi etkilenmez.
  - `verso-operator` ile `verso-admin` ayrı iki roldür.
- **Etkilenen dosyalar:**
  - `platform/platform-security` (`authority/{ClaimAuthoritiesConverter,VersoAuthorityProperties,VersoRoles,CallerRoles}`, `PlatformSecurityAutoConfiguration`, test-jar `TestIdentityProvider`).
  - `qa-api` `ServiceInfoResponse`, `qa-core` `InfoController`.
  - Config: `config/verso.yml`, `application-local.yml`, `deploy/prod.env.example`, `compose.yaml`.
  - `deploy/keycloak/realm/verso-realm.json`; testler `ConfigDriftTest`, `KeycloakRealmTest`, `QuestionApiTest`, `AnthropicCloudModeTest`.
- **Geri alma yolu:** Dönüştürücü bean'i kaldırılınca Spring'in varsayılanına (yalnız `SCOPE_*`) dönülür. Bugün hiçbir uç rol istemediği için hiçbir uç kırılmaz; `/v1/info`'daki `roles` alanı kaldırılır. Realm'deki roller ve mapper'lar zararsız kalabilir.

## Yeniden değerlendirme koşulu

- Rol değişikliğinin anında etkili olması gerekirse (ör. işten ayrılan admin): rol kaynağı her istekte okunur ya da token ömrü kısaltılır (referans 9.8).
- Entra'yı aracısız kullanan ilk müşteri: RS256 ve `type-header` için ayrı ADR. Panelin rolleri `/v1/info`'dan okuması da bu işle gelir.
- Adında nokta geçen bir claim'e ihtiyaç (Auth0 ad alanları): yol sözdizimi kaçış ya da liste biçimi kazanır.
- İlk yalnız-admin uç eklenince: rolü `verso-operator`'la birleştirmek (panel rol matrisi tek rol) ve "herkesin belgeleri" için sahiplik kuralının nasıl genişleyeceği o ucun ADR'sinde yazılır.
