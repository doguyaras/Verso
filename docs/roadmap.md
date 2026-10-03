# Yol haritası

Fazlar sırayla yapılır. Her faz kendi dalında geliştirilir, review'lardan ve CI'dan geçer, kanıtı `docs/evidence/faz-N-dogrulama.md`'ye yazılır ve kullanıcı onayıyla bir sonrakine geçilir. Kararlar `docs/decisions.md`'de.

| Faz | Kapsam | Durum |
|---|---|---|
| 1 | İskelet: modüler monolit, platform starter'ları (hata zarfı, trace id, log sanitizer), mimari testler, yönetişim (AGENTS.md, skill'ler, hook'lar), CI | Bitti (PR #1–#3) |
| 2 | Veri altyapısı: PostgreSQL 18 + pgvector, modül başına iki rol, Flyway (tek seferlik migrate), compose, Dockerfile, şifreli yedek ve otomatik restore provası (ADR-0009) | Bitti (PR #4) |
| 3 | Kimlik: Keycloak (OIDC), resource server, JWT doğrulama, `@CurrentAccount`, güvenlik filtreleri, management portu (ADR-0005, ADR-0010) | Bitti (PR #5) |
| 4 | Belge alımı: PDF yükleme, sayfa korumalı ayrıştırma, chunking, embedding (bge-m3), sahiplik (ADR-0011) | Bitti (PR #6) |
| 5 | Soru-cevap (local): retrieval, Ollama sohbet modeli, atıflar, `X-Rag-Mode` (ADR-0012) | Bitti (PR #7) |
| 6 | Cloud modu: Anthropic (varsayılan) ve OpenAI uyumlu sağlayıcı, egress kuralları (ADR-0006, ADR-0013) | Bitti (PR #8) |
| 7 | Gözlem: Alloy → Loki, Prometheus + Alertmanager, Grafana; alarmlar ve runbook (ADR-0014) | Devam ediyor |
| 8 | Ölçüm: Türkçe eval seti, yük testi, kapasite | Planlı |
| 9 | Teslim: README (Türkçe + İngilizce özet, KVKK md. 9 açıklaması, ≤5 komutla kurulum, curl örnekleri), `samples/` sentetik PDF'ler ve demo script'i, image yayını | Planlı |
| 10 | **Yönetim paneli** (backoffice): kullanıcının "plate app" projesindeki panel tarzında. Referans 17: React/TS, token yalnız bellekte, rol matrisi tek dosyada. Başlamadan önce kullanıcıdan plate app paneli (ekranlar, yığın) öğrenilir. ADR-0007 #10'daki "panel yok" kararı bu fazda kalkar | Planlı (en son; kullanıcı isteği 2026-10-02) |
