# API istemcisi soru-cevap entegrasyonu [v1]

Kullanıcının kendi yüklediği belgelere Türkçe soru sorup kaynaklı (belge + sayfa) cevap almasını sağlar. Cevap yalnız `READY` belgelerden gelir; ilgili pasaj yoksa model çağrılmaz ve "bulunamadı" cevabı döner.

> **OpenAPI doğrulanmadı:** uç ve alanlar `QuestionController` ve `qa-api` DTO'larından çıkarıldı (ADR-0004). Generated client paketi: yok.

Bu doküman **3 Ekim 2026** tarihli `feature/doguyaras-faz-5-soru-cevap` branch'indeki koda dayanır (ADR-0012).

## Değişikliklerin özeti
- Yeni uç: `POST /v1/questions`.
- Her yanıtta (hatalar dahil) `X-Rag-Mode: local|cloud` başlığı.
- Yeni hata kodları: 11001, 11002, 11010, 11011, 10030, 10031.
- Her 503 yanıtı `Retry-After` (saniye) taşır.
- Sonradan eklenen, geriye uyumlu alan: `GET /v1/info` yanıtında `roles` (ADR-0019).
- Sonradan eklenen, geriye uyumlu alan: `citations[].excerpt` (alıntılanan pasajdan cevabın dayandığı kısım, ≤ 280 karakter). Eski istemciler bilinmeyen alanı yok sayar; sürüm değişmez.
- 24 Ekim 2026: `excerpt` artık pasajın başı değil, atfı taşıyan cevap cümlesiyle en çok örtüşen pencere (ADR-0020). Alan, tip ve sınır aynı; istemci değişikliği gerekmez.
- Sonradan eklenen, geriye uyumlu alanlar (ADR-0023, 24 Ekim 2026): istekte isteğe bağlı `conversationId`, yanıtta `conversationId` ve `turnId`. Her cevap kullanıcının geçmişine yazılır; geçmiş, puanlama ve yeni hata kodları (11020, 11022): [`docs/api-conversations-integration-v1.md`](api-conversations-integration-v1.md).
- Ekleyici alan (ADR-0056, 28 Ekim 2026): istekte isteğe bağlı `model` (kullanıcı model seçimi; yalnız yönetici açtıysa). Sunulmayan seçim `400 11004`, rotadaki fiyatlı modellerin bütçesi dolmuşsa `429 11003`: [`docs/api-model-gateway-integration-v1.md`](api-model-gateway-integration-v1.md).
- Yeni uç (ADR-0035, 24 Ekim 2026): `POST /v1/questions/stream`, aynı cevabın server-sent events akışı. `POST /v1/questions` değişmedi; akış istemeyen istemci hiçbir şey yapmaz.
- Sonradan eklenen, geriye uyumlu (ADR-0055, 27 Ekim 2026; kurulumda varsayılan kapalı): yanıtta ve `done` olayında `reasoning`, akışta `DECOMPOSING` ve `SUB_QUESTION` aşamaları, `stage` olayında `step` ve `steps`. Tanımadığı aşamayı genel metinle gösteren istemcinin değişmesi gerekmez.

## Kurallar
- **Kritik:** Atıflar sunucuda üretilir. İstemci, cevap metnindeki `[n]` işaretini yalnız `citations` listesindeki `number` ile eşler; listede olmayan numara gösterilmez.
- **Kritik (akış):** `delta` metni doğrulanmamıştır. İçindeki `[n]` atıf gibi gösterilmez (panel gizler); cevabın son hâli `citations` olayındaki `answer`'dır, taslak onunla değiştirilir.
- Başarı yanıtı ham, hata yanıtı zarflıdır (ADR-0004). Soru ve cevap kişisel veri içerebilir: istemci log'una ve analitiğe yazılmaz.
- Bilinmeyen alanlar yok sayılır; `mode` için bilinmeyen değer genel "AI" etiketi olarak gösterilir.

## Kullanılan endpoint'ler
| Method | Path | Auth | Idempotency | Sınır | Zarf |
|---|---|---|---|---|---|
| POST | `/v1/questions` | user JWT | yok (yan etkisi yok) | gövde ≤ 16 KB, `Content-Length` zorunlu; 503 `11002` (model meşgul) | başarı ham, hata zarflı |
| POST | `/v1/questions/stream` | user JWT | yok | aynı gövde ve sınırlar; aynı sohbet slotu (instance başına 1) | başarı `text/event-stream`; akış açılmadan önceki hata zarflı JSON |

## API sözleşmesi
### Soru sor
İstek: `{"question": "Yıllık izin kaç gün?"}` (1–1000 karakter).

#### Senaryo: cevap bulundu
`HTTP 200`, ham, `X-Rag-Mode: local`:
```json
{ "answer": "Yıllık izin yirmi iş günüdür [1].", "found": true, "outcome": "ANSWERED",
  "citations": [ { "number": 1, "documentId": "01a0fe8e-…", "fileName": "izin-yonetmeligi.pdf", "page": 3, "unit": "PAGE",
                   "excerpt": "Madde 12 - Çalışanlara yılda yirmi iş günü ücretli yıllık izin verilir." } ],
  "mode": "local", "model": "gemma4:e2b" }
```

#### Senaryo: belgelerde yok
`HTTP 200`: `{"answer":"Belgelerde bu sorunun cevabı bulunamadı.","found":false,"outcome":"NOT_FOUND","citations":[],"mode":"local","model":"gemma4:e2b"}`. Model pasajları okuyup aynı cümleyi yazdığında da `outcome` `NOT_FOUND` olur.

#### Senaryo: model atıf yazmadı
`HTTP 200`, `found: false`, `outcome: "UNCITED"`, `citations: []`, ama `answer` modelin metnidir (sabit "bulunamadı" cümlesi değil). "Kaynak gösterilemedi" uyarısıyla gösterilir.

#### Senaryo: model kapalı
`HTTP 503`, zarflı, `"code": 11001`, `Retry-After: 5`. Bu kadar bekleyip tekrar dene. Model art arda hata verirse sunucu 15 sn boyunca modeli hiç denemeden aynı yanıtı verir.

#### Alan eşleme
| Alan | Tip | Not |
|---|---|---|
| `answer` | string | Düz metin; `[n]` işaretleri `citations`'a göre bağlantıya çevrilir |
| `found` | boolean | Yalnız `ANSWERED`'da true |
| `outcome` | `ANSWERED` \| `NOT_FOUND` \| `UNCITED` | Görünümü bu seçer: `NOT_FOUND` "bulunamadı", `UNCITED` "kaynak gösterilemedi" uyarısı. Faz 10'da eklendi (geriye uyumlu); bilinmeyen değer `found`'a göre gösterilir |
| `citations[].number` | number | Metindeki `[n]` |
| `citations[].documentId` | UUID | `GET /v1/documents/{id}` ile açılır |
| `citations[].fileName`, `page` | string, number | "izin-yonetmeligi.pdf, s. 3" |
| `citations[].unit` | `PAGE` \| `SECTION` | DOCX, TXT ve MD sayfasızdır: `page` bölüm numarasıdır, "notlar.docx, bölüm 2" diye gösterilir (ADR-0016). Eksik ya da bilinmeyen → sayfa |
| `citations[].excerpt` | string, ≤ 280 karakter | Alıntılanan pasajın, bu `[n]`'i taşıyan cevap cümleleriyle en çok ortak sözcüğü ve sayısı olan bir ya da iki cümlesi (ADR-0020); örtüşme yoksa pasajın başı. Pasaj 280 karaktere sığıyorsa tamamı. Her zaman pasaj metnidir, model metni değildir. Boşluklar tek boşluğa indirilmiş; kesildiyse kelime sınırında, kesilen yerde `…` (sonda, uzun cümlenin ortasından başlıyorsa başta da). Pasaj metni boşsa `""`. Yalnız gösterim içindir (`[n]` üzerine gelince açılan kart): düz metin olarak gösterilir, HTML olarak yorumlanmaz (belge içeriği güvenilmeyen veridir); istemci log'una ve analitiğe yazılmaz. Sonradan eklendi (geriye uyumlu); eksikse kart yalnız dosya adı ve sayfayı gösterir. Sınır Unicode kod noktasıyla sayılır: emoji gibi karakterlerde JS `length` 280'i aşabilir |
| `mode` | `local` \| `cloud` | Bilinmeyen → "AI" |
| `model` | string | Bilgi amaçlı |
| `conversationId`, `turnId` | UUID \| null | Cevabın kaydedildiği konuşma ve tur (ADR-0023); puanlama ve devam sorusu için. Kayıt başarısızsa ikisi de `null`: cevap gösterilir, puanlama gizlenir |
| `contextQuery` | string \| null | Takip sorusu (ADR-0046): soru konuşmanın son turlarıyla tek başına anlaşılan bir arama sorgusuna yeniden yazıldıysa aramada kullanılan o sorgu (en çok 300 karakter; model metnidir, düz metin olarak gösterin). Soru olduğu gibi arandıysa `null`. Saklanmaz, geçmişte görünmez |
| `unverified` | `[{start, end, citations}]` \| null | Dayanak kontrolü (ADR-0047). Liste, atıflı olup sayısı, sayı sözcüğü ya da kodu atıf yaptığı pasajda aynı bağlamda geçmeyen cümleleri cevap sırasıyla verir. `start`/`end` cümlenin `answer` içindeki `[start, end)` aralığıdır: UTF-16 birimi (JS dizi indeksi), `[n]` işaretleri dahil. `citations` cümlenin taşıdığı atıf numaralarıdır. Bütün cümleler destekleniyorsa `[]`. Kontrol kapalıysa ya da model çağrılmadıysa (`NOT_FOUND`) `null`. Önerilen gösterim: cümleyi hafifçe işaretleyin, "dayanağı doğrulanamadı" deyin, metni değiştirmeyin. Aralığı kullanmadan önce `answer` sınırları içinde olduğunu doğrulayın. Kontrol yalnız sayı ve kodlara bakar: işaret yoksa cevap doğru demek değildir. Saklanmaz, geçmişte görünmez |
| `memory` | `{cardId, verifiedAt, expiresAt}` \| null | Onaylı bilgi belleği (ADR-0049). Cevap, uzmanın onayladığı bir bilgi kartından geldiyse kartın kimliği, onay zamanı ve onayın geçerlilik sonu (ISO-8601). O zaman `model` `verified-answer`, `outcome` `ANSWERED`, `unverified` `null`'dır ve model çağrılmamıştır; `citations` kartın kaynaklarıdır (sunucuda, sorana görünen sayfa metnine eşlenmiş). Önerilen gösterim: "Uzman onaylı" rozeti. Kart yalnız alıntıladığı **bütün** belgeleri okuyabilen kişiye gelir; okuyamayan ya da kart eskimiş/itirazlıysa alan `null` ve cevap normal yoldan gelir. Geçmişte görünmez |
| `procedure` | `{skillId, name, description, steps: [{position, instruction, sources: [{documentId, fileName, page, unit, passageId}]}]}` \| null | Onaylı prosedür (ADR-0053). Sorunun, onaylı bir prosedürün örnek sorularına yeterince benzediği (kosinüs ≥ 0,90) ve prosedürün **bütün** kaynaklarını sizin yetkinizle okuyabildiğiniz durumda dolu; yalnız kurulumda `VERSO_QA_SKILLS_SERVING_ENABLED=true` ise. Cevabın yerine geçmez: önerilen gösterim cevabın üstünde "Bu bir prosedür: <name>" kutusu, adımlar ve kaynakları (`passageId` MCP `verso_fetch` kimliğidir). Geçmişte görünmez |
| `reasoning` | `{steps: [{number, question, found, citations: [int]}], contradictions: [{first, second, source}]}` \| null | Çok adımlı cevap (ADR-0055, eklemeli). Yalnız kurulumda `VERSO_QA_REASONING_ENABLED=true` ise ve soru karşılaştırma ya da çok parçalı bulunup en çok 3 bağımsız alt soruya ayrıldıysa dolu; tek geçişli cevapta `null`. `steps`: alt sorular arama sırasıyla; `found=false` olan alt soru için belgelerinizde bilgi bulunamamıştır ve `answer`'ın sonunda sunucu bunu açıkça yazar (“<alt soru>” için belgelerde bilgi bulunamadı.); `citations` o alt sorunun pasajlarından gelen atıf numaralarıdır. `contradictions`: cevaptaki iki atfın (`first` < `second`, `citations[].number`) çeliştiği, **önceden saklanmış** bir denetimin bildiği durumlar; `source` `CONTRADICTION_SCAN` (bilgi sağlığı taraması, ADR-0052) ya da `SYSTEM_MODEL` (aynı terimin iki farklı tanımı, ADR-0054). Model çelişki kararı vermez. Önerilen gösterim: katlanır "Nasıl araştırıldı" kutusu, bulunamayan kısımlar ve "Belgeler arasında çelişki" notu iki kaynağıyla. Alt soru metni model çıktısıdır: düz metin olarak gösterin. Saklanmaz; geçmişte görünmez |

**`UNCITED` ve dolu `citations` (ADR-0047):** hiçbir atıflı cümle atıf yaptığı pasajla desteklenmiyorsa (`unverified` atıflı her cümleyi içeriyorsa) `outcome` `UNCITED`, `found` `false` olur. `citations` yine dolu gelir ki kullanıcı kaynakla karşılaştırabilsin. `UNCITED`'da atıf beklemeyen istemci kartları göstermeye devam etmeli; uyarı metni "kaynak gösterilemedi" yerine "dayanağı doğrulanamadı" olmalıdır.

#### fetch örneği
```js
const res = await fetch('/v1/questions', { method: 'POST',
  headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
  body: JSON.stringify({ question }) });
const mode = res.headers.get('X-Rag-Mode');            // "local" | "cloud"
if (res.ok) {
  const { answer, citations } = await res.json();
  const byNumber = new Map(citations.map(c => [c.number, c]));
  return answer.replace(/\[(\d+)]/g, (m, n) => byNumber.has(+n) ? link(byNumber.get(+n)) : '');
}
const { error } = await res.json();
if (res.status === 503) retryLater(error.code);
```

### Akışlı cevap (ADR-0035)
`POST /v1/questions/stream`, gövde `POST /v1/questions` ile aynı. İstek başlıkları: `Authorization`, `Content-Type: application/json`, `Accept: text/event-stream, application/json` (akış açılmadan önceki hata JSON zarfla döner). `EventSource` Authorization başlığı gönderemez: `fetch` + `ReadableStream` kullanın.

**Akış açılmadan dönen hatalar** JSON uçla aynıdır, durum koduyla: 400 `90000`, 401 `90100`, 411 `11011`, 413 `11010`, 404 `11020` / 409 `11022` (konuşma), 503 `11001` + `Retry-After` (devre açık). Bunlarda model çağrılmaz.

**Akış açıldıktan sonra** `HTTP 200`, `Content-Type: text/event-stream`, `Cache-Control: no-store, private`, `X-Accel-Buffering: no`, `X-Rag-Mode`. Olaylar bu sırayla gelir:

| Olay | `data` | Not |
|---|---|---|
| `stage` | `{"stage":"REWRITING","retrieved":null,"relevant":null}` | Yalnız takip sorusu konuşmayla yeniden yazılırken (ADR-0046); bağımsız soruda ve konuşmanın ilk sorusunda gelmez. Tanımadığınız bir `stage` değerini genel bir "hazırlanıyor" metniyle gösterin |
| `stage` | `{"stage":"RETRIEVING","retrieved":null,"relevant":null}` | Soru vektörleniyor ve belgeler aranıyor |
| `stage` | `{"stage":"MEMORY_FOUND","retrieved":null,"relevant":null}` | Onaylı bir bilgi kartı eşleşti (ADR-0049, "Onaylı bilgi bulundu"): ardından doğrudan `citations` ve `done` gelir; `PASSAGES_FOUND`, `WRITING` ve `delta` gelmez |
| `stage` | `{"stage":"DECOMPOSING","retrieved":null,"relevant":null,"step":null,"steps":null}` | Çok adımlı akıl yürütme (ADR-0055; kurulumda `VERSO_QA_REASONING_ENABLED=true` ve soru karşılaştırma ya da çok parçalıysa): "Soru alt sorulara ayrılıyor". `RETRIEVING`'den sonra gelir. Ayrıştırma başarısız olursa ya da süre dolarsa ardından tek geçişli cevabın `PASSAGES_FOUND` ve `WRITING` aşamaları gelir |
| `stage` | `{"stage":"SUB_QUESTION","retrieved":null,"relevant":null,"step":1,"steps":3}` | Her alt soru için bir kez (en çok 3): "Alt soru 1/3 aranıyor". Alt sorunun metni yalnız `done` olayındaki `reasoning`'de gelir |
| `stage` | `{"stage":"PASSAGES_FOUND","retrieved":5,"relevant":2}` | `relevant` 0 ise model çağrılmaz, `WRITING` gelmez. Çok adımlı cevapta sayılar alt soruların birleştirilmiş pasajlarıdır. `step` ve `steps` (ADR-0055, eklemeli) yalnız `SUB_QUESTION`'da dolu, diğer aşamalarda `null` |
| `stage` | `{"stage":"WRITING","retrieved":null,"relevant":null}` | Sohbet slotu alındı, model yazıyor |
| `delta` | `{"text":"Yıllık izin "}` | 0..n kez; doğrulanmamış model metni |
| `citations` | `{"answer":"…","citations":[…],"unverified":[…]}` | JSON uçtaki `answer`, `citations` ve `unverified` ile birebir aynı (sunucuda, bütün metin üzerinde doğrulanmış; dayanak kontrolü atıf doğrulamasından sonra, ADR-0047) |
| `done` | `{"outcome":"ANSWERED","found":true,"mode":"local","model":"gemma4:e2b","conversationId":"…","turnId":"…","contextQuery":null,"memory":null,"procedure":null,"reasoning":null}` | Son olay; alanlar JSON uçtaki anlamlarıyla |
| `error` | `{"code":11001,"message":"The language model is unavailable, try again later.","retryAfter":5}` | Başarısız akışın son olayı; `citations` ve `done` gelmez, hiçbir şey saklanmaz |

Model sessizken 10 sn'de bir `:keep-alive` yorum satırı gelir (olay değildir). `error` kodları: 11001 (model kapalı ya da süre sınırı aşıldı), 11002 (meşgul), 10030, 10031, 99997, 99999. `retryAfter` yalnız 503 kodlarında, saniye.

Örnek akış (kısaltılmış):
```text
event:stage
data:{"stage":"RETRIEVING","retrieved":null,"relevant":null}

event:stage
data:{"stage":"PASSAGES_FOUND","retrieved":5,"relevant":1}

event:stage
data:{"stage":"WRITING","retrieved":null,"relevant":null}

event:delta
data:{"text":"Yıllık izin yirmi "}

:keep-alive

event:delta
data:{"text":"iş günüdür [1]. Ek izin [7]."}

event:citations
data:{"answer":"Yıllık izin yirmi iş günüdür [1]. Ek izin.","citations":[{"number":1,"documentId":"0199…","fileName":"izin.pdf","page":3,"unit":"PAGE","excerpt":"Yıllık izin yirmi iş günüdür.","collectionId":null,"collectionName":null}]}

event:done
data:{"outcome":"ANSWERED","found":true,"mode":"local","model":"gemma4:e2b","conversationId":"0199…","turnId":"0199…"}
```
`[7]` taslakta vardı, getirilmiş bir pasaja eşlenmediği için `citations.answer`'da yok. Atıfsız metin `done.outcome = UNCITED` ile biter (`citations: []`); istemci bunu "kaynak gösterilemedi" uyarısıyla gösterir, taslaktaki numaraları atıf gibi göstermez.

**İptal.** İstemci bağlantıyı kapatırsa (fetch `AbortController.abort()`), sunucu model çağrısını iptal eder (Ollama üretimi durur) ve **turu saklamaz**: geçmişte, kullanım sayılarında ve denetim kaydında iz kalmaz. Sunucu bunu bir sonraki yazmada fark eder: model yazıyorsa hemen, sessizse en geç ~20 sn içinde.

**Geri düşme.** Akış hiç gelmezse (JSON dönen eski proxy ya da sunucu, 404 `90010`/405/415/501, boş gövde, bağlantı kurulamadı) panel aynı soruyu bir kez `POST /v1/questions`'a gönderir. Olaylar geldikten sonra kopan bağlantı hatadır; soru ikinci kez gönderilmez.

```js
const res = await fetch('/v1/questions/stream', { method: 'POST', signal: controller.signal,
  headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', Accept: 'text/event-stream, application/json' },
  body: JSON.stringify({ question }) });
if (!res.ok) throw await res.json();                     // akış öncesi hata: zarf
const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
// Satırları kendiniz ayrıştırın: "event:" ve "data:" alanları, boş satırda olay; ":" ile başlayan satır yorumdur.
```

### Servis bilgisi (faz 10)
`GET /v1/info` (token gerekir): `{"mode":"local","chatModel":"gemma4:e2b","embeddingModel":"bge-m3:567m","roles":["verso-user"]}`, `Cache-Control: no-store, private`. Panelin sistem ekranı ve "hangi model cevaplıyor" göstergeleri içindir. `roles` (ADR-0019, geriye uyumlu ek alan): çağıranın Verso rolleri, yüksekten alçağa (`verso-admin`, `verso-user`); her kimlikli çağıranda `verso-user` vardır. İstemci bunu yalnız arayüzü gizlemek için kullanır ve bilmediği değeri yok sayar; asıl kontrol sunucudadır (yalnız-admin uçta 403 `ACCESS_DENIED` 90101).

## Hata kodları ve ekran davranışı
| code | HTTP | Anlam | Ekran aksiyonu | Retry? |
|---|---|---|---|---|
| 11001 | 503 | Dil modeli kapalı | "Asistan şu an yanıt veremiyor" | evet, birkaç sn sonra |
| 11002 | 503 | Dil modeli meşgul | "Yoğunluk var, tekrar deneniyor" | evet, otomatik |
| 10030 | 503 | Embedding modeli kapalı, yavaş ya da meşgul | aynı | evet, `Retry-After` kadar sonra |
| 10031 | 409 | Belgeler yeniden indekslenmeli (model değişti) | "Belgeleriniz güncelleniyor" | hayır, yöneticiye bildir |
| 11010 | 413 | Gövde 16 KB'tan büyük | alanı vurgula | hayır |
| 11011 | 411 | `Content-Length` yok (chunked gövde) | istemci hatası; gövdeyi string olarak gönder | hayır |
| 90000 | 400 | Soru boş ya da 1000 karakterden uzun | alanı vurgula | hayır |
| 90001 | 400 | Gövde okunamadı (bozuk JSON) | istemci hatası | hayır |
| 90012 | 415 | `Content-Type` JSON değil | istemci hatası | hayır |
| 90020 | 400 | API sürümü geçersiz | istemci hatası | hayır |
| 90100 | 401 | Oturum yok | yeniden giriş | – |
| 90103 | 503 | Kimlik sağlayıcısına ulaşılamıyor | "Giriş servisi geçici olarak yok" | evet, `Retry-After` |
| 99997 | 503 | Veritabanı geçici olarak yok | "Geçici sorun" | evet, `Retry-After` |

İstek `conversationId` taşıyorsa iki kod daha gelebilir: 11020 (konuşma yok, model çağrılmaz) ve 11022 (konuşma dolu). Ekran davranışları [`docs/api-conversations-integration-v1.md`](api-conversations-integration-v1.md) tablosundadır; `conversationId` göndermeyen istemci bunları almaz.

## Önceki sürüme göre farklar
- Önceki doküman yok.
- v1 içinde eklenen alanlar (geriye uyumlu, ek alan): `outcome` (faz 10), `citations[].excerpt` (23 Ekim 2026), istekte `conversationId` ve yanıtta `conversationId`/`turnId` (24 Ekim 2026, ADR-0023), yanıtta ve `done` olayında `contextQuery`, akışta `REWRITING` aşaması (25 Ekim 2026, ADR-0046), yanıtta ve `citations` olayında `unverified` (25 Ekim 2026, ADR-0047; `UNCITED` artık dolu `citations` ile de gelebilir), yanıtta ve `done` olayında `memory`, akışta `MEMORY_FOUND` aşaması, `model` değeri `verified-answer` (26 Ekim 2026, ADR-0049), yanıtta ve `done` olayında `procedure` (26 Ekim 2026, ADR-0053), yanıtta ve `done` olayında `reasoning`, akışta `DECOMPOSING` ve `SUB_QUESTION` aşamaları, `stage` olayında `step`/`steps` (27 Ekim 2026, ADR-0055; varsayılan kapalı). Var olan alanların adı, tipi ve anlamı değişmedi.
- `citations[].excerpt` seçimi (24 Ekim 2026, ADR-0020): pasajın başı yerine cevabın dayandığı pencere. Alan, tip ve 280 sınırı aynı; baştaki `…` yeni olabilir, düz metin olarak gösterilir.
- `POST /v1/questions/stream` (24 Ekim 2026, ADR-0035): yeni uç; var olan uç ve alanlar değişmedi.

## Manuel test akışı
1. Bir belge yükle ve `READY` olmasını bekle (`docs/api-documents-integration-v1.md`).
2. `curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"question":"Yıllık izin kaç gün?"}' http://127.0.0.1:8080/v1/questions`
3. Alakasız bir soru sor: `found:false`, `citations:[]`.
4. Otomatik: `bash scripts/qa-smoke.sh`.
5. Akış: `curl -sN -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -H "Accept: text/event-stream, application/json" -d '{"question":"Yıllık izin kaç gün?"}' http://127.0.0.1:8080/v1/questions/stream` → `stage` olayları hemen, `delta`'lar model yazdıkça; Ctrl+C sonrası log'da `Question stream cancelled`.

## Deploy ve uyumluluk
Yeni uç. CPU'da cevap on saniyeler sürebilir. Sunucu tarafında en kötü durum ≈ 105 sn'dir (10 sn embedding + 5 sn slot bekleme + 90 sn chat): istemci zaman aşımını **en az 120 sn** tutmalı ve bekleme göstergesi göstermeli.

Akış ucu için yoldaki her proxy yanıtı tamponlamamalı ve okuma zaman aşımı en az 30 sn olmalı (keep-alive 10 sn'de bir). Kenar proxy'de bu, yalnız `location = /v1/questions/stream` için ayarlıdır (`deploy/edge/nginx.conf`: `proxy_buffering off`, `proxy_read_timeout 150s`); önündeki TLS sonlandırıcısında da aynısı gerekir (README "Kenar proxy"). Tamponlayan bir proxy akışı bozmaz, yalnız bütün olayları sonda birden teslim eder.

## Açık sorular
- Bulut sağlayıcısında (Anthropic, OpenAI) bağlantı kesilince üretimin de durduğu ölçülmedi (ADR-0035).
