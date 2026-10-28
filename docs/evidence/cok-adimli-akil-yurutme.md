# Çok adımlı akıl yürütme: doğrulama ve ölçüm (ADR-0055)

- **Tarih:** 2026-10-27 (birim ve kapı), 2026-10-28 (gerçek model)
- **Durum:** Gerçek modelle uçtan uca ölçüldü (aşağıda). Karar: bayrak **varsayılan kapalı kalır**.

## Yapılanlar (lokal, Docker yok)

| Kontrol | Komut | Sonuç |
|---|---|---|
| Derleme | `./mvnw -B -ntp -T 1 -DskipTests install` | başarılı |
| Birim testleri (qa-api, qa-core) | `./mvnw -B -ntp -T 1 -pl services/qa/qa-api,services/qa/qa-core test` | yeşil (kapı, ayrıştırma ayrıştırıcısı, birleştirme, erişim, bulunamadı cümlesi, geri dönüşler, çelişki notu, log gizliliği, sözleşme) |
| Sözcüksel kapı ölçümü | `ReasoningGateEvalTest` (her build) | çok adımlı 19/20 (kaçan `mh-05`), tek adımlı 3/140 (`depo-5`, `depo-11`, `erp-18`) |
| Çok adımlı setin doğrulaması | `node --test scripts/multihop-set.test.js` | 21/21: her `expect` belirtilen sayfanın `samples/src` metninde bütün sözcük; 17 soru iki belge; 2 kısmi soru |
| Panel | `node --test scripts/panel.test.mjs` | yeşil (aşama metinleri, iz düz metin, bulunamadı ve çelişki notu) |

## CI'da (Testcontainers, sahte model)

- `MultiStepApiTest`: akış aşamaları (`RETRIEVING`, `DECOMPOSING`, `SUB_QUESTION` 1/2 ve 2/2, `PASSAGES_FOUND`, `WRITING`), alt sorular arasında atıf doğrulaması (olmayan `[7]` atılır), erişim matrisi (başka hesabın belgesine yönelen alt soru bulunamaz ve cevap bunu söyler; o belge ne prompt'a ne cevaba girer), ayrıştırma zaman aşımında tek geçiş, geçersiz ayrıştırmada tek geçiş, geçmişte bulunamadı cümlesi (`QaTables`), log'da alt soru yok.
- `MultiStepDisabledApiTest`: bayrak kapalıyken aynı karşılaştırma sorusu eski aşamalarla, `reasoning: null` ve sıfır ayrıştırma çağrısıyla cevaplanır.

## Gerçek model ölçümü (2026-10-28)

- **Yığın:** `compose.yaml` + `deploy/compose.gpu.yaml` (RTX 4070 Ti SUPER), `gemma4:e2b` + `bge-m3:567m` Ollama'da, `OLLAMA_NUM_PARALLEL=1`, uygulama imajı `verso:local` (develop). Gözlem profili kapalı. Ölçümden önce yedek: `verso-20261028T105421Z.dump.gpg` (ok, 60 tablo).
- **Komutlar:** `node scripts/eval.mjs --set multihop --compare` (uygulama önce kapalı, sonra açık bayrakla yeniden oluşturuldu, sonunda kapalıya döndü); tek adımlı set için aynı imajda `VERSO_QA_REASONING_ENABLED=true`, sonra `false` ile `OUT=e2e-gemma4_e2b-reasoning-{on,off}.json node scripts/eval.mjs`. `eval.mjs` tek adımlı satıra da `steps` yazar (kapının ateşlediği sorular sayılabilsin diye).
- **Ham sonuçlar:** `eval/results/multihop-gemma4_e2b-{off,on}.json`, `eval/results/multihop-compare-gemma4_e2b.json`, `eval/results/e2e-gemma4_e2b-reasoning-{off,on}.json`.

### Çok adımlı set (20 soru)

| Ölçüm | Kapalı | Açık |
|---|---|---|
| Atıf isabeti (beklenen kaynak atıfta) | 0,868 | 0,842 |
| Tüm kaynaklar atıfta | **0,80** | 0,70 |
| Olgu oranı (beklenen olgu cevapta) | **0,895** | 0,842 |
| Tüm olgular cevapta | **0,85** | 0,75 |
| Kısmi sorularda "bulunamadı" (2 soru) | 2/2 | 2/2 |
| Ayrıştırılan soru oranı (kapı ateşledi) | – | 0,95 (19/20; kaçan `mh-05`, çevrimdışı ölçümle aynı) |
| Çelişki notu | 0 | 0 |
| Gecikme p50 / p95 / en çok (GPU) | **1,59 / 2,91 / 20,1 sn**¹ | 3,53 / 10,33 / 11,7 sn |
| Hata | 0 | 0 |

¹ Kapalı kolun 20 sn'si ilk soru (`mh-01`): uygulama yeni oluşturulmuştu, ilk sohbet çağrısı.

Değişen 5 soru: `mh-02` olgu 0 → 1; `mh-14` kaynak 0 → 1 ama olgu 2 → 1; `mh-03` kaynak 2 → 1; `mh-10` olgu 1 → 0; `mh-16` kaynak ve olgu 2 → 1. Açık kol 1 soruda kazandırıp 4 soruda kaybettirdi.

### Tek adımlı set (140 soru, aynı imaj)

| Ölçüm | Kapalı | Açık |
|---|---|---|
| Kapının ateşlediği soru | – | 3/140 (`depo-5`, `depo-11`, `erp-18`; çevrimdışı ölçümle aynı) |
| Bulunan cevap oranı | 0,938 | 0,938 |
| Atıf isabeti | 0,884 | 0,902 |
| Olgu doğruluğu | 0,893 | 0,902 |
| Cevaplanamayanda "bulunamadı" | 0,607 | 0,607 |
| Gecikme p50 / p95 / en çok | 1,20 / 1,99 / 3,17 sn | 1,20 / 2,39 / 4,07 sn |

Ateşleyen üç soru açıkken üçü de doğru (kapalıyken `depo-11` atıfı ve olguyu kaçırıyordu); gecikmeleri 1,2–1,8 sn'den 2,4–4,1 sn'ye çıktı. Fark 2 soruluk, gürültü düzeyinde; yan etki yok.

### Karar

ADR-0055'in açma koşulu tutmadı: çok adımlı sette tüm-kaynaklar-atıfta (0,80 → 0,70) ve olgu oranı (0,895 → 0,842) **düştü**, p95 2,9 sn'den 10,3 sn'ye çıktı (ADR-0008 bütçesinin içinde ama dört katı). Tek adımlı sette yanlış ateşleme %2 ve zararsız. `verso.qa.reasoning.enabled` **varsayılan kapalı kalır**. Olası sebep: `gemma4:e2b` iki alt cevabı birleştirirken ilk geçişin iki belgeden gelen pasajları tek prompt'ta görmesinden daha az bilgi taşıyor (ayrıştırılmadı). Bulut modunda ya da daha büyük yerel modelle yeniden ölçülmeli.
