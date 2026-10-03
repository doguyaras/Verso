# ADR-0015: Yönetim paneli (tarayıcı istemcisi)

- **Durum:** Kabul edildi (faz 10). Plate app paneline benzerlik varsayımları kullanıcı onayı bekliyor (aşağıda).
- **Tarih:** 2026-10-03
- **Karar verenler:** doguyaras (roadmap faz 10, referans Bölüm 17); uygulama ayrıntıları faz 10
- **İlgili eşik (referans Bölüm 24):** yok (sunum katmanı). Ekran sayısı ya da kullanıcı sayısı büyürse yeniden değerlendirilir.

## Bağlam

Roadmap faz 10, kullanıcının "plate app" projesindeki panel tarzında bir yönetim paneli istiyor. Referans 17 şunları ister:

- React/TS;
- token yalnız bellekte;
- rol matrisinin tek dosyada olması.

Plate app panelinin ekranları ve yığını sorulamadı: kullanıcı faz aralarında beklenmemesini istedi. Ayrıca yeni bağımlılıktan kaçınılması istendi. React/TS bir derleme zinciri (npm, Vite, TypeScript ve onların bağımlılık ağacı) getirirdi.

## Seçenekler

| Seçenek | Artı | Eksi |
|---|---|---|
| React + TypeScript + Vite | Referansla birebir; büyük ekranlarda ölçeklenir | Yüzlerce npm paketi, derleme adımı, ayrı CI işi; kullanıcının yeni bağımlılık kuralına aykırı |
| Sunucu tarafı şablon (Thymeleaf) | Java içinde kalır | API'ye ikinci bir sunum yüzeyi ve oturum çerezi ekler; API'nin token modeliyle çelişir |
| **Bağımlılıksız tarayıcı modülleri (vanilla JS, ES modules)** | Sıfır yeni bağımlılık, derleme yok; edge proxy statik dosya olarak sunar; API'yi her istemci gibi kullanır | Büyük bir arayüzde bileşen modeli eksikliği hissedilir |

## Karar

**Bağımsız, bağımlılıksız tarayıcı uygulaması** (`panel/`). Edge proxy `http://localhost:8080/panel/` adresinde sunar. API'yle aynı origin olduğu için CORS gerekmez.

**Ekranlar ve rol matrisi (`panel/js/roles.js`, tek dosya):**

| Ekran | `verso-user` | `verso-operator` | İçerik |
|---|---|---|---|
| Belgeler | ✓ | ✓ | Liste ve durum (işlenirken kendiliğinden yenilenir), sürükle-bırak yükleme (20 MB, PDF), silme (onaylı; KVKK silme) |
| Soru sor | ✓ | ✓ | Soru, atıf rozetleriyle cevap, kaynak listesi, "bulunamadı" ve "kaynak gösterilemedi" görünümleri, mod ve model |
| Sistem | – | ✓ | Mod, modeller (`GET /v1/info`), oturum ve token süresi, Grafana ve runbook bağlantıları |

- Rol, access token'ın `realm_access.roles` claim'inden gelir. Rolü olmayan her oturum `verso-user` sayılır.
- Panel yalnız menüyü gizler. Asıl kural API'dedir: her istek token taşır ve sahiplik sunucuda kontrol edilir (ADR-0005).
- Demo kullanıcısı `verso-operator` rolündedir.

**Kimlik (ADR-0010):**

- Yeni public istemci: `verso-panel`.
  - Authorization code + PKCE (S256).
  - Tek ve tam bir redirect URI, tek web origin.
  - Password grant, implicit flow ve device flow kapalı.
- Access token'a yalnız audience ve realm rolleri girer. Kullanıcı adı yalnız ID token'dadır; ID token tarayıcıdan çıkmaz.
- Token'lar yalnız modül belleğindedir (localStorage, sessionStorage ya da çerez yok). Redirect boyunca yalnız tek seferlik PKCE verifier ve state `sessionStorage`'da durur ve kod değiş tokuşunda silinir.
- Sayfa yenilenince yeniden giriş gerekir (IdP oturumu sürüyorsa tek tık). Süre dolmadan refresh token ile yenilenir.

**Güvenlik:**

- Model çıktısı, dosya adı ve hata metni sayfaya yalnız metin düğümü olarak girer; `innerHTML` yoktur. Bunu `scripts/panel.test.mjs` sabitler.
- Konsola log yazılmaz.
- CSP: `default-src 'none'`. Script ve stil yalnız `'self'`. Bağlantı yalnız `'self'` ve IdP. Inline script yok, frame yok. Ek başlıklar: `nosniff`, `no-referrer`.

**API eki:** `GET /v1/info`: mod ve model adları; token gerekir, yanıt önbelleğe alınmaz (`/actuator/info`'nun API portundaki karşılığı).

**Yükseltme:** Keycloak realm'i yalnız ilk açılışta içe aktarır. Faz 10'dan önce kurulmuş bir yığında `bash scripts/keycloak-reimport.sh` panel istemcisini ve rolü getirir. Demo IdP'nin kendi veritabanını sıfırlar; Verso verisine dokunmaz.

## Varsayımlar (kullanıcı onayı bekliyor)

Plate app paneli görülmeden yapılan varsayımlar:

- Sol değil, üst menü.
- Açık ve koyu tema (sistemi izler).
- Kart ve tablo düzeni.
- Türkçe arayüz.
- Üç ekran.
- Panel bir yönetici aracı değil, bir son kullanıcı arayüzüdür. Kullanıcı yönetimi IdP'de (Keycloak yönetim konsolu) kalır.

Plate app'in yığını React/TS ise ve aynı görünüm isteniyorsa, ekranlar bu API sözleşmesiyle (`panel/js/api.js`) bire bir taşınabilir.

## Sonuçlar

- **Olumlu:**
  - Yeni bağımlılık ve derleme adımı yok.
  - Panel, API'nin başka bir istemcisidir: sunucuya yeni bir oturum modeli eklenmedi.
  - Rol matrisi tek dosyada.
- **Olumsuz / kabul edilen risk:**
  - Tarayıcı akışları otomatik bir tarayıcı testiyle değil, canlı denemeyle doğrulandı (kanıt dosyasında).
  - Saf mantık (PKCE, rol matrisi, atıf ayrıştırma, hata metinleri, güvenlik kuralları) node testlerindedir.
  - Redirect URI ve CSP `localhost:8080` ve `localhost:8180` içindir. Başka port ya da host için `panel/js/config.js`, realm ve `deploy/edge/nginx.conf` birlikte değişir.
- **Etkilenen dosyalar:** `panel/**`, `deploy/edge/nginx.conf`, `compose.yaml`, `deploy/compose.cloud.yaml`, `deploy/keycloak/realm/verso-realm.json`, `qa-api` (`ServiceInfoResponse`), `qa-core` (`InfoController`), `scripts/panel.test.mjs`, `scripts/keycloak-reimport.sh`, CI.
- **Geri alma yolu:** `panel/` ve edge'deki `/panel/` bloğu silinir; realm'den `verso-panel` istemcisi çıkarılır. API etkilenmez.

## Yeniden değerlendirme koşulu

- Ekran sayısı beşi geçerse, ya da kullanıcı plate app'in yığınını (ör. React/TS) isterse: bileşen modeli olan bir çatıya geçilir. Bu, onay gerektiren yeni bir bağımlılıktır.
