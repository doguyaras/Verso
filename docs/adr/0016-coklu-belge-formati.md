# ADR-0016: DOCX, TXT ve MD belgeleri

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-03
- **Karar verenler:** doguyaras ("txt md ve docx ekle onay veriyorum", 2026-10-03; DOCX için Apache POI'ye onay verildi, gerek kalmadı)
- **İlgili eşik (referans Bölüm 24):** yok. Yeni format isteği, ya da DOCX ayrıştırmasının bilinmeyen bir yoldan API'yi düşürmesi durumunda yeniden değerlendirilir.

## Bağlam

ADR-0011 yalnız PDF kabul ediyordu. Kullanıcılar Word ve metin dosyalarını önce PDF'e çevirmek zorunda kalıyordu; kurumsal belgelerin önemli bir kısmı DOCX olarak dolaşır. Kullanıcı DOCX, TXT ve MD'yi istedi.

Kısıtlar:

- Dosya güvenilmeyen girdidir ve API ile aynı JVM'de ayrıştırılır (ADR-0011). Her yeni ayrıştırıcı aynı bellek ve CPU sınırlarını taşımalı.
- Atıflar sayfa numarasına dayanır. DOCX'te sayfa, açan programa göre değişir; TXT ve MD'de sayfa yoktur.
- Yükleme yolu kısa kalmalı: ayrıştırma yok, uzak çağrı yok (repo-context Bölüm 3).

## Seçenekler

| Konu | Seçenekler | Karar |
|---|---|---|
| DOCX ayrıştırıcı | Apache POI · Apache Tika · JDK (ZIP + StAX) | **JDK.** Metin yalnız `word/document.xml`'dedir; ZIP ve XML okumak için JDK yeter. POI'nin onayı vardı, ama getireceği bağımlılık ağacı (xmlbeans, commons-compress, log4j-api) olmadan aynı metin elde ediliyor ve sınırlar tamamen bizde |
| Sayfa olmayan formatlarda konum | Word'ün kaydettiği sayfa kırılımları (`lastRenderedPageBreak`) · bölüm | **Bölüm.** Kaydedilmiş kırılımlar son kaydın düzenine bağlıdır ve her üreticide yoktur. Bölüm, başlıklara göre kesildiği için çoğu zaman bir konudur |
| Formatın tespiti | İstemcinin media type'ı · uzantı + ilk baytlar | **Uzantı + ilk baytlar.** Media type tarayıcının tahminidir |
| TXT kodlaması | Yalnız UTF-8 · UTF-8, olmazsa Windows-1254 | **BOM varsa ona göre (UTF-8, UTF-16); yoksa UTF-8, geçersizse Windows-1254.** Eski Windows programlarının kaydettiği Türkçe metinler 1254'tür |

## Karar

**Yükleme (istek yolunda, ayrıştırmadan):**

| İlk baytlar / ad | Format |
|---|---|
| İlk 1024 baytta `%PDF-` (ad ne olursa olsun) | PDF |
| `.docx` adı ve 0. baytta ZIP başlığı (`PK\x03\x04`) | DOCX |
| `.txt`, `.md`, `.markdown` adı ve ilk 8 KB'ta NUL bayt yok (UTF-16 BOM'u varsa NUL serbest) | TXT / MD |
| Diğer her şey | 415 `DOCUMENT_TYPE_UNSUPPORTED` (10010; eski adı `DOCUMENT_NOT_PDF`, kod aynı) |

Parolalı DOCX ve eski `.doc` bir OLE kabıdır, ZIP değildir; yüklemede 415 alır. Format `document.format` sütununda saklanır (`V2__document_formats.sql`).

**Ayrıştırma (worker):**

- **DOCX:** ZIP bellekte akış olarak okunur, hiçbir yere yazılmaz.
  - En çok 1000 parça.
  - Bütün parçalar tek bir bayt bütçesinden açılır (max-content-bytes'ın iki katı): ZIP bombası sınırlı CPU harcar. Atlanan parça da açıldığı için sayılır.
  - Ana parça (`word/document.xml`) en çok max-content-bytes; aşarsa `TOO_MUCH_TEXT`.
  - İki ana parça varsa Word'ün hangisini gösterdiği belirsizdir: `UNSUPPORTED_FILE`.
  - XML, JDK'nın StAX okuyucusuyla okunur. DTD ve dış varlıklar kapalıdır (XXE, varlık genişletme), dış DTD ve şema erişimi boştur. Okuyucu yinelemeli değildir; derin iç içelik özyineleme yapmaz.
  - Okunan: paragraflar, sekme, satır sonu, tablolar (satır başına bir blok, hücreler " | " ile), metin kutuları (dış paragrafa katılır). Başlık stili (Heading n, Başlık n / `Balk` n, Title) ya da anahat düzeyi yeni bölüm başlatır.
  - Okunmayan: üst ve alt bilgi, dipnot, yorum, silinmiş revizyon, alan kodu.
- **TXT / MD:** paragraflar boş satırla ayrılır. MD'de kod bloğu dışındaki ATX başlığı (`# Başlık`) yeni bölüm başlatır.
- **Bölümleme:** başlıkta ya da sonraki paragraf bölümü ~3000 karakterin (yaklaşık bir sayfa) üstüne çıkaracaksa yeni bölüm. Sayfa sınırından (max-page-chars) uzun tek paragraf boşlukta kesilir. PDF sayfasının sınırları bölüme de uygulanır: bölüm sayısı (max-pages), bölüm başına karakter, toplam karakter.
- Bölüm, PDF sayfasının yerini alır: `document_page` satırı, chunk'ların `page_number`'ı ve atıf aynı kalır; yalnız birimi farklıdır.
- Yeni kalıcı nedenler: `INVALID_FILE` (okunabilir bir dosya değil), `UNSUPPORTED_FILE` (güvenle işlenmeyen DOCX yapısı). PDF nedenleri değişmedi.

**API (geriye uyumlu eklemeler):**

- `DocumentResponse.format`: `PDF`, `DOCX`, `TXT`, `MD`. `pageCount`, PDF'te sayfa, diğerlerinde bölüm sayısıdır.
- `Citation.unit`: `PAGE` ya da `SECTION`. `page` alanı bölüm numarasını da taşır.
- Prompt'ta pasaj başlığı `(sayfa n)` ya da `(bölüm n)`.

## Sonuçlar

- **Olumlu:**
  - Yeni bağımlılık yok; DOCX için POI'nin bağımlılık ağacı ve güvenlik açığı takibi gelmedi.
  - Bütün formatlar aynı sınırlardan, aynı chunk ve atıf yolundan geçer.
  - Panel ve demo değişmeden çalışır; panel bölümü "bölüm n" diye gösterir.
- **Olumsuz / kabul edilen risk:**
  - DOCX atfı sayfa değil bölümdür; kullanıcı Word'de sayfa numarasıyla arayamaz.
  - Üst ve alt bilgi, dipnot ve yorumlardaki metin aranmaz.
  - Windows-1254 geri dönüşü, UTF-8 olmayan başka bir kodlamayı (ör. Kiril) yanlış okur; dosya reddedilmez.
  - Ayrıştırma hâlâ API ile aynı JVM'de ve süre sınırı yok (ADR-0011'deki risk aynen sürer).
- **Etkilenen dosyalar:** `document-api` (`DocumentFormat`, `SourceUnit`, `DocumentResponse`, `RetrievedPassage`, `DocumentFailureReason`), `document-core` (`DocumentFormats`, `DocxTextExtractor`, `PlainTextExtractor`, `TextSections`, `TextNormalizer`, `DocumentTextExtractor`, `IngestionWorker`, repository'ler, `V2__document_formats.sql`), `qa-api` (`Citation`, `CitationUnit`), `qa-core` (`CitationExtractor`, `PromptBuilder`), `panel/`, entegrasyon dokümanları.
- **Geri alma yolu:** `DocumentFormats.detect` yalnız PDF'e döner; mevcut DOCX/TXT/MD belgeleri READY kalır ve aranabilir (sütun ve nedenler kalır).

## Yeniden değerlendirme koşulu

- Yeni format isteği (XLSX, PPTX, taranmış belge için OCR): her biri ayrı ayrıştırıcı ve sertleştirme demektir.
- Kullanıcılar DOCX'te sayfa numarası isterse: kaydedilmiş sayfa kırılımları ikinci bir konum olarak eklenebilir.
- Bir DOCX ayrıştırması API sürecini düşürürse: ayrıştırmayı ayrı süreçte çalıştırmak (ADR-0011 ile aynı koşul).
