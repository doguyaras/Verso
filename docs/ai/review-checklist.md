# review-checklist.md — Değişiklik Sonrası Kontrol Listesi

> Push öncesi çalıştırılacak skill'ler ve hangi değişiklikte hangisinin zorunlu olduğu. Skill'ler `.claude/skills/` (→ `.agents/skills/`) altındadır. Her skill'in nihai kararı PR şablonundaki tabloya yazılır.

## 1. Değişiklik türü → zorunlu skill'ler

| Değişiklik | Zorunlu | Koşullu |
|---|---|---|
| Her Java değişikliği | `verso-spring-code-review`, `verso-test-writer` | |
| Controller, filter, JWT, rate limit, log, dosya, privacy | `verso-security-review` | |
| `db/migration/` | `verso-db-migration-review` + `node scripts/flyway-immutability.js check` | |
| `*-api` DTO/enum/event, controller imzası, OpenAPI diff kırmızı | `verso-api-contract-review` | istemciyi etkiliyorsa `verso-client-integration-doc` |
| Yeni repository/entity/migration erişimi, yeni client, read-model | `verso-architecture-boundary-review` | |
| Yeni property/env/secret/port/audience/internal uç/flag | `verso-environment-impact-review` | |
| Servisler arası yazma, outbox, saga | `verso-operation-consistency-review` | |
| Yeni uzak senkron çağrı, timeout/circuit breaker değişikliği, sıcak yol | `verso-resilience-review` | |
| Yeni event/komut, envelope, tüketici, şema değişikliği | `verso-event-design-review` | |
| Release PR (`release`/`main`'e) | `verso-release-readiness-review` | |
| Model çağrısı, prompt, retrieval, embedding, chunking, atıf, local/cloud modu, egress config (Verso eki) | `verso-llm-review` | sıcak yol değiştiyse `verso-resilience-review` |

## 2. Makine kontrolleri (CI'da; lokalde de çalıştırılır; komutlar proje kökünden çalışır)

```bash
./mvnw -B -ntp verify                                    # testler + ArchUnit + ErrorCode tekilliği + config drift (Verso: tek reactor, Maven Wrapper)
./mvnw -B -ntp -pl <modül> -am test -Dtest=<Sınıf> -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false   # filtreli koşu (ADR-0007 #6)
node scripts/flyway-immutability.js check --base origin/<hedef>
node scripts/config-lint.js <değişen *.yml / *.properties / *.env* dosyaları>   # secret key'de ${ENV:literal} fallback ve local dışı düz secret yok (0/1/3)
bash scripts/gitleaks-check.sh all .                   # gitleaks 8.24.3: tüm geçmiş (git) + çalışma ağacı (dir), --redact (0/1/3)
node --test scripts/panel.test.mjs                     # panel (ADR-0015): rol matrisi, PKCE, oturum akışı, güvenlik kuralları
```

## 3. Öz-kontrol (skill'lerden bağımsız)

- [ ] Yeni uzak senkron çağrı sıcak yola eklendi mi? Eklendiyse kritik akış kaydı (`repo-context.md` Bölüm 3: bütçe, gerekçe, eskilik, düşünce davranış) güncellendi; varsayılan (≤1) aşılıyorsa ADR.
- [ ] Yeni/değişen internal uç delegasyon matrisinde (`repo-context.md` Bölüm 3.1).
- [ ] Yeni tüketici: inbox satırı + iş aynı TX; ack commit sonrası.
- [ ] Olay/uç/şema değişikliği için rollout sözleşmesi satırı PR'da (referans Bölüm 18.4).
- [ ] Yeni outbox satırı domain transaction'ında mı (MANDATORY)?
- [ ] Hata yolu: throw öncesi structured log; kullanıcı 4xx → WARN.
- [ ] Yeni `@RequestBody` → `@Valid`; binding adları açık.
- [ ] Config yüzeyleri birlikte güncellendi (`env_file`, `config/<svc>.yml`, `application-local.yml`).
- [ ] Testler: negatif + yetki + concurrency (poller/check-then-act) + log privacy.
- [ ] `docs/ai/repo-context.md` ve README kimlik tablosu güncel.
- [ ] Mimari karar verildiyse ADR açıldı.

## 4. Karar formatları

- Review skill'leri: `APPROVE` / `APPROVE WITH NON-BLOCKING COMMENTS` / `REQUEST CHANGES` / `BLOCK`.
- Tutarlılık ve release-readiness: `PASS` / `FAIL` / `BLOCKED`.
- Saga uygunluğu: `saga unnecessary` / `existing saga suitable` / `extension required` / `decision blocked`.
- Sorun yoksa sabit cümle: **"Bu kapsamda bulgu yok."** Spekülatif bulgu üretilmez; kanıt yoksa `needs verification`.
