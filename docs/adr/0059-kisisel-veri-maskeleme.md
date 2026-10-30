# ADR-0059: Bulut model çağrılarında Türkçe kişisel veri maskeleme (takma adlandırma)

- **Durum:** Kabul edildi. Uygulama tamam; birim testleri yerelde geçti (`TurkishPiiTest`, `PseudonymiserTest`, `RoutedChatModelTest`, `ModelGatewayTest`), Testcontainers'lı `ModelGatewayApiTest` CI'da koşar. Gerçek bir bulut sağlayıcısıyla canlı deneme yapılmadı.
- **Tarih:** 2026-10-29
- **Karar verenler:** doguyaras
- **İlgili eşik (referans Bölüm 24):** yanlış pozitif oranı (ayda maskelenen değerlerin altın sette elle örneklenmesi) ve cevaplarda geri dönmeyen yer tutucu oranı; ikisinden biri %2'yi aşarsa yeniden açılır.
- **Genişlettiği:** ADR-0056 (model geçidi; "gizlilik geçidi kancası" maddesi ve yeniden değerlendirme koşulu), ADR-0013 (cloud modu ve egress), ADR-0050 (Güven ekranı)

## Bağlam

Model geçidi (ADR-0056) kurumun kendi bulut sağlayıcısına giden her çağrıyı `ModelRequestFilter` zincirinden geçiriyordu; tek süzgeç `PASS_THROUGH` idi. Bulut modunda soru, seçilen pasajlar, takip turları ve beceri formu girdileri (ADR-0057) sağlayıcıya olduğu gibi gidiyor. Bu metinlerde Türkiye'ye özgü kişisel veri sık geçer: T.C. kimlik numarası, vergi kimlik numarası, IBAN, kart numarası, telefon, e-posta, araç plakası. Kurumlar bulut sağlayıcısı kullanırken bu değerlerin kurulumdan çıkmamasını istiyor; tamamen yerel çalışan kurulumlarda (Ollama, iç ağdaki OpenAI uyumlu sunucu) sorun yoktur, veri zaten çıkmaz.

Kısıtlar: değerler log'a, veritabanına, denetim kaydına girmez (llm-rules 2.1); süzgeç kurulum içinde çalışır ve hiçbir şeyi dışarı göndermez; başarısız süzgeç çağrıyı durdurur, maskelenmemiş istemi asla göndermez (ADR-0056 kuralı); akışlı cevapta (SSE) jetonlar parça parça gelir.

## Seçenekler

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| Yalnız uzunlukla eşleşme (11 hane = TCKN, 10 hane = VKN …) | Basit | Sipariş, fatura ve takip numaralarının çoğu maskelenir; model metni anlayamaz | Düşük |
| **Kurala dayalı tespit + resmi kontrol algoritmaları** (TCKN kontrol haneleri, VKN kontrol hanesi, IBAN mod-97, Luhn), çağrı başına tutarlı yer tutucu, cevapta geri koyma | Deterministik, hızlı (mikrosaniyeler), test edilebilir, ek model yok | İsim, adres gibi serbest biçimli veriyi bulmaz | Düşük |
| Yerel NER modeli (isim, adres, kurum) | Serbest biçimli veriyi de bulur | Her bulut çağrısına yerel model gecikmesi; Türkçe NER doğruluğu ölçülmedi; ayrı model yönetimi | Yüksek |
| Değeri silmek (`***`) | En basit | Model aynı kişiden iki kez söz edildiğini anlayamaz; cevapta değer geri gelmez | Düşük |

## Karar

Kurala dayalı tespit ve **çağrı başına takma adlandırma** seçildi. Yerel NER sonraki adımdır; aynı kancaya ikinci süzgeç olarak takılır. Silmek reddedildi: cevabın kullanıcıya değerle dönmesi gerekir.

1. **Tespit** (`TurkishPii`, saf ve durumsuz):
   - **TCKN:** 11 hane, ilki 0 değil; 10. hane = (7 × tek konumlar − çift konumlar) mod 10, 11. hane = ilk on hanenin toplamı mod 10.
   - **VKN:** 10 hane, resmi kontrol hanesi algoritması. Kontrol hanesi rastgele 10 haneli sayıların onda birini geçirdiği için VKN ayrıca **hemen önünde (40 karakter) bir vergi sözcüğü** ister ("vergi", "VKN", "V.K.N.", "tax id").
   - **IBAN:** `TR` + 24 hane, bitişik ya da dörtlü gruplar, mod-97 = 1.
   - **Kart:** 13–19 hane (bitişik, dörtlü gruplar ya da 4-6-5), ağ öneki (2–6 ile başlayan, Troy 9792) ve Luhn.
   - **Telefon:** `+90`, `0090`, `90` ya da `0` önekli, parantezli ya da önceksiz; boşluk, nokta, tire ile 3-3-2-2 ya da 3-3-4. Önceksiz yazımda yalnız **cep** (501–507, 530–559, 561) kabul edilir: önceksiz alan kodlu on hane çoğunlukla sipariş numarasıdır. Sabit hat (212–488) ve 850 önek ya da parantez ister.
   - **E-posta:** Türkçe harfli yerel kısım ve alan adı; cümle sonu noktası dahil edilmez; üst alan adı harf olmalı.
   - **Plaka:** 01–81, 1–3 büyük harf, harf sayısına göre 2–5 hane (1 harf: 4–5, 2 harf: 3–4, 3 harf: 2–3). Birim ve para kısaltmaları (`GB`, `TL`, `KG` …) plaka sayılmaz.
   - Çakışmada uzun eşleşme kazanır (IBAN'ın içindeki rakamlar ayrıca kart ya da TCKN olmaz).
   - Bilinçli tercih: **fazla maskelemek güvenli hatadır**; maskelenen değer cevapta geri gelir, maskelenmeyen kurulumdan çıkmıştır.
2. **Yer tutucu** (`Pseudonymiser`): türe göre numaralı, `[TCKN_1]`, `[IBAN_2]`, `[PHONE_1]`, `[EMAIL_1]`, `[PLATE_1]`, `[CARD_1]`, `[VKN_1]`. Aynı değer bir çağrı içinde (sistem, kullanıcı ve önceki tur mesajlarında) hep aynı yer tutucuyu alır; aynı değerin farklı yazımları (boşluklu/bitişik IBAN, `+90 532…` ile `0532-…`) tek değer sayılır. İstemde zaten yer tutucu biçiminde metin varsa (şablon belgesi) numaralar onun üstünden başlar, böylece o metin dönüşte değere çevrilmez. Eşleme **yalnız o çağrının bellekteki nesnesindedir**: log'a, veritabanına, denetime, metriğe yazılmaz; çağrı bitince atılır. Rotada yedek modele geçilirse yeni deneme kendi eşlemesini açar.
3. **Geri koyma:** cevaptaki yer tutucular ilk görülen yazımla değere döner; çağrıya ait olmayan yer tutucu ve atıf numaraları (`[1]`) olduğu gibi kalır. **Akışta** bir yer tutucunun parçalara bölünmesi (`[TC` + `KN_1]`) tamponla çözülür: parçanın sonunda bir yer tutucunun başı olabilecek kuyruk (en çok 14 karakter) bir sonraki parçaya kadar tutulur; akış bitince kalan kuyruk son bir parça olarak gönderilir (kullanım bilgisi sağlayıcının kendi son parçasında kalır).
4. **Kapsam:** yalnız kurulumdan çıkan çağrılar: **dış** (`EXTERNAL`) bağlantılar ve **cloud modda yerleşik model** (orada Anthropic ya da OpenAI'dır; ADR-0065 düzeltmesi: ilk uygulama yerleşik modeli her modda yerel sayıyordu ve cloud kurulumun varsayılan yolu maskelenmiyordu). Yerel bağlantı ve local modda yerleşik model hiç süzgeçten geçmez; istem değişmez. Bağlantı deneme çağrısı sabit bir cümledir ve maskelenmez. Embedding zaten yereldir (llm-rules 1.2).
5. **Kapalı başarısızlık:** süzgecin yeniden yazamadığı bir mesaj türü (araç çağrısı, araç cevabı) çağrıyı **göndermeden** durdurur (`FAILED`), yedek modele geçilmez; geçit bugün ikisini de göndermez.
6. **Yönetici ayarı:** `qa.model_gateway_setting.pii_masking` (V9), **varsayılan açık**. `PUT /v1/admin/model-gateway/settings` `{"piiMasking": false}` kapatır; alan isteğe bağlıdır, yalnız `userModelChoice` gönderen eski istemci ayarı değiştirmez. Açma/kapama denetime `MODEL_GATEWAY_CHANGED` + `PII_MASKING_ON` / `PII_MASKING_OFF` ile, değişiklikle aynı transaction'da yazılır; kapatma ayrıca bir WARN log satırıdır. Ayar okunamazsa (ilk yüklemeden önce) maskeleme açık sayılır.
7. **Sayım:** her dış çağrının `qa.model_usage` satırı `filter_policy` (`PII_PSEUDONYMISE` ya da kapalıyken `PASS_THROUGH`) ve `masked_counts` (`{"TCKN": 1, "IBAN": 2}`: türe göre farklı değer sayısı) taşır; metrik `verso.model.gateway.masked{type}`. `GET …/usage` ayın toplamını `maskedValues` ve türe göre `masked` olarak verir. Yalnız sayı; değer yok.
8. **Panel:** "Yapay zekâ sağlayıcıları" ekranında yönlendirme kartında tek anahtar ("Bulut çağrılarında kişisel verileri maskele"), maliyet kartında tek satır ("Bu ay bulut çağrılarında N değer maskelendi (TCKN 3, IBAN 1)"). `innerHTML` yok, CSP değişmedi.

### Güvenceler

| Güvence | Nasıl korunur | Doğrulama |
|---|---|---|
| Maskeleme açıkken dış çağrının isteminde ham değer yok | Her sistem/kullanıcı/asistan mesajı gönderimden önce maskelenir | `ModelGatewayTest` (giden istemde yedi türün hiçbiri yok, yerel çağrı birebir aynı), `RoutedChatModelTest`, `PseudonymiserTest` |
| Kullanıcı değeri cevapta geri görür, akışta da | Tam cevapta ve akışta geri koyma; parça tamponu | `PseudonymiserTest` (her bölme noktası), `RoutedChatModelTest` (akış) |
| Eşleme kalıcı değil | Yalnız çağrının bellek nesnesi; kayıtta yalnız tür sayıları | `ModelGatewayTest` (kullanım satırında değer yok), `ModelGatewayApiTest` (CI: `masked_counts`) |
| Süzgeç başarısızsa hiçbir şey gitmez | Gönderimden önce istisna, yedeğe geçiş yok | `RoutedChatModelTest` |
| Sipariş numarası, tarih, tutar maskelenmez | Kontrol algoritmaları, VKN için bağlam, önceksiz sabit hat yok | `TurkishPiiTest` |

## Sonuçlar

- **Olumlu:** bulut modunda Türkçe kimlik, hesap ve iletişim verisi kurulumdan çıkmaz; kullanıcı cevabı değerlerle görür; yöneticiye bu ayın sayısı görünür; ek model ya da gecikme yok.
- **Olumsuz / kabul edilen risk:**
  - **İsim, adres, doğum tarihi ve serbest biçimli veri maskelenmez** (yerel NER sonraki adım). Bu yüzden takma adlandırma aktarımı kişisel veri olmaktan çıkarmaz, yalnız riski azaltır (VKED 5.6).
  - VKN bağlamsız yazılırsa (önünde vergi sözcüğü yoksa) maskelenmez. Kontrol hanesi doğru bir sipariş/kart numarası yanlışlıkla maskelenebilir; cevapta geri geldiği için kullanıcı fark etmez, yalnız modelin bağlamı zayıflar.
  - Model yer tutucuyu değiştirirse (köşeli parantezi atar, `TCKN_1` yazar) değer geri konmaz; kullanıcı yer tutucuyu görür. Değer yine de dışarı çıkmamıştır.
  - Plaka için Türk alfabesine özgü harfler zaten kullanılmaz; birim listesi dışındaki iki-üç harfli kısaltmalar (`16 GB 512` gibi kalıplar listede) nadiren plaka sanılabilir.
  - Ayar kurulum geneli tektir; bağlantı ya da görev başına ayrı ayar yok.
- **Etkilenen dosyalar / yüzeyler:** `qa-core` `service/gateway/{TurkishPii, Pseudonymiser, PiiMaskingFilter, ModelRequestFilter, RoutedChatModel, ModelGateway, ModelGatewayAdmin}`, `repository/ModelGatewayRepository`, `qa` V9 (`model_gateway_setting.pii_masking`, `model_usage.masked_counts`), `qa-api` `ModelGatewaySettingsRequest` (`piiMasking`, isteğe bağlı), `ModelRoutingResponse.piiMasking`, `ModelUsageResponse.{maskedValues, masked}` (eklemeli), denetim ayrıntı sözcükleri `PII_MASKING_ON` / `PII_MASKING_OFF`, metrik `verso.model.gateway.masked`, panel `providers.js`, `screens/providers.js`, `api.js`. Yeni hata kodu, config anahtarı, ortam değişkeni ya da uzak çağrı yok.
- **Geri alma:** panelden anahtarı kapatmak (`PASS_THROUGH`, ADR-0056 davranışı). V9 eklemelidir; eski imaj yeni kolonları okumaz, yazdığı satırlar varsayılanı alır.

## Yeniden değerlendirme koşulu

- Yerel NER (isim, adres) eklendiğinde: ikinci süzgeç, sıra ve gecikme bütçesi. **Karşılandı:** ADR-0065 isim ve adresi kural ve sözlükle aynı süzgece ekledi (model yok, ~5 ms / 10 KB). Aynı ADR bu ADR'nin dört kusurunu kapattı: cloud modda yerleşik model, görünmez karakter ve rakam yazımları (`TextShadow`), yer tutucu taşması, panelde başarısız kaydın geri alınmaması.
- Yanlış pozitif ya da geri dönmeyen yer tutucu oranı eşiği aşarsa.
- Bağlantı ya da görev başına maskeleme ayarı istenirse.
- Geçit araç çağrısı (tool calling) göndermeye başlarsa: araç argümanları ve cevapları için maskeleme.
