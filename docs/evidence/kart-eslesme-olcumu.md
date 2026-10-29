# Kart eşleşmesi: anahtar olgu süzgeci ölçümü (ADR-0060)

- **Tarih:** 2026-10-29
- **Taban:** `origin/develop` `b36facb`
- **Soru:** eşik üstü bir onaylı kart, yeni sorudan yalnız bir sayı, tutar, tarih, kod, tırnaklı terim ya da olumsuzlukla ayrılıyorsa sunulmasın. Bu süzgeçle kosinüs eşiği 0,95'ten 0,90'a inebilir mi?
- **Koşu:** `CardMatchEvalTest` (qa-core, Docker'sız, model yok). Bütün sayılar bu testte sabitlenmiştir; set ya da süzgeç değişince test kırılır.

## İki set

**1. Etiketli set:** `services/qa/qa-core/src/test/resources/eval/kart-eslesme-set.json`. 70 Türkçe çift: satın alma 22, İK 22, BT 20, ek olarak 6 "intent" çifti. Her çiftte kartın sorusu ve yeni soru var. E-fatura yok.

| Etiket | Sayı | Açıklama |
|---|---|---|
| `paraphrase` | 34 | Aynı soru, kart sunulmalı. Zor örnekler bilerek var: "500 bin" / "500.000", "yüzde 18" / "%18", "on iki" / "12", "7. madde" / "madde 7", "2026 Ocak ayından" / "1 Ocak 2026'dan". |
| `near_miss`, anahtar olgu | 30 | Tutar 4, sayı 4, yüzde 2, miktar/birim 4, tarih/saat 4, kod/madde 5, tırnaklı terim 2, olumsuzluk 5 (`hariç`, `olmayan`, `değil`). Örnek: "500.000 TL" / "50.000 TL", "3 iş günü" / "3 gün", "P1" / "P3", "02:00" / "22:00". |
| `near_miss`, `intent` | 6 | Anahtar olgu aynı, sorulan şey farklı ("ne kadar sürede" / "kime"). Süzgeç bunları göremez; set onları bilerek içerir. |

**Kosinüs değerleri ölçülmedi, atandı.** Çevrimdışı gerçek embedding yok (Ollama ve Docker gerekir). Atama bantları:
- Yeniden ifadeler: 0,86–0,96. ADR-0049 ölçümünde gerçek değerler: medyan 0,855, en yüksek 0,966.
- Anahtar olgu yakın ıskaları: 0,93–0,98. Sav, kosinüsün bunları neredeyse aynı gördüğüdür. ADR-0049'da gerçek değerler: P1/P3 0,933, 0–5/5–15 yıl 0,941.
- Intent yakın ıskaları: 0,84–0,944. `i-1`, ADR-0049'daki `guvenlik-3` çiftinin ölçülen değeridir (0,944).

Süzgecin kararı metinden çıkar, kosinüse bağlı değildir. Atanan değerler yalnız "hangi eşikte kaç çift eşleşir" sorusunu etkiler.

**2. Ölçülmüş set:** `eval/embedding-olcumu/kart-esik-set.json`. ADR-0049'un 30 kartı, 64 yeniden ifadesi ve 30 yakın ıskası; bge-m3 kosinüsleri `docs/evidence/onayli-bilgi-dogrulama.md`'de. Burada yalnız süzgeç koşturuldu: her soru kendi kartıyla.

## Sonuç 1: etiketli set, eşik × süzgeç

Kesinlik (P) = eşleşen yeniden ifade / bütün eşleşmeler. Duyarlılık (R) = eşleşen yeniden ifade / 34. Parantezdeki sayı, eşleşen yakın ıska sayısıdır.

| Eşik | Süzgeç yok: P / R (yakın ıska) | Süzgeç var: P / R (yakın ıska) |
|---|---|---|
| 0,97 | 0,000 / 0,000 (24) | 1,000 / 0,000 (0) |
| 0,96 | 0,038 / 0,029 (25) | 1,000 / 0,029 (0) |
| **0,95 (bugün)** | **0,125 / 0,118 (28)** | **1,000 / 0,118 (0)** |
| 0,94 | 0,189 / 0,206 (30) | 0,875 / 0,206 (1) |
| 0,93 | 0,262 / 0,324 (31) | 0,917 / 0,324 (1) |
| 0,92 | 0,319 / 0,441 (32) | 0,882 / 0,441 (2) |
| 0,91 | 0,377 / 0,588 (33) | 0,864 / 0,559 (3) |
| **0,90 (aday)** | **0,431 / 0,735 (33)** | **0,889 / 0,706 (3)** |
| 0,89 | 0,443 / 0,794 (34) | 0,867 / 0,765 (4) |
| 0,88 | 0,453 / 0,853 (35) | 0,848 / 0,824 (5) |
| 0,85 | 0,493 / 1,000 (35) | 0,868 / 0,971 (5) |

- Süzgeç 30 anahtar olgu yakın ıskasının **30'unu** her eşikte reddetti.
- Süzgeç 34 yeniden ifadenin **1'ini** reddetti: `h-p4`, "Deneme süresi en çok iki ay mı?" (kartın sorusu "en fazla kaç aydır?"). Soru kartta olmayan bir değeri sınıyor; süzgeç bunu farklı soru sayar.
- Süzgeçle bile 0,90'da 3 intent yakın ıska eşleşir: `i-1` 0,944, `i-2` 0,92, `i-5` 0,91. Kesinlik 0,889.
- Süzgeçle kesinliğin 1,0 kaldığı en düşük eşik **0,95**.

## Sonuç 2: ölçülmüş set (ADR-0049), yalnız süzgeç

| | Sayı | Hangileri |
|---|---|---|
| Süzgecin reddettiği gerçek yeniden ifade | 1 / 64 | `erp-3`: "Talebim 48 saattir" / "iki gündür". Aynı süre, başka birim. |
| Süzgecin yakaladığı gerçek yakın ıska | 5 / 30 | `izin-1` (0–5 / 5–15 yıl, 0,941), `erp-1` (kod 20 / 40), `bt-1` (P1 / P3, 0,933), `satin-2` (2 milyon / 200 bin TL), `satin-3` (60 / 90 puan) |

- ADR-0049'da 0,90'ın üstündeki 3 gerçek yakın ıskadan ikisini süzgeç yakalar: `izin-1` ve `bt-1`.
- Üçüncüsü `guvenlik-3`, 0,944: "ne kadar süre içinde" / "kime". Anahtar olgu farkı yok; süzgeç görmez.
- Kalan 25 yakın ıska konu ya da niyet farkıdır (otel / yemek, irsaliye / stok, A / C bölgesi). Bunların kosinüsü zaten düşük; en yükseği 0,944.

## Karar

**Eşik 0,95'te kalır.** Görev koşulu "süzgeç yakın ıskalarda kesinliği 1,0 tutarsa indir"di. Koşul 0,90'da tutmuyor:
- Etiketli sette 0,90'da 3 intent yakın ıskası eşleşiyor.
- Ölçülmüş sette `guvenlik-3` 0,944'te.

İki sette de engel, süzgecin göremeyeceği türden bir farktır.

Süzgeç yine de devreye girer, çünkü 0,95 tek başına anahtar olgu farkını engellemez. Etiketli sette 0,95'te 28 yakın ıska eşleşiyordu; süzgeçle 0. Bu sayılar atanmış kosinüse dayanır. Gerçek bge-m3 ölçümünde bugüne kadar 0,95 üstünde yakın ıska görülmedi. Kullanıcının örnekleri ("500.000 / 50.000 TL", farklı kod, yıl, madde, birim) ise bu sınıfın tam olarak kosinüsün ayıramadığı sınıf olduğunu söyler.

## Yeniden ölçüm

Gerçek kosinüslerle aynı tablo: setteki metinler `eval/embedding-olcumu/kart-esik.mjs`'teki tek `/api/embed` isteğiyle bge-m3'e verilir, `cosine` alanları ölçülen değerle değiştirilir, `CardMatchEvalTest` koşturulur. Docker ve Ollama gerekir; bu PR'da koşulmadı.

İntent ayrımı için reranker ikinci kontrolü (`verso.qa.knowledge.rerank-check`, varsayılan kapalı) GPU kurulumunda ölçülmedi. `min-score` 0,80 bir başlangıç değeridir.
