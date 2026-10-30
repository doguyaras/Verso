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
| `ChatStalled` | page | [model-unavailable.md](model-unavailable.md) (ADR-0062) |
| `IngestionBacklog` | ticket | [ingestion-backlog.md](ingestion-backlog.md) |
| `DoclingFallback` (yalnız `deploy/compose.docling.yaml` ile, ADR-0032) | ticket | [docling-fallback.md](docling-fallback.md) |
| `RerankerFallback` (yalnız `deploy/compose.reranker.yaml` ile, ADR-0043) | ticket | [reranker-fallback.md](reranker-fallback.md) |
| `KnowledgeRecallDropped` (ADR-0049) | ticket | [knowledge-recall-dropped.md](knowledge-recall-dropped.md) |
| `QaSlow` | ticket | [qa-slow.md](qa-slow.md) |
| `QaP99OverBudget` | ticket | [qa-slow.md](qa-slow.md) |
| `HistoryNotStored` | ticket | [history-not-stored.md](history-not-stored.md) |
| `AuditEventNotStored` | page | [audit-write-failed.md](audit-write-failed.md) |
| `OutboxOldestPendingTooOld` (ADR-0039) | page | [outbox-stuck.md](outbox-stuck.md) |
| `OutboxDeadRows` (ADR-0039) | page | [outbox-stuck.md](outbox-stuck.md) |
| `ConnectorSyncStale` (yalnız tanımlı bir dosya paylaşımı bağlayıcısı varken, ADR-0038) | ticket | [connector-sync-stale.md](connector-sync-stale.md) |

- **page:** kullanıcıyı etkiler, hemen bakılır.
- **ticket:** bir sonraki iş günü bakılır.

**Alarmsız, talep üzerine iş:** [KVKK ilgili kişi başvurusu](kvkk-basvurusu.md): erişim (dışa aktarım), silme, silinen belgeden alıntı yapan cevapları karartma; 30 günlük yanıt süresi (ADR-0034).

**Alarmsız, periyodik iş:** [aylık güvenlik taraması](aylik-guvenlik-taramasi.md): `supply-chain-scan` iş akışı kırmızıysa (CRITICAL ya da düzeltmesi olan HIGH bulgu, süresi dolmuş istisna) bakılacak yer burasıdır (ADR-0030).

**Bildirim kanalı:** henüz bağlı değil. `deploy/obs/alertmanager/alertmanager.yml` içindeki `default` alıcısına bir kanal eklenmeden (Slack, Telegram, e-posta ya da webhook) alarmlar yalnız Grafana ve Alertmanager arayüzlerinde görünür. Kanalın adresi ya da token'ı `/run/secrets` altından `*_file` ayarıyla okunur, YAML'a yazılmaz.

**Hangi log'lar toplanır:** yalnız `verso-app`, `migrate`, `edge` ve `backup`. Keycloak (başarısız girişlerde kullanıcı adı ve adres), PostgreSQL (hata satırları değer içerebilir), Ollama ve gözlem servisleri Loki'ye gönderilmez; onlar için `docker compose logs <servis>` kullanılır.

**Alarm testleri:** `deploy/obs/prometheus/alerts.test.yml` (promtool). Her alarm hem kendi kesintisinde çalar hem normal durumda susar; CI'da `scripts/obs-smoke.sh` koşar.

**Log'larda ne yok:** belge içeriği, soru, cevap ve dosya adı log'a yazılmaz (llm-rules 2.1). Teşhis id, süre, sayı ve sabit sonuç kelimeleriyle yapılır. Grafana'da: `{service="verso-app"} |= "outcome="`.

**Alarmsız, elle izlenen iş:** [Sürüm yükseltme ve geri alma](surum-yukseltme-ve-geri-alma.md): imzalı digest ile kurulum, yükseltme sırası, expand/contract, geri alma ve ileri düzeltme kararı (ADR-0040).
