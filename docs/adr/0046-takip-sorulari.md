# ADR-0046: Takip sorularının konuşmayla yeniden yazılması

- **Durum:** Kabul edildi (varsayılan açık; `VERSO_QA_FOLLOWUP_ENABLED=false` kapatır)
- **Tarih:** 2026-10-25
- **Karar verenler:** doguyaras. İstek: bir takip sorusunu konuşma geçmişiyle tek başına anlaşılan bir arama sorgusuna yeniden yazmak ve ölçmek (yol haritası `rag_quality.md` iş 7).
- **İlgili eşik (referans Bölüm 24):** ADR-0008 sıcak yol bütçesi (local-GPU QA p99 15 sn, local-CPU 60 sn); `docs/evidence/yuk-olcumu.md`'deki sohbet slotu darboğazı; aşağıda "Yeniden değerlendirme koşulu".
- **Genişlettiği:** ADR-0023 (geçmiş saklanır, prompt'a girmez), ADR-0035 (akış aşamaları). Değiştirmediği: ADR-0022 (hibrit arama ve kapı), ADR-0043 (rerank).

## Bağlam

- ADR-0023'ten beri her cevap konuşmasına yazılır, ama geçmiş hiçbir modele girmez: "Peki P2 için?" tek başına aranır. Eval v2 bu yüzden takip sorusu içermiyordu (`eval/README.md`).
- Yeni takip seti (`eval/followup-set.json`, 32 iki turlu konuşma, beklenen sayfalar PDF metninden doğrulandı) v1 davranışının açığını gösterdi: takip sorularında atıf isabeti **0,719**, aynı konuşmaların ilk sorularında 0,906.
- Dış kanıt (araştırma notu): ayrı bir "bağımsız soru yazma" adımı standart; kısa takipler ("peki ya ücret?") en kırılgan yer; Türkçe'de üretken modül yığmaya karşı uyarı var. Not, yeniden yazmayı yalnız kısa ya da zamirli sorularda önerir.
- Kısıtlar:
  - Geçmiş bir model girdisidir: aynı kullanıcının aynı konuşması dışından hiçbir şey girmez, log'a hiçbir şey çıkmaz (llm-rules 2.1, 3.1, 4.1).
  - Sohbet slotu darboğazdır (`chat-concurrency: 1`). Ek çağrı yalnız gerektiğinde, kısa ve sınırlı olmalı.
  - Cevap, kullanıcının sorduğu soruya yazılmalı. Yeniden yazma yanlışsa kullanıcı bunu görebilmeli.
- Ölçüm ve bütün sayılar: [`docs/evidence/takip-sorulari-olcumu.md`](../evidence/takip-sorulari-olcumu.md).

## Seçenekler

| Seçenek | Artı | Eksi | Sonuç |
|---|---|---|---|
| Hiçbir şey (v1) | Ek çağrı yok | Takip sorularında atıf 0,719 | Taban |
| **Sezgisel kural + tek yeniden yazma çağrısı, retrieval yazılmış sorguyla, cevap sorulan soruya** | Atıf 0,875, doğru bilgi 0,844; bağımsız sorular etkilenmez; kullanıcıya gösterilebilir bir sorgu | Takip sorusu başına +1 chat çağrısı (GPU p50 0,38 sn); küçük model bazen ayırt edici sözcüğü atar | **Seçildi** |
| Aynı, cevap prompt'una yazılmış sorgu da eklenir | – | Atıf 0,844, doğru bilgi 0,781: model iki soru satırını birlikte cevaplıyor | Ayar olarak kaldı, kapalı |
| Her konuşma sorusunu yeniden yazmak (kural yok) | `implicit` takipleri de yakalar | Bağımsız soruların her biri için boşa çağrı (139'da 135) | Seçilmedi |
| Modelsiz: ilk soru + takip sorusunu birlikte aramak | Çevrimdışı recall@5 0,969 (yazma 0,938), çağrı yok | Uçtan uca ölçülmedi; konu değişen konuşmada önceki konuyu taşır; kullanıcıya gösterilecek bir sorgu yok; fark tek soru | Yeniden değerlendirme adayı |
| Ham + yazılmış sorguyla iki arama, RRF | Yanlış yazmaya sigorta (araştırma notu) | İkinci embedding ve sorgu; `DocumentRetrieval` sözleşmesi değişir | Ölçülmedi, aday |

## Karar

**Akış** (`QuestionServiceImpl.search`, JSON ve akış uçlarında aynı):
1. `conversationId` yoksa, ayar kapalıysa ya da sezgisel kural soruyu bağımsız buluyorsa: soru olduğu gibi aranır, geçmiş okunmaz, model çağrılmaz.
2. Kural (`FollowUpRewriter.needsContext`): en çok 3 kelime ya da gösterme/zamir/bağlaç sözcüğü ("bu, bunun, o, onun, ora…, peki, öyle, diğer, hangisi…"; "ya da" sayılmaz). Ölçülen kesinlik 0,862, duyarlılık 0,781.
3. Konuşmanın son `turns` (2) turu `qa` şemasından tek salt-okunur sorguyla okunur (`account_id` ve `conversation_id` sorgunun içinde). Tur yoksa soru olduğu gibi aranır.
4. Akışta `REWRITING` aşaması ("Soru bağlamla yeniden yazılıyor") yalnız bu noktada gönderilir; istemci gitmişse model çağrılmaz.
5. Yeniden yazma prompt'u: talimat yalnız sistem mesajında; sorular (her biri ≤ 300 karakter) ve yalnız en son, atıflı cevabın başı (≤ 200 karakter, `[n]` işaretsiz), toplam ≤ 1000 karakter, en eski tur önce düşer; `[[KONUŞMA]]` ayracı içinde, pasajlar gibi etkisizleştirilmiş (`PromptBuilder.defuse`). Bir örnek, set dışı bir konuda ("kargo ücreti").
6. Çağrı aynı sohbet slotunda (bulkhead), kendi sınırıyla (`timeout` 10 sn; aşılınca iptal, Ollama üretimi durur). Hata, zaman aşımı ya da sorgu olmayan çıktı (boş, > 300 karakter) devreyi etkilemez; soru olduğu gibi aranır. Slot yoksa cevapla aynı 503 `MODEL_BUSY`.
7. Retrieval (embedding + sözcüksel + kapı) yazılmış sorguyla. Cevap prompt'u **sorulan soruyu** taşır; `answer-with-context` varsayılan kapalı (ölçüldü, yardım etmedi).
8. Yanıtta `contextQuery` (JSON ve `done` olayı, en sonda, eklemeli): soranın kendisine, nasıl anlaşıldığını görmesi için. Panel cevabın üstünde "Bağlamla arandı: …" notunu düz metin olarak gösterir. Saklanmaz, log'a, metriğe, span'e girmez. Log satırına yalnız `followUp=<sabit kelime>` ve `rewriteMs` eklenir; metrik `verso.qa.followup{result}` ve `verso.qa.followup.duration`.

**Varsayılan: açık.** Takip sorularında atıf isabeti 0,719 → 0,875, doğru bilgi 0,750 → 0,844 (soru bazında +5/−0 ve +4/−1); bağımsız sorularda retrieval aynı, uçtan uca fark gürültü düzeyinde ve olumlu yönde; maliyet yalnız takip sorularında GPU'da p50 381 ms, p95 676 ms.

## Sonuçlar

- **Olumlu:**
  - "Peki P2 için?" artık doğru tabloya gidiyor; BT ile müşteri desteği gibi örtüşen belgelerde ayrım ilk sorudan geliyor (`disambiguation` atıf 0,714 → 1,00).
  - Bağımsız sorularda maliyet yok: kural 139 bağımsız sorudan 4'ünde ateşledi, dördü de zarar görmedi.
  - Kullanıcı aramanın nasıl yorumlandığını görüyor.
- **Olumsuz / kabul edilen risk:**
  - Takip sorusu başına bir chat çağrısı daha; slot o süre dolu. CPU'da ölçülmedi (birkaç saniye beklenir, sınır 10 sn).
  - İşaret sözcüğü olmayan takipler (`implicit`, 7/32) yakalanmıyor: v1 gibi aranır.
  - Küçük model bazen ayırt edici sözcüğü atar ("sayım farkı" → "fark"). Yanlış yazılmış sorgu kullanıcıya görünür.
  - **Cloud modda** bağlama muhtaç soruda konuşmanın son iki sorusu ve son cevabın başı da sağlayıcıya gider (llm-rules 1.2, 3.6; VKED 5.2, aydınlatma metni ve kullanım politikası güncellendi). İstenmiyorsa ayar kapatılır.
  - Set küçük ve tek yazarlı; kural setin bağımsız sorularına bakılarak bir sözcük çıkarıldı ("aynı").
  - API: yeni `stage` değeri `REWRITING`; kendi istemcimiz dışındaki bir istemci tanımadığı değeri genel metinle göstermeli (entegrasyon dokümanında yazılı).
- **Etkilenen dosyalar / config yüzeyleri:**
  - `qa-api`: `AnswerStage.REWRITING`, `AnswerResponse.contextQuery`, `StreamDoneEvent.contextQuery`.
  - `qa-core`: `FollowUpRewriter`, `QaFollowUpProperties`, `QuestionServiceImpl`, `ConversationHistory.recent`, `TurnRepository.recent`, `PromptBuilder` (isteğe bağlı bağlam satırı), `QaMetrics`.
  - Panel `js/{stream,answer}.js`, `screens/ask.js`, `css/panel.css`.
  - Config: `config/verso.yml` (`verso.qa.followup.*`), `application-local.yml` (`enabled` aynası, `ConfigDriftTest`), `compose.yaml`, `.env.example`, `deploy/prod.env.example` (`VERSO_QA_FOLLOWUP_ENABLED`).
  - Ölçüm: `eval/followup-set.json`, `scripts/followup-eval.mjs`, `eval/embedding-olcumu/takip-{set,rapor}.mjs`, `eval/results/followup-*-gpu.json`, `retrieval-followup-gpu.json`.
  - Kurallar ve dokümanlar: llm-rules 1.2 ve yeni 3.6, `repo-context.md` sıcak yol kaydı, `api-questions-integration-v1.md`, `docs/uyum/0{1,2,3}`.
  - Migration yok: geçmiş tablosu ADR-0023'ün; okuma var olan `(conversation_id, created_at, id)` indeksini kullanır.
- **Geri alma yolu:** `VERSO_QA_FOLLOWUP_ENABLED=false` ve uygulamayı yeniden başlatmak. Davranış v1'e döner; `contextQuery` hep `null`, `REWRITING` gelmez. Veri değişikliği yok.

## Yeniden değerlendirme koşulu

- Takip sorularının uçtan uca atıf isabeti, kapalı kola göre farkını yitirirse (chat modeli ya da embedding değişince takip seti yeniden koşulur).
- CPU kurulumunda yeniden yazma p95'i 10 sn sınırına yaklaşırsa ya da QA p99 ADR-0008 bütçesini aşarsa (o kurulumda kapatılır ya da modelsiz seçenek ölçülür).
- "İlk soru + takip" ya da iki sorgulu RRF seçeneği uçtan uca ölçülüp yazmadan iyi çıkarsa.
- Gerçek kullanıcı konuşmalarında (sayı olarak) kural kesinliği 0,8'in altına düşerse ya da `implicit` takipler çoğunluktaysa.
- Geçmişin cevap prompt'una girmesi istenirse: ayrı ölçüm ve `verso-llm-review`.
