# ADR-0052: Bilgi sağlığı teşhisi ve kancalar (öğrenen katman faz 2)

- **Durum:** Kabul edildi. Teşhis işi varsayılan açık (`verso.qa.diagnostics.enabled`), kancaların varsayılanları aşağıdaki tabloda.
- **Tarih:** 2026-10-26
- **Karar verenler:** doguyaras.
- **Uyguladığı:** ADR-0048 faz 2 (ilkeler, k uzlaştırması ve arıza biçimleri oradadır, burada tekrar edilmez). Faz 1: ADR-0049.
- **Dayanak:** araştırma notu `research_notes/Verso öğrenen bilgi katmanı/research.md` §6 (teşhis, BetrVG, k), §7 (kanca taksonomisi, güvenli otomasyon sınırı), §8 (sürekli değerlendirme).
- **İlgili eşikler (referans Bölüm 24):** sıcak yola uzak çağrı eklenmez (ADR-0008); yeni servis, broker ya da veritabanı yok (ADR-0001, ADR-0003).
- **Genişlettiği:** ADR-0023 (k = 3 soru listesi, `VERSO_SHOW_UNANSWERED_QUESTIONS`), ADR-0026/0039 (denetim, outbox), ADR-0028 (okuyucu ilkeleri), ADR-0034 (KVKK), ADR-0047 (dayanaklılık, AnchorCheck katlaması).

## Bağlam

- Faz 1 doğruluk döngüsünü kurdu; danışmanlık ekibinin müşteriye götüreceği şey ise kurumun **bilgi açıklarıdır**: hangi konularda soru cevapsız kalıyor, hangi belgeler eski ama hâlâ kullanılıyor, hangileri hiç kullanılmıyor, hangi belgeler birbirini tutmuyor (research.md §6: Kapa "Finding + Recommendation", Guru doğrulama kuyruğu).
- Teşhisler çok boyutludur (küme × pencere × bilgi alanı). ADR-0048: her hücrede ve pencerede **k ≥ 5** farklı hesap; kişi bazında döküm yok; soru metni yalnız mevcut politikayla.
- Kancalar araştırmada ağırlıkla "sahibine görev" olarak uygulanıyor (Guru, Glean, Kapa). Güvenli sınır: kanca **bildirir, işaretler, düşürür, eval çalıştırır**; terfi, silme, ACL insandır (ADR-0048 ilke 5).
- `qa` modülü `document` şemasına erişemez (AGENTS.md 4); parçalar, vektörler ve belge yaşları `document`'tadır.

## Seçenekler

| Konu | Seçenekler | Seçilen ve neden |
|---|---|---|
| Hesaplama yeri | istek anında · **zamanlanmış toplu iş** | Gece 03:30 (golden-set kontrolünden sonra) toplu iş; rapor yalnız saklanan sonucu okur. Sıcak yol değişmez; tek ek yazma turdaki doğrulanamayan cümle sayısıdır (kolon) |
| Soru vektörü | sıcak yolda saklamak · **toplu işte gömmek** | İş, belirsiz soruları yerel embedding modeliyle bir kez gömer ve `qa.turn_question_vector`'a yazar (turla silinir). Bellek kapalıyken de çalışır; sıcak yol bütçesi aynı |
| Kümeleme | LLM ile etiketleme · HDBSCAN · **deterministik lider + k-means arıtma** | Model yok, rastgelelik yok: girdi tur kimliğine göre sıralanır, aynı sorular hep aynı kümeyi verir; O(soru × küme). Etiket yerine k-anonim **anahtar sözcükler**: insanların yazdığı biçimiyle sözcük (kök değil; bir Türkçe kökün en çok kullanılan biçimi), yalnız tam o sözcüğü en az k farklı kişi yazdıysa; kökü olmayan durak sözcükler çıkar |
| Kilit | ShedLock · advisory lock · **koşu satırı** | ADR-0049 ile aynı desen: `qa.diagnostic_run`'da tek `RUNNING` satırı (kısmi tekil indeks) + claim token + kira |
| Çelişki | LLM yargıcı · **benzer parça çifti + AnchorCheck** | `document-api` `DocumentDiagnostics.similarChunkPairs`: rakam içeren paylaşılan parçaların HNSW en yakın komşuları (başka belgeden, ≥ 0,90); ADR-0047'nin katlamasıyla aynı bağlamdaki farklı sayı/kod. "on dört" ile "14" aynı değerdir. Koşu başına 2 000 parça, imleçle devam |
| Değerlerin saklanması | değerleri tablo içinde · **yalnız kimlik + özet** | Çelişen değerler belge içeriğidir; saklanmaz. Rapor iki parçayı yöneticinin **kendi ilkeleriyle** yeniden okur; ikisini de okuyabiliyorsa değerler, birini okuyabiliyorsa yalnız o tarafın adı, hiçbirini okuyamıyorsa kimlik ve sayfa |
| Kanca motoru | serbest kural dili · **sabit kural kümesi + aç/kapa** | Kurallar migration'la gelir; yönetici yalnız açar/kapatır. Eylem enum'u ve DB CHECK'i dört eylemle sınırlı; motorun terfi/silme/ACL yapabilecek bağımlılığı yok |
| Kanca beslemesi | Spring olayı · **outbox** | Kart düşmesi, itiraz, küme, çelişki, recall düşüşü `qa.outbox_event`'e işle aynı transaction'da `knowledge.hook.triggered` olarak yazılır; belge değişikliği zaten `document.changed` outbox'ından gelir. Commit sonrası eager teslim (ayrı sanal iş parçacığında, denetim kayıtlarıyla aynı yol), poll emniyet ağıdır; yalnız poll ile teslim, poll'un kapalı olduğu ortamlarda (testler) satırı bekletiyordu |
| Bildirim kanalı | e-posta/webhook · **uygulama içi kayıt** | `qa.hook_notification` (kimlik + sabit sözcük). E-posta ve webhook **sonra** (dış egress, alıcı yönetimi, imza gerektirir; yeni ADR) |

## Karar

### Sinyaller (toplu iş, `KnowledgeDiagnostics`)

1. **Belirsiz soru kümeleri:** pencere (30 gün) içindeki `NOT_FOUND`, `UNCITED` ve dayanaklılık kontrolünün işaretlediği (`unverified_sentences > 0`) turlar; en çok 5 000. Kosinüs ≥ 0,80 ile kümelenir; **≥ 5 farklı soran** (veritabanında `count(DISTINCT account_id)`) olmayan küme saklanmaz (`ck_question_cluster_askers` da reddeder). Her kümede boyut, soran sayısı, önceki koşudaki eşleşen kümeye göre eğilim (YENİ / BÜYÜYOR ≥ ×1,25 / DURAĞAN / KÜÇÜLÜYOR), k-anonim anahtar sözcükler ve temsilci soru **özetleri** (en az k farklı kişinin sorduğu normalleştirilmiş soru). Metin raporda yalnız `VERSO_SHOW_UNANSWERED_QUESTIONS=true` iken, geçmişten okunur (saklama süresiyle kaybolur).
2. **Eski belgeler:** 180 günden eski (Verso'da değişen dosya yeni belgedir; oluşturma zamanı son değişikliktir) ama pencerede **≥ k farklı kişiye** kaynak olmuş paylaşılan belgeler; onlara dayanan `STALE` kart sayısı.
3. **Hiç kullanılmayan belgeler ve kapsama:** bilgi alanı başına belge sayısı, pencerede kaynak olan belge sayısı (o alandan atıf alan kişi < k ise gizlenir) ve hiç atıf almamış belge.
4. **Çelişki adayları:** yukarıdaki tarama; koşu başına en çok 50 yeni aday; belge değişince adayları silinir, 30 gün görülmeyen aday silinir.
5. **Kart sayıları ve 👎 gerekçeleri:** durum başına kart, onayı 14 günde dolacaklar; bilgi alanı × gerekçe başına olumsuz puan (yalnız ≥ k farklı puanlayan hücreler).

Kişisel belgeler hiçbir teşhise girmez (yalnız paylaşılan bilgi alanları). Hiçbir tablo hesap kimliği tutmaz; çapraz tablo (küme × birim × gün) yoktur.

### Rapor

`GET /v1/admin/knowledge-health?from&to` (yalnız `verso-admin`): aralıkta biten en son başarılı koşudan; bölümler: bulgular (önem + bulgu + öneri, Türkçe, **sabit şablonlar**, model yazmaz), kümeler, eski belgeler, kapsama, hiç kullanılmayan belge sayısı, çelişkiler, kart sayıları, gerekçe hücreleri, eğilim serisi, inceleme bekleyen işaret sayısı. `GET …/export?format=MARKDOWN|JSON` aynı süzgeçle ek dosya (ad yalnız tarihler). `GET/POST …/runs` (409 `11061`). Her okuma `KNOWLEDGE_HEALTH_VIEWED` (VIEW / MARKDOWN / JSON). Belge adı yalnız yönetici okuyabiliyorsa; ADR-0023'ün ad politikası kişi × gün sayımı içindir, burada her belge hücresi ≥ k kişidir.

### Kancalar

| Kural | Tetikleyici | Eylem | Varsayılan |
|---|---|---|---|
| `DOCUMENT_CHANGED_RUN_EVAL` | belge silindi/değişti/taşındı ve bir altın vaka onu alıntılıyor | altın set kontrolü (birleştirilmiş, 10 dk sessizlikten sonra, `HOOK` koşusu) | açık |
| `CLUSTER_EMERGED_NOTIFY` | yeni küme | bildir | açık |
| `CLUSTER_GROWING_NOTIFY` | büyüyen küme | bildir | açık |
| `CONTRADICTION_FLAG` | yeni çelişki adayı | incelemeye işaretle | açık |
| `CONTRADICTION_DEMOTE_CARD` | yeni çelişki adayı | o parçalara bağlı canlı kartı `STALE(CONTRADICTION)` | **kapalı** |
| `CARD_STALE_NOTIFY` | kart eskidi | bildir | açık |
| `CARD_DISPUTED_FLAG` | kart itiraz eşiğini geçti | incelemeye işaretle | açık |
| `GOLDEN_RECALL_DROP_NOTIFY` | altın set uyarısı | bildir (kritik) | açık |
| `VERIFICATION_DUE_NOTIFY` | onayı 14 günde dolacak kart (gece kontrolü) | bildir | açık |

- Belge değişince kartın `STALE` olması kural değildir, güvencedir (ADR-0049): kanca kapatılsa da olur.
- Her eylem kendi transaction'ında denetim kaydı yazar (`KNOWLEDGE_HOOK_FIRED`, aktör `system:knowledge-hooks`, hedef konu kimliği, ayrıntı kural anahtarı). Bildirim kural × olay kimliğiyle tekildir; düşürme koşulludur; eval isteği tek satırdır: tekrar teslim hiçbir şeyi değiştirmez.
- Yönetici: `GET /v1/admin/knowledge-hooks`, `PUT …/{key} {enabled}` (`KNOWLEDGE_HOOK_RULE_CHANGED`, `<KEY>_ON/_OFF`), `GET /v1/admin/notifications?unread=`, `POST …/{id}/read`. Bildirimler bütün yöneticilerin ortak kutusudur.

### Gizlilik

- k ≥ 5 hücre ve pencere başına; yapılandırma yükseltebilir, düşüremez (açılış kontrolü + DB CHECK). Kişi bazında döküm yok; soru metni yalnız politikayla.
- Saklama: koşular ve kümeleri **400 gün** (çeyrek raporu bir yıl geriye bakabilsin), bildirimler **180 gün**, görülmeyen çelişki adayları **30 gün**; soru vektörleri turla (geçmiş saklama süresi, kullanıcının silmesi, KVKK silme başvurusu).
- Loglar sayı ve kimlik taşır; soru, parça metni, dosya adı hiçbir log satırına girmez.
- VKED'de yeni işleme ve R18; Almanya ekinde konsey notu.

### Hata kodları

11060 aralık geçersiz (400), 11061 teşhis zaten çalışıyor (409), 11062 kanca kuralı yok (404), 11063 bildirim yok (404).

## Sonuçlar

- Olumlu: danışmanlık ekibi her çeyrek aynı biçimde, şablonlu ve denetlenebilir bir rapor alır; kancalar faz 1'in eskime döngüsünü görünür kılar; yerelde, model çağrısı olmadan (yalnız yerel embedding) çalışır.
- Olumsuz: çelişki taraması aday üretir, yargı değil (madde numarası gibi sayılar yanlış alarm verebilir); küme etiketleri sözcüktür, cümle değil; teşhis bir gece gecikmelidir.
- Faz 2'nin geri bildirim ağırlığı (±%20, kümeye bağlı, sönümlü; ADR-0048 ilke 3) bu ADR'ye **girmedi**: kümeler artık var, ama sıralamaya dokunmak ayrı ölçüm ve ayrı karar ister.

## Yeniden değerlendirme koşulu

- Çelişki adaylarının çoğu yanlış alarm çıkarsa (müşteri ölçümü): bağlam terimi eşiği ve benzerlik eşiği.
- Bir kurulumda belirsiz soru 5 000'i aşarsa ya da koşu kirayı (1 saat) aşarsa: parçalı koşu.
- İşçi temsilciliği k ≥ 5'ten farklı bir eşik isterse; e-posta/webhook bildirimi istenirse (yeni ADR).
