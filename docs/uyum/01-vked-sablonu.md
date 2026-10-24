# Veri Koruma Etki Değerlendirmesi (VKED) şablonu

> Bu belge hukuki tavsiye değildir; kurumun hukuk birimi ve veri sorumlusu tarafından uyarlanmalıdır.

| Alan | Değer |
|---|---|
| Kurum (veri sorumlusu) | {{KURUM_ADI}} |
| Asistan adı (panelde görünen) | {{ASISTAN_ADI}} |
| Kurulum modu | {{AI_MODU}} (`local` ya da `cloud`) |
| Verso sürümü / paket sürümü | {{VERSO_SURUMU}} / {{PAKET_SURUMU}} |
| Hazırlayan / tarih | {{HAZIRLAYAN}} / {{TARIH}} |
| Onaylayan (veri sorumlusu temsilcisi, hukuk) | {{ONAYLAYAN}} |
| Bir sonraki gözden geçirme | {{GOZDEN_GECIRME_TARIHI}} |

**Dayanak ve zorunluluk.** KVKK Üretken Yapay Zekâ Rehberi (15 Soruda), Yayın No 113, 24 Kasım 2025: VKED zorunlu değildir, ancak etkisi büyük işlemelerde "faydalı"dır ve yaşam döngüsü boyunca iyi uygulamadır **[repo: `research_notes/.../market_needs.md`]**. Bu şablon o rehberin istediği türden bir kaydı tutmak için hazırlanmıştır; kurumun yasal bir VKED yükümlülüğü olup olmadığı kurum hukuk birimi teyit etmeli.

Aşağıdaki "Verso gerçekleri" bölümleri kodla ve ADR'lerle doğrulanmıştır; kurulumda bir ayar değiştirildiyse ilgili satır güncellenir. Çift süslü parantezli alanlar kuruma aittir.

## 1. İşlemenin tanımı

- **Amaç:** çalışanların kuruma ait belgelere (PDF, Word, TXT, Markdown) doğal dille soru sorması; cevabın belge adı ve sayfa ile kaynak gösterilmesi. Amaç beyanı: {{ISLEME_AMACI}}.
- **Sistem:** Verso. Cevaplar bir dil modeli tarafından üretilir (yapay zekâ); model eğitilmez ya da ince ayar yapılmaz, kodda böyle bir akış yoktur. Hazır model kullanılır.
- **İlgili kişiler:** asistanı kullanan çalışanlar {{KULLANICI_GRUPLARI}}; belgelerde adı geçen üçüncü kişiler (belge içeriğine bağlı: {{BELGE_TURLERI}}).
- **İşlenen veri kategorileri:** aşağıdaki envanter (Bölüm 3). Belgelerin içeriği kurumun yüklediği belgelere bağlıdır; Verso belge içeriğini sınıflandırmaz. Özel nitelikli kişisel veri (sağlık, biyometrik vb.) belgelerde bulunabilir mi: {{OZEL_NITELIKLI_VERI_DURUMU}}. Bulunabiliyorsa [03](03-uretken-yz-kullanim-politikasi.md) Bölüm 4 ve kurumun özel nitelikli veri tedbirleri uygulanır.
- **Kurulum modeli:** kurumun kendi altyapısında ({{ALTYAPI_YERI}}) Docker Compose ile; tek kurum, çok kiracılık yok.

## 2. Roller

| Rol | Kim | Not |
|---|---|---|
| Veri sorumlusu | {{KURUM_ADI}} | Hazır modeli kendi amacıyla kullanan kurum veri sorumlusudur **[repo: KVKK Üretken YZ Rehberi, 24.11.2025, `market_needs.md`]**. |
| Veri işleyen | {{KURULUM_EKIBI_ADI}} (yalnız kurulum ve destek sırasında erişim varsa) | [04](04-veri-isleyen-sozlesmesi.md). Üretimde erişim yoksa ve yalnız yazılım teslim ediliyorsa işleyen sayılıp sayılmayacağı kurum hukuk birimi teyit etmeli. |
| Kimlik sağlayıcı | Kurumun OIDC sağlayıcısı ({{IDP_ADI}}) | Verso kullanıcı adı, parola ya da e-posta saklamaz; token'dan `sub` ve rol/grup claim'lerini okur (`verso.security.authorities.*`, ADR-0019). |
| Alt işleyen (yalnız `cloud` modda) | Chat sağlayıcısı: {{BULUT_SAGLAYICI}} (Anthropic ya da OpenAI uyumlu API; ADR-0013) | Sağlayıcının veri işleme/saklama/eğitim koşulları sağlayıcı sözleşmesine bağlıdır; bu şablon onları bilmez: {{SAGLAYICI_KOSULLARI}}. |
| Yönetici (`verso-admin`) | {{YONETICI_ROLU_SAHIPLERI}} | Metrik ve denetim kaydı görür; başkasının soru ve cevabını okuyan bir API ucu yoktur (ADR-0023: her geçmiş sorgusu `account_id` taşır). Bilgi alanlarını yönetir (oluşturma, gruba ya da kullanıcıya erişim verme, belge yükleme ve taşıma; `/v1/collections`, ADR-0028); bir alanın içeriğini sormak için kendisinin de o alana yetkili olması gerekir. Başka kullanıcının kişisel belgesini görmez. |

## 3. Veri envanteri: ne saklanır, ne kadar, nerede

| Veri | Tablo / yer | Ne kadar kalır | Silme yolu |
|---|---|---|---|
| Yüklenen orijinal dosya | `document.document_file` | Kullanıcı silene kadar; zamana bağlı otomatik silme **yok** | `DELETE /v1/documents/{id}`; kişinin başvurusunda `POST /v1/admin/subjects/delete` (kişisel belgeler, ADR-0034) |
| Belge metni (sayfa) ve dosya adı | `document.document_page`, `document.document` (`file_name`) | Aynı | Aynı `DELETE` (`ON DELETE CASCADE`) |
| Parçalar (chunk), embedding vektörleri ve tam metin indeksi | `document.document_chunk` (`embedding`, `search_vector`) | Aynı | Aynı `DELETE` (llm-rules 4.2) |
| Belge okuyucu listesi | `document.document_principal` | Aynı | Aynı `DELETE`. Bütün şemalarda belgeye bağlı satır kalmadığı testlidir; yalnız aşağıdaki atıf referansı, anonim sayaç ve denetim satırı kimliği bilerek kalır (`SubjectRequestApiTest`, ADR-0034) |
| Soru, cevap, konuşma başlığı | `qa.turn`, `qa.conversation` | Konuşmanın **başlangıcından** `VERSO_HISTORY_RETENTION_DAYS` gün (varsayılan **90**, aralık 1-3650) ya da kullanıcı silene kadar | `DELETE /v1/conversations/{id}`, `DELETE /v1/conversations`; kişinin başvurusunda `POST /v1/admin/subjects/delete`; süre dolunca saatlik iş (`HistoryRetentionJob`) |
| Atıf referansı (belge kimliği, sayfa) | `qa.turn_citation` | Turla birlikte. **Pasaj metni saklanmaz** | Turla birlikte |
| Cevap puanı ve gerekçesi | `qa.turn_feedback` | Turla birlikte | Turla birlikte |
| Hesap × gün satırı (aktif kullanıcı sayımı için) | `qa.active_account_day` | Geçmişle aynı süre | Geçmişle birlikte; "tümünü sil" ve kişi başvurusuyla hemen |
| Günlük sayaçlar (soru sayısı, sonuç türü oranları, süre kovaları, geri bildirim) | `qa.usage_daily*` | Süresiz; hesap kimliği ve metin **yok** | Silinmez (anonim) |
| Denetim kaydı: token `sub`, rol (`verso-admin`/`verso-user`), eylem, hedef kimlikleri, zaman, trace id, mod, model kimliği | `audit.event` | `VERSO_AUDIT_RETENTION_DAYS` gün (varsayılan **365**, aralık 30-3650) | Altı saatte bir çalışan iş, yalnız `audit.purge_expired` ile. Kullanıcının "geçmişimi sil" işlemi ve kişi başvurusu denetim satırlarını silmez (içerik yoktur; başvurunun kendisi yeni satır olarak yazılır, ADR-0034) |
| Yedekler | Şifreli yedek | `BACKUP_RETENTION_DAYS` (varsayılan **7**) gün daha; geri yüklemede süresi dolan satırlar bir sonraki çalışmada yeniden silinir | Yedek rotasyonu |
| Uygulama / gözlem günlükleri | Loki, uygulama logu | Loki'de 168 saat (7 gün, `deploy/obs/loki/loki.yml`); içerik **yok** (aşağıda) | Loki saklama süresi |
| Tarayıcı | `sessionStorage` | Yalnız tercihler (tema, dil, menü, bildirimin görüldüğü); soru/cevap/token **yok**; sekme kapanınca gider | Sekmeyi kapatmak |

**Kaynak:** ADR-0011, ADR-0023 (bölüm 4 ve kişisel veri envanteri), ADR-0026 (bölüm 8); `verso-app/src/main/resources/config/verso.yml` (`verso.qa.history.retention-days`, `verso.audit.retention-days`); `compose.yaml` (`BACKUP_RETENTION_DAYS`).

**Kurulumda geçerli değerler:** geçmiş {{GECMIS_SAKLAMA_GUN}} gün, denetim {{DENETIM_SAKLAMA_GUN}} gün, yedek {{YEDEK_SAKLAMA_GUN}} gün. Kurumun saklama ve imha politikasındaki süreyle uyumu: {{SAKLAMA_IMHA_POLITIKASI_UYUMU}}. Silme Yönetmeliği'nin periyodik imha aralığı (en geç 6 ay) ADR-0023'te 90 günün gerekçelerinden biri olarak anılır **[repo: ADR-0023, `market_needs.md` §4]**; yönetmelik maddesi ve geçerli aralık kurum hukuk birimi teyit etmeli.

### 3.1 Açık noktalar (kurumun bilmesi gerekenler)

- **Belgelere zaman bazlı imha yoktur.** Belgeler kullanıcı silene kadar kalır. Kurumun saklama politikası belge için süre öngörüyorsa bu süreç kurumda işletilir: {{BELGE_IMHA_SURECI}}.
- **Kişi başvurusu yönetici ucuyla yürür** (ADR-0034). `verso-admin`, kişinin hesap kimliğiyle (`sub`) ne tutulduğunu görür (`POST /v1/admin/subjects/summary`), JSON olarak dışa aktarır (`POST /v1/admin/subjects/export`) ve siler (`POST /v1/admin/subjects/delete`, gövdede onay): geçmiş, puanlar, hesap × gün satırları ve kişisel belgeler bütün türevleriyle gider. Kişinin **bilgi alanlarına** yüklediği belgeler kurumundur: silinmez, raporlanır; yönetici tek tek karar verir. IdP'deki hesap Verso'nun dışındadır; önce orada kapatılır. Adımlar: `docs/runbooks/kvkk-basvurusu.md`. Ayrılan çalışanın bilgi alanı belgeleri için kurum süreci: {{AYRILAN_CALISAN_SURECI}}.
- **Silinen belge, kalan cevap.** Belge silinince geçmişteki atıf "silinmiş" diye işaretlenir; o belgeden alıntı yapan cevap metni varsayılan olarak, saklama süresi dolana ya da kullanıcı silene kadar turda kalır (ADR-0023 bölüm 7). Belge kişisel veri içeriyorduysa yönetici `POST /v1/admin/documents/{id}/answer-redaction` ile bu cevapları bütün hesaplarda sabit bir notla değiştirir; soru, atıf ve sayaçlar kalır, işlem geri alınamaz (ADR-0034). Karartma kararını kim verir: {{CEVAP_KARARTMA_KARARI}}.
- **Belge metninde kişi adıyla arama yoktur** (ADR-0034 bölüm 7). Kişi yalnız hesap kimliğiyle bulunur. Başkasının belgesinde adı geçen kişinin başvurusunda kurum, belgeyi tanıyan birimle ilgili belgeleri belirler; yönetici siler ve cevapları karartır (runbook).
- **Denetim kaydındaki `sub` kişisel veridir:** IdP'de bir kişiye bağlanabilir; bu yüzden saklama süresi vardır ve dışa aktarım yalnız `verso-admin`'edir (ADR-0026).
- **Veritabanı yöneticisi** (kurum BT'si) teknik olarak tabloları okuyabilir; bu uygulama katmanının koruması değildir. Erişim {{DB_ERISIM_POLITIKASI}} ile sınırlanır.

## 4. Hiç saklanmayan ya da loglanmayan

- **Loglara hiç girmeyen:** belge metni, dosya adı, soru, prompt, cevap, pasaj, parola/token/secret, e-posta, telefon, IP, user-agent, istek/yanıt gövdesi. Loglara yalnız id, süre, boyut, sonuç kodu ve sabit nedenler girer (llm-rules 2.1; security-rules bölüm 4). Test: log privacy testleri, `scripts/obs-smoke.sh` (Loki'de soru metni yok).
- **Denetim kaydına hiç girmeyen:** soru ve cevap metni, konuşma başlığı, dosya adı, IP, user-agent, e-posta, serbest metin. Tabloda yalnız kimlik ve sabit sözcük vardır; bu kısıt kayıt tipinde, tablo CHECK'lerinde ve log'da zorlanır (ADR-0026 bölüm 2).
- **Hiç saklanmayan:** cevabı destekleyen pasajın metni (yalnız belge kimliği ve sayfa); kullanıcı adı, e-posta, parola (kimlik sağlayıcıdadır); token'lar tarayıcıda yalnız bellekte durur (ADR-0015).
- **Prompt'a girmeyen:** konuşma geçmişi (her soru bağımsızdır; ADR-0023 bölüm 3). Takip sorusu özelliği eklenirse bu şablon ve `llm-rules.md` güncellenir.
- **Metrik etiketleri ve izleme span'ları:** kullanıcı kimliği ve içerik taşımaz (llm-rules 2.1).

## 5. Veri akışı ve yurt dışına çıkış

### 5.1 `local` mod (varsayılan)

- Embedding ve chat modeli kurumun sunucusundaki Ollama'dadır (bge-m3, gemma4:e2b; model adları ve sürümleri `docs/versions.md`). Soru, belge, cevap ve vektör sunucudan çıkmaz.
- Uygulama, veritabanı ve model sunucusu yalnız `internal: true` Docker ağlarındadır; uygulamanın HTTP istemcileri yalnız iç adreslere bağlanabilir; mod ile model ayarı tutarsızsa uygulama açılmaz (ADR-0006, ADR-0013, llm-rules 1.1).
- Kanıt: [05-egress-kaniti.md](05-egress-kaniti.md). Yapay zekâ modunu her HTTP yanıtı `X-Rag-Mode` ile bildirir (llm-rules 1.3).
- Tek bilinçli istisna: model dosyaları kurulumda bir kez indirilir (`ollama-pull`, `default` ağ); çalışma zamanındaki Ollama'nın internet erişimi yoktur (llm-rules 8.2).
- Yurt dışı aktarım: yok. KVKK md. 9 aktarımı bu modda doğmaz; yine de kurum kendi değerlendirmesini yapar **[genel]**: {{LOCAL_MOD_DEGERLENDIRMESI}}.

### 5.2 `cloud` mod (isteğe bağlı; yalnız {{CLOUD_ONAY_TARIHI}} tarihli yazılı karardan sonra)

- Embedding yine yereldir. Sağlayıcıya yalnız **sistem kuralları, soru ve sahiplik filtresinden geçmiş en fazla 5 pasaj** gider (`verso.qa.top-k: 5`; pasaj başına en çok 1500 karakter, `max-passage-chars`). Dosya, tam metin, dosya adı ve vektör gitmez; eşik altında hiç istek yapılmaz (ADR-0013 bölüm 4, llm-rules 1.2). SDK'lar isteğe işletim sistemi/sürüm gibi teknik üstbilgi ekler; belge ya da kimlik taşımaz.
- Sağlayıcı: {{BULUT_SAGLAYICI}}; sunucu konumu: {{SAGLAYICI_KONUMU}}. Bu, yurt dışına aktarım olabilir; pasajlar kişisel veri içerebilir. Aktarımın hukuki mekanizması (KVKK md. 9 kapsamındaki yöntem, sözleşme ve gerekirse Kurul bildirimi) kurum hukuk birimi teyit etmeli: {{AKTARIM_MEKANIZMASI}}. Üretken YZ sağlayıcısına dosya yüklemenin aktarım sayılabileceğine dair 2026 görüşleri araştırma notlarında geçer **[repo: `regulation_markets_hardware.md`]**; kesin değerlendirme hukuk birimine aittir.
- Banka, ödeme kuruluşu, SPK'ya tabi kurum ve kamu kurulumlarında `cloud` mod kapalıdır: [06](06-sektor-notlari.md).
- Sağlayıcı hata metni yanıta ve log'a girmez (llm-rules 2.3).

### 5.3 MCP erişim kanalı (isteğe bağlı; varsayılan kapalı, ADR-0031)

- **Ne:** çalışanın kurumca onaylanmış yapay zekâ istemcisi (Claude, ChatGPT, Copilot Studio, iç ajanlar) Verso'yu salt okunur bir bilgi kaynağı olarak kullanır: `verso_search` (pasaj arama) ve `verso_fetch` (tek pasaj). Yeni bir **erişim kanalıdır**; panelden ayrı değerlendirilir. Açılması `VERSO_MCP_ENABLED` ile ve yalnız {{MCP_ONAY_TARIHI}} tarihli yazılı karardan sonra olur. Onaylanan istemciler: {{MCP_ISTEMCILERI}}.
- **Kim adına:** her çağrı, çalışanın kendi oturumuyla (IdP'den aldığı, yalnız `/mcp` için geçerli `verso-mcp` audience'lı token) ve paneldeki aynı okuma yetkisiyle çalışır: kişisel belgeleri ve grubunun bilgi alanları (ADR-0028). Servis hesabı, paylaşılan anahtar ya da yönetici yetkisi yoktur; MCP token'ı `verso-admin` rolü taşımaz.
- **Ne çıkar:** yalnız okuyabildiği belgelerden, soruya benzerliği en az `verso.mcp.min-score: 0.30` olan pasajlar; arama başına en çok `verso.mcp.max-results: 10` sonuç, sonuç başına en çok 600 karakterlik alıntı (`verso.mcp.excerpt-max-chars`), tek pasaj için en çok 4000 karakter (`verso.mcp.passage-max-chars`). Belge ve bilgi alanı adı da gider; `VERSO_MCP_SHOW_DOCUMENT_NAMES=false` iken ad yerine kısa belge kimliği gider. Dosya, tam belge ve vektör gitmez.
- **Nereye:** pasajlar MCP istemcisine, oradan istemcinin kendi modeline gider. Verso'nun kendisi dışarıya bağlantı açmaz (`local` modun egress kanıtı değişmez); ancak istemci bir bulut hizmetiyse (ChatGPT, Claude, Copilot) pasajlar o sağlayıcıya ulaşır. Bu, `local` modda da **yurt dışına aktarım** olabilir; sağlayıcı, konumu ve aktarım mekanizması kurum hukuk birimi teyit etmeli: {{MCP_AKTARIM_MEKANIZMASI}}. İstemcinin sağlayıcısı ayrı bir alıcıdır; Bölüm 2'deki tabloya eklenir.
- **Ne saklanır:** sorgu metni saklanmaz ve loglanmaz. Her araç çağrısı denetim kaydına `MCP_SEARCH` ya da `MCP_FETCH` olarak, yalnız kimliklerle (çağıranın `sub`'ı, dönen belge kimlikleri, sonuç sözcüğü) yazılır; saklama süresi denetim kaydınınkidir.
- **Aydınlatma:** kanal açılırsa [02](02-aydinlatma-metni-calisan.md) bu kanalı ve istemcilerin sağlayıcılarını sayacak şekilde güncellenir.

## 6. Hukuki sebep, aydınlatma, haklar

- **Hukuki sebep:** girdi (soru), çıktı (cevap) ve belgeler için ayrı analiz yapılır **[repo: KVKK Üretken YZ Rehberi]**. Kurumun belirlediği sebep: {{HUKUKI_SEBEP_GIRDI}} / {{HUKUKI_SEBEP_CIKTI}} / {{HUKUKI_SEBEP_BELGELER}}. Dayanak maddeler (KVKK md. 5, özel nitelikli veri için md. 6) **[genel]** kurum hukuk birimi teyit etmeli.
- **Aydınlatma:** [02](02-aydinlatma-metni-calisan.md). Aydınlatma yükümlülüğü maddesi **[genel]** (6698 sayılı KVKK, aydınlatma yükümlülüğü) kurum hukuk birimi teyit etmeli. Cevaplanamayan sorular listesi ya da belge adı görünümü açılacaksa metin bu amacı kapsamalıdır (ADR-0023).
- **İlgili kişi hakları:** KVKK md. 11 hakları model çıktılarını ve sorgulara dahil edilen içerikleri de kapsar **[repo: KVKK Üretken YZ Rehberi, `market_needs.md`]**. Verso'daki karşılıkları:

| Hak talebi | Verso'da yol |
|---|---|
| Erişim: kendi soru ve cevapları | Panelde "Geçmiş"; `GET /v1/conversations`, `GET /v1/conversations/{id}` |
| Erişim: kendi belgeleri | Panelde "Belgeler"; `GET /v1/documents` (kişisel belgeler ve okuyabildiği bilgi alanlarının belgeleri) |
| Silme: geçmiş | `DELETE /v1/conversations/{id}` ya da `DELETE /v1/conversations` (turlar, atıflar, puanlar, hesap-gün satırı; anonim sayaçlar kalır) |
| Silme: belge | `DELETE /v1/documents/{id}` (dosya, sayfalar, parçalar, vektörler, okuyucu satırları) |
| Erişim (başvuru, yönetici eliyle): kişi hakkında tutulan her şey | `POST /v1/admin/subjects/summary` (özet) ve `POST /v1/admin/subjects/export` (tek JSON: konuşmalar, turlar, atıf referansları, puanlar, belge adları ve durumları, kişinin aktör olduğu denetim satırlarının kimlikleri; `no-store`, sınırlı) |
| Silme (başvuru, yönetici eliyle) | `POST /v1/admin/subjects/delete` (geçmiş, puanlar, hesap × gün satırları, kişisel belgeler türevleriyle; bilgi alanı belgeleri ve denetim satırları kalır ve raporlanır; tekrarlanabilir) |
| Silinen belgeden alıntı yapan cevaplar | `POST /v1/admin/documents/{id}/answer-redaction` (sabit not; geri alınamaz) |
| Denetim kaydındaki satırlar | Silinmez; veri güvenliği ve hesap verilebilirlik amacıyla süre sonuna kadar tutulur (ADR-0026). Başvurunun ele alındığı da kaydedilir (ADR-0034). Dayanak (KVKK md. 5/2-ç hukuki yükümlülük, md. 5/2-f meşru menfaat) kurum hukuk birimi teyit etmeli. Aydınlatma metni bunu söyler |
| Düzeltme / itiraz | Verso'da özel bir uç yoktur. Kurum başvuru sürecini işletir: {{BASVURU_SURECI}} |
| Taşınabilirlik | Yönetici dışa aktarımı (JSON); kullanıcı panelde cevabı Markdown olarak indirir |

Başvuru yanıt süresi (kanunda 30 gün, KVKK md. 13) **[repo: `security-rules.md` bölüm 5: "KVKK 30 gün"]**; maddesi kurum hukuk birimi teyit etmeli. İşletim adımları: `docs/runbooks/kvkk-basvurusu.md`.

## 7. Gereklilik, orantılılık, azaltma

- **Veri azaltma:** cevap yalnız soranın okuyabildiği belgelerden üretilir: kendi kişisel belgeleri ve kimlik sağlayıcının token'ında taşıdığı gruplara (ya da doğrudan kendisine) açılmış bilgi alanlarının belgeleri (ADR-0028). Erişim filtresi vektör ve sözcük sorgusunun içindedir, sıralamadan ve sınırdan önce (llm-rules 4.1; testler: iki hesapla `RetrievalTest`, gruplarla `CollectionAccessTest`). Okuyamadığı belgenin parçası prompt'a hiç girmez; grup claim'i bozuksa grup yok sayılır (kapalı kalma). Gruptan çıkarılan kişi, bir sonraki oturum anahtarından (en çok 300 sn) itibaren o alanı göremez; bir alandan kaldırılan erişim ise bir sonraki istekte geçerlidir.
- **Amaçla sınırlılık:** pasaj benzerlik eşiğinin altında model çağrılmaz ("belgelerde bulunamadı" döner); uydurma yerine ret tercih edilir (llm-rules 3.4).
- **Saklama sınırı:** geçmiş 90 gün (başlangıç ayarı, kurum belirler), denetim 365 gün (başlangıç ayarı, mevzuat sayısı değildir; ADR-0026).
- **Yönetici görünürlüğü:** metrikler toplulaştırılmıştır. Cevaplanamayan soru listesi ve belge adları **varsayılan kapalıdır** (`VERSO_SHOW_UNANSWERED_QUESTIONS`, `VERSO_SHOW_DOCUMENT_NAMES`). Açılırsa:
  - Soru listesi yalnız en az **k = 3 farklı hesabın** sorduğu, normalleştirilmiş soruları gösterir (`verso.qa.metrics.unanswered-min-users: 3`; k yükseltilebilir, 3'ün altına indirilemez; eşik sorgunun içindedir).
  - Belge adlarında sahip hiçbir durumda gösterilmez.
  - Bu kurulumda: soru listesi {{ACIK_KAPALI_SORU_LISTESI}}, belge adları {{ACIK_KAPALI_BELGE_ADLARI}}.
- **Yapay zekâ olduğunun bildirimi:** panel her zaman "Yapay zekâ ile yazışıyorsunuz ({{ASISTAN_ADI}}); cevaplar hatalı olabilir, kaynağını kontrol edin." satırını gösterir; ilk kullanımda kapatılabilir bir bildirim kartı çıkar; üretilen cevap öğesi `data-ai-generated="true"` taşır ve Markdown dışa aktarımı "Yapay zekâ ile üretildi · {{ASISTAN_ADI}}" satırıyla biter (ADR-0027).

## 8. Risk değerlendirmesi

Olasılık ve etki: {{DUSUK_ORTA_YUKSEK}} ile doldurulur. "Verso'daki önlem" sütunu koddan doğrulanmıştır; "ek önlem" kuruma aittir.

| # | Risk | Verso'daki önlem | Kalan risk | Ek önlem (kurum) | Olasılık / etki |
|---|---|---|---|---|---|
| R1 | Kullanıcı yetkisi olmadığı belgeden cevap alır (IDOR, aşırı paylaşım) | Erişim filtresi (kişisel sahiplik + bilgi alanı erişimleri) vektör ve sözcük sorgusunun içinde; kimlik ve gruplar yalnız doğrulanmış token'dan; okunamayan kaynak 404; erişimi yalnız `verso-admin` verir (ADR-0028) | Bir alan yanlış gruba açılırsa o grubun herkesi okur; grup üyeliği IdP'dedir ve token ömrü (300 sn) kadar gecikir; Keycloak grup yolu yeniden adlandırılınca erişim kaybolur (kapalı kalma) | Bilgi alanlarını ve gruplarını kimin açacağına karar ver; erişim listelerini dönemsel gözden geçir: {{YUKLEME_YETKISI}} | {{R1}} |
| R2 | Hatalı / uydurma cevap | Sunucuda üretilen atıf, getirilmemiş kaynağa atıf atılır; yetersiz kanıtta model çağrılmaz; kaynaksız cevap "kaynağa bağlanamadı" uyarısı alır | Model yine hata yapabilir; atıf doğruluğu eval ile ölçülür, %100 değildir (`eval/README.md`) | Politika: cevap doğrulanır ([03](03-uretken-yz-kullanim-politikasi.md)) | {{R2}} |
| R3 | Belgedeki gizli talimat (prompt injection) | Belge içeriği güvenilmeyen veri; ayraçlı prompt; model çıktısı çalıştırılmaz (araç/SQL/URL yok) (llm-rules 3.1, 3.2) | Cevap metni yanıltılabilir | Güvenilmeyen kaynaktan belge yüklemeyi sınırla: {{YUKLEME_KURALI}} | {{R3}} |
| R4 | Özel nitelikli veri belgelere/sorulara girer | Verso içeriği sınıflandırmaz | Veri modelde (local: kurum içinde, cloud: sağlayıcıda) işlenir | Politika Bölüm 4; özel nitelikli veri tedbirleri; cloud kapalı | {{R4}} |
| R5 | `cloud` modda yurt dışına aktarım | Yalnız soru + en çok 5 pasaj; dosya, tam metin, vektör yok; mod her yanıtta görünür | Pasajlar kişisel veri içerebilir | Aktarım mekanizması, sağlayıcı sözleşmesi ya da `local` mod | {{R5}} |
| R6 | Sohbet kayıtlarının süresiz tutulması | 90 gün (ayarlı) ya da kullanıcı silene kadar; saatlik silme işi | Yedeklerde +7 gün | Süreyi politikayla hizala | {{R6}} |
| R7 | Yöneticinin tek tek kullanıcıyı izlemesi | Metrikler toplulaştırılmış; soru listesi/belge adları varsayılan kapalı; k-anonimlik (k ≥ 3) | Denetim kaydı `sub` taşır ve yönetici dışa aktarabilir; k-anonimlik sahte hesaplara karşı korumaz | `verso-admin` rolü kısıtlı; denetim kaydı erişimi yazılı süreçle; [07](07-almanya-eki-betrvg.md) | {{R7}} |
| R8 | Denetim kaydında kişisel veri birikmesi | Yalnız kimlik ve sabit sözcük; 365 gün (ayarlı); silme yalnız `audit.purge_expired` | `sub` kişiye bağlanabilir | Süreyi gerekçelendir; IdP'de `sub` opak tutulur | {{R8}} |
| R9 | Kayıtların kurcalanması | `svc_audit` yalnız INSERT/SELECT; silme fonksiyonu 30 günden yeni kaydı reddeder | Tablo sahibi ve süper kullanıcı değiştirebilir; WORM değildir (ADR-0026) | Dış arşiv / DB erişim ayrımı | {{R9}} |
| R10 | Model sunucusuna yetkisiz erişim (Ollama kimlik doğrulamasız) | Ollama'nın host portu yok, yalnız `internal` ağ | Ağ yapılandırması bozulursa açılır | Kurulumda ağ ve güvenlik duvarı denetimi; Ollama'ya host portu eklenmez | {{R10}} |
| R11 | Silinen kişinin/belgenin izleri | Belge silme türevleri siler (bütün şemalarda testli); kullanıcı geçmişini siler; yönetici kişi başvurusuyla geçmişi ve kişisel belgeleri siler, silinen belgeden alıntı yapan cevapları karartır (ADR-0034) | Belge imha süresi yok; bilgi alanı belgeleri ve denetim satırları başvuruda kalır; belge metninde adla arama yok; karartma yöneticinin kararına bağlı; yedek +7 gün | Bölüm 3.1 süreçleri; `docs/runbooks/kvkk-basvurusu.md` | {{R11}} |
| R12 | Gözlem yığınından sızıntı | Loglarda içerik yok; Loki 7 gün; Grafana dış paylaşım kapalı | Yanlış yapılandırma | Gözlem yığınına erişim kısıtlı | {{R12}} |
| R13 | Modelin kurum dışına bilgi sızdırması (egress) | `local` modda ağ, istemci ve açılış kontrolü; kanıt betiği | Host düzeyi ayarlar kapsam dışı | [05](05-egress-kaniti.md) kontrolleri; kurum güvenlik duvarı | {{R13}} |

## 9. Teknik ve idari tedbirler (özet)

- Kimlik: kurumun OIDC sağlayıcısı; kullanıcı token'ı ES256, `typ: at+jwt`, `aud: verso-api`; roller token claim'lerinden (ADR-0010, ADR-0019). Admin 2FA kurumun IdP'sinde zorunlu tutulur: {{ADMIN_2FA_DURUMU}}.
- Aktarım şifrelemesi: kenar proxy (nginx) önünde {{TLS_DURUMU}}.
- Yedek: şifreli; restore provası CI'da (`.github/workflows/restore-drill.yml`); kurulumda restore provası {{RESTORE_PROVASI_TARIHI}}.
- Erişim kayıtları: [Bölüm 3](#3-veri-envanteri-ne-saklanır-ne-kadar-nerede) denetim kaydı. KVKK Kurul'un 2018/10 sayılı kararı özel nitelikli veri için erişim ve işlemlerin kayıt altına alınmasını bekler **[repo: ADR-0026, `market_needs.md`]**; kararda bir saklama sayısı geçtiği ya da 365'in o sayıyla örtüştüğü iddia edilmez (net kanıt bulunamadı).
- Çalışan farkındalığı ve eğitim: [03](03-uretken-yz-kullanim-politikasi.md) Bölüm 8. Gözetim: cevapların insan denetimi [03](03-uretken-yz-kullanim-politikasi.md) Bölüm 5.

## 10. Sonuç

| Soru | Cevap |
|---|---|
| Kalan riskler kabul edilebilir mi? | {{KABUL_EDILEBILIR_MI}} |
| Ek önlem gerekli mi, kim, ne zamana kadar? | {{EK_ONLEM_PLANI}} |
| Kurul'a danışma / VERBİS güncellemesi gerekli mi? | {{VERBIS_VE_KURUL}} (kurum hukuk birimi teyit etmeli) |
| Gözden geçirme tetikleyicileri | Mod değişimi; saklama süresi değişimi; yönetici görünürlüğü ayarlarının açılması; yeni belge türü/özel nitelikli veri; sağlayıcı değişimi; Verso ADR'lerinde işleme gerçeklerini değiştiren karar |

İmza: {{VERI_SORUMLUSU_IMZA}} / {{HUKUK_IMZA}} / {{TARIH}}

## Ek A. Dayanak dosyaları (kurulum ekibi için)

| İddia | Dosya |
|---|---|
| Saklama süreleri, anahtarlar | `verso-app/src/main/resources/config/verso.yml`, `deploy/prod.env.example` |
| Tablolar | `services/{document,qa,audit}/*-core/src/main/resources/db/migration/*` |
| Geçmiş, metrikler, k-anonimlik | `docs/adr/0023-qa-semasi-gecmis-ve-metrikler.md` |
| Denetim kaydı | `docs/adr/0026-denetim-kaydi.md` |
| Local / cloud mod, egress | `docs/adr/0006-ai-calisma-modu.md`, `docs/adr/0013-cloud-modu-ve-egress.md`, `docs/ai/llm-rules.md` |
| Log gizliliği | `docs/ai/security-rules.md` bölüm 4, `docs/ai/llm-rules.md` bölüm 2 |
| Panel ve tarayıcı deposu | `docs/adr/0015-panel.md`, `docs/adr/0027-yapay-zeka-bildirimi-ve-uyum-paketi.md` |
