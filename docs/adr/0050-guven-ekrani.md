# ADR-0050: Panelde "Güven" ekranı ve salt okunur güven ucu

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-26
- **Karar verenler:** doguyaras (görev: müşteriye verinin yerinde kaldığını doğrulanabilir kanıtla gösteren ekran)
- **İlgili eşik (referans Bölüm 24):** sıcak yol, şema ve config anahtarı değişmedi
- **Genişlettiği:** ADR-0006 (mod), ADR-0013 (egress kanıtı), ADR-0019 (roller), ADR-0026 (denetim), ADR-0027 ve `docs/uyum/05-egress-kaniti.md`, ADR-0030/0040 (imza, SBOM, `verify-release.sh`), ADR-0036 (Yönetim grubu), ADR-0044/0045 (bağlayıcı çıkışı), llm-rules 1.1 ve 1.7

## Bağlam

- "Veri bu sunucudan çıkmaz" iddiasının kanıtı betiklerde, testlerde ve belgelerde duruyor; müşterinin BT ekibi bunları panelden bulamıyor. Yöneticinin tek bakışta görmesi gereken şey: mod, açık dış yol var mı, imaj kime ait, denetim kaydı yaşıyor mu.
- Uygulamanın kendi söylediği bağımsız kanıt değildir. Ekran bunu açıkça söylemeli ve bağımsız kanıtın nasıl alınacağını göstermelidir.

## Karar

1. **`GET /v1/admin/trust`** (yalnız `verso-admin`, ADR-0019; diğerleri 403 `ACCESS_DENIED`; `Cache-Control: no-store`). Alanlar:
   - `ai`: `mode`, `chatProvider`, `chatModel`, `embeddingModel`.
   - `egress`: `externalConnectorEgress`, `allowedHosts` (kapalıyken boş).
   - `connectors`: `types` (SMB, CONFLUENCE, SHAREPOINT), `cloudConnectorPresent`.
   - `mcp`: `enabled`, `audience`.
   - `identity.issuers`: `name` (primary, entra) ve `host`; yol, anahtar adresi, istemci kimliği yok.
   - `build`: `version`, `builtAt` (spring-boot `build-info`), `imageDigest` (ortamdan `VERSO_IMAGE_DIGEST`, yalnız `sha256:<64 onaltılık>` biçimindeyse; yoksa `null`).
   - `audit`: `lastEventAt`, `outboxOldestAgeSeconds` (tüm modüllerin outbox'ının en eskisi; bekleyen yoksa `null`).
   - `backup`: `lastSuccessAt` her zaman `null` (uygulama yedeği okuyamaz) ve `verification` = "kurulum betiğiyle doğrulanır".
   - `guarantee`: modun sabit Türkçe cümlesi.
2. **Modül sınırı.** Her modül kendi payını `platform-core`'daki `TrustContributor` arayüzüyle verir (bağlayıcı, belge, qa, denetim); denetleyici (`qa-core`, `/v1/info`nun yanında) yalnız toplar ve başka modülün tablosunu ya da sınıfını okumaz. Bir katkı okunamazsa ilgili alan boş kalır, uç 500 vermez.
3. **Sızıntı yok.** Yanıtta secret değeri, secret dosya adı, bağlayıcı adresi/paylaşımı/kullanıcısı, belge, soru ya da cevap içeriği yoktur. Bağlayıcılar yalnız tür olarak sayılır. Kimlik sağlayıcı yalnız host. İmaj özeti ortam değişkeninden gelir ve biçim doğrulanır.
4. **Panel "Güven"** (Yönetim grubu, `g u`): çalışma modu kartı (yerelde "bulut modunda ne değişir"), "Dışarı açık bağlantılar", "Kurulum doğrulaması" (`prove-local-mode.sh --report`, `verify-release.sh` ve çıktıların anlamı), "Denetim kaydı: son olay …", "Uyum paketi" belge adları, "Güven raporu indir" (Markdown ve JSON; Blob ile istemcide üretilir, sunucuya gitmez). Metin ürün adını kuruluma ait markalamadan alır.
5. **`scripts/prove-local-mode.sh --report <dosya>`**: bitişte (başarısız ya da yığın kapalıyken de) `{tool, timestamp, result: pass|fail|error, counts, checks[{name,status}]}` yazar. Dosyada adres, token ya da içerik yoktur. `error` = kanıt üretilemedi, geçti değildir. `--report` yokken çıktı ve çıkış kodu değişmez.

## Sonuçlar

- Sürüm ve zamanı `build-info` hedefi verir; imaj özetini uygulama kendisi bilemez, kurulum `VERSO_IMAGE_DIGEST` geçerse görünür. Geçmezse ekran `verify-release.sh`'ı gösterir.
- Yedek zamanı okunamaz; ekran bunu uydurmaz.
- Rapor uygulamanın beyanıdır; bağımsız kanıt betik raporu ve kurumun güvenlik duvarı gözlemidir (egress kanıtı madde 7).

## Yeniden değerlendirme koşulu

Yedek betiği başarı zamanını uygulamanın okuyabileceği bir yere yazarsa `backup.lastSuccessAt` doldurulur. Yeni bir dış çıkış yolu eklendiğinde (ADR gerektirir) `TrustContributor` ile bu uca da eklenir.
