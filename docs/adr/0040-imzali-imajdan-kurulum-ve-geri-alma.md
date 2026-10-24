# ADR-0040: İmzalı imajdan kurulum, yükseltme sırası ve geri alma

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras (referans Bölüm 18.1: "deploy yalnız pull yapar, imza doğrulanır"; 18.4: yükseltme sırası ve geri dönüş sınırı)
- **İlgili eşik (referans Bölüm 24):**
  - İlk gerçek müşteri kurulumu ya da ilk `v*` etiketi: offline doğrulama ve elle yedekten dönüş provası (aşağıda "Bilinen sınırlar").
  - Bir PR'da `-- contract:` işaretli ikinci migration: contract'ın ayrı sürüme bölünmesi kuralı gözden geçirilir.

## Bağlam

- **Yayım yarısı vardı, kurulum yarısı yoktu.** `release.yml` imajı digest ile GHCR'ye iter, cosign ile anahtarsız imzalar ve CycloneDX atestasyonu ekler (ADR-0030 madde 6). Ama `compose.yaml` hâlâ `build: .` ile yerelde build eder (ADR-0007 #42, geliştirme için doğru). Müşteri imzalı imajı çalıştıramaz ve doğrulayamaz; referans 18.1 "prod sunucusunda build yapılmaz, deploy imzayı doğrular" der.
- **Yükseltme ve geri alma yazılı değildi.** README'de ADR'ye bağlı yükseltme notları var, ama sıra (pull → doğrula → migrate → uygulama), "eski uygulama durmuşken migrate" (ADR-0028 notu), geri alma ve ne zaman ileri düzeltileceği tek yerde değil. Yedek alma ve restore provası var; canlıya geri yükleyen betik yok.
- **Migration'lar geriye uyumluluk açısından denetlenmiyordu.** Referans 18.4: contract değişikliği (silme, yeniden adlandırma) ayrı sürüme bölünür. Şu an `V*.sql` dosyaları arasında yalnız `document/V2` bir `DROP` içerir (aşağıda). CI hiçbir yeni `DROP`/`RENAME`'i görünür kılmıyor.
- **Kısıt.** Docker'a bu değişiklik içinde dokunulmaz (yalnız `docker compose config`); cosign gerçek bir imajla bu ortamda çalıştırılmadı (ilk `v*` etiketi yok).

## Seçenekler

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| **A. Overlay (`deploy/compose.release.yaml`) + doğrulama betiği + runbook** | Mevcut desen (`compose.gpu/docling/cloud.yaml`); `compose.yaml` ve geliştirici akışı değişmez; tek zorunlu değişken | Müşteri iki `-f` yazar; digest biçimini compose denetleyemez (betik denetler) | Düşük |
| B. `compose.yaml`'ı imaja çevir, geliştirme için ayrı dosya | Müşteri tek dosya | Geliştirici akışı ve CI (`--build`), ADR-0007 #42 değişir; geniş etki | Orta |
| C. Doğrulamayı yalnız runbook'a yaz | Kod yok | Atlanır; test edilemez | Sıfır |
| D. `DROP`/`RENAME` migration'ını CI'da tamamen yasakla | En basit | Contract sürümü hiç yazılamaz | Düşük |

## Karar

1. **Overlay.** `deploy/compose.release.yaml`: `verso-app` ve `migrate` için `image: ${VERSO_IMAGE:?…}` (varsayılan **yok**: unutmak hata verir, yerel build'e sessizce düşmez), `pull_policy: missing` (taban `migrate` için `never` der), `verso-app` için `build: !reset null`. Başka hiçbir şeye dokunmaz. `ComposeConfigTest` bunu kilitler.
2. **Tag reddi.** Compose değerin biçimini denetleyemez; `scripts/verify-release.sh` `…@sha256:<64 hex>` dışındaki her değeri (tag, digest'siz ad, kısa digest) cosign'a hiç gitmeden reddeder; `verify-release.test.js` bunu sahte cosign ile sınar.
3. **Doğrulama.** `verify-release.sh`: `cosign verify` ve `cosign verify-attestation --type cyclonedx`; kimlik `^https://github\.com/<owner>/<repo>/\.github/workflows/release\.yml@refs/tags/v` (`release.yml` `SIGNER_IDENTITY` ile aynı), veren `https://token.actions.githubusercontent.com`. Varsayılan depo `doguyaras/Verso`; fork'lar `VERSO_RELEASE_REPO` ile değiştirir. Herhangi bir adım başarısızsa çıkış kodu sıfırdan farklıdır. Çevrimdışı: `VERSO_VERIFY_OFFLINE=1` (`--offline=true`) ve `COSIGN_TRUSTED_ROOT` (`--trusted-root`); bayrakların cosign v3.1.4 ile gerçek bir air-gapped makinede çalıştığı **doğrulanmadı**.
4. **Yükseltme sırası** (runbook): ön kontroller (yedek, doğrulama, disk) → `pull` → `verify` → `migrate` (eski uygulama durmuş) → `app` → `edge` → `smoke` (`qa-smoke.sh`, `prove-local-mode.sh`) → yedeği tut. Aşamalı (rolling) yayın tanımlı değil (tek `verso-app`).
5. **Migration politikası: expand/contract.** Önceki uygulama sürümü yeni şemada çalışabilmeli. Contract (silme, yeniden adlandırma, tip değişimi) ayrı sonraki sürümde. CI (`scripts/migration-contract.js`, `ci.yml`): PR'da **yeni eklenen** bir `V*.sql` yorum ve tırnaklı metin dışında `DROP` ya da `RENAME` içeriyorsa `-- contract: <en az 10 karakter gerekçe>` satırı zorunludur; yoksa kırmızı. Kapı bir yasak değil, bilinçli olma zorunluluğudur (D reddedildi). Mevcut dosyalar (base'te) taranmaz (immutability zaten korur). Yalnız `DROP`/`RENAME` aranır: `ALTER COLUMN TYPE` veya `SET NOT NULL` aynı sonucu doğurur ama bu kapıda **yoktur** (kabul edilen boşluk; `verso-db-migration-review` ve inceleme yakalar).
6. **Geri alma.** Önceki digest'i yeniden kur (`verify-release.sh` ile). Şema geriye uyumsuzsa yükseltme öncesi yedeğe dön; canlıya geri yükleyen betik **yok**, runbook elle adımları `restore-check.sh`'e dayandırır. Keycloak 26.8 şema yükseltmesi tek yönlüdür: paketli demo IdP için `keycloak` veritabanı ayrıca dökülür (Verso yedeği kapsamaz, ADR-0010). Ollama model volume'ü (ADR-0033) yükseltmede silinmez.
7. **İleri düzeltme / geri alma tablosu** runbook'tadır (ne zaman hangisi).

**Bugün geriye uyumsuz sayılan migration'lar (2026-10-24 taraması):** `DROP`/`RENAME` içeren tek dosya `document/V2__document_formats.sql` (`ck_document_failure_reason` kısıtını daha geniş olarak yeniden ekler; eski imaj için güvenli, yeni biçimler eski imajda tanımsız). `document/V4__collections.sql` söz diziminde ekleme ama **davranışta** geriye uyumsuzdur (eski imaj `document_principal` yazmaz; ADR-0028 notu). Ayrıntı runbook'ta.

Reddedilen: B (geliştirici akışını bozar), C (doğrulanamaz), D (contract sürümünü engeller).

## Sonuçlar

- **Olumlu:**
  - Müşteri yalnız cosign ile doğrulanmış, digest'li imajı çalıştırır; kaynak (kimin, hangi iş akışının ürettiği) ve SBOM bağlıdır.
  - Yükseltme ve geri alma tek runbook'ta; hangi migration'ın geri almayı kısıtladığı bellidir.
  - Yeni geriye uyumsuz migration PR'da kendini gösterir.
- **Olumsuz / kabul edilen risk:**
  - **Bilinen sınırlar:** (a) `verify-release.sh` gerçek bir imajla çalıştırılmadı (ilk `v*` etiketi yok); sahte cosign ile akış ve bayraklar test edildi. (b) Air-gapped doğrulama (offline + trusted root) elle denenmedi. (c) Canlıya yedekten dönüş elle ve provasızdır; RTO 1 saat hedefi bunun için kanıtsızdır. (d) Anahtarsız imza Rekor'a bağlıdır; `--offline` kaydın hâlâ var olduğunu kanıtlamaz.
  - **Compose `!reset`** (Compose 2.24+) gerekir; eski Compose `build` alanını silmez ve yine yerelde build eder.
  - **Tarayıcı tabanlı kapı değil.** Migration kapısı yalnız PR/feature dalında çalışır; doğrudan `develop`'a itilen dosya ilk dalda yakalanmadıysa yakalanmaz (immutability adımıyla aynı koşul).
  - İşaret satırı gerekçenin doğruluğunu değil varlığını denetler.
- **Etkilenen dosyalar:** `deploy/compose.release.yaml`, `scripts/verify-release.sh`, `scripts/verify-release.test.js`, `scripts/migration-contract.js`, `scripts/migration-contract.test.js`, `.github/workflows/ci.yml`, `verso-app/src/test/java/com/verso/ComposeConfigTest.java`, `.env.example`, `docs/runbooks/surum-yukseltme-ve-geri-alma.md`, `docs/runbooks/README.md`, `README.md`, `docs/decisions.md`, `docs/ai/repo-context.md`. Yeni ortam değişkeni: yalnız `VERSO_IMAGE` (kurulum anında export; `.env.example`'da yorum satırı), uygulama konfigürasyonu değişmez.
- **Geri alma yolu:** overlay, iki betik ve `ci.yml`'deki iki adım silinir; `compose.yaml` ve yayım akışı etkilenmez.

## Yeniden değerlendirme koşulu

İlk gerçek `v*` etiketinde doğrulama betiği imajı reddeder ya da yanlış kabul ederse; ilk müşteri air-gapped kurulumunda offline yol çalışmazsa; yedekten dönüş bir kez gerçekten kullanılırsa (betikleştirilir); ikinci `-- contract:` işaretli migration yazılırsa.
