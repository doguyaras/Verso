# Eval seti sürüm 2: doğrulama ve kanıt kaydı

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

## Künye

- **Tarih:** 2026-10-24
- **Branch:** `feature/doguyaras-eval-v2` (`origin/develop` `5a5ece2` üzerinden). Veri ve taban commit'i `fb919db`; bu belge ve README güncellemeleri sonraki commit'te.
- **Ortam:**
  - Windows 11 + Docker Desktop; NVIDIA RTX 4070 Ti SUPER; JDK 25.0.4.1 (`JAVA_HOME=C:/Users/doguy/.jdks/jdk-25.0.4.1+1`).
  - Modeller: bge-m3:567m, gemma4:e2b.
- **Neden:** sürüm 1 seti tavana dayanmıştı (recall@5 1,00, 33 soru, 6 kısa yönetmelik). O setle hiçbir retrieval iyileştirmesi ölçülemezdi. Ürün, şirket sistemlerinin nasıl çalıştığına dair sorular için satılıyor.
- **Kapsam:**
  - Beş yeni sentetik belge (`samples/src/*.txt`, 28 sayfa) ve PDF'leri (`SamplePdfs`).
  - `eval/eval-set.json`'a 107 yeni soru ve isteğe bağlı `tags` alanı (sürüm 2).
  - `RetrievalEvalTest`'te bir koruma değişikliği (aşağıda).
  - Retrieval, eşik, prompt ve model kodu **değişmedi**.
- **Davranışsal kapsam (seviye 3):** var. Bütün sayılar gerçek modellerle ölçüldü:
  - retrieval: Testcontainers PostgreSQL + gerçek bge-m3;
  - uçtan uca: çalışan yığın.

## 1. Belgeler ve sorular

| Belge | Sayfa | Konu |
|---|---|---|
| `depo-yonetim-kilavuzu.pdf` | 6 | WMS: mal kabul, ASN, yerleştirme, karantina, sevkiyat, **3.4 stok düşümü ve rezervasyon** (panel tanıtımındaki kural), stok durumları tablosu, sayım, terminal hata kodları |
| `erp-satin-alma-kilavuzu.pdf` | 6 | ERP satın alma: belge türleri, ST/SAS durum kodları, onay basamakları, vekalet, bütçe kontrolü, ERP–WMS entegrasyonu, SAT-E hata tablosu, roller |
| `bt-operasyon-runbook.pdf` | 6 | BT runbook: P1–P4 tablosu (ilk yanıt, çözüm hedefi), eskalasyon, VPN hataları, parola sıfırlama ve kilit, yedekleme tablosu, bakım ve CAB, nöbet |
| `musteri-destek-sureci.pdf` | 5 | CRM: kayıt durumları ve SLA saati tablosu, P1–P4 müşteri SLA'sı, yeniden açma kuralları, iade, CSAT |
| `isg-depo-proseduru.pdf` | 5 | Depo İSG: KKD, forklift hızları, raf kapasiteleri, hasar renkleri, ortam ölçümleri, kalite göstergeleri |

- **Ortak kurgu:** belgeler aynı kurgusal şirkete aittir ve "sentetik örnek belge" uyarısını taşır. E-fatura ve e-arşiv konuları yoktur.
- **Bilerek bırakılan örtüşmeler (hard negative):**
  - İki ayrı P1–P4 tablosu: BT ve müşteri desteği.
  - Kabul toleransları: WMS %2, ERP %5.
  - "Yılda iki kez tatbikat" iki belgede: tahliye ve oltalama.
  - Tutara göre onay: masraf, satın alma ve ERP.
  - Saklama süreleri: KVKK, BT yedekleri, CRM kayıtları.
- **Sorular:** 107 yeni soru; 87'si cevaplanabilir, 20'si cevaplanamaz ve konusu belgede olan sorular. Set toplamı 140: 112 cevaplanabilir, 28 cevaplanamaz.
- **Soru türleri (`tags`; bir soru birden çok türde olabilir):**

| Tür | Soru |
|---|---|
| `paraphrase` | 17 |
| `abbreviation` | 25 |
| `number` | 60 |
| `table` | 26 |
| `disambiguation` | 21 |
| `on-topic` | 20 |
| `showcase` | 1 |

## 2. Makine kontrolleri

| Kontrol | Komut | Sonuç |
|---|---|---|
| PDF üretimi | `./mvnw -pl verso-app -am test -Dtest=SamplePdfsTest -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false -Dverso.samples.write=true` | **PASS** (2 test). Eski 6 PDF bayt bayt aynı kaldı (git'te değişiklik yok). Yeni kaynakların her satırı çıkarılan metinde birebir var. Türkçe harfler sağlam |
| Sayfa yerleşimi | PDFBox 3.0.8 ile sayfa başına çıkarım. Betik `PageCheck.java`, repoda değil | Bütün sayfalarda metin alt kenar boşluğunun üstünde, sağ kenar 539/595 pt içinde. İlk denemede iki uzun başlık sayfa dışına taşmıştı; başlıklar kısaltıldı, PDF'ler yeniden üretildi |
| Beklenen sayfa | Her cevaplanabilir sorunun `expect` değeri, beklenen sayfanın çıkarılmış metninde `eval.mjs` ile aynı eşleştirmeyle arandı. Betik `verify.mjs`, repoda değil | **107/107** yeni soru doğrulandı. bt-3'ün sayfası [1, 5]'ten [1]'e daraltıldı: cevap yalnız 1. sayfadaki tabloda |
| Build | `./mvnw -B -ntp verify` | **PASS**: 406 test, 0 hata, 0 atlama |

## 3. Ölçümler

### 3.1 Retrieval

Komut:

```bash
./mvnw -pl verso-app -am test -Dverso.excludedGroups=none -Dgroups=eval -Dtest=RetrievalEvalTest -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false
```

Yığının Ollama'sı host'a CPU modunda açılarak koşuldu. Çalışan yığına yalnız `ollama` servisi yeniden oluşturuldu; diğer servisler değişmedi:

```bash
VERSO_DB_LOCAL_PORT=55432 docker compose -f compose.yaml -f deploy/compose.local.yaml up -d --wait ollama
```

Önce GPU katmanıyla denendi (`... -f deploy/compose.gpu.yaml -f deploy/compose.local.yaml up -d --wait ollama`). İki koşuda da ilk soru `EMBEDDING_MODEL_UNAVAILABLE` ile düştü:
- Test ortamında soru embedding'inin zaman aşımı 2 sn (`VersoTestEnvironment`).
- GPU'daki Ollama'da tek tük 1,4–4 sn'lik gecikmeler ölçüldü (Ollama log'u: `3.97s POST /api/embed`).
- Faz 8 tabanı da CPU'da ölçülmüştü. CPU'da ölçüm iki kez koşuldu, sonuçlar aynı.

| Ölçüm | Sürüm 1 (2026-10-03, 33 soru) | Sürüm 2 (2026-10-24, 140 soru) |
|---|---|---|
| recall@1 | 0,88 | **0,804** |
| recall@5 | 1,00 | **0,955** (107/112) |
| MRR | 0,94 | **0,869** |
| Belge düzeyinde recall@5 | 1,00 | 1,00 |
| Cevaplanabilir eşik üstü | 25/25 | 107/112 |
| Cevaplanamaz eşik altı | 4/8 | 6/28 |
| Cevaplanabilir en iyi benzerlik (min / ortanca / max) | 0,522 / 0,62 / 0,738 | 0,45 / 0,62 / 0,738 |
| Cevaplanamaz en iyi benzerlik (min / ortanca / max) | 0,431 / 0,508 / 0,563 | 0,431 / 0,547 / 0,702 |

**Eşik ayrımı:** sürüm 1'de iki dağılım ince bir payla ayrılıyordu (0,522'ye karşı 0,50). Sürüm 2'de iki dağılım iç içe:
- 5 cevaplanabilir soru eşiğin altında kalıyor. Hepsi kısa kısaltma ya da kod sorusu (depo-17, depo-18, erp-12, bt-16, isg-10; 0,45–0,49) ve beşi de doğru sayfayı ilk sırada buluyor.
- Konusu belgede olan 20 cevaplanamaz sorudan 17'si eşiği geçiyor.

Türlere göre kırılım ve kaçan sorular: [`eval/README.md`](../../eval/README.md) "Sonuçlar". En zayıf tür `paraphrase` (recall@5 0,76). Kaçan 5 sorunun 3'ü eş anlamlı söyleniş, 2'si tablodaki cevap.

`FormatEvalTest` (aynı komut, `-Dtest=FormatEvalTest`) PASS:
- PDF: bilgi ilk 5 pasajda 0,946.
- DOCX ve MD: 1,00.
- Belge ilk 5'te üçünde de 1,00.

### 3.2 Uçtan uca

Komut:

```bash
OUT=e2e-gemma4_e2b-gpu.json node scripts/eval.mjs
```

- **Yığın:** retrieval ölçümünden sonra normal GPU yığınına dönüldü (`docker compose -f compose.yaml -f deploy/compose.gpu.yaml up -d --wait`). Ollama'nın host portu kapandı, GPU rezervasyonu geri geldi.
- **Hesap:** betik CI istemcisinin hesabını boşaltıp 11 PDF'i yükledi, sonunda yüklediklerini sildi.
- **Secret:** betiğin okuduğu CI istemci secret'ı, koşu süresince worktree'nin git'e girmeyen `secrets/` klasörüne kopyalandı ve koşudan sonra silindi.
- **Koşu:** commit `fb919db`, 140 soru, hata yok, yaklaşık 5 dk.

| Ölçüm | Sürüm 1 (GPU, 33 soru) | Sürüm 2 (GPU, 140 soru) |
|---|---|---|
| Kaynak bulundu | 1,00 | 0,902 |
| Atıf isabeti | 1,00 | **0,866** |
| Atıf kesinliği (sayfa) | 1,00 | 0,929 |
| Cevapta doğru bilgi | 0,96 | **0,848** |
| "Bulunamadı" doğruluğu | 0,875 (eşik 4, model 3/4) | **0,536** (eşik 6, model 9/22) |
| Gecikme p50 / p95 | 1,3 sn / 5,4 sn | 1,4 sn / 3,1 sn |

Yorum:
- **Sürüm 1 soruları 11 belgenin içinde:** atıf ve bilgi değişmedi (1,00 / 0,96). "Bulunamadı" 7/8'den 6/8'e indi: yok-3 yeni belgelerle eşiği geçti.
- **Kaynak bulunamayan 11 cevaplanabilir soru:** 5'ini eşik durdurdu. 6'sında retrieval doğru sayfayı getirmedi; model de "bulunamadı" dedi.
- **Cevaplanamazlarda yanlış sayılan 13 cevap:**
  - 5'i, modelin kendi cümlesiyle yazdığı doğru bir ret.
  - 8'i, konusu yakın bir pasajdan atıflı ama soruyu cevaplamayan bir cümle. Örnek: crm-yok-1'de gümüş sözleşme sorulunca altın sözleşmenin süreleri geldi.

## 4. Koruma değişikliği (`RetrievalEvalTest`)

| Koruma | Önce | Sürüm 2 ölçümü | Sonra | Gerekçe |
|---|---|---|---|---|
| recall@5 | ≥ 0,80 | 0,955 | ≥ 0,80 (değişmedi) | Koruma geçiyor. Ölçümün epey altında kaldığı `eval/README.md`'de yazılı; yükseltmek ayrı bir karar |
| Cevaplanabilir eşik üstü | = 1,00 | 0,955 (107/112) → **kırmızı** | ≥ 0,95 | Görev retrieval kodunu ve eşiği değiştirmeyi kapsam dışı tuttu. Koruma ölçülen düzeye çekildi ki yeni kayıpları yakalasın. 5 sorunun eşik altında kalması açık hata olarak testin yorumunda ve `eval/README.md` "Eşikler ve karar" altında yazılı; sessizce gevşetilmedi |

Değişiklikten sonra `RetrievalEvalTest` ve `FormatEvalTest` birlikte koşuldu: **PASS** (2 test).

## 5. Review

`verso-llm-review` bu kapsamda yürütüldü. Değişen yollar:
- yeni sentetik belge metinleri: test DB'si ve yerel Ollama;
- soru metinleri: eval setinde;
- bir test assertion'ı.

Değerlendirme:
- Yeni log satırı, HTTP istemcisi, prompt ya da model çağrısı yok.
- Belgeler sentetik, kişisel veri yok (llm-rules 10).
- Retrieval sonucu bu belgede (7.1).

Karar: **Bu kapsamda LLM bulgusu yok.** Not: eşik açığı bir bulgu değil, ölçülen bir ürün hatasıdır; yukarıda yazılı.

`verso-spring-code-review` ve `verso-test-writer` (Java değişikliği tek bir test assertion'ı ve yorumu): bu kapsamda bulgu yok.

## 6. Yapılmayanlar

- **Uçtan uca ölçüm CPU'da koşulmadı.** 140 soru CPU'da yaklaşık 1 saat sürer (tahmin). `e2e-gemma4_e2b.json` sürüm 1'in CPU sonucudur.
- **Takip soruları sette yok:** sistem sohbet geçmişi tutmuyor.
- **Eşik ve retrieval değişiklikleri** bu işin kapsamı dışında. Bu set, onların önce/sonra ölçümü için kuruldu.
