# context-boundaries.md — Token Ekonomisi ve Tarama Sınırları

> Amaç: Ajanın gereksiz dosya okumasını önlemek; büyük repoda doğru yüzeyi doğru zamanda açmak.

## 1. Varsayılan olarak context DIŞI

`**/target/`, `**/node_modules/`, `**/dist/`, `**/build/`, `.idea/`, `.vscode/`, `*.log`, `.env*`, `secrets/`, `**/*.enc.yaml`, `obs/**/data/`, `postman/`, `docs/<client>-*-integration-v*.md` (yalnız istemci dokümanı görevinde), `docs/adr/` (yalnız mimari karar görevinde), `**/src/test/resources/**/*.json` (fixture'lar), image/binary dosyalar.

## 2. Şartlı açılacak yüzeyler

| Görev | Aç | Açma |
|---|---|---|
| Kod değişikliği (servis içi) | İlgili `*-core` modülünün `service`, `controller`, `repository`, `entity`, ilgili `*-api` DTO'ları, `config/<svc>.yml` | Diğer servislerin core'ları; `obs/`; workflow'lar |
| Yeni internal uç / client | Hedefin `*-api`'si, hedefin `config/<svc>.yml` (`internal-access`), çağıranın `client/` | Hedefin core iç sınıfları |
| Migration | İlgili `db/migration/` klasörü (yalnız son 3–5 dosya + `V1`), ilgili entity'ler | Diğer servislerin migration'ları |
| Event / read-model | `<domain>-api/event`, `platform-messaging`, tüketicinin `readmodel/` | – |
| Güvenlik incelemesi | `platform-security`, `gateway/config`, ilgili controller/filter | Frontend (ayrı görev) |
| Config/env etkisi | `deploy/`, `config/<svc>.yml`, `application-local.yml`, `.github/workflows/deploy.yml` | Dockerfile (yalnız build/runtime değişiyorsa) |
| Gözlemlenebilirlik | `obs/`, `platform-observability` | Servis iş kodu |
| İstemci dokümanı | `docs/<client>-<feature>-integration-v*.md` (son sürüm), OpenAPI çıktısı | Backend iç kodu (yalnız contract) |
| Frontend | `<panel>-web/src/{api,pages/<ilgili>,components/<ilgili>}` | `dist/`, tüm pages |

## 3. Tarama disiplini

- Önce `docs/ai/repo-context.md` Bölüm 4 "yüksek sinyalli dosyalar"; sonra grep ile daraltılmış hedef; sonra dosya.
- Bir dosyayı tamamen okumadan önce boyutuna bak; 500 satırı geçen dosyada önce `grep -n "class\|public .*(" ` ile harita çıkar.
- Aynı dosyayı iki kez okuma; not al.
- "Bütün repoyu tara" isteği gelirse önce hangi soruya cevap arandığını netleştir; soruya göre yukarıdaki tablodan yüzey seç.
- Büyük doküman (referans, plan) yalnız ilgili bölümüyle açılır (`grep -n "^## "` ile başlık haritası → offset ile oku).

## 4. Bu Belgede Özellikle Taşınmayanlar

Repo'nun dosya sayısı/boyutu gibi hızlı değişen sayılar.

## 5. Net Kanıt Bulunamayan Alanlar

- (ajan ekler)
