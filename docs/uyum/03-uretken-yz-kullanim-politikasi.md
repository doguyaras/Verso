# Kurumsal Üretken Yapay Zekâ Kullanım Politikası

> Bu belge hukuki tavsiye değildir; kurumun hukuk birimi ve veri sorumlusu tarafından uyarlanmalıdır.

| Alan | Değer |
|---|---|
| Kurum | {{KURUM_ADI}} |
| Sürüm / yürürlük | {{POLITIKA_SURUMU}} / {{YURURLUK_TARIHI}} |
| Politika sahibi | {{POLITIKA_SAHIBI}} (BT / bilgi güvenliği) |
| Onay | {{ONAYLAYAN}} |
| Kapsam | {{KURUM_ADI}} çalışanları, yüklenicileri ve üretken yapay zekâ ile iş yapan herkes |

**Dayanak.** KVKK "İş Yerlerinde Üretken Yapay Zekâ Araçlarının Kullanımı" rehberi (Şubat 2026, 5 Mart 2026'da yayımlandı) şunları önerir: yazılı politika, yalnızca onaylı araçlar, "görev ve ihtiyaç temelli" erişim, kurum dışı platformların ağ düzeyinde engellenmesi, çıktıların insan denetimi, "hangi verilerin hangi amaçla kullanıldığının" yeniden kurulabileceği kayıt; "gölge yapay zekâ" yasağının tek başına gerçekçi olmadığını belirtir **[repo: `research_notes/.../market_needs.md`]**. Bu şablonun maddeleri o başlıklara göre sıralanmıştır; rehberin metni birebir alınmamıştır, kurum hukuk birimi güncel rehberle karşılaştırmalı. Yapay zekâ okuryazarlığı (EU AI Act md. 4, Şubat 2025'ten beri) AB'de faaliyet gösteren kurumlar için Bölüm 8'e ilişkindir **[repo: `regulation_markets_hardware.md`]**.

## 1. Onaylı araç

- Kurumun onayladığı üretken yapay zekâ aracı: **{{ASISTAN_ADI}}** (Verso). Çalışma modu: {{AI_MODU}}.
- `local` modda sorularınız, belgeleriniz ve cevaplar kurumun sunucularında kalır. `cloud` modda soru ve en fazla 5 belge pasajı {{BULUT_SAGLAYICI}} sağlayıcısına gider ([02](02-aydinlatma-metni-calisan.md)).
- **Başka üretken YZ araçları** (kişisel hesaplarla halka açık sohbet botları, tarayıcı eklentileri, yapay zekâ özellikli not alma uygulamaları): {{DIGER_ARACLAR_KURALI}}. Yasak tek başına yeterli kabul edilmez; kurumun ihtiyacını onaylı araç karşılamalıdır. Onaylı listeye yeni araç ekleme süreci: {{ARAC_ONAY_SURECI}}.
- Kurumun ağ düzeyindeki engel/izin listesi: {{AG_ENGEL_LISTESI}}.

## 2. Görev ve ihtiyaç temelli erişim

- Asistana erişim, kurumun kimlik sisteminde ({{IDP_ADI}}) şu gruplara/rollere verilir: {{ERISIM_GRUPLARI}}. Erişim verilme ve geri alınma süreci: {{ERISIM_SURECI}}.
- Rol ayrımı: **kullanıcı** (belge yükler, sorar, kendi geçmişini yönetir); **yönetici** (toplu metrikleri ve denetim kaydını görür). Yönetici rolü yalnız {{YONETICI_ROLU_SAHIPLERI}} kişide olur; işten ayrılma ve görev değişikliğinde gözden geçirilir.
- Yüklenecek belge türleri: {{IZINLI_BELGE_TURLERI}}. Yüklenmemesi gerekenler: {{YASAK_BELGE_TURLERI}}.
- Her belge yalnız yükleyenin hesabına aittir; başkaları o belgeden cevap alamaz. Ortak bilgi için belgeyi yükleyecek kişi: {{ORTAK_BELGE_SORUMLUSU}}.

## 3. Soruya ne yazılır (kişisel veri)

- **Gerekmedikçe kişisel veri yazmayın.** Soruyu kişi adı olmadan, rol ya da dosya adıyla kurun: "Ahmet Yılmaz'ın izin hakkı kaç gün?" yerine "Yıllık izin hakkı kıdeme göre nasıl hesaplanır?".
- Gerekiyorsa yalnız o iş için gerekli en az veriyi yazın.
- TC kimlik numarası, banka/kart bilgisi, parola ve gizli anahtar sorulara **hiçbir zaman** yazılmaz.
- Yüklediğiniz belgede başkasına ait kişisel veri varsa, o belgeyi yükleme yetkiniz ve amacınız olmalıdır. Emin değilseniz {{VERI_KORUMA_IRTIBAT}} ile görüşün.

## 4. Özel nitelikli kişisel veri

Sağlık, biyometrik, cinsel hayat, sendika üyeliği, ceza mahkûmiyeti ve güvenlik tedbirleri, din, mezhep, ırk gibi **özel nitelikli** veriler için ayrı kural: {{OZEL_NITELIKLI_KURAL}} (örneğin "asistana yüklenmez" ya da "yalnız {{YETKILI_BIRIM}} yükler ve yalnız `local` modda"). Özel nitelikli veriyle çalışan kurulumda ek tedbirler (kripto, anahtarın ayrı ortamda tutulması, işlemlerin kaydı, süreli yetki, periyodik gözden geçirme, iki faktörlü doğrulama) uygulanır; kaynak KVKK Kurul 2018/10 kararı **[repo: `market_needs.md`; karar metni repoda yok, kurum hukuk birimi teyit etmeli]**. `cloud` modda özel nitelikli veri **sorulmaz ve yüklenmez**.

## 5. Cevapların doğrulanması (insan gözetimi)

- Cevap yapay zekâ ürünüdür; hata yapabilir. Her cevap kaynak (belge adı + sayfa) gösterir. **Önemli bir iş, finansal, hukuki, insan kaynakları ya da güvenlik kararından önce kaynağı açıp okuyun.**
- "Bu cevap bir kaynağa bağlanamadı" uyarısı çıkan cevabı kaynağını bulmadan kullanmayın.
- "Belgelerde bulunamadı" cevabı, bilginin kurumda olmadığı anlamına gelmez; yalnız yüklü belgelerde bulunamadığı anlamına gelir.
- Asistan, kişiler hakkında **tek başına karar vermek, puanlamak, sıralamak ya da izlemek** için kullanılmaz (işe alım, performans değerlendirme, disiplin vb.): {{YASAK_KULLANIMLAR}}. Bu tür kullanım ayrı bir risk değerlendirmesi ve hukuk onayı gerektirir.
- Cevabı dışarıya (müşteri, resmi kurum) iletirken yapay zekâ yardımı kullandığınızı belirtme kuralı: {{DIS_ILETISIM_KURALI}}.

## 6. Hataların ve olayların bildirilmesi

| Durum | Ne yapılır | Kime |
|---|---|---|
| Cevap yanlış ya da yanıltıcı | Cevabın altındaki 👎 ile nedenini seçin (örneğin "yanlış kaynak" hangi atıf) | Panel (puan ve gerekçe konuşmayla birlikte saklanır; ADR-0023) |
| Asistan başkasına ait olması gereken bir belge/bilgi gösterdi | Hemen bildirin, ekran görüntüsü paylaşmayın (kişisel veri içerebilir); belge adı ve saat yeterli | {{GUVENLIK_IRTIBAT}} |
| Kişisel veri yanlış kişiye gitti / yanlış belge yüklendi | Belgeyi silin ve bildirin | {{VERI_KORUMA_IRTIBAT}} |
| Asistan çalışmıyor / anormal davranıyor | Bildirin | {{BT_DESTEK}} |
| Güvenlik olayı şüphesi | Hemen bildirin | {{GUVENLIK_IRTIBAT}}; sektörünüze göre olay bildirimi için [06](06-sektor-notlari.md) |

Veri ihlali bildirimi (Kurul'a ve ilgili kişilere) süreleri ve yöntemi kurum hukuk birimi tarafından işletilir: {{IHLAL_BILDIRIM_SURECI}} [genel; süre ve usul kurum hukuk birimi teyit etmeli].

## 7. Kayıtlar ve izleme

- Sistem, kimin hangi işlemi ne zaman yaptığını kaydeder (belge yükleme/silme, soru, geri bildirim, konuşma silme, yönetici işlemleri). Kayıt soru ve cevap *metnini* içermez, süresi {{DENETIM_SAKLAMA_GUN}} gündür. Soru ve cevap metni konuşma geçmişinizde {{GECMIS_SAKLAMA_GUN}} gün kalır ve yalnız size görünür.
- Kayıtlar çalışan performansını ya da tek tek kullanıcıların kullanım alışkanlıklarını değerlendirmek için kullanılmaz; kullanım yalnız güvenlik soruşturması, hukuki yükümlülük ve toplulaştırılmış istatistik amacıyla ve {{KAYIT_ERISIM_YETKISI}} onayıyla incelenir. Almanya'da [07](07-almanya-eki-betrvg.md) uygulanır.

## 8. Eğitim ve farkındalık

- Her yeni kullanıcı bu politikayı ve [aydınlatma metnini](02-aydinlatma-metni-calisan.md) okur ve onaylar: {{ONAY_YONTEMI}}.
- Yılda en az bir kez kısa eğitim: yapay zekânın sınırları (hata, uydurma), kaynak doğrulama, kişisel veri ve özel nitelikli veri, olay bildirimi. Eğitim sahibi: {{EGITIM_SAHIBI}}. AB'de faaliyet gösteren kurumlarda bu eğitim yapay zekâ okuryazarlığı yükümlülüğünü (EU AI Act md. 4) karşılamaya yardımcı olabilir; kapsamı kurum hukuk birimi teyit etmeli.

## 9. Politikanın ihlali ve gözden geçirme

- İhlalin sonuçları: {{IHLAL_SONUCLARI}} (kurumun disiplin yönetmeliğine göre).
- Politika yılda bir ve şu durumlarda gözden geçirilir: mod değişimi, yeni araç, mevzuat ya da rehber değişimi, olay sonrası. Sorumlu: {{POLITIKA_SAHIBI}}.
