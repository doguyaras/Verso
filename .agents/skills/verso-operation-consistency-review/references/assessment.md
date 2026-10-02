# Assessment — Mekanizma Seçimi

## 1. Soru ağacı

1. **Bu işlem başka servisin verisini değiştiriyor mu?**
   - Hayır → local TX + constraint. Bitti.
   - Evet → 2.
2. **Diğer servis yalnız "haberdar" mı olacak (kendi kararını kendi verecek)?**
   - Evet → **domain event** (outbox `kind=EVENT`) + idempotent consumer. Bitti.
   - Hayır (bizim işlem onun sonucuna bağlı) → 3.
3. **Uzak etki geri alınabilir mi (compensate edilebilir)?**
   - Evet ve tek katılımcı, tek adım → **local saga**.
   - Evet ama çok adım / çok katılımcı → `extension required` (ADR).
   - Hayır (para transferi, dış sistem, geri alınamaz) → saga uygun değil → süreç tablosu + durum makinesi + insan/dış onay adımı; `decision blocked` ile mimari karar iste.
4. **İşlem sıcak yolda mı?**
   - Evet → kritik akış kaydı güncellenir (bütçe, bağımlılık gerekçesi, eskilik); varsayılan ≤1 uzak çağrı aşılıyorsa ADR; consume dışındaki kontroller read-model/claim ile.
5. **Dış sağlayıcıya iş emri mi (SMS, push, webhook)?**
   - Evet → **komut** (outbox `kind=COMMAND` → queue) veya `kind=HTTP`.

## 2. Karar tablosu

| Durum | Mekanizma | Örnek |
|---|---|---|
| Kendi tablomu yazıyorum | TX + unique | Profil güncelleme |
| Aynı isteğin tekrarını engellemek | Idempotency key | Sipariş oluşturma retry'ı |
| Başkası haberdar olsun | Event + inbox | `order.order.created` → notification, analytics |
| Başkasının verisini karar için okumam lazım | Read-model / JWT claim | Hesap durumu, engel listesi |
| Başkasından geri alınabilir bir hak/stok tüketmem lazım | Local saga | Kampanya hakkı, envanter rezervi |
| Geri alınamaz dış etki | Süreç + insan/dış onay, ADR | Ödeme çekimi, resmi bildirim |

## 3. Çıktı formatı

- Karar: `saga unnecessary` / `existing saga suitable` / `extension required` / `decision blocked`
- Gerekçe (2–4 cümle), seçilen mekanizma, sıcak yol etkisi (uzak çağrı sayısı), açık sorular.
