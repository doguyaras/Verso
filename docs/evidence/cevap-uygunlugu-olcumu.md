# Cevap uygunluğu ölçümü (ADR-0064)

- **Tarih:** 2026-10-30
- **Yığın:** canlı, `compose.yaml` + `deploy/compose.gpu.yaml`, RTX 4070 Ti SUPER; gemma4:e2b (`ollama-chat`), bge-m3 (`ollama`). Kapı ve `passage-min-similarity` 0,55 (ADR-0063).
- **Yöntem:** aynı imaj iki kolda: "önce" `VERSO_QA_RELEVANCE_ENABLED=false` (develop davranışı), "sonra" varsayılan `true`. Her koşu `scripts/eval.mjs` ile; her koşu belgeleri baştan yükler.
- **Setler:**
  - Dış: `eval-data/external` (200 yönetmelik PDF'i, 305 cevaplanabilir + 45 cevaplanamaz; 35'i konusu belgede). Sonuç dosyaları yerelde kalır (`eval/results/e2e-external-uygunluk-{off,on}.json`): soru seti git dışında.
  - İç: `eval/eval-set.json` (112 + 28). `eval/results/e2e-internal-uygunluk-{off,on}.json`.
  - Çok adımlı iç: `eval/multihop-set.json` (20). `eval/results/multihop-uygunluk-{off,on}.json`.
  - Çok adımlı dış: `eval-data/external/questions-multihop.json` (32). Yerelde: `eval/results/multihop-external-uygunluk-{off,on}.json`.
- Çok adımlı akıl yürütme kapalı (varsayılan); bütün sorular tek geçişle cevaplandı.

## 1. Sonuçlar

| Set | Ölçüm | ADR-0063 p55 | Önce (kapalı) | Sonra (açık) |
|---|---|---|---|---|
| Dış | Kaynak bulundu | 0,925 | 0,925 | 0,911 |
| Dış | Atıf isabeti | 0,731 | 0,725 | 0,711 |
| Dış | Atıf kesinliği | 0,621 | 0,618 | 0,618 |
| Dış | Doğru bilgi | 0,784 | 0,787 | 0,790 |
| Dış | "Bulunamadı", sıkı | 0,711 | 0,711 | **0,956** |
| Dış | Ret, modelinki dahil (`notFoundOrOwnRefusal`) | 0,756 | 0,756 | **0,956** |
| Dış | Gecikme p50 / p95 | 771 / 1799 ms | 989 / 2008 ms | 712 / 1797 ms |
| İç | Kaynak bulundu | 0,893 | 0,893 | 0,884 |
| İç | Atıf isabeti | 0,839 | 0,839 | 0,830 |
| İç | Doğru bilgi | 0,848 | 0,857 | 0,857 |
| İç | Ret, modelinki dahil | 0,786 | 0,786 | **0,929** |
| İç | Gecikme p50 / p95 | 391 / 733 ms | 400 / 888 ms | 391 / 807 ms |
| Çok adımlı iç | Kaynak / bilgi isabeti | – | 0,684 / 0,658 | 0,684 / 0,658 |
| Çok adımlı iç | Kısmi cevapta eksik söylendi | – | 1,0 | 1,0 |
| Çok adımlı dış | Kaynak / bilgi isabeti | – | 0,484 / 0,531 | 0,484 / 0,516 |
| Çok adımlı dış | Kısmi cevapta eksik söylendi | – | 1,0 | 1,0 |

- Dış: 45 cevaplanamazdan 43'ü reddedildi (önce 34). İç: 28'den 26'sı (önce 22).
- Gürültü (ADR-0063): dışta atıf isabetinde ±7 soru, retlerde 2; içte 1.
  - Dış: bulundu −4, atıf isabeti −4, doğru bilgi +1 soru. Gürültü içinde.
  - İç: bulundu −1, atıf isabeti −1, doğru bilgi 0. Gürültü içinde.
  - Çok adımlı dış: bilgi −1 (mh-02). O koşuda model farklı bir metin üretti ve "bulunamadı" oldu; kapalı koldaki cevabı kontrolden geçer (bkz. 2). Tek soru, gürültü.
- "Önce" sütunu p55'i tekrar etti (dış ret 0,756, iç ret 0,786): kapalı kol develop'la aynı davranır.

## 2. Soru bazında: "bulunamadı"ya dönen cevaplanabilir sorular

Aynı imaj, kapalı → açık; atıf isabeti ya da doğru bilgi kaybeden ve açık kolda `NOT_FOUND` olan sorular:

| Set | Soru | Kapalıdaki cevap | Beklenen bilgi cevapta mıydı? |
|---|---|---|---|
| Dış | hu-dis-04 "ne kadar ek süre" | "… telafi süresi verilir [3]" (süre yok) | Hayır |
| Dış | hu-hzr-05 "kaç yıllık" | "Belgelerde … bilgi bulunmamaktadır [1]…[5]" | Hayır |
| Dış | bina-03 "kaç kişiden" | "Belgelerde … bilgi bulunmamaktadır. [1]" | Hayır |
| İç | erp-14 "hangi gün" | "Geciken teslimatlar raporu e-postayla gelir [1]" (gün yok) | Hayır |

Doğru bilgisi olan hiçbir cevap kaybolmadı. Kalan atıf isabeti farkları "bulunamadı"ya dönmeyen sorulardadır (model ve retrieval gürültüsü).

**Kayıtlı cevaplarda yeniden oynatma** (kontrolün son hali, cevaplar sabit; model gürültüsü yok):

| Kayıt | Doğru bilgili cevap kaybı | Yeni reddedilen cevaplanamaz | Ret |
|---|---|---|---|
| Dış p55 (ADR-0063) | 0 | 9 | 34 → 43 / 45 |
| Dış, kapalı kol | 0 | 9 | 34 → 43 / 45 |
| İç p55 | 0 | 4 | 22 → 26 / 28 |
| İç, kapalı kol | 0 | 4 | 22 → 26 / 28 |
| Çok adımlı dış, kapalı kol | 0 | – | – |
| Çok adımlı iç, kapalı kol ve 2026-10-28 dosyaları | 0 | – | – |

`RelevanceEvalTest` iç p55 ve iç çok adımlı kayıtlar için bunu her build'de yeniden oynatır.

## 3. Kalibrasyon sırasında düzeltilenler

İlk canlı koşu (ilk sürüm) üç doğru cevabı "bulunamadı"ya çevirdi; hepsi düzeltildi ve testlere girdi:

- "oniki yarıyıldır", "kırkbeş saattir": bitişik yazılan sayı sözcükleri okunmuyordu. `QuestionSlot` bitişik sayıyı da okur.
- "kaç kişiden oluşur" → "4 öğretim elemanı ile Merkez Müdürü ve 2 yardımcısından": kişi birimi yalnız "kişi/üye" idi. Kurul üyelerinin her türü sayılır.
- Çok adımlı dış mh-07 ("… oranıyla aynı mı?"): model "doğrudan bilgi bulunmamaktadır. Ancak, …" diye başlayıp doğru oranı verdi. Evet/hayır ve karşılaştırma sorusunda ret + dolgu kuralı uygulanmaz.

## 4. Hâlâ geçen cevaplanamazlar

| Set | Soru | Neden geçiyor |
|---|---|---|
| Dış | yok-c-09 "aylık burs miktarı ne kadar" | Cevap bir miktar veriyor (üst sınır %50 artırımlı tutar); "ne kadar" yüzdeyi de kabul eder (doğru cevaplar "yüzde elli" ile veriliyor, is-04) |
| Dış | yok-c-13 "kütüphane kaça kadar açık" | Dolguda "saatleri" geçiyor; saat boşluğu doldu sayılıyor |
| İç | crm-yok-1 "Gümüş müşterilerin yanıt süresi" | Cevap "Altın" müşterilerinkini veriyor: varlık değişimi, sözcüksel olarak görülmez |
| İç | erp-yok-4 "ne yaptırım uygulanır" | Açık uçlu soru, denetlenebilir boşluk yok |

Bunlar soru–cevap anlam denetimi ister (LLM yargıcı ya da NLI). ADR-0064'ün yeniden değerlendirme koşulu.

## 5. LLM yargıcı

Ölçülmedi: sözcüksel kontrol hedefi (dış ret ≥ 0,85) yargıçsız aştı ve kalan 4 hata için ek bir sohbet çağrısı (her cevapta ~0,3–1 sn, tek sohbet slotu) bedeli karşılamaz. Gecikme açık kolda değişmedi (dış p50 712 ms, iç 391 ms).

## 6. Testler

- `qa-core` (container'sız, her build): `RefusalDetectorTest` 23, `QuestionSlotTest` 29, `AnswerRelevanceTest` 16 (ADR'yi açan üç hata dahil), `RelevanceEvalTest` 2; modülün tamamı yeşil.
- `verso-app` (Testcontainers, yerelde de koşuldu): `QuestionApiTest` (+3: atıflı ret → `NOT_FOUND`, şu anki müdür → `NOT_FOUND` ve tur `NOT_FOUND` saklanır, rol cevabı `ANSWERED` kalır; log'da içerik yok), `QuestionStreamApiTest` (+1: `citations` ve `done` olayı); etkilenen sınıflar `KnowledgeCardApiTest`, `SkillApiTest`, `KnowledgeHealthApiTest`, `ConversationApiTest`, `FollowUpApiTest`, `CollectionAccessTest`, `MultiStepApiTest`, `ConfigDriftTest`: 104 test, 0 hata.
