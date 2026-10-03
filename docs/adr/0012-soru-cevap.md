# ADR-0012: Soru-cevap (retrieval, prompt, atıf, local chat modeli)

- **Durum:** Kabul edildi (faz 5)
- **Tarih:** 2026-10-03
- **Karar verenler:** doguyaras
- **İlgili eşik (referans Bölüm 24):** ADR-0008 (sıcak yol bütçesi); arama satırı (ADR-0002).

## Bağlam

Faz 5, kullanıcının kendi belgelerine Türkçe soru sorup atıflı cevap almasını ister. ADR-0006 modu, ADR-0008 sıcak yolu (iki uzak çağrı) belirledi. `docs/ai/llm-rules.md` bölüm 3 (prompt injection, atıf, eşik, sınırlar), 4.1 (sahiplik sorgunun içinde) ve 6.1 (model uyuşmazlığında fail-closed) bu fazın doğrulama yükümlülükleridir. Referans bu alanda sessizdir.

## Karar

**Modüller (ADR-0001, ADR-0003):**

- Yeni `qa` modülü (`qa-api` + `qa-core`). Kendi tablosu yoktur. `document` modülüne yalnız `document-api`'deki `DocumentRetrieval` arayüzüyle erişir (`@ApplicationModule(allowedDependencies = "document :: api")`; `ModuleStructureTest`, `QaArchitectureTest`).
- Embedding'i ve vektör aramasını `document` modülü yapar: aynı model ve aynı tablo bilgisi tek yerde kalır.

**Retrieval (`DocumentRetrievalServiceImpl`):**

1. Hesabın chunk'ları arasında başka bir modelle üretilmiş olan varsa 409 `DOCUMENT_REINDEX_REQUIRED` (10031) döner; karşılaştırma yapılmaz (llm-rules 6.1).
2. Soru, belgelerle aynı yerel modelle embed edilir; transaction dışında, en çok 10 sn ve instance başına en çok 4 eşzamanlı çağrı (`verso.document.retrieval.*`). Model yanıt vermezse, süre dolarsa ya da slot yoksa 503 `EMBEDDING_MODEL_UNAVAILABLE` (10030). Yarıda bırakılan çağrı gerçekten bitene kadar slotunu tutar; takılan model 4'ten fazla çağrı biriktiremez.
3. Kısa bir salt-okunur transaction'da şu sorgu çalışır: `account_id = :account AND embedding_model = :model AND status = 'READY'` koşulları **vektör sorgusunun içinde**, `ORDER BY embedding <=> :q LIMIT k`.
4. `hnsw.iterative_scan = relaxed_order` ve `ef_search = max(40, 4k)` kullanılır. Filtreli HNSW taraması küçük hesaplarda `LIMIT`'ten az satır döndürmez (faz 4 db review D4). Gevşek sıra uygulamada benzerliğe göre yeniden sıralanır.

**Cevap (`QuestionServiceImpl`):**

| Adım | Kural |
|---|---|
| Soru | 1–1000 karakter (`@NotBlank @Size`); log'a yazılmaz. Gövde okunmadan önce sınırlanır: `Content-Length` 16 KB'tan büyükse 413 (11010), yoksa (chunked) 411 (11011) |
| Pasaj sayısı | `top-k` 5 |
| Eşik | En iyi pasajın cosine benzerliği < 0,50 → chat modeli **çağrılmaz**, sabit "Belgelerde bu sorunun cevabı bulunamadı." (`found=false`). Faz 8'de ölçüldü: başlangıç değeri 0,45'ti; 0,50 cevaplanabilir 25 sorunun hepsini korur, cevaplanamaz 8 sorunun 4'ünü modele göndermez (`eval/README.md`) |
| Prompt | Talimatlar yalnız sistem mesajında. Pasajlar kullanıcı mesajında `[[BELGE n]] (sayfa p) … [[/BELGE n]]` ayraçlarıyla, numaralı. Köşeli parantez yalnız builder'dan gelir: pasajda ve soruda NFKC katlamasından (tam genişlikli benzerler) ve görünmez biçim karakterleri (`\p{Cf}`) silindikten sonra her `[`/`]` yuvarlak paranteze döner. Bir belge kendi ayracını kapatamaz ve modelin kopyalayıp yanlış pasaja bağlanabilecek bir `[n]` işareti taşıyamaz. Dosya adı prompt'a girmez. Pasaj başına en çok 1500 karakter |
| Model çağrısı | Instance başına en çok 1 eşzamanlı çağrı (bulkhead; faz 8'de 2'den indi, `docs/capacity.md`); boş slot 5 sn içinde yoksa 503 `MODEL_BUSY` (11002). Zaman aşımında çağrı kesilir; model sunucusu üretimi bırakır. Sıcaklık 0,1, en çok 512 token, `think: false`: Spring AI 2.0 sağlayıcıları genel `ChatOptions` kabul etmediği için bunlar `spring.ai.ollama.chat.*` ayarıdır, çağrı seçeneksizdir. İstemci retry'ı kapalı; ardışık 2 hatada devre 15 sn açılır |
| Hata | Model hatası 503 `MODEL_UNAVAILABLE` (11001). Sağlayıcı metni ne yanıta ne log'a girer (llm-rules 2.3). Boş cevap da hatadır; uydurma ya da boş cevap dönülmez |
| Atıf | Model yalnız `[n]` yazar (`[1, 3]`, `[1-3]`, `[ 2 ]` ve tam genişlikli biçimler de tanınır). Sunucu her numarayı o istekte getirilen pasaja (belge id, ad, sayfa) eşler; geçersiz numara metinden silinir (llm-rules 3.3). `found` = en az bir geçerli atıf |
| Araç | Yok: model çıktısı hiçbir yerde çalıştırılmaz (llm-rules 3.2; ArchUnit: `org.springframework.ai.tool..` kullanılmaz) |
| Yanıt | `{answer, found, citations[{number, documentId, fileName, page}], mode, model}`, `Cache-Control: private, no-store` |
| Log | Yalnız sayılar ve süreler: `passages`, `relevant`, `citations`, `promptChars`, `answerChars`, `retrievalMs`, `chatMs`, `outcome=answered|uncited|not_found` |

**Mod görünürlüğü (ADR-0006, llm-rules 1.3):**

- Her yanıt `X-Rag-Mode: local|cloud` taşır: 401, 404, hata sayfaları ve management portu (`QaManagementContextConfiguration`) dahil. Filtre Spring Security'den önce çalışır. Tomcat'in uygulamaya ulaşmadan reddettiği bozuk URL'ler (`/v1/%zz`) istisnadır.
- `/actuator/info` modu ve model adlarını gösterir (`verso.ai.*`, istemcilerin kendi ayarlarından okunur).

**Modeller:**

| Rol | Model | Not |
|---|---|---|
| Embedding | `bge-m3:567m` (değişmedi) | MIT |
| Chat (varsayılan) | `gemma4:e2b`, model katmanı 3,5 GB, digest ile pinli | ADR-0006: küçük, çok dilli Gemma 4. Gemma kullanım şartları; `docs/versions.md` |
| Chat (CI) | `qwen3:0.6b` (523 MB) | Yalnız hattı dener; kalite eval setinin işi (faz 8) |

- `VERSO_CHAT_MODEL` ve `VERSO_CHAT_MODEL_DIGEST` başka bir modeli seçer; `ollama-pull` her modeli kendi digest'iyle doğrular (`OLLAMA_PULL` listesi).
- Ollama bellek sınırı 6 GB (iki model birlikte yüklü, `OLLAMA_MAX_LOADED_MODELS=2`).
- HTTP okuma timeout'u 90 sn (ADR-0008, local chat).

## Sonuçlar

- **Olumlu:**
  - Başka hesabın pasajı prompt'a giremez: koşul sorgunun içinde ve gerçek PostgreSQL ile testli (iki hesap, aynı konu).
  - Eşik altında model çağrılmadığı için uydurma ve maliyet düşer.
  - Atıflar doğrulanabilir.
- **Olumsuz / kabul edilen risk:**
  - CPU'da `gemma4:e2b` ile cevap süresi on saniyeler mertebesindedir (ADR-0008 bütçesi: local-CPU 60 sn). Ölçüm faz 8'de.
  - 0,50 eşiği ve `top-k` küçük bir eval setiyle ölçüldü (33 soru); gerçek belge setiyle yeniden ölçülmeli.
  - Model atıf yazmazsa cevap döner ama `found=false` olur (`outcome=uncited`). İstemci bunu "kaynaksız cevap" olarak gösterir; metin sabit "bulunamadı" cümlesi değildir.
  - Streaming (SSE) yok: cevap tek seferde gelir (ADR-0008 yeniden değerlendirme koşulu).
- **Etkilenen dosyalar:** `services/qa/*`, `document-api` (`DocumentRetrieval`, `RetrievedPassage`), `document-core` (`RetrievalRepository`, `DocumentRetrievalServiceImpl`), `config/verso.yml` (`spring.ai.ollama.chat`, `verso.ai`, `verso.qa`), `compose.yaml`, `deploy/ollama/pull.sh`, `scripts/qa-smoke.sh`.
- **Geri alma yolu:** `qa` modülü bağımsız olarak kaldırılabilir; `document-api`'deki arayüz kalır.

## Yeniden değerlendirme koşulu

- Eval setinde recall@5 ya da atıf kesinliği eşik altında kalırsa (faz 8): eşik, `top-k`, chunk boyutu, model.
- Local-CPU p99 60 sn'yi aşarsa: daha küçük model, GPU ya da streaming.
