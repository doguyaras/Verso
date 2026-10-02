---
name: verso-architecture-boundary-review
description: Use this skill before writing code that touches another module's data, adds a cross-service dependency, a new HTTP client, a read-model, or changes api/core dependency direction — and when reviewing such changes.
---

Modül sınırı ihlallerini ve bağımlılık yönünü `AGENTS.md` Bölüm 4–5, `docs/ai/operation-consistency.md`, `docs/ai/repo-context.md` Bölüm 2 (servis kimlik tablosu), 3 (sıcak yol), 3.1 (delegasyon matrisi) ve mimari referans (yolu `AGENTS.md` Bölüm 1'de; bu repoda `docs/architecture-reference.md`) Bölüm 1.1/3.3/4.6/5.6/10.1'e göre incele. Bu skill **kod yazılmadan önce** de çalıştırılır: ihtiyaç bir sınırı aşıyorsa alternatif önerilir ve kullanıcıya sorulur. Sorun yoksa: **"Bu kapsamda sınır bulgusu yok."** Girdi: diff = `git diff <base>...HEAD` + `git diff --stat` (base = hedef branch; PR diff'i verilmişse o); izlenmeyen (untracked) dosyalar da kapsamdadır. Severity: `BLOCKER` = veri sahipliği/bağımlılık yönü ihlali (`BLOCK` kuralları) → `BLOCK`; `HIGH` → `REQUEST CHANGES`; `MEDIUM`/`LOW` → `REQUEST CHANGES` veya gerekçeli `APPROVE`.
Sınırla iç içe bulguları (saga → operation-consistency, outbox/routing → event-design, timeout/CB/retry → resilience, kimlik kaynağı → security) tek satırda ilgili skill'e devret; kararı yalnız sınır bulguları belirler.

Kontrol et:

## Veri sahipliği
- Başka modülün tablosu, şeması, repository'si, entity'si, migration'ı okunuyor/yazılıyor mu? (native SQL'de başka şema adı, JPQL'de başka modül entity'si, `@Table(schema=…)` uyuşmazlığı). Yönetim servisi de dahil. **Varsa `BLOCK`.**
- Cross-schema FK/join var mı?
- DB rolü hatası "GRANT" ile mi çözülmüş? (**`BLOCK`**)
- Başka servisin verisi gerekiyorsa hangi yol seçilmiş: (a) hedefin public/internal API'si (yalnız yazma/rezervasyon türü, sıcak yol dışı okuma), (b) event ile read-model, (c) JWT claim — anotasyonun varlığı kanıt değildir: `@CurrentAccount` gibi özel parametre anotasyonu için bu modülde bir `HandlerMethodArgumentResolver` kayıtlı mı (`WebMvcConfigurer.addArgumentResolvers` veya classpath'teki platform auto-config; dosya:satır ile kanıtla)? Yoksa Spring parametreyi request'ten (query/path) bağlar → IDOR veya 500: **`HIGH`**, ayrıntılı değerlendirme `verso-security-review`'da. Sıcak yolda (a) ile okuma → `REQUEST CHANGES`, (b)/(c) öner.

## Bağımlılık yönü
- `*-api` → `*-core` bağımlılığı yok; `*-core` → başka `*-core` yok; `platform-*` → servis modülü yok.
- Yeni Maven bağımlılığı bu kuralları ihlal etmiyor (enforcer `bannedDependencies` geçiyor).
- Split package yok (api kökü `com.verso.<domain>.api`).
- Hedef servis başına tek client; client çağıranın `client/` paketinde; DTO hedefin api'sinden.

## Read-model kuralları
- Read-model tüketicinin kendi şemasında; kaynak başına `source_revision` ile koşullu UPSERT (tek `revision` kolonu yetmez); eskime eşiği ve "satır yok" davranışı yazılı; dışa açılmıyor; rebuild yolu var.
- Read-model'den **yazma** kararı (hak/stok) veriliyor mu? Bu yasak; saga/rezervasyon gerekir.

## Paylaşılan altyapı sözleşmeleri
- Redis key formatı başka serviste kopyalanmış mı? `RedisKeys` (projede varsa; platform-core) veya API/event'e çevir.
- RabbitMQ queue/exchange adı başka servisin sahasına giriyor mu (kendi queue'sunu tanımlıyor mu)?
- Parametre okuması yalnız kendi `SystemParameterProvider`'ı üzerinden mi (projede varsa; yoksa bu kontrol `N/A`)?

## Mimari şekil
- PR çapraz kesen bir mekanizma (argument resolver, interceptor, auth filter, `platform-*` auto-config) kullanıyor ve bağlaması bu modülde eksik mi? Anotasyon/arayüzün nerede kayıtlı olduğunu bul, classpath'te olduğunu doğrula; kanıtlamadan doğru yolu "doğru" sayma.
- Değişiklik "dağıtık monolit" sinyali üretiyor mu (yeni senkron zincir, ortak kütüphaneye servis-özel kod, birlikte deploy zorunluluğu)? ADR gerekiyor mu?
- Yeni servis/modül ekleniyorsa Bölüm 1.1 kararıyla tutarlı mı (modül olarak mı, servis olarak mı)?

Makine kanıtı (proje kökünden): `mvn -B -o -fn -pl <core> -am test -Dtest=ArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false` (enforcer + ArchUnit); `-am` upstream `failIfNoTests` yüzünden `-fn` ister. Migration değiştiyse `node scripts/flyway-immutability.js check --base <base>` (`OK`/`IHLAL`/`DOGRULANAMADI` satırını aynen yaz).

Çıktı:
1. **Sınır risk seviyesi:** `BLOCKER` / `HIGH` / `MEDIUM` / `LOW` / `OK`.
2. **İhlaller:** `dosya:satır/fonksiyon · SEVERITY · hangi kural · kanıt`.
3. **Alternatif:** her ihlal için sınır içinde kalan tasarım (API / event + read-model / claim / saga) ve maliyeti.
4. **Kod yazmadan önce sorulacak sorular** (belirsizlik varsa).
5. **Nihai karar:** `APPROVE` / `REQUEST CHANGES` / `BLOCK` — sınır ihlali `APPROVE WITH COMMENTS` alamaz. `BLOCKER` → `BLOCK`; `HIGH` → `REQUEST CHANGES`; `OK` → `APPROVE`.

Sorun yoksa bölüm 1 = `OK`, 2 = "Bu kapsamda sınır bulgusu yok.", 5 = `APPROVE`; 3–4 atlanır. Diff yoksa (kod öncesi): 2 yerine planlanan değişikliğin sınır riskleri (`dosya/paket · SEVERITY · kural`), 3–4 aynen.
