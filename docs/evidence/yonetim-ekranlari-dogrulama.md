# Yönetim ekranları: doğrulama ve kanıt kaydı (ADR-0036)

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

## Künye

- **Tarih:** 2026-10-24
- **Branch:** `feature/doguyaras-yonetici-ekranlari` (`origin/develop` üzerinden).
- **Ortam:** Windows 11, JDK 25.0.4.1, Node (yerel). **Docker kapalı** (makine yükü); Testcontainers testleri ve tarayıcıda görsel kontrol bu turda koşulmadı.

## 1. Yerelde koşulan

| Kontrol | Komut | Sonuç |
|---|---|---|
| Derleme (testler dahil derlendi, koşulmadı) | `./mvnw -B -ntp -T 1 -DskipTests install` | PASS |
| Yapısal testler | `./mvnw -B -ntp -T 1 -pl verso-app -am test -Dtest='ArchitectureRulesTest,TransactionBoundaryRulesTest,ModuleStructureTest,ErrorCodeUniquenessTest,ConfigDriftTest'` | PASS (15/15) |
| Node testleri (panel dahil) | `GITLEAKS=/c/Users/doguy/.local/bin/gitleaks.exe node --test scripts/*.test.js scripts/panel.test.mjs` | PASS (199 test, 0 hata) |

Panelde yeni testler (`scripts/panel.test.mjs`, "Yönetim" bölümü): rol kapısı (token'daki `verso-admin` yetmez; kullanıcı ve operatör listesi aynı; sekme çubuğu değişmez; kısayol harfleri tekil), `sub` deseni Java `SubjectIds` ile birebir, kırpma ve harf katlama yok, hata metni değeri tekrar etmez; silme ve karartma onayının birebir eşleşmesi; özet modeli (silinir / kalır ve raporlanır, eksik alan sıfır, kesilme bayrağı) ve silme sonucu (tekrar edilen istek); dışa aktarım dosya adı (`sub` yok); `sub`'ın URL, hash, tarayıcı deposu ve konsola girmemesi, ekrandan çıkınca silinmesi; API yolları ve gövdeleri (`sub` yalnız gövdede), 403'ün `ApiError` olması; bilgi alanı düzenleme kuralları; parça başlığı, önizleme, etiket, tekrarsız sayfalama ve Türkçe harf duyarsız arama; 44 px dokunma hedefi, yeni renk ve hareket yok, ürün adı eki yok.

## 2. CI'da koşacak (yerelde koşulmadı)

`AdminDocumentChunkApiTest` (gerçek port ve PostgreSQL): yöneticinin kendi belgesinde sıralı parçalar, `no-store`, denetim satırı yok, log'da parça metni yok; `SECTION` birimi; yönetici olmayana 403 + `ACCESS_DENIED` (`ADMIN_DOCUMENT_CHUNK`), token'sıza 401; başka hesabın belgesi ile olmayan belge aynı 404 gövdesi; bilgi alanında grant yokken 404, `USER` grant'ıyla 200, grant kaldırılınca 404; sayfalama ve `page`/`size` sınırlarında 400; 2 000 kod noktası sınırı ve `charCount`.

## 3. Canlı kontrol bekleyen (yapılmadı)

- Tarayıcıda görsel kontrol: menü ve hesap menüsündeki "Yönetim" grubu (daraltılmış şeritte çizgi), üç ekranın açık ve koyu temada görünümü, telefon genişliği ve 44 px hedefleri, `prefers-reduced-motion`.
- KVKK akışı uçtan uca: özet, gerçek Blob indirmesi ve dosya adı, onaylı silme, 409 `13021` ile karartma; CSP altında indirmenin engellenmediği.
- Parça ekranından sayfa çekmecesinin açılması ve parçanın vurgulanması.

## 4. Review skill kararları

| Skill | Karar |
|---|---|
| `verso-security-review` | APPROVE WITH NON-BLOCKING COMMENTS (LOW: `autocomplete="off"` tarayıcıca yok sayılabilir; dışa aktarım tasarım gereği diske iner) |
| `verso-test-writer` | PASS (node); Java IT CI'da |
| `verso-api-contract-review` | APPROVE WITH NON-BLOCKING COMMENTS (OpenAPI diff yok, elle çıkarıldı; ekleme, kırıcı değil) |
| `verso-spring-code-review` | APPROVE WITH NON-BLOCKING COMMENTS (LOW: 404 öncesi ayrı ret log satırı yok, sayfa görünümüyle aynı desen) |
