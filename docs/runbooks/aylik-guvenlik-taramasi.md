# Aylık güvenlik taraması

**Anlamı:** `supply-chain-scan` iş akışı her ayın 1'inde (05:17 UTC), elle (`workflow_dispatch`) ve pom, `compose.yaml`, `Dockerfile`, `docs/versions.md`, allowlist ya da kapı betiği değişince koşar (ADR-0030). Java derlemesinin ve compose'taki her imajın (`@sha256` ile pinli) CycloneDX SBOM'unu üretir, Grype ile tarar. **Kırmızı** demek: bir CRITICAL bulgu, düzeltmesi olan bir HIGH bulgu ya da süresi dolmuş bir allowlist kaydı var. Bu kontrol şimdilik zorunlu değildir; kırmızı çalışma bir iş kaydıdır, birleştirmeyi engellemez. Zamanlanmış koşu yalnız varsayılan dalda (`main`) çalışır; elle koşuyu istediğin dalda başlatabilirsin.

**Yerel koşuda tek tarayıcı konteyneri çalıştır** (Grype veritabanı ve katmanlar çok bellek ister; paralel koşular takılabilir).

**İlk bakış:** Actions → `supply-chain-scan` → ilgili koşu → her işin özetinde (job summary) kapıdan geçmeyen bulguların tablosu: kimlik, önem, paket, düzeltme sürümü. SBOM'lar koşunun artifact'ları olarak 90 gün durur (`sbom-maven`, `sbom-<imaj>`); bir önceki ayla karşılaştırmak için indirilir.

## Karar ağacı

| Durum | Ne yapılır |
|---|---|
| Maven bağımlılığında düzeltmesi olan bulgu | Boot BOM'u yeni yama sürümüne yükselt (`pom.xml` `spring-boot.version`) ya da yalnız o bağımlılığı `dependencyManagement`'ta düzelt; `./mvnw -B -ntp verify`; `docs/versions.md` satırını tarih ve CVE kimliğiyle güncelle |
| İmajda düzeltmesi olan bulgu | `compose.yaml`'da (ya da `Dockerfile`'da) imajı yeni sürüme ve yeni `@sha256` digest'ine al; **digest'i resmi kayıttan doğrula**, tahmin etme; `docs/versions.md` satırını güncelle; `ImageVersionsTest` ve `docker compose config --quiet` |
| CRITICAL ama düzeltme yok | Önce gerçekten ulaşılabilir mi bak (yalnız iç ağ, kullanılmayan paket). Hafifletme gerçekse allowlist kaydı (aşağıda). Hafifletme yoksa bileşeni değiştir ya da kapat |
| Yanlış pozitif (paket başka kütüphanenin adıyla eşleşmiş) | Allowlist kaydı, `package` ve `scope` ile daraltılmış, gerekçesinde yanlış pozitifliğin kanıtı |
| "Süresi dolmuş kayıt" | Düzeltme çıktıysa kaydı sil ve yükselt. Hâlâ geçerliyse gerekçeyi yeniden değerlendirip **yeni bir tarihle** yenile; tarihi sessizce uzatma |

Bir bulguyu susturmak için kapının eşiğini, Grype'ı ya da bir `--ignore` bayrağını değiştirme. Tek istisna yolu allowlist'tir ve her kayıt gerekçe + son kullanma tarihi taşır.

## Allowlist kaydı

`.github/vuln-allowlist.yaml`:

```yaml
entries:
  - id: CVE-2025-63389          # CVE ya da GHSA kimliği; Grype'ın ilgili kimlikleri de eşleşir
    scope: ollama               # isteğe bağlı: SBOM adında geçen parça (örn. ollama-ollama-0.35.1)
    package: github.com/ollama/ollama   # isteğe bağlı: tam paket adı
    reason: "Neden kabul edildi, hangi hafifletme var, kanıtı nerede"   # en az 20 karakter
    expires: 2027-01-31         # YYYY-MM-DD; en fazla ~6 ay sonrası
```

- `package` sonuna tek `*` konarak ön ek kalıbı olur (`golang.org/x/*`); `*` tek başına imajdaki her paket demektir.
- `id: "*"` (paket geneli istisna) yalnız `scope` ve `package` ile yazılır ve en fazla ~2 ay sürer: yukarı akış imajındaki bayat bir araç zinciri onlarca kimlik üretiyorsa kullanılır, ama sonradan çıkan yeni bulguları da gizler. Gerekçesi çaresini (hangi yükseltme) söylemelidir.
- Süresi dolan kayıt hiçbir şeyi susturmaz; kapı kırmızı olur ve `node --test scripts/vuln-gate.test.js` düşer.
- Kayıt eklemek bir güvenlik kararıdır: PR'da `verso-security-review` çalıştırılır ve gerekçe `docs/versions.md` ya da `security-rules.md` ile tutarlı olmalıdır.
- Gerekçede `#` karakteri kullanma (satır sonu yorumu sayılır).

## Elle koşma (yerel)

Docker yeterlidir; imajlar `.github/workflows/supply-chain-scan.yml` başındaki `SYFT` ve `GRYPE` digest'leriyle çalıştırılır.

```bash
# Java SBOM'u (JAVA_HOME JDK 25)
./mvnw -B -ntp -DskipTests package org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom   # → target/verso-bom.json
# Bir imajın SBOM'u (kayıttan okunur, imaj çalıştırılmaz) ve taraması
docker run --rm -v "$PWD/sbom:/w" "$SYFT" "registry:<imaj@sha256:…>" -o cyclonedx-json@1.6=/w/x.cdx.json
docker run --rm -v "$PWD/sbom:/w" "$GRYPE" sbom:/w/x.cdx.json -o json --file /w/x.grype.json
# Kapı
node scripts/vuln-gate.js --label x --grype sbom/x.grype.json --sbom sbom/x.cdx.json --allowlist .github/vuln-allowlist.yaml
node scripts/sbom-images.js     # taranan imaj listesi (digest'siz imaj hata verir)
```

Çalışan yığına dokunmaz: Syft imajı kayıttan okur, hiçbir şey başlatmaz.

## İmzalı imajı doğrulama

`v*` sürümü yayımlanırken (`release.yml`) imaj digest'i cosign ile anahtarsız imzalanır ve CycloneDX SBOM'u atestasyon olarak eklenir. Doğrulama (imzalayan kimlik yalnız bu deponun `release.yml` iş akışıdır):

```bash
cosign verify ghcr.io/<owner>/verso@sha256:<digest> \
  --certificate-identity-regexp '^https://github\.com/<owner>/<repo>/\.github/workflows/release\.yml@refs/tags/v' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
cosign verify-attestation --type cyclonedx ghcr.io/<owner>/verso@sha256:<digest> <aynı iki bayrak>
```

**Kapanış:** koşu yeşil (ya da kalan her bulgu geçerli allowlist kaydıyla örtülü) ve `docs/versions.md` değişen satırlarıyla güncel.
