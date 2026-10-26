# Çok adımlı akıl yürütme: doğrulama ve ölçüm (ADR-0055)

- **Tarih:** 2026-10-27
- **Durum:** Birim testleri ve çevrimdışı kapı ölçümü yapıldı. **Gerçek modelle uçtan uca ölçüm bekliyor**: bu oturumda Docker kapalıydı (Testcontainers entegrasyon testleri ve demo yığını çalıştırılamadı); entegrasyon testleri GitHub CI'da koşar.

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

## Bekleyen ölçüm (gerçek model, Docker)

Yığın ayaktayken tek komut:

```bash
docker compose up -d --build --wait
node scripts/eval.mjs --set multihop --compare
```

- Uygulama kapsayıcısı önce `VERSO_QA_REASONING_ENABLED=false`, sonra `true` ile yeniden oluşturulur (`docker compose up -d --wait --force-recreate --no-deps verso-app`); örnek PDF'ler bir kez yüklenir; sonunda bayrak varsayılana (kapalı) döner.
- Çıktılar: `eval/results/multihop-<model>-off.json`, `eval/results/multihop-<model>-on.json`, `eval/results/multihop-compare-<model>.json` (iki özet yan yana ve değişen sorular).
- Metrikler: `sourceHitRate` (beklenen kaynakların atıfta olma oranı), `allSourcesCitedRate`, `factRate`, `allFactsRate`, `partialStatesMissing`, `decomposedRate`, `contradictionNotes`, gecikme p50/p95.
- Tek adımlı sette yan etki: aynı yığında `VERSO_QA_REASONING_ENABLED=true` ile `node scripts/eval.mjs` (140 soru; kapı 3 soruda ateşler).
- Açma kararı ADR-0055 "Yeniden değerlendirme koşulu"ndadır.

| Ölçüm | Kapalı | Açık |
|---|---|---|
| Tüm kaynaklar atıfta (20 soru) | bekliyor | bekliyor |
| Olgu oranı | bekliyor | bekliyor |
| Kısmi sorularda "bulunamadı" | bekliyor | bekliyor |
| Ayrıştırılan soru oranı | – | bekliyor |
| Gecikme p50 / p95 (GPU) | bekliyor | bekliyor |
