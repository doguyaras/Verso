# ADR-0005: Kimlik doğrulama ve belge sahipliği (harici OIDC)

- **Durum:** Kabul edildi; faz 3'te uygulandı (ayrıntılar ADR-0010)
- **Tarih:** 2026-10-02
- **Karar verenler:** doguyaras
- **İlgili eşik (referans Bölüm 24):** Servis kimliği satırı.

## Bağlam

Referansa göre yetkisiz veri erişiminin engellenmesi her profilde **zorunlu güvencedir** (Bölüm 1.4). Belgeler kişisel veri içerebilir (KVKK).

Verso'nun hedef müşterisi zaten bir kimlik sistemine sahip kurumlardır (Active Directory/LDAP, kurumsal SSO). Kimlik verisi de belgeler gibi müşteri altyapısında kalmalıdır.

## Seçenekler

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| A. Harici OIDC IdP (Keycloak, on-prem), Verso yalnız token doğrular (resource server) | Kurumun mevcut dizinine bağlanır; parola, MFA ve oturum yönetimi Verso'da değil; standart (OIDC, JWKS) | Compose'a bir servis eklenir | Orta |
| B. Verso içinde auth modülü (referanstaki `auth` servisi) | Tam kontrol | Parola, refresh rotasyonu, `sv`, admin 2FA gibi geniş bir güvenlik yüzeyini Verso üstlenir | Yüksek |
| C. Statik API key | Basit | Kullanıcı bazlı sahiplik yok; anahtar rotasyonu ve paylaşım riski | Düşük |

## Karar

**A** seçildi; kullanıcı 2026-10-02'de onayladı.

- Verso, Spring Security OAuth2 Resource Server olarak çalışır. JWT imzası IdP'nin JWKS ucundan, `kid` ile doğrulanır.
- Kabul edilen algoritmalar EdDSA ve ES256'dır (referans 9.2). `alg` başlığına göre doğrulayıcı seçilmez. Faz 3'te yalnız ES256 açıldı (ADR-0007 #49).
- `iss`, `aud` ve `exp` doğrulanır.
- Hesap kimliği **yalnız** doğrulanmış token'ın `sub` claim'idir ve `@CurrentAccount` ile okunur. Path, query veya body'den alınmaz (referans 6.5).
- Her belge bir hesaba aittir. Listeleme, okuma, silme ve **retrieval** sahiplik koşuluyla yapılır. Retrieval'da filtre, vektör sorgusunun içindedir; sonuçları bellekte süzmek kabul edilmez (`docs/ai/llm-rules.md`).
- Şekil A'da servisler arası çağrı yoktur. Bu yüzden servis JWT'si, `/internal/**` uçları ve delegasyon matrisi gerekmez (referans 1.1).
- Demo için compose'ta Keycloak ve sentetik bir realm bulunur. Realm dosyasında gerçek parola yoktur; demo kullanıcısının parolası setup script'iyle üretilip secret dosyasına yazılır (referans 9.7 "Kaçın").
- **Reddedilenler:** B, geniş güvenlik yüzeyi demektir. C ise kullanıcı bazlı sahiplik sağlamaz.

## Sonuçlar

- **Olumlu:**
  - Verso parola veya MFA saklamaz.
  - Kurumsal SSO ile entegrasyon yapılandırma işidir.
  - Kimlik verisi on-prem kalır.
- **Olumsuz / kabul edilen risk:**
  - IdP erişilemezse yeni token alınamaz. Mevcut token'lar ömürleri boyunca çalışır; JWKS önbelleği Spring Security'dedir.
  - Rol değişikliği token ömrü kadar gecikir.
- **Etkilenen dosyalar (faz 3):** `platform-security` starter'ı (yeni), compose (Keycloak), `config/verso.yml` (issuer, audience), `secrets/`.
- **Geri alma yolu:** IdP değişimi yalnız issuer/JWKS yapılandırmasıdır. Uygulama kodu `sub` dışında IdP'ye özgü claim kullanmaz.

## Yeniden değerlendirme koşulu

- Çok kiracılı (multi-tenant) kullanım gerekirse (kiracı claim'i ve şema/RLS kararı).
- Düzenleyici bir gereklilikle mTLS'e geçmek gerekirse.
