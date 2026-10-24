# ADR-0032: Docling ile düzen ve tablo farkında ayrıştırma, taranmış PDF için OCR (isteğe bağlı yan konteyner)

- **Durum:** Kabul edildi (isteğe bağlı; varsayılan kapalı)
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras (görev: "tablo ve düzen farkında ayrıştırma, taranmış PDF için OCR, eval setinde ölçülsün; ölçüm haklı çıkarırsa varsayılan açılsın")
- **İlgili eşik (referans Bölüm 24):** eval setinde `table` sorularının uçtan uca doğru bilgi oranı; taranmış belge oranı; sayfa başına alım süresi.

## Bağlam

- ADR-0011 ve ADR-0016 metni JDK içinde çıkarır (PDFBox 3, JDK ZIP + StAX). Tablo yapısı kaybolur: yazı programından çıkmış çizgili bir tabloda hücreler aynı satırda boşlukla birleşir, çok satırlı hücreler komşu sütunlarla karışır. Metin katmanı olmayan (taranmış) bir PDF `NO_TEXT` ile reddedilir.
- Araştırma (`research_notes/.../technologies_dependencies.md`, `asia_jp_cn_kr.md`):
  - Başarısızlıkların yaklaşık %80'i veri ve ayrıştırma kalitesinden gelir; tablolar yapısını korumalı (HTML/Markdown tablo, Excel başlıklarının tekrarı).
  - Docling + docling-serve (MIT, LF AI & Data) ve Tesseract `tur`, izin verici lisanslı, CPU'da çalışan ve Türkçe OCR'ı olan tek düzen ve tablo yan konteyneridir. Marker/Surya/Chandra ağırlıkları gelir sınırlı, MinerU atıf ve MAU şartlı, 2025–26 VLM OCR'ları GPU öncelikli.
  - Docling 23 Ekim 2026'da 10'dan fazla DoS advisory'si yayımladı; hepsi 2.142.1'de kapandı. docling-serve 1.38.1 bu sürümü taşır.
- Kısıtlar: local modda dışarı bağlantı yok (llm-rules 1.1); dosya güvenilmeyen girdidir; ayrıştırma ingestion worker'ında, asenkron; atıf sayfaya dayanır (llm-rules 6.3).

## Seçenekler

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| A. JDK yolu olduğu gibi | Sıfır yeni bileşen | Tablo yapısı ve taranmış PDF yok | – |
| B. docling-serve yan konteyneri, JDK önde ve geri dönüş | Tablo yapısı, OCR, bölüm başlıkları; JDK sınırları dosyayı önce süzer; yan konteyner düşerse davranış bugünkü gibi | 7,6 GB image, 1,1–2,2 GB RAM, sayfa başına 0,7–2,6 sn CPU | Bir overlay, bir HTTP istemcisi |
| C. Tika / LangChain4j Docling ayrıştırıcısı JVM'de | Tek süreç | Tika tablo yapısını vermez; LangChain4j yine Docling sunucusu ister, ikinci bir soyutlama getirir | – |
| D. VLM tabanlı OCR (Granite-Docling, olmOCR) | Daha iyi tablo | GPU öncelikli, Türkçe kanıtı yok | GPU |

## Karar

**B, isteğe bağlı overlay olarak; varsayılan kapalı.** Ölçüm (aşağıda) varsayılanı açmayı haklı çıkarmadı.

**Dağıtım (`deploy/compose.docling.yaml`, `deploy/docling/Dockerfile`):**

- Image: `quay.io/docling-project/docling-serve-cpu:v1.38.1` digest ile pinli (docling 2.142.1, DoS düzeltmeleri). Üstüne yalnız `tessdata_best` `tur.traineddata` eklenir: commit ve SHA-256 ile pinli (`ADD --checksum`). Yerleşim, TableFormer ve OCR ağırlıkları upstream image'ın içindedir.
- Çalışma zamanında internet yok: yan konteyner yalnız `parsers` iç ağında (`internal: true`), host portu yok; yalnız uygulama o ağdadır. `HF_HUB_OFFLINE`, `TRANSFORMERS_OFFLINE`, uzak servis, dış eklenti ve web arayüzü kapalı; sonuçlar tek kullanımlık.
- Sınırlar: `mem_limit` 4g, `cpus` 2 (ölçülen tepe 2,2 GB), `pids_limit` 512, salt okunur kök + 1 GB tmpfs, `cap_drop: ALL`, `no-new-privileges`. Sunucunun kendi sınırları (200 sayfa, 20 MB, 600 sn) uygulamanınkilerin üstünde ikinci çittir.

**Alım (`document-core` `worker`):**

- `DocumentTextExtractor`: yan konteyner açıkken PDF (ve `docx: true` ise DOCX) **önce JDK ayrıştırıcısından** geçer. Onun sınırları (sayfa, glif, açılmış içerik, grafik durumu yığını, parola, ZIP bombası, XXE) dosyayı yan konteynere ulaşmadan reddeder; çıkardığı metin geri dönüş metnidir.
- `DoclingClient`: Boot'un `RestClient.Builder`'ı ve `HttpClientSettings`'i; yalnız iç adresler (`InetAddressFilter.internalAddresses()`), yönlendirme izlenmez, bağlantı 2 sn, çağrı başına okuma 30 sn, belge başına toplam 5 dk (claim kirasının, 10 dk, altında olduğu açılışta denetlenir). Asenkron API: `/v1/convert/file/async` → `/v1/status/poll/{id}` → `/v1/result/{id}`. Dosya sabit adla gider (`document.pdf`), kullanıcının dosya adı JVM'den çıkmaz. Yalnız yüklemede belirlenen giriş formatı, yalnız JSON çıktı; resim, sayfa resmi, resim sınıflandırma ve açıklama, zenginleştirme kapalı. Sonuç en çok 16 MB okunur (ölçülen ~15 KB/sayfa); JSON derinliği 200 ile sınırlı; görev kimliği desene uymazsa URL'ye konmaz.
- `DoclingDocumentReader`: DoclingDocument JSON'u okuma sırasıyla gezilir; `$ref` yalnız belge içine (`#/texts|tables|groups|pictures/n`), her öğe bir kez, derinlik 64, öğe sayısı ve tablo hücresi sınırlı. Sayfa üst ve alt bilgisi atılır. Tablo GitHub Markdown tablosu olur: tek başlık satırı (birden çok başlık satırı sütun bazında birleşir; işaretli başlık yoksa ilk satır), boş satır ve sütunlar atılır, `|` kaçışlanır; başlığı (caption) tablodan hemen önce gelir.
- `DoclingParser`: bulkhead (instance başına 1 belge), devre kesici (arka arkaya 3 hata → 5 dk atla; durum instance başınadır, worker'ın model duraklaması gibi), sayfa sınırı (100 sayfadan uzun PDF doğrudan JDK). Yan konteynerin sayfa sayısı JDK'nınkiyle aynı olmalı, yoksa geri dönüş (`PAGE_MISMATCH`: atıf yanlış sayfaya gider). Aynı sayfa ve metin sınırları (`max-page-chars`, `max-text-chars`) Markdown metne de uygulanır.
- **Geri dönüş:** yan konteynerin her hatası (`UNAVAILABLE`, `TIMEOUT`, `CONVERSION_FAILED`, `INVALID_RESPONSE`, `TOO_LARGE`, `PAGE_MISMATCH`, `TOO_MUCH_TEXT`, `CIRCUIT_OPEN`, `BUSY`, `TOO_MANY_PAGES`, `NO_TEXT`) JDK metnini kullandırır. Log satırı yalnız belge kimliği, sabit neden ve süredir (`Document parsed without the sidecar: documentId=… reason=… parseMs=…`); metrik `verso.ingestion.parsed{parser, fallback}`.
- **Metin katmanı olmayan PDF:** geri dönüş metni yoktur. Geçici hata (`UNAVAILABLE`, `TIMEOUT`, `CIRCUIT_OPEN`, `BUSY`) worker'ın backoff'lu yeniden denemesine gider; kalıcı hata `NO_TEXT` (ya da `TOO_MUCH_TEXT`) olur. Kapanışta dönüşüm beklenmez; belge deneme harcamadan geri bırakılır.
- **OCR:** `do_ocr=true`, `force_ocr=false`, `ocr_preset=tesserocr`, `ocr_lang=[tur, eng]`. Docling yalnız metin katmanı olmayan bölgeleri (sayfanın %5'inden büyük bitmap) OCR'lar. docling-serve 1.38.1, Tesseract için BCP-47 biçimini (`tr`) reddediyor ("No traineddata file 'tr'"); Tesseract kodları (`tur`, `eng`) çalışıyor. Bu yüzden ayar Tesseract kodu ister.
- **Parçalama (`PageChunker`):** Markdown tablosu sığıyorsa tek chunk'tır; sığmıyorsa satır aralarından bölünür ve her parça başlık satırını tekrarlar. Tablodan önceki kısa satır (başlık ya da caption, en çok 200 karakter) her parçanın önüne konur; tek başına kalan başlık ayrı chunk olmaz. Markdown tablosu olmayan sayfa eskisi gibi parçalanır: JDK yolu değişmez (`PageChunkerTest`: `|` içeren JDK metni `¦`'li eşiyle aynı chunk'ları verir).
- API, şema ve hata kodu değişmedi. `DocumentFailureReason.NO_TEXT` aynı.

## Ölçüm

Ayrıntı ve komutlar: [`docs/evidence/adr-0029-dogrulama.md`](../evidence/adr-0029-dogrulama.md), [`eval/README.md`](../../eval/README.md) "Docling".

Üç belge seti, aynı 11 kaynak metinden ve aynı 140 sorudan (26'sı `table`), hepsi `SamplePdfs` ile üretildi, el ile PDF yok:

- **Depodaki örnekler:** tablolar `|` ile yazılmış metin satırları.
- **Çizgili tablolar:** aynı satırlar yazı programından çıkmış gibi çizgili tablo (`SamplePdfs.Tables.GRID`).
- **Taranmış:** çizgili sürümün sayfaları 200 dpi gri JPEG; metin katmanı yok (`SamplePdfs.scanned`).

**Retrieval** (`RetrievalEvalTest`, bge-m3 CPU, aynı makine; sidecar'sız → sidecar'lı):

| Set | Bütün set recall@5 | Bütün set MRR | `table` recall@1 | `table` recall@5 | `table` MRR | Kapıda duran cevaplanamaz |
|---|---|---|---|---|---|---|
| Depodaki örnekler | 0,982 → 0,982 | 0,877 → 0,883 | 0,731 → 0,731 | 0,962 → 0,962 | 0,827 → 0,830 | 0,25 → 0,179 |
| Çizgili tablolar | 0,982 → 0,964 | 0,885 → 0,890 | 0,769 → 0,808 | 0,962 → 0,962 | 0,849 → 0,878 | 0,214 → 0,179 |
| Taranmış | 0 → 0,964 | 0 → 0,875 | 0 → 0,731 | 0 → 0,962 | 0 → 0,824 | – → 0,143 |

**Uçtan uca** (`scripts/eval.mjs`, gemma4:e2b GPU, geçici yığın; atıf isabeti / doğru bilgi / "bulunamadı"):

| Set | Sidecar'sız | Sidecar'lı | `table`: atıf isabeti, doğru bilgi |
|---|---|---|---|
| Depodaki örnekler | 0,902 / 0,902 / 0,536 (aynı Ollama) | 0,884 / 0,893 / 0,643 | 0,808, 0,769 → 0,808, 0,769 |
| Çizgili tablolar | ölçülemedi (koşu sırasında ana yığının Ollama'sı kapandı) | 0,848 / 0,848 / 0,679 | – → 0,769, 0,769 |
| Taranmış | 0 (her belge `NO_TEXT`) | 0,884 / 0,848 / 0,643 | 0 → 0,846, 0,769 |

**Maliyet:** ayrıştırma JDK'da sayfa başına 7–9 ms; docling'de (3 CPU) metin tablolu sayfada 0,67 sn, çizgili tablolu sayfada 1,45 sn, taranmış sayfada OCR ile 2,6 sn. Yan konteynerin bellek tepesi 2,2 GB (modeller yüklüyken boşta ~1,1 GB). Image 7,6 GB (amd64, Tesseract `tur` dahil).

**Karar gerekçesi:**

- Depodaki örneklerde (metin tablolar) kazanç yok. Docling bu satırları tablo saymıyor, tek paragrafa birleştiriyor; uçtan uca atıf isabeti ve doğru bilgi biraz düştü, "bulunamadı" yükseldi. Kapıda duran cevaplanamaz sorular 7/28'den 5/28'e indi; `RetrievalEvalTest`'in 0,2 koruması sidecar'lı koşuda tutmuyor.
- Çizgili tablolarda `table` sıralaması iyileşiyor (recall@1 +0,04, MRR +0,03), ama bütün set recall@5 iki soru kaybediyor.
- Taranmış PDF'de fark kesin: okunamayan belge okunuyor (recall@5 0 → 0,964).
- Sayfa başına maliyet 100–300 kat. Bu yüzden **varsayılan kapalı**; taranmış PDF'i olan kurulum overlay'i açar.

## Sonuçlar

- **Olumlu:**
  - Taranmış PDF artık okunabilir (yan konteyner açıkken); önce `NO_TEXT` ile reddediliyordu.
  - Çizgili tablolar yapısıyla saklanır; satır, başlığıyla birlikte aranır.
  - Yan konteyner düşerse, yavaşlarsa ya da tuhaf çıktı verirse belge yine READY olur (JDK metni).
- **Olumsuz / kabul edilen risk:**
  - 7,6 GB image, 2,2 GB'ya kadar RAM ve sayfa başına 0,7–2,6 sn CPU (Ölçüm).
  - Docling bu setin `|` ile yazılmış metin tablolarını tablo saymıyor, satırları tek paragrafa birleştiriyor (Ölçüm); bu yüzden varsayılan kapalı.
  - Taranmış tabloların OCR'ı zayıf: hücreler tek sütuna düşüyor, Türkçe karakterlerin bir kısmı kayboluyor.
  - Yan konteyner kendi DoS yüzeyidir; JDK ön süzgeci ve kaynak sınırları onu daraltır, kapatmaz. Docling sürümleri çok sık çıkıyor; güvenlik advisory'leri izlenir (`docs/versions.md`).
  - docling-serve'ün iptal çağrısı yok: zaman aşımında görev yan konteynerde `document_timeout` (5 dk) dolana kadar sürer.
- **Etkilenen dosyalar:** `document-core` (`config/DoclingProperties`, `DocumentConfiguration`, `worker/{DoclingClient,DoclingDocumentReader,DoclingParser,DocumentTextExtractor,PdfTextExtractor,PageChunker,IngestionWorker,IngestionMetrics}`, `pom.xml`), `config/verso.yml`, `application-local.yml`, `compose.yaml` (`x-db-env`), `deploy/compose.docling.yaml`, `deploy/docling/Dockerfile`, `deploy/prod.env.example`, `.env.example`, testler, `eval/`, `scripts/eval.mjs`.
- **Geri alma yolu:** overlay'i kaldırmak ya da `VERSO_DOCLING_ENABLED=false`. Saklanmış Markdown sayfalar READY kalır ve aranır; yeniden JDK ile ayrıştırmak için belge yeniden yüklenir.

## Yeniden değerlendirme koşulu

- Gerçek müşteri belgelerinde çizgili tablo ya da taranmış PDF oranı ölçülürse (ör. %10'u geçerse) varsayılan yeniden tartılır.
- Docling'in Türkçe OCR'ı ya da Granite-Docling gibi bir VLM CPU'da kabul edilir sürede çalışırsa.
- Bir docling advisory'si düzeltmesiz kalırsa overlay kullanım dışı bırakılır.
