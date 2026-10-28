# API istemcisi beceri kütüphanesi entegrasyonu ("Beceriler") [v1]

Verso'nun hazır ve kuruma özel **görev becerileri**: kullanıcı bir formu doldurur, Verso kullanıcının erişebildiği belgelerde arar, sınırlı bir istem zinciri çalıştırır ve her satırı kaynaklı (ya da açıkça "değerlendirilemez") yapılandırılmış bir **taslak** saklar. Kullanıcı taslağı düzenler, "onaylandı" işaretler, Markdown/JSON olarak dışa aktarır ve puanlar. Karar: [ADR-0057](adr/0057-beceri-kutuphanesi.md). Tanım biçimi: [`skills/README.md`](../skills/README.md).

> **OpenAPI doğrulanmadı:** uçlar ve alanlar `TaskSkillController`, `AdminTaskSkillController`, `SkillRunServiceImpl`, `SkillAdminServiceImpl` ve `work-api` DTO'larından çıkarıldı (ADR-0004). Generated client paketi: yok.

Bu doküman **28 Ekim 2026** tarihli `feature/doguyaras-beceri-kutuphanesi` branch'indeki koda (taban `develop` `2628365`) dayanır.

> **Karıştırma:** `/v1/admin/skills` ADR-0053'ün **Prosedürleri**dir (SKILL.md). Bu dokümandaki görev becerilerinin yönetici yolu `/v1/admin/task-skills`'tir.

## Değişikliklerin özeti
- Yeni kullanıcı uçları: `/v1/skills` (galeri, tanım, çalıştırma başlatma) ve `/v1/skill-runs` (kendi çalıştırmaları: oku, ilerleme akışı, düzenle, onayla, puanla, dışa aktar, sil).
- Yeni yönetici uçları: `/v1/admin/task-skills` (kurulum başına aç/kapa), `/v1/admin/custom-skills` (kuruma özel beceri: doğrula, oluştur, yeni sürüm, onayla, arşivle).
- Yeni hata kodları: 15001–15017 (`work` bloğu, 15000–15999).
- Yeni denetim eylemleri: `TASK_SKILL_RUN_STARTED`, `TASK_SKILL_RUN_FINISHED`, `TASK_SKILL_RUN_EDITED`, `TASK_SKILL_RUN_APPROVED`, `TASK_SKILL_RUN_RATED`, `TASK_SKILL_RUN_EXPORTED`, `TASK_SKILL_RUN_DELETED`, `TASK_SKILL_TOGGLED`, `TASK_SKILL_CUSTOM_SAVED`, `TASK_SKILL_CUSTOM_APPROVED`, `TASK_SKILL_CUSTOM_ARCHIVED`; hedef türleri `TASK_SKILL`, `TASK_SKILL_RUN`. Çalıştırma sonunun aktörü `system:skill-runner`'dır.
- `SubjectHistory` (KVKK başvurusu yanıtları) sona `skillRuns` alanını ekledi (eklemeli).
- Mevcut uçların sözleşmesi **değişmedi**. Mobil için etki yok; panel "Beceriler" ekranı ve soru kutusundaki `/` komutları bu dokümana dayanır.

## Kurallar
- **Kritik:** Çağıran her zaman doğrulanmış token'dır (`sub` + gruplar). Başka kullanıcının çalıştırması **404**'tür (`15004`; varlığı açıklanmaz); `verso-admin` de başkasının çalıştırmasını okuyamaz.
- **Kritik:** Arama çağıranın erişimiyle yapılır (ADR-0028); kaynak pasajlar saklanmaz, her `GET`'te yeniden okunur. Erişimi kalkmış ya da silinmiş belgenin atfı `accessible: false`, `fileName` ve `excerpt` `null` gelir.
- **Kritik:** Çıktı bir **taslaktır**; `disclaimer` her yerde gösterilmelidir (ekranda, dışa aktarmada). Uyum becerilerinin etiketi kimin inceleyeceğini söyler.
- Form girdileri kişisel veri içerebilir: istemci onları URL'ye, tarayıcı deposuna ya da loga yazmaz. Geçmiş listesi (`SkillRunSummary`) girdi ve çıktı taşımaz.
- Bütün yanıtlar `Cache-Control: no-store, private`'tır. Başarı ham, hata zarflıdır (ADR-0004). Bilinmeyen alan ve enum değerleri yok sayılır ya da ham gösterilir.

## Uçlar

| Yöntem ve yol | Rol | Gövde | Başarı |
|---|---|---|---|
| `GET /v1/skills` | kullanıcı | – | `200` `SkillSummary[]` (yalnız açık beceriler; hazırlar önce, ada göre) |
| `GET /v1/skills/{skillId}` | kullanıcı | – | `200` `SkillDefinition` (form, çıktı şeması, rubrik, çerçeve, etiket, atıf) |
| `POST /v1/skills/{skillId}/runs` | kullanıcı | `{"inputs": {"<key>": "metin" \| ["<uuid>", …]}}` | `202` `SkillRunResponse` (`status: RUNNING`), `Location: /v1/skill-runs/{id}` |
| `GET /v1/skill-runs?page=0&size=20` | kullanıcı | – | `200` `{items: SkillRunSummary[], page, size, total}`; `size` 1–100 |
| `GET /v1/skill-runs/{runId}` | sahibi | – | `200` `SkillRunResponse` |
| `GET /v1/skill-runs/{runId}/events` | sahibi | – | `200` `text/event-stream`: `stage` (aşama değişince), `done` (bir kez); veri `SkillRunEvent` JSON; 10 sn sessizlikte `keep-alive` yorumu |
| `PUT /v1/skill-runs/{runId}/output` | sahibi | `{"output": SkillOutput}` | `200` `SkillRunResponse` (`edited: true`, `status: DRAFT`) |
| `POST /v1/skill-runs/{runId}/approve` | sahibi | – | `200` `SkillRunResponse` (`status: APPROVED`; tekrar zararsız) |
| `POST /v1/skill-runs/{runId}/rating` | sahibi | `{"rating": 1..5}` | `204` |
| `GET /v1/skill-runs/{runId}/export?format=MARKDOWN\|JSON` | sahibi | – | `200` ek dosya (`Content-Disposition: attachment`), `text/markdown` ya da `application/json` |
| `DELETE /v1/skill-runs/{runId}` | sahibi | – | `204` |
| `GET /v1/admin/task-skills` | verso-admin | – | `200` `AdminSkillResponse[]` (hazır ve kuruma özel, `enabled` ile) |
| `PUT /v1/admin/task-skills/{skillId}/enabled` | verso-admin | `{"enabled": true\|false}` | `200` `AdminSkillResponse` |
| `GET /v1/admin/custom-skills` | verso-admin | – | `200` `CustomSkillResponse[]` (arşivlenmemiş) |
| `GET /v1/admin/custom-skills/{id}` | verso-admin | – | `200` `CustomSkillResponse` |
| `POST /v1/admin/custom-skills/validate` | verso-admin | `{"definition": SkillDefinition}` | `200` `{valid, errors[]}` (hiçbir şey saklanmaz) |
| `POST /v1/admin/custom-skills` | verso-admin | `{"definition": SkillDefinition}` | `201` `CustomSkillResponse` (`DRAFT`, sürüm 1) |
| `PUT /v1/admin/custom-skills/{id}` | verso-admin | `{"definition": SkillDefinition}` | `200` yeni sürüm, yeniden `DRAFT` (id değişemez) |
| `POST /v1/admin/custom-skills/{id}/approve` | verso-admin | – | `200` `APPROVED` |
| `POST /v1/admin/custom-skills/{id}/archive` | verso-admin | – | `200` `ARCHIVED` |

Yönetici uçlarında başka her kimlikli çağıran `403` (`90101`; denetimde `ACCESS_DENIED` / `ADMIN_TASK_SKILL`), kimliksiz `401` alır.

### Çalıştırma yaşam döngüsü
`RUNNING` (aşamalar: `QUEUED` → `RETRIEVING` → `GENERATING` (`step`/`stepCount`, `stepTitle`) → gerekirse `REPAIRING` → `CHECKING`) → `DRAFT` ya da `FAILED`. `DRAFT` → `APPROVED` (onay), düzenleme yeniden `DRAFT`. `failureCode` sabit sözcüklerdir: `MODEL_UNAVAILABLE`, `MODEL_TIMEOUT`, `OUTPUT_INVALID` (bir onarım denemesinden sonra da şemaya uymadı), `RETRIEVAL_FAILED`, `INTERRUPTED` (çalıştıran örnek durdu), `RUN_FAILED`, `MODEL_BUDGET_EXCEEDED` (model geçidiyle, ADR-0056: `SKILL_RUN` rotasındaki fiyatlı modellerin aylık üst sınırı doldu). Bilinmeyen sözcük ham gösterilir. Model geçidi kuruluysa her adım `SKILL_RUN` rotasıyla, yedekleri, bütçesi ve kullanım kaydıyla çalışır (yönlendirme sözcüğü `skill:<id>:<adım>` kullanım satırında saklanır; kullanıcı metni değildir).

### Çıktı (`SkillOutput`)
Şema sırasıyla bölümler; `TEXT` bölümü `text` + `evidence` ya da `notAssessable: true`; `ROWS` bölümü satırlar: `cells` (sütun anahtarı → metin), `evidence` (`citations[].number` değerleri) **ya da** `notAssessable: true` ("değerlendirilemez"), sunucunun doldurduğu `unverified` (satırın alıntıladığı kaynaklarda ve girdilerde bulunmayan sayı ve kodlar; ekran "kaynakta yok" diye işaretler). Düzenlemede de aynı kurallar geçerlidir; uymayan gövde `400 15006` ve `details` (bölüm/satır/kural; metin yok).

### Denetimler (`checks`)
`repaired`, `rows`, `notAssessable`, `unverified`, `unreadableCitation` (çalıştırma sonunda okunamayan alıntılanmış pasaj), `withinLength`, `disclaimerPresent`.

## Hata kodları

| Kod | HTTP | Anlam |
|---|---|---|
| 15001 | 404 | Beceri bulunamadı |
| 15002 | 404 | Beceri bu kurulumda kapalı (ya da kuruma özel beceri onaylanmadı) |
| 15003 | 400 | Form değerleri beceriye uymuyor; `details` alan ve kural adlarıdır, değer yoktur |
| 15004 | 404 | Çalıştırma bulunamadı (ya da başkasının) |
| 15005 | 409 | Çalıştırmanın bitmiş çıktısı yok (sürüyor ya da başarısız) |
| 15006 | 400 | Düzenlenen çıktı şemaya uymuyor |
| 15007 | 400 | Beceri tanımı geçersiz (`details`: `yol: kural`) |
| 15008 | 404 | Kuruma özel beceri bulunamadı |
| 15009 | 409 | Bu id ya da eğik çizgi komutu kullanılıyor (hazır ya da başka canlı beceri) |
| 15010 | 409 | Kuruma özel beceri bu işleme uygun durumda değil (ör. onaylıyı yeniden onaylamak, arşivliyi değiştirmek) |
| 15011 | 409 | Kullanıcının süren bir çalıştırması var |
| 15012 | 503 | Model meşgul; `Retry-After` kadar sonra yeniden dene |
| 15013 | 400 | Sayfa ya da boyut aralık dışında |
| 15014 | 400 | Bilinmeyen dışa aktarma biçimi |
| 15015 | 413 | Gövde 1 MB'tan büyük (Jackson okumadan reddedilir) |
| 15016 | 411 | Gövdeli `POST`/`PUT` isteğinde `Content-Length` yok (parçalı gönderim) |
| 15017 | 503 | Bu örnekte çok fazla ilerleme akışı açık (en çok 200); istemci `GET` ile yoklar |

## İstemci notları
- Başlattıktan sonra `events` akışını aç; akış gelmezse ya da kesilirse `GET /v1/skill-runs/{id}`'yi 1–2 sn aralıkla yokla.
- Dışa aktarmayı Blob ile indir; dosya adı sunucunun `Content-Disposition` adıdır (beceri id'si ve çalıştırma kimliğinin başı; kişisel veri yok).
- Eğik çizgi komutu (`slash`) yalnız istemci kolaylığıdır; sunucuya `skillId` gider.
