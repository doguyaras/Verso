# Verso

> **Private document Q&A with source citations. Spring AI + local LLMs.**

Verso answers questions about your own documents (PDF, Word, TXT, Markdown) in Turkish and cites every answer with document name and page or section. In `local` mode the embedding and chat models run on Ollama inside your infrastructure and nothing of Verso can reach the internet: the application, its database and the model server sit on internal networks only, and a script proves it on the running stack. `cloud` mode swaps only the chat model for Anthropic or an OpenAI-compatible API, by configuration; documents, full texts and vectors stay home. Every response says which mode answered (`X-Rag-Mode`).

**English summary**

- **Stack:** Java 25, Spring Boot 4.1, Spring AI 2.0, PostgreSQL 18 + pgvector, Flyway, Ollama (bge-m3, gemma4:e2b), Keycloak (OIDC), an nginx edge proxy, Docker Compose. Optional: Prometheus, Alertmanager, Loki, Alloy, Grafana.
- **Answers:**
  - Retrieval with the ownership filter inside the vector query.
  - No model call below a measured similarity threshold.
  - A fenced prompt, and citations mapped on the server.
- **Turkish eval set** (version 2: 140 questions on 11 synthetic documents, including manuals for a WMS, ERP purchasing, an IT runbook and a support process), gemma4:e2b on an NVIDIA GPU:
  - retrieval recall@5 0.955, recall@1 0.804, MRR 0.869 (bge-m3);
  - citation hit rate 0.866;
  - correct fact in the answer 0.848;
  - "not found" accuracy 0.536 (the threshold stopped 6 of 28, the model got 9 of the other 22 right). Questions whose topic is in the documents but whose answer is not are the weakest case;
  - median answer time 1.4 s on the GPU (`deploy/compose.gpu.yaml`). On CPU the median was 21–28 s (version 1 set, two runs).
- **Engineering:**
  - A modular monolith with machine-checked architecture rules.
  - About 400 Java tests (the database ones on a real PostgreSQL) plus Node suites for the scripts, hooks and panel, and about 230 mutation checks that prove the tests catch what they claim to.
  - An ADR for every decision.
  - Encrypted backups with an automated restore drill in CI.
- **Run it:** the four commands below; the last one is the demo.
- **Disclaimer:** this is a portfolio project. The KVKK notes describe design choices; they are not legal advice.

## Ne yapar

Kurumlar yapay zekâ ile belgelerine soru sormak istiyor, ama belgeleri, soruları ve cevapları kendi altyapılarının dışına çıkarmak istemiyor. Bunun ardında KVKK md. 9 kapsamındaki yurt dışına aktarım soruları ve kurum politikaları var. Verso, bunun mevcut Java sistemlerine veri dışarı çıkmadan eklenebileceğini gösterir:

- PDF, Word (DOCX), TXT ya da Markdown yüklersin; Verso metni okur ve yerel bir embedding modeliyle indeksler. Orijinal dosya işlendikten sonra silinir.
- Türkçe soru sorarsın; cevap yalnız senin belgelerinden gelir ve her bilginin yanında kaynağı (belge, sayfa) durur.
- Belgelerde cevap yoksa "bulunamadı" der; uydurmaz.
- Her hesap kendi belgelerini ve kendisine açılmış **bilgi alanlarının** belgelerini görür. Bilgi alanını yönetici açar ve kurumun kimlik sağlayıcısındaki gruplara (Keycloak'ta grup yolu, Entra'da grup kimliği) ya da tek kullanıcılara verir. Yetki her soruda token'daki gruplarla, arama sorgusunun içinde ve sıralamadan önce uygulanır; grup claim'i bozuksa grup yok sayılır. Yönetici alanları yönetir ama içeriğini sormak için kendisine de erişim verilmesi gerekir (ADR-0028).
- Sorular ve cevaplar kullanıcının geçmişinde kaynak referanslarıyla durur; kullanıcı cevabı 👍/👎 ile puanlar, geçmişini silebilir. Geçmiş kurumun belirlediği süre kadar (varsayılan 90 gün) tutulur. Yönetici kullanım ve kalite sayılarını tek uçtan alır (ADR-0023).
- Değiştirilemez denetim kaydı: belge yükleme/silme, soru, geri bildirim, konuşma silme, yönetici metrik görüntüleme, denetim dışa aktarımı ve yönetici uçlarındaki 403'ler `audit` şemasına yalnız kimlik (id) ve sabit sözcüklerle yazılır; soru, cevap, dosya adı, IP ve serbest metin hiç girmez. Uygulama rolü yalnız INSERT ve SELECT yapabilir; varsayılan saklama 365 gün. Yönetici `GET /v1/admin/audit` ile okur, `GET /v1/admin/audit/export` ile CSV alır (ADR-0026).
- **Öğrenen bilgi katmanı** ([ADR-0048](docs/adr/0048-ogrenen-bilgi-katmani.md)): Verso yalnız belgeden cevap vermez, kurumun onayladığı bilgiyle zamanla daha doğru olur. İlk adım ([ADR-0049](docs/adr/0049-onayli-bilgi-karti-ve-altin-set.md)): yönetici kendi sorduğu ve kaynaklı cevaplanan bir soruyu "Onayla" ile **onaylı bilgi kartına** çevirir. Aynı soru yeniden sorulduğunda, kartın alıntıladığı bütün belgeleri okuyabilen kişiye model yerine onaylı cevap "uzman onaylı" rozetiyle gelir; okuyamayan kişi kartın varlığını bile görmez. Kaynak belge silinir, yeni sürümü gelir ya da taşınırsa, 90 günlük onay dolarsa ya da üç farklı kullanıcı 👎 verirse kart kendiliğinden kullanımdan çıkar; yeniden onaylamak ve arşivlemek insan kararıdır. Onaylanan her kart kurumun altın setine girer: her gece aramanın onu hâlâ bulup bulmadığı ölçülür (model çağrılmaz), düşüşte `KnowledgeRecallDropped` uyarısı açılır. Öğrenme denetlenebilir bir veri katmanındadır, model ince ayarlanmaz. `VERSO_QA_KNOWLEDGE_ENABLED=false` kartlardan cevap vermeyi kapatır; `VERSO_QA_KNOWLEDGE_TWO_PERSON_RULE=true` iki yöneticinin onayını ister.
- **Bilgi sağlığı ve kancalar** ([ADR-0052](docs/adr/0052-bilgi-sagligi-ve-kancalar.md)): her gece Verso, cevapsız ya da kaynaksız kalan soruları konularına göre kümeler, uzun süredir değişmeyen ama çok kullanılan belgeleri, hiç kullanılmayan belgeleri ve aynı konuda farklı sayı ya da kod veren belge çiftlerini bulur. Hiçbir toplam 5 kişiden azına dayanmaz, kişi bazında döküm yoktur, soru metni yalnız `VERSO_SHOW_UNANSWERED_QUESTIONS=true` ise görünür. Kancalar onaylı bilgi eskiyince, itiraz edilince, çelişki ya da yeni küme bulununca yöneticilere bildirir ya da incelemeye işaretler; gece kontrolünü yeniden çalıştırabilir, bir kartı kullanımdan çıkarabilir, ama hiçbir şeyi onaylamaz, silmez, erişim değiştirmez.
- **Onaylı prosedürler** ([ADR-0053](docs/adr/0053-onayli-prosedurler-skill.md)): sık tekrarlanan "nasıl yaparım" soruları uzmanların onayladığı adım adım prosedürlere (SKILL.md becerileri) dönüşür. Taslak, bilgi sağlığının soru kümesinin çevresindeki ya da yöneticinin seçtiği onaylı kartların cevaplarından ve alıntıladıkları sayfalardan şablonla kurulur; her adım kaynak parçalarına özetle bağlıdır, sayı ve kodları kaynağında yoksa onaylanamaz; her prosedürde 2-5 kontrol sorusu vardır ve gece kontrolünde çalışır. Kaynak silinir, değişir ya da taşınırsa prosedür kendiliğinden kullanımdan çıkar ve yöneticilere bildirilir. Onaylı prosedür, kaynaklarının hepsini okuyabilen kişiye cevabın üstünde "Bu bir prosedür" olarak gösterilir (`VERSO_QA_SKILLS_SERVING_ENABLED=true`, varsayılan kapalı), çalışanların Claude ya da Copilot gibi asistanlarına MCP üzerinden kendi yetkileriyle sunulur (`verso_skills`, `verso_skill`) ve SKILL.md olarak indirilebilir; dosya belge metni değil, kaynak bağlantıları taşır.
- **Sistem haritası** ([ADR-0054](docs/adr/0054-hafif-tipli-sistem-modeli.md)): her belgeden alım anında sabit kurallarla hafif, tipli bir model çıkarılır: sistemler (WMS, ERP), ekran ve menü yolları, durum/hata kodları ve tanımları, numaralı adımlı süreçler ve sorumlu **unvan ya da birim** (kişi adı hiçbir zaman tutulmaz), kısaltmalar ve açılımları ("FEFO (First Expired First Out)"). Her bilgi kaynak parçasına SHA-256 ile bağlıdır; belge silinince ya da yeniden işlenince yalnız o belgenin modeli silinir ya da yeniden çıkarılır (global yeniden kurulum yok). "Bu kod ne demek?" soruları için `GET /v1/system-model/lookup` ve MCP `verso_lookup` yalnız okuyabildiğiniz kaynaklarla cevap verir; bilgi sağlığı raporu tanımsız kodları, iki belgede farklı açılan kısaltmaları ve sahipsiz süreçleri gösterir. Arama zenginleştirme (`VERSO_DOCUMENT_SYSTEM_MODEL_ENRICHMENT_ENABLED`) ve yerel modelin taslak ilişki önerileri (`VERSO_QA_SYSTEM_MODEL_SUGGESTIONS_ENABLED`, yönetici onayı olmadan hiçbir yerde kullanılmaz) varsayılan kapalıdır.
- **Çok adımlı sorular** ([ADR-0055](docs/adr/0055-sinirli-cok-adimli-akil-yurutme.md)): "BT ile müşteri desteğinin P1 ilk yanıt süreleri arasındaki fark nedir?" gibi karşılaştıran ya da iki şey soran bir soru, ucuz bir kapı (karşılaştırma sözcükleri, iki soru sözcüğü ya da sistem haritasında iki sistem/kod) seçerse modelle **en çok 3** bağımsız alt soruya ayrılır. Her alt soru normal arama gibi, yalnız sizin okuyabildiğiniz belgelerde aranır; tek bir sentez çağrısı pasajları alt sorulara göre gruplanmış görür ve atıflar her zamanki gibi doğrulanır. Belgelerde karşılığı olmayan kısım tahmin edilmez, "“…” için belgelerde bilgi bulunamadı." diye açıkça yazılır. İki atıf arasındaki çelişki yalnız önceden saklanmış denetimlerden (bilgi sağlığı taraması, sistem modelindeki farklı tanımlar) gelir ve "Belgeler arasında çelişki" notuyla iki kaynağıyla gösterilir; model çelişki kararı vermez. Panel cevabın altında katlanır "Nasıl araştırıldı" kutusunu gösterir. Sohbet slotu meşgulse, süre dolarsa ya da ayrıştırma geçersizse normal tek geçişli cevap verilir. Gerçek modelle ölçülene kadar **kapalıdır** (`VERSO_QA_REASONING_ENABLED`); ölçüm tek komut: `node scripts/eval.mjs --set multihop --compare`.
- KVKK ilgili kişi başvurusu (md. 11): yönetici bir kişinin hesap kimliğiyle Verso'da tutulanı görür (`POST /v1/admin/subjects/summary`), tek JSON dosyası olarak dışa aktarır (`POST /v1/admin/subjects/export`) ve siler (`POST /v1/admin/subjects/delete`, gövdede onay); `sub` yalnız JSON gövdede taşınır, URL'ye hiç girmez. Geçmiş, puanlar ve kişisel belgeler bütün türevleriyle gider; ortak bilgi alanına yüklenen belgeler ve denetim satırları kalır ve raporlanır. Silinmiş bir belgeden alıntı yapan cevaplar istenirse sabit bir notla değiştirilir. Belge silmenin hiçbir şemada iz bırakmadığı testlidir (ADR-0034, `docs/runbooks/kvkk-basvurusu.md`).

## Hızlı başlangıç

Gereksinim: Docker (Compose v2) ve Node 24 (demo betiği için). İlk açılış model tarafı için yaklaşık 8,5 GB, `--build` sırasında Maven bağımlılıkları ve diğer imajlar için birkaç GB daha indirir; host'ta en az 12 GB boş RAM önerilir.

```bash
git clone https://github.com/doguyaras/Verso.git && cd Verso
```

```bash
bash scripts/dev-secrets.sh
```

```bash
docker compose up -d --build --wait
```

```bash
bash scripts/demo.sh
```

Demo, `samples/` altındaki sentetik belgeleri yükler: kurgusal bir şirketin altı yönetmeliği ve sistemlerinin beş teknik kılavuzu. Ardından dört soru sorar: üçünün cevabı belgelerdedir, biri belgelerde yoktur. Her cevabın kaynakları ve modu gösterilir. Gerçek bir koşudan (2026-10-03, CPU):

```text
Soru: Şirket laptopu kaybolursa ne kadar süre içinde bildirmem gerekir?
  Cevap: Şirkete ait dizüstü bilgisayar, telefon veya erişim kartı kaybolduğunda ya da çalındığında, çalışan durumu en geç iki saat içinde Bilgi Güvenliği ekibine bildirir [1].
  [1] bilgi-guvenligi.pdf, sayfa 2
  (kaynaklı, local / gemma4:e2b)

Soru: Şirketin borsa kodu nedir?
  Cevap: Belgelerde bu sorunun cevabı bulunamadı.
  (kaynak yok, local / gemma4:e2b)
```

CPU'da bir cevap 20–60 sn sürer. NVIDIA GPU'lu bir makinede Ollama'yı GPU ile açarsan (sürücü ve Docker GPU desteği gerekir) medyan 1,3 sn'ye iner, cevapların kalitesi değişmez:

```bash
docker compose -f compose.yaml -f deploy/compose.gpu.yaml up -d --build --wait
```

**Taranmış PDF ve tablolar için Docling (isteğe bağlı, varsayılan kapalı; [ADR-0032](docs/adr/0032-docling-tablo-ve-ocr.md)).** Metin katmanı olmayan (taranmış) PDF varsayılan yığında `NO_TEXT` ile reddedilir. Docling yan konteyneri bu sayfaları Tesseract `tur` + `eng` ile OCR'lar ve çizgili tabloları başlık satırıyla Markdown tablo olarak saklar:

```bash
docker compose -f compose.yaml -f deploy/compose.docling.yaml up -d --build --wait
```

- Yan konteyner yalnız uygulamayla paylaştığı iç ağdadır: portu yayınlanmaz, internete çıkamaz, modeller image'ın içindedir.
- PDF önce yine JDK ayrıştırıcısından geçer. Yan konteyner düşerse, 5 dakikada bitiremezse ya da tuhaf bir çıktı verirse belge JDK metniyle READY olur; taranmış PDF daha sonra yeniden denenir.
- **Donanım maliyeti** (eval setinde ölçüldü): image 7,6 GB; RAM boşta ~1,1 GB, ölçülen tepe 2,2 GB (sınır 4 GB, `DOCLING_MEM_LIMIT`); 3 CPU ile sayfa başına 0,7 sn (metin), 1,5 sn (çizgili tablo), 2,6 sn (taranmış sayfa, OCR). JDK ayrıştırıcısı sayfa başına 7–9 ms'dir. Varsayılan 2 CPU'dur (`DOCLING_CPUS`), daha yavaştır.
- **Kazanç:** taranmış sette retrieval recall@5 0 → 0,964. Depodaki örneklerde (tablolar düz metin) kazanç yok; çizgili tablolarda tablo sorularının sıralaması biraz iyileşiyor. Bu yüzden varsayılan kapalıdır. Ayrıntı: [`eval/README.md`](eval/README.md) "Docling".

**Rerank (isteğe bağlı, yalnız GPU, varsayılan kapalı; [ADR-0043](docs/adr/0043-capraz-kodlayici-rerank.md)).** Bir çapraz kodlayıcı (bge-reranker-v2-m3, HF TEI) hibrit aramanın ilk 20 adayını soruya göre yeniden sıralar. GPU overlay'inin üstüne eklenir:

```bash
docker compose -f compose.yaml -f deploy/compose.gpu.yaml -f deploy/compose.reranker.yaml up -d --wait
```

- İlk açılışta `reranker-pull` modeli (2,27 GB) sabit bir sürümden indirir ve her dosyayı SHA-256 ile doğrular. Sunucu sonra internetsiz, portsuz, yalnız uygulamayla paylaştığı iç ağda çalışır.
- İmaj etiketi GPU kuşağına bağlıdır: varsayılan `89-1.9` RTX 40xx / L4 içindir. Başka bir GPU için `VERSO_RERANKER_IMAGE`'ı digest ile verin (A10/A40 `86-1.9`, A100 `1.9`, H100 `hopper-1.9`). CPU'lu kurulumda çalışmaz.
- **Maliyet:** +1,4–1,6 GB VRAM, 2,5–2,7 GB RAM (sınır 3 GB, `RERANKER_MEM_LIMIT`), imaj 8,2 GB; soru başına p50 93 ms, p95 111 ms.
- **Kazanç:** retrieval'da ilk sonucun doğru sayfa olma oranı 0,795'ten 0,929'a çıkar (MRR 0,877 → 0,958). Ama küçük yerel modelle (gemma4:e2b) cevaplarda kazanç ölçülmedi: atıf ve bilgi aynı kaldı, "bulunamadı" 28 sorunun 2'sinde kötüleşti. Bu yüzden varsayılan kapalıdır; sıralamanın kendisinin sonuç olduğu MCP aramasında işe yarar.
- Yan konteyner düşerse ya da 2 sn'de cevap vermezse sorular rerank'siz cevaplanır. "Bulunamadı" kararı rerank'ten etkilenmez.
- Kapatmak: overlay'i çıkarın (`VERSO_RERANKER_ENABLED=false`); `reranker-models` volume'ü silinebilir.

**Çok kullanıcılı kurulum (paylaşımlı kademe; [ADR-0051](docs/adr/0051-paylasimli-sohbet-sunucusu.md)).** Varsayılan kurulum aynı anda tek soru cevaplar; ikinci soru `503` "meşgul" alır. Tek kullanıcı ve küçük ekip için bu yeterlidir. Birkaç kişinin aynı anda soru sorduğu bir şirket kurulumunda iki yol var:

- **Ollama'nın kendi paralelliği** (GPU'lu makinede): `.env` içinde `OLLAMA_NUM_PARALLEL` ve `VERSO_QA_CHAT_CONCURRENCY` aynı değere (ör. 4) çıkarılır. Her ek yuva, sohbet modelinin bağlamı için VRAM ayırır.
- **llama.cpp `llama-server`** (MIT; sürekli toplu işleme, OpenAI uyumlu API): sohbeti Ollama yerine o cevaplar, Ollama yalnız embedding'i tutar. Mod yine `local`'dir: sunucu internetsiz iç ağdadır, aynı model dosyasını (Ollama'nın indirip digest ile doğruladığı GGUF) salt okunur okur ve açılışta SHA-256'sını yeniden denetler.

```bash
bash scripts/dev-secrets.sh   # bir kez: secrets/SECRET_LOCAL_MODEL_API_KEY
docker compose -f compose.yaml -f deploy/compose.gpu.yaml -f deploy/compose.llamacpp.yaml up -d --wait
```

- `LLAMA_PARALLEL` (varsayılan 4) aynı anda cevaplanan soru sayısıdır; uygulamanın sohbet sınırı da buna eşitlenir. `LLAMA_CTX_PER_SLOT` (4096) yuva başına bağlamdır.
- **Ölçüm (RTX 4070 Ti SUPER):** Ollama'da `OLLAMA_NUM_PARALLEL=4` ve `VERSO_QA_CHAT_CONCURRENCY=4` ile 4 eşzamanlı kullanıcıda dakikada ~96 cevap (tek yuvada ~53), medyan 2,0 sn, p99 10,4 sn, "meşgul" yanıtı yok. GPU'lu kurulumda bu ayar önerilir; varsayılan CPU kurulumu için 1'de kalır. llama-server'ın karşılaştırmalı ölçümü ve kalite kontrolü henüz yapılmadı. Ayrıntı: [`docs/capacity.md`](docs/capacity.md) "Paylaşımlı kademe".

Küçük yerel model kaynağı doğru gösterse de bazen bilgiyi yanlış okur. Aynı koşuda "beş yıldan az hizmeti olan" sorusuna doğru sayfayı göstererek "yirmi gün" dedi; belgede "on dört gün" yazıyor. Eval setinde (sürüm 2, GPU) doğru sayfaya atıf yapılan 97 cevabın 3'ünde bilgi yanlış ya da eksikti. Atıflar kullanıcının cevabı kaynağından kontrol etmesi içindir. Ölçümler: [`docs/capacity.md`](docs/capacity.md), [`eval/README.md`](eval/README.md).

## Panel

Tarayıcıdan: `http://localhost:8080/panel/`. İki hazır kullanıcı var, ikisi de panelde operatör rolünde. API'de `admin` `verso-admin`, `demo` `verso-user` rolündedir (ADR-0019). Parolaları `scripts/dev-secrets.sh`'ın ürettiği dosyalarda:

| Kullanıcı | Parola dosyası |
|---|---|
| `admin` | `secrets/SECRET_KEYCLOAK_PANEL_ADMIN_PASSWORD` |
| `demo` | `secrets/SECRET_KEYCLOAK_DEMO_USER_PASSWORD` |

Ekranlar:

- **Tanıtım ve giriş:** panel önce bir tanıtım gösterir: büyük imza ve açık kitapta kurulumun örnek sorusu ile kaynaklı cevabı (marka dosyasında `showcase`; aşağıda). "Giriş yap" kurumun kimlik sağlayıcısına götürür; paketteki demo Keycloak ikiye bölünmüş bir ekranla açılır: solda imzalı boyalı levha, sağda form. İkisinde de sağ üstte tema ve TR / EN anahtarları var.
- **Genel bakış:** ortada büyük imza ve soru kutusu, kurulumun önerdiği sorular (`suggestions`), sorulabilir belge, kurulum aşaması ve çalışma modu kartları, ilk kullanım listesi, son belgeler ve açık konuşmanın soruları (öncekiler "Geçmiş"te). Menü varsayılan olarak ince ikon şerididir, genişletilebilir.
- **Soru sor** ([ADR-0024](docs/adr/0024-cevap-ekrani.md)):
  - **Atıflar:** bir atıf numarasının üzerine gelince belge adı, sayfa ya da bölüm ve alıntılanan pasaj görünür.
  - **Kaynak kartları** belgeye göre gruplanır ve her atıfın alıntısını gösterir. Alıntıda cevapla ortak sözcükler işaretlidir; baştaki ya da sondaki "…" alıntının kısaltıldığı yerdir. Atıfla kartı hareket bağlar: birinin üzerine gelmek ya da odaklanmak ikisini birlikte ve dayandığı cümleyi vurgular.
  - **Geniş ekranda (≥1200 px)** kartlar cevabın yanında yapışkan bir sütundadır ve atıflar "belge · s. 3" çipine dönüşür.
  - **Cevap türleri:** kaynaklı, bulunamadı ve kaynak gösterilemedi kendi görünümüyle gösterilir. Model kapalı, yoğun ya da bağlantı yok gibi hatalar da ayrı ve açık bir metinle gelir; `Retry-After` varsa "Tekrar dene" geri sayar.
  - **Takip:** cevabın altında şüpheci takip soruları durur ("Bu hangi belgeye dayanıyor?", "Aksi bir madde var mı?", "Bunu daha kısa anlat"). Her biri yeni bir soru olarak sorulur. Cevapta ya da alıntıda seçilen metin "Bunu sor" ile soru kutusuna alınır.
  - **Dışa aktarma:** "Kopyala" cevabı atıfları dipnot olarak Markdown'da kopyalar. ".md indir" aynısını soruyla birlikte dosya olarak indirir.
  - **Klavye:** PageUp/PageDown ya da Alt+↑/↓ sorular arasında gezer; Esc açık önizlemeyi ya da kartı kapatır.
  - **Akış** ([ADR-0035](docs/adr/0035-akisli-cevap-ve-sayfa-gorunumu.md)): cevap yazıldıkça gelir. Üstte yalnız sunucunun gerçekten yaptığı adım görünür: "Belgelerinizde aranıyor", "2 ilgili pasaj bulundu", "Cevap yazılıyor". Yazılan metin geçicidir; atıf numaraları sunucu doğrulayana kadar gösterilmez, cevap bitince kaynaklı hâliyle yeniden çizilir. Ekran okuyucu yalnız adımları ve sonu duyurur. Kelime kelime belirme efekti yoktur.
  - **Durdur:** isteği keser; sunucu modelin yazmasını da durdurur ve bu soruyu geçmişe kaydetmez. Akış bir proxy yüzünden hiç gelmezse panel soruyu bir kez normal uca sorar (o durumda "Durdur" yalnız beklemeyi bırakır). Odak soru kutusuna döner.
  - **Sayfa görünümü:** kaynak satırındaki "Sayfayı aç" (DOCX/TXT/MD'de "Bölümü aç") sağdan bir panel açar: belgenin saklanan sayfa metni, alıntı vurgulu, önceki/sonraki sayfa. Yalnız okuyabildiğiniz belgeler açılır. Esc kapatır, odak açan düğmeye döner.
  - **Bekleme:** geçen süre ve gerçekçi bir beklenti görünür.
  - **Konuşma** ([ADR-0025](docs/adr/0025-gecmis-geri-bildirim-deger-panosu.md)): ardışık sorular sunucuda aynı konuşmaya yazılır; "Yeni konuşma" yenisini başlatır. Konuşma silinmiş ya da dolmuşsa soru kendiliğinden yeni konuşmada sorulur ve bir notla belirtilir.
  - **Takip soruları** ([ADR-0046](docs/adr/0046-takip-sorulari.md)): aynı konuşmada "Peki P2 için?" ya da "Bunun süresi ne kadar?" gibi tek başına anlaşılmayan bir soru, aramadan önce son iki turla tek başına anlaşılan bir arama sorgusuna yeniden yazılır. Akışta "Soru bağlamla yeniden yazılıyor" adımı görünür, cevabın üstünde küçük bir "Bağlamla arandı: …" notu çıkar. Cevap yine sorulan soruya yazılır. Kısa ya da "bu, bunun, peki, ya…" içeren sorular yeniden yazılır; diğerleri olduğu gibi aranır. `VERSO_QA_FOLLOWUP_ENABLED=false` kapatır.
  - **Dayanak kontrolü** ([ADR-0047](docs/adr/0047-cevap-dayanaklilik-kontrolu.md)): sunucu, atıflı her cümledeki sayıyı, tutarı, süreyi ve kodu atıf yapılan pasajda aynı bağlamda arar. Bulamadığı cümlenin altı noktalı çizilir ve cevabın altında "dayanağı doğrulanamadı" notu çıkar. Cevap metni değişmez. Hiçbir atıflı cümle desteklenmiyorsa cevap "kaynaksız" sayılır, kaynaklar yine gösterilir. Kontrol yalnız sayı ve kodlara bakar; işaret yoksa cevap doğru demek değildir. Model kullanmaz, iki modda da yereldir. `VERSO_QA_GROUNDING_ENABLED=false` kapatır.
  - **Geri bildirim:** her cevabın altında 👍/👎. 👎'den sonra küçük bir neden seçici açılır (Yanlış kaynak, Eksik ya da yanlış, Güncel değil, Diğer); "Yanlış kaynak"ta hangi `[n]` olduğu sorulur. Yeni puan eskisinin yerine geçer.
- **Geçmiş** (menüde "Soru sor"un altında, `g g`, hesap menüsü ya da soru ekranındaki düğme): kendi konuşmalarınız sağdan açılan bir panelde, son etkinliğe göre ve 20'şer. Yüklenen başlıklarda Türkçe harf duyarsız arama; yeniden adlandırma; tek konuşmayı silme (panelin kendi onay penceresi); "Tümünü sil" yalnız `SİL` yazılınca çalışır. Açılan konuşma soru ekranına salt okunur gelir (alıntı saklanmadığı için takip sorusu yok) ve sonraki soru onu sürdürür. Silinmiş belgeye atıf "Belge silinmiş" diye görünür. Konuşmalar kurulumun saklama süresi dolunca silinir (`VERSO_HISTORY_RETENTION_DAYS`, varsayılan 90 gün; panel süreyi API'den alamadığı için sayı yazmaz).
- **Değer panosu** (yalnız `/v1/info` `verso-admin` rolü verirse; sunucu diğerlerine 403 döner): 7 / 30 / 90 günlük aralık; soru, aktif kullanıcı, kaynaklı / bulunamadı / kaynak gösterilemedi oranları, p50 / p95 süre ve 👍 oranı kartları; günlük soru ve sonuç oranı çizgileri (üzerine gelince ya da ←/→ ile gün gün, tablo olarak da); mod dağılımı, 👎 nedenleri, en çok ve hiç atıf almamış belgeler. Belge adları kurulum açtıysa görünür, yoksa "Belge #…". Cevaplanamayan sorular yalnız kurulum açtıysa ve en az 3 kişi sorduysa listelenir. "Tahmini kazanılan süre" gösterilmez: API'de dürüstçe türetilecek bir veri yok.
- **Bilgi kaynakları:** sayfanın her yerine sürükle-bırak; dosya başına ilerleme ve "tekrar dene"; Türkçe harf duyarsız arama, durum filtresi ve sıralanabilir tablo; ayrıntı paneli; dosya adıyla silme onayı. Bilgi alanındaki belgeler alan adıyla rozet taşır ve yalnız yönetici siler; yönetici yükleme yerini ("Kişisel alanım" ya da bir bilgi alanı) seçer ve ayrıntı panelinden belgeyi taşır.
- **Bilgi alanları** (yalnız `verso-admin`, ADR-0028): alan oluşturma, gruba (tam yol) ya da kullanıcıya (`sub`) erişim verme ve kaldırma, boş alanı silme. Kaynak kartları belgenin alanını gösterir; geçmişte erişimi kalkan belge "Erişiminiz yok" olarak görünür. Altında planlanan bağlantılar (SharePoint ve dosya paylaşımları, talep yönetimi, ERP) "Planlandı" olarak görünür.
- **Yönetim grubu** ([ADR-0036](docs/adr/0036-yonetim-ekranlari.md)): değer panosu, bilgi alanları ve aşağıdaki iki ekran menüde "Yönetim" başlığı altında durur; yalnız `/v1/info` `verso-admin` verirse görünür, kullanıcı ve operatörün menüsü değişmez. Telefonda hesap menüsünden açılır.
  - **Bilgi alanları:** ayrıca alanın adı ve açıklaması kartta düzenlenir.
  - **Parçaları incele:** okuyabildiğiniz bir belgenin retrieval parçaları (sayfa ya da bölüm, karakter sayısı, varsa başlık, ilk satırlar), 50'şer yüklenir, yüklenenlerde Türkçe harf duyarsız arama; "Sayfayı aç" sayfa panelini parça vurgulu açar. "Bilgi kaynakları" ayrıntı panelinden de açılır. Yönetici rolü okuma hakkı değildir: erişiminiz olmayan alanın belgesi açılmaz.
  - **KVKK başvuruları:** kişinin hesap kimliğiyle (`sub`) özet (silinecekler, kalacak ve raporlanacaklar, kalan bilgi alanı belgeleri), JSON dışa aktarım, kimliği yeniden yazarak silme ve silinmiş belge için cevap karartma. Kimlik URL'e ve tarayıcı deposuna yazılmaz, ekrandan çıkınca silinir. İşletim: [`docs/runbooks/kvkk-basvurusu.md`](docs/runbooks/kvkk-basvurusu.md).
  - **Onaylı bilgiler** ([ADR-0049](docs/adr/0049-onayli-bilgi-karti-ve-altin-set.md), `g n`): onaylı bilgi kartları durum süzgeciyle (Onaylı, Taslak, Eskimiş, İtirazlı, Arşivde); her kartta onay sayısı, geçerlilik, itiraz sayısı ve eskime nedeni; ayrıntıda cevap, kaynaklar, gün başına itiraz ve geçmiş; Onayla ve Arşivle. Altında son altın set kontrolleri (isabet, önceki koşu, kaynak kayması) ve "Şimdi kontrol et". Kart, "Soru sor" ekranında yöneticinin kendi kaynaklı cevabının altındaki "Onayla" düğmesiyle oluşur; API: [docs/api-knowledge-integration-v1.md](docs/api-knowledge-integration-v1.md).
  - **Bilgi sağlığı** ([ADR-0052](docs/adr/0052-bilgi-sagligi-ve-kancalar.md), `g h`): bulgular ve öneriler (önem sırasıyla), cevapsız soru kümeleri (ortak sözcükler, kişi ve soru sayısı, eğilim), eski ama sık kullanılan belgeler, bilgi alanı başına kapsama, çelişen belge çiftleri (sayfalarıyla; değerler yalnız iki belgeyi de okuyabilen yöneticiye), onaylı kart sayıları ve 👎 gerekçeleri, kanca kuralları (aç/kapa) ve bildirimler; "Şimdi teşhis et" ve "Raporu indir" (Markdown, JSON). Üst çubuktaki zil okunmamış kanca bildirimlerini gösterir. API: [docs/api-knowledge-integration-v1.md](docs/api-knowledge-integration-v1.md).
  - **Danışmanlık ekibi çeyrek raporunu nasıl kullanır:** çeyreğin ilk haftası Yönetim → Bilgi sağlığı ekranında dönem olarak "Geçen çeyrek" seçilir, "Şimdi teşhis et" ile son durum alınır ve "Raporu indir" ile `bilgi-sagligi-<başlangıç>-<bitiş>.md` indirilir (araç entegrasyonu için JSON). Rapor müşteriyle birlikte okunur: her cevapsız soru kümesi bir dokümantasyon, süreç ya da sistem entegrasyonu işi; eski belgeler sahiplerine güncelleme görevi; çelişkiler konu sahibine karar; itirazlı ve eskiyen kartlar onay kuyruğu olur. Bulgular sabit şablonlardan gelir (model yazmaz), bu yüzden çeyrekler arası karşılaştırılabilir. Rapor kişi adı içermez; belge adları yalnız indiren yöneticinin okuyabildiği belgeler için yazılır.
  - **Prosedürler** ([ADR-0053](docs/adr/0053-onayli-prosedurler-skill.md), `g r`): tekrarlayan soru kümelerinden öneriler ("Taslak oluştur", isteğe bağlı yerel modelle sırala ve kısalt), onaylı kartlardan elle taslak, durum süzgeçli prosedür listesi; ayrıntıda adımlar ve kaynakları (kaynağında olmayan sayı ya da kod içeren adım "Doğrulanamadı" işaretli), örnek sorular, kontrol soruları, onayı engelleyen nedenler, geçmiş ve sürüm farkları; Düzenle (yeni sürüm), Onayla, Arşivle, "SKILL.md indir". API: [docs/api-knowledge-integration-v1.md](docs/api-knowledge-integration-v1.md).
  - **Danışmanlık ekibi tekrarlayan soruları nasıl onaylı prosedüre çevirir:** Bilgi sağlığı raporunda cevapsız ya da sık sorulan bir "nasıl yaparım" kümesi görülünce önce o konudaki kaynaklı cevaplar "Soru sor" ekranında "Onayla" ile karta çevrilir (eksik belge varsa önce belge eklenir). Ardından Yönetim → Prosedürler'de küme önerisinden "Taslak oluştur" seçilir: Verso adımları kartların cevaplarından ve kaynak sayfalarından şablonla kurar. Konu sahibiyle birlikte adımlar sıralanır, sadeleştirilir ve gereksiz olanlar çıkarılır (yeni kaynak eklenemez; adımlar kaynağına karşı yeniden denetlenir), 2-5 kontrol sorusu ve beklenen kaynak sayfası girilir, "Onayla" ile yayına alınır (yüksek riskli içerikte iki yöneticinin onayı). Onaylı prosedür müşterinin çalışanlarına cevabın üstünde, MCP'de ve SKILL.md olarak ulaşır; kaynak değişince kendiliğinden çekilir, zildeki "Bir prosedür eskidi" bildirimi yeni taslak işidir. Her çeyrek onaylı prosedür sayısı ve kontrol sorularının gece sonuçları danışmanlık raporuna eklenir.
  - **Sistem haritası** ([ADR-0054](docs/adr/0054-hafif-tipli-sistem-modeli.md), `g m`): sistemler, ekranlar, kodlar, süreçler, terimler ve roller; tipe göre liste ve "Kod ya da terim ara"; ayrıntıda tanımlar ve kaynakları, sistemin ekranları ve kodları, sürecin adımları ve sorumlusu, "Nerede geçiyor"; açıksa yerel modelin taslak ilişki önerileri (Kabul et / Reddet). Yönetici de yalnız okuyabildiği belgelerden çıkanı görür.
  - **Bağlayıcılar** ([ADR-0042](docs/adr/0042-baglayici-ekrani.md), `g e`): SMB bağlayıcılarının listesi (hedef alan, sağlık rozeti renk ve metinle: Güncel / Gecikmiş / Başarısız …, son başarılı eşitleme, dosya sayıları, son koşu), oluşturma ve düzenleme formu (sunucunun kurallarıyla anında Türkçe hata; parola sorulmaz, yalnız sunucudaki `SECRET_CONNECTOR_*` dosyasının adı seçilir; hedef alan sonradan değişmez), "Şimdi eşitle", sayfalı koşu ve dosya listeleri (durum süzgeciyle), adı yazarak silme ve emniyetin beklettiği toplu silmeyi dosya sayısını yazarak onaylama. Kurulum adımları aşağıdaki "Dosya paylaşımı bağlayıcısı" bölümündedir.
  - **Güven** ([ADR-0050](docs/adr/0050-guven-ekrani.md), `g u`): kurulumun verisinin nereye gidebildiğini sade Türkçeyle gösterir (`GET /v1/admin/trust`, yalnız `verso-admin`): çalışma modu ("Yerel: soru, belge ve cevaplar bu sunucudan çıkmaz"; yerelde bulut modunda neyin değişeceği), dışarı açık bağlantılar (bulut modeli, bağlayıcı çıkışı ve izinli adresler, bulut bağlayıcı, MCP), kurulum doğrulaması (`bash scripts/prove-local-mode.sh --report guven-kaniti.json` ve `scripts/verify-release.sh`; çıktıların anlamı), son denetim olayı, uyum paketi belge adları ve "Güven raporu indir" (Markdown ya da JSON; tarayıcıda üretilir, sunucuya gönderilmez). Ekran uygulamanın kendi beyanıdır; bağımsız kanıt betik raporu ve kurumun güvenlik duvarı gözlemidir. Sürümün imaj özeti görünsün diye kurulum `VERSO_IMAGE_DIGEST=sha256:…` ortam değişkenini uygulamaya geçirebilir.
- **Kurulum durumu:** kurulumu yapan ekibin aşamaları (Keşif, Pilot, Yaygınlaştırma, Entegrasyonlar) ve sistemden okunan kontrol listesi. Aşama, pilot grubu ve kurum adı marka dosyasından gelir.
- **Sistem ve güvenlik:** mod ve KVKK açıklaması, üç koruma katmanı, modeller, oturum süresi, gözlem bağlantıları. Yalnız operatör rolüne görünür.

Gezinme: etiketli sol menü (ikon şeridine daraltılabilir), telefonda alt sekme çubuğu, **Ctrl+K / ⌘K** komut paleti (sayfa, eylem ve belge arar) ve `g` + harf kısayolları (`g s` soru, `g g` geçmiş, `g b` bilgi kaynakları, `g k` kurulum, `g d` değer panosu, `g a` bilgi alanları, `g p` parçaları incele, `g v` KVKK başvuruları). Varsayılan tema açık ("Fildişi tuval"); koyu tema ("Obscur") ya da "sistemi izle" hesap menüsünden seçilir. Tasarım sistemi ve gerekçeleri: [ADR-0017](docs/adr/0017-panel-v3-tasarim-sistemi.md), [ADR-0018](docs/adr/0018-fildisi-tuval.md).

Panelin özellikleri:

- Bağımlılıksızdır; derleme adımı ve dış kaynak yoktur. İkonlar panelin kendi SVG'leri; başlık fontu Fraunces (OFL) panelin kendi sunucusundan gelir (`font-src 'self'`).
- Token yalnız bellekte durur. Açık konuşma da yalnız sekmenin belleğindedir; geçmiş sunucudan okunur, tarayıcı deposuna yazılmaz.
- Model çıktısı sayfaya yalnız metin olarak girer.
- Rol matrisi tek dosyadadır (`panel/js/roles.js`).
- Varsayılan portlarla çalışır (8080, 8180, 3000). Port değişirse `panel/js/config.js`, realm'deki `verso-panel` istemcisi ve `deploy/edge/nginx.conf`'taki CSP birlikte değişir.

Kararlar: [ADR-0015](docs/adr/0015-panel.md), [ADR-0017](docs/adr/0017-panel-v3-tasarim-sistemi.md), [ADR-0018](docs/adr/0018-fildisi-tuval.md), [ADR-0021](docs/adr/0021-kurulum-marka-dosyasi.md), [ADR-0024](docs/adr/0024-cevap-ekrani.md), [ADR-0025](docs/adr/0025-gecmis-geri-bildirim-deger-panosu.md), [ADR-0036](docs/adr/0036-yonetim-ekranlari.md), [ADR-0042](docs/adr/0042-baglayici-ekrani.md). Belgeler hesaba aittir: `demo.sh`'ın yüklediği belgeler CI istemcisinin hesabındadır; panelde `samples/` PDF'lerini sürükleyip bırakın.

Daha önce kurulmuş bir yığında panelin IdP istemcisi, `admin` kullanıcısı ya da Verso giriş teması yoktur (Keycloak realm'i yalnız ilk açılışta içe aktarır). Önce eksik secret dosyalarını üretin (`bash scripts/dev-secrets.sh`, var olanlara dokunmaz). Sonra Keycloak'ı tema bağlantısıyla yeniden oluşturun; bu, sıkıştırılmış kaynak önbelleğini de siler (tema dosyaları her değiştiğinde de gerekir):

```bash
docker compose up -d --force-recreate --no-deps --wait keycloak
```

Ardından realm'i bir kez yeniden içe aktarın; yalnız demo IdP'nin verisini sıfırlar:

```bash
bash scripts/keycloak-reimport.sh
```

Demo kullanıcıları yeni kimlik alır; önceki kimlikle yüklenen belgeler silinmez ama yeni oturumda görünmez.

### Kuruluma özel marka

Kurulum ekibi paneli JavaScript'e dokunmadan markalar ([ADR-0021](docs/adr/0021-kurulum-marka-dosyasi.md)):

1. Şablonu kopyalayın ve düzenleyin: `cp deploy/branding/branding.example.json deploy/branding/branding.json`. Logo kullanacaksanız dosyayı aynı dizine koyun (`deploy/branding/logo.svg`; ad harf, rakam, `-` ve `_`, uzantı `.svg`, `.png` ya da `.webp`).
2. Paneli yenileyin. Kenar proxy dizini salt okunur bağlar ve dosyayı önbelleksiz sunar (`Cache-Control: no-cache`); yeniden başlatma gerekmez. Dizin bağlantısı yeni olduğu için, bu sürüme geçen bir yığında `edge` bir kez yeniden oluşturulur: `docker compose up -d --force-recreate --no-deps edge`.
3. Tarayıcı konsoluna bakın. Dosyada bir hata varsa panel dosyanın tamamını yok sayar, varsayılanlarla açılır ve konsola her hatayı yoluyla sayan tek bir uyarı yazar.

| Alan | Ne | Sınır |
|---|---|---|
| `productName` | Sayfa başlığı ve etiketlerdeki ürün adı (varsayılan "Verso"). İmza Verso işareti olarak kalır. Ad hâl eki almadan kullanılır. | 40 karakter |
| `client` | Menüde imzanın altındaki kurum adı | 80 karakter |
| `logo` | Kurum adının yanındaki logo: `/branding/<ad>.svg\|png\|webp` | yalnız bu dizin |
| `phase` | `discovery`, `pilot`, `rollout`, `integrations` (Kurulum durumu) | |
| `pilotGroup` | Pilot kullanıcı grubu tanımlandı mı (`true`/`false`) | |
| `team` | Kurulum ekibinin adı ve iletişim adresi (ekip adresi, kişisel değil) | 160 karakter |
| `showcase` | Tanıtım ekranındaki örnek: `tr` ve `en`'in ikisi de, `label`, `question`, `answer`, `source` dolu | 80 / 200 / 600 / 120 |
| `suggestions` | Önerilen sorular; gerçekten sorulur, dizinli belgelerden cevaplanabilmeli | 1–6 soru, her biri 200 |
| `accent` | Temaya göre (`light`, `dark`) vurgu token'ları: `fg-accent`, `accent-fill`, `accent-emphasis`, `accent-emphasis-hover`, `focus-ring`; yalnız `#rrggbb` | kontrast: metin 4,5:1, UI 3:1 |

Dosyada olmayan alan `panel/js/config.js`'teki varsayılanında kalır; `null` isteğe bağlı bir alanı (`client`, `logo`, `team`, `accent`) boşaltır. Bilinmeyen anahtar, sınırı aşan metin, kontrol karakteri ya da kontrastı yetmeyen renk hatadır; dosya 16 KB'ı aşamaz.

Dosya girişten önce, kimliksiz okunur: içine gizli bilgi, iç sunucu adı ya da kişisel veri yazmayın. `branding.json` ve logolar git'e girmez (`.gitignore`); yalnız örnek dosya repodadır.

Giriş sayfası bu dosyayı okumaz: Keycloak'ın sunucu tarafı temasıdır (`deploy/keycloak/themes/verso/`) ve ayrıca markalanır. Müşterinin kendi IdP'si kullanılıyorsa giriş sayfası onundur.

## curl ile

Token (paketteki demo Keycloak'ın CI istemcisi; üretimde kurumun IdP'si):

```bash
TOKEN="$(curl -s http://localhost:8180/realms/verso/protocol/openid-connect/token -d grant_type=client_credentials -d client_id=verso-ci --data-urlencode client_secret@secrets/SECRET_KEYCLOAK_CI_CLIENT_SECRET | node -pe 'JSON.parse(require("fs").readFileSync(0)).access_token')"
```

```bash
curl -s -H "Authorization: Bearer $TOKEN" -F "file=@samples/izin-yonetmeligi.pdf;type=application/pdf" http://localhost:8080/v1/documents
```

```bash
curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"question":"Doğum izni toplam kaç haftadır?"}' http://localhost:8080/v1/questions
```

Akışlı cevap (server-sent events; olaylar `stage`, `delta`, `citations`, `done` ya da `error`):

```bash
curl -sN -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -H "Accept: text/event-stream, application/json" -d '{"question":"Doğum izni toplam kaç haftadır?"}' http://localhost:8080/v1/questions/stream
```

Bir atfın sayfası (sayfa ya da bölüm numarası atıftaki `page`): `curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/v1/documents/<id>/pages/3`.

Windows'ta (Git Bash) yerel `curl.exe`, argümandaki Türkçe harfleri bozar; gövdeyi UTF-8 bir dosyadan verin: `--data-binary @soru.json` (`scripts/demo.sh` böyle yapar).

Sözleşmeler: [`docs/api-documents-integration-v1.md`](docs/api-documents-integration-v1.md), [`docs/api-questions-integration-v1.md`](docs/api-questions-integration-v1.md), [`docs/api-conversations-integration-v1.md`](docs/api-conversations-integration-v1.md) (geçmiş, geri bildirim, yönetici metrikleri), [`docs/api-audit-integration-v1.md`](docs/api-audit-integration-v1.md) (denetim kaydı: sorgu ve CSV dışa aktarım), [`docs/api-subjects-integration-v1.md`](docs/api-subjects-integration-v1.md) (KVKK başvurusu: özet, dışa aktarım, silme, cevap karartma).

## Modlar ve KVKK md. 9

| | `local` (varsayılan) | `cloud` (isteğe bağlı) |
|---|---|---|
| Embedding | Yerel (Ollama, bge-m3) | Yerel (Ollama, bge-m3) |
| Chat | Yerel (Ollama, gemma4:e2b) | Anthropic ya da OpenAI uyumlu API |
| Host'tan çıkan | Hiçbir şey | Yalnız sistem kuralları, soru ve en fazla 5 pasaj; takip sorusunda ayrıca aynı konuşmanın son iki sorusu ve son cevabın başı (ADR-0046) |
| Kanıt | `scripts/prove-local-mode.sh` (CI'da koşar) | `X-Rag-Mode: cloud` her yanıtta |
| Açma | `docker compose up` | `deploy/compose.cloud.yaml` + anahtar dosyası |

- **Local mod üç katmanda zorlanır:**
  - Uygulama, veritabanı ve model sunucusu yalnız iç Docker ağlarındadır. API'ye bir kenar proxy üzerinden ulaşılır.
  - Uygulamanın HTTP istemcileri yalnız iç adreslere bağlanabilir.
  - Mod ile model ayarları tutarsızsa uygulama başlamaz.
- **MCP sunucusu açıksa** (varsayılan kapalı) her iki modda da pasajlar çağıranın yapay zekâ istemcisine çıkar; istemci bulutta ise oradan sağlayıcısına gider (aşağıda "Yapay zekâ istemcileri için MCP sunucusu").
- **Cloud modda** pasajlar (kişisel veri içerebilir) yurt dışındaki bir sağlayıcıya aktarılabilir. Bu modu açma kararı ve hukuki dayanağı veri sorumlusunundur.
- **Log'lar** belge içeriği, soru, cevap ya da dosya adı taşımaz; yalnız id, süre ve sayı taşır. Bu, test edilir ve gözlem yığınında uçtan uca kontrol edilir.
- Bu bölüm tasarım kararlarını anlatır; hukuki tavsiye değildir.

## Beceriler: hazır iş akışları ve incelemeler

**Beceri**, boş sohbet kutusu yerine bir iş akışıdır ([ADR-0057](docs/adr/0057-beceri-kutuphanesi.md)): kullanıcı kısa bir formu doldurur (toplantı notu, sözleşme maddeleri, gereksinim listesi, şikâyet dökümü …), Verso **kullanıcının kendi erişebildiği** belgelerde arar, en çok dört adımlık sınırlı bir zincir çalıştırır (ör. pre-mortem → 5 Neden → önlemler) ve tablo halinde bir **taslak** üretir. Her satır dayandığı kaynağı gösterir; kaynağı olmayan satır tahmin edilmez, "değerlendirilemez" yazılır; kaynakta olmayan sayı ya da kod "kaynakta yok" diye işaretlenir. Çıktı hiçbir zaman kesin hüküm değildir: her becerinin bir uyarı etiketi vardır (ör. sözleşme ön incelemesinde "hukuk incelemesi için"). "Prosedürler" ekranındaki onaylı prosedürlerden (ADR-0053, belgelerden türetilen "nasıl yapılır" adımları) farklıdır.

İlk 10 beceri: toplantı notu → karar/aksiyon listesi (`/toplanti`), kaynaklı rapor taslağı (`/rapor`), e-posta/yazışma özeti (`/ozet`), ihale/RFP yanıt taslağı (`/ihale`), sözleşme ön incelemesi (`/sozlesme`), gereksinim kalitesi (INVEST, ISO/IEC/IEEE 29148; `/gereksinim`), proje sağlık değerlendirmesi (RAID, PMI; `/proje`), pre-mortem (`/premortem`), müşteri şikâyeti analizi (`/sikayet`), SOP/prosedür taslağı (`/sop`). Tanımlar `skills/*.json`, yazım kuralları [skills/README.md](skills/README.md).

**Kullanıcı nasıl çalıştırır:** panelde **Beceriler** (`g c`) → kategori ya da arama → beceri kartı → form → "Çalıştır". İlerleme aşama aşama görünür (belgeler aranıyor, adım 2/3, kontroller). Bitince taslak tablo halinde gelir; kaynak çipleri belge sayfasını açar. Kullanıcı hücreleri düzenler, "Onayla" ile "Onaylandı" işaretler, Markdown ya da JSON olarak indirir ve 1-5 puan verir. Soru kutusuna `/` yazınca beceri listesi açılır; `/toplanti <not>` formu notla doldurur. Çalıştırmalar Beceriler ekranının geçmiş listesinde durur, konuşmalarla aynı süre saklanır ve silinebilir. Yönetici her beceriyi kurulum için açıp kapatabilir.

**Danışmanlık ekibi kuruma özel beceriyi nasıl ekler:** müşterinin tekrarlayan bir iş akışı (ör. "tedarikçi ret raporu", "ISG olay ön analizi") için `skills/README.md`'deki biçimde bir JSON tanımı yazılır: form alanları, hangi bilgi alanlarında aranacağı (`grounding.scopeCollections`; kullanıcının erişimiyle kesişir, kimsenin erişimini genişletmez), adımlar, çıktı tablosu, rubrik, çerçeve ve uyarı etiketi. Beceriler ekranının yalnız `verso-admin`'in gördüğü yönetici bölümündeki JSON düzenleyicide "Doğrula" ile kurallar denetlenir, "Taslak kaydet" ile saklanır (kullanıcılar görmez), konu sahibiyle 2-3 örnek girdiyle denenip "Onayla" ile galeriye alınır. Her değişiklik yeni sürümdür ve yeniden onay ister; eski sürümler silinmez. Örnek vakalar (`skills/golden/` biçiminde) `node scripts/skill-eval.mjs --base <adres> --skill <id> [--judge]` ile kurulumun modeline karşı çalıştırılır; sonuç `eval/results/` altına yazılır ve danışmanlık raporuna eklenir.

## Yapay zekâ istemcileri için MCP sunucusu

Çalışanların kurumca onaylanmış yapay zekâ istemcileri (Claude, ChatGPT, Copilot Studio, iç ajanlar) Verso'yu **salt okunur**, atıflı bir bilgi kaynağı olarak kullanabilir ([ADR-0031](docs/adr/0031-mcp-sunucusu.md), istemci kurulumu [`docs/api-mcp-integration-v1.md`](docs/api-mcp-integration-v1.md)).

- **Varsayılan kapalı.** `VERSO_MCP_ENABLED=true` uygulamayı ve kenar proxy'nin `/mcp` yolunu birlikte açar; `VERSO_MCP_RESOURCE` uç adresinin dışarıdan görünen https adresidir.
- Beş araç: `verso_search` (pasaj arama: belge adı, sayfa, alıntı, benzerlik) ve `verso_fetch` (tek pasajın tam metni), ChatGPT'nin `search`/`fetch` biçiminde; `verso_skills` (okuyabildiğiniz onaylı prosedürlerin adı ve açıklaması) ve `verso_skill` (bir prosedürün SKILL.md'si; adımlardaki kaynak bağlantıları `verso_fetch` kimlikleridir, ADR-0053); `verso_lookup` (bir kodun, kısaltmanın ya da sistemin tanımları, ilişkileri ve kaynakları, ADR-0054). Üretilmiş cevap aracı yok.
- **Müşteri prosedürleri Claude/Copilot'ta nasıl kullanır:** çalışan, kurumun onayladığı istemciye (Claude, Copilot Studio, ChatGPT ya da iç ajan) Verso MCP sunucusunu kendi hesabıyla bağlar. "VPN'i nasıl kurarım?" gibi bir işte istemci `verso_skills` ile uygun prosedürü bulur, `verso_skill` ile onaylı adımları alır ve gerekirse adımdaki kaynağı `verso_fetch` ile okuyup atıf yapar. Her çağrı çalışanın yetkisiyle çalışır: kaynaklarından birini okuyamadığı prosedürü göremez. Dosya olarak kullanmak isteyen ekip (Claude Code, Codex `.agents/skills`), yöneticinin panelden indirdiği SKILL.md zip'ini kullanabilir; ama dosya kaynak değişince güncellenmez, MCP her zaman günceldir.
- **Çağıranın kimliğiyle çalışır:** IdP'den alınan, yalnız `/mcp`'de geçerli token (`aud` `verso-mcp`); paneldeki aynı okuma yetkisi (kişisel belgeler ve grubunun bilgi alanları). Servis hesabı, paylaşılan anahtar, dinamik istemci kaydı ve token iletimi yok. Token'sız istek `401` ile RFC 9728 metadata adresini öğrenir.
- Sınırlar: sorgu en çok 1000 karakter, en çok 10 sonuç, alıntı 600 ve pasaj 4000 karakter; benzerliği 0,30'un altındaki pasaj hiç dönmez; instance başına 2 eşzamanlı çağrı. Pasaj metni güvenilmeyen içerik olarak işaretlenir, görünmeyen karakterler atılır.
- Her arama ve okuma denetim kaydına yalnız kimliklerle yazılır (`MCP_SEARCH`, `MCP_FETCH`); sorgu saklanmaz ve loglanmaz.
- **Veri nereye gider:** Verso dışarı bağlantı açmaz, `local` mod değişmez. Ancak pasajlar istemciye, oradan istemcinin modeline gider; istemci bulutta ise bu bir aktarımdır. Açmadan önce VKED'in MCP bölümü doldurulur ([`docs/uyum/01-vked-sablonu.md`](docs/uyum/01-vked-sablonu.md), 5.3).
- Desteklenen MCP sürümü: 2025-11-25 (Spring AI 2.0.1, MCP Java SDK 2.0.0).

## Uyum paketi

Kurulum ekibinin her müşteriye teslim ettiği Türkçe KVKK şablonları [`docs/uyum/`](docs/uyum/README.md) altındadır ([ADR-0027](docs/adr/0027-yapay-zeka-bildirimi-ve-uyum-paketi.md)):

- VKED, çalışan aydınlatma metni, Kurumsal Üretken YZ Kullanım Politikası ve Veri İşleyen Sözleşmesi iskeleti.
- Egress kanıtı: `local` modun dışarı veri göndermediği, var olan kontrol ve betiklerle nasıl gösterilir.
- Sektör notları (banka, ödeme kuruluşu, SPK: yalnız `local`; kamu; 7545 olay bildirimi) ve Almanya eki (BetrVG § 87).
- Olgusal bölümler (neyin ne kadar saklandığı, silme yolları, k-anonimlik) kod ve ADR'lerle eşleşir; kuruma özgü alanlar `{{YER_TUTUCU}}` ile işaretlidir. Belgeler hukuki tavsiye değildir.

Panelde yapay zekâ ile yazışıldığı her zaman söylenir: soru kutusunun altındaki satır, ilk kullanımda kapatılabilir bir kart; üretilmiş cevap `data-ai-generated="true"` taşır ve Markdown dışa aktarımı "Yapay zekâ ile üretildi · <ürün adı>" satırıyla biter.

## Dosya paylaşımı bağlayıcısı (SMB)

Bir Windows ya da Samba paylaşımındaki klasörü bir bilgi alanına eşitler ([ADR-0038](docs/adr/0038-smb-baglayicisi.md), API: [docs/api-connectors-integration-v1.md](docs/api-connectors-integration-v1.md)). Yeni ve değişen dosyalar belge olarak alınır, kaynakta silinen dosya Verso'da bütün türevleriyle silinir (ADR-0034). Okuma yetkisini hedef bilgi alanının erişimleri belirler: **alana erişimi olan herkes eşitlenen her dosyayı görür**. NTFS izinleri bu sürümde aktarılmaz; paylaşımı ve öneki buna göre seç (ör. herkese açık politika klasörü, İK'nın kendi alanına İK klasörü).

Kurulum ekibi için:

1. **Servis hesabı.** Etki alanında yalnız bu iş için bir hesap aç (ör. `svc-verso`). Paylaşımda ve NTFS'te yalnız **okuma** izni ver; yazma, silme, sahiplik yok. Hesabın parolasını secret dosyasına yaz (satır sonu olmadan): `printf '%s' '<parola>' > secrets/SECRET_CONNECTOR_SMB_PASSWORD`. Değer veritabanına, log'a ve API yanıtına hiç girmez; parola değişince dosyayı güncelleyip `verso-app`'i yeniden başlat. Başka bir hesap için `SECRET_CONNECTOR_<AD>` biçiminde ikinci bir dosya ve overlay'de `secrets:` satırı eklenir.
2. **Sunucu tarafı.** SMB imzalama açık olsun (Verso imzasız oturumu reddeder). Samba'da `wide links = no` (varsayılan) kalsın: sunucunun kendisi çözdüğü bağlantılar istemciye düz klasör görünür ve paylaşım dışına çıkabilir. Windows'taki sembolik bağlantı ve junction'lar Verso tarafından izlenmez.
3. **Ağ.** Uygulama varsayılan olarak hiçbir ağa çıkamaz. `.env`'de izinli iç ağları yaz (yalnız özel aralıklar; en dar hali sunucunun kendisi): `VERSO_CONNECTOR_ALLOWED_NETWORKS=10.20.30.40/32`. Yığını overlay ile aç: `docker compose -f compose.yaml -f deploy/compose.smb.yaml up -d --wait`. Overlay uygulamaya sabit alt ağlı (`VERSO_SMB_SUBNET`, varsayılan `172.31.250.0/24`) ve `verso-smb` adlı arayüzlü tek bir ağ ekler. Bu ağın internete çıkmaması için host güvenlik duvarında yalnız dosya sunucusunun 445 portuna izin ver (Linux, `DOCKER-USER` zinciri; komutlar `deploy/compose.smb.yaml` başındadır). Local mod kanıtı bu overlay'le `scripts/prove-local-mode.sh`'ın 1. adımında `smb` ağını raporlar (beklenen); kanıt güvenlik duvarı kuralı ve 2. adımdır. Uygulama ayrıca her bağlantıda sunucu adının çözüldüğü **bütün** adresleri izinli listeyle karşılaştırır; loopback, link-local ve metadata adresleri her zaman reddedilir. Sıkı kurulumda sunucuyu IP adresiyle tanımla.
4. **Bağlayıcı.** `verso-admin` önce bilgi alanını oluşturur ve erişimleri verir, sonra panelde "Yönetim → Bağlayıcılar" ekranından ([ADR-0042](docs/adr/0042-baglayici-ekrani.md)) ya da `POST /v1/admin/connectors` ile sunucu, paylaşım, önek, hesap, `credentialRef`, hedef alan, isteğe bağlı `includeGlobs` / `excludeGlobs`, en büyük dosya boyu (en çok 20 MB) ve eşitleme aralığını (5 dk – 1 hafta) verir. İlk eşitleme hemen başlar; `GET /v1/admin/connectors/{id}` `health` alanı (`OK`, `FAILING`, `STALE` …) ve `/runs` geçmişi gösterir.
5. **İzleme.** Üç eşitleme aralığı boyunca başarılı eşitleme yoksa `ConnectorSyncStale` alarmı çalar ([runbook](docs/runbooks/connector-sync-stale.md)). Bir koşu izlenen dosyaların %20'sinden fazlasını silecekse silmeyi bekletir; gerçekten silindiyse tek koşu için `{"confirmMassDeletion": true}` ile onaylanır.

Yalnız PDF, DOCX, TXT ve MD dosyaları alınır (ADR-0016 sınırlarıyla); Office kilit dosyaları (`~$…`), çevrimdışı (HSM) dosyalar ve bağlantılar atlanır. Bir koşu en çok 500 dosya ve 2 GB indirir, kalan iş birkaç dakika sonra devam eder.

## Confluence bağlayıcısı

Confluence alanlarındaki sayfaları (isteğe bağlı olarak PDF ve DOCX eklerini) bir bilgi alanına eşitler ([ADR-0044](docs/adr/0044-confluence-baglayicisi.md), API: [docs/api-connectors-integration-v1.md](docs/api-connectors-integration-v1.md)). SMB bağlayıcısıyla aynı motoru kullanır: aynı koşu geçmişi, sağlık rozeti, `ConnectorSyncStale` alarmı, eksiksiz silme ve %20 silme emniyeti. Sayfalar Markdown'a çevrilir; başlıklar ve tablolar korunur, makro parametreleri ve HTML makroları alınmaz.

- **Okuma yetkisi** hedef bilgi alanının erişimlerinden gelir; Confluence alan ve sayfa izinleri bu sürümde aktarılmaz. **Güvenlik kuralı (varsayılan):** kendisinde ya da bir üst sayfasında okuma kısıtı olan sayfa hiç alınmaz; sonradan kısıtlanan sayfa günde bir yapılan tam listede (kısıt taraması) belgesiyle birlikte silinir. Confluence'ın "son değişiklik" alanı kısıt değişikliğini görmediği için bu tarama zorunludur (CONFCLOUD-41375). Kuralı kapatmak (`skipRestricted: false`) kısıtlı sayfaları alanın bütün okuyucularına açar.
- **Eşitleme:** aralıkta yalnız değişen sayfalar okunur (CQL `lastmodified`); silinen ve arşivlenen sayfalar günlük tam listede bulunur. Confluence 429 dönerse `Retry-After`'a uyulur, sürerse koşu durur ve bağlayıcı bir süre sonra yeniden dener.

Kurulum ekibi için, ortak adımlar:

1. **Hesap ve belirteç.** Yalnız eşitlenecek alanları **okuyabilen**, bu iş için açılmış bir hesap kullan. Belirteci secret dosyasına yaz (satır sonu olmadan): `printf '%s' '<belirteç>' > secrets/SECRET_CONNECTOR_CONFLUENCE_TOKEN`. Değer veritabanına, log'a ve API yanıtına hiç girmez; değişince dosyayı güncelleyip `verso-app`'i yeniden başlat.
2. **Overlay.** `docker compose -f compose.yaml -f deploy/compose.confluence.yaml up -d --wait`. Overlay uygulamaya `verso-cfl` arayüzlü, sabit alt ağlı (`VERSO_CONFLUENCE_SUBNET`, varsayılan `172.31.251.0/24`) bir ağ ve belirtecin secret'ını ekler; host güvenlik duvarı komutları dosyanın başındadır.
3. **Bağlayıcı.** `verso-admin` bilgi alanını oluşturup erişimleri verir, sonra panelde "Yönetim → Bağlayıcılar → Yeni bağlayıcı" ekranında türü **Confluence** seçer: adres, kurulum türü, alan anahtarları, isteğe bağlı etiket ve üst sayfa, belirteç dosyasının adı, hedef alan. İlk koşu tam listedir.

**Data Center / Server (kurum içi):**

- Belirteç bir **kişisel erişim belirteci**dir (Confluence → Profil → Kişisel erişim belirteçleri); istekler `Authorization: Bearer` ile gider, hesap adı alanı boş kalır.
- `.env`: `VERSO_CONNECTOR_ALLOWED_NETWORKS=<Confluence sunucusunun IP'si>/32` (SMB ile aynı liste, yalnız özel aralıklar). Adın çözüldüğü bütün adresler listede olmalıdır; bağlantı anında adres yeniden denetlenir.
- Güvenlik duvarı: `verso-cfl` arayüzünden yalnız sunucunun 443 portu. Adres `https://` ile başlamalıdır; kurum içi bir sertifika otoritesi kullanılıyorsa onun kökü uygulamanın Java güven deposuna eklenmelidir (canlı doğrulama listesinde).

**Cloud (`*.atlassian.net`):**

- Belirteç hesabın **API belirtecidir** (id.atlassian.com → Güvenlik → API belirteçleri); istekler hesabın e-postası ile `Authorization: Basic` gider, formda "Hesap e-postası" doldurulur.
- Cloud internete çıkış ister ve varsayılan olarak **kapalıdır**: `VERSO_CONNECTOR_EXTERNAL_EGRESS=true` ve `VERSO_CONNECTOR_EXTERNAL_HOSTS=kurum.atlassian.net` (en dar hâli kurumun tam site adıdır; `*.atlassian.net` jokeri bir yöneticinin belirteci başka bir Atlassian sitesine göndermesine izin verir). Local mod **sıkıdır**: `VERSO_AI_MODE=local` iken uygulama bu ayarla **açılmaz**, local modun "internete çıkış yok" vaadinin istisnası yoktur. Cloud bağlayıcısı yalnız bulut modunda (`deploy/compose.cloud.yaml`) kullanılır. `/actuator/info` dış bağlantıyı ve izinli host'ları gösterir; VKED'e Atlassian yeni veri kaynağı ve aktarım olarak yazılır ([docs/uyum](docs/uyum/README.md)).
- Güvenlik duvarı: `verso-cfl` arayüzünden yalnız 443. Uygulama yalnız listedeki host adlarına ve yalnız genel (public) adreslere bağlanır, başka host'a yönlendirmeyi izlemez. Cloud ekleri medya sunucusuna yönlendiği için bu sürümde alınmaz.

## SharePoint Online / OneDrive bağlayıcısı

Bir SharePoint Online sitesinin (ya da bir OneDrive'ın) belge kitaplıklarındaki PDF, DOCX, TXT ve MD dosyalarını, isteğe bağlı bir klasör ve desenlerle, bir bilgi alanına eşitler ([ADR-0045](docs/adr/0045-sharepoint-baglayicisi.md), API: [docs/api-connectors-integration-v1.md](docs/api-connectors-integration-v1.md)). SMB ve Confluence ile aynı motoru kullanır: koşu geçmişi, sağlık rozeti, `ConnectorSyncStale` alarmı, eksiksiz silme, %20 silme emniyeti. Microsoft Graph'a bağlayıcının **kendi** Entra uygulama kaydıyla (istemci kimlik bilgileri) bağlanır; kullanıcı adına değil.

- **Eşitleme:** Graph `driveItem/delta`. İlk koşu kitaplığın tamamını listeler; sonrakiler yalnız değişenleri okur ve silinen ya da kapsamdan çıkan dosyayı **aynı koşuda** siler. Delta bağlantısının süresi dolarsa (410) koşu kendiliğinden baştan listeler. Günde bir tam liste yapılır. Graph 429/503 dönerse `Retry-After`'a uyulur, sürerse koşu durur ve bağlayıcı bir süre sonra yeniden dener.
- **Okuma yetkisi** hedef bilgi alanının erişimlerinden gelir; SharePoint izinleri bu sürümde **aktarılmaz**. **Güvenlik kuralı (varsayılan):** izinleri kitaplıktan farklı olan dosya (benzersiz izin, doğrudan verilmiş yetki, paylaşım bağlantısı; ya da benzersiz izinli bir klasörün içinde) hiç alınmaz; izni okunamayan dosya da alınmaz. Sonradan farklılaşan izin günlük tam listede (izin taraması) yakalanır ve belge silinir. Kuralı kapatmak (`skipUniquePermissions: false`) bu dosyaları alanın bütün okuyucularına açar.
- **Aşırı paylaşım (alıcıların bir numaralı kaygısı):** kural yalnız kitaplıktan *farklı* izinleri yakalar; kitaplığın kendisi geniş açıksa ("Herkes", EEEU grupları, site üyeleri) o dosyalar da alanın okuyucularına açılır. **Kurulumdan önce kitaplığın izin denetimini yap** ve kitaplığı bilgi alanının okuyucu kitlesine göre seç (VKED R16).
- **Yalnız bulut modu.** SharePoint Online internettedir: `VERSO_AI_MODE=local` iken dış bağlantı ayarıyla uygulama **açılmaz**; API ve her eşitleme de reddeder. Ulusal bulutlar (ABD kamu, 21Vianet) desteklenmez.

Kurulum ekibi için (Entra uygulama kaydı ve en az yetki):

1. **Uygulama kaydı.** Entra yönetim merkezi → Uygulama kayıtları → Yeni kayıt: ad "Verso SharePoint bağlayıcısı", yalnız bu kuruluş, yönlendirme adresi yok. Oturum açma uygulamasını (ADR-0041) **kullanma**; bağlayıcıya ayrı kayıt aç. Uygulama (client) kimliğini ve dizin (tenant) kimliğini not et.
2. **İzin: `Sites.Selected` (önerilen).** API izinleri → İzin ekle → Microsoft Graph → **Uygulama izinleri** → `Sites.Selected` → "Yönetici onayı ver". Bu izin tek başına **hiçbir siteyi** açmaz; site ayrıca verilir (adım 4). `Sites.Read.All` de çalışır ama kiracının **bütün** sitelerini okutur; önerilmez.
3. **Sertifika (önerilen; istemci gizlisi yerine).** Anahtar ve sertifikayı üret, sertifikayı (yalnız açık kısmı) Entra'ya yükle, ikisini tek secret dosyasına koy:

   ```bash
   openssl req -x509 -newkey rsa:3072 -nodes -keyout verso-spo.key -out verso-spo.crt -days 365 -subj "/CN=verso-sharepoint"
   # Entra: Sertifikalar ve gizli diziler → Sertifikayı karşıya yükle → verso-spo.crt
   cat verso-spo.key verso-spo.crt > secrets/SECRET_CONNECTOR_SHAREPOINT_CERT && rm verso-spo.key
   ```

   Anahtar PKCS#8 (`-----BEGIN PRIVATE KEY-----`, RSA ≥ 2048) olmalıdır. Sertifika süresi dolmadan yenisini yükleyip dosyayı değiştir (süresi dolunca koşu `CREDENTIAL_EXPIRED`). İstemci gizlisi kullanılacaksa: `printf '%s' '<gizli>' > secrets/SECRET_CONNECTOR_SHAREPOINT_SECRET`, formda "İstemci gizlisi" ve bu dosya adı; overlay'deki secret adını da buna göre değiştir.
4. **Siteye okuma izni (yalnız eşitlenecek site).** Kiracı yöneticisi (Graph PowerShell; yönetici bir kez `Sites.FullControl.All` ile oturum açar, bağlayıcı uygulaması değil):

   ```powershell
   Install-Module Microsoft.Graph.Sites -Scope CurrentUser
   Connect-MgGraph -TenantId "<tenant-id>" -Scopes "Sites.FullControl.All"
   $site = Get-MgSite -SiteId "kurum.sharepoint.com:/sites/ik"
   New-MgSitePermission -SiteId $site.Id -BodyParameter @{
     roles = @("read")
     grantedToIdentities = @(@{ application = @{ id = "<client-id>"; displayName = "Verso SharePoint bağlayıcısı" } })
   }
   Get-MgSitePermission -SiteId $site.Id    # doğrulama; geri almak: Remove-MgSitePermission -SiteId $site.Id -PermissionId <id>
   ```

   PnP.PowerShell ile aynısı: `Grant-PnPAzureADAppSitePermission -AppId <client-id> -DisplayName "Verso SharePoint bağlayıcısı" -Site https://kurum.sharepoint.com/sites/ik -Permissions Read`. Her ek site için tekrarla; OneDrive için kişinin sitesi (`kurum-my.sharepoint.com:/personal/...`).
5. **Dış bağlantı ve overlay.** Bulut modunda (`deploy/compose.cloud.yaml`), `.env`: `VERSO_CONNECTOR_EXTERNAL_EGRESS=true`, `VERSO_CONNECTOR_EXTERNAL_HOSTS=graph.microsoft.com,login.microsoftonline.com,kurum.sharepoint.com` (OneDrive için `kurum-my.sharepoint.com` da; joker `*.sharepoint.com` başka kiracıların sitelerine de izin verir). `docker compose -f compose.yaml -f deploy/compose.cloud.yaml -f deploy/compose.sharepoint.yaml up -d --wait`. Overlay `verso-spo` arayüzlü bir ağ (`VERSO_SHAREPOINT_SUBNET`, varsayılan `172.31.252.0/24`) ve secret'ı ekler; güvenlik duvarında yalnız 443 (komutlar dosyanın başında). Uygulama yalnız listedeki adlara ve genel adreslere bağlanır; dosya indirmesi yalnız izinli `sharepoint.com` host'una ve erişim belirteci **olmadan** yönlendirilir.
6. **Bağlayıcı.** Panelde "Yönetim → Bağlayıcılar → Yeni bağlayıcı" → tür **SharePoint Online / OneDrive**: site adresi, isteğe bağlı kitaplıklar, klasör ve desenler, uygulama kimliği, isteğe bağlı dizin kimliği (boşsa `OIDC_ENTRA_TENANT_ID`), kimlik doğrulama, anahtar dosyasının adı, hedef alan. `DRIVE_NOT_FOUND` / `SITE_NOT_FOUND` çoğunlukla adım 4'ün yapılmadığını gösterir.

## Ölçümler

| Ölçüm | Sonuç | Kaynak |
|---|---|---|
| Retrieval: doğru sayfa ilk 5 pasajda (sürüm 2, 112 soru; hibrit arama, ADR-0022) | 0,982 (ilk sırada 0,795, MRR 0,877) | `eval/results/retrieval.json` |
| Cevaplanabilirlik kapısı (sürüm 2) | cevaplanabilir 112/112 modele gider, cevaplanamaz 7/28 modelsiz "bulunamadı" alır | aynı |
| Cevapta doğru belge ve sayfaya atıf (sürüm 2, GPU) | 0,911 | `eval/results/e2e-gemma4_e2b-gpu.json` |
| Cevapta beklenen bilgi (sürüm 2, GPU) | 0,884 | aynı |
| Belgelerde olmayan soruya "bulunamadı" (sürüm 2, 28 soru) | 0,607: kapı 7/7, model 10/21. Konusu belgede olan sorular en zayıf yer | aynı |
| Cevap süresi (sürüm 2, GPU) | p50 1,5 sn, p95 3,3 sn | aynı |
| Takip sorusu ("Peki P2 için?", 32 konuşma, GPU): doğru sayfaya atıf | yeniden yazmayla 0,875 (yazmadan 0,719); yazma çağrısı p50 0,38 sn, p95 0,68 sn | `eval/results/followup-{on,off}-gpu.json` |
| Dayanak kontrolü (192 etiketli cümle; ADR-0047) | desteklenmeyen cümlede kesinlik 1,00, duyarlılık 0,46 (sayı ve kodda 0,76); gerçek 238 atıflı cümlede 2 işaret, ikisi de doğru | `eval/grounding-set.json`, `docs/evidence/dayanaklilik-olcumu.md` |
| Çok adımlı kapı (ADR-0055; 20 çok adımlı, 140 tek adımlı soru) | sözcüksel kapı çok adımlı 19/20'de ateşler, tek adımlı 3/140'ta; uçtan uca açık/kapalı karşılaştırma gerçek modelle **bekliyor** | `eval/multihop-set.json`, `ReasoningGateEvalTest` |
| Cevap süresi (CPU, 2 çekirdek; sürüm 1, 33 soru) | iki koşu: p50 28 / 21 sn, p95 45 / 37 sn | `eval/results/e2e-gemma4_e2b.json` |
| Belge listesi | 841 istek/sn, p95 49 ms | `docs/capacity.md` |
| Eşzamanlı soru | 1 (ikincisi `503` ve `Retry-After` ile "meşgul" alır); GPU'da `OLLAMA_NUM_PARALLEL=4` ile 4 (4 kullanıcıda 1,6 cevap/sn, 503 yok; ADR-0051) | `docs/capacity.md` |

Set 2026-10-24'te 140 soruya ve 11 sentetik belgeye (44 sayfa) çıktı: ilk 6 belge yönetmelik, yeni 5 belge şirket sistemlerinin kılavuzu. Hâlâ küçüktür; kapının eşikleri bu setle seçildi, belge grubuna göre çapraz doğrulaması ADR-0022'dedir. Sonuçlar yön gösterir, istatistiksel güvence vermez. Metrik tanımları, sınırlar ve komutlar: [`eval/README.md`](eval/README.md).

## Mimari

```text
 tarayıcı (panel) / istemci ──► edge (nginx, 127.0.0.1:8080: /panel/ + API) ──► verso-app (Spring Boot, modüler monolit)
                                                              │  document modülü: yükleme, PDF, worker, embedding, arama
                                                              │  qa modülü: eşik, prompt, chat, atıf, geçmiş, geri bildirim, metrikler
                          keycloak (OIDC, 127.0.0.1:8180) ◄────┤  platform: hata zarfı, güvenlik, log temizleyici
                                                              ├──► postgres 18 + pgvector (iç ağ)
                                                              └──► ollama: bge-m3 + gemma4:e2b (iç ağ, internetsiz)
 isteğe bağlı: prometheus · alertmanager · loki ◄ alloy · grafana (obs profili) · yedek ve restore provası
```

Kararlar [`docs/decisions.md`](docs/decisions.md) içindedir (ADR-0001 – ADR-0035). Fazlar [`docs/roadmap.md`](docs/roadmap.md), her fazın kanıtı `docs/evidence/faz-N-dogrulama.md` dosyasındadır.

### Kenar proxy

`edge` (nginx) API'nin ve panelin tek yayınlanan portudur. Akışlı cevap ucu `location = /v1/questions/stream` ile ayrı tutulur: yalnız orada yanıt tamponlama kapalıdır (`proxy_buffering off`, `proxy_cache off`, `gzip off`), okuma zaman aşımı 150 sn'dir ve uygulama model sessizken 10 sn'de bir `:keep-alive` yazar (ADR-0035). Üretimde önüne bir TLS sonlandırıcısı (ya da kurumun ters proxy'si) konursa aynı yol için de tamponlamayı kapatın ve okuma zaman aşımını en az 30 sn yapın; uygulama `X-Accel-Buffering: no` gönderir, bunu dikkate alan proxy'lerde ayrıca ayar gerekmez. Tamponlayan bir proxy akışı bozmaz, cevap yalnız sonunda tek seferde görünür. Panel CSP'si değişmez (`connect-src 'self'`).

---

# Ayrıntılar

## Servis kimlik tablosu

| Servis | Port | `spring.application.name` | DB şeması / rol | Hata kodu bloğu | Main sınıf |
|---|---|---|---|---|---|
| verso-app | 8080 (API, `/v1` audience `verso-api`; MCP `/mcp` audience `verso-mcp`, varsayılan kapalı), 8081 (actuator, compose ağı içinde) | `verso` | `document` / `svc_document` (DML), `svc_document_migrate` (sahip, DDL); `qa` / `svc_qa`, `svc_qa_migrate` (ADR-0023); `audit` / `svc_audit` (yalnız INSERT ve SELECT), `svc_audit_migrate` (ADR-0026); `connector` / `svc_connector`, `svc_connector_migrate` (ADR-0038, ADR-0044); `work` / `svc_work`, `svc_work_migrate` (ADR-0057) | document 10000–10999, qa 11000–11999, audit 12000–12999, privacy 13000–13999, connector 14000–14999, work 15000–15999 | `VersoApp` |

## Hata kodu blokları

Kodlar global olarak tekildir. `ErrorCodeUniquenessTest` bu tabloyu zorlar.

| Blok | Aralık | Not |
|---|---|---|
| document | 10000–10999 | belge yükleme, işleme, retrieval, bilgi alanları (10002–10004, 10040–10044; ADR-0028), sayfa görünümü (10005; ADR-0035), sistem modeli (10050–10051; ADR-0054) |
| qa | 11000–11999 | soru-cevap, model çağrısı; onaylı bilgi kartı ve altın set (11040–11048; ADR-0049); bilgi sağlığı ve kancalar (11060–11063; ADR-0052); onaylı prosedürler (11070–11079; ADR-0053); sistem modeli önerileri (11080–11083; ADR-0054) |
| audit | 12000–12999 | denetim kaydı sorgusu ve dışa aktarımı |
| privacy | 13000–13999 | KVKK ilgili kişi başvurusu ve cevap karartma (ADR-0034) |
| connector | 14000–14999 | dosya paylaşımı bağlayıcısı yönetimi (ADR-0038); Confluence türü, URL ve dış bağlantı (14012–14015; ADR-0044) |
| work | 15000–15999 | beceri kütüphanesi: beceriler, çalıştırmalar, kuruma özel beceriler (15001–15017; ADR-0057) |
| validation | 90000–90099 | ortak: bean validation, binding, 404/405/415, API sürümü |
| security | 90100–90199 | ortak: kimlik ve yetki (faz 3) |
| system | 99997–99999 | ortak: geçici olarak kullanılamıyor (503), upstream, beklenmeyen |

Hata yanıtı her zaman aynı zarftadır (ADR-0004):

```json
{ "ok": false, "data": null,
  "error": { "code": 90010, "message": "Resource not found.", "service": "validation",
             "path": "/v1/...", "timestamp": 1790935200000, "traceId": "…", "details": [] } }
```

## Çalıştırma

Gereksinim: Docker (Compose v2). Temiz bir klondan:

```bash
bash scripts/dev-secrets.sh
```

```bash
docker compose up -d --build --wait
```

API `http://127.0.0.1:8080` adresindedir; PostgreSQL ve actuator portu dışarı açılmaz. Migration'ları tek seferlik `migrate` servisi çalıştırıp çıkar. Uzun ömürlü uygulama migration rolünün parolasını hiç görmez ve DDL yapamaz. Gizli olmayan ayarlar için `.env.example`'ı `.env` olarak kopyalayabilirsin; her değerin varsayılanı vardır. Kararlar: [ADR-0009](docs/adr/0009-veri-altyapisi.md).

**Secret'lar** dosyadır, ortam değişkeni değildir (referans 15.3). `secrets/<AD>` → container'da `/run/secrets/<AD>` → Spring'de aynı adlı property:

| Secret dosyası / CI secret adı | Kullanan | Property / rol |
|---|---|---|
| `SECRET_POSTGRES_SUPERUSER_PASSWORD` | postgres | superuser; uygulama görmez |
| `SECRET_DB_DOCUMENT_MIGRATE_PASSWORD` | migrate (Flyway, tek seferlik) | `verso.document.flyway.password` → `svc_document_migrate` (ADR-0039) |
| `SECRET_DB_DOCUMENT_PASSWORD` | verso-app | `verso.document.datasource.password` → `svc_document` (ADR-0039) |
| `SECRET_DB_BACKUP_PASSWORD` | backup | `verso_backup` (`pg_read_all_data`) |
| `SECRET_BACKUP_ENCRYPTION_KEY` | backup, restore provası | yedeklerin gpg parolası |
| `SECRET_DB_KEYCLOAK_PASSWORD` | postgres (init), keycloak | `keycloak` rolü ve veritabanı |
| `SECRET_KEYCLOAK_ADMIN_PASSWORD` | keycloak | master realm yöneticisi `admin` |
| `SECRET_KEYCLOAK_CI_CLIENT_SECRET` | keycloak, CI smoke | `verso-ci` istemcisi (client credentials) |
| `SECRET_KEYCLOAK_DEMO_USER_PASSWORD` | keycloak | `verso` realm'indeki `demo` kullanıcısı |

**Kimlik doğrulama** (ADR-0005, ADR-0010). API her istekte `Authorization: Bearer <access token>` ister; health uçları dışında token'sız istek `401` döner. Token'ı OIDC IdP verir: compose'ta demo Keycloak `http://127.0.0.1:8180` (realm `verso`), üretimde kurumun kendi IdP'si (`OIDC_ISSUER`, `OIDC_JWK_SET_URI`). Kabul edilen token: ES256 imzalı, `typ: at+jwt`, `iss` birebir, `aud` içinde `verso-api`, süreli. Hesap kimliği token'ın `sub`'ıdır. Roller ve gruplar da token'dan okunur (ADR-0019): rolü olmayan kullanıcı `verso-user` sayılır, `verso-admin` yalnız IdP bu rolü verirse. Claim yolları `OIDC_ROLES_CLAIM`, `OIDC_CLIENT_ROLES_CLAIM` ve `OIDC_GROUPS_CLAIM` ile ayarlanır; varsayılanlar Keycloak'ındır. Kurallar issuer başınadır (ADR-0041): token'ın `iss`'i yalnız güven listesinden bir doğrulayıcı seçer, listede olmayan issuer için anahtar çekilmez.

**Microsoft Entra ID** (ADR-0041, kurulum: [`docs/entra-kurulumu.md`](docs/entra-kurulumu.md)). Entra token'ları Keycloak'sız, ikinci bir güvenilen issuer olarak kabul edilir: `OIDC_ENTRA_TENANT_ID`, `OIDC_ENTRA_AUDIENCES` (ve MCP için `OIDC_ENTRA_MCP_AUDIENCES`). Yalnız bu kiracının v2.0 token'ları, RS256, `typ: JWT`, dolu `scp`; hesap kimliği `oid`; roller `roles`, gruplar `groups` (200'den fazla grupta hiç grup verilmez). Yalnız Entra için `OIDC_ISSUER` ve `OIDC_JWK_SET_URI` boş bırakılır. Panel için `panel/js/config.js`'te Entra uçları ve `scope` yazılır; kenar CSP'si statiktir, bu kurulumda `deploy/edge/nginx.conf` panel bloğundaki `connect-src 'self' http://localhost:8180` elle `connect-src 'self' https://login.microsoftonline.com` yapılır. Yerel modda Entra anahtarları için iç ağda bir JWKS aynası gerekir (`OIDC_ENTRA_JWK_SET_URI`).

Demo kullanıcıyla token (device flow): betik bir bağlantı yazar, tarayıcıda `demo` kullanıcısıyla (parola `secrets/SECRET_KEYCLOAK_DEMO_USER_PASSWORD`) giriş yapınca token'ı verir.

```bash
TOKEN="$(bash scripts/demo-token.sh)"
```

Token'sız istek `401` döner. Kimlik zincirinin uçtan uca kontrolü (CI'da da çalışır):

```bash
bash scripts/auth-smoke.sh
```

**Belgeler** (ADR-0011, ADR-0016; istemci sözleşmesi: [`docs/api-documents-integration-v1.md`](docs/api-documents-integration-v1.md)). PDF, DOCX, TXT ya da MD yükle; belge `PENDING` olarak döner, worker okuyup yerel bge-m3 modeliyle vektörleştirir ve birkaç saniye içinde `READY` olur. PDF sayfa sayfa okunur; sayfası olmayan türler başlıklara göre bölümlere ayrılır ve cevaplarda "bölüm n" diye kaynak gösterilir. Orijinal dosya işlendikten sonra silinir; metin ve vektörler kalır.

```bash
curl -s -H "Authorization: Bearer $TOKEN" -F "file=@scripts/fixtures/smoke.pdf;type=application/pdf" http://127.0.0.1:8080/v1/documents
```

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/v1/documents
```

`DELETE /v1/documents/{id}` belgeyi tüm türevleriyle siler.

**Soru sor** (ADR-0012; istemci sözleşmesi: [`docs/api-questions-integration-v1.md`](docs/api-questions-integration-v1.md)). Cevap yalnız senin belgelerinden gelir ve kaynak (belge + sayfa) gösterir; ilgili pasaj yoksa model hiç çağrılmaz ve "bulunamadı" döner. Yanıttaki `X-Rag-Mode` cevabı hangi modun verdiğini söyler.

```bash
curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"question":"Yıllık izin kaç gün?"}' http://127.0.0.1:8080/v1/questions
```

CPU'da `gemma4:e2b` ile bir cevap on saniyeler sürer (ADR-0008). Uçtan uca kontroller (gerçek modellerle, CI'da da çalışır):

```bash
bash scripts/ingest-smoke.sh
```

```bash
bash scripts/qa-smoke.sh
```

**Gözlem** (isteğe bağlı; ADR-0014). Prometheus, Alertmanager, Loki, Alloy ve Grafana `obs` profiliyle açılır (yaklaşık 1,6 GB ek bellek). Grafana `http://127.0.0.1:3000` adresindedir; kullanıcı `admin`, parola `secrets/SECRET_GRAFANA_ADMIN_PASSWORD` dosyasındadır. Alarmlar ve runbook'ları: [`docs/runbooks/`](docs/runbooks/README.md).

```bash
docker compose --profile obs up -d --wait
```

```bash
bash scripts/obs-smoke.sh
```

**Local mod kanıtı** (ADR-0006, ADR-0013). Uygulama, veritabanı ve model sunucusu yalnız iç ağlardadır; API'ye kenar proxy (`edge`, nginx) üzerinden ulaşılır. Script, uygulamanın kendi ağından internete bağlanılamadığını ve isim çözülemediğini, her yanıtın `X-Rag-Mode: local` taşıdığını gösterir (CI'da da çalışır):

```bash
bash scripts/prove-local-mode.sh
```

**Cloud modu** (isteğe bağlı; ADR-0013). Yalnız chat çağrısı sağlayıcıya gider: sistem kuralları, soru ve en fazla 5 pasaj. Belgeler, tam metin ve vektörler yerelde kalır. Varsayılan sağlayıcı Anthropic'tir (`VERSO_CLOUD_CHAT_MODEL=claude-sonnet-5-5`). OpenAI uyumlu bir API için `.env`'de üçü birlikte değişir: `VERSO_CLOUD_PROVIDER=openai`, `VERSO_OPENAI_BASE_URL` ve o sağlayıcının model adıyla `VERSO_CLOUD_CHAT_MODEL`; aksi hâlde sağlayıcıya Claude model adı gider ve her soru 503 alır. Anahtar yalnız dosyadır, ortam değişkeni değildir (ortamdan gelirse uygulama başlamaz). Anahtarı kabuk geçmişine yazmadan oluşturmak için:

```bash
read -rs -p "API anahtarı: " key && printf '%s' "$key" > secrets/SECRET_CLOUD_API_KEY && unset key
```

```bash
docker compose -f compose.yaml -f deploy/compose.cloud.yaml up -d --build --wait
```

> **KVKK md. 9:** cloud modda pasajlar (kişisel veri içerebilir) yurt dışındaki bir sağlayıcıya aktarılabilir. Bu modu açma kararı ve hukuki dayanağı veri sorumlusunundur; bu not hukuki tavsiye değildir. Mod ve sağlayıcı tutarsızsa (ör. local modda bulut sağlayıcısı, cloud modda anahtar yok) uygulama başlamaz.

**Faz 2'den yükseltme.** Init script'leri yalnız boş veri volume'ünde çalışır; faz 3'ten önce oluşmuş bir volume'de Keycloak'ın veritabanı yoktur ve `keycloak` sağlıklı olmaz. Bir kez şu adımlar (secret'ları üretir, postgres'i yeni secret'la yeniden oluşturur, idempotent script'i çalıştırır):

```bash
bash scripts/dev-secrets.sh && docker compose up -d --wait postgres
```

```bash
MSYS_NO_PATHCONV=1 docker compose exec -T -u postgres postgres bash /docker-entrypoint-initdb.d/30-keycloak.sh
```

```bash
docker compose up -d --build --wait
```

(`MSYS_NO_PATHCONV=1` yalnız Windows Git Bash için; diğer kabuklarda etkisizdir.)

**Konuşma geçmişinden önceki bir kurulumdan yükseltme (ADR-0023).** `qa` şeması ve rolleri (`svc_qa`, `svc_qa_migrate`) init script'leri boş volume'de çalıştığı için var olan bir volume'de yoktur; uygulama açılmaz. Bir kez: secret'ları üret, postgres'i yeni secret'larla yeniden oluştur, idempotent script'i çalıştır (var olan rol ve şemalara dokunmaz), sonra yığını kaldır.

```bash
bash scripts/dev-secrets.sh && docker compose up -d --wait postgres
```

```bash
MSYS_NO_PATHCONV=1 docker compose exec -T -u postgres postgres bash /docker-entrypoint-initdb.d/40-modules.sh
```

```bash
docker compose up -d --build --wait
```

Yükseltmeden önce alınmış yedekte `qa` şeması yoktur; restore provası onu Flyway `validate` adımında reddeder. Yükseltmeden sonra yeni bir yedek al: `docker compose run --rm backup once`.

**Denetim kaydı olan bir sürüme yükseltme (ADR-0026).** `audit` şeması ve rolleri (`svc_audit`, `svc_audit_migrate`) var olan bir volume'de yoktur; uygulama açılmaz. Konuşma geçmişi yükseltmesiyle aynı yol: `bash scripts/dev-secrets.sh` (yalnız eksik `SECRET_DB_AUDIT_*` dosyalarını üretir), `docker compose up -d --wait postgres`, `40-modules.sh` (idempotent: var olan `document` ve `qa` rollerinin parolasına dokunmaz), sonra `docker compose up -d --build --wait` (`migrate` container'ı `audit` tablosunu kurar). Yükseltmeden önce alınmış yedekte `audit` şeması yoktur; restore provası onu reddeder, yükseltmeden sonra yeni yedek al. Üretimde `secrets/` dosyalarını kendi gizli yönetiminden ver (`secrets/README.md`) ve `deploy/prod.env.example`'a `VERSO_AUDIT_RETENTION_DAYS` ekle.

**Outbox'lı denetim kaydına yükseltme (ADR-0039).** Yeni rol, secret ya da şema yok; `document.outbox_event`, `qa.outbox_event` ve `audit.event.event_id` migration'larla gelir. Sıra her zamanki: `docker compose up -d --build --wait` (`migrate` önce çalışır; yeni imaj `event_id` sütunu olmadan açılmaz, eski imaj yeni sütunu yok sayar). Kendi config dosyasını tutan kurulumlar: `spring.datasource.*` ve `spring.flyway.url/user/password` anahtarları `verso.document.datasource.*` ve `verso.document.flyway.*` oldu (Boot'un kendi DataSource ve Flyway'i kapalı); `deploy/prod.env.example`'a `VERSO_INGESTION_ENABLED=true` ekle. Denetim kayıtları artık iş değişikliğiyle aynı transaction'da modülün outbox'ına yazılır ve commit'ten hemen sonra teslim edilir; denetim veritabanı yokken kaybolmaz, bekler (`outbox_oldest_pending_age_seconds`, [runbook](docs/runbooks/outbox-stuck.md)). `VERSO_INGESTION_ENABLED=false` aynı imajı yalnız API olarak çalıştırır (ingestion worker'ı yok; yüklenen belgeler `PENDING` kalır), bugün tek süreç kullanıldığı için `true` kalır.

**Beceri kütüphanesi olan bir sürüme yükseltme (ADR-0057).** `work` şeması ve rolleri (`svc_work`, `svc_work_migrate`) var olan bir volume'de yoktur; bağlayıcı yükseltmesiyle aynı yol: `bash scripts/dev-secrets.sh` (yalnız eksik `SECRET_DB_WORK_*` dosyalarını üretir), `docker compose up -d --wait postgres`, `docker compose exec -T -u postgres postgres bash /docker-entrypoint-initdb.d/40-modules.sh`, sonra `docker compose up -d --build --wait`. Yeni ortam değişkeni yok; çalıştırmalar konuşma geçmişiyle aynı süre (`VERSO_HISTORY_RETENTION_DAYS`) saklanır. Yükseltmeden sonra yeni yedek al.

**Bağlayıcı modülü olan bir sürüme yükseltme (ADR-0038).** `connector` şeması ve rolleri (`svc_connector`, `svc_connector_migrate`) var olan bir volume'de yoktur; denetim kaydı yükseltmesiyle aynı yol: `bash scripts/dev-secrets.sh` (yalnız eksik `SECRET_DB_CONNECTOR_*` dosyalarını üretir), `docker compose up -d --wait postgres`, `40-modules.sh`, sonra `docker compose up -d --build --wait`. SMB kullanmayan kurulum overlay'i açmaz; `VERSO_CONNECTOR_ALLOWED_NETWORKS` boş kalır. Yükseltmeden sonra yeni yedek al.

**Bilgi alanları olan bir sürüme yükseltme (ADR-0028).** Yeni şema, secret ya da ortam değişkeni yok. Adımlar:

1. Önce yedek al: `docker compose run --rm backup once`.
2. `docker compose up -d --build --wait`. `migrate` container'ı `V4__collections.sql`'i uygular: tablolar eklenir, var olan her belgeye tek okuyucu satırı (`user:<sahip>`) yazılır, chunk tablosu yeniden yazılmaz. Var olan belgeler kişisel kalır, davranış değişmez.
3. Gruplar token'da olmalı. Paketteki realm'de `verso-panel` ve `verso-cli` istemcilerinin `groups` mapper'ı vardır (ADR-0019). Eski bir yığın realm'i yeniden içe aktarmadıysa `bash scripts/keycloak-reimport.sh`. Entra'da `groupMembershipClaims` ve `OIDC_GROUPS_CLAIM=groups`.
4. Yönetici panelde "Bilgi alanları"nı açar, alanı oluşturur, grup yolunu (`/hukuk`) yazarak erişim verir, sonra "Bilgi kaynakları"nda yükleme yeri olarak alanı seçer.

Grup üyeliği değişikliği kullanıcının bir sonraki oturum anahtarında (en çok 300 sn) geçerli olur. Alandan kaldırılan erişim sonraki istekte geçerlidir. Keycloak'ta grubu yeniden adlandırmak o yola verilmiş erişimi düşürür; yeni yolla yeniden verin. Geri dönmeden önce paylaşımlı belgeleri kişisel alana taşıyın ya da silin: eski sürüm onları yükleyenin belgesi sayar.

**MCP sunucusu olan bir sürüme yükseltme (ADR-0031).** Yeni şema, migration ya da secret yok; MCP kapalı gelir. Açmak için:

1. VKED'in MCP bölümünü ([`docs/uyum/01-vked-sablonu.md`](docs/uyum/01-vked-sablonu.md), 5.3) ve onaylı istemci listesini tamamla.
2. Paketteki realm'i kullanıyorsan `verso-mcp` scope'u ve `verso-mcp-inspector` istemcisi için `bash scripts/keycloak-reimport.sh` (demo oturumları gider). Kurumun IdP'sinde `verso-mcp` audience'ı veren bir scope ve her istemci için kayıt aç ([`docs/api-mcp-integration-v1.md`](docs/api-mcp-integration-v1.md)).
3. `.env`'de `VERSO_MCP_ENABLED=true` ve `VERSO_MCP_RESOURCE=https://<dış adres>/mcp`, sonra `docker compose up -d --wait` (uygulama ve kenar proxy birlikte açılır).

**Ollama 0.40'lı bir sürüme yükseltme (ADR-0033).** Yeni secret ya da ortam değişkeni yok; var olan model volume'ü korunur, modeller yeniden indirilmez (yalnız küçük manifestler gelir). Çalışan Ollama volume'ü salt okunur bağlamaya devam eder, bu yüzden 0.35'ten kalma depo (`manifests-v2` yok) önce `ollama-pull` ile tamamlanmalıdır. Adımlar:

1. Yedek: `docker compose run --rm backup once`.
2. Depoyu tamamla (internet gerekir, tek seferlik): `docker compose up ollama-pull`. Log'da her model için `ollama-pull: … ready` ve çıkış kodu 0 beklenir; `does not match the pinned digest` görürsen dur, digest'i kontrol et.
3. `docker compose up -d --wait ollama` (GPU'da `-f compose.yaml -f deploy/compose.gpu.yaml` ekle). `docker compose exec ollama ollama ps` iki modeli göstermeli.
4. Geri dönmek için: eski imaj etiketiyle `ollama-pull` ve `ollama` yeniden oluşturulur; 0.35 `manifests-v2/`'yi okumaz, v1 manifestleri yerinde durur (0.35.1'in 0.40 tarafından yazılan v1 manifestini okuyup okumadığı ölçülmedi: gerekirse `ollama-pull` modelleri yeniden çeker).

**Denetim kaydının saklama süresi.** Satırlar `VERSO_AUDIT_RETENTION_DAYS` gün (varsayılan 365, 30–3650) tutulur, sonra 6 saatte bir çalışan iş yalnız veritabanındaki `audit.purge_expired` fonksiyonuyla siler; fonksiyon 30 günden yeni bir sınırı reddeder, yani ele geçirilen bir uygulama yakın geçmişi silemez. Süreyi veri sorumlusu kurum belirler (KVKK md. 4/2-d, md. 12); gerekçe ADR-0026'dadır. Kayıt yazılamazsa istek yine başarılı olur, `AuditEventNotStored` alarmı çalar (`docs/runbooks/audit-write-failed.md`).

**Geçmişin saklama süresi ve yönetici panosunun politikaları.** Bir konuşma, başladığı andan itibaren `VERSO_HISTORY_RETENTION_DAYS` gün (varsayılan 90) tutulur, sonra saatlik bir iş bütün turları ve başlığıyla siler; kullanıcı kendi geçmişini her zaman silebilir. Süreyi veri sorumlusu kurum belirler (KVKK md. 4: amaç için gerekli süre). Yönetici panosu, en az 3 farklı kullanıcının sorduğu "bulunamadı" sorularını yalnız `VERSO_SHOW_UNANSWERED_QUESTIONS=true` ise gösterir (varsayılan kapalı; çalışan aydınlatma metni bu amacı kapsamalıdır). En çok ve hiç atıf alan belgelerin adları da yalnız `VERSO_SHOW_DOCUMENT_NAMES=true` ise görünür (varsayılan kapalı: kişisel bir belgenin adı ve günlük atıf sayısı bir çalışanın ne aradığını gösterebilir; bilgi alanlarının belgeleri de aynı kurala bağlıdır, alan adı panoda hiç görünmez). Üçü de `.env`'de; bu not hukuki tavsiye değildir.

**Kaynaklar.**

- **Bellek:** container sınırlarının toplamı yaklaşık 10,6 GB'dır (uygulama 1,5 GB, kenar proxy 64 MB, Ollama 6 GB (embedding ve chat modeli açılışta yüklenir ve bellekte kalır; ilk soru model yüklemesini beklemez, `docs/capacity.md`), PostgreSQL 1 GB, Keycloak 1 GB, yedek 512 MB, tek seferlik migrate ve model indirme 512'şer MB). Host'ta en az 12 GB boş RAM önerilir.
- **Disk ve ağ:** ilk açılış yaklaşık 8,5 GB indirir (Ollama imajı ~3,8 GB, bge-m3 modeli 1,2 GB, gemma4:e2b modeli 3,5 GB, nginx imajı 23 MB); buna Keycloak, PostgreSQL, Flyway ve Temurin imajları ile `--build` sırasında Maven bağımlılıkları eklenir. Sonraki açılışlar internetsiz çalışır; model volume'de kalır ve çalışan Ollama'nın internete çıkışı yoktur.
- **Embedding:** varsayılan olarak CPU'da yapılır; Ollama 2 CPU ile sınırlıdır (`OLLAMA_CPUS`). `deploy/compose.gpu.yaml` ile embedding ve chat NVIDIA GPU'da çalışır (`docs/capacity.md`). Portları `.env` ile değiştirdiysen (`VERSO_HTTP_PORT`, `VERSO_KEYCLOAK_PORT`) betikler için de `export` et: `scripts/*.sh` `.env`'i okumaz.

**Yedek ve geri yükleme.** `backup` servisi şifreli `pg_dump` alır: varsayılan günde bir, 7 gün saklanır ve en yeni yedek hiç silinmez. Prova, en yeni yedeği geçici bir veritabanına geri yükler; satır sayılarını, yetkileri ve Flyway `validate`'i doğrular. CI bunu haftalık çalıştırır.

```bash
docker compose run --rm backup once
```

```bash
bash scripts/restore-drill.sh
```

- **RPO:** yedek aralığı (varsayılan 24 saat, `BACKUP_INTERVAL_SECONDS`).
- **RTO hedefi:** 1 saat.
- **Bağlantı bütçesi:** `document` havuzu 10 (ADR-0039: modülün kendi havuzu, eski uygulama havuzu) + `qa` havuzu 4 + `audit` havuzu 4 + Flyway 2 (yalnız migrate anında) + yedek 2 = 22; `max_connections` 100 (ADR-0023, ADR-0026).
- **Kapsam:** yalnız Verso veritabanı. Demo Keycloak'un `keycloak` veritabanı yedeklenmez; realm dosyadan yeniden kurulur (ADR-0010).
- **Bilinen sınır:** yedek aynı host'taki volume'dedir. Host dışına kopyalama üretim kurulumunun işidir (ADR-0009).
- **`docker compose down -v`** veritabanıyla birlikte `backups` volume'ünü de siler; önce yedekleri kopyala.
- **Parola ve anahtar rotasyonu:** [`secrets/README.md`](secrets/README.md). Init script'leri yalnız ilk kurulumda çalışır.
- **Backup servisi** son başarılı yedek iki aralıktan eskiyse `unhealthy` görünür (`docker compose ps`).

**İmaj yayını** (`.github/workflows/release.yml`): `v*` etiketi önce o commit'te build ve testleri koşar, sonra uygulama imajını bir kez build edip SBOM ve provenance ile `ghcr.io/<owner>/verso` adresine iter. Digest cosign ile anahtarsız imzalanır, CycloneDX SBOM'u atestasyon olarak eklenir ve ikisi doğrulandıktan sonra sürüm etiketi, geri çekilip kontrol edilen digest'e konur. İmaj yalnız uygulamayı içerir: compose dosyaları, kenar proxy ayarı, panel, realm ve init script'leri depodan bağlanır. Yani imajdan kurulum için de depo checkout'u gerekir.

**İmzalı imajdan kurulum, yükseltme ve geri alma** ([ADR-0040](docs/adr/0040-imzali-imajdan-kurulum-ve-geri-alma.md)): müşteri imajı derlemez; `VERSO_IMAGE=ghcr.io/<owner>/verso@sha256:<digest>` verir, `bash scripts/verify-release.sh` imzayı ve CycloneDX atestasyonunu doğrular (tag kabul edilmez, hata varsa durur), sonra `docker compose -f compose.yaml -f deploy/compose.release.yaml ...` kullanır. Sıra, migration politikası (expand/contract), geri alma ve ileri düzeltme kararı: [sürüm yükseltme ve geri alma](docs/runbooks/surum-yukseltme-ve-geri-alma.md).

**Aylık SBOM ve zafiyet taraması** (`.github/workflows/supply-chain-scan.yml`, [ADR-0030](docs/adr/0030-sbom-ve-zafiyet-taramasi.md)): her ayın 1'inde, elle ve pom, `compose.yaml`, `Dockerfile` ya da sürüm tablosu değişince Java derlemesinin ve compose'taki her imajın CycloneDX SBOM'unu üretir (artifact olarak saklanır), Grype ile tarar; CRITICAL ve düzeltmesi olan HIGH bulgusu işi kırar. Kabul edilmiş riskler [`.github/vuln-allowlist.yaml`](.github/vuln-allowlist.yaml) dosyasında gerekçe ve son kullanma tarihiyle durur. Şimdilik zorunlu kontrol değildir. Adımlar: [aylık güvenlik taraması](docs/runbooks/aylik-guvenlik-taramasi.md).

## Geliştirme

Gereksinimler: JDK 25, Docker (testler gerçek PostgreSQL'e karşı Testcontainers ile koşar), Node 24 (script testleri için), gitleaks 8.24.3 (pre-commit için).

```bash
./mvnw -B -ntp verify
```

```bash
GITLEAKS=<gitleaks ikilisi> node --test scripts/flyway-immutability.test.js scripts/config-lint.test.js scripts/gitleaks-check.test.js scripts/pre-commit.test.js scripts/review-gate.test.js scripts/repo-hygiene.test.js scripts/keycloak-start.test.js scripts/ollama-pull.test.js scripts/migration-contract.test.js scripts/verify-release.test.js scripts/panel.test.mjs
```

IDE'den `local` profille çalıştırmak için PostgreSQL'i `127.0.0.1:5432`'ye açan katman. Port `VERSO_DB_LOCAL_PORT` ile değişir. IDE'nin çalışma dizini depo kökü olmalı; parolalar `secrets/`'tan okunur.

```bash
docker compose -f compose.yaml -f deploy/compose.local.yaml up -d postgres
```

Klon başına bir kez: commit öncesinde CI ile aynı kontroller (immutability, config-lint, gitleaks) çalışır.

```bash
git config core.hooksPath .githooks
```

## Yapı ve kurallar

- Mimari referans: [`docs/architecture-reference.md`](docs/architecture-reference.md). Tek otoritedir.
- AI ajanları ve katkıcılar için kurallar: [`AGENTS.md`](AGENTS.md) ve [`docs/ai/`](docs/ai/).
- Referanstan bilinçli sapmalar: [ADR-0007](docs/adr/0007-blueprint-uyarlamalari.md).
- LLM/RAG'e özgü kurallar: [`docs/ai/llm-rules.md`](docs/ai/llm-rules.md).
- Referansın kendisine geri bildirim: [`docs/reference-feedback.md`](docs/reference-feedback.md).
