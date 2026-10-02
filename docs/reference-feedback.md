# Referans dokümana geri bildirim

> Bu dosya `docs/architecture-reference.md`'nin **kaynağına** (blueprint ve iskelet örneği) uygulanabilecek düzeltmeleri toplar. Verso'da bulunan her kusur burada kanıtıyla birlikte yazılır. Referans dokümanın kendisi bu repoda değiştirilmez (ADR-0007 #13). Bu yüzden her madde, referansın bir sonraki sürümü için öneridir.
>
> Biçim: **Ne · Nerede (referans) · Kanıt · Önerilen düzeltme · Verso'daki karşılığı.** Kanıtı olmayan madde yazılmaz.

## Faz 1 (2026-10-02)

Kaynak: beş bağımsız review (spring-code, security, test-writer, architecture-boundary, environment-impact) ve `scripts/mutation-check.sh`.

### R1 · Global handler binding hatasında reddedilen değeri istemciye döndürüyor (HIGH)
- **Nerede:** Ek B `order-core/.../web/GlobalServiceExceptionHandler.java`, `fieldDetails`. Metot `FieldError.getDefaultMessage()` kullanıyor; Javadoc'unda "The rejected value is deliberately not read" yazıyor.
- **Kanıt:** `@ModelAttribute` alanına `?id=Ahmet_maas_bordrosu.pdf` gönderildi. Yanıt `details:["id=Failed to convert … Invalid UUID string: Ahmet_maas_bordrosu.pdf"]` oldu. İskeletteki test yalnız `@RequestBody` yolunu denediği için görünmüyordu; o yolda tip hatası `HttpMessageNotReadableException`'a düşüyor. Üç reviewer bağımsız olarak buldu.
- **Düzeltme:**
  - `f.isBindingFailure()` ise sabit `alan=invalid` yaz.
  - Kural mesajı reddedilen değeri içeriyorsa (`${validatedValue}` interpolasyonu) yine `invalid` yaz.
  - Aynısını `ConstraintViolationException` ve `HandlerMethodValidationException` için uygula.
  - Regresyon testi: `@ModelAttribute` ve query binding.
- **Verso:** `GlobalServiceExceptionHandler.safeMessage`, testler `modelAttribute_whenTypeMismatch_*`, `requestBody_whenMessageInterpolatesValue_*`, mutasyonlar M10 ve M11.

### R2 · `SensitiveLogSanitizer` JSON alanlarını, kısa Bearer token'ları, IP'leri ve URL kimlik bilgilerini süzmüyor (HIGH)
- **Nerede:** Ek B `order-core/.../logging/SensitiveLogSanitizer.java`; referans 8.4 tablosu sanitizer'ın "JSON/`k=v` içindeki" gizli alanları redakte ettiğini söylüyor.
- **Kanıt:**
  - `{"password":"hunter2"}` girdisi değişmeden kalıyor. Anahtar tırnaklı olduğu için `\b(password)\b(\s*[=:]` deseni eşleşmiyor.
  - `Authorization: Bearer shortTok-123` → `Authorization: [REDACTED] shortTok-123`.
  - `10.0.0.5` olduğu gibi kalıyor; oysa referans 8.4 IP loglanmaz der.
- **Düzeltme:**
  - Desen `("?\s*[=:]\s*"?)`.
  - Ayrı bir `(bearer|basic)\s+<kimlik>` deseni.
  - IPv4/IPv6 desenleri.
  - `scheme://user:pass@` ve URL query redaksiyonu.
- **Verso:** sertleştirilmiş sanitizer, `SensitiveLogSanitizerTest` (16 test), mutasyonlar M20 ve M21.

### R3 · Sanitizer'da ReDoS ve döngüsel cause zinciri (MEDIUM)
- **Nerede:** Aynı sınıf. 240 karakterlik kesme desenlerden **sonra** yapılıyor; e-posta deseninin yerel kısmı sınırsız. `safeExceptionSummary` cause zincirini döngü kontrolü yapmadan yürüyor.
- **Kanıt:**
  - Kesme sonrası çalışan desenler için ölçüm: 8k karakter 145 ms, 64k karakter yaklaşık 10 sn (yaklaşık karesel).
  - `Throwable.initCause` ile kurulan A → B → A zinciri sonsuz döngüye giriyor.
- **Düzeltme:**
  - Desenlerden önce girdiyi kes (ör. 4 × 240).
  - E-posta yerel kısmını `{1,64}` ile sınırla.
  - Cause zincirinde kimlik kümesi ve derinlik sınırı kullan.
  - Zaman sınırlı testler ekle.
- **Verso:** `MAX_INPUT`, `rootCause(...)`; testler `sanitize_whenHugeCraftedInput_*`, `rootCauseType_whenCauseChainIsCyclic_*`; mutasyonlar M22 ve M23.

### R4 · 500 log'undaki "sanitize edilmiş özet" yeterli değil (MEDIUM)
- **Nerede:** Referans 7.3 son satır ("`exceptionType=` + sanitize edilmiş root-cause özeti").
- **Kanıt:** PostgreSQL `Detail: Key (owner_id, title)=(42, Ahmet_maas_bordrosu.pdf)` mesajındaki dosya adını hiçbir desen tanımıyor. Belge metni ve soru da tanınmaz.
- **Düzeltme:**
  - 500 log'una yalnız `exceptionType` ve `rootCauseType` yazılsın; mesaj gerekiyorsa trace ile bulunsun.
  - Sanitizer yalnız, içeriğinin yapısı bilinen metinler için kullanılsın.
- **Verso:** ADR-0007 #16; test `unexpectedException_whenThrown_returns500AndLogsTypesOnly`; mutasyon M12.

### R5 · Global handler taban sınıfın hazırladığı header'ları atıyor; eşlenmemiş 4xx "validation" diyor (LOW–MEDIUM)
- **Nerede:** Ek B handler, `handleExceptionInternal` ve `default -> VALIDATION`.
- **Kanıt:**
  - 405 yanıtında `Allow` yok; RFC 9110 15.5.6 bunu zorunlu tutar.
  - 415 yanıtında `Accept` yok.
  - `ResponseStatusException(401)` gövdede `code:90000, service:"validation"` olarak dönüyor.
- **Düzeltme:**
  - `headers` parametresini zarfa taşı.
  - Status'a göre kod eşle (`CommonErrorCode.forStatus`); security bloğunda `UNAUTHENTICATED`, `ACCESS_DENIED`, `TOO_MANY_REQUESTS` tanımla.
- **Verso:** mutasyonlar M14 ve M15.

### R6 · Ham request path üç ayrı yoldan log'a düşüyor (MEDIUM)
- **Nerede:** Referansta karşılığı yok. Boot ve Spring varsayılan davranışıyla ortaya çıkıyor.
- **Kanıt:**
  - `ResourceHandlerUtils` `../` içeren path'te WARN basıyor.
  - Tomcat `Http11Processor` geçersiz karakterli istek satırını INFO ile, stack trace dahil yazıyor.
  - `DispatcherServlet` `PageNotFound` logger'ı eşleşmeyen her istekte "No mapping for GET <ham path>" WARN basıyor. Bu son kanal reviewer'ların bulamadığı, Verso'nun smoke testinin yakaladığı bir sızıntı.
- **Düzeltme:**
  - API servislerinde `spring.web.resources.add-mappings=false` kullan.
  - `org.springframework.web.servlet.PageNotFound: ERROR` ve `org.apache.coyote.http11.Http11Processor: WARN` seviyelerini ayarla.
  - Gerçek port üzerinde path işaretli log testi yaz.
  - Referans 8.4 "Hiçbir seviyede loglanmaz" listesine framework logger'ları için bir not eklenebilir.
- **Verso:** `VersoAppSmokeTest.dotSegmentPath_*`, `invalidRequestTarget_*`; mutasyon M29.

### R7 · `/error` zarfsız dönüyor (LOW)
- **Nerede:** Referans 6.2 ve 7.6 "filtre redleri dahil tek biçim" ister; iskelette bir `ErrorController` yok.
- **Kanıt:** `GET /error` çağrısı 500 ve `{"status":999,"error":"None"}` döndürüyor.
- **Düzeltme:** Platform starter'ında, `ErrorMvcAutoConfiguration`'dan önce yüklenen ve zarf yazan bir `ErrorController`. Doğrudan çağrı 404 olarak dönmeli.
- **Verso:** `EnvelopeErrorController`; mutasyon M28.

### R8 · `failIfNoTests` plugin config'i filtreli koşuyu kırıyor (LOW)
- **Nerede:** Ek B modül pom'ları (`<failIfNoTests>true</failIfNoTests>` plugin konfigürasyonunda). Ek A skill'leri bu sorunu not olarak anlatıyor ama çözümü pom'a işlenmemiş.
- **Kanıt:** `-pl X -am -Dtest=Y` koşusunda upstream modüller "No tests were executed!" ile düşüyor. Bu düşüş iki kez yanlışlıkla "mutasyon yakalandı" sanıldı.
- **Düzeltme:** Ayar modül **property**'si olsun (`<properties><failIfNoTests>true</failIfNoTests>`). Filtreli koşu `-DfailIfNoTests=false` ile çalışır; tam koşuda kural geçerli kalır.
- **Verso:** ADR-0007 #6; mutasyon M30.

### R9 · Şekil A'da enforcer `bannedDependencies` toplayıcı modülü de yasaklıyor (MEDIUM)
- **Nerede:** Ek B kök pom. İskelette her `*-core` kendi Boot uygulaması; şekil A'nın toplayıcı modülü hiç düşünülmemiş.
- **Kanıt:** `verso-app` → `document-core` bağımlılığı "banned via the exclude/include list" ile build'i kırıyor (architecture review, kopyada).
- **Düzeltme:**
  - Yasağı ayrı bir execution'a al (`ban-core-to-core`) ve yalnız toplayıcı modülde `phase=none` yap.
  - Her `*-core` kuralı kendisi koşturmaya devam eder.
  - Referans 3.2'ye "şekil A notu" eklenebilir.
- **Verso:** ADR-0007 #20; mutasyon M09.

### R10 · Spring Modulith, kök paketin altındaki platform starter'larını uygulama modülü sayıyor (MEDIUM)
- **Nerede:** Referans 3.5 paket kökleri `com.<org>.platform.*` ve `com.<org>.<domain>` olarak tanımlı; şekil A'da main sınıf `com.<org>` köküne konursa sorun çıkıyor.
- **Kanıt:**
  - `ApplicationModules.of(App.class)` `MODULE platform` raporluyor. Domain modüllerinin `ServiceException` kullanımı "non-exposed type" ihlali olur.
  - Ayrıca `@SpringBootApplication` açık anotasyonlara ayrılırsa (scan dışlaması için) Modulith kök tipi bulamıyor: "not annotated with @Modulith, @Modulithic or @SpringBootApplication".
- **Düzeltme:**
  - `ApplicationModules.of(App.class, resideInAPackage("com.<org>.platform.."))` kullan.
  - Main sınıfa `@Modulithic` ekle.
  - Referans 1.1 ve 16'ya not düş.
- **Verso:** ADR-0007 #23; `ModuleStructureTest`; mutasyon M08.

### R11 · Platform starter'ları kök paket altındaysa component scan onları da tarıyor (LOW)
- **Nerede:** Referans 4.5 "ortak paket taranmaz" diyor, ama şekil A'da main sınıf `com.<org>` köküne konursa `com.<org>.platform..` taranır.
- **Kanıt:** Scan açıkken `@RestControllerAdvice` bean'i scan'den geliyor; `@ConditionalOnMissingBean` yüzünden bean sayısı yine 1 kalıyor. Bu yüzden sayı testi sorunu göremiyor; testin bean'in fabrikasının auto-configuration olduğunu doğrulaması gerekiyor.
- **Düzeltme:**
  - Main sınıfta `com.<org>.platform..` için açık bir `excludeFilters` kullan.
  - Testte bean tanımının fabrikasını doğrula.
- **Verso:** ADR-0007 #5; mutasyon M07.

### R12 · `${revision}` ile `install` çözülmemiş POM yazıyor (MEDIUM)
- **Nerede:** Referans 3.2 (`${revision}`); iskelet `install` kullanmadığı için sorun görünmüyor.
- **Kanıt:** "Could not find artifact com.verso:verso:pom:${revision}". `-pl` ile tek modül koşusu ve referans 3.2'nin öngördüğü `*-api` yayını kırılıyor.
- **Düzeltme:** `flatten-maven-plugin` (`resolveCiFriendliesOnly`) kök pom'a eklensin.
- **Verso:** ADR-0007 #22.

### R13 · Blueprint CI: token kalıcılığı ve ifade enjeksiyonu (LOW)
- **Nerede:** Ek A `ci.yml`.
- **Kanıt:**
  - `actions/checkout` varsayılan `persist-credentials: true` ile job token'ını `.git/config`'e yazıyor; sonraki adımlarda koşan PR kodu bunu okuyabilir.
  - `${{ github.base_ref }}` doğrudan `run:` metnine gömülüyor.
  - `pull_request` tetikleyicisinde `edited` tipi yok; referans 18.3 ister.
  - gitleaks indirmesinde checksum doğrulaması yok.
- **Düzeltme:**
  - `persist-credentials: false` kullan.
  - Değeri `env: { BASE_REF: … }` ile geçir.
  - Tetikleyiciye `types: [opened, synchronize, reopened, edited]` ekle.
  - gitleaks tarball'ı için `sha256sum -c` çalıştır.
- **Verso:** ADR-0007 #10.

### R14 · Review gate PowerShell aracıyla ve seçenekli `git push` ile atlanıyor (LOW)
- **Nerede:** Ek A `.claude/settings.json` (`matcher: Bash`) ve `review-gate.sh` (`*"git push"*`).
- **Kanıt:** Windows'ta PowerShell aracı ya da `git -C . push` hook'u tetiklemiyor.
- **Düzeltme:** Matcher `Bash|PowerShell` olsun; `git` ile `push` arasındaki seçenekleri (`-C`, `-c`, `--x`) kabul eden bir regex kullanılsın.
- **Verso:** ADR-0007 #25.

### R15 · Mutasyon kanıtının kendi tuzakları (süreç)
- **Nerede:** Referans 16 ve 19.6, iskelet README'si ("elle mutasyonlar").
- **Kanıt (Verso'da iki kez yaşandı):**
  - Yedekten `mv` ile geri yüklenen dosya eski mtime'ı taşıdığı için Maven mutasyonlu `.class`'ı kullanmaya devam etti.
  - Çözümleme ya da "No tests were executed" hatası, test hatası sanılıp "yakalandı" diye sayıldı.
- **Düzeltme:**
  - Mutasyonlar repoda bir script olarak dursun.
  - Script bir kırmızıyı ancak logda assertion hatası görünüyorsa saysın.
  - Geri yüklenen dosyaya `touch` yapılsın.
  - Sonuç `docs/evidence/` altına yazılsın.
- **Verso:** `scripts/mutation-check.sh`, ADR-0007 #29.

## Faz 1, ikinci tur review (2026-10-02)

### R16 · Filtrede fırlayan istisnayı Tomcat mesajı ve stack trace ile logluyor; istemci hataları 500 oluyor (HIGH)
- **Nerede:** Referansta karşılığı yok. Boot/Tomcat varsayılanıyla ortaya çıkıyor, faz 3'teki security filtreleri de bu yolu kullanıyor.
- **Kanıt:** Üç review'da bağımsız olarak bulundu, `ContainerErrorPathTest` ile tekrarlandı.
  - `org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/].[dispatcherServlet]` logger'ı filtre istisnasını throwable'ıyla birlikte ERROR olarak yazıyor.
  - Bozuk form gövdesi (`FormContentFilter`), bozuk multipart ve Tomcat 11'in `InvalidParameterException`'ı 500 dönüyor.
  - Filtreden gelen `ServiceException` hata sayfasında kodunu kaybediyor.
- **Düzeltme:**
  - Bu logger'ı `OFF` yap; hata sayfası yalnız tip loglasın.
  - JSON API'de `spring.mvc.formcontent.filter.enabled=false`.
  - Hata sayfası, konteynerin ilettiği istisnayı global handler'la aynı sınıflandırıcıdan geçirsin: `ServiceException` kendi kodunu korusun, ayrıştırılamayan istek 400 olsun.
- **Verso:** `ErrorClassifier`, `EnvelopeErrorController`, `ContainerErrorPathTest` (6 test).

### R17 · Tomcat cookie parser ham header'ı INFO seviyesinde logluyor (MEDIUM)
- **Kanıt:** Tırnak içeren bir `Cookie` değeri `org.apache.tomcat.util.http.parser.Cookie` INFO satırına aynen düşüyor.
- **Düzeltme:** `org.apache.tomcat.util.http: WARN` ve işaretli cookie testi. Referans 8.4'teki framework logger notuna (R6) eklenebilir.

### R18 · "Mesaj reddedilen değeri içeriyor mu" kontrolü yetersiz; şablon kontrol edilmeli (MEDIUM)
- **Nerede:** R1'in ilk düzeltmesi.
- **Kanıt:**
  - Sınıf düzeyi constraint `${validatedValue.phone}` ile nesnenin başka bir alanını gösterebiliyor.
  - `${formatter.format('%.6s', validatedValue)}` değerin bir önekini gösterebiliyor.
  - Map anahtarı alan yoluna düşüyor (`attrs[jane@…]`).
  - Kısa değerler kural mesajını rastgele gizliyor (yanlış pozitif).
- **Düzeltme:**
  - `ConstraintViolation.getMessageTemplate()` içinde `${` (Expression Language) varsa mesajı gösterme; düz `{min}` parametreleri güvenlidir.
  - Alan yolundan container anahtarlarını at.
  - `ConstraintViolationException`'da yalnız yaprak adı göster; tam yol metot adını açığa çıkarır.
- **Verso:** `GlobalServiceExceptionHandler.ruleMessage`, `requestBody_whenMessagesUseExpressionsOrMapKeys_*`.

### R19 · Pre-commit secret taraması çalışma ağacını tarıyor (MEDIUM)
- **Nerede:** Referans 15.3 ("gitleaks pre-commit"). Ek A sarmalayıcısında staged modu yok.
- **Kanıt:**
  - Stage'lenip çalışma kopyasında temizlenen secret commit'e giriyor.
  - Gitignore'lu `.env` her commit'i engelliyor; bu, ekibi `--no-verify`'a alıştırır.
- **Düzeltme:**
  - Sarmalayıcıya `staged` modu ekle (`gitleaks git --staged`); pre-commit bu modu kullansın.
  - config-lint staged içerikten (`git show :yol`) çalışsın.
- **Verso:** `gitleaks-check.sh staged`, iki yeni test.

### R20 · Bean Validation sağlayıcısı olmadan `@Valid` üretimde sessizce no-op (MEDIUM)
- **Nerede:** Ek B iskelet POM'ları. Validation yalnız test scope'unda geliyor.
- **Kanıt:** Uygulama açılışında "no Jakarta Validation provider" uyarısı çıkıyor; MockMvc testleri (test scope'ta Hibernate Validator ile) yeşil kalıyor.
- **Düzeltme:** Uygulama modülüne `spring-boot-starter-validation` ekle ve smoke testte gerçek bir `Validator`'ın kuralı uyguladığını doğrula.

### R21 · Review gate: SIGPIPE ile sessiz geçiş ve seçenek bypass'ları (LOW)
- **Kanıt:**
  - `set -o pipefail` altında `printf | grep -q`, uzun komutta 141 dönüyor ve gate "eşleşme yok" sayıp geçiyor.
  - `git -P push`, `git.exe push`, `"git" push` ve `--work-tree . push` yakalanmıyor.
- **Düzeltme:** Pipe'sız `[[ =~ ]]` kullan; tırnakları at; kısa bayrakları ve değerli uzun bayrakları kabul et; CI'da varyant ve uzun komut assert'leri ekle.

### R22 · Mutasyon script'inin kendi doğruluğu (süreç)
- **Kanıt (ikinci tur test review):**
  - Yeşil baseline koşusu yoktu; önceden kırmızı bir test, etkisiz bir mutasyonu "yakalandı" gösterdi.
  - Kriter sınıf düzeyindeydi; yanlış metot kırılınca da "yakalandı" sayılıyordu.
  - config-lint mutasyonu herhangi bir sıfır dışı exit kodunu "yakalandı" sayıyordu.
  - `trap` yoktu; kesinti mutasyonlu kaynak bırakırdı.
- **Düzeltme:**
  - Önce yeşil baseline koşusu.
  - Her mutasyon kırılması beklenen **test metodunu** adıyla belirtsin.
  - Exit kodları birebir kontrol edilsin.
  - `trap` ile geri yükleme.
- **Verso:** `scripts/mutation-check.sh` (ikinci sürüm).

## Faz 1, üçüncü tur (2026-10-02)

Kaynak: `verso-security-review` (APPROVE WITH NON-BLOCKING COMMENTS) ve `verso-test-writer` (PASS WITH COMMENTS) üçüncü turu. Her madde önce kırmızı bir testle tekrarlandı.

### R23 · Tomcat host logger'ı ve motor adı (MEDIUM)
- **Nerede:** Referans 8.4 (içerik loglanmaz) ve Ek B iskeletinin log ayarları. Referans, konteynerin kendi log kanallarından söz etmiyor.
- **Kanıt:**
  - Hem REQUEST hem ERROR dispatch'inde çalışan bir filtre (Spring Security'nin kaydı böyledir) hata dispatch'inde de patlarsa `ContainerBase.[Tomcat].[localhost]` mesajı ve stack trace'i ERROR olarak yazıyor.
  - Logger adı motor adını taşıyor: yalnız JVM'deki ilk Tomcat `[Tomcat]`; management portu ve sonraki test context'leri `[Tomcat-1]`, `[Tomcat-2]`. Sabit adla susturma, test sırası değişince kırmızıya dönüyordu.
- **Düzeltme:** Susturmayı sabit adla değil, sunucu başladığında motorun gerçek adıyla yap. Bunu, sunucuları adlarından bağımsız sayan bir testle doğrula.
- **Verso:** `ContainerErrorLogSilencer`, `ContainerLogSilencingTest`, `ContainerErrorPathTest.filterException_whenErrorDispatchFailsToo_*`; ADR-0007 #34.

### R24 · Hata controller'ı commit edilmiş yanıtta 404 ve fazladan gövde (LOW)
- **Kanıt:** Filtre yazıp flush ettikten sonra patlayınca konteynerin hata durumu 200 kalıyor. Controller bunu "hata durumu yok" sayıp WARN 404 logluyor ve gönderilmiş gövdeye zarf ekliyordu.
- **Düzeltme:** İstisna varsa durum en az 500 olsun; `response.isCommitted()` ise gövde yazılmasın.
- **Verso:** `EnvelopeErrorController`; ADR-0007 #35.

### R25 · Config lint ve pre-commit kapsamı (LOW–MEDIUM)
- **Kanıt:**
  - Lint glob'ları yalnız `.yml`'yi kapsıyordu; `.yaml` ve `.properties` içindeki fallback secret geçiyordu.
  - pre-commit'in davranış testi yoktu; CI'daki kuru çalıştırma boş index ile yeşildi. `staged` yerine `tree` taraması ya da config-lint adımının silinmesi fark edilmiyordu.
  - Migration kontrolü çalışma ağacına bakıyordu.
- **Düzeltme:**
  - Dosya listesi tek bir pathspec dosyasında olsun.
  - Hook, geçici bir git deposunda gerçek commit'lerle test edilsin.
  - Migration kontrolü de index'e baksın.
- **Verso:** `scripts/config-lint.pathspec`, `scripts/pre-commit.test.js`, `flyway-immutability.js check --staged`; ADR-0007 #36, #38.

### R26 · Sanitizer'da kalan boşluklar (LOW)
- **Kanıt:**
  - `Authorization: DPoP tok` kimliği bırakıyordu.
  - `salt=` / `privateKey=` / `pw=` geçiyordu.
  - `redis://:parola@host` maskelenmiyordu.
  - Kaçışlı tırnak değerin kuyruğunu açık bırakıyordu.
  - U+2028, NEL ve ANSI kaçışları log satırı sahteciliğine izin veriyordu.
- **Verso:** ADR-0007 #39; testler `sanitize_whenAuthorizationUsesAnyScheme_*` ve diğer dört test.

### R27 · Mutasyon script'inin doğruluğu, üçüncü kısım (süreç)
- **Kanıt (test-writer N4 ve bu oturumda gözlem):**
  - **Sabit log yolu.** İki eşzamanlı koşu (iki klon ya da reviewer kopyası) birbirinin logunu okudu. Biri 137 testlik sahte bir baseline raporladı.
  - **Node testlerinin baseline'ı yoktu.** gitleaks eksikken her node testi kırmızıydı ve mutasyon "yakalandı" görünüyordu.
  - **Kesinti başarı sayılıyordu.** INT/TERM sonrası script devam edip 0 dönüyordu.
  - **`ONLY` yazım hatası.** Hiçbir şey koşmuyor, 0 dönüyordu.
- **Düzeltme:**
  - Koşu başına `mktemp` logu.
  - Node süitleri için de yeşil baseline.
  - INT/TERM'de 130/143 ile çık.
  - Sonucu olmayan her `ONLY` kimliği hata sayılsın.
- **Verso:** `scripts/mutation-check.sh` (üçüncü sürüm).

### R28 · Test işaretçisi sanitizer kuralına takılıp sızıntıyı gizliyordu (test tasarımı)
- **Kanıt:** 36 karakterlik `container-marker-Ahmet_maas_bordrosu` "opak blok" kuralıyla `[REDACTED]` oluyordu. Sanitize edilmiş istisna mesajının ERROR loguna girmesi (yalnız tip yazılmalı) bu yüzden testte görünmüyordu.
- **Düzeltme:** Log sızıntı testlerinde işaretçi, hiçbir redaksiyon kuralına takılmayan düz bir metin olsun (boşluklu, kısa kelimeler).
- **Verso:** `ContainerErrorPathTest.PLAIN_MARKER`.

## Faz 2 (2026-10-02)

Kaynak: faz 2 uygulaması, gerçek Postgres'e karşı testler (`DatabaseRolesTest`), compose ile uçtan uca kurulum ve restore provası öz-testi.

### R29 · Maven Wrapper, `unzip` yoksa dağıtımı sessizce değiştiriyor (MEDIUM, build)
- **Nerede:** Referans 18.1 Dockerfile'ı hazır jar kopyalar. Ama aynı repoda image içinde `./mvnw` ile build eden her kurulum, `distributionSha256Sum` pinini kullanır.
- **Kanıt:** `eclipse-temurin:25-jdk-noble` image'ında `unzip` yok. `mvnw` (3.3.4, only-script) bu durumda `distributionUrl`'i `.zip`'ten `.tar.gz`'ye çeviriyor; pinlenmiş SHA zip'e ait olduğu için "Maven distribution might be compromised" hatası veriyor. CI runner'larında `unzip` olduğu için görünmüyor.
- **Düzeltme:** Build aşamasına `unzip` kurulsun ya da wrapper properties'e iki SHA'yı ayırt eden bir not düşülsün. Image build'i CI'da en az bir kez koşsun.
- **Verso:** `Dockerfile` build aşaması; ADR-0009.

### R30 · Testcontainers bağlantısı rolleri ve auto-configuration sırasını bozuyor (MEDIUM, test)
- **Nerede:** Referans 16, "`@ServiceConnection` (`@DynamicPropertySource` yerine)".
- **Kanıt:**
  - `@ServiceConnection` uygulamayı container'ın superuser'ıyla bağlar. Rol ayrımının (10.1) test edildiği her durumda eksik GRANT'ı gizler.
  - `@ImportTestcontainers` ile gelen `@DynamicPropertySource` değerleri bean olarak geliyor. Flyway auto-configuration koşulu daha önce değerlendirildiği için `DB_HOST` çözülemedi ve context açılmadı.
- **Düzeltme:** Rol ayrımı olan projelerde, uygulamayı ve Flyway'i kendi rolleriyle bağlayan bir `ApplicationContextInitializer` kullanılsın; container compose ile aynı image'ı ve aynı init script'lerini kullansın.
- **Verso:** `VersoPostgres`, `@WithVersoPostgres`; ADR-0007 #43.

### R31 · `pg_dump` veritabanı düzeyindeki yetkileri taşımaz (MEDIUM, yedek)
- **Nerede:** Referans 10.1 altyapı SQL'i ve 10.5 restore provası.
- **Kanıt:** `pg_dump` (`--create` olmadan) `REVOKE ... ON DATABASE` / `GRANT CONNECT` satırlarını içermiyor. Bu yetkiler şema script'inde dururken restore hedefinde `PUBLIC` yeniden bağlanabiliyordu.
- **Düzeltme:** Veritabanı ACL'leri rol script'inde dursun; restore hedefinde yalnız rol script'i çalışsın, şemalar ve yetkiler dump'tan gelsin. Prova bunu doğrulasın.
- **Verso:** `10-roles.sh`, `DatabaseRolesTest.database_whenInitialized_isClosedToPublicAndOpenToTheNamedRoles`.

### R32 · Altyapı SQL'i parolayı loga yazabilir (MEDIUM, güvenlik)
- **Nerede:** Referans 10.1, `CREATE ROLE ... PASSWORD '<secret>'`.
- **Kanıt:**
  - `log_min_error_statement` varsayılanı `error`. Başarısız bir `CREATE ROLE`/`ALTER ROLE ... PASSWORD` ifadesi, parolayla birlikte sunucu loguna yazılır.
  - Parola argüman olarak (`psql -v pw=...`) verilirse process listesinde görünür.
- **Düzeltme:** Oturumda `SET log_min_error_statement = panic`; parola dosyadan psql `\set` ve backtick ile okunsun.
- **Verso:** `deploy/postgres/initdb/10-roles.sh`.

### R33 · Sunucu tarafı parametre logu içerik sızdırabilir (LOW, gizlilik)
- **Nerede:** Referans 8.4 uygulama loglarını kapsar; PostgreSQL'in kendi logundan söz etmez.
- **Kanıt:** `log_parameter_max_length` varsayılanı -1 (tam değer). `log_min_duration_statement` açılınca bind parametreleri (belge metni, soru) sunucu loguna düşer; hata anında da `log_parameter_max_length_on_error` devreye girer.
- **Düzeltme:** İki ayar da 0 olsun; `log_statement = none`. Test bunları okusun.
- **Verso:** `05-settings.sh`, `DatabaseRolesTest.server_whenStarted_hasStatisticsAndNoParameterLogging`.

### R34 · `public` kapatılınca extension'lar erişilemez olur (LOW, veri)
- **Nerede:** Referans 10.1, `REVOKE ALL ON SCHEMA public FROM PUBLIC`.
- **Kanıt:** `vector` tipi ve operatörleri `public`'te kurulursa modül rolleri onlara erişemez.
- **Düzeltme:** Extension'lar superuser'a ait ayrı bir `extensions` şemasında dursun; modül rollerine `USAGE` verilsin; `search_path` = `<şema>, extensions`.
- **Verso:** `20-database.sh`; ADR-0007 #45.

### R35 · Restore provasındaki "smoke test" tanımsız; tutarlı karşılaştırma gerekiyor (süreç)
- **Nerede:** Referans 10.5, "Flyway `validate` + smoke test".
- **Kanıt:** Yedekten sonra ayrı alınan satır sayıları, eşzamanlı yazmalar yüzünden geri yüklenen veriyle uyuşmayabilir (yanlış alarm). Hiç başarısız olmayan bir prova da bir şey kanıtlamaz.
- **Düzeltme:**
  - Satır sayısı manifest'i `pg_export_snapshot()` + `pg_dump --snapshot` ile aynı snapshot'ta alınsın; prova manifest'le birebir karşılaştırsın.
  - Bir öz-test, provanın değiştirilmiş manifest, bozulmuş dosya ve eksik checksum durumlarında kırmızı verdiğini göstersin.
- **Verso:** `deploy/backup/{backup,restore-check}.sh`, `scripts/restore-drill-selftest.sh`.

### R36 · `chmod 600` secret dosyası root olmayan container'larda okunamaz (HIGH, deploy)
- **Nerede:** Referans 15.3 seviye 1: "Secret dosyaları ... `chmod 600`"; 18.1 non-root image kuralı.
- **Kanıt:**
  - Compose (swarm dışı) dosya secret'larını, host'taki sahip ve izinle bind mount eder; long syntax'taki `uid/gid/mode` uygulanmaz.
  - Deploy kullanıcısına ait `0600` dosyayı postgres (999) ve uygulama (10001) okuyamadı; Linux'ta yeniden üretildi. İlk Linux CI koşusunda yığın başlamadı.
  - Windows'taki Docker Desktop bind mount'larda izinleri `0777` gösterdiği için bu durum geliştirici makinesinde görünmüyor.
- **Düzeltme:** Secret klasörü `0700` (host koruması), dosyalar `0644`. Ya da container kullanıcısıyla aynı UID/GID'ye `chown`; ya da gerçek secret mekanizması (swarm/Kubernetes). Kural, Linux'ta koşan bir uçtan uca testle doğrulansın.
- **Verso:** `scripts/dev-secrets.sh`, `secrets/README.md`; ADR-0007 #47.

## Faz 2 review'ları (2026-10-02)

Kaynak: faz 2'nin altı bağımsız review'ı (db-migration, security, test-writer, environment, spring-code, architecture). Her madde bir reproduksiyonla doğrulandı; düzeltmeler önce kırmızı testle yapıldı.

### R37 · Constraint hatası satırın tamamını sunucu loguna yazar (HIGH, gizlilik)
- **Nerede:** Referans 8.4 ve R33. Bind parametrelerini gizlemek yetmiyor.
- **Kanıt:** Uygulama rolü bind parametreleriyle bir CHECK ve bir UNIQUE constraint'i ihlal etti. Sunucu logu `DETAIL: Failing row contains (..., <belge metni>, ...)` ve `Key (title)=(...) already exists` yazdı.
- **Düzeltme:** `log_error_verbosity = terse`. İstemci tarafında JDBC `logServerErrorDetail=false`. Bunu container logunu okuyan bir test doğrulasın.
- **Verso:** `05-settings.sh`, `DatabaseRolesTest.constraintViolation_whenRowCarriesDocumentText_neverReachesTheServerLog`.

### R38 · Uygulama içi Flyway, uygulama sürecine şema sahibi yetkisi verir (MEDIUM, güvenlik)
- **Nerede:** Referans 10.2 (Flyway uygulama açılışında) ile 10.1'in gerekçesi ("ele geçirilen uygulama tablo düşürememeli") çelişiyor.
- **Kanıt:** Uygulama container'ı (UID 10001) migration parolasını okuyabiliyor. Bu parolayla Flyway geçmişi silinebildi ve tablo düşürülebildi.
- **Düzeltme:** Migration'ı aynı image'la çalışan tek seferlik bir job/servis yapsın. Uzun ömürlü uygulama yalnız DML rolünü alsın.
- **Verso:** compose `migrate` servisi; ADR-0007 #48; `ComposeConfigTest`.

### R39 · Okunamayan secret, init'te sessizce parolasız rol üretir (MEDIUM, işletim)
- **Kanıt:**
  - psql'in backtick'i okunamayan dosyada boş string döndürüyor.
  - PostgreSQL boş parolayı NOTICE ile "parolasız rol"e çeviriyor.
  - `[ -s ]` kontrolü okuma izni olmadan da geçiyor.
  - Init başarı raporluyor.
- **Düzeltme:** `[ -r ] && [ -s ]` ile kontrol et; rollerden sonra `rolpassword IS NOT NULL` doğrula; init'i durdur.
- **Verso:** `10-roles.sh`.

### R40 · Katalog sorgusu yetkinin geri geldiğini kanıtlamaz (MEDIUM, yedek)
- **Kanıt:** `pg_restore --no-privileges` ile tüm yetkiler kaybedildi. `pg_tables` sorgusu, yetki gerektirmediği için yine geçti ve prova "OK" dedi.
- **Düzeltme:**
  - Yedek, aynı snapshot'ta bir ACL parmak izi alsın (`acldefault` ile normalize edilmiş). Prova bu parmak izini karşılaştırsın.
  - Uygulama rolü her tablosunu gerçekten okusun.
  - Öz-test, gerçek bir yetki kaybını yakalasın.
- **Verso:** `deploy/backup/queries.sh`, `restore-check.sh`, öz-test vakası "restore that loses its grants".

### R41 · `afterMigrate` yalnız başarıda çalışır (LOW, veri)
- **Kanıt:** İlk migrate başarısız olunca geçmiş tablosu default privilege'larla uygulama rolüne açık kaldı. Uygulama rolü sahte bir `success=true` satırı ekleyebildi.
- **Düzeltme:** Aynı `REVOKE` ile bir `afterMigrateError.sql`; ya da geçmiş tablosu uygulama rolünün erişemediği bir şemada dursun.
- **Verso:** `afterMigrateError.sql`, `MigrationConventionsTest.callbacks_*`.

### R42 · Config tree, yolunda `..` geçen klasörü sessizce atlar (MEDIUM, yapılandırma)
- **Nerede:** Referans 15.3 config tree önerisi.
- **Kanıt:** Boot'un `ConfigTreePropertySource`'u, adı "." ile başlayan her yol parçasını (`..` dahil) atlıyor. `optional:configtree:../secrets/` hiçbir property yüklemedi; yer tutucu metin olarak veritabanına gitti.
- **Düzeltme:** Config tree yollarında nokta ile başlayan parça olmasın; bir test bunu denetlesin.
- **Verso:** `ConfigProfilesTest.configTreeImports_whenDeclared_haveNoDotSegments`.

### R43 · `@Transactional` tanımı Spring 7'de daha geniş (MEDIUM, mimari test)
- **Nerede:** Referans 16, `TransactionBoundaryRulesTest` ("@Transactional metot ... çağırmaz").
- **Kanıt:** Spring 7, `public` olmayan metotları da transaction'a alıyor. Şu biçimler ilk kural sürümünden kaçtı:
  - birleşik (meta) anotasyon;
  - arayüz metodu ya da üst sınıftaki anotasyon;
  - `jakarta.transaction.Transactional`;
  - private yardımcı üzerinden çağrı;
  - `RestClient`'ın iç tipleri.
- **Düzeltme:** Meta-anotasyon, arayüz ve üst sınıf taransın; private ve static dışındaki metotlar sayılsın; aynı sınıf içi çağrılar izlensin; iç tipler ön ekle eşleşsin. Her biçim için bir fixture olsun.
- **Verso:** `TransactionBoundaryRulesTest`, `archfixture.tx`.

### R44 · Parola rotasyonu `pg_stat_statements`'e ve loga iz bırakır (MEDIUM, güvenlik)
- **Kanıt:** Superuser'ın çalıştırdığı `ALTER ROLE ... PASSWORD '...'`, metni `pg_stat_statements`'e ve PGDATA'daki sorgu dosyasına yazdı. Başarısız olursa sunucu loguna da düşer.
- **Düzeltme:** Oturumda `SET pg_stat_statements.track_utility = off; SET log_min_error_statement = panic;`. Parola stdin'den psql değişkenine alınsın.
- **Verso:** `secrets/README.md` rotasyon bölümü; prosedür denendi ve iz kalmadığı doğrulandı.

### R45 · `.dockerignore` desenleri yalnız kökte eşleşir (LOW, image)
- **Kanıt:** `verso-app/.env` ve `src/main/resources/.env.prod` build context'ine girdi. İkincisi `target/classes` üzerinden jar'a ve image'a gidebilirdi.
- **Düzeltme:** `**/.env`, `**/.env.*`, `**/secrets/`.
- **Verso:** `.dockerignore`.

### R46 · Test yığınında loopback güveni parola kontrolünü gizler (LOW, test)
- **Kanıt:** Postgres image'ının `pg_hba.conf`'u loopback'e güveniyor. Container içinden `127.0.0.1` ile bağlanan bir test her parolayla geçiyor.
- **Düzeltme:** Testler servis adı (ağ arayüzü) üzerinden bağlansın.
- **Verso:** `scripts/restore-drill-selftest.sh`.

## Faz 3 (2026-10-02)

Kaynak: faz 3 geliştirmesi sırasında gözlenenler, altı review (security, spring-code, test-writer, environment-impact, architecture-boundary, resilience) ve `scripts/mutation-check.sh` (M89–M127).

### R47 · Global handler'ın `Exception` yakalayıcısı yetki reddini 500'e çevirir (HIGH, güvenlik/hata zarfı)
- **Nerede:** Ek B `GlobalServiceExceptionHandler.handleUnexpected` (`@ExceptionHandler(Exception.class)`).
- **Kanıt:** Controller'dan (ileride `@PreAuthorize`) fırlayan `AccessDeniedException`, Spring Security'nin `ExceptionTranslationFilter`'ına ulaşmadan `@ControllerAdvice`'ta yakalandı; istemci 403 yerine 500 ve ERROR logu aldı (`PlatformSecurityTest.request_whenControllerDeniesAccess_*`, mutasyon M97).
- **Düzeltme:** Catch-all, `AccessDeniedException` ve `AuthenticationException`'ı (alt sınıfları dahil) yeniden fırlatsın. platform-core Spring Security'ye derleme bağımlılığı almamak için sınıf adlarıyla ve üst sınıf zinciriyle kontrol etsin.
- **Verso:** `ErrorClassifier.isSecurityException`, `GlobalServiceExceptionHandler.handleUnexpected`.

### R48 · Resource server açılınca health probları token ister (MEDIUM, işletim)
- **Nerede:** Referans 9 ve 18.2: yönetim portu ayrı, compose healthcheck readiness'i sorar.
- **Kanıt:** `anyRequest().authenticated()` yönetim portundaki `/actuator/health`'i de kapsadı; compose healthcheck 401 aldı, container `unhealthy` kaldı.
- **Düzeltme:** `EndpointRequest.to("health")` permitAll (yönetim portunu bilen eşleştirici); actuator yoksa kural eklenmesin.
- **Verso:** `PlatformSecurityAutoConfiguration`, `VersoAppSmokeTest.probesAndApi_whenCalledWithoutToken_*`, M98.

### R49 · Spring Security firewall'ının reddi zarfın dışında kalır (LOW, hata zarfı)
- **Nerede:** Referans 7 (her hata zarflı) ve Ek B.
- **Kanıt:** Nokta segmentli yol (`/v1/x/../y`) `StrictHttpFirewall` tarafından `RequestRejectedException` ile durduruldu; varsayılan işleyici zarfsız yanıt üretti. Faz 1'in "ham yol loglanmaz" testi bu yolu dener.
- **Düzeltme:** Zarflı bir `RequestRejectedHandler` bean'i (400, ortak validation bloğunda bir kod).
- **Verso:** `EnvelopeRequestRejectedHandler`, `VersoAppSmokeTest.dotSegmentPath_*`, M99.

### R50 · Token türü ayrımı JOSE başlığıyla da yapılmalı (MEDIUM, güvenlik)
- **Nerede:** Referans 9.2: yüzeyler `typ` **claim**'i ve ayrı anahtarla ayrılır.
- **Kanıt:** Harici IdP (Keycloak) ID token'ını ve access token'ı aynı anahtarla imzalar; `typ` claim'ini Verso seçemez. RFC 9068 bunun için JOSE `typ: at+jwt` başlığını tanımlar. Kural kaldırılınca `typ: JWT` taşıyan, imzası ve `aud`'u geçerli token kabul edildi (M94, M96).
- **Düzeltme:** Harici IdP kullanan profiller için kural: "access token = JOSE `typ` `at+jwt` (RFC 9068); IdP desteklemiyorsa `aud` + ADR."
- **Verso:** `JwtValidation`, `verso.yml` `type-header`, realm istemci ayarı `access.token.header.type.rfc9068`.

### R51 · Keycloak readiness'i realm içe aktarımı bitmeden UP der (LOW, compose)
- **Kanıt:** Boş veritabanıyla açılışta `/health/ready` 19:05:41'de UP; bootstrap 19:05:49'da bitti, aradaki token istekleri 503 aldı. `docker compose up --wait` hemen ardından gelen smoke testi düştü.
- **Düzeltme:** Healthcheck realm'in discovery belgesini (`/realms/<realm>/.well-known/openid-configuration`) sorsun.
- **Verso:** `compose.yaml` keycloak healthcheck; prosedür boş IdP veritabanıyla tekrar denendi.

### R52 · Starter'ın API zinciri, ilk ek zincirde tamamen kapanır (HIGH, güvenlik)
- **Nerede:** Referans 9 / Ek B güvenlik starter'ı kalıbı: `@ConditionalOnMissingBean(SecurityFilterChain.class)`.
- **Kanıt:** Bir modül yalnız `/hooks/**` için zincir ekleyince platform zinciri hiç oluşmadı; Boot'un varsayılanı da (`@ConditionalOnDefaultWebSecurity`) geri çekildi. Token'sız `GET /v1/...` 200 döndü (iki review bağımsız buldu).
- **Düzeltme:** Platform zinciri koşulsuz, `@Order(LOWEST_PRECEDENCE - 10)` ile catch-all; yalnız aynı adlı bean onu değiştirir. Test: ek zincir varken API 401.
- **Verso:** `PlatformSecurityAutoConfiguration`, `PlatformSecurityTest.api_whenAnotherSecurityChainIsAdded_*`, M107.

### R53 · `@CurrentAccount` yanlış tipte sessizce query'den bağlanır (HIGH, güvenlik/IDOR)
- **Nerede:** Referans 6.5 örneği `@CurrentAccount UUID accountId`; resolver yalnız kendi tipini destekler.
- **Kanıt:** `@CurrentAccount String account` + `?account=victim` + başka hesabın geçerli token'ı → `200 account=victim`.
- **Düzeltme:** Resolver anotasyonlu her parametreyi üstlensin, tip yanlışsa reddetsin; açılışta tüm handler metotları taransın (yanlış tip = uygulama başlamaz). Referans örneği `AccountId` gibi tek bir değer tipine çevrilsin.
- **Verso:** `CurrentAccountArgumentResolver`, `CurrentAccountParameterCheck`, M108, M109.

### R54 · `withJwkSetUri` hız sınırı olmadan kurulur; IdP kesintisi 500'dür (HIGH, dayanıklılık)
- **Nerede:** Referans 9.2 rotasyon kuralı ("JWKS'te `kid`... restart gerekmez") ve 13 (dayanıklılık).
- **Kanıt:** Spring Security 7.1.1'in builder'ı Nimbus kaynağını hız sınırsız kurar: uydurma `kid`'li 20 token = 20 JWKS isteği. IdP kapalıyken (önbellek soğuk) `AuthenticationServiceException` filtre tarafından yeniden fırlatıldı → 500 ve ERROR. Önbellek süresi dolunca outage toleransı da yoktu.
- **Düzeltme:** `JWKSourceBuilder`: önbellek, `rateLimited`, `outageTolerant`, timeout + boyut sınırı; hız sınırına takılan bilinmeyen `kid` 401, ilk yükleme hiç olmamışsa 503. `AuthenticationEntryPointFailureHandler.setRethrowAuthenticationServiceException(false)` ile 503 + `Retry-After`. Kritik akış kaydına "önbellekli kontrol düzlemi bağımlılığı" satırı.
- **Verso:** `JwtValidation.keySource`, `EnvelopeAuthenticationEntryPoint`, `IDP_UNAVAILABLE` 90103, M110–M114; canlı denendi (sıcak önbellek: kesinti görünmez; soğuk: 503).

### R55 · ArchUnit'in `DO_NOT_INCLUDE_TESTS`'i test-jar'ları dışlamaz (LOW, mimari test)
- **Kanıt:** `test` koşusunda test-jar klasör (`test-classes`) olarak gelip dışlandı; `verify`'da `*-tests.jar` olarak geldi ve üretim kodu sayıldı. İki koşu farklı sınıf kümesini denetledi.
- **Düzeltme:** `location -> !location.contains("-tests.jar")` ek seçeneği.
- **Verso:** `ArchitectureRulesTest.NO_TEST_JARS`.

### R56 · Keycloak her realm'e password grant'lı `admin-cli` ve isteğe bağlı `offline_access` koyar (MEDIUM, kimlik)
- **Nerede:** Referans 9.7 (demo IdP / kimlik desenleri).
- **Kanıt:** Realm dosyasında yalnız iki istemci tanımlıyken `admin-cli` password grant'a açıktı. `verso-cli` varsayılan isteğe bağlı kapsamlarla `offline_access` isteyebiliyordu.
- **Düzeltme:** Realm dosyasında `admin-cli` kapalı; istemcilerin kapsamları açıkça (`basic`) ve isteğe bağlı kapsam boş. Canlı: `admin-cli` → `invalid_client`; `offline_access` istense de refresh token türü `Refresh`.
- **Verso:** `verso-realm.json`, `KeycloakRealmTest.builtInAndCliClients_*`, M124.

### R57 · Init script'leri eski volume'de çalışmaz; yeni bir veritabanı eklemek yükseltme yolu ister (MEDIUM, işletim)
- **Kanıt:** Faz 2'den gelen volume'de `keycloak` rolü ve veritabanı oluşmaz; Keycloak sağlıklı olmaz.
- **Düzeltme:** Yeni init script'i idempotent olsun (psql `\gset` + `\if`), README'de elle çalıştırma adımı; çalışan sunucuda `pg_stat_statements.track_utility = off` (R44).
- **Verso:** `30-keycloak.sh`, README "Faz 2'den yükseltme", `DatabaseRolesTest.keycloakScript_*`, M123.
