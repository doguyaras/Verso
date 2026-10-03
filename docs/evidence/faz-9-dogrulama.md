# Faz 9: Doğrulama ve kanıt kaydı

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

- **Tarih:** 2026-10-03
- **Ortam:** Windows 11 + Docker Desktop (Docker Engine 29.6.1, 16 GB, 8 CPU, GPU yok); GitHub Actions `ubuntu-latest`.
- **Kapsam:**
  - README'nin yeniden yazılan üst bölümü: İngilizce özet, "Ne yapar", dört komutluk hızlı başlangıç, gerçek demo çıktısı, curl örnekleri, modlar ve KVKK md. 9 tablosu, ölçümler, mimari şeması.
  - `scripts/demo.sh`: örnek belgeleri yükler, işlenmesini bekler, dört soru sorar (biri cevaplanamaz).
  - `.github/workflows/release.yml`: `v*` etiketiyle GHCR'a imaj yayını.
- **Davranışsal kapsam:** demo betiği çalışan yığında koşuldu. Release workflow'u **koşulmadı**: tetiklemek kamuya açık bir imaj yayınlar ve kullanıcı onayı bekler (Bölüm 4).

## 1. Makine kontrolleri

| Kontrol | Komut | Sonuç |
|---|---|---|
| Build ve testler | `./mvnw -B -ntp verify` | **PASS**: 353 Java testi (faz 8 ile aynı; bu fazda Java kodu değişmedi) |
| Betik sözdizimi | `bash -n scripts/demo.sh` | **PASS** |
| Workflow sözdizimi | GitHub'ın workflow ayrıştırıcısı (PR'da) | Bölüm 6 |

## 2. Canlı denemeler

| Deneme | Sonuç |
|---|---|
| `bash scripts/demo.sh` (temiz hesapta, review düzeltmelerinden sonra üç kez) | **PASS**: altı belge yüklendi ve READY oldu; üç soru atıflı cevap aldı, cevaplanamaz soru kaynaksız döndü. Süreler 18–81 sn (yük altındaki makinede). İzin sorusunda model her koşuda "yirmi iş günü" dedi (belgeye göre doğrusu on dört gün); README bunu olduğu gibi gösterir |
| `bash scripts/demo.sh` (belgeler zaten yüklü) | **PASS**: yükleme atlandı, sorular soruldu |
| `bash scripts/demo.sh --clean` | **PASS**: betiğin yüklediği belgeler silindi |

## 3. Review

Tek ajan, faz 9 ve faz 10 birlikte (`c1c4e63..eafc302`). Faz 9'a düşen bulgular:

| Skill | Karar | Bulgular |
|---|---|---|
| `verso-security-review` | REQUEST CHANGES | S1 (HIGH): release workflow'u test koşmadan yayınlıyordu. Yorum "Dockerfile tam verify koşar" diyordu, oysa Dockerfile `-DskipTests` kullanır; CI'dan geçmemiş bir commit'e `v*` etiketi basmak test edilmemiş imaj yayınlardı. S2: BuildKit ve SBOM tarayıcısı kayan tag'lerdi; iterilen imaj, kontrol edilen imaj değildi (iki ayrı build). S3: ifadeler `run` içine gömülüydü |
| `verso-environment-impact-review` | APPROVE WITH NON-BLOCKING COMMENTS | E4: imajın depo checkout'u olmadan kurulamayacağı yazılmamıştı. E5: `demo.sh` boşluklu dosya adlarında, `FAILED` belgede ve token alınamadığında yanlış davranıyordu. R2–R6: README'de abartılı ya da eksik ifadeler |

## 4. Bulgu → düzeltme

| Bulgu | Düzeltme | Kalıcı kontrol |
|---|---|---|
| S1: test edilmemiş yayın | Ayrı `verify` job'ı (`./mvnw -B -ntp verify`); imaj job'ı `needs: verify`. Yanlış yorum düzeltildi | Workflow'un yapısı |
| S2: kayan builder, iki build | BuildKit `v0.31.0` ve syft tarayıcısı `1.12.0` digest ile pinli. Tek build: önce yalnız `sha-<commit>` etiketiyle iter, digest'i geri çeker, kullanıcıyı ve revision etiketini doğrular, sürüm etiketini ancak sonra o digest'e koyar | Workflow'un yapısı |
| S3: ifade gömme | Bütün değerler `env:` üzerinden | – |
| E4: imaj tek başına kurulamaz | README "İmaj yayını" paragrafı | – |
| E5: `demo.sh` | Sekmeyle ayrılmış satırlar (dosya adı sonda), `FAILED` bekleme dışı, token alınamazsa açık mesajla çıkış | Canlı deneme |
| R2–R6: README | Test sayısı ifadesi, "ikincisi meşgul alır" ifadesi (`Retry-After`, otomatik yeniden deneme yok), indirme boyutu, Windows curl notu, "dört komut" | – |

Kabul edilen açıklar:

- **Release workflow'u hiç koşulmadı.** İlk koşu ilk sürümün yayını olacak; kullanıcı onayı bekliyor (`v0.1.0`). Tag koruması ve GHCR paket görünürlüğü GitHub ayarlarıdır; bu depoda doğrulanamadı.
- **`demo.sh`'ın token alınamayınca çıkması ve `FAILED` belgeyi beklememesi** kodla düzeltildi, canlı olarak üretilmedi.
- **İmaj panel, kenar proxy ayarı ve realm dosyasını içermez.** Bunlar depodan bağlanır; imajdan kurulum için de depo checkout'u gerekir.

## 5. Mutasyonlar

Bu fazda yeni mutasyon yok: değişen dosyalar belge, betik ve workflow. Davranış canlı denemeyle doğrulandı (Bölüm 2).

## 6. CI

PR #11: ilk koşu (`9a9efb6`) **failure**. Faz 8'de eklenen `RetrievalTest.search_whenTheCallerIsInterrupted_keepsTheEmbeddingSlots` her kesilmiş aramanın hata vermesini şart koşuyordu; CI'da model anında cevap verince arama sonucu alabildi (yarış). Aynı test, PR #10 birleştikten sonra `develop` push'unda da (`57d5bb1`) kırmızı oldu. Düzeltme (`e54a9cd`): test, her çağrının sonucundan bağımsız olarak slotların korunduğunu kontrol eder, 64 çağrı yapar; yerelde 3/3 yeşil, M212 hâlâ yakalanıyor. Sonra `ci` ve `restore-drill` **success** (run 37121010481, 37121010522). Birleştirme: `55e7b0d`.
