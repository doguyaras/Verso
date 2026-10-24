# versions.md — Sürüm ve Destek Anlık Görüntüsü

> **Tarih:** 2026-10-24 (çeyrekte bir güncellenir; `verso-release-readiness-review` bu tarihi kontrol eder). Kurallar mimari referans Bölüm 25'te; bu dosya yalnız **sayıları ve tarihleri** taşır. Kaynağı olmayan satır yazılmaz.

| Bileşen | Kullanılan sürüm | OSS destek sonu | Kaynak | Not |
|---|---|---|---|---|
| Java (derleme, CI, image) | 25 LTS (Temurin 25.0.4.1) | – (LTS) | endoflife.date/oracle-jdk; adoptium.net API (2026-10-02) | 21 LTS de destekli; referans tek sürüm ister |
| Spring Boot | 4.1.2 | 2027-07-31 | referans Ek A/B (spring.io support policy); Maven Central 2026-10-24 | 3.x OSS 2026-06-30'da bitti. 4.1.1 → 4.1.2 (2026-10-24, güvenlik): BOM Tomcat 11.0.24 → 11.0.27 (CVE-2026-65905, -65182, -68525 CRITICAL), Jackson 2.21.5/3.1.5 → 2.21.7/3.1.7 (CVE-2026-91777, -91776, -89425, -89407, -68497 HIGH), Spring Security 7.1.2, PostgreSQL JDBC 42.7.14 |
| Spring Modulith (şekil A) | 2.1.1 (`spring-modulith-api` compile, `spring-modulith-core` test) | – | Maven Central metadata 2026-10-02 | referans: 2.1.1 `withMinAge` hatası (Bölüm 11.2) |
| Spring AI | 2.0.1 (`spring-ai-bom`; `spring-ai-model`, `spring-ai-starter-model-ollama`, `-anthropic`, `-openai`) | – | spring.io/blog 2026-06-12 (2.0.0 GA); Maven Central 2026-10-02; spring.io/security/cve-2026-47851, -47852 (2026-10-24) | 2.x Boot 4.0/4.1 ister; 2.1.0-M1 milestone, kullanılmaz. CVE-2026-47851 (PDF reader, HIGH) ve -47852 (ONNX önbelleği, HIGH) 2.0.1'de OSS olarak kapanır, 2.0.2 gerekmez; Verso `spring-ai-pdf-document-reader` kullanmaz |
| Anthropic Java SDK / OpenAI Java SDK | 2.52.0 / 4.49.0 (Spring AI 2.0.1 starter'larının altında; OkHttp 4.12.0, Kotlin stdlib 2.3.21, Jackson 2.21.7 ile) | – | Maven Central 2026-10-03 (Spring AI 2.0.1 bağımlılığı) | yalnız cloud modda kullanılır; kullanıcı onayı bekliyor (ADR-0013) |
| nginx (kenar proxy) | 1.30.6 stable (`nginxinc/nginx-unprivileged:1.30.6-alpine@sha256:f5116ed4…cdf03`) | – | Docker Hub 2026-10-24; nginx.org/en/security_advisories.html | 1.29.8 → 1.30.6 (2026-10-24): 2026 advisory'leri 1.30.6/1.31.7'de kapanır (CVE-2026-42533 map+regex taşması "major"; -15176, -93479, -97629, -97205, -42945, -9256 dahil), 1.29.x için yama yok. | root'suz, salt okunur; kullanıcı onayı bekliyor (ADR-0013) |
| Gözlem yığını (`obs` profili) | Prometheus 3.15.1, Alertmanager 0.34.1, Loki 3.7.9, Alloy 1.21.2, Grafana 13.2.4 (hepsi `@sha256` pinli, compose.yaml) | – | Docker Hub 2026-10-24 (digest'ler `docker buildx imagetools inspect` ile) | 2026-10-24 yükseltmesi (yama sürümleri, kırıcı değişiklik yok; CRITICAL/HIGH önce → sonra): Prometheus v3.15.0 → v3.15.1 6/29 → 0/3 (kalan 3 HIGH düzeltmesiz), Loki 3.7.8 → 3.7.9 3/13 → 0/0, Alloy v1.20.1 → v1.21.2 3/15 → 0/0, Grafana 13.2.3 → 13.2.4 42/207 → 39/190 (Go araç zinciri hâlâ bayat; yukarı akışta daha yeni sürüm yok). Alertmanager v0.34.1 hâlâ en yeni sürüm (6/35 kalır). Akış doğrulandı: Prometheus hedefleri up, Loki hazır ve Alloy'dan log alıyor, Grafana `/api/health` ok. Lisans: Prometheus ve Alertmanager Apache 2.0; Loki ve Grafana AGPL-3.0 (değiştirilmeden, ayrı süreç olarak kullanılır); Alloy Apache 2.0. Promtail EOL 2026-03-02, yerine Alloy (referans 25). Kullanıcı onayı bekliyor (ADR-0014) |
| Micrometer Prometheus registry | Boot 4.1.2 BOM | – | Boot 4.1.2 BOM | `/actuator/prometheus` (ADR-0014) |
| Fraunces (başlık fontu) | `@fontsource-variable/fraunces@5.2.9`: `fraunces-latin(-ext)-standard-normal.woff2` ve `fraunces-latin(-ext)-wght-italic.woff2`, `panel/fonts/` ve Keycloak teması `resources/fonts/` (aynı dosyalar) | – | cdn.jsdelivr.net 2026-10-24; SHA-256 `7234ed86…d783`, `a2930b27…f88e`, `bceec2ef…6db7`, `877bcc2f…ca45` | SIL Open Font License 1.1 (The Fraunces Project Authors); lisans metni dosyaların yanında `OFL.txt` (ADR-0018) |
| Allura (imza konturları) | Google Fonts `allura/v22` TTF'inden "Verso" kelimesinin konturları (`panel/js/signature-data.js`) | – | fonts.google.com 2026-10-23 | SIL Open Font License 1.1 (Robert Leuschke). Font dosyası dağıtılmaz ve yüklenmez; yalnız konturlar kullanılır (ADR-0017) |
| Apache PDFBox | 3.0.8 (`pdfbox`, `pdfbox-io`, `fontbox`) | – | Maven Central 2026-10-24 (en son sürüm) | Apache 2.0; kullanıcı onayı 2026-10-02 (ADR-0011) |
| docling-serve (isteğe bağlı yan konteyner, `deploy/compose.docling.yaml`) | 1.38.1 CPU (`quay.io/docling-project/docling-serve-cpu:v1.38.1@sha256:203c2ce8…eebf`); içinde docling 2.142.1, docling-core 2.104.0, docling-ibm-models 4.0.4, docling-parse 7.23.0, docling-jobkit 3.9.1 | – | github.com/docling-project/docling-serve releases (v1.38.1, 2026-10-23); container `/version` 2026-10-24; github.com/docling-project/docling/security/advisories | MIT (docling, docling-serve, docling-core, docling-parse); model ağırlıkları kendi lisanslarıyla (yerleşim ve TableFormer modelleri `docling-ibm-models`, MIT kod). 23 Ekim 2026'da yayımlanan 10'dan fazla Moderate/Low DoS advisory'si 2.142.1'de kapanır (DOCX/XLSX/EPUB üyelerinin açılmış boyutu, çözülen resim pikseli); `ImageVersionsTest` 1.38.1'in altını reddeder. Image 7,6 GB (amd64, `tur` dahil), RAM boşta ~1,1 GB, ölçülen tepe 2,2 GB. Docling neredeyse her gün sürüm çıkarır: advisory'ler aylık izlenir (ADR-0032) |
| Tesseract Türkçe modeli | `tessdata_best` `tur.traineddata` commit `9ddc24e7…f3b`, SHA-256 `e0c3338d…e438`, 7,1 MB (`deploy/docling/Dockerfile`) | – | github.com/tesseract-ocr/tessdata_best 2026-10-24 | Apache 2.0; Tesseract motoru ve `eng` modeli upstream image'ın işletim sistemi paketlerinden (Apache 2.0) |
| Ollama | 0.40.10 (`ollama/ollama:0.40.10@sha256:4c5b2787…5e35`); canlı yığında doğrulandı (ADR-0033), önceki sürüm 0.35.1 (`@sha256:292ee794…278c`) | – | Docker Hub 2026-10-02; osv.dev/vulnerability/GHSA-f6mr-38g8-39rg | ~3,8 GB; CPU (ADR-0011). CVE-2025-63389 (kimlik doğrulama yok, CRITICAL) tasarım gereği; yama yok, bu yüzden Ollama yalnız iç ağda (`security-rules.md` 9). 2026-10-24: 0.35.1 notlarında güvenlik düzeltmesi yok. **2026-10-24, ADR-0033: 0.40.10'a geçildi ve canlı yığında doğrulandı.** Taşıma `ollama-pull`'da yapılır (`pull.sh` `ollama pull` ile `manifests-v2`'yi yazar, digest'i `ollama show --modelfile` ile okur); çalışan Ollama salt okunur ve internetsiz kalır. Volume kopyasında ölçüldü: `manifests-v2` doluyken salt okunur 0.40.10 açıldı, bge-m3 embedding'i 0.35.1 ile kosinüs 1,000000. Canlı yığında (GPU): `ollama-pull` tamam, salt okunur bağlamada healthy, embedding kosinüsü 1,000000, `gemma4:e2b` chat 200, `qa-smoke` ve `prove-local-mode` OK. Tarama 10 / 53 / 194 → 0 / 0 / 83, Ollama'nın id'ye özel allowlist kayıtları silindi (yalnız CVE-2025-63389 kaydı kalır). İlk deneme: 0.40.10 geri alındı çünkü 0.40.x model deposunu yeni `manifests-v2` düzenine taşır ve salt okunur `ollama-models` volume'ünde çalışmaz (`mkdir /root/.ollama/models/manifests-v2: read-only file system`; `/api/embed` ve `/api/generate` 500 döner, sağlık denetimi yeşermez). 0.40.2 sürüm notlarına göre eski modeller ilk çalıştırmada arka planda yükseltilir (yedek tutulur). Çözüm (ADR-0033): boş `manifests-v2` sorunu yazılabilir pull aşamasında giderilir, çalışan container'a yazma verilmez. Tarama kazancı büyüktü (CRITICAL/HIGH 10/53 → 0/0, toplam 194 → 83), bu yüzden bu iş açık bir yükseltme adayıdır. Bu arada 0.35.1'in Go bileşenleri allowlist'te id'ye özel yazılıdır |
| ArchUnit | 1.5.1 | – | Maven Central 2026-10-02 | düz `@Test` ile (Ek B ders 2) |
| Maven / Maven Wrapper | 3.9.16 / 3.3.4 | – | Maven Central 2026-10-02 | dağıtım SHA-256'sı `.mvn/wrapper/maven-wrapper.properties`'te |
| maven-compiler / surefire / enforcer / flatten | 3.16.0 / 3.5.6 / 3.6.3 / 1.8.0 | – | Maven Central 2026-10-02 | flatten: `${revision}` çözümü (ADR-0007 #22) |
| PostgreSQL + pgvector | 18.6 + 0.8.7 (`pgvector/pgvector:0.8.7-pg18-trixie@sha256:9d9c9302…2e0`) | 2030-11 | postgresql.org/support/versioning; Docker Hub 2026-10-24; github.com/pgvector/pgvector CHANGELOG | compose, Testcontainers ve restore provası aynı image (`ImageVersionsTest`) |
| PostgreSQL JDBC / HikariCP | 42.7.14 / 7.0.2 | – | Boot 4.1.2 BOM | |
| Flyway (kütüphane / CLI image) | 13.11.1 / `flyway/flyway:13.11.1@sha256:fd082457…88e2` | – | Maven Central metadata ve Docker Hub 2026-10-24; Flyway sürüm notları (13.0.0'da kırıcı değişiklik yok) | CLI yalnız restore provasında `validate` için; kütüphaneyle aynı sürüm (`pom.xml` `flyway.version`; `ImageVersionsTest` eşitliği doğrular). 12.4.0 → 13.11.1 (2026-10-24, güvenlik): CRITICAL/HIGH 2/81 → 0/12, toplam 620 → 109. Boot 4.1.2 BOM Flyway 12.4.0 yönetir; 13.11.1 `dependencyManagement`'ta sabitlendi. Doğrulama: `./mvnw verify` (258 test, migration'lar Testcontainers'ta), 12.4.0 kütüphanesinin yazdığı geçmişe karşı CLI 13.11.1 `validate` (document, qa, audit; aynı bayraklar). Kalan 12 HIGH Flyway'in kendi paketlediği Java kütüphaneleridir (jackson, httpcore5, lz4-java), allowlist'te id'ye özel |
| Testcontainers | 2.0.5 (`testcontainers-postgresql`) | – | Boot 4.1.2 BOM | `org.testcontainers.postgresql.PostgreSQLContainer` (2.x paketi) |
| SBOM ve tarama araçları | CycloneDX Maven eklentisi 2.9.3; Syft v1.55.0 (`ghcr.io/anchore/syft@sha256:4cc92b64…a110a`); Grype v0.121.0 (`ghcr.io/anchore/grype@sha256:621d56d1…f7ea`); cosign v3.1.4 (`sigstore/cosign-installer` v4.1.2) | – | Maven Central metadata ve `git ls-remote` (2026-10-24); digest'ler `docker buildx imagetools inspect` ile | Hepsi Apache 2.0. Syft ve Grype `supply-chain-scan.yml`'de, cosign yalnız `release.yml`'de (ADR-0030) |
| Keycloak (demo IdP) | 26.8.1 (`quay.io/keycloak/keycloak:26.8.1@sha256:a78d6b79…dd6b`) | – | quay.io 2026-10-24; keycloak.org/2026/10/keycloak-2681-released | 26.7.5 → 26.8.1 (2026-10-24, güvenlik): 26.8.1 sürüm notları 18 Keycloak + 9 bağımlılık CVE kapatır (ör. CVE-2026-18569, CVE-2026-94000, jackson CVE-2026-91777); 26.7.x için 26.7.6 yok, yama yalnız 26.8.1'de. Geri dönüş: 26.8 veritabanı şemasını yükseltir, eski image'a dönüş desteklenmez; var olan kurulumda yükseltmeden önce `keycloak` veritabanı yedeklenir. | prod modu (`start`), compose'ta; üretimde müşterinin IdP'si (ADR-0010) |
| Spring Security / OAuth2 Resource Server | 7.1.2 | – | Boot 4.1.2 BOM | `spring-boot-starter-oauth2-resource-server`; Nimbus JOSE+JWT BOM'dan |
| Node (CI script'leri) | 24 LTS | 2028-04-30 | nodejs.org/en/about/eol | |
| gitleaks (CI ve yerel pre-commit) | 8.24.3 | – | github.com/gitleaks/gitleaks releases | güncel 8.30.1; sarmalayıcı testleri 8.24.3 ile doğrulandı (ADR-0007 #15) |
| GitHub Actions | checkout v7.0.1, setup-node v7.0.0, setup-java v6.0.1, upload-artifact v7.0.1 | – | `git ls-remote` ile SHA ↔ tag doğrulandı (2026-10-02) | hepsi en son sürüm |
| Docker base image | `eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:0d623ea1…7602` (build), `-jre-noble@sha256:398f8102…168b` (çalışma) | – | Docker Hub 2026-10-02 | Ubuntu 24.04; JRE image'ında wget/curl yok (healthcheck bash `/dev/tcp`) |

**Lombok:** faz 1'de yok; ilk kullanımda eklenir (ADR-0007 #27).

**Modeller (`llm-rules.md` 8.1):**

| Model | Etiket | Model katmanı digest'i | Boyut | Lisans | Kaynak |
|---|---|---|---|---|---|
| Embedding | `bge-m3:567m` | `sha256:daec91ffb5dd0c27411bd71f29932917c49cf529a641d0168496c3a501e3062c` | 1,16 GB, 1024 boyut | MIT | registry.ollama.ai manifest 2026-10-02; `ollama-pull` doğrular |
| Chat (varsayılan) | `gemma4:e2b` | `sha256:6dd7ac24fe13a238898e16e253ebc7a72fa27fb83f2658c2f6c241df3af5937d` | 3,5 GB | Gemma kullanım şartları | registry.ollama.ai manifest 2026-10-03; `ollama-pull` doğrular |
| Chat (yalnız CI) | `qwen3:0.6b` | `sha256:7f4030143c1c477224c5434f8272c662a8b042079a0a584f0a27a1684fe2e1fa` | 523 MB | Apache 2.0 | registry.ollama.ai manifest 2026-10-03 |

**Bilinen CVE tetikleyicileri** (yaması yalnız ticari sürümde olan → upgrade): 2026-10-24: Spring AI 2.0.1 (CVE-2026-47851/-47852; 2.0.0.1 ticari, 2.0.1 OSS) zaten kullanılıyor; pgvector 0.8.7 (CVE-2026-3172, -18022, -103484) zaten kullanılıyor; Keycloak 26.8.1, nginx 1.30.6 ve Boot 4.1.2 bu tarihte yükseltildi (yukarıdaki satırlar). OSV taraması (182 çalışma zamanı bağımlılığı, 2026-10-24): Boot 4.1.2 sonrası bilinen HIGH/CRITICAL yok.

**Son kontrol komutları:** `./mvnw versions:display-dependency-updates`, Renovate panosu, `osv-scanner`.

## Aylık SBOM ve zafiyet taraması rutini (ADR-0030)

Her ayın 1'inde `supply-chain-scan` iş akışı kendiliğinden koşar (elle: Actions → `supply-chain-scan` → Run workflow). Java derlemesinin ve compose'taki 11 imajın CycloneDX SBOM'unu üretir (artifact, 90 gün), Grype ile tarar; CRITICAL ya da düzeltmesi olan HIGH bulgu işi kırar. Kırmızı koşu **zorunlu kontrol değildir**: bir iş kaydıdır, birleştirmeyi engellemez; `backend` ve `scripts-and-hooks` aynen kalır. Adımlar: [`docs/runbooks/aylik-guvenlik-taramasi.md`](runbooks/aylik-guvenlik-taramasi.md). Bu dosyadaki tarih ve satırlar, bir bulgu yüzünden sürüm değiştikçe CVE kimliğiyle güncellenir.

**İlk tarama (2026-10-24, `develop`):**

| Hedef | Bileşen | Bulgu (CRITICAL / HIGH / toplam) | Kapı |
|---|---|---|---|
| Java derlemesi (compile + runtime) | 192 | 0 / 0 / 0 | geçti, istisna yok |
| `eclipse-temurin` JRE (Dockerfile çalışma tabanı) | 3881 | 0 / 0 / 87 (düşük ve orta) | geçti, istisna yok |
| Keycloak 26.8.1 | 4577 | 1 / 1 / 92 | allowlist: CVE-2026-114406 (ca-certificates, düzeltme yok) |
| nginx 1.30.6-alpine | 1282 | 0 / 2 / 7 | allowlist: CVE-2026-85091 (zlib) |
| pgvector 0.8.7-pg18-trixie | 7482 | 4 / 118 / 370 | allowlist: gosu'nun Go stdlib'i, libxml2, libpcre2 (pinli digest bu etiketin en yenisi) |
| Ollama 0.35.1 | 2920 | 10 / 53 / 194 | allowlist: Go stdlib ve x/ modülleri; CVE-2025-63389 kaydı (Grype bu kimliği eşlemiyor) |
| Prometheus v3.15.0 / Alertmanager v0.34.1 / Loki 3.7.8 / Alloy v1.20.1 / Grafana 13.2.3 | 444 / 234 / 1321 / 4013 / 2689 | 6/29, 6/35, 3/13, 3/15, 42/207 | allowlist: paket genelinde, 2026-12-15'e kadar |
| Flyway CLI 12.4.0 | 4691 | 2 / 81 / 620 | allowlist: görüntü genelinde (yalnız `drill` profili), 2026-12-15'e kadar |

İstisnasız yalnız Java derlemesi ve Temurin geçti; kalan dokuz imaj gerçek bulgu taşıyordu ve `.github/vuln-allowlist.yaml` kayıtlarıyla örtülüydü.

**İmaj yükseltmesi sonrası (2026-10-24, aynı sabitlenmiş Syft v1.55.0 / Grype v0.121.0 konteynerleriyle yeniden tarandı, `scripts/vuln-gate.js` ile geçildi):**

| İmaj | Önce → sonra | CRITICAL / HIGH / toplam (önce → sonra) | Allowlist |
|---|---|---|---|
| Prometheus | v3.15.0 → v3.15.1 | 6 / 29 / ? → 0 / 3 / 5 | kayıt silindi (kapı kayıtsız geçer) |
| Loki | 3.7.8 → 3.7.9 | 3 / 13 / ? → 0 / 0 / 2 | kayıt silindi |
| Alloy | v1.20.1 → v1.21.2 | 3 / 15 / ? → 0 / 0 / 76 | kayıt silindi |
| Grafana | 13.2.3 → 13.2.4 | 42 / 207 / ? → 39 / 190 / 300 | paket geneli kayıt yerine 23 id'ye özel kayıt (Go stdlib, x/net, x/crypto, grpc, tempo, kin-openapi, zlib) |
| Flyway CLI | 12.4.0 → 13.11.1 | 2 / 81 / 620 → 0 / 12 / 109 | paket geneli kayıt yerine 8 id'ye özel kayıt (jackson, httpcore5, lz4-java) |
| Ollama | 0.35.1 → 0.40.10 (ADR-0033) | 10 / 53 / 194 → 0 / 0 / 83 | 55 id'ye özel kayıt silindi; yalnız CVE-2025-63389 (ve takma adı) kaldı |
| Alertmanager | v0.34.1 (en yeni) | 6 / 35 / 57 (değişmedi) | paket geneli kayıt yerine 14 id'ye özel kayıt |
| pgvector | 0.8.7-pg18-trixie (yeni digest yok) | 4 / 118 / 370 (değişmedi) | gosu'nun `stdlib` kaydı 35 id'ye özel kayıt; libxml2 ve libpcre2 aynen |
| nginx, Keycloak | değişmedi (aynı etiketin yeni digest'i yok; nginx 1.31.x anadalı bilerek alınmadı, stable hattı kalır) | – | aynen |

**Aylık tarama sonrası (2026-10-24, yeni danışma notları; sürüm değişmedi):** Üç imajın da en yeni sürümü/digest'i zaten sabitli (Keycloak 26.8.1, Grafana 13.2.4, nginx 1.30.6-alpine; quay.io ve Docker Hub'da daha yeni yama etiketi ya da yeniden derlenmiş digest yok), bu yüzden yükseltme yerine id'ye özel, 2026-12-15'te biten kayıt eklendi: Keycloak `java-21-openjdk-headless` CVE-2026-91403 (CRITICAL, Red Hat düzeltmesi yok; eMudhra zayıf ara CA, realm'de dış IdP/SMTP yok), nginx ve Grafana `brotli-libs` CVE-2026-111497 (CRITICAL; Alpine 1.2.1-r0 düzeltmesi 2026-10-24'te çıktı, imajlar henüz yeniden derlenmedi, brotli modülü yüklü değil). Sonraki taramada yeni digest varsa bu kayıtlar silinir.

Hiçbir paket geneli (`id: "*"`) kayıt kalmadı: yeni çıkan bir danışma notu kapıyı kırar. Id'ye özel kayıtlar Grype raporlarından üretildi (paket başına bir kayıt), 2027-01-31'e kadar sürer; yukarı akışta düzeltme çıktıkça silinir.

**Sonraki yükseltme adayları:** Grafana ve Alertmanager (yukarı akışta yeni sürüm çıkınca), pgvector ve nginx (aynı etiketin yeni digest'i ya da nginx 1.31.x anadalı).
