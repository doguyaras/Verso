# ADR-0006: AI çalışma modu (local / cloud) ve modellerin seçimi

- **Durum:** Kabul edildi (uygulama faz 4–6)
- **Tarih:** 2026-10-02
- **Karar verenler:** doguyaras
- **İlgili eşik (referans Bölüm 24):** Yok (ürün kararı). Model kalitesi faz 8 eval setiyle ölçülür.

## Bağlam

Verso'nun vaadi şudur: belgeler ve sorular kurumun altyapısından çıkmaz. Kişisel verinin yurt dışına aktarımı KVKK md. 9'a tabidir.

Buna karşın bazı müşteriler, kalite için harici bir LLM API'si kullanmayı bilinçli olarak seçebilir. Mod yalnız yapılandırmayla, kod değişmeden değişmeli ve her yanıtta görünmelidir.

## Seçenekler

| Konu | Seçenekler | Karar |
|---|---|---|
| Mod anahtarı | Spring profili `local`/`cloud` · property | **Property** `verso.ai.mode` (`VERSO_AI_MODE`). Referansta `local` profili yerel geliştirme config'i için ayrılmış (15.1); aynı adı iki anlamda kullanmak hatalı yapılandırmaya yol açar |
| Embedding | Mod ile değişen · her modda yerel | **Her modda yerel `bge-m3`** (Ollama, 1024 boyut). Mod değişince saklı vektörler geçersiz kalmaz; cloud modda dışarı yalnız soru ve seçilen pasajlar gider, belgenin tamamı gitmez |
| Yerel chat modeli | gemma 4 (e4b), qwen3 (4b), llama 3.2 (3b) | **Varsayılan küçük ve çok dilli bir Gemma 4 modeli.** 140+ dille eğitilmiş, CPU'lu dizüstünde çalışabilir. Ollama etiketi faz 5'te doğrulanır. Llama 3.2'nin resmi dil listesinde Türkçe yok. Model adı `VERSO_CHAT_MODEL` ile değişir |
| Cloud sağlayıcı | Anthropic · OpenAI uyumlu · ikisi | **Varsayılan Anthropic**; OpenAI uyumlu ikinci yapılandırma. İkisi de Spring AI `ChatModel` arayüzünün arkasında; seçim property ile |
| Model API'sinin seçimi | Kendi arayüzümüz · Spring AI `ChatModel`/`EmbeddingModel` | **Spring AI arayüzleri.** Ek soyutlama katmanı icat edilmez (AGENTS.md: mevcut pattern) |

## Karar

1. **`local` modunda Verso hiçbir harici adrese bağlanmaz.** Bu üç katmanda zorlanır:
   1. Compose'da uygulama ve Ollama yalnız `internal: true` ağdadır. Modeller tek seferlik ayrı bir container ile indirilir.
   2. Uygulamada Boot 4.1 `InetAddressFilter` yalnız yapılandırılmış model host'una izin verir.
   3. Açılış kontrolü, chat veya embedding modeli yerel değilse uygulamayı başlatmaz.

   Kanıtı testler ve `scripts/prove-local-mode.sh` sağlar (faz 6).
2. **`cloud` modunda yalnız chat çağrısı dışarı çıkar.** Dışarı giden içerik: soru, sahiplik filtresinden geçmiş en fazla `top-k` pasaj ve sistem talimatı. Belge dosyası, tam metin ve embedding'ler gitmez.
3. Her HTTP yanıtı `X-Rag-Mode: local|cloud` taşır. `/actuator/info` aktif modu ve model adlarını gösterir.
4. Cloud API anahtarı yalnız `/run/secrets` ile gelir. Literal fallback yoktur (referans 15.3).
5. Modelle ilgili tüm kurallar (log, prompt injection, atıf, embedding sürümü, eval) `docs/ai/llm-rules.md`'de yaşar. Değişiklikleri `verso-llm-review` skill'i inceler.

## Sonuçlar

- **Olumlu:**
  - Mod değişimi kod değil, yapılandırmadır.
  - Local modun "dışarı çıkmaz" iddiası makineyle kanıtlanır.
  - Embedding yerel kaldığı için cloud modda dışarı çıkan veri minimumdur.
- **Olumsuz / kabul edilen risk:**
  - Küçük yerel modellerin Türkçe yanıt kalitesi büyük modellerden düşüktür. Kalite eval setiyle ölçülür ve model `VERSO_CHAT_MODEL` ile değiştirilebilir.
  - Cloud mod, KVKK md. 9 açısından müşterinin kendi hukuki değerlendirmesini gerektirir. README'de açıkça yazılır (hukuki tavsiye değildir).
- **Etkilenen dosyalar:** `qa-core` (model seçimi), `verso-app` config, compose (ağlar, ollama-pull), `docs/ai/llm-rules.md`.
- **Geri alma yolu:** Cloud desteği kaldırılmak istenirse cloud starter bağımlılığı çıkarılır. Local mod etkilenmez.

## Yeniden değerlendirme koşulu

- Eval setinde yerel modelin cevap doğruluğu ya da atıf kesinliği eşik altında kalırsa (eşikler faz 8'de belirlenir).
- Daha iyi bir çok dilli embedding modeli çıkarsa. Embedding değişikliği yeniden indeksleme gerektirir.
