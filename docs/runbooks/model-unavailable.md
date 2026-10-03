# ModelUnavailable

**Anlamı:** Son 10 dakikanın çoğunda ya embedding modeli belge işlemeyi durdurdu (`verso_ingestion_paused`), ya da chat modelinin devre kesicisi açıktı (`verso_qa_circuit_open`). Sorular 503 alıyor ya da yüklemeler bekliyor.

**Kontrol:**

1. `docker compose ps ollama`. Local modda chat ve embedding Ollama'dır.
2. `docker compose logs --tail 50 ollama`: bellek (`OLLAMA_MEM_LIMIT`), model yükleme hataları.
3. Uygulama log'u:
   - `{service="verso-app"} |= "Embedding model"`: `unavailable` geçici bir kesinti demektir, `misconfigured` yanlış model demektir.
   - `|= "code=MODEL_UNAVAILABLE"` (soru tarafı, hata işleyicisinin satırı).
4. Cloud modda: sağlayıcının durum sayfası; anahtar geçerli mi (`spring.ai.<provider>.api-key` dosyası).

**Sık nedenler ve çözümler:**

| Neden | Çözüm |
|---|---|
| Ollama yeniden başlıyor ya da bellek yetmiyor | `OLLAMA_MEM_LIMIT`'i artır ya da daha küçük chat modeli kullan (`VERSO_CHAT_MODEL` + digest) |
| Model volume'de yok | `docker compose up ollama-pull` (internet gerekir); log'da `does not match the pinned digest` varsa digest'i kontrol et |
| Yanlış model adı (`misconfigured`) | `OLLAMA_EMBEDDING_MODEL` ve `bge-m3:567m` eşleşmeli; worker 5 dk'da bir yeniden dener |

Belge kaybı yoktur. Model kesintisinde belgeler deneme harcamadan `PENDING`'e döner (ADR-0011).

**Kapanış:** iki gauge da 0'a döner; `verso_ingestion_queue{status="pending"}` azalmaya başlar.
