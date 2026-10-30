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

Konuşma bağlamı isteyen takip soruları bu sette yok; ayrı bir sette ölçülür (aşağıda "Takip soruları").

### Müşteriye özel altın set (ADR-0049)

Bu klasördeki set **ürünün** regresyon setidir: Verso'nun kendisi değişince koşulur. Her kurulumun ayrıca **kendi** altın seti vardır ve veritabanında durur (`qa.golden_case`): yöneticinin onayladığı her bilgi kartı bir vakadır (soru ve vektörü, beklenen belge, sayfa ve parça özetleri, ana bilgiler). Bu set her gece kurulumun kendi belgelerinde, model çağırmadan koşulur: kaynak metin hâlâ duruyor mu (kaynak kayması), hibrit arama bağlı parçayı ilk 5'te buluyor mu (recall@5). Sonuçlar `qa.eval_run` ve panelde Yönetim → "Onaylı bilgiler"dedir; recall 0,02'den fazla düşerse ya da kayma %10'u geçerse `KnowledgeRecallDropped` uyarısı açılır. Bu set kurumun verisidir, repoya girmez; buradaki 140 soru kuruluma girmez.

## Takip soruları (2026-10-25, ADR-0046)

**[`followup-set.json`](followup-set.json):** 32 iki turlu konuşma. İlk soru bu setin cevaplanabilir bir sorusu (`first`), takip sorusu ancak onunla anlaşılır ("Peki P2 için?", "Bunun çözüm hedefi kaç saat?"). Türler: `ellipsis` 16, `pronoun` 9, `implicit` 7 (işaret sözcüğü yok), `disambiguation` 7. Her beklenen sayfa PDF metninden doğrulandı. Yöntem ve bütün tablolar: [`docs/evidence/takip-sorulari-olcumu.md`](../docs/evidence/takip-sorulari-olcumu.md).

| Ölçüm (GPU, gemma4:e2b) | Yeniden yazma kapalı | Açık (varsayılan) |
|---|---|---|
| Takip soruları: recall@1 / recall@5 / MRR (`results/retrieval-followup-gpu.json`) | 0,563 / 0,875 / 0,681 | **0,656 / 0,938 / 0,772** |
| Takip soruları: atıf isabeti / doğru bilgi (`results/followup-{off,on}-gpu.json`) | 0,719 / 0,750 | **0,875 / 0,844** |
| Bağımsız 140 soru, konuşma içinde: atıf isabeti / doğru bilgi / "bulunamadı" | 0,902 / 0,884 / 0,571 | 0,920 / 0,893 / 0,607 |
| Bağımsız sorular: recall@5 / kapı (cevaplanabilir geçen, cevaplanamaz duran) | 0,982 / 112, 7 | 0,982 / 112, 7 |
| Yeniden yazma çağrısı p50 / p95 | – | 381 / 676 ms |
| Sezgisel kural: kesinlik / duyarlılık | – | 0,862 / 0,781 |

- Cevap prompt'una yazılmış sorguyu da eklemek (`answer-with-context`) takip sorularında atıfı 0,844'e, doğru bilgiyi 0,781'e düşürdü; kapalı.
- Modelsiz taban "ilk soru + takip" çevrimdışı recall@5'te 0,969 (bir soru fazla); uçtan uca ölçülmedi.

```bash
ARM=on OUT=followup-on-gpu.json APP_CONTAINER=verso-verso-app-1 KEEP_DOCS=1 node scripts/followup-eval.mjs
```

Kol, çalışan uygulamanın ayarıdır (`VERSO_QA_FOLLOWUP_ENABLED`, `verso.qa.followup.answer-with-context`); `ARM` yalnız etikettir. Retrieval: `node eval/embedding-olcumu/takip-set.mjs . eval/results/followup-on-gpu.json set.json`, ardından `lex.mjs` ve `olcum.mjs` (yalnız bge-m3) ve `takip-rapor.mjs`.

## Dayanak kontrolü (2026-10-25, ADR-0047)

[`grounding-set.json`](grounding-set.json) 192 cümle içerir; 57'si desteklenmiyor.

- **Kaynak:** `results/e2e-rerank-off-gpu.json`'ın atıflı cevap cümleleri.
- **Etiket:** her cümle, atıf yaptığı sayfaya (`samples/src`, sayfalar `---` ile ayrılmış) göre elle etiketlendi.
- **Bozulmuş kopyalar:** sayı, sayı sözcüğü, olumsuzlama, ad ve kod değiştirildi. Destekleyen yeniden yazımlar da eklendi.
- **Alanlar:** her öğede `cites` (`belge.pdf#sayfa`), `question` (`eval-set.json`'daki soru kimliği), `kind` ve `label` vardır.
- Yöntem, adaylar (NLI dahil) ve bütün tablolar: [`docs/evidence/dayanaklilik-olcumu.md`](../docs/evidence/dayanaklilik-olcumu.md).

| Ölçüm (sözcüksel kontrol, varsayılan) | Sonuç |
|---|---|
| Kesinlik / duyarlılık | 1,00 / 0,46 (26/57) |
| Sayı ve kod bozulmalarında duyarlılık | 25/33 |
| Olumsuzlama ve ad bozulmalarında duyarlılık | 0/21 (kapsam dışı) |
| Doğal 128 cümle / dışarıda tutulan 110 gerçek cümle: işaretlenen | 1 / 1, ikisi de doğru |

`GroundingEvalTest` bu seti her build'de koşar: yanlış alarm 0, en az 26 doğru işaret.

## Çok adımlı sorular (2026-10-27, ADR-0055)

[`multihop-set.json`](multihop-set.json) 20 soru içerir: 7 karşılaştırma, 9 köprü (bir belgedeki bilgi diğerindeki adımı tamamlar), 4 çok parçalı.

- **Kaynaklar:** 17 soru iki ayrı belge ister (örnek: BT ile müşteri desteğinin P1 ilk yanıt süreleri, satın alma prosedürü ile ERP onay basamakları). Her sorunun `sources` listesinde belge, sayfa(lar) ve cevapta geçmesi beklenen ifade (`expect`, eşanlamlılar `|` ile) vardır.
- **Kısmi sorular:** `partial: true` olan 2 soruda bir parça hiçbir belgede yoktur (`missing`: kıdem tazminatı, maaş zammı); cevabın o parçayı "bulunamadı" diye açıkça söylemesi beklenir.
- **Doğrulama:** her `expect` ifadesi belirtilen sayfanın `samples/src` metninde (PDF'lerin kaynağı, sayfalar `---` ile ayrılmış) bütün sözcük olarak geçer; `missing` konuları 11 belgenin hiçbirinde geçmez. `node --test scripts/multihop-set.test.js` bunu her koşuda denetler.
- **Sözcüksel kapı:** `ReasoningGateEvalTest` her build'de koşar. Kapı çok adımlı 20 sorunun 19'unda ateşler (kaçan `mh-05`: karşılaştırma sözcüğü yok, iki sistem adı var; varlık kapısı gerçek belgelerle ölçülecek). Tek adımlı 140 sorunun 3'ünde ateşler (`depo-5`, `depo-11`: "fark" ad olarak; `erp-18`: "ERP ile WMS arasında").
- **Uçtan uca ölçüm (bekliyor, gerçek model ve Docker ister):** `node scripts/eval.mjs --set multihop --compare`. Uygulama kapsayıcısı önce `VERSO_QA_REASONING_ENABLED=false`, sonra `true` ile yeniden oluşturulur; belgeler bir kez yüklenir. Sonuç: `results/multihop-<model>-off.json`, `-on.json` ve `multihop-compare-<model>.json`. Metrikler: kaynak isabeti (beklenen her belge ve sayfa atıfta mı), tüm kaynaklar atıfta oranı, olgu oranı, kısmi sorularda "bulunamadı" oranı, ayrıştırılan soru oranı, gecikme p50/p95. Ayrıntı: [`docs/evidence/cok-adimli-akil-yurutme.md`](../docs/evidence/cok-adimli-akil-yurutme.md).
- **Önyargı:** set ve kapı sözcükleri aynı oturumda yazıldı; kapının setteki isabeti iyimserdir. Gerçek kullanıcı sorularında yeniden ölçülür.

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

## Rerank (2026-10-25, ADR-0043)

İsteğe bağlı çapraz kodlayıcı (bge-reranker-v2-m3, HF TEI, GPU), hibrit aramanın ilk 20 adayı üzerinde. Yöntem ve bütün tablolar: [`docs/evidence/rerank-olcumu.md`](../docs/evidence/rerank-olcumu.md).

| Ölçüm | Hibrit | Hibrit + rerank |
|---|---|---|
| recall@1 / recall@5 / MRR (`results/retrieval-rerank-gpu.json`) | 0,795 / 0,982 / 0,877 | **0,929 / 1,00 / 0,958** |
| `paraphrase` / `table` recall@1 | 0,647 / 0,731 | 0,824 / 0,885 |
| Kapı: cevaplanabilir geçen / cevaplanamaz duran | 112/112, 7/28 | 112/112, 7/28 (kapının okuduğu pasaj korunur) |
| Uçtan uca atıf isabeti / doğru bilgi (`results/e2e-rerank-{off,on,on2}-gpu.json`) | 0,902 / 0,902 | 0,893–0,911 / 0,902–0,920 |
| Uçtan uca sıkı "bulunamadı" | 16/28 | 14/28 (iki koşuda da) |

Çevrimdışı ölçüm (TEI çalışırken; `RERANK_URL`, `CANDIDATES`, `PASSES`):

```bash
node eval/embedding-olcumu/rerank.mjs lex.json emb.json eval/eval-set.json out.json gpu
```

`lex.json` `lex.mjs`'in, `emb.json` bge-m3 vektörlerinin (`{docs, qs}`) çıktısıdır; `docs/evidence/embedding-olcumu.md`'deki düzenek.

## Docling (2026-10-24, ADR-0032)

İsteğe bağlı docling-serve yan konteyneri, üç belge setinde, sidecar'sız ve sidecar'lı. Setler aynı kaynaklardan `SamplePdfs` ile üretilir:

- `samples`: depodaki PDF'ler; tablolar `|` ile yazılmış metin.
- `grid`: aynı satırlar çizgili tablo, başlık satırı kalın.
- `scanned`: `grid`'in sayfaları 200 dpi gri JPEG, metin katmanı yok.

Çizgili ve taranmış setler commit edilmez (çizim makinenin fontlarına bağlı); her koşuda üretilir.

```bash
./mvnw -pl verso-app -am test -Dtest=SamplePdfsTest -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false -Dverso.samples.variants=<DIR>
./mvnw -pl verso-app -am test -Dverso.excludedGroups=none -Dgroups=eval -Dtest=RetrievalEvalTest -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false \
  -Dverso.eval.samples-dir=<DIR>/grid -Dverso.eval.out=retrieval-grid-docling.json \
  -Dverso.document.docling.enabled=true -Dverso.document.docling.base-url=http://127.0.0.1:<port>
SAMPLES_DIR=<DIR>/grid OUT=e2e-grid-docling.json node scripts/eval.mjs
```

`RetrievalEvalTest` her belgeyi bir kez ayrıştırıp süreyi ayrıca ölçer (`summary.parsing`). Docling ölçümünde yan konteyner 3 CPU ve 4 GB ile çalıştı. Sorunun embedding süresi bu koşularda üretim değeri olan 10 sn'dir: paylaşılan makinede 2 sn yetmedi.

### Retrieval

`results/retrieval-{samples,grid,scanned}-{jdk,docling}.json`; bge-m3 CPU.

| Set | Ayrıştırıcı | recall@1 | recall@5 | MRR | `table` recall@1 | `table` recall@5 | `table` MRR | Kapıda duran cevaplanamaz | Ayrıştırma (sayfa başına) |
|---|---|---|---|---|---|---|---|---|---|
| samples | JDK | 0,795 | 0,982 | 0,877 | 0,731 | 0,962 | 0,827 | 7/28 | 7 ms |
| samples | docling | 0,804 | 0,982 | 0,883 | 0,731 | 0,962 | 0,830 | 5/28 | 0,67 sn |
| grid | JDK | 0,804 | 0,982 | 0,885 | 0,769 | 0,962 | 0,849 | 6/28 | 9 ms |
| grid | docling | 0,830 | 0,964 | 0,890 | 0,808 | 0,962 | 0,878 | 5/28 | 1,45 sn |
| scanned | JDK | 0 | 0 | 0 | 0 | 0 | 0 | – (11 belge `NO_TEXT`) | – |
| scanned | docling | 0,813 | 0,964 | 0,875 | 0,731 | 0,962 | 0,824 | 4/28 | 2,6 sn (OCR) |

- `samples` + JDK, ADR-0022 tabanıyla birebir aynı: ölçüm tekrarlanabilir.
- Docling `|` ile yazılmış satırları tablo saymıyor, bir paragrafa birleştiriyor. Bu yüzden `samples`'ta kazanç yok, `RetrievalEvalTest`'in "cevaplanamaz ≥ 0,2" koruması sidecar'lı koşuda tutmuyor (0,179).
- Çizgili tabloda docling tabloyu ve başlığını tanıyor: `table` recall@1 ve MRR yükseliyor; bütün sette recall@5 iki soru düşüyor.
- Taranmış sette Tesseract `tur` + `eng` ile okunan metin, metin katmanlı sürümün recall@5'ine yakın (0,964 / 0,982). Taranmış tabloların hücreleri OCR'da çoğu zaman tek sütuna düşüyor; `table` sıralaması `samples` düzeyinde kalıyor.
- Yan konteynerin bellek tepesi 2,2 GB, boşta ~1,1 GB.

### Uçtan uca

`results/e2e-{samples,grid,scanned}-docling.json` ve `e2e-samples-jdk.json`; gemma4:e2b GPU, geçici bir yığında (kendi veritabanı, `deploy/compose.docling.yaml`).

| Set | Ayrıştırıcı | Kaynak bulundu | Atıf isabeti | Doğru bilgi | "Bulunamadı" | `table` atıf isabeti | `table` doğru bilgi |
|---|---|---|---|---|---|---|---|
| samples | JDK (aynı Ollama) | 0,955 | 0,902 | 0,902 | 0,536 | – | – |
| samples | JDK (ikinci Ollama; `e2e-samples-jdk.json`) | 0,964 | 0,911 | 0,884 | 0,607 | 0,808 | 0,769 |
| samples | docling | 0,920 | 0,884 | 0,893 | 0,643 | 0,808 | 0,769 |
| grid | JDK | ölçülemedi | | | | | |
| grid | docling | 0,920 | 0,848 | 0,848 | 0,679 | 0,769 | 0,769 |
| scanned | JDK | 0 (belge yok) | 0 | 0 | – | 0 | 0 |
| scanned | docling | 0,929 | 0,884 | 0,848 | 0,643 | 0,846 | 0,769 |

- Docling koşuları ve ilk JDK koşusu ana yığının GPU Ollama'sıyla koşuldu. İlk JDK koşusunun sonuç dosyası ikinci koşunun üzerine yazıldı; sayıları koşu günlüğünden alındı (`docs/evidence/adr-0032-dogrulama.md`).
- İkinci JDK koşusu ayrı bir Ollama ile yapıldı ve `e2e-gemma4_e2b-gpu.json` ile birebir aynı çıktı. İki Ollama örneği arasındaki fark 0,01–0,07, docling etkisiyle aynı büyüklükte: uçtan uca farklar yön gösterir, kesin değildir.
- `grid` + JDK koşusu iki kez başladı. İkisinde de ana yığının Ollama'sı koşu sırasında kapandı (`EMBEDDING_MODEL_UNAVAILABLE`, 140 sorunun 74'ünde ve 97'sinde); sonuç atıldı, ölçüm eksik.

## Eşikler ve karar

### ADR-0063 (geçerli): başlıklı vektörler

- **Kapı eşikleri değişmedi:** 0,47 / 0,44 / 0,045 (aşağıda ADR-0022).
  - ADR-0061'den sonra dış sette ret 0,867'den 0,667'ye düşmüştü. Soru başına ölçümde kapı orada önce de sonra da yalnız 1–2 soruyu durduruyordu; retleri chat modeli veriyordu.
  - Konusu belgede olan cevaplanamazlar (dış 0,51–0,69) cevaplanabilirlerin ortasında kalıyor (dış p10 0,613). Hiçbir kapı ayarı dış setin reddini artırmadı; eşiği 0,52'ye çekmek kısaltmayla sorulan üç merkez sorusunu kaybettirdi.
  - Kapının başlıksız kosinüse bakması da reddedildi. Ayırma gücü (AUC) iç sette 0,799'dan 0,833'e çıkıyor, dış sette 0,917'den 0,856'ya düşüyor.
- **`passage-min-similarity` 0,50 → 0,55.** Başlık, konuyu tutan pasajların kosinüsünü yükseltti, prompt'a giren pasaj arttı ve model daha çok "bir şey buldu". Taban iki sette 0,50 / 0,53 / 0,55 / 0,60 ile uçtan uca tarandı:

  | Taban | İç: bulundu / atıf / doğru bilgi / ret | Dış: bulundu / atıf / doğru bilgi / ret |
  |---|---|---|
  | 0,50 | 0,902 / 0,848 / 0,884 / 0,714 | 0,925 / 0,698 / 0,784 / 0,667 |
  | 0,53 | 0,893 / 0,839 / 0,857 / 0,750 | 0,921 / 0,705 / 0,777 / 0,733 |
  | **0,55** | **0,893 / 0,839 / 0,848 / 0,786** | **0,925 / 0,731 / 0,784 / 0,756** |
  | 0,60 | 0,830 / 0,768 / 0,759 / 0,786 | 0,908 / 0,725 / 0,777 / 0,867 |

  Kabul edilen bedel: iç sette doğru bilgi −4 soru, bulunma ve atıf isabeti −1 soru.
- **Ölçüm aracı:** `GATE_PROBE=1 node scripts/eval.mjs` (`scripts/gate-probe.mjs`) her sorunun kapı girdisini cevabın yanına yazar:
  - en iyi kosinüs, terim payı, karar ve prompt'taki pasaj sayısı;
  - `GATE_PROBE=only`: soru sormadan yalnız ölçüm;
  - `GATE_PROBE_SPLIT=1`: ek olarak başlıksız ve yalnız başlık kosinüsü.

  Prob embedding sunucusuna `VERSO_EVAL_OLLAMA_URL` üstünden ulaşır (`deploy/compose.local.yaml` yayınlar), PostgreSQL'e `docker exec` ile. "Kapının durdurduğu" sayısı artık bu probdan gelir; `retrieval.json` yalnız iç sette ve prob yokken okunur.
- **`RetrievalEvalTest` koruması:** cevaplanabilir kapıdan geçen ≥ 0,97 (ölçülen 0,973, 109/112).
  - bt-16 (0,4358), isg-10 (0,4611) ve depo-17 (0,4625) başlıkla kapının altına indi: ADR-0061'in kabul edilen bedeli.
  - Ölçülen diğer değerler, başlıklı vektörlerle: recall@5 0,982, MRR 0,876, duran cevaplanamaz 0,214 (6/28; koruma ≥ 0,20).
- Ayrıntı: `docs/evidence/kapi-kalibrasyonu-olcumu.md`.

### ADR-0022: kapı (eşikleri geçerli)

- **Kapı** (`AnswerabilityGate`, `verso.qa.*`): en benzer pasajın cosine benzerliği ≥ 0,47 ise ya da ≥ 0,44 ve o pasaj hesabın chunk'larının en çok %4,5'inde geçen bir soru terimini içeriyorsa model çağrılır. Füzyon skoru karara girmez.
- **Seçim:** ızgara arama. Kısıt: hiçbir cevaplanabilir soru durmasın; amaç: en çok cevaplanamaz soru dursun. T2 için ızgara 0,45'i verdi; CPU ile GPU embedding'i dördüncü basamakta ayrıştığı için 0,44 seçildi (erp-12: 0,4497 / 0,4502).
- **Çapraz doğrulama:** belge grubuna göre altı kat (sürüm 1, WMS, ERP, BT, CRM, İSG). Kapı ailesi 110/112 cevaplanabilir geçirdi, 5/28 cevaplanamaz durdurdu (kesinlik 0,714, duyarlılık 0,179). Tek cosine eşiği çapraz doğrulamada hiçbir cevaplanamaz soruyu durduramadı.
- **İnce paylar:** isg-10 (0,4712) T1'in hemen üstünde, bt-yok-3 (0,4697) hemen altında; bt-yok-2 ve bt-yok-3'ün terim payı 0,053 (sınır 0,045).
- **Regresyon korumaları (`RetrievalEvalTest`):**
  - recall@5 ≥ 0,97 (ölçülen 0,982, 110/112; bir soru kaybı kırmızı değil, iki soru kırmızı);
  - MRR ≥ 0,86 (ölçülen 0,877);
  - cevaplanabilir kapıdan geçen = 1,00 (ADR-0063 ile ≥ 0,97);
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
