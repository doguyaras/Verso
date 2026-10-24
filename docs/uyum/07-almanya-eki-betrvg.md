# Almanya eki: işyeri konseyi (BetrVG § 87) notu

> Bu belge hukuki tavsiye değildir; kurumun hukuk birimi ve veri sorumlusu tarafından uyarlanmalıdır.

Yalnız Almanya'daki kurulumlar için. Kurulum ekibi bu notu müşterinin hukuk birimi, veri koruma görevlisi (DSB) ve işyeri konseyi (Betriebsrat) ile görüşmeye hazırlık için kullanır. Alman iş hukukuna ilişkin hiçbir cümle bu repoda doğrulanmamıştır; aşağıdaki hukuki çerçeve araştırma notundan (`research_notes/Verso kapsamlı küresel araştırma/europe_de_fr.md`) alınmıştır ve **Alman hukukçu tarafından teyit edilmelidir** **[repo; birincil kaynak doğrulanmadı]**.

## 1. Hukuki çerçeve (araştırma notundaki hâliyle)

- BetrVG § 87 Abs. 1 Nr. 6: teknik cihazların "davranış ve performansı izlemeye elverişli" olması işyeri konseyine ortak karar (Mitbestimmung) hakkı verir; belirleyici olan "Eignung zur Überwachung"dur. Pratik özet: BV ihtiyacı genelde vardır; ancak sistem **kullanıcıya bağlı değerlendirme (nutzerbezogene Auswertung) üretmiyorsa** BV gerekmeyebilir **[repo: europe_de_fr.md; ki-syndikat, CMS bildirimi kaynaklı]**.
- CMS: "kim ne söyledi" türü bileşenler kapsamdadır; kalıcı BV öncesi denetim hakları içeren bir "Duldungsvereinbarung" önerilir; yüksek mahkeme içtihadı eksiktir. Taylor Wessing § 90, § 80 Abs. 2 ve olası § 111'i ekler. § 88 gönüllü BV'ye izin verir **[repo]**.
- AB Yapay Zekâ Tüzüğü md. 50 (sohbet robotu bildirimi) 2 Ağustos 2026'dan beri uygulanır; md. 26 yükümlülükleri § 87 sürecine sığar **[repo]**. Verso panelinde yapay zekâ bildirimi vardır (ADR-0027).
- Karar verici faktörler olarak alıcıların saydığı özellikler: kaynak gösterimi, hibrit arama, "kullanıcıya bağlı değerlendirme yok", çevrimdışı çalışma, istemlerle eğitim yok **[repo]**.

## 2. Verso hakkında dürüst durum: ne "kullanıcı düzeyinde" değildir, ne öyledir

Konsey ile konuşmada abartı yapılmaz. Verso'nun kayıtları ikiye ayrılır:

**Kullanıcıya bağlı olmayan (toplulaştırılmış):**

| Ne | Nerede | Not |
|---|---|---|
| Günlük sayaçlar: soru sayısı, bulundu/bulunamadı/kaynaksız oranları, süre kovaları, mod, geri bildirim sayısı | `qa.usage_daily*` | Hesap ve metin yok (ADR-0023) |
| Yönetici panosu | `GET /v1/admin/metrics` | Yanıtta hesap kimliği hiç yoktur; aktif kullanıcı yalnız **sayı**dır |
| Cevaplanamayan sorular | `verso.qa.metrics.show-unanswered-questions` **varsayılan kapalı** | Açıksa yalnız **k ≥ 3 farklı hesabın** sorduğu normalleştirilmiş soru; k, 3'ün altına indirilemez; eşik sorgunun içindedir |
| Belge adları (en çok atıf alan belgeler) | `verso.qa.metrics.show-document-names` **varsayılan kapalı** | Açıksa belge adı görünür, **sahip hiçbir durumda görünmez** |

**Kullanıcıya bağlanabilen (konsey için kritik):**

| Ne | Nerede | Kim görür | Not |
|---|---|---|---|
| Kullanıcının kendi soru/cevap geçmişi | `qa.turn`, `qa.conversation` | Yalnız kullanıcının kendisi (API'de başkasının geçmişini veren uç yok) | 90 gün; kullanıcı siler. DB yöneticisi teknik olarak okuyabilir |
| Hesap × gün satırı | `qa.active_account_day` | Yönetici yalnız sayıyı görür | Geçmişle birlikte silinir |
| **Denetim kaydı:** kim (`sub`), hangi eylem, ne zaman, hangi belge/tur/konuşma kimliği | `audit.event` | `verso-admin`: sayfalı sorgu ve **CSV dışa aktarım** (`GET /v1/admin/audit`, `/export`) | 365 gün. İçerik yok ama **kullanıcı düzeyindedir**; `sub` IdP'de kişiye bağlanır (ADR-0026) |

**Sonuç (konsey için):** Verso'nun sayaçları ve panosu kullanıcıya bağlı değerlendirme üretmez; ancak **denetim kaydı "kim ne zaman soru sordu / belge yükledi" bilgisini kullanıcı düzeyinde tutar ve yöneticiye dışa aktarılabilir kılar**. Bu, "izlemeye elverişlilik" tartışmasını doğrudan açar. "Verso kullanıcıya bağlı analiz yapmaz" cümlesi **yalnız panoya ve sayaçlara** doğrudur, denetim kaydına değil; sözleşmeye ya da konseye bu cümleyi kayıt ayrımı yapmadan yazmayın.

## 3. Önerilen yaklaşım (kurulum ekibi, konsey ve hukuk ile görüşülecek)

1. **BV ya da Duldungsvereinbarung'a girecek hususlar:** (a) kullanım amacı: bilgi erişimi, güvenlik, hesap verilebilirlik; **performans ve davranış değerlendirmesi hariç**; (b) denetim kaydına erişim: yalnız adlandırılmış güvenlik/uyum rolü, yazılı talep ve konsey bilgilendirmesiyle, `verso-admin` rolü kişi sayısı sınırlı; (c) `AUDIT_EXPORTED` ve `ADMIN_METRICS_VIEWED` kayıtlarının konsey tarafından periyodik gözden geçirilmesi (bu eylemler zaten kaydedilir; ADR-0026); (d) saklama süreleri: `VERSO_HISTORY_RETENTION_DAYS` ve `VERSO_AUDIT_RETENTION_DAYS` (denetim için alt sınır 30 gün, ADR-0026) konseyle uzlaşılan değere ayarlanır; (e) cevaplanamayan soru listesi ve belge adı görünümü kapalı kalır ya da yazılı uzlaşıyla açılır; (f) yönetici panosundaki sayıların bireysel değerlendirmede kullanılmayacağı taahhüdü.
2. **Teknik taahhütler (kodla doğrulanmış):** metrik uçlarında hesap kimliği yoktur; k-anonimlik eşiği sorgunun içindedir; sahip adı hiçbir ekranda gösterilmez; denetim kaydı yalnız INSERT/SELECT'tir ve içerik taşımaz; Verso kullanıcı başına "kullanım skoru", sıralama ya da karşılaştırma üretmez. Bunlar [01](01-vked-sablonu.md) Bölüm 7-8'dedir.
3. **Yapılmayacak şey:** "kullanıcıya bağlı analiz yok" ifadesini denetim kaydı hariç tutmadan söylemek; k-anonimlik eşiğini 3'ün altına indirmeye çalışmak (açılış kontrolü reddeder); sahte hesapla k eşiğini doldurmak (k-anonimlik buna karşı korumaz; hesap yönetimi kurumdadır; ADR-0023).
4. **DSB ve DSGVO:** DSGVO tarafı (hukuki dayanak, DPIA = VKED'in Almanya karşılığı, işleyen sözleşmesi) bu pakette KVKK diliyle yazılmıştır; Alman kurulumunda [01](01-vked-sablonu.md) ve [04](04-veri-isleyen-sozlesmesi.md) DSGVO kavramlarına göre uyarlanır: {{DSB_UYARLAMASI}}. DSGVO maddeleri bu repoda doğrulanmamıştır, DSB teyit etmeli.
5. **Yapay zekâ bildirimi ve eğitim:** panel bildirimi ve [03](03-uretken-yz-kullanim-politikasi.md) Bölüm 8 (YZ okuryazarlığı) konsey görüşmesinde sunulur.
6. **Çevrimdışı çalışma:** `local` mod ve egress kanıtı ([05](05-egress-kaniti.md)) konseyin ve DSB'nin sık sorduğu "veri dışarı çıkıyor mu?" sorusunu yanıtlar; ABD sağlayıcılarına (CLOUD Act) ilişkin endişe `cloud` modda ayrıca değerlendirilir **[repo: europe_de_fr.md]**.

## 4. Konseye verilecek belge seti

[01](01-vked-sablonu.md) Bölüm 3-4, 7-8; bu belgenin Bölüm 2 tablosu; [03](03-uretken-yz-kullanim-politikasi.md); [05](05-egress-kaniti.md) kanıt çıktıları. Konseyin sorabileceği ve kurulum ekibinin cevaplayabileceği sorular: Hangi kayıtlar kişiye bağlanabilir? (Bölüm 2.) Kim erişir? (`verso-admin`; {{YONETICI_ROLU_SAHIPLERI}}.) Ne kadar saklanır? (VKED Bölüm 3.) Yapılandırma değişirse kim haberdar olur? (Yazılı talimat; [04](04-veri-isleyen-sozlesmesi.md) 5.3.)
