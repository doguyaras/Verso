# Verso

> **Private document Q&A with source citations. Spring AI + local LLMs.**
>
> Verso answers questions about your PDF documents in Turkish and cites every answer with document name and page. In `local` mode the embedding and chat models run on Ollama inside your own infrastructure and the application makes no outbound connections. `cloud` mode swaps only the chat model for an external LLM API, behind the same Spring AI interface, through configuration alone. Every response carries `X-Rag-Mode` so a demo can prove which mode answered. Built with Java 25, Spring Boot 4.1, Spring AI 2.0, PostgreSQL 18 + pgvector, following a strict architecture reference with machine-enforced rules.

**Durum:** Faz 1 / 9: iskelet, yönetişim ve mimari testler. Uygulama henüz belge almıyor; ingestion faz 4'te, soru-cevap faz 5'te gelir. Fazlar ve kararlar: [`docs/decisions.md`](docs/decisions.md).

## Neden

Kurumsal müşteriler yapay zekâ özelliği istiyor; ama belgeleri, soruları ve cevapları kendi altyapılarının dışına çıkarmak istemiyorlar. Bunun ardında hem KVKK md. 9 kapsamındaki yurt dışına aktarım soruları hem de kurum politikaları var. Verso, bunun mevcut Java sistemlerine veri dışarı çıkmadan eklenebileceğini gösterir.

Ayrıntılı açıklama, kurulum ve demo faz 9'da bu dosyaya eklenecek. Bu metin hukuki tavsiye değildir.

## Servis kimlik tablosu

| Servis | Port | `spring.application.name` | DB şeması / rol | Hata kodu bloğu | Main sınıf |
|---|---|---|---|---|---|
| verso-app | 8080 (API), 8081 (actuator) | `verso` | (faz 2) `document` / `svc_document`, `svc_document_migrate` | document 10000–10999, qa 11000–11999 | `VersoApp` |

## Hata kodu blokları

Kodlar global olarak tekildir. `ErrorCodeUniquenessTest` bu tabloyu zorlar.

| Blok | Aralık | Not |
|---|---|---|
| document | 10000–10999 | belge yükleme, işleme, retrieval |
| qa | 11000–11999 | soru-cevap, model çağrısı |
| validation | 90000–90099 | ortak: bean validation, binding, 404/405/415, API sürümü |
| security | 90100–90199 | ortak: kimlik ve yetki (faz 3) |
| system | 99998–99999 | ortak: upstream, beklenmeyen |

Hata yanıtı her zaman aynı zarftadır (ADR-0004):

```json
{ "ok": false, "data": null,
  "error": { "code": 90010, "message": "Resource not found.", "service": "validation",
             "path": "/v1/...", "timestamp": 1790935200000, "traceId": "…", "details": [] } }
```

## Geliştirme

Gereksinimler: JDK 25, Node 24 (script testleri için), gitleaks 8.24.3 (pre-commit için), Docker (faz 2'den itibaren).

```bash
./mvnw -B -ntp verify
```

```bash
node --test scripts/flyway-immutability.test.js scripts/config-lint.test.js
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
