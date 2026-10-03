# Kararlar (ADR indeksi)

Mimari kararların her biri `docs/adr/NNNN-<baslik>.md` dosyasında, referans şablonuyla (`docs/adr/0000-template.md`) yazılır (referans Bölüm 20). Bu dosya yalnız indekstir; karar metni burada tekrar edilmez.

| ADR | Karar | Durum |
|---|---|---|
| [0001](adr/0001-mimari-sekil-ve-profil.md) | Şekil A (modüler monolit, Spring Modulith) + P0 profili | Kabul edildi |
| [0002](adr/0002-veri-stratejisi.md) | PostgreSQL 18 + pgvector; modül başına şema ve iki rol | Kabul edildi |
| [0003](adr/0003-mesajlasma.md) | Broker yok; ingestion DB'den claim edilen iş; modüller arası çağrı `*-api` ile | Kabul edildi |
| [0004](adr/0004-api-sozlesmesi.md) | `/v1` + `API-Version`; hatalar zarflı, başarı ham; hata kodu blokları; yönetim portu 8081 | Kabul edildi |
| [0005](adr/0005-kimlik-dogrulama.md) | Harici OIDC (Keycloak), resource server, belge sahipliği | Kabul edildi; uygulandı (faz 3) |
| [0006](adr/0006-ai-calisma-modu.md) | `verso.ai.mode` local/cloud; embedding her zaman yerel; varsayılan cloud Anthropic | Kabul edildi (faz 4–6) |
| [0007](adr/0007-blueprint-uyarlamalari.md) | Blueprint ve iskelet örneğinden bilinçli uyarlamalar | Kabul edildi |
| [0008](adr/0008-soru-cevap-sicak-yolu.md) | Soru-cevap sıcak yolunda iki uzak senkron çağrı ve bütçeleri | Kabul edildi |
| [0009](adr/0009-veri-altyapisi.md) | Compose, roller ve GRANT sınırı, secret dosyaları, pg_dump + gpg yedek ve otomatik restore provası, çok aşamalı Dockerfile | Kabul edildi (faz 2) |
| [0010](adr/0010-kimlik-altyapisi.md) | Demo IdP (Keycloak prod modu, kendi veritabanı), token kuralları (ES256, `at+jwt`, `iss`/`aud`/`sub`), device flow + PKCE ve client credentials, zarflı 401/403/400 | Kabul edildi (faz 3) |
| [0011](adr/0011-belge-alimi.md) | Belge alımı: PDFBox 3.0.8, PDF işlenince silinir, DB claim'li worker, sayfa içi chunking, Ollama bge-m3 embedding, `/v1/documents` | Kabul edildi (faz 4) |
| [0012](adr/0012-soru-cevap.md) | Soru-cevap: `qa` modülü, sahiplik sorgunun içinde retrieval, eşik altında model çağrılmaz, ayraçlı prompt, sunucuda atıf, `gemma4:e2b`, `X-Rag-Mode` | Kabul edildi (faz 5) |
| [0013](adr/0013-cloud-modu-ve-egress.md) | Cloud modu: Anthropic / OpenAI uyumlu (Spring AI starter'ları), mod tutarlılık kontrolü, anahtar yalnız `/run/secrets`, uygulama yalnız iç ağlarda + kenar proxy (nginx), `InetAddressFilter`, `prove-local-mode.sh` | Kabul edildi (faz 6); yeni bileşenler onay bekliyor |
| [0014](adr/0014-gozlem.md) | Gözlem: `obs` profili (Prometheus, Alertmanager, Loki, Alloy, Grafana), Verso metrikleri, 5 temel alarm + runbook'lar, token'sız scrape yalnız management portunda | Kabul edildi (faz 7); image'lar ve bildirim kanalı onay bekliyor |
| [0015](adr/0015-panel.md) | Panel: bağımlılıksız tarayıcı uygulaması (`panel/`), edge'den `/panel/`, `verso-panel` istemcisi (code + PKCE), token yalnız bellekte, rol matrisi tek dosyada, `GET /v1/info` | Kabul edildi (faz 10); plate app benzerliği varsayımları onay bekliyor |
