# ADR-0042: Panelde "Bağlayıcılar" ekranı (SMB bağlayıcısının yönetimi)

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras (görev: "SMB bağlayıcıları için yönetim ekranı"); uygulama ayrıntıları bu ADR'de
- **İlgili eşik (referans Bölüm 24):** yok (sıcak yol, şema, config değişmedi)
- **Genişlettiği:** ADR-0038 (SMB bağlayıcısı; "panelde ekran yok" notu), ADR-0036 ("Yönetim" grubu ve yönetici ekranı deseni), ADR-0018 (görsel kimlik), ADR-0028 (bilgi alanları ve okuyucu listesi)

## Bağlam

- ADR-0038 bağlayıcıyı yalnız API olarak açtı (`/v1/admin/connectors`); yönetici curl ile JSON yazıyordu. Pazardaki en büyük şikâyet "arayüz başarılı derken indeksin sessizce durması"dır: ekran, son koşunun rozetini değil API'nin `health` alanını (son başarılı eşitlemeden türer, `STALE`) göstermelidir (`docs/api-connectors-integration-v1.md` Kurallar).
- Silme emniyeti (`PARTIAL/DELETION_SAFETY`) yöneticiden `confirmMassDeletion: true` ister; curl'de bunun kaç dosyayı sildiği görünmüyordu.
- Kimlik bilgisi hiçbir yüzeyde yoktur; ekran da parola alanı göstermemeli ve parolanın nerede durduğunu sade Türkçeyle söylemelidir.

## Seçenekler

| Konu | Seçenek | Karar |
|---|---|---|
| Yer | A. "Bilgi alanları" ekranına gömmek · B. **"Yönetim" grubunda ayrı ekran** | B: bağlayıcının kendi yaşam döngüsü (koşular, dosyalar, silme) var; ADR-0036 deseni |
| Son koşu | A. API'ye "son koşu" alanı eklemek · B. **Listede her bağlayıcı için `runs?size=1`** | B: sözleşme değişmez; en çok 50 bağlayıcı, aynı anda 4 istek, yalnız yönetici ekranında |
| Bekleyen silme sayısı | A. API'ye "bekletilen silme" alanı · B. **Son koşunun `deferred` sayısı, "en çok" diye** | B: `deferred` silmeleri ve sonraki koşuya kalan diğer işleri birlikte sayar; ekran bunu üst sınır olarak söyler. Kesin sayı gerekirse API'ye alan eklenir (yeniden değerlendirme) |
| Onaylar | A. Tek "Emin misiniz?" · B. **Yazdırarak onay** | B: toplu silme onayında dosya sayısı, bağlayıcı silmede adı birebir yazılır (ADR-0036'daki gibi) |
| Doğrulama | A. Yalnız sunucu · B. **Sunucunun belgelenmiş kuralları istemcide de, sunucu yine karar verir** | B: alan altında anında Türkçe hata; sunucu kodları (14002–14010) ilgili alanın altına düşer |

## Karar

### 1. Gezinme ve rol

- `SCREENS.connectors` ("Bağlayıcılar", `g e`, ikon `plug`): `roles: [verso-admin]`, `tab: false`, `group: 'admin'`. Hesap menüsünün "Yönetim" grubunda da var. Rol yalnız `/v1/info`'dan gelir; token'daki rol ekranı açmaz (testli). 403 sakin "Bu ekran yöneticiler içindir" durumudur, 401 oturum akışına gider.

### 2. Ekran (`panel/js/screens/connectors.js`, saf mantık `panel/js/connectors.js`)

- **Liste:** ad, hedef bilgi alanı (`/v1/collections`'tan ad), sağlık rozeti (renk **ve** metin: `OK` Güncel, `STALE` Gecikmiş, `FAILING` Başarısız, `NEVER_SYNCED`, `DISABLED`, `DELETING`; bilinmeyen değer ham), son başarılı eşitleme (göreli, tam zaman başlıkta), dosya sayıları (eşitlenen / başarısız / atlanan), son koşu özeti ve nedeni; silme onayı bekleyen bağlayıcıda ayrı rozet. 50'şer sayfa.
- **Oluşturma / düzenleme formu:** ad, sunucu, port, paylaşım, klasör, etki alanı, servis hesabı, parola dosyasının adı (`credentialRef`), hedef bilgi alanı (seçici; düzenlemede kilitli, 409 `14006`), dahil/hariç desenleri (satır başına bir), en büyük dosya (MB, en çok 20), aralık (5–10080 dk), zamanlama açık/kapalı. `connectorRequest` sunucunun kurallarını uygular: `ConnectorRequest` desenleri ve uzunlukları, `SourcePaths.normalizePrefix` (boş parça, `.`, `..`, `:`, joker, sondaki nokta/boşluk), `GlobSet` (≤ 20 desen, ≤ 200 karakter, `..` yok, en çok 6 `*`, iç içe olmayan kapalı süslü parantez), IP literal'i yalnız özel aralıkta (10/8, 172.16/12, 192.168/16, fc00::/7; `localhost`, loopback, link-local reddedilir). İzinli ağ listesi kurulumdadır; panel onu bilmez, sunucu `14004` döner ve hata sunucu alanının altına düşer.
- **Parola:** form parola sormaz. Alanın yanındaki metin parolanın sunucudaki secrets klasöründe `SECRET_CONNECTOR_` ile başlayan bir dosyada durduğunu, panelde yalnız dosyanın adının seçildiğini söyler. Hedef alan seçicisinin yanında risk: alana erişimi olan herkes eşitlenen her dosyayı görür, NTFS izinleri aktarılmaz.
- **Eylemler:** "Şimdi eşitle" (`confirmMassDeletion: false`), "Düzenle", "Koşuları göster" (20'şer, en yeni önce, bütün sayılar ve kodun Türkçesi, liste eksikse "hiçbir şey silinmedi"), "Dosyaları göster" (50'şer, durum süzgeci `SYNCED`/`FAILED`/`SKIPPED`/hepsi, neden, deneme, yeniden deneme zamanı). Dosya yolları yalnız tabloda; URL'e, başlığa, tarayıcı deposuna girmez.
- **Silme onayı:** son koşu `PARTIAL/DELETION_SAFETY` ise ayrıntıda kırmızı kutu: en çok kaç dosya, izlenen dosyaların yaklaşık yüzde kaçı, kuralın kendisi (%20 ve 20 dosya), onayın belgeleri türevleriyle sildiği, klasör taşındıysa onay yerine yolun düzeltilmesi. Düğme, dosya sayısı yazılmadan kapalıdır; onay yalnız bir koşu içindir (sunucunun kuralı). `confirmMassDeletion: true` yalnız bu formdan gider (testli).
- **Bağlayıcı silme:** eşitlenen belge sayısı ve "belgeler sayfa, parça ve vektörleriyle silinir, sunucudaki dosyalara dokunulmaz, eski cevaplar kendiliğinden değişmez" uyarısı; düğme ad birebir yazılmadan kapalı. 202'den sonra liste `DELETING` gösterir.
- **Kurulum yardımı:** servis hesabı (yalnız okuma), `VERSO_CONNECTOR_ALLOWED_NETWORKS` (en dar hâli `/32`), overlay ve host güvenlik duvarı (`DOCKER-USER`, yalnız 445), SMB imzalama ve `wide links = no`; README bölümü, `deploy/compose.smb.yaml`, entegrasyon dokümanı, ADR-0038 ve runbook adlarıyla. Prosedürün otoritesi README'dir.
- Hata kodları 14001–14011 `api.js` `MESSAGES`'ta Türkçedir; `ERROR_FIELDS` alan kodlarını forma bağlar.

### 3. Değişmeyenler

- CSP, Trusted Types, `innerHTML` yasağı, satır içi stil yasağı: aynı (her şey `el()` ile). Yeni renk token'ı ve hareket yok; rozetler mevcut `pill` tonlarıdır (kontrast testleri aynı çiftleri sınar). Dokunmatikte alanlar, düğmeler, desen kutuları ve "Zamanlama açık" satırı en az 44 px. Ürün adı ekranda geçmez.
- API sözleşmesi, şema, config, env ve sıcak yol değişmedi.

## Sonuçlar

- **Olumlu:** yönetici bağlayıcıyı curl'süz yönetir; "duran indeks" listede Gecikmiş rozetiyle görünür; toplu silme, kaç dosya olduğunu görerek ve sayıyı yazarak onaylanır; parola panelin hiçbir yerinden geçmez.
- **Olumsuz / risk:**
  - Bekleyen silme sayısı `deferred`'dır, kesin değil üst sınırdır (aynı koşuda geri çekilme ya da kuyruk nedeniyle bekleyen dosyalar da sayılır). Koşu birden çok sorun yaşadıysa `errorCode` ilk sorunu taşır (`SyncRunner.Counter.problem`); silmeler bekletildiği hâlde kod `FILES_FAILED` olabilir ve onay kutusu görünmez. Kesin çözüm API'de ayrı bir alan.
  - Liste her bağlayıcı için bir `runs` isteği atar (en çok 50, 4'er).
  - IP literal'i istemcide yalnız özel aralık mı diye bakılır; kurulumun izinli listesi sunucuda.
  - Ekran bu turda tarayıcıda görülmedi (Docker yük testinde); saf mantık ve kaynak kuralları node testleriyle sınandı (`docs/evidence/baglayici-ekrani-dogrulama.md`).
- **Etkilenen dosyalar:** `panel/js/connectors.js`, `panel/js/screens/connectors.js`, `panel/js/roles.js`, `panel/js/app.js`, `panel/js/api.js`, `panel/css/panel.css`, `scripts/panel.test.mjs`; `README.md`, `docs/adr/0038-smb-baglayicisi.md`, `docs/api-connectors-integration-v1.md`, `docs/decisions.md`, `docs/evidence/baglayici-ekrani-dogrulama.md`.
- **Rollout (referans 18.4):** yalnız panel; mevcut uçları kullanır. Bağlayıcı modülü olmayan eski sunucuda uçlar 404 döner, ekran hata bildirimi gösterir (zararsız).
- **Geri alma yolu:** `SCREENS.connectors` satırını ve `RENDER`/hesap menüsü girdisini kaldırmak; API davranışı etkilenmez.

## Yeniden değerlendirme koşulu

- Yöneticiler bekleyen silmenin kesin sayısını ya da hangi dosyaların silineceğini isterse: `SyncRunResponse`'a `deletionsHeld` (ve isteğe bağlı liste) eklenir, `pendingApproval` onu kullanır.
- Bağlayıcı sayısı 50 sınırına yaklaşır ya da liste yavaşlarsa: `ConnectorResponse`'a son koşu özeti eklenir, `runs?size=1` döngüsü kalkar.
