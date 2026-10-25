# Takip soruları: ölçüm ve kanıt kaydı (ADR-0046)

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

## Künye

- **Tarih:** 2026-10-25
- **Branch:** `feature/doguyaras-takip-sorulari` (`origin/develop` `b183e54` üzerinden).
- **Ortam:** Windows 11 + Docker Desktop; 31 GB RAM, NVIDIA RTX 4070 Ti SUPER 16 GB; çalışan GPU'lu yığın (`compose.yaml` + `deploy/compose.gpu.yaml`), Ollama, bge-m3:567m, gemma4:e2b, local mod. Gözlem servisleri kapalı, ayrı yığın açılmadı.
- **İmaj:** bu branch'ten `verso:takip`; yalnız `verso-app` servisi bu imajla ve iki ortam değişkeniyle yeniden açıldı (kollar aşağıda). Ölçümden sonra yığının kendi `verso:local` imajına dönüldü.
- **Davranışsal kapsam (seviye 3):** var. Uçtan uca ölçüm çalışan yığında API üzerinden; retrieval çevrimdışı düzenekle (aynı yığının PostgreSQL ve Ollama'sı).
- **Kapsam dışı:** CPU'da gecikme ölçülmedi (aşağıda tahmin). Bulut modunda koşulmadı.

## 1. Set

`eval/followup-set.json`: 32 iki turlu konuşma, 11 örnek belgenin hepsinden.

- İlk tur `eval/eval-set.json`'daki cevaplanabilir, tek başına anlaşılır bir sorudur (id ile).
- Takip sorusu ancak ilk soruyla anlaşılır. Türler (`tags`): `ellipsis` 16 ("Peki P2 için?"), `pronoun` 9 ("Bunun çözüm hedefi kaç saat?"), `implicit` 7 (işaret sözcüğü yok: "Kendiliğinden açılması ne kadar sürer?"), `disambiguation` 7 (ilk soru olmadan benzer başka belge daha yakın: BT ile müşteri desteğinin iki P1–P4 tablosu, WMS %2 ile ERP %5 kabul toleransı, oltalama ile tahliye tatbikatı).
- Beklenen belge, sayfa ve bilgi verilir. **Her beklenen bilgi, beklenen sayfanın PDF metninde doğrulandı** (`ChunkDump` çıktısı üzerinde `eval.mjs`'in tam kelime eşleşmesiyle: 32/32). İlk soruların hepsi setin cevaplanabilir sorularıdır.

## 2. Yöntem

**Uçtan uca** (`scripts/followup-eval.mjs`, çalışan yığın, API):
- Her konuşma: ilk soru yeni konuşmada, takip sorusu aynı konuşmada (`conversationId`).
- Bağımsız sorular: `eval-set.json`'ın 140 sorusu art arda tek bir konuşmada. Böylece her sorunun önünde iki tur vardır ve sezgisel kural, yanlış ateşlerse, gerçekten yeniden yazdırır.
- Metrikler `eval.mjs` ile aynı: kaynak bulundu, atıf isabeti (beklenen belge ve sayfada atıf), doğru bilgi, "bulunamadı".
- Sunucunun her cevap için yazdığı içeriksiz log satırı (`followUp=… rewriteMs=…`) istek sırasıyla eşlendi (204/204). Sezgisel kuralın kararı ve yeniden yazma süresi oradan geldi.
- Kollar (`verso-app` ortamı):
  - **kapalı:** `VERSO_QA_FOLLOWUP_ENABLED=false` (v1 davranışı);
  - **açık:** yeniden yazma, cevap prompt'unda yalnız sorulan soru (varsayılan);
  - **açık + bağlam:** ayrıca `answer-with-context=true` (cevap prompt'una "Sorunun konuşmaya göre açık hali: …").

**Retrieval (çevrimdışı)**, `eval/embedding-olcumu/{takip-set,lex,olcum,takip-rapor}.mjs`:
- Düzenek `docs/evidence/embedding-olcumu.md`'deki: 75 chunk, üretimdeki sözcüksel SQL (çalışan yığının PostgreSQL'inde oturuma özel `TEMP` tablo), bge-m3 cosine ilk 20, RRF k = 60, ilk 5, kapı.
- Sorgular: takip sorusu olduğu gibi (`raw`), sunucunun "açık" kolda döndürdüğü `contextQuery` (`rw`; yeniden yazılmadıysa `raw`), ve saf bir taban: ilk soru + takip sorusu (`concat`).
- **Düzenek doğrulaması:** 140 bağımsız sorunun `raw` satırı resmî `RetrievalEvalTest` sonucunu aynen verdi (0,795 / 0,982 / 0,877; kapı 112/112 ve 7/28).

## 3. Retrieval (`eval/results/retrieval-followup-gpu.json`)

| Alt küme | n | Sorgu | recall@1 | recall@5 | MRR | Kapıdan geçen |
|---|---|---|---|---|---|---|
| **Takip soruları** | 32 | olduğu gibi | 0,563 | 0,875 | 0,681 | 30 |
| | | **yeniden yazılmış** | **0,656** | **0,938** | **0,772** | 31 |
| | | ilk soru + takip (taban) | 0,656 | 0,969 | 0,797 | 32 |
| `ellipsis` | 16 | olduğu gibi / yeniden yazılmış | 0,625 / 0,688 | 0,938 / 1,00 | 0,745 / 0,825 | 15 / 15 |
| `pronoun` | 9 | olduğu gibi / yeniden yazılmış | 0,556 / 0,778 | 0,778 / 0,889 | 0,633 / 0,815 | 8 / 9 |
| `implicit` | 7 | olduğu gibi / yeniden yazılmış | 0,429 / 0,429 | 0,857 / 0,857 | 0,595 / 0,595 | 7 / 7 |
| `disambiguation` | 7 | olduğu gibi / yeniden yazılmış | 0,714 / 0,571 | 1,00 / 1,00 | 0,833 / 0,762 | 7 / 7 |
| **Bağımsız sorular** | 112 | olduğu gibi | 0,795 | 0,982 | 0,877 | 112 |
| | | sunucunun sorgusu (4'ü yeniden yazıldı) | 0,795 | 0,982 | 0,879 | 112 |
| Cevaplanamaz bağımsız sorular, kapıda duran | 28 | olduğu gibi / sunucunun sorgusu | – | – | – | 7 / 7 |

- Takip sorularında recall@5 +2/−0 (takip-2, takip-6), recall@1 +3 net. Bağımsız sorularda recall@5 değişmedi (+0/−0).
- `implicit` takip sorularını sezgisel kural yakalamıyor; sorgu değişmiyor (aşağıda 5).
- `disambiguation`'da recall@1 bir soru düştü: yeniden yazma bazen ayırt edici sözcüğü atıyor (takip-17 "4.500 TL'lik farkı kim onaylar?": "sayım" gitti).
- **İlk soru + takip** tabanı recall@5'te bir soru daha iyi, modelsiz. Fark bir soru (Wilson aralıkları iç içe), uçtan uca ölçülmedi, konu değişen konuşmada ve kullanıcıya gösterilecek bir sorgu olarak sorunlu. ADR-0046'da yeniden değerlendirme adayı.

## 4. Uçtan uca (`eval/results/followup-{off,on,on-context}-gpu.json`)

| Alt küme | n | Kol | Kaynak bulundu | Atıf isabeti | Doğru bilgi | "Bulunamadı" |
|---|---|---|---|---|---|---|
| **Takip soruları** | 32 | kapalı | 0,906 | 0,719 | 0,750 | – |
| | | **açık** | **0,969** | **0,875** | **0,844** | – |
| | | açık + bağlam | 0,969 | 0,844 | 0,781 | – |
| `ellipsis` | 16 | kapalı / açık / açık + bağlam | | 0,750 / 0,938 / 0,938 | 0,813 / 0,875 / 0,813 | |
| `pronoun` | 9 | kapalı / açık / açık + bağlam | | 0,667 / 0,889 / 0,778 | 0,667 / 0,889 / 0,778 | |
| `implicit` | 7 | kapalı / açık / açık + bağlam | | 0,714 / 0,714 / 0,714 | 0,714 / 0,714 / 0,714 | |
| `disambiguation` | 7 | kapalı / açık / açık + bağlam | | 0,714 / 1,00 / 0,857 | 0,857 / 0,857 / 0,571 | |
| İlk turlar | 32 | üç kolda da | 0,969 | 0,906 | 0,875 | – |
| **Bağımsız sorular** (112 + 28) | 140 | kapalı | 0,964 | 0,902 | 0,884 | 0,571 |
| | | **açık** | 0,973 | 0,920 | 0,893 | 0,607 |
| | | açık + bağlam | 0,964 | 0,911 | 0,893 | 0,571 |

- **Takip soruları, açık / kapalı, soru bazında:** atıf isabeti +5/−0 (takip-2, 3, 6, 23, 24), doğru bilgi +4/−1 (kayıp takip-7: "10.000 TL'lik masrafı kim onaylar?" doğru sayfaya atıf, ama cevapta "Finans Müdürü" yok).
- **Bağımsız sorular zarar görmedi:** atıf isabeti açık / kapalı +2/−0. Kapalı ve açık kollar arasındaki 0,02–0,04'lük farklar, iki Ollama örneği arasındaki farkla aynı büyüklükte (`eval/README.md`, docling bölümü): yön gösterir, kazanç sayılmaz.
- **Cevap prompt'una bağlam eklemek yardım etmedi:** açık + bağlam, açık koldan takip sorularında atıf 0,031, doğru bilgi 0,063 düşük. Küçük model iki soru satırını birlikte cevaplamaya çalışıyor. Kapalı kaldı (`answer-with-context: false`).

## 5. Sezgisel kural

Kural (`FollowUpRewriter.needsContext`): en çok 3 kelime, ya da bir gösterme/zamir/bağlaç sözcüğü ("bu, bunun, şu, o, onun, ora…, peki, öyle, diğer, hangisi…"; "ya", "ya da" değilse). Konuşmanın ilk sorusunda ve `conversationId` yokken hiç uygulanmaz.

| | Kural ateşledi | Ateşlemedi |
|---|---|---|
| Takip sorusu (32) | 25 | 7 (hepsi `implicit`: takip-4, 8, 19, 22, 26, 27, 30) |
| Bağımsız soru, konuşmada (139) | 4 (depo-2, depo-18, erp-3, bt-2) | 135 |

- **Kesinlik 0,862** (25/29), **duyarlılık 0,781** (25/32). Bağımsız sorularda yanlış ateşleme oranı 4/139 = 0,029: model çağrısının %97'si boşa yapılmıyor.
- Yanlış ateşlenen 4 bağımsız soru zarar görmedi: model dördünü de anlamı koruyarak yeniden yazdı; retrieval aynı, erp-3 atıfı kazanıldı (kapalı kolda kaçıyordu).
- Kural, set yazılmadan önce sabitlendi; tek ayar `aynı` sözcüğünün listeden çıkarılmasıydı: bağımsız sette 3 yanlış ateşleme yapıyordu ("aynı gün", "aynı zamanda", "aynı anda"). Bu, bağımsız sete bakılarak yapıldı; kesinliği iyimser gösterebilir.
- Model 29 yeniden yazmanın 28'inde farklı bir sorgu, 1'inde aynı soruyu döndürdü; hata ya da zaman aşımı yok (`fallback` 0).

## 6. Gecikme (GPU)

| Ölçü | p50 | p95 | En çok |
|---|---|---|---|
| Yeniden yazma çağrısı (sunucu log'u, 29 çağrı) | **381 ms** | **676 ms** | 862 ms |
| Yeniden yazılan 25 takip sorusu, uçtan uca, açık | 2,20 sn | 4,08 sn | – |
| Aynı 25 soru, kapalı | 1,28 sn | 2,93 sn | – |
| Bağımsız 140 soru, açık / kapalı | 1,29 / 1,20 sn | 3,39 / 2,10 sn | – |

- Uçtan uca farkın (+0,9 sn p50) bir kısmı yeniden yazma, kalanı açık kolda iki sorunun daha cevap üretmesi (kaynak bulundu 29/32 → 31/32) ve daha uzun cevaplar.
- Bağımsız sorularda p95 farkı (3,39 / 2,10 sn) yeniden yazmadan değil (yalnız 4 soru): koşular arası gürültü; p50 farkı 90 ms.
- Yeniden yazma sohbet slotunu (`chat-concurrency: 1`) o süre kadar tutar; `docs/evidence/yuk-olcumu.md`'deki darboğaz budur. GPU'da çağrı başına ~0,4 sn, yalnız takip sorularında.
- **CPU tahmini (ölçülmedi):** CPU'da gemma4:e2b ~20–60 sn'lik cevapta prompt değerlendirme baskındır; yeniden yazma prompt'u ~150–250 token, çıktı ~20 token: birkaç saniye beklenir. Sert sınır `verso.qa.followup.timeout` 10 sn; aşılırsa soru olduğu gibi aranır. CPU kurulumunda ölçülüp gerekirse kapatılır.

## 7. Davranışsal doğrulama (testler)

| Kanıt | Ne gösterir | Durum |
|---|---|---|
| `FollowUpRewriterTest` (28) | sezgisel kural (pozitif/negatif örnekler), prompt sınırları (soru, cevap, toplam; en eski tur önce düşer), yalnız son atıflı cevap, ayraç taklidinin etkisizleşmesi, çıktı ayrıştırma ve sınır, ayar doğrulaması | yerelde geçti |
| `FollowUpQuestionTest` (10) | retrieval yeniden yazılmış sorguyla, cevap prompt'unda geçmiş ve sorgu yok, log'da içerik yok; bağımsız soruda geçmiş okunmaz; kapalı/konuşmasız/geçmişsiz yol; zaman aşımında (500 ms) soru olduğu gibi, slot ve devre etkilenmez; hata ve geçersiz çıktı; akışta `REWRITING` ilk aşama; istemci bu aşamada giderse model çağrılmaz | yerelde geçti |
| `FollowUpApiTest` (CI, Testcontainers) | gerçek veritabanıyla: takip sorusu yeniden yazılmış sorguyla bulunur (test embedding'i konusuz soruda bir şey bulmaz), cevap prompt'u sorulan soruyu taşır, geçmişte sorulan soru saklanır, akışta `REWRITING` ve `done.contextQuery`; yeniden yazma yalnız adı verilen konuşmayı görür, başka hesap 404; log'da içerik yok | CI'da |
| `QaApiContractTest` | `contextQuery` en sonda (eklemeli), `REWRITING` ilk aşama | yerelde geçti |
| `scripts/panel.test.mjs` | "Soru bağlamla yeniden yazılıyor" aşaması, `done.contextQuery`, "Bağlamla arandı" notu (tek satır, bidi ve kontrol karakteri yok, kırpılmış, metin olarak) | yerelde geçti |

## 8. Ölçüm sırasında yığında bulunan sorun

- Çalışan yığının veritabanı bağlayıcı modülünden (ADR-0038) önceki bir sürümdeydi: `connector` şeması yoktu, `document`/`qa` outbox migration'ları uygulanmamıştı. README'deki yükseltme yolu uygulandı (`40-modules.sh`, ardından `migrate`); öncesinde yedek alındı (`backup once`).
- `secrets/SECRET_DB_CONNECTOR_{,MIGRATE_}PASSWORD` dosyaları yoktu; Docker, kaynak yokken bağlanan secret için host'ta **boş dizin** oluşturmuştu. `40-modules.sh`'in `require_secret`'i `-r` ve `-s` ile dizini geçerli saydı, `cat` boş döndü ve iki rol **parolasız** oluştu (script sonda bunu yakaladı ve hata verdi).
- Düzeltme: boş dizinler silindi, `scripts/dev-secrets.sh` eksik dosyaları üretti, PostgreSQL yeniden açıldı (Docker Desktop, dizini tutan bütün container'lar durana kadar eski görünümü verdi), iki rolün parolası `/run/secrets`'tan `\set` ile atandı.
- Bu branch'te `require_secret` (`10-roles.sh`, `40-modules.sh`, `30-keycloak.sh`, `deploy/keycloak/start.sh`) artık `-f` de ister: dizin bir secret sayılmaz.
