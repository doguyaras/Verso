# Sektör notları: banka, ödeme, SPK, kamu; 7545 olay bildirimi

> Bu belge hukuki tavsiye değildir; kurumun hukuk birimi ve veri sorumlusu tarafından uyarlanmalıdır.

Bu notlar kurulum ekibinin sektöre göre **kapsamı belirlemesi** içindir. Madde numaraları ve tarihler araştırma notlarından alınmıştır (`research_notes/Verso kapsamlı küresel araştırma/regulation_markets_hardware.md` ve `.../market_needs.md`); araştırma notları bazı birincil kaynaklara erişemediklerini belirtir (DDO Rehber sürümü, 2025-2026 BDDK değişiklikleri, 7545 ikincil mevzuatı, yerli malı listesi). Bu nedenle her satır **kurum hukuk birimi teyit etmeli**. Kaynak işaretleri: **[repo]** = araştırma notunda geçen ifade.

## 1. Özet karar tablosu

| Kurum türü | Önerilen mod | Nedeni (özet) |
|---|---|---|
| Banka | Yalnız `local` | Birincil/ikincil sistemler ve dış hizmet/bulut Türkiye'de; yalnız özel bulut |
| Ödeme ve elektronik para kuruluşu | Yalnız `local` | Sistemlerin yerelleştirilmesi; kişisel/özel nitelikli veri yalnız özel bulutta |
| SPK'ya tabi kuruluş (aracı kurum vb.) | Yalnız `local` | Sistemlerin Türkiye'de bulunması; dış hizmet şartları |
| Kamu kurumu | Yalnız `local`, kapalı ağ | Genelge 2019/12 ve DDO rehberi: veri kurumun sistemlerinde ya da yurt içi, kurum kontrolündeki sağlayıcıda |
| Diğer özel sektör | `local` (varsayılan); `cloud` yalnız yazılı karar ve aktarım mekanizması sonrası | KVKK md. 9 [genel]; [01](01-vked-sablonu.md) Bölüm 5.2 |

**Uygulama:** bu kurum türlerinde kurulum ekibi `deploy/compose.cloud.yaml` overlay'ini **uygulamaz**, `SECRET_CLOUD_API_KEY` oluşturmaz ve sözleşmeye "cloud mod kapalıdır" maddesini koyar. `local` mod, uygulamanın açılış kontrolü ve ağ tanımıyla zorlanır; kanıt [05](05-egress-kaniti.md).

## 2. Banka (BDDK)

- BDDK Yönetmeliği (RG 15 Mart 2020): md. 25/1 ve 25/5 birincil/ikincil ve dış hizmet/bulut sistemlerinin Türkiye'de olması; md. 29/11 yalnız özel bulut (topluluk bulutu Kurul izniyle); md. 22/6 kaynak kod emaneti; md. 18/7 yıllık sızma testi; md. 29/10 kritik bilgi sistemi ürünlerinde yerli üretim tercihi **[repo: `regulation_markets_hardware.md`]**. Destek Hizmetleri Yönetmeliği risk/teknik yeterlilik raporu ve BDDK denetim hakları getirir **[repo]**.
- **Çıkarım [repo, çıkarım olarak işaretli]:** bankada Verso "dış hizmet"tir; BDDK denetim maddeli sözleşme, sızma testi iş birliği, kaynak kod emaneti ve çıkış planı gerekir. Bunlar bu pakette hazır değildir (takip işi); [04](04-veri-isleyen-sozlesmesi.md) iskeletine banka ekinde eklenir.
- BDDK'da yeni üretken yapay zekâ kuralı yok (araştırma notu, 2026); Şubat 2026'da BDDK-KKB çalıştayı sandbox kararı almış **[repo: `market_needs.md`]**. Güncel durum kurum hukuk birimi teyit etmeli.
- Kurulum notu: `local` mod; sızma testi, SBOM ve kaynak kod emaneti talepleri sözleşme sürecinde ayrıca ele alınır. Verso'nun bugünkü kanıtları: ADR'ler, `docs/versions.md` (sürüm ve CVE tarihleri), `docs/evidence/`.

## 3. Ödeme ve elektronik para kuruluşları (TCMB)

- TCMB Tebliği (RG 1 Aralık 2021): md. 21 yerelleştirme; md. 16/7 kişisel/özel nitelikli verinin yalnız özel bulutta tutulması **[repo: `regulation_markets_hardware.md`]**.
- Kurulum notu: `local` mod; bu satır [repo] kaynaklıdır ve tebliğin güncel metni kurum hukuk birimi tarafından teyit edilmelidir.

## 4. SPK'ya tabi kuruluşlar

- SPK VII-128.9: md. 26/1 sistemlerin Türkiye'de olması; md. 18 dış hizmet şartları **[repo: `regulation_markets_hardware.md`]**. SPK VII-128.10 Tebliği aracı kurumlara yıllık bilgi sistemleri denetimi ve **denetim izi yönetimi** getirir **[repo: `market_needs.md`]**.
- Verso'nun denetim kaydı ([01](01-vked-sablonu.md) Bölüm 3, ADR-0026) bu beklentiye yardımcı olabilir; ancak kaydın amacı, kapsamı (içerik yok) ve süresi (varsayılan 365 gün) bu tebliğin gerektirdiği izle uyumlu olduğu **iddia edilmez**: kurum teyit etmeli. 2026 için SPK'nın yapay zekâ duyurusu bulunamadı **[repo]**.
- Kurulum notu: `local` mod; denetim kaydı saklama süresi kurumun denetim izi politikasıyla hizalanır (`VERSO_AUDIT_RETENTION_DAYS`, 30-3650 gün aralığı; ADR-0026).

## 5. Kamu kurumları (CBDDO / Genelge 2019/12)

- Genelge 2019/12 "Bilgi ve İletişim Güvenliği Tedbirleri" (RG 6 Temmuz 2019) ve onunla birlikte anılan CBDDO (Cumhurbaşkanlığı Dijital Dönüşüm Ofisi) Bilgi ve İletişim Güvenliği Rehberi: kamu verisinin bulut depolamada tutulmaması, istisna olarak kurumun kendi sistemleri ya da kurum kontrolündeki yurt içi sağlayıcılar; kritik veri için kapalı ağ, erişim kontrolü, kurcalamaya karşı korunmuş log saklama; sağlık verisi yurt dışına ancak KVKK md. 9 ile **[repo: `pazar_ve_fiyatlandirma.md`, `regulation_markets_hardware.md`]**.
- **Doğrulanmayan:** rehberin güncel sürümü (araştırma notlarında 1.1/Mart 2026 ve Siber Güvenlik Başkanlığı'na geçiş geçer; erişilemeyen kaynaklar) ve 2025-2026 değişiklikleri **[repo: net kanıt bulunamadı]**. Kamu kurulumunda güncel rehber metni kurum tarafından sağlanır ve bu belge o metne göre yeniden okunur.
- Kamuda üretken yapay zekâ için ayrı genelge yok; Yapay Zekâ Eylem Planı 2026-2030 (Genelge 2026/9, RG 18 Ağustos 2026) özel sektör için doğrudan yükümlülük getirmez, kamuda yüksek etkili YZ için etki değerlendirmesi öngörür **[repo: `regulation_markets_hardware.md`]**; kurum hukuk birimi teyit etmeli.
- Kurulum notu: `local` mod, **internet bağlantısı olmayan ağ** tercih edilir. Model dosyaları ilk kurulumda indirilir (`ollama-pull`); kapalı ağda modeller dışarıda hazırlanıp aktarılır: aktarım yöntemi {{KAPALI_AG_MODEL_AKTARIMI}} (bu repoda belgelenmiş bir çevrimdışı kurulum prosedürü için net kanıt bulunamadı). Kamu alımlarındaki yerli malı/yerli yazılım koşulları için kurum hukuk birimi teyit etmeli (yerli malı belgesinde yazılımın ele alınmadığı araştırma notunda geçer **[repo]**).

## 6. Siber Güvenlik Kanunu 7545 ve olay bildirimi

- 7545 sayılı Kanun (RG 19 Mart 2025): md. 7/1-b olay ve zafiyet bildirimi; md. 7/1-c kritik altyapıların siber güvenlik ürünlerini yalnız sertifikalı tedarikçilerden alması; md. 4/1-d yerli tercihi; idari para cezası 1-10 milyon TL aralığı; ikincil mevzuat bir yıl içinde çıkarılacak **[repo: `regulation_markets_hardware.md`]**. Araştırma notu: ikincil mevzuat Temmuz 2026'da hâlâ yok **[repo: `market_needs.md`]**; bugünkü durum teyit edilmeli.
- Verso bir "siber güvenlik ürünü" olmayabilir ama Siber Güvenlik Başkanlığı/USOM bildirim adımı ve SBOM kanıtı gerekebilir **[repo, çıkarım]**.
- **Kurulum ekibi için kural:** bir güvenlik olayı ya da zafiyet fark edildiğinde sırayla: (1) kuruma hemen bildir ([04](04-veri-isleyen-sozlesmesi.md) 3.8, süre {{IHLAL_BILDIRIM_SURESI}}); (2) kurumun olay yönetimi sürecine katıl: {{OLAY_YONETIMI_SURECI}}; (3) 7545 kapsamındaki bildirimin kimin tarafından, hangi merciye ve hangi sürede yapılacağını **kurum hukuk birimi belirler**: {{7545_BILDIRIM_KARARI}}. Kişisel veri ihlali bildirimi (KVKK Kurulu) ayrı bir süreçtir ve [03](03-uretken-yz-kullanim-politikasi.md) Bölüm 6'daki iç bildirimle başlar.
- Verso tarafında olay izi: denetim kaydı, `verso.audit.events.not.stored` sayacı ve `AuditEventNotStored` alarmı, `docs/runbooks/audit-write-failed.md`, `docs/runbooks/README.md`.

## 7. KVKK genel notlar (tüm sektörler)

- KVKK Üretken Yapay Zekâ Rehberi (24 Kasım 2025) ve İş Yerlerinde Üretken Yapay Zekâ Araçlarının Kullanımı rehberi (Şubat 2026): bkz. [01](01-vked-sablonu.md) ve [03](03-uretken-yz-kullanim-politikasi.md).
- Yurt dışına aktarım (KVKK md. 9; 2024 değişikliği): `cloud` mod için [01](01-vked-sablonu.md) Bölüm 5.2. Araştırma notunda geçen Eylül 2026 avukat rehberi, üretken YZ sağlayıcısına dosya yüklemenin aktarım sayılabileceğini söyler **[repo: `regulation_markets_hardware.md`]**; kesin değerlendirme kurum hukuk birimi teyit etmeli.
- İş yerinde izleme: KVKK Kurulu'nun 2026/2035 sayılı ilke kararı (iş yerinde izleme) araştırma notunda anılır **[repo: `regulation_markets_hardware.md`; içeriği doğrulanmadı]**; kullanıcı düzeyinde kayıt/izleme planlayan kurulumlar için hukuk birimi okumalıdır.

## 8. Avrupa Birliği ek notu: yapay zekâ bildirimi

AB Yapay Zekâ Tüzüğü md. 50 (sohbet robotu şeffaflığı) 2 Ağustos 2026'dan beri uygulanır; md. 4 (YZ okuryazarlığı) Şubat 2025'ten beri **[repo: `regulation_markets_hardware.md`]**. Verso panelinde yapay zekâ bildirimi ve üretilmiş içerik işareti bunun için vardır (ADR-0027). Hangi alt fıkranın hangi bileşene karşılık geldiği ve kurumun sağlayıcı/dağıtıcı sıfatı kurum hukuk birimi teyit etmeli. Almanya kurulumu için [07](07-almanya-eki-betrvg.md).
