# Kanıt: Güven ekranı ve `GET /v1/admin/trust` (ADR-0050)

Tarih: 2026-10-26. Bu geliştirmede Docker çalıştırılmadı; tarayıcıyla bakılmadı. Entegrasyon testlerini CI koşar.

## Yerelde koşanlar

| Ne | Komut | Sonuç |
|---|---|---|
| Derleme | `./mvnw -B -ntp -T 1 -DskipTests install` | BUILD SUCCESS |
| Birim: yardımcılar (özet biçimi, yaş, mod metinleri) | `-pl services/qa/qa-core test -Dtest=AdminTrustControllerTest` | 3 test geçti |
| Mimari ve config | `-pl verso-app test -Dtest=ArchitectureRulesTest,ConfigDriftTest,ModuleStructureTest,ModuleConsistencyTest` | 14 test geçti |
| Betik ve panel | `GITLEAKS=… node --test scripts/*.test.js scripts/panel.test.mjs` | `trust-panel.test.js` (6), `prove-local-mode.test.js` (6) dahil hepsi geçti |

## Yalnız CI'da koşanlar (Docker gerekir)

- `AdminTrustApiTest`: 403 matrisi (kullanıcı, operatör, yabancı rol), 401 (belirteçsiz, bozuk), `no-store`, yerel mod içeriği, yanıtta secret değeri ve `SECRET_*` içeriği olmaması.
- `AnthropicCloudModeTest.trust_inCloudMode_...`: bulut modu metni, sağlayıcı ve model, API anahtarının yanıtta olmaması.

## Yapılmayanlar

- Ekranın gerçek tarayıcıda görünümü, 44 px hedefler ve indirme düğmesi elle denenmedi; testler yalnız modeli, raporu ve `URL.createObjectURL` ile bağlantı tıklamasının taklit edilmiş halini sınar.
- `prove-local-mode.sh` gerçek yığında koşturulmadı; sahte `docker`/`curl` ile sınandı.
