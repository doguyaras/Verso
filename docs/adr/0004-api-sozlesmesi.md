# ADR-0004: API sözleşmesi (sürüm, zarf, hata kodları, yönetim portu)

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-02
- **Karar verenler:** doguyaras
- **İlgili eşik (referans Bölüm 24):** Contract testi satırı. Ayrıca ilk dış istemci ekibi ortaya çıkınca OpenAPI diff gate'i (P1).

## Bağlam

Referans, API versiyonlamanın ilk günden kararlaştırılmasını (Bölüm 20, 21.0), tek bir hata zarfını (6.2) ve global olarak tekil hata kodlarını (7.2) ister.

Verso'nun istemcileri iki tür: demo script'leri ve müşterinin kendi sistemleri. Bunlar uzun süre eski sürümle yaşayabilir.

## Seçenekler

| Konu | Seçenek | Karar |
|---|---|---|
| Ana sürüm | `/v1` path öneki | Seçildi (referans önerisi) |
| Ara sürüm | `API-Version` header'ı, Spring Framework 7 `@GetMapping(version=…)` | Seçildi. `required=false`, `default=1.0`. Curl ile denemede header zorunluluğu sürtünme yaratır; sürümsüz istek 1.0 sayılır |
| Başarı yanıtı | Her zaman zarflı ya da her zaman ham | **Ham** (referans iskeleti ile aynı) |
| Hata yanıtı | `ApiResponse{ok,data,error}` + `ErrorResponse` | Her zaman zarflı; filtre redleri dahil |
| RFC 9457 ProblemDetail | Kullanılabilir | Kullanılmaz: tek zarf (referans 7.3 notu); ikisi karıştırılmaz |
| Actuator | Aynı port ya da ayrı yönetim portu | **Ayrı port 8081.** API sürüm kuralları actuator'a uygulanmaz (referans 20 uyarısı) ve yönetim uçları API'nin dışa açıldığı yüzeyde görünmez |

## Karar

- Public uçlar `/v1/<kaynak-çoğul>` biçimindedir (referans 5.3).
- Ara sürüm `API-Version` header'ı ile seçilir. Bu yapılandırma ilk sürümlü controller ile eklenir (faz 4). Kaldırılacak sürümler `Deprecation` (RFC 9745), `Sunset` (RFC 8594) ve `Link rel="deprecation"` taşır.
- Hatalar her zaman zarflıdır ve her yanıtta `X-Trace-Id` bulunur. Yalnız `platform-core` `GlobalServiceExceptionHandler` zarf üretir.
- Hata kodu blokları README'de ve `ErrorCodeUniquenessTest`'te birebir aynıdır:

  | Blok | Aralık |
  |---|---|
  | document | 10000–10999 |
  | qa | 11000–11999 |
  | validation | 90000–90099 |
  | security | 90100–90199 |
  | system | 99997–99999 |

- Her yanıtta `X-Rag-Mode: local|cloud` header'ı bulunur (ADR-0006).
- Kişisel veri veya belge içeriği dönen uçlar `Cache-Control: private, no-store` döner (referans 6.7).

## Sonuçlar

- **Olumlu:** İstemciler hata kodunu koda göre eşler. Kırıcı değişiklik yeni sürümle yan yana açılır.
- **Olumsuz / kabul edilen risk:** Ham başarı yanıtı ile zarflı hata yanıtı arasındaki fark istemci kodunda iki ayrı ayrıştırma yolu gerektirir. Bu risk belgelenmiştir.
- **Etkilenen dosyalar:** `platform-core` (zarf, handler), `application.yml` (yönetim portu, sürüm ayarı).
- **Geri alma yolu:** Ara sürüm zorunluluğu (`required=true`) ileride açılabilir. Bu, istemcileri kıran bir değişikliktir ve bir sonraki ana sürümle yapılır.

## Yeniden değerlendirme koşulu

- İlk harici istemci ekibi entegrasyona başlayınca: OpenAPI üretimi ve `openapi-diff` gate'i (P1).
- Sürümsüz istek oranı ölçülebilir hale gelince.
