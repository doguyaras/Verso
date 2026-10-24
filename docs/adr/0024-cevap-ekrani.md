# ADR-0024: Cevap ekranı (alıntı vurgusu, kaynak sütunu, takip soruları)

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras. İstek: Soru sor ekranı kurumsal yapay zekâ ürünlerinin en iyileri düzeyine gelsin; yalnız panel, backend değişmez. Yol haritası `ui_ux.md` maddeleri 1, 5, 6 ve 7.
- **İlgili eşik (referans Bölüm 24):** yok (sunum katmanı). Yeniden değerlendirme koşulu aşağıda.
- **Genişlettiği:** ADR-0017'nin "Soru ekranı" bölümü (atıf önizlemesi, kaynak kartları, cevap türleri) ve ADR-0018'in görsel dili. ADR-0021'de açık kalan "Verso" metinleri burada ürün adına bağlandı.

## Bağlam

- ADR-0020 ile `citations[].excerpt` pasajın başı değil, cevabın dayandığı cümleyi taşıyan pencere oldu. Panel bunu yalnız `[n]` üzerine gelince gösteriyordu. Kaynak kartlarında alıntı yoktu, hangi sözcüğün eşleştiği görünmüyordu.
- NN/g'ye göre atıflara nadiren tıklanır. Doğrulama ancak destekleyen pasaj iddianın yanında görünürse ucuzlar. Takip soruları "devam et" yerine şüpheci olmalıdır.
- Kısıtlar değişmedi:
  - derleme adımı yok, vanilla ES modülleri;
  - CSP aynen kalır: `script-src 'self'`, `style-src 'self'`, Trusted Types `'none'`;
  - `innerHTML` ve satır içi stil yok;
  - sessionStorage'da yalnız tercihler tutulur;
  - `prefers-reduced-motion` desteklenir;
  - kontrast çiftleri palet testinden geçer.
- API her soruyu tek başına cevaplar; konuşma geçmişi sunucuya gitmez (ADR-0012).

## Seçenekler

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| A. Alıntıyı karta düz metin koymak | Basit | Neden bu pasajın seçildiği görünmez | S |
| **B. Alıntıda cevapla ortak sözcükleri işaretlemek; eşleme sunucunun kurallarıyla, panelde** | Ek çağrı yok; işaret sunucunun seçimiyle tutarlı | Kurallar iki yerde; sapma riski testle kapatılır | M |
| C. Sunucunun işaret aralıklarını döndürmesi (`highlightStart/End`) | Tek kaynak | Backend ve sözleşme değişir; bu iş kapsamında yasak | M |

## Karar

**B.** C, API ekiyle sonraki bir işte yapılabilir. O zaman panel kendi eşlemesini bırakır.

**Alıntı ve vurgu.**
- Kaynak kartı belgeye göre gruplanır. Her atıf için sayfa ya da bölüm ve alıntı gösterilir. Kapalı kartta alıntı 4 satıra kısalır; "Tamamını göster" yalnız kısalan kartta çıkar.
- Alıntıda, atfı taşıyan cevap cümlesiyle ortak sözcükler işaretlenir.
  - Kurallar sunucunun kurallarıdır (`panel/js/excerpt.js`). Kaynakları `qa-core` `ExcerptSelector`, `SentenceSplitter` ve `CitationExtractor`'dır:
    - NFKC ve Türkçe küçük harf; `ı`/`i` ve aksan katlama;
    - 5 harfli kök, sayılar yalnız rakamlarıyla;
    - aynı durak sözcükleri;
    - aynı cümle sınırları;
    - cümleyi açan işaret önceki cümleye aittir.
  - Test, durak sözcüğü ve kısaltma listelerini Java dosyalarından okuyup karşılaştırır. 17 girdilik bir karşılaştırmada JS ve Java aynı anahtarları ve cümle aralıklarını verdi.
- Vurgu CSS Custom Highlight API ile yapılır (`CSS.highlights`, `::highlight(verso-match)`). Metin düğümleri değişmez; kopyalama ve ekran okuyucu düz metni görür.
  - API yoksa eşleşen sözcükler `createElement('mark')` ile sarılır. HTML dizesi kullanılmaz.
- Sunucunun baştaki ve sondaki `…` işareti kesme olarak gösterilir: soluk ve açıklamalı. Belgedeki üç nokta metin olarak kalır.

**Bağlantı.**
- `[n]` ya da karttaki atıf satırının üzerine gelinince ya da odaklanınca ikisi birlikte yanar. Cevapta o kaynağa dayanan cümle de hafifçe boyanır (`::highlight(verso-cited)`).
- `[n]`'e tıklanınca kart açılır ve 600 ms yanar (önceden 1200 ms). Karttaki sayfa düğmesi cevaptaki işarete götürür.

**Geniş ekran (≥1200 px).**
- Kaynak kartları cevabın yanında yapışkan bir sütundadır (300 px).
- `[n]` işaretleri "belge · s. 3" ya da "belge · böl. 2" çipidir; dosya adı 22 karakterde kısalır.
- Erişilebilir ad her genişlikte tam kaynaktır ("Kaynak 1: dosya.pdf, sayfa 3"). Önizleme `role="tooltip"` olarak kalır.
- Dar ekranda kartlar cevabın altındadır, işaret sayıdır.

**Eylemler.**
- "Kopyala" cevabı Markdown olarak kopyalar: atıflar dipnottur (`[^n]`), dipnotta dosya, sayfa ve alıntı yer alır. Cevap, alıntı ve dosya adı Markdown'da kaçışlanır (`\`, `` ` ``, `[`, `]`, `<`, `>`); belgeye gömülü bir bağlantı, resim ya da HTML dışa aktarılan dosyada etkisiz kalır. Bidi kontrol karakterleri silinir.
- ".md indir" aynı metni soruyu başlık yaparak indirir. Altına ürün adı, mod, model ve tarih eklenir.
  - Yöntem: `Blob` + `URL.createObjectURL` + `<a download>`. Bu bir indirme, getirme ya da çerçeve değil; CSP'de yönergesi yoktur. Doğrulamada ihlal olayı görülmedi; CSP'ye `blob:` eklenmedi. URL 1 sn sonra bırakılır.
  - Dosya adı sorudan türetilir: ASCII harf, rakam ve `-`, en çok 48 karakter.
- Eylemler fareyle kullanılan ekranlarda soluk durur (`--fg-subtle`). Üzerine gelince ya da odakta belirginleşir. Dokunmatikte her zaman normaldir.
- "Durdur"dan sonra odak soru kutusuna döner.

**Klavye.**
- Her soru-cevap bir `article`'dır. `tabindex="-1"` taşır, sekme durağı değildir. Etiketi sorunun kendisidir.
- PageUp/PageDown ve Alt+↑/↓ turlar arasında gezer. Alt+↑/↓ soru kutusunda da çalışır. PageUp/PageDown çok satırlı bir soru yazılırken kutuda kalır.
- Esc sırayla kapatır:
  - "Bunu sor" düğmesi;
  - açılmış önizleme;
  - üzerinde durulan önizleme (WCAG 1.4.13);
  - açık kart.

**Takip soruları.**
- Her kaynaklı ya da kaynaksız cevabın altında üç şüpheci soru durur: "Bu hangi belgeye dayanıyor?", "Aksi bir madde var mı?", "Bunu daha kısa anlat".
- API konuşmayı bilmediği için her biri önceki soruyu adıyla anan yeni ve bütün bir sorudur. "Bu" ya da "buna" demez. Çipin ipucu gönderilecek soruyu aynen gösterir. Soru 1000 karakter sınırını aşmasın diye önceki soru kısaltılır.
- Kaynaklı cevapta "Bu hangi belgeye dayanıyor?" panelde cevaplanır: kartlar yanar ve belgeler sayılır, model çağrılmaz. Kaynaksız cevapta yerini "Kaynaklı olarak yeniden sor" alır; yeni cevabın kaynakları eskisinin kanıtı sayılmasın diye.
- "Bulunamadı"dan sonra takip sorusu yoktur; çevresinde soru sormak tahmine davet eder.
- **Bunu sor:** cevapta ya da bir alıntıda seçilen metnin üstünde küçük bir düğme çıkar. Düğme seçimi tırnak içinde soru kutusuna koyar ve odağı oraya verir. Seçim tek satıra indirilir ve 300 karaktere kısaltılır; hiçbir şey kendiliğinden gönderilmez.

**Durumlar.** API'nin üç sonucu ve hatalar ayrı görünür:
- **Kaynaklı.**
- **Bulunamadı:** "Belgelerde dayanak bulunamadığı için tahmin yürütülmedi."
- **Kaynak gösterilemedi:** doğru olabilir ama doğrulanamadı.
- **Hatalar** API kodlarına göre:
  - model ya da servis kapalı (11001, 10030, 90103, 99997, 99998, 5xx);
  - yoğun (11002, 90102);
  - belgeler yeniden hazırlanmalı (10031, tekrar yok);
  - soru reddedildi (90000, 11010, 11011; "Soruyu düzenle");
  - oturum bitti;
  - bağlantı yok.
- `Retry-After` varsa "Tekrar dene" süre dolana kadar geri sayar.

**Mobil.**
- Klavye açılınca `visualViewport` ile soru kutusu klavyenin üstüne çıkar ve alt sekme çubuğu gizlenir. Yalnız soru kutusu odaktayken uygulanır: masaüstünde yakınlaştırma yanlış tetiklemesin.
- Dokunmatikte cevap ekranının bütün denetimleri en az 44 px'tir. Satır içi `[n]` satır yüksekliğini bozmasın diye görünmez bir dokunma alanıyla büyütülür.

**Ürün adı (ADR-0021).** "Verso cevapladı" ve "Verso hata yapabilir" artık kurulumun ürün adını kullanır, hâl eki almadan: "X cevap verdi", "X hata yapabilir; cevabı kaynağından doğrulayın."

**Görsel dil (ADR-0018).**
- Fildişi ve Obscur temaları, Fraunces ve antik altın aynen kalır.
- Vurgu `--accent-bg-hover` zemininde `--fg-default` metindir. Boyanan cümle `--accent-bg-subtle` zemindedir.
- Palet testine yedi çift eklendi (iki tema × 7), hepsi WCAG 2.2 AA'yı geçiyor.

## Sonuçlar

**Olumlu:**
- Kullanıcı cevabın dayandığı cümleyi ve neden o cümlenin seçildiğini aynı bakışta görür.
- Geniş ekranda kaynak, cevabı kapatmadan yanında durur.
- Ek çağrı, bağımlılık ya da CSP değişikliği yok. Backend ve sözleşme aynı.

**Olumsuz / kabul edilen risk:**
- **Eşleme kuralları iki yerde.** Java'da kural değişirse panelin vurgusu sapabilir. Liste karşılaştırması testte. Kural mantığındaki bir değişiklik ise ancak elle aktarılır.
- **Vurgu yalnız sözcük örtüşmesidir** (ADR-0020'nin sınırı). "İki hafta" ile "on dört gün" eşleşmez. Kısa sözcükler ("günü" ↔ "günüdür") eşleşmeyebilir.
- **Takip soruları yeni sorudur.** Şablona eklenen sözcükler retrieval benzerliğini değiştirebilir; eval'de ölçülmedi.
- **İndirmenin dosyaya yazıldığı** gerçek bir kaydetme penceresinde denenmedi. Kullanıcının masaüstünde pencere açmamak için doğrulama `URL.createObjectURL` ve `click` taklit edilerek yapıldı.
- **Dokunmatik klavye davranışı** emülasyonda doğrulanamadı; mantık ve CSS testte.

**Etkilenen dosyalar:**
- yeni `panel/js/{answer,excerpt,highlight}.js`;
- `panel/js/screens/ask.js`, `panel/js/render.js` (`answerSegments`), `panel/js/{icons,motion}.js`, `panel/css/panel.css`;
- `scripts/panel.test.mjs`.

**Geri alma yolu:** panel dosyaları geri alınır. Sözleşme ve sunucu değişmediği için başka bir şey gerekmez.

## Yeniden değerlendirme koşulu

- API vurgu aralıklarını döndürmeye başlarsa (C) panel kendi eşlemesini bırakır.
- Sunucuda konuşma geçmişi gelirse (yol haritası 3): takip soruları kısa bir hâle döner ("Bu hangi belgeye dayanıyor?" olduğu gibi gönderilir).
- Kullanıcılar takip sorularının cevapsız kaldığını bildirirse ya da eval'de bulunamadı oranı artarsa: şablonlar yeniden yazılır.
