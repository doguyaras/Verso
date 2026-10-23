# ADR-0017: Panel v3 tasarım sistemi ve Verso giriş teması

- **Durum:** Kabul edildi
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

**Renk: "Petrol" paleti.**
- 12 adımlı gri ve vurgu ölçekleri; vurgu `#177a83` / `oklch(53% 0.085 205)`.
- Dört durum rengi; adım rolleri Radix modelinde.
- Bileşenler yalnız fonksiyonel token'ları kullanır (`--bg-*`, `--fg-*`, `--border-*`, `--accent-*`), Primer modelinde.
- Mor/indigo, gradyan vurgu, glow ve parıltı ikonu kullanılmaz.
- Kontrast WCAG 2.2 AA'dır:
  - Ana metin 16,3:1.
  - Vurgu dolgusu üzerinde beyaz 5,1:1.
  - Form kenarlığı 3,3:1; bu yüzden gri 8 değil, gri 9 kullanılır.
- Koyu temada yükselti gölgeyle değil, açılan yüzeyle verilir.

**Tipografi ve şekil.**
- Sistem fontları, Segoe UI başta: Windows'ta `system-ui` dile göre başka fonta düşebiliyor.
- Ölçek 12/13/14/16/18/20/24/30 px; sayılar `tabular-nums`.
- Köşe yarıçapı 4/8/12 px; düğme yüksekliği 32/36/44 px.
- Odak halkası 2 px + 2 px boşluk.
- Hareket 100–250 ms, girişte ease-out; `prefers-reduced-motion` ile kapanır.

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
- `keycloak.v2`'nin alt teması: yalnız CSS ve işaretleme, kendi script'i yok.
- `template.ftl` keycloak.v2'nin kopyasıdır; tek fark, formun yanına statik bir marka paneli eklenmesidir.
- Metinler `messages_tr` / `messages_en` dosyalarındadır.
- Realm: `loginTheme: verso`, `defaultLocale: tr`. Panel giriş isteğine `ui_locales=tr` ekler; tarayıcı İngilizce olsa da sayfa Türkçe açılır.
- Tema compose'da salt okunur bağlanır.

## Sonuçlar

**Olumlu:**
- Kullanıcı cevabın hangi belge ve sayfaya dayandığını ve o pasajı tek hareketle görür. Canlı denemede model "yirmi gün" dedi (belgede "on dört gün"). Önizleme doğru belgeyi ve sayfayı gösterdi, ama alıntı pasajın başı olduğu için ilgili cümleyi içermedi. Bu, aşağıdaki "alıntı penceresi" işinin gerekçesidir.
- Ürün ilk ekrandan itibaren Türkçe ve kendi kimliğiyle görünür.
- Yeni bağımlılık yok. CSP değişmedi; görünüm ayarları için yalnız CSSOM `element.style` kullanılır.

**Olumsuz / kabul edilen risk:**
- **Alıntı pasajın başıdır,** cevabın dayandığı cümle olmayabilir. Daha isabetli seçim (cevapla örtüşen pencere) sonraki iştir.
- **"Durdur" yalnız beklemeyi bırakır:** sunucudaki model çağrısı kendi süresinde biter. Sunucu tarafı iptal, istek bağlantısı koptuğunda çağrıyı kesecek ayrı bir değişikliktir.
- **Keycloak güncellemelerinde şablon:** `template.ftl` keycloak.v2'nin kopyası olduğu için her Keycloak sürüm yükseltmesinde üst temanın şablonuyla karşılaştırılmalıdır (`ImageVersionsTest`'in sürüm sabitlemesiyle aynı anda).
- **Keycloak tema kaynaklarını iki yerde önbelleğe alır:**
  - Sıkıştırılmış kopyalar `data/tmp/kc-gzip-cache`'tedir ve yeniden başlatmada silinmez. Tema değişince container yeniden oluşturulur: `docker compose up -d --force-recreate keycloak`.
  - Tarayıcılar kaynakları 30 gün önbellekte tutar; URL'deki sürüm Keycloak sürümüyle değişir. Canlıda bir tema değişikliği, CSS dosyasının adını değiştirerek dağıtılmalıdır (`verso.css` → `verso-2.css`).
- **Realm değişikliği:** realm yalnız ilk açılışta içe aktarılır; mevcut yığında `bash scripts/keycloak-reimport.sh` gerekir. Bu, demo IdP'nin kullanıcı kimliklerini yeniler. Belgeler `sub`'a bağlı olduğundan, önceki demo kullanıcılarının yüklediği belgeler yeni kimlikle görünmez; veri silinmez.

**Etkilenen dosyalar:**
- `panel/` (bütün modüller, `css/panel.css`; yeni `js/palette.js`, `js/store.js`)
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
