# Değerlendirme seti (faz 8)

Verso'nun cevap kalitesi bu klasördeki sabit bir Türkçe setle ölçülür (llm-rules 7.1). Belgeler `samples/` altındaki sentetik PDF'lerdir: kurgusal bir şirketin altı yönetmeliği. Gerçek kişi ve kurum verisi yoktur.

- **[`eval-set.json`](eval-set.json):** 33 soru. Bunların 25'i cevaplanabilir (beklenen belge ve sayfa, cevapta geçmesi beklenen bilgi), 8'i cevaplanamaz (belgelerde yok; sistem "bulunamadı" demeli).
- **[`results/`](results/):** ölçüm sonuçları (JSON). Her koşu üzerine yazar; tarihçe git'tedir.

## İki ölçüm

| Ölçüm | Ne ölçer | Nasıl koşulur |
|---|---|---|
| Retrieval (`RetrievalEvalTest`, tag `eval`) | Doğru sayfa ilk 5 pasajda mı (recall@1, @5, MRR); cevaplanabilir ve cevaplanamaz soruların en iyi benzerliği eşiğin hangi tarafında | Gerçek bge-m3 ile, Testcontainers PostgreSQL üzerinde. Yığın gerekmez, yalnız Ollama gerekir |
| Uçtan uca (`scripts/eval.mjs`) | Gerçek chat modeliyle: kaynak bulma oranı, doğru sayfaya atıf, atıf kesinliği, cevapta doğru bilgi, cevaplanamaz sorularda "bulunamadı" doğruluğu, gecikme | Çalışan yığın üzerinden API ile. CPU'da yaklaşık 20–30 dk |

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

## Eşikler ve karar

- **Benzerlik eşiği:** 0,45'ten 0,50'ye çekildi. Ölçüm (`results/retrieval.json`): cevaplanabilir 25 sorunun en düşük en-iyi benzerliği 0,522. Cevaplanamaz 8 sorunun 4'ü 0,50'nin altında kaldı (0,45'te yalnız 1'i). Eşiğin üstünde kalan cevaplanamaz sorularda "bulunamadı" kararını chat modeli verir; uçtan uca ölçüm bunu ayrıca sayar.
- **Regresyon koruması:** `RetrievalEvalTest`, recall@5 0,80'in altına düşerse kırmızı olur. Uçtan uca sonuçlar karar için okunur, build'i durdurmaz: model çıktısı deterministik değildir.
- **Set küçük:** 33 soru, 6 belge. Sonuçlar yön gösterir, istatistiksel güvence vermez. Gerçek bir müşteri belgesi seti eklendiğinde (anonimleştirilmiş, müşteri onayıyla) eşik yeniden ölçülür.
