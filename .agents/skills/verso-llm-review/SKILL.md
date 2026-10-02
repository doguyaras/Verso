---
name: verso-llm-review
description: Use this skill when a change touches model calls (ChatModel, ChatClient, EmbeddingModel), prompts, retrieval, embeddings, chunking, citations, the local/cloud AI mode, egress configuration, or what reaches logs from documents, questions and answers.
---

LLM/RAG değişikliğini `docs/ai/llm-rules.md` (tek kaynak) ile karşılaştır. Genel güvenlik konuları `verso-security-review`'un, timeout/bütçe konuları `verso-resilience-review`'un işidir. Bu skill onların LLM'e özgü uzantısıdır; çakışan bulguyu tekrarlama, `Kapsam dışı — ilgili skill: …` satırıyla devret. Temel soru: **"Bu değişiklikten sonra bir belge cümlesi, bir soru ya da bir cevap; log'a, başka bir hesaba ya da makinenin dışına ulaşabilir mi?"** Sorun yoksa: **"Bu kapsamda LLM bulgusu yok."**

Girdi: diff = `git diff <base>...HEAD` + `git diff --stat` (base = hedef branch; PR diff'i verilmişse o); izlenmeyen (untracked) dosyalar da kapsamdadır. Severity: `BLOCKER` (bir ZG kuralı ihlali: local modda dış bağlantı, içerik loglama, başka hesabın chunk'ı, sunucu tarafında doğrulanmayan atıf) → `BLOCK`; `HIGH` → `REQUEST CHANGES`; `MEDIUM`/`LOW` engellemez. Bir kural için "bulgu yok" yazmadan önce onu zorlayan test veya config'i dosya:satır ile göster; gösteremiyorsan `needs verification`.

Çalıştır (proje kökünden): `./mvnw -B -ntp verify` (mimari testler, `TransactionBoundaryRulesTest`, log privacy testleri); local mod kanıtı değiştiyse `bash scripts/prove-local-mode.sh` (faz 6'dan itibaren); retrieval/chunking/prompt/model değiştiyse eval komutu (faz 8'den itibaren; öncesinde `needs verification`).

Kontrol et:

## Veri yerelliği ve egress (`llm-rules.md` 1)
- Yeni bir HTTP istemcisi, model sağlayıcısı, exporter veya dış URL eklendi mi? Local modda allowlist dışında kalıyor mu; compose ağ tanımı ve `InetAddressFilter` allowlist'i birlikte güncellendi mi?
- Cloud moda giden içerik yalnız soru + `top-k` pasaj + talimat mı? Tam belge, dosya adı veya vektör prompt'a giriyor mu?
- `X-Rag-Mode` header'ı yeni uçta ve hata yanıtlarında da var mı?

## Loglama ve gözlem (`llm-rules.md` 2)
- Yeni log satırlarında yalnız id, süre, boyut ve sonuç kodu mu var? Belge metni, dosya adı, soru, prompt, cevap ya da exception mesajı (sanitize edilmeden) var mı?
- Spring AI içerik loglama ayarları ve `SimpleLoggerAdvisor` kapalı mı? Span attribute'u veya metrik etiketinde içerik var mı?
- Yeni log yolunun privacy testi (`ListAppender` + sentetik işaret) var mı?

## Prompt ve çıktı (`llm-rules.md` 3)
- Pasajlar ayraçlı ve numaralı mı; sistem talimatı bağlamdan ayrı mı; ayraç taklidi kaçışlanıyor mu?
- Model çıktısı çalıştırılıyor mu (tool callback, SQL, URL, dosya)? Varsa ADR ve tehdit modeli var mı?
- Atıflar sunucuda, getirilen chunk'lardan mı üretiliyor; geçersiz `[n]` atılıyor mu?
- Eşik altında model çağrılmıyor mu; sınırlar (soru uzunluğu, `top-k`, token) yapılandırmada mı?

## Sahiplik (`llm-rules.md` 4)
- Retrieval sorgusu sahiplik filtresini vektör sorgusunun içinde mi uyguluyor? İki hesaplı gerçek PGVector testi var mı?
- Silme; chunk, vektör ve (varsa) dosyayı birlikte siliyor mu?

## Uzak çağrı disiplini (`llm-rules.md` 5)
- Model çağrısı `@Transactional` bir metot içinden mi yapılıyor (doğrudan ya da yardımcı bean üzerinden)? ArchUnit yalnız doğrudan çağrıyı görür; dolaylı yolu kod okuyarak kontrol et.
- Timeout ve eşzamanlılık sınırı tanımlı mı; senkron yolda retry var mı; istemci auto-configured builder'dan mı kuruluyor?

## Embedding ve chunking (`llm-rules.md` 6)
- Model adı ve boyutu chunk ile saklanıyor mu; uyuşmazlıkta fail-closed mi?
- Embedding modeli değişiyorsa migration, yeniden indeksleme planı ve ADR var mı?
- Chunk'lar sayfa sınırında mı; sayfa numarası korunuyor mu?

## Eval ve tedarik zinciri (`llm-rules.md` 7–8)
- Retrieval/prompt/model değişikliğinde eval sonucu PR'da mı?
- Yeni model adı veya etiketi `docs/versions.md`'ye tarih ve lisansla eklendi mi?

Çıktı:
1. **Veri akışı tablosu:** değişen her yol → hangi içerik → nereye gidiyor (log / DB / model host / dış sağlayıcı / istemci) → hangi kural ve test koruyor.
2. **Bulgular:** `severity (BLOCKER|HIGH|MEDIUM|LOW) · dosya:satır · ihlal edilen kural (llm-rules.md #) · exploit/sızıntı senaryosu · düzeltme · test`.
3. **Eksik doğrulamalar:** `llm-rules.md` Bölüm 11'deki listeden bu değişikliğin dokunduğu ve hâlâ testi olmayan kurallar.
4. **Nihai karar:** `APPROVE` / `APPROVE WITH NON-BLOCKING COMMENTS` / `REQUEST CHANGES` / `BLOCK`.
