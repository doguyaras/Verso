# Bağlayıcılar ekranı: doğrulama ve kanıt kaydı (ADR-0042)

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

## Künye

- **Tarih:** 2026-10-24
- **Branch:** `feature/doguyaras-baglayici-ekrani` (`origin/develop` üzerinden).
- **Ortam:** Windows 11, Node (yerel). **Docker başlatılmadı** (yığında yük testi koşuyordu); tarayıcıda görsel kontrol bu turda **yapılmadı**. Değişiklik yalnız paneldedir; Java kodu, şema, config değişmedi, Maven koşulmadı.

## 1. Yerelde koşulan

| Kontrol | Komut | Sonuç |
|---|---|---|
| Node testleri (panel dahil) | `GITLEAKS=/c/Users/doguy/.local/bin/gitleaks.exe node --test scripts/*.test.js scripts/panel.test.mjs` | PASS (229 test: 228 geçti, 0 hata, 1 atlandı — POSIX sinyal testi, Windows'ta beklenen) |

Yeni testler (`scripts/panel.test.mjs`, "Bağlayıcılar" bölümü):

- **Rol kapısı:** yalnız `/v1/info` `verso-admin` verirse açılır; token'daki `verso-admin` (Keycloak ya da Entra `roles`) yetmez; "Yönetim" grubunda, sekme çubuğunda değil; `RENDER`'a ve hesap menüsüne bağlı. Var olan yönetim testleri yeni ekranla güncellendi (ekran listeleri, kısayol tekilliği).
- **Hata kodları:** entegrasyon dokümanındaki 14001–14011'in her biri Türkçe, sabit adı değil cümle; `ApiError.message` aynı metin; alan kodları (14002, 14003, 14004, 14005, 14006, 14009, 14010) forma bağlı alana düşer, diğerleri ekran bildirimi.
- **Sağlık rozeti:** belgelenmiş altı değer, her biri metin + `panel.css`'te var olan bir `pill` tonu; `STALE` "Güncel" değil; bilinmeyen değer ham.
- **Form doğrulama:** geçerli formdan belgelenmiş gövde (önek normalize, desenler satır satır, MB → bayt, port varsayılanı, boş etki alanı `null`); parola ya da secret alanı yok. Negatifler: ad (boş, 101 karakter, yön karakteri), sunucu (şema, `\\`, genel IP, loopback, metadata, `localhost`, `::1`, geçersiz IP), port, paylaşımda eğik çizgi, önekte `..`, `:`, boş parça, `//`, sondaki nokta, joker; hesap adında `\`; `credentialRef` (parola gibi değer, başka secret, boş ek, küçük harf, 61 karakter); 21 desen, `..`, kapanmamış/iç içe parantez, 7 yıldız; 0/21 MB; 4, 10081, 7,5 dakika. Özel aralık sınırları (172.16/172.31/172.32, fd.., `::ffff:`). Düzenlemede hedef alan orijinal kalır.
- **Sunucuyla sözleşme:** `credentialRef` deseni, aralık sınırları ve desen sınırları `ConnectorRequest.java`'dan, silme emniyeti varsayılanları `ConnectorProperties.java`'dan, sağlık değerleri `ConnectorHealth.java`'dan okunup karşılaştırılır.
- **Silme onayı:** sunucunun kuralı (%20 ve 20 dosya; tam %20 serbest), `pendingApproval` (yalnız `PARTIAL/DELETION_SAFETY`, silinen bağlayıcıda yok, 0 bekleyen yok, yüzde 100'ü aşmaz), sayının birebir yazılması, adın birebir yazılması; `confirmMassDeletion: true` kaynakta yalnız bir yerde (onay formu), "Şimdi eşitle" `false`.
- **Metinler:** dokümandaki her koşu kodu ve dosya hata kodu Türkçe; koşu özeti; sayfa durumu; aralık metni.
- **API:** bütün connector çağrılarının yöntemi, yolu ve gövdesi; id'ler kodlanır; durum süzgeci yalnız üç değer (enjeksiyon denemesi düşer); 409 `14002` `ApiError` olarak gelir.
- **Kaynak kuralları:** `innerHTML`, satır içi stil, tarayıcı deposu, URL durumu, konsol, parola alanı, kendi hareketi yok; 403 durumu; ürün adı geçmez; parola ve NTFS uyarı metinleri var; yardım belge adlarını, `VERSO_CONNECTOR_ALLOWED_NETWORKS` ve `DOCKER-USER`'ı anar; ekrandan çıkınca durum boşalır; 44 px kuralı ve yönetim CSS bloğunda yalnız token (kontrast çiftleri değişmedi), animasyon yok.

## 2. CI'da koşacak

Aynı node testleri (`node --test scripts/*.test.js`, panel testleri). Backend değişmediği için yeni Java testi yok.

## 3. Canlı kontrol bekleyen (yapılmadı)

- Tarayıcıda görsel kontrol: liste, form, ayrıntı, koşu/dosya tabloları ve onay kutularının açık ve koyu temada görünümü; telefon genişliğinde form ızgarası ve tabloların yatay kaydırması; 44 px hedefleri; `prefers-reduced-motion`.
- Gerçek uçlarla uçtan uca: oluşturma (201), 409 `14002`/`14006` ve 400 `14004` mesajının alan altına düşmesi, "Şimdi eşitle" (202), Samba'ya karşı ilk koşunun listede `OK`'a dönmesi, `STALE` rozeti, dosya süzgeci ve sayfalama, `DELETION_SAFETY` onayı ve tek koşuluk etkisi, silme (202 → `DELETING` → liste dışı).
- Bilinen sınır: bekleyen silme sayısı `deferred`'dır (üst sınır); koşuda daha önce `FILES_FAILED`/`BACKLOG`/`RUN_LIMIT` yaşandıysa `errorCode` o koddur ve onay kutusu görünmez (ADR-0042 Sonuçlar).
