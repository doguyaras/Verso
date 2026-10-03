# Faz 4: Doğrulama ve kanıt kaydı

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

- **Tarih:** 2026-10-03
- **Ortam:** Windows 11 + Docker Desktop (16 GB, 8 CPU); GitHub Actions `ubuntu-latest`. Temurin 25.0.4.1, Maven 3.9.16, Node 24.18, gitleaks 8.24.3, Ollama 0.35.1, bge-m3:567m.
- **Kapsam:**
  - `/v1/documents` (yükleme, liste, okuma, silme).
  - PDFBox 3.0.8 ile sınırlı ayrıştırma.
  - Sayfa içi chunking.
  - DB claim'li ingestion worker (kira, `claim_token`, backoff, zehirli dosya, model devre kesici, kapanışta bırakma).
  - Yerel bge-m3 embedding.
  - V1 şeması.
  - Compose'ta Ollama (iç ağ) ve digest doğrulamalı model indirme.
  - Kararlar: ADR-0011, ADR-0007 #52a, #53–#55, ADR-0002 güncellemesi.
- **Davranışsal kapsam (seviye 2–3):** var. Uygulama testleri gerçek PostgreSQL + pgvector ile, deterministik test modelleriyle koşar. Yığın gerçek Keycloak token'ı ve gerçek Ollama modeliyle uçtan uca denendi.

## 1. Makine kontrolleri

| Kontrol | Komut | Sonuç |
|---|---|---|
| Build ve testler | `./mvnw -B -ntp verify` | **PASS**: 279 Java testi (faz 3 sonunda 194) |
| Script ve hook testleri | `node --test scripts/*.test.js` | **PASS**: 8 suite (ollama-pull ve keycloak-start dahil) |
| Uçtan uca ingestion | `bash scripts/ingest-smoke.sh` | **PASS**: ~4 sn'de READY, 2 sayfa, 2 chunk. Veritabanında PDF yok; vektörler 1024 boyutlu ve `bge-m3:567m` adlı. Silmeden sonra sayfa ve chunk kalmıyor |
| Kimlik smoke | `bash scripts/auth-smoke.sh` | **PASS** |
| Restore provası öz-testi | `bash scripts/restore-drill-selftest.sh` | **PASS**, 13 vaka, vektör verisiyle |
| Mutasyon | `bash scripts/mutation-check.sh` | Bölüm 5 |

## 2. Canlı denemeler

| Deneme | Sonuç |
|---|---|
| Ollama ağ izolasyonu | **PASS**: container'dan `1.1.1.1:443` "Network is unreachable", DNS çözümü yok. Uygulama Ollama'ya `models` iç ağından ulaşıyor |
| Faz 3 yığınından geçiş | **PASS**: yeni volume ve servisler otomatik kuruldu; V1 değişikliği sonrası yerel şema bir kez sıfırlandı (V1 henüz yayınlanmamıştı) |
| Model indirme | **PASS**: 1,2 GB indirildi, digest doğrulandı; ikinci açılış indirme yapmadı |

## 3. Review'lar

Dokuz skill, beş ajanda, `cc9d9eb` üzerinde koştu.

| Skill | Karar | Öne çıkan bulgular |
|---|---|---|
| `verso-security-review` | REQUEST CHANGES | S1 (HIGH): 10–22 KB'lık PDF JVM'i OOM ile kapatıyordu. S2 (HIGH): 20 paralel yükleme JVM'i kapattı. S3: kuyrukta hesaplar arası adalet. S4: Ollama kaynak sınırları |
| `verso-llm-review` | APPROVE WITH NON-BLOCKING COMMENTS | L1: Spring AI retry ayarı etkisiz. L2: model adı tek kaynaktan değil. L3: Ollama'nın internet erişimi |
| `verso-spring-code-review` | REQUEST CHANGES | C1 (HIGH): metin bombası OOM. C2: deflate. C3: kapanış. C4: DB yokken 500. C5–C8 |
| `verso-architecture-boundary-review` | REQUEST CHANGES | A1: modül başına mimari test yok. A2: NamedInterface negatif testi yok |
| `verso-test-writer` | REQUEST CHANGES | T1–T3 (HIGH): yükleme log gizliliği, kota, eşzamanlı idempotency testleri yoktu. T4–T19 |
| `verso-db-migration-review` | REQUEST CHANGES | D1 (HIGH): restore öz-testi yeni tablolarla kırılacaktı. D2: silme kilit zaman aşımına takılıyordu. D3–D6 |
| `verso-api-contract-review` | APPROVE WITH NON-BLOCKING COMMENTS | P1: istemci entegrasyon dokümanı. P2: hiç dönmeyen 10012. P3–P6 |
| `verso-environment-impact-review` | APPROVE WITH NON-BLOCKING COMMENTS | E1: uygulama Ollama'yı bekliyordu. E2: yükleme belleği. E3–E8 |
| `verso-resilience-review` | REQUEST CHANGES | R1 (HIGH): model kesintisi belgeleri kalıcı FAILED yapıyordu. R2: yanlış yapılandırma deneme yakıyordu. R3: kapanış. R4–R8 |

## 4. Bulgu → düzeltme

| Bulgu | Kanıt (önce) | Düzeltme | Kalıcı kontrol |
|---|---|---|---|
| PDF bombaları (S1/C1/C2) | 21 KB glif, 10 KB `q`, deflate → OOM | Toplanırken glif bütçesi; operatör ve grafik yığını sınırı; ayrıştırmadan önce bayt sınırlı inflater ile içerik ölçümü; ölçülemeyen kodlama `UNSUPPORTED_PDF` | `PdfTextExtractorTest` (dört bomba), M141–M144 |
| Paralel yükleme OOM (S2/E2) | 20 × 20 MB → JVM çıktı | Instance başına 4 yükleme, 503 + `Retry-After`, multipart sınırlayıcıdan sonra okunur | `UploadLimiterTest`, M153 |
| Model kesintisi belge kaybettiriyordu (R1/R2) | 7,5 dk kesinti → FAILED, sayfalar silindi | Devre kesici: deneme harcamadan geri bırak, 30 sn / 5 dk duraklama | `IngestionWorkerTest` (kesinti, yanlış yapılandırma), M146–M151 |
| Kapanış (C3/R3) | Belge 10 dk PROCESSING kaldı, deneme yandı | `SmartLifecycle`; gruplar arasında durur, cezasız bırakır | `runOnce_whenShutdownBeginsDuringEmbedding_*`, M149 |
| DB yokken 500 (C4/R4/D2) | `CannotGetJdbcConnectionException` → 500; silme `lock_timeout`'a takıldı | 503 `SERVICE_UNAVAILABLE` (99997) + `Retry-After`; silme 15 sn bekler | `DatabaseUnavailableTest`, `delete_whenTheRowIsLockedForSeconds_*`, M140, M152, M163 |
| Restore öz-testi (D1) | Sabit `app_readable_tables=1` | Manifest'ten hesaplanır | Öz-test 13/13 |
| Ollama internete açık (E5/L3) | Varsayılan ağ, `OLLAMA_REMOTES` | `internal: true` ağ, `OLLAMA_NO_CLOUD`, salt okunur model | `ComposeConfigTest.ollama_*`, M159; canlı deneme |
| Kota ve kuyruk (C6/S3) | Check-then-act; tek hesap kuyruğu dolduruyordu | Advisory lock; hesap başına 20 bekleyen belge (429) | `DocumentLimitsTest`, M137–M139 |
| Test açıkları (T1–T19) | Mutasyonlar yeşil kalıyordu | Eşzamanlı idempotency, kira yenileme, token korumaları, CHECK ve index'ler, SKIP LOCKED, backoff, magic-byte sınırı, Cache-Control, başlatma kontrolleri | `IngestionJobTest`, `DocumentApiTest`, `IngestionBackoffTest`, `DocumentPropertiesTest` |
| Mimari (A1/A2) | Modül testi ve negatif fixture yoktu | `DocumentArchitectureTest`; `fixturegamma`/`fixturedelta` | `ModuleStructureTest` |

## 5. Mutasyonlar

`bash scripts/mutation-check.sh`: baseline yeşil (261 Java testi, 8 node suite), **161/164 yakalandı**. Yaşayan üçünün nedeni testler değil, mutasyon tanımlarıydı:

- **M36:** validation starter artık `document-core` üzerinden de geliyordu.
- **M119:** yeni eklenen bir döngüyü hedefliyordu.
- **M157:** silinen satır davranışı değiştirmiyordu.

Üçü düzeltildi; yeni M163 ile birlikte dört mutasyon yeniden koşuldu, dördü de yakalandı (baseline 279 test). Faz 4 toplamı: **165/165**. Çıktı: [`faz-4-mutasyon-ciktisi.txt`](faz-4-mutasyon-ciktisi.txt).

Not: `parser-memory` (PDFBox akış önbelleği) `byte[]`'tan yüklenen belgede devreye girmiyor. Bunu varsayan bir test yazıldı, kırmızı oldu ve kaldırıldı. Bellek koruması glif, içerik ve operatör sınırlarındadır (ADR-0011'de yazılı).

## 6. CI

CI_RESULTS
