# ADR-0049: Onaylı bilgi kartı, müşteriye özel altın set ve gecelik kontrol (öğrenen katman faz 1)

- **Durum:** Kabul edildi. Sunma varsayılan açık (`VERSO_QA_KNOWLEDGE_ENABLED`); iki kişi kuralı varsayılan kapalı (`VERSO_QA_KNOWLEDGE_TWO_PERSON_RULE`).
- **Tarih:** 2026-10-26
- **Karar verenler:** doguyaras.
- **Uyguladığı:** ADR-0048 faz 1 (ilkeler, arıza biçimleri ve k uzlaştırması oradadır, burada tekrar edilmez).
- **İlgili eşik (referans Bölüm 24):** sıcak yolun uzak çağrı sayısı değişmez (ADR-0008: embedding + chat); bellekten cevapta chat çağrısı hiç yoktur.
- **Genişlettiği:** ADR-0023 (`qa` şeması, geri bildirim), ADR-0028 (okuyucu ilkeleri), ADR-0035 (akış aşamaları), ADR-0039 (outbox), ADR-0019 (rol).

## Bağlam

- Faz 1'in amacı: uzmanın onayladığı cevabı aynı soruyu soran ve kaynaklarını okuyabilen herkese tekrar kullanmak, kaynak değişince kendiliğinden geri çekmek ve onaylananları kurumun regresyon setine çevirmek.
- `qa` modülü `document` şemasına erişemez (AGENTS.md 4). Parça kimlikleri ve metin özetleri `document`'tadır; belge silme, değiştirme ve taşıma da orada olur.
- Geçmişteki atıf yalnız belge ve sayfa tutar (ADR-0023); parça kimliği tutmaz.
- Verso'da belge kimliği değişmez içeriklidir: değişen dosya yeni belge olur (ADR-0038 `replaces`). "Yeniden indeksleme" bugün silme + yeni belgedir.

## Seçenekler

| Konu | Seçenekler | Seçilen ve neden |
|---|---|---|
| Kartın yeri | ayrı `knowledge` modülü · `qa` şeması | **`qa` şeması** (yeni migration V4): kart turdan doğar, itiraz geri bildirimden gelir, sunma soru yolundadır; ayrı modül şimdilik yalnız sınır maliyeti getirir (ADR-0048 yeniden değerlendirme: 10 000 kart) |
| Soru eşleşmesi | metin özeti · ikinci embedding · **aynı embedding'i paylaşmak** | `DocumentRetrieval.embed` + `search(…, QuestionEmbedding, …)`: soru bir kez gömülür, önce kartlara bakılır, bulunmazsa aynı vektörle aranır; sıcak yol bütçesi aynı |
| Kaynak bağlama | parça kimliği · sayfa · **(belge, sayfa, metin SHA-256)** | Karttaki her atıf sayfasının bütün parçaları özetle bağlanır; özeti veritabanı hesaplar. Aynı metinle yeniden gömülen parça kartı bozmaz, metin değişirse bozar |
| Görünürlük | kopyalanmış ilke listesi · **sunarken yeniden okumak** | `DocumentProvenance.sourceChunks(reader, …)` okuyucunun ilkeleriyle sorgu içinde (ADR-0028). Kart yalnız bütün bağlı parçaları bu okuyucuya görünüyor ve özetleri tutuyorsa sunulur. Kartta saklanan `scope_collections` yalnız gösterim ve denetim içindir |
| Eskime bildirimi | gece taraması · Spring olayı · **document outbox'u** | `document.changed` satırı silme/değiştirme/taşıma ile aynı işlemde (ADR-0039); relay `DocumentChangeListener`'ları çağırır (`qa`'da `KnowledgeTransitions`). Gece kontrolü ve sunarken özet kontrolü emniyet ağıdır |
| Bilgi sahibi rolü | yeni rol (ADR-0019) · **`verso-admin`** | v1'de `verso-admin`. Yeni rol denetim tablosunun rol CHECK'ini, IdP eşlemesini ve paneli değiştirir; ayrı rol, kart sayısı ve sahiplik (faz 4) gelince açılır |
| Gecelik koşunun kilidi | advisory lock · ShedLock · **koşu satırı** | `qa.eval_run`'da tek `RUNNING` satırı (kısmi tekil indeks) + claim token + kira; ölen instance'ın koşusu kira bitince `FAILED` olur ve devralınır |

## Karar

### Veri modeli (`qa` şeması, `V4__knowledge_cards.sql`)

- `knowledge_card`: kanonik soru, normalleştirilmiş soru özeti (canlı kart başına tekil), soru vektörü (`vector(1024)`) ve modeli, onaylı cevap (işaretleriyle), ana bilgiler (≤ 10), durum (`DRAFT`, `VERIFIED`, `STALE`, `DISPUTED`, `ARCHIVED`), eskime nedeni, kapsam (bilgi alanları), kaynak tur, oluşturan, onay döngüsü, gereken onay sayısı, itiraz sayısı, `verified_at`, `expires_at`.
- `knowledge_card_citation` (numara, belge, sayfa, birim), `knowledge_card_source` (parça, belge, sayfa, SHA-256), `knowledge_card_verification` (döngü × onaylayan), `knowledge_card_dispute` (döngü × kart başına hesap takma adı, neden, gün), `knowledge_card_event` (durum geçmişi), `turn_knowledge_card` (hangi tur hangi karttan).
- `golden_case` (onaylanan her kart; arşivde pasif), `eval_run`, `eval_case_result`.
- Bağlamalar, onaylar, itirazlar ve olaylar uygulama rolüyle değiştirilemez (REVOKE UPDATE); atıf, kaynak, onay ve olay yalnız kartla silinir.

### Durum makinesi

| Durumdan | Olay | Duruma | Kim |
|---|---|---|---|
| – | yöneticinin kendi kaynaklı cevabından oluşturma | `DRAFT` | insan |
| `DRAFT` | onay; döngüde yeterli farklı onaylayan (1 ya da 2) | `VERIFIED` (+ altın sete) | insan |
| `DISPUTED`, `STALE(EXPIRED)` | onay | yeni döngü: `DRAFT` → `VERIFIED` | insan |
| `DRAFT`, `VERIFIED`, `DISPUTED` | alıntılanan belge silindi / değiştirildi / taşındı, özet tutmuyor | `STALE` (ilk neden kalır) | sistem |
| `VERIFIED` | `expires_at` geçti (gece işi; sunma zaten durur) | `STALE(EXPIRED)` | sistem |
| `VERIFIED` | döngüde farklı kullanıcılardan ≥ eşik 👎 | `DISPUTED` | sistem |
| arşiv dışı her durum | arşivleme | `ARCHIVED` (altın setten çıkar) | insan |

Kaynağı değişmiş `STALE` kart yeniden onaylanamaz: metin artık yoktur; güncel cevaptan yeni kart açılır.

### Oluşturma ve onay

- `POST /v1/admin/knowledge-cards {conversationId, turnId, question?, keyFacts?, verify}`: yalnız yöneticinin **kendi** turu (başkasının sorusu ve cevabı onun kalır; ADR-0023). Tur `ANSWERED`, atıflı ve karartılmamış olmalı. Cevap metni ve atıflar turdan alınır, değiştirilemez; yalnız eşleşmede kullanılacak kanonik soru düzeltilebilir.
- Her atıf sayfası yöneticinin ilkeleriyle okunur: okunamayan sayfa `11041`, kişisel belge `11046`. Soru bir kez gömülür (yerel embedding, işlem dışında). Kart, atıflar, bağlama, olay ve denetim tek `qa` işleminde.
- Onaylayan bütün kaynakları okuyabilmeli ve özetler tutmalı (`11041`, `11044`). İki kişi kuralında aynı yöneticinin ikinci onayı `11045`.

### Sunma kuralları

1. Bayrak açıkken soru bir kez gömülür; `VERIFIED`, süresi dolmamış, aynı embedding modelli kartlardan en yakın 3'ü tam kosinüsle okunur.
2. Yalnız benzerliği **≥ 0,93** olanlar (başlangıç ayarı; müşteri setiyle ölçülecek).
3. Sırayla: kart yeniden okunur ve hâlâ sunulabilir olmalı; bağlı ve atıflı her sayfa **çağıranın ilkeleriyle** okunur; her bağlı özet sayfasında bulunmalı. Biri tutmazsa sonraki aday; hiçbiri tutmazsa normal yol, sanki kart yokmuş gibi.
4. Cevap kartın metnidir; atıflar sunucuda, okunabilen sayfa metnine eşlenir (llm-rules 3.3, `CitationExtractor`), alıntı penceresi o metinden. `model` = `verified-answer`, `memory = {cardId, verifiedAt, expiresAt}` (ekleyici alan). Chat modeli çağrılmaz; dayanaklılık kontrolü (ADR-0047) çalışmaz (`unverified` null): metni bir insan onayladı.
5. Akışta `RETRIEVING`'den sonra `MEMORY_FOUND` ("Onaylı bilgi bulundu"), ardından `citations` ve `done` (`memory` ile); `delta` ve `WRITING` yoktur.
6. Tur normal saklanır ve karta bağlanır; bu turun 👎'ü kartın itirazıdır (kullanıcı, kart ve döngü başına bir kez).
7. Kart araması veritabanı hatası verirse normal yola düşülür (log yalnız istisna türü).

### Bayrak kararı

Sunma **varsayılan açık**: güvenli koşulların hepsi kodda zorunlu: yalnız insanın onayladığı kart, okuyucunun ilkeleriyle sorgu anında yeniden okuma, özet kontrolü, süre, itiraz eşiği, yüksek eşik. Hiç kart yokken maliyet tek bir küçük sorgudur. `VERSO_QA_KNOWLEDGE_ENABLED=false` eski yolu birebir geri getirir (embedding yine aramanın içinde).

### Altın set ve gecelik kontrol

- Onaylanan her kart bir altın vakadır: soru ve vektörü, beklenen sayfa ve parça özetleri, ana bilgiler.
- `KnowledgeEval` her gece 02:30'da (Europe/Istanbul) önce bakım yapar: süresi dolan onayları `STALE` yapar; 365 günlük arşiv kartlarını ve 180 günlük koşuları siler. Sonra her aktif vaka için:
  - bağlı özetler hâlâ var mı (`DocumentProvenance.sourceHashes`, okuyucu filtresiz ama metinsiz)? Yoksa `SOURCE_LOST` ve canlı kart `STALE(SOURCE_CHANGED)`;
  - varsa saklı vektörle, kaynak belgelerin okuyucu kitlesi olarak hibrit arama (`rankForAudience`, yalnız parça kimlikleri, rerank yok): bağlı bir parça ilk k (5) içinde mi? `FOUND` (sıra), `MISSED`; vektör başka modelin ise `SKIPPED`.
- Metrikler: recall@k = bulunan / (bulunan + kaçan), kaynak kayması = kaybolan / vaka. Uyarı: önceki başarılı koşuya göre recall **0,02**'den fazla düşerse ya da kayma **%10**'u geçerse. Gösterge `verso_qa_knowledge_eval_alert`, alarm `KnowledgeRecallDropped` (ticket, runbook `docs/runbooks/knowledge-recall-dropped.md`).
- Gecelik koşu hiçbir model çağırmaz (embedding de yok). Uçtan uca mod (cevabı chat modeline yazdırıp ana bilgileri aramak) bu fazda **eklenmedi**: ölü bir ayar bırakmamak için anahtarı da yok; faz 2'de kanca altyapısıyla birlikte, varsayılan kapalı gelir.

### API (yalnız `verso-admin`)

`POST /v1/admin/knowledge-cards`, `GET /v1/admin/knowledge-cards?state=`, `GET /v1/admin/knowledge-cards/{id}`, `POST …/{id}/verify`, `POST …/{id}/archive`, `GET /v1/admin/knowledge-eval/runs`, `POST /v1/admin/knowledge-eval/runs`. Ayrıntı ve hata kodları (11040-11048): `docs/api-knowledge-integration-v1.md`. Soru ucu: `memory` alanı ve `MEMORY_FOUND` aşaması ekleyicidir.

### Denetim (yalnız kimlikler)

`KNOWLEDGE_CARD_CREATED`, `KNOWLEDGE_CARD_VERIFIED` (`VERIFIED` / `VERIFICATION_ADDED`), `KNOWLEDGE_CARD_ARCHIVED` (yönetici); `KNOWLEDGE_CARD_STALE` (neden), `KNOWLEDGE_CARD_DISPUTED` (aktör `system:knowledge-memory`); `KNOWLEDGE_EVAL_RUN` (`MANUAL` yönetici, `SCHEDULED` sistem). Hedef türü `KNOWLEDGE_CARD` / `KNOWLEDGE_EVAL`. Hepsi iş değişikliğiyle aynı işlemde outbox'a (ADR-0039).

### Panel

Yönetim → "Onaylı bilgiler": durum süzgeci, kartlar (onay sayısı, süre, itiraz, eskime nedeni), ayrıntı (cevap, kaynaklar, gün başına itiraz, geçmiş), Onayla / Arşivle, son kontroller ve "Şimdi kontrol et". "Soru sor" ekranında yöneticiye kaynaklı ve saklanmış cevapta "Onayla"; bellekten gelen cevapta "Uzman onaylı" rozeti. `innerHTML` ve satır içi stil yok; Trusted Types değişmedi.

## Sonuçlar

- Sıcak yol: bellek açıkken embedding `search`'ün dışına çıkar; ek iş bir `qa` sorgusu (kart yoksa bu kadar) ve eşleşmede bir `document` sorgusu. Bellekten cevapta chat yoktur: p99 düşer, model kapalıyken de cevap verilebilir (devre açıkken yine 503; ileride değerlendirilir).
- Eskime olay kaybına dayanıklıdır: outbox (en az bir kez) + sunarken özet kontrolü + gece kontrolü.
- Kartın metni kişisel veri içerebilir; VKED'e yeni işleme olarak eklendi (`docs/uyum/01-vked-sablonu.md`, R17). KVKK silme başvurusu kişinin itirazlarını da siler.
- Geçmişten açılan turda rozet görünmez (tur cevabın kaynağını saklamaz; yalnız bağlantı tablosu var); faz 2'de `TurnResponse`'a eklenebilir.
- Rerank (ADR-0043) açık kurulumda gece kontrolü füzyon sırasını ölçer; canlı sıralamadan biraz farklı olabilir.

## Yeniden değerlendirme koşulu

- Altın sette yanlış eşleşme (aynı karta düşen farklı soru) görülürse ya da müşteri seti eşiği ölçerse: `match-threshold`.
- Bellekten cevaplarda itiraz oranı > model cevaplarınınki: onay süreci ve iki kişi kuralı varsayılanı.
- Gece kontrolü 1 saati (kira) aşarsa: `max-cases` ve parçalı koşu.
