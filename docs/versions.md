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
| Gözlem yığını (`obs` profili) | Prometheus 3.15.0, Alertmanager 0.34.1, Loki 3.7.8, Alloy 1.20.1, Grafana 13.2.3 (hepsi `@sha256` pinli, compose.yaml) | – | Docker Hub 2026-10-03 | Lisans: Prometheus ve Alertmanager Apache 2.0; Loki ve Grafana AGPL-3.0 (değiştirilmeden, ayrı süreç olarak kullanılır); Alloy Apache 2.0. Promtail EOL 2026-03-02, yerine Alloy (referans 25). Kullanıcı onayı bekliyor (ADR-0014) |
| Micrometer Prometheus registry | Boot 4.1.2 BOM | – | Boot 4.1.2 BOM | `/actuator/prometheus` (ADR-0014) |
| Fraunces (başlık fontu) | `@fontsource-variable/fraunces@5.2.9`: `fraunces-latin(-ext)-standard-normal.woff2` ve `fraunces-latin(-ext)-wght-italic.woff2`, `panel/fonts/` ve Keycloak teması `resources/fonts/` (aynı dosyalar) | – | cdn.jsdelivr.net 2026-10-24; SHA-256 `7234ed86…d783`, `a2930b27…f88e`, `bceec2ef…6db7`, `877bcc2f…ca45` | SIL Open Font License 1.1 (The Fraunces Project Authors); lisans metni dosyaların yanında `OFL.txt` (ADR-0018) |
| Allura (imza konturları) | Google Fonts `allura/v22` TTF'inden "Verso" kelimesinin konturları (`panel/js/signature-data.js`) | – | fonts.google.com 2026-10-23 | SIL Open Font License 1.1 (Robert Leuschke). Font dosyası dağıtılmaz ve yüklenmez; yalnız konturlar kullanılır (ADR-0017) |
| Apache PDFBox | 3.0.8 (`pdfbox`, `pdfbox-io`, `fontbox`) | – | Maven Central 2026-10-24 (en son sürüm) | Apache 2.0; kullanıcı onayı 2026-10-02 (ADR-0011) |
| Ollama | 0.35.1 (`ollama/ollama:0.35.1@sha256:292ee794…278c`) | – | Docker Hub 2026-10-02; osv.dev/vulnerability/GHSA-f6mr-38g8-39rg | ~3,8 GB; CPU (ADR-0011). CVE-2025-63389 (kimlik doğrulama yok, CRITICAL) tasarım gereği; yama yok, bu yüzden Ollama yalnız iç ağda (`security-rules.md` 9). 2026-10-24: sürüm notlarında güvenlik düzeltmesi yok, sürüm değişmedi |
| ArchUnit | 1.5.1 | – | Maven Central 2026-10-02 | düz `@Test` ile (Ek B ders 2) |
| Maven / Maven Wrapper | 3.9.16 / 3.3.4 | – | Maven Central 2026-10-02 | dağıtım SHA-256'sı `.mvn/wrapper/maven-wrapper.properties`'te |
| maven-compiler / surefire / enforcer / flatten | 3.16.0 / 3.5.6 / 3.6.3 / 1.8.0 | – | Maven Central 2026-10-02 | flatten: `${revision}` çözümü (ADR-0007 #22) |
| PostgreSQL + pgvector | 18.6 + 0.8.7 (`pgvector/pgvector:0.8.7-pg18-trixie@sha256:9d9c9302…2e0`) | 2030-11 | postgresql.org/support/versioning; Docker Hub 2026-10-24; github.com/pgvector/pgvector CHANGELOG | compose, Testcontainers ve restore provası aynı image (`ImageVersionsTest`) |
| PostgreSQL JDBC / HikariCP | 42.7.14 / 7.0.2 | – | Boot 4.1.2 BOM | |
| Flyway (kütüphane / CLI image) | 12.4.0 / `flyway/flyway:12.4.0@sha256:5be18367…afac` | – | Boot 4.1.2 BOM; Docker Hub 2026-10-02 | CLI yalnız restore provasında `validate` için; kütüphaneyle aynı sürüm |
| Testcontainers | 2.0.5 (`testcontainers-postgresql`) | – | Boot 4.1.2 BOM | `org.testcontainers.postgresql.PostgreSQLContainer` (2.x paketi) |
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
