# Ne değişti

<!-- 2–5 madde. Neden yapıldığı; ne yapıldığı değil. -->

# Etki

- [ ] Contract (api modülü, OpenAPI diff): değişti / değişmedi — breaking: evet / hayır
- [ ] Migration: var / yok — `node scripts/flyway-immutability.js check --base origin/<hedef>` ✅
- [ ] Config/env/secret yüzeyi: `env_file` · `config/<svc>.yml` · `application-local.yml` · Dockerfile — güncellendi / etkilenmedi
- [ ] Yeni uzak senkron çağrı: var / yok — varsa kritik akış kaydı güncellendi (`docs/ai/repo-context.md` Bölüm 3); varsayılan (≤1) aşılıyorsa ADR: `…`
- [ ] Yeni/değişen internal uç: var / yok — varsa delegasyon matrisi satırı (`repo-context.md` Bölüm 3.1)
- [ ] Yeni event/komut/tüketici/şema/enum/claim değişikliği: var / yok — **rollout sözleşmesi** (referans Bölüm 18.4): tür: `…` · sıra: `…` · kırıcıysa uyumluluk matrisi (yeni→eski / eski→yeni / yeni→yeni / eski→eski): `…`
- [ ] İstemciyi etkiliyor: evet / hayır — `docs/<client>-<feature>-integration-vN.md`: `…`
- [ ] Güvenlik/privacy etkisi: var / yok — özet: …

# Çalıştırılan review skill'leri ve kararları

<!-- Her satır: skill adı → nihai karar (sabit enum). Çalıştırılmadıysa "atlandı: <neden>". -->

| Skill | Karar |
|---|---|
| verso-spring-code-review | |
| verso-security-review | |
| verso-db-migration-review | |
| verso-api-contract-review | |
| verso-architecture-boundary-review | |
| verso-environment-impact-review | |
| verso-operation-consistency-review | |
| verso-resilience-review | |
| verso-event-design-review | |
| verso-test-writer | |
| verso-llm-review (model/prompt/retrieval/mod değişikliklerinde) | |
| verso-release-readiness-review (release PR'larında) | |

# Doğrulama

<!-- Yapısal (ArchUnit/enforcer/drift/immutability) ve davranışsal (outbox/inbox/saga/restart) ayrı yazılır. Davranışsal her PASS için kanıt kaydı. -->

**Yapısal:** `mvn verify` (commit `…`, CI job `…`): PASS / FAIL — test sayısı: `…` (0 = başarısız)

**Davranışsal kanıt kaydı** (yalnız tutarlılık/olay/saga değişikliklerinde):

| Senaryo | Seviye (1–4) | Test / komut | Commit | Ortam | Sonuç (link) | Tarih |
|---|---|---|---|---|---|---|
| | | | | | | |

# Net kanıt bulunamayan alanlar

<!-- Doğrulanamayan varsayımlar; boşsa "yok". -->
