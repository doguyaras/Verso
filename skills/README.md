# Beceri kütüphanesi (`skills/`)

Karar: [ADR-0057](../docs/adr/0057-beceri-kutuphanesi.md). Atıflar: [NOTICE.md](NOTICE.md).

## Beceri nedir, prosedürden farkı ne?

| | **Beceri** (bu klasör, `work` modülü) | **Prosedür** ([ADR-0053](../docs/adr/0053-onayli-prosedurler-skill.md), `qa` modülü) |
|---|---|---|
| Ne | Bir iş akışı: rehberli form → belge araması → en çok 4 adımlı model zinciri → yapılandırılmış, kaynaklı çıktı | Belgelerden türetilmiş, onaylı "nasıl yapılır" adımları |
| Kim yazar | Verso (hazır 10 beceri) ve kurulumdaki danışmanlık ekibi (şirkete özel beceri) | Yönetici, doğrulanmış cevaplardan ve soru kümelerinden |
| Çıktı | Kullanıcının düzenleyip "onaylandı" diye işaretlediği bir **taslak** | SKILL.md olarak dışa aktarılan onaylı prosedür |
| Panelde | **Beceriler** | **Prosedürler** |

İki kavram aynı kelimeyle anılmaz: panel ve belgeler "Beceriler" ile "Prosedürler"i ayrı tutar.

## Tasarım kuralları (araştırmadan)

1. **Taslak, asla imza değil.** Her çıktı `DRAFT` olarak saklanır; kişi düzenler, onaylar. Etiket (`disclaimer`) her çıktıda ve dışa aktarımda görünür.
2. **Satır başına kanıt.** Her satır ve her metin bölümü numaralı kaynaklara (`evidence`) dayanır ya da **`değerlendirilemez`** der. Kanıtsız bir hüküm saklanmaz.
3. **Önce arama, sonra akıl yürütme.** Arama çağıranın kendi yetkisiyle yapılır; bulunamayan bilgi eksik/açık konu olarak yazılır.
4. **Uzunluk tavanları.** Hücre, bölüm ve toplam çıktı sınırlıdır; rubrikte uzunluk ayrı bir boyuttur (araştırma: uzunluk kullanıcıyı yoruyor, hakemi yanıltıyor).
5. **Uyum etiketi.** Hukuk ve uyum becerileri "… incelemesi için" der, "tamamen uyumlu" gibi hükümleri yasaklı ifade olarak listeler.
6. **Sınırlı zincir.** En çok 4 adım (ör. pre-mortem → 5 Neden → önlemler); yalnız son adım yapılandırılmış çıktıyı yazar.
7. **Sayılar ve kodlar yalnız kaynaktan.** Çalışma zamanı satırdaki sayı ve kodları alıntılanan kaynaklarda arar (ADR-0047); bulunamayanlar "doğrulanamadı" işaretini alır.

## Biçim (`skills/<id>.json`, `formatVersion: 1`)

Dosya adı `id` ile aynıdır. Doğrulayıcı (Java `SkillDefinitionValidator` ve `scripts/skill-library.test.js`) aşağıdaki kuralları aynı biçimde uygular; isteğe bağlı alan `null` yazılır.

| Alan | Kural |
|---|---|
| `formatVersion` | `1` |
| `id` | `[a-z][a-z0-9-]{2,63}`, kurulumda tekil, değişmez |
| `version` | `1.0.0` biçiminde; her koşu kullandığı sürümü kaydeder |
| `name` | 3–80 karakter, Türkçe |
| `description` | 20–600 karakter: ne girer, ne çıkar |
| `category` | `PRODUCTIVITY`, `SALES`, `LEGAL_COMPLIANCE`, `PROJECT`, `QUALITY_CUSTOMER`, `PROCESS` |
| `slash` | `[a-z0-9-]{2,32}`, tekil; soru kutusunda `/<slash>` |
| `inputs` | 1–10 alan; en az biri `required: true` |
| `inputs[].key` | `[a-z][a-zA-Z0-9]{1,31}`, tekil; metinde `{{key}}` |
| `inputs[].type` | `TEXT` (`maxLength` 1–500), `LONG_TEXT` (`maxLength` 1–30000), `SELECT` (`options` 2–20; `value` `[a-z0-9-]{1,40}`), `DOCUMENTS` / `COLLECTIONS` (`maxItems` 1–20) |
| `inputs[].label` / `help` / `placeholder` | 1–80 / ≤ 300 / ≤ 200 |
| `grounding` | `null`: arama yok. `queries` 1–3 şablon (≤ 300; `{{key}}` yalnız TEXT/LONG_TEXT/SELECT alanı, ilk 300 karakteriyle), `maxPassages` 1–12, `documentsInput` bir DOCUMENTS alanı ya da `null`, `collectionsInput` bir COLLECTIONS alanı ya da `null`, `scopeCollections` repodaki becerilerde `null` |
| `steps` | 1–4 adım; `key` `[a-z][a-z0-9-]{1,31}`, `title` 1–80, `instruction` 20–4000 (Türkçe, emir kipi; `{{key}}` yalnız alan adları). Son adım dışındaki adımlarda `maxChars` 200–6000 (çalışma notu); son adımda `null` |
| `output.sections` | 1–8 bölüm; `key` `[a-z][a-zA-Z0-9]{1,31}`. `TEXT`: `maxLength` 1–4000, `columns/minRows/maxRows` `null`. `ROWS`: `columns` 1–8 (`key` tekil, `evidence` olamaz; `maxLength` 1–2000; isteğe bağlı `allowed` ≤ 10 değer, her biri ≤ 40), `0 ≤ minRows ≤ maxRows ≤ 50`, `maxLength` `null` |
| `maxOutputChars` | 500–30000, bütün çıktı metni |
| `rubric` | 3–8 boyut (`key` `[a-z][a-z-]{1,31}`, `description` 10–400); `groundedness` ve `length` zorunlu |
| `framework` | `name` 2–120, `reference` 2–300 (PMI, ISO 31000, INVEST …) |
| `disclaimer` | 10–200 karakter; çıktıyla birlikte gösterilir |
| `compliance` | `true`/`false`; `LEGAL_COMPLIANCE` ise `true`. `true` ise etiket "inceleme" kelimesini içerir ve `forbiddenPhrases` en az bir ifade taşır |
| `attribution` | `null` ya da `{source, license (Apache-2.0, MIT, CC-BY-4.0), url (https), note}`; `anthropics/skills` reddedilir |
| `forbiddenPhrases` | ≤ 20 ifade, her biri ≤ 80; çıktıda geçerse deterministik kontrol yakalar |

Talimatlarda JSON sözdizimi anlatılmaz: çıktı sözleşmesini, numaralı kaynakları ve girdileri çalışma zamanı ekler. Talimat şunları söylemelidir: dayanaksız satır `değerlendirilemez`, sayı ve kodlar yalnız kaynaktan, uzunluk sınırları.

## Çıktı ve kanıt kuralı

Model her satır için ya `evidence` (kaynak numaraları) ya da `değerlendirilemez` döndürür. Kaynaklar iki türdür: çağıranın okuyabildiği belge parçaları (`PASSAGE`, her görüntülemede yeniden yetkiyle okunur) ve kullanıcının uzun metin girdisinin parçaları (`INPUT`). Şemaya uymayan çıktı için bir onarım denemesi yapılır; yine uymazsa koşu `FAILED` (`OUTPUT_INVALID`) olur.

## Yeni beceri eklemek

1. `skills/<id>.json` yaz (yukarıdaki kurallar).
2. `skills/golden/<id>.json`: 2–3 vaka. `documents` (gerekiyorsa `samples/` altındaki örnek PDF'ler), geçerli `inputs` (DOCUMENTS/COLLECTIONS kimlikleri kuruluma özgü olduğundan boş), `expect`: `sections`, `minRows`, `citesDocuments`, `mentionsAny` (her iç listeden en az biri), `forbids`, `notAssessableAllowed`. Beklenen **özellikler** yazılır, birebir metin değil.
3. Uyarlama varsa `attribution` ve [NOTICE.md](NOTICE.md).
4. Kontroller:
   ```bash
   node --test scripts/skill-library.test.js
   ./mvnw -B -ntp -T 1 -pl services/work/work-core -am test -Dtest=RepoSkillsTest -Dsurefire.failIfNoSpecifiedTests=false
   ```

## Şirkete özel beceriler (danışmanlık ekibi)

Danışman, `verso-admin` rolüyle aynı JSON'u yönetici API'sine gönderir:

- `POST /v1/admin/custom-skills/validate` — yalnız doğrulama (panelde JSON düzenleyicisi).
- `POST /v1/admin/custom-skills` — `{"definition": {...}}`; `DRAFT` olarak, sürüm 1. `id` hazır bir beceriyle çakışamaz.
- `PUT /v1/admin/custom-skills/{id}` — yeni sürüm, yeniden `DRAFT`.
- `POST /v1/admin/custom-skills/{id}/approve` — onaylanınca kullanıcıların galerisinde görünür; `archive` ile kaldırılır.
- `grounding.scopeCollections`: becerinin arayabileceği bilgi alanları. Çağıranın kendi erişimiyle **kesişir**; paylaşılan bir beceri kimsenin erişimini genişletmez.
- Hazır ya da özel her beceri kurulum bazında `PUT /v1/admin/task-skills/{skillId}/enabled` (`/v1/admin/skills` prosedürlerindir, ADR-0053) ile açılıp kapatılır.

Ayrıntılar ve uçların tamamı: [docs/api-work-integration-v1.md](../docs/api-work-integration-v1.md) ve ADR-0057.

## Değerlendirme (eval)

- **Katman 1, deterministik (CI):** şema, kaynak numaraları, okunabilir atıflar, sayı/kod dayanaklılığı, uzunluk, etiket, yasaklı ifadeler; sahte (stub) modelle entegrasyon testlerinde.
- **Katman 2, gerçek model + isteğe bağlı LLM hakemi:**
  ```bash
  VERSO_TOKEN=<erişim belirteci> node scripts/skill-eval.mjs --base http://localhost:8080 [--skill premortem] [--judge] [--no-upload]
  ```
  Altın vakalardaki örnek belgeleri yoksa yükler, her vakayı çalıştırır, beklenen özellikleri kontrol eder; `--judge` ile her rubrik boyutu için ayrı, ikili (PASS/FAIL/UNKNOWN), uzunluğu ödüllendirmeyen bir hakem sorusu sorar (`SKILL_JUDGE_URL`, `SKILL_JUDGE_MODEL`, isteğe bağlı `SKILL_JUDGE_API_KEY`; OpenAI uyumlu uç). Hakem, koşturan modelle aynı aileden ise uyarı verir (kendini kayırma yanlılığı). Sonuç: `eval/results/skills-<yyyymmdd-hhmm>.json`. Konsola girdi ve çıktı metni yazılmaz.
- **Katman 3, insan:** danışmanlar kurulumda beceri başına örnekleri puanlar; hakem–insan uyumu izlenir.
