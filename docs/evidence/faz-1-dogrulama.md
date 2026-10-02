# Faz 1 — Doğrulama ve kanıt kaydı

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz. Bu dosya faz 1'in kanıtıdır. Sonraki fazlar kendi dosyalarını ekler.

- **Tarih:** 2026-10-02
- **Ortam:** Windows 11, Temurin 25.0.4.1, Maven 3.9.16 (wrapper), Node 24.18, gitleaks 8.24.3
- **Kapsam:**
  - Platform starter'ları: `platform-core`, `platform-observability`.
  - Uygulama: `verso-app`.
  - Yönetişim dosyaları: AGENTS.md, `docs/ai/*`, skill'ler, hook'lar, script'ler, CI.
  - Kararlar: ADR 0001–0008.
- **Davranışsal kapsam (seviye 2+):** yok. Faz 1'de veritabanı, model ve dış bağımlılık bulunmuyor. Seviye 2 testleri (gerçek PostgreSQL, Testcontainers) faz 2'de başlar.

## 1. Makine kontrolleri

| Kontrol | Komut | Sonuç |
|---|---|---|
| Build ve testler | `./mvnw -B -ntp clean verify` | **PASS**: 120 test. platform-observability 39, platform-core 44, verso-app 37 (ilk turda 77, ikinci tur sonunda 103) |
| Enforcer | aynı koşu | **PASS**: Java ≥25, Maven ≥3.9, `dependencyConvergence`, core→core yasağı (`verso-app` hariç; ADR-0007 #20) |
| Migration değişmezliği | `node --test scripts/flyway-immutability.test.js` · `node scripts/flyway-immutability.js check --base origin/develop` | **PASS**: 16/16 test (`--staged` dahil); `OK` (migration yok) |
| Config lint | `node --test scripts/config-lint.test.js` · `node scripts/config-lint.js <config-lint.pathspec listesi>` | **PASS**: 23/23; `OK` |
| gitleaks | `GITLEAKS=… node --test scripts/gitleaks-check.test.js` · `bash scripts/gitleaks-check.sh all .` | **PASS**: 10/10; geçmiş ve çalışma ağacı temiz |
| pre-commit davranışı | `GITLEAKS=… node --test scripts/pre-commit.test.js` | **PASS**: 4/4. Hook geçici bir depoda gerçek commit'lerle denendi: staged secret, staged fallback, staged migration ve temiz commit |
| Review gate | `node --test scripts/review-gate.test.js` | **PASS**: 5/5. 15 push biçimi soruyor, push olmayan 7 komut sessiz geçiyor, 300 bin karakterlik komut soruyor |
| Hook kuru çalıştırma | `bash -n` tüm hook'lar, flyway hook (Windows yolları) | **PASS**: hook'lar fail-closed |
| Mutasyon (negatif doğrulama) | `bash scripts/mutation-check.sh` | **PASS**: 66/66 mutasyonun her biri beklenen **test metodunu** adıyla kırmızıya çevirdi. Java ve node baseline'ları yeşil, artık dosya yok. Çıktılar: `faz-1-mutasyon-ciktisi.txt` (ilk tur, 30), `-2.txt` (ikinci tur, 46), `-3.txt` (üçüncü tur, 66) |
| CI (GitHub Actions) | `.github/workflows/ci.yml`: `backend` ve `scripts-and-hooks` | **PASS** (ikinci denemede). İlk koşuda `backend` yeşildi (Linux'ta 120 test), `scripts-and-hooks` kırmızıydı: pre-commit testi Linux'ta hook'u atlıyordu (ders 12). Düzeltme c665df0, PR #2 yeşil. `main` ve `develop` 12bc1c0'da yeşil. Hata ve düzeltme ayrıca `node:24` container'ında yeniden üretildi; CI'da hiç koşmamış beş adım da Linux'ta denendi |
| Branch protection | GitHub API, `main` ve `develop` | **Aktif**: `backend` ve `scripts-and-hooks` geçmeden merge yok (`strict`, yöneticiler dahil); force push ve dal silme kapalı. PR #1 CI kırmızıyken merge edilebilmişti |

## 2. Review skill'leri

İlk tur, faz 1 kodunun ilk haliyle yapıldı. Skill'ler bu oturumda yüklenemediği için (oturum başında kayıtlı değillerdi) her biri bağımsız bir alt ajana verildi. Her ajan ilgili `SKILL.md` dosyasını okuyup uyguladı.

| Skill | İlk karar | Bulgular (özet) |
|---|---|---|
| `verso-environment-impact-review` | APPROVE WITH NON-BLOCKING COMMENTS | Drift prefix'leri, Prometheus ucu, Lombok, secret-fallback kapsamı (LOW) |
| `verso-spring-code-review` | REQUEST CHANGES | Binding hatasında değer sızıntısı (HIGH); 500 özetinde dosya adı/IP (MEDIUM); 405 `Allow`, 4xx eşlemesi, boş döngü kuralı, Lombok (LOW) |
| `verso-architecture-boundary-review` | REQUEST CHANGES | Enforcer şekil A'yı kırıyor (MEDIUM, kanıtlı); Modulith platform tespiti (MEDIUM); api arayüzü ADR'siz (MEDIUM); Modulith testi zamanlaması, `dependencyConvergence` (LOW) |
| `verso-test-writer` | FAIL | Değer sızıntısı ve JSON secret (HIGH, kırmızı probe); 8/8 reviewer mutasyonu kurtuldu; Tomcat 400 zarfsız; `${revision}` install |
| `verso-security-review` | REQUEST CHANGES | B1 değer sızıntısı; B2 ham path log'u; B3 ReDoS; B4 pre-commit yok (MEDIUM); B5–B10 (LOW) |

**Bulguların çözümü:** her bulgu bir test ve bir mutasyonla kapatıldı. Bulgu → çözüm eşlemesi `docs/reference-feedback.md` (R1–R15) ve ADR-0007 (#14–#29) dosyalarında. Kabul edilen tek sınır ADR-0007 #19: Tomcat'in istek satırını reddettiği 400 yanıtları zarfsızdır. Path loglanmaz; bu testli.

**İkinci tur:** düzeltmelerden sonra aynı skill'lerle yeniden inceleme yapıldı. Sonuçlar bölüm 4'te.

## 3. Bu fazda öğrenilenler (kanıtla)

1. **Kırmızı her zaman yakalandı demek değil.** Mutasyon koşusu iki kez sahte "yakalandı" sonucu verdi. Birinde `${revision}` çözülemedi, diğerinde upstream modülde "No tests were executed" hatası vardı. Script artık logda assertion hatası görmeden hiçbir mutasyonu yakalandı saymıyor.
2. **Geri yükleme zaman damgası.** Yedekten `mv` ile dönen dosya eski mtime'ı taşıdığı için mutasyonlu `.class` dosyası kullanılmaya devam etti. Script geri yüklemeden sonra `touch` yapıyor.
3. **Bean sayısı kanıt değil.** Platform paketi scan edilse bile handler tek bean kalıyor. Test bu yüzden bean'in fabrikasının auto-configuration olduğunu doğruluyor.
4. **Gerçek port testi sızıntı buldu.** `PageNotFound` ham path'i WARN olarak yazıyordu. Bunu reviewer'lar değil, path işaretli gerçek Tomcat testi yakaladı.
5. **Referans iskeletindeki iki gizlilik kusuru Verso'ya kopyalanmıştı** (R1, R2). Birebir kopyalamak, kopyalananı test etme ihtiyacını ortadan kaldırmıyor.

6. **Bir düzeltmenin kendisi de review ister.** İkinci tur, birinci turun düzeltmelerinde yeni delikler buldu:
   - "Mesaj değeri içeriyor mu" kontrolü sınıf düzeyi constraint'leri ve formatter ifadelerini kaçırıyordu.
   - Pre-commit çalışma ağacını tarıyordu.
   - Mutasyon script'inin baseline'ı yoktu.
7. **Hata yalnız Spring MVC içinde oluşmaz.** Filtrede, konteynerin ayrıştırma katmanında ve cookie parser'da oluşan hatalar, MVC handler'ının hiç görmediği ayrı log kanallarına düşüyor. Faz 3'teki security filtreleri tam bu yoldan geçecek.
8. **Test sırası kanıt değildir.** Tomcat logger'ı sabit `[Tomcat]` adıyla susturulmuştu, ama bu ad yalnız JVM'deki ilk motoru tutuyor. Testler, `ContainerErrorPathTest` alfabetik olarak önce koştuğu için yeşildi. Ters sırada dört test kırmızıya döndü (N1).
9. **Test işaretçisi de bir kurala takılabilir.** 36 karakterlik işaretçi, sanitizer'ın "opak blok" kuralıyla maskeleniyordu. Bu yüzden sanitize edilmiş istisna mesajının loga girmesi testte görünmüyordu (N3). Log sızıntı testlerinde işaretçi düz ve boşluklu bir metin olmalı.
10. **Eşzamanlı doğrulama kendi kanıtını kirletebilir.** Mutasyon script'i sabit bir log yolu kullanıyordu. Reviewer'ın kopyasındaki koşu ile ana koşu aynı dosyaya yazınca 137 ve 177 testlik sahte baseline'lar çıktı. Log artık her koşuda `mktemp` ile ayrı.
11. **Adla eşleşen tipler sessizce kırılır.** `org.apache.catalina.connector.BadRequestException` Tomcat 11'de yok, sınıf `org.apache.coyote` paketinde. Adla eşleşen her konteyner tipi için artık "sınıf yolunda gerçekten var" testi bulunuyor.
12. **Windows'ta yeşil olmak Linux'ta yeşil olmak değildir.** Pre-commit testi hook'u geçici depoya `fs.writeFileSync` ile kopyalıyordu ve çalıştırma izni düşüyordu. Windows'taki git bu izne bakmıyor; Linux'taki git hook'u sessizce atlıyor. CI'ın ilk koşusunda üç "engellenmeli" testi kırmızıya döndü. Düzeltmeyle birlikte pozitif kontrol de eklendi: temiz commit testi, hook'un kontrolleri gerçekten çalıştırdığını çıktısından doğruluyor. Platforma bağlı her test artık bir Linux container'ında da koşturuluyor.

## 4. İkinci tur review kararları

İkinci tur, birinci turun düzeltmelerinden sonra aynı skill'lerle yapıldı. Her reviewer önce kendi birinci tur bulgularını yeniden doğruladı ve düzeltmeleri kendi kopyasında kırmaya çalıştı.

| Skill | Karar | Birinci tur bulguları | Yeni bulgular |
|---|---|---|---|
| `verso-environment-impact-review` | APPROVE WITH NON-BLOCKING COMMENTS | 4 bulgunun 3'ü RESOLVED, 1'i yalnız doküman eksiği | L-A…L-F (LOW) |
| `verso-spring-code-review` | **APPROVE WITH NON-BLOCKING COMMENTS** | 6/6 RESOLVED. Mutasyon script'ini kendi kopyasında koşturup 30/30 sonucunu teyit etti | Filtre yolu sızıntısı (MEDIUM). Sınıf düzeyi constraint, sanitizer boşlukları ve validation sağlayıcısı (LOW) |
| `verso-architecture-boundary-review` | **APPROVE** | 5/5 RESOLVED; enforcer, Modulith ve `dependencyConvergence` gerçek modüllerle denendi | Adlandırılmış arayüz kuralı, modül adlandırma, api → platform, fixture izolasyonu (LOW, ileriye dönük) |
| `verso-security-review` | REQUEST CHANGES | 10 bulgunun 7'si RESOLVED, 2'si kısmen | B11 filtre/konteyner yolu, B12 cookie logu, B13 pre-commit kapsamı (MEDIUM); B14–B19 (LOW) |
| `verso-test-writer` | FAIL | Kırmızı vakalar ve 8 kurtulan mutasyonun hepsi kapandı (41 bağımsız mutasyonla doğrulandı) | Filtre istisnası Tomcat logunda (HIGH); mutasyon script'inin kendi açıkları (MEDIUM); testsiz yollar |

**İkinci tur bulgularının çözümü:** Her bulgu önce kırmızı bir testle tekrarlandı, sonra düzeltildi ve mutasyon script'ine eklendi (M31–M43). Tam bulgu → çözüm eşlemesi `docs/reference-feedback.md` (R16–R22) ve ADR-0007'de (#18, #19, #24, #30–#33).

| Bulgu | Kırmızı test (önce) | Düzeltme |
|---|---|---|
| Filtre/konteyner hata yolu (3 review) | `ContainerErrorPathTest`: 6/6 kırmızı | `ErrorClassifier`; `EnvelopeErrorController` sınıflandırması; Tomcat logger OFF; form filtresi kapalı |
| Cookie parser logu | `cookieHeader_whenMalformed_isNotLogged` kırmızı | `org.apache.tomcat.util.http: WARN` |
| Pre-commit staged kapsamı | `gitleaks-check.test.js`'te 2 yeni test kırmızı | `staged` modu; config-lint staged içerikten |
| Şablon tabanlı mesaj kontrolü | Reviewer probe'ları | `ruleMessage` (şablon), `fieldPath`, `leafName` |
| Validation sağlayıcısı | Uygulama açılış uyarısı | `spring-boot-starter-validation` + smoke testi |
| Sanitizer boşlukları | Reviewer probe'ları | Desenler genişletildi; 7 yeni test |
| Review gate SIGPIPE ve bypass'ları | 2 milyon karakterlik komut + 7 varyant | Pipe'sız regex; CI assert'leri |
| Mutasyon script'i | Test review gösterimi | Baseline, metot düzeyi kriter, `trap`, birebir exit kodu |

## 5. Üçüncü tur

İkinci turda onay vermeyen iki review (security, test-writer), ikinci tur düzeltmeleri üzerinde bağımsız alt ajanlarla yeniden koştu. İki ajan da repoyu `target/` olmadan geçici bir kopyaya aldı ve `C:\verso`'ya dokunmadı.

| Skill | Karar | İkinci tur bulguları | Yeni bulgular |
|---|---|---|---|
| `verso-security-review` | **APPROVE WITH NON-BLOCKING COMMENTS** | B11, B12, B13 ve B14 RESOLVED. Reviewer bunu kendi probe'larıyla doğruladı: bozuk chunked gövde, çok sayıda parametre, sınırsız multipart, derin JSON, kontrol karakterli cookie, `git commit <yol>` | B20 (MEDIUM, gizli): hata dispatch'i de patlayınca host logger'ı yazıyor. B21–B25 (LOW). Kapsam dışı not: bozuk chunked gövde VALIDATION koduyla bitiyor |
| `verso-test-writer` | **PASS WITH COMMENTS** | İkinci turun HIGH bulgusu çözüldü; mutasyon script'i ve testsiz yollar kısmen çözüldü | N1–N5 (MEDIUM), N6–N11 (LOW). Reviewer'ın 25 bağımsız mutasyonundan 14'ü yaşadı (J01–J05, J09–J12, J15, P1, P2, C1, C2) |

**Çözüm.** Her bulguda sıra aynıydı: önce kırmızı test, sonra düzeltme, sonra mutasyon.

| Bulgu | Kırmızı test (önce) | Düzeltme | Mutasyon |
|---|---|---|---|
| B20 hata dispatch'i | `filterException_whenErrorDispatchFailsToo_*`: host logger stack trace yazdı | Host logger alt ağacı OFF | M44 |
| N1 motor adı / test sırası | Ters sırada CEP'te 4 test ve `ContainerLogSilencingTest` kırmızı | `ContainerErrorLogSilencer`: gerçek motor adıyla, API ve management portu | M31, M50 |
| B21 `]` içeren map anahtarı | `requestBody_whenMapKeyContainsBrackets_*` | Yol Bean Validation düğümlerinden kuruluyor; liste index'inden sonraki alan korunuyor | M11b, M45 |
| B22 `.yaml` / `.properties` | Pathspec kapsam testi eski glob'larla 3 dosyayı kaçırdı | `scripts/config-lint.pathspec` (tek kaynak) | M47 |
| B23 review gate bypass'ları | `git -c "alias.p=!git push" p` sessiz geçti | `review-gate-detect.js` ve `scripts/review-gate.test.js` | M43 |
| B24 sanitizer boşlukları | 5 test kırmızı | Authorization her şemayla redakte ediliyor; salt, pepper, private key, pass, pw anahtarları; kullanıcısız URL; kaçışlı tırnak; Unicode kontrol karakterleri | M51, M52, M60 |
| B25 commit edilmiş yanıt | `filterException_whenResponseAlreadyCommitted_*`: WARN 404 ve bozuk chunk | İstisna varsa durum en az 500; yanıt commit edilmişse gövde yazılmıyor | M48, M49 |
| Chunked gövde kodu (kapsam dışı not) | `chunkedBody_whenMalformed_*`: kod 90000 | `org.apache.coyote.BadRequestException` malformed sayılıyor; sınıf adlarının varlığı test ediliyor | M64 |
| N2 controller dalları | J01–J03 yaşadı | `filterReadingParameter_*` ve `*WrappedInServletException_*` testleri | M53, M54 |
| N3 işaretçi | J04 yaşadı | `PLAIN_MARKER` | (M31) |
| N4 mutasyon script'i | Sahte baseline; `ONLY=M99` 0 ile çıktı; SIGTERM sonrası koşu devam etti | Koşu başına `mktemp` log; node baseline; bilinmeyen kimlik hata sayılıyor; INT/TERM'de çıkış | Kendi koşusu |
| N5 pre-commit testi | P1 ve P2 yaşadı | `scripts/pre-commit.test.js` | M56, M57, M58 |
| N6 migration staged | Stage'deki değişiklik geçti | `check --staged` | M58, M59 |
| N7–N11 testsiz kurallar | J05, J09–J12, J15, C1 ve C2 yaşadı | Actuator, sanitizer, custom Validator, trace id ve camelCase/export testleri eklendi | M55, M61, M62, M63 |
| N8 zayıf assertion'lar | – | Pozitif `code=REQUEST_NOT_READABLE` WARN satırı aranıyor; multipart için zarf ve trace kontrolü; form testi birleştirildi | – |

**Bilinçli olarak açık bırakılanlar:**
- **Management portu, çok kısa bir aralık.** Management portundaki Tomcat'in host logger'ı, sunucu başladıktan sonra `WebServerInitializedEvent` yayınlanana kadar susturulmuş değil. Ana sunucu için `application.yml` girdisi bu aralığı da kapsıyor. Management portunda bugün özel filtre yok; faz 3'te security filtreleri eklenince yeniden bakılacak.
- **Çift log.** Bozuk chunked gövdede aynı istek iki kez WARN olarak loglanıyor: önce MVC handler'ı, sonra hata dispatch'i. İki satır da aynı trace id'yi ve aynı kodu taşıyor.
- **Review gate bir kolaylık.** `git config alias` ile tanımlanmış bir takma ad yakalanmaz. Zorunlu katman CI.

## 6. Net kanıt bulunamayan alanlar

- **Claude Code hook'larının oturum içinde tetiklenmesi hâlâ kanıtsız.** Hook'lar örnek girdilerle ve `scripts/review-gate.test.js` ile doğrulandı. Ancak ilk push'ta review gate sormadı. Bu oturum ayarlarını `C:\verso` dışındaki bir klasörden yükledi, bu yüzden projenin `.claude/settings.json`'u etkin değildi. Push'u kullanıcı sohbette onayladı. Oturum içi tetiklenme, `C:\verso`'da açılan bir oturumda ilk korunan migration (faz 2) ve ilk push sırasında gözlenecek.
- ~~CI workflow'u henüz koşmadı~~: koştu, sonuç bölüm 1'de.
