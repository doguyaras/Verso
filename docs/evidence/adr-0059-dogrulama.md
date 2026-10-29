# ADR-0059 doğrulaması: Türkçe kişisel veri maskeleme

Tarih: 2026-10-29. Dal: `feature/doguyaras-pii-maskeleme` (taban `develop` `b36facb`). Makine kaynakları sınırlı olduğu için **yerelde konteyner başlatılmadı**; Testcontainers isteyen `ModelGatewayApiTest` GitHub CI'da koşar.

## Yerelde koşulanlar

| Komut | Sonuç |
|---|---|
| `./mvnw -B -ntp -T 1 -pl services/qa/qa-core -am test` | BUILD SUCCESS; qa-core 455 test, 0 hata (yeni: `TurkishPiiTest` 52, `PseudonymiserTest` 11; güncel: `RoutedChatModelTest` 12, `ModelGatewayTest` 8) |
| `./mvnw -B -ntp -T 1 -pl verso-app -am test -Dtest=ArchitectureRulesTest,ConfigDriftTest,ErrorCodeUniquenessTest,ModuleStructureTest,ModuleConsistencyTest,MigrationConventionsTest,TransactionBoundaryRulesTest,*ContractTest` | BUILD SUCCESS; `ModelGatewayApiTest` derlendi |
| `node --test scripts/providers-panel.test.js scripts/panel.test.mjs scripts/uyum-paketi.test.js` | 10 + 162 + 11 test, hepsi geçti |
| `node scripts/flyway-immutability.js check --base origin/develop` | `flyway-immutability: OK (base=origin/develop, merge-base=b36facb029bc)` |
| `node scripts/migration-contract.js check --base origin/develop` | `migration-contract: OK (1 yeni migration, base=origin/develop)` |

## Testlerin kanıtladıkları

- **Tespit (`TurkishPiiTest`):** TCKN iki kontrol hanesi ve 0 ile başlamama; VKN kontrol hanesi ve vergi sözcüğü bağlamı (bağlamsız, uzak bağlam, yanlış hane reddi); IBAN mod-97 (bitişik, gruplu, küçük harf); kart Luhn + ağ öneki (Visa, Mastercard, Amex 4-6-5, Troy), tutarsız gruplama reddi; e-posta Türkçe harf ve cümle noktası; telefonun 17 yazımı, önceksiz alan kodu, 599, tarih, tutar ve 11 hane reddi, iki yazımın tek anahtarı; plaka biçimleri ve 82/00, birim kısaltmaları, küçük harf reddi; sıralı ve çakışmasız bulgular; sıradan iş metninde bulgu yok.
- **Yer tutucu (`PseudonymiserTest`):** aynı değer aynı yer tutucu, türe göre numara, çağrılar arası bağımsızlık; ilk yazımla geri koyma, `[1]` atıfları ve yabancı yer tutucuya dokunmama; istemde zaten bulunan yer tutucu biçiminin değere çevrilmemesi; akışta **her iki bölme noktası** için doğru geri koyma, jeton jeton akışta yarım yer tutucu yayılmaması, kuyruğun sonda bırakılması; süzgecin sistem/kullanıcı/asistan mesajlarını maskelemesi ve tek son parça.
- **Rota (`RoutedChatModelTest`):** akışta bölünmüş yer tutucu ve kayıtta yalnız tür sayıları; yerel modelde istem değişmez ve `NONE`; yeniden yazılamayan mesajda hiçbir şey gönderilmez, yedeğe geçilmez.
- **Geçit (`ModelGatewayTest`):** dış çağrının isteminde yedi türün hiçbiri yok, sipariş numarası, tarih ve hatalı VKN okunur kalır; cevap değerlerle döner; kullanım satırı `PII_PSEUDONYMISE` ve sayılar, değer yok; aynı istem yerel bağlantıya birebir gider; ayar kapalıyken `PASS_THROUGH`.

## CI'da koşacaklar (Testcontainers)

`ModelGatewayApiTest`: dış çağrı satırında `filter_policy = 'PII_PSEUDONYMISE'` ve `masked_counts = '{}'`; yalnız `userModelChoice` gönderen ayar isteğinin maskelemeyi açık bırakması, boş gövdenin `400` olması; V9'un gerçek PostgreSQL'de uygulanması.
