# Uyum paketi (KVKK ve yapay zekâ şeffaflığı)

> Bu belge hukuki tavsiye değildir; kurumun hukuk birimi ve veri sorumlusu tarafından uyarlanmalıdır.

Danışmanlık ekibinin her kurulumda müşteriye teslim ettiği şablon setidir. Şablonlar Verso'nun gerçek işleme gerçeklerini (ne saklanır, ne saklanmaz, ne kadar kalır, nasıl silinir) kod ve ADR'lerden alınmış hâliyle içerir; kuruma özgü boşluklar `{{YER_TUTUCU}}` ile işaretlidir. Karar [ADR-0027](../adr/0027-yapay-zeka-bildirimi-ve-uyum-paketi.md).

## İçindekiler

| Dosya | Ne için | Kim doldurur |
|---|---|---|
| [01-vked-sablonu.md](01-vked-sablonu.md) | Veri Koruma Etki Değerlendirmesi: Verso'nun işleme gerçekleriyle önceden doldurulmuş | Kurum veri sorumlusu + hukuk, kurulum ekibi teknik bölümleri teyit eder |
| [02-aydinlatma-metni-calisan.md](02-aydinlatma-metni-calisan.md) | Çalışanlara aydınlatma metni (sade dil) | Hukuk birimi |
| [03-uretken-yz-kullanim-politikasi.md](03-uretken-yz-kullanim-politikasi.md) | Kurumsal Üretken YZ Kullanım Politikası | Kurum BT / bilgi güvenliği + hukuk |
| [04-veri-isleyen-sozlesmesi.md](04-veri-isleyen-sozlesmesi.md) | Kurulum ve destek sırasında danışmanlık ekibi için Veri İşleyen Sözleşmesi iskeleti | Hukuk (iki taraf) |
| [05-egress-kaniti.md](05-egress-kaniti.md) | Local modun dışarı veri göndermediğinin gösterilmesi | Kurulum ekibi |
| [06-sektor-notlari.md](06-sektor-notlari.md) | Banka, ödeme kuruluşu, SPK, kamu notları; 7545 olay bildirimi | Kurulum ekibi seçer, hukuk teyit eder |
| [07-almanya-eki-betrvg.md](07-almanya-eki-betrvg.md) | Almanya: işyeri konseyi (BetrVG § 87) notu | Yalnız Almanya kurulumlarında |

## Kurulum başına kullanım

1. **Kapsam belirle.** Kurulumun AI modunu (`local` / `cloud`), sektörünü ve ülkesini yaz. Banka, ödeme kuruluşu, SPK'ya tabi kurum ve kamu kurulumlarında mod yalnız `local` olur ([06](06-sektor-notlari.md)). Almanya'da [07](07-almanya-eki-betrvg.md) eklenir.
2. **Şablonları kopyala.** Bu klasörü müşteriye ait bir klasöre (örneğin `musteri-<kisa-ad>/uyum/`) kopyala. Kurulum ekibinin bu repodaki dosyaları müşteri bilgisiyle düzenlemesi gerekmez; müşteriye özgü doldurulmuş kopyalar bu repoya girmez (`deploy/branding/branding.json` gibi, müşteriye aittir; ADR-0021).
3. **Yer tutucuları doldur.** Çift süslü parantezli hiçbir alan kalmayana kadar. Verso'ya ait olgusal tablolar (saklama süreleri, anahtar adları) şablonda hazırdır; kurulumda bir değer değiştirildiyse (örneğin `VERSO_HISTORY_RETENTION_DAYS`) VKED'deki ve aydınlatma metnindeki sayıyı da değiştir.
4. **Teknik teyit.** [05-egress-kaniti.md](05-egress-kaniti.md)'deki kontrolleri kuruluma karşı çalıştır; çıktıyı VKED ekine koy. Cloud modda egress kanıtı yerine sağlayıcı ve aktarım bölümlerini (VKED 5.2) doldur.
5. **Hukuk teyidi.** Her belge müşterinin hukuk birimi ve veri sorumlusu tarafından uyarlanır. Şablondaki "kurum hukuk birimi teyit etmeli" ifadeleri bu teyidin yapılacağı yerlerdir; teyitten sonra kaldırılır, kalıyorsa belge tamamlanmamıştır.
6. **Panel ve politika birlikte.** Yapay zekâ bildirimi panelde kendiliğinden çıkar (satır ve ilk kullanım kartı; ADR-0027). Kurumun ürün adı `deploy/branding/branding.json` ile verilir; belgelerde `{{ASISTAN_ADI}}` aynı addır. Ad hâl eki almaz (ADR-0021): "{{ASISTAN_ADI}} asistanı" yazın, "{{ASISTAN_ADI}}'nın" yazmayın.
7. **Teslim ve güncelleme.** Teslim edilen sürümü ve tarihi `{{PAKET_SURUMU}}` alanına yaz. Verso'nun bir ADR'si saklama, denetim kaydı, mod ya da egress davranışını değiştirdiğinde bu paket güncellenir (ADR-0027 "Yeniden değerlendirme").

## Neyi kapsamaz

- VERBİS kaydı, yurt dışı aktarım sözleşmesi (cloud mod) ve kurumun kendi saklama ve imha politikası bu pakette yoktur; kurum hukuk birimi hazırlar.
- Verso bir hukuki görüş vermez; belgelerdeki hukuki atıflar kaynağıyla işaretlidir ve her biri teyit içindir.
- Sızma testi, kırmızı takım ve SBOM özetleri bu pakette değildir (araştırma notları bunları ayrı kalemler olarak sayar; takip işi).

## Kaynak işaretleri

Belgelerde hukuki cümlelerin yanındaki işaretler:

- **[repo]**: Verso'nun ADR'sinde ya da araştırma notunda geçen ifade; dayanak dosyası belirtilir.
- **[genel]**: bilinen mevzuat ifadesi, repoda doğrulayan metin yok; kurum hukuk birimi teyit etmeli.
