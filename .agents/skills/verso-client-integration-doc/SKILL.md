---
name: verso-client-integration-doc
description: Use this skill after backend changes to decide whether a client (mobile/web) is affected and, if so, to produce the versioned integration document from code and OpenAPI — never from memory.
---

İstemci etkisini değerlendir ve gerekiyorsa `docs/<client>-<feature>-integration-vN.md` üret. Kapsam: base branch'e göre diff (`git diff <base>...HEAD` + `--stat`; izlenmeyen dosyalar dahil). Doküman dosyası PR'a eklenir (kod değişikliği değildir); üretim kodunu düzeltme. Gerçekler **koddan ve üretilen OpenAPI'den** toplanır; hatırlanan/varsayılan bilgi yazılmaz. Endpoint ve alan listesi OpenAPI'den gelir; doküman **davranış, ekran akışı, hata kodu → ekran** eşlemesine odaklanır.

## 1. Etki gate'i (biri evetse doküman gerekir)
- Yeni/değişen public endpoint, path, method, versiyon?
- Request/response alanı, enum değeri, validation kuralı, sayfalama biçimi değişti mi?
- Yeni hata kodu / değişen status?
- Davranış değişti mi (rate limit, idempotency, sıralama, gizlilik)?
- Realtime (WebSocket topic/zarf), push payload'ı, deep link?
- Auth akışı (token TTL, `sv`, refresh, 2FA, attestation)?
- Kullanıcıya görünür veri (yeni alan gösterimi, gizleme)?

Hiçbiri evet değilse çıktı: **"İstemci etkisi yok."** + gerekçe (hangi dosyalar incelendi).

`<client>`: etkilenen istemciyi `docs/ai/repo-context.md` Bölüm 2 (servis kimlik tablosu) ve çağıranlardan belirle; birden çok istemci → istemci başına ayrı doküman; belirsizse Açık sorulara yaz. Severity: `BLOCKER` = istemci akışını bozan/veri kaybı/güvenlik (dokümandaki `Kritik`) → `BLOCK`; `HIGH` = breaking (mevcut uçta alan silme/yeniden adlandırma/zorunlu yapma, enum daraltma, status değişimi) ve `Deprecation`/`Sunset` veya `/v(N+1)` yok → `REQUEST CHANGES`; `MEDIUM` diğer istemci etkileri; `LOW` bilgi notu.

## 2. Gerçekleri topla
- OpenAPI diff (CI artifact'ı) — breaking işaretleri. OpenAPI üretimi/CI artifact'ı yoksa: endpoint/alan listesini controller imzaları + `*-api` DTO'larından çıkar, dokümanın başına "OpenAPI doğrulanmadı" damgası koy; şablondaki `verso-api.yaml`/generated client alanlarına `yok / net kanıt bulunamadı` yaz; bunu bulgu değil Öz-kontrol notu olarak raporla.
- Controller imzaları, DTO'lar (`*-api`), `ErrorCode` enum'ları, validation anotasyonları.
- Gateway route/permitAll, rate-limit scope'ları (429 davranışı), idempotency header'ları (dosyalar `repo-context.md` Bölüm 4 tablosunda; gateway/scope yoksa `net kanıt bulunamadı`).
- Yanıt zarfı biçimi (zarflı/ham) — uç bazında; proje standardına uyan `{data}` kısmi zarfı zarflı sayılır. Hata zarfı `GlobalServiceExceptionHandler`'a bağlıdır; handler bulunamazsa `needs verification`.
- Kanıt komutları (proje kökünden): `mvn -B -ntp -pl <core> -am test -Dtest=ErrorCodeUniquenessTest,ConfigDriftTest`; `node scripts/flyway-immutability.js check --base <base>` (`OK`/`IHLAL`/`DOGRULANAMADI` satırını aynen yaz). `-am` ile upstream `failIfNoTests=true` filtreli koşuyu düşürürse `-fn` ekle.
- Realtime/push: topic adları, zarf alanları, `seq`/history pull.

## 3. Dokümanı üret (`template.md`)
- Sürüm: mevcut `vN` üzerine yazılmaz; `v(N+1)` açılır; "Önceki sürüme göre farklar" bölümü doldurulur; ilk doküman `v1`, farklar bölümü "önceki doküman yok".
- Her senaryo: HTTP status + body örneği (zarflı mı ham mı açık), alan eşleme, istemci dilinde örnek (`dio`/`fetch`), **bilinmeyen enum değeri için fallback**.
- Hata kodları ve ekran davranışı tablosu (kod → mesaj → ekran aksiyonu → retry?).
- Güvenlik ve log kuralları (istemci ne loglamaz, token nerede tutulur).
- Manuel test akışı: uygulama + API koleksiyonu (OpenAPI'den üretilmiş; OpenAPI yoksa curl örnekleri kabul).
- "Kritik" etiketi yalnız veri kaybı / güvenlik / ücret / bozuk akış için.
- Kaynak damgası: "<tarih> tarihli <branch> <sha> koduna dayanır."

## 4. Öz-kontrol
- [ ] Her endpoint OpenAPI'de var ve path/method aynı (OpenAPI yoksa alt-kanıt: modül derleniyor ve controller mapping'leri diff ile eşleşiyor; kutuyu `[ ]` bırak, nota yaz).
- [ ] Her hata kodu backend enum'unda var; istemci kod→mesaj tablosu güncellendi.
- [ ] Zarf biçimi uç başına doğru.
- [ ] Örneklerde gerçek kişisel veri/secret yok.
- [ ] Generated client paketi versiyonu ve değişiklik notu.
- [ ] Deploy sırası / eski istemci uyumluluğu (sunset) yazılı.

Çıktı:
1. **Gate kararı:** `İstemci etkisi yok` / `Doküman üretildi` / `Doküman üretilemedi: <sebep>` + kanıt. PR tablosuna eşleme: ilk ikisi → `APPROVE` (`HIGH` bulgu varsa `REQUEST CHANGES`); `Doküman üretilemedi` → `REQUEST CHANGES`; `BLOCKER` → `BLOCK`.
2. **Doküman yolu:** `docs/<client>-<feature>-integration-vN.md` (yeni sürüm) ve özet (Türkçe, 5–8 madde).
3. **Breaking değişiklikler** ve istemci için zorunlu aksiyonlar.
4. **Açık sorular** (ürün/istemci ekibine).

Bulgu listesi yalnız istemci sözleşmesini/davranışını değiştiren maddeleri içerir (alan, enum, status/hata kodu, zarf, rate-limit, auth). Güvenlik/DB/mimari ihlalleri istemciyi etkiliyorsa dokümanda tek satır "Kritik" notu olarak geçer; ayrıntısı ilgili `verso-*-review` skill'ine bırakılır.
