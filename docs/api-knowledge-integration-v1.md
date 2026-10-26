# API istemcisi onaylı bilgi entegrasyonu (cevap belleği ve altın set) [v1]

Verso'da `verso-admin` rolündeki yönetici, kendi sorduğu ve kaynaklı cevaplanan bir soruyu **onaylı bilgi kartına** çevirir. Aynı soru yeniden sorulduğunda, kartın alıntıladığı **bütün** belgeleri okuyabilen kişiye model yerine kartın cevabı "uzman onaylı" olarak gösterilir. Kaynak belge silinir, değişir ya da taşınırsa, onay süresi dolarsa ya da birkaç kullanıcı 👎 verirse kart kendiliğinden kullanımdan çıkar. Onaylanan her kart müşteriye özel altın sete girer ve her gece aramanın onu hâlâ bulup bulmadığı ölçülür.

> **OpenAPI doğrulanmadı:** uçlar ve alanlar `KnowledgeCardController`, `KnowledgeCardServiceImpl`, `KnowledgeEval` ve `qa-api` DTO'larından çıkarıldı (ADR-0004). Generated client paketi: yok.

Bu doküman **26 Ekim 2026** tarihli `feature/doguyaras-ogrenen-katman-1` branch'indeki koda (taban `develop` `0ed1ede`) dayanır. Kararlar: [ADR-0048](adr/0048-ogrenen-bilgi-katmani.md) (mimari), [ADR-0049](adr/0049-onayli-bilgi-karti-ve-altin-set.md) (faz 1).

## Değişikliklerin özeti

- Yeni yönetici uçları: `/v1/admin/knowledge-cards` (oluşturma, liste, okuma, onay, arşiv) ve `/v1/admin/knowledge-eval/runs` (liste, şimdi çalıştır).
- `POST /v1/questions` yanıtına ve akışın `done` olayına `memory` alanı; akışa `MEMORY_FOUND` aşaması; `model` için yeni değer `verified-answer` (ekleyici; ayrıntı: [api-questions-integration-v1.md](api-questions-integration-v1.md)).
- Yeni hata kodları: 11040–11048 (`qa` bloğu).
- **Faz 2 ([ADR-0052](adr/0052-bilgi-sagligi-ve-kancalar.md)), ekleyici:** `/v1/admin/knowledge-health` (rapor, dışa aktarım, teşhis koşuları), `/v1/admin/knowledge-hooks` (kanca kuralları), `/v1/admin/notifications` (kanca bildirimleri); hata kodları 11060–11063; `KnowledgeEvalRunResponse.trigger` için yeni değer `HOOK`, `staleReason` için yeni değer `CONTRADICTION` (istemciler bilmedikleri değeri göstermeden geçmeli); denetim eylemleri `KNOWLEDGE_DIAGNOSTICS_RUN`, `KNOWLEDGE_HEALTH_VIEWED`, `KNOWLEDGE_HOOK_FIRED`, `KNOWLEDGE_HOOK_RULE_CHANGED`, `KNOWLEDGE_NOTIFICATION_READ`; hedef türleri `KNOWLEDGE_HEALTH`, `KNOWLEDGE_HOOK`, `QUESTION_CLUSTER`, `CONTRADICTION`.
- Yeni denetim eylemleri: `KNOWLEDGE_CARD_CREATED`, `KNOWLEDGE_CARD_VERIFIED`, `KNOWLEDGE_CARD_STALE`, `KNOWLEDGE_CARD_DISPUTED`, `KNOWLEDGE_CARD_ARCHIVED`, `KNOWLEDGE_EVAL_RUN`; yeni hedef türleri `KNOWLEDGE_CARD`, `KNOWLEDGE_EVAL`. Sistem geçişlerinin aktörü `system:knowledge-memory`'dir.
- Mobil için etki yok (yönetici ekranı yalnız panelde). Mobil istemci `memory` alanını görmezden gelebilir; gösterirse "Uzman onaylı" rozeti önerilir.
- **Faz 3 ([ADR-0053](adr/0053-onayli-prosedurler-skill.md)), ekleyici:** `/v1/admin/skills` (taslak, liste, öneriler, okuma, yeni sürüm, onay, arşiv, sürüm farkı, SKILL.md dışa aktarımı); soru yanıtında `procedure`; MCP `verso_skills`/`verso_skill`; hata kodları 11070–11079; kanca tetikleyicisi `SKILL_STALE`, bildirim konusu `SKILL`; değerlendirme koşusunda `skill*` sayaçları. Mobil istemci `procedure` alanını görmezden gelebilir; gösterirse cevabın üstünde "Bu bir prosedür" kutusu önerilir.

## Kimlik ve yetki

Bütün uçlar `Authorization: Bearer <access token>` ister ve yalnız `verso-admin` içindir: başka herkes `403 90101`, token'sız istek `401 90100`. Yönetici kimliği token'dan okunur; istekte hesap kimliği yoktur. Yanıtlar `Cache-Control: no-store, private`.

## Uçlar

### `POST /v1/admin/knowledge-cards`

Yöneticinin **kendi** konuşmasındaki bir turdan kart oluşturur.

| Alan | Tip | Zorunlu | Not |
|---|---|---|---|
| `conversationId` | uuid | evet | |
| `turnId` | uuid | evet | |
| `question` | string (1–1000) | hayır | Eşleşmede kullanılacak kanonik soru; yoksa turun sorusu |
| `keyFacts` | string[] (≤ 10, her biri ≤ 200) | hayır | Altın setin ana bilgileri |
| `verify` | boolean | hayır (`false`) | `true`: yöneticinin onayı hemen kaydedilir (panelin "Onayla" düğmesi) |

Cevap metni ve atıflar turdan alınır, istekle değiştirilemez. Yanıt `201` ve kart (aşağıda). İki kişi kuralı açıksa `verify: true` kartı `DRAFT` bırakır (ikinci yöneticinin onayı gerekir).

### `GET /v1/admin/knowledge-cards?state=&page=&size=`

`state`: `DRAFT`, `VERIFIED`, `STALE`, `DISPUTED`, `ARCHIVED` ya da yok (hepsi). `page` ≥ 0, `size` 1–100 (20). Son değişiklik önce. Yanıt `{data: [...], page: {number, size, totalElements, totalPages}}`; her öğe:

| Alan | Tip | Not |
|---|---|---|
| `id` | uuid | |
| `question` | string | Kanonik soru; düz metin olarak gösterin |
| `state` | enum | yukarıdaki beş değer |
| `staleReason` | enum \| null | `SOURCE_DELETED`, `SOURCE_REPLACED`, `SOURCE_MOVED`, `SOURCE_CHANGED`, `EXPIRED`; yalnız `STALE`'de |
| `verifiers` / `requiredVerifiers` | number | geçerli döngüdeki onay sayısı / gereken (1 ya da 2) |
| `disputes` | number | geçerli döngüde 👎 veren farklı kullanıcı sayısı |
| `verifiedAt`, `expiresAt`, `createdAt`, `updatedAt` | ISO-8601 \| null | |

### `GET /v1/admin/knowledge-cards/{id}`

Listedeki alanlara ek olarak: `answer` (onaylı metin, `[n]` işaretleriyle), `keyFacts`, `citations` (geçmiş atıfıyla aynı biçim: `number`, `documentId`, `fileName`, `page`, `unit`, `documentDeleted`, `documentAccessible`, `collectionId`, `collectionName`; yöneticinin o anki erişimiyle çözülür), `createdByYou`, `verifiedByYou`, `disputesByDay` (`[{day, disputes}]`, kullanıcı yok), `events` (`[{fromState, toState, reason, at}]`; `reason`: `CREATED`, `VERIFICATION_ADDED`, `VERIFIED`, `NEW_CYCLE`, `DISPUTE_THRESHOLD`, `ARCHIVED` ya da bir `staleReason`). Hiçbir alan bir kullanıcının ya da yöneticinin kimliğini taşımaz (kim yaptı: denetim kaydı).

### `POST /v1/admin/knowledge-cards/{id}/verify`

Gövde yok. `DRAFT` kartta onayı kaydeder; `DISPUTED` ya da süresi dolmuş (`STALE` + `EXPIRED`) kartta yeni bir onay döngüsü açar. Onaylayan bütün kaynakları okuyabilmeli ve kaynak metin değişmemiş olmalı. Yanıt `200` ve kart.

### `POST /v1/admin/knowledge-cards/{id}/archive`

Gövde yok. Kart artık hiçbir soruya gösterilmez ve altın setten çıkar; 365 gün sonra silinir. Yanıt `200` ve kart.

### `GET /v1/admin/knowledge-eval/runs?page=&size=`

Altın set kontrolleri, yeniden eskiye. Her koşu: `id`, `trigger` (`SCHEDULED` gece, `MANUAL`), `status` (`RUNNING`, `SUCCEEDED`, `FAILED`), `startedAt`, `finishedAt`, `cases`, `found`, `missed`, `sourceLost`, `skipped`, `topK`, `recallAtK` (0–1 \| null), `baselineRecall` (önceki başarılı koşu), `sourceDrift` (0–1 \| null), `alert` (boolean).

### `POST /v1/admin/knowledge-eval/runs`

Kontrolü hemen çalıştırır (model çağrılmaz; en çok 1 000 vaka, eşzamanlı). Yanıt `201` ve koşu. Başka bir koşu sürüyorsa `409 11048`.

## Bilgi sağlığı ve kancalar (faz 2, ADR-0052)

Bütün uçlar yalnız `verso-admin` içindir (`403 90101`, token'sız `401 90100`); yanıtlar `Cache-Control: no-store, private`. Yöneticinin adı ve grupları token'dan okunur: rapordaki belge adları ve çelişki değerleri **bu yöneticinin okuyabildiği** belgelerle sınırlıdır.

### `GET /v1/admin/knowledge-health?from=YYYY-MM-DD&to=YYYY-MM-DD`

Penceresi `from..to` içinde biten en son başarılı teşhis koşusundan rapor (aralık en çok 400 gün; ters ya da uzun aralık `400 11060`). Koşu yoksa `run: null`, listeler boş, tek bulgu `NO_RUN`.

| Alan | Anlam |
|---|---|
| `run` | koşu kimliği, soru penceresi (`windowFrom`, `windowTo`), bitiş, belirsiz soru ve küme sayısı |
| `minAskers` | k (en az 5): her toplam en az bu kadar farklı kişiye dayanır |
| `questionTextShown` | kurulum politikası (`VERSO_SHOW_UNANSWERED_QUESTIONS`); `false` iken hiçbir soru metni dönmez |
| `findings[]` | `code`, `severity` (`INFO`, `WARNING`, `CRITICAL`), `title`, `finding`, `recommendation`: sabit Türkçe şablonlar, en ağır önce |
| `clusters[]` | `turns`, `askers` (≥ k), `previousTurns`, `trend` (`NEW`, `GROWING`, `STABLE`, `SHRINKING`), `keywords` (≥ k kişinin kullandığı sözcük kökleri), `representativeQuestions` (yalnız politika açıkken, ≥ k kişinin sorduğu normalleştirilmiş soru) |
| `staleDocuments[]` | belge kimliği, `fileName` (yalnız yönetici okuyabiliyorsa), bilgi alanı, son değişiklik, atıf ve kişi sayısı (≥ k), eskiyen kart sayısı |
| `coverage[]` | bilgi alanı başına belge, dönemde kaynak olan (`null`: < k kişi, gizli), `coverage` oranı, hiç kullanılmayan |
| `neverCitedDocuments` | hiç atıf almamış paylaşılan belge sayısı |
| `contradictions[]` | `first`/`second`: `documentId`, `page`, `readable`, `fileName` (okunabiliyorsa); `values[]` (`first`, `second`) yalnız iki taraf da okunabiliyorsa; `similarity`, `differences` |
| `cards` | durum başına kart ve onayı yakında dolacaklar |
| `downRatingReasons[]` | bilgi alanı × gerekçe (`null`: gerekçesiz) başına olumsuz puan ve kişi (≥ k) |
| `trend[]` | aralıktaki koşular: pencere sonu, soru, küme |
| `openFlags` | okunmamış `FLAG` bildirimleri |

### `GET /v1/admin/knowledge-health/export?from&to&format=MARKDOWN|JSON`

Aynı rapor ek dosya olarak (`Content-Disposition: attachment; filename="bilgi-sagligi-<from>-<to>.md|json"`, Markdown `text/markdown;charset=UTF-8`). Aynı süzgeçler; Markdown'da her ad ve metin kaçışlanır. Bilinmeyen `format` `400`.

### `GET /v1/admin/knowledge-health/runs?page&size` · `POST /v1/admin/knowledge-health/runs`

Koşular, en yenisi önce (`trigger` `SCHEDULED`/`MANUAL`, `status`, pencere, `questions`, `embedded`, `clusters`, `contradictionsChecked`). `POST` teşhisi hemen ve eşzamanlı çalıştırır (yalnız yerel embedding; sınırları `verso.qa.diagnostics.*`); başka koşu kilidi tutuyorsa `409 11061`.

### `GET /v1/admin/knowledge-hooks` · `PUT /v1/admin/knowledge-hooks/{key}`

Sabit kural kümesi: `key`, `trigger`, `action` (yalnız `NOTIFY`, `FLAG`, `DEMOTE`, `RUN_EVAL`), `enabled`, `defaultEnabled`, Türkçe `description`. `PUT` gövdesi `{"enabled": true|false}`; başka hiçbir alan değişmez. Bilinmeyen anahtar `404 11062`, biçimsiz anahtar ya da eksik `enabled` `400`.

### `GET /v1/admin/notifications?unread=false&page&size` · `POST /v1/admin/notifications/{id}/read`

Kanca bildirimleri, en yenisi önce: `ruleKey`, `action` (`NOTIFY`/`FLAG`), `trigger`, `subjectType` (`DOCUMENT`, `KNOWLEDGE_CARD`, `QUESTION_CLUSTER`, `CONTRADICTION`, `EVAL_RUN`), `subjectId`, `detail` (sabit sözcük), `severity`, Türkçe `title`, `createdAt`, `readAt`. Bildirimler bütün yöneticilerin ortak kutusudur; okundu işareti herkes için geçerlidir. Bilinmeyen kimlik `404 11063`. E-posta ve webhook bu sürümde yoktur. ADR-0053 ile `subjectType` için `SKILL` ve `trigger` için `SKILL_STALE` eklendi (kural `SKILL_STALE_NOTIFY`, varsayılan açık).

## Prosedürler (faz 3, ADR-0053)

Onaylı prosedür (beceri): ad (agentskills.io: küçük harf, rakam, tire, ≤ 64), tek satır açıklama (≤ 1024), sıralı adımlar (≤ 12; her biri kaynak sayfalarına özetle bağlı), örnek sorular (tetikleyiciler, ≤ 10) ve 2-5 kontrol sorusu (altın vaka). Durumlar `DRAFT`, `APPROVED`, `STALE` (`staleReason`: `SOURCE_DELETED`, `SOURCE_REPLACED`, `SOURCE_MOVED`, `SOURCE_CHANGED`), `ARCHIVED`. Yalnız `APPROVED` sunulur, dışa aktarılır ve MCP'de listelenir. Kaynaklarından birini okuyamayan yönetici durumu, sayıları ve geçmişi görür, metinleri görmez (`readable: false`, metin alanları `null`, listeler boş).

### `POST /v1/admin/skills`

Gövde `{clusterId?, cardIds?: [uuid ≤ 12], turns?: [{conversationId, turnId} ≤ 12], name?, condense: false}`: ya `clusterId` ya da kart/tur listesi (ikisi birden ya da hiçbiri `400 11074`). Kart `VERIFIED` olmalı; tur yöneticinin **kendi** kaynaklı cevabı (başkasınınki `404 11021`). Yöneticinin okuyamadığı ya da metni değişmiş kaynak `409 11072`; kişisel belge `409 11072`. Taslak şablonla kurulur; `condense: true` yalnız local modda yerel modeli sıralama ve kısaltma için çağırır, her adım kaynağına karşı yeniden denetlenir. Ad verilmezse üretilir (çakışırsa `-2`); verilen ad canlı bir prosedürde varsa `409 11077`. `201` ve `SkillResponse`.

### `GET /v1/admin/skills?state=&page=&size=` · `GET /v1/admin/skills/suggestions`

Liste (`SkillSummary`: `id`, `name`, `description` ya da `null`, `state`, `staleReason`, `version`, `origin` `CLUSTER`/`MANUAL`, `steps`, `goldenCases`, `approvals`, `requiredApprovals`, `approvedAt`, `updatedAt`, `readable`), en son değişen önce. Öneriler: son başarılı teşhis koşusunun kümeleri (`clusterId`, `runId`, k-anonim `keywords`, `turns`, `askers`, `trend`, çevresindeki `verifiedCards`, `procedural`); kişi ve soru metni yok, "nasıl yaparım" olanlar önce.

### `GET /v1/admin/skills/{id}`

`SkillResponse`: güncel sürümün `name`, `description`, `steps[]` (`position`, `instruction`, `grounded`, `sources[]`: `documentId`, `fileName` yalnız yönetici okuyabiliyorsa, `page`, `unit`, `accessible`), `triggers[]`, `goldenCases[]` (`question`, `documentId`, `page`, `fact`), `approvals`, `requiredApprovals`, `approvedByYou`, `createdByYou`, `events[]` (`version`, `from`, `to`, `reason`, `at`), `versions[]` (`version`, `reason` `DRAFTED`/`EDITED`, `condensed`, `createdAt`), `origin`, `originClusterId`, `condensed`, `approvedAt`, `createdAt`, `updatedAt`, `readable`. Yok `404 11070`.

### `PUT /v1/admin/skills/{id}`

Yeni sürüm (yalnız `DRAFT` ya da `APPROVED`; onaylı olan `DRAFT`'a döner, yeni onay döngüsü): `{name, description, steps: [{from, instruction}] 1..12, triggers: [..] 1..10, goldenCases: [{question, documentId, page, fact?}] 0..5}`. `from` güncel sürümdeki adım numarasıdır: adım o adımın kaynaklarını taşır (yeni kaynak eklenemez; aynı `from` iki kez `400 11074`). Kontrol sorusunun sayfası prosedürün kaynak sayfalarından biri olmalı (`400 11074`). Düzenleyen bütün kaynakları özetleri tutarak okuyabilmeli (`409 11073`); adımlar bu metne karşı yeniden denetlenir ve `grounded` olarak saklanır. `STALE`/`ARCHIVED` `409 11071`. Metinler tek satır, kontrol/biçim karakteri `400`.

### `POST /v1/admin/skills/{id}/approve` · `POST /v1/admin/skills/{id}/archive`

Onay yalnız `DRAFT`'ta (`409 11071`); onaylayan bütün kaynakları özetleri tutarak okumalı (`409 11073`); 2-5 kontrol sorusu, en az bir örnek soru, kontrol sorusunun beklenen bilgisi sayfada geçmeli (`400 11074`); her adım dayanaklı olmalı (`409 11075`). İki kişi kuralı açıkken aynı yöneticinin ikinci onayı `409 11076`. Arşiv arşiv dışı her durumda; arşivdeki `409 11071`.

### `GET /v1/admin/skills/{id}/diff?from=&to=`

İki sürümün alan alan farkı: `{from, to, changes: [{field (name, description, step, trigger, goldenCase), position, kind (ADDED, REMOVED, CHANGED), before, after}]}`; `1 ≤ from < to ≤ güncel` değilse `400 11074`. Metinler yalnız bütün kaynakları okuyan yöneticiye (ad her zaman).

### `GET /v1/admin/skills/{id}/export`

Yalnız `APPROVED` (`409 11079`) ve bütün kaynakları özetleri tutarak okuyan yöneticiye (`409 11073`): `application/zip`, `Content-Disposition: attachment; filename="<ad>.zip"`, içinde tek dosya `<ad>/SKILL.md`. Frontmatter `name`, `description` (çift tırnaklı tek satır), `metadata` (`verso-skill-id`, `verso-version`, `verso-approved-at`); gövde "Ne zaman kullanılır", numaralı adımlar ve `verso://passages/<id>` kaynak bağlantıları (MCP `verso_fetch` kimlikleri), "Kaynaklar" listesi (yalnız "Belge 0199abcd, s. 3"; dosya adı ve belge metni yok). < 500 satır. Denetim `SKILL_EXPORTED`.

### Soru ucu ve MCP

`POST /v1/questions` yanıtına ve `done` olayına `procedure` (ekleyici; [api-questions-integration-v1.md](api-questions-integration-v1.md)); MCP araçları `verso_skills`, `verso_skill` ([api-mcp-integration-v1.md](api-mcp-integration-v1.md)). Denetim eylemleri `SKILL_DRAFTED`, `SKILL_EDITED`, `SKILL_APPROVED`, `SKILL_ARCHIVED`, `SKILL_STALE` (aktör `system:knowledge-skills`), `SKILL_EXPORTED`, `MCP_SKILLS`, `MCP_SKILL`; hedef türü `SKILL`. `KnowledgeEvalRunResponse`'a ekleyici `skillCases`, `skillFound`, `skillMissed`, `skillSourceLost` (kart recall'u ve uyarısı değişmedi).

## Sistem modeli (faz 4, ADR-0054)

Belgelerden alım anında **sabit kurallarla** çıkarılan sistemler, ekranlar, kodlar, roller (yalnız unvan/birim; kişi adı hiçbir zaman), süreçler, adımlar ve terimler; her biri kaynak parçasına SHA-256 ile bağlı. Her uç çağıranın **kendi** ilkeleriyle okur (ADR-0028): okuyamadığınız belgedeki bir varlık sizin için yoktur, sayılmaz da. Gövdeler `Cache-Control: private, no-store`; sorgu loglanmaz.

Ortak varlık ayrıntısı (`SystemEntityDetail`): `{type, key, label, definitions: [{value, source}], links: [{relation, outgoing, type, key, label, ordinal}], sources: [{documentId, fileName, pageNumber, unit, passageId, contentSha256, collectionId, collectionName, defining}], truncated}`. `type` ∈ `SYSTEM`, `SCREEN`, `CODE`, `ROLE`, `PROCESS`, `STEP`, `TERM`; `relation` ∈ `STEP_OF`, `NEXT_STEP`, `CODE_OF`, `PART_OF`, `OWNED_BY` (`DEFINES` yalnız `definitions` olarak döner). `outgoing=true` "bu varlık → diğeri" demektir (adımın süreci), `false` tersi (sürecin adımları). `key` katlanmış anahtardır (`İK-3` → `IK-3`, `iade sureci`); bir sonraki çağrıda aynen gönderilir. `passageId` `GET /v1/documents/...` ve MCP `verso_fetch` ile okunabilir parça kimliğidir. `truncated`: bir liste sınıra takıldı (tanım 10, ilişki 60, kaynak 20; aramada kaynak 5).

### `GET /v1/system-model/lookup?q=`

Her oturum açmış kullanıcı. `q` 1-200 karakter bir kod, kısaltma ya da ad (büyük/küçük harf ve Türkçe harf farkı önemsiz: `wms-204`, `fefo`, `İK-3`). Yanıt en çok 10 varlıklık dizi (`SystemEntityDetail`); eşleşme yoksa `[]`. Boş ya da uzun `q`: 400 `10051`.

### `GET /v1/admin/system-map/stats`

Yalnız `verso-admin`. `{entities: {SYSTEM: n, …}, documents, pendingDocuments, extractorVersion, enrichmentEnabled}`: okuyabildiğiniz READY belgeler üzerinden tip başına farklı varlık sayısı, modeli çıkarılmış ve geri doldurmayı bekleyen belge sayısı, arama zenginleştirmenin açık olup olmadığı.

### `GET /v1/admin/system-map/entities?type=&prefix=&limit=`

Yalnız `verso-admin`. `type` isteğe bağlı (yoksa adımlar hariç hepsi), `prefix` en çok 200 karakter (katlanır), `limit` 1-200 (varsayılan 100). `[{type, key, label, documents, mentions}]`, en çok belgede geçen önce.

### `GET /v1/admin/system-map/entity?type=&key=`

Yalnız `verso-admin`. Bir varlığın ayrıntısı ("nerede geçiyor" dahil). Okuyabildiğiniz hiçbir belgede yoksa 404 `10050` (yok ile kapalı ayırt edilmez).

### Öneri geçişi (isteğe bağlı, varsayılan kapalı)

Yalnız `verso-admin`; geçiş yalnız local modda ve `VERSO_QA_SYSTEM_MODEL_SUGGESTIONS_ENABLED=true` iken çalışır.

- `GET /v1/admin/system-map/suggestions/settings` → `{enabled}`.
- `POST /v1/admin/system-map/suggestions/documents/{documentId}` → `{documentId, outcome, proposed, stored, rejected}`; `outcome` ∈ `STORED`, `NONE`, `TOO_FEW_ENTITIES`, `BUSY`, `TIMEOUT`, `UNPARSEABLE`, `FAILED`. Kapalıysa 409 `11080`; belge yok/okunamaz 404 `11083`.
- `GET /v1/admin/system-map/suggestions?state=DRAFT|ACCEPTED|REJECTED` → `[{id, documentId, passageId, relation, sourceType, sourceKey, targetType, targetKey, state, createdAt, reviewedAt}]`, yalnız okuyabildiğiniz belgelerden; kanıt parçası değişmiş öneri silinir.
- `POST /v1/admin/system-map/suggestions/{id}/accept` · `/reject` → öneri; yok/okunamaz 404 `11081`, zaten incelenmiş 409 `11082`. Kabul edilen öneri hiçbir aramada kullanılmaz; yalnız haritada "incelenmiş öneri" olarak görünür.

### Bilgi sağlığı ve MCP

`GET /v1/admin/knowledge-health` yanıtına ekleyici `systemModel: [{kind, entityType, key, label, documentIds, values}]` (`kind` ∈ `UNDEFINED_CODE`, `TERM_CONFLICT`, `PROCESS_WITHOUT_OWNER`; yalnız okuyabildiğiniz paylaşılan belgeler) ve bulgular `TERM_CONFLICTS`, `UNDEFINED_CODES`, `PROCESSES_WITHOUT_OWNER`; Markdown dışa aktarımda "Sistem modeli sinyalleri" tablosu. MCP aracı `verso_lookup` ([api-mcp-integration-v1.md](api-mcp-integration-v1.md)). Denetim eylemleri `MCP_LOOKUP`, `SYSTEM_SUGGESTIONS_RUN`, `SYSTEM_SUGGESTION_ACCEPTED`, `SYSTEM_SUGGESTION_REJECTED`; hedef türleri `SYSTEM_MODEL`, `SYSTEM_SUGGESTION`.

## Hata kodları

| Kod | HTTP | Anlam | İstemci davranışı | Yeniden dene |
|---|---|---|---|---|
| 10050 | 404 | Sistem modelinde böyle bir varlık yok (ya da okuyamadığınız belgelerde) | listeyi yenile | hayır |
| 10051 | 400 | Arama metni boş ya da 200 karakterden uzun | metni düzelt | hayır |
| 11021 | 404 | Tur yok ya da yöneticinin değil | "Bu cevap artık geçmişte yok" | hayır |
| 11040 | 404 | Kart yok | listeyi yenile | hayır |
| 11041 | 409 | Bir kaynak yok ya da yöneticiye kapalı | kaynak listesini göster | hayır |
| 11042 | 409 | Cevap kaynaklı değil (`ANSWERED` + atıf değil ya da karartılmış) | düğmeyi gösterme | hayır |
| 11043 | 409 | Durum işleme izin vermiyor (zaten onaylı, arşivde, kaynağı değişmiş) | kartı yeniden oku | hayır |
| 11044 | 409 | Kaynak metin değişmiş | "yeni cevaptan kart oluşturun" | hayır |
| 11045 | 409 | Bu yönetici bu döngüde zaten onayladı (iki kişi kuralı) | ikinci yöneticiyi bekle | hayır |
| 11046 | 409 | Kişisel belgeye dayanıyor | belgeyi bir bilgi alanına taşı | hayır |
| 11047 | 409 | Aynı soru için canlı kart var | mevcut kartı göster | hayır |
| 11048 | 409 | Kontrol zaten çalışıyor | biraz sonra | evet |
| 11060 | 400 | Rapor aralığı ters ya da 400 günden uzun | aralığı düzelt | hayır |
| 11061 | 409 | Teşhis zaten çalışıyor (ya da bugünün gece koşusu sürüyor) | biraz sonra | evet |
| 11062 | 404 | Kanca kuralı yok | listeyi yenile | hayır |
| 11063 | 404 | Bildirim yok (saklama süresi doldu) | listeyi yenile | hayır |
| 11070 | 404 | Prosedür yok | listeyi yenile | hayır |
| 11071 | 409 | Prosedürün durumu işleme izin vermiyor (onaylı, eskimiş, arşivde ya da başka yönetici değiştirdi) | prosedürü yeniden oku | hayır |
| 11072 | 409 | Taslak için okunabilen onaylı kaynak yok (kart onaylı değil, kaynak kapalı/değişmiş, kişisel belge) | kart seçimini göster | hayır |
| 11073 | 409 | Bir kaynak değişmiş ya da yöneticiye kapalı | "yeni taslak gerekir" | hayır |
| 11074 | 400 | Prosedür eksik/geçersiz (kaynak seçimi, adım numarası, kontrol sorusu sayfası/bilgisi, 2-5 kontrol sorusu, sürüm aralığı) | formu düzelt | hayır |
| 11075 | 409 | Bir adım kaynaklarında olmayan sayı ya da kod içeriyor | adımı düzelt | hayır |
| 11076 | 409 | Bu yönetici bu döngüde zaten onayladı (iki kişi kuralı) | ikinci yöneticiyi bekle | hayır |
| 11077 | 409 | Bu adla canlı bir prosedür var | başka ad | hayır |
| 11078 | 404 | Soru kümesi yok (koşu saklama süresiyle silinmiş) | önerileri yenile | hayır |
| 11079 | 409 | Yalnız onaylı prosedür indirilebilir | düğmeyi gösterme | hayır |
| 11080 | 409 | Öneri geçişi kapalı (bayrak ya da bulut modu) | düğmeyi gösterme | hayır |
| 11081 | 404 | Öneri yok ya da belgesi okunamaz | listeyi yenile | hayır |
| 11082 | 409 | Öneri zaten incelenmiş | listeyi yenile | hayır |
| 11083 | 404 | Belge yok, hazır değil ya da okunamaz | belge listesini yenile | hayır |
| 90101 | 403 | `verso-admin` değil | yönetici ekranını gösterme | hayır |

## Gizlilik

- Kartın soru ve cevap metni yöneticinin kendi turundandır; başka kullanıcının sorusu karta çevrilemez.
- İtiraz eden kullanıcı saklanmaz (kart başına takma ad); uç yalnız gün başına sayıyı verir.
- Metinler hiçbir log satırına ve denetim kaydına yazılmaz; denetimde yalnız kimlikler ve sabit sözcükler vardır.

## Net kanıt bulunamayan alanlar

- OpenAPI farkı üretilmedi (repo'da OpenAPI üretimi yok, ADR-0004).
