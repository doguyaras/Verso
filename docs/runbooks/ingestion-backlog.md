# IngestionBacklog

**Anlamı:** İşlenmeyi bekleyen bir belge 10 dakikadan uzun süredir sırada. Bu süre worker kirasına eşittir.

**Kontrol:**

1. Grafana → "Kuyruk" panosu.
   - `verso_ingestion_paused` 1 ise: [model-unavailable.md](model-unavailable.md).
   - 0 ise worker çalışıyor ama yetişemiyor.
2. `verso_ingestion_documents_total`: `retry` artıyorsa belgeler hata alıp yeniden deneniyor. Log'da: `{service="verso-app"} |= "outcome=retry"` → `exceptionType`.
3. Kuyruk boyu (`verso_ingestion_queue{status="pending"}`) ile işleme hızını karşılaştır. CPU'da bge-m3 bir 16'lık grubu birkaç saniyede embed eder.

**Çözümler:**

| Durum | Çözüm |
|---|---|
| Çok büyük belgeler, CPU yetmiyor | `OLLAMA_CPUS`'u artır; kalıcıysa GPU'lu host (faz 8 kapasite notu) |
| Bir hesap kuyruğu dolduruyor | Hesap başına 20 bekleyen belge sınırı (`verso.document.max-queued-per-account`) zaten var. Sınırı düşür |
| Worker hiç çalışmıyor | `verso.document.ingestion.enabled` true mı? Uygulama log'unda `Ingestion poll failed` var mı? |

**Kapanış:** `verso_ingestion_oldest_due_wait_seconds` 600'ün altına iner.
