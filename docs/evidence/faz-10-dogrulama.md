# Faz 10: Doğrulama ve kanıt kaydı

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

- **Tarih:** 2026-10-03
- **Ortam:** Windows 11 + Docker Desktop (Docker Engine 29.6.1, 16 GB, 8 CPU, GPU yok); uygulama içi tarayıcı (Chromium); GitHub Actions `ubuntu-latest`.
- **Kapsam:**
  - `panel/`: bağımlılıksız, derleme adımsız panel. Ekranlar: belgeler, soru sor, sistem.
  - Kenar proxy'de `/panel/` ve CSP; realm'de `verso-panel` istemcisi (code + PKCE) ve `verso-operator` rolü.
  - API ekleri: `GET /v1/info`; soru cevabında `outcome` alanı (review C1, geriye uyumlu).
  - `scripts/keycloak-reimport.sh`: faz 10'dan önce kurulmuş yığına panel istemcisini getirir.
  - Kararlar: ADR-0015, ADR-0007 #10 güncellendi.
- **Davranışsal kapsam (seviye 3):** panel çalışan yığında, gerçek Keycloak ve gerçek modellerle tarayıcıda kullanıldı (Bölüm 2).
- **Varsayımlar:** plate app paneli görülmeden yapıldı; ADR-0015 "Varsayımlar" bölümü kullanıcı onayı bekliyor.

## 1. Makine kontrolleri

| Kontrol | Komut | Sonuç |
|---|---|---|
| Build ve testler | `./mvnw -B -ntp verify` | **PASS**: 358 Java testi, faz 8 ve 9 ile birleştikten sonra (mutasyon baseline'ı birleşmeden önce 356) |
| Panel testleri | `node --test scripts/panel.test.mjs` | **PASS**: 14 test. Rol matrisi, PKCE (RFC 7636 vektörü), JWT okuma, atıf ayrıştırma, hata metinleri, güvenlik kuralları, giriş akışı (state, verifier, URL temizliği), paylaşılan refresh, çıkış, dosya adı temizliği |
| Realm ve compose | `KeycloakRealmTest`, `ComposeConfigTest` | **PASS**: istemcinin tam ayarı, CSP'nin tamamı, `absolute_redirect off` |
| Mutasyon | `bash scripts/mutation-check.sh` | Bölüm 5 |

## 2. Canlı denemeler

| Deneme | Sonuç |
|---|---|
| Giriş (PKCE) | **PASS**: Keycloak giriş sayfası, dönüşte kod değişimi, URL temizlendi, kullanıcı adı üst çubukta |
| Yükleme (dosya seçici) | **PASS**: PDF yüklendi, durum "Sırada" → "Hazır" kendiliğinden güncellendi |
| Soru (atıflı) | **PASS**: cevap, `[n]` işaretleri kaynak çipine dönüştü, kaynak listesi belge ve sayfa adıyla |
| Soru (model "bulunamadı" dedi) | **PASS**: review C1'den sonra "Kaynak gösterilemedi" uyarısı çıkmıyor (`outcome: NOT_FOUND`) |
| Sistem ekranı (operatör) | **PASS**: mod, chat ve embedding modeli, oturum süresi |
| `127.0.0.1:8080/panel/` | **PASS**: `localhost`'a yönlendi, giriş çalıştı (önce Keycloak "Invalid parameter: redirect_uri" veriyordu) |
| Trusted Types açıkken | **PASS**: belgeler ve soru ekranları çalıştı, konsolda CSP ihlali yok |
| Keycloak (review sırasında, curl) | **PASS**: `code_challenge`'sız istek ve `plain` method reddedildi; redirect URI tam eşleşme; implicit kapalı; `profile`/`email` scope'ları `invalid_scope`; yabancı `post_logout_redirect_uri` 400; token endpoint'ine başka origin'den POST 403 |

## 3. Review

Tek ajan, faz 9 ve faz 10 birlikte (`c1c4e63..eafc302`), beş skill.

| Skill | Karar | Faz 10'a düşen bulgular |
|---|---|---|
| `verso-security-review` | REQUEST CHANGES (sebebi faz 9'daki S1) | S4: ADR "ID token tarayıcıdan çıkmaz" diyordu, oysa çıkışta `id_token_hint` olarak IdP'ye gider. S5: innerHTML yasağı yalnız regex testindeydi. S6: onay penceresinde dosya adı bidi karakterleriyle yanıltıcı olabilirdi. S7: ölü "silent" kodu. Panelde XSS yolu bulunmadı; token saklama, state kontrolü ve Keycloak istemcisi doğru |
| `verso-api-contract-review` | APPROVE WITH NON-BLOCKING COMMENTS | C1: "bulunamadı" ile kaynaksız model metni ayırt edilemiyordu; panel standart bulunamadı cevabının altına yanlış uyarı koyuyordu. C2: bazı 9xxxx kodlarının mesajı yoktu. C3: `Retry-After` okunup kullanılmıyordu |
| `verso-environment-impact-review` | APPROVE WITH NON-BLOCKING COMMENTS | E1: portlar ve host sabit; `127.0.0.1` ile giriş kırılıyordu. E2: mutlak 301. E3: doküman kayması (review-checklist, ADR-0007 #10, repo-context, README test listesi). R1: kanıt dosyası yoktu |
| `verso-test-writer` | APPROVE WITH NON-BLOCKING COMMENTS | T1: liste ilk 50 belgede kalıyordu (kota 200); fazlası panelden silinemiyordu. T2: eşzamanlı iki refresh'ten ikincisi iptal edilmiş token'la oturumu düşürüyordu. T3: kalıcı 401'de sonsuz yönlendirme döngüsü. T4: hata sonrası poll sürüyordu, eski cevap yeni ekranın üstüne yazabiliyordu. Test boşlukları: giriş akışı, CSP'nin tamamı, realm alanları, cloud modda `/v1/info` |
| `verso-spring-code-review` | APPROVE WITH NON-BLOCKING COMMENTS | `InfoController` DTO'yu kendisi kuruyor; bu uç için kabul edildi |

## 4. Bulgu → düzeltme

| Bulgu | Düzeltme | Kalıcı kontrol |
|---|---|---|
| C1: bulunamadı / kaynaksız | Cevaba `outcome` (`ANSWERED`, `NOT_FOUND`, `UNCITED`); modelin kendi "bulunamadı" cümlesi `NOT_FOUND`. Metrik ve log aynı sınıflandırmayı kullanır. Panel görünümü `outcome`'a göre seçer | `QuestionApiTest.ask_whenTheModelCitesNothing_tellsNotFoundFromUncited`, M207, canlı deneme |
| T2: eşzamanlı refresh | Tek refresh paylaşılır (`refreshing ??=`) | `panel.test.mjs` "refresh: concurrent callers", M209 |
| T3: 401 döngüsü | Bir otomatik giriş; ikinci 401'de mesajlı giriş ekranı; başarılı çağrı sayacı sıfırlar | Kod incelemesi (DOM'a bağlı; node testi ve canlı deneme yok) |
| T1: 50 belge sınırı | Sayfalama, sayfa başına 100 (API üst sınırı) | `panel.test.mjs` (`PAGE_SIZE`) |
| T4: poll ve eski cevap | Hata sonrası poll durur; her gezinmede görünüm sayacı artar, geç gelen cevap atılır; kullanılmayan önbellek silindi | Kod incelemesi |
| S5: innerHTML yalnız testte | CSP'ye `require-trusted-types-for 'script'; trusted-types 'none'` | `ComposeConfigTest` (CSP'nin tamamı), M208, canlı deneme |
| S6: bidi karakterleri | `plainName`: kontrol ve biçim karakterleri ayıklanır (onay penceresi, tablo, bildirimler) | `panel.test.mjs`, M211 |
| S4, S7: ADR ifadesi, ölü kod | ADR-0015 düzeltildi; `silent` kaldırıldı | – |
| C2, C3: mesajlar, Retry-After | Bütün ortak kodların Türkçe mesajı; `Retry-After` mesajda | `panel.test.mjs` "text: …" |
| E1: host ve port | `127.0.0.1` → `localhost` yönlendirmesi; portların birlikte değişmesi gerektiği `config.js`, README ve ADR'de | Canlı deneme |
| E2: mutlak 301 | `absolute_redirect off` | `ComposeConfigTest` |
| E3, R1: doküman | review-checklist, ADR-0007 #10, repo-context, README; bu kanıt dosyası | – |
| Test boşlukları | Giriş akışı ve çıkış node testleri; realm'de implicit/direct grant/consent/PKCE/rol mapper/rol tanımı; cloud modda `/v1/info`; `info` testleri ikiye ayrıldı | `panel.test.mjs`, `KeycloakRealmTest`, `AnthropicCloudModeTest`, M210 |

Kabul edilen açıklar:

- **Portlar:** panel varsayılan portlarla çalışır. Port değişirse `config.js`, realm istemcisi ve CSP birlikte değişir; bunu şablonla üretmek ileriye bırakıldı.
- **Çıkış URL'si:** `id_token_hint` (kullanıcı adıyla birlikte) tarayıcı geçmişine düşer; demo için kabul edildi (ADR-0015).
- **401 döngüsü koruması** DOM'a bağlı olduğu için node testinde değil ve kalıcı 401 canlı olarak üretilmedi; yalnız kod incelemesiyle doğrulandı.
- **Plate app varsayımları** kullanıcı onayı bekliyor.

## 5. Mutasyonlar

`ONLY="M203 … M211" bash scripts/mutation-check.sh`: baseline yeşil (356 Java testi, 9 node suite). Sonuç: **9/9 yakalandı**. M203–M206 ilk sürümün korumaları; M207–M211 review düzeltmelerinin (outcome sınıflandırması, Trusted Types, paylaşılan refresh, state kontrolü, dosya adı temizliği). Çıktı: [`faz-10-mutasyon-ciktisi.txt`](faz-10-mutasyon-ciktisi.txt).

## 6. CI

CI_RESULT
