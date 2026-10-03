# ADR-0014: Gözlem yığını (metrik, log, alarm)

- **Durum:** Kabul edildi (faz 7). Yeni container image'ları kullanıcı onayı bekliyor (aşağıda).
- **Tarih:** 2026-10-03
- **Karar verenler:** doguyaras (referans Bölüm 8.7 ve P0 profili); uygulama ayrıntıları faz 7
- **İlgili eşik (referans Bölüm 24):** alarm eşikleri başlangıç değeridir; faz 8 yük testi ölçer.

## Bağlam

Referans P0 profili ilk deploy'dan önce şunları ister:

- yapılandırılmış log → Alloy → Loki;
- Prometheus;
- Alertmanager ve bir kanal;
- 5 temel alarm.

ADR-0007 #28 actuator'ın `prometheus` ucunu bu faza bıraktı. Faz 6 sonunda uygulamanın tek gözlem kaynağı JSON log'larıydı ve kimse alarm almıyordu.

## Seçenekler

| Konu | Seçenekler | Karar |
|---|---|---|
| Yığın her zaman mı açık? | Temel compose'un parçası · `obs` profili | **`obs` profili** (`docker compose --profile obs up`). Temel yığın zaten ~10,6 GB; gözlem ~1,6 GB ekler ve demo için şart değil. CI her koşuda profille açar |
| Scrape kimliği | Token (Keycloak client credentials) · token'sız | **Token'sız, yalnız management portunda.** Port hiçbir zaman yayınlanmaz, uygulama ve Prometheus iç ağdadır. Metrikler hesap, id ya da içerik taşımaz (testli). Token'lı scrape ayrı bir IdP client'ı ve secret ister; tek host'ta karşılığı yok |
| Log toplama | Uygulamadan doğrudan push (OTLP) · Alloy + Docker log'u | **Alloy + Docker log'u** (referans). Uygulama yalnız stdout'a yazar; gözlem yığını kapalıyken uygulamada hiçbir şey değişmez |
| Tracing (Tempo) | Şimdi · sonra | **Sonra** (referans P1): tek servis, tek host; trace id log'da ve Loki'de structured metadata olarak var |
| Exporter'lar (node, cAdvisor, postgres) | Şimdi · sonra | **Sonra:** Windows/Docker Desktop'ta node-exporter ve cAdvisor host'u göremez; postgres-exporter ayrı bir izleme rolü ister |

## Karar

**Metrikler (`/actuator/prometheus`, Micrometer):**

| Metrik | Tür | Etiket | Ne için |
|---|---|---|---|
| `verso_ingestion_queue` | gauge | `status` (pending, processing) | Kuyruk boyu |
| `verso_ingestion_oldest_due_wait_seconds` | gauge | – | Alarm `IngestionBacklog` |
| `verso_ingestion_documents_total` | counter | `outcome` (ready, failed, retry, released, lost) | İşleme sonucu |
| `verso_ingestion_paused` | gauge | – | Alarm `ModelUnavailable` |
| `verso_qa_questions_total` | counter | `outcome` (answered, uncited, not_found, model_unavailable, model_busy, circuit_open, retrieval_failed) | Soru sonucu |
| `verso_qa_chat_duration_seconds`, `verso_qa_retrieval_duration_seconds` | histogram | – | Alarm `QaSlow` (p95) |
| `verso_qa_circuit_open` | gauge | – | Alarm `ModelUnavailable` |
| `http_server_requests_seconds` | histogram (Boot) | `uri` şablonu, `status` | Alarm `VersoHighErrorRate` |

- Kuyruk, scrape anında en çok 5 sn'de bir okunur. Veritabanı yoksa değer NaN olur; boş kuyruk sanılmaz.

**Alarmlar (`deploy/obs/prometheus/alerts.yml`):**

| Alarm | Koşul | Önem |
|---|---|---|
| `VersoDown` | `up == 0`, 2 dk | page |
| `VersoHighErrorRate` | 5xx oranı > %5, 10 dk | page |
| `ModelUnavailable` | ingestion duraklaması ya da devre kesici 10 dk'nın yarısından fazlasında açık | page |
| `IngestionBacklog` | en eski bekleyen > 10 dk (kira süresi), 5 dk | ticket |
| `QaSlow` | chat p95 > 60 sn (ADR-0008), 15 dk | ticket |

- Her alarmın `docs/runbooks/` altında bir runbook'u vardır.
- `VersoDown`, ondan doğan alarmları susturur.

**Bileşenler (hepsi digest pinli, salt okunur, yetkisiz, `obs` iç ağında):**

| Servis | Image | Bellek | Not |
|---|---|---|---|
| prometheus | `prom/prometheus:v3.15.0` | 512 MB | Ayrıca `backend` ağında (uygulamayı scrape eder). 15 gün saklar |
| alertmanager | `prom/alertmanager:v0.34.1` | 64 MB | Kanal yok (aşağıda) |
| loki | `grafana/loki:3.7.8` | 512 MB | Dosya sistemi, schema v13, 7 gün |
| alloy | `grafana/alloy:v1.20.1` | 256 MB | Docker soketi salt okunur. Etiketler yalnız `service` ve `level`; trace id structured metadata'dır |
| grafana | `grafana/grafana:13.2.3` | 256 MB | `127.0.0.1:3000`. Datasource uid'leri sabit, "Verso — genel bakış" panosu. Dışarıya çağrısı kapalı (güncelleme, haber, gravatar, eklenti ön kurulumu, analitik) |

**Kanıt:**

- `MetricsTest`:
  - Token'sız scrape yalnız management portunda çalışır; diğer actuator uçları token ister.
  - Sayaçlar ve kuyruk doğru sayar.
  - Metriklerde hesap, belge id'si, dosya adı ya da metin yoktur.
- `ComposeConfigTest`: profil, ağlar, yayınlanan portlar, Grafana ayarları; her alarmın önemi ve var olan runbook'u.
- `scripts/obs-smoke.sh` (CI restore-drill):
  - Prometheus uygulamayı scrape eder ve 5 kuralı yüklemiştir; Alertmanager bağlıdır.
  - Grafana datasource'ları sağlıklıdır ve pano yüklüdür.
  - Loki'de uygulamanın log'ları vardır, ama `qa-smoke`'un sorduğu soruların metni hiçbir log satırında yoktur.

## Onay bekleyenler (kullanıcı kuralı: yığın dışı bileşen)

| Bileşen | Neden |
|---|---|
| `io.micrometer:micrometer-registry-prometheus` | `/actuator/prometheus` (Boot BOM sürümü) |
| Prometheus, Alertmanager, Loki, Alloy, Grafana image'ları | Referans 8.7 P0 gözlem yığını. Yalnız `obs` profilinde çalışır |
| Bildirim kanalı | **Kullanıcıdan bilgi bekliyor:** Slack, Telegram, e-posta ya da webhook. Bağlanana kadar alarmlar yalnız arayüzde görünür |

## Sonuçlar

- **Olumlu:**
  - Beş temel durum (çöküş, hata oranı, model, kuyruk, yavaşlık) alarm ve runbook ile kapsanıyor.
  - Log'ların içerik taşımadığı artık Loki üzerinde uçtan uca test ediliyor.
- **Olumsuz / kabul edilen risk:**
  - Alloy'un Docker soketine erişimi Docker daemon'u üzerinde kontrol demektir; salt okunur bağlama bunu sınırlamaz. Alloy'un yayınlanan portu ve dış ağı yoktur.
  - Kanal bağlanmadan alarmlar kimseye ulaşmaz.
  - Yedek tazeliği (backup) Prometheus'tan görünmez. `backup` container'ının healthcheck'i `docker compose ps`'te görünür (ADR-0009).
  - Prometheus scrape'i token'sızdır; management portu yayınlanırsa metrikler açılır. Bu port hiçbir ortamda yayınlanmaz (ADR-0004).
- **Etkilenen dosyalar:**
  - `verso-app/pom.xml`, `application.yml` (exposure), `platform-security` (prometheus `permitAll`, yalnız management portu).
  - `document-core` (`IngestionMetrics`, `IngestionRepository.queueStats`), `qa-core` (`QaMetrics`).
  - `compose.yaml`, `deploy/obs/**`, `scripts/obs-smoke.sh`, `scripts/dev-secrets.sh`, `docs/runbooks/**`, `.github/workflows/restore-drill.yml`.
- **Geri alma yolu:** `obs` profilindeki servisler ve `deploy/obs` silinir. Metrik ucu kalabilir; tüketicisi yoksa exposure'dan çıkarılır.

## Yeniden değerlendirme koşulu

- İkinci bir instance ya da servis eklendiğinde: tracing (Tempo) ve tail sampling.
- Üretim host'u Linux olduğunda: node-exporter ve cAdvisor.
- Faz 8 ölçümleri eşiklerin gürültülü ya da kör olduğunu gösterirse.
