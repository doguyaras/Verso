# ADR-0030: Aylık SBOM üretimi, zafiyet taraması ve imaj imzalama

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras (referans: aylık SBOM + tarayıcı kapısı önerisi, araştırma notları `technologies_dependencies.md` Bölüm 10)
- **İlgili eşik (referans Bölüm 24):**
  - Taramanın zorunlu kontrole çevrilmesi: üç ardışık ayda yanlış pozitif ya da altyapı kaynaklı (ağ, veritabanı indirme) kırmızı çalışma olmaması.
  - Allowlist'te aynı anda üçten fazla geçerli kayıt.
  - Yaması yalnız ticari sürümde olan bir CVE (`security-rules.md` 9: upgrade tetikleyicisi).

## Bağlam

- **Yukarı akışta kanıt yok.** Araştırma notları, Verso'nun paketlediği hiçbir imaj (Keycloak, pgvector, Temurin, Ollama, TEI, docling) için cosign imzası ya da SBOM kanıtı bulamadı; sonuç: digest'le sabitle, kendi SBOM'unu üret, tara ve kendi imajlarını imzala (`technologies_dependencies.md` Bölüm 10). Küresel rapor aylık "SBOM farkı + tarayıcı kapısı"nı asgari yığın tazeleme düzeni sayar.
- **Mevcut durum.** Her imaj `@sha256` ile pinli (`ImageVersionsTest`); bağımlılık taraması elle yapılıyor (`docs/versions.md`: OSV, 182 bağımlılık). `release.yml` imajı GHCR'ye SBOM ve provenance ile itiyor ama imzalamıyor; zafiyet taraması hiçbir iş akışında yok. Yani yama zamanı geçse de kimse fark etmiyor: CVE'ler ancak biri bakınca yakalanıyor (Keycloak, nginx, Boot yükseltmeleri 2026-10-24'te elle yapıldı).
- **Kısıtlar.** Zorunlu kontroller (`backend`, `scripts-and-hooks`) yavaşlamaz ve yeni bir CVE ilgisiz bir PR'ı kırmamalıdır. Üçüncü taraf action'lar SHA'ya pinli, izinler en dar (`security-rules.md` 9). Kullanılan araçlar açık kaynak ve Apache 2.0 olmalı.
- **Verso imaj yayımlıyor.** `v*` etiketinde `ghcr.io/<owner>/verso` (`release.yml`); bu yüzden imzalama atlanmaz, eklenir.

## Seçenekler

**Tarayıcı:**

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| **A. Syft (SBOM) + Grype (tarama), ikisi de digest'li konteyner olarak** | Üçüncü taraf action yok; release'in kullandığı BuildKit SBOM tarayıcısı zaten Syft; Grype CycloneDX SBOM'u doğrudan tarar; Apache 2.0 | İki araç; Grype'ın bulguları OSV'den farklı olabilir | Düşük |
| B. Trivy (`trivy-action`) | Tek araç, hem SBOM hem tarama; Apache 2.0 | Action zincirine güven (tag/bağımlılık zinciri; `tj-actions` olayı, `ci.yml` başlığı); konteyner olarak da çalışır ama SBOM üretimi ve taraması aynı aracın görüşüne bağlı | Düşük |
| C. OSV-Scanner | `security-rules.md` 9 zaten adını geçirir; Java'da güçlü | İmaj katman taraması zayıf; CycloneDX girişi sınırlı | Düşük |
| D. Dependabot / GitHub Advisory yalnız | Hazır | İmajların işletim sistemi paketlerini görmez; SBOM üretmez | Sıfır |

**Kapı kuralı:**

| Seçenek | Artı | Eksi |
|---|---|---|
| **A. CRITICAL her zaman; HIGH yalnız düzeltmesi varsa; geçerli allowlist kaydıyla örtülmeyen** | Görev tanımına ve `security-rules.md` 9'a uyar; düzeltilemeyen HIGH gürültü yapmaz, ama CRITICAL asla gizlenmez | Düzeltmesiz HIGH'lar kapıya takılmaz (raporda sayıları görünür) |
| B. Her HIGH | Daha sıkı | Düzeltilemeyecek bulguyla her ay kırmızı; ekip kırmızıya alışır |

**Allowlist biçimi:** Grype'ın `.grype.yaml` `ignore` bölümü yerine kendi dosyamız (`.github/vuln-allowlist.yaml`) ve kendi kapımız (`scripts/vuln-gate.js`): son kullanma tarihi ve gerekçe zorunludur, tarihi dolan kayıt kapıyı kırar ve birim testi düşer. Grype'ın kendi `ignore` mekanizması süre tanımaz.

## Karar

1. **SBOM.** Java derlemesi için CycloneDX Maven eklentisi 2.9.3 (Apache 2.0; `pom.xml` `pluginManagement`, sürüm özelliği olarak sabit, bir faza bağlı değil: normal `verify` yavaşlamaz), `makeAggregateBom` ile CycloneDX 1.6 JSON; derleme+runtime kapsamı, test kapsamı yok. Her imaj için Syft v1.55.0, `compose.yaml`'daki ve `Dockerfile` çalışma tabanındaki **digest'ten**, imajı çalıştırmadan kayıttan okur (CycloneDX 1.6 JSON). Taranan imaj listesi `scripts/sbom-images.js` ile `compose.yaml`'dan türer (elle liste yok; sapamaz) ve digest'siz imajda hata verir.
2. **Tarama.** Grype v0.121.0, SBOM'u tarar. Kapı `scripts/vuln-gate.js`: CRITICAL ve düzeltmesi olan HIGH başarısız; `--min-components` ve `matches` kontrolü boş ya da bozuk girdiyi "temiz" saymaz (çıkış 3, fail-closed).
3. **İş akışı.** `.github/workflows/supply-chain-scan.yml`: ayın 1'i 05:17 UTC, `workflow_dispatch`, ve `pom.xml`/`compose.yaml`/`Dockerfile`/`docs/versions.md`/allowlist/kapı betiği değişen PR ve push'lar. `permissions: contents: read`, secret yok, Syft ve Grype `@sha256` pinli konteynerler, kullanılan action'lar mevcut iş akışlarındakilerle aynı SHA'lar. SBOM'lar 90 gün artifact; iş özeti kısa tablo. **`ci.yml`'e dokunulmaz**; `backend` ve `scripts-and-hooks` aynen kalır. **Bu iş akışı zorunlu kontrol değildir**: kırmızı koşu bir iş kaydıdır, birleştirmeyi engellemez (eşik yukarıda). Allowlist süresi testi (`scripts/vuln-gate.test.js`) takvime bağlı olduğu için zorunlu `ci.yml`'e konmaz; bu iş akışının `plan` işinde koşar.
4. **Allowlist.** `.github/vuln-allowlist.yaml`: `id`, isteğe bağlı `scope`/`package`, `reason` (≥20 karakter), `expires`. En fazla ~6 ay; geçmiş tarihli kayıt susturmaz, kapıyı ve testi kırar. `package` bir ön ek kalıbı (`golang.org/x/*`) olabilir. **Paket geneli istisna** (`id: "*"`) yalnız `scope` ve `package` ile birlikte yazılabilir ve en fazla ~2 ay sürer: elle yamalayamadığımız yukarı akış imajındaki bayat bir Go araç zinciri onlarca kimlik üretir ve kimlik kimlik yazmak okunmaz bir liste yapar; karşılığında sonradan çıkan yeni bulguları da o süre boyunca gizler, bu yüzden süre kısadır ve her kayıt çaresini (hangi yükseltme) gerekçesinde söyler. Kimliği belli, düzeltmesi olmayan bulgular kimlikle yazılır.
5. **İlk tarama (2026-10-24, `develop`).** Java derlemesi ve Temurin geçti; 11 imajın dokuzu gerçek bulgu taşıyordu (Ollama 10 CRITICAL, pgvector 4, Grafana 42, Prometheus 6, Alertmanager 6, Loki 3, Alloy 3, Flyway 2, Keycloak 1; ayrıntı `docs/versions.md`). Hiçbiri sessizce bırakılmadı: hepsinin çaresi daha yeni bir yukarı akış imajıdır, bu değişiklik imajları yükseltmez (her biri kendi digest'i ve testiyle ayrı iş); kayıtlar 2026-12-15'e kadar köprüdür.
6. **İmzalama.** `release.yml`'in imaj işi, itilen ve kontrol edilen digest'i cosign v3.1.4 (Apache 2.0) ile **anahtarsız** (GitHub OIDC kimliği → Fulcio sertifikası → Rekor kaydı) imzalar, Syft'in CycloneDX SBOM'unu atestasyon olarak ekler (`cosign attest --type cyclonedx`) ve ikisini `cosign verify` / `verify-attestation` ile (imzalayan = bu deponun `release.yml` iş akışı, `refs/tags/v*`) doğrulamadan sürüm etiketini taşımaz. Saklı imza anahtarı yok; ek izin yalnız o iş için `id-token: write`.
7. **Bilinen kabul: Ollama.** Ollama sunucusunda kimlik doğrulama yok (CVE-2025-63389, kritik, yama yok). Korunma yalnız ağdadır: `compose.yaml`'da host portu yok, yalnız `internal: true` `models` ağı (`security-rules.md` 9). Kayıt `.github/vuln-allowlist.yaml`'da gerekçe ve son kullanma tarihiyle (2027-01-31) durur ve `scope: ollama` ile yalnız Ollama imajına bağlıdır; GHSA takma adı (`GHSA-f6mr-38g8-39rg`) ayrıca yazılıdır. **İlk taramada Grype bu kimliği Ollama 0.35.1'e eşlemedi** (kayıt şimdilik hiçbir bulguyu örtmüyor); kayıt, veritabanı ileride eşlerse kapının kırılmaması ve kararın yazılı olması için durur. Tarih dolunca yeniden değerlendirilir.

Reddedilen: Trivy (action zinciri, tek aracın görüşü), OSV-Scanner (imaj katmanı zayıf), her HIGH'ı kırmak (kırmızıya alışma), `.grype.yaml` ignore (süre yok).

## Sonuçlar

- **Olumlu:**
  - Her ay, her imaj ve Java derlemesi için SBOM elde (alıcı kapısı: SBOM istenince hazır); yeni bir CRITICAL ya da düzeltmesi olan HIGH ay içinde görünür.
  - Yayımlanan imajın kaynağı kriptografik olarak doğrulanabilir (kimin, hangi iş akışının ürettiği); SBOM digest'e bağlı.
  - Kabul edilen riskler tek dosyada, gerekçeli ve süreli; unutulan istisna kendini kırar.
  - Zorunlu kontroller yavaşlamaz.
- **Olumsuz / kabul edilen risk:**
  - **Kapı zorunlu değil.** Kırmızı bir koşu birleştirmeyi durdurmaz; birinin bakması gerekir (runbook). Eşik yukarıda.
  - **Tarayıcı veritabanı hareketli.** Aynı SBOM yarın başka sonuç verebilir; bu istenen davranış (yeni CVE'ler çıkar), ama "dün yeşildi" kanıt değildir. Grype sürümü sabit, veritabanı her koşuda güncel.
  - **Ağ bağımlılığı.** Grype veritabanı indirmesi ve Docker Hub/Quay/GHCR anonim okuma gerekir; anonim Docker Hub çekme sınırı (rate limit) nadiren kırmızı koşu yaratabilir. Sınır sorun olursa Docker Hub kimliği ayrı bir ADR ile eklenir.
  - **Kapsam.** Taranan: Java derlemesinin bağımlılıkları ve compose/Dockerfile'daki üçüncü taraf imajlar. Taranmayan: panelin statik dosyaları (bağımlılık yok), modellerin ağırlıkları, `verso:local` imajı (içeriği Temurin JRE + jar'lar, ikisi de ayrı taranır; release iş akışı yayımlanan imajın SBOM'unu ayrıca üretir).
  - **Public Rekor.** Anahtarsız imza, imza kaydını herkese açık şeffaflık günlüğüne yazar (depo adı, iş akışı yolu, etiket görünür). Depo özel kalırsa bu üst veri dışarı çıkar; bilerek kabul edildi, çünkü imajlar zaten `ghcr.io/<owner>` altında.
  - **Release değişikliği henüz gerçek bir etiketle koşmadı.** `actionlint` ve cosign bayrak doğrulaması yapıldı; ilk `v*` etiketi ilk gerçek sınavdır. Imza adımı başarısız olursa sürüm etiketi taşınmaz (güvenli yön), `sha-<commit>` etiketi kalır.
  - **Paket geneli istisnalar yeni bulguları da gizler.** 2026-12-15'e kadar Grafana, Prometheus, Alertmanager, Loki, Alloy ve Flyway imajlarında ya da Ollama/pgvector'un belirli paketlerinde çıkacak yeni bir CRITICAL kapıyı kırmaz. Hafifletme: kısa süre, tek imaj + tek paket kapsamı, iş özetinde "allowlisted" sayısı, ve 2026-12-15 öncesi yükseltme listesi (`docs/versions.md`). Bu süre dolarken yükseltmeler yapılmamışsa kayıtlar yenilenmez, kapı kırmızı kalır.
  - **Ollama'da kalıcı kabul.** Süre dolunca kayıt gerekçeyle yenilenir; yama çıkarsa kaydı silmek ve yükseltmek gerekir.
- **Etkilenen dosyalar:** `.github/workflows/supply-chain-scan.yml` (yeni), `.github/workflows/release.yml` (cosign), `.github/vuln-allowlist.yaml` (yeni), `scripts/vuln-gate.js`, `scripts/vuln-gate.test.js`, `scripts/sbom-images.js` (yeni), `pom.xml` (eklenti sürümü), `docs/versions.md`, `docs/runbooks/aylik-guvenlik-taramasi.md` (yeni), `docs/runbooks/README.md`, `docs/decisions.md`, `README.md`.
- **Geri alma yolu:** iş akışı dosyasını silmek yeterli (hiçbir zorunlu kontrol ona bağlı değil). İmzalama için `release.yml`'deki iki adım (`cosign-installer` ve imza adımı) ve `id-token: write` kaldırılır; imajlar etkilenmez.

## Yeniden değerlendirme koşulu

Üç ardışık ay kırmızısız (altyapı dışı) koşulursa zorunlu kontrol yapılması ayrı kararla düşünülür. Allowlist'te üçten fazla geçerli kayıt, ya da yaması yalnız ticari sürümde olan bir CVE çıkarsa bu karar yeniden açılır. Imaj yayını kayıt defterini ya da imzalama kimliğini değiştirirse (`SIGNER_IDENTITY`) doğrulama deseni güncellenir.
