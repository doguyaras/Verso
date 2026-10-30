# ADR-0064: Cevap uygunluğu ve çekimser kalma

- **Durum:** Kabul edildi. Varsayılan açık; `VERSO_QA_RELEVANCE_ENABLED=false` kapatır.
- **Tarih:** 2026-10-30
- **Karar verenler:** doguyaras. İstek: belgelerin cevaplamadığı soruları asistan cevaplamasın. ADR-0063'ten sonra dış setteki ret 0,756'da kaldı; hedef ≥ 0,85, diğer ölçümler gürültü içinde.
- **İlgili eşik (referans Bölüm 24):** sıcak yola uzak çağrı eklenmez (ADR-0008 bütçesi değişmez). llm-rules 3.10 (yeni), 7.1 eval seti.
- **Genişlettiği:**
  - ADR-0022 ve llm-rules 3.4: `NOT_FOUND` artık model cevabından sonra da verilebilir.
  - ADR-0047: dayanak kontrolünden sonra, aynı yerde (`QuestionServiceImpl.cited`) çalışır.
  - ADR-0055: kısmi cevap korunur.
- **Değiştirmediği:** prompt, retrieval, kapı eşikleri, atıf eşlemesi (3.3), API alanları.
- **Kanıt:** `docs/evidence/cevap-uygunlugu-olcumu.md`.

## Bağlam

- ADR-0063'ün ölçümü: konusu belgede olup sorulan bilgisi olmayan cevaplanamazlar (dış sette 35/45) retrieval sinyaliyle ayrılamıyor. Kosinüsleri cevaplanabilirlerin arasında.
- Ret kararını model veriyor, ve küçük model (gemma4:e2b) üç biçimde hata yapıyor. Dış set p55 koşusunda reddedilmeyen 11 sorunun dağılımı:
  - **Atıflı ret (3):** "Belgelerde … bilgi bulunmamaktadır [4]." Ürün atıf olduğu için bunu `ANSWERED` sayıyordu.
  - **Ret + dolgu (2):** "… bilgi bulunmamaktadır. Ancak, adayların … ALES puanına sahip olmaları gerekir [3]."
  - **Soruyu cevaplamayan cevap (6):** "Bağımlılık Merkezinin şu anki müdürü kimdir?" sorusuna atama kuralı; "hangi şirketten?" sorusuna ilgisiz bir sigorta hükmü; "kaç kuruş?" sorusuna "ücret alınmaz"; "hangi kampüs ve binada?" sorusuna merkezin tanımı.
- Dayanak kontrolü (ADR-0047) cümlenin pasajda durduğunu denetler. Cevabın soruyu karşılayıp karşılamadığını denetleyen bir şey yoktu.

## Seçenekler

| Seçenek | Ölçüm | Sonuç |
|---|---|---|
| Prompt'u sıkılaştırmak ("emin değilsen sabit cümleyi yaz") | Ret davranışı modelindir; ADR-0022 ve ADR-0063'te prompt ve pasaj sayısı ret oranını oynattı ama atıflı reddi ve dolguyu bitirmedi. Her prompt değişikliği iki seti baştan ölçtürür | Reddedildi |
| `passage-min-similarity` 0,60 | Dış ret 0,867 ama iç doğru bilgi 0,848 → 0,759 (ADR-0063) | Reddedildi |
| İkinci yerel model çağrısı (LLM yargıcı: "cevap soruyu karşılıyor mu?") | Gerek kalmadı: sözcüksel kontrol kayıtlı cevaplarda dış setin 11 hatasından 9'unu, iç setin 6 hatasından 4'ünü yakaladı, doğru bilgili cevap kaybı 0. Yargıç her cevaba bir sohbet slotu ve ~0,3–1 sn ekler; tek slotlu yerel kurulumda (ADR-0008) p99 bütçesini zorlar | Ölçülmedi; yeniden değerlendirme koşulunda |
| Kısmi cevap ("X belgelerde yok; belgeler yalnız Y'yi söylüyor [1]", ADR-0055 dili) | Atıf kalır, ürün ve eval cevap sayar; kullanıcıya yine yanlış kural sunulur | Reddedildi: ADR-0055'in kısmi cümlesi yalnız gerçekten birden çok şey soran soruda kalır |
| **Sözcüksel ret ve boşluk kontrolü, sonucu mevcut `NOT_FOUND` biçimi** | Aşağıda | **Seçildi** |

## Karar

**Kontrol** (`AnswerRelevance`, `qa-core`; yardımcıları `RefusalDetector` ve `QuestionSlot`). Atıflar (3.3) ve dayanak (3.7) doğrulandıktan sonra, iki uçta da aynı yerde çalışır. Model ve ağ yoktur. Sırayla:

1. **Ret.** Cevabın her parçası bir retse (`REFUSAL`). Ret yan cümlesi:
   - Bir "yok" yüklemiyle biter: "bulunmamaktadır", "bulunamadı", "yer almamaktadır", "belirtilmemiştir", "rastlanmamıştır", "mevcut değildir", "bilgi yok" vb.
   - Yüklem yan cümleyi kapatır: cümle sonu, noktalama ya da "ancak/fakat/ama". "Belgesi bulunamadığında" ve "belirtilmemiş durumlarda" ret değildir.
   - Bilgiden ya da belgelerden söz eder: "bilgi", "cevap", "açıklama", "düzenleme", … ya da "belgelerde", "pasajlarda", "metinde". "Yönetmelikte yaş sınırı bulunmamaktadır" ret değildir: bir cevaptır.
   - Atıf işaretleri ve modelin kopyaladığı `[[BELGE n]]` ayraçları sayılmaz.
2. **Ret + dolgu.** Cevap retle başlar ve sürerse (`REFUSAL_WITH_FILLER`), kalanı sorunun boşluğunu doldurmuyorsa. Dolduruyorsa cevap kalır: model erken reddetmiş olabilir (dış set is-06 doğru bilgiyi böyle verdi). Evet/hayır sorusunda ("… mı?") cevap kalır.
3. **Boşluk.** Cevap sorunun tek ve denetlenebilir istediğini vermiyorsa (`SLOT_MISSING`). Yalnız `ANSWERED` olacak cevapta. Soru türleri (`QuestionSlot`):

   | Soru | İstenen | Karşılayan |
   |---|---|---|
   | kim, kime, kimin | kişi ya da rol | rol sözcüğü, "tarafından", cümle içinde büyük harfli ad |
   | "şu anki / şimdiki / halen / bugünkü … kim" | kişi adı | unvan ("Prof. Dr.") ya da yan yana iki büyük harfli ad; makam ve kurum adları ("Merkez Müdürü", "Hacettepe Üniversitesi") ve soruda geçen adlar sayılmaz |
   | ne zaman, hangi gün/ay/yıl | tarih ya da süre | ay adı, sayı, "gün/hafta/yıl", "başında/sonunda/itibaren" |
   | kaçta, kaça kadar, saat kaç | saat | "saat" ya da `16.30` |
   | nerede, nereye | yer ya da onu belirleyen düzenleme | yer sözcüğü, "kanun/yönetmelik/karar", büyük harfli ad |
   | hangi kampüs/bina/şehir/salon … | aynı tür yer | sorulan yer adının kendisi ("kampüs", "bina") |
   | kaç, ne kadar | miktar | rakam, Türkçe ya da bitişik sayı sözcüğü ("oniki", "kırkbeş"), "yüzde", "katı"; atıf yapılan pasajlardan birinde de bir miktar |
   | kaç + birim | o türden miktar | para (kuruş, lira, TL), süre (saat … yıl, yarıyıl, dönem: birbirine çevrilebilir), kişi (üye, eleman, öğretim üyesi …), kredi, puan, sayfa |
   | hangi şirket/firma/banka | özel ad | hukuki biçim ("A.Ş.") ya da cümle içinde büyük harfli ad |
   | diğerleri ("nedir", "nasıl", evet/hayır, genel "hangi") | – | denetlenmez |

4. **Birden çok şey soran soru** (iki farklı soru türü, "karşılaştır", "fark", "aynı mı", "hangisi") ve **çok adımlı sentez** (ADR-0055): yalnız adım 1. Kısmi cevap ("X 12 karakterdir [1]. Y için belgelerde bilgi bulunamadı.") korunur.

**Ürün davranışı** (mevcut `NOT_FOUND` biçimi, yeni alan yok):

- Adım 1–3'ten biri tutarsa ve sonuç zaten `NOT_FOUND` değilse: `answer` sabit "Belgelerde bu sorunun cevabı bulunamadı." cümlesi, `found=false`, `outcome=NOT_FOUND`, `citations=[]`, `unverified=null`. Modelin açıklaması gösterilmez: `NOT_FOUND`'un her yolda aynı biçimde olması panelin, geçmişin ve istemcilerin sözleşmesidir.
- Atıfsız kendi sözleriyle ret de (önce `UNCITED`) artık `NOT_FOUND`.
- Atıflı ama dayanağı doğrulanmayan cevap (`UNCITED`, ADR-0047) boşluk kontrolüne girmez: zaten işaretli.
- Akışta `delta` olayları ham metni taşımış olabilir; `citations` ve `done` olayları doğrulanmış sonucu taşır (ADR-0035). Panel `NOT_FOUND`'da taslağı "bulunamadı" kutusuyla değiştirir.
- Saklama: tur `NOT_FOUND` ve atıfsız saklanır. Migration yok.
- **API:** alan, tür ya da enum değeri eklenmedi; `AnswerOutcome.NOT_FOUND` açıklaması genişledi. `docs/api-questions-integration-v1.md` güncellendi.
- **Log ve metrik:** "Question answered/streamed" satırına `relevance=answers|refusal|refusal_with_filler|slot_missing|off` ve `questionType=person|date|place|number|entity|other|off`. Yeni sayaç `verso_qa_relevance_abstained_total{verdict,type}`. İçerik hiçbir yerde yok (2.1).
- **Ayar:** `verso.qa.relevance.enabled` (`VERSO_QA_RELEVANCE_ENABLED`), varsayılan `true`. Yüzeyler: `config/verso.yml`, `application-local.yml`, `compose.yaml`, `.env.example`, `deploy/prod.env.example`, `ConfigDriftTest`. Sözcük listeleri kod sabitidir (BA).
- **Mod:** iki modda aynı; local modda yerelde kalır (1.1).

**Sonuç sayıları:** canlı yığın, GPU, aynı imaj; "önce" `VERSO_QA_RELEVANCE_ENABLED=false`, "sonra" varsayılan. Ayrıntı ve soru bazında fark: kanıt belgesi.

| Ölçüm | Dış, önce | Dış, sonra | İç, önce | İç, sonra |
|---|---|---|---|---|
| Kaynak bulundu | 0,925 | 0,911 | 0,893 | 0,884 |
| Atıf isabeti | 0,725 | 0,711 | 0,839 | 0,830 |
| Doğru bilgi | 0,787 | 0,790 | 0,857 | 0,857 |
| "Bulunamadı", sıkı | 0,711 | **0,956** | 0,750 | **0,929** |
| Ret, modelinki dahil | 0,756 | **0,956** | 0,786 | **0,929** |
| Gecikme p50 | 989 ms | 712 ms | 400 ms | 391 ms |

| Çok adımlı set | Kaynak isabeti, önce → sonra | Bilgi isabeti, önce → sonra | Kısmi cevapta eksik söylendi |
|---|---|---|---|
| İç (20) | 0,684 → 0,684 | 0,658 → 0,658 | 1,0 → 1,0 |
| Dış (32) | 0,484 → 0,484 | 0,531 → 0,516 | 1,0 → 1,0 |

- Dışta 45 cevaplanamazın 43'ü, içte 28'in 26'sı reddedildi.
- Bulundu −4 / −1, atıf isabeti −4 / −1 soru: gürültü içinde (ADR-0063: dışta ±7, içte ±1). "Bulunamadı"ya dönen cevaplanabilir sorular (dış hu-dis-04, hu-hzr-05, bina-03; iç erp-14) beklenen bilgiyi zaten vermiyordu.
- Kayıtlı cevaplarda yeniden oynatma (model gürültüsü yok): dört kayıtta da doğru bilgili cevap kaybı 0.

## Sonuçlar

- **Olumlu:**
  - Dış sette ret 0,756 → 0,956, iç sette 0,786 → 0,929. "Bulunamadı" artık tek biçimde: sıkı ölçü (`notFoundAccuracy`) ile modelin kendi reddi dahil ölçü eşitlendi.
  - Atıflı ret ortadan kalktı: kaynağı gösterilen bir "bilgi yok" cevabı artık yok.
  - Sıcak yolda ek çağrı, bellek ve bağımlılık yok (cevap başına < 1 ms).
- **Olumsuz / kabul edilen risk:**
  - Bulunma oranı düşer: önceden "bulundu" sayılan retler ve soruyu cevaplamayan cevaplar (dış hu-hzr-05, bina-03, hu-dis-04; iç erp-14). Bunların hiçbirinde beklenen bilgi yoktu; atıf isabeti bu yüzden de birkaç soru düşer.
  - Sözcük listeleri Türkçe ve yönetmelik diline göre seçildi. İngilizce soru denetlenmez (tür `other`). Başka bir alanda (ör. müşteri hizmetleri) yanlış "bulunamadı" görülebilir; `RelevanceEvalTest` yalnız iki setin kayıtlı cevaplarını korur.
  - Hâlâ geçenler: varlık değişimi ("Gümüş" sorusuna "Altın" cevabı), aynı türde başka bir değer ("aylık burs miktarı" sorusuna üst sınır yüzdesi), dolgusu yanlışlıkla "saat" içeren cevap. Bunlar için soru–cevap anlam denetimi gerekir (LLM yargıcı ya da NLI).
  - Kullanıcı modelin "neden bulunamadı" açıklamasını görmez.
- **Etkilenen dosyalar:**
  - `qa-core`: `RefusalDetector`, `QuestionSlot`, `AnswerRelevance`, `QaRelevanceProperties` (yeni); `QuestionServiceImpl`, `QaMetrics`, `QaConfiguration`.
  - `qa-api`: `AnswerOutcome` (yalnız açıklama).
  - Testler: `RefusalDetectorTest`, `QuestionSlotTest`, `AnswerRelevanceTest`, `RelevanceEvalTest` (yeni); `QuestionApiTest`, `QuestionStreamApiTest` (CI); dört `qa-core` servis testi (kurucu); `KnowledgeCardApiTest`, `SkillApiTest`, `KnowledgeHealthApiTest` (sabit cevap artık "kaç gün" sorusuna gün sayısı veriyor); `ConfigDriftTest`.
  - Belgeler: llm-rules 3.10, `repo-context.md`, `README.md`, `docs/decisions.md`, `docs/api-questions-integration-v1.md`, `eval/results/*-uygunluk-*`.
- **Geri alma yolu:** `VERSO_QA_RELEVANCE_ENABLED=false`. Sonuç türü ADR-0047'deki gibi olur. Migration yok, imaj değişmez.

## Hata avı 2026-10-30

Kayıtlı setlerde görünmeyen, üç kalıp hatası düzeltildi (`QuestionSlotTest`, `RefusalDetectorTest`, `AnswerRelevanceTest`):

- `QuestionSlot`: soru sözcükleri bütün sözcük olarak eşleşir. "kaç" artık "kaçak", "kaçış", "kaçınılmaz" içinde; "hangi ay/yıl/gün/saat/tarih" artık "hangi ayrıntılar", "hangi ayarlar", "hangi yıllık" içinde; "şu an" artık "şu anlaşma" içinde eşleşmez. Önceden bu sorular sayı ya da tarih isteyen soru sayılıyor, doğru atıflı cevap `slot_missing` ile "bulunamadı"ya dönüyordu.
- `RefusalDetector`: bir cümlede olumsuz yüklemden önce yüklemle kapanan ve sayı ya da kod veren bir ifade varsa ("yıllık izin 14 gündür ve … hüküm bulunmamaktadır") o kısım içerik, yalnız sonraki yan cümle rettir. Önceden bütün cümle ret sayılıyor, tek cümlelik doğru cevap kayboluyordu.
- `QuestionSlot.namesSomeone`: ad, sorunun sözcükleriyle karşılaştırılır, ham metinle değil ("Ali", "mali" içinde geçtiği için ad sayılmıyordu).

Kayıtlı cevaplar yeniden oynatıldı: `RelevanceEvalTest` değişmeden geçer; dış set (`e2e-external-p55`, `e2e-external-uygunluk-on`) yeniden oynatma sonucu aynı günün hata avı raporundadır.

## Yeniden değerlendirme koşulu

- Chat modeli değişirse (gemma4:e2b dışında) iki set yeniden ölçülür: ret dili modelindir.
- Gerçek bir müşteri setinde doğru bilgili cevap kaybı 100 cevapta 1'i geçerse sözcük listeleri gözden geçirilir ya da kapatılır.
- Kalan hata sınıfı (varlık ya da değer değişimi) ölçülebilir bir paya ulaşırsa isteğe bağlı LLM yargıcı ölçülür: yalnız `ANSWERED` cevaplarda, ayrı ve kısa süre sınırıyla, local-GPU p99 15 sn bütçesi içinde.
