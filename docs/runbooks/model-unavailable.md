# ModelUnavailable, ChatStalled

**Anlamı:** Son 10 dakikanın çoğunda ya embedding modeli belge işlemeyi durdurdu (`verso_ingestion_paused`), ya da chat modelinin devre kesicisi açıktı (`verso_qa_circuit_open`). Sorular 503 alıyor ya da yüklemeler bekliyor. **ChatStalled** (ADR-0062): cevap çağrıları zaman sınırında kesiliyor (`verso_qa_chat_stalls_total`; `phase="first_token"` ilk token gelmedi, `phase="timeout"` chat süresi doldu) ve hiçbir soru cevaplanmıyor. Chat sunucusu takılmış demektir, yavaş değil.

**Kontrol:**

1. `docker compose ps ollama ollama-chat`. Local modda embedding `ollama`, chat `ollama-chat` container'ıdır (ADR-0062; `deploy/compose.llamacpp.yaml` ile chat `llama`).
2. `docker compose logs --tail 50 ollama ollama-chat`: bellek (`OLLAMA_MEM_LIMIT`, `OLLAMA_CHAT_MEM_LIMIT`), model yükleme hataları, ısınma satırları (`ollama-warm:`). `docker compose exec ollama ollama ps`: `bge-m3:567m` `Forever`; `docker compose exec ollama-chat ollama ps`: chat modeli `Forever`.
3. Takılma şüphesinde (ChatStalled): `ollama-chat` logunda `llama-server process no longer running ... signal: killed` ya da context checkpoint satırları. Bellek mi doldu: `docker inspect -f '{{.State.OOMKilled}}' $(docker compose ps -q ollama-chat)` ve `docker stats --no-stream`. Embedding'in etkilenmediğini doğrula: `ollama` logunda `/api/embed` satırları akmaya devam ediyor olmalı.
4. Uygulama log'u:
   - `{service="verso-app"} |= "Embedding model"`: `unavailable` geçici bir kesinti demektir, `misconfigured` yanlış model demektir.
   - `|= "code=MODEL_UNAVAILABLE"` (soru tarafı, hata işleyicisinin satırı); `reason=TIMEOUT` ya da `FIRST_TOKEN_TIMEOUT` takılmadır.
5. Cloud modda: sağlayıcının durum sayfası; anahtar geçerli mi (`spring.ai.<provider>.api-key` dosyası).

**Sık nedenler ve çözümler:**

| Neden | Çözüm |
|---|---|
| Ollama açıldıktan sonra ilk sorular 10030 alıyor | Isınma henüz bitmedi ya da başarısız oldu: `docker compose logs ollama` içindeki `ollama-warm:` satırları. Sağlık kontrolü model bellekte değilse `unhealthy` der. Isınma başarısızsa model ilk kullanımda yüklenir; `docker compose restart ollama` ısınmayı yeniden dener |
| Chat sunucusu takıldı (ChatStalled; 2026-10-29 olayı) | `docker compose restart ollama-chat`. Embedding ayrı container'da olduğu için arama ve belge işleme sürer; yalnız cevaplar 503 alır. Tekrarlıyorsa `LLAMA_ARG_CACHE_RAM`/`LLAMA_ARG_CTX_CHECKPOINTS` değerlerinin `0` olduğunu (`docker compose exec ollama-chat env`) ve bellek sınırını kontrol et (ADR-0062) |
| Ollama yeniden başlıyor ya da bellek yetmiyor | Embedding için `OLLAMA_MEM_LIMIT`, chat için `OLLAMA_CHAT_MEM_LIMIT`'i artır ya da daha küçük chat modeli kullan (`VERSO_CHAT_MODEL` + digest) |
| Model volume'de yok | `docker compose up ollama-pull` (internet gerekir); log'da `does not match the pinned digest` varsa digest'i kontrol et |
| `ollama` logunda `manifests-v2: read-only file system` ya da modeller bulunamıyor | Volume 0.35'ten kalma (`manifests-v2` yok ya da boş). `ollama-pull` bunu tamamlar: `docker compose up ollama-pull` (manifest için internet gerekir), sonra `docker compose up -d --wait ollama ollama-chat`. Çalışan Ollama'ya yazma izni verme (ADR-0033) |
| Yanlış model adı (`misconfigured`) | `OLLAMA_EMBEDDING_MODEL` ve `bge-m3:567m` eşleşmeli; worker 5 dk'da bir yeniden dener |

Belge kaybı yoktur. Model kesintisinde belgeler deneme harcamadan `PENDING`'e döner (ADR-0011).

**Kapanış:** iki gauge da 0'a döner; `verso_ingestion_queue{status="pending"}` azalmaya başlar; ChatStalled için `verso_qa_chat_stalls_total` artmayı bırakır ve `verso_qa_questions_total{outcome="answered"}` yeniden artar.
