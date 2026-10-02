# Faz 2: Doğrulama ve kanıt kaydı

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

- **Tarih:** 2026-10-02
- **Ortam:** Windows 11 + Docker Desktop 29.6.1 (Linux engine); GitHub Actions `ubuntu-latest`. Temurin 25.0.4.1, Maven 3.9.16, Node 24.18, gitleaks 8.24.3.
- **Kapsam:**
  - PostgreSQL 18.6 + pgvector 0.8.7.
  - Modül başına iki rol ve GRANT sınırı; Flyway 12.4.0.
  - Compose (postgres, migrate, verso-app, backup; restore provası profili) ve çok aşamalı Dockerfile.
  - Şifreli yedek ve otomatik restore provası.
  - Testcontainers ile gerçek DB testleri.
  - Kararlar: ADR-0009, ADR-0007 #40–#48.
- **Davranışsal kapsam (seviye 2):** var. Uygulama context testleri gerçek PostgreSQL'e karşı koşar: compose ile aynı image ve aynı init script'leri, gerçek roller. Yığının kendisi temiz checkout'tan CI'da (Linux) ve yerelde ayağa kaldırılıp yedek/restore öz-testiyle denendi.

## 1. Makine kontrolleri

| Kontrol | Komut | Sonuç |
|---|---|---|
| Build ve testler | `./mvnw -B -ntp verify` | **PASS**: 160 Java testi (faz 1 sonunda 120) |
| Script ve hook testleri | `node --test scripts/*.test.js` | **PASS**: 61 test (config-lint 23, flyway 16, gitleaks 10, pre-commit 5, review-gate 5, repo-hygiene 2) |
| Temiz yığın (Linux, CI) | `restore-drill` workflow: `dev-secrets.sh` → `docker compose up --build --wait` → öz-test → prova | **PASS** (son koşu bölüm 5'te) |
| Temiz yığın (yerel) | Ayrı proje adı, hiç var olmamış image etiketi, boş volume | **PASS**: migrate 0 ile çıktı; postgres, verso-app ve backup healthy |
| Restore provası öz-testi | `bash scripts/restore-drill-selftest.sh` | **PASS**, 13 vaka. Geçmesi gerekenler: AES256 at rest, probe satırları birebir, yetkiler ve grant'ler, Flyway validate çalıştı, eşzamanlı yazma altında tutarlılık. Kırmızı vermesi gerekenler: değiştirilmiş satır sayısı, değiştirilmiş yetki parmak izi, yetkileri gerçekten kaybeden restore, DDL yetkili uygulama rolü, checkout'ta olmayan migration, bozulmuş dosya, eksik checksum |
| Mutasyon (negatif doğrulama) | `bash scripts/mutation-check.sh` | Bölüm 5'te |
| gitleaks / config-lint / migration değişmezliği | CI `scripts-and-hooks` | **PASS** |
| Parola rotasyonu prosedürü | `secrets/README.md` | **PASS**: denendi. Uygulama yeni parolayla healthy, eski parola reddedildi, `pg_stat_statements`'te ve sunucu logunda iz yok |

## 2. Review'lar

Altı review, her biri bağımsız bir alt ajanda ve dalın temiz bir klonunda koştu. Docker'da ayrı proje adı kullandılar; `C:\verso`'ya dokunmadılar.

| Skill | Karar | Öne çıkan bulgular |
|---|---|---|
| `verso-db-migration-review` | APPROVE WITH NON-BLOCKING COMMENTS | D1 (MEDIUM): restore provası yetkilerin geri geldiğini kanıtlamıyordu. D2–D4 (LOW) |
| `verso-architecture-boundary-review` | APPROVE WITH NON-BLOCKING COMMENTS | A1 (MEDIUM, ileriye dönük): ikinci modül şeması için DataSource/rol kararı. A2 (MEDIUM): transaction kuralı Spring 7 biçimlerini kaçırıyordu. A3–A6 (LOW) |
| `verso-spring-code-review` | REQUEST CHANGES | C1 (HIGH): Linux'ta `0600` secret dosyaları okunamıyor, roller parolasız oluşuyordu. C2 (MEDIUM): `../secrets/` config tree'si sessizce boş. C3 (MEDIUM), C4 (LOW) |
| `verso-security-review` | REQUEST CHANGES | S1 (HIGH): constraint hatası satırın tamamını sunucu loguna yazıyordu. S2–S4 (MEDIUM): secret izinleri, rotasyon izi, uygulamada migration parolası. S5–S6 (LOW) |
| `verso-environment-impact-review` | APPROVE WITH NON-BLOCKING COMMENTS | E1 (HIGH, CI'da bulunup düzeltilmişti), E2–E7 (MEDIUM), E8–E13 (LOW) |
| `verso-test-writer` | PASS WITH COMMENTS | 27 bağımsız mutasyondan 22'si yaşadı. T1–T7 (MEDIUM): PUBLIC ACL kontrolü yanlış geçebiliyordu, şifreleme doğrulanmıyordu, Flyway validate hiç başarısız olamıyordu. T8–T11 (LOW) |

## 3. Bulgu → düzeltme

Her düzeltmede sıra aynıydı: önce kırmızı test ya da reproduksiyon, sonra düzeltme, sonra mutasyon.

| Bulgu | Kanıt (önce) | Düzeltme | Kalıcı kontrol |
|---|---|---|---|
| CI: secret izinleri (E1/C1/S2) | İlk Linux koşusunda yığın başlamadı. Linux container'larında yeniden üretildi: `0600` dosyayı postgres (999) ve uygulama (10001) okuyamadı | Klasör `0700`, dosyalar `0644` (ADR-0007 #47) | `restore-drill` workflow |
| Parolasız roller (C1/S2/E2) | Okunamayan secret'la init "başarılı" bitti, roller parolasız kaldı | `[ -r ] && [ -s ]`, rollerden sonra `rolpassword IS NOT NULL` | Elle reproduksiyon: init exit 1 ve açık hata mesajı |
| Satır içeriği sunucu logunda (S1) | `DatabaseRolesTest` kırmızı: işaretçi container logunda | `log_error_verbosity = terse`, JDBC `logServerErrorDetail=false` | M74 |
| Uygulamada migration parolası (S4) | Reviewer parolayla geçmişi silebildi | Tek seferlik `migrate` servisi; uygulamada Flyway kapalı (ADR-0007 #48) | `ComposeConfigTest`, `MigrateModeTest`, M76, M79 |
| Restore yetkileri kanıtlamıyor (D1, T3) | `--no-privileges` restore'u "OK" | Aynı snapshot'ta ACL parmak izi; uygulama rolü her tabloyu okur | Öz-test vakası, M65 |
| Flyway validate etkisiz (T4) | Geçmişe sahte satır eklenmesi geçiyordu | `-ignoreMigrationPatterns=` | Öz-test vakası "applied migration unknown" |
| Şifreleme doğrulanmıyor (T2) | `--store` ile düz dump `.gpg` adıyla geçiyordu | — | Öz-test: `PGDMP` yok, AES256 paketi |
| PUBLIC ACL yanlış geçiyordu (T1) | ACL hiç ayarlanmazsa (NULL) test geçiyordu | `acldefault()` ile normalize | M80 |
| `afterMigrate` yalnız başarıda (D4) | Başarısız ilk migrate'te geçmişe sahte satır eklenebildi | `afterMigrateError.sql` | M75 |
| Transaction kuralı boşlukları (A2/C3/A3/T10) | Meta-anotasyon, arayüz, üst sınıf, private yardımcı ve iç tipler kaçıyordu | Kural yeniden yazıldı; fixture'lar eklendi | `TransactionBoundaryRulesTest` |
| Migration kuralı boşlukları (D2/D3/A4/T9) | Tırnaklı şema, virgüllü join, `SET SCHEMA`, `OWNER TO`, dinamik SQL; daraltan REVOKE'u yanlışlıkla reddediyordu | Kurallar ve her kural için fixture | M71, fixture'lar |
| Modül listesi dağınık (A5) | 8+ yerde tekrar | `ModuleConsistencyTest` | — |
| Readiness DB'yi görmüyor (E8) | DB kapalıyken container healthy | `readinessState,db`. Bunun `migrate`'i bozduğu temiz yığında yakalandı | `MigrateModeTest`, M79 |
| Backup sağlığı ve sinyal (E7, E9) | Başarısız yedek yalnız logda; durdurma SIGKILL | `last-success` healthcheck, `init: true` | Yerelde healthy gözlendi |
| Config tree `..` (C2/E5) | `../secrets/` 0 property yükledi | Kaldırıldı; IDE depo kökünden çalışır | `ConfigProfilesTest.configTreeImports_*` |
| Compose ↔ config uyumu (E4/E11) | Yeni `${VAR}` yalnız compose'u bozuyordu | — | `ComposeConfigTest`; workflow uygulama değişikliklerinde de koşuyor |
| `.dockerignore` (S5) | İç dizinlerdeki `.env` image'a girebiliyordu | `**/` desenleri | M77 |
| Image pin (S6/T8) | Testcontainers tag ile çekiyordu | Digest ile; servis başına çözülmüş image kontrolü | M72, M73 |
| Rotasyon izi (S3/E10) | `ALTER ROLE ... PASSWORD`, metni `pg_stat_statements`'e yazdı | Prosedür: `track_utility = off`, stdin ile parola | Prosedür denendi, iz yok |
| Test havuzları (C4) | 4 context × 10 bağlantı | Havuz 3, `minimum-idle` 0 | — |

## 4. Bu fazda öğrenilenler (kanıtla)

1. **Windows'taki Docker Desktop iki Linux gerçeğini gizledi.** Birincisi dosya izinleri: bind mount'ta her şey 777 görünüyor; `0600` secret Linux'ta okunamıyor. İkincisi çalıştırma biti: git'e 644 eklenen init script'i Linux'ta source ediliyor. İkisi de ancak Linux CI'da ya da Linux container'ında yeniden üretilince görüldü. Artık `repo-hygiene` testi ve pre-commit bunu commit anında yakalıyor.
2. **Yeniden build edilmemiş bir image yanlış güven verir.** E8 config değişikliği `migrate` modunu bozdu. Yerel yığın eski image'la çalıştığı için yeşildi; CI'daki temiz build'de kırmızıya döndü. Ders: config değişikliğinden sonra yığın yeni bir image etiketiyle sıfırdan kurulur. `MigrateModeTest` artık compose argümanlarını okuyup uygulamayı o modda başlatıyor.
3. **"Kanıt" sorgusu kendisi kanıtlanmalı.** Restore provası yetkileri katalog sorgusuyla, PUBLIC kontrolü `aclexplode(NULL)` ile, Flyway doğrulaması da varsayılan `*:future` ile "kanıtlıyordu". Üçü de hiçbir zaman kırmızı veremezdi. Her biri artık kasıtlı bir bozulmaya karşı kırmızı verdiği gösterilen bir kontrolle değiştirildi.
4. **Gizlilik kuralı uygulama logunda bitmiyor.** PostgreSQL'in `DETAIL` satırı, istatistik görünümü (`pg_stat_statements`) ve parola rotasyonu, uygulama kodundan bağımsız yollardan veri sızdırabiliyordu (S1, S3).
5. **Ayrıcalık ayrımı süreç düzeyinde de gerekli.** İki DB rolü vardı ama ikisinin parolası da aynı container'daydı. Migration'ı tek seferlik bir servise almak, referansın 10.1 gerekçesini gerçekten karşılayan adım oldu (S4).
7. **Doğrulama aracının kendisi de eşzamanlılığa karşı korunmalı.** `&` ile başlattığım bir mutasyon koşusu ölmüş görünüyordu, ama yaşıyordu. İkinci koşuyla aynı ağaçta çakıştı: `.bak` yedekleri birbirini ezdi ve 22 dosya mutasyonlu kaldı. İş commit'li olduğu için ağaç HEAD'den geri alındı; kayıp yok. Script artık `.git/mutation-check.lock` ile ikinci koşuyu reddediyor (exit 3), index'teki çalıştırma bitini her çıkışta geri koyuyor.
6. **Shell üzerinden ters bölü içeren düzenlemeler güvenilmez.** Bu oturumda kaçış karakterleri birkaç kez sessizce değişti. Ters bölü içeren her düzenleme dosya okunup Write/Edit ile yapıldı ve ardından derlendi ya da test edildi.

## 5. Mutasyon ve son CI

- **Mutasyon:** `bash scripts/mutation-check.sh` → **90/90**. Her mutasyon, beklenen test metodunu adıyla kırmızıya çevirdi. Java baseline'ı 160 test, node baseline'ı 6 süit; artık dosya yok, exit 0. Çıktı: `faz-2-mutasyon-ciktisi.txt`.
  - Faz 1'de 66 mutasyon vardı. Faz 2 ekledi: M65–M88 (roller, migration'lar, image'lar, review düzeltmeleri, test review'ının hayatta kalan mutasyonları) ve M30 / M42–M64'ün bu fazda güncellenen biçimleri.
  - Yedek ve prova script'leri bu sayının dışında; onları 13 vakalı öz-test korur (bölüm 1).
- **CI (PR #4):**
  - dfb06b7'de `backend`, `scripts-and-hooks` ve `drill` yeşil. `drill`, temiz checkout'tan image build'i, yığın, öz-test ve prova demek.
  - Son commit'in sonucu PR'ın kendisinde.
  - CI'daki minimum test eşiği 160.
- **İki ara kırmızı CI koşusu ve nedenleri:**
  - Secret izinleri: bölüm 3, ilk satır.
  - Readiness grubunun `migrate` modunu bozması: bölüm 3 ve ders 2.
  - İkisi de Linux'ta yeniden üretilip testle sabitlendi.

## 6. Bilinçli olarak açık bırakılanlar

- **RPO 24 saat ve yedek aynı host'ta.** WAL-G ve host dışı kopya, gerçek müşteri verisiyle (ADR-0009).
- **İkinci modül şeması için DataSource/rol kararı** (A1). İkinci şemadan önce yeni bir ADR gerekiyor; `ModuleConsistencyTest` restore provasının tek modüllü olduğunu açıkça kilitliyor.
- **Tip dönüşüm hatalarının birincil mesajı** girdi değerini içerebilir. Uygulama doğrulaması ve parametreli sorgular nedeniyle kabul edildi (ADR-0009).
- **Init script'inin parolasız rol kontrolü otomatik testte değil.** Bunun için okunamayan bir secret'la container başlatmak gerekiyor; Linux container'ında elle yeniden üretildi.
- **Yedek ve prova script'lerinin mutasyonları** Maven/node mutasyon script'inde değil; Docker yığını gerektirdikleri için öz-test tarafından korunuyorlar.
- **M65 sırasında migration klasöründe bir `.bak` dosyası** oluşuyor. Flyway `.sql` olmayan dosyayı yok saydığı için zararsız (test review T11).
- **`backup.sh`'taki "en yeni yedeği silme" koruması** fiilen ölü kod: prune yalnız başarılı bir yedekten sonra çalışıyor (test review). Savunma amaçlı bırakıldı.
- **Claude Code hook'larının oturum içinde tetiklenmesi** hâlâ kanıtsız. Bu oturum ayarları `C:\verso` dışından yükledi.
