# ADR-0048: Öğrenen bilgi katmanı (mimari)

- **Durum:** Kabul edildi. Faz 1 uygulandı (ADR-0049); faz 2-5 planlı, her biri kendi ADR'siyle açılır.
- **Tarih:** 2026-10-26
- **Karar verenler:** doguyaras. İstek: kurucunun ürün yönü ("dümdüz döküman atalım öğrenilsin cevap versin olarak bakma; sektörde bir farkımız olmalı").
- **Dayanak:** araştırma notu `research_notes/Verso öğrenen bilgi katmanı/research.md` (repo dışında, 29 kaynak; Glean, Guru, Kapa, Copilot Studio, Stack Overflow, Self-RAG/CRAG, ExpeL, Mem0, PoisonedRAG, Agent Skills, LazyGraphRAG, KAG, BetrVG §87).
- **İlgili eşikler (referans Bölüm 24):** sıcak yola uzak çağrı eklenmez (ADR-0008); yeni bir servis, broker ya da veritabanı eklenmez (ADR-0001, ADR-0003).
- **Genişlettiği:** ADR-0023 (geçmiş, geri bildirim, k-anonimlik), ADR-0026 ve ADR-0039 (denetim ve outbox), ADR-0028 (bilgi alanları ve okuyucular), ADR-0022 (hibrit arama ve kapı), ADR-0046 (takip soruları), ADR-0047 (dayanaklılık).

## Bağlam

- Verso bugün "belgeyi yükle, sor, kaynaklı cevap al" ürünüdür. Rakiplerin hepsi bunu yapıyor; fark, kurumun kendi bilgisinden **zamanla daha doğru** cevap vermek ve kurumun bilgi açıklarını görünür kılmaktır.
- Araştırmanın özeti (research.md §0): ince ayar olmadan "öğrenme" beş mekanizmaya iner ve hepsi Postgres'te tutulabilir:
  1. onaylı cevap belleği (kaynaklı, onaylı soru → cevap);
  2. geri bildirimle sınırlı yeniden ağırlıklandırma;
  3. yordamsal bellek (beceriler ve kancalar);
  4. kurumun yapısal modeli (hafif graf);
  5. geri bildirim → altın set → regresyon döngüsü.
- Satıcılar (1)'i ve (5)'in analitiğini satıyor; hiçbiri beşini birden kurum içinde, Türkçe ve danışmanlık döngüsüyle sunmuyor (research.md §9).
- Bilinen tuzaklar (research.md §1, §2):
  - Stack Overflow çalışmasında eskimiş cevapların %44,1'i "kabul edilmiş" cevaptır; %58,4'ü yazıldığı gün zaten eskiymiş; yalnız %20,5'i güncellenmiş. "Onaylı" doğruluk garantisi değildir; asıl hata **onaylı ama eskimiş** cevaptır.
  - Tıklama ve beğeniyi doğrudan sıralama etiketi yapmak zenginin daha zengin olduğu bir döngü kurar (popülerlik ve konum yanlılığı).
  - PoisonedRAG: hedef soru başına 5 zehirli metin %97 başarıya ulaşıyor. Gerçekçi saldırı içeriden biri, bağlayıcıyla gelen bir belge ya da yanlış cevaba 👍 toplamak.
  - BetrVG §87(1) 6: davranış ya da performans izleyebilecek her sistem işçi temsilciliğinin ortak kararına tabidir; toplulaştırma tek başına yetmez, boyut birleşimleri kişiyi yeniden tanımlatabilir.
- Kısıtlar: tek GPU ya da CPU, yerel küçük model (`gemma4:e2b`, bge-m3); bulut modeli isteğe bağlı (ADR-0006, ADR-0013); içerik loglanmaz (llm-rules 2.1); erişim her sorguda okuyucunun ilkeleriyle (ADR-0028).

## Seçenekler

| Seçenek | Artı | Eksi | Sonuç |
|---|---|---|---|
| (a) İnce ayar (LoRA vb.) | Model "öğrenir" | Denetlenemez, geri alınamaz, silme hakkıyla çelişir (KVKK), tek GPU'da bakım maliyeti, eskime görünmez | Reddedildi |
| (b) Geri bildirimi otomatik sıralama sinyali yapmak | Ucuz | Popülerlik yanlılığı, zehirlenmeye açık, kimin neyi beğendiği izlenebilir | Reddedildi (yalnız sınırlı ve kümeye bağlı, faz 2'de) |
| (c) Tam GraphRAG (topluluk özetleri) | Çok belgeli sorular | Türkçe çıkarım kalitesi kanıtsız, global özetlerin tazelenmesi pahalı (research.md §4) | Reddedildi; yerine hafif, tipli model (faz 4) |
| **(d) Denetlenebilir veri katmanı: onaylı kartlar, kancalar, beceriler, hafif model, altın set** | Her öğrenme bir satır: kim, ne zaman, hangi kaynağa dayanarak; geri alınabilir; yerelde çalışır | İnsan onayı gerekir (tasarım gereği) | **Seçildi** |

## Karar

### İlkeler (zorunlu güvence)

1. **Öğrenme denetlenebilir veri katmanındadır, ince ayar yoktur.** Her öğrenilen şey `qa` (ileride `knowledge`) şemasında bir satırdır: kaynağı, onaylayanı, zamanı ve durumu ile. Model ağırlıkları değişmez.
2. **Geri bildirim kanıttır; terfi insan kararıdır.** 👍/👎, kümeler, sistemin önerileri yalnız bir insanın önüne konan kanıttır. Hiçbir şey kendiliğinden "onaylı" olmaz.
3. **Artırmalar sınırlı, kümeye bağlı ve zamanla söner.** Faz 2'nin geri bildirim ağırlığı hibrit skoru en çok **±%20** değiştirir; küresel değil soru kümesi × parça çiftine bağlıdır; üstel olarak söner; bir belgeyi görünmez yapamaz. Olumsuz önbellek belgeyi değil (küme, parça) çiftini bastırır.
4. **Her şey kaynak parçalara özetle (hash) bağlıdır ve onlar değişince eskir.** Kart, beceri ve model düğümü alıntıladığı parçaların SHA-256 özetini tutar. Belge silinince, değişince ya da taşınınca türetilen şey `STALE` olur ve kullanılmaz; süresi dolan onay da düşer.
5. **Kancalar yalnız bildirir, işaretler, düşürür ya da eval çalıştırır.** Terfi, silme ve erişim (ACL) değişikliği insandır. Kanca bir yapılandırma satırıdır, denetim kaydı yazar, tekrarlansa da aynı sonucu verir ve mevcut outbox'tan geçer (ADR-0039).
6. **Bulut modeli isteğe bağlıdır** ve yalnız eleştiri (beceri taslağına itiraz) ya da ayrıştırma (çok belgeli soruyu alt sorulara bölme) için kullanılır; tek başına terfi ettiremez. Her yetenek yalnız yerel modelle de çalışır; bulut kapalıyken kalite düşebilir, özellik kaybolmaz.
7. **Gizlilik.** Kullanıcı bazında döküm ürün arayüzünde yoktur. Toplu teşhislerde eşik ve gerekçe aşağıdadır (k uzlaştırması).

### k uzlaştırması (ADR-0023 ile)

- ADR-0023'teki **k = 3** kuralı yerinde kalır: tek boyutlu, tek kriterli bir liste ("bulunamadı" sorularının aynı normalleştirilmiş metni, en az 3 farklı hesap). Metin zaten soran herkes için aynıdır; ek boyut yoktur.
- Faz 2'nin teşhisleri (soru kümeleri × zaman penceresi, isteğe bağlı bilgi alanı) **çok boyutludur**. Boyutlar birleştikçe yeniden tanıma riski artar (research.md §6, BetrVG). Bu yüzden her hücrede ve her pencerede **k ≥ 5** farklı hesap; 5'in altı ayarla seçilemez. Çapraz tablo (küme × birim × gün) yoktur.
- İtiraz eşiği (faz 1) bir toplu gösterim değil, bir kartı kullanımdan çeken kanıttır; varsayılanı ADR-0023'ün k'sı olan **3** farklı kullanıcıdır, 2'nin altına inemez (tek kişi onaylı cevabı çekemez). İtiraz eden kullanıcı saklanmaz; kart başına takma ad tutulur, ekranda yalnız gün başına sayı görünür.

### Beş faz

| Faz | Kapsam | Ana veri | İnsan onayı | Ölçüm |
|---|---|---|---|---|
| **1. Cevap belleği + altın set + gecelik eval** (ADR-0049) | Onaylı bilgi kartı (yöneticinin kendi kaynaklı cevabından), özetle bağlama, eskime (silme, değiştirme, taşıma, süre), 👎 itirazı, sorudan önce eşleşme, müşteriye özel altın set, gecelik retrieval kontrolü | `knowledge_card` (+ atıf, kaynak özeti, onay, itiraz, olay), `golden_case`, `eval_run` | Kartı onaylamak ve arşivlemek | onaylı bellekten cevaplanan soru oranı, itiraz oranı, eskiyen kart oranı, altın sette recall@k, kaynak kayması |
| **2. Teşhis + kancalar** | Soru kümeleri (bulunamayan, belirsiz, 👎), k ≥ 5 bilgi sağlığı raporu, açık kümeleri, `document.changed` ve `verification.due` kancaları, sınırlı geri bildirim ağırlığı (±%20, kümeye bağlı, sönümlü) | `query_cluster`, `answer_feedback_agg`, `hook_rule`, `review_task` | Kümeyi bir sahibe vermek; kanca kuralı yazmak | açık/çözülen küme, kapsama, tazelik |
| **3. Beceri katmanı** | SKILL.md uyumlu beceriler (talimat + arama ipuçları + MCP araç çağrıları, keyfi kod yok), onaylı kartlardan ve çok adımlı konuşmalardan öneri, her beceride 2-5 altın vaka, kaynak özetine bağlı | `skill`, `skill_eval_case` | Beceriyi etkinleştirmek | becerinin altın vaka başarı oranı |
| **4. Hafif tipli sistem modeli** | Belge başına alt graf: sahipler, sistemler, süreçler, terimler; bağlayıcı üst verisi + Türkçe NER + kapalı şemalı küçük model çıkarımı; Postgres tabloları, graf veritabanı yok | `entity`, `entity_relation`, `entity_mention` | Çelişki ve sahiplik kararları | yapısal çelişki sayısı, sahipsiz belge oranı |
| **5. Sınırlı akıl yürütme** | En çok 3 alt soru (şablonlu), alt soru başına arama ve CRAG tarzı derecelendirme, alıntıya bağlı birleştirme; "karşılaştır/neden" sorularında bulut açıksa bulut | yalnız mevcut tablolar | – | çok belgeli sorularda isabet |

Sıra kurucunun önerisidir (1+7, sonra 6, 3-4, 2 ve 5; `verso-learning-vision`): faz 1 hem doğruluk döngüsünü hem danışmanlık için ölçümü başlatır.

### Bileşenler (bütün katman)

- **Cevap belleği eşleyicisi:** sorunun vektörüyle (tek embedding, ADR-0008 bütçesi değişmez) en yakın onaylı kartlar; eşik; okuyucunun ilkeleriyle kaynak özeti kontrolü; "uzman onaylı" rozet.
- **Geçiş motoru (yalnız düşürür):** belge değişikliği (outbox), süre, itiraz, gece kontrolü.
- **Değerlendirme koşucusu:** müşteriye özel altın set; gece, bağlayıcı yeniden indekslemesinden sonra (faz 2) ve Verso yükseltmesinden önce.
- **Geri bildirim toplayıcı, içgörü çıkarıcı, küme oluşturucu** (faz 2), **beceri deposu** (faz 3), **hafif model kurucu, çelişki ve eskime dedektörü** (faz 4).
- **İnceleme konsolu:** panelde Yönetim → "Onaylı bilgiler" (faz 1); itiraz, açık, çelişki ve beceri kuyrukları sonraki fazlarda aynı ekrana eklenir.
- **Bilgi sağlığı raporu:** kapsama, tazelik, çelişki sayısı, açık kümeleri, onay kuyruğu (danışmanlık çıktısı).

### Onay noktaları

Bir şeyin onaylı olması (kart, beceri), arşivlenmesi ya da silinmesi, kanca kuralının bildirim/işaret/düşürme/eval dışında bir şey yapması, erişim değişikliği ve kullanıcı bazında herhangi bir analiz (yalnız açık hukuki dayanakla) insan kararıdır.

### Güvenlik ve geri alma

- Her terfi ve arşiv denetim kaydına yazılır (yalnız kimlikler ve sabit sözcükler, ADR-0026) ve geri alınabilir: arşivlenen kart arşivden önceki satırıyla durur, aynı soruya yeni kart açılabilir; sistem yalnız düşürür.
- Görünürlük her sorguda yeniden hesaplanır: kart, alıntıladığı **bütün** belgeleri okuyabilen çağırana, onun ilkeleriyle yeniden okunan parçaların özeti tutuyorsa gösterilir. Kartın kendi sakladığı bilgi alanı listesi yalnız gösterim ve denetim içindir; güvenlik kararı ona dayanmaz.
- Özellik bayrağı (`VERSO_QA_KNOWLEDGE_ENABLED`) sunmayı kapatır; tablolar ve kartlar kalır. Geri dönüş: bayrak kapalı ya da önceki imaj (yeni tablolara dokunmaz).

### Arıza biçimleri ve önlemleri

| Arıza | Nasıl olur | Önlem |
|---|---|---|
| **Onaylı ama yanlış** | Yönetici yanlış cevabı onaylar | Yalnız kaynaklı (`ANSWERED`, atıflı) cevap onaylanır; cevap metni değiştirilemez, kaynaklarıyla gösterilir; isteğe bağlı iki kişi kuralı; 👎 eşiğinde `DISPUTED`; 90 günde onay düşer; altın set ve itiraz oranı ölçülür |
| **Zehirleme** | İçeriden biri ya da bağlayıcıyla gelen belge yanlış bilgi taşır; 👍 toplanır | 👍 hiçbir şeyi terfi ettirmez; kart yalnız yöneticinin kendi cevabından ve onun okuyabildiği bilgi alanı belgelerinden (kişisel belge yasak); kaynak bağlayıcı kökenini taşır; itiraz kullanıcı başına bir kez sayılır; her terfi denetimde ve geri alınabilir |
| **Popülerlik yanlılığı** | Çok sorulan cevap, doğru olandan daha çok öne çıkar | Faz 1'de geri bildirim sıralamayı hiç değiştirmez; faz 2'de ±%20 sınırı, kümeye bağlılık, sönüm ve konum yanlılığı düzeltmesi |
| **Eskime** | Kaynak belge değişir, silinir, taşınır ya da zaman geçer | Parça özetine bağlama; belge değişikliği outbox'la aynı işlemde bildirilir; sunarken özet yeniden kontrol edilir (olay kaybolsa da); gece kontrolü emniyet ağıdır; süre dolunca `STALE` |
| **Yanlış eşleşme** | Benzer ama farklı soru aynı karta düşer | Yüksek başlangıç eşiği (0,93), müşteriye özel ölçümle ayarlanır; aynı normalleştirilmiş soru için tek canlı kart |
| **Erişim sızıntısı** | Kart, kaynaklarını okuyamayan birine gösterilir | Okuyucunun ilkeleriyle sorgu içinde yeniden okuma (ADR-0028); okunamıyorsa kart hiç yokmuş gibi normal yol (varlık sızdırmaz) |

### Ölçümler

Onaylı bellekten cevaplanan soru oranı, onaya kadar geçen süre, eskiyen kart oranı, itiraz oranı, altın sette recall@k ve kaynak kayması (faz 1); açılan/çözülen küme, kapsama ve tazelik (faz 2); beceri başarı oranı (faz 3); müşteri başına sağlık eğilimi.

## Sonuçlar

- Olumlu: doğruluk artışı ölçülebilir ve denetlenebilir; her öğrenme geri alınabilir; danışmanlık ekibi için kuyruk ve rapor üretilir; yerelde çalışır.
- Olumsuz: insan emeği gerekir (onay, gözden geçirme); onaylı cevap bir insanın hatasını taşıyabilir; her faz yeni tablolar ve yönetim ekranı getirir.
- Ürün sınırı: Verso bir "bilgi tabanı yazma" aracı değildir; kartın metni değiştirilemez, yalnız onaylanır ya da geri çekilir.

## Yeniden değerlendirme koşulu

- Onaylı bellekten cevaplanan sorularda itiraz oranı modelin cevaplarından yüksek çıkarsa (onay süreci işlemiyor).
- Bir müşteride onaylı kart sayısı 10 000'i geçerse (vektör indeksi ve ayrı `knowledge` modülü).
- İşçi temsilciliği ya da KVKK değerlendirmesi k ≥ 5'ten farklı bir eşik isterse.
