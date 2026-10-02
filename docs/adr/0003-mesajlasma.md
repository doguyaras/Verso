# ADR-0003: Mesajlaşma ve modüller arası iletişim

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-02
- **Karar verenler:** doguyaras
- **İlgili eşik (referans Bölüm 24):** Mesajlaşma ve outbox satırları.

## Bağlam

Şekil A'da (ADR-0001) modüller aynı JVM'dedir. Referans 1.1'e göre bu durumda modüller arası olaylar Spring Modulith event publication registry ile taşınır. Uygulama dışına giden mesajlar ise `outbox_event` ve RabbitMQ ile gönderilir (referans 11.2, 12).

Bugün Verso'da uygulama dışına giden hiçbir mesaj yok. Bildirim, analytics veya başka bir servis bulunmuyor. İlk istek de gerekmedikçe broker eklenmemesini istedi.

Ingestion (parse, chunk, embedding) ise uzun süren bir iştir ve kullanıcı isteğinin içinde yapılmamalıdır.

## Seçenekler

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| A. Broker yok; ingestion DB'den claim edilen bir iş; modüller arası çağrı `*-api` arayüzü ile in-process | Ek altyapı yok; referans 11.1'in multi-instance kuralları (SKIP LOCKED + lease + `claim_token`) birebir uygulanır | Dış tüketici gelince outbox eklemek gerekir | Düşük |
| B. RabbitMQ + generic outbox şimdiden | Dış tüketiciye hazır | Tüketicisi olmayan bir broker işletmek (referans 22: erken altyapı) | Orta |
| C. Modulith event registry ile ingestion tetikleme | In-process olay, at-least-once | Ingestion zaten DB durumundan yürütülebilir; ikinci bir mekanizma | Düşük-orta |

## Karar

**A** seçildi.

- **Ingestion:** `document` tablosundaki durum (`PENDING` → `PROCESSING` → `READY`/`FAILED`) üzerinden, `FOR UPDATE SKIP LOCKED` ve kira süresiyle claim eden bir worker çalışır. Uzak çağrılar (embedding) transaction dışındadır. Sonuç `claim_token` doğrulanarak yazılır (faz 4).
- **Modüller arası:** `qa` modülü `document` modülüne yalnız `document-api`'deki arayüz üzerinden, in-process erişir. `qa` hiçbir tabloya dokunmaz.
- **Komut/olay ayrımı:** İlk dış yan etki eklendiğinde komut/olay ayrımı ve CloudEvents zarfı referans 12'ye göre kurulur. Modüller arası ilk gerçek olay ihtiyacında Spring Modulith event registry eklenir; o zaman ayrı ADR yazılır.
- **Reddedilenler:** B, tüketicisi olmayan bir broker. C ise aynı işi yapan ikinci bir mekanizma.

## Sonuçlar

- **Olumlu:** Compose'da broker yok. Ingestion multi-instance güvenlidir ve testi gerçek PostgreSQL ile yapılır.
- **Olumsuz / kabul edilen risk:** `outbox_event` tablosu ve `TransactionBoundaryRulesTest`'in outbox kuralı, ilk dış mesaja kadar devre dışıdır (ADR-0007).
- **Etkilenen dosyalar:** `document-core` worker paketi (faz 4).
- **Geri alma yolu:** İlk dış tüketici geldiğinde outbox tablosu migration ile eklenir, worker'lar ise değişmez.

## Yeniden değerlendirme koşulu

Aşağıdakilerden biri gerçekleşince karar yeniden açılır:

- Uygulama dışına ilk mesajın gönderilmesi (bildirim, webhook, analytics).
- Bir modülün ayrı servise çıkması.
- Ingestion kuyruğunun DB'de ölçülebilir yük üretmesi (claim sorgusu p99 > 50 ms).
