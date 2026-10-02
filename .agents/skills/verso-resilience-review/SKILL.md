---
name: verso-resilience-review
description: Use this skill when a change adds or modifies a remote synchronous call, a hot-path flow, timeouts, retries, circuit breakers, bulkheads, thread/connection pools, caches with fallback, or any control-plane dependency (parameters, config, flags).
---

Dayanıklılığı mimari referans Bölüm 1.2, 4.6, 4.7, 6.8 (HTTP client konvansiyonu + resilience4j örneği), 8.8, 14 (bounded staleness) ve `AGENTS.md` Bölüm 5'e göre incele. Temel soru: **"Bu bağımlılık yavaşlar veya düşerse kullanıcı ne görür, servis ne yapar?"** Her uzak bağımlılık için bu cevap yazılı olmalı. Sorun yoksa: **"Bu kapsamda dayanıklılık bulgusu yok."** (PR başına bir kez; temiz uçlar bağımlılık tablosunda "bulgu yok" satırıyla geçer). Girdi: diff = `git diff <base>...HEAD` + `git diff --stat` (base = hedef branch; PR diff'i verilmişse o); izlenmeyen (untracked) dosyalar da kapsamdadır. PR açıklamasındaki "Etki" kutuları (yeni uzak senkron çağrı vb.) girdidir. Severity: `BLOCKER` → `BLOCK` (kriter 5. maddede); `HIGH` → `REQUEST CHANGES`; `MEDIUM`/`LOW` engellemez.

Kontrol et:

## Sıcak yol (kritik akış kaydı)
- Değişen akış sıcak yolda mı (`docs/ai/repo-context.md` Bölüm 3)? Kaydın **tüm alanları** dolu mu: gecikme bütçesi (p99), uzak senkron bağımlılıklar ve **her birinin gerekçesi**, karar başına kabul edilen veri eskiliği, bağımlılık düşünce davranış, yeniden değerlendirme ölçümü. Boş alan → `REQUEST CHANGES`.
- Varsayılan tercih ≤1 uzak senkron çağrı, yazma/rezervasyon türünden. Aşılıyorsa: gerekçe + ADR var mı, toplam p99 bütçe içinde mi, alternatif (read-model / JWT claim / asenkron) ve **maliyeti** (replikasyon gecikmesi, rebuild, işletim) yazılmış mı? Gerekçeli ve bütçeli ikinci çağrı `APPROVE WITH NON-BLOCKING COMMENTS` olabilir; gerekçesiz olan `REQUEST CHANGES`.
- Okuma amaçlı senkron çağrı: read-model ile karşılanabiliyorsa ve eskilik toleransı buna izin veriyorsa neden eklendi? Eskilik toleransı sıfırsa (ör. bakiye) senkron kabul edilebilir — yazılı olsun.
- Availability aritmetiği: bağımlı bileşen sayısı × %99,9 → beklenen üst sınır; p99 toplamı hedefle karşılaştırılmış.

## Timeout bütçesi
- Gateway toplam bütçesi > client read timeout > downstream'in kendi downstream timeout'u (bütçe zinciri tutarlı).
- Her client grubunda `connect-timeout` ve `read-timeout` açıkça set; varsayılan/sonsuz timeout yok.
- Uzun işlemler (export, toplu işlem) senkron uçta değil; job + poll.

## Client yapılandırması
- Client, Boot'un `RestClient.Builder`/`@ImportHttpServices` grubuyla kurulmuş (statik `RestClient.create(...)` yok); base-url config'ten geliyor; başka servisin `/internal/**` ucuna giden çağrı servis JWT interceptor'ı (client-credentials token) taşıyor. Kimliksiz internal çağrı hedef auth'u sıkılaştırdığında 401 → hata yolu tanımsız; bulgu yaz ve `verso-security-review`'a devret.

## Circuit breaker / bulkhead / retry
- Hedef başına `resilience4j.circuitbreaker.instances.<hedef>` ve `bulkhead` tanımlı; açıkken tanımlı `ServiceException` (503 `UPSTREAM_UNAVAILABLE`) ve fallback davranışı belgeli.
- Spring Cloud CircuitBreaker kullanılıyorsa **TimeLimiter (varsayılan 1 sn)** ve thread-pool bulkhead ayarlanmış/kapatılmış.
- Senkron yolda retry **yok** (`NEVER_RETRY`); retry yalnız outbox/saga worker'ında, backoff ile, idempotent hedefe.
- Cascading failure hesabı: Tomcat/virtual thread modeli, Hikari havuzu, downstream timeout × istek hızı → havuz doluyor mu?

## Fail politikası
- Yeni bağımlılık için fail-open/fail-closed kararı yazılı ve `<servis>/README.md` Fail politikası tablosuyla uyumlu (yoksa eksik olarak raporla) (güvenlik → closed; iş → open + metrik).
- Control-plane bağımlılığı (parametre, config, flag): bounded-staleness (son bilinen değer + disk snapshot + `*_staleness_seconds` metriği + T eşiği); "5 sn cache + 503" **yok**.
- Redis güvenlik state'i ile cache ayrı instance; eviction politikası doğru.
- Read-model "satır yok/eski" davranışı tanımlı; tazelik tüketim konumundan (`readmodel_lag_seconds{source}`) ölçülüyor, satır yaşından değil; karar başına T farklı olabilir (engel kararı ≠ profil görseli).

## Kapasite ve kaynak
- Repoda karşılığı olmayan kontrol (gateway bütçesi, PgBouncer, WebSocket, Redis ayrımı) → tabloya `N/A (kanıt yok: <aranan yer>)` yaz; uydurma ya da sessizce atlama.
- Yeni thread pool/executor sınırlı ve isimli; virtual thread'lerle pinning riski (`synchronized` + IO) yok.
- Hikari `maximum-pool-size` × instance ≤ PgBouncer/`max_connections` bütçesi.
- Bellek: yeni cache'in üst sınırı ve TTL'i var.
- WebSocket/uzun bağlantı sayısı pod başına sınırlı; presence/fan-out Redis'te.

## Gözlem ve test
- Yeni bağımlılık için metrik (`circuitbreaker_state`, latency histogram) ve alarm.
- "Hedef 30 sn yanıt vermiyor" testi: çağıranın p99 bütçeyi aşmıyor, circuit açılıyor, health `DEGRADED`.
- Yük testi senaryosu güncellendi mi (sıcak yol değiştiyse).
- Runbook: bağımlılık düştüğünde ne yapılır (Bölüm 8.8).

Asgari kanıt (proje kökünden): `git diff --name-status <base>...<head>`; `grep -rnE 'RestClient\.create|@Retryable|resilience4j|CircuitBreaker|Bulkhead|EnableResilientMethods|rabbitTemplate\.convertAndSend' <modül>`; `grep -rn hikari`.

Çıktı:
1. **Bağımlılık tablosu:** her uzak çağrı → sıcak yol mu · timeout · CB/bulkhead · fail politikası · düşünce kullanıcı ne görür.
2. **Bulgular:** `severity (BLOCKER|HIGH|MEDIUM|LOW) · dosya:satır/config key · kanıt · düzeltme`.
3. **Kritik akış kaydı:** önce/sonra (bağımlılık sayısı, bütçe; "sonra" sütunu bağımlılık tablosuna atıf verebilir); kayıt güncellendi mi; varsayılan aşıldıysa ADR linki.
4. **Eksik testler/alarmlar.**
5. **Nihai karar:** `APPROVE` / `APPROVE WITH NON-BLOCKING COMMENTS` / `REQUEST CHANGES` / `BLOCK`. Kaydı eksik veya gerekçesiz ek senkron bağımlılık → en az `REQUEST CHANGES`. `BLOCK`: tasarımın baştan değişmesi gerekiyorsa (ör. sıcak yolu tamamen senkron zincire çeviren mimari) ya da veri kaybı/güvenlik etkisi PR içinde düzeltilemiyorsa; aynı PR'da düzeltilebilir `BLOCKER` bulgular → `REQUEST CHANGES`.
