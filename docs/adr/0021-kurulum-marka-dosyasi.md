# ADR-0021: Kuruluma özel marka dosyası (`/branding.json`)

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras. İstek: panel her kurulumda JavaScript düzenlenmeden markalanabilsin (yol haritası, kurumsal özellikler 6a "White-label adım 1").
- **İlgili eşik (referans Bölüm 24):** yok (sunum katmanı). Yeniden değerlendirme koşulu aşağıda.
- **Genişlettiği:** ADR-0018'in "Kurum adı" maddesi ve "kurumsal müşteri kendi marka renklerini isterse" koşulu.

## Bağlam

- Kurulum ekibi her firmada kurum adını, aşamayı, örnek soruyu ve önerilen soruları `panel/js/config.js`'teki `deployment` bloğunda değiştiriyordu. Bu, her kurulumda kaynak kodunu düzenlemek demekti: güncellemede çakışma, yanlış bir virgülde bütün panelin boş açılması.
- Kısıtlar değişmedi:
  - derleme adımı yok;
  - CSP `default-src 'none'`, `script-src 'self'`, `connect-src 'self' …`;
  - Trusted Types açık, `innerHTML` ve satır içi stil yok (ADR-0015).
- Tanıtım ekranı girişten önce açılır; marka bilgisi kimliksiz okunabilmelidir.
- Yol haritasının ikinci adımı (operatör ekranı, `GET/PUT /v1/branding`, veritabanında saklama) ayrı iştir; bu karar yalnız dosyayı kapsar.

## Seçenekler

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| **A. Kenar proxy'den statik `/branding.json`** (salt okunur bağlanan dizin) | Backend değişmez; `connect-src 'self'` ve `img-src 'self'` yeter; dosya değişince yeniden başlatma gerekmez | Doğrulama tarayıcıda; hatalı dosya ancak konsolda görünür | S |
| B. `config.js`'i kurulumda üzerine yazmak | Kod yok | Kaynak dosyası kurulumda değişir; sözdizimi hatası paneli boş bırakır; güncellemede çakışır | – |
| C. `GET /v1/branding` (uygulama, veritabanı) | Operatör ekranından değişir; sunucuda doğrulanır | Şema, migration, yetki ve denetim ister; yol haritasında 2. adım | M |

## Karar

**A.**

**Dosya ve sunum.**
- Kurulum ekibi `deploy/branding/branding.json`'u yazar; şablon `deploy/branding/branding.example.json`'dur. Dizin kenar proxy'ye salt okunur bağlanır (`compose.yaml` ve `deploy/compose.cloud.yaml`: `./deploy/branding:/usr/share/verso-branding:ro`).
- nginx yalnız iki şey sunar (`deploy/edge/nginx.conf`):
  - `/branding.json`: `application/json; charset=utf-8`;
  - `/branding/<ad>.svg|png|webp`: logo; ad harf, rakam, `-` ve `_`'dir, alt dizin yoktur.
  - Dizindeki başka her şey (`/branding/` altı) 404 döner.
- Başlıklar:
  - `Cache-Control: no-cache`: tarayıcı her açılışta ETag ile yeniden doğrular. Dosya değişince sonraki yenilemede görünür; proxy'yi yeniden başlatmak gerekmez.
  - `X-Content-Type-Options: nosniff`.
  - `X-Rag-Mode`: proxy'nin her yanıtı gibi, 404 dahil (llm-rules 1.3).
  - CSP `sandbox`: tek başına açılan bir logo SVG'si bu origin'de script çalıştıramaz. `<img>` içindeki SVG zaten script çalıştırmaz.
- Dosya yoksa nginx 404 döner ve panel `config.js`'teki varsayılanlarla açılır; uyarı yazılmaz, çünkü markasız kurulum olağan durumdur.
- `branding.json` ve logolar bir müşteriye aittir; git'e yalnız örnek dosya girer (`.gitignore`).

**Panelde okuma (`panel/js/branding.js`).**
- `app.js` açılışta `fetch('/branding.json')` yapar. Aynı origin olduğu için CSP değişmez. Çağrı giriş tamamlanırken paralel çalışır; ilk ekrandan (tanıtım ya da kabuk) önce beklenir.
- Çağrıya 3 sn süre verilir; yanıt gelmezse varsayılanlar kullanılır.
- Varsayılanlar `config.js`'te kalır (`DEPLOYMENT_DEFAULTS`). Dosya bunların üzerine birleştirilir; dosyada olmayan alan varsayılanını korur.
- `CONFIG.deployment` artık bir getter'dır. Ekranlar değeri modül yüklenirken değil, çizerken okur; test bunu kontrol eder. `screens/ask.js`'te yalnız önerilen soruların okunduğu yer değişti.

**Doğrulama: katı, ya hep ya hiç.**
- Yalnız bilinen anahtarlar kabul edilir: `productName`, `client`, `logo`, `phase`, `pilotGroup`, `team`, `accent`, `showcase`, `suggestions`. `__proto__` dahil bilinmeyen her anahtar hatadır.
- Metinler kırpılır, boş olamaz ve üst sınırları vardır (karakter): ürün adı 40, kurum 80, ekip 160, örnek etiketi 80, soru 200, cevap 600, kaynak 120, önerilen soru 200. Kontrol ve biçim karakterleri (bidi yön değiştiriciler gibi) reddedilir: bir etiketi olduğundan farklı okutabilirler.
- `suggestions` 1–6 sorudur.
- `showcase`'te `tr` ve `en`'in ikisi de, dördü de dolu (`label`, `question`, `answer`, `source`) olmalıdır. Biri eksikse tanıtım iki dilde farklı kurumları anlatırdı.
- `phase` dört aşamadan biridir (`screens/setup.js` → `PHASES`). `pilotGroup` true ya da false'tur.
- `logo` `/branding/…` altında, nginx'in sunduğu adla aynı kalıpta bir yoldur. Dış adres, `data:` ve `..` reddedilir.
- Dosya 16 KB'tan büyükse, JSON değilse ya da `application/json` olarak gelmezse kullanılmaz.
- **Herhangi bir hatada dosyanın tamamı yok sayılır;** konsola bütün hataları yoluyla sayan tek bir uyarı yazılır ve panel varsayılanlarla açılır. Yarım uygulanmış bir marka (doğru ad, yanlış renk) fark edilmeden kalırdı; hiç uygulanmayan marka hemen görünür. Panel hiçbir durumda çökmez.

**Ürün adı.**
- `productName` (varsayılan "Verso") şuralarda görünür: sayfa başlığı, tanıtımın gizli başlığı, menü bağlantısının etiketi, "… ile başlayın", Kurulum durumu ve Bilgi kaynakları açıklamaları.
- İmza Verso işaretidir ve değişmez.
- Ad Türkçe hâl eki almadan yazılır ("Kurumunuzdaki X kurulumunda", "X cevap verirken"), çünkü bir kurulumun adının alacağı ek bilinemez.

**Kurum adı ve logo.** Menüde imzanın altında, logo kurum adının solunda görünür (20 px yükseklik, en çok 96 px genişlik, kendi oranıyla). Yüklenemeyen logo kırık resim olarak kalmaz, kaldırılır.

**Vurgu rengi.**
- Yalnız mevcut vurgu token'ları değişebilir: `fg-accent`, `accent-fill`, `accent-emphasis`, `accent-emphasis-hover`, `focus-ring`. Değer temaya göre verilir (`light`, `dark`) ve yalnız `#rrggbb` biçimindedir.
- Değerler CSSOM ile (`style.setProperty`) kök öğeye yazılır; satır içi stil özniteliği kullanılmaz (CSP). Tema her değiştiğinde o temanın değerleri yazılır, diğerininkiler kaldırılır.
- Kontrast JS'te ölçülür (WCAG 2.2):
  - metin rengi (`fg-accent`): sayfa, kart, menü ve vurgu zeminleri üzerinde 4,5:1;
  - UI parçaları (dolgu, düğme, odak halkası): zemin üzerinde 3:1;
  - düğme zemini: üstündeki etiket (`fg-on-emphasis`) için ayrıca 4,5:1.
- Zemin değerleri `branding.js`'teki tablodadır. Test bu tabloyu `panel.css`'ten çözümlenen değerlerle karşılaştırır; palet değişirse tablo geride kalamaz.
- Kontrastı yetmeyen renk reddedilir (bütün dosyayla birlikte). Örnek: panelin kendi altını `#c9a45c` fildişi sayfada 2:1'dir; çizgi olarak kullanılır, kurulumun metin rengi olarak kabul edilmez.

**Keycloak teması ayrıdır.** Giriş sayfası Keycloak'ın sunucu tarafı temasıdır (`deploy/keycloak/themes/verso/`) ve script çalıştırmaz; bu dosyayı okumaz. Müşteriye özel giriş sayfası için tema ayrıca markalanır ya da müşterinin kendi IdP'si kullanılır.

## Sonuçlar

**Olumlu:**
- Kurulum kaynak koduna dokunmadan markalanır; panel güncellemesi kurulumun dosyasıyla çakışmaz.
- CSP, backend ve veritabanı değişmedi. Yeni bir çalışma zamanı bağımlılığı yok.
- Hatalı bir dosya paneli bozmaz; konsoldaki tek uyarı her sorunu yoluyla söyler.

**Olumsuz / kabul edilen risk:**
- **Dosya herkese açıktır.** Kenar proxy'ye erişen herkes, giriş yapmadan okuyabilir; tanıtım ekranı zaten girişten önce gösterir. İçine gizli bilgi, iç sunucu adı ya da kişisel veri yazılmaz. `team` alanındaki iletişim adresi bir ekip adresi olmalıdır.
- **Doğrulama yalnız tarayıcıdadır.** Hata kullanıcıya değil, konsola yazılır. Kurulum ekibi dosyayı değiştirince paneli açıp konsola bakar (README). Sunucu tarafı doğrulama 2. adımdadır.
- **Açılışta bir istek daha** (aynı origin, çoğunlukla 304). En kötü durumda ilk ekran 3 sn gecikir.
- **Bazı metinler hâlâ "Verso" der:** Soru sor ekranındaki "Verso cevapladı" (ekran okuyucu) ve "Verso hata yapabilir" uyarısı. O ekran ayrı bir işte değişiyor; ad orada ayrıca bağlanacak.
- Logo SVG'si sanitize edilmez: yalnız kurulum ekibinden gelir, `<img>` içinde script çalışmaz, doğrudan açılışı CSP `sandbox` korur.

**Etkilenen dosyalar:**
- yeni `panel/js/branding.js`;
- `panel/js/{config,app}.js`, `panel/js/screens/{overview,setup,documents}.js`, `panel/js/screens/ask.js` (yalnız önerilen soruların okunduğu satır), `panel/css/panel.css`;
- `deploy/edge/nginx.conf`, `compose.yaml`, `deploy/compose.cloud.yaml`;
- yeni `deploy/branding/branding.example.json`, `.gitignore`;
- `scripts/panel.test.mjs`, `verso-app/.../ComposeConfigTest.java`;
- `README.md`.

**Geri alma yolu:** `deploy/branding/branding.json` silinir; panel `config.js` varsayılanlarıyla açılır. Kodun tamamı geri alınırsa varsayılanlar yine `config.js`'tedir.

## Yeniden değerlendirme koşulu

- Kurulumlar markayı operatör ekranından değiştirmek isterse ya da doğrulamanın sunucuda yapılması gerekirse: 2. adım (`GET/PUT /v1/branding`, `installation` şeması, denetim kaydı).
- Bir müşteri vurgu token'larından fazlasını (zemin, metin, durum renkleri) isterse: token listesi ve kontrast tablosu birlikte genişletilir.
