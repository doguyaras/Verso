# Panel v3 doğrulama kanıtı (ADR-0017)

- **Tarih:** 2026-10-23
- **Dal:** `feature/doguyaras-panel-v3`
- **İstek:** kullanıcı mevcut menü, giriş ve ekran tasarımlarını beğenmedi. Derin bir araştırma, kullanıcı dostu ve satılabilir bir ürün istedi.

## Araştırma

Beş paralel araştırma ve bir sentez raporu yapıldı:
- navigasyon ve dashboard;
- giriş ve bileşenler;
- renk, tema ve tipografi;
- AI soru-cevap ve belge ekranları;
- pazar ve fiyatlandırma.

Kaynaklar: NN/g, WCAG 2.2, Material, Radix, Primer, Linear, Vercel Geist, NotebookLM, Glean, Keycloak belgeleri, satıcı fiyat sayfaları, KVKK ve BDDK.

Rapor ve notlar çalışma dosyası olarak tutuldu (`reports/`, `research_notes/`; `.git/info/exclude`), repoya girmedi. Kararlar ve gerekçeleri ADR-0017'de.

## Testler

| Kontrol | Komut | Sonuç |
|---|---|---|
| Build ve Java testleri | `./mvnw -B -ntp verify` | **PASS**: BUILD SUCCESS, 406 Java testi. `CitationExtractorTest` 13 test (9'u `excerpt` için yeni), `QuestionApiTest` 19 test (`ask_whenTheCitedPassageIsLong_returnsABoundedExcerpt` yeni) |
| Panel testleri | `node --test scripts/panel.test.mjs` | **PASS**: 28/28. Yeni testler: Türkçe katlama ve bulanık arama (sıralama dahil), kaynak gruplama, kaynaklarla kopyalama, süre biçimi, `fill()`'in boş değerleri atlaması, ekranlarda `replaceChildren`'a olası `null` geçmemesi, giriş temasının yapısı (üst tema, stil sırası, script yok, her mesaj anahtarı iki dilde), realm `loginTheme`/`defaultLocale`, tema bağlantısının salt okunur olması, `ui_locales=tr` |
| Mutasyon (panel, elle) | Her seferinde tek değişiklik, ardından `node --test scripts/panel.test.mjs` | **8/8 yakalandı**: P1 tablonun altına `null` yazan `replaceChildren`, P2 `ui_locales` kaldırma, P3 temaya script ekleme, P4 noktasız ı katlamasını kaldırma, P5 önek önceliğini kaldırma, P6 realm `loginTheme`'i silme, P7 bir mesaj anahtarını silme, P8 `fill()`'in `null` süzgecini kaldırma |
| Review'lar | `verso-security-review`, `verso-api-contract-review`, `verso-llm-review`, `verso-test-writer` | Security **APPROVE** (bulgu yok); api-contract **APPROVE WITH NON-BLOCKING COMMENTS** (ek alan, geriye uyumlu; emojide JS uzunluğu 280'i aşabilir, yanıt ~1,1 KB büyür); llm **APPROVE**; test-writer **PASS** |

## Canlı kontrol (çalışan yığın, uygulama içi tarayıcı)

1. **Panelin giriş ekranı:** iki sütun; marka paneli, üç güven maddesi ve "Kurum hesabıyla giriş yap" düğmesi.
2. **Keycloak giriş sayfası:** Verso teması, Türkçe (tarayıcı İngilizce olsa da `ui_locales=tr` ile), Verso renginde düğme. İlk denemede (o zamanki "Petrol" paletiyle) düğme mavi kaldı. İki önbellek bulundu:
   - **Keycloak gzip önbelleği:** `data/tmp/kc-gzip-cache` yeniden başlatmada silinmiyor ve sıkıştırma isteyen istemciye eski dosyayı veriyor. `curl` sıkıştırmasız istediği için yeni dosyayı görmüştü. Container yeniden oluşturulunca düzeldi.
   - **Tarayıcı önbelleği:** kaynaklar 30 gün önbellekleniyor.
   - Her ikisi de ADR-0017 ve README'ye yazıldı.
3. **Genel bakış:**
   - İlk kullanım listesi doğru ilerledi: 0/3 → belge yüklenince ve soru sorulunca 2/3.
   - Sayılar 7 belge ve 17 sayfa/bölüm gösterdi.
4. **Belgeler:**
   - Yedi örnek belge (6 PDF, 1 TXT) panelin kendi sürükle-bırak yolundan yüklendi: dosya başına ilerleme, sonra "Sırada", ardından "Hazır".
   - Canlıda bulunan hata: tablonun altında "null" yazıyordu (`replaceChildren(null)`). `fill()` ile düzeltildi, testle sabitlendi (P1).
5. **Soru sor:**
   - **Bekleme:** 10 sn'den sonra süre ve "yerel modelde … 20–60 sn" beklentisi görünüyor; durdurma düğmesi var.
   - **İlk soru:** soğuk açılışta 68 sn sürdü (model GPU'ya yüklendi).
   - **Cevap:** "Kaynaklı cevap"; `izin-yonetmeligi.pdf, sayfa 1` kartı ve `[1]` üzerinde alıntı önizlemesi görüldü.
   - **Modelin yanlış okuması:** model "yirmi iş günü" dedi (belgede on dört gün; README'deki bilinen hata). Alıntı pasajın başı olduğu için ilgili cümleyi göstermedi; ADR-0017'de "alıntı penceresi" sonraki iş olarak yazıldı.
   - **Önizleme taşması:** sayfanın üstündeki atıfta önizleme pencerenin dışına taşıyordu; yer yoksa aşağı açılacak şekilde düzeltildi.
   - **"Şirketin borsa kodu nedir?":** "Belgelerde bulunamadı" görünümü "Soruyu düzenle" ve "Belge yükle" eylemleriyle geldi; 2,3 sn.
6. **Ctrl+K paleti:** "izin" yazınca belge bulundu; Enter Belgeler ekranında ayrıntı panelini açtı.
7. **Tema ve ekran boyutu:**
   - Koyu ve açık tema denendi.
   - Mobil (375 px) görünümde alt sekme çubuğu ve tek sütun düzen çalıştı; soru kutusundaki sayaç tek satıra alındı.
   - Sistem ekranı koruma katmanlarını gösterdi.

## Palet: Clair obscur (aynı gün, kullanıcı seçimi)

- Kullanıcı ilk paleti beğenmedi; 13 farklı palet, 14 koyu gri palet ve "kül" tonunun 10 varyasyonu panel taslakları üzerinde gösterildi. Kullanıcı açık kül tabanlı "Clair obscur" varyasyonunu seçti.
- Kontrast çiftleri betikle ölçüldü (tablo ADR-0017'de). İlk denemede üç çift sınırda kaldı: açık temada yer tutucu 4,45; koyu temada form kenarlığı 2,62 ve yer tutucu 3,57. Bunlar `#666b72`, `#767b83` ve `#959aa1`'e çekildi (5,0 / 3,1 / 4,7).
- Giriş temasının CSS'i `verso-clair-obscur.css` adıyla dağıtıldı: tarayıcı önbelleğindeki eski dosya kullanılmasın diye. Test, dosyanın bir palet adı taşımasını ve var olmasını sınar.
- Canlı kontrol:
  - Panel ve Keycloak giriş ekranları açık ve koyu temada denendi.
  - Genel bakış açık temada denendi.
  - Soru ekranında altın atıf rozeti ve alıntı önizlemesi açık ve koyu temada denendi. Bu sefer alıntı ilgili cümleyi içeriyordu: "telefon veya erişim kartı kaybolduğunda…".
- **Bulunan ürün sorunu (bu değişiklikten bağımsız):** Ollama 30 dk boşta kaldıktan sonra (`OLLAMA_KEEP_ALIVE`) ilk soruda embedding modelinin yüklenmesi 10 sn'lik zaman aşımını geçti. Kullanıcı 503 10030 "Arama modeli şu an yanıt vermiyor" aldı; "Tekrar dene" ile ikinci deneme cevaplandı. Ayrı bir iş olarak ele alınmalı.

## Bilinen sınırlar

ADR-0017'nin "Olumsuz / kabul edilen risk" bölümünde ayrıntılı:
- **Alıntı:** pasajın başını gösterir, cevabın dayandığı cümleyi değil.
- **"Durdur":** yalnız beklemeyi bırakır; sunucudaki model çağrısı kendi süresinde biter.
- **Şablon bakımı:** `template.ftl`, Keycloak sürüm yükseltmelerinde üst temayla karşılaştırılmalıdır.
- **Realm yeniden içe aktarımı:** demo kullanıcılarına yeni kimlik verir; eski belgeler önceki kimlikte kalır.

## CI

PR açıldıktan sonra eklenecek.
