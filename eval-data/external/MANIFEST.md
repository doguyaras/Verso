# Harici değerlendirme külliyatı: "Hacettepe Üniversitesi" senaryosu

`eval/` altındaki set kurgusal bir şirketin 11 belgesini ölçer. Bu klasör daha büyük, gerçek bir kurumun belgelerine benzeyen harici bir ölçüm içindir. Senaryo şudur: **müşteri bir üniversitedir** (Hacettepe Üniversitesi). Belge havuzunda iki kısım vardır:

- üniversitenin Resmî Gazete'de yayımlanmış bütün yürürlükteki yönetmelikleri: eğitim-öğretim, lisansüstü, tıp ve diş hekimliği, döner sermaye, taşınmaz, arşiv ve 90'a yakın uygulama ve araştırma merkezi;
- personelin, öğrencinin, İSG, mali işler ve satın alma birimlerinin uyduğu ulusal kanun ve yönetmelikler.

Belgeler resmî metindir; içerik uydurulmamıştır. Sorular bu metinlerden elle yazıldı.

Bu klasör git'e girmez (`.gitignore`: `/eval-data/external/*`); git'e yalnızca bu dosya girer. Kalan dosyalar aşağıdaki adımlarla yeniden üretilir.

## İçerik

| Klasör / dosya | Ne |
|---|---|
| `pdf/` | **200 PDF**, 2.132 sayfa. Ölçümde yüklenen belgeler bunlardır |
| `html/` | Kaynak HTML, Adalet Bakanlığı mevzuat servisinden alındığı gibi. Yalnızca charset etiketi UTF-8 olarak düzeltildi |
| `text/` | `pdftotext` sayfa metinleri. `<slug>.txt` sayfa işaretlidir; `<slug>.json` sayfa dizisidir. Altın sayfalar bunlardan doğrulandı |
| `sources.json` | Her PDF için başlık, tür, mevzuat no, Resmî Gazete tarihi ve sayısı, sayfa sayısı, mevzuat.gov.tr adresi |
| `questions.json` | **350 soru**, `eval/eval-set.json` biçiminde |
| `questions-multihop.json` | **32 çok belgeli soru**, `eval/multihop-set.json` biçiminde |
| `eval-mjs-external.patch` | `scripts/eval.mjs` için yerel yama (aşağıda "Yükleme ve ölçüm"). Commit edilmez |
| `build/` | İndirme, PDF ve sayfa çıkarma araçları, soru taslakları (`drafts/*.jsonl`) ve doğrulayıcı (`build.mjs`) |

Toplam boyut yaklaşık 85 MB.

### Belge dağılımı

| Grup | Belge | Sayfa | Örnekler |
|---|---|---|---|
| Hacettepe Üniversitesi yönetmelikleri (`hu-*`) | 111 | 408 | Ön lisans ve lisans eğitim-öğretim ve sınav, lisansüstü, yabancı dil hazırlık, yaz okulu, Tıp ve Diş Hekimliği fakülteleri, konservatuvar, döner sermaye, sağlık hizmetleri, taşınmazlar, arşiv; yaklaşık 90 merkez yönetmeliği |
| Kanunlar (`kanun-*`) | 23 | 914 | 2547, 2914, 2809, 657, 4857, 6331, 6698, 4982, 3071, 6245, 5018, 4734, 4735, 2886, 5070, 5176, 5378, 5651, 3359, 4688, 4483, 7201, 7545 |
| Ulusal yönetmelikler (`yok-*`, `ky-*`, `isg-*`, `is-*`, `kvkk-*`, `mali-*`, `ihale-*`, `saglik-*` …) | 66 | 810 | YÖK yönetmelikleri (lisansüstü, geçiş/çift anadal/yan dal, öğrenci disiplini, doçentlik, BAP, kalite), memur yönetmelikleri, 15 İSG yönetmeliği, KVKK, taşınır, iç denetim, ihale uygulama yönetmelikleri, resmî yazışma, arşiv, yangın, sıfır atık |

Merkez yönetmelikleri neredeyse aynı kalıpta yazılmıştır ve **bilerek** havuzdadır. Çoğunda müdür üç yıl için görevlendirilir ve yönetim kurulu beş, yedi ya da dokuz üyeden oluşur. Sistem doğru merkezi bulmak zorundadır; hard negative ölçeği `samples/`'tan çok büyüktür. Ulusal metinler de birbirine benzer kurallar içerir: yıllık izin 657'de ve 4857'de, pilot ortak oranı mal, hizmet ve yapım ihalelerinde, lisansüstü kuralları hem Hacettepe'nin hem YÖK'ün yönetmeliğinde vardır.

## Kaynaklar ve lisans

| Kaynak | Adres | Lisans / dayanak | Kullanıldı mı |
|---|---|---|---|
| Adalet Bakanlığı Mevzuat Bilgi Sistemi (mevzuat.gov.tr metinleri). Belgelendirilmemiş JSON servisi `bedesten.adalet.gov.tr/mevzuat` (`searchDocuments`, `getDocumentContent`) | https://www.mevzuat.gov.tr , https://mevzuat.adalet.gov.tr | **Resmî metin.** 5846 sayılı FSEK md. 31: "Kanun, tüzük, yönetmelik, tebliğ, genelge ve mahkeme kararlarının çoğaltılması, yayılması ve işlenmesi serbesttir." Üniversite yönetmelikleri Resmî Gazete'de yayımlanan yönetmeliklerdir | **Evet**: 200 belgenin tamamı |
| `hasankursun/turkish-legislation-corpus` (Hugging Face) | https://huggingface.co/datasets/hasankursun/turkish-legislation-corpus | CC-BY-4.0 | Hayır. Yalnızca kanunları içeriyor, 1K'dan az kayıt; metni doğrudan kaynaktan aldık |
| `muhammetakkurt/mevzuat-gov-dataset` (HF) | https://huggingface.co/datasets/muhammetakkurt/mevzuat-gov-dataset | Kartta MIT yazıyor, ama aynı kart "ticari kullanım izinsiz yapılamaz" diyor; çelişkili | Hayır |
| `boranyzgc/turkish-legal-mevzuat` (HF) | https://huggingface.co/datasets/boranyzgc/turkish-legal-mevzuat | Lisans yok | Hayır |
| `ogulcanakca/epdk_elektrik_piyasasi_mevzuat` (HF) | – | Sektöre özel (EPDK) | Hayır |
| Üniversite yönergeleri (hacettepe.edu.tr, odtu.edu.tr) | – | Yönerge FSEK md. 31'de sayılmaz. Ayrıca `.edu.tr` ve `mevzuat.gov.tr` bu makineden erişilemedi (zaman aşımı) | Hayır |
| Açık şirket el kitapları (GitLab CC BY-SA 4.0, PostHog, Basecamp) | – | GitLab'ın BY-SA lisansı izin verilen listede değil; diğerlerinin lisansı incelenmedi | Hayır. Türkçe kaynak yeterliydi ve senaryo tutarlı kalsın istendi |
| Türkçe QA setleri (TQuAD, THQuAD, mmlu-tr …) | – | – | Hayır. Bu belgeler hakkında soru içermiyorlar; sorular belgelerden yazıldı |

Not: PDF'ler resmî nüsha değildir. Bakanlığın HTML'i Edge (headless, `--print-to-pdf`) ile basıldı. Sayfa numaraları bu baskıya göredir.

## Soru seti

Ortak kural: her cevaplanabilir sorunun beklenen belgesi (`document`, PDF adı), sayfası (`pages`) ve cevapta geçmesi beklenen ifadesi (`expect`, `|` ile eşanlamlılar) vardır. `section` bilgi amaçlıdır (MADDE/fıkra).

Sorular soru başına bir **çapa** ile yazıldı: belgeden aynen alınmış bir ifade. `build/build.mjs` çapayı `text/` sayfalarında bulur ve altın sayfayı oradan yazar. Ardından `expect`'in o sayfada `eval.mjs`'in kullandığı tam kelime eşleşmesiyle geçtiğini kontrol eder. 382 sorunun hepsi bu kontrolden hatasız geçti.

**`questions.json`: 350 soru**

| | Sayı |
|---|---|
| Cevaplanabilir | **305**, 143 farklı belgeden; 127'si Hacettepe yönetmeliklerinden |
| Cevaplanamaz | **45**: 35 `on-topic` (konu belgede var, sorulan bilgi yok: ücret, tarih, kişi, adres, bütçe kanunuyla belirlenen TL tutarı), 10 `off-topic` (servis, e-posta kotası, kütüphane saatleri …). Hepsi bütün metinlerde grep ile arandı |

Cevaplanabilir soruların etiketleri (bir soru birden fazla etiket taşıyabilir):

| Etiket | Sayı | Anlamı |
|---|---|---|
| `number` | 177 | Cevap sayı, süre, oran ya da puan |
| `lookup` | 88 | Doğrudan bilgi |
| `disambiguation` | 68 | Benzer kural başka belgede farklı değerle var (merkezler, Hacettepe ve YÖK lisansüstü, 657 ve 4857, ihale türleri) |
| `paraphrase` | 57 | Soru belgenin anahtar sözcüklerini kullanmıyor |
| `abbreviation` | 28 | Merkez kısaltmaları: HÜBAUM, HUZEM, HÜTÖMER, KARMER … |

**`questions-multihop.json`: 32 çok belgeli soru**

| Tür | Sayı |
|---|---|
| İki belgeli karşılaştırma ya da birleştirme | 24 |
| Üç belgeli | 4 |
| `partial` (bir parçası belgelerde yok, "bulunamadı" denmeli) | 4 |

## Yükleme ve ölçüm

Belgeler `scripts/eval.mjs`'in kullandığı yoldan yüklenir: CI istemcisiyle (`verso-ci`) `POST /v1/documents` (multipart `file`), ardından `GET /v1/documents/{id}` READY olana kadar beklenir. `eval.mjs` bu külliyatla değiştirilmeden çalışmaz:

- **Soru seti yolu sabittir.** `eval/eval-set.json` ve `eval/multihop-set.json` kodda yazılı.
- **Kuyruk sınırı.** `verso.document.max-queued-per-account` 20'dir. 21. yükleme 429 (`DOCUMENT_QUEUE_FULL`) alır ve betik durur. Eşzamanlı yükleme sınırı aşılırsa 503 gelir.
- **READY bekleme süresi** 10 dakikadır. 2.132 sayfa CPU'da bundan çok daha uzun sürer.
- **Temizlik** başlangıçta yalnızca ilk 100 belgeyi siler.

`eval-mjs-external.patch` bu dört noktayı ortam değişkenleriyle açar; varsayılan davranış değişmez. Eklenenler: `EVAL_SET_FILE`, 429/503'te 5 saniye bekleyip yeniden deneme, `READY_TIMEOUT_MIN`, sayfalı temizlik.

```bash
git apply eval-data/external/eval-mjs-external.patch   # yerel; commit etmeyin (geri almak: git checkout scripts/eval.mjs)
# yığın ayakta: docker compose up -d --build --wait

# Tek belgeli set (350 soru). eval.mjs verso-ci hesabındaki BÜTÜN belgeleri önce siler.
SAMPLES_DIR=eval-data/external/pdf EVAL_SET_FILE=eval-data/external/questions.json \
  READY_TIMEOUT_MIN=240 OUT=e2e-external-<model>.json node scripts/eval.mjs

# Çok belgeli set (32 soru). Çıktı adı sabittir (eval/results/multihop-<model>-current.json):
# iç setin sonucunun üzerine yazar, koşudan önce kenara alın.
SAMPLES_DIR=eval-data/external/pdf EVAL_SET_FILE=eval-data/external/questions-multihop.json \
  READY_TIMEOUT_MIN=240 node scripts/eval.mjs --set multihop
```

Dikkat edilecekler:

- **Hesap kotası.** `verso.document.max-documents-per-account` 200'dür ve külliyat tam 200 belgedir. Kullanılmayan bir belge (Memurlara Yapılacak Giyecek Yardımı Yönetmeliği, 1991) bu yüzden çıkarıldı. Belge eklenecekse kota yükseltilmelidir. `compose.yaml` verso-app'e ortam değişkenini kendiliğinden geçirmez; bir compose override ile `VERSO_DOCUMENT_MAXDOCUMENTSPERACCOUNT: "400"` verilmelidir (Spring relaxed binding).
- **Özetteki iki alan anlamsızdır.** `stoppedByThreshold` ve `modelNotFoundAccuracy`, iç setin `eval/results/retrieval.json` dosyasından okunur ve bu sette anlam taşımaz. Kullanılacak olanlar: `notFoundAccuracy` ve `notFoundOrOwnRefusal`.
- **Her koşu yeniden yükler.** `eval.mjs` sonunda yüklediklerini siler; aynı külliyatla iki koşu iki kez ingest eder.
- **OCR gerekmez.** PDF'ler metin katmanlıdır.
- **PDF'ler yeniden üretilirse** sayfa kırılımları değişebilir. O durumda `node build/pages.mjs` ve `node build/build.mjs build/drafts <çıktı klasörü>` ile sayfalar yeniden çözülür (araçlardaki mutlak yollar bu makineye göredir).

## Yeniden üretme

1. `build/fetchdoc.mjs`: liste dosyasındaki mevzuat kimlikleriyle `getDocumentContent` çağrılır ve HTML kaydedilir. Liste dosyaları: Hacettepe'nin bütün `UY` kayıtları ve `build/national*.txt`. Servis hız sınırı uygular (429); araç bekleyip yeniden dener.
2. `build/repealed.mjs`: aynı klasördeki bir yönetmeliğin "yürürlükten kaldırılmıştır" dediği yönetmelikleri bulur. Çıkarılanlar:
   - Hacettepe ön lisans ve lisans yönetmeliği (2018);
   - Hacettepe yabancı dil hazırlık yönetmeliği (2016);
   - Alkol Bilimsel Uygulama ve Araştırma Merkezi yönetmeliği;
   - yanlışlıkla gelen eski İş Kanunu (1475); yerine 4857 alındı.
3. Edge headless ile her HTML PDF'e basılır (`--print-to-pdf --no-pdf-header-footer`). Ardından `build/pages.mjs` (`pdftotext -enc UTF-8`) sayfa metinlerini çıkarır.
4. `build/build.mjs build/drafts eval-data/external` taslaklardan iki soru dosyasını yazar ve doğrular.

## Şüpheli noktalar

- **Güncellik.** Bakanlık veritabanı mülga metinleri de listeler; aynı klasörde birbirini kaldıranlar ayıklandı, ama her belgenin bugün yürürlükte olduğu tek tek doğrulanmadı. Eski metinler:
  - Yurtiçi ve Yurtdışı Görevlendirmeler (1983);
  - Dikey geçiş (2002);
  - Değişim programı / Farabi (2009);
  - Mediko-Sosyal (1984);
  - konservatuvar lise ve lisans yönetmelikleri;
  - parasız yatılı yönetmeliği (artık var olmayan kurumları anıyor).
- **İç çelişkiler.** Gerçek bir kurumda da olacağı gibi metinler arasında çelişkiler var:
  - Tıp Fakültesi, yaz okulu ve hazırlık yönetmelikleri kaldırılmış 2018 ön lisans/lisans yönetmeliğine atıf yapıyor;
  - yaz okulu md. 9 mülga bir fıkraya atıf yapıyor;
  - Nüfus Etütleri Enstitüsü yönetmeliği müdürü Senato'ya seçtiriyor, 2547 md. 19 ise atamayı rektöre veriyor;
  - Yıllık Ücretli İzin Yönetmeliği md. 6 iznin bölünemeyeceğini söylüyor, 4857 md. 56 bölünmesine izin veriyor.

  Bu noktalara soru yazılmadı.
- **Tarihe bağlı bilgiler.** 4734'teki TL eşikleri eski lirayla yazılı ve her yıl güncellenir; bunlara cevaplanabilir soru yazılmadı. 657'deki 2026 değişiklikleri (md. 56, 104, 127) iki soruda (memur-08, memur-09) kullanıldı; modelin eğitim bilgisi eski metni hatırlayabilir.
- **Kısa beklenen değerler.** Merkez sorularında `expect` çoğu zaman "iki|2" ya da "beş|5" gibi kısadır. Yanlış merkezi kaynak gösteren bir cevap da aynı sayıyı verebilir; ayırt etmeyi asıl `citationHitRate` ölçer. Doğrulayıcıda da kısa sayılar fıkra işaretlerine ("(4)") takılabilir; bu yüzden sayıların çoğu birimiyle yazıldı ("7 iş günü", "%30").
- **Tek doğru belge varsayımı.** Her soru tek bir belgeye bağlandı. Bazı genel sorular başka bir belgede de cevaplanmış olabilir ("senato yılda kaç kez toplanır", "tatbikat sıklığı"); öyle bir cevap atıf isabetinde yanlış sayılır. Bu sistematik olarak taranmadı.
- **Etiket dengesi.** Sorular sayı ağırlıklıdır (305 sorunun 177'si `number`), çünkü mevzuat çoğunlukla süre ve oran kuralıdır.
- **Yazım ve doğrulama.** Sorular üç Claude çalışmasıyla yazıldı: merkezler, cevaplanamazlar ve çok belgeli sorular bir çalışmada; Hacettepe ve YÖK ile ulusal mevzuat ayrı iki çalışmada. Makine doğrulaması yalnızca "beklenen bilgi altın sayfada geçiyor" kontrolüdür; insan gözünden geçmedi. `section` alanlarının bir kısmı metne karşı kontrol edilmedi.
- **Servis.** `bedesten.adalet.gov.tr` belgelendirilmemiş bir servistir; açık kaynak MCP istemcilerinin kullandığı yoldan çağrıldı ve hız sınırı uygular.
