# Runbook'lar

Her alarmın (`deploy/obs/prometheus/alerts.yml`) bir runbook'u vardır; alarmın `runbook` alanı buraya işaret eder (ADR-0014, referans 8.7). Gözlem yığını `obs` profiliyle açılır:

```bash
docker compose --profile obs up -d --wait
```

- **Grafana:** `http://127.0.0.1:3000`, kullanıcı `admin`, parola `secrets/SECRET_GRAFANA_ADMIN_PASSWORD`. "Verso — genel bakış" panosu.
- **Prometheus ve Alertmanager:** yayınlanmaz. Grafana'nın Explore ekranından ya da `docker compose exec prometheus wget -qO- ...` ile sorgulanır.

| Alarm | Önem | Runbook |
|---|---|---|
| `VersoDown` | page | [verso-down.md](verso-down.md) |
| `VersoHighErrorRate` | page | [high-error-rate.md](high-error-rate.md) |
| `ModelUnavailable` | page | [model-unavailable.md](model-unavailable.md) |
| `IngestionBacklog` | ticket | [ingestion-backlog.md](ingestion-backlog.md) |
| `QaSlow` | ticket | [qa-slow.md](qa-slow.md) |

- **page:** kullanıcıyı etkiler, hemen bakılır.
- **ticket:** bir sonraki iş günü bakılır.

**Bildirim kanalı:** henüz bağlı değil. `deploy/obs/alertmanager/alertmanager.yml` içindeki `default` alıcısına bir kanal eklenmeden (Slack, Telegram, e-posta ya da webhook) alarmlar yalnız Grafana ve Alertmanager arayüzlerinde görünür. Kanalın adresi ya da token'ı `/run/secrets` altından `*_file` ayarıyla okunur, YAML'a yazılmaz.

**Log'larda ne yok:** belge içeriği, soru, cevap ve dosya adı log'a yazılmaz (llm-rules 2.1). Teşhis id, süre, sayı ve sabit sonuç kelimeleriyle yapılır. Grafana'da: `{service="verso-app"} |= "outcome="`.
