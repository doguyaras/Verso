# API istemcisi model geçidi entegrasyonu [v1]

Kurumun kendi model sağlayıcısını ve API anahtarını bağladığı yönetici uçları, kullanıcının model seçimi ve ilgili ekleyici alanlar. Karar: [ADR-0056](adr/0056-model-gecidi.md). Tüketici: panelin "Yapay zekâ sağlayıcıları", "Değer panosu", "Güven" ve "Soru sor" ekranları.

> **OpenAPI doğrulanmadı:** alanlar `ModelGatewayController`, `ModelChoiceController` ve `qa-api` kayıtlarından (`Model*`) çıkarıldı (ADR-0004). Generated client paketi: yok.

## Değişikliklerin özeti
- Yeni uçlar: `/v1/admin/model-gateway/**` (yalnız `verso-admin`) ve `GET /v1/models` (oturum açmış her kullanıcı).
- Ekleyici, geriye uyumlu alanlar: `POST /v1/questions` ve `/v1/questions/stream` isteğinde isteğe bağlı `model`; `GET /v1/admin/trust` yanıtında `models`. Göndermeyen ve okumayan istemci etkilenmez; mobil ve internal çağıran etkilenmez.
- Yeni hata kodları: 11003, 11004, 11090–11099.

## Kurallar
- **Kritik:** `/v1/admin/model-gateway/**` yalnız `verso-admin` (ADR-0019). Diğerleri `403` (`90101`), kimliksiz `401`. Rol kontrolü gövde doğrulamasından sonra da sunucudadır; istemci ekranı yalnız gizler.
- **Kritik — anahtar yalnız yazılır:** `apiKey` yalnız istek gövdesinde (JSON) gider, URL'ye hiç girmez. Hiçbir yanıt anahtarı döndürmez; yalnız `keyStored` ve son dört karakter (`keyHint`, ör. `…a1b2`; servis hesabı JSON'unda `JSON`). İstemci anahtarı saklamaz, gösterilen alanı gönderimden sonra temizler.
- **Kritik — yerel mod:** `local` modda bulut türleri ve iç ağ dışındaki adresler kaydedilemez (`409 11091`), açık ya da kapalı.
- Yanıtlar `Cache-Control: no-store, private`; başarı ham JSON, hata zarflı. Sağlayıcının kendi hata metni hiçbir yanıtta yoktur.
- Bilinmeyen alanlar yok sayılır (ekleyici sözleşme).

## Bağlantılar
| Uç | Gövde | Yanıt |
|---|---|---|
| `GET /v1/admin/model-gateway/connections` | – | `ModelConnection[]` |
| `GET …/connections/{id}` | – | `ModelConnection` |
| `POST …/connections` | `ModelConnectionRequest` | `201` + `ModelConnection`. Açıksa önce sağlayıcıda en çok 8 token'lık bir deneme çağrısı; başarısızsa `422 11095`, hiçbir şey saklanmaz |
| `PUT …/connections/{id}` | `ModelConnectionRequest` (tür değişmez; `apiKey` boşsa kayıtlı anahtar kalır, doluysa döndürme sayılır) | `ModelConnection`. Adres, ilk model, anahtar değişince ya da açılınca yeniden denenir |
| `POST …/connections/{id}/key` | `{"apiKey": "…"}` | `ModelConnection` (`keyVersion` +1). Bağlantı açıksa yeni anahtar önce denenir |
| `POST …/connections/{id}/test` | – | `{ok, status, latencyMs}`; `status` ∈ `OK`, `AUTH_FAILED`, `UNREACHABLE`, `MODEL_NOT_FOUND`, `RATE_LIMITED`, `FAILED` |
| `DELETE …/connections/{id}` | – | `204`; bir rotada kullanılıyorsa `409 11097` |

`ModelConnectionRequest`: `name` (1–80), `type` (`OPENAI`, `AZURE_OPENAI`, `ANTHROPIC`, `BEDROCK`, `GOOGLE_GENAI`, `MISTRAL`, `OLLAMA`, `OPENAI_COMPATIBLE`), `baseUrl` (https; http yalnız iç ağ adresine), `region`, `deployment` (Azure), `project` (Vertex), `models` (1–20 ad), `apiKey` (yalnız yazılır; Bedrock için `<erişim anahtarı kimliği>:<gizli anahtar>`, Vertex için servis hesabı JSON'u), `enabled` (boxed: yokken yeni bağlantıda açık, değişiklikte aynı kalır).

`ModelConnection`: `id`, `name`, `type`, `baseUrl`, `region`, `deployment`, `project`, `models`, `enabled`, `locality` (`LOCAL` iç ağ, `EXTERNAL` dış), `egressHost`, `keyStored`, `keyHint`, `keyVersion`, `keyRotatedAt`, `validatedAt`, `validationStatus`, `createdAt`, `updatedAt`.

## Rotalar ve ayarlar
| Uç | Gövde | Yanıt |
|---|---|---|
| `GET …/routes` | – | `{routes: {ANSWER: [...], FOLLOW_UP: [...], DECOMPOSITION: [...], SKILL_CONDENSE: [...], SKILL_RUN: [...], EMBEDDING: []}, builtInModel, userModelChoice, keyStoreReady, piiMasking}` (`piiMasking` sonradan eklendi, ADR-0059) |
| `PUT …/routes/{task}` | `{"entries": [{"connectionId": "…"\|null, "model": "…"}]}` (en çok 5; ilk birincil, sonrakiler yedek; `connectionId: null` yerleşik model) | Güncel rotalar. `EMBEDDING` ya da bilinmeyen/kapalı bağlantı, listede olmayan model, tekrar eden adım `400 11096`; local modda dış bağlantı `409 11091` |
| `PUT …/settings` | `{"userModelChoice": true\|false, "piiMasking": true\|false}`: iki alan da isteğe bağlı, yok ya da `null` olan ayar değişmez, en az biri zorunlu (ikisi de yoksa `400`). Yalnız `userModelChoice` gönderen eski istemci aynen çalışır | Güncel rotalar |

Yedeğe yalnız 5xx, 429 ve ağ hatasında geçilir; akışta yalnız ilk token'dan önce. Rota boşsa (ya da rotadaki bütün bağlantılar kapalıysa) yerleşik model cevaplar.

## Fiyat, bütçe, kullanım
| Uç | Gövde / parametre | Yanıt |
|---|---|---|
| `GET` / `PUT …/prices` | `{connectionId, model, inputPerMillion, outputPerMillion, currency}` | Fiyat listesi / kaydedilen fiyat; bilinmeyen bağlantı ya da model `400 11098` |
| `DELETE …/prices/{connectionId}?model=` | – | `204` |
| `GET` / `PUT …/budgets` | `{scope: CONNECTION\|TEAM, scopeKey, hardLimit, softLimit?}` | Bütçe listesi / kaydedilen bütçe; uyarı sınırı üst sınırdan büyükse `400 11098` |
| `DELETE …/budgets/{scope}?key=` | – | `204` |
| `GET …/usage?month=YYYY-MM` | ay (varsayılan bu ay) | `{month, requests, promptTokens, completionTokens, cost, rows[], teams[], hiddenTeams, minTeamUsers, budgets[], maskedValues, masked}`; geçersiz ay `400 11099` |

`maskedValues` ve `masked` (ADR-0059, sonradan eklendi): ayın bulut çağrılarında kişisel veri süzgecinin yer tutucuyla değiştirdiği değer sayısı, toplam ve türe göre (`{"TCKN": 3, "IBAN": 1}`; türler `TCKN`, `VKN`, `IBAN`, `CARD`, `EMAIL`, `PHONE`, `PLATE`). Çağrı başına farklı değer sayısıdır, çağrılar boyunca toplanır; değerlerin kendisi hiçbir yerde yoktur.

`rows[]`: `{connectionId, connectionName, model, task, requests, promptTokens, completionTokens, cost, failures}` (bağlantı silinmişse `connectionName` `null`). `teams[]`: yalnız o ay en az `minTeamUsers` farklı kullanıcısı olan ekipler; kişi bazında satır **yoktur**. `budgets[].state` ∈ `OK`, `SOFT`, `HARD`.

## Kullanıcı model seçimi
- `GET /v1/models` → `{enabled, choices: [{id, label, external}]}`. Yönetici açmadıysa `enabled: false`, liste boş: istemci seçiciyi göstermez.
- `POST /v1/questions` ve `/stream` gövdesine isteğe bağlı `"model": "<choices[].id>"`. Sunulmayan seçim `400 11004` (model çağrılmaz). `external: true` seçimde soru ve pasajlar dış sağlayıcıya gider; istemci bunu seçenekte belirtir.

## Güven raporu
`GET /v1/admin/trust` yanıtına `models: {known, connections: [{name, type, locality, host, region}]}` (yalnız açık bağlantılar). `known: false`: liste okunamadı; "bağlantı yok" demek değildir.

## Hata kodları
| Kod | HTTP | Anlam | İstemci davranışı | Yeniden dene |
|---|---|---|---|---|
| 11003 | 429 | Rotadaki fiyatlı modellerin aylık üst sınırı doldu | "Aylık model bütçesi doldu; yöneticinize başvurun" | hayır (ay dönene ya da sınır artana kadar) |
| 11004 | 400 | Seçilen model sunulmuyor | seçimi sıfırla, `GET /v1/models`'ı yenile | hayır |
| 11090 | 404 | Bağlantı yok | listeyi yenile | hayır |
| 11091 | 409 | Yerel modda bulut ya da iç ağ dışı bağlantı | türü/adresi değiştir | hayır |
| 11092 | 400 | Türün istediği alan eksik ya da biçimsiz | alanın altında göster | hayır |
| 11093 | 409 | Ad kullanılıyor | adı değiştir | hayır |
| 11094 | 503 | Anahtar deposu kurulu değil (`SECRET_MODEL_GATEWAY_KEK`) | kurulum ekibine yönlendir | kurulumdan sonra |
| 11095 | 422 | Sağlayıcı bağlantıyı kabul etmedi (doğrulama çağrısı) | anahtarı, adresi, modeli kontrol et | düzeltip |
| 11096 | 400 | Rota geçersiz | rotayı yeniden seç | hayır |
| 11097 | 409 | Bağlantı bir rotada kullanılıyor | önce rotayı değiştir | hayır |
| 11098 | 400 | Fiyat ya da bütçe geçersiz | alanı düzelt | hayır |
| 11099 | 400 | Ay geçersiz | `YYYY-MM` | hayır |

Sürüm uyumluluğu: hepsi ekleyicidir; `API-Version` 1.0 değişmedi.
