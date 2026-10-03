# Verso

> **Private document Q&A with source citations. Spring AI + local LLMs.**

Verso answers questions about your own PDF documents in Turkish and cites every answer with document name and page. In `local` mode the embedding and chat models run on Ollama inside your infrastructure and nothing of Verso can reach the internet: the application, its database and the model server sit on internal networks only, and a script proves it on the running stack. `cloud` mode swaps only the chat model for Anthropic or an OpenAI-compatible API, by configuration; documents, full texts and vectors stay home. Every response says which mode answered (`X-Rag-Mode`).

**English summary**

- **Stack:** Java 25, Spring Boot 4.1, Spring AI 2.0, PostgreSQL 18 + pgvector, Flyway, Ollama (bge-m3, gemma4:e2b), Keycloak (OIDC), an nginx edge proxy, Docker Compose. Optional: Prometheus, Alertmanager, Loki, Alloy, Grafana.
- **Answers:**
  - Retrieval with the ownership filter inside the vector query.
  - No model call below a measured similarity threshold.
  - A fenced prompt, and citations mapped on the server.
- **Turkish eval set** (33 questions, synthetic documents), gemma4:e2b on CPU:
  - retrieval recall@5 1.00;
  - citation hit rate 1.00;
  - correct fact in the answer 0.96;
  - "not found" accuracy 1.00;
  - median answer time 28 s.
- **Engineering:**
  - A modular monolith with machine-checked architecture rules.
  - About 350 tests (the database ones on a real PostgreSQL), and 200 mutation checks that prove the tests catch what they claim to.
  - An ADR for every decision.
  - Encrypted backups with an automated restore drill in CI.
- **Run it:** the four commands below; the last one is the demo.
- **Disclaimer:** this is a portfolio project. The KVKK notes describe design choices; they are not legal advice.

## Ne yapar

Kurumlar yapay zekâ ile belgelerine soru sormak istiyor, ama belgeleri, soruları ve cevapları kendi altyapılarının dışına çıkarmak istemiyor. Bunun ardında KVKK md. 9 kapsamındaki yurt dışına aktarım soruları ve kurum politikaları var. Verso, bunun mevcut Java sistemlerine veri dışarı çıkmadan eklenebileceğini gösterir:

- PDF yüklersin; Verso sayfa sayfa okur ve yerel bir embedding modeliyle indeksler. Orijinal dosya işlendikten sonra silinir.
- Türkçe soru sorarsın; cevap yalnız senin belgelerinden gelir ve her bilginin yanında kaynağı (belge, sayfa) durur.
- Belgelerde cevap yoksa "bulunamadı" der; uydurmaz.
- Her hesap yalnız kendi belgelerini görür; sahiplik sorgunun içinde uygulanır.

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

Demo, `samples/` altındaki sentetik belgeleri (kurgusal bir şirketin altı yönetmeliği) yükler. Ardından dört soru sorar: üçünün cevabı belgelerdedir, biri belgelerde yoktur. Her cevabın kaynakları ve modu gösterilir. Gerçek bir koşudan (2026-10-03, CPU):

```text
Soru: Şirket laptopu kaybolursa ne kadar süre içinde bildirmem gerekir?
  Cevap: Şirkete ait dizüstü bilgisayar, telefon veya erişim kartı kaybolduğunda ya da çalındığında, çalışan durumu en geç iki saat içinde Bilgi Güvenliği ekibine bildirir [1].
  [1] bilgi-guvenligi.pdf, sayfa 2
  (kaynaklı, local / gemma4:e2b)

Soru: Şirketin borsa kodu nedir?
  Cevap: Belgelerde bu sorunun cevabı bulunamadı.
  (kaynak yok, local / gemma4:e2b)
```

CPU'da bir cevap 20–60 sn sürer. Küçük yerel model kaynağı doğru gösterse de bazen bilgiyi yanlış okur. Aynı koşuda "beş yıldan az hizmeti olan" sorusuna doğru sayfayı göstererek "yirmi gün" dedi; belgede "on dört gün" yazıyor. Eval setinde bu tür hata oranı %4'tür. Atıflar kullanıcının cevabı kaynağından kontrol etmesi içindir. Ölçümler: [`docs/capacity.md`](docs/capacity.md), [`eval/README.md`](eval/README.md).

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

Sözleşmeler: [`docs/api-documents-integration-v1.md`](docs/api-documents-integration-v1.md), [`docs/api-questions-integration-v1.md`](docs/api-questions-integration-v1.md).

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

## Ölçümler

| Ölçüm | Sonuç | Kaynak |
|---|---|---|
| Retrieval: doğru sayfa ilk 5 pasajda | 1,00 (ilk sırada 0,88) | `eval/results/retrieval.json` |
| Cevapta doğru belge ve sayfaya atıf | 1,00 | `eval/results/e2e-gemma4_e2b.json` |
| Cevapta beklenen bilgi | 0,96 | aynı |
| Belgelerde olmayan soruya "bulunamadı" | 1,00 | aynı |
| Cevap süresi (CPU, 2 çekirdek) | p50 28 sn, p95 45 sn | aynı |
| Belge listesi | 841 istek/sn, p95 49 ms | `docs/capacity.md` |
| Eşzamanlı soru | 1 (ikincisi `503` ve `Retry-After` ile "meşgul" alır) | `docs/capacity.md` |

Set küçüktür (33 soru, 6 belge). Sonuçlar yön gösterir, istatistiksel güvence vermez. Komutlar: [`eval/README.md`](eval/README.md).

## Mimari

```text
 tarayıcı / istemci ──► edge (nginx, 127.0.0.1:8080) ──► verso-app (Spring Boot, modüler monolit)
                                                              │  document modülü: yükleme, PDF, worker, embedding, arama
                                                              │  qa modülü: eşik, prompt, chat, atıf
                          keycloak (OIDC, 127.0.0.1:8180) ◄────┤  platform: hata zarfı, güvenlik, log temizleyici
                                                              ├──► postgres 18 + pgvector (iç ağ)
                                                              └──► ollama: bge-m3 + gemma4:e2b (iç ağ, internetsiz)
 isteğe bağlı: prometheus · alertmanager · loki ◄ alloy · grafana (obs profili) · yedek ve restore provası
```

Kararlar [`docs/decisions.md`](docs/decisions.md) içindedir (ADR-0001 – ADR-0014). Fazlar [`docs/roadmap.md`](docs/roadmap.md), her fazın kanıtı `docs/evidence/faz-N-dogrulama.md` dosyasındadır.

---

# Ayrıntılar

## Servis kimlik tablosu

| Servis | Port | `spring.application.name` | DB şeması / rol | Hata kodu bloğu | Main sınıf |
|---|---|---|---|---|---|
| verso-app | 8080 (API), 8081 (actuator, compose ağı içinde) | `verso` | `document` / `svc_document` (DML), `svc_document_migrate` (sahip, DDL) | document 10000–10999, qa 11000–11999 | `VersoApp` |

## Hata kodu blokları

Kodlar global olarak tekildir. `ErrorCodeUniquenessTest` bu tabloyu zorlar.

| Blok | Aralık | Not |
|---|---|---|
| document | 10000–10999 | belge yükleme, işleme, retrieval |
| qa | 11000–11999 | soru-cevap, model çağrısı |
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

**Kimlik doğrulama** (ADR-0005, ADR-0010). API her istekte `Authorization: Bearer <access token>` ister; health uçları dışında token'sız istek `401` döner. Token'ı OIDC IdP verir: compose'ta demo Keycloak `http://127.0.0.1:8180` (realm `verso`), üretimde kurumun kendi IdP'si (`OIDC_ISSUER`, `OIDC_JWK_SET_URI`). Kabul edilen token: ES256 imzalı, `typ: at+jwt`, `iss` birebir, `aud` içinde `verso-api`, süreli. Hesap kimliği token'ın `sub`'ıdır.

Demo kullanıcıyla token (device flow): betik bir bağlantı yazar, tarayıcıda `demo` kullanıcısıyla (parola `secrets/SECRET_KEYCLOAK_DEMO_USER_PASSWORD`) giriş yapınca token'ı verir.

```bash
TOKEN="$(bash scripts/demo-token.sh)"
```

Token'sız istek `401` döner. Kimlik zincirinin uçtan uca kontrolü (CI'da da çalışır):

```bash
bash scripts/auth-smoke.sh
```

**Belgeler** (ADR-0011; istemci sözleşmesi: [`docs/api-documents-integration-v1.md`](docs/api-documents-integration-v1.md)). PDF yükle; belge `PENDING` olarak döner, worker sayfa sayfa okuyup yerel bge-m3 modeliyle vektörleştirir ve birkaç saniye içinde `READY` olur. Orijinal PDF işlendikten sonra silinir; sayfa metinleri ve vektörler kalır.

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

**Kaynaklar.**

- **Bellek:** container sınırlarının toplamı yaklaşık 10,6 GB'dır (uygulama 1,5 GB, kenar proxy 64 MB, Ollama 6 GB (embedding ve chat modeli birlikte yüklü), PostgreSQL 1 GB, Keycloak 1 GB, yedek 512 MB, tek seferlik migrate ve model indirme 512'şer MB). Host'ta en az 12 GB boş RAM önerilir.
- **Disk ve ağ:** ilk açılış yaklaşık 8,5 GB indirir (Ollama imajı ~3,8 GB, bge-m3 modeli 1,2 GB, gemma4:e2b modeli 3,5 GB, nginx imajı 23 MB); buna Keycloak, PostgreSQL, Flyway ve Temurin imajları ile `--build` sırasında Maven bağımlılıkları eklenir. Sonraki açılışlar internetsiz çalışır; model volume'de kalır ve çalışan Ollama'nın internete çıkışı yoktur.
- **Embedding:** CPU'da yapılır; Ollama 2 CPU ile sınırlıdır (`OLLAMA_CPUS`). Portları `.env` ile değiştirdiysen (`VERSO_HTTP_PORT`, `VERSO_KEYCLOAK_PORT`) betikler için de `export` et: `scripts/*.sh` `.env`'i okumaz.

**Yedek ve geri yükleme.** `backup` servisi şifreli `pg_dump` alır: varsayılan günde bir, 7 gün saklanır ve en yeni yedek hiç silinmez. Prova, en yeni yedeği geçici bir veritabanına geri yükler; satır sayılarını, yetkileri ve Flyway `validate`'i doğrular. CI bunu haftalık çalıştırır.

```bash
docker compose run --rm backup once
```

```bash
bash scripts/restore-drill.sh
```

- **RPO:** yedek aralığı (varsayılan 24 saat, `BACKUP_INTERVAL_SECONDS`).
- **RTO hedefi:** 1 saat.
- **Bağlantı bütçesi:** uygulama havuzu 10 + Flyway 1 + yedek 2 = 13; `max_connections` 100.
- **Kapsam:** yalnız Verso veritabanı. Demo Keycloak'un `keycloak` veritabanı yedeklenmez; realm dosyadan yeniden kurulur (ADR-0010).
- **Bilinen sınır:** yedek aynı host'taki volume'dedir. Host dışına kopyalama üretim kurulumunun işidir (ADR-0009).
- **`docker compose down -v`** veritabanıyla birlikte `backups` volume'ünü de siler; önce yedekleri kopyala.
- **Parola ve anahtar rotasyonu:** [`secrets/README.md`](secrets/README.md). Init script'leri yalnız ilk kurulumda çalışır.
- **Backup servisi** son başarılı yedek iki aralıktan eskiyse `unhealthy` görünür (`docker compose ps`).

**İmaj yayını** (`.github/workflows/release.yml`): `v*` etiketi önce o commit'te build ve testleri koşar, sonra uygulama imajını bir kez build edip SBOM ve provenance ile `ghcr.io/<owner>/verso` adresine iter. Sürüm etiketi, geri çekilip kontrol edilen digest'e konur. İmaj yalnız uygulamayı içerir: compose dosyaları, kenar proxy ayarı, panel, realm ve init script'leri depodan bağlanır. Yani imajdan kurulum için de depo checkout'u gerekir.

## Geliştirme

Gereksinimler: JDK 25, Docker (testler gerçek PostgreSQL'e karşı Testcontainers ile koşar), Node 24 (script testleri için), gitleaks 8.24.3 (pre-commit için).

```bash
./mvnw -B -ntp verify
```

```bash
GITLEAKS=<gitleaks ikilisi> node --test scripts/flyway-immutability.test.js scripts/config-lint.test.js scripts/gitleaks-check.test.js scripts/pre-commit.test.js scripts/review-gate.test.js scripts/repo-hygiene.test.js scripts/keycloak-start.test.js scripts/ollama-pull.test.js
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
