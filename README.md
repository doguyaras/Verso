# Verso

> **Private document Q&A with source citations. Spring AI + local LLMs.**
>
> Verso answers questions about your PDF documents in Turkish and cites every answer with document name and page. In `local` mode the embedding and chat models run on Ollama inside your own infrastructure and the application makes no outbound connections. `cloud` mode swaps only the chat model for an external LLM API, behind the same Spring AI interface, through configuration alone. Every response carries `X-Rag-Mode` so a demo can prove which mode answered. Built with Java 25, Spring Boot 4.1, Spring AI 2.0, PostgreSQL 18 + pgvector, following a strict architecture reference with machine-enforced rules.

**Durum:** Faz 4 / 10: belge alımı (PDF yükleme, sayfa sayfa ayrıştırma, chunking, yerel bge-m3 embedding; ADR-0011). Faz 3 kimliği ekledi (OIDC resource server, compose'ta demo Keycloak). Önceki faz veri altyapısını kurdu (PostgreSQL 18 + pgvector, roller, Flyway, şifreli yedek ve otomatik restore provası). Soru-cevap faz 5'te gelir. Fazlar: [`docs/roadmap.md`](docs/roadmap.md); kararlar: [`docs/decisions.md`](docs/decisions.md).

## Neden

Kurumsal müşteriler yapay zekâ özelliği istiyor; ama belgeleri, soruları ve cevapları kendi altyapılarının dışına çıkarmak istemiyorlar. Bunun ardında hem KVKK md. 9 kapsamındaki yurt dışına aktarım soruları hem de kurum politikaları var. Verso, bunun mevcut Java sistemlerine veri dışarı çıkmadan eklenebileceğini gösterir.

Ayrıntılı açıklama, kurulum ve demo faz 9'da bu dosyaya eklenecek. Bu metin hukuki tavsiye değildir.

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

`DELETE /v1/documents/{id}` belgeyi tüm türevleriyle siler. Uçtan uca kontrol (gerçek model, CI'da da çalışır):

```bash
bash scripts/ingest-smoke.sh
```

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

- **Bellek:** container sınırlarının toplamı yaklaşık 7,5 GB'dır (uygulama 1,5 GB, Ollama 3 GB, PostgreSQL 1 GB, Keycloak 1 GB, yedek 512 MB, tek seferlik migrate ve model indirme 512'şer MB). Host'ta en az 8 GB boş RAM önerilir.
- **Disk ve ağ:** ilk açılış yaklaşık 5 GB indirir (Ollama imajı ~3,8 GB, bge-m3 modeli 1,2 GB). Sonraki açılışlar internetsiz çalışır; model volume'de kalır ve çalışan Ollama'nın internete çıkışı yoktur.
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

## Geliştirme

Gereksinimler: JDK 25, Docker (testler gerçek PostgreSQL'e karşı Testcontainers ile koşar), Node 24 (script testleri için), gitleaks 8.24.3 (pre-commit için).

```bash
./mvnw -B -ntp verify
```

```bash
node --test scripts/flyway-immutability.test.js scripts/config-lint.test.js scripts/review-gate.test.js scripts/repo-hygiene.test.js scripts/keycloak-start.test.js scripts/ollama-pull.test.js
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
