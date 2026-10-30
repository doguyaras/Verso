# ADR-0065: Bulut model çağrılarında Türkçe kişi adı ve adres maskeleme

- **Durum:** Kabul edildi. Uygulama tamam; birim testleri yerelde geçti, etiketli sette ölçüldü (aşağıda). Gerçek bir bulut sağlayıcısıyla canlı deneme yapılmadı.
- **Tarih:** 2026-10-30
- **Karar verenler:** doguyaras
- **Genişlettiği:** ADR-0059 (kişisel veri maskeleme; "Yerel NER sonraki adım" maddesi ve yeniden değerlendirme koşulu), ADR-0056 (model geçidi), ADR-0013 (cloud modu)
- **İlgili eşik (referans Bölüm 24):** etiketli sette tür başına kesinlik ≥ 0,97 ve duyarlılık ≥ 0,90 (`NameAddressEvalTest`); ADR-0059'un yanlış pozitif ve geri dönmeyen yer tutucu eşiği (%2) aynen geçerli.
- **Kanıt:** `docs/evidence/adr-0065-dogrulama.md`

## Bağlam

ADR-0059 bulut çağrısından önce yapısal kimlikleri (TCKN, VKN, IBAN, kart, telefon, e-posta, plaka) yer tutucuyla değiştirdi. Serbest biçimli kişisel veri olduğu gibi gidiyordu: "Ayşe Yılmaz'ın izin talebi", "Sn. Mehmet Öztürk", "Av. Zeynep Kaya", "Kızılay Mah. Atatürk Bulvarı No:45 D:7 Çankaya/Ankara". Kurumlar bulut anahtarını kendisi getiriyor ve kişisel verinin kurulumdan çıkmamasını istiyor.

Kısıtlar:

- Ürün küçük, yalnız CPU'lu bir sunucuda çalışır.
- Çalışma anında ağ yok; hava boşluklu kurulumda da çalışmalı.
- **Kesinlik önce gelir.** Kurum adı ("Hacettepe Üniversitesi", "Sosyal Güvenlik Kurumu"), kanun adı ("4857 sayılı İş Kanunu"), ürün adı ya da sıradan bir kelime maskelenirse model metni anlayamaz ve cevap bozulur. Kaçan bir ad ise ADR-0059'daki gibi riski azaltan bir katmanın eksiğidir; aktarım zaten kişisel veri olmaktan çıkmaz (VKED 5.6).

Bu ADR, ADR-0059 kodunun bağımsız incelemesinde bulunan dört kusuru da kapatır (aşağıda "ADR-0059 düzeltmeleri").

## Araştırma

| Kaynak | Bulgu | Etkisi |
|---|---|---|
| [mkozturk/trnames](https://github.com/mkozturk/trnames) | MIT lisanslı ad/soyad sıklık listeleri (`male_first.csv`, `female_first.csv`, `all_last.csv`). README: 1888–1991 doğumlu 49.611.709 kişiden çıkarıldı. | Sayı, 2016 MERNIS sızıntısının kayıt sayısıyla birebir aynı ([2016 MERNIS scandal](https://en.wikipedia.org/wiki/2016_MERNIS_scandal)): veri bir kişisel veri ihlalinden türetilmiş. Kişisel veriyi koruyan bir üründe **kullanılmaz**, lisans MIT olsa bile. |
| TÜİK "İstatistiklerle Çocuk 2024" ([haber özeti](https://www.yenisafak.com/tuik-acikladi-2024-yilinin-en-populer-isimleri-neler-h-4696961)) | Yalnız en sık birkaç ad yayımlanıyor (Yusuf, Mustafa, Ömer; Zeynep, Elif, Ecrin); indirilebilir tam ad listesi yok. | Tam bir açık resmî liste bulunamadı. Liste elle derlendi; TÜİK özetleri yalnız kapsamı kontrol için. |
| Küçük ve Yazıcı 2009, [Named Entity Recognition Experiments on Turkish Texts](https://avesis.metu.edu.tr/yayin/b0beb0bc-199a-44bb-83cc-a88e4d566450/named-entity-recognition-experiments-on-turkish-texts) | İlk kural tabanlı Türkçe NER: sözlük ve kalıp tabanı. Haber dışı türlerde başarım düşüyor. | Kural + sözlük Türkçede bilinen bir yol. Kurallar alana göre yazılmalı; burada kurumsal İK, satın alma ve BT metni. |
| [Resources for Turkish NLP: a critical survey](https://arxiv.org/pdf/2204.05042), [ACL 2023](https://aclanthology.org/2023.acl-long.768.pdf) | Türkçe NER verisinin çoğu haber (MilliyetNER: 1997–98 Milliyet). Veri e-postayla ya da lisans sözleşmesiyle alınıyor. | Hazır modellerin eğitim verisinin lisansı belirsiz; alan (haber) kurumsal metinden farklı. |
| [akdeniz27/bert-base-turkish-cased-ner](https://huggingface.co/akdeniz27/bert-base-turkish-cased-ner) | MIT, BERTurk tabanlı, ~110 M parametre, PER/ORG/LOC. Genel F1 0,959; tür başına değer yok. Eğitim verisi yukarıdaki haber setinin gözden geçirilmiş hali. | En uygun ONNX adayı. Ama **adres etiketi yok** ve alan haber. FP32 ağırlık ~440 MB (110 M × 4 bayt), int8 ~110 MB. |
| [savasy/bert-base-turkish-ner-cased](https://huggingface.co/savasy/bert-base-turkish-ner-cased) | Model kartında lisans alanı yok, ONNX dosyası yok. | Dağıtılamaz (lisans belirsiz). |
| [Maven Central: onnxruntime 1.20.0](https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.20.0/) | Java CPU jar'ı 92.987.839 bayt (üç platformun yerel kütüphaneleriyle). | ONNX seçeneği imaja ~93 MB çalışma zamanı + ~110–440 MB model ekler. |
| [ONNX Runtime ile BERT'i Intel CPU'da hızlandırma](https://opensource.microsoft.com/blog/2021/03/01/optimizing-bert-model-for-intel-cpu-cores-using-onnx-runtime-default-execution-provider/) | int8 nicemleme ile 12 katmanlı BERT'te "2,9 kata kadar" hızlanma. Mutlak gecikme verilmiyor; makineye bağlı. | Gecikme ölçülmeden karar verilemez. Bir RAG istemi (3–4 bin jeton) 512 jetonluk 6–8 pencere demek; her bulut çağrısına yüzlerce ms CPU eklenir (tahmin, ölçülmedi). |
| [Presidio: adding recognizers](https://microsoft.github.io/presidio/analyzer/adding_recognizers), [deny list](https://microsoft.github.io/presidio/tutorial/01_deny_list) | Endüstri kalıbı: düzenli ifade + bağlam sözcükleri + liste tanıyıcıları. NER modeli ayrı bir tanıyıcı. Belgeler listelerin her varlığı bulamadığını da söylüyor. | Aynı katmanlı yapı: önce kural + liste, model sonradan ikinci bir tanıyıcı olarak eklenebilir. |

Ollama üzerinden NER: Ollama token sınıflandırma (BERT NER) modeli çalıştırmaz; yalnız üretken ve embedding modelleri çalıştırır. Bu yolla NER, üretken bir LLM'e "adları çıkar" demek olur. Sonuç deterministik değildir, metinde olmayan değer uydurabilir ve her bulut çağrısının önüne yerel bir üretim çağrısı (saniyeler) ekler. Cloud modda yerel sohbet modeli hiç olmayabilir; Ollama yalnız embedding taşıyor olabilir.

## Seçenekler

| Seçenek | Kesinlik / duyarlılık | Maliyet (küçük CPU sunucu) | Lisans | Karar |
|---|---|---|---|---|
| **Kural + Verso'nun kendi sözlüğü** (ad, belirsiz ad, soyad, 81 il, hariç ifadeler; unvanlar; Bey/Hanım; kesme işaretinden sonra ek; adres dilbilgisi) | Etiketli sette AD 1,000 / 0,948, ADRES 1,000 / 1,000. Sözlükte olmayan soyad ve kelime olan adlar kaçar. | 10 KB istem başına ~5 ms (ölçüldü), ek bellek birkaç yüz KB, imaja ~10 KB. | Verso'nun kendisi | **Seçildi** |
| ONNX NER (BERTurk) JVM içinde, ONNX Runtime Java | Haber alanında F1 ~0,96; kurumsal metinde ölçülmedi. Adres etiketi yok. | +93 MB çalışma zamanı, +110–440 MB model; her çağrıda yüzlerce ms CPU (tahmin). Yerel kütüphane yeni bir tedarik zinciri yüzeyi. | Model MIT; eğitim verisinin lisansı belirsiz | Şimdilik hayır. İsteğe bağlı ikinci katman olarak ileride. |
| Ollama'da üretken LLM ile çıkarım | Deterministik değil, uydurma riski | Çağrı başına saniyeler; GB'larca model | Modele göre | Hayır |
| trnames gibi hazır büyük ad listeleri | Duyarlılık artar | Düşük | MIT, ama veri bir sızıntıdan türemiş | Hayır (köken) |

## Karar

### 1. Tespit (`TurkishNames`, `TurkishAddresses`, `TurkishLexicon`)

Saf ve durumsuzdur. ADR-0059'un `TurkishPii.find` akışına iki tür eklenir: `AD` ve `ADRES`.

**Kişi adı** şu biçimlerden biridir:

- Bir–üç ad ve en çok iki soyad: "Ayşe Yılmaz", "Mehmet Ali Öztürk", "AYŞE YILMAZ".
  - Sözlükte olmayan bir soyad yalnız ilk soyad yerinde ve yalnız kelime olmayan bir addan sonra kabul edilir. İyelik ya da çoğul ekiyle biten kelimeler ("Dönemi", "Kuvvetleri") soyad sayılmaz.
- Küçük harfle yalnız "ayşe yılmaz" biçimi: kelime olmayan bir ad ve sözlükteki bir soyad.
- Kelime olmayan bir ad tek başına: "Mehmet'e ilet".
- Bir unvan ve bir–üç büyük harfli kelime: "Sn. Mehmet Öztürk", "Prof. Dr. Ali Rıza Tekin", "Dr. Kaya". Unvanlar: Sn., Sayın, Bay, Bayan, Av., Dr., Doç., Prof., Uzm., Op., Müh., Ecz., SMMM, YMM, Müdür ve benzerleri. Unvan eşleşmenin dışında kalır.
- Bey, Hanım, Beyefendi, Hanımefendi ya da Hoca'dan önceki bir–üç ad kelimesi: "Ayşe Hanım", "Yılmaz Bey'e".

Ad **sayılmayanlar**:

- Kelime, yer ya da ay da olan bir ad (`ad-belirsiz.txt`: Deniz, Umut, Bahar, Metin, Emin, Güven, Veli, Levent, Fatih …), yanında sözlükteki bir soyad ya da unvan yoksa.
- Aynı büyük harfli dizide (araya "ve", "&", "-" girebilir) bir kurum sözcüğüyle devam eden ad: üniversite, hastane, kurum, bakanlık, holding, A.Ş., Ltd., Şti., inşaat, lojistik, kanun, yönetmelik, cadde, mahalle ve diğerleri. Örnekler: "Kadir Has Üniversitesi", "Ahmet Yılmaz İnşaat Ltd. Şti.", "Dr. Sadi Konuk Eğitim ve Araştırma Hastanesi".
- `ad-haric.txt` ifadeleri: yer, kurum ve kanun adı olarak geçen tarihî ve kamusal kişiler ("Mustafa Kemal Atatürk", "Fatih Sultan Mehmet", "Mehmet Akif Ersoy") ve "Demir Çelik", "Deniz Kuvvetleri" gibi kalıplar.

**Adres**, boşluk, virgül ya da satır sonuyla birleşen parçalardan oluşan bir zincirdir. Parçalar:

- Yer: adıyla birlikte Mah./Mahallesi, Cad./Caddesi, Sok./Sokak, Bulvarı, Apt./Apartmanı, Sitesi, Plaza, İş Merkezi.
- Kapı numarası (`No:12`, `No: 12/3`), kat (`Kat:3`, `K:2`, `3. Kat`), daire (`D:5`, `Daire 4`), blok.
- Posta kodu: beş hane, ilk ikisi 01–81.
- İlçe ve il: `Çankaya/Ankara`, `Pendik İstanbul`, tek başına il.

Bir zincir şu durumlarda adrestir:

- bir yer ve bir ayrıntı ("Atatürk Cad. No:12");
- iki yer ("Kızılay Mah. Atatürk Bulvarı");
- yer olmadan iki ayrıntı ve bir ilçe ya da il ("No:12 D:5 Çankaya/Ankara").

Tek başına bir cadde ya da şehir ("Atatürk Caddesi'nde yol çalışması", "Ankara ofisi") bir yerdir, kişinin adresi değildir; maskelenmez.

Ortak kurallar:

- Kesme işaretinden sonraki ek ("Yılmaz'ın", "Ankara'da") eşleşmeye girmez.
- Değerin anahtarı Türkçe küçük harftir; adreste noktalama ve boşluk da sadeleşir. Böylece "Ayşe Yılmaz", "Ayşe Yılmaz'ın" ve "AYŞE YILMAZ" tek yer tutucu alır.
- Çakışmada uzun eşleşme kazanır. Adresin içindeki "Fevzi Çakmak Cad." ayrıca bir ad olmaz.

### 2. Sözlükler (`qa-core/src/main/resources/pii/tr/`)

| Dosya | İçerik | Satır |
|---|---|---|
| `ad.txt` | Kelime olmayan Türkçe adlar | 401 |
| `ad-belirsiz.txt` | Kelime, yer ya da ay da olan adlar | 149 |
| `soyad.txt` | Sık soyadlar (`-oğlu`, `-oğulları` sonlu soyadlar sona bakılarak tanınır) | 265 |
| `il.txt` | 81 il | 81 |
| `ad-haric.txt` | Hiç ad sayılmayan ifadeler | 28 |

**Lisans:** listeler Verso için elle derlendi ve projenin kendi lisansıyla dağıtılır. Hiçbir üçüncü taraf dosyası kopyalanmadı. Ad ve il adları olgudur; seçim Verso'nundur. `docs/versions.md`'de satırı vardır. Bir liste eksik ya da boşsa sınıf yüklenmez ve süzgeç gönderimden önce durur (ADR-0059 kapalı başarısızlık).

### 3. Yer tutucu ve geri koyma (`Pseudonymiser`, `TurkishSuffix`)

- Türler `[AD_n]` ve `[ADRES_n]`; numaralar türe göre ayrı. Model "Sn. [AD_1]" ve "[AD_1]'ın" görür.
- Model yer tutucuyu okunuşuna göre çekimleyebilir ("[AD_1]'in", çünkü "bir"). Geri koyarken ad ve adresten sonraki ek, değerin son kelimesine uydurulur (`TurkishSuffix`):
  - büyük ve küçük ünlü uyumu;
  - ünlüden sonra n/y kaynaştırması, ünsüzden sonra düşmesi;
  - sert ünsüzden sonra d/t, c/ç;
  - "-ki" değişmez;
  - "Kemal", "Hilal" gibi ince ek alan alıntı adlar.
  
  Doğru ek değişmez. Değer harfle bitmiyorsa (posta kodu) ya da ünlüsüzse (kısaltma) ek olduğu gibi kalır.
- **Akış:** ADR-0059'un parça tamponu genişler. Akışın sonundaki tam bir `[AD_n]`/`[ADRES_n]` ve kesme işaretiyle başlayan ek (en çok 16 harf), ek bitene kadar tutulur. Ek ancak bütün görülünce uydurulur. Testler her iki bölme noktasını dener.

### 4. Kapsam ve yönetici ayarı

Mevcut `pii_masking` anahtarı kullanılır; **ayrı bir anahtar ve migration yok**. Gerekçeler:

- Ad ve adres, TCKN ile aynı türden kişisel veridir.
- Yöneticinin kişisel veriyi kısmen göndermek isteyeceği bir senaryo yok. Kısmi açıklık, "maskeleme açık" yazan bir anahtarın verdiği güvenceyi bulanıklaştırır.
- Arayüz sadeliği: tek anahtar, tek sayı satırı.
- Kesinlik sorunu çıkarsa doğru çare anahtarı kapatmak değil, sözlüğü düzeltmektir: `ad-belirsiz.txt` ya da `ad-haric.txt`'e bir satır.

Panelde anahtarın metni "(… plaka, isim, adres)" olur. Maliyet kartı `AD` ve `ADRES` sayılarını "isim" ve "adres" diye gösterir.

### 5. Sayım

`qa.model_usage.masked_counts` ve `verso.model.gateway.masked{type}` iki yeni tür adını taşır: `AD`, `ADRES`. Çağrıdaki farklı değer sayısıdır; değer yoktur. Kolon zaten serbest anahtarlı `jsonb` olduğu ve `ModelGatewayRepository.MASK_TYPE` (`[A-Z]{1,16}`) iki adı kabul ettiği için şema değişmez.

### 6. ADR-0059 düzeltmeleri (bağımsız incelemenin doğruladığı kusurlar)

1. **YÜKSEK: cloud modda yerleşik model maskelenmiyordu.**
   - `Candidate.external()` bağlantısı olmayan yerleşik model için `false` dönüyordu. Oysa `verso.ai.mode=cloud` iken yerleşik model Anthropic ya da OpenAI'dır (`verso.yml`, ADR-0013).
   - Rota tanımsızken ya da veritabanı okunamazken geçit yerleşik modele düşer. Varsayılan bir cloud kurulumu, anahtar "açık" görünürken ham TCKN ve IBAN gönderiyor ve kayıtta `filter_policy=NONE` yazıyordu.
   - Düzeltme: `Candidate`'e `external` alanı eklendi. `ModelGateway.builtIn()` bu alanı cloud modda `true` yapar. Yerleşik model artık süzgeçten geçer, veri türlerini (`sent_categories`) ve `external=true` kaydını yazar.
   - ADR-0059 karar 4'teki "yerleşik model hiç süzgeçten geçmez" cümlesi yalnız local mod için doğrudur ve öyle düzeltildi.
2. **YÜKSEK: yazım varyantları tespiti atlıyordu.** Tespit artık normalleştirilmiş bir gölge kopyada çalışır (`TextShadow`):
   - `\p{Cf}` (sıfır genişlikli boşluk, yumuşak tire, BOM, yön işaretleri) atılır;
   - her `\p{Nd}` rakamı ASCII'ye çevrilir;
   - geri kalan NFKC'lenir (bölünmez boşluk boşluk, tam genişlikli `＠` `@` olur).
   
   Eşleşmeler orijinal metindeki aralığa geri eşlenir. Ayraçlar da genişledi:
   - TCKN 3-3-3-2 gruplu yazılabilir (`100 000 001 46`, `100.000.001.46`);
   - IBAN'da herhangi iki hane arasında en çok üç ayraç olabilir (boşluk, satır sonu, nokta, tire); sözcüğe yapışıklık için arka bakış `(?<!\p{N})`;
   - kartta nokta ayraç olabilir.
3. **DÜŞÜK: yer tutucu taşması.** İstemde `[TCKN_999999]` geçince sıradaki numara `[TCKN_1000000]` oluyordu. Bu numara hiç geri konmuyor ve akış tamponunu aşıyordu. Artık istemdeki yer tutucular birebir saklanır ve numaralandırma onları atlar. Numaralar istemdeki yer tutucu sayısıyla sınırlı kalır.
4. **DÜŞÜK: panelde başarısız kayıt anahtarı geri almıyordu.** Kayıt başarısız olursa maskeleme ve model seçimi kutuları eski durumuna döner (`scripts/providers-panel.test.js`).

### Güvenceler

| Güvence | Nasıl korunur | Doğrulama |
|---|---|---|
| Maskeleme açıkken dış çağrıda ve cloud modda yerleşik modelde ham ad, adres ve kimlik yok | Ad ve adres aynı süzgeçte; yerleşik model cloud modda dış sayılır | `ModelGatewayTest` (yerleşik model, cloud: istemde ad, adres ve TCKN yok; cevap değerlerle; kayıt `external`, `AD`/`ADRES`/`TCKN` sayıları) |
| Kurum, kanun, ürün ve yer adı maskelenmez | Belirsiz ad listesi, kurum sözcüğü, hariç ifadeler, iyelik eki kuralı | `NameAddressEvalTest` (kesinlik ≥ 0,97), `NameAddressDetectionTest` |
| Aynı kişi ya da adres tek yer tutucu alır, ek geri gelir ve uyar | Küçük harf anahtar, ek eşleşme dışında, `TurkishSuffix` | `PseudonymiserTest`, `TurkishSuffixTest` |
| Akışta bölünen yer tutucu ve ek doğru geri konur | Ad ve adres yer tutucusu ekiyle birlikte tutulur | `PseudonymiserTest` (her bölme noktası, jeton jeton) |
| Görünmez karakter ve başka rakam yazımları tespiti atlatmaz | `TextShadow` | `TurkishPiiTest`, `NameAddressDetectionTest` |
| Uzun ya da kötü niyetli girdi süzgeci kilitlemez | Sahiplenici nicelik, sınırlı zincirler, doğrusal tarama | `NameAddressDetectionTest.longAdversarialInput_staysLinear` (~250 KB < 5 s; ölçülen 0,18 s, düzeltmeden önce 3,1 s) |

## Ölçüm

`NameAddressEvalTest`, `src/test/resources/pii/ad-adres-etiketli.txt` üzerinde çalışır. Set 192 cümledir:

- İK, satın alma ve BT bağlamı; sentetik kişi ve adresler;
- 97 ad ve 29 adres etiketi;
- etiketsiz 81 zor negatif: kurum ve kanun adları, yer olarak geçen iller, ürün adları, cümle başı büyük harf, kelime olan adlar;
- e-fatura örneği yok.

Bulgu, türü ve aralığı etiketle **birebir** aynıysa doğru sayılır.

| Tür | Doğru | Yanlış pozitif | Kaçan | Kesinlik | Duyarlılık |
|---|---|---|---|---|---|
| AD | 92 | 0 | 5 | 1,000 | 0,948 |
| ADRES | 29 | 0 | 0 | 1,000 | 1,000 |

- Kaçanlar bilinçli sınırdır: kelime olan adlar tek başına ("Deniz bugün izinli", "Umut", "Bahar'a") ve sözlükte olmayan, kelime gibi biten soyadlar ("Karabıyıklı", "Ersoylu").
- İlk koşuda bir yanlış pozitif ve bir kaçan vardı: "Dr. Kemal Aydın"da il adı olan soyad, unvandan sonra ad kelimesi sayılmıyordu. Düzeltildi: sözlükteki ad ve soyad, il adı olsa da ad kelimesidir.
- **Uyarı:** set kurallarla birlikte yazıldı. 1,000 kesinlik görülmemiş metinde beklenmez. Gerçek kesinliği ADR-0059'un aylık örneklemesi ölçer.
- **Eşikler:**
  - Kesinlik **0,97**: bozulan cevap geri alınamaz, kaçan ad ise süzgecin ötesindeki tek risk değil.
  - Duyarlılık **0,90**: ölçülen değerin altında, payı olan bir taban. Sözlüğü ya da dilbilgisini bozan bir değişiklik bu tabanı kırar.
  - Set küçük olduğu için tek bir cümle oranı yaklaşık 0,01 oynatır. Eşikler bu yüzden ölçülenin hemen altında değil.
- **Gecikme:** 10,4 KB'lık metinde `TurkishPii.find` çağrı başına ~5 ms (ısınmış JVM, geliştirici makinesi; 200 tekrarın ortalaması).

## Sonuçlar

- **Olumlu:**
  - Bulut modunda kişi adı ve posta adresi de kurulumdan çıkmaz; kullanıcı cevabı değerlerle ve doğru eklerle görür.
  - Ek model, ağ, bellek ya da yeni bağımlılık yok.
  - Cloud modun varsayılan yolu (yerleşik model) artık maskelenir.
  - Yazım varyantları ve görünmez karakterler tespiti atlatmaz.
- **Olumsuz / kabul edilen risk:**
  - **Sözlükte olmayan soyadlar ve kelime olan adlar tek başına kaçar.** Unvan ya da Bey/Hanım varsa yakalanır. Takma adlandırma aktarımı kişisel veri olmaktan çıkarmaz; riski azaltır (VKED 5.6).
  - Kişi adını taşıyan kurum, şirket ve hastane adları maskelenmez ("Ahmet Yılmaz İnşaat Ltd. Şti."). Kurum adı kişisel veri değildir; şahıs şirketinde sınır bulanıktır.
  - Aynı büyük harfli dizide kurum adıyla bitişik yazılan ad ("Ayşe Yılmaz Hacettepe Üniversitesi'nde", virgülsüz) kesinlik uğruna maskelenmez.
  - Tarihî ve kamusal kişiler `ad-haric.txt` ile sınırlı. Listede olmayan bir kamusal kişi maskelenir; cevapta geri gelir, yalnız modelin bağlamı zayıflar.
  - Adresin başında cümle başı büyük harfle yazılmış bir kelime ("Tebligat Bağdat Cad." gibi, liste dışıysa) adrese katılabilir. Değer geri geldiği için kullanıcı fark etmez.
  - Ek uydurma kural tabanlıdır. Ünlü uyumuna uymayan nadir adlarda ek yanlış uyabilir; anlam bozulmaz.
  - Doğum tarihi ve diğer serbest biçimli veriler hâlâ maskelenmez.
- **Etkilenen dosyalar / yüzeyler:**
  - `qa-core` `service/gateway/`: yeni `TurkishNames`, `TurkishAddresses`, `TurkishLexicon`, `TurkishSuffix`, `TextShadow`; değişen `TurkishPii`, `Pseudonymiser`, `RoutedChatModel` (`Candidate.external`), `ModelGateway` (`builtIn()`).
  - `qa-core` `resources/pii/tr/*.txt`.
  - Panel `providers.js`, `screens/providers.js`.
  - Kayıt ve metrikte iki yeni tür adı.
  - Yeni migration, config anahtarı, ortam değişkeni, hata kodu, uç ya da uzak çağrı **yok**.
  - API sözleşmesi değişmez: `masked` zaten tür adından sayıya bir map'tir; `ModelUsageResponse` javadoc'u yeni adları sayar. Davranış değişikliği: `GET /v1/models` cloud modda yerleşik model seçeneğini artık `external: true` döner (doğru olan budur); kullanım satırında yerleşik modelin cloud çağrısı `external` ve veri türleriyle yazılır.
- **Geri alma:** panelden maskelemeyi kapatmak (ADR-0059). Kod geri alınırsa eski imaj `AD`/`ADRES` sayılarını okur ve toplar; panel bilinmeyen türü ham adıyla gösterir.

## Yeniden değerlendirme koşulu

- Altın sette ya da müşteri örneklemesinde ad veya adres kesinliği 0,97'nin altına inerse.
- Duyarlılık açığı ölçülürse; özellikle sözlük dışı soyadlar ve kelime olan adlar. O zaman isteğe bağlı ONNX katmanı yeniden tartılır: BERTurk NER int8, yalnız "unvan ya da Bey/Hanım yok ama büyük harfli dizi var" adaylarında çalışır, kesinliği kural katmanı belirler.
- Kurumlardan bölge, il ya da sektöre özgü kurum sözcüğü ve ad listesi istekleri gelirse: kurulum başına liste.
- Geçit araç çağrısı göndermeye başlarsa (ADR-0059).
