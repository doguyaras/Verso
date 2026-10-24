# ADR-0017: Panel v3 tasarım sistemi ve Verso giriş teması

- **Durum:** Kabul edildi; renk, menü rengi ve giriş ekranı bölümlerinin yerini [ADR-0018](0018-fildisi-tuval.md) aldı
- **Tarih:** 2026-10-23
- **Karar verenler:** doguyaras (istek: "tasarımları beğenmedim; araştır, kullanıcı dostu ve satılabilir bir ürün yap"); uygulama ayrıntıları bu ADR
- **İlgili eşik (referans Bölüm 24):** yok (sunum katmanı). Ekran sayısı beşi geçerse ya da bir bileşen çatısı istenirse ADR-0015'in yeniden değerlendirme koşulu geçerlidir.

## Bağlam

- Panel v2 (ADR-0015 eki) çalışıyordu ama görsel dili sıradandı. İndigo/mor vurgu rengi, mor→teal gradyan ve "parıltı" ikonu, 2025-2026'da "yapay zekâyla üretilmiş görünüm" diye anılan kalıpların ta kendisiydi.
- Giriş ekranı Keycloak'ın varsayılan teması olarak kalmıştı: İngilizce, Keycloak arka planı, ürünle ilgisiz.
- Soru ekranında atıflar yalnız numaraydı; kullanıcı cevabı kaynağından doğrulamak için belgeyi kendisi açmak zorundaydı. Oysa küçük yerel model bilgiyi bazen yanlış okuyor (eval'de yaklaşık %4).
- "Bulunamadı" metni, modelin pasaj okuyup "bulunamadı" dediği durumda da "ilgili pasaj bulunamadı" diyordu. Bu metin yalnız eşik altındaki durum için doğruydu.
- Kısıtlar değişmedi:
  - Panelde bağımlılık ve derleme adımı yok.
  - Dış font ve ikon paketi yok.
  - CSP `default-src 'none'`, Trusted Types açık.
  - Token yalnız bellekte.
- Derin araştırma raporu (NN/g, Baymard, Material 3, WCAG 2.2, Radix, Linear, Vercel Geist, NotebookLM, Glean ve pazar verisi) çalışma dosyası olarak tutuldu, repoya girmedi. Aşağıdaki kararlar bu araştırmaya dayanır.

## Seçenekler

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| A. v2'yi renk değişikliğiyle bırakmak | Az iş | Navigasyon, atıf ve bekleme sorunları kalır | Düşük |
| B. Bileşen çatısı + UI kütüphanesi (React, shadcn) | Hazır bileşenler | ADR-0015'in bağımlılık kararına aykırı; derleme zinciri; CSP ve Trusted Types işi | Yüksek |
| **C. Kendi tasarım sistemi: token'lı CSS + bağımlılıksız modüller** | Kısıtlarla uyumlu; tam kontrol; araştırmadaki her ilke uygulanabilir | Bileşenler elle yazılır | Orta |

## Karar

**C.** Ayrıntılar aşağıda.

**Renk: "Clair obscur" paleti.**

Kullanıcı ilk önerilen "Petrol" paletini beğenmedi. 13 palet yönü, ardından 14 koyu gri palet ve "kül" tonunun 10 varyasyonu panel taslakları üzerinde karşılaştırıldı. Seçilen: açık kül zemin ve "Clair obscur" varyasyonu. Esin kaynağı *Clair Obscur: Expedition 33*'ün ışık-gölge (chiaroscuro) fikridir; oyunun logosu, yazı tipi ya da görselleri kullanılmaz.

- **Açık tema (clair):** sayfa açık kül `#f1f2f4`, kartlar `#fafafb`, metin `#1c1e22`, kenarlık `#d8dbdf`, ikincil metin `#5f646b`. Kullanıcı ilk sürümden sonra renkleri "biraz daha açık" istedi; bütün ölçek bir adım açıldı.
- **Çerçeve (obscur):** menü ve giriş ekranının marka paneli her iki temada da koyu antrasittir (`#2a2d33`, koyu temada `#1a1c1f`; koyu temanın sayfası `#2b2e33`). Menü kendi alt ağacında fonksiyonel token'ları yeniden tanımlar; paylaşılan bileşenlerin menüye özel varyantı yoktur.
- **Antik altın** yalnız kaynağa işaret eden yerlerde kullanılır: atıflar, etkin menü öğesi, odak halkası. Açık temada metin `#6e521a`, zemin `#f1e6cc`. Koyu temada dolgu `#d9bc7c`, metin `#e8cb8a`. İmzanın altını da `#d9bc7c`'dir.
- **Birincil düğme:** açık temada antrasit üstüne krem (`#f3e3bc`), koyu temada altın üstüne koyu metin.
- **Uyarı rengi** turuncuya kaydırıldı (`#8a3f06` / `#fdebdd`), altın vurguyla karışmasın diye.
- 12 adımlı gri ("kül") ve vurgu ("antik altın") ölçekleri; adım rolleri Radix modelinde. Bileşenler yalnız fonksiyonel token'ları kullanır (`--bg-*`, `--fg-*`, `--border-*`, `--accent-*`), Primer modelinde.
- Mor/indigo, gradyan vurgu ve parıltı ikonu kullanılmaz. Işık efektleri (aşağıda "Hareket") yalnız koyu çerçevede ve kartların üstünde, altın tonunda ve düşük opaklıkta kalır.
- **Kontrast (WCAG 2.2 AA, ölçüldü):**

| Çift | Açık | Koyu |
|---|---|---|
| Metin / sayfa | 14,9:1 | 12,1:1 |
| İkincil metin / sayfa | 5,3:1 | 6,9:1 |
| Yer tutucu / kart | 5,0:1 | 4,6:1 |
| Form kenarlığı / kart | 3,6:1 | 3,2:1 |
| Düğme etiketi / düğme | 10,9:1 (krem / antrasit) | 9,4:1 (koyu / altın) |
| Altın metin / altın zemin | 5,9:1 | 7,6:1 |
| Odak halkası / sayfa | 5,4:1 | 8,7:1 |
| Menü metni / menü | 11,9:1 | 15,1:1 |
| Menü ikincil / menü | 6,0:1 | 6,1:1 |
| İmza (altın) / çerçeve | 7,5:1 | 9,3:1 |
| İmza (mobil çubuk) / sayfa | 12,3:1 | 7,4:1 |

Tablo `scripts/panel.test.mjs`'teki "palette" testiyle aynı token çiftlerinden hesaplanır; test bir çift eşiğin altına düşerse kırmızıdır.

- Koyu temada yükselti gölgeyle değil, açılan yüzeyle verilir.

**Tipografi ve şekil.**
- Sistem fontları, Segoe UI başta: Windows'ta `system-ui` dile göre başka fonta düşebiliyor.
- Ölçek 12/13/14/16/18/20/24/30 px; sayılar `tabular-nums`.
- Köşe yarıçapı 4/8/12 px; düğme yüksekliği 32/36/44 px.
- Odak halkası 2 px + 2 px boşluk.
- Hareket 100–250 ms, girişte ease-out; `prefers-reduced-motion` ile kapanır (aşağıda "Hareket").

**Logo: "Verso" imzası.**
- Kullanıcı italik serif logoları beğenmedi; "imza atılıyormuş gibi", altı çizgisiz, okunaklı bir yazı istedi. Great Vibes, Alex Brush ve Allura karşılaştırıldı; seçilen **Allura** (Robert Leuschke, SIL Open Font License 1.1).
- Font yüklenmez (CSP yalnız `'self'`): "Verso" kelimesi opentype.js ile bir kez kontura çevrildi ve `panel/js/signature-data.js`'e yazıldı. OFL, konturlara çevrilmiş bir çizimin üründe kullanılmasına izin verir; lisans notu dosyanın başındadır (`docs/versions.md`).
- **Atılma animasyonu:** her glifin orta çizgisi rasterleştirme ve Zhang-Suen inceltmesiyle çıkarıldı. Kalemin yazma sırasındaki 12 hareketi ayrı yollar olarak saklanır.
  - Kontur bir maskenin arkasından bu yollar boyunca açılır; her hareket kendi `stroke-dasharray`'iyle çizilir. Chrome dash'i her alt yolda baştan başlattığı için hareketler tek yolda birleştirilemez.
  - Maske bölgesi açık verilir (`userSpaceOnUse` + kutu); varsayılan bölge Allura'nın alt kuyruklarını kesiyordu.
  - Kalem tek hızla gider; kelime **1,6 sn**'de atılır (ilk deneme 2,4 sn'ydi, kullanıcı yavaş buldu). Hareketler arasında 45 ms kalem kaldırma vardır.
  - Panelde kalemin ucu (`getPointAtLength`) mürekkebin ucunda ilerler.
- **Nerede:**
  - Panelin ve Keycloak'ın giriş ekranında atılır.
  - Menüde ve mobil üst çubukta durağandır.
  - Daraltılmış menüde, asistan avatarında ve favicon'da imzanın "V"si antrasit karo üstünde altındır.
- **Keycloak'ta script yok:** `scripts/signature-theme.mjs` aynı veriden `template.ftl`'deki SVG'yi, CSS-yalnız animasyonu (`signature:start/end` işaretleri arasında) ve `panel/img/favicon.svg`'yi üretir. `--check` güncel değilse 1 döner; panel testi bunu çalıştırır.

**Hareket ("Tuval ışığı").** Kullanıcı ilk tasarımın giriş ekranını ve arayüzünü durağan buldu, efekt ve animasyon istedi. Üç giriş yönü taslakta karşılaştırıldı; seçilen "Tuval ışığı".
- **Giriş ekranı** (panel ve Keycloak):
  - Koyu çerçevede yavaşça kayan altın ışık: kayıtlı `@property` değişkenleriyle radyal gradyan, 38 sn. İlk denemedeki konik gradyan sert kenarlı bir şerit bıraktığı için kaldırıldı.
  - `feTurbulence` film greni (veri URI'si, `img-src data:`).
  - İmza atılır; başlık kelime kelime gelir; maddeler ve form sırayla gelir; birincil düğmenin üstünden tek bir ışık geçer.
  - Yalnız panelde imleci izleyen yumuşak ışık vardır; fare ve kalem için, dokunmada yok.
- **Panel** (`panel/js/motion.js`):
  - Etkin menü işareti sayfadan sayfaya kayar.
  - Kartlarda imleç altında altın ışık, sayı kartlarında hafif yükselme.
  - Sayılar 0'dan sayar (ease-out-expo, 0,9 sn, `tabular-nums`).
  - İskelet ve "Cevap hazırlanıyor" yazısı üstünden ışık geçer. Uydurma aşama metni yine gösterilmez.
  - Yeni cevap kelime kelime belirir; boşluklar metin olarak kalır, kopyalama ve ekran okuyucu düz cümleyi görür. Sonraki ziyarette cevap animasyonsuz çizilir.
  - Atıf üzerindeyken ya da odaktayken ilgili kaynak kartı vurgulanır; tıklanınca kart görünür alana kayar ve kısa bir nabız atar.
  - Ekranlar arasında View Transitions ile yumuşak geçiş olur (tarayıcıda yoksa doğrudan).
- **Kurallar:**
  - Hiçbir animasyon bilgi taşımaz: son durum düz içeriktir.
  - `prefers-reduced-motion` her şeyi durdurur: imza tamamlanmış görünür, ışık durur.
  - Biçim yalnız CSSOM ve Web Animations ile verilir; CSP değişmez.

**Kabuk.**
- Masaüstünde etiketli sol menü; hamburger yok (NN/g). Menü ikon şeridine daraltılabilir.
- 768 px altında dört sekmeli alt çubuk; çekmece yok (NN/g, Material 3).
- Menü sırası Genel bakış, Soru sor, Belgeler, Sistem: en sık iş ikinci sırada.
- Etkin ekran `aria-current`'la işaretlenir.
- Ctrl+K / ⌘K komut paleti (sayfalar, eylemler, belgeler) Türkçe harf katlamalı arama yapar: "izin", "İZİN" ve "ızın" eşleşir.
- `g` + harf kısayolları var.
- Hesap menüsünde tema seçimi: sistemi izle, açık, koyu.

**Soru ekranı.**
- `[n]` bir düğmedir. Üzerine gelince, odaklanınca ya da tıklanınca belge adı, sayfa/bölüm ve **alıntılanan pasaj** görünür (NotebookLM ve Glean deseni).
- Pasaj API'ye eklenen `citations[].excerpt` alanından gelir: en çok 280 karakter, sunucuda alınan pasajdan, model metninden değil.
- Kaynak kartları belgeye göre gruplanır.
- Her cevap türünün kendi dürüst görünümü vardır:
  - **Kaynaklı.**
  - **Bulunamadı:** "Yüklediğiniz belgelerde bu sorunun cevabını bulamadım" ve eylemler. Bu metin eşik ve model durumlarının ikisi için de doğrudur.
  - **Kaynak gösterilemedi:** yalnız bu durumda bir uyarı.
- Güven yüzdesi gösterilmez.
- Bekleme ekranı:
  - 1 sn altında bir şey gösterilmez.
  - Sonra iskelet görünür.
  - 10 sn'den sonra geçen süre ve mod için gerçekçi beklenti yazılır.
  - Uydurma aşama gösterilmez.
  - Bir durdurma düğmesi vardır.
- Erişilebilirlik: cevap gövdesi `aria-live` dışında kalır (`role="log"`); yalnız başlangıç ve bitiş ayrı bir `role="status"` ile duyurulur.
- Her cevabın altında mod rozeti (yerel/bulut) ve süre durur.

**Diğer ekranlar.**
- **Genel bakış:** üç adımlı ilk kullanım listesi ve tıklanabilir dört sayı kartı. Bu oturumdaki sorular yalnız sekme belleğindendir.
- **Belgeler:** sıralanabilir tablo, klavyeyle açılan satırlar, dosya başına "tekrar dene", dosya adını söyleyen silme onayı. Odak "Vazgeç"te olduğu için Enter yanlışlıkla silmez.
- **Sistem:** üç koruma katmanının adları (ağ, uygulama, açılış).

**Giriş teması** (`deploy/keycloak/themes/verso`).
- `keycloak.v2`'nin alt teması: yalnız CSS ve işaretleme, kendi script'i yok. Stil dosyası `verso-tuval.css`.
- `template.ftl` keycloak.v2'nin kopyasıdır; tek fark, formun yanına statik bir marka paneli eklenmesidir.
- Metinler `messages_tr` / `messages_en` dosyalarındadır.
- Realm: `loginTheme: verso`, `defaultLocale: tr`. Panel giriş isteğine `ui_locales=tr` ekler; tarayıcı İngilizce olsa da sayfa Türkçe açılır.
- Tema compose'da salt okunur bağlanır.

## Sonuçlar

**Olumlu:**
- Kullanıcı cevabın hangi belge ve sayfaya dayandığını ve o pasajı tek hareketle görür. Canlı denemede model "yirmi gün" dedi (belgede "on dört gün"). Önizleme doğru belgeyi ve sayfayı gösterdi, ama alıntı pasajın başı olduğu için ilgili cümleyi içermedi. Bu, aşağıdaki "alıntı penceresi" işinin gerekçesidir ([ADR-0020](0020-alinti-penceresi.md)).
- Ürün ilk ekrandan itibaren Türkçe ve kendi kimliğiyle görünür.
- Yeni bağımlılık yok. CSP değişmedi; görünüm ayarları için yalnız CSSOM `element.style` kullanılır. İmza bir font değil, çizimdir: indirilen font dosyası yoktur.

**Olumsuz / kabul edilen risk:**
- **Alıntı pasajın başıydı,** cevabın dayandığı cümle olmayabiliyordu. Cevapla örtüşen pencere [ADR-0020](0020-alinti-penceresi.md) ile geldi; örtüşme yoksa alıntı yine pasajın başıdır.
- **"Durdur" yalnız beklemeyi bırakır:** sunucudaki model çağrısı kendi süresinde biter. Sunucu tarafı iptal, istek bağlantısı koptuğunda çağrıyı kesecek ayrı bir değişikliktir.
- **Keycloak güncellemelerinde şablon:** `template.ftl` keycloak.v2'nin kopyası olduğu için her Keycloak sürüm yükseltmesinde üst temanın şablonuyla karşılaştırılmalıdır (`ImageVersionsTest`'in sürüm sabitlemesiyle aynı anda).
- **Keycloak tema kaynaklarını iki yerde önbelleğe alır:**
  - Sıkıştırılmış kopyalar `data/tmp/kc-gzip-cache`'tedir ve yeniden başlatmada silinmez. Tema değişince container yeniden oluşturulur: `docker compose up -d --force-recreate keycloak`.
  - Tarayıcılar kaynakları 30 gün önbellekte tutar; URL'deki sürüm Keycloak sürümüyle değişir. Canlıda bir tema değişikliği, CSS dosyasının adını değiştirerek dağıtılmalıdır (bu ADR'de `verso.css` → `verso-clair-obscur.css` → `verso-tuval.css`).
- **İmza verisi elle düzenlenmez:** konturlar ve kalem yolları bir kez üretildi; değişiklik araştırma sayfasıyla (opentype.js + inceltme) yeniden üretilir, sonra `node scripts/signature-theme.mjs` çalıştırılır.
- **Hareket maliyeti:** kayan ışık ve gren yalnız giriş ekranındadır (`contain: paint` / `isolation`); panel içindeki animasyonlar kısa ve tek seferliktir.
- **Realm değişikliği:** realm yalnız ilk açılışta içe aktarılır; mevcut yığında `bash scripts/keycloak-reimport.sh` gerekir. Bu, demo IdP'nin kullanıcı kimliklerini yeniler. Belgeler `sub`'a bağlı olduğundan, önceki demo kullanıcılarının yüklediği belgeler yeni kimlikle görünmez; veri silinmez.

**Etkilenen dosyalar:**
- `panel/` (bütün modüller, `css/panel.css`; yeni `js/palette.js`, `js/store.js`, `js/motion.js`, `js/signature.js`, `js/signature-data.js`, `img/favicon.svg`)
- `scripts/signature-theme.mjs`, `deploy/edge/nginx.conf` (`/panel/` altında SVG türü)
- `deploy/keycloak/themes/verso/`, `deploy/keycloak/realm/verso-realm.json`, `compose.yaml` (tema bağlantısı)
- `services/qa` (`Citation.excerpt`), `docs/api-questions-integration-v1.md`
- `scripts/panel.test.mjs`

**Geri alma yolu:**
- Panel dosyaları geri alınır.
- Realm'den `loginTheme` kaldırılırsa Keycloak varsayılan temaya döner.
- `excerpt` geriye uyumlu bir ek alandır; kaldırmak istemcileri bozmaz.

## Yeniden değerlendirme koşulu

- Ekran sayısı beşi geçerse ya da bir bileşen çatısı istenirse (ADR-0015).
- Keycloak'ın keycloak.v2 şablonu büyük ölçüde değişirse: o zaman kopya şablon yerine yalnız CSS'e dönülür, marka paneli kaldırılır.
