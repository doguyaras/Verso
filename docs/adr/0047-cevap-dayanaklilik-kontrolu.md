# ADR-0047: Cevap dayanaklılık kontrolü (sayı ve kod)

- **Durum:** Kabul edildi. Varsayılan açık; `VERSO_QA_GROUNDING_ENABLED=false` kapatır.
- **Tarih:** 2026-10-25
- **Karar verenler:** doguyaras. İstek: atıf yapılan pasajın desteklemediği cevap cümlelerini bulmak ve ölçmek (yol haritası `rag_quality.md` iş 3 ve 8).
- **İlgili eşik (referans Bölüm 24):** sıcak yola uzak çağrı eklenmez (ADR-0008 bütçesi değişmez). Aşağıda "Yeniden değerlendirme koşulu".
- **Genişlettiği:**
  - ADR-0012 ve ADR-0022: sonuç türleri.
  - ADR-0020: cümle bölücü, katlama ve sayı anahtarı.
  - ADR-0035: `citations` olayı.
- **Değiştirmediği:** atıf eşlemesi (llm-rules 3.3), prompt, retrieval, kapı.

## Bağlam

- Atıflar sunucuda doğrulanır (llm-rules 3.3). Bu doğrulama numaranın getirilen bir pasaja gittiğini gösterir, pasajın cümleyi desteklediğini göstermez.
- ADR-0017'deki canlı denemede model "yirmi gün" dedi; belge "on dört gün" diyordu.
- ADR-0020 alıntı penceresiyle doğrusunu kartta gösteriyor. Kullanıcı yine de karşılaştırmayı kendisi yapmak zorunda.
- Eval v2'nin uçtan uca çıktısında atıflı 128 doğal cümlenin 3'ü atıf yaptığı sayfayla çelişiyor. Üçü de sayı ya da durum hatası.
- Kısıtlar:
  - local modda yalnız yerel model (llm-rules 1.1).
  - İçerik loglanmaz (2.1).
  - Cevap sessizce değiştirilmez.
  - Makine kaynakları sınırlı: tek yan konteyner, ≤ 3 GB.
- Ölçüm ve bütün sayılar: `docs/evidence/dayanaklilik-olcumu.md`. Etiketli set: `eval/grounding-set.json`, 192 cümle, 57 desteklenmiyor.

## Seçenekler

| Seçenek | Kesinlik / duyarlılık (etiketli) | Gerçek cevaplarda | Maliyet | Sonuç |
|---|---|---|---|---|
| (a) mDeBERTa-v3-base-xnli NLI (MIT), fp32, eşik 0,1 | 1,00 / 0,61 | 5 işaretin 1'i doğru | 1,1 GB model; cümle başına 0,3–1,8 sn CPU. TEI DeBERTa-v2'yi sunmuyor: kendi Python yan konteynerimiz ya da JVM içinde ONNX Runtime ve SentencePiece gerekir. Uygulama sınırı 1,5 GiB | Ürüne girmedi |
| (a') Aynı model, int8 | ≤ 0,80 / 0,61 | – | 339 MB | Kalite yetersiz |
| **(b) Sözcüksel sayı ve kod kontrolü** | **1,00 / 0,46** (sayı ve kodda 0,76) | **1 işaret, doğru** | Süreç içi, cümle başına < 1 ms | **Seçildi** |
| (c) gemma ile LLM-as-judge | – | – | Tek sohbet slotunda ikinci çağrı; cevap süresi yaklaşık iki katı | Ölçülmedi: (b) kesin |

Lisans tuzakları (ölçülmeden elendi):
- Bespoke-MiniCheck ve Patronus Lynx: CC-BY-NC.
- HHEM-2.1-Open ve Granite Guardian: yalnız İngilizce.
- LettuceDetect: Türkçe yok.

## Karar

**Kontrol** (`GroundingCheck` → `AnchorCheck`, `qa-core`). `CitationExtractor`'dan hemen sonra çalışır, iki uçta da aynı yerde (`QuestionServiceImpl.cited`). Adımlar:

1. Temizlenmiş cevap ADR-0020 bölücüsüyle cümlelere ayrılır, satır sonu da sınırdır.
2. Her `[n]` kendi cümlesine bağlanır. Cümleyi açan işaret önceki cümleye aittir.
3. Atıflı her cümlenin **çapaları** çıkarılır:
   - Rakamla sayı: yalnız rakamlar karşılaştırılır.
   - Türkçe sayı sözcüğü: "on dört", "yirmi altı", "beşinci".
   - Kod: büyük harf ve rakam birlikte; `P1`, `SAT-E058`.
   - Hem sayı hem başka sözcük olabilen kökler ("bir", "on", "yüz", "bin", "altı", "yedi") cümlede yalnız bileşik sayıda ya da sıra sayısıyken çapadır.
4. Her çapa, cümlenin atıf yaptığı pasajlardan birinde aranır. Pasajın yalnız prompt'a giden kısmı (`max-passage-chars`) kanıttır. Pasajda bulunması yetmez; cümledeki komşu terimlerinden biriyle yan yana durmalıdır:
   - Cümlede her iki yandan en yakın 2 terim, pasajda 3 terim alınır.
   - Terim, katlanmış ilk 5 harftir. Ön ekle eşleşir.
   - Kod için pasajda bütün satır da yeterlidir (tablo anahtarı).
5. Komşuluk aranmayan iki durum:
   - Soruda geçen bir çapa her yerde serbesttir ("30.000 TL'lik masraf …").
   - Cümle pasajla en az 2 terimi paylaşmıyorsa (örneğin İngilizce belgeye Türkçe cevap) sayının pasajda bulunması yeter. Pasajda İngilizce sayı sözcükleri de okunur.
6. Çapası olmayan cümle kontrol edilmez. Olumsuzlama ve ad değişikliği bilinçli olarak kapsam dışıdır.

**Ürün davranışı:**

- **API.** Yeni alan `unverified: [{start, end, citations}]`, son alan olarak:
  - Ekleyici bir değişiklik.
  - Konum UTF-16 birimiyle verilir (JS indeksi). İşaretler dahildir.
  - Liste `AnswerResponse` ve akışın `citations` olayında yer alır.
  - Kontrol kapalıysa ya da model çağrılmadıysa `null`. Destekleniyorsa `[]`.
- **Cevap metni** hiç değişmez.
- **Sonuç türü.**
  - En az bir atıflı cümle destekleniyor ya da kontrol edilemiyorsa: `ANSWERED`.
  - **Atıflı her cümle desteklenmiyorsa:** `UNCITED` ve `found=false` ("kaynaksız cevap" anlamı). Atıflar yine listelenir, kullanıcı karşılaştırabilsin.
  - Bu, `UNCITED` ile dolu `citations` birlikteliğini getiren tek durumdur. `docs/api-questions-integration-v1.md`'de yazılı.
  - Metrikte `outcome="uncited"` sayılır.
- **Akış.** Kontrol, atıflar doğrulandıktan sonra ve `citations` olayından önce çalışır. Taslak (`delta`) aynı kalır.
- **Panel.**
  - Desteklenmeyen cümlenin altı noktalı çizgiyle çizilir (CSS Custom Highlight; metin düğümleri değişmez).
  - Cevabın altında "dayanağı doğrulanamadı" notu çıkar: cümleler düz metin alıntı olarak, en çok 3 tane.
  - `UNCITED` ve atıflı cevapta rozet "Dayanağı doğrulanamadı" der.
- **Saklama.** Sonuç saklanmaz, migration yoktur. Geçmişten açılan turda alan yoktur. Sonuç türü saklanır.
- **Log ve metrik.**
  - Log satırına yalnız sayı (`unverified=N|off`) eklenir.
  - Yeni metrik: `verso_qa_grounding_sentences_total{result=verified|unverified|unchecked}`.
  - İçerik hiçbir yerde yok.
- **Ayar.**
  - `verso.qa.grounding.enabled` (`VERSO_QA_GROUNDING_ENABLED`), varsayılan `true`.
  - Yüzeyler: `config/verso.yml`, `application-local.yml`, `compose.yaml`, `.env.example`, `deploy/prod.env.example`, `ConfigDriftTest`.
  - Eşikler (komşu sayıları, ortak terim alt sınırı) kod sabitidir (BA): ölçümle değişir, kurulum başına değil.
- **Mod.** Model ve ağ yoktur. Her iki modda aynıdır; local modda yerelde kalır (1.1).

## Sonuçlar

- **Olumlu:**
  - Küçük modelin en pahalı hatası artık kendiliğinden işaretleniyor: belgedekinden farklı bir sayı, tutar, süre ya da kod.
  - Ölçülen her yerde yanlış alarm yok.
  - Sıcak yolda ek çağrı, bellek ve bağımlılık yok.
- **Olumsuz / kabul edilen risk:**
  - Duyarlılık 0,46. Olumsuzlama, yanlış ad ya da rol ve aynı komşularla duran başka kuralın sayısı geçer.
  - Rozetin olmaması doğruluk garantisi değildir. Panel metni bunu söylemez, yalnız işaret koyar.
  - Ayarlar ve etiketler aynı küçük, sentetik, tek etiketleyicili setten. Gerçek bir müşteri setinde yanlış alarm görülebilir.
  - Tümü desteklenmeyen cevapta sonuç türü `UNCITED` olur. `UNCITED`'da atıf beklemeyen bir istemci kartları gösterir ama metni "kaynak gösterilemedi" der. Panel bunu ayırt eder.
- **Etkilenen dosyalar:**
  - `qa-api`: `UnverifiedSentence` (yeni), `AnswerResponse`, `StreamCitationsEvent`.
  - `qa-core`: `AnchorCheck` ve `GroundingCheck` (yeni), `QaGroundingProperties` (yeni), `QaConfiguration`, `QuestionServiceImpl`, `QaMetrics`, `QuestionEventStream`.
  - Panel: `stream.js`, `answer.js`, `highlight.js`, `screens/ask.js`, `panel.css`.
  - Testler: `AnchorCheckTest`, `GroundingCheckTest`, `GroundingEvalTest` (etiketli set, her build'de), `QuestionApiTest` ve `QuestionStreamApiTest` (CI), `panel.test.mjs`.
  - Belgeler: `docs/api-questions-integration-v1.md`, llm-rules 3.7, `eval/README.md`.
- **Geri alma yolu:** `VERSO_QA_GROUNDING_ENABLED=false`. Alan `null` olur, sonuç türü yalnız atıflara bakar. Migration yoktur.

## Yeniden değerlendirme koşulu

- **NLI (a) yeniden ölçülür:**
  - TEI ya da bakımlı bir sunucu DeBERTa-v2 sınıflandırmayı desteklediğinde,
  - ya da lisansı uygun, Türkçe kapsayan bir XLM-R tabanlı NLI modeli çıktığında.
  - Dışarıda tutulan gerçek cevaplarda kesinlik ≥ 0,8 olursa, (b)'ye ek ve isteğe bağlı yan konteyner olarak (ADR-0043 kalıbı) önerilir.
- **Gerçek bir müşteri setinde:**
  - (b)'nin yanlış alarmı 100 atıflı cümlede 1'i geçerse komşu sayıları yeniden ayarlanır, gerekirse kapatma önerilir.
  - Kullanıcı 👎 "Eksik ya da yanlış" ile işaretsiz cevapları sık bildirirse (c) çevrimdışı ölçüm için kurulur.
- Bulut modunda ya da daha büyük bir yerel modelde uydurulan sayı oranı ölçülür. İşaret çok seyrekse varsayılanın açık kalması yine de ucuzdur, değişmez.
