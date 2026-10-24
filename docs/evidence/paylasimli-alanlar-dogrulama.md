# Paylaşımlı bilgi alanları ve izin duyarlı retrieval: doğrulama ve kanıt kaydı (ADR-0028)

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

## Künye

- **Tarih:** 2026-10-24
- **Branch:** `feature/doguyaras-paylasimli-alanlar` (`origin/develop` `55cd858` üzerinden).
- **Ortam:** Windows 11 + Docker Desktop; JDK 25.0.4.1; PostgreSQL 18 + pgvector 0.8.7 (`pgvector/pgvector:0.8.7-pg18-trixie`).
- **Kapsam:** yol haritası `enterprise_features.md` madde 3; `market_needs.md` madde 1 ("aşırı paylaşım").
- **Davranışsal kapsam (seviye 3):**
  - Gerçek PostgreSQL ve HNSW üzerinde HTTP uçtan uca testler (`CollectionAccessTest`).
  - Ayrı bir container'da sentetik performans ölçümü.
  - Gerçek bge-m3 ile eval (`RetrievalEvalTest`).
  - Çalışan yığına dokunulmadı.

## 1. Güvenlik erişim matrisi (`CollectionAccessTest`, gerçek port ve veritabanı)

| Durum | Beklenen | Sonuç |
|---|---|---|
| A grubundaki kullanıcı (`/hukuk`) | A alanının belgesini listeler, okur, retrieval'da bulur; B'nin belgesi ve alanı 404, retrieval'da yok | PASS |
| Grupsuz kullanıcı | Yalnız kişisel belgeleri; alan listesi boş; retrieval yalnız kişisel | PASS |
| Yönetici, erişimsiz | Bütün alanları `readable: false`, `manageable: true` görür ve paylaşımlı belge metadatasını okur. Soru-cevapta alan içeriği yok: `NOT_FOUND`, model çağrısı 0. Başka kullanıcının kişisel belgesi 404 | PASS |
| Yönetici, `USER` grant'ı ile | Aynı soru `ANSWERED`, atıfta `collectionName` | PASS |
| Grant kaldırıldı | Sonraki istekte belge 404, liste boş, retrieval boş. Geçmişteki atıf `documentAccessible: false`, `documentDeleted: false`, ad ve alan `null`. Silinince `documentDeleted: true` | PASS |
| Bozuk grup claim'i | Liste değil (nesne), iç içe liste ve kontrol karakterli değer: grup yok, alan yok, belge 404. Kontrol karakterli grant `400` | PASS |
| Yetkisiz chunk birebir en iyi eşleşme | İK belgesinin metni sorunun kendisi (cosine 1, bütün terimler). İK okuyucusunda ilk sırada. A okuyucusunun 100/100 sınırlı vektör ve sözcük aday listelerinde yok; terim payının paydası yalnız A'nın chunk'larını sayıyor; API cevabında ve prompt'ta yok | PASS |
| IDOR | Yönetici olmayanın 8 yönetim çağrısı 403 `90101` ve `ACCESS_DENIED` satırı (6 `COLLECTION_ADMIN`, 2 `DOCUMENT`). Okuyucunun paylaşımlı belge silmesi 403 `10004`. Okunamayan alan/belge ve başka alanın grant'ı 404; yöneticinin başkasının kişisel belgesini taşıması ve silmesi 404 | PASS |
| Eşzamanlılık | 12 yükleme ile 12 grant ekleme/kaldırma eşzamanlı: `document_principal` ile türetilen beklenen liste arasında fark 0; kaldırılan grant'tan satır kalmadı | PASS |
| Denetim ve log | Oluşturma, güncelleme ve silme olayları; grant olayları `GROUP` ayrıntısıyla; taşıma `TO_COLLECTION` ve `TO_PERSONAL`. Hiçbir log satırında grup yolu, alan adı ya da dosya adı yok | PASS |
| Metrikler | Belge adı politikası kapalıyken paylaşımlı belgenin adı ve alan adı yanıtta yok | PASS |

Birim testleri:
- `ClaimAuthoritiesConverterTest.callerGroups_*`: gruplar yalnız `GROUP_` yetkilerinden; bozuk claim boş.
- `DocumentApiContractTest.reader_*`: kullanıcı principal'ı önce; bozuk değerler düşer; en çok 256; `toString` grup yazmaz.
- `AccessDeniedAuditListenerTest.declaresAdmin_*`.
- `scripts/panel.test.mjs` "collections".

## 2. Performans (sentetik, ayrı container)

PERF_PLACEHOLDER

## 3. Eval

EVAL_PLACEHOLDER

## 4. Makine kontrolleri

CHECKS_PLACEHOLDER

## 5. Review skill'leri (her biri bir kez)

REVIEW_PLACEHOLDER

## Bilinen sınırlar

- Erişim belge düzeyindedir. Sözcüksel liste kurulumun eşleşen chunk'larını tarar ve sonra süzer; maliyet kurulumun büyüklüğüyle artar (bölüm 2). Yeniden değerlendirme koşulu ADR-0028'de.
- Grup üyeliği değişikliği token ömrü kadar (300 sn) gecikir. Keycloak grup yolu yeniden adlandırılınca erişim düşer (kapalı kalma).
- Gerçek bir Entra kiracısıyla denenmedi (ADR-0019).
- Panelde alan ekranı yönetici içindir. Alan yeniden adlandırma ve açıklama düzenleme yalnız API'de (`PUT /v1/collections/{id}`).
