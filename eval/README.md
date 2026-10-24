# Değerlendirme seti (faz 8; sürüm 2: 2026-10-24)

Verso'nun cevap kalitesi bu klasördeki sabit bir Türkçe setle ölçülür (llm-rules 7.1). Belgeler `samples/` altındaki sentetik PDF'lerdir: kurgusal bir şirketin **11 belgesi, 44 sayfa**. Gerçek kişi ve kurum verisi yoktur.

- **Sürüm 1 (faz 8):** altı kısa yönetmelik (izin, masraf, bilgi güvenliği, uzaktan çalışma, KVKK, satın alma; 16 sayfa) ve 33 soru.
- **Sürüm 2:** şirket sistemlerinin nasıl çalıştığını anlatan beş teknik belge (28 sayfa) ve 107 soru. Verso'ya sorulacak soruların çoğu bu türdendir.
  - WMS kullanım kılavuzu: `depo-yonetim-kilavuzu`, 6 sayfa. Panel tanıtımındaki "stok koli kapatılınca düşer" kuralı bölüm 3.4'te.
  - ERP satın alma kılavuzu: `erp-satin-alma-kilavuzu`, 6 sayfa. Durum kodları, onay akışı, hata mesajları.
  - BT operasyon el kitabı: `bt-operasyon-runbook`, 6 sayfa. P1–P4, eskalasyon, VPN, parola sıfırlama, yedekleme.
  - Müşteri destek süreci: `musteri-destek-sureci`, 5 sayfa. Kayıt durumları, SLA, yeniden açma.
  - Depo İSG prosedürü: `isg-depo-proseduru`, 5 sayfa. Sayı tabloları.
- **Zorlaştıran kurgu:**
  - Belgeler bilerek örtüşür (hard negative). İki ayrı P1–P4 tablosu var: BT ve müşteri desteği.
  - Kabul toleransı iki yerde farklıdır: WMS'te %2, ERP'de %5.
  - "Yılda iki kez tatbikat" iki belgede geçer: İSG'de tahliye, bilgi güvenliğinde oltalama.
  - Tutar bazlı onay matrisi üç belgede var: masraf, satın alma, ERP.
  - Sayım farkı onayı ile masraf onayının tutar basamakları benzer.
- **[`eval-set.json`](eval-set.json):** 140 soru. 112'si cevaplanabilir: beklenen belge ve sayfa ile cevapta geçmesi beklenen bilgi verilir. 28'i cevaplanamaz: sistem "bulunamadı" demeli. Sürüm 2 sorularında beklenen her sayfa, üretilen PDF'in sayfa metninden doğrulandı (cevaptaki bilgi o sayfada geçiyor).
- **[`results/`](results/):** ölçüm sonuçları (JSON). Her koşu üzerine yazar; tarihçe git'tedir.

Kaynak metinler `samples/src/*.txt` altındadır. PDF'ler `SamplePdfs` ile üretilir (`SamplePdfsTest`, `-Dverso.samples.write=true`); el ile PDF yazılmaz.

### Soru türleri (sürüm 2, `tags`)

Bir soru birden fazla türde olabilir. Sürüm 1 sorularının etiketi yoktur.

| Tür (`tags`) | Anlamı | Soru |
|---|---|---|
| `paraphrase` | Soru, belgenin sözcüklerini kullanmaz. Örnek: "envanterde miktar aynı görünüyor" sorusunun karşılığı belgede "rezerve" | 17 |
| `abbreviation` | Kısaltma ya da kod: WMS, ASN, FEFO, CAB, DÖF, SAT-E027, WMS-204 | 25 (5'i cevaplanamaz) |
| `number` | Cevap bir sayı ya da saat | 60 (10'u cevaplanamaz) |
| `table` | Cevap bir tablonun satırında | 26 |
| `disambiguation` | Benzer iki belgeden doğrusu gerekir | 21 |
| `on-topic` | Konu belgede var, sorulan bilgi yok. Örnekler: PIN değişim sıklığı, gümüş sözleşme SLA'sı | 20 |
| `showcase` | Panel tanıtımındaki örnek soru | 1 |

Konuşma bağlamı isteyen takip soruları sette yok: sistem sohbet geçmişi tutmuyor.

## İki ölçüm

| Ölçüm | Ne ölçer | Nasıl koşulur |
|---|---|---|
| Retrieval (`RetrievalEvalTest`, tag `eval`) | Doğru sayfa ilk 5 pasajda mı (recall@1, @5, MRR; bütün set ve soru türleri). Ayrıca cevaplanabilirlik kapısının (`AnswerabilityGate`, ADR-0022) kararı: kaç cevaplanabilir soru modele gider, kaç cevaplanamaz soru durur; "bulunamadı" kesinliği ve duyarlılığı | Gerçek bge-m3 ile, Testcontainers PostgreSQL üzerinde. Yığın gerekmez, yalnız Ollama gerekir |
| Uçtan uca (`scripts/eval.mjs`) | Gerçek chat modeliyle: kaynak bulma oranı, doğru sayfaya atıf, atıf kesinliği, cevapta doğru bilgi, cevaplanamaz sorularda "bulunamadı" doğruluğu, gecikme | Çalışan yığın üzerinden API ile. 140 soru GPU'da yaklaşık 5 dk. CPU'da yaklaşık 1 saat sürer (tahmin: 33 soruda p50 21 sn idi; sürüm 2 CPU'da koşulmadı) |

Ollama'yı host'a açarak (yalnız yerel ölçüm için):

```bash
VERSO_DB_LOCAL_PORT=55432 docker compose -f compose.yaml -f deploy/compose.local.yaml up -d --build --wait
```

```bash
./mvnw -pl verso-app -am test -Dverso.excludedGroups=none -Dgroups=eval -Dtest=RetrievalEvalTest -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false
```

```bash
node scripts/eval.mjs
```

Retrieval ölçümünü **CPU'daki Ollama ile** koşturun (GPU katmanı olmadan). Testteki soru embedding'inin zaman aşımı 2 sn'dir (`VersoTestEnvironment`). GPU'daki Ollama'da tek tük 1–4 sn'lik gecikme sıçramaları görüldü (2026-10-24). Bu durumda test ilk sorularda `EMBEDDING_MODEL_UNAVAILABLE` ile düşer. Faz 8 tabanı da CPU'da ölçülmüştü; sayılar karşılaştırılabilir kalır. Uçtan uca ölçüm üretim ayarıyla (10 sn) koşar, GPU'da sorun çıkmaz.

## Sonuçlar: hibrit arama (2026-10-24, ADR-0022)

Sözcüksel + vektör arama (RRF) ve cevaplanabilirlik kapısı. Bütün ara ölçümler, bırakılan başlık yolu ön eki ve çapraz doğrulama: [`docs/evidence/hibrit-arama-dogrulama.md`](../docs/evidence/hibrit-arama-dogrulama.md).

### Retrieval

`results/retrieval.json`; bge-m3:567m, CPU, top-k 5. Kapı: `min-similarity` 0,47, `lexical-min-similarity` 0,44, `specific-term-max-share` 0,045 (`config/verso.yml`).

| Alt küme | Cevaplanabilir | recall@1 | recall@5 | MRR | Cevaplanabilir kapıdan geçen | Cevaplanamaz kapıda duran |
|---|---|---|---|---|---|---|
| **Bütün set** | 112 | **0,795** (0,804) | **0,982** (0,955) | **0,877** (0,869) | **112/112** (107) | **7/28** (6) |
| Sürüm 1 soruları | 25 | 0,84 | 1,00 | 0,92 | 25/25 | 4/8 |
| Sürüm 2 soruları | 87 | 0,782 | 0,977 | 0,864 | 87/87 | 3/20 |
| `paraphrase` | 17 | 0,647 | 0,941 | 0,752 | 17/17 | – |
| `abbreviation` | 20 | 0,85 | 1,00 | 0,925 | 20/20 | 1/5 |
| `number` | 50 | 0,80 | 0,98 | 0,875 | 50/50 | 1/10 |
| `table` | 26 | 0,731 | 0,962 | 0,827 | 26/26 | – |
| `disambiguation` | 21 | 0,81 | 0,952 | 0,869 | 21/21 | – |
| `on-topic` (cevaplanamaz) | – | – | – | – | – | 3/20 |

- Parantez içinde önceki taban (aşağıdaki bölüm).
- **Soru bazında:** recall@5 +3/−0 (erp-3, erp-17, bt-2); recall@1 +4/−5.
- **Kaçanlar (ilk 5'te doğru sayfa yok):** bt-3 ve bt-5.
- **"Bulunamadı" (kapı):** kesinlik 1,00 (önce 0,545), duyarlılık 0,25 (önce 0,214).
  - Kısa kısaltma soruları artık modele gidiyor. erp-12 ve bt-16 özgül terim koluyla geçiyor (paylar 0,027 ve 0,013).
  - Konusu belgede olan cevaplanamaz sorular ayrışmıyor: 20 sorudan 3'ü duruyor.
- **Arama süresi:** CPU'daki soru embedding'i baskın. Veritabanı payı aynı koşuda yalnız vektörle 12,7 ms, hibritle 13,7 ms (p50).

### Belge türleri (hibrit)

| Tür | Belge ilk 5'te | Bilgi ilk 5 pasajda | Cevaplanabilir kapıdan geçen | Cevaplanamaz kapıda duran |
|---|---|---|---|---|
| PDF | 1,00 | 0,973 (0,946) | 1,00 (0,955) | 0,25 (0,214) |
| DOCX | 1,00 | 0,991 (1,00) | 0,991 (0,973) | 0,071 (0,179) |
| MD | 1,00 | 0,991 (1,00) | 0,991 (0,973) | 0,071 (0,179) |

DOCX ve MD'de kapı daha az cevaplanamaz soru durduruyor: eşikler PDF sayfalarıyla ölçüldü, bölümler yaklaşık üç kat uzun. Bu, ADR-0022'de olumsuz sonuç olarak yazılı.

### Uçtan uca (hibrit)

`results/e2e-gemma4_e2b-gpu.json`; GPU, gemma4:e2b, 140 soru, hata yok, commit `f587d3e`. Parantez içinde aynı tabanda (`origin/develop` `6d72c1e`, bu değişiklik olmadan) aynı yığında koşulan ölçüm. Tabanın tekrarında sıkı "bulunamadı" ve atıf isabeti aynen çıktı; model bu sette deterministik davranıyor.

| Alt küme | Cevaplanabilir | Kaynak bulundu | Atıf isabeti | Atıf kesinliği | Doğru bilgi | Cevaplanamaz | "Bulunamadı" |
|---|---|---|---|---|---|---|---|
| **Bütün set** | 112 | **0,964** (0,902) | **0,911** (0,866) | **0,922** (0,929) | **0,884** (0,839) | 28 | **0,607** (0,536) |
| Sürüm 1 soruları | 25 | 1,00 | 1,00 | 1,00 | 0,96 | 8 | 0,875 |
| Sürüm 2 soruları | 87 | 0,954 | 0,885 | 0,901 | 0,862 | 20 | 0,50 |
| `paraphrase` | 17 | 0,882 | 0,765 | 0,842 | 0,765 | – | – |
| `abbreviation` | 20 | 1,00 | 0,95 | 0,909 | 0,95 | 5 | 0,40 |
| `number` | 50 | 0,94 | 0,92 | 0,96 | 0,92 | 10 | 0,40 |
| `table` | 26 | 0,962 | 0,808 | 0,815 | 0,769 | – | – |
| `disambiguation` | 21 | 1,00 | 0,762 | 0,739 | 0,762 | – | – |

- **"Bulunamadı":** 17/28 sıkı (önce 15).
  - Kapı 7 soruyu modele göndermedi; modelin karar verdiği 21 sorunun 10'u doğru.
  - Modelin kendi atıfsız reddini de sayınca 18/28 (değişmedi).
  - Konusu belgede olan cevaplanamaz sorular hâlâ en zayıf yer.
- **Atıf isabeti:** soru bazında +6/−1. depo-17, depo-18, erp-5, erp-12, erp-17, bt-16 kazanıldı; crm-13 kaybedildi.
- **Gecikme:** p50 1,50 sn (1,40), p95 3,32 sn (2,88). Fark çoğunlukla daha çok sorunun cevap üretmesinden.
- **Prompt'a giden pasajlar:** en benzer pasaj ve benzerliği ≥ 0,50 olanlar (`passage-min-similarity`). Bütün beş pasajla model 28 cevaplanamaz sorudan yalnız 10'una "bulunamadı" dedi (`docs/evidence/hibrit-arama-dogrulama.md` bölüm 5).

## Taban: sürüm 2, vektör araması (2026-10-24, ADR-0022 öncesi)

### Retrieval

`results/retrieval.json` (ADR-0022 öncesi, git geçmişinde); bge-m3:567m, CPU, top-k 5, eşik 0,50. Ölçüm iki kez koşuldu, sonuç aynı çıktı.

| Alt küme | Cevaplanabilir | recall@1 | recall@5 | MRR | Cevaplanabilir eşik üstü | Cevaplanamaz eşik altı |
|---|---|---|---|---|---|---|
| **Bütün set** | 112 | **0,804** | **0,955** | **0,869** | 107/112 | 6/28 |
| Sürüm 1 soruları (11 belgenin içinde) | 25 | 0,88 | 1,00 | 0,94 | 25/25 | 3/8 |
| Sürüm 2 soruları | 87 | 0,78 | 0,94 | 0,85 | 82/87 | 3/20 |
| `paraphrase` | 17 | 0,65 | 0,76 | 0,68 | 17/17 | – |
| `abbreviation` | 20 | 0,80 | 1,00 | 0,90 | 16/20 | 1/5 |
| `number` | 50 | 0,84 | 0,96 | 0,89 | 49/50 | 1/10 |
| `table` | 26 | 0,77 | 0,92 | 0,83 | 25/26 | – |
| `disambiguation` | 21 | 0,81 | 0,90 | 0,85 | 21/21 | – |
| `on-topic` (cevaplanamaz) | – | – | – | – | – | 3/20 |

- **Önceki taban (sürüm 1, 2026-10-03):** 33 soru; recall@1 0,88, recall@5 1,00, MRR 0,94; cevaplanamazların 4/8'i eşik altındaydı.
- Belge düzeyinde recall@5 yine 1,00. Fark sayfa düzeyinde ve belgeler arasında ortaya çıkıyor.
- **Kaçanlar (ilk 5'te doğru sayfa yok), 5 soru:**
  - erp-3, erp-17, bt-5: eş anlamlı söyleniş. Örnek: "onaycım iki gündür bakmadı" sorusu müşteri destek belgesinin SLA sayfalarına gidiyor.
  - bt-2, bt-3: cevap P1–P4 tablosunda. Retrieval soruyu örnek olaya bağlıyor ama tabloya gelmiyor.
- **En iyi benzerlik:** cevaplanabilirlerde en düşük 0,45, ortanca 0,62. Cevaplanamazlarda ortanca 0,547, en yüksek 0,702 (depo-yok-4: "PIN kaç günde bir değiştirilmeli", konusu belgede var). İki dağılım artık büyük ölçüde iç içe.

### Belge türleri (ADR-0016)

`FormatEvalTest` (tag `eval`) aynı 11 belgeyi PDF, DOCX ve Markdown olarak ayrı hesaplara yükler ve 140 soruyu her birinde arar. Sayfa ile bölüm örtüşmediği için sayfa recall'u yerine iki şey ölçülür: ilk 5 sonuçta beklenen belge, ilk 5 pasajda beklenen bilgi (`results/retrieval-formats.json`):

| Tür | Belge ilk 5'te | Bilgi ilk 5 pasajda | Cevaplanabilir eşik üstü | Cevaplanamaz eşik altı |
|---|---|---|---|---|
| PDF | 1,00 | 0,946 | 0,955 | 0,214 |
| DOCX | 1,00 | 1,00 | 0,973 | 0,179 |
| MD | 1,00 | 1,00 | 0,973 | 0,179 |

Bu sette, başlıklara göre kesilen bölümler (yaklaşık 3000 karakter) bilgiyi PDF sayfası parçalarından (1000 karakter) daha sık getiriyor. Test, DOCX ve MD'nin PDF'in 0,05 altına düşmesini kırmızı sayar.

```bash
./mvnw -pl verso-app -am test -Dverso.excludedGroups=none -Dgroups=eval -Dtest=FormatEvalTest -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false
```

### Uçtan uca

`results/e2e-gemma4_e2b-gpu.json` (ADR-0022 öncesi, git geçmişinde). Koşu bilgileri:
- Yığın: çalışan yığın, `compose.yaml` + `deploy/compose.gpu.yaml`.
- Donanım: RTX 4070 Ti SUPER.
- Model: gemma4:e2b, local mod.
- Commit `fb919db`, tek koşu, 140 soru, hata yok.

| Alt küme | Cevaplanabilir | Kaynak bulundu | Atıf isabeti | Atıf kesinliği | Doğru bilgi | Cevaplanamaz | "Bulunamadı" |
|---|---|---|---|---|---|---|---|
| **Bütün set** | 112 | **0,902** | **0,866** | **0,929** | **0,848** | 28 | **0,536** |
| Sürüm 1 soruları | 25 | 1,00 | 1,00 | 1,00 | 0,96 | 8 | 0,75 |
| Sürüm 2 soruları | 87 | 0,874 | 0,828 | 0,909 | 0,816 | 20 | 0,45 |
| `paraphrase` | 17 | 0,706 | 0,706 | 0,889 | 0,706 | – | – |
| `abbreviation` | 20 | 0,80 | 0,75 | 0,938 | 0,80 | 5 | 0,40 |
| `number` | 50 | 0,94 | 0,90 | 0,923 | 0,90 | 10 | 0,30 |
| `table` | 26 | 0,885 | 0,808 | 0,885 | 0,769 | – | – |
| `disambiguation` | 21 | 0,905 | 0,762 | 0,773 | 0,762 | – | – |

- **"Bulunamadı":** 15/28 doğru (0,536).
  - Eşik 6 soruyu modele hiç göndermedi; modelin karar verdiği 22 sorunun 9'u doğru (0,409).
  - Yanlış sayılan 13 sorudan 5'inde model doğru bir reddi kendi cümlesiyle yazdı; sıkı tanım bunu yanlış sayar.
  - Kalan 8 soruda model, konusu yakın bir pasajdan atıflı ama soruyu cevaplamayan bir cümle döndürdü. Örnek, crm-yok-1: gümüş sözleşme sorulunca altın sözleşmenin süreleri geldi. "Konu var, cevap yok" türü, bugünkü sistemin en zayıf yeri.
- **Sürüm 1 soruları 11 belgenin içinde:** atıf ve bilgi değişmedi. "Bulunamadı" 7/8'den 6/8'e düştü: yok-3 ("yıllık prim") yeni belgelerle eşiği geçti, model ilgisiz bir cümle yazdı.
- **Kaynak bulunamayan 11 cevaplanabilir soru:**
  - 5'ini eşik durdurdu: kısa kısaltma soruları (yukarıda).
  - 6'sında retrieval doğru sayfayı getirmedi; model de "bulunamadı" dedi.
- **Doğru sayfa, yanlış bilgi:** doğru sayfaya atıf yapılan 97 cevabın 4'ünde beklenen bilgi bulunamadı.
  - izin-1: yine "yirmi gün" dedi.
  - erp-10: yalnız 1.000.000 TL üstü basamağı söyledi.
  - erp-14: günü söylemedi.
  - depo-8: metriğin yanlış negatifi. "Eldeki stoğa dahildir" doğru, ama beklenen kelime "evet".
- **Gecikme:** p50 1,4 sn, p95 3,1 sn, en çok 23 sn (ilk soru, model ısınması). Faz 8 GPU koşusu 33 soruda p50 1,3 sn idi.

## Eşikler ve karar

### ADR-0022 (geçerli)

- **Kapı** (`AnswerabilityGate`, `verso.qa.*`): en benzer pasajın cosine benzerliği ≥ 0,47 ise ya da ≥ 0,44 ve o pasaj hesabın chunk'larının en çok %4,5'inde geçen bir soru terimini içeriyorsa model çağrılır. Füzyon skoru karara girmez.
- **Seçim:** ızgara arama. Kısıt: hiçbir cevaplanabilir soru durmasın; amaç: en çok cevaplanamaz soru dursun. T2 için ızgara 0,45'i verdi; CPU ile GPU embedding'i dördüncü basamakta ayrıştığı için 0,44 seçildi (erp-12: 0,4497 / 0,4502).
- **Çapraz doğrulama:** belge grubuna göre altı kat (sürüm 1, WMS, ERP, BT, CRM, İSG). Kapı ailesi 110/112 cevaplanabilir geçirdi, 5/28 cevaplanamaz durdurdu (kesinlik 0,714, duyarlılık 0,179). Tek cosine eşiği çapraz doğrulamada hiçbir cevaplanamaz soruyu durduramadı.
- **İnce paylar:** isg-10 (0,4712) T1'in hemen üstünde, bt-yok-3 (0,4697) hemen altında; bt-yok-2 ve bt-yok-3'ün terim payı 0,053 (sınır 0,045).
- **Regresyon korumaları (`RetrievalEvalTest`):**
  - recall@5 ≥ 0,97 (ölçülen 0,982, 110/112; bir soru kaybı kırmızı değil, iki soru kırmızı);
  - MRR ≥ 0,86 (ölçülen 0,877);
  - cevaplanabilir kapıdan geçen = 1,00;
  - cevaplanamaz kapıda duran ≥ 0,20 (ölçülen 0,25, 7/28; iki soru kaybı kırmızı).
- Uçtan uca sonuçlar karar için okunur, build'i durdurmaz.

### Sürüm 2 tabanında (ADR-0022 öncesi)

- **Benzerlik eşiği 0,50, değişmedi.** Faz 8'de 0,45'ten 0,50'ye çekilmişti. O ölçümde cevaplanabilir 25 sorunun en düşük en-iyi benzerliği 0,522 idi.
- **Sürüm 2'de eşik iki yönde de iş görmüyor:**
  - 5 cevaplanabilir soru 0,45–0,49 arasında kalıyor ve "bulunamadı" alır, oysa beşi de doğru sayfayı ilk sırada buluyor. Hepsi kısa kısaltma ya da kod sorusu:
    - depo-17 "A-03-02-B…";
    - depo-18 "FEFO ne demek?";
    - erp-12 "MK belgesi nasıl oluşuyor?";
    - bt-16 "CAB toplantısı hangi gün?";
    - isg-10 "Yanlış toplama oranı için hedef nedir?".
  - Cevaplanamaz 28 sorudan yalnız 6'sı eşiğin altında. Konusu belgede olan 20 sorudan 17'si eşiği geçip modele gidiyor; "bulunamadı" kararını chat modeli veriyor.
- **Eval v2 görevinde eşik değiştirilmedi** (ADR-0022 bu açıkları ele aldı, yukarıda). Yukarıdaki iki açık, retrieval iyileştirmelerinin ölçüleceği hedeftir. Aday iyileştirmeler (repoda olmayan bir araştırma notundan):
  - başlık yolu ve belge adı ön eki;
  - hibrit sözcüksel arama;
  - rerank ve iki kademeli eşik.
- **Regresyon korumaları (`RetrievalEvalTest`):**
  - **recall@5 ≥ 0,80:** değişmedi. Ölçülen değer 0,955, yani koruma ölçümün epey altında. Yükseltmek ayrı bir karardır.
  - **Cevaplanabilir eşik üstü:** önceki koruma "1,00'a eşit" idi ve sürüm 2'de kırmızıya döndü (0,955, 107/112). Koruma, ölçülen düzeye, ≥ 0,95'e çekildi. Gerekçe: koruma yeni kayıpları yakalamalı; ölçülen açığı gizlemesin diye bu bölümde ve testin yorumunda açık hata olarak yazıldı. 0,95 × 112 = 106,4, yani bugünkü 107 sorudan biri daha kaybedilirse test kırmızı olur.
  - Uçtan uca sonuçlar karar için okunur, build'i durdurmaz: model çıktısı deterministik değildir.

## Metriklerin tanımı (uçtan uca)

- **Atıf isabeti:** cevaptaki atıflardan en az biri beklenen belge ve sayfada.
- **Atıf kesinliği:** bütün atıflar içinde beklenen sayfaya düşenlerin oranı. Sayfa düzeyinde ölçülür; belge düzeyi neredeyse kendiliğinden 1 çıkıyordu.
- **Cevapta doğru bilgi:** beklenen değer cevapta bütün bir kelime ya da sayı olarak geçiyor. `[n]` işaretleri silinir; "2", "[2]" ya da "30.000" içinde eşleşmez.
- **"Bulunamadı" doğruluğu:** cevap sabit "bulunamadı" cümlesi olmalı. Kaynaksız bir uydurma cevap yanlış sayılır.
  - Sonuç iki gruba ayrılır: kapının durdurduğu sorular (`stoppedByThreshold`; `retrieval.json`'daki `decision`) ve modelin karar verdikleri (`decidedByModel`, `modelNotFoundAccuracy`).
  - `notFoundOrOwnRefusal`: modelin atıfsız, kendi cümlesiyle yazdığı reddi de doğru sayan gevşek ölçü. Sıkı ölçünün yerine geçmez.
- `eval.mjs` CI istemcisinin (`verso-ci`) hesabındaki **bütün belgeleri siler**, sonra örnekleri yükler. Yalnız yerel demo yığınında koşturun.

## Sınırlar

- **Set hâlâ küçük:** 112 cevaplanabilir, 28 cevaplanamaz soru; 11 belge, 44 sayfa. Kaba %95 güven aralıkları (Wilson):
  - recall@5 107/112 → 0,90–0,98;
  - recall@1 90/112 → 0,72–0,87.
  - İki retrieval ayarını aynı sette karşılaştırırken soru bazında farka bakın; iki ayrı aralığı karşılaştırmak yerine "kaç soru kazanıldı, kaç soru kaybedildi" sayısı daha duyarlıdır.
- **Sentetik ve tek yazar:** belgeleri ve soruları aynı kişi yazdı. Söyleniş çeşitliliği gerçek çalışanlarınkinden dardır. Gerçek bir müşteri belgesi seti (anonimleştirilmiş, müşteri onayıyla) eklendiğinde eşik yeniden ölçülür.
- **Dosya adı sözcük örtüşmesi:** örnek PDF'lerin sayfa altbilgisi dosya adını taşır ("depo-yonetim-kilavuzu.pdf — sayfa 3 / 6"). Bu, soruyla sözcük örtüşmesi yaratıp retrieval'ı kolaylaştırır.
- **Eşikler aynı veriyle ayarlandı** (ADR-0012 ve ADR-0022). ADR-0022 belge grubuna göre çapraz doğrulama sayılarını ayrıca verir; gerçek bir belge setinde yeniden ölçülür.
- **Belirsiz sorular:**
  - izin-1 "beş yıldan az" diyor, belge "bir yıldan beş yıla kadar" diyor.
  - uzaktan-2'de beklenen "10.00", esnek başlangıç saati ("07.30 ile 10.00") cevabıyla da eşleşir.
  - Sürüm 2'de bazı beklenen değerler kısadır ("bir", "on", "iki"); doğru bilgi metriğinde yanlış pozitif verebilir. Atıf isabeti bu sorularda daha güvenilirdir.
