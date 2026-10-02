---
name: verso-release-readiness-review
description: Use this skill on release PRs (to release/main), before the first production deploy of a new service, and quarterly — to verify backups, alerting, SLOs, runbooks, capacity, version/EOL status, security posture and rollback readiness.
---

Sürümün üretime çıkmaya hazır olup olmadığını mimari referans Bölüm 8.7 (Metrik ve Gözlem Yığını: asgari alarm seti), 10.5 (Yedekleme, PITR, HA ve Kapasite), 18.4 (Değişiklik Türüne Göre Rollout Sözleşmesi), 19.6 (Doğrulama Kapsamı ve Kanıt Kaydı), 24 (Ölçek Eşikleri ve Evrim Yolu), 25 (Sürüm ve Destek Takibi) ve Ek A'ya göre değerlendir (Bölüm 21.0 bu listeyle aynıdır, ayrıca okuma). Bu skill kod kalitesine değil **operasyonel gerçeklere** bakar: yedek var mı, alarm gidiyor mu, geri dönüş kaç dakika. Her madde için kanıt (dosya, config, pano linki, tarih) istenir; kanıtsız madde `BLOCKED`. Sorun yoksa: **"Release hazırlık kontrolü geçti."**

Kapsam: release PR'ı, ilk prod deploy veya çeyreklik kontrol. Feature PR'ında çağrılırsa yalnız diff'e bağlı maddeleri değerlendir (migration, rollout sözleşmesi, rollback, secret/config, PII, doküman); operasyonel maddeleri tek satırda topla: `repo dışı: BLOCKED (proje geneli, çeyreklik kontrolde)`. Diff: `git diff <base>...HEAD` + `git diff --stat` (base = hedef branch; PR diff'i verilmişse o); izlenmeyen dosyalar dahil. CI/compose/obs/runbooks bu repoda yoksa hangi repoda olduğunu kullanıcıya bir kez sor; cevap yoksa ilgili bölümü tek satırlık `BLOCKED` geç.

Durum kuralı: repo/PR içinde ya da kullanıcıdan alınan kanıtla eksikliği GÖSTERİLEN madde `FAIL`; kanıt bulunamayan madde `BLOCKED`. Bölüm durumu maddelerinin en kötüsüdür (`FAIL` > `BLOCKED` > `PASS`); en az bir `FAIL` varsa nihai durum `FAIL`, yoksa en az bir `BLOCKED` varsa `BLOCKED`, ikisi de yoksa `PASS`. Her `FAIL` için `Kaynak: bu release / mevcut durum` yaz; mevcut durumdan gelen eksik release'i ancak bu release o yüzeye dokunuyorsa engeller, dokunmuyorsa `Kabul edilen riskler` veya takip maddesi olarak raporla. Kanıt kaynakları: release notu = PR gövdesi (yoksa `CHANGELOG.md` ya da `docs/releases/<sürüm>.md`); CI sonucu = PR check run'ları; pano linki, son yedek tarihi ve test alarmı tarihi repo dışındadır — kullanıcıdan iste, gelmezse `BLOCKED`.

Koştur (proje kökünden): `node scripts/flyway-immutability.js check --base <base>` (`OK`/`IHLAL`/`DOGRULANAMADI` satırını aynen yaz); `mvn -B verify` (ya da `docs/ai/review-checklist.md` Bölüm 2 makine kontrolleri); `gitleaks detect` (kurulu değilse `BLOCKED`). "CI test sayısı kontrolü" maddesi mekanizmayı sorar (failIfNoTests / sayım adımı); son koşu sonucu "Doğrulama kapsamı" altında ayrıca yazılır. Filtreli koşu (`-pl X -am -Dtest=...`) `failIfNoTests=true` modüllerde kırılır: tam modül koşusu kullan ya da pom'daki `failIfNoTests`'i `${failIfNoTests}` property'sine bağla.

Kontrol et:

## Veri ve yedek
- WAL arşivi + base backup çalışıyor (son başarılı yedek tarihi); retention; şifreli.
- **Restore provası** son 30 gün içinde yapılmış ve kayıtlı (süre, doğrulama).
- RPO/RTO README'de; HA durumu (managed/standby/yok) açıkça yazılı ve kabul edilmiş.
- Redis security instance AOF; RabbitMQ definitions yedeği; object storage versioning.
- Bu release'in migration'ları prod benzeri veri hacminde denenmiş (süre, lock).

## Alarm ve gözlem
- Alertmanager/Grafana alerting bir kanala **gerçekten** bildirim gönderiyor (test alarmı tarihi).
- Asgari alarm seti (referans 8.7): restart-loop, health DOWN, disk/RAM, WAL arşiv gecikmesi, Redis bellek/eviction, RabbitMQ queue/DLQ, `outbox_oldest_pending_age_seconds`, `*_STUCK`, SLO burn-rate.
- Yeni servis/uç için scrape hedefi, log kaynağı, pano.
- Structured log; trace sampling politikası; Loki label kardinalitesi.

## SLO ve kapasite
- Kritik akışlar için SLO tanımlı ve pano var; son yük testi tarihi ve sonucu (p99, hata oranı) SLO içinde.
- Kapasite tablosu: host RAM/CPU kullanımı < %70; ikinci instance/host planı; DB bağlantı bütçesi; Bölüm 24 eşiklerinden yaklaşılan var mı.

## Deploy ve geri dönüş
- Image CI'da build, registry'de, cosign imzalı, digest ile deploy; prod'da build yok.
- **Rollout sözleşmesi** (referans Bölüm 18.4) release notunda değişiklik türü bazında: her olay/uç/şema/enum/claim değişikliği için sıra ve birlikte çalışacak sürümler; kırıcı değişiklikler için 4 hücreli uyumluluk matrisi; "çalışmaz" hücresi kabul edilmişse `FAIL`.
- Rollback: önceki digest listesi mevcut; migration expand/contract'a uygun (eski image yeni şemayla çalışır); prova edilmiş rollback süresi. **Image rollback ≠ veri rollback:** yeni sürümün yazdığı veri/olay/read-model ve dış komutlar geri alınmaz; geri alınamaz etkiler flag arkasında; staging'de "N+1 → yaz → N → oku" provası kayıtlı.
- CI test sayısı kontrolü var (mimari/tutarlılık testleri 0 test ile yeşil olamaz).
- Healthcheck ve readiness her serviste; `restart: always`; `docker-rollout`/blue-green.
- Staging'de smoke test geçti; prod deploy onay kapısı var.

## Güvenlik duruşu
- Secret'lar `/run/secrets`; literal fallback yok; gitleaks CI temiz; `.dockerignore`.
- Asimetrik service JWT; JWKS/`kid`; rotasyon prosedürü.
- Mock entegrasyon bean'i prod profilinde yok (açılış guard'ı testi).
- Seed/test verisi prod location'ında yok; bilinen parolalı admin yok.
- Gateway: rate limit, timeout, CORS listesi, `gateway` actuator kapalı, trusted proxy.
- Bağımlılık taraması (Dependabot/OSV) açık CRITICAL/HIGH yok veya kabul edilmiş risk yazılı.
- CI workflow'larında tüm üçüncü taraf action'lar commit SHA'ya pinli; Renovate digest güncellemesi açık.

## Sürüm ve destek (Bölüm 25)
- Java, Spring Boot, Spring Cloud, PostgreSQL, Redis/Valkey, RabbitMQ, arama motoru, Alloy/Loki/Tempo/Prometheus/Grafana, Node: **hepsi OSS destek içinde**; bitişe < 3 ay kalan için upgrade PR/plan var.
- Yaması yalnız ticari sürümde olan bilinen CVE yok. Config Server kullanılıyorsa 2026 CVE'leri (22739/40982/47894) için düzeltilmiş sürümde ve native backend prod'da değil.
- Lisans değişikliği (Redis, Elastic, BSL, GPL) gözden geçirilmiş.

## Uyum ve ürün
- Hesap silme akışı çalışıyor (uygulama içi + saga); export ucu; KVKK/GDPR süreleri ölçülüyor.
- Kişisel veri envanteri ve DPIA güncel; yeni alanlar eklendi mi.
- İstemci entegrasyon dokümanı ve OpenAPI/generated client release ile uyumlu; API sunset tarihleri.
- Runbook'lar: her alarm için "ne yapılır" sayfası; on-call kim; olay sonrası postmortem şablonu.

## Doğrulama kapsamı ve kanıt (referans Bölüm 19.6)
- Release notu yapısal (ArchUnit/enforcer/drift) ile davranışsal (outbox tekrar teslimi, iki worker, restart, inbox atomikliği, saga recovery) doğrulamayı ayrı listeliyor; davranışsal senaryoların kanıt kaydı (senaryo · seviye · test · commit · ortam · sonuç · tarih) dolu. Kaydı olmayan senaryo `BLOCKED`.
- Yeni servisin ilk prod deploy'unda en az: outbox tekrar teslimi, iki worker, süreç öldürme/kira devri seviye 2/3'te `PASS`.

## Dokümantasyon
- README kimlik tablosu, sıcak yol tablosu, fail politikası tablosu, kapasite tablosu güncel.
- Bu release için ADR gerektiren karar varsa ADR var.
- `docs/ai/repo-context.md` güncel; `docs/versions.md` tarihli.

Çıktı:
1. **Kontrol tablosu:** her `##` başlığı için bir satır (8 satır) → `PASS` / `FAIL` / `BLOCKED` + kanıt (dosya/link/tarih); kanıt sütununda yalnız belirleyici madde(ler).
2. **Engelleyiciler:** `FAIL` olanlar ve düzeltme; `BLOCKED` olanlar ve istenen kanıt.
3. **Kabul edilen riskler:** yazılı, sahipli, tarihli (kaynak: `docs/adr` ya da release notunda `Risk kabulü: <madde> · sahip · tarih · bitiş` satırı).
4. **Bölüm 24 eşik durumu:** yaklaşılan eşikler ve planlanan adım.
5. **Nihai durum:** `PASS` / `FAIL` / `BLOCKED`. Yedek, restore provası veya alarm kanalı eksikliği kanıtlanmışsa `FAIL`, kanıtsızsa `BLOCKED`. `PASS` ise "Release hazırlık kontrolü geçti." yaz; `FAIL`/`BLOCKED` ise bu cümleyi kullanma.
