# ADR-0008: Soru-cevap sıcak yolunda iki uzak senkron çağrı

- **Durum:** Kabul edildi (bütçeler başlangıç ayarı, faz 5'te ölçülür)
- **Tarih:** 2026-10-02
- **Karar verenler:** doguyaras
- **İlgili eşik (referans Bölüm 24):** Yok. Ölçüm tetikleyicileri aşağıda.

## Bağlam

Referansın varsayılan tercihi, sıcak yolda en fazla bir uzak senkron çağrı yapılmasıdır (Bölüm 1.2, 4.6). Varsayılanı aşan her bağımlılık bu ADR'yi ve `verso-resilience-review` incelemesini ister.

`POST /v1/questions` akışı yapısı gereği iki uzak çağrı yapar:
1. **Sorunun embedding'i** (Ollama `bge-m3`).
2. **Cevabın üretilmesi** (local modda Ollama chat modeli, cloud modda harici LLM).

İki çağrının arasında yerel bir vektör araması (PostgreSQL) yapılır.

## Seçenekler

| Seçenek | Artı | Eksi |
|---|---|---|
| A. İki senkron çağrı, ikisi de timeout ve eşzamanlılık sınırı altında | Doğrudan; kullanıcı cevabı tek istekte alır | Toplam gecikme iki modelin toplamıdır |
| B. Embedding'i JVM içinde çalıştırmak (ONNX) | Bir uzak çağrı azalır | Ek bağımlılık; Ollama ile aynı modelin iki kopyası; embedding tutarlılık riski |
| C. Asenkron cevap (iş + poll) | İstek kısa sürer | İstemci karmaşıklığı; demo deneyimi kötüleşir |

## Karar

**A** seçildi. İki çağrı da okuma amaçlı değildir; hesaplamadır ve read-model ya da JWT claim ile karşılanamaz.

Kritik akış kaydı (`docs/ai/repo-context.md` Bölüm 3) bu ADR ile tutarlıdır. Aşağıdaki değerler **başlangıç ayarıdır** (referans 1.4); faz 5 review'ından sonra uygulamadaki hâlleriyle yazıldı, ölçümü faz 8'de:

| Alan | Değer |
|---|---|
| Gecikme bütçesi (p99) | local-GPU 15 sn · local-CPU 60 sn · cloud 20 sn. Faz 8 ölçümü (local-CPU, gemma4:e2b, Ollama 2 CPU): sıralı sorularda p50 28 sn, p95 45 sn, en çok 67 sn (`docs/capacity.md`) |
| Embedding timeout | 10 sn (sorunun embedding'i; worker'ın toplu çağrıları HTTP okuma timeout'una tabidir) |
| Chat timeout | local 90 sn · cloud 30 sn (HTTP okuma timeout'u) |
| Eşzamanlılık | Chat: instance başına 2 çağrı (semaphore, boş slot için 5 sn bekleme → 503 `MODEL_BUSY`). Sorunun embedding'i: 4 çağrı, dolunca anında 503. Ollama zaten tek tek işler |
| Retry | Senkron yolda yok (referans 4.7). Spring AI'ın istemci retry'ı kapalı (`spring.ai.retry.max-attempts: 0`; `OllamaChatClientTest` gerçek istemciyle doğrular) |
| Devre kesici | Ardışık 2 chat hatasından sonra 15 sn boyunca soru modele ve embedding'e gitmeden 503 `MODEL_UNAVAILABLE` alır; süre dolunca ilk soru modeli yeniden dener |
| Bağımlılık düşünce | 503 `MODEL_UNAVAILABLE`; boş ya da uydurma cevap dönülmez (fail-closed) |
| Benzerlik eşiği altında | Chat çağrısı **yapılmaz**; "belgelerde bulunamadı" cevabı döner. Hem maliyet hem uydurma riski düşer |

**B ve C reddedildi:** B ek bağımlılık getirir ve embedding tutarlılığını riske atar; C demo deneyimini bozar.

## Sonuçlar

- **Olumlu:** Akışın tek bir gerçek darboğazı var: model çalışma süresi. Bu süre açıkça ölçülür.
- **Olumsuz / kabul edilen risk:** CPU'da çalışan küçük modellerle cevap süresi on saniyeler mertebesindedir. README'de donanıma göre beklenti yazılır. En kötü durumda bir soru: 10 sn embedding + 5 sn slot bekleme + 90 sn chat ≈ 105 sn; istemci zaman aşımı en az 120 sn olmalıdır.
- **Etkilenen dosyalar (faz 5):** `qa-core` (service, client yapılandırması), `config/verso.yml` (timeout'lar), `docs/ai/repo-context.md`.
- **Geri alma yolu:** Akışa streaming (SSE) eklemek yeni bir uç gerektirir (ayrı ADR).

## Yeniden değerlendirme koşulu

- p99 değeri bütçeyi 3 gün üst üste aşarsa.
- Kullanıcı testinde ilk cevap gecikmesi demo deneyimini bozarsa. Bu durumda streaming (SSE) değerlendirilir.
- Eşzamanlı kullanıcı sayısı 5'i geçerse. Bu durumda kapasite planı ve model sunucusu ölçeği değerlendirilir.
