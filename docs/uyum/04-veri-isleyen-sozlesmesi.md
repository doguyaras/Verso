# Veri İşleyen Sözleşmesi (iskelet)

> Bu belge hukuki tavsiye değildir; kurumun hukuk birimi ve veri sorumlusu tarafından uyarlanmalıdır.

Danışmanlık ekibinin **kurulum ve destek sırasında** kurumun kişisel verilerine erişebildiği durumlar için sözleşme iskeletidir. Tam metin değil, ana maddeler ve Verso'ya özgü içeriktir; iki tarafın hukuk birimleri tamamlar. Veri sorumlusunun, işlemeyi kendi adına yapan kişiyle birlikte veri güvenliği tedbirlerinden müştereken sorumlu olduğu ve işleyenle yazılı sözleşme yapılması gerektiği yönündeki hükümler 6698 sayılı KVKK md. 12 çerçevesindedir **[genel; ADR-0026 md. 12'yi veri güvenliği olarak anar, işleyen hükmü repoda yok: kurum hukuk birimi teyit etmeli]**.

| Taraf | Bilgi |
|---|---|
| Veri sorumlusu | {{KURUM_ADI}}, {{KURUM_ADRESI}}, temsilci {{KURUM_TEMSILCISI}} |
| Veri işleyen | {{KURULUM_EKIBI_ADI}}, {{EKIP_ADRESI}}, temsilci {{EKIP_TEMSILCISI}} |
| Ana sözleşme | {{ANA_SOZLESME_REFERANSI}} |
| Sürüm / tarih | {{SOZLESME_SURUMU}} / {{TARIH}} |

## 1. Konu ve ilişki

1.1. İşleyen, veri sorumlusunun talimatıyla yalnızca şu hizmetleri sunarken kişisel veriye erişebilir: Verso'nun kurulumu, yapılandırması, güncellenmesi, hata giderme ve destek ({{HIZMET_KAPSAMI}}).
1.2. Taraflar, hazır modeli kendi amacıyla kullanan kurumun **veri sorumlusu** olduğunu kabul eder **[repo: KVKK Üretken YZ Rehberi, `market_needs.md`]**. İşleyen, Verso'nun işletiminde yalnızca bu sözleşmede sayılan erişimler için işleyen sıfatındadır; erişimi olmayan, yalnızca yazılım teslimi yapılan işlerde sıfatın doğup doğmadığı kurum hukuk birimi teyit etmeli.
1.3. Süre: {{SOZLESME_SURESI}}; kurulum ve destek dönemi boyunca.

## 2. İşlemenin niteliği, amacı, veri ve kişi kategorileri

| Başlık | İçerik |
|---|---|
| Amaç | Yalnız 1.1'deki hizmet |
| Nitelik | Erişim, görüntüleme, yapılandırma, hata ayıklama; **dışa aktarma yok** (madde 5.4) |
| Olası veri kategorileri | Belge içeriği, dosya adları, sorular ve cevaplar, kullanıcı kimliği (`sub`) ve rolleri, denetim kayıtları (Verso envanteri: [01](01-vked-sablonu.md) Bölüm 3) |
| Özel nitelikli veri | {{OZEL_NITELIKLI_VAR_MI}} |
| İlgili kişiler | Kurum çalışanları ve belgelerde adı geçen üçüncü kişiler |

## 3. İşleyenin yükümlülükleri

3.1. **Yalnız talimat.** Kişisel veriyi yalnız veri sorumlusunun yazılı talimatıyla işler; talimatın hukuka aykırı olduğunu düşünürse bildirir.
3.2. **Gizlilik.** Erişimi olan kişiler yazılı gizlilik yükümlülüğü altındadır; erişim listesi: {{ERISIM_LISTESI}}. Sözleşme bitse de sürer.
3.3. **Erişimin sınırlanması.** Üretim verisine erişim yalnız destek talebi kapsamında, veri sorumlusunun onayıyla, süreli ve kayıtlıdır: {{ERISIM_YONTEMI}}. Kalıcı uzaktan erişim: {{KALICI_ERISIM_VAR_YOK}}. Erişim, kurumun kimlik sistemindeki bireysel hesapla yapılır; ortak hesap kullanılmaz.
3.4. **Veri kopyası yok.** Üretim verisi (belge, soru, cevap, veritabanı dökümü, yedek) kurum ortamından çıkarılmaz; işleyenin cihazlarına, e-postasına, sohbet araçlarına ya da hata kayıt sistemlerine kopyalanmaz. Hata raporuna eklenen log/ekran görüntüsü kişisel veri içermemelidir: Verso logları içerik taşımaz (llm-rules 2.1), yine de paylaşmadan önce kontrol edilir.
3.5. **Veri güvenliği.** İşleyen en az şunları uygular: {{ISLEYEN_GUVENLIK_TEDBIRLERI}} (cihaz şifrelemesi, güncel işletim sistemi, çok faktörlü kimlik doğrulama, kurum VPN'i ya da muadili). Verso'nun kendi tedbirleri [01](01-vked-sablonu.md) Bölüm 8-9'dadır.
3.6. **Alt işleyen.** Önceden yazılı izin olmadan alt işleyen kullanılmaz. Onaylı liste: {{ALT_ISLEYEN_LISTESI}} (varsayılan: yok). Alt işleyene aynı yükümlülükler yansıtılır.
3.7. **İlgili kişi başvuruları.** İşleyen, doğrudan gelen başvuruyu yanıtlamaz; {{SURE}} içinde veri sorumlusuna iletir ve yanıta yardım eder. Verso'daki hak karşılıkları: [01](01-vked-sablonu.md) Bölüm 6.
3.8. **İhlal bildirimi.** İşleyen, kişisel verinin hukuka aykırı ele geçirilmesini ya da şüphesini öğrendiği andan itibaren **{{IHLAL_BILDIRIM_SURESI}}** içinde veri sorumlusuna bildirir; bildirimde olay, etkilenen veri/kişi, alınan tedbirler yer alır. Veri sorumlusunun Kurul'a ve ilgili kişilere bildirim süreleri kurum hukuk birimi teyit etmeli **[genel]**.
3.9. **Güvenlik açığı ve olay (7545).** Siber Güvenlik Kanunu (7545 sayılı, RG 19 Mart 2025) olay ve açık bildirimi yükümlülüklerini (md. 7/1-b) ve bunların işleyene ya da yazılım tedarikçisine yansımasını taraflar ayrıca değerlendirir; ikincil mevzuat Temmuz 2026 itibarıyla yoktu, güncel durum kurum hukuk birimi teyit etmeli **[repo: `regulation_markets_hardware.md`, `market_needs.md`]**. Ayrıntı: [06](06-sektor-notlari.md).
3.10. **Denetim.** Veri sorumlusu (ve sektörü gerektiriyorsa düzenleyici) işleyenin bu sözleşmeye uyumunu {{DENETIM_USULU}} ile denetleyebilir; işleyen belge ve erişim kayıtlarını sağlar. Bankalarda düzenleyici denetim hakları ve çıkış planı için ek: [06](06-sektor-notlari.md).
3.11. **Kayıt.** İşleyen, hangi kişinin ne zaman hangi sistemde ne yaptığına dair erişim kaydını {{ERISIM_KAYDI_SURESI}} süreyle tutar ve talepte verir.

## 4. Veri sorumlusunun yükümlülükleri

4.1. Talimatları yazılı verir; işleme için hukuki sebebi, aydınlatmayı ve VKED'i yerine getirir ([01](01-vked-sablonu.md), [02](02-aydinlatma-metni-calisan.md)).
4.2. Erişim için gerekli kimlikleri ve ortamı sağlar; destek için kişisel veri içermeyen ortam (test belgeleri) olanaklıysa tercih eder.

## 5. Verso'ya özgü maddeler

5.1. **Mod.** Kurulum `{{AI_MODU}}` modundadır. `local` modda işleyen, kurum ortamında hiçbir veriyi dışarı göndermeyen yapılandırmayı bozmaz: Ollama'ya host portu açmaz, `internal` ağları değiştirmez, `VERSO_AI_MODE` ya da sağlayıcı ayarını veri sorumlusunun yazılı talimatı olmadan değiştirmez. `cloud` mod yalnız yazılı karar ve hukuki mekanizma sonrasında açılır ([01](01-vked-sablonu.md) Bölüm 5.2).
5.2. **Sır ve anahtarlar.** Secret değerleri (parolalar, bulut API anahtarı) işleyenin sistemlerine kopyalanmaz; yalnız adları paylaşılır. Cloud API anahtarı yalnız `/run/secrets` ile uygulamaya gelir (llm-rules 1.4).
5.3. **Yapılandırma değişiklikleri.** Saklama süreleri (`VERSO_HISTORY_RETENTION_DAYS`, `VERSO_AUDIT_RETENTION_DAYS`), yönetici görünürlüğü (`VERSO_SHOW_UNANSWERED_QUESTIONS`, `VERSO_SHOW_DOCUMENT_NAMES`) ve mod ayarı yalnız yazılı talimatla değiştirilir; değişiklik [01](01-vked-sablonu.md)'e ve [02](02-aydinlatma-metni-calisan.md)'ye yansıtılır.
5.4. **Dışa aktarma yasağı.** Denetim kaydı CSV dışa aktarımı (`GET /v1/admin/audit/export`), veritabanı dökümü ve yedek yalnız veri sorumlusunun talimatıyla ve veri sorumlusunun kontrolündeki ortama alınır.
5.5. **Yetki yükseltme yok.** İşleyenin kullanıcı hesabı `verso-admin` rolü dışında, gerekmedikçe yönetici rolü almaz; yönetici erişimi denetim kaydına yazılır (`ADMIN_METRICS_VIEWED`, `AUDIT_EXPORTED`, ADR-0026).
5.6. **Güncelleme.** Sürüm yükseltmeleri (özellikle güvenlik yükseltmeleri) veri sorumlusuna önceden bildirilir; değişikliğin işleme gerçeklerini etkileyip etkilemediği (ADR listesi) belirtilir.
5.7. **Test ve değerlendirme verisi.** İşleyen, kuruma ait belgeleri Verso'yu değerlendirmek, eval setine eklemek ya da modelleri iyileştirmek için **kullanmaz**.

## 6. Süre sonu: iade ve silme

6.1. Sözleşme bitince ya da veri sorumlusu isteyince işleyen, elindeki kişisel verinin tümünü {{IADE_SURESI}} içinde siler ya da iade eder ve yazılı doğrular (kopya tutulmadığına dair beyan: madde 3.4 gereği zaten yoktur).
6.2. Kurulum, kurumun ortamında kalır; sistemdeki verinin akıbeti (silinmesi, saklanması) veri sorumlusunun kararıdır. Verso'daki silme yolları: belgeler `DELETE /v1/documents/{id}`; geçmiş `DELETE /v1/conversations`; denetim kaydı süre sonunda `audit.purge_expired` ile. Yedekler `BACKUP_RETENTION_DAYS` (varsayılan 7) gün sonra düşer.
6.3. Mevzuatın saklamayı zorunlu kıldığı hâller dışında işleyen veri tutmaz.

## 7. Sorumluluk, ceza, uyuşmazlık

{{SORUMLULUK_MADDESI}}, {{CEZAI_SART}}, {{YETKILI_MAHKEME}}. (Hukuk birimleri doldurur.)

## Ek 1. Teknik ve idari tedbirler özeti

Verso'nun uygulamadığı ve kurumun/işleyenin uygulayacağı tedbirler ayrı sütunlarda yazılır:

| Tedbir | Verso (uygulamada) | İşleyen | Kurum |
|---|---|---|---|
| Kimlik doğrulama | OIDC; ES256 token; `aud: verso-api` | Bireysel hesap, MFA | IdP yönetimi |
| Erişim kaydı | Denetim kaydı (365 gün) | Kendi erişim kaydı | Kayıtların incelenmesi |
| Veri sızıntısı önleme | `local` modda egress kapalı ([05](05-egress-kaniti.md)) | Yapılandırmayı bozmaz | Ağ/güvenlik duvarı |
| Şifreleme | Şifreli yedek | Cihaz şifreleme | TLS, disk şifreleme: {{SIFRELEME_DURUMU}} |

## Ek 2. Alt işleyen listesi

| Alt işleyen | Hizmet | Konum | Onay tarihi |
|---|---|---|---|
| {{ALT_ISLEYEN_1}} | {{HIZMET_1}} | {{KONUM_1}} | {{ONAY_1}} |

(`cloud` modda chat sağlayıcısı veri sorumlusunun kendi sağlayıcı sözleşmesiyle de ele alınabilir; hangisi geçerliyse burada belirtilir: {{SAGLAYICI_ILISKISI}}.)
