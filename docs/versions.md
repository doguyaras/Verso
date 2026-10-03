# versions.md — Sürüm ve Destek Anlık Görüntüsü

> **Tarih:** 2026-10-02 (çeyrekte bir güncellenir; `verso-release-readiness-review` bu tarihi kontrol eder). Kurallar mimari referans Bölüm 25'te; bu dosya yalnız **sayıları ve tarihleri** taşır. Kaynağı olmayan satır yazılmaz.

| Bileşen | Kullanılan sürüm | OSS destek sonu | Kaynak | Not |
|---|---|---|---|---|
| Java (derleme, CI, image) | 25 LTS (Temurin 25.0.4.1) | – (LTS) | endoflife.date/oracle-jdk; adoptium.net API (2026-10-02) | 21 LTS de destekli; referans tek sürüm ister |
| Spring Boot | 4.1.1 | 2027-07-31 | referans Ek A/B (spring.io support policy); Maven Central metadata 2026-10-02 | 3.x OSS 2026-06-30'da bitti |
| Spring Modulith (şekil A) | 2.1.1 (`spring-modulith-api` compile, `spring-modulith-core` test) | – | Maven Central metadata 2026-10-02 | referans: 2.1.1 `withMinAge` hatası (Bölüm 11.2) |
| Spring AI | 2.0.1 (`spring-ai-bom`; `spring-ai-model`, `spring-ai-starter-model-ollama`, `-anthropic`, `-openai`) | – | spring.io/blog 2026-06-12 (2.0.0 GA); Maven Central 2026-10-02 | 2.x Boot 4.0/4.1 ister; 2.1.0-M1 milestone, kullanılmaz |
| Anthropic Java SDK / OpenAI Java SDK | 2.52.0 / 4.49.0 (Spring AI 2.0.1 starter'larının altında; OkHttp 4.12.0, Kotlin stdlib 2.3.21, Jackson 2.21.5 ile) | – | Maven Central 2026-10-03 (Spring AI 2.0.1 bağımlılığı) | yalnız cloud modda kullanılır; kullanıcı onayı bekliyor (ADR-0013) |
| nginx (kenar proxy) | 1.29.8 (`nginxinc/nginx-unprivileged:1.29.8-alpine@sha256:0c79d56a…7fbe6`) | – | Docker Hub 2026-10-03 | root'suz, salt okunur; kullanıcı onayı bekliyor (ADR-0013) |
| Apache PDFBox | 3.0.8 (`pdfbox`, `pdfbox-io`, `fontbox`) | – | Maven Central 2026-10-02 | Apache 2.0; kullanıcı onayı 2026-10-02 (ADR-0011) |
| Ollama | 0.35.1 (`ollama/ollama:0.35.1@sha256:292ee794…278c`) | – | Docker Hub 2026-10-02 | ~3,8 GB; CPU (ADR-0011) |
| ArchUnit | 1.5.1 | – | Maven Central 2026-10-02 | düz `@Test` ile (Ek B ders 2) |
| Maven / Maven Wrapper | 3.9.16 / 3.3.4 | – | Maven Central 2026-10-02 | dağıtım SHA-256'sı `.mvn/wrapper/maven-wrapper.properties`'te |
| maven-compiler / surefire / enforcer / flatten | 3.16.0 / 3.5.6 / 3.6.3 / 1.8.0 | – | Maven Central 2026-10-02 | flatten: `${revision}` çözümü (ADR-0007 #22) |
| PostgreSQL + pgvector | 18.6 + 0.8.7 (`pgvector/pgvector:0.8.7-pg18-trixie@sha256:9d9c9302…2e0`) | 2030-11 | postgresql.org/support/versioning; Docker Hub 2026-10-02 | compose, Testcontainers ve restore provası aynı image (`ImageVersionsTest`) |
| PostgreSQL JDBC / HikariCP | 42.7.13 / 7.0.2 | – | Boot 4.1.1 BOM | |
| Flyway (kütüphane / CLI image) | 12.4.0 / `flyway/flyway:12.4.0@sha256:5be18367…afac` | – | Boot 4.1.1 BOM; Docker Hub 2026-10-02 | CLI yalnız restore provasında `validate` için; kütüphaneyle aynı sürüm |
| Testcontainers | 2.0.5 (`testcontainers-postgresql`) | – | Boot 4.1.1 BOM | `org.testcontainers.postgresql.PostgreSQLContainer` (2.x paketi) |
| Keycloak (demo IdP) | 26.7.5 (`quay.io/keycloak/keycloak:26.7.5@sha256:37dbaf6f…5a85`) | – | quay.io 2026-10-02 | prod modu (`start`), compose'ta; üretimde müşterinin IdP'si (ADR-0010) |
| Spring Security / OAuth2 Resource Server | 7.1.1 | – | Boot 4.1.1 BOM | `spring-boot-starter-oauth2-resource-server`; Nimbus JOSE+JWT BOM'dan |
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

**Bilinen CVE tetikleyicileri** (yaması yalnız ticari sürümde olan → upgrade): yok (2026-10-02).

**Son kontrol komutları:** `./mvnw versions:display-dependency-updates`, Renovate panosu, `osv-scanner`.
