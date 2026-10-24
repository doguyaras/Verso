# ADR-0035: Akışlı cevap (SSE), sunucu tarafında iptal ve sayfa görünümü

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras (görev: "dürüst aşamalarla akışlı cevap, sunucu tarafında iptal, sayfa görüntüleyici")
- **İlgili eşik (referans Bölüm 24):** ilk metnin ekrana gelme süresi; iptal edilen cevap oranı (`verso.qa.questions{outcome="cancelled"}`); sayfa görünümü isteklerinin p99'u.

## Bağlam

- `POST /v1/questions` cevabı tek parça döner (ADR-0012). CPU'da cevap 20–60 sn sürer (docs/capacity.md); kullanıcı bu sürede yalnız bir sayaç görür. "Bekleme durduruldu" yalnız istemcinin beklemesini bırakır; sunucu modeli sonuna kadar çalıştırır ve cevabı saklar (ADR-0017'de açık iş).
- Atıflar sunucuda, **bütün metin** üzerinden eşlenir (llm-rules 3.3, ADR-0020): `[n]` metin bitmeden doğrulanamaz.
- Kaynak kartı yalnız 280 karakterlik alıntıyı gösterir; kullanıcı sayfanın kendisini göremez. Sayfa metni zaten `document.document_page`'de saklanır (V1).
- Araştırma: `research_notes/.../ui_ux.md` madde 2 (akış UX'i: gerçek aşamalar, gövde canlı bölge dışında, Durdur sunucuyu da durdurur, `proxy_buffering off`) ve madde 4 (önce sayfa metni görünümü, `CSS.highlights`, PDF.js değil); `rag_quality.md` "SSE" (Servlet'te `SseEmitter` yeter; atıf eşleme akış bitince).

## Seçenekler

| Seçenek | Artı | Eksi |
|---|---|---|
| A. Mevcut uca `Accept: text/event-stream` ile ikinci biçim | Tek yol | Aynı uç iki sözleşme taşır; API istemcileri ve OpenAPI karışır |
| B. Ayrı uç `POST /v1/questions/stream` (SSE), JSON uç olduğu gibi | JSON istemcileri etkilenmez; akış kendi sözleşmesiyle | İki uç birlikte bakılır |
| C. WebFlux / WebSocket | Tam reaktif | Yığın değişir (Servlet + sanal thread), CSP ve proxy yeniden ele alınır |

Atıflar için: (1) akışta `[n]`'yi anında düğmeye çevirmek (doğrulanmamış numara atıf gibi görünür, ret); (2) metni akıtıp işaretleri **yalnız doğrulamadan sonra** çizmek (seçilen).
İptal için: (a) iptal edilen turu "iptal" olarak saklamak (doğrulanmamış metin geçmişe girer, sayaçlar şişer); (b) **hiç saklamamak** (seçilen).

## Karar

**B.** `POST /v1/questions/stream`, `text/event-stream`, gövde `POST /v1/questions` ile aynı (`QuestionRequest`, aynı 16 KB / `Content-Length` sınırı). JSON uç değişmedi.

**Olaylar (sıra sözleşmedir):**

1. `stage` `{stage, retrieved, relevant}`: yalnız gerçekten çalışan adımlar: `RETRIEVING` (soru vektörleniyor ve aranıyor), `PASSAGES_FOUND` (`retrieved` gelen, `relevant` modele giden pasaj sayısı), `WRITING` (sohbet slotu alındı, model çağrıldı). Cevaplanabilirlik kapısı kapalıysa `WRITING` gelmez.
2. `delta` `{text}`: modelin metni geldiği gibi. **Doğrulanmamış**: `[n]` işaretleri henüz atıf değildir.
3. `citations` `{answer, citations}`: JSON ucun `answer` ve `citations` alanlarıyla birebir aynı, bütün metin üzerinde `CitationExtractor` ile (geçersiz numara silinir). İstemci taslağı bu metinle değiştirir.
4. `done` `{outcome, found, mode, model, conversationId, turnId}`: JSON uçtaki anlamlarıyla. Atıfsız metin `UNCITED`, model "bulunamadı" derse ya da kapı kapalıysa `NOT_FOUND`: bugünkü sonuçların aynısı.
5. `error` `{code, message, retryAfter}`: son olay; ortak zarfın sabit mesajı, sağlayıcı metni asla (llm-rules 2.3). 503 kodlarında `retryAfter` 5.
- Model sessizken 10 sn'de bir `:keep-alive` yorumu (proxy zaman aşımı ve kopmuş istemciyi fark etmek için).
- **Akış açılmadan** reddedilenler JSON uçtaki gibi düz HTTP hatasıdır (zarf + durum kodu): 400, 401, 411/413, açık devre 503, başkasının ya da dolu konuşma 404/409. İstemci `Accept: text/event-stream, application/json` gönderir.

**İş parçacığı ve sınırlar.** Ön kontroller (devre, konuşma sahipliği, token grupları) istek thread'inde; geri kalanı sanal bir thread'de, çağıranın güvenlik bağlamı (denetim aktörü) ve trace id'siyle. Sağlayıcının token'ları kendi I/O thread'inde yalnız kuyruğa girer; istemciye yazmak bu thread'in işidir (yavaş istemci Reactor Netty olay döngüsünü tutmaz). **Aynı** bulkhead semaforu (instance başına 1 sohbet slotu, 5 sn bekleme), aynı devre kesici ve aynı süre sınırı (local 90 sn, cloud 30 sn; ADR-0008) iki uçta da geçerlidir. `SseEmitter`'ın kendi zaman aşımı yalnız yedek sınırdır (slot beklemesi + sohbet sınırı + 30 sn).

**İptal (sunucu tarafında).** İstemci bağlantıyı kapatınca bir sonraki yazma (delta ya da keep-alive) başarısız olur; thread aboneliği `dispose()` eder. Ollama'da bu, Reactor Netty bağlantısını kapatır ve Ollama üretimi durdurur (JSON yoldaki zaman aşımı kesmesiyle aynı mekanizma, faz 8'de ölçüldü); slot `doFinally` ile hemen boşalır. İstemcinin gidişi en geç bir sonraki token'da, model sessizse en geç ~10–20 sn'de (bir ya da iki keep-alive yazması) fark edilir. **Tur saklanmaz:** metin hiç doğrulanmadı ve kullanıcı istemedi; geçmiş satırı, kullanım sayacı ve `QUESTION_ASKED` denetim olayı yazılmaz; `verso.qa.questions{outcome="cancelled"}` artar ve `Question stream cancelled` satırı (yalnız süre) loglanır. Bulut SDK'larının (Anthropic, OpenAI) aboneliği bırakınca sağlayıcıdaki üretimin de durduğu doğrulanmadı (Bölüm "Net kanıt bulunamayan").

**Egress.** Akışlı Ollama çağrısı Boot'un otomatik yapılandırılmış `WebClient.Builder`'ından geçer; `InetAddressFilter` reaktif bağlayıcıya da uygulanır (llm-rules 1.1, `OllamaChatClientTest`). Cloud modunda giden veri JSON uçla aynıdır (yalnız `"stream":true` eklenir; `AnthropicCloudModeTest`).

**Güvenlik zinciri.** Bir async dispatch, REQUEST dispatch'inde yetkilendirilmiş isteğin devamıdır ve yeni bir handler çalıştırmaz; `platform-security` `DispatcherType.ASYNC`'i `permitAll` eder (aksi halde durumsuz istek, akış bittiğinde kimliksiz kalır ve yanıt yazıldıktan sonra 401 denenir).

**Kenar proxy.** `deploy/edge/nginx.conf`'ta yalnız `location = /v1/questions/stream`: `proxy_buffering off`, `proxy_cache off`, `gzip off`, `proxy_read_timeout 150s` (keep-alive bunu yalnız ölü upstream için tetikler), aynı başlık temizliği. Uygulama ayrıca `X-Accel-Buffering: no` gönderir. Panel CSP'si değişmedi: `connect-src 'self'` fetch akışını kapsar.

**Panel.** `fetch` + `ReadableStream` (EventSource Authorization başlığı gönderemez), kendi SSE ayrıştırıcısı (`panel/js/stream.js`: parça sınırları, CRLF/CR, çok satırlı `data`, yorumlar). Aşama satırı ve `role="status"` duyurusu yalnız aşama değişiminde ve sonda; taslak metin `aria-live="off"` günlüğün içinde düz metin, `[n]` işaretsiz, griye yakın; `citations` gelince ADR-0024/0025 cevap görünümü (alıntılar, vurgular, kaynak sütunu, takip soruları, puanlama, geçmiş) aynen çizilir. Akışlı cevapta kelime kelime belirme yok (her hareket ayarında). "Durdur" isteği keser; iptal ekranı geçmişe kaydedilmediğini söyler. Akış hiç gelmezse (JSON dönen eski proxy/sunucu, uç yok, boş gövde, bağlantı kurulamadı) bir kez JSON uca düşülür; olaylar geldikten sonra kopan bağlantı hatadır (soru ikinci kez sorulmaz).

**Sayfa görünümü.** `GET /v1/documents/{id}/pages/{number}` → `{documentId, number, unit, pageCount, text, truncated}`. `number` PDF'te sayfa, DOCX/TXT/MD'de bölümdür (ADR-0016), 1..100 000. Okuma, listeleme ve retrieval'la **aynı** `READABLE` principal filtresiyle tek SQL'de (ADR-0028): erişilemeyen ve olmayan belge aynı 404 `DOCUMENT_NOT_FOUND` (10001); okunabilir belgede olmayan sayfa 404 `DOCUMENT_PAGE_NOT_FOUND` (10005). `verso-admin` yalnız principal'larının izin verdiğini okur (yönetmek okumak değildir). Metin en çok 60 000 kod noktası (`left()` ile veritabanında kesilir; ayrıştırıcının sayfa sınırı 50 000), kesilirse `truncated: true`. `Cache-Control: private, no-store`. **Denetim olayı yok:** ADR-0026 okumaları kaydetmez ("liste, detay"); sayfa okuması da bir okumadır. Panelde kaynak satırındaki "Sayfayı aç" sağdan bir çekmece açar: önceki/sonraki (sınırlar içinde), alıntı `CSS.highlights` ile (yoksa `<mark>`), Esc kapatır, odak çekmecede tutulur ve açan düğmeye döner.

## Sonuçlar

- Sıcak yol: uzak çağrı sayısı değişmedi (2). Akış ucu aynı bütçe, bulkhead, devre ve sınır altında; ek olarak her 10 sn'lik sessizlikte bir keep-alive yazması. Sayfa ucu: 0 uzak çağrı, tek okuma.
- `qa-api`'ye beş olay kaydı ve `AnswerStage`; `document-api`'ye `DocumentPageResponse` (eklemeli). Hata kodu 10005 yeni.
- İptal edilen soru hiçbir yerde iz bırakmaz (geçmiş, sayaç, denetim); yalnız sayaç ve süre logu.
- Yeni config yok (keep-alive aralığı ve kesme sınırı sabit; ölçülürse parametreye taşınır).

## Doğrulama

`QuestionStreamTest` (birim; Docker'sız), `QuestionStreamApiTest`, `DocumentPageApiTest`, `OllamaChatClientTest` (akış ve egress), `AnthropicCloudModeTest` (cloud akışı), `ComposeConfigTest` (nginx), `scripts/panel.test.mjs` (ayrıştırıcı, aşama, iptal, geri düşme, sayfa çekmecesi). Kanıt: `docs/evidence/adr-0035-dogrulama.md`.

## Net kanıt bulunamayan

- Anthropic/OpenAI SDK aboneliği bırakıldığında sağlayıcı tarafında üretimin durması (bağlantı kapanır; fatura ve üretim davranışı ölçülmedi).
- Gerçek tarayıcıda ve gerçek Ollama ile ilk token süresi, ekran okuyucu davranışı ve iptalin Ollama'daki etkisi bu değişiklikte canlı ölçülmedi (Docker kapalıydı).
- Üretimde TLS sonlandırıcısının akışı tamponlayıp tamponlamadığı kuruluma bağlıdır (README "Kenar proxy").
