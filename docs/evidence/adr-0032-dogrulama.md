# Docling yan konteyneri: doğrulama ve kanıt kaydı (ADR-0032)

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

## Künye

- **Tarih:** 2026-10-24
- **Branch:** `feature/doguyaras-tablo-ayristirma` (`origin/develop` üzerinden; ADR-0028 paylaşımlı bilgi alanları ve ADR-0030 tabanında).
- **Ortam:** Windows 11 + Docker Desktop (16 GB VM, 8 CPU), NVIDIA GPU, JDK 25.0.4.1. Makine aynı anda başka işler de koşturuyordu; bir kez aşırı yük yüzünden yeniden başlatıldı.
- **Bileşenler:** `verso-docling:local` = `quay.io/docling-project/docling-serve-cpu:v1.38.1@sha256:203c2ce8…eebf` + `tessdata_best` `tur` (konteynerin `/version` çıktısı: docling-serve 1.38.1, docling 2.142.1, docling-core 2.104.0, docling-ibm-models 4.0.4, docling-parse 7.23.0). bge-m3:567m, gemma4:e2b.
- **Davranışsal kapsam:** var (retrieval CPU'da, uçtan uca GPU'da). Bir uçtan uca koşu eksik (bölüm 3).

## 1. Ölçüm düzeni

- **Belge setleri:** `SamplePdfsTest -Dverso.samples.variants=<DIR>` aynı 11 kaynak metinden `grid` (çizgili tablo) ve `scanned` (200 dpi gri JPEG, metin katmanı yok) setlerini üretir; `samples` depodaki PDF'lerdir. Soru seti değişmedi (140 soru, 26 `table`).
- **Retrieval:** `RetrievalEvalTest`, Testcontainers PostgreSQL, bge-m3 CPU'daki geçici bir Ollama'da (3 GB, 3 CPU, ana yığının model volume'u salt okunur). Docling açık koşularda yan konteyner `127.0.0.1`'e yayınlanmış geçici bir konteyner (4 GB, 3 CPU, salt okunur kök, `cap_drop: ALL`).
- **Uçtan uca:** `scripts/eval.mjs`, `verso-sx` adlı geçici bir compose projesi: kendi PostgreSQL volume'u, `verso:docling-sx` image'ı, `deploy/compose.docling.yaml`'daki yan konteyner (4 GB, 3 CPU), anahtarlar ana yığının Keycloak'ından. Ana yığının verisine dokunulmadı. Model: önce ana yığının GPU Ollama'sı, o kapanınca geçici bir GPU Ollama.

## 2. Retrieval sonuçları

`eval/results/retrieval-{samples,grid,scanned}-{jdk,docling}.json`. Tablo: `eval/README.md` "Docling".

| Set | recall@5 (JDK → docling) | `table` recall@1 | `table` MRR | Kapıda duran cevaplanamaz | Ayrıştırma, sayfa başına |
|---|---|---|---|---|---|
| samples | 0,982 → 0,982 | 0,731 → 0,731 | 0,827 → 0,830 | 7 → 5 / 28 | 7 ms → 0,67 sn |
| grid | 0,982 → 0,964 | 0,769 → 0,808 | 0,849 → 0,878 | 6 → 5 / 28 | 9 ms → 1,45 sn |
| scanned | 0 → 0,964 | 0 → 0,731 | 0 → 0,824 | – → 4 / 28 | – → 2,6 sn |

- `samples` + JDK, ADR-0022'nin yayımlanmış tabanını birebir verdi (0,795 / 0,982 / 0,877).
- `samples` + docling koşusunda `RetrievalEvalTest`'in "cevaplanamaz ≥ 0,2" koruması tutmadı (0,179). Sidecar varsayılan kapalı kaldığı için koruma değiştirilmedi.
- Bellek (5 sn'de bir `docker stats`): yan konteyner boşta ~1,1 GB, tepe 2,2 GB.

## 3. Uçtan uca sonuçlar

| Set | Ayrıştırıcı | Atıf isabeti | Doğru bilgi | "Bulunamadı" | Kaynak |
|---|---|---|---|---|---|
| samples | JDK, ana Ollama | 0,902 | 0,902 | 0,536 | koşu günlüğü (dosyanın üzerine ikinci koşu yazıldı) |
| samples | JDK, geçici Ollama | 0,911 | 0,884 | 0,607 | `e2e-samples-jdk.json` (`e2e-gemma4_e2b-gpu.json` ile aynı) |
| samples | docling, ana Ollama | 0,884 | 0,893 | 0,643 | `e2e-samples-docling.json` |
| grid | docling, ana Ollama | 0,848 | 0,848 | 0,679 | `e2e-grid-docling.json` (1 soru 503) |
| scanned | docling, ana Ollama | 0,884 | 0,848 | 0,643 | `e2e-scanned-docling.json` |

- **Eksik ölçüm: `grid` + JDK uçtan uca.** Koşu iki kez başlatıldı, ikisinde de ana yığının Ollama'sı koşu sırasında kapandı (başka bir işin elinde, `Exited (137)`): 140 sorunun 74'ü, sonra 97'si `503 EMBEDDING_MODEL_UNAVAILABLE` aldı. Sonuç dosyası atıldı. Geçici GPU Ollama ile üçüncü deneme başlamıştı; makine aşırı yüklenince Docker kapatıldı ve tekrar koşulmadı.
- `scanned` + JDK uçtan uca koşulmadı: 11 belgenin hepsi `NO_TEXT` ile FAILED olur (retrieval koşusu bunu gösterdi), cevap verilecek belge yoktur.
- İki Ollama örneği aynı sette 0,01–0,07 fark verdi; uçtan uca farkları yön gösterir.

## 4. Yapısal doğrulama

| Kontrol | Kanıt | Sonuç |
|---|---|---|
| Yan konteynerin dışarı çıkışı yok | `verso-sx-docling-1` içinden: `urlopen('https://huggingface.co')` → `Name or service not known`; `connect(('1.1.1.1', 443))` → `Network is unreachable`; ağ yalnız `verso-sx_parsers` (internal), port bağlaması yok, kök salt okunur, bellek 4 GB | PASS |
| Modeller çevrimdışı yükleniyor | `HF_HUB_OFFLINE=1` ile açılış, `artifacts_path is set ... No model weights will be downloaded at runtime` | PASS |
| docling-serve Tesseract kodu ister | `ocr_lang=tr` → `TesseractOcr has no model for the OCR language 'tr'`; `tur`, `eng` çalışıyor | PASS (ayar Tesseract kodu doğrular) |
| Alarm | `promtool test rules alerts.test.yml` → `SUCCESS` (yeni `DoclingFallback`, iki senaryo) | PASS |
| Docker'sız testler (rebase sonrası) | `document-core`: 102 test, 0 hata; `verso-app`: `ConfigDriftTest`, `ImageVersionsTest`, `ComposeConfigTest`, `SamplePdfsTest`, `ArchitectureRulesTest`, `TransactionBoundaryRulesTest`, `ModuleStructureTest`, `ErrorCodeUniquenessTest`, `ConfigProfilesTest`: 44 test, 0 hata | PASS |
| Testcontainers'lı testler | `DoclingIngestionTest` (3) rebase öncesi yeşildi; rebase sonrası tam paket GitHub CI'da koşar (Docker kapalıydı) | CI'da doğrulanacak |
| Node testleri | `node --test scripts/*.test.js scripts/panel.test.mjs`: 179 test, 178 geçti, 0 hata (1 atlandı) | PASS |

## 5. Review skill'leri

| Skill | Karar | Not |
|---|---|---|
| `verso-security-review` | APPROVE WITH NON-BLOCKING COMMENTS | B1 yanıt sınırı 32 → 16 MB (düzeltildi); B2 docling-serve'de API anahtarı yok, yalnız uygulama `parsers` ağında; B3 yan konteyner kendi log'una sabit dosya adı ve md5 öneki yazar, Alloy bu log'u toplamaz |
| `verso-llm-review` | APPROVE WITH NON-BLOCKING COMMENTS | Çıkış kanıtı bölüm 4'te; eval bu belgede |
| `verso-resilience-review` | APPROVE WITH NON-BLOCKING COMMENTS | Kalıcı geri dönüş için alarm ve runbook eklendi (`DoclingFallback`); 30 sn'lik tek çağrı 25 sn'lik kapanış aşamasını aşabilir, kira belgeyi devreder |
| `verso-environment-impact-review` | APPROVE WITH NON-BLOCKING COMMENTS | Bütün yüzeyler birlikte; `docker compose -f compose.yaml -f deploy/compose.docling.yaml config -q` temiz |
| `verso-spring-code-review` | APPROVE WITH NON-BLOCKING COMMENTS | Bilinçli geniş `catch (RuntimeException)` (geri dönüş, `exceptionType` log'u); FQN düzeltildi |
| `verso-test-writer` | PASS | Bulkhead (`BUSY`) testi eklendi; testlerin yakaladığı iki kusur düzeltildi (tablo öncesi başlığın ayrı chunk olması, Türkçe harfli test PDF'i) |

## 6. Kalan

- `grid` + JDK uçtan uca ölçümü (bölüm 3).
- Gerçek müşteri belgelerinde çizgili tablo ve taranmış PDF oranı bilinmiyor; varsayılanın yeniden tartılması buna bağlı (ADR-0032).
- Geçici kaynaklar Docker kapalıyken silinemedi: `verso-sx` projesinin konteynerleri, `verso-sx_pgdata` volume'u, `verso-sx-models` ağı ve `verso-sx-ollama` konteyneri.
