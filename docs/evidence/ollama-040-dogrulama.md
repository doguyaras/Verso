# Ollama 0.40.10 model deposu: doğrulama ve kanıt kaydı (ADR-0033)

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

## Künye

- **Tarih:** 2026-10-24
- **Branch:** `fix/doguyaras-ollama-040` (`origin/develop` `a24cc0c` üzerinden).
- **İmaj:** `ollama/ollama:0.40.10@sha256:4c5b27871a3239a717048f9b93057680a51b60c8119a7016aebb57f938e25e35`; önceki `0.35.1@sha256:292ee794…278c`.
- **Durum: canlı doğrulama bekliyor.** Makine aşırı yüklendiği için Docker kapatıldı; bölüm 1-2 `verso_ollama-models` volume'ünün **kopyasında** (`ollama-test`, canlı volume'e yazılmadı), bölüm 3 Docker'sız yapıldı.

## 1. Davranışsal (volume kopyası, CPU, `--network none` ya da internetli pull)

| Durum | Beklenen | Sonuç |
|---|---|---|
| 0.40.10, yazılabilir, 0.35 deposu | Açılır; `manifests-v2` boş kalır; embedding 200 | PASS (kosinüs 1,000000, 1024 boyut) |
| 0.40.10, yazılabilir, `gemma4:e2b` üretimi | 200, `pong` | PASS; ardından yaklaşık 2 dk içinde arka plan yükseltmesi olmadı |
| 0.40.10, `:ro`, boş `manifests-v2` | Önceki hata yeniden üretilir | PASS: `remove …/manifests-v2: read-only file system` |
| `ollama pull` (iki model, yazılabilir, internetli) | Blob'lar yeniden kullanılır, v2 manifesti yazılır | PASS: model blob'ları değişmedi; yeni dosyalar yalnız küçük manifest/config blob'ları |
| 0.40.10, `:ro`, dolu `manifests-v2`, `--network none` | Açılır; embedding 200 | PASS: kosinüs 1,000000 (0.35.1 vektörüne) |
| `ollama show --modelfile` (`:ro` sunucu) | Her iki modelin model katmanı digest'i | PASS: `sha256-6dd7ac24…`, `sha256-daec91ff…` pin'lerle aynı |
| `:ro` sunucuda `gemma4:e2b` üretimi | 200 | **Doğrulanmadı**: istek sürerken Docker durduruldu |

## 2. Yapısal (Docker'sız)

| Kontrol | Sonuç |
|---|---|
| `node --test scripts/ollama-pull.test.js` (sahte `ollama`; v1'de olup v2'de olmayan model çekilir, yanlış digest reddedilir, çevrimdışı yeniden başlatma) | PASS, 9 test |
| `ComposeConfigTest`: çalışan Ollama `:ro`, `models` ağı, `OLLAMA_NO_CLOUD`; pull aynı imajı ve yazılabilir volume'ü kullanır, Ollama pull'a bağlı | `./mvnw verify` ile aşağıda |
| `ImageVersionsTest`: imaj digest ile pinli | `./mvnw verify` ile aşağıda |

## 3. Canlı doğrulama bekliyor

Komutlar ADR-0033 "Canlı doğrulama bekliyor" bölümündedir (yedek, volume yedeği, `ollama-pull`, `ollama`, embedding kosinüsü, chat, `/actuator/health`, `scripts/qa-smoke.sh`, `scripts/prove-local-mode.sh`, Syft/Grype taraması). Geçene kadar:

- `.github/vuln-allowlist.yaml` Ollama kayıtları **silinmedi**.
- Tarama sonucu (0 / 0 / 83) bu kayıtta **iddia edilmez**; önceki denemenin ölçümüdür.
