# ADR-0019: Alıntı penceresi

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras (ADR-0017'de "sonraki iş" diye bırakılan alıntı penceresi işinin yapılması istendi); uygulama ayrıntıları bu ADR
- **İlgili eşik (referans Bölüm 24):** yok (sıcak yola uzak çağrı eklenmez). Alıntının cevabın dayandığı cümleyi göstermediği şikâyetleri ya da eval'de ölçülen isabet düşerse yeniden değerlendirilir.

## Bağlam

- `citations[].excerpt` (ADR-0017) alıntılanan pasajın **başıydı**: en çok 280 karakter. Pasaj bir chunk'tır (yaklaşık 1000 karakter); cevabın dayandığı cümle çoğu zaman ilk 280 karakterin dışında kalıyordu.
- ADR-0017'deki canlı denemede model "yirmi gün" dedi, belgede "on dört gün" yazıyordu. Önizleme doğru belgeyi ve sayfayı gösterdi ama ilgili cümleyi göstermedi; kullanıcı yanlışı ancak belgeyi açarak görebildi.
- Kısıtlar:
  - Sıcak yol iki uzak çağrıyla sınırlı (ADR-0008); seçim için ek model ya da embedding çağrısı yapılamaz.
  - Alıntı yalnız getirilen pasaj metninden gelir, model metninden değil (llm-rules 3.3).
  - Belge içeriği loglanmaz (llm-rules 2.1).
  - API sözleşmesi değişmez: aynı alan, aynı sınır.

## Seçenekler

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| A. Pasajın başı (mevcut) | Basit | İlgili cümle çoğu zaman görünmez | – |
| B. Modelden alıntı istemek | Model hangi cümleye dayandığını bilir | Ek çağrı ya da prompt değişikliği; model metni alıntıya karışır (3.3); eval yeniden gerekir | Yüksek |
| C. Cümle embedding'i ile benzerlik | Anlamsal eşleşme | Pasaj başına ek embedding çağrısı sıcak yolda (ADR-0008 bütçesi) | Orta |
| **D. Sözcük örtüşmesiyle pencere seçimi** | Deterministik, ucuz, ek çağrı yok; Türkçe eklere dayanıklı hâle getirilebilir | Eş anlamlıyı ve başka sözcükle anlatımı yakalamaz | Düşük |

## Karar

**D.** B ve C sıcak yola maliyet ve risk ekler; A sorunu çözmez.

**Cevap tarafı (hangi cümle bu atfa dayanıyor):**
- Model cevabı cümlelere bölünür; cevapta satır sonu da sınırdır (madde listeleri).
- Her `[n]` işareti bulunduğu cümleye bağlanır. Cümleyi açan işaret ("… gündür. [1] Ayrıca …") önceki cümleye aittir.
- Aynı numara birkaç cümlede geçiyorsa cümleler birleştirilir. İşaretler sözcük sayılmaz.

**Pasaj tarafı (pencere):**
- Pasajın boşlukları tek boşluğa indirilir. Pasaj 280 kod noktasına sığıyorsa tamamı alıntıdır: dayanak her durumda içindedir.
- Pasaj cümlelere bölünür. Bölücü:
  - Kural tabanlıdır: `.`, `!`, `?`, `…` (kapanan tırnak ve parantez dahil), ardından boşluk ve küçük harf olmayan bir karakter.
  - Nokta şu durumlarda cümleyi bitirmez: bilinen kısaltma (`Dr.`, `vb.`, `bkz.`, `No.`), tek harf (`A. Yılmaz`, `s. 12`), noktalı kısaltma (`A.Ş.`, `T.C.`), Roma rakamı ve en çok üç basamaklı sayı (`3. madde`, `2. Dünya`: sıra sayısı).
  - Sayı içindeki nokta ve virgülden sonra boşluk gelmediği için `25.000 TL` ve `3.2` hiç bölünmez.
- Bir ve iki cümlelik her pencere, atfı taşıyan cevap cümleleriyle **ortak terimlerine** göre puanlanır. Her farklı terim pencerede bir kez sayılır.
  - **Normalleştirme:** NFKC, Türkçe yerelle küçük harf (`İ`→`i`, `I`→`ı`), aksan ve `ı` katlama (ş→s, ğ→g, ç→c, ö→o, ü→u, ı→i, â→a). Böylece "İZİN", "izin" ve "ızın" aynı sözcüktür.
  - **Basit kök:** en az 5 harfli sözcükler ilk 5 harfleriyle eşleşir ("çalışanlara" ~ "Çalışanın"). Daha kısa sözcükler tam eşleşmelidir.
  - **Sayılar** yalnız rakamlarıyla karşılaştırılır (`25.000` = `25000`) ve **3 puan** alır; sözcükler 1 puan alır. Tutar, gün sayısı ve tarih, cevabın hangi cümleye dayandığının en güçlü işaretidir.
  - Bağlaçlar ve modelin kalıbı ("belgeye göre") ile tek harfli sözcükler sayılmaz.
- En yüksek puanlı pencere seçilir. Eşitlikte kısa pencere, sonra önceki pencere kazanır: yeni terim katmayan ikinci cümle gösterilmez.
- Yalnız prompt'a giden kısım (`verso.qa.max-passage-chars`) kanıt sayılır; model pasajın kesilen kısmına dayanamaz. Varsayılanlarda (1500 > chunk 1000) bütün pasaj prompt'tadır.
- **Kesme (en çok 280 kod noktası, `…` dahil):**
  - Pencere sığıyorsa cümle sınırından başlar ve biter; `…` yoktur.
  - Uzun pencere başından kesilir; sondaki `…` kesilen yeri gösterir. Kesme kelime sınırındadır ve vekil çifti bölmez (ADR-0017'deki kurallar).
  - Baştan kesmek kanıtın bir kısmını dışarıda bırakıyorsa alıntı kanıtın bulunduğu kelimeden başlar ve başa `…` konur. Seçilen başlangıç, en çok kanıtı tutan başlangıçların en sonuncusudur: dayanak alıntının başında görünür.
- **Örtüşme yoksa** (ya da cevapta terim kalmadıysa) alıntı eskisi gibi pasajın başıdır.

**Değişmeyenler:**
- `Citation.excerpt` alanı, tipi ve 280 sınırı aynıdır. Alıntı her zaman pasaj metninin bir parçasıdır (yalnız `…` eklenir); cevap yalnız pencerenin yerini seçer.
- Seçim log'a, metriğe ve span'e yazılmaz; yeni log satırı yoktur.
- Atıf numarası, belge ve sayfa eşlemesi değişmedi. Bu yüzden eval (llm-rules 7.1) gerekmez: retrieval, prompt ve model aynıdır.

## Sonuçlar

- **Olumlu:**
  - Önizleme çoğu durumda cevabın dayandığı cümleyi gösterir. Model bir sayıyı yanlış okuduysa kullanıcı doğrusunu aynı kartta görür.
  - Ek çağrı, bağımlılık ve config yok. İş pasaj başına küçük ve sınırlıdır (bir chunk).
  - API ve panel değişmedi; panel alıntıyı düz metin olarak gösterir, baştaki `…` de metindir.
- **Olumsuz / kabul edilen risk:**
  - Sözcük örtüşmesi eş anlamlıyı yakalamaz. Model başka sözcüklerle anlattıysa (ör. "iki hafta" ↔ "on dört gün") pencere yanlış cümleye ya da pasajın başına düşebilir. Yanlış pencere bile pasaj metnidir; kaynak gösterimi bozulmaz.
  - Basit kök kaba bir yaklaşımdır: 5 harften kısa kökler ("izin" ↔ "izni") eşleşmez; ilk 5 harfi aynı ilgisiz sözcükler eşleşebilir.
  - Kısaltma listesi eksik kalabilir; eksik bir kısaltma bir cümleyi ikiye böler. İki cümlelik pencere bunu çoğu zaman telafi eder.
  - `3.2` ile `32` aynı sayı sayılır (yalnız rakamlar karşılaştırılır).
  - Cümle içinde atıf değiştiğinde ("A [1], B [2].") iki atıf da bütün cümleyi kullanır; her pasaj kendi terimleriyle eşleştiği için sonuç genelde doğrudur.
- **Etkilenen dosyalar:**
  - `services/qa/qa-core`: `SentenceSplitter` (yeni), `ExcerptSelector` (yeni), `CitationExtractor`.
  - Testler: `SentenceSplitterTest`, `ExcerptSelectorTest`, `CitationExtractorTest`, `QuestionApiTest`.
  - `qa-api` `Citation` (yalnız Javadoc); `docs/api-questions-integration-v1.md`.
- **Geri alma yolu:** `CitationExtractor` `ExcerptSelector.select` yerine pasajın başını verir (`select(content, "")`). Sözleşme aynı olduğu için istemci etkilenmez.

## Yeniden değerlendirme koşulu

- Kullanıcılar alıntının ilgisiz cümleyi gösterdiğini bildirirse ya da eval setine alıntı isabeti ölçümü eklenip sonuç düşük çıkarsa: önce kısaltma ve durak listeleri, sonra C (cümle embedding'i; ADR-0008 bütçesiyle).
- Prompt modelden atıfla birlikte alıntı da istemeye başlarsa: B, yine sunucuda pasaj metniyle doğrulanarak.
