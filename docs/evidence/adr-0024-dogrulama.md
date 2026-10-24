# ADR-0024 doğrulaması: Cevap ekranı

Tarih 2026-10-24. Dal `feature/doguyaras-cevap-ekrani`, `origin/develop` (0aab34f, ADR-0020 dahil) üstünde. Yalnız panel; backend, sözleşme ve CSP değişmedi. Çalışan compose yığınına dokunulmadı.

## Testler

- `GITLEAKS=… node --test scripts/*.test.js scripts/panel.test.mjs`: 140 test; 139 geçti, 1 atlandı (POSIX sinyali, Windows; önceden de böyle).
- Panel testi 60 test. 17'si yeni ya da genişletildi:
  - **Eşleme (`excerpt.js`):**
    - Türkçe katlama: `İZİN`, `ızın`, `IŞIK`, NFKC bağlı harf ve tam genişlik harf;
    - 5 harfli kök ve durak sözcükleri;
    - sayılar her yazıda: `25.000`, `3,2`, Arap-Hint rakamı, matematik rakamları;
    - cümle sınırları: kısaltma, baş harf, sıra sayısı, `A.Ş.`, `T.C.`, liste satırı;
    - işaretin cümlesi: cümleyi açan işaret, `[10]` ile `[1]`, yalnız işaret;
    - işaretlenen sözcükler;
    - `…` kesme işaretleri.
  - **Sunucuyla aynı listeler:** durak sözcükleri ve kısaltmalar test sırasında `ExcerptSelector.java` ve `SentenceSplitter.java`'dan okunup panelinkilerle karşılaştırılır; `STEM = 5` de öyle.
  - **Markdown:**
    - dipnotlar, bilinmeyen işaret ve alıntısız eski cevap;
    - bağlantı, resim, HTML, kod, sahte dipnot ve bidi denemeleri etkisiz kalır;
    - indirme adı ASCII'dir, `../` taşımaz ve boş olmaz.
  - **Çip etiketi:** sayfa ve bölüm, kısaltma, bidi.
  - **Takip soruları:**
    - etiketler;
    - kaynaklı cevapta yerel cevap;
    - kaynaksızda "Kaynaklı olarak yeniden sor";
    - bulunamadıda hiç yok;
    - 1000 karakter sınırı, sınırın tam üstü ve bir fazlası.
  - **"Bunu sor":** tek satır, 300 karakter, kontrol karakteri yok.
  - **Hata durumları:** 11001, 10030, 90103, 99997, 99998, 502, 11002, 90102, 10031, 90000, 11010, 11011, 401, ağ hatası ve bilinmeyen kod; metin `Retry-After`'ı tekrar etmez.
  - **Metin parçalarının konumları:** bilinen ve bilinmeyen işaretlerin arasında `answerSegments`.
  - **`highlight.js` (DOM taklidiyle):**
    - CSS highlight aralıkları;
    - iki parçaya yayılan cümle vurgusunun kırpılması;
    - temizleme ve sayfadan çıkan aralıkların atılması;
    - `<mark>` geri dönüşü.
  - **Kaynak metni ve CSS:**
    - ürün adı, `::highlight` kuralları;
    - 1200 px sütunu ve çip;
    - 44 px dokunma alanı, klavye ve `visualViewport` kuralları;
    - Esc ile önizlemeyi gizleme;
    - CSP'nin aynen kaldığı ve `blob:` içermediği;
    - 600 ms parlama.
  - **Palet:** 7 yeni kontrast çifti (iki tema).

## JS–Java eşdeğerliği

`qa-core`'un `ExcerptSelector` ve `SentenceSplitter` sınıfları JDK 25 ile tek başına derlendi, repoya girmeyen küçük bir çalıştırıcıyla 17 girdinin anahtarlarını ve cümle aralıklarını yazdırdı. Aynı girdiler `panel/js/excerpt.js`'ten geçirildi: **17 satır, 0 fark**. Girdilerde şunlar vardı:
- Türkçe büyük ve küçük harf;
- kısaltma, sıra sayısı, Roma rakamı;
- tırnak ve parantez;
- `25.000`, `1.250,50`;
- `ﬁ`, tam genişlik harf;
- Arap-Hint ve matematik rakamları;
- satır sonu.

## Mutasyon

Kaynakta tek tek uygulanan 17 mutasyonun **17'si** panel testinde öldü:

| Dosya | Mutasyon |
|---|---|
| `excerpt.js` | `ı` katlanmaz; kök yok; cümleyi açan işaret kendi cümlesinde kalır; rakam değeri modülosuz |
| `answer.js` | bilinmeyen işaret dipnot olur; Markdown kaçışı yok; alıntı kaçışsız; bulunamadıdan sonra takip sorusu; takip sorusu kısaltılmaz; tek satır değerlerde bidi kalır; 90102 yoğun sayılmaz |
| `render.js` | işaret uzunluğu 3 varsayılır |
| `highlight.js` | `<mark>` sınıfsız; cümle vurgusu kırpılmaz |
| `screens/ask.js` | parlama 1200 ms; "Durdur"dan sonra odak dönmez |
| `panel.css` | Esc önizlemeyi gizleyemez |

## Görsel doğrulama

**Kurulum:**
- Paneli tek kullanımlık bir nginx container'ı sundu: aynı image ve digest, repodaki `deploy/edge/nginx.conf` ve `mode-local.conf`, salt okunur, `127.0.0.1:18090`. CSP başlığı yığındakiyle aynıydı.
- Kimlik sağlayıcı ve uygulama yoktu. Bu yüzden yalnız geçici dizinde duran, repoya girmeyen bir geliştirme fikstürü kullanıldı: `index.html`'e eklenen tek bir modül `fetch`'i taklit etti.
  - token ucu: imzasız sahte token;
  - `/v1/questions`: hazır cevaplar;
  - `/v1/info` ve `/v1/documents`.
- Cevapların alıntıları `samples/src` metinlerinden, sunucunun kendi `ExcerptSelector`'ıyla üretildi; biri baştan ve sondan kesik.
- Container iş bitince silindi.

**Ekranlar (Chromium, uygulama içi tarayıcı):**
- **1440 px, açık tema:**
  - Kaynak kartları sağda yapışkan sütunda, alıntıda ortak sözcükler fildişi-altın zeminle işaretli; `…100.000 TL ile …` kesme işaretleri soluk.
  - İşaretler "satin-alma-proseduru · s. 2" çipi.
  - `[1]` üzerine gelince önizleme açıldı, kart altın çerçeveyle yandı, aynı numaralı ikinci işaret halkalandı ve dayandığı iki cümle hafifçe boyandı.
  - Takip çipleri, soluk eylemler (Kopyala, .md indir, Düzenle) ve mod satırı görünüyor.
  - Bulunamadı, kaynak gösterilemedi ve "Dil modeli yanıt vermiyor" durumları ayrı renk ve metinle göründü; "Tekrar dene (4 sn)" geri saydı.
- **1440 px, koyu tema (Obscur):** aynı düzen; vurgular koyu altın zeminde okunaklı.
- **375 px, açık ve koyu:**
  - Kartlar cevabın altında, işaretler sayı.
  - Takip çipleri ve atıf satırları 44 px.
  - Soru kutusu alt sekme çubuğunun üstünde.
  - Yatay taşma yok (`scrollWidth` 375).
- **Marka dosyasıyla** (`branding.example.json`): ekran okuyucu başlığı "Bilgi Asistanı cevap verdi", uyarı "Bilgi Asistanı hata yapabilir; …", sayfa başlığı "Soru sor · Bilgi Asistanı". Kurulumun mavi vurgusu alıntı çizgisine ve gönder düğmesine geçti.
- **Hareketi azalt** (`matchMedia` taklidiyle): yeni cevap kelime kelime değil, bir anda çizildi (`.w` 0, `.reveal` yok). CSS'teki genel `prefers-reduced-motion` kuralı ve testi değişmedi.

**Davranış:**
- Alt+↑ soru kutusundan son tura gitti; PageUp önceki turlara çıktı ve odak halkası turu çevreledi.
- `[n]` tıklanınca kart açıldı ve yandı; 700 ms sonra parlama sınıfı kalkmıştı (600 ms). Esc önce önizlemeyi kapattı, sonra kartı.
- Alıntıda 40 karakter seçilince "Bunu sor" seçimin üstünde çıktı. Tıklanınca soru kutusu `“100.000 TL ile 1.000.000 TL arasındaki a” ` ile doldu ve odak oraya geçti.
- Beklerken "Durdur"a basılınca odak soru kutusuna döndü; "Bekleme durduruldu." duyuruldu.
- Kopyala: `navigator.clipboard.writeText` taklit edildi. Dipnotlu Markdown üretildi ve "Cevap kaynaklarıyla kopyalandı (Markdown)." bildirimi çıktı.
- **.md indir:**
  - `URL.createObjectURL` ve `HTMLAnchorElement.prototype.click` taklit edilerek doğrulandı:
    - Blob türü `text/markdown;charset=utf-8`, 1231 bayt;
    - bağlantı `download="soru-satin-alma-onay-matrisi-tutara-gore-nasil-isliyo.md"` ile belgeye eklendi, tıklandı ve kaldırıldı.
  - Taklitsiz ilk denemede `securitypolicyviolation` olayı gelmedi. Bu deneme kullanıcının masaüstünde bir "Farklı kaydet" penceresi açtı; sonraki denemeler bu yüzden taklitle yapıldı.
  - Sonuç: `blob:` bağlantıyla indirme bu CSP'de engellenmiyor ve CSP'ye yönerge eklenmedi.
- Konsolda yalnız beklenen `branding.json` 404'leri vardı (marka dosyası yoktu).

## Review skill'leri

| Skill | Karar | Bulgular ve sonuç |
|---|---|---|
| `verso-security-review` | APPROVE WITH NON-BLOCKING COMMENTS | **B1 (LOW):** Markdown dışa aktarımında kaçış yoktu; belgeye gömülü `![](…)` ya da `<img>` dosyayı açan görüntüleyicide dışarı istek yapabilirdi. **Düzeltildi:** `escapeMarkdown`, bidi silme, test ve mutasyon. Başka bulgu yok: DOM'a yalnız metin, CSSOM, dosya adı, blob ömrü, pano, seçim. |
| `verso-test-writer` | APPROVE WITH NON-BLOCKING COMMENTS | **Düzeltildi:** Markdown injection testi; durak sözcüğü ve kısaltma listelerinin Java dosyalarından okunup karşılaştırılması; `highlight.js` iki yolu ve `answerSegments` testi; `fitQuestion` sınırı; eksik hata kodları; `citingSpans` kenar durumları; `ask.js`'in Node'da içe aktarılmasına not. **Kaldı:** CSS ve kaynak metnine bakan düzenli ifadeler, DOM ortamı olmadığı için davranışı başka yoldan ölçemeyen kurallarda bilerek tutuldu. |
| `verso-llm-review` | APPROVE WITH NON-BLOCKING COMMENTS | **Düzeltildi:** Markdown kaçışı (3.2). Kaynaksız cevapta "Bu hangi belgeye dayanıyor?" yerine "Kaynaklı olarak yeniden sor". "Aksi" şablonu "buna" demiyor; her çipin ipucu gönderilecek soruyu gösteriyor. Bulunamadı metni mutlak iddia yerine bu sorudaki durumu söylüyor. **Açık (needs verification):** şablon sözcüklerinin retrieval'e etkisi eval'de ölçülmedi. |

`verso-spring-code-review` ve diğer backend skill'leri uygulanmadı: Java, config, migration ve sözleşme değişmedi.

## Bilinen sınırlar

- Gerçek IdP ve modelle uçtan uca denenmedi; ekranlar fikstürle doğrulandı.
- Dokunmatik klavye (`visualViewport`) emülasyonda açılamadı; mantık ve CSS kuralı testte.
- `prefers-reduced-motion` tarayıcı ayarıyla değil, `matchMedia` taklidiyle denendi.
