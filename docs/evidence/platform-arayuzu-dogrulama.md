# Platform arayüzü doğrulaması (ADR-0058)

Tarih: 2026-10-28. Dal: `feature/doguyaras-platform-arayuzu` (`origin/develop` üzerine).

## Yerel koşular

Docker bu oturumda kullanılmadı (başka bir ajan ölçüm yapıyordu): Testcontainers'lı entegrasyon testleri yerelde koşmadı, kapı GitHub CI'dır.

| Komut | Sonuç |
|---|---|
| `./mvnw -B -ntp -T 1 -DskipTests install` (test kaynakları dahil derlenir) | BUILD SUCCESS |
| `./mvnw -B -ntp -T 1 -pl platform/platform-core,services/qa/qa-api,services/qa/qa-core,services/connector/connector-core,services/document/document-core,services/audit/audit-core test` | BUILD SUCCESS (modül ArchUnit testleri dahil) |
| `./mvnw -B -ntp -T 1 -pl verso-app test -Dtest=ArchitectureRulesTest,ConfigDriftTest,ErrorCodeUniquenessTest` | 8 test, 0 hata |
| `GITLEAKS=/c/Users/doguy/.local/bin/gitleaks.exe node --test scripts/*.test.js scripts/panel.test.mjs` | 304 test: 303 geçti, 1 atlandı (önceden de atlanan), 0 hata |

## Yeni testler

- Birim: `ActivityEntryTest` (sabit sözcükler, tek satır sınırlı metin, okunamayan kaynağın adı düşer, görünen `toString`'de hesap yok), `ActivityServiceImplTest` (birleştirme sırası, kategori/tür/durum süzgeçleri, pencere ve onayların yaştan bağımsızlığı, sayfa sınırları ve 400'ler, kaynak hatasında `partial` ve log gizliliği, durum şeridi nedenleri, ayrıntı kimliği ve 404, ayrıntıda kaynak hatasının 404'e dönmemesi), `ConnectorActivitySourceTest`, `DocumentActivitySourceTest`, `AuditActivitySourceTest` (aktör hiçbir zaman kişi değil, kararlı kimlikler), `HomeFeedServiceImplTest` (her kaynak okunabilir olmalı).
- Entegrasyon (CI): `AdminActivityApiTest`: üç yolda kullanıcı/operatör/yabancı rol 403, kimliksiz 401; dört modülün katkısı; erişimi olmayan yöneticiye alan ve dosya adı görünmez; kategori ve alan süzgeci; altın değerlendirme çekmecesi; durum şeridi; yanıtta ve loglarda hesap kimliği ve dosya adı yok; sınır dışı süzgeçler 400 `11110`, bilinmeyen kimlik 404 `11111`; en derin izinli sayfa; `/v1/home` herkes için, `/v1/info` `staleAfterDays`.
- Panel (node): bilgi mimarisi ve rol kapısı (kullanıcı, operatör, yönetici), diğer dalların ekranlarının özellik algılamasıyla yerleşmesi, eski adreslerin yönlendirilmesi, telefon alt çubuğu ve "Daha", ana sayfa modeli (selam, `/` yalnız becerilerle, beş sayı, kurulum ilerlemesi), Etkinlik merkezi (şerit, sorgu dizesi temizliği, güne göre gruplama, satır modeli, bağlantı doğrulaması), bilgi sağlığı skoru formülü, benimseme k ≥ 5, sorun sıralaması, cevap içi içgörü kartları, API yolları. Mevcut testler yeni bilgi mimarisine göre güncellendi.

## Canlı tarayıcı kontrolü

Yapılmadı. Ekranlar maketlerden (`research_notes/tasarim/platform-A/B/C`) panelin token'larıyla çizildi; Fildişi ve Obscur'da görünüm, dar ekranda alt çubuk ve "Daha" menüsü, daraltılmış şeritte grup çizgileri, ana sayfa şeridinin yoğunluğu, Etkinlik merkezi çekmecesi ve cevap içi kartların yeri canlı yığında gözle doğrulanmalı.
