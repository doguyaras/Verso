# ADR-0063: Başlıklı vektörlerde kapı kalibrasyonu

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-30
- **Karar verenler:** doguyaras. İstek: ADR-0061'den sonra dış sette cevaplanamaz soruların reddi düştü. Kapı eşiklerini başlıklı vektörlere göre kanıtla yeniden seçmek.
- **Genişlettiği:** ADR-0022 (cevaplanabilirlik kapısı ve prompt pasaj tabanı), ADR-0061 (ölçüm planı adım 4–6).
- **İlgili eşik (referans Bölüm 24):** llm-rules 3.4 (sınıf BA: `verso.qa.*`), eval seti (llm-rules 7.1).
- **Kanıt:** `docs/evidence/kapi-kalibrasyonu-olcumu.md`.

## Bağlam

- ADR-0061 her parçanın embedding girdisine belge başlığını ve bölümü ekledi. Dış sette (200 yönetmelik, 45 cevaplanamaz soru) ret düştü:

  | Koşu | Ret (`notFoundOrOwnRefusal`) | Atıf isabeti |
  |---|---|---|
  | ADR-0061 öncesi | 0,867 | 0,646 |
  | Başlıklı, koşu 1 | 0,711 | 0,695 |
  | Başlıklı, koşu 2 | 0,667 | 0,698 |

- Varsayım şuydu: kapı (0,47 / 0,44 / 0,045) başlıksız vektörlerle seçildi, başlık kosinüsleri yükseltti, konusu belgede olan cevaplanamazlar kapıdan geçiyor.
- **Ölçüm bu varsayımı doğrulamadı.** Soru başına kapı girdisi yeni `scripts/gate-probe.mjs` ile ölçüldü; `RetrievalEvalTest` ile dört basamakta aynı.
  - Dış sette kapı bugün 45 cevaplanamazdan 1'ini durduruyor. Başlıksız vektörlerle de en çok 2'sini durdururdu. ADR-0061 öncesindeki 39 reddin 37 ya da daha fazlası chat modelinin kararıdır (başlıksız kosinüsle tahmin).
  - Kapının durdurabildiği sorular konu dışı olanlardır (0,44–0,52) ve model onları zaten reddediyor. Konusu belgede olan cevaplanamazlar (0,51–0,69) cevaplanabilirlerin arasında kalıyor (dış cevaplanabilir p10 0,613, ortanca 0,704).
  - Izgarada hiçbir kapı ayarı dış setin tahmini reddini artırmadı; dışta 10 cevaplanabilir soru kaybına izin verilse bile.
- Başlık iki sette kosinüsü ters yöne kaydırdı:
  - **Dış sette yukarı.** Yönetmelik adları soruların kelimeleriyle örtüşüyor.
  - **İç sette aşağı.** Üç cevaplanabilir soru kapının altına indi: bt-16, isg-10 ve depo-17; kapıdan geçen 112'den 109'a düştü. `RetrievalEvalTest`'in "geçen = 1,00" koruması kırmızıya döndü.
- **Değişen, modelin gördüğü pasajlardır.**
  - Prompt'a en benzer pasaj ve 0,50 ya da üstündekiler girer (ADR-0022).
  - Dış sette cevaplanamazların prompt'u başlıkla ortalama 3,0'dan 3,4 pasaja çıktı.
  - Aynı pasaj sayısında da model daha az reddediyor: 4–5 pasajlı sorularda 12/14'ten 10/20'ye.
  - ADR-0022 bu etkiyi ölçmüştü: pasaj ne kadar çoksa model o kadar "bir şey bulur".

## Seçenekler

| Seçenek | Ölçüm | Sonuç |
|---|---|---|
| Kapı eşiklerini yükseltmek (ör. 0,52 / 0,42) | İç: +4 duran, −1 cevaplanabilir. Dış: +4 duran, ama tahmini ret değişmez (durdurulanları model zaten reddediyor). Bedeli 3 dış set kısaltma sorusu (mrk-16, mrk-34, mrk-38), yani ADR-0061'in hedeflediği sınıf | Reddedildi |
| Kapıyı iç setin 3 sorusu için gevşetmek (0,46 / 0,435 / 0,07) | 112/112 geçer, ama duran cevaplanamaz 6'dan 4'e iner; görevin tersi | Reddedildi |
| Kapının başlıksız kosinüse bakması (parça başına ikinci vektör ya da soru anında yeniden embedding) | Ayırma gücü (AUC) iç sette 0,799 → 0,833, dış sette 0,917 → 0,856. Saklama ya da sıcak yol maliyeti | Reddedildi: dış sette daha kötü |
| Prompt tabanını göreli yapmak (en iyinin δ altı) | Cevaplanamazların ilk beşi düz; göreli sayı onlarda daha yüksek (dış, δ = 0,08: cevaplanabilir 3,36, cevaplanamaz 3,62). Kod değişikliği | Reddedildi |
| **`passage-min-similarity` 0,50 → 0,55** | Dış: ret 0,667 → 0,756, atıf isabeti 0,698 → 0,731; bulunma ve doğru bilgi aynı. İç: ret 0,714 → 0,786; doğru bilgi 0,884 → 0,848 | **Seçildi** |
| `passage-min-similarity` 0,53 | Dış ret 0,733, atıf isabeti 0,705. İç doğru bilgi 0,857 | 0,55'in gerisinde |
| `passage-min-similarity` 0,60 | Dış ret 0,867 (ADR-0061 öncesi), atıf isabeti 0,725. İç atıf isabeti 0,768, doğru bilgi 0,759 | Reddedildi: iç sette büyük kayıp |

## Karar

**`verso.qa.passage-min-similarity` 0,55** (`config/verso.yml`, `QaProperties` varsayılanı):
- Kapı açıldıktan sonra prompt'a en benzer pasaj ve benzerliği 0,55 ya da üstü olan diğerleri girer. Yalnız ayardır; kural ve kod aynıdır (`AnswerabilityGate.promptPassages`).
- Seçim yöntemi: 0,50 / 0,53 / 0,55 / 0,60 iki ayrı külliyatta uçtan uca ölçüldü. Taban tek bir sayıdır ve etkisi model cevabından okunur, bu yüzden ADR-0022'nin belge grubu çapraz doğrulaması yerine iki bağımsız set kullanıldı. 0,55, dış setin en iyisi (0,60 dışında) ve iç setin kabul edilebilir kaybıdır.

**Kapı eşikleri değişmez:** `min-similarity` 0,47, `lexical-min-similarity` 0,44, `specific-term-max-share` 0,045.
- Başlıklı vektörlerle de konu dışı soruları durduruyorlar (iç 6/28, dış 1/45).
- Konusu belgede olan cevaplanamazları ucuz bir retrieval sinyali ayıramıyor. ADR-0022'nin "bilinen sınır"ı aynen geçerli.

**Ölçüm aracı:**
- `scripts/gate-probe.mjs`, `GATE_PROBE=1|only` ve `GATE_PROBE_SPLIT=1` (`eval.mjs`): soru başına kapı girdisi, her sette ve her külliyatta.
- `eval.mjs` "kapının durdurduğu" sayısını artık kendi koşusundan alır. Dış sette `retrieval.json` okunmaz.
- Prob, `RetrievalRepository.hybrid` ve `rarestSharedTermCounts`'un SQL'ini kopyalar; ikisi birlikte değişir (dosyanın başında yazılı). Prob soruyu yerel yığının embedding sunucusuna gönderir; belge ya da soru makineden çıkmaz.

**`RetrievalEvalTest` koruması:**
- Cevaplanabilir kapıdan geçen ≥ 0,97 (109/112).
- İç setteki üç soru kaybı (bt-16 0,4358, isg-10 0,4611, depo-17 0,4625) ADR-0061'in ölçülmüş ve kabul edilmiş bedelidir. Bir soru daha kaybedilirse koruma kırmızı olur.

**Sonuç sayıları** (canlı yığın, GPU, aynı gün; "önce" 0,50, "sonra" 0,55):

| Ölçüm | İç, önce | İç, sonra | Dış, önce | Dış, sonra |
|---|---|---|---|---|
| Kaynak bulundu | 0,902 | 0,893 | 0,925 | 0,925 |
| Atıf isabeti | 0,848 | 0,839 | 0,698 | **0,731** |
| Atıf kesinliği | 0,927 | 0,925 | 0,590 | 0,621 |
| Doğru bilgi | 0,884 | **0,848** | 0,784 | 0,784 |
| "Bulunamadı", sıkı | 0,679 | 0,750 | 0,489 | **0,711** |
| Ret, modelinki dahil | 0,714 | **0,786** | 0,667 | **0,756** |
| `on-topic` ret | 12/20 | 14/20 | 22/35 | 26/35 |
| Gecikme p50 | 465 ms | 391 ms | 990 ms | 771 ms |

Gürültü, aynı ayarla iki koşu arasındaki farktır:
- dış sette atıf isabetinde +7/−6 soru, retlerde 2 soru;
- iç sette 1 soru.

Dış set atıf isabeti +13/−3 soru değişti.

## Sonuçlar

- **Olumlu:**
  - Dış sette ret +4 soru (sıkı ölçüde +10) ve atıf isabeti +0,033. Kazanç benzer belgeleri ayırma sorularında (`disambiguation` 42 → 49/68) ve merkez yönetmeliklerinde (31 → 37/58).
  - İç sette ret +2 soru.
  - Prompt kısaldı: gecikme p50 iki sette de düştü.
  - Kapı girdisi artık her eval koşusunda soru başına kayıtlı. Bir sonraki kalibrasyon tahmin değil ölçümle yapılır.
- **Olumsuz / kabul edilen risk:**
  - **İç sette doğru bilgi 0,884'ten 0,848'e düştü** (−4 soru: depo-13, erp-14, erp-17, isg-4, isg-8; +1 bt-3). Gürültünün (±1) üstündedir. Kaybedilen sorular, cevabı ikinci bir 0,50–0,55 pasajında olan çok parçalı sorulardır.
  - Kaynak bulundu ve atıf isabeti iç sette −1 soru.
  - Dış sette kısaltma sorularının atıf isabeti 18'den 15/28'e indi.
  - Ret ADR-0061 öncesine dönmedi (dış 0,756, önce 0,867). Kalan fark, konusu belgede olan sorularda modelin konuyu tutan pasajdan cevap üretmesidir. Retrieval eşiği bunu ayıramıyor.
  - İç sette ADR-0061'in üç cevaplanabilir kaybı kalıyor (kapı 109/112).
  - Eşik iki setle seçildi; ikisi de tek yazarlı. Gerçek müşteri setinde yeniden ölçülür.
- **Etkilenen dosyalar:**
  - `config/verso.yml`; `qa-core`: `QaProperties` (varsayılan ve açıklama), `AnswerabilityGate` (açıklama), `AnswerabilityGateTest`;
  - `RetrievalEvalTest` (koruma);
  - `scripts/gate-probe.mjs`, `scripts/gate-probe.test.js`, `scripts/eval.mjs`;
  - `eval/README.md`, `eval/results/*`, ADR-0061, `docs/decisions.md`, `docs/ai/llm-rules.md`.
  - API, DTO, hata kodu, ortam değişkeni, secret, port ya da yeni uzak çağrı yok. Yeniden indeksleme gerekmez.
- **Geri alma yolu:** `verso.qa.passage-min-similarity: 0.50`. Spring'in gevşek bağlamasıyla imaj yeniden kurulmadan da verilebilir: `VERSO_QA_PASSAGEMINSIMILARITY=0.50`.

## Yeniden değerlendirme koşulu

- Embedding girdisi ya da başlık kuralı değişirse (`ChunkContext.VERSION`, `embed-header`) prob iki sette yeniden koşulur ve bu taban yeniden seçilir.
- Chat modeli değişirse (gemma4:e2b dışında) uçtan uca tarama yeniden yapılır: ret davranışı modelindir.
- Konusu belgede olan cevaplanamazlar için retrieval dışı bir sinyal (rerank skoru, ADR-0043; cevap dayanak kontrolü, ADR-0047) ölçüldüğünde kapı yeniden kurulur.
- Gerçek bir müşteri belge setiyle eval yapıldığında.
