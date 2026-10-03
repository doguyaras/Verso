# Panel v2 ve kalıcı `admin` kullanıcısı: Doğrulama ve kanıt kaydı

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

- **Tarih:** 2026-10-03
- **İstek (kullanıcı):** "admin kullanıcısını kalıcı yap", "arayüzü hiç beğenmedim, güzel sağlam bir ui yapman lazım".
- **Kapsam:**
  - Realm'e `admin` kullanıcısı (`verso-operator`), parolası yeni secret `SECRET_KEYCLOAK_PANEL_ADMIN_PASSWORD`'dan (dev-secrets, compose, Keycloak başlangıç betiği).
  - Panelin yeniden tasarımı (ADR-0015 "Panel v2"): yeni kabuk, Genel bakış ekranı, Belgeler, Soru sor ve Sistem ekranlarının yeniden yazımı, açık/koyu tema, telefon düzeni.
- **Yeni bağımlılık yok:** sistem fontları, panelin kendi SVG ikonları, CSS değişkenleri. CSP değişmedi.
- **Davranışsal kapsam:** panel çalışan yığında, gerçek Keycloak ve gerçek modellerle tarayıcıda kullanıldı (Bölüm 2).

## 1. Makine kontrolleri

| Kontrol | Komut | Sonuç |
|---|---|---|
| Build ve testler | `./mvnw -B -ntp verify` | **PASS**: 395 Java testi. `KeycloakRealmTest` artık `admin`'i, rolünü ve parola yer tutucusunu da sınar; `ComposeConfigTest` Keycloak'ın secret listesini |
| Panel testleri | `node --test scripts/panel.test.mjs` | **PASS**: 19. Yeni: her modül ES modülü olarak ayrışır; kullanılan her ikon çizilidir |
| Betik testleri | `node --test scripts/*.test.js` | **PASS**: `keycloak-start.test.js` yeni secret'ı da bekler (mutasyon baseline'ı bunu yakaladı) |
| Mutasyon | `bash scripts/mutation-check.sh` | Bölüm 4 |

## 2. Canlı denemeler

| Deneme | Sonuç |
|---|---|
| `admin` ile giriş (gerçek PKCE akışı, betikle) | 302 panele dönüş, `verso-operator` rolü, `/v1/info` 200 |
| `admin` ile giriş (tarayıcı) | Keycloak formu → Genel bakış |
| Genel bakış | Sayılar, tür dağılımı (PDF 6, Word 1, Metin 1, Markdown 1), son yüklenenler, hızlı soru, asistan kartı; işlenen belge varken kendiliğinden yenilenir |
| Belgeler | 9 belge; durum filtresi sayıları, arama, tür ikonları, "6 bölüm" / "2 sayfa"; satıra tıklayınca ayrıntı çekmecesi; silmede onay penceresi (Vazgeç ile kapandı) |
| Soru sor | "25.000 TL üstü masrafları kim onaylar?": kaynaklı cevap, `masraf-politikasi.pdf sayfa 3`, `masraf-politikasi.docx bölüm 6`, `masraf-politikasi.md bölüm 6`; bekleme süresi sayacı; kopyala |
| Sistem | Yerel mod açıklaması, modeller, oturum süresi geri sayımı |
| Tema | Koyu (sistem) ve açık; üst çubuktan geçiş |
| Telefon (375 px) | Çekmece menü, tek sütun; ilk denemede ana sütun tablo yüzünden taşıyordu: `grid-template-columns: minmax(0, 1fr)` ve küçük ekranda iki sütunun gizlenmesiyle düzeldi |
| Sözdizimi hatası | İlk yüklemede bir ekran modülündeki fazladan parantez bütün paneli boş bırakıyordu; düzeltildi ve modül ayrıştırma testi eklendi |

## 3. Güvenlik notları

- Bütün metin `el()` ile metin düğümü olarak girer; `innerHTML` yok (test), Trusted Types CSP'de açık.
- İlerleme çubuğu ve metin kutusu yüksekliği CSSOM ile ayarlanır; CSP inline stil yasağı sürer.
- Yükleme `XMLHttpRequest` ile; aynı Bearer token, `X-Idempotency-Key` ve hata eşlemesi.
- Tema tercihi `sessionStorage`'da (yalnız "light"/"dark"). Token yine yalnız bellekte; test, `auth.js`'in `sessionStorage`'a PKCE kaydı dışında bir şey yazmadığını sınar.
- Paralel çağrıların hepsi 401 alırsa tek bir giriş yönlendirmesi başlar.

## 4. Mutasyonlar

`ONLY="M203 M204 M209 M210 M211 M224 M231 M232" bash scripts/mutation-check.sh`: baseline yeşil (395 Java testi, 9 node suite). Sonuç: **8/8 yakalandı**. M204 tanımı rol matrisindeki yeni ikon alanına göre güncellendi; M231 (bir ekran modülünde sözdizimi hatası) ve M232 (çizilmemiş menü ikonu) yeni. Çıktı: [`panel-v2-mutasyon-ciktisi.txt`](panel-v2-mutasyon-ciktisi.txt).

## 5. CI

PR #17 (`7262285`): `ci` **success** (run 37140240584; panel ve betik testleri dahil), `restore-drill` **success** (run 37140240597; temiz Linux'ta yeni secret ile Keycloak açılışı dahil). Birleştirme: `00cb41f`.
