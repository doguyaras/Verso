# API istemcisi soru-cevap entegrasyonu [v1]

Kullanıcının kendi yüklediği belgelere Türkçe soru sorup kaynaklı (belge + sayfa) cevap almasını sağlar. Cevap yalnız `READY` belgelerden gelir; ilgili pasaj yoksa model çağrılmaz ve "bulunamadı" cevabı döner.

> **OpenAPI doğrulanmadı:** uç ve alanlar `QuestionController` ve `qa-api` DTO'larından çıkarıldı (ADR-0004). Generated client paketi: yok.

Bu doküman **3 Ekim 2026** tarihli `feature/doguyaras-faz-5-soru-cevap` branch'indeki koda dayanır (ADR-0012).

## Değişikliklerin özeti
- Yeni uç: `POST /v1/questions`.
- Her yanıtta (hatalar dahil) `X-Rag-Mode: local|cloud` başlığı.
- Yeni hata kodları: 11001, 11002, 11010, 11011, 10030, 10031.
- Her 503 yanıtı `Retry-After` (saniye) taşır.

## Kurallar
- **Kritik:** Atıflar sunucuda üretilir. İstemci, cevap metnindeki `[n]` işaretini yalnız `citations` listesindeki `number` ile eşler; listede olmayan numara gösterilmez.
- Başarı yanıtı ham, hata yanıtı zarflıdır (ADR-0004). Soru ve cevap kişisel veri içerebilir: istemci log'una ve analitiğe yazılmaz.
- Bilinmeyen alanlar yok sayılır; `mode` için bilinmeyen değer genel "AI" etiketi olarak gösterilir.

## Kullanılan endpoint'ler
| Method | Path | Auth | Idempotency | Sınır | Zarf |
|---|---|---|---|---|---|
| POST | `/v1/questions` | user JWT | yok (yan etkisi yok) | gövde ≤ 16 KB, `Content-Length` zorunlu; 503 `11002` (model meşgul) | başarı ham, hata zarflı |

## API sözleşmesi
### Soru sor
İstek: `{"question": "Yıllık izin kaç gün?"}` (1–1000 karakter).

#### Senaryo: cevap bulundu
`HTTP 200`, ham, `X-Rag-Mode: local`:
```json
{ "answer": "Yıllık izin yirmi iş günüdür [1].", "found": true, "outcome": "ANSWERED",
  "citations": [ { "number": 1, "documentId": "01a0fe8e-…", "fileName": "izin-yonetmeligi.pdf", "page": 3 } ],
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
| `mode` | `local` \| `cloud` | Bilinmeyen → "AI" |
| `model` | string | Bilgi amaçlı |

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

### Servis bilgisi (faz 10)
`GET /v1/info` (token gerekir): `{"mode":"local","chatModel":"gemma4:e2b","embeddingModel":"bge-m3:567m"}`, `Cache-Control: no-store, private`. Panelin sistem ekranı ve "hangi model cevaplıyor" göstergeleri içindir.

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

## Önceki sürüme göre farklar
- Önceki doküman yok.

## Manuel test akışı
1. Bir belge yükle ve `READY` olmasını bekle (`docs/api-documents-integration-v1.md`).
2. `curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"question":"Yıllık izin kaç gün?"}' http://127.0.0.1:8080/v1/questions`
3. Alakasız bir soru sor: `found:false`, `citations:[]`.
4. Otomatik: `bash scripts/qa-smoke.sh`.

## Deploy ve uyumluluk
Yeni uç. CPU'da cevap on saniyeler sürebilir. Sunucu tarafında en kötü durum ≈ 105 sn'dir (10 sn embedding + 5 sn slot bekleme + 90 sn chat): istemci zaman aşımını **en az 120 sn** tutmalı ve bekleme göstergesi göstermeli.

## Açık sorular
- Streaming (kelime kelime cevap) gerekecek mi? (ADR-0008: SSE yeniden değerlendirme koşulu)
