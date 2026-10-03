# Faz 8: Doğrulama ve kanıt kaydı

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

- **Tarih:** 2026-10-03
- **Ortam:** Windows 11 + Docker Desktop (Docker Engine 29.6.1, 16 GB, 8 CPU, GPU yok); GitHub Actions `ubuntu-latest`. Modeller: bge-m3:567m, gemma4:e2b.
- **Kapsam:**
  - Türkçe değerlendirme seti (`eval/eval-set.json`: 25 cevaplanabilir, 8 cevaplanamaz soru) ve sentetik örnek belgeler (`samples/`, kaynakları `samples/src/`).
  - Retrieval ölçümü (`RetrievalEvalTest`, tag `eval`) ve uçtan uca ölçüm (`scripts/eval.mjs`).
  - Yük testi (`scripts/load-test.mjs`) ve kapasite belgesi (`docs/capacity.md`).
  - Ölçümün gerektirdiği değişiklikler: eşik 0,45 → 0,50, chat eşzamanlılığı 2 → 1, zaman aşımında model çağrısının iptali.
  - Kararlar: ADR-0008 ve ADR-0012 güncellendi; llm-rules 7.1.
- **Davranışsal kapsam (seviye 3):** var. Bütün sayılar gerçek modellerle, çalışan yığın üzerinde ölçüldü; ham sonuçlar `eval/results/` altında.

## 1. Makine kontrolleri

| Kontrol | Komut | Sonuç |
|---|---|---|
| Build ve testler | `./mvnw -B -ntp verify` | **PASS**: 353 Java testi (faz 7 sonunda 348). `eval` tag'li test varsayılan build'in dışında (`verso.excludedGroups`) |
| Örnek PDF'ler kaynaklarıyla aynı | `SamplePdfsTest` | **PASS**: `samples/*.pdf`, `samples/src/*.txt`'den yeniden üretilince bayt bayt aynı |
| Retrieval ölçümü | `./mvnw -pl verso-app -am test -Dverso.excludedGroups=none -Dgroups=eval -Dtest=RetrievalEvalTest` | **PASS**: recall@1 0,88, recall@5 1,00, MRR 0,94 (`eval/results/retrieval.json`); eşik: recall@5 ≥ 0,80 |
| Mutasyon | `bash scripts/mutation-check.sh` | Bölüm 5 |

## 2. Ölçümler

| Ölçüm | Sonuç | Kaynak |
|---|---|---|
| Uçtan uca, gemma4:e2b, 33 soru, ilk koşu (eski tanımlar) | foundRate 1,00 · citationHit 1,00 · atıf kesinliği (belge düzeyi) 1,00 · factAccuracy 0,96 · notFound (found=false) 1,00 · p50 28 sn, p95 45 sn, en çok 67 sn | `e2e-gemma4_e2b.json`, git geçmişi |
| Uçtan uca, review sonrası sıkı tanımlarla | foundRate 1,00 · citationHit 1,00 · atıf kesinliği (sayfa düzeyi) 1,00 · factAccuracy 0,96 · notFound **0,875** (eşik 4/4, model 3/4) · p50 21 sn, p95 37 sn, en çok 43 sn | `eval/results/e2e-gemma4_e2b.json` |
| Belge listesi, 20 istemci, 30 sn | 841 istek/sn, p95 49 ms, hata yok | `eval/results/load.json` |
| Yükleme, 12 eşzamanlı | 7 kabul, 5 anında 503 10014 + `Retry-After`; ~29 belge/dk | `eval/results/load.json` |
| Retrieval yükü, 20 istemci | ~7,7 soru/sn, p50 0,49 sn; 96 istek bulkhead'de 503 10030 | `eval/results/load-retrieval.json` |
| Eşzamanlı chat (Ollama 2 ve 6 CPU) | Her seferinde yalnız bir cevap; diğerleri 503 11002 / 10030 | `eval/results/load-chat-*.json` |

Ayrıntılar ve yorum: [`docs/capacity.md`](../capacity.md), [`eval/README.md`](../../eval/README.md).

## 3. Ölçümün bulduğu hata ve düzeltme

| Bulgu | Kanıt (önce) | Düzeltme | Kalıcı kontrol |
|---|---|---|---|
| Zaman aşımında vazgeçilen model çağrısı Ollama'da sürüyordu | İstemci 503 aldıktan dakikalar sonra Ollama CPU'su %600; bu sırada 0,25 sn'lik embedding 10 sn'yi aştı, sonraki sorular düştü | Çağrı ayrı bir görevde; zaman aşımında `Future.cancel(true)` ile kesiliyor, HTTP istemcisi isteği iptal ediyor. Slot görevin kendi `finally`'sinde bırakılıyor | `QuestionApiTest.ask_whenTheModelHangs_*`, `RetrievalTest.search_whenTheEmbeddingTimesOut_interruptsTheModelCall`, M200, M201. Canlı: 503'ten 2 sn sonra Ollama CPU %0 |
| İkinci eşzamanlı chat yalnız kuyrukta bekliyordu | 2 istemcide iki cevabın süresi de uzadı, biri yine düştü | `verso.qa.chat-concurrency` 2 → 1 | `QaProperties` varsayılanı, `verso.yml`; testler 2 ile koşar (`VersoTestEnvironment`) |
| Eşik cevaplanamaz soruları modele gönderiyordu | 0,45'te 8 cevaplanamaz sorudan yalnız 1'i eşik altında | 0,50: 25 cevaplanabilir sorunun hepsi korunur (en düşük en-iyi benzerlik 0,522), cevaplanamazların 4'ü modele gitmez | `RetrievalEvalTest` (tag `eval`), ADR-0012 |

Dürüst not: uçtan uca koşuda bir soruda (izin-1) model doğru sayfaya atıf yapıp yanlış sayıyı yazdı ("yirmi gün"; belgede "on dört gün"). factAccuracy 0,96'daki eksik budur (iki koşuda da). Atıf, kullanıcının kontrol edebilmesi için var. Sıkı tanımla kaçan "bulunamadı" (yok-5): model doğru bir ret yazdı ("fazla mesai saat ücreti hakkında bilgi bulunmamaktadır") ama sabit cümleyi kullanmadı; API bunu kaynaksız cevap olarak döndürür.

## 4. Review

Tek ajan, beş skill, `c1c4e63` üzerinde koştu. Ajan kendi worktree'sinde testleri ve 11 ek mutasyonu koşturdu, Ollama'ya canlı iptal deneyi yaptı.

| Skill | Karar | Öne çıkan bulgular |
|---|---|---|
| `verso-resilience-review` | REQUEST CHANGES | RS1 (HIGH): iptal, sanal thread başlamadan gelirse `FutureTask` gövdeyi hiç çalıştırmaz; `finally`'deki `release()` da çalışmaz ve slot kalıcı olarak kaybolur (JDK deneyi: 20000 submit+cancel → 19995 kayıp izin; geçici test kırmızı). Chat'te tek slot olduğu için tek sızıntı, yeniden başlatmaya kadar her soruya 11002 demek. RS2: kritik akış kaydı ve ADR-0008 eski değerleri (2 çağrı, 0,45) taşıyordu. RS3: local-CPU bütçesi (60 sn) aşılıyordu ve yazılmamıştı. RS4: eşzamanlılık 1'in 10030'u çözmediği yazılmamıştı. RS5: yük testi kapalı döngü, liste ölçümü boş hesapta, `Retry-After` okunmuyor, sonuç dosyaları sunucu ayarını kaydetmiyor |
| `verso-llm-review` | APPROVE WITH NON-BLOCKING COMMENTS | Veri sızıntısı yok. L1: eşik aynı veriyle ayarlandı, pay ince (0,522), `retrieval.json` eski eşiği taşıyordu. L2: "doğru bilgi" düz alt-dize araması ("2", "[2]" ile eşleşiyordu). L3: "bulunamadı" doğruluğu atıfsız uydurmayı da doğru sayıyordu. L4: atıf kesinliği belge düzeyindeydi. L5: küçük örneklemden fazla sonuç; sınırlar yazılmamıştı. L6: iki belirsiz soru |
| `verso-spring-code-review` | REQUEST CHANGES | S1 = RS1. S2: "her istemci isteği iptal eder" yorumu yalnız Ollama için doğrulanmıştı. S3: küçük stil notları |
| `verso-test-writer` | FAIL | T1: RS1 için regresyon testi yok. T2: iki testte sınırsız bekleme döngüsü (mutasyonda build asılı kaldı). T3: eşik ve eşzamanlılık değerlerini sabitleyen test yok (4 mutasyon yaşadı). T4: `SamplePdfsTest` satırların yalnız ilk 40 karakterine bakıyordu |
| `verso-security-review` | APPROVE | Bulgu yok. Not: `eval.mjs` CI hesabının bütün belgelerini siliyor; belgelenmeli |

Canlı iptal deneyi (ajan): üretimdeki Ollama istemcisi JDK HttpClient değil Reactor Netty. 600 token'lık bir üretim 6 sn sonra kesildi, sonraki küçük istekler 1,3 sn ve 0,29 sn sürdü: Ollama üretimi bıraktı. Bulut SDK'larında (OkHttp) iptalin sağlayıcıya ulaştığı doğrulanmadı.

## 3a. Bulgu → düzeltme

| Bulgu | Düzeltme | Kalıcı kontrol |
|---|---|---|
| RS1/S1/T1: slot sızıntısı | Slotu, `started` bayrağını ilk alan bırakır: görev başlarsa kendi `finally`'si, başlamamış görevi iptal eden çağıran taraf. Chat ve soru embedding'i aynı desende | `RetrievalTest.search_whenTheCallerIsInterrupted_keepsTheEmbeddingSlots` (kesilmiş 8 çağrıdan sonra arama çalışır), M212. Chat yolu için deterministik test yok (kesmeyi görev başlamadan üretmek tam API yolunda mümkün değil); aynı desen kod incelemesiyle |
| RS2: eski değerler | `repo-context.md` ve ADR-0008: 1 çağrı, eşik 0,50 | – |
| RS3: bütçe aşımı | ADR-0008'e aşım, karar ve yeniden değerlendirme koşulu | – |
| RS4: 10030 | `capacity.md`: eşzamanlılık 1 bunu çözmez; çözüm ayrı embedding sunucusu ya da GPU | – |
| RS5: yük testi | `Retry-After` okunuyor; kapalı döngü ve boş hesap sınırı yazıldı; sonuçlar commit'i ve sunucu ayarını kaydediyor; eski yorum düzeldi | – |
| L1: eşik | Test eşiği `QaProperties`'ten okur; hiçbir cevaplanabilir sorunun eşik altına düşmemesi şart; `retrieval.json` 0,50 ile yeniden üretildi; takas ADR-0012'de | `RetrievalEvalTest` (tag `eval`) |
| L2–L4: metrik tanımları | Doğru bilgi: bütün kelime ya da sayı, `[n]` silinir, binlik ayırıcı içinde eşleşmez. "Bulunamadı": sabit cümle şart; eşiğin durdurdukları ve modelin karar verdikleri ayrı. Atıf kesinliği sayfa düzeyinde. Uçtan uca ölçüm yeni tanımlarla yeniden koşuldu (Bölüm 2) | `eval/README.md` "Metriklerin tanımı" |
| L5/L6: sınırlar | `eval/README.md` "Sınırlar": güven aralıkları, kolay retrieval, aynı veriyle ayar, belirsiz sorular | – |
| T2: sınırsız bekleme | İki döngüye 10 sn son tarih | – |
| T3: ayar değerleri | `ConfigProfilesTest.qaSettings_whenDeployProfile_areTheMeasuredValues` (yml ve kod varsayılanı) | M213, M214 |
| T4: örnek PDF metni | Satırın tamamı aranıyor | `SamplePdfsTest` |
| S2/S3, güvenlik notu | Yorum düzeltildi; stil; `eval.mjs` silme davranışı başlıkta ve README'de | – |

Kabul edilen açıklar:

- **`InterruptedException` yolundaki iptal** yalnız embedding tarafında test ediliyor (RS1 testi bu yoldan geçer); chat tarafı kod incelemesiyle.
- **Bulut SDK'larında iptal:** sağlayıcının üretimi bıraktığı doğrulanmadı; etkisi maliyet, yerel CPU değil.
- **Set küçük ve eşik aynı veriyle ayarlandı:** sonuçlar yön gösterir (`eval/README.md`).
- **PDFBox glif kontrolü sistem fontuna bakıyor:** Linux CI'da `SamplePdfsTest`'in geçtiği PR CI'ında görülecek.

## 5. Mutasyonlar

`ONLY="M200 M201 M202 M212 M213 M214" bash scripts/mutation-check.sh`: baseline yeşil (353 Java testi, 8 node suite). Sonuç: **6/6 yakalandı**. M200 ve M201 iptalin kendisini, M212 başlamamış görevin slotunu (review RS1), M213 ve M214 ölçülen ayarları (review T3) korur. M200/M201 tanımları, iptal ortak bir yardımcıya taşındığı için ona göre yeniden yazıldı. Çıktı: [`faz-8-mutasyon-ciktisi.txt`](faz-8-mutasyon-ciktisi.txt).

## 6. CI

CI_RESULT
