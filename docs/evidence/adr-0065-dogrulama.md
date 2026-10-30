# ADR-0065 doğrulaması: kişi adı ve adres maskeleme (ve ADR-0059 düzeltmeleri)

Tarih: 2026-10-30. Dal: `feature/doguyaras-ad-adres-maskeleme` (taban `origin/develop` `fbb995f`). Docker başka bir işte kullanıldığı için **yerelde konteyner başlatılmadı**; Testcontainers isteyen testler (`ModelGatewayApiTest`, `AnthropicCloudModeTest`, `OpenAiCloudModeTest`) GitHub CI'da koşar. JDK 25.0.4.1, Maven `-T 1`.

## Yerelde koşulanlar

| Komut | Sonuç |
|---|---|
| `./mvnw -B -ntp -T 1 -o -pl services/qa/qa-core test` | BUILD SUCCESS; qa-core 553 test, 0 hata |
| `./mvnw -B -ntp -T 1 -o -pl verso-app -am test -Dtest=ArchitectureRulesTest,ConfigDriftTest,ErrorCodeUniquenessTest,ModuleStructureTest,ModuleConsistencyTest,MigrationConventionsTest,TransactionBoundaryRulesTest,*ContractTest` | BUILD SUCCESS; verso-app 18 test, api modüllerinin sözleşme testleri yeşil |
| `node --test scripts/providers-panel.test.js scripts/uyum-paketi.test.js` | 21 test, hepsi geçti |

Migration, config anahtarı ya da ortam değişkeni değişmedi; `flyway-immutability` ve `config-lint` gerekmedi.

## Yeni ve değişen testler

| Sınıf | Sayı | Kanıtladığı |
|---|---|---|
| `NameAddressEvalTest` | 2 | Etiketli set ≥ 150 cümle, ≥ 50 etiketsiz zor negatif, ≥ 25 adres; tür başına kesinlik ≥ 0,97, duyarlılık ≥ 0,90 |
| `NameAddressDetectionTest` | 18 | Sözlüklerin yüklenmesi; çekimli ve büyük harfli yazımların tek anahtarı; unvan ve Bey/Hanım'ın eşleşme dışında kalması; belirsiz adlar; kurum, kanun ve hariç ifadeler; küçük harf kuralı; görünmez karakter; adres zinciri (satır sonu, posta kodu, küçük harf); tek başına yer ya da numaranın adres sayılmaması; ~250 KB kötü niyetli girdi < 5 s (ölçülen 0,18 s) |
| `TurkishSuffixTest` | 33 | Ünlü uyumu, kaynaştırma, ünsüz benzeşmesi, "-ki", alıntı adlar, büyük harf; yargılanamayan ekin değişmemesi |
| `TurkishPiiTest` | 52 → 62 | ADR-0059 düzeltmesi 2: sıfır genişlikli karakterli, tam genişlikli, Arapça-Hint ve Farsça rakamlı TCKN; 3-3-3-2 gruplu TCKN (boşluk, nokta, tire) ve daha uzun grubun reddi; IBAN'da NBSP, tire, çift boşluk, satır sonu, ortadan bölünme, nokta; sözcüğe yapışık IBAN; NBSP'li telefon; tam genişlikli `＠`; noktalı ve yumuşak tireli kart; orijinal metne geri eşlenen aralık |
| `PseudonymiserTest` | 11 → 16 | Ad ve adres yer tutucusu, eklerde ve büyük harfte aynı yer tutucu, geri koyma; modelin yanlış ekinin uydurulması; akışta her iki bölme noktası ve jeton jeton (yarım yer tutucu ya da uydurulmamış ek yayılmaz); ADR-0059 düzeltmesi 3: `[TCKN_999999]` numaraları taşırmaz |
| `ModelGatewayTest` | 8 → 10 | ADR-0059 düzeltmesi 1: cloud modda yerleşik model dış sayılır, isteminde ad, adres ve TCKN yok, cevap değerlerle ve doğru ekle döner, kayıt `external`, veri türleri ve `AD`/`ADRES`/`TCKN` sayıları; local modda yerleşik model dış değil |
| `RoutedChatModelTest` | 12 → 13 | Cloud yerleşik modelin akışı süzgeçten geçer; parçalara bölünmüş ad yer tutucusu ve eki doğru geri gelir |
| `scripts/providers-panel.test.js` | güncel | ADR-0059 düzeltmesi 4: kayıt başarısızsa maskeleme ve model seçimi kutuları geri döner; panel metni ve "isim"/"adres" sayı etiketleri |

## Ölçüm: etiketli set

`services/qa/qa-core/src/test/resources/pii/ad-adres-etiketli.txt`: 192 cümle (İK, satın alma, BT), 97 ad, 29 adres, 81 etiketsiz zor negatif. Ölçüt birebir aralık eşleşmesidir.

| Tür | Doğru | Yanlış pozitif | Kaçan | Kesinlik | Duyarlılık |
|---|---|---|---|---|---|
| AD | 92 | 0 | 5 | 1,000 | 0,948 |
| ADRES | 29 | 0 | 0 | 1,000 | 1,000 |

Kaçanlar: "Deniz", "Umut", "Bahar'a" (kelime olan adlar tek başına), "Ayşe Karabıyıklı", "Tekin Ersoylu" (sözlükte olmayan, kelime gibi biten soyadlar). İlk koşuda "Dr. Kemal Aydın" yanlış aralıkla bulunuyordu (il adı olan soyad); düzeltildi.

## Ek denetimler

- **Sistem istemleri maskelenmiyor.** `qa-core/src/main/java` altındaki 1.499 dize ve metin bloğu (sistem istemleri dahil) geçici bir test ile `TurkishPii.find`'dan geçirildi. Ad ya da adres bulgusu yalnız iki yerde çıktı; ikisi de yeni sınıfların javadoc örnekleri ("Ali Kurtuluş", "Atatürk Cad. No:12 Çankaya/Ankara"). Geçici test commit edilmedi.
- **Gecikme.** Etiketli setin birleştirilmiş metni (10.413 karakter) üzerinde, 200 ısınma turundan sonra 200 tekrarın ortalaması: `TurkishPii.find` 5,1 ms, `Pseudonymiser.mask` 4,9 ms. Geliştirici makinesi; ölçüm kodu commit edilmedi.
- **Doğrusallık.** Ölçüm, adres taramasında 20.000 harflik tek bir kelimede karesel geri izleme gösterdi: 2,1 s. Desen sahiplenici nicelik ve kelime sınırıyla düzeltildi: 0 ms. Örtüşme denetimi `TreeMap` ile O(n log n) oldu.

## CI'da koşacaklar (Testcontainers)

- `ModelGatewayApiTest`: dış çağrı satırı `filter_policy = 'PII_PSEUDONYMISE'` ve `masked_counts = '{}'`. Testin sorusunda ad ya da adres yok.
- `AnthropicCloudModeTest`, `OpenAiCloudModeTest`: cloud modda yerleşik model artık süzgeçten geçer. Giden istekte aranan metinler ("Soru: topic-leave Yillik izin kac gun?", `[[BELGE 1]] (sayfa 1)`) ad ya da adres içermez; değişmeden kalmaları beklenir. **CI sonucu bu dosyaya eklenmedi.**
