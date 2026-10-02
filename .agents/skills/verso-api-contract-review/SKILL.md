---
name: verso-api-contract-review
description: Use this skill when reviewing API contracts — DTO placement, api/core dependency direction, backward compatibility for mobile/panel/internal callers, HTTP parameter binding, OpenAPI diff and versioning.
---

Contract değişikliğini `docs/architecture-reference.md` Bölüm 3.3, 5.3–5.4, 6 (özellikle 6.2 zarf, 6.4 idempotency, 6.5 kimlik, 6.7 cache, 6.8 internal client), 18.4 (rollout) ve 20'ye göre incele; internal çağıran için `docs/ai/repo-context.md` Bölüm 3.1 delegasyon matrisine bak. OpenAPI diff çıktısı (CI artefaktı veya PR yorumu) varsa önce onu oku; yoksa `git diff <base>...<head> -- '*-api/**' '**/controller/**'` ile DTO ve controller imzalarını karşılaştır ve raporda "OpenAPI diff yok, elle çıkarıldı" yaz. Sorun yoksa: **"Bu kapsamda contract bulgusu yok."**

Girdi: diff = `git diff <base>...HEAD` + `git diff --stat` (base = hedef branch; PR diff'i verilmişse o); izlenmeyen (untracked) dosyalar da kapsamdadır. Severity: `BLOCKER` = çalışan istemciyi mevcut sürümde kıran ve rollout planı olmayan değişiklik → `BLOCK`; `HIGH` = versiyonsuz breaking veya hesap kimliğinin istemciden alınması → `REQUEST CHANGES`; `MEDIUM`/`LOW` engellemez (yalnız bunlar varsa `APPROVE WITH NON-BLOCKING COMMENTS`).

Kontrol et:

## Yerleşim ve bağımlılık yönü
- Servisler arası DTO/enum/event payload'ı hedefin `<domain>-api` modülünde (`com.verso.<domain>.api.*`); core'da kopyası yok.
- `*-api` hiçbir `*-core`'a bağımlı değil; `*-core` başka `*-core`'a bağımlı değil (enforcer).
- Entity dışarı açılmıyor; `toResponse` ile DTO.
- Servisler arası okunan DTO Lombok sınıfıysa `@NoArgsConstructor` (Jackson); Java record ise gerekmez.
- Event payload'ı `api/event` altında bir record (string literal JSON değil); yayın platform outbox'ı üzerinden ve `type/source/id/time` attribute'ları ile.

## Geriye uyumluluk
- Alan silme/yeniden adlandırma/tip değişimi/zorunlu alan ekleme = **breaking**. Etkilenen çağıranlar: mobil (mağazadaki eski sürüm aylarca yaşar), panel, internal client'lar, event tüketicileri.
- Breaking ise: yeni versiyon (`/v2` veya yeni `type`), eski versiyon sunset tarihiyle yaşıyor, ADR var.
- Enum'a yeni değer: tüketiciler bilinmeyen değeri tolere ediyor (`from()` fallback); **tüketici önce deploy**.
- Yanıt zarfı: projenin 6.2 kararına bak (`ApiResponse<T>{ok,data,error}` veya ham); hata her zaman zarflı; 200 ile `success=false` yok. Mevcut doğru örnekten sapan ucu işaretle, doğru ucu karşılaştırma için kusurlu sayma.
- Hata kodları servisin bloğunda ve global tekil; istemci kod→mesaj tablosu güncellendi (contract testi).
- Sayfalama: boyut sınırı, sıralama allowlist, keyset alanları tutarlı.

## HTTP parameter binding
- `@PathVariable("ad")`, `@RequestParam("ad")`, `@RequestHeader("Ad")` **açık isimli**; parameter-name inference'a güvenilmiyor.
- `required`/`defaultValue` bilinçli; opsiyonel parametre `Optional` veya default.
- Binding gerçek MVC üzerinden test edilmiş (`MockMvc standaloneSetup`; geçersiz tip 400 ve servise ulaşmıyor).
- Idempotent uçlar `X-Idempotency-Key` alıyor; UUID tipiyle bağlanmış.
- Kimlik `@CurrentAccount`; path/query/body'de hesap kimliği yok; `@CurrentAccount` için bir `HandlerMethodArgumentResolver` `WebMvcConfigurer.addArgumentResolvers` ile kayıtlı mı (yoksa parametre query'den bağlanır → IDOR/500; dosya:satır ile kanıtla)?

## Path ve isim
- Kaynak çoğul kebab-case; fiiller yalnız durum geçişi alt kaynaklarında (`/{id}/cancel`); güncelleme `PUT/PATCH`; versiyon prefix'i var.
- Internal uç `/internal/<kaynak-çoğul>/...`; public/internal karışmıyor.

## OpenAPI ve istemci
- OpenAPI diff: breaking değişiklik işaretli mi; istemci client generate ediliyor mu.
- Breaking değişiklik veya yeni public uç varsa `docs/<client>-<feature>-integration-vN.md` var mı? Yoksa ayrı bulgu yaz (dosya: eksik doküman yolu) ve `verso-client-integration-doc`'a yönlendir.
- PR gövdesindeki "breaking" ve "istemciyi etkiliyor" beyanlarını diff kanıtıyla karşılaştır; yanlışsa bulgu yaz.
- Makine kontrollerini koş (proje kökünden): `mvn -B -ntp -pl <svc>-core -am test -Dtest=ArchitectureRulesTest,ErrorCodeUniquenessTest -Dsurefire.failIfNoSpecifiedTests=false`; FAIL olan kural bulgunun kanıtıdır. Not: `-am` ile upstream modülde `failIfNoTests=true` sabitse filtreli koşu düşer; `-fn` ekle veya filtresiz `test` koş.
- Örnek değerler ve açıklamalar üretilen OpenAPI'de anlamlı (springdoc anotasyonları).

Çıktı:
1. **Contract risk seviyesi:** `BLOCKER` / `HIGH` / `MEDIUM` / `LOW` / `OK`.
2. **Breaking-change riski:** evet/hayır + gerekçe; etkilenen çağıranlar (mobil / panel / internal / event tüketicileri).
3. **Yerleşim/bağımlılık bulguları:** `dosya:satır · kanıt · düzeltme`.
4. **Binding bulguları:** `dosya:satır · kanıt · düzeltme · eksik test`.
4b. **Uyumluluk/zarf/hata kodu bulguları** ve **Event contract bulguları:** aynı biçim (checklist'in geriye uyumluluk, zarf, hata kodu, event payload, idempotency maddeleri).
5. **Daha güvenli yapı önerisi** (breaking ise versiyonlama/çift yayın planı).
6. **Nihai karar:** `APPROVE` / `APPROVE WITH NON-BLOCKING COMMENTS` / `REQUEST CHANGES` / `BLOCK` (kriterler yukarıdaki severity satırında).
7. **Kapsam dışı gözlemler** (→ ilgili skill): tek satır. Uzun kanıtlarda bulgular çok satırlı madde işaretli yazılabilir.
