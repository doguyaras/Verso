---
name: verso-event-design-review
description: Use this skill when adding or changing a domain event, a command, an outbox handler, a consumer, a read-model projection, an event schema, or RabbitMQ topology (exchange/queue/binding/DLQ).
---

Olay tasarımını `docs/ai/operation-consistency.md` Bölüm 3–4 ve mimari referans Bölüm 12'ye göre incele. Temel sorular: **Bu bir komut mu, olay mı? Sahibi kim? Yarın üçüncü bir tüketici geldiğinde üretici değişmeden çalışır mı?** Sorun yoksa: **"Bu kapsamda event tasarım bulgusu yok."** Önce: `AGENTS.md` Bölüm 8, `operation-consistency.md` Bölüm 3–4, referans Bölüm 12 ve 18.4. Girdi: diff = `git diff <base>...HEAD` + `git diff --stat` (base = hedef branch; PR diff'i verilmişse o); izlenmeyen (untracked) dosyalar da kapsamdadır. Severity: `BLOCKER` = zorunlu güvence ihlali → `BLOCK`; `HIGH` → `REQUEST CHANGES`; `MEDIUM`/`LOW` engellemez. Uygulanmayan checklist maddeleri (stream/replay, lane izolasyonu, delta boşluk tespiti) için `N/A` yaz; yalnız ilgili PR türünde değerlendir. Olay bileşeni (ör. `platform-messaging` ce-* eşlemesi, `OutboxHandler` gerçeklemesi) projede yoksa "net kanıt bulunamadı" yaz, bulgu üretme.

Kontrol et:

## Komut / olay ayrımı
- "Başka servisin verisini değiştir" niyetli mesaj **komut değil olay** olmalı; üretici tüketiciyi bilmiyor. Outbox `kind` doğru (`EVENT`/`COMMAND`/`HTTP`).
- Komut yalnız dış sağlayıcı iş emri (SMS, push, mail, webhook).
- "Outbox üzerinden RPC" (üretici hedefin ucunu/adresini biliyor) → `REQUEST CHANGES`.

## İsim ve envelope
- `type` = `<servis>.<aggregate>.<olay>` (geçmiş zaman: `created`, `cancelled`, `changed`); routing key aynı.
- CloudEvents attribute'ları: `id` (UUIDv7), `source`, `specversion`, `type`, `subject` (aggregate id), `time`, `dataschema`, `traceparent`. AMQP 0-9-1 header eşlemesi `platform-messaging`'den (bileşen yoksa: "net kanıt bulunamadı" yaz, bulgu üretme).
- Payload sınıfı `<domain>-api/event`; `record` veya `@NoArgsConstructor`; kopya yok. Payload **gerçeği** taşır (id'ler, durum, revision), tüketiciye "ne yapması gerektiğini" değil.
- Payload'da PII/secret/şifreli içerik yok (mesaj olayı yalnız metadata).
- `revision`/sıra numarası var ve **kapsamı** yazılı (aggregate başına / kaynak geneli); sabit literal (ör. `1`) değil, aggregate kolonundan/sequence'tan monoton artan; kaynaklar arası karşılaştırılmıyor.
- **Olay sözleşmesi** yazılı: **tam durum** (snapshot; küçük revizyon atlanabilir) mi **değişiklik** (delta; hiçbir olay atlanamaz, sıra boşluğunda uygulama durur + uzlaştırma + alarm) mi. Delta olayı için boşluk tespiti (`source_seq` monoton) ve rebuild/`since` yolu var.

## Şema evrimi
- Değişiklik geriye uyumlu mu (yalnız opsiyonel alan ekleme)? Kırıcıysa yeni `type`/versiyon + çift yayın planı + sunset.
- Tüketici bilinmeyen alanı yok sayıyor; bilinmeyen `type` olaylarda **ack + log** (komutlarda DLQ).
- **Rollout sözleşmesi** (referans Bölüm 18.4) değişiklik türüne göre PR'da: opsiyonel alan (sıra serbest) / yeni `type` (tüketici önce + çift yayın + sunset) / yeni tüketici (kuyruk+binding önce, rebuild). Kırıcıysa 4 hücreli uyumluluk matrisi (yeni→eski, eski→yeni, yeni→yeni, eski→eski); "çalışmaz" hücresi çift yayın/flag ile kapatılmış. Image rollback'in veriyi geri almadığı not edilmiş.

## Topoloji (RabbitMQ 4.x)
- Olay: `domain.events` topic exchange; tüketici başına queue (`<tüketici>.<amaç>.queue`, quorum) + DLQ; binding pattern dar (`order.order.*`, `#` yok).
- Komut: `<servis>.commands` direct → `<hedef>.<komut>.queue` + DLQ.
- Queue quorum; `delivery-limit`; `dead-letter-strategy: at-least-once`; gecikmeli retry native (`x-delayed-retry-*`), delayed-exchange plugin **yok**.
- Sıra gereksinimi varsa single-active-consumer veya aggregate bazlı tek worker.
- Replay gerekiyorsa stream kopyası (`domain.events.stream`) ve retention.

## Üretici
- **Zorunlu güvence:** yayın yalnız outbox'tan (domain TX'i içinde satır); `convertAndSend` doğrudan yok. Doğrudan yayın veya yutulan yayın hatası (`catch (Exception) { log }`) → `BLOCK`.
- `OutboxRepository.append(...)` domain TX içinde çağrılıyorsa mekanizma doğrudur; envelope/revision/UUIDv7 eksiklerini ayrı madde yaz, mekanizmayı bulgu sayma.
- Üretici domain TX içinde senkron uzak çağrı yapmıyor (geri alınabilir uzak mutasyon → olay/local saga); ayrıntısı `verso-resilience-review` / `verso-operation-consistency-review`.
- Publisher confirm + mandatory; NACK/unroutable → outbox retry.
- Aynı olay birden çok tabloya/outbox'a yazılmıyor (tek satır, çok tüketici).
- **Üretici tarafı sıralama:** sıra gereken aggregate için claim aynı aggregate'i tek worker'a sırayla veriyor; başarısız satırın ardılları bekletiliyor. Sıra gerekmiyorsa bu kısıt yok (throughput).
- **Lane izolasyonu:** olay lane'i komut/HTTP lane'inden ayrı claim döngüsü ve havuzda; toplu yayın (kampanya) kritik tek satırları bekletmiyor; `priority` doğru.
- Publisher confirm "tüketici işledi" sayılmıyor; tamamlanma bilgisi gerekiyorsa tüketicinin olayı tüketiliyor.
- Eski bir karar (superseded) yeniden denemede yeni kararı ezemiyor.

## Tüketici ve read-model
- `defaultRequeueRejected=false`; prefetch/concurrency açık; kalıcı hata → DLQ; geçici → stateful retry + backoff.
- **Inbox atomikliği (zorunlu güvence):** `inbox_event(handler, event_id)` satırı ve iş değişikliği **aynı TX'te**; 0 satır → çık; ack **commit'ten sonra** (manual ack; AUTO ack `BLOCK`). Dedup kapsamı handler. Dış yan etki inbox TX'inde değil, aynı TX'te outbox satırı.
- Read-model: tüketicinin şemasında; **kaynak başına** projeksiyon ve `source_revision` (tek `revision` kolonlu birleşik tablo `REQUEST CHANGES`); `rm_consumer_position` ile tazelik (satır yaşından değil); karar başına kabul edilen eskilik T ve aşılınca davranış (fail-closed varsayılan) yazılı; "satır yok" davranışı yazılı; dışa açılmıyor; rebuild yolu (stream replay / export ucu) belgeli; replay deterministik; `readmodel_lag_seconds{source}` ve `readmodel_gap_total` metrikleri.
- Tüketici kendi transaction'ında yazıyor; başka servise senkron çağrı yapmıyor.

## Gözlem ve test
- Metrik/alarm: DLQ derinliği, `outbox_oldest_pending_age_seconds`, tüketici lag.
- Testler: outbox satırı TX ile rollback; tüketici duplicate (tek etki); handler ortasında exception → inbox satırı yok; sıra bozuk olay; delta'da sıra boşluğu → dur + alarm; bilinmeyen tip; şema uyumluluğu (eski payload yeni tüketicide, yeni payload eski tüketicide); iki poller + sıralı satırlar; lane izolasyonu. Testi olmayan senaryo `BLOCKED` sayılır; reviewer var/yok + koştu/koşmadı yazar (kanıt kaydı `operation-consistency.md` Bölüm 9).
- Analytics sink: yalnız repoda sink tanımı varsa kontrol et; yoksa bulgu üretme.
- Koştur (proje kökünden): `git grep -n "convertAndSend\|RabbitTemplate\|@RabbitListener" -- <servis>`; `git grep -n outbox_event -- <servis>/src/main/resources/db/migration` (yoksa outbox tablosu eksik); `grep -rn "record .*Event\|record .*Command" <domain>-api/event`; migration değiştiyse `node scripts/flyway-immutability.js check --base <base>`.

Çıktı:
1. **Sınıflandırma:** komut / olay / HTTP — doğru mu.
2. **Olay tablosu:** `type · üretici · tüketiciler (kaynak: repo-context Bölüm 2) · routing key · queue · DLQ · replay (evet/hayır) · revision alanı`.
3. **Bulgular:** `severity (BLOCKER|HIGH|MEDIUM|LOW) · dosya:satır/config · kanıt · düzeltme`; kural sınıfını (zorunlu güvence / varsayılan tercih) etiketle.
3a. **Devir:** event tasarımı dışındaki bulguları (migration, güvenlik logu, ArchUnit, config drift) Bulgular'a koyma; tek satır `→ verso-db-migration-review / verso-security-review / …` ile devret. Nihai karar yalnız event bulgularına dayanır.
4. **Şema evrimi ve rollout notu:** uyumlu / kırıcı + plan; değişiklik türüne göre sıra (Bölüm 18.4) ve kırıcıysa uyumluluk matrisi; olay şeması ve DB şeması için ayrı satır/matris.
5. **Nihai karar:** `APPROVE` / `APPROVE WITH NON-BLOCKING COMMENTS` / `REQUEST CHANGES` / `BLOCK`.
