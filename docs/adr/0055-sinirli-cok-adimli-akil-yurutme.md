# ADR-0055: Sınırlı çok adımlı akıl yürütme (öğrenen katman faz 5)

- **Durum:** Kabul edildi. Kod, testler ve ölçüm düzeneği hazır; varsayılan **kapalı** (`VERSO_QA_REASONING_ENABLED=false`). Gerçek modelle uçtan uca ölçüm bekliyor (Docker kapalıyken yapılamadı): `node scripts/eval.mjs --set multihop --compare`.
- **Tarih:** 2026-10-27
- **Karar verenler:** doguyaras.
- **Uyguladığı:** ADR-0048 faz 5 (ilke 6: bulut modeli yalnız eleştiri ya da ayrıştırma için, her yetenek yalnız yerel modelle de çalışır). Faz 1: ADR-0049, faz 2: ADR-0052, faz 3: ADR-0053, faz 4: ADR-0054.
- **Dayanak:** `research_notes/Verso öğrenen bilgi katmanı/research.md` §5: AgenticRAGTracer'ın en zor çok adımlı diliminde GPT-5 bile %22,6 EM; hataların çoğu erken duran ya da taşan zincirler; küçük modelde açık planlama modülü olmadan 15-20 F1 kaybı; çelişki tespitinde (MAGIC, WikiContradict) açık ve kapalı bütün modeller zayıf. Öneri: sınırlı (≤ 3) şablonlu ayrıştırma, alt soru başına arama, alıntıya bağlı birleştirme; çelişkiyi sorgu anında modele değil, çevrimdışı yapısal denetimlere bırakmak.
- **İlgili eşikler (referans Bölüm 24):** ADR-0008 sıcak yol bütçesi (local-GPU p99 15 sn, local-CPU 60 sn, cloud 20 sn); sohbet slotu darboğazı (`chat-concurrency: 1`, ADR-0051); yeni servis, tablo ya da migration yok.
- **Genişlettiği:** ADR-0035 (akış aşamaları), ADR-0046 (aynı slot, kısa sınır, sezgisel kapı, etkisizleştirme), ADR-0047 (dayanak kontrolü), ADR-0052 (çelişki adayı tablosu), ADR-0054 (sistem modeli araması). Değiştirmediği: ADR-0022 (hibrit arama ve kapı), ADR-0028 (okuyucu ilkeleri), ADR-0043 (rerank).

## Bağlam

- "P1 olayında BT ile müşteri destek SLA'ları arasındaki fark nedir?" gibi sorular iki belgeden iki ayrı bilgi ister. Tek geçişli arama bu sorunun tek vektörüyle çoğu zaman belgelerden yalnız birini üst sıralara getirir; küçük model ötekini uydurur ya da atlar.
- Örnek belgeler bunu bilerek zorlaştırır: iki ayrı P1–P4 tablosu (BT, müşteri desteği), iki ayrı kabul toleransı (WMS %2, ERP %5), üç tutar bazlı onay matrisi (eval/README.md "Zorlaştıran kurgu").
- Kısıtlar: yerel küçük model (`gemma4:e2b`), tek sohbet slotu, içerik loglanmaz (llm-rules 2.1), erişim her sorguda okuyucunun ilkeleriyle (ADR-0028), local modda dışarı trafik yok.

## Seçenekler

| Konu | Seçenekler | Seçilen ve neden |
|---|---|---|
| Ne zaman ayrıştırılır | her soru · LLM sınıflandırıcı · **ucuz kapı** | Sözcüksel kapı + sistem modeli varlık sayımı: model çağrısı yok. LLM sınıflandırıcı yalnız kapı kararsız kalırsa düşünülür; önce ölçülür (uygulanmadı) |
| Kaç alt soru | sınırsız ajan döngüsü · **en çok 3, tek çağrı** | Araştırma §5: zincirler erken durur ya da taşar; sınır yapılandırmayla aşılamaz (`HARD_CAP`) |
| Alt soru başına cevap | her alt soruya cevap + birleştirme (N + 1 chat) · **alt sorular yalnız arar, tek sentez** | Sohbet slotu darboğaz: alt soru sayısı ne olursa olsun 2 chat çağrısı (ayrıştırma + sentez) |
| Çelişki | modele sormak · **yalnız saklanmış çevrimdışı denetimler** | Araştırma §5: modeller çelişkiyi bulamıyor ve yerini gösteremiyor. ADR-0052 tarama tablosu ve ADR-0054 farklı tanımlar zaten var |
| Bulunamayan parça | modele bırakmak · **sunucu sabit cümle ekler** | Küçük model tahmin eder; sunucu cümlesi her istemcide aynı ve test edilebilir |
| Bulut | her zaman yerel · **mod bulutsa ayrıştırma da bulut** | ADR-0048 ilke 6. Tek `ChatModel` modu izler: local modda bütün çağrılar yerel, dışarı trafik yok |

## Karar

### Kapı (`ReasoningGate`, model ve ağ yok)

1. En az 5 kelime (Türkçe katlamayla: "FARKI" = "farki").
2. **Karşılaştırma:** "fark…", "karşılaştır…", "kıyas…", "mukayese…" ile başlayan bir sözcük; "hem … hem"; "arasında/arasındaki/ilişki…" ile birlikte "ile" ya da "ve".
3. **Çok parçalı:** iki soru işareti arasında sözcük, ya da iki soru sözcüğü (ne, kaç, kim, nasıl, hangi, nerede, neden …) "ve", "ayrıca" ya da virgülle.
4. Sözcükler karar vermezse (`verso.qa.reasoning.entity-gate`): soru sistem modelinde çağıranın okuyabildiği belgelerden **iki ayrı SYSTEM ya da CODE** varlığı adlandırıyorsa (`DocumentSystemModel.lookup`, tek indeksli okuma, süreç içi). Okuma hatası "varlık yok" sayılır.
5. Ölçüm (`ReasoningGateEvalTest`, her build): sözcüksel kapı çok adımlı setin 19/20'sinde ateşler (kaçan `mh-05`: iki sistem adı, karşılaştırma sözcüğü yok; varlık kapısına kalır), tek adımlı 140 sorunun 3'ünde (%2,1; "sayım farkı" ad olarak, "ERP ile WMS arasında"). Set ve kapı sözcükleri aynı oturumda yazıldı: isabet iyimserdir.

### Ayrıştırma (`QuestionDecomposer`, 1 chat çağrısı)

- Aynı sohbet slotu; slot `chat-wait` içinde boşalmazsa **beklemeden tek geçişe** düşülür (503 değil, `MODEL_BUSY` sayılmaz). Kendi sınırı `decompose-timeout` 10 sn; aşılınca çağrı iptal edilir (Ollama üretimi durur, ADR-0046 ile aynı mekanizma).
- Prompt: talimat yalnız sistem mesajında; soru `[[SORU]]` ayracında etkisizleştirilmiş (`PromptBuilder.defuse`); sistem modelinin sorudaki sistem/kod etiketleri ipucu olarak (en çok 6, her biri ≤ 40 karakter). Örnek set dışı bir konuda ("kargo ile kurye").
- Çıktı güvenilmeyen veridir. Kabul yalnız: "1." "2." ("3.") sırasıyla, başka satır yok, 2-3 satır, her alt soru 8–200 karakter, en az 3 kelime, ADR-0046 kuralına göre bağlama muhtaç değil, talimat/rol/bağlantı sözcüğü (talimat, yok say, ignore, system, prompt, sen artık, https …) ve işaretleme (`[ ] < > { } | \``) yok, tekrar yok, sorunun kendisi değil. **Tek bir satır bozuksa bütün ayrıştırma reddedilir** (tek geçiş).
- Çağrı modu izler: cloud modda ayrıştırma bulut sağlayıcısına gider (soru ve etiketler; etiketler cevap prompt'undaki pasajlarla aynı veri sınıfıdır), local modda yalnız yerel model.

### Alt soru araması ve birleştirme (`MultiStepReasoning`)

- Her alt soru **soru gibi** aranır: çağıranın `Reader`'ı (erişim sorgunun içinde, ADR-0028), hibrit arama, isteğe bağlı rerank, cevaplanabilirlik kapısı. Alt soruya ayrı cevap üretilmez.
- Birleştirme: alt soru başına en çok `passages-per-step` (3), toplam en çok `max-passages` (6; üç alt soruda 2'şer); aynı parça bir kez listelenir, iki alt soruya da yazılır. Tek numaralı liste: atıflar ADR-0012/3.3 ile, dayanak ADR-0047 ile, sorulan soruya göre doğrulanır.
- Bütün hazırlık (ayrıştırma + alt aramalar) `timeout` 20 sn ile sınırlı. Aşılırsa, alt arama hata verirse ya da alt sorular hiçbir şey bulamadığı halde bütün soru bulduysa **tek geçiş**: bütün soru için zaten getirilmiş pasajlarla normal cevap (ek arama yok). Ayrıştırma hataları devreyi etkilemez.
- Hiçbir alt soru ve bütün soru bir şey bulamazsa sabit "bulunamadı" cevabı, sentez çağrısı yok; iz yine döner.

### Sentez (1 chat çağrısı, cevap çağrısının yerine)

- Sistem mesajı: cevap kuralları + "pasajlar alt sorulara göre gruplanmıştır; önce her alt soruyu kendi grubuyla, sonra soruyu cevapla; 'Bilgi bulunamadı' işaretli alt soru için tahmin yürütme ve bir şey yazma". Modelden çelişki kararı **istenmez**.
- Kullanıcı mesajı: "Alt soru n: …" başlıkları (etkisizleştirilmiş), altında `[[BELGE k]]` pasajları; başka gruptan paylaşılan pasaj numarasıyla anılır; pasajı olmayan alt soruda "Bilgi bulunamadı."; en sonda sorulan soru.
- Pasajı olmayan her alt soru için sunucu, model metninden **sonra** sabit cümleyi ekler: “<alt soru>” için belgelerde bilgi bulunamadı. (Dayanak kontrolünün konumları bu yüzden geçerli kalır; cevap `NOT_FOUND` ise eklenmez.) Cümle geçmişe cevabın parçası olarak yazılır.

### Çelişki notu (yalnız saklanmış denetimler)

- `qa.contradiction_candidate` (ADR-0052): iki parçası da bu cevapta atıf alan ve iki tarafın saklı SHA-256'sı pasajın şimdiki metniyle aynı olan aday → `CONTRADICTION_SCAN`. Okuma: atıflı parça kimlikleriyle tek salt-okunur sorgu (`ContradictionRepository.between`).
- Sistem modeli (ADR-0054): aynı TERM ya da CODE'un katlanmış hali farklı iki tanımı, tanımlayan iki parçası da atıfta ve SHA-256'ları değişmemiş → `SYSTEM_MODEL`. Kapı varlıkları zaten okuduysa onlar, okumadıysa bir kez okunur.
- En çok 5 not; yalnız iki atıf numarası ve kaynak türü döner (dosya adları zaten `citations`'ta). Okuma hatası notsuz cevap verir.

### API (eklemeli, ADR-0035 sözleşmesi)

- `AnswerStage.DECOMPOSING` ("Soru alt sorulara ayrılıyor") ve `SUB_QUESTION` ("Alt soru 1/3 aranıyor"); `StreamStageEvent.step`, `steps` (yalnız `SUB_QUESTION`'da dolu, Boxed `Integer`).
- `AnswerResponse.reasoning` ve `StreamDoneEvent.reasoning` (en sonda): `ReasoningTrace{steps:[{number, question, found, citations}], contradictions:[{first, second, source}]}`; tek geçişte `null`. Saklanmaz, loglanmaz. Ayrıntı: `docs/api-questions-integration-v1.md`.
- Akış sırası: `RETRIEVING` → `DECOMPOSING` → `SUB_QUESTION`×n → `PASSAGES_FOUND` (birleştirilmiş sayılar) → `WRITING`. Geri dönüşte: `RETRIEVING` → `DECOMPOSING` → `PASSAGES_FOUND` → `WRITING`.

### Gözlem ve gizlilik

- Log satırına yalnız `reasoning=off|single|multi_step steps=n notFoundSteps=n decomposeMs=n contradictions=n` ve geri dönüşte `result=fallback_* trigger=<kapı nedeni>`. Alt soru, etiket, pasaj hiçbir log, metrik ya da span'e girmez (llm-rules 3.8; `MultiStepQuestionTest`, `MultiStepApiTest`).
- Metrik: `verso.qa.reasoning{result}` (`not_triggered`, `decomposed`, `fallback_invalid|timeout|busy|failed|retrieval|no_passages`), `verso.qa.reasoning.decompose.duration`. Alarm eklenmedi (bayrak kapalı; ölçümden sonra).

### Panel

- Cevabın altında katlanır "Nasıl araştırıldı" (`<details>`): alt sorular, her birinin atıf numaraları ya da "(bulunamadı)"; "Belgelerde bulunamayan kısım" notu; "Belgeler arasında çelişki" notu iki kaynağın dosya ve sayfasıyla. Alt sorular düz metin (`js/reasoning.js`: tek satır, bidi karakterleri atılır, kırpılır); `innerHTML` ve satır içi stil yok.

## Sonuçlar

- **Olumlu:** iki belgeli sorularda her taraf kendi aramasını alır; bulunamayan parça açıkça söylenir; çelişki yalnız kanıtlı ve kaynaklı gösterilir; her arıza tek geçişe düşer; bayrak kapalıyken davranış ve gecikme aynı (`MultiStepDisabledApiTest`).
- **Olumsuz / kabul edilen risk:**
  - Ateşleyen soruda +1 chat çağrısı (ayrıştırma) ve en çok 3 ek embedding + arama; slot o süre dolu. GPU'da ayrıştırma ADR-0046'daki yeniden yazmaya benzer kısa bir çağrıdır (ölçülmedi); CPU'da birkaç saniye beklenir, sınır 10 sn.
  - En kötü durum: süre sınırı adımlar arasında denetlenir, sürmekte olan bir alt arama kendi sınırına kadar (embedding 10 sn) sürebilir; hazırlık en çok ~30 sn, ardından sentez normal chat sınırıyla. Slot meşgulse ayrıştırma `chat-wait` (5 sn) bekler, ardından tek geçiş de aynı süre bekleyebilir. Bunlar bayrak kapalıyken yoktur; açma kararından önce GPU p95 ölçülür.
  - Katı biçim küçük modelde sık reddedilebilir (her ret tek geçiş, maliyet bir çağrı). Ölçümde `fallback_invalid` oranı yüksekse biçim gevşetilmez, prompt örneği iyileştirilir.
  - Sözcüksel kapı "sayım farkı" gibi ad kullanımlarında yanlış ateşler (%2,1).
  - Cloud modda ayrıştırma da sağlayıcıya gider (soru ve sistem/kod adları); yeni bir veri sınıfı çıkmaz.
  - Sentez prompt'u tek geçişten uzun olabilir (en çok 6 pasaj; tek geçişte de en çok `top-k` 5).
- **Etkilenen dosyalar / config yüzeyleri:**
  - `qa-api`: `AnswerStage`, `StreamStageEvent`, `ReasoningTrace`, `AnswerResponse.reasoning`, `StreamDoneEvent.reasoning`.
  - `qa-core`: `ReasoningGate`, `QuestionDecomposer`, `MultiStepReasoning`, `QaReasoningProperties`, `PromptBuilder.buildMultiStep`, `QuestionServiceImpl`, `AnswerStream.subQuestion`, `QuestionEventStream`, `ContradictionRepository.between`, `QaMetrics`.
  - Panel `js/{reasoning,stream}.js`, `screens/ask.js`, `css/panel.css`.
  - Config: `config/verso.yml` (`verso.qa.reasoning.*`), `application-local.yml` (`enabled` aynası, `ConfigDriftTest`), `compose.yaml`, `.env.example`, `deploy/prod.env.example` (`VERSO_QA_REASONING_ENABLED`).
  - Ölçüm: `eval/multihop-set.json` (20 soru, `scripts/multihop-set.test.js`), `scripts/eval.mjs --set multihop [--compare]`, `docs/evidence/cok-adimli-akil-yurutme.md`.
  - Kurallar: llm-rules 1.2 ve yeni 3.8, `repo-context.md` sıcak yol kaydı.
  - Migration yok.
- **Geri alma yolu:** `VERSO_QA_REASONING_ENABLED=false` (varsayılan) ve yeniden başlatmak. `reasoning` hep `null`, yeni aşamalar gelmez. Veri değişikliği yok.

## Yeniden değerlendirme koşulu

- Açmak için: `--compare` ölçümünde çok adımlı setin tüm-kaynaklar-atıfta oranı ve olgu oranı kapalı kola göre açıkça yükselir, kısmi sorularda "bulunamadı" söylenir ve tek adımlı sette (yanlış ateşleme %2) sonuç kötüleşmez; GPU'da ateşleyen sorunun p95'i ADR-0008 bütçesinde kalır.
- `fallback_invalid` oranı %30'u geçerse: prompt örnekleri ya da (ölçerek) bulut modunda bulut ayrıştırması.
- Gerçek kullanıcı sorularında kapı isabeti setten belirgin düşükse: varlık kapısı ağırlığı ya da kararsız sorular için LLM sınıflandırıcı (ayrı ölçüm).
- Çelişki tablosunun kapsamı genişlerse (kişisel belgeler, ilişki çelişkileri) not kaynağı genişletilir; modelin çelişki kararı vermesi ayrı ADR ister.
