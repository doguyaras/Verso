# ADR-0001: Mimari şekil ve uygulama profili

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-02
- **Karar verenler:** doguyaras
- **İlgili eşik (referans Bölüm 24):** Deploy birimi satırı: ≥2 ekip, ayrı release kadansı veya bir modülün farklı ölçek profili.

## Bağlam

Verso, belgelerin müşteri altyapısından çıkmadan sorgulanmasını gösteren bir doküman soru-cevap sistemidir. Hedef ortam tek host'tur: müşterinin kendi sunucusu (on-prem) ya da demo makinesi. Ekip tek kişidir.

İlk istekte iki ayrı servis önerildi: `ingestion` ve `query`. Referans (Bölüm 1.1) şekil kararını "mikroservis modern" diye değil, ekip büyüklüğüne ve ölçek profiline göre verdirir. Mikroservisin üç faydası (bağımsız deploy, bağımsız ölçek, hata izolasyonu) bu projede alınamaz:

- Tek kişi her şeyi birlikte deploy eder.
- Tek host ve tek PostgreSQL vardır.
- Asıl hesaplama yükü (embedding ve LLM) JVM'de değil Ollama'dadır. Bu yüzden ingestion ile query'nin JVM tarafında farklı ölçek ihtiyacı yoktur.

İki servise ayırmak sınırları korumak için servis JWT'si, gateway, ağ çağrısı ve iki Flyway hattı ekler. Bu tam olarak referansın tarif ettiği "dağıtık monolit" maliyetidir.

## Seçenekler

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| A. Modüler monolit (Spring Modulith modülleri, `*-api`/`*-core` Maven modülleri) | Tek deploy; in-process çağrı; sınırlar ArchUnit, enforcer ve Modulith `verify()` ile korunur; ileride modül servise çıkarılabilir | Modüller birlikte ölçeklenir | Düşük |
| B. İki mikroservis (ingestion, query) | Bağımsız deploy ve ölçek | Gateway, servis JWT, ağ hatası yüzeyi, iki deploy; fayda alınamıyor | Yüksek |
| C. Hibrit | Farklı ölçek profilli alan ayrılabilir | Henüz farklı profilli alan yok | Orta |

## Karar

**Şekil A** seçildi: tek deploy birimi `verso-app` ve içinde Spring Modulith modülleri. Domain modülleri `document` (yükleme, parse, chunk, embedding, saklama, retrieval) ve `qa` (prompt, model çağrısı, atıflar) olur. Her biri `*-api`/`*-core` Maven modül çiftidir. Ortak kod `platform/*` starter'larındadır.

- **Profil P0 (MVP).** Referans 1.3'ün P0'da pazarlıksız saydığı maddeler ilk fazlarda yapılır: yedek ve restore provası, alarm kanalı, secret hijyeni, DB rolleri, CI'da gerçek DB testleri, modül sınırı testleri, komut/olay ayrımı.
- **B reddedildi:** faydası alınamayan maliyet.
- **C reddedildi:** ayrılacak farklı ölçek profilli bir alan henüz yok.

## Sonuçlar

- **Olumlu:**
  - Tek uygulama, tek compose servisi.
  - Modüller arası çağrı in-process: sıcak yolda ağ hopu yok.
  - Gateway ve servis JWT'si gerekmez (referans 1.1 şekil A).
- **Olumsuz / kabul edilen risk:**
  - Ingestion ile soru-cevap aynı JVM'i paylaşır. Ağır bir ingestion, soru-cevap gecikmesini etkileyebilir. Azaltma: ingestion arka plan worker'ında ve sınırlı eşzamanlılıkla koşar (faz 4).
- **Etkilenen dosyalar:** `pom.xml` (modül listesi), `verso-app`, `docs/ai/repo-context.md`.
- **Geri alma yolu:** Modül sınırları ilk günden korunur. Bir modül, kendi `*-core`'u ayrı bir Boot uygulamasına taşınarak servise çıkarılır. Bu durumda `*-api` sözleşmesi HTTP client olur.

## Yeniden değerlendirme koşulu

Aşağıdakilerden biri gerçekleşince karar yeniden açılır:

- Ingestion için birden fazla bağımsız worker (ör. ayrı GPU host) gerekmesi.
- Soru-cevap p99 gecikmesinin ingestion yükü altında bütçeyi 3 gün üst üste aşması.
- İkinci bir ekip.
