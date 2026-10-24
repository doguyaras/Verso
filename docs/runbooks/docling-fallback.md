# DoclingFallback

**Anlamı:** Docling yan konteyneri (ADR-0032, `deploy/compose.docling.yaml`) 30 dakikadır hiçbir belgeyi ayrıştırmadı; istenen her belge JDK ayrıştırıcısına döndü. Belgeler yine READY olur, ama tablolar yapısını kaybeder. Metin katmanı olmayan (taranmış) PDF'ler backoff ile yeniden denenir ve 5 denemeden sonra `PROCESSING_FAILED` olur.

**Kontrol:**

1. Log: `{service="verso-app"} |= "Document parsed without the sidecar"` → `reason=`.
   - `UNAVAILABLE`: yan konteyner kapalı ya da ağ yok. `docker compose -f compose.yaml -f deploy/compose.docling.yaml ps docling`.
   - `TIMEOUT`: belge 5 dakikada bitmedi. CPU sınırı (`DOCLING_CPUS`) ya da çok sayfalı OCR.
   - `CIRCUIT_OPEN`: arka arkaya 3 hatadan sonra 5 dakika atlanıyor; asıl neden önceki satırlarda.
   - `CONVERSION_FAILED`, `INVALID_RESPONSE`, `PAGE_MISMATCH`: belgeye özgü; tek tük ise alarm gelmez.
2. Yan konteynerin kendi log'u: `docker compose ... logs docling --tail 100` (belge metni yazmaz; dosya adı sabit `document.pdf`).
3. Bellek: `docker stats` — 4 GB sınırına dayanıyorsa OOM ile yeniden başlıyordur.

**Çözümler:**

| Durum | Çözüm |
|---|---|
| Konteyner kapalı / yeniden başlıyor | `up -d docling`; OOM ise `DOCLING_MEM_LIMIT`'i artır ya da `verso.document.docling.max-pages`'i düşür |
| Zaman aşımı | `DOCLING_CPUS`'u artır; çok sayfalı taranmış belgeler için `max-pages`'i düşür |
| Yan konteyner gereksiz | `VERSO_DOCLING_ENABLED=false` ve overlay'siz başlat: davranış ADR-0011/0016'daki gibi olur |

**Kapanış:** `verso_ingestion_parsed_total{parser="docling"}` yeniden artar.
