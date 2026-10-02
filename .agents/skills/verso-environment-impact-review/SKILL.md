---
name: verso-environment-impact-review
description: Use this skill when a change adds or modifies configuration, environment variables, secrets, ports, audiences, issuers, rate-limit scopes, internal endpoints, feature flags, service URLs, tracing/observability settings, Dockerfile or deploy definitions.
---

Config/env etkisini `AGENTS.md` Bölüm 9 ve mimari referans Bölüm 15, 18 ve 8.6–8.7'ye göre incele. Amaç: bir yüzeyde eklenip diğerinde unutulan key (drift) ve deploy'da patlayan ayar. Sorun yoksa: **"Bu kapsamda environment bulgusu yok."** Girdi: diff = `git diff <base>...HEAD` + `git diff --stat` (base = hedef branch; PR diff'i verilmişse o); izlenmeyen (untracked) dosyalar da kapsamdadır. Gerçek yollar (`config/<svc>.yml`, deploy `env_file`) `ConfigDriftTest` sabitlerinden veya servis README'sinden alınır (örn. `order-core/src/main/resources/config/order.yml`, `deploy/prod.env.example`). Karar: `BLOCK` (= `BLOCKER`) = gerçek secret değeri PR'da / veri kaybı / geri alınamaz etki; `REQUEST CHANGES` (= `HIGH`) = drift, secret literal fallback, deploy'u kıran config/migration; `APPROVE WITH NON-BLOCKING COMMENTS` = yalnız `MEDIUM`/`LOW` ve doküman eksikleri.

Kontrol et:

## Birlikte güncellenmesi gereken yüzeyler
Yeni/değişen her key için tabloyu doldur:

| Key | `config/<svc>.yml` | `application-local.yml` | deploy `env_file` / `secrets/<env>.enc.yaml` | compose `secrets:` | Dockerfile (yalnız build/runtime) | Gateway route | Prometheus/alert | Not |
|---|---|---|---|---|---|---|---|---|

- Hücre: `var` · `eksik` (bulgu) · `n/a` (yüzey bu repoda yok) · `?` (kanıt yok). Yüzey repoda hiç yoksa (Dockerfile, compose, gateway, Prometheus, SOPS, Config Server) tek satırda `net kanıt bulunamadı` yaz; madde madde gerekçelendirme.
- Eksik hücre = bulgu. `ConfigDriftTest` sabitlerini oku (`MIRRORED_KEY_PREFIXES`, taranan dosyalar); yeni key'in prefix'i listede değilse ya da secret-fallback kontrolü yalnız `config/<svc>.yml`'i tarıyorsa ayrı bulgu yaz.
- Değişen her YAML dosyasını parse et (`python3 -c 'import yaml,sys;yaml.safe_load(open(sys.argv[1]))' <file>`); base dosya newline'sız bitiyorsa eklenen blok önceki satıra yapışır.
- PR şablonundaki "Etki" beyanlarını (config/env/secret yüzeyi, güvenlik etkisi, rollout sırası) diff kanıtıyla tek tek karşılaştır; yanlış beyan = bulgu.
- Key adı üç ortamda **aynı**; profil dosyaları yalnız ortam farkı taşıyor, iş config'i base'de.
- Dockerfile'a config için `ENV` **eklenmemiş**.
- Secret: `${ENV:literal}` fallback yok; `/run/secrets` yolu; SOPS dosyasına eklenmiş; `.dockerignore` kapsıyor; PR'da değeri yok. Config Server'a konmamış.
- Admin'in değiştirebileceği iş kuralı config'e değil parametre kataloğuna gitmiş (Bölüm 14); rate-limit kuralları (`rate-limit.rules.<scope>`) altyapı config'idir, admin panelinden değişecek iş eşiği kataloğa gider.

## Servisler arası
- Yeni bağımlılık starter'ı (amqp, redis, jdbc) eklendiyse bağlantı config'i (`spring.rabbitmq.*` vb.) her yüzeyde var mı? Kodda sabit URL / `RestClient.create("http://...")` config'i by-pass ediyor mu?
- Yeni internal uç: hedefin `service-jwt.internal-access` kuralı dar ve catch-all'dan önce; local + deploy'da birlikte.
- Yeni client: `spring.http.serviceclient.<grup>.base-url` + timeout + circuit breaker/bulkhead config'i her ortamda; `services.<svc>.base-url` compose servis adıyla uyumlu.
- Yeni audience/issuer: JWKS'e public key + `kid`; doğrulayanların `iss` listesi; gateway route `metadata.audience`.
- Yeni rate-limit scope: `rate-limit.rules.<scope>` local + deploy; fail politikası tablosuna satır.
- Yeni event/queue: RabbitMQ definitions (queue/DLQ/binding) local + deploy; tüketici önce deploy sırası deploy notunda.

## Çalışma zamanı ve replica
- Değişiklik instance-local dosya/dizin varsayıyor mu? Sticky session gerektiriyor mu? (multi-instance kuralı)
- Yeni `@Scheduled`/cron dağıtık kilitli mi?
- Bellek/CPU etkisi: compose `mem_limit`, JVM `MaxRAMPercentage`, Hikari havuzu, PgBouncer pool'u güncellendi mi; kapasite tablosu (README) uyumlu mu?
- Healthcheck/readiness yeni bağımlılığı (DB, broker, Redis) kapsıyor mu?

## Gözlemlenebilirlik
- Yeni servis/uç: Prometheus scrape hedefi, Alloy log kaynağı, SLO/pano, alarm kuralı (özellikle yeni outbox/queue için `outbox_oldest_pending_age_seconds`, DLQ derinliği).
- Tracing: sampling, OTLP endpoint, yeni async sınırda `traceparent` taşınıyor.
- Yeni log alanı structured JSON'a uyuyor; label kardinalitesi artmıyor.

## Deploy
- Deploy sırası (sahip → tüketici → auth → gateway) yeni değişiklik için doğru; migration expand/contract ile uyumlu. Migration değişikliği varsa `node scripts/flyway-immutability.js check --base <PR base>` koştur (proje kökünden; `OK`/`IHLAL`/`DOGRULANAMADI` satırını aynen yaz): base'teki `V<n>` değişikliği deploy'da checksum mismatch'tir.
- Rollback yolu: önceki digest ile geri dönüldüğünde yeni config eski image'ı bozar mı?
- Image build CI'da; `.dockerignore`; non-root; healthcheck.

Çıktı:
1. **Etki özeti** (2–4 cümle) ve etkilenen yüzey tablosu (yukarıdaki).
2. **Güncellenen dosyalar** (kanıtla) / **eksik dosyalar** (bulgu). Tablo yeterliyse 2 ve 3 yalnız tabloya sığmayanları listeler.
3. **Kanıtsız/doğrulanamayan yüzeyler** (`net kanıt bulunamadı`).
4. **Güvenlik etkisi** var mı (secret, audience, allowlist) — varsa `verso-security-review`'a yönlendir.
5. **Doğrulama komutu (proje kökünden):** `mvn -B -ntp -pl <svc>-core -am verify` (`ConfigDriftTest` dahil) + `node scripts/flyway-immutability.js check --base <base>` + `docker compose config` (compose varsa). Not: `-am` ile upstream modülde `failIfNoTests=true` sabitse filtreli koşu düşer; `-fn` ekle veya filtresiz `test` koş.
6. **Nihai karar:** `APPROVE` / `APPROVE WITH NON-BLOCKING COMMENTS` / `REQUEST CHANGES` / `BLOCK`.
