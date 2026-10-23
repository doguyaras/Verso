# ADR-0018 doğrulaması: Fildişi tuval

Tarih 2026-10-24. Dal `feature/doguyaras-fildisi-tuval`. Yığın GPU katmanıyla çalışıyordu (`compose.yaml` + `deploy/compose.gpu.yaml`).

Değişiklikler canlı yığına iki adımla alındı:
- Kenar proxy `nginx -s reload` ile yeniden yüklendi (`font-src 'self'`, `font/woff2`).
- Keycloak `--force-recreate` ile yeniden oluşturuldu; tema önbelleği temizlendi.

## Testler

- **`node --test scripts/panel.test.mjs`:** 33/33 geçti. Yeni ya da genişletilen testler:
  - **palette:** 2 tema × 19 çift. Menü, altın metin ve dört durum rengi eklendi.
  - **fonts:** dört WOFF2 ve `OFL.txt` yerinde. latin-ext var. nginx'te `font-src 'self'` ve `font/woff2` tanımlı, data: ya da dış adres yok. Keycloak temasındaki kopyalar bayt bayt aynı.
  - **setup:** varsayılanlarda yalnız "şirket girişi" tamamlanmış görünür. Bulut modu notu sağlayıcıyı söyler. Bağlantılar "Planlandı" rozetiyle listelenir.
  - **roles:** menü en çok beş ekran, mobil alt çubuk en çok dört sekme.
  - **motion:** giriş ekranındaki boyalı katmanlarda ve Keycloak temasında `infinite` animasyon yok (WCAG 2.2.2).
  - **signature:** imzanın tamamı 1,15 sn'de atılır.
- **`ComposeConfigTest`** (`./mvnw -pl verso-app -am test -Dtest=ComposeConfigTest`) geçti; CSP başlığı yeni `font-src 'self'` ile birebir sabitli.

**Mutasyonlar** (hepsi geri alındı):
- Açık temada meta rengini `#a89a82`'ye açmak palette testini kırdı: "fg-subtle on bg-surface is 2.60:1".
- Keycloak temasındaki `OFL.txt`'ye bir satır eklemek fonts testini kırdı: "the login theme serves the same OFL.txt".
- Işık huzmesi animasyonunu `infinite` yapmak motion testini kırdı: ".paint.rays".

## Canlı

**Panel giriş ekranı:**
- Açık temada boyalı gökyüzü, Fraunces başlık, kâğıt form ve altın köşe süsleri göründü.
- Koyu temada mürekkep zemin, altın imza ve deniz mavisi ışık göründü.
- Gül yaprağı yok.

**Keycloak:**
- Türkçe ve İngilizce sayfalar açıldı. İngilizce kesme işaretleri doğru: `organisation’s`. Java MessageFormat düz `'` işaretini yutuyordu, bu yüzden tipografik `’` kullanıldı.
- Form kontrol edildi. Odakta tek altın halka çıkıyor. Şifre alanı ve göz düğmesi tek alan gibi görünüyor. Dil seçici sade bir hap. Koyu tema da denendi.
- Keycloak font dosyasını `application/octet-stream` olarak döndürüyor. Tarayıcı fontu yine yükledi (başlıklar Fraunces); `nosniff` font türünü denetlemez.

**Kabuk ve ekranlar:**
- **Menü:** açık temada fildişi, imza antrasit; koyu temada imza altın.
- **Genel bakış:** "Kurulum durumu" kartı Pilot aşamasını gösterdi.
- **Kurulum durumu:**
  - Dört aşama görünüyor; Keşif tamamlandı, Pilot sürüyor.
  - Kontrol listesi 3/5. Giriş ve yerel mod tamam; 7 hazır belge bulundu.
  - Bağlantılar "Planlandı".
- **Bilgi kaynakları:** tablo ve planlanan bağlantılar koyu temada denendi.
- **Konsol:** hata yok.

**Mobil (375 px):** giriş ekranında yatay kaydırma yok (`scrollWidth` 375).

## Varsayılan tema (ek, aynı gün)

- Kullanıcı koyu sistemde paneli koyu gördü. Varsayılan artık açık tema; Keycloak `darkMode=false`.
- `node --test scripts/panel.test.mjs`: 34/34. Yeni "theme" testi: koyu sistemde varsayılan açık; koyu yalnız seçilince; Keycloak `darkMode=false`.
- Canlı, tarayıcı koyu renk şemasında: panel `data-theme="light"` açıldı, Keycloak sayfasında `pf-v5-theme-dark` sınıfı yok, marka paneli fildişi gökyüzü.

## Bilinen sınırlar

ADR-0018'in "Olumsuz / kabul edilen risk" bölümünde ayrıntılı:
- "Verso" adının oyun karakteriyle çakışması: marka taraması önerildi.
- Fraunces boyutu 213 KB.
- Kurulum aşaması config dosyasından gelir.
- "Kaynaklı ilk cevap" yalnız bu sekmeye bakar.
