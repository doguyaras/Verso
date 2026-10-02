# <Client> <özellik> entegrasyonu [vN]

<2–4 cümle giriş: bu özellik ne yapar, istemci için ne değişir.> Bu doküman **<GG Ay YYYY>** tarihli `<branch>` branch'indeki `<sha>` backend koduna ve CI'da üretilen `verso-api.yaml` (`<sürüm>`) OpenAPI çıktısına dayanır. Generated client paketi: `<paket>@<sürüm>`.

## Değişikliklerin özeti
- …
- …

## Kurallar
> **Kritik:** yalnız veri kaybı / güvenlik / ücret / bozuk akış için kullanılır.
- **Kritik:** …
- …

## Değişiklikler ve <client>'a etkisi
### 1. <değişiklik>
Ne değişti · neden · istemci ne yapmalı · geriye uyumluluk (eski sürüm ne görür).

## Kullanılan endpoint'ler
| Method | Path | Auth | Idempotency | Rate limit (429) | Zarf |
|---|---|---|---|---|---|
| POST | `/v1/...` | user JWT | `X-Idempotency-Key` | `<scope>` | ham / zarflı |

## API sözleşmesi
### <işlem>
#### Senaryo: <başarı>
`HTTP 201` — body (ham/zarflı):
```json
{ ... }
```
#### Senaryo: <hata>
`HTTP 409` — zarflı:
```json
{ "ok": false, "data": null, "error": { "code": 11002, "message": "...", "service": "order", "traceId": "...", "details": ["retryAfterSeconds=120"] } }
```
#### Alan eşleme
| Alan | Tip | Zorunlu | Not (bilinmeyen enum → fallback) |
|---|---|---|---|
#### <Dil> örneği
```dart
// dio / generated client kullanımı; ApiException; unknown enum fallback
```

## Realtime / push (varsa)
Topic/zarf alanları, `seq` ve history pull, push payload'ı (içerik yok).

## Hata kodları ve ekran davranışı
| code | Anlam | Ekran aksiyonu | Retry? |
|---|---|---|---|

## Güvenlik ve log kuralları
Token yalnız bellekte; loglanmayacaklar; attestation gereken uçlar.

## Önceki sürüme göre farklar (v(N-1) → vN)
- …

## Manuel test akışı
### Uygulama üzerinden
1. …
### API koleksiyonu ile
1. …

## Deploy ve uyumluluk
Backend deploy sırası; eski istemci sürümü davranışı; sunset tarihi (varsa).

## Açık sorular
- …
