# ADR-0027: Yapay zekâ etkileşim bildirimi (AB Yapay Zekâ Tüzüğü md. 50) ve Türkçe KVKK uyum paketi

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras (kurumsal yol haritası, `market_needs.md` madde 8 "KVKK uyum paketi + üründe 'yapay zekâyla konuşuyorsunuz' bildirimi"); uygulama ayrıntıları bu ADR'de
- **İlgili eşik (referans Bölüm 24):** yok (sunum katmanı ve dokümantasyon). Yeniden değerlendirme koşulu aşağıda.
- **Genişlettiği:** ADR-0015 (panel, CSP, Trusted Types), ADR-0021 (ürün adı), ADR-0024 (cevap ekranı, Markdown dışa aktarımı); ADR-0023 ve ADR-0026 (saklama) paketin olgu kaynağıdır.

## Bağlam

- AB Yapay Zekâ Tüzüğü md. 50 (sohbet robotu şeffaflığı) 2 Ağustos 2026'dan beri uygulanır; md. 4 (YZ okuryazarlığı) Şubat 2025'ten beri (`regulation_markets_hardware.md`). Hangi alt fıkranın hangi önleme karşılık geldiği ve kurumun sağlayıcı/dağıtıcı sıfatı hukuk birimi tarafından teyit edilir; bu ADR madde alt fıkrası numarası iddia etmez.
- Panelde bugün yalnız composer altında "X hata yapabilir; cevabı kaynağından doğrulayın." satırı vardı (ADR-0024). Karşıdakinin **yapay zekâ** olduğunu söylemiyordu; üretilmiş cevabı makinece okunur biçimde işaretleyen bir şey de yoktu.
- KVKK tarafında (Üretken YZ Rehberi, 24.11.2025; İş Yerlerinde Üretken YZ Araçlarının Kullanımı, Mart 2026) alıcılar yazılı politika, çalışan aydınlatması, VKED ve olay süreci ister. Rakiplerde Türkçe VKED/politika şablonu yoktur (`market_needs.md`). Verso'nun işleme gerçekleri (ne saklanır, ne kadar, nasıl silinir) ADR-0011, 0013, 0023 ve 0026'da dağınık durur; her kurulumda sıfırdan yazılması hataya açıktır.
- Kısıtlar (değişmedi): derleme adımı yok; CSP `default-src 'none'` … `require-trusted-types-for 'script'; trusted-types 'none'`; `innerHTML` ve satır içi stil yok (ADR-0015). `sessionStorage` yalnız tercihler tutar; soru, cevap ve token orada durmaz (ADR-0015, 0024, 0025; `store.js`).

## Seçenekler

**Panel bildirimi:**

| Seçenek | Artı | Eksi |
|---|---|---|
| **A. Composer altında kalıcı satır + ilk kullanımda sayfa içi kapatılabilir kart + cevap öğesinde işaret + Markdown altbilgisi** | Her etkileşimde görünür; ilk kullanımda belirgin; tarayıcı iletişim kutusu yok; yeni CSP yönergesi yok | Kart bir tercih bayrağı ister |
| B. Yalnız kalıcı satır | Durum yok | İlk kullanımda fark edilmeyebilir |
| C. Giriş öncesi modal | Kaçırılmaz | Modal erişilebilirlik yükü; `window.alert` yasak; akışı bölen sürtünme |

**Bayrağın saklanması:**

| Seçenek | Artı | Eksi |
|---|---|---|
| **A. Var olan `verso.panel.onboarding` tercih anahtarına bir `aiNoticeSeen` boole alanı** | Yeni depolama anahtarı yok; "yalnız tercihler" kuralı korunur; sekme oturumuyla sınırlı | Depo engelliyse bildirim her sayfa yüklemesinde çıkar (kabul: güvenli yön) |
| B. Yeni `sessionStorage` anahtarı | Ayrık | `privacy` testinin anahtar listesi büyür; gerekçesiz |
| C. Yalnız bellek | Hiç depo | Her yenilemede çıkar |
| D. `localStorage` | Kalıcı | Kural dışı (token ve içerik dışında bile "yalnız oturum" tercihi: ADR-0015) |

## Karar

### 1. Panel (`panel/js/disclosure.js`, `screens/ask.js`, `answer.js`, `store.js`)

- **Kalıcı satır.** Composer'ın `meta` satırı (giriş alanının hemen altı) şunu gösterir: "Yapay zekâ ile yazışıyorsunuz ({{productName}}); cevaplar hatalı olabilir, kaynağını kontrol edin." Eski "X hata yapabilir…" satırının yerini aldı. Ürün adı marka dosyasından gelir ve hâl eki almaz (ADR-0021): ad parantez içindedir.
- **İlk kullanım kartı.** Soru ekranının akışının başında, `notice info` görünümlü, `role="note"` bir kart: başlık "Yapay zekâ ile yazışıyorsunuz", gövde ("X bir yapay zekâ asistanıdır, bir kişi değildir… önemli bilgileri kaynağından kontrol edin. Gerekmedikçe soruya kişisel veri yazmayın."), "Anladım" düğmesi. `window.alert`, `confirm`, modal ve `<dialog>` kullanılmaz. Düğme kartı kaldırır, odağı soru kutusuna verir.
- **Görüldü bayrağı.** `verso.panel.onboarding` JSON'una `aiNoticeSeen` (boole) eklendi. Yeni depo anahtarı yok; `privacy` testi anahtar listesini (`theme`, `rail`, `onboarding`, `lang`) aynen doğrular. Bayrak yalnız bir tercih biçimidir (içerik değil), sekme oturumuyla sınırlıdır: yeni tarayıcı oturumunda kart yeniden çıkar. Depo engelliyse kart her açılışta çıkar ve hiçbir hata atılmaz.
- **Makine tarafından okunur işaret.** Üretilmiş bir cevabın `.answer` öğesi `data-ai-generated="true"` taşır. Sabit "belgelerde bulunamadı" metni model çıktısı olmadığı için taşımaz; kaynaksız (`UNCITED`) cevap üretilmiş olduğundan taşır.
- **Markdown.** `answerMarkdown` hem dosya hem pano çıktısını "Yapay zekâ ile üretildi · {{productName}}" satırıyla bitirir (ürün adı verilmemişse yalnız "Yapay zekâ ile üretildi"). Ad, diğer metinler gibi `escapeMarkdown`'dan geçer: marka dosyasındaki bir ad bağlantı ya da HTML sokamaz.
- **Değişmeyenler.** CSP ve Trusted Types değişmedi (testle doğrulanır); `innerHTML` ve satır içi stil yok; kart mevcut `.notice` stillerini kullanır, yeni CSS kuralı eklenmedi.
- **Test:** `scripts/panel.test.mjs` ("disclosure:" testleri).

### 2. Uyum paketi (`docs/uyum/`)

- Türkçe şablonlar: `README.md` (kurulum başına kullanım), `01-vked-sablonu.md`, `02-aydinlatma-metni-calisan.md`, `03-uretken-yz-kullanim-politikasi.md`, `04-veri-isleyen-sozlesmesi.md`, `05-egress-kaniti.md`, `06-sektor-notlari.md`, `07-almanya-eki-betrvg.md`.
- **Biçim kuralları:** her dosya "Bu belge hukuki tavsiye değildir; kurumun hukuk birimi ve veri sorumlusu tarafından uyarlanmalıdır." satırıyla açılır; kuruma özgü alanlar `{{YER_TUTUCU}}`'dur; Verso'ya ait olgusal bölümler (saklama süreleri, anahtar adları, uç adları) kodla ve ADR'lerle birebir eşleşir; hukuki cümleler kaynağını taşır (**[repo]**: ADR/araştırma notu; **[genel]**: repoda doğrulayan metin yok) ve emin olunmayan her yerde "kurum hukuk birimi teyit etmeli" yazar. Madde numarası uydurulmaz; repoda geçmeyen maddeler (ör. KVKK md. 5, 6, 10) yalnız [genel] işaretiyle ve teyit uyarısıyla anılır.
- **Olgu kaynağı tek yerde:** VKED'in envanter tablosu ADR-0011/0023/0026'yı özetler, yeniden karar vermez. Bu ADR'ler bir saklama süresini, anahtarı ya da mod davranışını değiştirdiğinde paket de güncellenir (aşağıda).
- **Dürüstlük kararları (bilinçli):**
  - VKED "açık noktalar" bölümü kodda bugün olmayanı söyler: belgelerde zaman bazlı imha yok, hesap silme akışı yok, silinen belgeden alıntı yapan cevap kalır (ADR-0023), k-anonimlik sahte hesaplara karşı korumaz.
  - Almanya eki "kullanıcıya bağlı analiz yok" cümlesini yalnız panoya ve sayaçlara bağlar; **denetim kaydı kullanıcı düzeyindedir** (`sub`, yönetici dışa aktarımı) ve konseye öyle sunulur.
  - Egress kanıtı yalnız var olan kontrolleri ve betiği referans verir; sınırlarını (model indirme adımı, IdP erişimi, host düzeyi ayarlar) yazar ve en güçlü kanıtın kurumun bağımsız ağ gözlemi olduğunu söyler.
- **Çalıştırma:** pakette hiçbir betik yok; `README.md` kurulum ekibinin adımlarını anlatır.
- **Kurulum başına kopya:** doldurulmuş kopyalar müşteriye aittir ve bu repoya girmez (ADR-0021 deseni).

### 3. Sınırlar ve kapsam dışı

- Paket hukuki tavsiye vermez; her belge kurum hukuk birimince uyarlanır.
- Sektör notları araştırma notlarına dayanır; birincil kaynaklara erişilemeyen kalemler (DDO Rehber sürümü, 2025-2026 BDDK değişiklikleri, 7545 ikincil mevzuatı) açıkça "doğrulanmadı"dır.
- Kapsam dışı (takip işleri): VERBİS, yurt dışı aktarım sözleşmesi, kurumun saklama ve imha politikası, sızma testi / kırmızı takım / SBOM özetleri, kontrol eşleme tablosu (KVKK Güvenlik Rehberi, ISO 27001/42001), BDDK dış hizmet eki, kapalı ağ çevrimdışı kurulum prosedürü, egress kanıtı için panel ekranı ("egress kanıt ekranı" yol haritası maddesi).
- Panelde İngilizce (`lang: en`) metin: bildirim yalnız Türkçe yazıldı. Tanıtım ekranı dışındaki ekranlar zaten Türkçedir (ADR-0021).

## Sonuçlar

- **Olumlu:**
  - Her etkileşimde ve ilk kullanımda yapay zekâ olduğu söylenir; üretilmiş cevap hem DOM'da hem dışa aktarımda işaretlidir.
  - Kurulum ekibi, her müşteriye aynı doğrulanmış olgularla doldurulmuş bir VKED, aydınlatma metni ve politika taslağı verir; satış kapısı "KVKK uyum paketi" kapanır (yol haritası madde 8).
  - Yeni depo anahtarı, CSP değişikliği ve yeni CSS yok.
- **Olumsuz / kabul edilen risk:**
  - **Şablonlar eskir.** Saklama süresi, mod ya da yönetici görünürlüğü değişirse paket elle güncellenir; otomatik doğrulama yok. Hafifletme: dayanak dosyaları VKED Ek A'da listeli ve `scripts/uyum-paketi.test.js` (CI'da koşar) sayıları (90, 365, 7, k = 3, top-k, pasaj sınırı), ayar adlarını, uç tabanlarını, dosya yollarını, bağlantıları ve yer tutucu biçimini yapılandırmayla karşılaştırır. Test hukuki cümleleri doğrulayamaz.
  - **Bildirim kapatılınca kalıcı satır tek göstergedir** ve ekran okuyucu kullanıcıları için kartın duyurusu `role="note"` ile sınırlıdır (anons yok, bilinçli: ekran her açıldığında okunmasın).
  - **Hukuki içerik teyitsizdir.** Madde numaraları araştırma notlarına dayanır; birçoğu birincil kaynakla doğrulanmadı. Pakette bu açıkça işaretlidir.
  - **Bayrak sekme oturumuyla sınırlıdır:** aynı kullanıcı yeni sekmede kartı yeniden görür. Bu AB md. 50 bakımından güvenli yöndür.
- **Yeniden değerlendirme koşulları:**
  - Verso başka dillerde kullanıma açılırsa (`lang: en`): bildirim çevirisi.
  - Panel dışı bir istemci (mobil, API tüketicisi) soru-cevap sunarsa: işaretin API yanıtına taşınması (`X-AI-Generated` benzeri başlık ya da alan) ayrı ADR.
  - Geçmiş prompt'a girerse (takip soruları), mod değişirse ya da denetim kaydının içeriği genişlerse: VKED ve politika yeniden yazılır.
  - AB md. 50 uygulama rehberleri / Kurul rehberleri değişirse paket gözden geçirilir.
- **Etkilenen dosyalar:** `panel/js/disclosure.js` (yeni), `panel/js/screens/ask.js`, `panel/js/answer.js`, `panel/js/store.js`, `scripts/panel.test.mjs`, `scripts/uyum-paketi.test.js` (yeni), `.github/workflows/ci.yml` (testi koşturur), `docs/uyum/*` (yeni), `docs/decisions.md`, `docs/ai/repo-context.md`, `README.md`.
