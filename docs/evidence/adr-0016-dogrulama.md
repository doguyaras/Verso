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
| Build ve testler | `./mvnw -B -ntp verify` | **PASS**: 395 Java testi (önce 358). Yeni: `DocxTextExtractorTest` (16), `PlainTextExtractorTest` (9), `DocumentFormatsTest` (5), `DocumentTextExtractorTest` (1), API, worker, retrieval ve soru testleri |
| Panel testleri | `node --test scripts/panel.test.mjs` | **PASS**: 17 (3 yeni: kabul edilen türler, bölüm etiketi, her başarısızlık nedeninin Türkçe metni) |
| Migration | `MigrationConventionsTest`, Testcontainers'ta Flyway | **PASS**: V2 kendi şemasında, açıklamalı; yeni nedenler veritabanı kontrolünden geçiyor (`IngestionWorkerTest.runOnce_whenADocxIsBrokenOrUnsafe_failsWithTheNewReasons`) |
| Mutasyon | `bash scripts/mutation-check.sh` | Bölüm 5 |

## 2. Canlı deneme

Örnek belgelerden üretilen dosyalar (`samples/src/`): masraf politikası DOCX (başlıklar `Balk1` stilinde) ve MD; izin yönetmeliği **Windows-1254** kodlu TXT.

| Adım | Sonuç |
|---|---|
| Yükleme | Üçü de 201; `format` DOCX, MD, TXT |
| İşleme | Üçü de READY. DOCX ve MD 6 bölüm (her başlık bir bölüm), TXT 1 bölüm, 3 chunk |
| "Kişisel araçla yapılan iş yolculuğunda kilometre başına ne ödenir?" | "kilometre başına 9 TL yakıt ve yıpranma bedeli ödenir [1][2]"; kaynaklar: `masraf-politikasi.md, bölüm 4` ve `masraf-politikasi.docx, bölüm 4`. 60 sn; review düzeltmelerinden sonra aynı cevap ve kaynaklar, 25 sn |
| "Bir yıldan beş yıla kadar hizmeti olan çalışan yılda kaç gün yıllık izin kullanır?" | "on dört iş günü yıllık izin kullanır [1]"; kaynak `izin-yonetmeligi.txt, bölüm 1`. Windows-1254 metin doğru çözüldü. 46 sn; review sonrası "Yılda on dört iş günü", aynı kaynak, 21 sn |

## 2a. Değerlendirme (gerçek bge-m3)

`FormatEvalTest`: altı örnek belge PDF, DOCX ve MD olarak ayrı hesaplarda, 33 soru.

| Tür | Belge ilk 5'te | Bilgi ilk 5 pasajda | Cevaplanabilir eşik üstü | Cevaplanamaz eşik altı |
|---|---|---|---|---|
| PDF | 1,00 | 0,96 | 1,00 | 0,50 |
| DOCX | 1,00 | 1,00 | 1,00 | 0,50 |
| MD | 1,00 | 1,00 | 1,00 | 0,50 |

## 3. Review

Tek ajan, altı skill, `0e7940e` üzerinde koştu. Ajan kendi worktree'sinde tam build'i, panel testlerini ve saldırgan girdilerle bir prob programını çalıştırdı (üretim sınırları, `-Xmx1152m`); kendi mutasyonlarını da denedi.

| Skill | Karar | Öne çıkan bulgular |
|---|---|---|
| `verso-security-review` | APPROVE WITH NON-BLOCKING COMMENTS | Doğrulanan: XXE ve billion laughs reddi, ZIP bombası bütçesi (292 KB'lık 5×60 MB dosya ~0,7 sn'de red), loglarda içerik yok. B1: metin olmayan XML ve boş hücre bombası 0,2–0,3 GB geçici heap harcatıyordu (çöküş yok). B2: derinlik sınırı yalnız JDK varsayılanıydı. B3: NUL yalnız ilk 8 KB'ta aranıyordu. B4: gizli metin (`w:vanish`) okunuyordu |
| `verso-db-migration-review` | APPROVE WITH NON-BLOCKING COMMENTS | D1: eski sürüme dönüş yeni nedenlerle listeyi 500'e düşürür; çok instance'lı kurulumda eski worker yeni türleri PDF sanar. D2: `CHECK`'ler `NOT VALID`'siz |
| `verso-api-contract-review` | APPROVE WITH NON-BLOCKING COMMENTS | Breaking change yok. A1: panel uzantısız PDF'i reddediyordu (gerileme). A2: bilinmeyen format ham gösteriliyordu |
| `verso-spring-code-review` | APPROVE WITH NON-BLOCKING COMMENTS | K1: adı `.md` olan ve "%PDF-" geçen dosya PDF ayrıştırıcısına gidiyordu. K2: Word metin kutusu iki kez okunuyordu (`mc:Choice` + `mc:Fallback`), taşınan metin de. K3: `outlineLvl 9` başlık sayılıyordu. K4: geniş catch. K5: sert kesim surrogate bölebiliyordu. K6: parça adı büyük/küçük harf. K7: import sırası |
| `verso-test-writer` | APPROVE WITH NON-BLOCKING COMMENTS | 6 mutasyon yaşadı: birikimli bütçe, toplayıcı karakter sınırı, dış varlık ayarları, `outlineLvl`, `unreadable`, UTF-16 katı çözme. Log privacy testi yalnız PDF içindi |
| `verso-llm-review` | APPROVE WITH NON-BLOCKING COMMENTS | L1: yeni türler için eval sonucu yoktu. L3: `CitationUnit.valueOf` yeni bir birimde 500 verir |

## 4. Bulgu → düzeltme

| Bulgu | Düzeltme | Kalıcı kontrol |
|---|---|---|
| K1: format önceliği | `.docx`/`.txt`/`.md` adı ve uyan baytlar önce; PDF başlığı yalnız diğer adlarda | `DocumentFormatsTest.detect_whenATextOrMarkdownMentionsThePdfHeader_staysText`, M229 |
| K2: çift okuma | `mc:Fallback` ve `w:moveFrom` alt ağaçları atlanır; iç paragraf boşlukla ayrılır | `extract_whenTextBoxesHaveAFallbackOrTextWasMoved_readsItOnce`, M225 |
| B4: gizli metin | `w:vanish`, `w:webHidden` olan run'lar atlanır (`val="0"` görünür sayılır) | `extract_whenARunIsHidden_*`, M226 |
| B1: heap | Ana parça en çok 32 MB; satırda en çok 1000 hücre; ayraçlar sayıma girer | `extract_whenATableRowHasTooManyCells_*`, M227 |
| B2: derinlik | `jdk.xml.maxElementDepth` açıkça 100 | `extract_whenElementsNestDeeperThanTheLimit_*`, M228 |
| B3: geç gelen ikili veri | Worker çözülen metinde NUL görürse `INVALID_FILE` | `extract_whenBinaryDataFollowsTheFirstKilobytes_*`, M230 |
| D1/D2: geri dönüş | ADR-0016'ya kurulum ve dönüş sözleşmesi, dönüş SQL'i | – |
| A1/A2: panel | Tür kararı sunucuda (seçici yalnız öneri); bilinmeyen format "Belge" | `panel.test.mjs` |
| K3–K7 | `outlineLvl 9` gövde; yalnız beklenmeyen hata türü loglanır; surrogate korunur; parça adı harf duyarsız; import sırası | `extract_whenAnOutlineLevelIsSet_*`, `lastWhitespace_*`, `extract_whenTheMainPartNameHasOtherCase_*` |
| T1/T5/T6, L4 | Birden çok parçalı bütçe testi, `unreadable` testi, yalancı UTF-16 BOM testi; DOCX ve MD log privacy | `DocxTextExtractorTest`, `DocumentTextExtractorTest`, `PlainTextExtractorTest`, `IngestionWorkerTest.ingestion_whenSuccessfulOrFailing_logsNoFileNameOrText` |
| L1: eval | `FormatEvalTest`: PDF/DOCX/MD üzerinde 33 soru, gerçek bge-m3 | `eval/results/retrieval-formats.json` (Bölüm 2a) |
| L3: kırılgan eşleme | Kapsayıcı `switch` | M223 |

Kabul edilen açıklar:

- **Gerçek Word, LibreOffice ve Google Docs dosyalarıyla deneme yapılmadı:** testler ve canlı deneme sentetik DOCX kullanır.
- **Toplayıcıdaki karakter sınırı** (T2) ve dış varlık ayarları (T3) davranışı değiştirmeyen derinlemesine savunmadır; `TextSections` ve `SUPPORT_DTD=false` aynı sonucu verir.
- **Ayrıştırma süresi sınırsız ve API ile aynı JVM'de** (ADR-0011'deki risk).
- **Çok instance'lı kurulumda** yeni türler, son eski instance kapanana kadar yüklenmemelidir (ADR-0016).

## 5. Mutasyonlar

`ONLY="M135 M145 M215 … M230" bash scripts/mutation-check.sh`: baseline yeşil (395 Java testi, 9 node suite). Sonuç: **18/18 yakalandı** (M220, format önceliği değişince yeniden tanımlanıp tek başına koşuldu).

- M135 ve M145 eski korumalardır; kod taşındığı için tanımları güncellendi (format tespiti, `TextNormalizer`).
- M215–M218: DTD genişletme, ZIP bombası bütçesi, parça sayısı, ikinci ana parça.
- M219–M220: ikili dosyanın .txt, ZIP olmayanın .docx diye kabulü.
- M221: başlıkların bölüm başlatması. M222: Windows-1254 geri dönüşü (ilk tanım bir istisna attığı için test hatası olarak görünüyordu; kod sayfası değiştirilerek yeniden tanımlandı ve tek başına koşuldu).
- M223–M224: atıf birimi (API ve panel).
- M225–M230 (review düzeltmeleri): `mc:Fallback` çift okuma, gizli metin, hücre sınırı, derinlik sınırı, format önceliği, geç gelen NUL.
- Yakalanmayan, eşdeğer bir mutasyon: DOCX toplayıcısındaki karakter sayacı kaldırılırsa `TextSections` aynı sınırı yine uygular; sayaç yalnız belleği erken korur.

Çıktı: [`adr-0016-mutasyon-ciktisi.txt`](adr-0016-mutasyon-ciktisi.txt).

## 6. CI

CI_RESULT
