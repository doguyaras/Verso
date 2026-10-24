# ADR-0018: "Fildişi tuval" görsel dili, Fraunces fontu ve kurulum odaklı ekranlar

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras. İstekler:
  - "biraz daha expedition 33 paletlerine uyman lazım renkleri açman gerekiyor animasyon efekt koyman gerekiyor"
  - "firmalara yapay zeka danışmanlığı entegrasyonluğu yaparak şirketlerine kolayca entegre edebilmelerini sağlayacağız"
  - Üç konsept arasından "Fildişi tuval"i seçti ve "bu renkleri beğendim menüde de kullanabilirsin fakat bu gül yapraklarını istemiyorum" dedi.
  - Uygulama ayrıntıları bu ADR'dedir.
- **İlgili eşik (referans Bölüm 24):** yok (sunum katmanı). Menü beş ekranda kalır (ADR-0015'in yeniden değerlendirme koşulu).
- **Değiştirdiği:** ADR-0017'nin renk, logo yerleşimi ve giriş ekranı bölümleri. Kabuk, soru ekranı, bekleme ve atıf kararları ADR-0017'de geçerli.

## Bağlam

- ADR-0017'nin "Clair obscur" paleti (soğuk kül sayfa, koyu antrasit menü) kullanıcıya fazla koyu ve soğuk geldi. Giriş ekranında da kusurlar vardı:
  - marka panelinde çapraz "sayfa" motifi;
  - Keycloak formunda otomatik doldurmanın mavimsi zemini;
  - odakta çift kenarlık;
  - dil seçicinin kutusu;
  - şifre alanındaki göz düğmesinin ayrı kenarlığı.
- Ürün metni Verso'yu bir "belge asistanı" olarak anlatıyordu. Oysa Verso, yapay zekâ danışmanlığı ve entegrasyonu yapan bir ekibin firmalara kurduğu, şirketin sistemlerine uyum sağlayan, ihtiyacı anlayıp soruları kaynağıyla cevaplayan bir katman.
- Kısıtlar değişmedi: bağımlılık ve derleme adımı yok, CSP `default-src 'none'`, Trusted Types açık, Keycloak temasında script yok.
- Üç konu araştırıldı; rapor çalışma dosyası olarak tutuldu, repoya girmedi:
  - Clair Obscur: Expedition 33'ün sanat yönü;
  - 2025–2026'nın ürün arayüzleri ve hareket teknikleri (CSP altında uygulanabilirlik);
  - kurumsal yapay zekâ platformlarının konumlanması (Glean, Dust, Langdock, Onyx, Copilot Studio, Türkiye).
- Üç konsept animasyonlu önizlemeyle karşılaştırıldı: A Fildişi tuval, B Altın saat, C Gece galerisi.

## Seçenekler

| Seçenek | Artı | Eksi |
|---|---|---|
| **A. Fildişi tuval** (açık, boyalı gökyüzü, altın köşe süsleri) | En okunaklı; "renkleri aç" isteğine en yakın; menü de açık olabilir | Açık temada altın metin olamaz (2:1); drama giriş ekranında kalmalı |
| B. Altın saat (koyu gökyüzü, alttan gün batımı ışığı) | Afiş etkisi | Koyu ağırlık "açık" isteğine ters; alt kenarda metin okunurluğu zayıf |
| C. Gece galerisi (mürekkep siyahı, altın varak, deniz mavisi ışık) | Güçlü koyu tema | Açık tema yerine geçemez |

## Karar

**A**, koyu teması C'nin dilinde ("Obscur").

**Renk.** Ayrıntılı değerler `panel/css/panel.css`'tedir; kontrast çiftleri `scripts/panel.test.mjs`'teki "palette" testinde ölçülür.
- **Açık tema "Tuval ışığı":**
  - sayfa fildişi `#f4efe4`, kartlar kâğıt `#fbf8f1`, menü yıkama tonu `#ece4d3`;
  - metin `#1c1a17`, ikincil `#4a453d`, meta `#6b5e4c`;
  - birincil düğme sıcak antrasit `#23211f` üstünde krem `#f3e3bc`.
- **Altın:** `#c9a45c` yalnız dolgu, çizgi ve süs olarak kullanılır (`--accent-fill`). Açık temada metin olarak asla kullanılmaz; altın metin `#7a5c24`'tür.
- **Durum renkleri oyunun paletinden türetildi:** tehlike `#a3262a` (kırmızı yaprak), bilgi deniz mavisi `#2f6f6c`, uyarı pas rengi `#8a4b0c`.
- **Koyu tema "Obscur":**
  - sayfa `#131216`, kart `#1d1c21`, menü `#0d0c10`;
  - metin `#efe7d6`;
  - altın `#d9bc7c`; burada metin olarak da kullanılır.
- **Varsayılan tema açık (Fildişi), sistem koyu olsa bile.** Kullanıcı koyu sistemde ürünü koyu görünce "hala koyu tema gözüküyor" dedi. Koyu tema yalnız hesap menüsünden "Koyu (Obscur)" ya da "Sistemi izle" seçilince gelir (`isDark`, `panel/js/store.js`). Keycloak teması `darkMode=false` ile her zaman açıktır; stil dosyasındaki koyu kurallar ileride açmak için durur. ADR-0017'deki "sistemi izle" varsayılanının yerini alır.
- **Menü:** kullanıcı isteğiyle sayfanın renklerini kullanır, ayrı koyu çerçeve kalktı. Etkin sayfa kâğıt zemin ve altın çizgiyle işaretlenir.
- **Kontrast (WCAG 2.2 AA):** testte 22 çift (2 tema × 11) kontrol edilir. Seçilmiş değerler:

| Çift | Açık | Koyu |
|---|---|---|
| Metin / sayfa | 15,1:1 | 15,2:1 |
| İkincil metin / sayfa | 8,3:1 | 8,1:1 |
| Meta / kart | 5,9:1 | 4,7:1 |
| Metin / menü | 13,7:1 | 15,8:1 |
| Altın metin / altın zemin | 5,0:1 | 9,4:1 |
| Form kenarlığı / kart | 4,0:1 | 3,7:1 |
| Düğme etiketi / düğme | 12,6:1 | 8,9:1 |

**Tipografi.**
- **Başlık fontu: Fraunces** (OFL 1.1, Fontsource `@fontsource-variable/fraunces@5.2.9`). Yalnız büyük başlıklarda kullanılır: giriş başlığı, sayfa başlıkları, aşama adları. Arayüz, tablo ve rakamlar sistem sans fontunda kalır.
- **Neden Fraunces:** araştırma Cormorant Garamond'u önerdi. Kullanıcı ise önizlemede Fraunces'ı gördü ve onayladı; Fraunces 20–30 px'te de okunaklı kalıyor.
- **Dosyalar:** düz (wght + opsz) ve italik (wght), her biri latin ve latin-ext. ğ, ş, İ latin-ext'tedir. Toplam 213 KB; `unicode-range` yalnız gereken dosyayı indirir, düz latin dosyası önden yüklenir (preload).
- **Nereden servis edilir:** panel `/panel/fonts/`, Keycloak teması `resources/fonts/`. İki kopyanın aynı olduğunu test kontrol eder.
- **CSP:** panele tek yönerge eklendi, `font-src 'self'` (data: ve dış adres yok). nginx `woff2`'yi `font/woff2` olarak döndürür. `ComposeConfigTest` başlığı birebir sabitler. Keycloak'ın varsayılan realm CSP'si font kısıtlamaz.

**Üç ekran: tanıtım, giriş, iç panel.** Kullanıcı her biri için üç öneri arasından seçti: 1C, 2C, 3C.
- **Nasıl seçildi:**
  - Önce giriş ekranı için on konsept karşılaştırıldı: tuval, kemerli pencere, galeri, ufuk, belge katmanları, Art Deco, dev imza, sahne ışığı, kitap, mektup. Kitap seçildi.
  - Ayrıntılı bir kitap (deri kapak, kurdele, kapak çevirme) "abartı" bulundu.
  - On üç başlık cümlesi reddedildi; araştırmaya göre incelenen 13 giriş sayfasının 10'unda başlık yok. Kullanıcı "başlık hiç olmasın"ı seçti.
  - Sonra panelin girişi Keycloak'ın kopyası olduğu için ("aynı ekran sadece girişlerle geliyor") tanıtım, giriş ve iç panel ayrı ayrı tasarlandı.
- **Tanıtım ekranı (panel, 1C):**
  - Masada açık kitap, üstünde ortada büyük imza (104 px, atılır) ve "Kurumunuza uyum sağlayan yapay zekâ".
  - Sol sayfada bir örnek soru, sağ sayfada kaynaklı cevabı ve kenar notu, altında "Giriş yap".
  - Örnek kurulumundur (`config.js` → `deployment.showcase`, TR ve EN) ve "Örnek · …" etiketiyle gösterilir. Kullanıcı basit politika sorularını "basit ve saçma" buldu; Verso bir firmaya kurulduğunda o sistemin teknik işleyişini cevaplar. Varsayılan örnek bir depo yönetim sisteminin stok davranışıdır (ilk sürümdeki e-fatura örneği kullanıcı isteğiyle değişti); kurulum ekibi firmanın kendi sisteminden bir örnek koyar.
  - Tanıtımın düğmesi "Başlayın →" der; "Giriş yap" yalnız giriş sayfasındadır, iki kez görünmez.
  - "Başlayın" IdP'ye `prompt=login` ile gider: Keycloak'ta açık bir oturum olsa da giriş formu her seferinde görünür. Kullanıcı formun atlanmasını (sessiz SSO) "giriş adımı uçmuş" diye gördü. 401 sonrası otomatik giriş sessiz kalır.
- **Giriş ekranı (Keycloak, 2C):** ekran ikiye bölünür.
  - Sol yarı baştan sona boyalı levhadır; ortasında büyük imza (132 px, CSS ile atılır), altında "Kurumunuza uyum sağlayan yapay zekâ".
  - Sağ yarı kâğıttır: "Giriş yapın", alt satır, altın çizgi, form, iki güvence.
- **Alanlar: dolgulu "defter satırı":**
  - Etiket alanın içindedir, altta ince çizgi vardır. Odakta zemin kâğıda açılır ve altın çizgi ortadan iki yana yayılır.
  - Google'ın alan araştırmasında dolgulu alanlar en iyi, yalnız alt çizgili alanlar en kötü sonucu verdi; bu ikisinin iyi yanı.
  - Odak rengi açık temada `#9c7a35`: saf altın fildişi zeminde 3:1'i geçmez.
  - Şifre alanı ve göz düğmesi tek satırdır. Otomatik doldurma alan rengine boyanır.
- **Sağ üstte kayan anahtarlar:**
  - Tema: güneşten aya döner, yaylı kayar.
  - Dil: TR / EN; gösterge kayar.
  - Panelin tanıtım ekranında ikisi de JS'tir (`panel/js/toggles.js`). Dil seçimi tanıtım metnini ve Keycloak'ın dilini (`ui_locales`) belirler; iç panel Türkçedir (çok dillilik ayrı bir iş).
  - Keycloak'ta tema bir checkbox'tır ve CSS `:has()` ile okunur; temada script yoktur, bu yüzden seçim o sayfa için geçerlidir. Varsayılan açık temadır.
  - Keycloak'ta dil düz bağlantılardır; göstergesi sayfalar arası view transition ile kayar (Chrome 126+, Safari 18.2+).
- **İç panel (3C):**
  - Menü varsayılan olarak ince ikon şerididir ve genişletilebilir. Genişletilince imza menünün başında ortada ve büyüktür (42 px).
  - Genel bakış bir "sor" sayfasıdır: ortada büyük imza, selam, büyük soru kutusu ve kurulumun önerdiği sorular (`deployment.suggestions`; gerçekten sorulduğu için dizinli belgelerden cevaplanabilir olmalı).
  - Altında üç küçük kart (sorulabilir belge, kurulum aşaması, çalışma modu), ilk kullanım listesi, son belgeler ve bu oturumun soruları.
  - Sağ üstte tema anahtarı.
  - Araştırma da son kullanıcı için önce soru kutusunu öneriyor (Glean deseni).
- **Hareket kısa ve tek seferliktir:** kitap ya da form yerine oturur, imza atılır, sayfa sırayla gelir. "Reduce motion" ile hiçbiri oynamaz. Test, tanıtım bloğunda ve Keycloak temasında `infinite` olmadığını kontrol eder (WCAG 2.2.2).
- **İmza:** her kalem hareketinin önünde, yuvarlak uçtan geniş bir boşluk bırakılır; atılmadan önce nokta görünmez.
- **Keycloak düzeltmeleri:**
  - keycloak.v2'nin `onchange`'li dil `select`'i kalktı; şablonda satır içi script yok.
  - FreeMarker yönergelerinin dengesi testte sayılır: dengesiz bir yönerge bütün giriş sayfasını 500'e düşürür, bir kez oldu.
- **Önbellek:** stil dosyası `verso-tuval.css` → … → `verso-giris.css` oldu, çünkü tarayıcılar tema dosyalarını 30 gün tutar.

**Ürün metni ve ekranlar.**
- **Konumlandırma cümlesi** (ürün ve satış metinleri için): "Kurumunuza uyum sağlayan yapay zekâ katmanı." Giriş ekranında ve menüde yazmaz; kullanıcı menüde "yapay zeka katmanı diye bir şey yazmasına gerek yok" dedi.
- **"KVKK uyumlu" yazılmaz;** genel bir uyum iddiası hukuki inceleme ister.
- **Menü** (beş ekran): Genel bakış · Soru sor · Bilgi kaynakları · Kurulum durumu · Sistem ve güvenlik (operatör). Mobil alt çubuk dört sekmede kalır; Kurulum durumu'na Genel bakış kartından gidilir.
- **Bilgi kaynakları:** eski Belgeler ekranı. Altında "Bağlantılar" kartları var: SharePoint ve dosya paylaşımları, talep yönetimi, ERP. Hepsi "Planlandı" rozetli; çalışıyormuş gibi gösterilmez.
- **Kurulum durumu:** danışmanlık işini müşteriye gösteren yeni ekran. İncelenen satıcıların hiçbirinde yok.
  - Aşamalar: Keşif → Pilot → Yaygınlaştırma → Entegrasyonlar. Kurulumu yapan ekip `config.js`'te `deployment.phase`'i ayarlar.
  - Kontrol listesi sistemden okunur: şirket girişi, model modu, sorulabilir belge sayısı, bu sekmede kaynaklı ilk cevap.
  - Pilot grubu yine config'ten gelir (`deployment.pilotGroup`). Varsayılanlar hiçbir şeyi tamamlanmış göstermez; test bunu kontrol eder.
- **Kurum adı:** `deployment.client` verilirse menüde imzanın altında görünür (beyaz etiket için ilk adım). Verilmezse imzanın altında bir şey yazmaz; kullanıcı "yapay zeka katmanı diye bir şey yazmasına gerek yok" dedi.

## Sonuçlar

**Olumlu:**
- Ürün kendi kimliğiyle ve danışmanlık modeline uygun metinle açılıyor. Kurulum işi müşteriye görünür.
- Açık tema daha ferah. Altın yalnız kaynağı ve etkin yeri gösteriyor.
- CSP'ye yalnız `font-src 'self'` eklendi. Yeni bir çalışma zamanı bağımlılığı yok; font, imza konturları gibi bir varlık dosyası.

**Olumsuz / kabul edilen risk:**
- **"Verso" adı oyunun merkezi bir karakterinin adı.** Oyuna yakın bir görsel dil bu çağrışımı güçlendirir.
  - Geniş lansmandan önce TÜRKPATENT, EUIPO ve USPTO'da Sınıf 9/42 taraması ve marka vekili görüşü alınmalı.
  - Ürün ve pazarlama metninde oyuna hiç atıf yapılmaz. Oyunun adı, logosu, "33" sayısı, karakterleri ve varlıkları kullanılmaz; bütün doku ve süsler kendi CSS çizimimizdir.
- **Fraunces 213 KB.** Yalnız ilk açılışta indirilir, sonra önbellekte kalır. Daha hafif seçenek tek bir Türkçe alt kümesi (`pyftsubset`); ölçülmedi.
- **Kurulum durumu'ndaki aşama bilgisi** config dosyasından gelir, yani kurulum ekibi güncellemezse eskir. İleride yönetim API'sine taşınabilir.
- **Kontrol listesindeki "kaynaklı ilk cevap"** yalnız bu sekmenin belleğine bakar. Sunucu tarafı kullanım sayaçları ayrı bir iştir (kullanım ve değer metrikleri).
- **Ambiyans katmanları** (bulanık gradyanlar, `mix-blend-mode`) zayıf tümleşik grafikte ilk saniyelerde yük getirir. Süre sınırlı, "reduce motion" ile kapalı.

**Etkilenen dosyalar:**
- `panel/css/panel.css`, `panel/index.html`;
- `panel/js/{app,config,icons,roles,signature,signature-data}.js`, yeni `panel/js/screens/setup.js`;
- `panel/js/screens/{documents,overview,system}.js`;
- yeni `panel/fonts/`;
- `deploy/edge/nginx.conf`;
- `deploy/keycloak/themes/verso/login/`: `template.ftl`, mesajlar, `theme.properties`, yeni `resources/css/verso-fildisi.css` ve `resources/fonts/`;
- `scripts/signature-theme.mjs`, `scripts/panel.test.mjs`, `verso-app/.../ComposeConfigTest.java`.

**Geri alma yolu:**
- Panel ve tema dosyaları geri alınır, `theme.properties` eski stil dosyasını gösterir, Keycloak yeniden oluşturulur.
- `font-src 'self'` kaldırılırsa başlıklar sistem serif yığınına düşer.

## Yeniden değerlendirme koşulu

- Marka taraması "Verso" adı için risk gösterirse: ad ve imza değişir, görsel dil kalabilir.
- Kurumsal müşteri kendi marka renkleriyle tema isterse: `deployment` config'i tema token'larını da taşıyacak şekilde genişletilir.
