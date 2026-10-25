# Cevap dayanaklılığı ölçümü (ADR-0047)

- **Tarih:** 2026-10-25
- **Taban:** `origin/develop` `d352e57`
- **Soru:** cevapta, atıf yaptığı pasajın desteklemediği cümleyi bulabilir miyiz; hangi aday ne kadar kesin, ne kadar hızlı?

## Etiketli set

`eval/grounding-set.json`, 192 cümle; 57'si desteklenmiyor, 135'i destekleniyor.

**Kaynak:**
- `eval/results/e2e-rerank-off-gpu.json`: gemma4:e2b, GPU, 140 soru.
- Atıflı her cevap cümlesi alındı (129).
- Pasaj olarak atıf yapılan sayfanın metni kullanıldı: `samples/src/*.txt`, sayfalar `---` ile ayrılmış. Chunk sayfayı aşmaz (llm-rules 6.3); sayfa, chunk'tan biraz geniştir. Bu yüzden ölçüm destek tarafında hafif cömerttir.
- `[n]` ile sayfa eşlemesi: cevaptaki k'inci farklı işaret, sonuçtaki k'inci atıftır.

**Etiketleme:** her cümle atıf yaptığı sayfa okunarak elle etiketlendi.

**Doğal cümleler:** 128 doğal cümleden 3'ü desteklenmiyor:
- `erp-10-s1`: "1.000.000 TL ile 1.000.000 TL arası". Belgede 100.000.
- `bt-20-s0`: "Çözüldü" durumundaki kayıt yeniden açılamaz. Belgede bu kural "Kapatıldı" için.
- `crm-7-s0`: P2 için 2 iş günü. Belgede 1.

Bir ret cümlesi ("Belgelerde … bilgi bulunmamaktadır") iddia olmadığı için set dışında bırakıldı.

**Bozulmuş kopyalar:** doğal cümlelerden 64 kopya üretildi:

| Tür | Sayı | Örnek |
|---|---|---|
| Rakamla sayı | 22 | 900 → 1.200 TL; 4 saat → 6 saat |
| Sözle sayı | 8 | on altı → on dört hafta |
| Olumsuzlama | 12 | devredilebilir → devredilemez |
| Ad, rol | 9 | Finans Müdürü → Genel Müdür |
| Kod | 3 | SAT-E058 → SAT-E027; P3 → P1 |
| Desteklenen yeniden yazım | 10 | "on dört" → "14"; "takım lideri" → "ekip lideri" |

**Dışarıda tutulan set:** başka beş uçtan uca koşunun (`e2e-rerank-on`, `e2e-rerank-on2`, `e2e-samples-jdk`, `e2e-samples-docling`, `e2e-gemma4_e2b-gpu`) etiketli setle örtüşmeyen 110 atıflı cümlesi. Bu cümleler etiketlenmedi. Yalnız işaretlenenler elle incelendi. Amaç gerçek trafikteki yanlış alarm oranıdır.

## Adaylar

| Aday | Lisans (model kartından doğrulandı) | Sunum |
|---|---|---|
| (a) `MoritzLaurer/mDeBERTa-v3-base-xnli-multilingual-nli-2mil7`, revision `b5113eb3…` | MIT (`LICENSE` dosyası ve kart) | ONNX Runtime 1.22 (CPU, 2 vCPU, `python:3.12-slim` ölçüm konteyneri, 3 GB sınır); fp32 `onnx/model.onnx` (1,1 GB, SHA-256 `e9238a33…`) ve int8 `onnx/model_quantized.onnx` (339 MB, `18307c3b…`) |
| (b) Sözcüksel sayı/kod tutarlılığı (ADR-0020 makinesinin genişlemesi) | – (kendi kodumuz) | Süreç içi Java, `AnchorCheck` |
| (c) Yerel gemma ile LLM-as-judge | Apache 2.0 | Ölçülmedi, aşağıya bakın |

Dışarıda bırakılanlar (lisans ya da dil):
- Bespoke-MiniCheck-7B ve Patronus Lynx: CC-BY-NC-4.0, ticari kullanım yok.
- Vectara HHEM-2.1-Open: Apache 2.0 ama yalnız İngilizce ve `trust_remote_code` istiyor. Çok dilli HHEM-2.3 yalnız Vectara platformunda.
- Granite Guardian 3.3: Apache 2.0, "yalnız İngilizce eğitildi ve test edildi".
- LettuceDetect: MIT, desteklediği diller arasında Türkçe yok.

**Sunucu:** HF TEI (ADR-0043'ün yan konteyner kalıbı) DeBERTa-v2 sınıflandırıcılarını desteklemiyor. README'de yalnız CamemBERT, RoBERTa, XLM-RoBERTa, GTE ve ModernBERT var.

### (a) NLI

- Öncül pasajdır. Hipotez cevap cümlesidir.
- Skor P(entailment).
- Varyantlar:
  - `full`: bütün pasaj, en çok 1500 karakter.
  - `windows`: pasajın her satırı ve satır içi 1–2 cümlelik pencereler; ortalama 38 pencere.
  - `max(full, windows)`: ikisinin en yükseği.
- Eşiğin altı "desteklenmiyor" sayılır.

| Varyant | Eşik | Kesinlik | Duyarlılık | Dışarıda tutulan sette işaretlenen |
|---|---|---|---|---|
| int8, `full` | < 0,5 | 0,307 | 0,947 | – |
| int8, `windows` | < 0,3 | 0,795 | 0,614 | – |
| fp32, `full` | < 0,05 | 0,500 | 0,860 | – |
| fp32, `windows` | < 0,1 | 0,923 | 0,632 | – |
| fp32, `max(full, windows)` | < 0,05 | 1,000 | 0,474 | 2/110 |
| fp32, `max(full, windows)` | < 0,1 | 1,000 | 0,614 | 5/110 |
| fp32, `max(full, windows)` | < 0,2 | 0,951 | 0,684 | 7/110 |
| fp32, `max(full, windows)` | < 0,3 | 0,933 | 0,737 | 11/110 |
| fp32, sözcük örtüşmesiyle ilk 5 pencere + `full` | < 0,1 | 1,000 | 0,754 | 8/110 |
| fp32 `max` < 0,1 **veya** (b1) | – | 1,000 | 0,667 | – |

**Dışarıda tutulan sette fp32 `max` < 0,1'in işaretlediği 5 cümle** (elle incelendi):
- 1 doğru: "Gümüş sözleşmeli müşterilerin P1 … 30 dakikadır". Belgede bu kural Altın için.
- 4 yanlış:
  - Çıkarım: "30.000 TL'lik bir masraf için Genel Müdür onayı gerekebilir".
  - İki ret cümlesi.
  - Eksik ama doğru bir cümle: 1.000.000 TL üzeri için üç onay. Belgede "Yukarıdakilere ek olarak Yönetim Kurulu" yazıyor.

Gerçek trafikte kesinlik ≈ 0,2. Desteklenen her 25 cümleden biri yanlışlıkla işaretlenir.

**Gecikme** (cümle başına, 2 vCPU):

| Model | `full` p50 / p95 | Bütün pencereler p50 / p95 |
|---|---|---|
| int8 | 112 / 139 ms | 0,50 / 0,88 sn |
| fp32 | 200 / 235 ms | 1,0 / 1,8 sn |

İlk 5 pencere + `full` fp32'de yaklaşık 0,33 sn sürer.

**Diğer gözlemler:**
- int8 sürüm kullanılamaz: `full`'da bütün eşiklerde kesinlik ≤ 0,33.
- Tablo satırları (`|` ile yazılmış) NLI'nin en zayıf yeri: doğal yanlış alarmların çoğu tablo satırlarına dayanan cümlelerde.

### (b) Sözcüksel sayı ve kod kontrolü

Cümledeki her **çapa**, atıf yapılan pasajda (ya da soruda) bulunmalıdır. Çapalar:
- Rakamla sayı: yalnız rakamlar karşılaştırılır (ADR-0020).
- Türkçe sayı sözcüğü: "on dört", "yirmi altı", "beşinci".
- Kod: büyük harf ve rakam birlikte; `P1`, `SAT-E058`, `WMS-204`.

Çapanın yalnız pasajda geçmesi yetmez. Pasajda, cümlede yanında duran sözcüklerden biriyle yan yana durmalıdır:
- Cümlede her iki yandan en yakın 2 terim, pasajda 3 terim alınır.
- Terim, katlanmış ilk 5 harftir. Ön ekle eşleşir: "saat" ~ "saattir".
- Kod için pasajda bütün satır da yeterlidir (tablo anahtarı).

| Varyant | Kesinlik | Duyarlılık | Doğal 128 cümlede işaretlenen | Dışarıda tutulan sette işaretlenen |
|---|---|---|---|---|
| (b1) Çapa pasajın herhangi bir yerinde | 1,000 | 0,351 | 0 | – |
| (b4) Komşu terimle yan yana, pasajda 2 terim | 1,000 | 0,491 | 1 (`crm-7`, doğru) | 1 |
| **(b4) Komşu terimle yan yana, pasajda 3 terim; kod için satır (seçilen)** | **1,000** | **0,456** | **1 (`crm-7`, doğru)** | **1** |

**Seçilen varyant türe göre** (işaretlenen / toplam):

| Tür | İşaretlenen |
|---|---|
| Rakamla sayı | 18/22 |
| Sözle sayı | 5/8 |
| Kod | 2/3 |
| Olumsuzlama | 0/12 |
| Ad, rol | 0/9 |
| Desteklenen yeniden yazım | 0/10 |

Ulaşabildiği tür sayı ve koddur: 25/33 = 0,76.

**Dışarıda tutulan setteki tek işaret:** "… en geç 4 saat içinde yerleştirilmelidir …". Cümlenin atıf yaptığı sayfada yalnız "24 saat" geçiyor. Kural belgenin başka bir sayfasında. Atıf düzeyinde doğru bir işaret.

**Gecikme:** prototipte cümle başına ~0,45 ms. Java'da aynı düzen ya da daha az. Ek bellek ve uzak çağrı yok.

**Kaçırılanlar:**
- Aynı pasajda aynı komşularla duran başka bir kuralın sayısı. Örnek: evlenme izni "üç iş günü" ↔ babalık "üç iş günü".
- Olumsuzlama ve ad değişikliği hiç görülmez. Tasarım gereği.

**Prototip ve port:**
- Prototip: Node, ölçüm dizininde. Java portu `AnchorCheck`.
- Port, etiketli sette aynı sonucu verir: `GroundingEvalTest`, 26/57, yanlış alarm 0.
- Porta iki gevşetme eklendi. İkisi de yalnız işaret kaldırabilir:
  - Cümle pasajla en az 2 terimi paylaşmıyorsa (başka dilde pasaj) komşuluk aranmaz.
  - Pasajda İngilizce sayı sözcükleri okunur.
- İkisi de etiketli sonucu değiştirmedi.

### (c) LLM-as-judge

Ölçülmedi:
- (b) kesin çıktı. Talimat (c)'yi yalnız (a) ve (b) zayıfsa istiyordu.
- (c) tek sohbet slotunu (`chat-concurrency: 1`) ikinci bir çağrıyla tutar.
- Cevap süresini yaklaşık iki katına çıkarır.

## Karar

- **(b) ürüne girer, varsayılan açık.**
  - Kesinlik ölçülen her yerde 1,00: etiketli set, doğal cümleler, dışarıda tutulan set.
  - Duyarlılık 0,46: yalnız sayı ve kodlar.
  - Maliyet yok.
- **(a) ürüne girmez.**
  - Etiketli sette güçlü: fp32, < 0,1, kesinlik 1,00, duyarlılık 0,61.
  - Gerçek cevaplarda işaretlerinin çoğu yanlış: 5'te 1 doğru.
  - TEI sunamıyor. Kendi Python yan konteynerimiz ya da JVM içinde ONNX Runtime ile SentencePiece gerekir.
  - fp32 model 1,1 GB. Uygulama konteynerinin sınırı 1,5 GiB.
  - Cümle başına 0,3–1,8 sn CPU.
  - Yeniden değerlendirme koşulu ADR-0047'de.
- Ölçüm konteyneri ve imajı silindi. Yan konteyner eklenmedi.

## Sınırlar

- Set küçük ve sentetik. Etiketleyen tek kişi. Bozulmalar elle seçildi.
- (b)'nin ayarları (komşu sayısı, kod satırı) aynı setle seçildi. Dışarıda tutulan set etiketsiz; yalnız yanlış alarm için bakıldı.
- Pasaj olarak sayfa kullanıldı, chunk değil.
- Model tek: gemma4:e2b. Büyük bir model daha az sayı uydurur. Bulut modelleri başka dillerde başka hatalar yapar.

## Yeniden üretme

- Etiketli set: `eval/grounding-set.json`.
- Kontrol: `./mvnw -pl services/qa/qa-core -am test -Dtest=GroundingEvalTest -Dsurefire.failIfNoSpecifiedTests=false`.
- NLI ölçümü bir kerelik yapıldı. Betikler depoya girmedi; yöntem yukarıda.
