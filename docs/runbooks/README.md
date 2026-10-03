# Runbook'lar

Her alarmın (`deploy/obs/prometheus/alerts.yml`) bir runbook'u vardır; alarmın `runbook` alanı buraya işaret eder (ADR-0014, referans 8.7). Gözlem yığını `obs` profiliyle açılır:

```bash
docker compose --profile obs up -d --wait
```

- **Grafana:** `http://127.0.0.1:3000` (önündeki `obs-edge` proxy'si üzerinden; Grafana'nın kendisinin internete çıkışı yoktur). Kullanıcı `admin`, parola `secrets/SECRET_GRAFANA_ADMIN_PASSWORD`. Parola yalnız ilk açılışta uygulanır; sonradan değiştirmek için `docker compose exec grafana grafana cli admin reset-admin-password` gerekir. "Verso — genel bakış" panosu.
- **Prometheus ve Alertmanager:** yayınlanmaz. Grafana'nın Explore ekranından ya da `docker compose exec prometheus wget -qO- ...` ile sorgulanır.

| Alarm | Önem | Runbook |
|---|---|---|
| `VersoDown` | page | [verso-down.md](verso-down.md) |
| `VersoHighErrorRate` | page | [high-error-rate.md](high-error-rate.md) |
| `VersoDatabaseUnavailable` | page | [database-unavailable.md](database-unavailable.md) |
| `ModelUnavailable` | page | [model-unavailable.md](model-unavailable.md) |
| `IngestionBacklog` | ticket | [ingestion-backlog.md](ingestion-backlog.md) |
| `QaSlow` | ticket | [qa-slow.md](qa-slow.md) |

- **page:** kullanıcıyı etkiler, hemen bakılır.
- **ticket:** bir sonraki iş günü bakılır.

**Bildirim kanalı:** henüz bağlı değil. `deploy/obs/alertmanager/alertmanager.yml` içindeki `default` alıcısına bir kanal eklenmeden (Slack, Telegram, e-posta ya da webhook) alarmlar yalnız Grafana ve Alertmanager arayüzlerinde görünür. Kanalın adresi ya da token'ı `/run/secrets` altından `*_file` ayarıyla okunur, YAML'a yazılmaz.

**Hangi log'lar toplanır:** yalnız `verso-app`, `migrate`, `edge` ve `backup`. Keycloak (başarısız girişlerde kullanıcı adı ve adres), PostgreSQL (hata satırları değer içerebilir), Ollama ve gözlem servisleri Loki'ye gönderilmez; onlar için `docker compose logs <servis>` kullanılır.

**Alarm testleri:** `deploy/obs/prometheus/alerts.test.yml` (promtool). Her alarm hem kendi kesintisinde çalar hem normal durumda susar; CI'da `scripts/obs-smoke.sh` koşar.

**Log'larda ne yok:** belge içeriği, soru, cevap ve dosya adı log'a yazılmaz (llm-rules 2.1). Teşhis id, süre, sayı ve sabit sonuç kelimeleriyle yapılır. Grafana'da: `{service="verso-app"} |= "outcome="`.
