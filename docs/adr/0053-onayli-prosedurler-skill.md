# ADR-0053: Onaylı prosedürler (SKILL.md becerileri; öğrenen katman faz 3)

- **Durum:** Kabul edildi. Cevabın üstünde gösterme varsayılan **kapalı** (`VERSO_QA_SKILLS_SERVING_ENABLED`); taslak, onay, SKILL.md dışa aktarımı, MCP araçları ve gece kontrolü bayraktan bağımsız çalışır.
- **Tarih:** 2026-10-26
- **Karar verenler:** doguyaras.
- **Uyguladığı:** ADR-0048 faz 3 (ilkeler, arıza biçimleri ve onay noktaları oradadır, burada tekrar edilmez). Faz 1: ADR-0049, faz 2: ADR-0052.
- **Dayanak:** `research_notes/Verso öğrenen bilgi katmanı/research.md` §3 (beceri = talimat + arama ipuçları + MCP araç çağrısı, keyfi kod değil; türetilmiş eser olarak kaynak özetine bağlı; her beceride 2-5 altın vaka; insan onayı), `research_notes/Verso kapsamlı küresel araştırma/claude_ecosystem.md` Q1 (agentskills.io: `name` ≤ 64, küçük harf/rakam/tire; `description` ≤ 1024; isteğe bağlı `metadata` dizgi eşlemi; SKILL.md < 500 satır; claude.ai Skills API'si spesifikasyon dışı anahtarda hata verir).
- **İlgili eşikler (referans Bölüm 24):** sıcak yola uzak çağrı eklenmez (ADR-0008); bayrak açıkken soru yoluna bir `qa` sorgusu ve eşleşmede bir `document` sorgusu eklenir (aşağıda). Yeni servis, broker ya da veritabanı yok.
- **Genişlettiği:** ADR-0028 (okuyucu ilkeleri), ADR-0031 (MCP sunucusu), ADR-0039 (outbox), ADR-0047 (dayanaklılık kontrolü), ADR-0049 (kart, özet bağlama, altın set, iki kişi kuralı), ADR-0052 (kümeler, kancalar, bildirimler).

## Bağlam

- Danışmanlık ekibi her müşteride aynı şeyi görüyor: "nasıl yaparım" soruları (VPN kurulumu, masraf iadesi, izin talebi) tekrar tekrar soruluyor, cevapları birkaç belgeye dağılmış. Faz 1 tek soruluk onaylı cevabı, faz 2 tekrarlayan soru kümelerini verdi; eksik olan, bunları **adım adım bir prosedüre** çevirmek ve çalışanların kullandığı asistanlara (Claude, Copilot) kurumun onayladığı biçimde taşımaktır.
- SKILL.md açık bir standarttır (agentskills.io; Claude Code, Codex, Copilot ve diğerleri okur). Ama spesifikasyon sahiplik, inceleme ve korumalı çalıştırma getirmez (research.md §3); güvenceleri Verso koymalıdır.
- `qa` modülü `document` şemasına erişemez (AGENTS.md 4); MCP modülü yalnız `*-api` sözleşmelerinden geçer (ADR-0031).

## Seçenekler

| Konu | Seçenekler | Seçilen ve neden |
|---|---|---|
| Yer | ayrı `knowledge` modülü · **`qa` şeması** | `qa` (V6): beceri kartlardan ve kümelerden doğar, sunma soru yolundadır; ADR-0049'daki gerekçe aynı (ayrı modül 10 000 kartta) |
| Beceri içeriği | kod/betik · **talimat + kaynak bağlantısı** | Adımlar düz metindir; SKILL.md'de `scripts/` yok, referanslar yalnız `verso_fetch` kimlikleridir (`verso://passages/<id>`). Belge metni dışa aktarılmaz; istemci kaynağı kullanıcının kendi yetkisiyle okur |
| Taslak | LLM yazar · **şablon** (+ isteğe bağlı yerel model yalnız sıralar ve kısaltır) | Onaylı kartların cevapları ve alıntıladıkları sayfalardan sabit şablon; yerel model yalnız sıra ve kısaltma önerir, her adım ADR-0047'nin AnchorCheck'iyle kendi kaynaklarına karşı yeniden denetlenir, tutmayan şablon metnine döner; bulut modu hiç kullanılmaz (ADR-0048 ilke 6) |
| Kaynak bağlama | sayfa · **adım başına parça + SHA-256** | Her adım kendi işaretlerinin ([n]) alıntıladığı sayfaların parçalarına özetle bağlıdır (işaretsiz adım: cevabın bütün atıf sayfaları, en çok 10). Eşleştirme (sayfa, özet) ile, kartlardaki gibi |
| Görünürlük | kopyalanmış kapsam · **sunarken yeniden okumak** | `scope_collections` (bütün kaynakların bilgi alanları; okuyucu hepsine erişmeli: kesişim kuralı) yalnız gösterim ve denetim içindir; her sunuş bağlı parçaları çağıranın ilkeleriyle yeniden okur |
| Sunma | cevabın yerine · **cevabın üstünde** · gösterme | Aşağıda "Sunma kararı" |
| Eşleşme | soru metni · **tetikleyici soruların vektörü** | Onaylı becerinin örnek soruları (tetikleyiciler) yerel modelle gömülür; soru yolunun **aynı** vektörüyle en yakın tetikleyici, eşik **0,90** (başlangıç ayarı, kartın 0,95'inden düşük çünkü yanlış eşleşmede cevap yine gelir) |
| Altın vakalar | kartın vakası · **beceriye özel 2-5 vaka** | Soru → beklenen kaynak sayfa (+ isteğe bağlı sayfada geçen bilgi); onay için 2-5 zorunlu, gece kontrolü kartlarla aynı koşuda çalıştırır |

## Karar

### Veri modeli (`qa` şeması, `V6__skills.sql`)

- `skill`: güncel sürüm ve ad (canlı beceri başına tekil ad; agentskills.io biçimi DB CHECK'te), durum (`DRAFT`, `APPROVED`, `STALE`, `ARCHIVED`), eskime nedeni (`SOURCE_DELETED`, `SOURCE_REPLACED`, `SOURCE_MOVED`, `SOURCE_CHANGED`), köken (`CLUSTER`, `MANUAL`) ve küme kimliği, kapsam, oluşturan, onay döngüsü, gereken onay sayısı (kartların iki kişi kuralı ayarı: `verso.qa.knowledge.two-person-rule`), onay zamanı.
- Sürüm başına değişmez satırlar: `skill_version` (ad, açıklama tek satır ≤ 1024, neden `DRAFTED`/`EDITED`, yoğunlaştırıldı mı), `skill_step` (≤ 12; metin ve dayanaklı mı), `skill_step_source` (parça, belge, sayfa, birim, SHA-256), `skill_trigger` (≤ 10; soru, vektör, model), `skill_golden_case` (≤ 5; soru, belge, sayfa, isteğe bağlı bilgi, vektör). `skill_approval` (döngü × onaylayan), `skill_event` (durum geçmişi), `skill_eval_result` (koşu × vaka). Uygulama rolü bunları değiştiremez ve silemez (REVOKE); yalnız beceriyle silinir.
- `eval_run`'a dört sayaç (`skill_cases`, `skill_found`, `skill_missed`, `skill_source_lost`); kancaya `SKILL_STALE` tetikleyicisi, `SKILL` konusu ve `SKILL_STALE_NOTIFY` kuralı.

### Durum makinesi

| Durumdan | Olay | Duruma | Kim |
|---|---|---|---|
| – | kümeden ya da seçilen kartlardan/turlardan taslak | `DRAFT` (sürüm 1) | insan |
| `DRAFT`, `APPROVED` | düzenleme (yeni sürüm, yeni onay döngüsü) | `DRAFT` | insan |
| `DRAFT` | onay; döngüde yeterli farklı onaylayan, 2-5 altın vaka, ≥ 1 tetikleyici, her adım dayanaklı, onaylayan bütün kaynakları özetleri tutarak okuyor | `APPROVED` | insan |
| `DRAFT`, `APPROVED` | kaynak belge silindi / değişti / taşındı (outbox) ya da özet gece kontrolünde tutmuyor | `STALE` (ilk neden kalır) | sistem |
| arşiv dışı her durum | arşivleme | `ARCHIVED` | insan |

`STALE` beceri düzenlenemez ve onaylanamaz: kaynak metin artık yoktur; güncel kartlardan yeni taslak açılır. Düzenleme adımları yeniden yazar, sıralar ve çıkarır ama **yeni kaynak eklemez**: her adım geldiği adımın bağlarını taşır. Düzenleyen de bütün kaynakları okuyabilmeli; adımlar onun okuduğu metne karşı yeniden denetlenir.

### Taslak

- **Kümeden (a):** faz 2'nin son başarılı teşhis koşusundaki kümeler `GET /v1/admin/skills/suggestions` ile önerilir (yalnız k-anonim anahtar sözcükler ve sayılar; kişi ve soru metni yok). Küme merkezine kosinüsü ≥ 0,80 olan (`cluster-card-threshold`) `VERIFIED` kartlar taslağın malzemesidir; kartlardan biri "nasıl/adım/başvuru…" diye soruyorsa öneri "muhtemelen bir prosedür" diye öne alınır. Yöneticinin okuyamadığı ya da metni değişmiş kart atlanır.
- **Elle (b):** yönetici `VERIFIED` kartları ve/veya **kendi** kaynaklı cevaplarını (ADR-0023: başkasının sorusu onundur) seçer.
- **Şablon:** liste biçimindeki cevap (1., 2., - …) madde başına bir adım, diğer cevap tek adım; [n] işaretleri metinden çıkar, adımı alıntıladığı sayfaların parçalarına bağlar; adım ≤ 400 karakter, gizli karakterler atılır, aynı adım bir kez. Tetikleyiciler kartların (yöneticinin yazdığı, onaylı) sorularıdır; altın vaka tohumu her cevabın ilk atıf sayfası ve yalnız o sayfada gerçekten geçen ana bilgisi. Ad küme sözcüklerinden ya da ilk sorudan (`yillik-izin-talebi`), çakışırsa `-2`; açıklama şablonlu Türkçe tek satır.
- **İsteğe bağlı yerel model (`condense: true`):** yalnız local modda, instance başına aynı anda bir çağrı (meşgulse adım atlanır), 60 sn sınır, işlem dışında. Çıktı adımların bir permütasyonu değilse yok sayılır; her kısaltılmış adım kendi kaynaklarına karşı AnchorCheck'ten geçmezse şablon metnine döner. Kaynaklar hiç değişmez.
- Her adımın dayanaklı olup olmadığı (`grounded`) saklanır ve ekranda gösterilir; dayanaksız adım onayı engeller (`11075`). Taslak hiçbir yerde sunulmaz.

### Sunma kararı (cevabın üstünde, bayrak arkasında)

**Seçilen: "Bu bir prosedür: <ad>" kutusu üretilen cevabın üstünde; cevabın yerine geçmez. Varsayılan kapalı. Ölçüm bu iş kapsamında yapılmadı.**

- Kartın aksine bir prosedür bir soruya verilmiş cevap değildir, bir işin adımlarıdır: soran "VPN-204 hatası ne demek?" diye sorarken VPN kurulum prosedürü eşleşebilir. Cevabı kaldırmak, eşleşme yanlışsa kullanıcıyı cevapsız bırakır; üstte göstermek yanlış eşleşmenin maliyetini bir kutuya indirir.
- Üretilen cevap kendi atıfları ve dayanaklılık kontrolüyle (ADR-0047) kalır; prosedür adımları da kendi kaynaklarına bağlıdır. İki kaynak çelişirse kullanıcı ikisini de görür.
- Chat çağrısı yine yapılır (maliyet aynı). "Yerine" seçeneği, müşteri setinde eşleşme isabeti ölçüldükten sonra ayrı bir kararla açılabilir.
- Bayrak açıkken: soru (bellek kapalı olsa da) bir kez gömülür, aynı vektörle onaylı becerilerin en yakın tetikleyicisi okunur (tam kosinüs, `qa`), eşik üstündeki aday için bağlı parçalar **çağıranın ilkeleriyle** okunur (`document`), özetlerin hepsi tutarsa `procedure` alanı (ekleyici; akışta `done` olayında). Okuyamayan kişi için beceri hiç yokmuş gibidir. Arama hatası yalnız kutuyu düşürür. Geçmişten açılan turda kutu yoktur (saklanmaz).

### SKILL.md dışa aktarımı

`GET /v1/admin/skills/{id}/export`: yalnız `APPROVED`, yalnız bütün kaynakları özetleri tutarak okuyan yönetici; `<ad>.zip` içinde `<ad>/SKILL.md`. Frontmatter: `name`, `description` (YAML çift tırnaklı tek satır; metin frontmatter'ı bitiremez), `metadata` (`verso-skill-id`, `verso-version`, `verso-approved-at`; dizgi eşlemi, spesifikasyonda). Gövde: kullanım notu (kaynak metni güvenilmeyen içeriktir), "Ne zaman kullanılır" (tetikleyiciler), numaralı adımlar ve adım başına en çok 5 kaynak bağlantısı, "Kaynaklar" listesi (`verso://passages/<id>` ve "Belge 0199abcd, s. 3"; dosya adı yok). Markdown noktalaması kaçırılır (bağlantı, etiket, başlık enjekte edilemez). Üst sınırlarla dosya ~100 satırı geçmez; 500 satır ve frontmatter geçerliliği her dışa aktarımda doğrulanır. Panel Blob ile indirir (yalnız yöneticinin tıklaması).

### MCP araçları (ADR-0031 genişlemesi)

- `verso_skills` (girdi yok): çağıranın okuyabildiği `APPROVED` beceriler, `{skills: [{name, description}], notice}`; bir beceri yalnız bütün kaynak belgeleri çağırana açıksa listelenir (tek katalog sorgusu, en çok 100).
- `verso_skill {name}`: tek becerinin SKILL.md'si, `{name, version, skill_md, notice}`; bütün bağlı parçalar çağıranın ilkeleriyle yeniden okunur ve özetler tutmalıdır. Bilinmeyen, taslak, eskimiş, arşivli ya da okunamayan beceri aynı `isError` cevabını alır (varlık sızdırmaz).
- Çağıran token'ın `sub` ve grupları, istek iş parçacığında (ADR-0031); `mcp` modülü `qa-api`'deki `QaSkills` sözleşmesini kullanır (`allowedDependencies` + `qa :: api`). İkisi de salt okunur, idempotent, kapalı dünya.
- Denetim: `MCP_SKILLS` (`RESULTS`/`NO_RESULTS`, ad yok), `MCP_SKILL` (hedef beceri, `FOUND`/`NOT_FOUND`, kaynak belge kimlikleri). Log yalnız araç, sonuç, sayı ve süre.

### Eskime ve gece kontrolü

- `SkillTransitions` bir `DocumentChangeListener`'dır (ADR-0049 boru hattı): güncel sürümü silinen/değişen/taşınan belgeye bağlı `DRAFT`/`APPROVED` beceri, olay, denetim (aktör `system:knowledge-skills`) ve `SKILL_STALE` kanca olayıyla aynı `qa` işleminde `STALE` olur; tekrar teslim bir şey değiştirmez.
- `SKILL_STALE_NOTIFY` (varsayılan açık) yöneticilerin ortak bildirim kutusuna yazar (ADR-0052'de bildirimler bütün yöneticilerindir; becerinin sahibi oluşturan yöneticidir ve oradan görür). Belgesi altın vakası olan onaylı beceriye kaynak olan belge değişince `DOCUMENT_CHANGED_RUN_EVAL` gece kontrolünü de ister.
- Gece kontrolü (ADR-0049 koşusu, aynı kilit ve kira) onaylı becerilerin altın vakalarını da çalıştırır, model çağırmaz: becerinin bütün özetleri duruyor mu (`sourceHashes`, metinsiz)? Durmuyorsa `SOURCE_LOST` ve beceri `STALE(SOURCE_CHANGED)`; duruyorsa saklı vektörle kaynak belgelerin kitlesi olarak hibrit arama, beklenen sayfanın bir parçası ilk k'da mı (`FOUND`/`MISSED`, başka model `SKIPPED`). Sayaçlar ayrı tutulur; kartların recall'u, taban çizgisi ve `KnowledgeRecallDropped` uyarısı değişmez (yeni uyarı yok).

### API (yalnız `verso-admin`)

`GET /v1/admin/skills?state=`, `GET …/suggestions`, `POST /v1/admin/skills`, `GET …/{id}`, `PUT …/{id}`, `POST …/{id}/approve`, `POST …/{id}/archive`, `GET …/{id}/diff?from&to`, `GET …/{id}/export`. Kaynaklarından birini okuyamayan yönetici durumu, sayıları ve geçmişi görür, metni görmez (`readable: false`). Hata kodları **11070-11079**: `docs/api-knowledge-integration-v1.md`. Soru ucu: `procedure` alanı ekleyicidir (`docs/api-questions-integration-v1.md`).

### Denetim (yalnız kimlikler)

`SKILL_DRAFTED` (`CLUSTER`/`MANUAL`), `SKILL_EDITED`, `SKILL_APPROVED` (`APPROVED`/`APPROVAL_ADDED`), `SKILL_ARCHIVED`, `SKILL_EXPORTED` (kaynak belge kimlikleri), `SKILL_STALE` (sistem; neden), `KNOWLEDGE_HOOK_FIRED` (hedef `SKILL`), `MCP_SKILLS`, `MCP_SKILL`. Hedef türü `SKILL`. Yönetici eylemleri iş değişikliğiyle aynı işlemde outbox'a (ADR-0039).

### Panel

Yönetim → "Prosedürler" (`g r`): kümelerden öneriler ("Taslak oluştur", isteğe bağlı "yerel modelle sırala ve kısalt"), onaylı kartlardan elle taslak, durum süzgeçli liste, ayrıntıda adımlar ve kaynakları (dayanaksız adım işaretli), örnek sorular, kontrol soruları, onayı engelleyen nedenler, geçmiş ve sürüm farkları; Düzenle (yeni sürüm: ad, açıklama, adım sırası/metni/çıkarma, örnek sorular, 2-5 kontrol sorusu ve beklenen sayfa), Onayla, Arşivle, "SKILL.md indir". "Soru sor" ekranında "Bu bir prosedür: <ad>" kutusu. `innerHTML` ve satır içi stil yok; Trusted Types değişmedi.

### Gizlilik ve saklama

- Beceri, paylaşılan belgelerden ve onaylı cevaplardan türetilmiş prosedür metni tutar: tasarım gereği kişisel veri değildir (kişisel belge kaynak olamaz, kullanıcı sorusu metni tetikleyici olmaz: tetikleyiciler kartların yönetici onaylı sorularıdır), ama belgede geçen bir adı alıntılayabilir; oluşturan ve onaylayanın hesap kimliği hesap verebilirlik içindir. VKED'de işleme ve R19.
- Saklama belge yaşam döngüsünü izler: kaynak silinince beceri hemen `STALE` olur ve hiçbir yerde sunulmaz; arşivlenen beceri 365 gün sonra satırlarıyla silinir (`archived-retention`); değerlendirme sonuçları koşuyla (180 gün) gider. KVKK silme başvurusu beceriye dokunmaz (kişiye ait satır yoktur; oluşturan kimliği denetim kaydı gibi tutulur).
- Loglar kimlik ve sayı taşır; ad, açıklama, adım, soru ve parça metni hiçbir log satırına girmez.

## Sonuçlar

- Olumlu: tekrarlayan "nasıl yaparım" soruları denetlenebilir, kaynağa bağlı, sürümlü prosedürlere dönüşür; müşteri bunları kendi asistanında (Claude, Copilot, MCP ya da SKILL.md) kendi yetkisiyle kullanır; kaynak değişince prosedür kendiliğinden çekilir.
- Olumsuz: şablon cevabı bölmekten öteye gitmez, iyi bir prosedür çoğu zaman yöneticinin düzenlemesini ister; AnchorCheck olumsuzlamayı ve yer değiştirmiş adı göremez (ADR-0047); düzenleme yeni kaynak ekleyemez (yeni kaynak için yeni kart, sonra yeni taslak).
- Sıcak yol: bayrak kapalıyken değişiklik yok. Açıkken bellek kapalı kurulumda da embedding aramanın dışına çıkar (sayı aynı), artı bir `qa` sorgusu ve eşleşmede bir `document` sorgusu (kritik akış kaydı `docs/ai/repo-context.md`).
- SKILL.md'yi bir istemciye yükleyen kurum, dosyanın Verso dışında kaldığını bilir: dosya belge metni taşımaz, ama prosedür metnini taşır; kaynak değişince dosya kendiliğinden güncellenmez (MCP'deki `verso_skill` günceldir, tercih edilir).

## Yeniden değerlendirme koşulu

- Müşteri setinde tetikleyici eşleşme isabeti ölçülünce: eşik ve "cevabın yerine" seçeneği.
- Şablon taslakların çoğu ağır düzenleme isterse: yerel yoğunlaştırmanın varsayılanı ya da bulut eleştirisi (ADR-0048 ilke 6, ayrı karar).
- Bir müşteride onaylı beceri sayısı birkaç yüzü geçerse: tetikleyici vektör indeksi.
