# VersoDatabaseUnavailable

**Anlamı:** Uygulama 5 dakikadır kendi veritabanını okuyamıyor. Kuyruk metrikleri NaN dönüyor. API metrik ucu cevap verdiği için `VersoDown` çalmaz. Uygulamanın readiness'i DOWN'dur ve istekler 503 (99997) alır.

**Kontrol:**

1. `docker compose ps postgres`: container ayakta mı, `healthy` mi?
2. `docker compose logs --tail 50 postgres`: disk dolu mu (`No space left on device`)? Bağlantı sınırı aşıldı mı (`too many clients`)?
3. Grafana → "JVM heap ve veritabanı bağlantıları": `hikaricp_connections_active` 10'da takılıysa havuz tükenmiştir (uzun süren sorgular, kilitler).

**Sık nedenler ve çözümler:**

| Neden | Çözüm |
|---|---|
| postgres durdu ya da yeniden başlıyor | `docker compose up -d postgres`. Uygulama bağlantıyı kendisi yeniden kurar |
| Disk dolu | Eski yedekleri host dışına taşı (`backups` volume'ü), sonra postgres'i yeniden başlat |
| Kilit bekleyen sorgular | `docker compose exec postgres psql -U postgres -d verso -c "select pid, state, wait_event_type, now() - query_start from pg_stat_activity where datname = 'verso'"` (sorgu metni yazdırılmaz) |
| Bozuk veri | Geri yükleme: `bash scripts/restore-drill.sh` ile önce provayla doğrula (ADR-0009) |

**Kapanış:** `verso_ingestion_oldest_due_wait_seconds` yeniden sayı döner; readiness UP olur.
