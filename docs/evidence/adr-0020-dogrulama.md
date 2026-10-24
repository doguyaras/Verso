# ADR-0020 doğrulaması: Alıntı penceresi

Tarih 2026-10-24. Dal `feature/doguyaras-alinti-penceresi`, `origin/develop` (533a80d) üstünde. JDK 25. Çalışan compose yığınına dokunulmadı; değişiklik canlıda denenmedi (aşağıda "Bilinen sınırlar").

## Testler

- `./mvnw -B -ntp -pl verso-app -am test -Dtest='*Citation*,*Excerpt*,*Question*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false`: geçti.
  - `CitationExtractorTest` 11
  - `ExcerptSelectorTest` 22
  - `QuestionApiTest` 21
- `./mvnw -B -ntp -pl services/qa/qa-api,services/qa/qa-core -am test`: geçti; qa-core 64 test, qa-api 2 test.
- `./mvnw -B -ntp verify` (rebase öncesi, develop 5a5ece2 üstünde): bütün modüller geçti; verso-app 192 test.
- Yeni ya da değişen testler:
  - **`SentenceSplitterTest`** (13):
    - nokta + büyük harf;
    - küçük harfle devam;
    - `25.000`, `3.2`, `1.250,50`;
    - sayıyla biten cümle;
    - sıra sayısı (`2. Dünya`);
    - kısaltmalar (`Dr.`, `Bkz.`, `Örn.`, `No.`);
    - `A.Ş.`, `T.C.` ve baş harf;
    - Roma rakamı;
    - `?`, `!`, `…`, `...` ve kapanan tırnak;
    - terminatörsüz metin; boşluklar;
    - cevapta satır sonu;
    - işaretten sonra gelen nokta.
  - **`ExcerptSelectorTest`** (22):
    - ortadaki dayanak cümlesi;
    - iki komşu cümle;
    - yeni terim katmayan ikinci cümlenin gösterilmemesi;
    - Türkçe büyük harf (`İ`/`ı`) ve Türkçe harfsiz yazım;
    - `fold` ve 5 harfli kök;
    - sayıların ayraçsız eşleşmesi ve ağırlığı;
    - örtüşme yoksa pasajın başı;
    - eşitlikte önceki cümle;
    - uzun cümlenin ortasındaki dayanakta baştaki ve sondaki `…`;
    - uzun cümlenin başındaki dayanak;
    - prompt sınırı;
    - kısa pasajın tamamı;
    - eski kesme kuralları (boş, boşluk, sınırda, bir fazla, kelime ortası, uzun kelime, Türkçe harfler, vekil çifti).
  - **`CitationExtractorTest`** (11):
    - iki atıflı cevap;
    - cümleyi açan işaret;
    - madde listesi;
    - aynı pasaja iki cümleden atıf;
    - örtüşme yoksa pasajın başı;
    - prompt sınırının ötesindeki dayanak;
    - alıntının model metni içermemesi.
  - **`QuestionApiTest`** (uçtan uca, gerçek PDF ve PGVector): cevabın dayandığı orta cümle `excerpt` olarak döner. Örtüşmeyen uzun pasajda alıntı hâlâ pasajın başıdır.

**Mutasyonlar** (hepsi geri alındı; `-pl services/qa/qa-core -am test -Dtest=ExcerptSelectorTest,SentenceSplitterTest,CitationExtractorTest`):

| # | Mutasyon | Kıran test |
|---|---|---|
| M1 | Sayı ağırlığı 3 → 1 | `ExcerptSelectorTest.select_whenOneSentenceSharesTheNumber_prefersItOverMoreSharedWords` |
| M2 | `ı` → `i` katlaması kaldırıldı | `ExcerptSelectorTest.fold_whenTurkishCasingDiffers_givesOneForm` |
| M3 | Sıra sayısından sonraki nokta cümleyi bitirir | `SentenceSplitterTest.split_whenAPeriodClosesAnOrdinal_doesNotSplit` |
| M4 | Cümleyi açan işaret kendi cümlesine bağlanır | `CitationExtractorTest.extract_whenAMarkerOpensTheNextSentence_usesTheSentenceBeforeIt` |
| M5 | Prompt sınırı yok sayılır | `CitationExtractorTest.extract_whenTheSupportingSentenceIsBeyondThePromptLimit_…`, `ExcerptSelectorTest.select_whenThePromptCarriedOnlyPartOfThePassage_…` |

## Review skill'leri

| Skill | Karar | Not |
|---|---|---|
| `verso-llm-review` | APPROVE WITH NON-BLOCKING COMMENTS | Veri akışı aşağıda. Yorum (LOW): eval setinde alıntı isabeti ölçümü yok (`needs verification`). Eş anlamlı ifadeler ADR'de kabul edilmiş risk |
| `verso-spring-code-review` | APPROVE | Bu kapsamda bulgu yok |
| `verso-test-writer` | PASS | Kalan boşluklar aşağıda |
| `verso-api-contract-review` | APPROVE | Alan, tip ve 280 sınırı aynı; değişen yalnız `Citation` Javadoc'u ve entegrasyon dokümanı. Baştaki `…` panelde düz metin |
| `verso-security-review` | APPROVE | Bu kapsamda bulgu yok: yeni log satırı yok, alıntı panelde metin düğümü olarak gösterilir |
| `verso-resilience-review` | APPROVE | Yeni uzak çağrı yok; sıcak yol kaydı (`repo-context.md` Bölüm 3) değişmedi |

**`verso-llm-review` veri akışı:**

| Yol | İçerik | Nereye | Koruyan kural ve test |
|---|---|---|---|
| Pasaj → `excerpt` | Getirilen pasajın (aynı hesabın, sahiplik filtresinden geçmiş) bir parçası | İstemci yanıtı (önceden de öyleydi) | 3.3: `CitationExtractorTest.extract_whenTheAnswerWouldMatchTextOutsideThePassage_excerptStaysPassageText`, `ExcerptSelectorTest`'te `assertIsPassageText` |
| Model cevabı → terimler | Cevap cümlelerinin terimleri | Yalnız bellek: pencerenin yerini seçer, çıktıya girmez | 3.3, aynı testler |
| Log / metrik / span | Yok | – | 2.1: üç sınıfta logger yok |
| Model veya ağ çağrısı | Yok | – | 5.2, ADR-0008 |

**`verso-spring-code-review`:**
- `CitationExtractor` constructor injection kullanıyor (`QaProperties`).
- Üç sınıfta `@Autowired`, logger ve transaction yok.
- `SentenceSplitter` ve `ExcerptSelector` durumsuz yardımcılar (`final`, statik); çok instance'ta güvenli.
- `QaArchitectureTest` geçti: servis katmanı yalnız `config`'e iniyor.

**`verso-test-writer` kalan boşluklar:**
- Gerçek model cevaplarıyla alıntı isabeti ölçülmedi: `scripts/eval.mjs` atıf numarasını ve sayfayı ölçer, alıntıyı ölçmez.
- Kısaltma listesinin kapsamı sentetik örneklerle sınırlı.

## Bilinen sınırlar

ADR-0020'nin "Olumsuz / kabul edilen risk" bölümünde ayrıntılı:
- Eş anlamlı ya da başka sözcükle anlatım yakalanmaz.
- 5 harfli kök kaba bir yaklaşımdır.
- Kısaltma listesi eksik kalabilir.
- `3.2` ile `32` aynı sayıdır.
- Canlı yığında denenmedi (görev çalışan yığına dokunmamayı istiyordu). Uçtan uca davranış `QuestionApiTest` ile gerçek PDF, PGVector ve test chat modeliyle doğrulandı.
