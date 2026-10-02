---
name: verso-spring-code-review
description: Use this skill when reviewing Spring Boot service code in code review or after changes — layering, transactions, multi-instance safety, exception/log discipline, dynamic parameters, hot-path calls.
---

Spring servis kodunu mimari referansa ve `AGENTS.md`'ye göre incele. Kanıt odaklı ol: her bulgu dosya + satır + gerekçe taşır. Spekülatif bulgu üretme; kanıt yoksa `needs verification` yaz. Sorun yoksa sabit cümle: **"Bu kapsamda bulgu yok."**

Referans bölümleri: 1.2, 4.3, 6.5, 7.4, 9.2.1, 11.1–11.2, 14. Girdi: diff = `git diff <base>...HEAD` + `git diff --stat` (base = hedef branch; PR diff'i verilmişse o); izlenmeyen (untracked) dosyalar da kapsamdadır. Severity: `BLOCKER` = zorunlu güvence ihlali veya kırmızı yapısal test → `BLOCK`; `HIGH` = gerekçesiz varsayılan tercih sapması / düzeltilmesi gereken kusur → `REQUEST CHANGES`; `MEDIUM`/`LOW` engellemez. Yeni anotasyon, aspect veya config sınıfı için "Bu kapsamda bulgu yok" yazmadan önce onu çalıştıran bileşeni (resolver, interceptor, proxy) dosya:satır kanıtıyla göster. Platform bileşeni (`safeLogReason`, `SystemParameterProvider`, `*TransactionService`, `X-Subject-Id` interceptor, `SensitiveLogSanitizer`…) projede yoksa ilgili madde `N/A` yazılır, bulgu üretilmez. Başka skill'in alanındaki bulguları (contract, config drift, event PII, cross-schema) tek satırla ilgili `verso-*-review`'a devret.

Kontrol et:
- Controller ince mi: yalnız binding, `@Valid`, `@CurrentAccount`, header okuma, status/`Cache-Control`. Business logic, repository erişimi, try/catch ile hata dönüşü **yok**.
- Constructor injection (`@RequiredArgsConstructor`) her yeni/değişen bean'de (controller, ServiceImpl, client, reader). `grep -n '@Autowired' <değişen dosyalar>` çalıştır, her dosya:satır'ı yaz; bir dosyadaki bulgu diğerini kapsamaz.
- Geniş `catch (Exception)` yok; yakalanan hata ya tipli ServiceException'a çevriliyor ya da yeniden fırlatılıyor.
- Use-case servisleri arayüz + `service/impl/*ServiceImpl`; guard/policy/provider/poller somut sınıf. `service.impl` altında Impl olmayan sınıf yok; `@Configuration` yalnız `config/`.
- Mapping servis katmanında veya `*Mapper`; tek metot adı (`toResponse`). Controller'da mapping yok.
- Hedef servis başına **tek** HTTP client (`client/` paketi); DTO'lar hedefin `*-api` modülünden; `X-Subject-Id` karşı tarafta beklenmiyor (`@CurrentAccount` ile okunuyor).
- Kimlik yalnız `@CurrentAccount`; path/query/body'den hesap kimliği alınmıyor.
- `@CurrentAccount` (veya herhangi bir özel parametre anotasyonu) tek başına kimlik kanıtı değildir: modülde onu çözen bir `HandlerMethodArgumentResolver` (`WebMvcConfigurer.addArgumentResolvers`) ya da classpath'te platform auto-config'i var mı? Yoksa Spring parametreyi query/path'ten bağlar → IDOR veya 500. Resolver'ı dosya:satır ile kanıtla; kanıtlamadan "bulgu yok" yazma. Resolver yok = `BLOCKER`.
- Yeni `@RequestBody` → `@Valid`; `@PathVariable("...")`, `@RequestParam("...")`, `@RequestHeader("...")` adları açık.

## Transaction ve Sıcak Yol
- Uzak HTTP çağrısı `@Transactional` metot, row lock veya advisory lock **içinde değil**. Kanonik biçim: `ServiceImpl` (tx yok) → guard'lar → `*TransactionService` (tx) → `AFTER_COMMIT` yan etkileri → outbox.
- Outbox yazıcı `Propagation.MANDATORY`; audit/log kaydı `REQUIRES_NEW` yalnız gerekçeliyse.
- `noRollbackFor` kullanımı gerekçeli (deneme sayacı, token iptali).
- Sıcak yolda (kullanıcıya latency yansıyan akış) **okuma amaçlı** uzak çağrı eklenmiş mi? Eklendiyse `REQUEST CHANGES`: read-model veya JWT claim öner; `docs/ai/repo-context.md` sıcak yol tablosuna bak. Yazma türü tek çağrı kabul; ikincisi `verso-resilience-review` ister.
- Her client çağrısı timeout + circuit breaker + bulkhead altında mı (grup config'i var mı)?

## Multi-Instance Safety
- `@Scheduled`/poller/worker tek instance varsaymıyor: `FOR UPDATE SKIP LOCKED` + lease + `claim_token`; sonuç yazımı claim sahibine kısıtlı; lease güvenlik payı.
- Check-then-act (`existsBy` → `save`) yerine unique/partial unique, koşullu update veya lock.
- JVM-local state yok (rate limit, cache, "tek aktif kayıt", WebSocket üyeliği). İzinli JVM cache: kısa TTL + bayatlık toleransı + yorumla gerekçe.
- Bilinçli single-instance davranış yorumla belgelenmiş.

## Exception ve Log
- Throw/rethrow öncesi tek structured log: `"<Olay> rejected: code=X reason=Y"`; kullanıcı kaynaklı 4xx WARN, altyapı/beklenmeyen ERROR.
- Ham `e.getMessage()`, body, token, PII loglanmıyor (`docs/ai/security-rules.md` Bölüm 4). Log'a özel neden `safeLogReason`'da, `details`'te değil.
- Normal Flow Logging: akış INFO'dan izlenebilir (`operation=`, `outcome=`); her metot loglanmıyor; batch'ler özetleniyor; boş poll turu loglanmıyor; commit görülmeden "success" yazılmıyor.
- Tek log stili (`"<Olay>: key=value"`); `[TAG]`/snake_case karışımı yok.
- `ErrorCode` yeni sabiti servisin bloğunda ve global tekil (`ErrorCodeUniquenessTest` çıktısını kanıt olarak yaz).

## Tracing ve Async Context
- Outbox satırında `traceparent/tracestate`; poller span'i bu bağlamdan başlıyor; MQ header'larına inject; span attribute'larında PII yok.
- `@Async`/executor kullanımında context propagation korunuyor.

## Sistem Parametreleri
- Admin'in değiştirebileceği iş kuralı değeri config/env/`@Value` ile **değil**, `SystemParameterProvider` ile okunuyor; key yalnız enum.
- Fail politikası: key yok / değer bozuk → fail-closed; kaynak erişilemez → bounded-staleness (grup `criticality`'sine göre) — kod içi default/yml fallback **yok**.
- Tazelik doğru seçilmiş: kullanıcı girdisini doğrulayan yazma `freshGroup`; kalıcı sonuçta revizyon snapshot'ı; birlikte anlamlı key'ler aynı revizyondan; TX/lock dışında okuma; worker her turda yeniden okuyor.
- Değer düşürülünce mevcut veriyi uzlaştıran worker var mı?
- Yeni key için: enum + migration + registry kuralı + `usage_status` + tutarlılık testleri.

## Entity ve Repository
- Entity suffix'siz; `@Enumerated(STRING)`; `Instant` ↔ `TIMESTAMPTZ`; `@Data`/`@ToString` yok; hassas alanlarda `@ToString.Exclude`.
- ID üretimi tek yöntem (UUIDv7, platform generator); `@PrePersist`/elle atama yok.
- Optimistic lock gereken aggregate'te `@Version`; 409 handler.
- Kilit sırası birden çok kilit alan akışta belgelenmiş; lock timeout hint'i var.
- Native sorgular tam nitelikli `<schema>.<tablo>` ve **yalnız kendi şeması**.

Çalıştır (proje kökünden):
- `mvn -q -o -B -ntp -pl <modül> -am test` (`ArchitectureRulesTest`, `ErrorCodeUniquenessTest`, `ConfigDriftTest` yeşil olmalı; `-o` yalnız offline ortamda); migration değiştiyse `node scripts/flyway-immutability.js check --base <base>` ve çıktıdaki `OK`/`IHLAL`/`DOGRULANAMADI` satırını kanıt olarak aynen yaz. Not: `-am` ile upstream modülde `failIfNoTests=true` sabitse filtreli koşu düşer; `-fn` ekle veya filtresiz `test` koş.
- Build koşamazsa ilgili iddiayı `needs verification` yaz; bir test başka hata yüzünden düştüyse (ör. YAML parse) kalan iddiaları da `needs verification` işaretle.

Çıktı (8 madde):
1. **Ne değişti** (2–4 cümle, dosya listesi).
2. **Neden önemli** (risk: doğruluk / güvenlik / operasyon / performans).
3. **Bulgular**: her biri `severity (BLOCKER|HIGH|MEDIUM|LOW) · dosya:satır · kanıt · düzeltme`; severity'ye göre sıralı. 5–7. maddede bulgu yoksa o maddeye `Bu kapsamda bulgu yok.` yaz.
4. **Testler**: eksik test case'leri (negatif, yetki, concurrency, log privacy).
5. **Log doğrulaması**: hassas veri sızıntısı var mı; seviye politikası doğru mu.
6. **INFO akış görünürlüğü**: akış DEBUG açmadan izlenebilir mi.
7. **Parametre ve sıcak yol uyumu**: parametre kuralları; sıcak yol uzak çağrı sayısı (tablo güncellendi mi).
8. **Nihai karar:** `APPROVE` / `APPROVE WITH NON-BLOCKING COMMENTS` / `REQUEST CHANGES` / `BLOCK` (`BLOCK` = zorunlu güvence ihlali veya kırmızı yapısal test).
