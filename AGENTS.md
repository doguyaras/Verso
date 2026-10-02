# AGENTS.md — verso için AI Ajan Kuralları

Bu dosya tüm AI kodlama ajanları (Claude Code, Codex, Copilot, Cursor vb.) için **kanonik giriş noktasıdır**. `CLAUDE.md` ve `.github/copilot-instructions.md` yalnız buraya yönlendirir. Kurallar burada **tekrar edilmez**; ilgili dosyaya link verilir.

## 1. Okuma Sırası

Önce bu dosya. Sonra göreve göre:

| Görev | Aç |
|---|---|
| Her görev | `docs/ai/repo-context.md` (modül haritası, portlar, sıcak yol tablosu) |
| Güvenlik, kimlik, secret, log, dosya, privacy | `docs/ai/security-rules.md` |
| Büyük repo taraması gerekiyorsa | `docs/ai/context-boundaries.md` (neyi açma, neyi şartlı aç) |
| Değişiklik bitince | `docs/ai/review-checklist.md` (hangi skill'ler çalışacak) |
| Servisler arası yazma, outbox, event, saga | `docs/ai/operation-consistency.md` |
| Model çağrısı, prompt, retrieval, embedding, chunking, local/cloud modu, belge/soru/cevap içeriğinin log veya ağa ulaşması | `docs/ai/llm-rules.md` (Verso eki; ADR-0007 #13) |
| Mimari karar (yeni servis, yeni altyapı bileşeni, veri ayrımı) | `docs/adr/` (mevcut ADR'leri oku, yenisini şablondan aç) |
| Mimari referans (nasıl inşa edilir) | `docs/architecture-reference.md` — yalnız ilgili bölüm. Dosya repoda yoksa **aramaya tur harcama**: ilgili maddeyi "net kanıt bulunamadı (referans yok)" diye işaretle ve `docs/ai/*` ile devam et |

## 2. Temel Kurallar

- Gereksiz geniş tarama yapma; `docs/ai/context-boundaries.md`'deki sınırlara uy.
- Doğrulanmamış bilgiyi kesin yazma. Dosya yolu, key, env, path, tablo, schema, secret, paket veya servis adı **uydurma**. Doğrulayamıyorsan "**net kanıt bulunamadı**" yaz ve sor.
- Mevcut bir pattern varsa onu kullan; yoksa yenisini icat etmeden önce sor.
- Secret, token, parola, private key değerlerini hiçbir yeni içeriğe (kod, doküman, test, log, PR açıklaması) taşıma. Yalnız isimleri yaz.
- Aynı kuralı iki yerde yazma; belgeye link ver.
- Kurallar üç sınıftadır (referans Bölüm 1.4): **zorunlu güvence** (ihlali `BLOCK`; değişmez), **varsayılan tercih** (sapma gerekçeli ADR ister), **başlangıç ayarı** (sayılar; ölçümle değişir). Bir sayıyı "kural" diye savunma, bir güvenceyi "tercih" diye gevşetme.
- Her değişiklikte ownership kontrolü: kimlik her zaman doğrulanmış bağlamdan (`@CurrentAccount`), path/body'den değil. Servis kimliği (service JWT) kullanıcı adına yetki **değildir**; internal uçlar `docs/ai/repo-context.md` Bölüm 3.1 delegasyon matrisine göre çağıran × işlem × kullanıcı bağlamı × kaynak yetkisini birlikte kontrol eder.
- Ana README kökteki `README.md`'dir; servis kimlik tablosu ve hata kodu blokları oradadır.

## 3. Kanıt ve Varsayım Disiplini

- Varsayımla kod, config, migration, endpoint veya güvenlik kuralı üretme.
- Kritik bir belirsizlik varsa (güvenlik, veri kaybı, ödeme, yasal) kod yazmadan önce sor.
- Bir iddiayı yazarken dayandığı dosyayı/satırı belirt.

## 4. Modül Sınırı ve DB Erişimi

- Hiçbir modül başka modülün tablosuna, schema'sına, repository'sine, entity'sine veya migration'ına erişmez. **Yönetim (backoffice) servisi de istisna değildir.**
- Cross-schema FK ve join yasaktır. Başka servisin kimliği düz UUID kolon olarak tutulur.
- Başka servisin verisi gerekiyorsa: public API/contract, event ile replike edilen read-model (`readmodel/` paketi) veya JWT claim. Böyle bir ihtiyaç doğarsa **kod yazmadan önce** `verso-architecture-boundary-review` skill'ini çalıştır ve sor.
- DB rolleri şema bazlıdır; kod bu sınırı aşmaya çalışırsa çalışma zamanında hata alır. Bu hatayı "GRANT ekleyerek" çözme.

## 5. Sıcak Yol Kuralı

- **Zorunlu güvence:** kullanıcıya latency olarak yansıyan her akışın kritik akış kaydı (`docs/ai/repo-context.md` Bölüm 3: gecikme bütçesi, uzak bağımlılıklar ve gerekçeleri, kabul edilen veri eskiliği, bağımlılık düşünce davranış) yazılıdır ve güncel tutulur.
- **Varsayılan tercih:** en fazla bir uzak senkron çağrı, o da yazma/rezervasyon türünden; okuma amaçlı senkron çağrı yerine read-model veya JWT claim. Varsayılanı aşan her ek bağımlılık ADR + `verso-resilience-review` ister; "ikinci çağrı" yasak değildir, gerekçesiz ve bütçesiz olanı yasaktır.
- Yeni bir uzak çağrı ekliyorsan kaydı güncelle ve `verso-resilience-review` skill'ini çalıştır.
- Her HTTP client çağrısı timeout + circuit breaker + bulkhead altındadır; bunlar olmadan client ekleme.

## 6. Migration Değişmezliği

- Base branch'e (`develop`/`release`/`main`) girmiş `V*.sql` dosyasına dokunulmaz (değiştirme, silme, yeniden adlandırma yok). Düzeltme yeni `V<sonraki>` ile yapılır.
- Migration'a her dokunuştan sonra: `node scripts/flyway-immutability.js check --base origin/<hedef-branch>`.
- Hook bunu yazma anında engeller; engellenirse kuralı aşmaya çalışma, yeni dosya aç.

## 7. API / Core Contract

- `<domain>-api` yalnız DTO, enum, sabit ve event payload'ı taşır; hiçbir `*-core`'a bağımlı olamaz. (Verso, şekil A: modüller arası in-process sözleşme arayüzü de `*-api`'de durabilir; ADR-0007 #24.)
- `<domain>-core` başka bir `*-core`'a bağımlı olamaz (enforcer bunu kırar). Uygulama modülleri yalnız `<domain>-api` ve `<domain>-core` adını taşır; enforcer yasağı bu adlandırmaya dayanır (ADR-0007 #31).
- Entity dışarı açılmaz; DTO kopyalanmaz; hedefin api modülü import edilir.
- Contract değişikliği geriye uyumluluk açısından mobil, panel ve internal çağıranlar için değerlendirilir; `verso-api-contract-review` çalıştırılır.

## 8. Event ve Outbox

- Başka servisin verisini değiştirmek için komut gönderilmez; kendi domain event'in yayınlanır (`outbox_event`, `kind=EVENT`).
- Yayın her zaman outbox'tan; doğrudan `convertAndSend` yasak.
- Tüketici: inbox satırı ve iş değişikliği **aynı transaction'da**; ack commit'ten sonra. Read-model'de kaynak başına `source_revision`; olay sözleşmesi (tam durum / değişiklik) yazılı.
- Yeni event/komut için `verso-event-design-review` çalıştırılır; rollout sözleşmesi (referans Bölüm 18.4: değişiklik türüne göre sıra + uyumluluk matrisi) PR'a yazılır. "Tüketici önce" tek başına kural değildir.

## 9. Environment ve Config Etkisi

- Yeni property, URL, port, audience, issuer, rate limit scope'u, bağlantı ayarı, internal uç veya feature flag eklendiğinde şu yüzeyler **birlikte** güncellenir: deploy `env_file`/secrets, `config/<svc>.yml`, `application-local.yml`. Dockerfile yalnız build/runtime davranışı değişiyorsa.
- Dockerfile'a config için `ENV` eklenmez. Secret için `${ENV:literal}` fallback yazılmaz.
- Admin'in değiştirebileceği iş kuralı değeri config'e değil parametre kataloğuna gider.
- `verso-environment-impact-review` çalıştırılır.

## 10. Multi-Instance

- Her servisin birden fazla instance ile çalıştığı varsayılır. `@Scheduled`/poller/worker `FOR UPDATE SKIP LOCKED` + lease + `claim_token` ile claim eder.
- Check-then-act güvenli değildir; unique/partial unique index, koşullu update veya lock kullan.
- Instance'lar arası state JVM'de tutulmaz. Bilinçli single-instance davranış yorumla gerekçelendirilir.

## 11. Loglama

- Throw öncesi tek structured log satırı: `"<Olay> rejected: code=X reason=Y"`. Kullanıcı kaynaklı reddetme WARN, altyapı/beklenmeyen ERROR.
- Ham exception mesajı, body, token, OTP, telefon, e-posta, plaka, konum, IP, şifreli içerik loglanmaz. `docs/ai/security-rules.md`.

## 12. Kod Açıklama Disiplini

- Yorumlar kısa, ekibin dilinde, "**neden** var" anlatır. Bu repoda yeni yazılan kod ve yorumlar **İngilizce**dir; blueprint'ten birebir alınan script/test/hook yorumları Türkçe kalır; `docs/` Türkçedir (ADR-0007 #2). Changelog tarzı yorum ("şu güncellendi") yasak.
- Business rule, güvenlik, modül sınırı, fallback, rate limit, ownership, migration etkisi ve non-obvious kararlar için yorum şart. Kendini açıklayan koda yorum yazılmaz.

## 13. Çıktı Disiplini

- Kısa, kanıta dayalı, görev odaklı. Güvenlik ve privacy riski açıkça yazılır. Belirsiz alan gizlenmez.
- Push öncesi `docs/ai/review-checklist.md`'deki skill'ler çalıştırılır; PR şablonundaki "Review skill'leri" bölümü doldurulur.

## 14. Bu Belgede Özellikle Taşınmayanlar

Secret değerleri, default admin parolası, üretim host adları, CI token'ları. Bunlar deploy secret'larında yaşar; dokümanlarda yalnız **isimleri** geçer.

## 15. Net Kanıt Bulunamayan Alanlar

Her `docs/ai/*` dosyasının sonunda bu başlık bulunur; ajan doğrulayamadığı iddiaları buraya ekler, uydurmaz.
