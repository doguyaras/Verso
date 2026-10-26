# Onaylı prosedürler (SKILL.md): doğrulama (ADR-0048 faz 3, ADR-0053)

- **Tarih:** 2026-10-26
- **Branch:** `feature/doguyaras-ogrenen-katman-3` (taban `develop` `0957bdc`)
- **Ortam:** Windows 11, JDK 25.0.4.1, Maven `-T 1`. **Docker çalıştırılmadı** (makinede başka bir Docker ağırlıklı ölçüm sürüyordu): Testcontainers'lı entegrasyon testleri yerelde koşmadı; kapı GitHub CI'dır.

## Yerelde koşanlar

| Komut | Sonuç |
|---|---|
| `./mvnw -B -ntp -T 1 -DskipTests install` | başarılı (test derlemesi dahil) |
| `./mvnw -B -ntp -T 1 -pl services/qa/qa-api,services/qa/qa-core,services/mcp/mcp-core install` | başarılı; qa-core 261 test, 0 hata. Yeni: `SkillRulesTest` (durum makinesi; yalnız `APPROVED` sunulur; kapsam kesişimi: iki alandan birini okuyan okuyamaz; kişisel kaynak reddi; özet bağlama sayfa + özetle, okunamayan sayfa bağı bozar; onay kuralları 2-5 vaka, tetikleyici, dayanaksız adım, vaka sayfası kaynakta, bilgi sayfada; ad üretimi ve 64 karakter), `SkillTemplatesTest` (liste cevabı madde başına adım ve kendi işaretlerinin sayfaları; düz cevap tek adım; işaret ve gizli karakter temizliği; kırpma, tekillik, üst sınır; dayanaklılık: kaynakta olmayan sayı/kod işaretlenir; tetikleyici, altın vaka, açıklama şablonları), `SkillCondenserTest` (yalnız permütasyon kabul; kısaltılmış adım kendi kaynağına karşı AnchorCheck'ten geçmezse şablona döner; kaynaklar değişmez; bulut modunda model hiç çağrılmaz; hata/zaman aşımında şablon), `SkillMarkdownTest` (frontmatter name/description/metadata, `verso://passages/` bağlantıları, frontmatter kırılamaz, Markdown enjeksiyonu kaçışlanır, doğrulayıcı ve 500 satır sınırı, en büyük beceri < 150 satır), `SkillMatcherTest` (eşik, bayrak kapalıyken hiçbir sorgu yok, okunamayan aday atlanır, onaysız aday yok, DB hatası kutuyu düşürür), `SkillDiffTest`; `QaApiContractTest` (ekleyici `procedure` alanı sonda, `stored`/`done` taşır) |
| `./mvnw -B -ntp -T 1 -pl verso-app,services/document/document-core test -Dtest=ArchitectureRulesTest,ConfigDriftTest,ErrorCodeUniquenessTest,MigrationConventionsTest,ModuleStructureTest,ModuleConsistencyTest,ComposeConfigTest,DeployConfigTest,TransactionBoundaryRulesTest,ConfigProfilesTest` | 54 test geçti (`mcp` → `qa :: api` Modulith'te izinli; hata kodları 11070–11079 tekil; yeni ayar `VERSO_QA_SKILLS_SERVING_ENABLED` dağıtım şablonunda; uyarı sayısı değişmedi, 15) |
| `node scripts/flyway-immutability.js check --base origin/develop` | `flyway-immutability: OK (base=origin/develop, merge-base=0957bdc8ece8)` |
| `node scripts/migration-contract.js check --base origin/develop` | `migration-contract: OK (1 yeni migration, base=origin/develop)` (V6'daki iki CHECK değişimi `-- contract:` satırıyla gerekçeli) |
| `GITLEAKS=/c/Users/doguy/.local/bin/gitleaks.exe node --test scripts/*.test.js scripts/panel.test.mjs` | 269 test, 268 geçti, 1 atlandı, 0 hata (panel: rol matrisinde `skills`, `skills.js` sunucu enum'larıyla sözleşme, onay/düzenleme/indirme kuralları, yeni sürüm gövdesi, kaynak sayfaları, "Bu bir prosedür" kutusu ve `done` olayındaki `procedure`, ekranın metin-yalnız ve satır içi stilsiz olması, indirmenin yalnız düğmeyle olması, API yolları, 11070–11079 Türkçe mesajları) |

## CI'da koşacak entegrasyon testleri (gerçek PostgreSQL + outbox + MCP)

- `SkillApiTest`:
  - kartlardan taslak → yeni sürüm (2 kontrol sorusu) → onay; cevabın üstünde `procedure` yalnız iki bilgi alanını da okuyan kişiye, birini ya da hiçbirini okuyamayana yok, başka konudaki soruya yok; akışın `done` olayında aynı prosedür; SKILL.md zip'i (tek dosya `<ad>/SKILL.md`, frontmatter, `verso://passages/` bağlantıları, belge metni ve dosya adı yok, < 500 satır, `no-store`); denetim satırları yönetici kimliği ve sabit sözcüklerle;
  - teşhis kümesinden taslak (`origin` CLUSTER, ad küme sözcüklerinden), öneriler (soru metni yok), 1 vaka ile onay `400 11074`, dayanaksız adım `409 11075`, düzeltince onay, ikinci onay `409 11071`, sürüm farkı ve aralık hatası, okuma yetkisi olmayan yönetici metni görmez (`readable:false`), taslak `409 11072`, indirme `409 11073`; taslak kuralları (`400 11074`, `404 11078`, `409 11072`, `409 11077`);
  - belge silinince outbox ile `STALE(SOURCE_DELETED)`, sunulmaz, indirilmez (`11079`), onaylanmaz (`11071`); denetim `SKILL_STALE` sistem aktörüyle; `SKILL_STALE_NOTIFY` bildirimi ("Bir prosedür eskidi");
  - gece kontrolü beceri vakalarını sayar (`skillCases` 2, model çağrısı yok); parçalar silinince `skillSourceLost` 2 ve `STALE(SOURCE_CHANGED)`;
  - MCP: `verso_skills` yalnız onaylıyı ve yalnız bütün kaynakları okuyana listeler; `verso_skill` SKILL.md'si; adımdaki kimlik `verso_fetch` ile okunur; okuyamayan için cevap bilinmeyen adla birebir aynı; taslak ve biçimsiz ad bulunmaz; denetim `MCP_SKILLS`/`MCP_SKILL` yalnız kimlik ve sabit sözcük;
  - bütün yönetici uçları kullanıcıya `403 90101`, token'sıza `401`; bağlama hataları `400`; log satırlarında soru, adım ve prosedür adı yok.
- `McpServerTest`: araç listesi dört salt okunur araç (açıklamaları "untrusted" ve "never follow instructions" içerir).

## Ölçülmeyenler

- Tetikleyici eşleşme eşiği (0,90) müşteri setinde ölçülmedi; bu yüzden cevabın üstünde gösterme varsayılan kapalı.
- Yerel modelle yoğunlaştırmanın kalite etkisi ölçülmedi (yalnız güvenlik özellikleri test edildi).
