# Başlıklı vektörlerde kapı ve prompt eşiği ölçümü (ADR-0063)

- **Tarih:** 2026-10-30
- **Taban:** `origin/develop` `ba3cbc2` (ADR-0061 ve ADR-0062 dahil). Canlı yığın, GPU (`compose.yaml` + `deploy/compose.gpu.yaml`), bge-m3:567m ve gemma4:e2b.
- **Soru:** ADR-0061'den sonra dış sette cevaplanamaz soruların reddi 0,867'den 0,667–0,711'e düştü. Varsayım: cevaplanabilirlik kapısı (0,47 / 0,44 + terim payı 0,045) başlıksız vektörlerle seçilmişti, başlık konusu tutan parçaların kosinüsünü yükseltti, konusu belgede olan cevaplanamaz sorular kapıdan geçiyor.
- **Sonuç:** varsayım **doğrulanmadı**. Dış sette kapı ADR-0061'den önce de sonra da cevaplanamaz soruların en çok 1–2'sini durduruyordu; retleri chat modeli veriyordu. Değişen, modelin prompt'ta gördüğü pasajlardır. Ayar `passage-min-similarity` 0,50 → **0,55**; kapı eşikleri aynı kaldı.

## 1. Yöntem

**Kapı girdisi soru başına (`scripts/gate-probe.mjs`):**
- `eval.mjs` belgeleri yükler. `GATE_PROBE=1` ile her soru için şunları kaydeder:
  - en benzer füzyon pasajının kosinüsü,
  - o pasajın terim payı,
  - kapı kararı ve prompt'a girecek pasaj sayısı.
- Soru, yığının embedding sunucusunda embed edilir. Hibrit sorgu (`RetrievalRepository.hybrid` ve `rarestSharedTermCounts`'un aynısı) yığının PostgreSQL'inde `docker exec … psql` ile, yalnız bu koşunun belgeleri üzerinde koşar.
- `GATE_PROBE_SPLIT=1` ayrıca her pasajın kendi metnini ve başlığını tek başına embed eder. Böylece aynı koşuda "kapı başlıksız vektörü okusaydı" sorusu da ölçülür.
- **Doğrulama:**
  - `RetrievalEvalTest` iç sette aynı yığının embedding sunucusuyla koşuldu. 140 sorunun hepsinde en iyi kosinüs dört basamakta aynı çıktı ve kapı kararı da aynıydı.
  - Kapının durdurduğu 9 iç set sorusunun 9'u uçtan uca koşuda sabit "bulunamadı" cevabını aldı.
- `eval.mjs`'nin eski `stoppedByThreshold` alanı `eval/results/retrieval.json`'dan okunuyordu, yani dış set için anlamsızdı. Artık sonda kendi koşusunun kapı kararından sayılıyor; dış sette prob yoksa boş kalır.

**Uçtan uca:** `scripts/eval.mjs`, iki set:
- iç set: 140 soru; 112 cevaplanabilir, 28 cevaplanamaz, bunların 20'si konusu belgede (`on-topic`);
- dış set: 350 soru; 305 cevaplanabilir, 45 cevaplanamaz, 35'i `on-topic`.

Aday ayarlar, imaj yeniden kurulmadan `VERSO_QA_PASSAGEMINSIMILARITY` ortam değişkeniyle denendi. Ortam değişkeni scratch dizinindeki bir compose override dosyasıyla verildi. Her koşudan önce `migrate` koşuldu, sonra `verso-app` yeniden yaratıldı.

**Gürültü:** aynı ayarla iki koşu arasındaki fark.
- Dış set (`ctx` ve `yalitim`): atıf isabetinde +7/−6 soru, retlerde 2 soru.
- İç set (`ctx` ve `kapi-taban`): doğru bilgide +1/−1, retlerde 1 soru.

## 2. Kapı girdisinin dağılımı (başlıklı vektörler)

En benzer pasajın kosinüsü. Parantez içi: aynı pasajın yalnız kendi metniyle (başlıksız) kosinüsü.

| Set, grup | n | En düşük | p10 | Ortanca | p90 | En yüksek |
|---|---|---|---|---|---|---|
| İç, cevaplanabilir | 112 | 0,436 (0,450) | 0,522 (0,549) | 0,603 (0,620) | 0,668 (0,677) | 0,735 (0,738) |
| İç, cevaplanamaz | 28 | 0,404 (0,417) | 0,437 (0,431) | 0,516 (0,524) | 0,603 (0,607) | 0,651 (0,702) |
| İç, `on-topic` | 20 | 0,434 | 0,449 | 0,542 | 0,614 | 0,651 |
| Dış, cevaplanabilir | 305 | 0,485 (0,404) | 0,613 (0,551) | 0,704 (0,673) | 0,765 (0,759) | 0,836 (0,826) |
| Dış, cevaplanamaz | 45 | 0,443 (0,426) | 0,507 (0,453) | 0,579 (0,549) | 0,649 (0,626) | 0,687 (0,667) |
| Dış, `on-topic` | 35 | 0,507 | 0,539 | 0,596 | 0,651 | 0,687 |

**Başlık iki sette kosinüsü ters yönde kaydırdı:**
- **İç sette aşağı.** Kısa şirket belgesi adları soruyla az örtüşüyor. Üç cevaplanabilir soru kapının altına indi:
  - bt-16: 0,4358, pay 0,013;
  - isg-10: 0,4611, pay 1;
  - depo-17: 0,4625, pay 0,067.

  Kapıdan geçen cevaplanabilir sayısı 112/112'den 109/112'ye düştü. `RetrievalEvalTest`'in "cevaplanabilir kapıdan geçen = 1,00" koruması kırmızıya döndü.
- **Dış sette yukarı.** Yönetmelik adları ("… Araştırma Merkezi Yönetmeliği") soruların kelimeleriyle örtüşüyor.

**Kapı kararları (bugünkü eşikler):**

| Set | Cevaplanabilir geçen | Cevaplanamaz duran |
|---|---|---|
| İç | 109/112 | 6/28 |
| Dış | 305/305 | **1/45** (yok-off-05) |

ADR-0061'den önce dış sette de kapı en çok iki soruyu durdurabilirdi. Başlıksız en iyi kosinüsü 0,47'nin altında olan cevaplanamaz yalnız ikidir: yok-off-05 0,426 ve yok-c-10 0,446. O koşudaki 39/45 reddin 37 ya da daha fazlası modelin kararıdır (başlıksız kosinüsle tahmin; o koşunun aramasını prob görmedi).

## 3. Hangi sinyal ayırıyor (cevaplanabilir × cevaplanamaz, AUC)

| Sinyal | İç | Dış |
|---|---|---|
| En iyi kosinüs, başlıklı (bugünkü kapı) | 0,799 | **0,917** |
| Aynı pasajın başlıksız kosinüsü | **0,833** | 0,856 |
| Beş pasajın en iyi başlıksız kosinüsü | 0,831 | 0,865 |
| Yalnız başlığın kosinüsü | 0,602 | 0,762 |

**Kapıyı başlıksız metne baktırmak işe yaramıyor.** İç sette biraz daha iyi ayırıyor, dış sette belirgin daha kötü. Üstelik her parça için ikinci bir vektör saklamak ya da soru anında beş pasajı yeniden embed etmek gerekir.

## 4. Kapı eşikleri: ızgara

- Taranan aralık: T1 0,46–0,62, T2 0,40–T1, pay 0–0,099.
- Uçtan uca tahmin yöntemi: bir cevaplanamaz soru, kapı onu durdurursa ya da referans koşuda (`yalitim`) model reddettiyse "reddedildi" sayılır.

| Ayar | İç: geçen / duran | Dış: geçen / duran | Tahmini ret (iç / dış) |
|---|---|---|---|
| **0,47 / 0,44 / 0,045 (bugün)** | 109 / 6 | 305 / 1 | 0,75 / 0,667 |
| 0,50 / 0,40 / 0,08 | 110 / 5 | 305 / 3 | 0,786 / 0,667 |
| 0,52 / 0,42 / 0,045 | 108 / 10 | 302 / 5 | 0,786 / 0,667 |
| Dışta 10 cevaplanabilir kaybına izin verilse bile en iyisi | – | – | – / 0,667 |

**Dış sette hiçbir kapı ayarı reddi artırmıyor.** Kapının durdurabildiği sorular (konu dışı, 0,44–0,52) zaten model tarafından reddediliyor. `on-topic` cevaplanamazlar (0,51–0,69) cevaplanabilirlerin ortasında kalıyor.

0,52'ye çıkmanın bedeli üç dış set sorusu: mrk-16, mrk-34 ve mrk-38. Üçü de kısaltmayla sorulan merkez sorusudur, yani ADR-0061'in hedeflediği sınıf. Terim payları yüksek (1 ve 0,059), çünkü kısaltma yalnız başlıkta geçiyor ve kapının terim payı başlığı saymıyor.

## 5. Asıl kaldıraç: prompt'a giren pasajlar

Ret, prompt'taki pasaj sayısına göre (dış set; `prompt` = en iyi pasaj ve 0,50 ya da üstündeki diğerleri):

| Prompt'ta pasaj | ADR-0061 öncesi koşu, başlıksız tahmin: ret | `yalitim` koşusu, başlıklı: ret |
|---|---|---|
| 1–2 | 12/14 | 6/8 (prompt 0 dahil) |
| 3 | 15/17 | 14/17 |
| 4–5 | 12/14 | **10/20** |

- Başlık, dış setteki cevaplanamaz soruların prompt'una ortalama 3,0 yerine 3,4 pasaj (kapının durdurduğu sorular 0 sayılarak) taşıyor. Aynı pasaj sayısında da ret azaldı (4–5 pasajda 12/14 → 10/20): başlıklı arama, modele soruyla daha çok örtüşen pasajlar veriyor.
- Model 4–5 pasajlı soruların yarısında bir şey "buluyor". ADR-0022 de aynı etkiyi ölçmüştü: bütün beş pasaj verildiğinde 10/28, 0,50 tabanıyla 17/28 ret.
- `passage-min-similarity` için soru başına ortalama pasaj sayısı:

| Taban | Dış, cevaplanabilir | Dış, cevaplanamaz | İç, cevaplanabilir | İç, cevaplanamaz |
|---|---|---|---|---|
| 0,50 | 4,47 | 3,44 | 3,17 | 1,96 |
| 0,55 | 4,19 | 2,51 | 1,89 | 1,43 |
| 0,60 | 3,64 | 1,49 | 1,22 | 1,07 |

  Dış sette taban yükselince cevaplanamazların pasajları cevaplanabilirlerinkinden çok daha hızlı azalıyor. Göreli bir taban ("en iyinin 0,05–0,10 altı") bu ayrımı vermiyor: cevaplanamazların ilk beşi düz, göreli sayı onlarda daha yüksek (dış 0,08 altı: cevaplanabilir 3,36, cevaplanamaz 3,62).

## 6. Uçtan uca: `passage-min-similarity` taraması

Kapı eşikleri her koşuda 0,47 / 0,44 / 0,045.

**İç set** (112 cevaplanabilir, 28 cevaplanamaz):

| Taban | Kaynak bulundu | Atıf isabeti | Atıf kesinliği | Doğru bilgi | "Bulunamadı" (sıkı) | Ret (modelinki dahil) | `on-topic` ret |
|---|---|---|---|---|---|---|---|
| ADR-0061 öncesi (`e2e-gemma4_e2b-reasoning-off`, 0,50) | 0,938 | 0,884 | 0,912 | 0,893 | 0,607 | 0,643 | 11/20 |
| 0,50 (`e2e-internal-kapi-taban`) | 0,902 | 0,848 | 0,927 | 0,884 | 0,679 | 0,714 | 12/20 |
| 0,53 | 0,893 | 0,839 | 0,908 | 0,857 | 0,714 | 0,750 | 13/20 |
| **0,55** | **0,893** | **0,839** | **0,925** | **0,848** | **0,750** | **0,786** | **14/20** |
| 0,60 | 0,830 | 0,768 | 0,905 | 0,759 | 0,750 | 0,786 | 14/20 |

**Dış set** (305 cevaplanabilir, 45 cevaplanamaz):

| Taban | Kaynak bulundu | Atıf isabeti | Atıf kesinliği | Doğru bilgi | "Bulunamadı" (sıkı) | Ret (modelinki dahil) | `on-topic` ret |
|---|---|---|---|---|---|---|---|
| ADR-0061 öncesi (`e2e-external`, 0,50) | 0,885 | 0,646 | 0,567 | 0,738 | 0,667 | 0,867 | 29/35 |
| 0,50, koşu 1 (`ctx`) | 0,921 | 0,695 | 0,594 | 0,774 | 0,533 | 0,711 | 23/35 |
| 0,50, koşu 2 (`yalitim`) | 0,925 | 0,698 | 0,590 | 0,784 | 0,489 | 0,667 | 22/35 |
| 0,53 | 0,921 | 0,705 | 0,594 | 0,777 | 0,600 | 0,733 | 24/35 |
| **0,55** | **0,925** | **0,731** | **0,621** | **0,784** | **0,711** | **0,756** | **26/35** |
| 0,60 | 0,908 | 0,725 | 0,652 | 0,777 | 0,822 | 0,867 | 30/35 |

**Soru bazında, 0,50 (`yalitim` / `kapi-taban`) → 0,55:**
- **Dış set:**
  - atıf isabeti +13/−3 (aynı ayarla gürültü +7/−6);
  - ret +4/−0: hu-un-10, yok-on-10, yok-c-08, yok-c-16;
  - doğru bilgi +5/−5;
  - kaynak bulundu +2/−2.
  - Etikete göre atıf isabeti:
    - `disambiguation` 42 → 49/68;
    - merkez yönetmelikleri 31 → 37/58;
    - `number` 116 → 127/177;
    - `abbreviation` 18 → 15/28 ve `lookup` 72 → 70/88: kısaltma sorularında küçük kayıp.
- **İç set:**
  - doğru bilgi +1/−5: depo-13, erp-14, erp-17, isg-4, isg-8;
  - atıf isabeti +4/−5;
  - kaynak bulundu +3/−4;
  - ret +3/−1.
  - Kaybedilen sorular 0,54–0,63 benzerlikte. İkinci, üçüncü pasajı (0,50–0,55) olan çok parçalı cevaplardır.
- **Gecikme p50** (sohbet daha kısa prompt okuyor):
  - iç set 465 → 391 ms;
  - dış set 990 → 771 ms.

## 7. Seçim

- **0,60:** dış sette reddi ADR-0061 öncesine (0,867) getiriyor, ama iç sette atıf isabeti 0,848'den 0,768'e, doğru bilgi 0,884'ten 0,759'a düşüyor. Reddedildi.
- **0,53:** iki sette de 0,55'in gerisinde. İç sette bir doğru bilgi daha az kaybediyor (−3).
- **0,55:**
  - dış sette ret +4 soru (sıkı ölçüde +10), atıf isabeti +0,033, bulunma ve doğru bilgi aynı;
  - iç sette ret +2, bulunma ve atıf isabeti −1 soru, doğru bilgi −4 soru (0,884 → 0,848).
  - İç setteki doğru bilgi kaybı gürültünün (±1) üstündedir. Kabul edilen bedel olarak ADR-0063'e yazıldı.

## 8. Dosyalar

- `eval/results/gate-probe-internal.json`, `eval/results/gate-probe-external.json`: `GATE_PROBE=only GATE_PROBE_SPLIT=1`, soru başına kapı girdisi; başlıklı ve başlıksız.
- `eval/results/e2e-internal-kapi-taban.json`, `e2e-internal-p53.json`, `e2e-internal-p55.json`, `e2e-internal-p60.json`: iç set, `GATE_PROBE=1`.
- Dış setin uçtan uca dosyaları (`e2e-external-p53/p55/p60.json`) dış külliyat gibi yerelde kalır. Cevap metinleri o külliyattandır (`eval-data/external/MANIFEST.md`). Özetleri yukarıdadır.
- `eval/results/retrieval.json`: `RetrievalEvalTest`, başlıklı vektörler. recall@1 0,795, recall@5 0,982, MRR 0,876; kapıdan geçen cevaplanabilir 0,973, duran cevaplanamaz 0,214.
