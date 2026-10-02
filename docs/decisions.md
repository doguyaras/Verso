# Kararlar (ADR indeksi)

Mimari kararların her biri `docs/adr/NNNN-<baslik>.md` dosyasında, referans şablonuyla (`docs/adr/0000-template.md`) yazılır (referans Bölüm 20). Bu dosya yalnız indekstir; karar metni burada tekrar edilmez.

| ADR | Karar | Durum |
|---|---|---|
| [0001](adr/0001-mimari-sekil-ve-profil.md) | Şekil A (modüler monolit, Spring Modulith) + P0 profili | Kabul edildi |
| [0002](adr/0002-veri-stratejisi.md) | PostgreSQL 18 + pgvector; modül başına şema ve iki rol | Kabul edildi |
| [0003](adr/0003-mesajlasma.md) | Broker yok; ingestion DB'den claim edilen iş; modüller arası çağrı `*-api` ile | Kabul edildi |
| [0004](adr/0004-api-sozlesmesi.md) | `/v1` + `API-Version`; hatalar zarflı, başarı ham; hata kodu blokları; yönetim portu 8081 | Kabul edildi |
| [0005](adr/0005-kimlik-dogrulama.md) | Harici OIDC (Keycloak), resource server, belge sahipliği | Kabul edildi (faz 3) |
| [0006](adr/0006-ai-calisma-modu.md) | `verso.ai.mode` local/cloud; embedding her zaman yerel; varsayılan cloud Anthropic | Kabul edildi (faz 4–6) |
| [0007](adr/0007-blueprint-uyarlamalari.md) | Blueprint ve iskelet örneğinden bilinçli uyarlamalar | Kabul edildi |
| [0008](adr/0008-soru-cevap-sicak-yolu.md) | Soru-cevap sıcak yolunda iki uzak senkron çağrı ve bütçeleri | Kabul edildi |
