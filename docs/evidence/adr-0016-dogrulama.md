# ADR-0016 (DOCX, TXT, MD): Doğrulama ve kanıt kaydı

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

- **Tarih:** 2026-10-03
- **Ortam:** Windows 11 + Docker Desktop (Docker Engine 29.6.1, 16 GB, 8 CPU, GPU yok); GitHub Actions `ubuntu-latest`. Modeller: bge-m3:567m, gemma4:e2b.
- **Kapsam:** DOCX, TXT ve MD yükleme; JDK ile DOCX ayrıştırma; sayfa yerine bölüm; atıfta `unit`; panel; `V2__document_formats.sql`.
- **Karar:** kullanıcı onayı (2026-10-03). DOCX için Apache POI'ye onay vardı; JDK yettiği için eklenmedi (yeni bağımlılık yok).
- **Davranışsal kapsam (seviye 3):** var. Gerçek modellerle, çalışan yığında üç türden dosya yüklendi ve soruldu (Bölüm 2).

## 1. Makine kontrolleri

| Kontrol | Komut | Sonuç |
|---|---|---|
| Build ve testler | `./mvnw -B -ntp verify` | **PASS**: 385 Java testi (önce 358). Yeni: `DocxTextExtractorTest` (10), `PlainTextExtractorTest` (7), `DocumentFormatsTest` (4), API, worker, retrieval ve soru testleri |
| Panel testleri | `node --test scripts/panel.test.mjs` | **PASS**: 17 (3 yeni: kabul edilen türler, bölüm etiketi, her başarısızlık nedeninin Türkçe metni) |
| Migration | `MigrationConventionsTest`, Testcontainers'ta Flyway | **PASS**: V2 kendi şemasında, açıklamalı; yeni nedenler veritabanı kontrolünden geçiyor (`IngestionWorkerTest.runOnce_whenADocxIsBrokenOrUnsafe_failsWithTheNewReasons`) |
| Mutasyon | `bash scripts/mutation-check.sh` | Bölüm 5 |

## 2. Canlı deneme

Örnek belgelerden üretilen dosyalar (`samples/src/`): masraf politikası DOCX (başlıklar `Balk1` stilinde) ve MD; izin yönetmeliği **Windows-1254** kodlu TXT.

| Adım | Sonuç |
|---|---|
| Yükleme | Üçü de 201; `format` DOCX, MD, TXT |
| İşleme | Üçü de READY. DOCX ve MD 6 bölüm (her başlık bir bölüm), TXT 1 bölüm, 3 chunk |
| "Kişisel araçla yapılan iş yolculuğunda kilometre başına ne ödenir?" | "kilometre başına 9 TL yakıt ve yıpranma bedeli ödenir [1][2]"; kaynaklar: `masraf-politikasi.md, bölüm 4` ve `masraf-politikasi.docx, bölüm 4`. 60 sn |
| "Bir yıldan beş yıla kadar hizmeti olan çalışan yılda kaç gün yıllık izin kullanır?" | "on dört iş günü yıllık izin kullanır [1]"; kaynak `izin-yonetmeligi.txt, bölüm 1`. Windows-1254 metin doğru çözüldü. 46 sn |

## 3. Review

REVIEW_PLACEHOLDER

## 4. Bulgu → düzeltme

FIX_PLACEHOLDER

## 5. Mutasyonlar

`ONLY="M135 M145 M215 … M224" bash scripts/mutation-check.sh`: baseline yeşil (385 Java testi, 9 node suite). Sonuç: **12/12 yakalandı**.

- M135 ve M145 eski korumalardır; kod taşındığı için tanımları güncellendi (format tespiti, `TextNormalizer`).
- M215–M218: DTD genişletme, ZIP bombası bütçesi, parça sayısı, ikinci ana parça.
- M219–M220: ikili dosyanın .txt, ZIP olmayanın .docx diye kabulü.
- M221: başlıkların bölüm başlatması. M222: Windows-1254 geri dönüşü (ilk tanım bir istisna attığı için test hatası olarak görünüyordu; kod sayfası değiştirilerek yeniden tanımlandı ve tek başına koşuldu).
- M223–M224: atıf birimi (API ve panel).
- Yakalanmayan, eşdeğer bir mutasyon: DOCX toplayıcısındaki karakter sayacı kaldırılırsa `TextSections` aynı sınırı yine uygular; sayaç yalnız belleği erken korur.

Çıktı: [`adr-0016-mutasyon-ciktisi.txt`](adr-0016-mutasyon-ciktisi.txt).

## 6. CI

CI_RESULT
