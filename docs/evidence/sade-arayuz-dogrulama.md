# Sade arayüz: doğrulama ve kanıt kaydı (ADR-0058 "Sadeleştirme")

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

## Künye

- **Tarih:** 2026-10-28
- **Branch:** `feature/doguyaras-sade-arayuz` (`origin/develop` üzerinden).
- **Ortam:** Windows 11, Node 24 (yerel). Docker yığını açıktı ama `localhost:8080/panel/` ana çalışma kopyasını (`C:\verso\panel`) sunar, bu dalın worktree'sini değil. Ana kopyaya dokunmamak için görsel kontrol, worktree'nin `panel/` klasörünü sunan geçici bir önizleme sunucusunda yapıldı (`localhost:8099`, depoya girmedi): oturum açma sahte bir `auth.js` ile atlandı, `/v1/*` istekleri sabit örnek yanıtlarla karşılandı. Gerçek API ve Keycloak ile uçtan uca görsel kontrol yapılmadı.

## 1. Yerelde koşulan

| Kontrol | Komut | Sonuç |
|---|---|---|
| Node testleri (panel dahil) | `GITLEAKS=/c/Users/doguy/.local/bin/gitleaks.exe node --test scripts/*.test.js scripts/panel.test.mjs` | PASS (365 test, 0 hata) |
| CSS blok dengesi | aynı koşu, "panel.css: every block is closed" | PASS |

Yeni testler (`scripts/panel.test.mjs`, "Sadeleştirme" blokları): rol başına menü (en çok 5 öğe + Yönetim, başlık yok), Bilgi sekmeleri ve "← Yönetim", telefon çubuğu (Ana sayfa, Asistan, Beceriler, Bilgi), hub içeriği rol başına ve ipuçları, eski her adresin rolüyle çözülmesi, paletin `screensFor` ile aynı kümeyi listelemesi, `g` kısayolları, özellik algılama, ana sayfa sınırları (öneri ≤ 3, beceri ≤ 4, yeni ≤ 3), yönetici tek satırı ("Her şey yolunda" / "N konu dikkat istiyor", hedef ekran, okunamayınca satır yok), ilk çalıştırma koşulları, açılır bölümlerin varsayılan kapalı olması; ekran ekran ilk görünüm sınırları (Komuta panosu 3 döşeme, Etkinlik merkezi 5 kayıt ve "Filtrele", takip önerileri ≤ 2, kaynaklar "Kaynakları göster" altında, formlar istenince).

## 2. Görsel kontrol (önizleme sunucusu, örnek veri)

**Önce** (develop, ekran görüntüsü yok; kod ve ADR'den): sol menüde 4 grup başlığı altında yönetici için 17 öğe + Geçmiş; telefonda "Daha" menüsü; ana sayfada 6 hızlı başlat, beş sayılı "Bu hafta şirketinizde" şeridi, kurulum kontrol listesi kartı, menü altında kurulum çipi, "Ekibinizin son çıktıları" ve "Bilgide yeni" (4) yan yana; ekran başlarında 3–5 satırlık açıklama paragrafları.

**Sonra:**

- **1440 px, yönetici, daraltılmış şerit:** 5 ikon (Ana sayfa, Asistan, Beceriler, Bilgi, Geçmiş) ve en altta dişli; ana sayfa ortada 860 px: başlık, geniş yazma kutusu, 3 öneri, "● 4 konu dikkat istiyor →", "Kurulum · 3/5 adım ▬ Devam et →", 4 beceri kartı tek sırada, "Bilgide yeni" 3 satır, kapalı "Ekibinizin son çıktıları (1)".
- **1440 px, kullanıcı, açık menü:** beş öğe, Yönetim yok, dikkat satırı ve kurulum satırı yok.
- **Yönetim hub'ı:** İçgörü / Bağlantılar / Erişim ve uyum / Kurulum başlıkları altında çerçevesiz kartlar; ipuçları görünür ("3 iş dikkat istiyor", "1 bulgu", "18.420 / 25.000 bu ay", "1 bağlayıcı çalışıyor", "3/5 adım").
- **Bilgi:** "Onaylı bilgiler" açıkken üstte sekmeler (Kaynaklar ve alanlar, Onaylı bilgiler, Prosedürler, Sistem haritası), menüde Bilgi seçili; "Kaynaklar ve alanlar"da açıklama tek satır + ⓘ, planlanan bağlantılar kapalı bölümde.
- **Komuta panosu / Etkinlik merkezi:** üstte "← Yönetim"; panoda 3 döşeme ve kapalı "Ayrıntılar · 1 öncelikli sorun"; etkinlikte durum satırı ve kapalı "Filtrele".
- **375 px:** yazma kutusu tam genişlikte, 3 öneri alt alta, beceriler 2×2 sıkı kart (açıklama karttaki ipucunda), alt çubuk Ana sayfa / Asistan / Beceriler / Bilgi; hesap menüsünde tema, Geçmiş, Yönetim, Çıkış.
- **İlk çalıştırma (belge ve geçmiş yok, yönetici):** "Hoş geldiniz" kartı, 3 adım; kapatınca kart gider, "Bilgide yeni" tek eylemli boş durumuyla ("Belge ekleyin") gelir; `sessionStorage` `verso.panel.onboarding` içinde yalnız `welcomeDismissed: true`.

Önizlemede örnek yanıtı olmayan uçlar (bildirimler, etkinlik listesi, onaylı bilgiler) hata bildirimi gösterdi; bu, önizleme sunucusunun eksik örneğidir, panel davranışı değil.

## 3. Canlı kontrol bekleyen (yapılmadı)

- Gerçek yığında (`localhost:8080/panel/`, `admin` ve `demo`) aynı turun tekrarı: bu dal ana kopyaya alınınca.
- Koyu tema (Obscur) ve `prefers-reduced-motion` altında hub ve ana sayfa.
- Cevap görünümü (kaynak çipleri, "Kaynakları göster", 2 takip önerisi) gerçek bir cevapla.
