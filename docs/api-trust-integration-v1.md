# API istemcisi güven raporu entegrasyonu [v1]

Kurulumun verisinin nereye gidebildiğini söyleyen salt okunur yönetici ucu. Karar: [ADR-0050](adr/0050-guven-ekrani.md). Tüketici: panelin "Güven" ekranı.

> **OpenAPI doğrulanmadı:** alanlar `AdminTrustController` ve `qa-api` içindeki `TrustResponse` kaydından çıkarıldı (ADR-0004). Generated client paketi: yok.

## Değişikliklerin özeti
- Yeni uç: `GET /v1/admin/trust`. Mevcut uçlar değişmedi; mobil ve internal çağıran etkilenmez.
- Yeni panel istemcisi: `api.trust()`.
- Ekleyici alan (ADR-0056): `models: {known, connections: [{name, type, locality, host, region}]}`, model geçidinin açık bağlantıları ve nereye gittikleri ([`docs/api-model-gateway-integration-v1.md`](api-model-gateway-integration-v1.md)).

## Kurallar
- **Kritik:** yalnız `verso-admin` (ADR-0019). Başka kimlikli çağıran `403` (`90101 ACCESS_DENIED`), kimliksiz `401`. İstemci ekranı `GET /v1/info` `roles`'a göre gizler; asıl kontrol sunucudadır.
- Yanıt `Cache-Control: no-store, private`. Başarı ham JSON, hata zarflıdır.
- Yanıtta secret, secret dosya adı, bağlayıcı adresi ya da içerik yoktur. İstemci yanıtı sunucuya geri göndermez; rapor tarayıcıda üretilir.
- Bilinmeyen alanlar yok sayılır (ekleyici sözleşme).

## Yanıt
| Alan | Tip | Anlam |
|---|---|---|
| `generatedAt` | ISO zaman | Yanıtın üretildiği an |
| `ai.mode` | `local` \| `cloud` | Çalışma modu |
| `ai.chatProvider` | string \| null | `ollama`, `anthropic`, `openai` |
| `ai.chatModel`, `ai.embeddingModel` | string | Model kimlikleri (embedding her modda yerel) |
| `egress.externalConnectorEgress` | bool | Bağlayıcılar internete çıkabilir mi (local modda hiç `true` olmaz) |
| `egress.allowedHosts` | string[] | İzinli host kalıpları; kapalıyken boş |
| `connectors.types` | string[] | `SMB`, `CONFLUENCE`, `SHAREPOINT` (tekil, sıralı) |
| `connectors.cloudConnectorPresent` | bool | Kaynağı internette olan bağlayıcı var mı |
| `connectors.known` | bool | `false`: liste okunamadı; `types` ve `cloudConnectorPresent` "yok" demek değildir |
| `mcp.enabled`, `mcp.audience` | bool, string \| null | MCP sunucusu ve hedef kitlesi |
| `identity.issuers[]` | `{name, host}` | Güvenilen kimlik sağlayıcılar; yalnız ad (`primary`, `entra`) ve host |
| `build.version`, `build.builtAt` | string \| null, ISO \| null | Derleme bilgisi |
| `build.imageDigest` | `sha256:<64 hex>` \| null | Kurulum `VERSO_IMAGE_DIGEST` geçtiyse |
| `audit.lastEventAt` | ISO \| null | Son denetim olayı; `null`: olay yok ya da okunamadı |
| `audit.outboxOldestAgeSeconds` | int \| null | En eski teslim bekleyen outbox kaydının yaşı; `null`: bekleyen yok |
| `backup.lastSuccessAt` | null | Uygulama yedeği okuyamaz; her zaman `null` |
| `backup.verification` | string | Yedeğin nerede doğrulandığı (sabit metin) |
| `guarantee` | string | Modun sabit Türkçe cümlesi |

Önerilen gösterim: metni olduğu gibi düz metin olarak gösterin (HTML yazmayın). `null` değerler "bilinmiyor" ya da "yok" olarak, nedenini söyleyerek gösterilir.
