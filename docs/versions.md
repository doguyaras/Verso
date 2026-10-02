# versions.md — Sürüm ve Destek Anlık Görüntüsü

> **Tarih:** 2026-10-02 (çeyrekte bir güncellenir; `verso-release-readiness-review` bu tarihi kontrol eder). Kurallar mimari referans Bölüm 25'te; bu dosya yalnız **sayıları ve tarihleri** taşır. Kaynağı olmayan satır yazılmaz.

| Bileşen | Kullanılan sürüm | OSS destek sonu | Kaynak | Not |
|---|---|---|---|---|
| Java (derleme, CI, image) | 25 LTS (Temurin 25.0.4.1) | – (LTS) | endoflife.date/oracle-jdk; adoptium.net API (2026-10-02) | 21 LTS de destekli; referans tek sürüm ister |
| Spring Boot | 4.1.1 | 2027-07-31 | referans Ek A/B (spring.io support policy); Maven Central metadata 2026-10-02 | 3.x OSS 2026-06-30'da bitti |
| Spring Modulith (şekil A) | 2.1.1 (`spring-modulith-api` compile, `spring-modulith-core` test) | – | Maven Central metadata 2026-10-02 | referans: 2.1.1 `withMinAge` hatası (Bölüm 11.2) |
| Spring AI (planlı, faz 4) | 2.0.1 | – | spring.io/blog 2026-06-12 (2.0.0 GA); Maven Central 2026-10-02 | 2.x Boot 4.0/4.1 ister; 2.1.0-M1 milestone, kullanılmaz |
| ArchUnit | 1.5.1 | – | Maven Central 2026-10-02 | düz `@Test` ile (Ek B ders 2) |
| Maven / Maven Wrapper | 3.9.16 / 3.3.4 | – | Maven Central 2026-10-02 | dağıtım SHA-256'sı `.mvn/wrapper/maven-wrapper.properties`'te |
| maven-compiler / surefire / enforcer / flatten | 3.16.0 / 3.5.6 / 3.6.3 / 1.8.0 | – | Maven Central 2026-10-02 | flatten: `${revision}` çözümü (ADR-0007 #22) |
| PostgreSQL + pgvector (planlı, faz 2) | 18 + 0.8.x (`pgvector/pgvector:pg18`) | 2030-11 | postgresql.org/support/versioning; hub.docker.com/r/pgvector/pgvector | |
| Testcontainers (planlı, faz 2) | 2.0.x | – | Maven Central 2026-10-02 (2.0.5) | `testcontainers-` önekli artefaktlar |
| Node (CI script'leri) | 24 LTS | 2028-04-30 | nodejs.org/en/about/eol | |
| gitleaks (CI ve yerel pre-commit) | 8.24.3 | – | github.com/gitleaks/gitleaks releases | güncel 8.30.1; sarmalayıcı testleri 8.24.3 ile doğrulandı (ADR-0007 #15) |
| GitHub Actions | checkout v7.0.1, setup-node v7.0.0, setup-java v6.0.1, upload-artifact v7.0.1 | – | `git ls-remote` ile SHA ↔ tag doğrulandı (2026-10-02) | hepsi en son sürüm |
| Docker base image (planlı, faz 2) | eclipse-temurin:25-jre | – | hub.docker.com | |

**Lombok:** faz 1'de yok; ilk kullanımda eklenir (ADR-0007 #27).

**Modeller (planlı, `llm-rules.md` 8.1):** embedding `bge-m3` (1024 boyut); chat varsayılanı bir Gemma 4 küçük modeli. Ollama etiketi, digest ve lisans faz 5'te eklenir.

**Bilinen CVE tetikleyicileri** (yaması yalnız ticari sürümde olan → upgrade): yok (2026-10-02).

**Son kontrol komutları:** `./mvnw versions:display-dependency-updates`, Renovate panosu, `osv-scanner`.
