# Ollama 0.40.10 model deposu: doğrulama ve kanıt kaydı (ADR-0033)

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

## Künye

- **Tarih:** 2026-10-24
- **Branch:** `fix/doguyaras-ollama-040` (`origin/develop` `a24cc0c` üzerinden).
- **İmaj:** `ollama/ollama:0.40.10@sha256:4c5b27871a3239a717048f9b93057680a51b60c8119a7016aebb57f938e25e35`; önceki `0.35.1@sha256:292ee794…278c`.
- **Durum: canlı yığında doğrulandı** (bölüm 3). Bölüm 1 volume kopyasında, ilk turda Docker kapalıyken yapıldı; bölüm 3 çalışan yığında.

## 1. Davranışsal (volume kopyası, CPU, `--network none` ya da internetli pull)

| Durum | Beklenen | Sonuç |
|---|---|---|
| 0.40.10, yazılabilir, 0.35 deposu | Açılır; `manifests-v2` boş kalır; embedding 200 | PASS (kosinüs 1,000000, 1024 boyut) |
| 0.40.10, yazılabilir, `gemma4:e2b` üretimi | 200, `pong` | PASS; ardından yaklaşık 2 dk içinde arka plan yükseltmesi olmadı |
| 0.40.10, `:ro`, boş `manifests-v2` | Önceki hata yeniden üretilir | PASS: `remove …/manifests-v2: read-only file system` |
| `ollama pull` (iki model, yazılabilir, internetli) | Blob'lar yeniden kullanılır, v2 manifesti yazılır | PASS: model blob'ları değişmedi; yeni dosyalar yalnız küçük manifest/config blob'ları |
| 0.40.10, `:ro`, dolu `manifests-v2`, `--network none` | Açılır; embedding 200 | PASS: kosinüs 1,000000 (0.35.1 vektörüne) |
| `ollama show --modelfile` (`:ro` sunucu) | Her iki modelin model katmanı digest'i | PASS: `sha256-6dd7ac24…`, `sha256-daec91ff…` pin'lerle aynı |
| `:ro` sunucuda `gemma4:e2b` üretimi | 200 | Kopyada doğrulanamadı (Docker durduruldu); canlı yığında PASS (bölüm 3) |

## 2. Yapısal (Docker'sız)

| Kontrol | Sonuç |
|---|---|
| `node --test scripts/ollama-pull.test.js` (sahte `ollama`; v1'de olup v2'de olmayan model çekilir, yanlış digest reddedilir, çevrimdışı yeniden başlatma) | PASS, 9 test |
| `ComposeConfigTest` (15 test): çalışan Ollama `:ro`, `models` ağı, `OLLAMA_NO_CLOUD`; pull aynı imajı ve yazılabilir volume'ü kullanır, Ollama pull'a bağlı | PASS |
| `ImageVersionsTest` (3 test): imaj digest ile pinli | PASS |

## 3. Canlı yığın (2026-10-24, GPU bindirmesi, `-p verso`, bu branch'in dosyalarıyla)

| Adım | Sonuç |
|---|---|
| Yedek `backup once`; model volume kopyası | PASS; kopya hepsi geçince silindi |
| `ollama-pull`, ilk koşu | **FAIL** → düzeltildi: 0.40 `manifests-v2` girdisini sembolik bağ olarak yazar, `find -type f` bulamadı (`bge-m3:567m does not match the pinned digest`); `! -type d` ile giderildi, sahte CLI testi sembolik bağ yazar |
| `ollama-pull`, ikinci koşu | PASS: `bge-m3:567m already present`, `gemma4:e2b ready`, çıkış 0 (512 MB sınırı yetti) |
| `ollama` 0.40.10 | PASS: healthy, logda `read-only` yok, `ollama ps` iki model `100% GPU` `Forever`, volume `RW: false` |
| Embedding kosinüsü (0.35.1 vektörüne) | PASS: 1,000000 |
| `gemma4:e2b` chat, salt okunur bağlamada | PASS: 200, `pong` |
| `verso-app` | PASS: healthy |
| `scripts/qa-smoke.sh` | PASS: `found=true`, atıf 1 |
| `scripts/prove-local-mode.sh` | PASS: egress yok |
| Syft v1.55.0 + Grype v0.121.0, `vuln-gate.js` | PASS: 0.35.1 10 / 53 / 194 → 0.40.10 0 / 0 / 83 (2925 bileşen, düzeltmesi olan 0); Ollama allowlist kayıtları silindi, `CVE-2025-63389` kaydı kaldı |
| `scripts/eval.mjs` (33 soru) | Koşulmadı: alt küme seçeneği yok, `verso-ci` belgelerini siler |
