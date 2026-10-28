# API istemcisi Etkinlik merkezi ve ana sayfa entegrasyonu [v1]

Yöneticinin denetim ekranı (Etkinlik merkezi) ve herkesin ana sayfası için salt okunur uçlar. Karar: [ADR-0058](adr/0058-platform-arayuzu.md). Tüketici: panelin "Etkinlik merkezi", "Ana sayfa" ve "Komuta panosu" ekranları.

> **OpenAPI doğrulanmadı:** alanlar `AdminActivityController`, `HomeController`, `InfoController` ve `qa-api` içindeki `ActivityPageResponse`, `ActivityItemResponse`, `ActivitySummaryResponse`, `ActivityDetailResponse`, `HomeFeedResponse`, `ServiceInfoResponse` kayıtlarından çıkarıldı (ADR-0004). Generated client paketi: yok.

## Değişikliklerin özeti
- Yeni uçlar: `GET /v1/admin/activity`, `GET /v1/admin/activity/summary`, `GET /v1/admin/activity/{id}`, `GET /v1/home`.
- Eklemeli alan: `GET /v1/info` yanıtında `staleAfterDays` (int \| null). Mevcut alanlar değişmedi; eski istemci alanı yok sayar.
- Mobil ve internal çağıran etkilenmez. Yeni panel istemcisi: `api.activity(query)`, `api.activitySummary()`, `api.activityDetail(id)`, `api.home()`.

## Kurallar
- **Kritik:** `/v1/admin/activity*` yalnız `verso-admin` (ADR-0019). Başka kimlikli çağıran `403` (`90101 ACCESS_DENIED`), kimliksiz `401`. `/v1/home` her oturum açmış çağırana açıktır.
- Yanıtlar `Cache-Control: no-store, private`. Başarı ham JSON, hata zarflıdır.
- **Kişi yok:** hiçbir yanıt hesap kimliği (`sub`), kullanıcı adı ya da e-posta taşımaz. Aktör bir ekip (bilgi alanı), rol ya da sistem bileşenidir.
- **Erişim süzgeci:** belge ve kart adları yalnız isteyen yöneticinin okuyabildikleri için yazılır (ADR-0028); okuyamadığı alanın belge değişiklikleri hiç listelenmez. Yönetici rolü okuma hakkı değildir.
- Soru, cevap, istem ya da belge metni yoktur; ayrıntı yalnız üst veridir.
- Bilinmeyen alanlar ve bilinmeyen `type` sözcükleri yok sayılmaz, olduğu gibi gösterilir (yeni kaynaklar eklemeli gelir).

## `GET /v1/admin/activity`
| Parametre | Tip | Anlam |
|---|---|---|
| `category` | `RUN` \| `BACKGROUND` \| `HOOK` \| `KNOWLEDGE` \| `APPROVAL` | Sekme; yoksa hepsi |
| `type` | `[A-Z][A-Z0-9_]{1,39}` | Kaynağın sabit sözcüğü (aşağıda) |
| `status` | `RUNNING` \| `SUCCEEDED` \| `PARTIAL` \| `FAILED` \| `REGRESSION` \| `PENDING` \| `INFO` | Durum |
| `from`, `to` | ISO gün | Dahil günler, kurulumun saat diliminde; varsayılan son 7 gün; en çok 90 gün |
| `collection` | UUID | Yalnız bu bilgi alanının satırları |
| `page`, `size` | int | `size` 1–50 (varsayılan 25); `(page + 1) × size ≤ 500` |

Geçersiz değer `400` `11110 ACTIVITY_FILTER_INVALID`. `APPROVAL` satırları beklediği sürece, yaşından bağımsız listelenir.

Yanıt: `{ items[], page, size, hasMore, sources[], partial }`. `sources` kurulumdaki kaynak anahtarlarıdır (`qa`, `connector`, `document`, `audit`; beceri kütüphanesi kuruluysa `work`, ADR-0057): bir sekme boşsa istemci kaynağın kurulu olup olmadığını buradan bilir. `partial: true` bir kaynağın yanıt vermediğini söyler.

Satır (`items[]`):
| Alan | Tip | Anlam |
|---|---|---|
| `id` | `{kaynak}.{uuid}` | Ayrıntı ucunun anahtarı |
| `category`, `type`, `status` | string | Yukarıdaki sözcükler |
| `startedAt`, `finishedAt` | ISO, ISO \| null | Başlangıç (bekleyen için bekleme başlangıcı); bitmeyen için `null` |
| `actor.kind`, `actor.label` | `SYSTEM` \| `ROLE` \| `TEAM`, string | Kim; asla kişi |
| `title`, `detail` | string, string \| null | Türkçe özet ve tek satır sayı ya da sabit neden |
| `collectionId` | UUID \| null | Satırın bilgi alanı |
| `link.screen`, `link.targetId` | string, UUID \| null | Panelin açacağı ekran |
| `audit.targetType`, `audit.targetId`, `audit.action` | string, UUID \| null, string \| null | Denetim kaydı referansı (`AuditTargetType`, `AuditAction`) |
| `attention` | bool | Durum şeridindeki "dikkat istiyor" sayısına girer |

Türler: `CONNECTOR_SYNC`, `GOLDEN_EVAL`, `KNOWLEDGE_HEALTH`, `SYSTEM_MODEL` (arka plan); `HOOK_ACTION` (kanca); `DOCUMENTS_ADDED`, `DOCUMENTS_CHANGED`, `DOCUMENTS_DELETED`, `CARD_STALE`, `PROCEDURE_STALE` (bilgi); `CARD_TO_VERIFY`, `CARD_DISPUTED`, `PROCEDURE_APPROVAL`, `BULK_DELETION`, `CONTRADICTION` (onay); beceri kütüphanesi kuruluysa `SKILL_RUN` (çalışma: gün ve beceri başına, yalnız en az 5 farklı hesabın çalıştırdığı gruplar; kişi ve tek çalışma yok).

## `GET /v1/admin/activity/summary`
`{ attention, reasons[], last24h: { runs, background, hooks, knowledge }, pendingApprovals, checkedAt, partial }`. Son 7 gün; `reasons` en çok üç Türkçe neden, en acili önce (hata, gerileme, onay). `attention = 0` "Her şey yolunda" demektir.

## `GET /v1/admin/activity/{id}`
`{ item, facts[{label, value}], steps[{label, status, detail, durationMs}], sources[{name, format, readable, citations}], modelCalls[{purpose, local, provider, inputTokens, outputTokens, durationMs}] }`. Bilinmeyen kaynak, bozuk kimlik ya da kaynağın bilmediği satır `404` `11111 ACTIVITY_NOT_FOUND`. Günlük toplu satırların (belge değişiklikleri, silmeler, sistem modeli) ayrıntısı yoktur; istemci satırın kendisini gösterir. `sources[].name` okunamayan kaynakta `null`'dır.

## `GET /v1/home`
`{ outputs[{ kind: CARD | PROCEDURE, id, title, description, at }] }`. Çağıranın her kaynağını okuyabildiği en yeni en çok 5 onaylı kart ve 5 onaylı prosedür, en yeni önce. Kimin oluşturduğu ya da onayladığı yoktur.

## `GET /v1/info` eki
`staleAfterDays`: bilgi sağlığının eskime eşiği (gün). Panel atıf yapılan belgenin son değişikliği bundan eskiyse cevapta "Bu kaynak eskimiş" kartını gösterir. `null` ya da yoksa istemci 180 kullanır.

## Hata kodları
| Kod | HTTP | Anlam | İstemci |
|---|---|---|---|
| 11110 | 400 | Süzgeç geçersiz | Süzgeci sıfırla |
| 11111 | 404 | Etkinlik satırı yok | Satırın kendi bilgisini göster |
| 90101 | 403 | Yönetici değil | Ekranı gizle |
