# Faz 7: Doğrulama ve kanıt kaydı

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

- **Tarih:** 2026-10-03
- **Ortam:** Windows 11 + Docker Desktop (Docker Engine 29.6.1, 16 GB, 8 CPU); GitHub Actions `ubuntu-latest`. Prometheus 3.15.0, Alertmanager 0.34.1, Loki 3.7.8, Alloy 1.20.1, Grafana 13.2.3.
- **Kapsam:**
  - `obs` compose profili; `/actuator/prometheus`.
  - Verso metrikleri (kuyruk, worker, soru-cevap, model devresi).
  - 6 alarm ve runbook'ları; Grafana panosu; Loki'ye log akışı.
  - Kararlar: ADR-0014, ADR-0007 #28 (prometheus ucu).
- **Davranışsal kapsam (seviye 2–3):** var.
  - Metrikler gerçek uygulamadan scrape edildi.
  - Alarmlar promtool birim testleriyle doğrulandı.
  - Yığın, gözlem profiliyle canlı koşuldu.

## 1. Makine kontrolleri

| Kontrol | Komut | Sonuç |
|---|---|---|
| Build ve testler | `./mvnw -B -ntp verify` | **PASS**: 348 Java testi (faz 6 sonunda 342) |
| Yapılandırma doğrulayıcıları | `promtool check config`, `amtool check-config`, `loki -verify-config`, `alloy validate`, `nginx -t` | **PASS** |
| Alarm birim testleri | `promtool test rules alerts.test.yml` | **PASS**: 8 test grubu. Model tıkanması, düşük trafikte model hatası, embedding kesintisi, normal durum (alarm yok), veritabanı NaN, kuyruk birikmesi, scrape kaybı, 5xx oranı |
| Uçtan uca gözlem | `bash scripts/obs-smoke.sh` | **PASS** (Bölüm 2) |
| Mutasyon | `bash scripts/mutation-check.sh` | Bölüm 5 |

## 2. Canlı denemeler

| Deneme | Sonuç |
|---|---|
| `obs-smoke.sh` (ilk sürüm) | **PASS**: 170 Verso serisi, 5 kural, 1 Alertmanager. Grafana datasource'ları OK, pano yüklü. Loki'de uygulama satırları var; qa-smoke sorularının metni hiçbir log satırında yok |
| Yük testi sırasında metrikler (faz 8) | Kuyruk, sonuç sayaçları ve süreler panoda izlendi |

## 3. Review

Tek ajan, altı skill, `2d7e11f` üzerinde koştu.

| Skill | Karar | Öne çıkan bulgular |
|---|---|---|
| `verso-security-review` | REQUEST CHANGES | B1 (HIGH): Grafana internete açıktı; dış snapshot, public dashboard ve eklenti yönetimi açıktı. B2: Alloy Keycloak log'larını da topluyordu (kullanıcı adı, IP). B3: actuator API portunu paylaşırsa prometheus token'sız açılıyordu. B4: Docker soketi. B5: AGPL lisansı yazılmamıştı |
| `verso-environment-impact-review` | APPROVE WITH NON-BLOCKING COMMENTS | E1: panodaki p95 paneli olmayan bucket'ları sorguluyordu. E2: bildirim kanalı yok. E3: saklama boyut sınırı yok. E4: doküman kayması |
| `verso-resilience-review` | REQUEST CHANGES | R1 (HIGH): chat modeli tıkanınca ya da düşük trafikte hiçbir alarm çalmıyordu (promtool ile kanıtlandı). R2: veritabanı kesintisi alarmsızdı. R3: kuyruk sorgusunun timeout'u scrape timeout'una eşitti |
| `verso-spring-code-review` | REQUEST CHANGES | SP1 (HIGH): `verso_ingestion_paused` canlıda hep NaN dönüyordu (zayıf referans) |
| `verso-test-writer` | FAIL | 26 mutasyonun 18'i yaşadı. T1: gauge değerleri test edilmiyordu. T2: alarm testi yoktu. T3: obs-smoke'un gizlilik kontrolünün pozitif kontrolü yoktu |
| `verso-llm-review` (1.5, 2.1) | REQUEST CHANGES | L1 = B1 |

## 4. Bulgu → düzeltme

| Bulgu | Kanıt (önce) | Düzeltme | Kalıcı kontrol |
|---|---|---|---|
| Paused gauge NaN (SP1/T1) | Probe: GC öncesi ve sonrası NaN | `strongReference(true)` | `MetricsTest.outageGauges_*` (0 → 1), M196 |
| Alarm körlükleri (R1/R2) | promtool T1–T4, T7: alarm yok | `ModelUnavailable` sonuçlardan hesaplanıyor; yeni `VersoDatabaseUnavailable` (NaN) | `alerts.test.yml` (CI'da obs-smoke koşar) |
| Grafana dışarı açık (B1/L1) | `default` ağ, raintank snapshot açık | Grafana yalnız `obs` iç ağında, önünde `obs-edge`; dış yollar ayrıca kapalı | `ComposeConfigTest.observability_*`, M199 |
| Alloy her log'u topluyordu (B2) | Keycloak dahil tüm servisler | Allowlist: verso-app, migrate, edge, backup | `ComposeConfigTest`, obs-smoke servis kontrolü, M198 |
| Prometheus paylaşılan portta açık (B3) | Probe: API portunda token'sız 200 | Yalnız ayrı management sunucusunun portunda token'sız (`ManagementServerPort`) | `MetricsSharedPortTest`, M197 |
| Pano p95 (E1) | Bucket yok | `percentiles-histogram` açık | Canlı pano |
| Kuyruk sorgusu (R3) | 10 sn = scrape timeout | Kendi 2 sn timeout'u | – |
| obs-smoke (T3) | Log hiç gelmese de PASS | Pozitif kontrol, dosya adı ve metin araması, etiket ve servis kontrolü, promtool testleri | CI |
| Doküman (B5/E3/E4) | – | AGPL lisansı, 2 GB saklama, runbook'lar, repo-context, llm-rules 1.5 | – |

Kabul edilen açıklar:

- **Bildirim kanalı yok (E2):** kullanıcıdan kanal bilgisi bekleniyor. Bu yüzden dead man's switch de yok.
- **Docker soketi (B4):** Alloy'un soket erişimi host üzerinde kontrol demektir. Bir soket proxy'si yeni bir bileşen olur; ileriye bırakıldı.
- **Yaşayan küçük mutasyonlar:** sign-up, haber akışı ve `--disable-reporting` ayarlarından bazıları artık test ediliyor. Inhibit kuralları ve alarm `for:` süreleri promtool testlerinin dışında kalıyor.

## 5. Mutasyonlar

`bash scripts/mutation-check.sh` (`ONLY` ile faz 7 mutasyonları): baseline yeşil (348 Java testi, 8 node suite). Sonuç: **4/4 yakalandı** (M196–M199).

Kodla birlikte değişen iki eski tanım (M98, M170) uyarlandı ve ikisi de yakalandı. Alarm ifadelerinin mutasyonları Java testlerinin değil `alerts.test.yml`'nin işidir; o dosya CI'da `obs-smoke.sh` ile koşar. Çıktı: [`faz-7-mutasyon-ciktisi.txt`](faz-7-mutasyon-ciktisi.txt).

## 6. CI

PR #9 (`db5ad73`): `ci` (backend, scripts-and-hooks) ve `restore-drill` (obs-smoke ve promtool testleri dahil) **success**: run 37110133954, 37110133962. Birleştirme sonrası `develop` push'u (`c497a82`): success, run 37110534660.
