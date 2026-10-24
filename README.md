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
  - **Bekleme:** geçen süre ve gerçekçi bir beklenti görünür. "Durdur"dan sonra odak soru kutusuna döner.
  - **Konuşma** ([ADR-0025](docs/adr/0025-gecmis-geri-bildirim-deger-panosu.md)): ardışık sorular sunucuda aynı konuşmaya yazılır; "Yeni konuşma" yenisini başlatır. Konuşma silinmiş ya da dolmuşsa soru kendiliğinden yeni konuşmada sorulur ve bir notla belirtilir.
  - **Geri bildirim:** her cevabın altında 👍/👎. 👎'den sonra küçük bir neden seçici açılır (Yanlış kaynak, Eksik ya da yanlış, Güncel değil, Diğer); "Yanlış kaynak"ta hangi `[n]` olduğu sorulur. Yeni puan eskisinin yerine geçer.
- **Geçmiş** (menüde "Soru sor"un altında, `g g`, hesap menüsü ya da soru ekranındaki düğme): kendi konuşmalarınız sağdan açılan bir panelde, son etkinliğe göre ve 20'şer. Yüklenen başlıklarda Türkçe harf duyarsız arama; yeniden adlandırma; tek konuşmayı silme (panelin kendi onay penceresi); "Tümünü sil" yalnız `SİL` yazılınca çalışır. Açılan konuşma soru ekranına salt okunur gelir (alıntı saklanmadığı için takip sorusu yok) ve sonraki soru onu sürdürür. Silinmiş belgeye atıf "Belge silinmiş" diye görünür. Konuşmalar kurulumun saklama süresi dolunca silinir (`VERSO_HISTORY_RETENTION_DAYS`, varsayılan 90 gün; panel süreyi API'den alamadığı için sayı yazmaz).
- **Değer panosu** (yalnız `/v1/info` `verso-admin` rolü verirse; sunucu diğerlerine 403 döner): 7 / 30 / 90 günlük aralık; soru, aktif kullanıcı, kaynaklı / bulunamadı / kaynak gösterilemedi oranları, p50 / p95 süre ve 👍 oranı kartları; günlük soru ve sonuç oranı çizgileri (üzerine gelince ya da ←/→ ile gün gün, tablo olarak da); mod dağılımı, 👎 nedenleri, en çok ve hiç atıf almamış belgeler. Belge adları kurulum açtıysa görünür, yoksa "Belge #…". Cevaplanamayan sorular yalnız kurulum açtıysa ve en az 3 kişi sorduysa listelenir. "Tahmini kazanılan süre" gösterilmez: API'de dürüstçe türetilecek bir veri yok.
- **Bilgi kaynakları:** sayfanın her yerine sürükle-bırak; dosya başına ilerleme ve "tekrar dene"; Türkçe harf duyarsız arama, durum filtresi ve sıralanabilir tablo; ayrıntı paneli; dosya adıyla silme onayı. Bilgi alanındaki belgeler alan adıyla rozet taşır ve yalnız yönetici siler; yönetici yükleme yerini ("Kişisel alanım" ya da bir bilgi alanı) seçer ve ayrıntı panelinden belgeyi taşır.
- **Bilgi alanları** (yalnız `verso-admin`, ADR-0028): alan oluşturma, gruba (tam yol) ya da kullanıcıya (`sub`) erişim verme ve kaldırma, boş alanı silme. Kaynak kartları belgenin alanını gösterir; geçmişte erişimi kalkan belge "Erişiminiz yok" olarak görünür. Altında planlanan bağlantılar (SharePoint ve dosya paylaşımları, talep yönetimi, ERP) "Planlandı" olarak görünür.
- **Kurulum durumu:** kurulumu yapan ekibin aşamaları (Keşif, Pilot, Yaygınlaştırma, Entegrasyonlar) ve sistemden okunan kontrol listesi. Aşama, pilot grubu ve kurum adı marka dosyasından gelir.
- **Sistem ve güvenlik:** mod ve KVKK açıklaması, üç koruma katmanı, modeller, oturum süresi, gözlem bağlantıları. Yalnız operatör rolüne görünür.

Gezinme: etiketli sol menü (ikon şeridine daraltılabilir), telefonda alt sekme çubuğu, **Ctrl+K / ⌘K** komut paleti (sayfa, eylem ve belge arar) ve `g` + harf kısayolları (`g s` soru, `g g` geçmiş, `g b` bilgi kaynakları, `g k` kurulum, `g d` değer panosu). Varsayılan tema açık ("Fildişi tuval"); koyu tema ("Obscur") ya da "sistemi izle" hesap menüsünden seçilir. Tasarım sistemi ve gerekçeleri: [ADR-0017](docs/adr/0017-panel-v3-tasarim-sistemi.md), [ADR-0018](docs/adr/0018-fildisi-tuval.md).

Panelin özellikleri:

- Bağımlılıksızdır; derleme adımı ve dış kaynak yoktur. İkonlar panelin kendi SVG'leri; başlık fontu Fraunces (OFL) panelin kendi sunucusundan gelir (`font-src 'self'`).
- Token yalnız bellekte durur. Açık konuşma da yalnız sekmenin belleğindedir; geçmiş sunucudan okunur, tarayıcı deposuna yazılmaz.
- Model çıktısı sayfaya yalnız metin olarak girer.
- Rol matrisi tek dosyadadır (`panel/js/roles.js`).
- Varsayılan portlarla çalışır (8080, 8180, 3000). Port değişirse `panel/js/config.js`, realm'deki `verso-panel` istemcisi ve `deploy/edge/nginx.conf`'taki CSP birlikte değişir.

Kararlar: [ADR-0015](docs/adr/0015-panel.md), [ADR-0017](docs/adr/0017-panel-v3-tasarim-sistemi.md), [ADR-0018](docs/adr/0018-fildisi-tuval.md), [ADR-0021](docs/adr/0021-kurulum-marka-dosyasi.md), [ADR-0024](docs/adr/0024-cevap-ekrani.md), [ADR-0025](docs/adr/0025-gecmis-geri-bildirim-deger-panosu.md). Belgeler hesaba aittir: `demo.sh`'ın yüklediği belgeler CI istemcisinin hesabındadır; panelde `samples/` PDF'lerini sürükleyip bırakın.

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

Windows'ta (Git Bash) yerel `curl.exe`, argümandaki Türkçe harfleri bozar; gövdeyi UTF-8 bir dosyadan verin: `--data-binary @soru.json` (`scripts/demo.sh` böyle yapar).

Sözleşmeler: [`docs/api-documents-integration-v1.md`](docs/api-documents-integration-v1.md), [`docs/api-questions-integration-v1.md`](docs/api-questions-integration-v1.md), [`docs/api-conversations-integration-v1.md`](docs/api-conversations-integration-v1.md) (geçmiş, geri bildirim, yönetici metrikleri), [`docs/api-audit-integration-v1.md`](docs/api-audit-integration-v1.md) (denetim kaydı: sorgu ve CSV dışa aktarım).

## Modlar ve KVKK md. 9

| | `local` (varsayılan) | `cloud` (isteğe bağlı) |
|---|---|---|
| Embedding | Yerel (Ollama, bge-m3) | Yerel (Ollama, bge-m3) |
| Chat | Yerel (Ollama, gemma4:e2b) | Anthropic ya da OpenAI uyumlu API |
| Host'tan çıkan | Hiçbir şey | Yalnız sistem kuralları, soru ve en fazla 5 pasaj |
| Kanıt | `scripts/prove-local-mode.sh` (CI'da koşar) | `X-Rag-Mode: cloud` her yanıtta |
| Açma | `docker compose up` | `deploy/compose.cloud.yaml` + anahtar dosyası |

- **Local mod üç katmanda zorlanır:**
  - Uygulama, veritabanı ve model sunucusu yalnız iç Docker ağlarındadır. API'ye bir kenar proxy üzerinden ulaşılır.
  - Uygulamanın HTTP istemcileri yalnız iç adreslere bağlanabilir.
  - Mod ile model ayarları tutarsızsa uygulama başlamaz.
- **Cloud modda** pasajlar (kişisel veri içerebilir) yurt dışındaki bir sağlayıcıya aktarılabilir. Bu modu açma kararı ve hukuki dayanağı veri sorumlusunundur.
- **Log'lar** belge içeriği, soru, cevap ya da dosya adı taşımaz; yalnız id, süre ve sayı taşır. Bu, test edilir ve gözlem yığınında uçtan uca kontrol edilir.
- Bu bölüm tasarım kararlarını anlatır; hukuki tavsiye değildir.

## Uyum paketi

Kurulum ekibinin her müşteriye teslim ettiği Türkçe KVKK şablonları [`docs/uyum/`](docs/uyum/README.md) altındadır ([ADR-0027](docs/adr/0027-yapay-zeka-bildirimi-ve-uyum-paketi.md)):

- VKED, çalışan aydınlatma metni, Kurumsal Üretken YZ Kullanım Politikası ve Veri İşleyen Sözleşmesi iskeleti.
- Egress kanıtı: `local` modun dışarı veri göndermediği, var olan kontrol ve betiklerle nasıl gösterilir.
- Sektör notları (banka, ödeme kuruluşu, SPK: yalnız `local`; kamu; 7545 olay bildirimi) ve Almanya eki (BetrVG § 87).
- Olgusal bölümler (neyin ne kadar saklandığı, silme yolları, k-anonimlik) kod ve ADR'lerle eşleşir; kuruma özgü alanlar `{{YER_TUTUCU}}` ile işaretlidir. Belgeler hukuki tavsiye değildir.

Panelde yapay zekâ ile yazışıldığı her zaman söylenir: soru kutusunun altındaki satır, ilk kullanımda kapatılabilir bir kart; üretilmiş cevap `data-ai-generated="true"` taşır ve Markdown dışa aktarımı "Yapay zekâ ile üretildi · <ürün adı>" satırıyla biter.

## Ölçümler

| Ölçüm | Sonuç | Kaynak |
|---|---|---|
| Retrieval: doğru sayfa ilk 5 pasajda (sürüm 2, 112 soru; hibrit arama, ADR-0022) | 0,982 (ilk sırada 0,795, MRR 0,877) | `eval/results/retrieval.json` |
| Cevaplanabilirlik kapısı (sürüm 2) | cevaplanabilir 112/112 modele gider, cevaplanamaz 7/28 modelsiz "bulunamadı" alır | aynı |
| Cevapta doğru belge ve sayfaya atıf (sürüm 2, GPU) | 0,911 | `eval/results/e2e-gemma4_e2b-gpu.json` |
| Cevapta beklenen bilgi (sürüm 2, GPU) | 0,884 | aynı |
| Belgelerde olmayan soruya "bulunamadı" (sürüm 2, 28 soru) | 0,607: kapı 7/7, model 10/21. Konusu belgede olan sorular en zayıf yer | aynı |
| Cevap süresi (sürüm 2, GPU) | p50 1,5 sn, p95 3,3 sn | aynı |
| Cevap süresi (CPU, 2 çekirdek; sürüm 1, 33 soru) | iki koşu: p50 28 / 21 sn, p95 45 / 37 sn | `eval/results/e2e-gemma4_e2b.json` |
| Belge listesi | 841 istek/sn, p95 49 ms | `docs/capacity.md` |
| Eşzamanlı soru | 1 (ikincisi `503` ve `Retry-After` ile "meşgul" alır) | `docs/capacity.md` |

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

Kararlar [`docs/decisions.md`](docs/decisions.md) içindedir (ADR-0001 – ADR-0030). Fazlar [`docs/roadmap.md`](docs/roadmap.md), her fazın kanıtı `docs/evidence/faz-N-dogrulama.md` dosyasındadır.

---

# Ayrıntılar

## Servis kimlik tablosu

| Servis | Port | `spring.application.name` | DB şeması / rol | Hata kodu bloğu | Main sınıf |
|---|---|---|---|---|---|
| verso-app | 8080 (API), 8081 (actuator, compose ağı içinde) | `verso` | `document` / `svc_document` (DML), `svc_document_migrate` (sahip, DDL); `qa` / `svc_qa`, `svc_qa_migrate` (ADR-0023); `audit` / `svc_audit` (yalnız INSERT ve SELECT), `svc_audit_migrate` (ADR-0026) | document 10000–10999, qa 11000–11999, audit 12000–12999 | `VersoApp` |

## Hata kodu blokları

Kodlar global olarak tekildir. `ErrorCodeUniquenessTest` bu tabloyu zorlar.

| Blok | Aralık | Not |
|---|---|---|
| document | 10000–10999 | belge yükleme, işleme, retrieval, bilgi alanları (10002–10004, 10040–10044; ADR-0028) |
| qa | 11000–11999 | soru-cevap, model çağrısı |
| audit | 12000–12999 | denetim kaydı sorgusu ve dışa aktarımı |
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
| `SECRET_DB_DOCUMENT_MIGRATE_PASSWORD` | migrate (Flyway, tek seferlik) | `spring.flyway.password` → `svc_document_migrate` |
| `SECRET_DB_DOCUMENT_PASSWORD` | verso-app | `spring.datasource.password` → `svc_document` |
| `SECRET_DB_BACKUP_PASSWORD` | backup | `verso_backup` (`pg_read_all_data`) |
| `SECRET_BACKUP_ENCRYPTION_KEY` | backup, restore provası | yedeklerin gpg parolası |
| `SECRET_DB_KEYCLOAK_PASSWORD` | postgres (init), keycloak | `keycloak` rolü ve veritabanı |
| `SECRET_KEYCLOAK_ADMIN_PASSWORD` | keycloak | master realm yöneticisi `admin` |
| `SECRET_KEYCLOAK_CI_CLIENT_SECRET` | keycloak, CI smoke | `verso-ci` istemcisi (client credentials) |
| `SECRET_KEYCLOAK_DEMO_USER_PASSWORD` | keycloak | `verso` realm'indeki `demo` kullanıcısı |

**Kimlik doğrulama** (ADR-0005, ADR-0010). API her istekte `Authorization: Bearer <access token>` ister; health uçları dışında token'sız istek `401` döner. Token'ı OIDC IdP verir: compose'ta demo Keycloak `http://127.0.0.1:8180` (realm `verso`), üretimde kurumun kendi IdP'si (`OIDC_ISSUER`, `OIDC_JWK_SET_URI`). Kabul edilen token: ES256 imzalı, `typ: at+jwt`, `iss` birebir, `aud` içinde `verso-api`, süreli. Hesap kimliği token'ın `sub`'ıdır. Roller ve gruplar da token'dan okunur (ADR-0019): rolü olmayan kullanıcı `verso-user` sayılır, `verso-admin` yalnız IdP bu rolü verirse. Claim yolları `OIDC_ROLES_CLAIM`, `OIDC_CLIENT_ROLES_CLAIM` ve `OIDC_GROUPS_CLAIM` ile ayarlanır; varsayılanlar Keycloak'ındır, Entra ID için `roles` ve `groups`.

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

**Bilgi alanları olan bir sürüme yükseltme (ADR-0028).** Yeni şema, secret ya da ortam değişkeni yok. Adımlar:

1. Önce yedek al: `docker compose run --rm backup once`.
2. `docker compose up -d --build --wait`. `migrate` container'ı `V4__collections.sql`'i uygular: tablolar eklenir, var olan her belgeye tek okuyucu satırı (`user:<sahip>`) yazılır, chunk tablosu yeniden yazılmaz. Var olan belgeler kişisel kalır, davranış değişmez.
3. Gruplar token'da olmalı. Paketteki realm'de `verso-panel` ve `verso-cli` istemcilerinin `groups` mapper'ı vardır (ADR-0019). Eski bir yığın realm'i yeniden içe aktarmadıysa `bash scripts/keycloak-reimport.sh`. Entra'da `groupMembershipClaims` ve `OIDC_GROUPS_CLAIM=groups`.
4. Yönetici panelde "Bilgi alanları"nı açar, alanı oluşturur, grup yolunu (`/hukuk`) yazarak erişim verir, sonra "Bilgi kaynakları"nda yükleme yeri olarak alanı seçer.

Grup üyeliği değişikliği kullanıcının bir sonraki oturum anahtarında (en çok 300 sn) geçerli olur. Alandan kaldırılan erişim sonraki istekte geçerlidir. Keycloak'ta grubu yeniden adlandırmak o yola verilmiş erişimi düşürür; yeni yolla yeniden verin. Geri dönmeden önce paylaşımlı belgeleri kişisel alana taşıyın ya da silin: eski sürüm onları yükleyenin belgesi sayar.

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
- **Bağlantı bütçesi:** uygulama havuzu 10 + `qa` havuzu 4 + `audit` havuzu 4 + Flyway 2 (yalnız migrate anında) + yedek 2 = 22; `max_connections` 100 (ADR-0023, ADR-0026).
- **Kapsam:** yalnız Verso veritabanı. Demo Keycloak'un `keycloak` veritabanı yedeklenmez; realm dosyadan yeniden kurulur (ADR-0010).
- **Bilinen sınır:** yedek aynı host'taki volume'dedir. Host dışına kopyalama üretim kurulumunun işidir (ADR-0009).
- **`docker compose down -v`** veritabanıyla birlikte `backups` volume'ünü de siler; önce yedekleri kopyala.
- **Parola ve anahtar rotasyonu:** [`secrets/README.md`](secrets/README.md). Init script'leri yalnız ilk kurulumda çalışır.
- **Backup servisi** son başarılı yedek iki aralıktan eskiyse `unhealthy` görünür (`docker compose ps`).

**İmaj yayını** (`.github/workflows/release.yml`): `v*` etiketi önce o commit'te build ve testleri koşar, sonra uygulama imajını bir kez build edip SBOM ve provenance ile `ghcr.io/<owner>/verso` adresine iter. Digest cosign ile anahtarsız imzalanır, CycloneDX SBOM'u atestasyon olarak eklenir ve ikisi doğrulandıktan sonra sürüm etiketi, geri çekilip kontrol edilen digest'e konur. İmaj yalnız uygulamayı içerir: compose dosyaları, kenar proxy ayarı, panel, realm ve init script'leri depodan bağlanır. Yani imajdan kurulum için de depo checkout'u gerekir.

**Aylık SBOM ve zafiyet taraması** (`.github/workflows/supply-chain-scan.yml`, [ADR-0030](docs/adr/0030-sbom-ve-zafiyet-taramasi.md)): her ayın 1'inde, elle ve pom, `compose.yaml`, `Dockerfile` ya da sürüm tablosu değişince Java derlemesinin ve compose'taki her imajın CycloneDX SBOM'unu üretir (artifact olarak saklanır), Grype ile tarar; CRITICAL ve düzeltmesi olan HIGH bulgusu işi kırar. Kabul edilmiş riskler [`.github/vuln-allowlist.yaml`](.github/vuln-allowlist.yaml) dosyasında gerekçe ve son kullanma tarihiyle durur. Şimdilik zorunlu kontrol değildir. Adımlar: [aylık güvenlik taraması](docs/runbooks/aylik-guvenlik-taramasi.md).

## Geliştirme

Gereksinimler: JDK 25, Docker (testler gerçek PostgreSQL'e karşı Testcontainers ile koşar), Node 24 (script testleri için), gitleaks 8.24.3 (pre-commit için).

```bash
./mvnw -B -ntp verify
```

```bash
GITLEAKS=<gitleaks ikilisi> node --test scripts/flyway-immutability.test.js scripts/config-lint.test.js scripts/gitleaks-check.test.js scripts/pre-commit.test.js scripts/review-gate.test.js scripts/repo-hygiene.test.js scripts/keycloak-start.test.js scripts/ollama-pull.test.js scripts/panel.test.mjs
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
