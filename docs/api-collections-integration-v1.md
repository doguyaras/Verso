# API istemcisi paylaşımlı bilgi alanları entegrasyonu [v1]

Bir belge artık iki yerden birinde durur. Yükleyenin kişisel alanı bugünkü davranıştır. Paylaşımlı bir bilgi alanında (collection) ise belgeyi o alana erişimi olan IdP grupları ve kullanıcılar okur. Alanları `verso-admin` yönetir. Yetki her istekte, retrieval'ın kendi SQL'inde, sıralamadan önce uygulanır. Okuyamadığınız bir belgenin parçası ne aranır, ne sıralanır, ne de cevaba girer (ADR-0028).

> **OpenAPI doğrulanmadı:** uçlar ve alanlar `CollectionController`, `CollectionAdminController`, `DocumentController`, `ConversationController` ve `document-api` / `qa-api` DTO'larından çıkarıldı (ADR-0004). Generated client paketi yok.

Bu doküman **24 Ekim 2026** tarihli `feature/doguyaras-paylasimli-alanlar` branch'indeki koda dayanır (taban `develop` `55cd858`).

## Değişikliklerin özeti

- Yeni uçlar:
  - `GET /v1/collections`, `GET /v1/collections/{id}` (her kullanıcı: okuyabildiği alanlar);
  - `POST|PUT|DELETE /v1/collections[/{id}]`, `GET|POST /v1/collections/{id}/grants`, `DELETE /v1/collections/{id}/grants/{grantId}` (yalnız `verso-admin`).
- `POST /v1/documents?collectionId=` paylaşımlı alana yükler (yalnız `verso-admin`). `PUT /v1/documents/{id}/collection` belgeyi taşır (yalnız `verso-admin`).
- `GET /v1/documents` artık okuyabildiğiniz **bütün** belgeleri döner: kişisel ve paylaşımlı. `?collectionId=` tek alanı süzer.
- Ek alanlar (geriye uyumlu):
  - belge yanıtında `collectionId`, `collectionName`;
  - cevap atıfında (`citations[]`) `collectionId`, `collectionName`;
  - geçmiş atıfında `documentAccessible`, `collectionId`, `collectionName`.
- Yeni hata kodları: 10002, 10003, 10004, 10040, 10041, 10042, 10043, 10044.

## Kurallar

- **Kritik:** Kimlik, roller ve gruplar yalnız token'dan gelir. Path'teki kimlik yalnız hedeftir.
  - Okuyamadığınız alan ya da belge her işlemde `404` döner; varlığı söylenmez.
  - Okuyabildiğiniz bir paylaşımlı belgeyi silmeye çalışırsanız `403 10004` döner.
- **Kritik:** Grup değerleri (Keycloak'ta tam yol, Entra'da object id) müşterinin dizin verisidir. Kullanıcı ekranında gösterilmez, log'a ve analitiğe yazılmaz. Grant listesi yalnız yöneticiye döner.
- **Kritik:** Grup değeri birebir karşılaştırılır. Kırpılmaz, büyük-küçük harf katlanmaz: `/Hukuk` ile `/hukuk` iki ayrı gruptur. İstemci değeri yazıldığı gibi gönderir.
- Yetki değişikliği ne zaman geçerli olur:
  - Bir alandan erişim kaldırılırsa sonraki istekte geçerlidir.
  - Kullanıcı IdP'de gruptan çıkarılırsa bir sonraki access token'da geçerlidir (en çok 300 sn).
  - Keycloak'ta grup yeniden adlandırılırsa yola verilmiş erişim eşleşmez ve düşer (kapalı kalma). Yönetici erişimi yeni yolla yeniden verir.
- Yönetici bütün alanları ve paylaşımlı belgelerin adlarını yönetim için görür. Bir alanın içeriğini soru-cevapta okumak için kendisinin (grubunun ya da `sub`'ının) o alana erişimi olmalıdır. `readable: false` bunu gösterir.
- Başarı yanıtları ham, hatalar zarflıdır (ADR-0004). Bilinmeyen alanlar yok sayılır. Yanıtlar `Cache-Control: no-store, private` taşır.

## Değişiklikler ve istemciye etkisi

### 1. Belge listesi

`GET /v1/documents` okuyabildiğiniz belgeleri en yeniden eskiye döner. Paylaşımlı olanlarda `collectionId` ve `collectionName` doludur; istemci bir rozet gösterir. Kişisel kota (200) yalnız kişisel belgeleri sayar. Paylaşımlı alanlar daha çok belge taşıyabilir, bu yüzden istemci sayfalamayı `page.totalPages` ile sınırlı tutar (panel en çok 5 sayfa yükler). Silme düğmesi şu durumlarda gösterilir:
- kişisel belgede;
- paylaşımlı belgede yalnız yöneticiye.

### 2. Cevap ve geçmiş

Cevaptaki atıflar paylaşımlı belgenin alan adını taşır. Geçmişte bir atıf üç durumdan birindedir:

| Durum | `documentDeleted` | `documentAccessible` | `fileName` | Ekran |
|---|---|---|---|---|
| Okunabilir | `false` | `true` | ad | dosya adı + alan rozeti |
| Erişim kalktı | `false` | `false` | `null` | "Erişiminiz yok"; ad ve alan gösterilmez |
| Silindi | `true` | `false` | `null` | "Belge silinmiş" |

Eski istemciler `documentAccessible` alanını bilmez ve `fileName: null`'u "silindi" diye gösterir. Bu yanlış ama güvenlidir: ad sızmaz.

### 3. Yönetim (yalnız `verso-admin`)

Akış dört adımdır:
1. Alan oluşturulur.
2. Bir gruba (`GROUP`) ya da kullanıcıya (`USER`, token `sub`) erişim verilir.
3. Belgeler `?collectionId=` ile yüklenir ya da var olan belgeler taşınır.

Kurallar:
- Erişimsiz bir alan kimseye görünmez.
- İçinde belge olan alan silinemez (`409 10041`).
- Taşıma hedefi `null` ise belge **yükleyeninin** kişisel alanına döner.
- Yönetici başka bir kullanıcının kişisel belgesini göremez ve taşıyamaz (`404`).

## Kullanılan endpoint'ler

| Method | Path | Auth | Idempotency | Sınır | Zarf |
|---|---|---|---|---|---|
| GET | `/v1/collections?page&size` | user JWT | – | `size` 1–100 (varsayılan 50), `page` 0–100000 | ham |
| GET | `/v1/collections/{collectionId}` | user JWT | – | – | ham |
| POST | `/v1/collections` | `verso-admin` | yok (aynı ad `409 10040`) | ad 1–100, açıklama ≤ 500; kurulum başına 500 alan | ham |
| PUT | `/v1/collections/{collectionId}` | `verso-admin` | doğal | aynı | ham |
| DELETE | `/v1/collections/{collectionId}` | `verso-admin` | – | boş alan | `204` |
| GET | `/v1/collections/{collectionId}/grants` | `verso-admin` | – | alan başına en çok 100 | ham (dizi) |
| POST | `/v1/collections/{collectionId}/grants` | `verso-admin` | doğal: aynı grant `200` ile döner | değer 1–256, kontrol/biçim karakteri yok | ham |
| DELETE | `/v1/collections/{collectionId}/grants/{grantId}` | `verso-admin` | – | – | `204` |
| POST | `/v1/documents?collectionId=` | `verso-admin` | `X-Idempotency-Key` (mevcut) | alan başına 5000 belge | ham, `201` |
| PUT | `/v1/documents/{documentId}/collection` | `verso-admin` | doğal (aynı yere taşıma değişiklik yapmaz) | – | ham |
| GET | `/v1/documents?collectionId&page&size` | user JWT | – | mevcut sınırlar | ham |

## API sözleşmesi

### Alan

```json
{
  "id": "0199…",
  "name": "Hukuk sözleşmeleri",
  "description": "Kurumsal sözleşme şablonları",
  "documentCount": 42,
  "readable": true,
  "manageable": false,
  "createdAt": "2026-10-24T09:00:00Z",
  "updatedAt": "2026-10-24T09:00:00Z"
}
```

- `readable`: belgeleri listenizde ve soru-cevapta mı? Yönetici olmayan için her zaman `true`, çünkü başka alan listelenmez.
- `manageable`: yönetici misiniz?

### Oluştur / güncelle

`POST /v1/collections`, `PUT /v1/collections/{id}`:

```json
{ "name": "Hukuk sözleşmeleri", "description": "isteğe bağlı" }
```

- Ad kırpılır.
- Kontrol karakteri ve yön değiştirme işareti (bidi override) `400` döner.

### Erişim ver

`POST /v1/collections/{id}/grants`:

```json
{ "principalType": "GROUP", "principal": "/hukuk/sozlesmeler" }
```

Yanıt (`201` yeni, `200` zaten vardı):

```json
{ "id": "0199…", "principalType": "GROUP", "principal": "/hukuk/sozlesmeler", "createdAt": "…" }
```

`principalType` iki değer alır: `GROUP` (token'ın groups claim değeri) ya da `USER` (token `sub`). Başka bir değer `400` döner.

### Belgeyi taşı

`PUT /v1/documents/{id}/collection`:

```json
{ "collectionId": "0199…" }
```

`{ "collectionId": null }` belgeyi yükleyenin kişisel alanına götürür. Yanıt güncel belgedir.

### Belge (ek alanlar)

```json
{ "id": "…", "fileName": "sozlesme.pdf", "…": "…", "collectionId": "0199…", "collectionName": "Hukuk sözleşmeleri" }
```

Atıf (`citations[]`) ve geçmiş atıfı aynı iki alanı taşır. Geçmiş atıfına ayrıca `documentAccessible` eklenir.

## Hata kodları ve ekran davranışı

| Kod | HTTP | Anlam | Ekran | Tekrar |
|---|---|---|---|---|
| 10002 | 404 | Alan yok ya da okuyamıyorsunuz | "Bilgi alanı bulunamadı", listeye dön | hayır |
| 10003 | 404 | Grant bu alanda yok | "Bu erişim kaydı artık yok", listeyi yenile | hayır |
| 10004 | 403 | Paylaşımlı belgeyi yalnız yönetici değiştirir | silme düğmesini gizle | hayır |
| 10040 | 409 | Aynı adla alan var (harf büyüklüğü fark etmez) | ad alanında hata | düzeltip |
| 10041 | 409 | Alanda belge var | "Önce belgeleri taşıyın ya da silin" | sonra |
| 10042 | 409 | Alan başına erişim sınırı (100) | bilgi | hayır |
| 10043 | 409 | Kurulum başına alan sınırı (500) | bilgi | hayır |
| 10044 | 409 | Alan başına belge sınırı (5000) | bilgi | hayır |
| 90101 | 403 | Yönetici olmayan bir yönetim ucuna geldi; denetim kaydına yazılır | "Bu işlem için yetkiniz yok" | hayır |
| 90015 | 409 | Taşıma sırasında belge başka biri tarafından taşındı | yenile | evet |

## Güvenlik ve log kuralları

- Sunucu loglarında yalnız kimlikler ve sabit sözcükler bulunur (`principalType=GROUP`). Grup yolu, `sub`, alan adı ve dosya adı yazılmaz.
- Denetim kaydı (ADR-0026) aşağıdaki eylemleri yazar:
  - `COLLECTION_CREATED`, `COLLECTION_UPDATED`, `COLLECTION_DELETED`;
  - `COLLECTION_GRANT_ADDED`, `COLLECTION_GRANT_REMOVED` (ayrıntı `GROUP`/`USER`);
  - `DOCUMENT_MOVED` (`TO_COLLECTION`/`TO_PERSONAL`);
  - yönetici olmayanın denemeleri: `ACCESS_DENIED`, uç sözcüğü `COLLECTION_ADMIN` ya da `DOCUMENT`.
- İstemci grup değerini URL'e koymaz. Grant kaldırma grant `id`'si ile yapılır.

## Önceki sürüme göre farklar

- `GET /v1/documents`, paylaşımlı alana erişimi olan kullanıcıya daha çok belge döndürür. Kişisel belgeler aynıdır.
- `DELETE /v1/documents/{id}` paylaşımlı belgede okuyucuya `403 10004` döner (önceden böyle bir belge yoktu).
- Başka her yanıt aynıdır; yalnız sona alan eklendi.

## Manuel test akışı

### Uygulama üzerinden

1. Yönetici ile panelde "Bilgi alanları" açılır. Bir alan oluşturulur ve "Erişim ver"e grup yolu yazılır (`/hukuk`).
2. "Bilgi kaynakları"nda yükleme yeri olarak alan seçilir ve bir PDF yüklenir.
3. `/hukuk` grubundaki bir kullanıcı ile:
   - belge rozetle listede görünür;
   - soruya verilen cevabın kaynak kartında alan adı görünür.
4. Gruptan olmayan bir kullanıcı belgeyi görmez; aynı soruya "belgelerde bulunamadı" alır.
5. Yönetici erişimi kaldırır. Kullanıcının geçmişindeki atıf "Erişiminiz yok" olur.

### API koleksiyonu ile (curl)

```bash
ADMIN="$(bash scripts/demo-token.sh)"   # tarayıcıda verso-admin rolündeki kullanıcıyla (demo realm: admin) giriş yapın
curl -s -X POST localhost:8080/v1/collections -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"name":"Hukuk"}'
curl -s -X POST localhost:8080/v1/collections/<id>/grants -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{"principalType":"GROUP","principal":"/hukuk"}'
curl -s -X POST "localhost:8080/v1/documents?collectionId=<id>" -H "Authorization: Bearer $ADMIN" -F file=@sozlesme.pdf
```

## Deploy ve uyumluluk

- Migration `V4__collections.sql` migrate adımında koşar. Tabloları ekler, bir kolonu NULL olarak ekler ve var olan her belgeye tek okuyucu satırı yazar. Chunk tablosu yeniden yazılmaz. Süre belge sayısıyla doğrusaldır.
- Ortam değişkeni, secret ya da port eklenmedi. Sınırlar `config/verso.yml` `verso.document.collections.*` altındadır.
- Grupların token'a girmesi için Keycloak istemcisinde `groups` mapper'ı gerekir (`verso-panel` ve `verso-cli`'de var, ADR-0019). Entra'da `groupMembershipClaims` ayarlanır.
- Eski panel çalışmaya devam eder: rozet ve alan seçici görünmez, paylaşımlı belgeler listede ad ile görünür.

## Açık sorular

- Alan yöneticisi (yönetici olmadan grant verebilen rol) ve gruba yazma yetkisi v1'de yok. İstenirse ADR-0028'in yeniden değerlendirme koşuluna bakılır.
- Gerçek bir Entra kiracısıyla grup object id eşlemesi denenmedi: **net kanıt bulunamadı** (ADR-0019).
