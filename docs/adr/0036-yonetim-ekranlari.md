# ADR-0036: Yönetim ekranları: KVKK başvuruları, bilgi alanı düzenleme ve parça incelemesi

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras (görev: "kalan yönetici ekranları"; Asya pazarı araştırması yöneticilerin parça incelemesi beklediğini söyler)
- **İlgili eşik (referans Bölüm 24):** yok (sıcak yol değişmedi)
- **Genişlettiği:** ADR-0015 (panel, rol matrisi), ADR-0018 (görsel kimlik), ADR-0025 (ekran sayısı, değer panosu), ADR-0028 (bilgi alanları; yönetmek okumak değildir), ADR-0034 (KVKK başvurusu; "panel ekranı istenince" maddesi), ADR-0035 (sayfa çekmecesi)

## Bağlam

- ADR-0034 KVKK başvurusu uçlarını yalnız API olarak açtı; yönetici `sub`'ı curl ile gövdeye yazıyordu. Yanlış kopyalanmış bir kimlik, silinecek ve kalacak verinin görülmemesi ve dışa aktarım dosyasının adında kişisel veri riski panelde çözülmeli.
- Bilgi alanının adı ve açıklaması `PUT /v1/collections/{id}` ile değişebiliyordu, panelde yoktu.
- Yönetici bir belgenin nasıl parçalandığını (cevap neden bu pasajı seçti, tablo bölündü mü) göremiyordu; parça metni yalnız retrieval içinde kullanılıyordu.
- Yönetici ekranları (değer panosu, bilgi alanları) menüde kullanıcı ekranlarıyla karışıktı. Kullanıcı ve operatör için ekran sayısı sınırı (ADR-0015, ADR-0025: en çok beş, telefonda dört sekme) korunmalı.

## Seçenekler

| Konu | Seçenek | Karar |
|---|---|---|
| Menü | A. Yönetici ekranlarını kullanıcı ekranlarının arasına eklemek · B. **"Yönetim" başlığı altında ayrı grup** (menü sütunu ve hesap menüsü), sekme çubuğunda hiç | B: yalnız `/v1/info` `verso-admin` dediğinde görünür; kullanıcı ve operatörün menüsü birebir aynı kalır |
| `sub`'ın yeri | A. Hash/URL parametresi (yenilemede kalır) · B. `sessionStorage` · C. **Yalnız ekranın belleği** | C: ADR-0034 `sub`'ı URL'den çıkardı; panel de çıkarır. Ekrandan çıkınca değişken ve alanlar boşaltılır |
| Silme onayı | A. Tek "Emin misiniz?" penceresi · B. **Kimliği yeniden yazdırmak** (sunucunun `confirm` alanıyla aynı kural) | B: düğme, yazılan değer aranan `sub` ile birebir aynı olana kadar kapalı; sunucu yine denetler |
| Parça verisi | A. Panelde sayfa metninden parçaları yeniden hesaplamak · B. **Küçük bir yönetici okuma ucu** | B: parçalayıcının kuralları (tablo başlığı, taşma) panelde kopyalanamaz; tek gerçek `document_chunk` |
| Parça ucu erişimi | A. Yönetici her belgeyi görür · B. **Yönetici rolü + aynı `READABLE` okuyucu filtresi** | B: ADR-0028'in "yönetmek okumak değildir" kuralı yöneticiye de uygulanır |

## Karar

### 1. Gezinme

- `SCREENS`'e `group: 'admin'` eklendi: `insights`, `collections`, yeni `chunks` ("Parçaları incele", `g p`) ve `subjects` ("KVKK başvuruları", `g v`). Hepsi `tab: false`, yalnız `ROLES.ADMIN`.
- Menü sütununda ilk yönetici ekranının önüne "Yönetim" başlığı (daraltılmış şeritte ince bir çizgi), hesap menüsünde aynı başlıkla grup. Yönetici yoksa başlık da yoktur. Kullanıcı ve operatörün ekran listesi testle sabittir.
- Yönetim ekranlarında `#main` `data-group="admin"` taşır; telefonda (`pointer: coarse`) alanlar, düğmeler ve menü satırları en az 44 px (WCAG 2.5.5).

### 2. "KVKK başvuruları" (`panel/js/screens/subjects.js`, saf mantık `panel/js/subjects.js`)

- `sub` biçimi sunucunun desenidir (`[A-Za-z0-9._@:-]{1,255}`, `SubjectIds`); test Java dosyasındaki deseni okuyup karşılaştırır. Kırpılmaz, harf katlanmaz; hata metni değeri tekrar etmez.
- Özet üç grupta gösterilir (ADR-0034 bölüm 3): **silinir** (konuşma, soru-cevap, puan, kullanım günü, kişisel belge), **kalır ve raporlanır** (bilgi alanına yüklenen belgeler, adıyla erişimler, oluşturduğu alanlar, denetim satırı). Kalan bilgi alanı belgeleri kimlikleriyle tablo olarak listelenir.
- Dışa aktarım `fetch` + Blob ile iner; dosya adı `verso-kvkk-disa-aktarim-YYYYMMDD-HHmm.json` (indirme anı, `sub` yok). `X-Subject-Export-Truncated` uyarı olarak gösterilir. Blob URL bir saniye sonra bırakılır.
- Silme: uyarı kutusu özetten silinecekleri ve kalacakları sayıyla listeler, "önce IdP'de hesabı kapatın" der; onay alanı `sub` ile birebir aynı olmadan düğme kapalı. Sonuç ve tekrar edilen istek ("silinecek bir şey kalmamıştı") ayrı gösterilir.
- Cevap karartma: belge kimliği iki kez yazılır (UUID ve birebir eşleşme); 409 `13021` "belge hâlâ duruyor" olarak gösterilir.
- Adım adım bölümü runbook'un kısa özetidir ve `docs/runbooks/kvkk-basvurusu.md`'yi işaret eder; prosedürün otoritesi runbook'tur.
- `sub` yalnız `subject` değişkeninde ve alanlarda; URL, hash, tarayıcı deposu, dosya adı, başlık ve konsolda yok (testli). Alanlar `autocomplete="off"`. Ekrandan çıkınca değişken `null`, bütün alanlar boş.
- 403 sakin bir "Bu ekran yöneticiler içindir" durumudur; 401 oturum akışına gider.

### 3. Bilgi alanı düzenleme (`panel/js/screens/collections.js`)

- Kartta "Adı ve açıklamayı düzenle" satır içi form açar; `collectionEdit` sunucunun kurallarını uygular (ad kırpılır, 1–100; açıklama ≤ 500; denetim ve yön karakteri yok; boş açıklama silinir) ve değişmemiş formu göndermez. Aynı ad `409 10040` mevcut mesajla gösterilir.

### 4. Parça incelemesi

- **Uç:** `GET /v1/admin/documents/{documentId}/chunks?page&size` (`document-core` `AdminDocumentChunkController` → `DocumentChunkService` → `DocumentChunkRepository`; DTO `document-api` `DocumentChunkResponse`). `@PreAuthorize(HAS_ADMIN)` hem controller'da hem serviste (ADR-0019); 403 denetim kaydına `ADMIN_DOCUMENT_CHUNK` olarak yazılır (`AccessDeniedAuditListener`, `/v1/admin` öneki).
- **Tek SQL:** `WITH doc AS (… WHERE d.id = :id AND READABLE)` + parça sayısı + `LEFT JOIN LATERAL (… ORDER BY chunk_index LIMIT/OFFSET)`. Okunamayan belge satır döndürmez → `404 10001` (olmayan belgeyle aynı gövde); parça metni veritabanından çıkmaz. Sıralama ve sayım `uq_document_chunk_position (document_id, chunk_index)` indeksini kullanır.
- **Sınırlar:** `size` 1–100 (varsayılan 50), `page` 0–100 000, parça metni `left(content, 2000)` (kod noktası); `charCount` bütün uzunluk, `truncated` kesildiğini söyler. Embedding dönmez. `Cache-Control: no-store, private`. Okuma olduğu için denetim olayı yok (ADR-0026); log satırı yok.
- **Ekran** (`panel/js/screens/chunks.js`, saf mantık `panel/js/chunks.js`): okunabilir ve hazır belgeler arasından seçim (ya da "Bilgi kaynakları" ayrıntı panelindeki "Parçaları incele"); her parça için sıra, sayfa/bölüm, karakter sayısı, varsa başlık (Markdown başlığı ya da cümle gibi bitmeyen kısa ilk satır) ve ilk 280 karakter; "Daha fazla parça yükle" ile 50'şer sayfalama (aynı sıra iki kez gelmez); yüklenen parçalarda Türkçe harf duyarsız, her kelimeyi arayan süzme (ekran bunun yalnız yüklenenlerde olduğunu söyler). "Sayfayı aç" ADR-0035 çekmecesini parçanın metniyle açar, parça sayfada vurgulanır.
- Başlık sütunu veritabanında yoktur; başlık yalnız panelde, metnin ilk satırından tahmin edilir (yanlış tahmin yalnız görünümü etkiler).

### 5. Değişmeyenler

- CSP, Trusted Types, `innerHTML` yasağı, satır içi stil yasağı: aynı (her şey `el()` ile metin düğümü; testli). Yeni renk token'ı yok, yeni hareket yok; mevcut `prefers-reduced-motion` kuralı geçerli. Ürün adı yeni ekranlarda geçmez (ek alma kuralı, ADR-0021).
- Sıcak yol, şema, migration, config ve env değişmedi.

## Sonuçlar

- Olumlu: KVKK başvurusu panelden, `sub`'ı URL'e ve diske koymadan, silinecek/kalacak veriyi görerek yürütülür; yanlış kimlikle silme iki katmanda (panel, sunucu) engellenir. Yönetici parçalamayı görür ve sayfaya gider; okuma hakkı genişlemez.
- Olumsuz / risk: (1) Silme hâlâ geri alınamaz; yazılı onay dikkatsiz kopyala-yapıştırı engellemez. (2) Parça araması yalnız yüklenen parçalarda; çok uzun belgede yönetici sayfa sayfa yükler (sunucu tarafı arama yok). (3) Ekranlar bu turda tarayıcıda görsel olarak denetlenmedi (Docker kapalıydı); saf mantık ve kaynak kuralları testli, ekran canlı kontrol bekliyor (`docs/evidence/yonetim-ekranlari-dogrulama.md`).
- **Rollout (referans 18.4):** yeni uç eklemedir, mevcut istemci etkilenmez; panel yeni ucu yalnız yönetici ekranında çağırır. Eski sunucuda uç yoksa ekran 404'ü "okuyamıyorsunuz" olarak gösterir (yanıltıcı ama zararsız); sunucu panelle birlikte yayımlanır.

## Yeniden değerlendirme koşulu

- Yöneticiler uzun belgelerde parça aramasını sunucuda isterse (`q` parametresi, `search_vector` ile).
- KVKK başvuru sayısı panelden takip gerektirecek kadar artarsa (başvuru listesi ve durum; bugün başvuru dosyası kurumdadır).
- Telefon kullanımında yönetim grubunun hesap menüsünde kalabalıklaşması.
