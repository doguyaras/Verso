# repo-context.md — verso Repo Haritası

> Amaç: Ajanın ilk 2 dakikada sistemi anlaması. Kurallar burada değil; `AGENTS.md`, `security-rules.md` ve `llm-rules.md`'dedir. Bu dosya **gerçekleri** taşır ve her yeni modül, uç veya olayda güncellenir. "(planlı)" işaretli satırlar henüz kodda yoktur; kodda karşılığı olmayan satır uydurulmaz.

## 1. Mimari şekil (ADR-0001)

- **Şekil:** **modüler monolit (A).** Tek deploy birimi `verso-app`; domain modülleri Spring Modulith modülleridir ve `*-api`/`*-core` Maven çiftleri olarak tutulur. Profil: **P0**.
- **Modüller:**
  - `platform-observability`, `platform-core`, `platform-security` (OIDC resource server, `@CurrentAccount`; faz 3): kodda var.
  - `document`: kodda var (faz 4, ADR-0011). Yükleme, PDF ayrıştırma, chunk, embedding, ingestion worker; retrieval faz 5.
  - `qa`: planlı, faz 5. Kapsamı prompt, model çağrısı ve atıflar.
- **Yeniden değerlendirme eşiği:** ADR-0001.
- **Repo:** Maven multi-module monorepo; `platform/*` starter'ları, `verso-app`, (planlı) `services/<domain>/<domain>-api|core`.

## 2. Servis kimlik tablosu

| Servis | Port | `application.name` | Actor / `iss` | Audience | DB schema / rol | Hata kodu bloğu | Yayınladığı olaylar | Tükettiği olaylar | Sıcak yol uzak çağrı sayısı |
|---|---|---|---|---|---|---|---|---|---|
| verso-app | 8080 (API), 8081 (actuator) | verso | – (şekil A: servis JWT'si yok) | user JWT `aud` = `verso-api`, `typ` `at+jwt` (ADR-0005, ADR-0010) | `document` / `svc_document`, `svc_document_migrate` | document 10000–10999, qa 11000–11999 | – (ADR-0003) | – | `POST /v1/questions`: **2** (ADR-0008) |

Ortak kod blokları: validation 90000–90099, security 90100–90199 (90100 UNAUTHENTICATED, 90101 ACCESS_DENIED, 90102 TOO_MANY_REQUESTS, 90103 IDP_UNAVAILABLE), system 99998–99999 (`ErrorCodeUniquenessTest`).

## 3. Kritik akış kaydı — sıcak yol tablosu (referans Bölüm 1.2)

| Akış | Uç | Gecikme bütçesi (p99) | Uzak senkron bağımlılıklar (gerekçe) | Read-model / claim ile karşılanan kontroller (kabul edilen eskilik) | Bağımlılık düşünce davranış | Yeniden değerlendirme |
|---|---|---|---|---|---|---|
| Kimlik doğrulama (her kimlikli istek; faz 3) | `/v1/**` | anahtar önbellekteyken 0 ek gecikme; önbellek kaçırılınca ≤ 2 sn (`jwks-timeout`) | 0 istek başına. JWKS bir kontrol düzlemi bağımlılığıdır: anahtarlar 5 dk önbellekte, bilinmeyen `kid` için en fazla 30 sn'de bir yeniden çekilir (restart'sız anahtar rotasyonu) | anahtar seti (5 dk önbellek; IdP kesintisinde son bilinen anahtarlar 1 saat) | önbellek sıcak: kesinti görünmez; soğuk: 503 `IDP_UNAVAILABLE` + `Retry-After: 30` (ADR-0010) | IdP kesintisi 1 saati aşarsa veya rotasyon 30 sn'lik sınıra takılırsa |
| Soru sor (planlı, faz 5) | `POST /v1/questions` | local-GPU 15 sn · local-CPU 60 sn · cloud 20 sn (başlangıç ayarı, ADR-0008) | 2: embedding (soru vektörü; read-model ile yapılamaz) + chat (cevap üretimi). Kimlik doğrulama satırındaki JWKS önbellekli olduğu için sayılmaz (ADR-0010) | sahiplik: token `sub` (token ömrü) | 503 `MODEL_UNAVAILABLE`; eşik altında model çağrılmaz | p99 > bütçe 3 gün; ADR-0008 |
| Belge yükle (faz 4) | `POST /v1/documents` | 2 sn (20 MB sınırı içinde) | 0 (yalnız DB yazımı; işleme asenkron worker'da, ADR-0011) | sahiplik: token `sub` | DB yoksa 503; model kapalıyken yükleme kabul edilir, worker sonra işler | p99 > 2 sn |
| Belge işleme (worker, faz 4) | – (zamanlanmış) | – (asenkron); kira 10 dk, her embedding grubundan sonra yenilenir | 1 grup başına: embedding (Ollama, transaction dışında, 60 sn okuma timeout'u) | – | geçici hata: backoff'lu yeniden deneme (30 sn · 2^n, en çok 10 dk, 5 deneme); sonra `PROCESSING_FAILED` | bekleme süresi > kira (faz 7 alarmı) |

Varsayılan: ≤1 uzak senkron çağrı. Aşan satır ADR + `verso-resilience-review` ister; kayıt alanlarından biri boş olan satır `REQUEST CHANGES`.

## 3.1 Delegasyon matrisi (referans Bölüm 9.2.1)

Yok. Şekil A'da servisler arası çağrı ve `/internal/**` uç yoktur (ADR-0001, ADR-0005). İlk internal uç eklendiğinde bu tablo zorunlu hale gelir.

## 4. Yüksek sinyalli dosyalar

| Konu | Dosya |
|---|---|
| Uygulama girişi ve component scan sınırı | `verso-app/src/main/java/com/verso/VersoApp.java` |
| Hata zarfı, global handler ve hata sayfası | `platform/platform-core/src/main/java/com/verso/platform/core/handler/{GlobalServiceExceptionHandler,EnvelopeErrorController}.java` |
| Hata kodu sözleşmesi | `platform/platform-core/src/main/java/com/verso/platform/core/exception/{ErrorCode,ServiceException,CommonErrorCode}.java` |
| Log sanitizer | `platform/platform-observability/src/main/java/com/verso/platform/observability/logging/SensitiveLogSanitizer.java` |
| Trace id | `platform/platform-observability/src/main/java/com/verso/platform/observability/tracing/{TraceIds,TraceIdFilter}.java` |
| Starter kayıtları | `platform/*/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| Config | `verso-app/src/main/resources/{application.yml,application-local.yml,config/verso.yml}`, `deploy/prod.env.example` |
| Mimari ve tutarlılık testleri | `verso-app/src/test/java/com/verso/{ArchitectureRulesTest,TransactionBoundaryRulesTest,ModuleStructureTest,ErrorCodeUniquenessTest,ConfigDriftTest,ImageVersionsTest}.java` |
| Gerçek DB testleri ve yetki sınırı | `verso-app/src/test/java/com/verso/support/{VersoPostgres,WithVersoPostgres}.java`, `DatabaseRolesTest.java` |
| Mutasyon kanıtı | `scripts/mutation-check.sh`, sonuçlar `docs/evidence/` |
| Yerel git hook'ları | `.githooks/pre-commit` (`git config core.hooksPath .githooks`) |
| Referansa geri bildirim | `docs/reference-feedback.md` |
| LLM kuralları | `docs/ai/llm-rules.md` |
| Kararlar | `docs/adr/`, indeks `docs/decisions.md` |
| CI | `.github/workflows/ci.yml` |
| Migration'lar ve kuralları | `services/document/document-core/src/main/resources/db/migration/document/` (+ `afterMigrate.sql`), `MigrationConventionsTest` |
| Compose, image, Postgres init | `compose.yaml`, `Dockerfile`, `.dockerignore`, `.env.example`, `deploy/postgres/initdb/`, `deploy/compose.local.yaml` |
| Secret dosyaları | `secrets/` (git dışı; `scripts/dev-secrets.sh`; eşleme `secrets/README.md`) |
| Kimlik (resource server, demo IdP) | `platform/platform-security/` (`JwtValidation`, `PlatformSecurityAutoConfiguration`, test-jar `TestIdentityProvider`), `deploy/keycloak/`, `deploy/postgres/initdb/30-keycloak.sh`, `scripts/{auth-smoke,demo-token}.sh` |
| Yedek ve restore provası | `deploy/backup/{backup,restore-check}.sh`, `scripts/restore-drill.sh`, `scripts/restore-drill-selftest.sh`, `.github/workflows/restore-drill.yml` |

## 5. Altyapı

| Bileşen | Sürüm | Not |
|---|---|---|
| Java / Spring Boot / Spring Modulith | 25 / 4.1.1 / 2.1.1 | `docs/versions.md` |
| Spring AI (planlı) | 2.0.x | Boot 4 hattı; 1.x yalnız Boot 3 |
| PostgreSQL + pgvector | 18.6 + 0.8.7 (digest ile pinli) | tek instance; şema + iki rol/modül; `extensions` şeması; ADR-0009 |
| Flyway | 12.4.0 | migration rolüyle; `baseline-on-migrate` kapalı |
| Ollama (planlı) | – | local modda internete kapalı ağda; modeller tek seferlik pull container'ıyla |
| Keycloak (demo IdP) | 26.7.5 (digest ile pinli) | prod modu, kendi `keycloak` veritabanı ve rolü; realm `verso` ES256; yalnız 127.0.0.1:8180 (ADR-0005, ADR-0010) |
| Gözlem (planlı, faz 7) | Alloy → Loki, Prometheus + Alertmanager, Grafana | portlar yalnız 127.0.0.1 |

## 6. Komutlar

```bash
./mvnw -B -ntp verify                                              # tüm testler (JAVA_HOME = JDK 25)
./mvnw -B -ntp -pl verso-app -am test -Dtest=ConfigDriftTest -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false
node --test scripts/flyway-immutability.test.js scripts/config-lint.test.js scripts/review-gate.test.js scripts/repo-hygiene.test.js scripts/keycloak-start.test.js
GITLEAKS=~/.local/bin/gitleaks.exe node --test scripts/gitleaks-check.test.js scripts/pre-commit.test.js
bash scripts/mutation-check.sh                                     # negatif doğrulama (~40 dk); ONLY="M20 M41" tek tek
bash scripts/dev-secrets.sh && docker compose up -d --build --wait  # yığın (ADR-0009)
bash scripts/restore-drill-selftest.sh                             # yedek + restore provası öz-testi (çalışan yığın)
bash scripts/auth-smoke.sh                                         # IdP token + API 401/404 (çalışan yığın)
git config core.hooksPath .githooks                                # klon başına bir kez
node scripts/flyway-immutability.js check --base origin/develop    # pre-commit: check --staged (index)
node scripts/config-lint.js $(git ls-files -- $(grep -v '^#' scripts/config-lint.pathspec))   # liste: config-lint.pathspec
```

## 7. Bu Belgede Özellikle Taşınmayanlar

Secret değerleri, üretim host adları, CI token'ları, kişisel veri örnekleri, gerçek belge içerikleri.

## 8. Net Kanıt Bulunamayan Alanlar

- Sıcak yol bütçeleri ölçülmedi; başlangıç ayarıdır (ADR-0008). Ölçüm faz 5'te.
