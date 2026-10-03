# VersoHighErrorRate

**Anlamı:** Son 10 dakikada API isteklerinin %5'inden fazlası 5xx döndü.

**Kontrol:**

1. Hangi uç ve hangi kod? Grafana → "İstek hızı" ve "API p95" panoları. Log'da: `{service="verso-app", level="ERROR"}` ve `{service="verso-app"} |= "status=503"`.
2. Hata koduna göre:

| Kod | Anlamı | Bak |
|---|---|---|
| 11001 / 10030 | Chat ya da embedding modeli yok | [model-unavailable.md](model-unavailable.md) |
| 11002 | Chat slotları dolu (yük) | [qa-slow.md](qa-slow.md); `verso.qa.chat-concurrency` |
| 10014 | Eşzamanlı yükleme sınırı | Yük kısa sürelidir; sürekliyse kapasite (faz 8) |
| 99997 | Veritabanı geçici olarak yok | `docker compose ps postgres`, bağlantı havuzu (`hikaricp_connections_active`) |
| 90103 | Kimlik sağlayıcısı yok | `docker compose ps keycloak` |
| 99999 | Beklenmeyen hata | Log'daki `exceptionType` ve `traceId`; hata kaydı aç |

**Kapanış:** 5xx oranı %5'in altına iner.
