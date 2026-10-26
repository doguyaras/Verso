# Hafif tipli sistem modeli: doğrulama (ADR-0048 faz 4, ADR-0054)

- **Tarih:** 2026-10-26
- **Branch:** `feature/doguyaras-ogrenen-katman-4` (taban `develop` `c5e335f`)
- **Ortam:** Windows 11, JDK 25.0.4.1, Maven `-T 1`. **Docker çalıştırılmadı** (kullanıcı durdurdu): Testcontainers'lı entegrasyon testleri yerelde koşmadı; kapı GitHub CI'dır.

## Yerelde koşanlar

| Komut | Sonuç |
|---|---|
| `./mvnw -B -ntp -T 1 -DskipTests install` | başarılı (test derlemesi dahil) |
| `./mvnw -B -ntp -T 1 test -pl platform/platform-core,services/document/document-core,services/qa/qa-core,services/mcp/mcp-core` | hepsi yeşil; document-core 166 test. Yeni: `SystemModelExtractorTest` (İ/ı katlamalı kodlar, liste/tablo/"kodu … gösterir" tanımları, üç biçimde kısaltma ve baş harf kuralı, sonraki kullanımların bahsedilmesi, başlık altı numaralı adımlar ve parça sınırını aşan liste, menü yolu ve "X ekranı", iyelikli sistem adı ve cümle başı sözcüğü, kişi adlı sahip ifadeleri, belirlenimcilik ve `toString`'de metin yok), `RoleNamesTest` (12 ifade: unvan kalır, ad/soyad/unvan eki gider, unvansız ifade atılır), `SystemModelPlanTest` (tek parça özeti değişince, sıra ya da sayı değişince, çıkarıcı sürümü artınca yeniden çıkarım; değişmeyen parçanın ilişkileri aynı), `SystemModelServiceImplTest` (her okuma çağıranın ilkeleriyle; boş/uzun sorgu okumadan reddedilir; okunamayan varlık 404; katlamayla aynı tanımlar çelişki değil; önek katlanır), `SystemModelEnrichmentTest` (kapalıyken hiç okuma yok; açıkken bulunanlar hariç, sınırlı, okuyucunun ilkeleriyle), `SystemModelExtractorBenchmarkTest`; qa-core: `SystemModelSuggestionsTest` (kapalıyken ve bulut modunda model ve belge çağrısı yok; kapalı şema dışı satırlar, ters tipler, yanlış parça ve sohbet reddedilir), `KnowledgeHealthReportTest` (üç yeni bulgu); platform-core `TurkishTextTest` |
| `./mvnw -B -ntp -T 1 test -pl verso-app -Dtest=ArchitectureRulesTest,ConfigDriftTest,ErrorCodeUniquenessTest,ModuleStructureTest,ComposeConfigTest,TransactionBoundaryRulesTest` | 36 test, 0 hata (`DeployConfigTest` Docker ister, CI) |
| `MigrationConventionsTest` (document-core ve qa-core içinde) | yeşil |
| `node scripts/flyway-immutability.js check --base origin/develop` | `flyway-immutability: OK (base=origin/develop, merge-base=c5e335f33e0e)` |
| `node scripts/migration-contract.js check --base origin/develop` | `migration-contract: OK (2 yeni migration, base=origin/develop)` (document V6, qa V7; yalnız yeni tablolar) |
| `GITLEAKS=/c/Users/doguy/.local/bin/gitleaks.exe node --test scripts/*.test.js scripts/panel.test.mjs` | 273 test, 272 geçti, 1 atlandı, 0 hata (panel: `systemmap` rol matrisinde yalnız yönetici, varlık/ilişki türleri sunucu enum'larıyla sözleşme (PERSON yok), adım sırası, kaynak etiketi, kodlanmış URL'ler, yeni hata kodu mesajları) |

## Ölçüm: çıkarım maliyeti

`SystemModelExtractorBenchmarkTest`: kodlar, kısaltmalar, iki numaralı süreç, menü yolları ve sahip ifadeleriyle yoğun, ~2 000 karakterlik sentetik sayfa; 100 sayfalık belge, 5 ısınma + 10 tur.

| Ölçü | Değer |
|---|---|
| Sayfa başına süre | **~2,8-3,0 ms** (iki koşu: 2,820 ve 2,969 ms) |
| Belge başına varlık (bu sayfa ×100) | 31 (aynı anahtarlar belgede bir kez) |
| Karşılaştırma | aynı sayfanın gömülmesi CPU dizüstünde ~1 sn (`docs/capacity.md`): çıkarım alım süresinin ~%0,3'ü |

Güncelleme maliyeti: yalnız değişen belge yeniden çıkarılır (`SystemModelPlan`); global yeniden kurulum yoktur. Bu, Asya uygulayıcılarının GraphRAG'i reddetme nedenine ("belge değişince graf yeniden kurulmalı") doğrudan cevaptır.

## CI'da koşacak entegrasyon testleri (gerçek PostgreSQL + outbox + MCP)

- `SystemModelApiTest`:
  - TXT alımı modeli doldurur (`CODE:WMS-204`, `TERM:FEFO`, `SYSTEM:WMS`, iki süreç, roller, menü yolu, adım); hiçbir etikette kişi adı yok; her bahsedilmenin özeti parçanın gerçek SHA-256'sı; durum satırı sürüm 1; en az iki `OWNED_BY`;
  - belge silinince modeli ve durumu gider; aynı belge yeniden alınınca eski varlıklar silinip yenileri yeni parçalara bağlanır; geri doldurma eksik olmayan belgeyi almaz, eksik olanı tek başına alır;
  - iki geri doldurma aynı anda: her belge bir kez (`SKIP LOCKED`);
  - arama erişim matrisi: `/depo` kullanıcısı yalnız kendi açılımını, `/ik` kullanıcısı yalnız kendisininkini, yönetici ikisini, grupsuz kullanıcı `[]`; IK kullanıcısı Depo'nun kodunu göremez; boş ve 201 karakterlik sorgu 400 `10051`; token'sız 401; `no-store`;
  - "Sistem haritası" uçları kullanıcıya 403, yöneticiye 200; sistem ayrıntısında kod ve ekran, kodun kaynağı, sürecin sorumlusu; hiçbir alanı okumayan yönetici 404 `10050` ve boş liste;
  - bilgi sağlığı raporunda `UNDEFINED_CODE` (WMS-310), `TERM_CONFLICT` (FEFO), `PROCESS_WITHOUT_OWNER` (sayım süreci) ve üç bulgu; okumayan yöneticiye boş;
  - öneri geçişi varsayılan kapalı (`settings.enabled=false`, çalıştırma 409 `11080`, model çağrısı yok); DRAFT öneri listelenir, okumayan yönetici göremez (404 `11081`), kabul 200, ikinci inceleme 409 `11082`, denetimde `SYSTEM_SUGGESTION_ACCEPTED` ve anahtar metni yok; belge silinince öneri outbox ile silinir;
  - log satırlarında belge metni, tanım, kişi adı, süreç anahtarı yok.
- `McpServerTest`: araç listesi beş salt okunur araç (`verso_lookup` dahil); `verso_lookup` yalnız çağıranın tanımını verir, kaynak `verso_fetch` ile okunur, grupsuz kullanıcıya boş, boş/eksik sorgu araç hatası; denetimde `MCP_LOOKUP` `RESULTS` ve kaynak belge kimliği, sorgu metni yok; loglarda sorgu ve tanım yok.

## Ölçülmeyenler

- Arama zenginleştirmenin cevap kalitesine etkisi (recall@5, doğruluk) müşteri setinde ölçülmedi; bu yüzden varsayılan kapalı.
- Çıkarıcıların gerçek müşteri belgelerinde kesinlik/duyarlılığı ölçülmedi (sentetik örneklerle birim testleri var).
- Yerel model öneri geçişinin kabul oranı ölçülmedi (yalnız güvenlik özellikleri test edildi).
