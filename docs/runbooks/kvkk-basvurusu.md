# KVKK ilgili kişi başvurusu (md. 11): erişim ve silme

Alarmı olmayan, talep üzerine yürütülen iş. Karar ve gerekçeler: [ADR-0034](../adr/0034-silme-ve-basvuru-akisi.md). Uçlar: [`docs/api-subjects-integration-v1.md`](../api-subjects-integration-v1.md). Bu belge hukuki tavsiye değildir; başvuru süreci kurumun veri sorumlusu sürecidir (`docs/uyum/01-vked-sablonu.md` bölüm 5).

## Süre

Başvuru en geç **30 gün** içinde yanıtlanır (KVKK md. 13/2; repoda kaynak: `docs/ai/security-rules.md` bölüm 5 "KVKK 30 gün", **madde ve sürenin geçerliliği teyit edilmeli**). Başvuru tarihini ve yanıt tarihini başvuru dosyasına yazın.

## Ön koşul

- `verso-admin` rolü olan bir hesap ve token (`scripts/demo-token.sh` geliştirmede; üretimde kurumun IdP'si).
- Başvuranın Verso'daki **hesap kimliği** (`sub`): IdP'de kullanıcının "ID"si (Keycloak: Users → kullanıcı → ID; Entra: `sub` ya da yapılandırılmış claim). Ad, e-posta ya da sicil no ile arama yoktur. Kimliği doğrulama (başvuranın gerçekten o kişi olduğu) kurumun işidir.

## 1. Silme başvurusunda önce hesabı kapat

Silmeden sonra kişinin geçerli token'ı en çok 300 sn daha soru sorabilir ya da belge yükleyebilir (ADR-0034 bölüm 6). Bu yüzden:

1. IdP'de hesabı devre dışı bırakın (Keycloak: "Enabled" kapalı; kurumun kendi IdP'si: eşdeğeri).
2. Token ömrü kadar (varsayılan 300 sn) bekleyin.

Yalnız erişim başvurusunda (silme yok) bu adım gerekmez.

## 2. Ne tutulduğunu gör

```bash
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" https://<verso>/v1/admin/subjects/$SUB
```

- `history`: konuşma, tur, puan, hesap × gün sayıları.
- `documents.personal`: kişisel belgeler (silmede gider).
- `documents.collection`: kişinin bilgi alanlarına yüklediği belgeler (**silmede kalır**, adım 4).
- `documents.grantedCollections` / `createdCollections`: kişiye adıyla açılmış ve kişinin oluşturduğu alanlar.
- `auditEventsAsActor`: denetim kaydında kişinin aktör olduğu satır sayısı (silinmez, adım 6).

`400 13001`: `sub` yanlış yazılmış (yalnız `A-Z a-z 0-9 . _ @ : -`).

## 3. Erişim başvurusu: dışa aktar

```bash
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" -D basliklar.txt -o verso-kvkk-export.json \
     https://<verso>/v1/admin/subjects/$SUB/export
```

- `X-Subject-Export-Truncated: true` ise bir sınır aşılmıştır (2 000 konuşma, 100 000 denetim satırı, liste başına 1 000 belge); kalanını kurumun geliştirme ekibiyle çıkarın, başvuru dosyasına not edin.
- Dosya kişisel veri içerir: yalnız başvuru dosyasında, kurumun kişisel veri kurallarıyla saklanır; e-postaya düz eklenmez.
- Belgelerin kendisi (dosya, metin) dışa aktarımda yoktur; yalnız adları ve durumları. Başvuran belgenin kopyasını isterse kurum, belgenin sahibi birimden verir.
- `jq . verso-kvkk-export.json` hata verirse indirme yarıda kopmuştur: yeniden indirin.

## 4. Silme başvurusu

```bash
curl -s -X DELETE -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
     -d "{\"confirm\":\"$SUB\"}" https://<verso>/v1/admin/subjects/$SUB
```

- `confirm` path'teki `sub` ile birebir aynı olmalı; değilse `400 13002` ve hiçbir şey silinmez.
- Yanıttaki `historyDeleted`, `personalDocumentsDeleted`, `answersRedacted` başvuru dosyasına yazılır.
- `kept.documents.collection` boş değilse: bu belgeler kurumundur. Belgeyi tanıyan birimle karar verin; silinecekse `DELETE /v1/documents/{id}` (yönetici) ve ardından adım 5'teki karartma.
- `kept.documents.grantedCollections`: kişiye adıyla verilmiş erişimleri `/v1/collections/{id}/grants` ile kaldırın (panelde "Bilgi alanları").

### Yarıda kalırsa

- Yanıt `503` (`Retry-After`) ya da `500` ise ve log'da `Subject deletion interrupted: step=<adım> outcome=partial` satırı varsa: silinen silinmiş kalır, **aynı isteği tekrarlayın**. İkinci yanıt yalnız kalanı sayar. Her adım idempotenttir; tekrar zararsızdır.
- `step=documents` ve `lock_timeout`: belge o anda işleniyordur (worker); birkaç dakika sonra tekrarlayın.
- Sürekli düşüyorsa: [database-unavailable.md](database-unavailable.md).
- Başarıda denetim kaydında `SUBJECT_DELETED` satırı oluşur; yalnız tam başarıda yazılır.

## 5. Silinmiş belgeden alıntı yapan cevaplar

Bir belge silinince (kişinin kendi isteği, adım 4 ya da yönetici) eski cevap metni geçmişte kalır ve belgeden kişisel veri aktarmış olabilir. Belge kişisel veri içeriyorduysa:

```bash
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
     -d "{\"confirm\":\"$DOC\"}" https://<verso>/v1/admin/documents/$DOC/answer-redaction
```

- Bütün hesaplarda o belgeyi alıntılayan cevaplar sabit notla değiştirilir; geri alınamaz. Belge hâlâ varsa `409 13021`.
- Belge kimliği: silmeden önce özetten (`documents.*.id`) ya da denetim kaydından (`DOCUMENT_DELETED` satırının `targetId`'si).

### Belgede adı geçen kişi (hesabı olmayan ya da başka birinin belgesi)

Verso belge metninde ad aramaz (ADR-0034 bölüm 7: yazım, ek ve OCR farkları yüzünden güvenilir değildir). Prosedür:

1. Kurum, belgeleri tanıyan birimle (İK, hukuk, ilgili departman) kişiye ait veri içeren belgeleri belirler.
2. Yönetici bu belgeleri `DELETE /v1/documents/{id}` ile siler (kişisel belgeyse sahibi de silebilir).
3. Her biri için adım 5'teki karartmayı çalıştırır.
4. Belgelerin kimlikleri ve yapılan işlem başvuru dosyasına yazılır.

## 6. Denetim kaydı ve yedekler (başvuru yanıtına girecek bilgi)

- Denetim satırları silinmez: yalnız kimlik ve sabit işlem adı taşır, veri güvenliği ve hesap verilebilirlik için `VERSO_AUDIT_RETENTION_DAYS` gün (varsayılan 365) tutulur ve süre sonunda silinir. Dayanak ADR-0034 bölüm 5'te, **kurum hukuk birimi teyit etmeli**.
- Başvurunun ele alındığı denetim kaydında görünür: `SUBJECT_VIEWED`, `SUBJECT_EXPORTED`, `SUBJECT_DELETED`, `ANSWERS_REDACTED`. Hedef kimliği `sub`'dan hesaplanır (`UUID.nameUUIDFromBytes("verso:subject:" + sub)`, Java), `sub` satırda yazılmaz.
- Yedekler `BACKUP_RETENTION_DAYS` gün (varsayılan 7) sonra düşer. Bu süre içinde bir yedek geri yüklenirse, geri yüklemeden sonra bu runbook'un adım 4 ve 5'i **yeniden** çalıştırılır (başvuru dosyasındaki `sub` ve belge kimlikleriyle).

## 7. Kontrol

```bash
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" https://<verso>/v1/admin/subjects/$SUB
```

Beklenen: `history` sıfır, `documents.personalCount` 0; kalanlar yalnız bilinçli tutulanlar (alan belgeleri, erişimler, denetim satırları).
