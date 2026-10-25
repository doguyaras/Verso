# Çalışan aydınlatma metni: {{ASISTAN_ADI}} yapay zekâ asistanı

> Bu belge hukuki tavsiye değildir; kurumun hukuk birimi ve veri sorumlusu tarafından uyarlanmalıdır.

*Sürüm {{AYDINLATMA_SURUMU}}, yürürlük tarihi {{YURURLUK_TARIHI}}. Bu metin, veri sorumlusunun aydınlatma yükümlülüğü kapsamında hazırlanmıştır; yükümlülüğün dayanağı olan madde ve metnin zorunlu içeriği kurum hukuk birimi teyit etmeli [genel]. KVKK Üretken YZ Rehberi, kullanım ve model geliştirme için ayrı aydınlatma ister [repo: `research_notes/.../market_needs.md`]; bu metin yalnız **kullanımı** kapsar. Kurum model geliştirmiyor ya da eğitmiyor.*

## Kimiz?

Veri sorumlusu: **{{KURUM_ADI}}**, adres {{KURUM_ADRESI}}, iletişim {{VERI_SORUMLUSU_ILETISIM}}.

## {{ASISTAN_ADI}} nedir?

{{ASISTAN_ADI}} bir **yapay zekâ asistanıdır; bir kişi değildir.** Yüklediğiniz belgelere soru sorarsınız, cevabı belge adı ve sayfa numarasıyla birlikte verir. Cevaplar hatalı olabilir; önemli bilgiyi kaynağından kontrol edin. Belgelerde cevap yoksa "bulunamadı" der.

## Hangi verilerinizi, neden işliyoruz?

| Veri | Neden | Ne kadar saklanır |
|---|---|---|
| Yüklediğiniz belgeler (dosyanın kendisi, metni, dosya adı, belgeden çıkarılan parçalar) | Size cevap verebilmek | Siz silene kadar {{BELGE_SAKLAMA_ACIKLAMASI}} |
| Sorularınız ve aldığınız cevaplar, konuşma başlığı | Konuşmanıza geri dönebilmeniz | Konuşmanın başlangıcından **{{GECMIS_SAKLAMA_GUN}} gün** (varsayılan 90), ya da siz silene kadar |
| Cevaba verdiğiniz 👍/👎 ve nedeni | Cevap kalitesini ölçmek | Konuşmayla birlikte |
| Kim olduğunuza dair teknik kimlik (kurumun kimlik sisteminden gelen opak kimlik no, rolünüz ve grup üyelikleriniz) | Kişisel belgelerinizi yalnız size, bilgi alanlarının belgelerini yalnız o alana yetkili gruplara göstermek ve yetkiyi kontrol etmek; grup üyelikleri saklanmaz, her istekte oturum anahtarından okunur | Bkz. aşağıda "kayıt tutma" |
| Kim, ne zaman, hangi işlemi yaptı kaydı (belge yükledi/sildi, soru sordu, geri bildirim verdi, konuşma sildi; yöneticiler için metrik görüntüledi, kayıt dışa aktardı) | Veri güvenliği ve hesap verilebilirlik | **{{DENETIM_SAKLAMA_GUN}} gün** (varsayılan 365) |
| Sayısal istatistikler (günlük soru sayısı, bulunan/bulunamayan oranı, süre) | Asistanın işe yarayıp yaramadığını görmek | Kimliksiz sayılardır; süresiz |

**Kayıt tutma hakkında açıkça:** Denetim kaydı soru ve cevap *metninizi*, belge adınızı ya da konuşma başlığınızı **içermez**; yalnız kimlik numaraları ve sabit işlem adlarını içerir. Yine de kimlik numaranız, kurumun kimlik sisteminde sizinle eşleştirilebilir; bu yüzden kişisel veridir ve süre sonunda silinir. "Geçmişimi sil" demeniz bu kayıtları silmez, çünkü onlarda içerik yoktur ve kayıt güvenlik amacıyla tutulur.

## Yönetici ne görür, ne görmez?

- Yöneticiler **toplu istatistikleri** görür (kaç soru soruldu, kaç kişi kullandı, hangi oranda kaynak bulundu). Başkasının sorusunu ya da cevabını okuyan bir ekran yoktur.
- {{CEVAPLANAMAYAN_SORU_AYDINLATMASI}} *(Bu kurulumda "cevaplanamayan sorular" listesi açık ise şunu yazın: "Bilgi tabanındaki boşlukları bulmak amacıyla, belgelerde cevabı bulunamayan sorular, en az 3 farklı çalışan tarafından sorulmuşsa, yazılışı normalleştirilmiş hâliyle ve kimlik göstermeden yöneticiye listelenir." Kapalıysa bu maddeyi silin.)*
- {{BELGE_ADI_AYDINLATMASI}} *(Belge adı görünümü açık ise: "Yöneticiler hangi belgelerin kaç kez kaynak gösterildiğini dosya adlarıyla görebilir; belgenin sahibini görmez." Kapalıysa silin.)*
- Yönetici işlemleri de kayıt altındadır.

## Verileriniz nerede işleniyor? Yurt dışına gidiyor mu?

{{VERI_AKISI_METNI}} *(`local` mod için örnek: "Soru, belge ve cevaplar kurumun kendi sunucularında işlenir; kurum dışına ya da yurt dışına gönderilmez." `cloud` mod için örnek: "Sorunuz (aynı konuşmadaki bir takip sorusunda ayrıca konuşmanın son iki sorusu ve son cevabın başı) ve cevaba yardımcı olan en fazla 5 belge pasajı, cevabı üretmek üzere {{BULUT_SAGLAYICI}} adlı hizmet sağlayıcıya, {{SAGLAYICI_KONUMU}} konumuna iletilir. Belgenin tamamı ve dosya adı iletilmez. Aktarımın hukuki dayanağı: {{AKTARIM_MEKANIZMASI}}." Hangisinin geçerli olduğunu yalnızca bir tanesini bırakarak yazın.)*

## Verilerinizi hangi hukuki sebeple işliyoruz?

{{HUKUKI_SEBEP_METNI}} *(Girdi, çıktı ve belgeler için ayrı analiz yapılır; KVKK Üretken YZ Rehberi, 24.11.2025 [repo]. İlgili madde numaralarını kurum hukuk birimi yazmalı ve teyit etmeli.)*

## Haklarınız ve nasıl kullanırsınız?

KVKK md. 11 kapsamındaki haklarınız, yapay zekâ cevaplarını ve sorularınıza eklenen içerikleri de kapsar [repo: KVKK Üretken YZ Rehberi]. Başvuru: {{BASVURU_YOLU}}; yanıt süresi {{BASVURU_YANIT_SURESI}} (kanunda 30 gün öngörülür [repo: `security-rules.md` bölüm 5]; kurum hukuk birimi teyit etmeli).

Kendi başınıza şunları hemen yapabilirsiniz:

- **Sorularınızı ve cevaplarınızı görmek:** menüden "Geçmiş".
- **Bir konuşmayı ya da tüm geçmişinizi silmek:** "Geçmiş" ekranı (konuşmayı sil / tümünü sil).
- **Belgenizi silmek:** "Belgeler" ekranı. Dosya, metni ve belgeden çıkarılan parçalar birlikte silinir. Bir belgeyi sildiğinizde, o belgeden alıntı yapan eski cevap metni, siz geçmişi silene ya da saklama süresi dolana kadar konuşmanızda kalır; belge kişisel veri içeriyorsa yönetici bu cevapları "kaldırıldı" notuyla değiştirebilir.

Başvurunuz üzerine yönetici şunları yapabilir:

- **Hakkınızda tutulanların dökümü:** sorularınız ve cevaplarınız, puanlarınız, yüklediğiniz belgelerin adları ve durumları, kayıt defterinde sizin yaptığınız işlemlerin listesi (tek bir dosya halinde).
- **Silme:** geçmişiniz, puanlarınız ve kişisel belgeleriniz (dosya, metin, parçalar) birlikte silinir. Ortak bilgi alanlarına yüklediğiniz belgeler kurumundur; onlar için kurum ayrıca karar verir. İşlem kaydı (kim, ne zaman, hangi işlem) silinmez, süresi dolunca silinir; başvurunuzun ele alındığı da bu kayda yazılır.
- Belgelerin içinde adınızın geçip geçmediği otomatik aranamaz; böyle bir başvuruda kurum, belgeleri tanıyan birimle ilgili belgeleri belirler.
- **Bir cevabı kaydetmek:** cevabın altındaki ".md indir" veya "Kopyala".

Silinen veri, yedeklerde {{YEDEK_SAKLAMA_GUN}} gün daha kalabilir ve yedek geri yüklenirse süresi dolanlar yeniden silinir.

## Bilmeniz gerekenler

- {{ASISTAN_ADI}} yapay zekâdır; cevaplar yanlış ya da eksik olabilir. Kararlarınızı yalnızca asistana dayandırmayın: [Kurumsal Üretken YZ Kullanım Politikası]({{POLITIKA_BAGLANTISI}}).
- Soruya gerekmedikçe kişisel veri (başkasının adı, TC kimlik no, sağlık bilgisi vb.) yazmayın.
- Tarayıcınızda yalnız tercihler (tema, dil) tutulur; sorularınız ve cevaplarınız tarayıcıda saklanmaz.
- Bu metin değişirse yeni sürüm {{DUYURU_YOLU}} ile duyurulur.
