# ADR-0025 doğrulaması: Panelde geçmiş, geri bildirim ve değer panosu

Tarih 2026-10-24. Dal `feature/doguyaras-panel-gecmis-pano`, `origin/develop` (`6d72c1e`, ADR-0023 dahil) üstünde. Yalnız panel; backend, sözleşme ve CSP değişmedi. Çalışan compose yığınına dokunulmadı.

## Testler

- `GITLEAKS=… node --test scripts/*.test.js scripts/panel.test.mjs`: 151 test; 150 geçti, 1 atlandı (POSIX sinyali, Windows; önceden de böyle).
- Panel testi 71 test (önce 60). 11'i yeni, ikisi genişletildi:
  - **Rol kapısı:** pano yalnız `/v1/info` `roles` `verso-admin` içerirse; token'daki `verso-admin`, bozuk ya da eksik yanıt, büyük harfli değer açmaz; yönetici + operatörde alt çubuk dört sekme; 3 sn bekleme sınırı.
  - **Konuşma kimliği:** `askBody`, yanıttaki kimliğin üstünlüğü, kaydedilemeyen turda açık konuşmanın korunması, 11020/11022'de tek yeniden deneme, eski konuşmanın geç cevabının eklenmemesi, store kimliğinin DOM'daki `thread` öğesine yazılmaması (doğrulamada bulunan hata, aşağıda).
  - **Geçmişten açma:** turlar, puan, `restored`, silinmiş belge "Belge silinmiş", adı gelmeyen atıf; "Yeni konuşma" ve açma `generation`'ı artırır.
  - **Türkçe arama:** `İZİN`, `ızın`, `ışık` ↔ `IŞIK`, `sirket` ↔ `Şirket`, boşluklar, boş sorgu, `null` başlık.
  - **Başlık ve "Tümünü sil":** sunucunun kuralları (boş, 120, bidi, kontrol); `SİL`/`sil`/`Sil`/`SIL` kabul, diğerleri ret; `window.confirm|alert|prompt` hiçbir modülde yok; onay penceresi açıkken Esc paneli kapatmaz.
  - **Puan durum makinesi:** 👍↔👎 yer değiştirme, 👍 tekrarı istek göndermez, 👎 seçiciyi açar, birden çok atıfta "hangi [n]", tek atıfta sormaz, atıfsız cevapta "Yanlış kaynak" yok, yabancı neden ve 👎'siz neden reddi, sunucu yanıtının durumu belirlemesi; nedenler `FeedbackReason.java` ile aynı.
  - **Tarih aralığı:** 7/30/90 gün iki uç dahil, ay ve yıl geçişi, artık yıl, yerel takvim günü, DST, 366 gün sınırı, `tr-TR` etiketleri.
  - **Sayı biçimleri:** `1.284`, `%73,1`, `%100`, `null` → "–", `250 ms` / `2,5 sn` / `3 dk`, 👍 oranı, KPI sırası ve boş kurulum, günlük oranda soru olmayan günün `null` olması, neden satırları, belge adı politikası ve bidi temizliği.
  - **SVG yolları:** temiz ölçek (sayımda kesirli çizgi yok), çizgi değerleri, `null`'da kesilen çizgi, taban çizgisinde kapanan dolgu, yalnız noktalar, imlecin güne oturması, eksen günleri; CSS'te 2 px çizgi, ince düz ızgara, %10 dolgu.
  - **API çağrıları** (sahte `fetch` ile): yol, yöntem, JSON gövde, `encodeURIComponent`, 204 → `null`.
  - **Gizlilik:** yeni modüllerde tarayıcı deposu, URL yazımı ve `URLSearchParams` yok; sessionStorage anahtarları yalnız tercihler.
  - **Palet:** 6 yeni çift (iki tema): `--chart-1`/`--chart-2` kartta 3:1, drawer üstünde soluk ikonlar 3:1, basılı 👍/👎, "Tümünü sil" uyarısı, drawer ve ipucu metni 4,5:1.
  - **Hata kodları:** 11020–11030 için Türkçe metin (test artık konuşma dokümanını da okur).

## Mutasyon

Scratchpad'deki bir çalıştırıcıyla kaynakta tek tek uygulanan 22 mutasyonun **22'si** panel testinde öldü (ilk koşuda 21/22; kalan "sayımda kesirli çizgi" mutasyonu için `niceScale(2)` testi eklendi):

| Dosya | Mutasyon |
|---|---|
| `roles.js` | admin rolü token'dan okunur; `/v1/info`'daki herhangi bir rol panoyu açar |
| `conversations.js` | açık kimlik yanıttakini ezer; kimlik her zaman gönderilir; 11022 yeniden denenmez; adsız atıf silinmiş sayılmaz; arama katlanmaz; başlık sınırı bir fazla; her metin "Tümünü sil"i onaylar |
| `feedback.js` | 👍 tekrarı gönderilir; "hangi [n]" hiç sorulmaz; 👎'siz neden kabul edilir |
| `metrics.js` | aralık bir gün uzun; yüzde `en-US`; boş gün %0; ad politikası yok sayılır |
| `chart.js` | `null` taban çizgisine çizilir; sayımda kesirli çizgi |
| `api.js` | soru konuşma kimliğini düşürür; kimlikler kodlanmaz |
| `screens/ask.js` | eski konuşmanın cevabı yenisine eklenir |
| `ui.js` | onay penceresindeki Esc paneli de kapatır |

## Görsel doğrulama

**Kurulum:**
- Paneli tek kullanımlık bir nginx container'ı sundu: yığındaki `edge` ile aynı image ve digest, repodaki `deploy/edge/nginx.conf` ve `mode-local.conf`, salt okunur, `127.0.0.1:18091`. CSP başlığı yığındakiyle aynıydı.
- IdP ve uygulama yoktu. Yalnız scratchpad'de duran, repoya girmeyen bir fikstür `index.html`'e eklenen tek modülle `fetch`'i taklit etti: token ucu (imzasız sahte token), `/v1/info` (rol seçilebilir), `/v1/documents`, `/v1/questions` (konuşma kimliğiyle), `/v1/conversations/**` (bellekte 25 konuşma, biri silinmiş belgeye atıflı ve puanlı), `/v1/admin/metrics` (dolu, boş, 403, 503).
- Container iş bitince durduruldu ve silindi (`--rm`); dal `git status`'ta fikstür taşımıyor.

**Ekranlar (uygulama içi Chromium):**
- **1440 px, açık:** menüde "Soru sor"un altında "Geçmiş" (ikon şeridinde de); soru ekranında üstte "Geçmiş" ve "Yeni konuşma"; iki soru aynı konuşmaya yazıldı (fikstürde 2 tur). 👍/👎 cevap eylemlerinin sağında; 👎'den sonra "Neden? Yanlış kaynak · Eksik ya da yanlış · Güncel değil · Diğer"; "Yanlış kaynak"ta `[1] satin-alma-proseduru · s. 2`, `[2] depo-yonetim-kilavuzu · böl. 3`; giden istekler sırayla `DOWN`, `DOWN + WRONG_SOURCE + 2`, `UP`; "Teşekkürler. Neden: Yanlış kaynak [2]." duyuruldu.
- **Geçmiş paneli:** 26 konuşma, ilk 20 ve "Daha eski konuşmaları yükle" (sonra 26); açık konuşma altın zeminde "· açık"; arama yüklenenlerde sonuç sayısını söyledi; yeniden adlandırma Enter ile kaydedildi ve odak düğmeye döndü; boş başlıkta "Başlık boş olamaz."; Esc yalnız düzenlemeyi kapattı. Silme panelin kendi onay penceresini açtı ("Vazgeç" odakta), onayla satır gitti ve odak sonraki satıra geçti. "Tümünü sil": `SİL` yazılmadan düğme bir şey yapmadı, `sil` + Enter ile hepsi silindi, açık konuşma kapandı, "Henüz konuşma yok" boş durumu ve bildirim göründü.
- **Geçmişten açma:** konuşma başlığı üstte, turlar "Geçmişten" etiketiyle; takip soruları, "Düzenle" ve ".md indir" yok; kaynak kartında "Geçmişte alıntı saklanmaz." ve silinmiş belge için "Belge silinmiş" + "Belge silinmiş; alıntı gösterilemez."; kayıtlı 👎 + "Yanlış kaynak" basılı. Yeni soru aynı konuşmaya 3. tur olarak yazıldı. Konuşma arkadan silinince sonraki soru "Önceki konuşma artık yok; soru yeni bir konuşmada soruldu." notuyla yeni konuşmaya gitti.
- **Değer panosu, 1440 px açık ve koyu:** aralık "25 Eyl – 24 Eki 2026 · Europe/Istanbul"; yedi KPI (`358`, `37`, `%80,5`, `%16,2`, `%3,4`, `2 sn / 7,5 sn`, `%76,7`); iki çizgi grafik, hafta sonu boşlukları kesik; üzerine gelince dikey imleç ve "7 Eki · %87,5 Kaynaklı · %12,5 Bulunamadı" ipucu; klavyede Home ile "25 Eyl: Soru 13" duyuruldu; tablo 60 satır (iki grafik). 90 gün seçilince eski sayılar soluk kaldı, sonra yenisi geldi ve aktif kullanıcı notu "saklama süresinden eski" dedi. Ad politikası kapalıyken "Belge #0199a901", açıkken dosya adları; silinmiş belge italik "Belge silinmiş". Cevaplanamayan sorular yalnız açıkken, k-anonimlik açıklamasıyla.
- **Durumlar:** boş kurulum (soru yok) boş durum + hiç atıf almamış belgeler; 403 "Bu pano yöneticilere açık" (bildirim yok); rolsüz kullanıcıda menüde pano yok ve `#/insights` Genel bakış'a düştü; hesap menüsünde "Yönetici".
- **375 px, açık ve koyu:** KPI'lar iki sütun, grafikler genişliğe göre yeniden çizildi, alt sekme çubuğu dört sekme; geçmiş paneli tam genişlik; 👍/👎 44 px; yatay taşma yok (`scrollWidth` 375); hesap menüsünde "Konuşma geçmişi" ve "Değer panosu".
- **Konsol:** yalnız beklenen `branding.json` 404'leri. Hızlı ardışık gezinmede görülen "Uncaught (in promise) AbortError: Transition was skipped" `motion.js`'te yakalandı ve tekrar denemede görülmedi. CSP ihlali yok.

**Doğrulamada bulunan hata:** soru ekranında cevap kütüğünün DOM öğesi de `thread` adını taşıyordu; store'un `thread`'i onun gölgesinde kaldı ve konuşma kimliği DOM öğesine yazıldı (konuşma çubuğu "null" gösterdi). Store nesnesi `current` adıyla içe alındı; test bunu ve `thread.` erişiminin kalmadığını denetler.

**Doğrulanamayanlar:** gerçek IdP ve gerçek API ile uçtan uca; `prefers-reduced-motion` (yeni ekranlarda animasyon yok, genel CSS kuralı ve testi değişmedi); ekran okuyucuyla gerçek duyuru (yalnız `aria-live` içerikleri okundu).

## Review skill'leri

| Skill | Karar | Bulgular ve sonuç |
|---|---|---|
| `verso-security-review` | APPROVE | Bu kapsamda güvenlik bulgusu yok. Başlık, soru, cevap, dosya adı ve cevaplanamayan soru metinleri yalnız metin düğümü (`el()`, `textContent`; `innerHTML` yasağı testte); yol kimlikleri kodlu; `CSS.escape` ile seçici; tarayıcı deposu ve URL'e kişisel veri yazılmıyor (test); rol yalnız arayüzü gizliyor, sunucu `@PreAuthorize(HAS_ADMIN)` ile 403 dönüyor (`AdminMetricsController`); sahiplik sunucuda (başkasının konuşması 404). CSP aynı (test). Java değişmediği için Maven testleri koşulmadı. |
| `verso-test-writer` | PASS | Davranış listesi yukarıdaki 11 test. 22 mutasyonun 22'si öldü (ilk koşuda kalan biri için test eklendi). **Kalan boşluk:** DOM ortamı olmadığından `rating.js` ve `history.js`'in tıklama akışı Node testinde yalnız kaynak düzenli ifadesiyle ve fikstürle görsel olarak doğrulandı. |
| `verso-llm-review` | APPROVE WITH NON-BLOCKING COMMENTS | Model çağrısı, prompt, retrieval ve egress değişmedi; CSP `connect-src` aynı. Model çıktısı yalnız metin; `[n]` yalnız sunucunun atıflarıyla eşlenir. Geçmiş prompt'a girmez; takip soruları bütün soru kalır. 11020/11022'de yeniden deneme ilk istek model çağrılmadan reddedildiği için tek model çağrısıdır. **L1 (LOW, kabul edilmiş risk):** silinmiş bir belgeden alıntı yapan geçmiş cevap metni panelde görünmeye devam eder (ADR-0023 §7, L3); panel atfı "Belge silinmiş" diye işaretler. |

`verso-spring-code-review` ve diğer backend skill'leri uygulanmadı: Java, config, migration ve sözleşme değişmedi. Gerçek bir API hatası bulunmadı.
