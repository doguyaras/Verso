# ADR-0025: Panelde konuşma geçmişi, cevap geri bildirimi ve değer panosu

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras. İstek: ADR-0023'ün sunucu özellikleri (geçmiş, 👍/👎, yönetici metrikleri) panelde kullanılabilsin; yalnız panel, gerçek bir API hatası çıkmadıkça backend değişmez. Yol haritası `ui_ux.md` maddeleri 3, 4, 5 ve 7; `market_needs.md` madde 5.
- **İlgili eşik (referans Bölüm 24):** yok (sunum katmanı). ADR-0015'in "ekran sayısı beşi geçerse" koşulu yeniden değerlendirildi (aşağıda).
- **Genişlettiği:** [ADR-0023](0023-qa-semasi-gecmis-ve-metrikler.md) (sunucu tarafı), [ADR-0024](0024-cevap-ekrani.md) (cevap ekranı), [ADR-0019](0019-token-rolleri-ve-gruplari.md) (`/v1/info` `roles`).

## Bağlam

- ADR-0023 her cevabı sunucuda kullanıcının geçmişine yazıyor, puanlamayı ve `verso-admin` metriklerini açıyordu. Panel bunları kullanmıyordu: her soru yeni bir konuşma oluyor, geçmiş yalnız API ile görülüp siliniyordu (ADR-0023 "Olumsuz").
- Kısıtlar değişmedi:
  - derleme adımı yok, vanilla ES modülleri;
  - CSP ve Trusted Types aynen kalır: `innerHTML` ve satır içi stil yok, `createElement`/`createElementNS` ve CSSOM;
  - sessionStorage'da yalnız tercihler;
  - `prefers-reduced-motion`;
  - kontrast çiftleri palet testinden geçer;
  - Türkçe, kısa ve sade metin; ADR-0018 kimliği; ürün adı marka dosyasından, hâl eki almadan (ADR-0021).
- API'nin verdikleri ve vermedikleri (kod ve `docs/api-conversations-integration-v1.md`):
  - Saklama süresi (`verso.qa.history.retention-days`) hiçbir yanıtta yok.
  - Metrikler "tahmini kazanılan süre"yi türetecek bir veri taşımıyor (soru başına beklenen arama süresi, maliyet ya da kullanıcı beyanı yok).
  - Geçmişteki atıflarda alıntı (`excerpt`) yok; silinen belge `documentDeleted: true`, `fileName: null`.

## Seçenekler

**Geçmişin yeri:**

| Seçenek | Artı | Eksi |
|---|---|---|
| **A. Menüde bir düğme + sağdan açılan panel (drawer)** | Her ekrandan açılır; ekran sayısı artmaz; mevcut `openDrawer` deseni | Uzun listede panel dar |
| B. Ayrı "Geçmiş" ekranı | Geniş liste | Kullanıcı için altıncı ekran; soru ekranından ayrılmak gerekir |
| C. Soru ekranında kalıcı sol sütun | ChatGPT benzeri | 1200 px'te kaynak sütunuyla birlikte cevaba yer kalmaz; telefonda yok |

**Yönetici rolünün kaynağı:**

| Seçenek | Artı | Eksi |
|---|---|---|
| **A. `GET /v1/info` `roles`** | Sunucunun kendi okuması; IdP'den bağımsız (Entra `roles` vb., ADR-0019) | Açılışta bir istek daha |
| B. Token'daki `realm_access.roles` | İstek yok | Yalnız Keycloak; sunucu ayarıyla sapabilir |

**Grafik:** elle çizilen satır içi SVG (`createElementNS`) ya da bir grafik kütüphanesi. Kütüphane bağımlılık ve büyük olasılıkla CSP/Trusted Types istisnası isterdi; ADR-0015 bağımlılıksızlık kararı geçerli.

## Karar

Geçmiş için **A**, rol için **A**, grafik için elle SVG.

**Ekran sayısı (ADR-0015 yeniden değerlendirme koşulu).**
- Geçmiş bir ekran değil, paneldir: kullanıcı ve operatör en çok beş ekran görür.
- Değer panosu yalnız `verso-admin`'e görünen altıncı ekrandır (operatör + yönetici). Mobil alt çubukta yoktur (`tab: false`); telefonda hesap menüsünden açılır.
- Bileşen çatısına geçilmedi: kural ekranlar arasında değil, ekran içindeki durum karmaşıklığına bağlı; yeni mantık saf modüllerde (`conversations.js`, `feedback.js`, `metrics.js`, `chart.js`) ve testli. Çatı yine onay gerektiren bir bağımlılık kararıdır; ekranlar artmaya devam ederse ADR-0015 koşulu yeniden açılır.

**Konuşma sürekliliği (`panel/js/conversations.js`, `store.js`, `screens/ask.js`).**
- Açık konuşmanın kimliği yalnız sekmenin belleğinde durur (`thread`). İlk soru kimliksiz gider; sonraki sorular `conversationId` taşır.
- Her cevaptan sonra **yanıttaki** `conversationId` kullanılır (sunucu silinmiş ya da süresi dolmuş konuşma yerine yenisini açabilir). Kayıt başarısızsa (`null`) açık konuşma korunur; bu cevap puanlanamaz.
- 11020 (konuşma yok) ve 11022 (dolu) model çağrılmadan önce döner. Panel soruyu bir kez, kimliksiz yeniden sorar ve cevabın üstüne bir not koyar ("Önceki konuşma artık yok; soru yeni bir konuşmada soruldu."). İkinci bir model çağrısı olmaz.
- "Yeni konuşma" (soru ekranında, geçmiş panelinde, komut paletinde) açık konuşmayı bırakır. Bekleyen bir cevap varsa durdurulur.
- Konuşma değiştiğinde bir sayaç (`generation`) artar. Eski konuşmaya ait geç gelen cevap yeni konuşmaya eklenmez; sunucuda kendi konuşmasında durur.

**Geçmiş paneli (`screens/history.js`).**
- Menüde "Soru sor"un altında bir düğme (`aria-haspopup="dialog"`), `g g`, hesap menüsü, komut paleti, soru ekranındaki "Geçmiş" ve Genel bakış'taki bağlantı açar.
- Liste 20'şer sayfalanır ("Daha eski konuşmaları yükle"). Satır: başlık, son etkinlik (göreli; tam tarih ipucunda), soru sayısı; açık konuşma işaretli.
- Arama yalnız **yüklenen** başlıklarda ve istemcidedir: `foldTr` ile Türkçe harf ve büyük/küçük harf katlanır ("ızın" → "İzin", "sirket" → "Şirket"). Not satırı kaç konuşmada arandığını söyler. Sunucu tarafı arama API'de yok (ADR-0023 açık soru).
- **Aç:** konuşma soru ekranına gelir ve sonraki soru onu sürdürür. Gelen turlar salt okunurdur: alıntı saklanmadığı için takip soruları, "Düzenle" ve ".md indir" yok; "Kopyala" ve puanlama var; "Geçmişten" etiketi taşır. Silinmiş belgeye atıf yerini korur ve "Belge silinmiş" diye görünür.
- **Yeniden adlandır:** satır içinde; sunucunun kuralları önce panelde (boş değil, en çok 120 karakter, kontrol ve yön karakteri yok). Esc yalnız düzenlemeyi kapatır.
- **Sil:** panelin kendi onay penceresi (`confirmDialog`, `<dialog>`), "Vazgeç" odakta. `window.confirm` hiçbir yerde yok (test).
- **Tümünü sil:** iki adım, panelin içinde. Düğme bir uyarı ve metin kutusu açar; "Tümünü kalıcı olarak sil" yalnız `SİL` yazılınca çalışır (büyük/küçük harf ve `İ`/`I` farkı gözetilmez).
- Açık konuşma silinirse soru ekranı yeni konuşmaya geçer.
- Pencerenin üstündeki onay penceresi açıkken Esc yalnız onu kapatır (`ui.js` `openDrawer`).
- **Saklama süresi:** API süreyi vermiyor. Panel "Konuşmalarınız yalnız size görünür ve kurulumun saklama süresi dolunca kendiliğinden silinir." der; sayı yazmaz. Süre `GET /v1/info`'ya eklenirse metin "Konuşmalar N gün sonra silinir" olur (yeniden değerlendirme).

**Geri bildirim (`feedback.js`, `rating.js`).**
- Her kaydedilmiş cevabın altında 👍 ve 👎 ikon düğmeleri, `aria-pressed` ile. Tur kimliği yoksa düğmeler yoktur.
- 👍'ye basılı iken yeniden basmak bir şey göndermez (API'de puanı geri alma yok). 👎 hemen `DOWN` gönderir ve küçük bir neden seçici açar; 👎'ye yeniden basmak yalnız seçiciyi açar/kapatır.
- Nedenler API'nin enum'u ve sırasıdır (`FeedbackReason.java` ile testte karşılaştırılır): Yanlış kaynak, Eksik ya da yanlış, Güncel değil, Diğer. Atıfsız cevapta "Yanlış kaynak" yoktur. Birden çok atıfta "Hangi kaynak yanlış?" sorulur (`[n] belge · s. 3` çipleri); tek atıfta sorulmaz.
- Yeni puan eskisinin yerine geçer. İstekler sırayla gider; sunucuda son tıklama kalır. Hata olursa düğme önceki durumuna döner; 11021'de (cevap artık yok) düğmeler kaldırılır.
- Kaydedilince "Teşekkürler." kısa süre (2,5 sn) görünür ve `role="status"` ile kibarca duyurulur.
- Serbest metin yok (ADR-0023 §5).

**Değer panosu (`screens/insights.js`, `metrics.js`, `chart.js`).**
- **Görünürlük:** açılışta `/v1/info` en çok 3 sn beklenir; `roles` `verso-admin` içeriyorsa ekran menüye eklenir (`withServerRoles`). Token'daki `verso-admin` tek başına yetmez. Yanıt gelmez ya da bozuksa pano gizli kalır. Sunucu yine 403 döner; panel bunu sakin bir boş durumla gösterir ("Bu pano yöneticilere açık"), hata bildirimiyle değil.
- **Aralık:** 7 / 30 / 90 gün, bugün dahil (tarayıcının takvim günü). Tek filtre satırı her şeyi kapsar; yeniden yüklemede eski sayılar soluk kalır, iskelet yanıp sönmez.
- **KPI kartları** (`tr-TR` sayı ve yüzde: `1.284`, `%73,1`): soru, aktif kullanıcı (aralığın başı saklama süresinden eskiyse not), kaynaklı cevap, bulunamadı, kaynak gösterilemedi, p50 / p95 süre ("yaklaşık": kova üst sınırı), 👍 oranı. API'nin `null` döndüğü oran "–"dir.
- **Eğilim çizgileri:** "Günlük soru" (tek seri, %10 dolgu, son değer çizginin ucunda) ve "Günlük sonuç oranı" (kaynaklı ve bulunamadı, iki seri, açıklama). Soru olmayan gün oran değil boşluktur; çizgi orada kesilir, %0 gibi çizilmez.
- **Grafik kuralları** (dataviz yönergesi, panelin token'larıyla): 2 px çizgi, yüzey renginde halkalı uç noktası, ince düz ızgara, tek y ekseni, metin metin token'larıyla (seri rengiyle değil), dikey imleç ve tüm serileri gösteren tek ipucu; aynısı klavyeyle (←/→, Home/End, Esc) ve `aria-live` ile; her grafiğin altında "Tablo olarak göster". Renkler `--chart-1` (sıcak altın-kahve) ve `--chart-2` (soğuk mavi); dataviz doğrulayıcısı iki temanın kart yüzeyinde açıklık bandı, kroma, renk körlüğü ayrımı ve 3:1 kontrastı geçirdi; palet testinde de 3:1 çiftleri var.
- **Diğer kartlar:** mod dağılımı (2 px boşluklu parça çubuğu, sayılar açıklamada), 👎 nedenleri (tek renk yatay çubuk, değer ucunda), en çok atıf alan belgeler, hiç atıf almamış belgeler (toplam ve en eski olanlar; `complete: false` notu).
- **Belge adı politikası:** `documentNamesShown` false ise ad gösterilmez, "Belge #0199a3b4" (kimliğin ilk 8 karakteri); silinmiş belge "Belge silinmiş". Gösterilen adlardan kontrol ve yön karakterleri silinir.
- **Cevaplanamayan sorular** yalnız `enabled: true` ise ve kısa bir k-anonimlik açıklamasıyla ("Yalnız en az 3 farklı kişinin sorduğu…; kimin sorduğu hiçbir yerde yoktur").
- **Tahmini kazanılan süre gösterilmez.** API'de dürüstçe türetilecek veri yok; bir varsayımla çarpılmış sayı pilot raporunda gerçek ölçüm gibi okunurdu.
- **Boş kurulum:** aralıkta soru yoksa sıfırlar yerine boş durum; hazır ama hiç atıf almamış belgeler varsa onlar yine görünür.

**Gizlilik.**
- Başlık, soru, cevap ve dosya adı sayfaya yalnız metin düğümü olarak girer; konsola, URL'e ve tarayıcı deposuna yazılmaz (test). sessionStorage anahtarları yalnız tercihlerdir.
- Yol kimlikleri `encodeURIComponent` ile kodlanır (test).

**Ek düzeltme.** `motion.js` hızlı ikinci gezinmede atlanan view transition'ın reddedilen sözünü yakalar; konsolda "Uncaught (in promise) AbortError" görünüyordu (fikstürle doğrulamada fark edildi).

## Sonuçlar

**Olumlu:**
- Kullanıcı konuşmasını sürdürür, eskisini açar, adlandırır ve siler; KVKK silme hakkı panelden kullanılabilir.
- Cevap kalitesi ve "yanlış kaynak" geri bildirimi pilot raporuna akar.
- Yönetici değer panosunu ek bağımlılık, CSP değişikliği ya da backend değişikliği olmadan görür.

**Olumsuz / kabul edilen risk:**
- **Altıncı ekran** (yalnız yönetici + operatör). ADR-0015 koşulu bilinçli olarak aşıldı; çatı kararı ertelendi.
- **Açılışta bir istek daha** (`/v1/info`, uzak çağrı yok); en kötü 3 sn.
- **Arama yalnız yüklenen sayfalarda.** 20'den fazla konuşmada eskisi için "Daha eski konuşmaları yükle" gerekir.
- **Saklama süresi sayı olarak yazmıyor** (API vermiyor).
- **Geçmiş turlarında alıntı yok;** kaynak kartı yalnız belge ve sayfayı gösterir. Silinmiş bir belgeden alıntı yapan cevap metni ise saklama süresince kalır (ADR-0023 §7, llm review L3).
- **p50/p95 yaklaşık** ve günlük oranlar az soruyla oynak; kart notları bunu söyler.
- **Gerçek IdP ve gerçek API ile denenmedi;** doğrulama fikstürle (kanıt dosyası).

**Etkilenen dosyalar:**
- yeni `panel/js/{conversations,feedback,metrics,chart,rating}.js`, `panel/js/screens/{history,insights}.js`;
- `panel/js/{api,app,icons,motion,roles,store,ui}.js`, `panel/js/screens/{ask,overview}.js`, `panel/css/panel.css`;
- `scripts/panel.test.mjs`;
- `README.md`, `docs/decisions.md`, `docs/ai/repo-context.md`, `docs/api-conversations-integration-v1.md` (manuel test akışı), kanıt `docs/evidence/adr-0025-dogrulama.md`.

**Geri alma yolu:** panel dosyaları geri alınır. Sunucu ve sözleşme değişmediği için başka bir şey gerekmez; geri alınan panelde her soru yine ayrı bir konuşma olur.

## Yeniden değerlendirme koşulu

- Saklama süresi `/v1/info`'ya (ya da başka bir uca) eklenirse: metin "Konuşmalar N gün sonra silinir" olur.
- Sunucu tarafı geçmiş araması gelirse: istemci filtresi bırakılır.
- Geçmiş prompt'a girerse (ADR-0023 koşulu): takip soruları ADR-0024'teki gibi kısa hâle döner. Bugün geçmiş prompt'a girmediği için takip soruları bütün soru olarak kalır.
- Ekranlar artmaya devam ederse ya da ekran içi durum yönetimi testle zor izlenir hâle gelirse: ADR-0015'in bileşen çatısı koşulu.
- Bir müşteri "kazanılan süre" isterse: önce ölçüm (kullanıcı beyanı ya da görev süresi) ve API alanı; panel uydurma sayı göstermez.
