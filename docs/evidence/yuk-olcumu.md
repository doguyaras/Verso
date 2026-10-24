# Yük ölçümü: soru-cevap sıcak yolu, ingestion yükü altında

> ADR-0001'in bölme tetiği: "Soru-cevap p99 gecikmesinin ingestion yükü altında bütçeyi 3 gün üst üste aşması". Bu kayıt tetiğin yük altındaki karşılığını ölçer; "3 gün üst üste" koşulu üretim telemetrisiyle `QaP99OverBudget` alarmına bırakıldı.

- **Tarih:** 2026-10-24
- **Commit:** bu dalın son commit'i (`feature/doguyaras-yuk-olcumu`); ölçüm betiği `scripts/load-test.mjs` (`qa` senaryosu), yardımcılar `scripts/load-lib.mjs`, testleri `scripts/load-lib.test.js`.
- **Ham sonuçlar:** `eval/results/yuk-qa-alone.json`, `yuk-qa-ingest.json`, `yuk-qa-stream.json`.

## 1. Donanım ve yığın

| Öğe | Değer |
|---|---|
| CPU | AMD Ryzen 7 9800X3D, 8 çekirdek / 8 mantıksal işlemci |
| RAM | 31 GB (ölçüm sırasında boş: 11,4 → 5,7 GB; makinede başka uygulamalar da açıktı) |
| GPU | NVIDIA GeForce RTX 4070 Ti SUPER, 16 GB; Ollama iki modeli de GPU'da çalıştırıyor |
| Yığın | Çalışan ana yığın: `compose.yaml` + `deploy/compose.gpu.yaml`; gözlem profili, docling ve ek yığın yok. Uygulama 1,5 GiB, Ollama 6 GiB sınırı (ölçüm sırasında 4,7 GiB), Postgres 1 GiB |
| Modeller | bge-m3:567m (embedding), gemma4:e2b (chat), `chat-concurrency` 1, chat bekleme 5 sn, embedding bulkhead'i 4, eşik 0,50 (`config/verso.yml`, değiştirilmedi) |
| Yedek | Ölçümden önce `backup once` çalıştırıldı (`verso-20261024T191535Z.dump.gpg`, ok) |

## 2. Yöntem

- **Hesap:** CI hesabı (`verso-ci`). Taban külliyat: `samples/` altındaki 11 PDF, her koşu öncesinde yüklendi, hepsi READY olunca ölçüm başladı. Ölçüm sonunda `lt-*` belgelerin hepsi silindi, hesapta belge kalmadı.
- **Soru karışımı:** `eval/eval-set.json` (v2, 140 soru): her beşinci soru cevaplanamaz (eşik altı, modele gitmez), kalanı cevaplanabilir; karışım belirlenimci (`buildMix`). Cevaplanamaz sorular hızlı döner, bu yüzden p50'yi aşağı çeker; p95/p99 cevaplanabilir sorulara aittir.
- **İstemci:** kapalı döngü, 1 / 2 / 4 eşzamanlı istemci; 503'te `Retry-After` kadar bekler (entegrasyon belgesinin istediği gibi). Her koşu en az 120 sn (akışlı koşular 90 sn).
- **Ingestion yükü:** koşunun başında 30 örnek PDF (2–5 sayfa), aynı anda 3 yükleme; yükleme sınırlayıcısı (503 10014) ve bekleyen belge sınırı (429 10015) `Retry-After` ile yeniden denendi. Bütün belgeler READY olana kadar ingestion sürdü (33–35 sn); "ingestion sırasında" sütunları yalnız bu aralıkta başlayan istekleri sayar.
- **Gecikme:** istemci tarafı, tam cevap süresi (200 dönenler). Akışlı koşuda ilk `stage` olayına ve ilk `delta` (ilk metin parçası) süresine bakıldı; model çağrılmayan (eşik altı) sorularda `delta` gelmez, TTFT'ye girmez.
- **DB ve model süresi:** `verso-edge-1` içinden `http://verso-app:8081/actuator/prometheus` (yönetim portu, yalnız iç ağ) koşu öncesi ve sonrası okundu; farktan ortalama süre hesaplandı: `verso_qa_retrieval_duration_seconds` (soru embedding'i + DB araması), `verso_qa_chat_duration_seconds` (model), `hikaricp_connections_acquire/usage_seconds` (havuz).
- **Sınırlar:** her hücre tek koşudur. İngestion penceresi kısadır (≈35 sn), o aralıktaki istek sayısı 17–31'dir; bu örnekte p99 fiilen en büyük değerdir. Kapalı döngü yük, açık bir geliş hızından iyimser sonuç verir. Yalnız local-GPU ölçüldü; local-CPU ve cloud bütçeleri bu kayıtta doğrulanmadı.

## 3. Sonuçlar

### 3.1 Soru-cevap, JSON uç (`POST /v1/questions`), yalnız soru

| İstemci | İstek | Cevap | 503 | 429 | Hata | p50 | p95 | p99 | en çok |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 85 | 85 | 0 | 0 | 0 | 1,20 sn | 2,71 sn | 3,01 sn | 3,01 sn |
| 2 | 111 | 110 | 1 (11002) | 0 | 0 | 1,90 sn | 3,51 sn | 5,80 sn | 12,28 sn |
| 4 | 139 | 139 | 0 | 0 | 0 | 3,32 sn | 5,68 sn | 6,40 sn | 6,62 sn |

### 3.2 Aynı, 30 belgelik ingestion sürerken

Koşunun tamamı (ingestion 33–35 sn sürdü; kalan süre ingestion'sız):

| İstemci | İstek | Cevap | 503 | 429 | Hata | p50 | p95 | p99 | en çok |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 87 | 87 | 0 | 0 | 0 | 1,42 sn | 2,12 sn | 2,80 sn | 2,80 sn |
| 2 | 79 | 78 | 1 (11002) | 0 | 0 | 2,80 sn | 5,80 sn | 10,69 sn | 10,69 sn |
| 4 | 100 | 93 | 7 (11002) | 0 | 0 | 4,64 sn | 6,89 sn | 7,98 sn | 7,98 sn |

Yalnız ingestion sürerken başlayan istekler ve aynı istemci sayısında yalnız-soru koşusuyla karşılaştırma:

| İstemci | İstek (ingestion sırasında) | p50 | p95 | p99 | p50, ingestion'sız | p99, ingestion'sız |
|---|---|---|---|---|---|---|
| 1 | 24 | 1,29 sn | 2,02 sn | 2,80 sn | 1,20 sn | 3,01 sn |
| 2 | 25 | 2,91 sn | 4,29 sn | 4,57 sn | 1,90 sn | 5,80 sn |
| 4 | 27 (3 × 503) | 5,53 sn | 7,17 sn | 7,98 sn | 3,32 sn | 6,40 sn |

Ingestion hızı bu sırada: 30 belge 33–35 sn'de READY (**≈52–55 belge/dk**; yalnız ingestion'da önceki ölçüm ≈73 belge/dk idi, `docs/capacity.md`). Bir ilk koşuda (düzeltilmeden önce) yükleme betiği 429 10015'e (bekleyen belge sınırı) takıldı; istemci `Retry-After` ile bekleyince sorun kalmadı.

### 3.3 Akış (`POST /v1/questions/stream`), ilk token süresi

90 sn koşular. TTFT = isteğin gönderilmesinden ilk `delta` olayına; "ilk olay" = ilk `stage` (RETRIEVING).

| İstemci | Ingestion | İstek | Cevap | 503 | İlk olay p50 / p99 | **TTFT p50** | TTFT p95 | TTFT p99 | Toplam p50 / p95 / p99 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | yok | 47 | 47 | 0 | 12 / 78 ms | **1,20 sn** | 2,71 sn | 7,59 sn | 1,79 / 5,89 / 9,00 sn |
| 1 | 30 belge | 56 | 56 | 0 | 11 / 38 ms | **1,19 sn** | 2,68 sn | 2,98 sn | 1,44 / 3,39 / 3,41 sn |
| 4 | yok | 82 | 74 | 8 (11002) | 11 / 31 ms | **2,98 sn** | 6,91 sn | 9,21 sn | 3,58 / 7,41 / 10,93 sn |
| 4 | 30 belge | 73 | 69 | 4 (11002) | 12 / 20 ms | **4,48 sn** | 6,42 sn | 7,14 sn | 5,05 / 7,00 / 7,59 sn |

Kullanıcı ilk geri bildirimi (ilk `stage`) 12 ms'de görür, ilk metni tek istemcide ≈1,2 sn'de; JSON uçta aynı cevabın tamamı p50 1,2–1,8 sn'dir. Yük altında TTFT'nin büyük kısmı sohbet slotunu beklemektir (`chat-concurrency` 1).

### 3.4 DB süresi ve model süresi (koşu başına ortalama, uygulama metrikleri)

| Koşu | Retrieval (embedding + DB arama) | Chat (model, slot beklemesi dahil) | `verso-db` havuzu: alma / kullanım | `verso-qa-db`: alma / kullanım | Havuz zaman aşımı |
|---|---|---|---|---|---|
| 1 istemci | 274 ms | 1,17 sn | 0,2 / 5,1 ms | 0,4 / 6,3 ms | 0 |
| 2 istemci | 139 ms | 2,05 sn | 0,2 / 6,3 ms | 0,9 / 5,5 ms | 0 |
| 4 istemci | 135 ms | 3,51 sn | 0,1 / 6,1 ms | 0,6 / 5,3 ms | 0 |
| 1 istemci + ingestion | 187 ms | 1,25 sn | 3,1 / 4,7 ms | 0,8 / 4,4 ms | 0 |
| 2 istemci + ingestion | 311 ms | 2,81 sn | 2,6 / 4,5 ms | 1,1 / 4,8 ms | 0 |
| 4 istemci + ingestion | 209 ms | 4,60 sn | 2,2 / 5,3 ms | 0,6 / 5,3 ms | 0 |

Okuma: bir sorunun süresinin yüzde 85–95'i modelde (chat) ve sohbet slotunu beklemede; DB bağlantısı soru başına ≈5 ms tutuluyor, bağlantı alma 1 ms altında (ingestion sürerken `verso-db` havuzunda 2–3 ms: worker yazıları aynı havuzu kullanıyor). Havuz zaman aşımı hiç yok, `verso-audit-db` kullanımı 1,2–1,6 ms. Retrieval'ın (embedding dahil) ingestion'dan etkilenmesi küçük: embedding aynı Ollama'yı paylaşıyor ama bge-m3 GPU'da ms mertebesinde. Darboğaz DB değil, tek chat slotu ve modeldir.

## 4. Bütçe değerlendirmesi

| Bütçe | Kaynak | Ölçüm | Karar |
|---|---|---|---|
| Soru sor, p99 ≤ 15 sn (local-GPU) | repo-context §3, ADR-0008 | En kötü p99 10,93 sn (akışlı, 4 istemci, ingestion'sız); JSON uçta 4 istemcide 6,4 sn, ingestion'la 7,98 sn; tek tek en uzun cevap 12,28 sn (2 istemci, tek istek) | **TUTUYOR** (1, 2 ve 4 istemci, ingestion'lı ve ingestion'sız). Payı en dar nokta: 2 istemcide tek bir 12,3 sn'lik cevap |
| Soru sor, reddedilme | ADR-0012 (bulkhead) | 4 istemcide yalnız soru: 0/139 ve 8/82 (akışlı); ingestion'lı 7/100 ve 4/73; hepsi 503 11002 + `Retry-After`; 429 ve 5xx yok | Tasarım gereği; 4 istemci, tek chat slotunda %0–10 reddedilme getiriyor. `chat-concurrency` artırılırsa azalır (ölçülmedi) |
| Akış, ilk metin | ADR-0035 (bütçe yok) | TTFT p50 1,2 sn (1 istemci), 3–4,5 sn (4 istemci); ilk olay < 100 ms | Bütçe tanımlı değil; referans değerler olarak kaydedildi |
| DB havuz bekleme | ADR-0039 | alma ≤ 3,1 ms ortalama, zaman aşımı 0 | **TUTUYOR**; havuzlar bu yükte darboğaz değil |
| Geçmiş, sayfa, parça, yükleme (2 sn) | repo-context §3 | Bu çalışmada ölçülmedi (yük soru-cevapta); önceki ölçüm: liste p99 55 ms, yükleme 12 eşzamanlı | Değişiklik yok |
| Local-CPU (60 sn), cloud (20 sn) | ADR-0008 | Ölçülmedi (yalnız GPU'lu yığın) | Önceki kayıtlar geçerli (`docs/capacity.md`) |

## 5. ADR-0001 bölme tetiği

**Karşılanmıyor.** 30 belgelik ingestion sürerken 1–4 eşzamanlı istemcide soru-cevap p99'u 2,8–10,7 sn'de kaldı; local-GPU bütçesi 15 sn. Ingestion gecikmeyi yine de artırıyor (aynı istemci sayısında p50 yaklaşık +7% (1 istemci), +53% (2), +67% (4), çünkü aynı Ollama'yı paylaşıyorlar), ama bütçenin altında; hiçbir koşuda 5xx, 429 ya da havuz zaman aşımı yok. Bu ölçüm tetiğin "3 gün üst üste" koşulunu kanıtlayamaz ya da çürütemez: bir iş gününün gerçek yükü bu 35 sn'lik pencereden farklıdır. Bu yüzden `QaP99OverBudget` alarmı (soru isteği p99'u son 24 saatte 15 sn'nin üstünde ve 2 gün daha öyle kalırsa) tetiği üretimde izler. Servise bölme için gerekçe yok; modül sınırları ve mevcut `afterMigrate`/havuz ayrımı yeterli.

Uyarılar:
- p99 değerleri 17–140 isteğe dayanır, tek koşudur; 2 istemcideki 10,7 sn'lik değer ingestion bitince geldi (tek aykırı cevap), ingestion'ın etkisi sayılamaz.
- Ölçülen belge boyutu küçüktür (2–5 sayfa). Büyük PDF'lerde ingestion süresi ve Ollama'yı meşgul ettiği süre uzar; bu durumda ölçüm tekrarlanmalı.
- Alarm eşiği (15 sn) local-GPU bütçesidir; local-CPU (60 sn) ya da cloud (20 sn) kurulumunda `alerts.yml` içinde değiştirilmelidir.

## 6. Yapılanlar

- `scripts/load-test.mjs`: `qa` senaryosu (eşzamanlı istemci sayısı, ingestion eşzamanlı modu, akış/TTFT, uygulama metriklerinden DB ve model süresi); ağ hatası artık koşuyu çökertmiyor (durum 0).
- `scripts/load-lib.mjs` ve `scripts/load-lib.test.js`: yüzdelikler, 503/429/hata ayrımı, soru karışımı, SSE ayrıştırıcı, metrik farkı (5 test).
- `deploy/obs/prometheus/alerts.yml` ve `alerts.test.yml`: `QaP99OverBudget` (13. alarm; promtool testi: gün penceresi 3 gün üst üste aşınca ateşler, bütçe içinde susar). `ComposeConfigTest` ve `scripts/obs-smoke.sh` alarm sayısı 13'e güncellendi (obs-smoke 11'de kalmıştı; 12 doğruydu).
- `docs/capacity.md`: ölçülen kapasite.
