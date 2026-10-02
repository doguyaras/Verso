---
name: verso-security-review
description: Use this skill when reviewing authentication, authorization, service JWT, input validation, sensitive logging, uploads, rate limiting, DB queries, WebSocket, privacy, secrets or supply-chain aspects of a change.
---

Değişikliği `docs/ai/security-rules.md` (tek kaynak) ve OWASP ASVS/API Security Top 10 ile karşılaştır. Her bulgu için **exploit senaryosu** yaz; senaryo yazamıyorsan bulgu değil, öneridir. Kanıt yoksa `needs verification`. Sorun yoksa: **"Bu kapsamda güvenlik bulgusu yok."** (1., 4. ve 5. bölüm yine yazılır: risk `OK`; 2–3 atlanır).

Referans: mimari referans Bölüm 6.5 (kimlik), 9.2.1 (delegasyon). Girdi: diff = `git diff <base>...HEAD` + `git diff --stat` (base = hedef branch; PR diff'i verilmişse o); izlenmeyen (untracked) dosyalar da kapsamdadır. Severity: `CRITICAL` (= `BLOCKER`: exploit doğrudan mümkün / `BLOCK` tetikleyicisi) → `BLOCK`; `HIGH` → `REQUEST CHANGES`; `security-rules.md` ihlali en az `REQUEST CHANGES`; `MEDIUM`/`LOW` engellemez. Güvenlikle doğrudan ilgisi olmayan bulguları (migration immutability, katman ihlali, hata kodu çakışması, test kalitesi) ayrıntılandırma; `Kapsam dışı — ilgili skill: …` başlığı altında tek satırla listele. Anotasyon/aspect/filtre için "bulgu yok" yazmadan önce onu çalıştıran bileşeni dosya:satır ile göster.

Çalıştır (proje kökünden): `mvn -B -ntp -pl <svc>-core -am test` (`ArchitectureRulesTest`, `ConfigDriftTest`, `ErrorCodeUniquenessTest`); migration değiştiyse `node scripts/flyway-immutability.js check --base <base>` (çıktıdaki `OK`/`IHLAL`/`DOGRULANAMADI` satırını aynen yaz); değişen yml'leri parse et. Not: `-am` ile upstream modülde `failIfNoTests=true` sabitse filtreli koşu düşer; `-fn` ekle veya filtresiz `test` koş.

Kontrol et:

## Trust boundary ve kimlik
- Üç JWT yüzeyi karışmıyor: `typ`, anahtar ve `aud` ayrı. Service JWT asimetrik; `iss` = imzalayan; `kid`/JWKS ile doğrulama; simetrik paylaşılan secret **yok** (varsa `BLOCK`).
- Filtre sırası: path decode+normalize → imza/iss/aud/exp → (opsiyonel jti) → allowlist first-match → default-deny. Allowlist ham URI ile eşleşmiyor.
- `internal-access` kuralı gerçek kullanım kadar dar; dar kural catch-all'dan önce; local yml ve deploy config **birlikte** güncellenmiş.
- Kullanıcı adına internal uç `sub` == path hesabı kontrolü yapıyor; hesabı body'den alan internal uç hiçbir aktöre açık değil.
- **Delegasyon** (`repo-context.md` Bölüm 3.1): yeni/değişen internal uç matriste satır aldı mı; hedef **üçünü birlikte** kontrol ediyor mu (çağıran allowlist'te + bu işlem için + `sub` bu kaynakta yetkili); arka plan token'ıyla (sub yok) kullanıcı-yetkisi gerektiren işlem reddediliyor mu; "her kullanıcı adına her şey" satırı var mı (varsa `BLOCK`); zincirde `act` korunuyor mu. Testler: izinli/izinsiz aktör + yanlış `sub` + arka plan token'ıyla kullanıcı işlemi.
- Giden internal çağrılar: `/internal/**`'e giden her client (RestClient/WebClient/Feign) service JWT interceptor'ı ile kuruluyor; interceptor yoksa bulgu `HIGH` (platform modülü iskelette yoksa da `HIGH` + `needs verification`, `MEDIUM`'a düşürme).
- Gateway: `/internal` engeli `StripPrefix` sonrası da; iç header temizliği; CORS `*` yok; token query'de yok; `gateway` actuator ucu kapalı; trusted-proxy ayarı; rate limit ve timeout bütçesi.

## Ownership / IDOR
- Hesap kimliği yalnız `@CurrentAccount`; path/query/body'den değil. Path'teki id hedef kaynak; ownership serviste doğrulanıyor.
- `@CurrentAccount` (veya herhangi bir özel parametre anotasyonu) tek başına kimlik kanıtı değildir: modülde onu çözen bir `HandlerMethodArgumentResolver` (`WebMvcConfigurer.addArgumentResolvers`) ya da classpath'te platform auto-config'i var mı? Yoksa Spring parametreyi query/path'ten bağlar → IDOR veya 500. Resolver'ı dosya:satır ile kanıtla; kanıtlamadan "bulgu yok" yazma. Resolver yok = `BLOCKER` (bu skill'de `CRITICAL`, karar `BLOCK`); parametre adı açık değilse `-parameters` derleme bayrağı da kontrol edilir.
- Liste/sayfalama uçlarında sıralama alanı allowlist; boyut sınırı.
- Read-model'den yetki kararı veriliyorsa "satır yok / eski" davranışı fail-closed; eskilik toleransı (T) bu karar için ayrıca yazılı (engel kararı ≤ 30 sn gibi) ve eski bir güvenlik kararı yeniden denemede yenisini ezemiyor.

## OTP, token, abuse
- OTP: `SecureRandom`, tuzlu hash, sabit zamanlı karşılaştırma, TTL + deneme sınırı, enumeration koruması; outbox payload'ında düz metin OTP kalmıyor.
- SMS pumping savunması `otp/start` için: geo-permissions, numara doğrulama, numara/IP/cihaz tavanı, velocity, harcama kesicisi.
- Refresh token: opak + hash, aile rotasyonu, reuse → aile iptali, mutlak süre.
- `sv` artışı: şifre sıfırlama, logout-all, **ban**, rol değişimi.
- Admin 2FA passkey/TOTP; SMS yalnız kurtarma. Admin OTP'si kullanıcı OTP'sinden zayıf değil.
- Rate limit: yeni saldırıya açık uç scope aldı; sayaç Redis'te; fail politikası scope tablosuyla uyumlu; `Retry-After`.
- Mock entegrasyon (`MockStoreValidation`, `MockSms`…) `@Profile("local|test")` ve prod açılış guard'ı var (yoksa `BLOCK`).
- Cihaz attestation sinyali gereken uçlarda (kayıt, OTP-gönder, satın alma) risk skoruna bağlanmış.

## Input ve sorgu güvenliği
- `@Valid`, boyut sınırları, enum parse (`from`), path/header/param adları açık.
- Native SQL'de parametre bağlama; dinamik sıralama/filtre allowlist; JPQL string birleştirme yok.
- Dosya adı/path kullanıcıdan gelmiyor; `..` normalize.
- JSON: bilinmeyen alan politikası bilinçli; polimorfik deserialization kapalı.

## WebSocket / realtime
- CONNECT'te native `Authorization` zorunlu; süresi dolmuş token ile komut reddi; SUBSCRIBE deny-all allowlist; `sv` süpürücüsü; Redis zarfı HMAC + tazelik + `relayId`; local'de bile imzasız zarf kabul edilmiyor.

## Object storage ve içerik
- Yükleme private quarantine → yeniden kodlama (EXIF/GPS) → moderasyon (CSAM hash + NSFW) → private delivery + signed GET. Public bucket'a doğrudan yükleme `BLOCK`.
- Presigned URL kısa TTL; key sunucuda üretilmiş; HEAD/ETag doğrulaması.
- Uçtan uca şifreleme varsa: iddia ile şema uyumlu (FS/MITM sınırları yazılı), anahtar pinleme, message franking; sohbet anahtarı panele verilmiyor.

## SSRF / dışa giden istekler
- Kullanıcı kaynaklı URL'yi sunucu çağırıyor mu (webhook, avatar, önizleme, import)? Ayrı egress client; `https` + host allowlist; DNS sonrası IP kontrolü (`InetAddressFilter` bean'i) ve redirect'te tekrar; timeout + boyut sınırı; iç ağ/metadata/localhost/actuator hedefi testle reddediliyor. Eksikse `HIGH`.

## Log, hata, secret
- `security-rules.md` Bölüm 4 yasak listesi: hiçbir ham PII/secret/body/exception mesajı log'da yok. Log'a özel neden `safeLogReason`'da; `details`'te yalnız istemciye gösterilebilir bilgi.
- Yeni secret: değeri hiçbir yerde yok; `/run/secrets` ile geliyor; `${ENV:literal}` fallback yok; SOPS dosyasına eklenmiş; `.dockerignore` kapsıyor.
- Değişen her `application-*.yml` / `config/*.yml` parse ediliyor (ör. `python3 -c 'import yaml,sys; yaml.safe_load(open(sys.argv[1]))'`); parse hatası güvenlik config'ini (allowlist, secret, rate limit) sessizce devre dışı bırakır → `HIGH`.
- Hata yanıtları tek format (`ErrorResponse`); filtre redleri de aynı; altyapı hatası 503 (500 değil).
- Audit: `details` allowlist; anahtar/şifreli içerik/serbest metin/ham IP yok; tablo değiştirilemez.

## Privacy
- Yeni kişisel veri alanı: envanter + aydınlatma + retention + silme saga'sı kapsamı güncellendi.
- Düşük entropili kimlik için HMAC+pepper; varlık oracle'ı yok; konum deterministik grid; kişisel veri dönen uç `no-store`.
- Arama index'i/cache/yedek aynı kurallara uyuyor.
- Olay/komut payload'ları (outbox, `domain.events`) ham telefon/e-posta/serbest metin taşımıyor; tüketici kişisel veriyi kendi kaynağından çözüyor.

## Tedarik zinciri
- Yeni bağımlılık: lisans (GPL/AGPL/BSL) ve bilinen CVE kontrolü; sürüm OSS destekli.
- Dockerfile non-root, `.dockerignore`, image CI'da build.
- Workflow'larda action'lar commit SHA'ya pinli (tag pin'i `REQUEST CHANGES`); `permissions` en dar.

Çıktı:
1. **Risk seviyesi:** `CRITICAL` / `HIGH` / `MEDIUM` / `LOW` / `OK` — yanına nihai kararı da yaz (çıktı kısalırsa karar kaybolmasın).
2. **Bulgular** (her biri `B1…` id'li; çok satırlı olabilir): `severity · dosya:satır` + alt etiketler `Kanıt / Exploit (kim, nasıl, sonuç) / Düzeltme / Test` + kural referansı (`security-rules.md` Bölüm N). Kanıtsız bulguda severity yanına `(needs verification)`. LOW bulgu tek satır.
3. **Kural referansı:** bulgu satırına gömülür (2. madde); ayrı liste gerekmez.
4. **Net kanıt bulunamayan alanlar** (`N/A` = yüzey yok; `kanıt yok` = yüzey var ama doğrulanamadı — ayrı işaretle).
5. **Nihai karar:** `APPROVE` / `APPROVE WITH NON-BLOCKING COMMENTS` / `REQUEST CHANGES` / `BLOCK`. `CRITICAL` → `BLOCK`; `CRITICAL` veya `HIGH` bulgu varsa karar `APPROVE` olamaz. Kararı başta (1. madde) da yaz.
