# RerankerFallback

**Anlamı:** Rerank yan konteyneri (ADR-0043, `deploy/compose.reranker.yaml`) 30 dakikadır hiçbir soruyu yeniden sıralamadı. Sorular yine cevaplanır: pasajlar hibrit aramanın füzyon sırasıyla gelir (overlay'siz kurulumdaki gibi). "Bulunamadı" kararı etkilenmez.

**Kontrol:**

1. Metrik: `verso_retrieval_rerank_total` → hangi `outcome` artıyor.
   - `unavailable`: yan konteyner kapalı, ağ yok ya da 5xx. `docker compose -f compose.yaml -f deploy/compose.gpu.yaml -f deploy/compose.reranker.yaml ps reranker`.
   - `timeout`: 2 sn'de cevap yok. GPU başka işle dolu ya da konteyner GPU'yu göremiyor (`docker compose ... logs reranker` içinde CUDA hatası).
   - `circuit_open`: arka arkaya 3 hatadan sonra 1 dakika atlanıyor; asıl neden önceki çıktılarda.
   - `busy`: instance başına 4 eşzamanlı çağrı dolu; yük artmış.
   - `invalid_response`: TEI sürümü ya da model değişmiş olabilir.
2. Log: `{service="verso-app"} |= "Reranker skipped"` → `reason=`. Yan konteynerin log'u istek başına yalnız süreleri yazar.
3. Bellek: `docker stats` — 3 GB sınırına (`RERANKER_MEM_LIMIT`) dayanıyorsa OOM ile yeniden başlıyordur. VRAM: `docker compose ... exec ollama nvidia-smi`.
4. İlk kurulumda: `reranker-pull` başarıyla bitti mi (`logs reranker-pull`, "checksum mismatch" ya da indirme hatası).

**Çözümler:**

| Durum | Çözüm |
|---|---|
| Konteyner kapalı / yeniden başlıyor | `up -d reranker`; OOM ise `RERANKER_MEM_LIMIT`'i artır |
| GPU görülmüyor / yanlış etiket | GPU kuşağına uygun `VERSO_RERANKER_IMAGE` (README "Rerank") |
| Zaman aşımı, GPU dolu | Ollama'nın yükünü azalt ya da `verso.document.reranker.candidates`'ı düşür |
| Rerank gereksiz | `VERSO_RERANKER_ENABLED=false` ve overlay'siz başlat: davranış ADR-0022'deki gibi olur |

**Kapanış:** `verso_retrieval_rerank_total{outcome="reranked"}` yeniden artar.
