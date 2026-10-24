# ADR-0034: KVKK ilgili kişi başvurusu (md. 11) ve eksiksiz silme akışı

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras (`market_needs.md` madde 4, "Z": "Silme ve başvuru akışı: belge silinince parça/embedding/önbellek/log temizliği; kimlikle indeks ve loglarda arama"); uygulama ayrıntıları bu ADR'de
- **İlgili eşik (referans Bölüm 24):** yok (sıcak yol değişmedi); yeniden değerlendirme koşulu aşağıda
- **Genişlettiği:** ADR-0011 (belge silme), ADR-0023 (geçmiş, sayaçlar, "silinen belge, kalan cevap"), ADR-0026 (denetim kaydı), ADR-0028 (bilgi alanları, `document_principal`), ADR-0032 (docling)

> Numara: `develop`'ta 0029, 0031 ve 0033 boş görünür; 0031 (MCP) ve 0033 (Ollama dalının yeniden numaralanacak 0032'si) başka dallarda bekliyor. Çakışmamak için 0034.

## Bağlam

KVKK md. 11 ilgili kişiye verisinin işlenip işlenmediğini öğrenme, işlenmişse bilgi isteme ve silinmesini isteme hakkı verir; KVKK Üretken YZ Rehberi bu hakların model çıktılarını ve sorgulara eklenen içeriği de kapsadığını söyler (`docs/uyum/01-vked-sablonu.md` bölüm 5, [repo: `market_needs.md`]). Verso'da bugüne kadar:

- Kullanıcı kendi konuşmasını ve belgesini silebiliyordu (`DELETE /v1/conversations[/{id}]`, `DELETE /v1/documents/{id}`), ama **yönetici bir kişi adına** ne bulma, ne dışa aktarma, ne silme yapabiliyordu. VKED "Hesap silme akışı yoktur" diyordu.
- Belge silinince dosya, sayfa, parça, vektör ve okuyucu satırları `ON DELETE CASCADE` ile gidiyordu; bunu **bütün şemalarda** doğrulayan bir test yoktu.
- Silinen belgeden alıntı yapan cevap metni, saklama süresi dolana kadar turda kalıyordu (ADR-0023 bölüm 7). Cevap belgeden kişisel veri aktarmış olabilir.

Kısıt: modül sınırı (AGENTS.md 4). `document`, `qa`, `audit` şemaları ayrı rollerle korunur; hiçbir modül diğerinin tablosuna dokunamaz.

## Seçenekler

| Seçenek | Artı | Eksi |
|---|---|---|
| A. Koordinatörü `qa-core`'a koy | Yeni Maven modülü yok | `qa` soru-cevap modülüdür; belge ve denetim verisine dair kişi başvurusu onun işi değil; `qa` → `audit` okuma bağımlılığı doğar |
| **B. Yeni `privacy` modülü (`privacy-api` + `privacy-core`), şeması yok; her sahip kendi `*-api`'sinde bir sözleşme arayüzü açar** | Sınır temiz: koordinatör yalnız `document-api`, `qa-api`, `audit-api` görür; her yazma sahibinin kendi şemasında ve transaction'ında; kimse `privacy`'ye bağımlı değil (döngü yok) | Bir modül çifti daha; `ModuleStructureTest` ve hata kodu bloğu güncellenir |
| C. Olay tabanlı silme (her modül `SubjectErasureRequested` dinler) | Gevşek bağlılık | Broker ve outbox yok (ADR-0003); süreç içi olay kaybolursa silme sessizce yarım kalır, sonucu yöneticiye raporlanamaz |

Silme tutarlılığı için: (1) **saga tablosu** (referans 12, `operation-consistency.md` 5) ya da (2) **idempotent adımlar ve isteğin kendisini tekrar etmek**.

## Karar

### 1. Modül: B

- `services/privacy/privacy-api` (DTO: `SubjectSummaryResponse`, `SubjectHistory`, `SubjectDocuments`, `SubjectDocumentItem`, `SubjectDeletionRequest`, `SubjectDeletionResponse`, `AnswerRedactionRequest`, `AnswerRedactionResponse`) ve `privacy-core` (`controller/{AdminSubjectController,AdminAnswerRedactionController}`, `service/{SubjectRequestService,SubjectIds}`, `service/impl/SubjectRequestServiceImpl`, `exception/PrivacyErrorCode`). `@ApplicationModule(allowedDependencies = {"document :: api", "qa :: api", "audit :: api"})`.
- Sahiplerin sözleşmeleri: `document-api` `DocumentSubjects` (+ `dto/SubjectDocument(s)`), `qa-api` `QaSubjects` (+ `dto/SubjectConversation`, `SubjectTurn`, `SubjectHistoryCounts`), `audit-api` `AuditSubjects` (+ `dto/AuditSubjectEvent`). Uygulamaları sahip modülde (`DocumentSubjectsServiceImpl`, `QaSubjectsServiceImpl`, `AuditSubjectsServiceImpl`) ve **her metot ayrıca** `@PreAuthorize(VersoRoles.HAS_ADMIN)` taşır (ADR-0019 derinlemesine savunma; `DocumentCatalog`'un yönetici metotlarıyla aynı desen).
- `PrivacyArchitectureTest`: modülde SQL, `DataSource`, `java.sql` ve transaction **yok**; başka modüle yalnız üç `*-api` ile erişir.
- Hata kodu bloğu: **privacy 13000–13999** (13001 `SUBJECT_INVALID`, 13002 `SUBJECT_CONFIRMATION_MISMATCH`, 13020 `REDACTION_CONFIRMATION_MISMATCH`, 13021 `DOCUMENT_NOT_DELETED`).

### 2. Uçlar (yalnız `verso-admin`)

| Uç | İş |
|---|---|
| `GET /v1/admin/subjects/{sub}` | Özet: geçmiş sayıları, belgeler (ad, biçim, durum, boyut; içerik yok), kişiye adıyla açılmış ve kişinin oluşturduğu bilgi alanları, kişinin aktör olduğu denetim satırı sayısı |
| `GET /v1/admin/subjects/{sub}/export` | Erişim başvurusu: tek JSON belge, akış halinde, sınırlı (2 000 konuşma, 100 000 denetim satırı, liste başına 1 000 belge; `X-Subject-Export-Truncated`), sabit dosya adı |
| `DELETE /v1/admin/subjects/{sub}` | Silme başvurusu; gövde `{"confirm": "<sub>"}` path'tekiyle **birebir** aynı olmalı |
| `POST /v1/admin/documents/{documentId}/answer-redaction` | Silinmiş belgeden alıntı yapan cevapları sabit bir notla değiştirir; gövde `{"confirm": "<documentId>"}`; belge hâlâ varsa 409 |

- `sub` doğrulaması: `[A-Za-z0-9._@:-]{1,255}`, yani `JwtValidation`'ın oturum açarken token `sub`'ına uyguladığı biçim. Başka her değer 400 `13001`, değer loglanmaz. Bu biçimle SQL joker karakteri ya da kontrol karakteri sorguya ulaşamaz (sorgular zaten parametrelidir).
- Her yanıt `Cache-Control: no-store, private`. Dışa aktarımın `Content-Disposition`'ı sabittir (`verso-kvkk-export.json`); `sub` başlığa yazılmaz. Kenar proxy erişim logu kapalıdır (`deploy/edge/nginx.conf` `access_log off`), path'teki `sub` bir log'a düşmez.
- Silme bir **onay alanı** ister: gövdesiz, boş, farklı ya da büyük-küçük harfi farklı `confirm` 400'dür ve hiçbir şey silinmez. Yanlış yola gönderilmiş bir istek, tekrar edilen bir GET ya da yanlış kopyalanmış bir path silme yapamaz.

### 3. Ne silinir, ne kalır, ne raporlanır

| Veri | Başvuruda | Gerekçe |
|---|---|---|
| Konuşmalar, turlar (soru, cevap), atıf referansları, kişinin puanları (`qa.conversation`, `qa.turn`, `qa.turn_citation`, `qa.turn_feedback`) | **Silinir** | Kişinin kendi verisi; turla birlikte cascade |
| Hesap × gün satırları (`qa.active_account_day`) | **Silinir** | Tek hesap kimlikli sayaç |
| Kişisel belgeler ve türevleri (`document.document`, `document_file`, `document_page`, `document_chunk` + embedding + `search_vector`, `document_principal`) | **Silinir** | Belge silmeyle aynı cascade (llm-rules 4.2) |
| Başka hesapların, bu kişisel belgelerden alıntı yapan cevapları | **Notla değiştirilir** (silmeden önce) | Belge kişisel olduğundan normalde yalnız sahibi alıntılar; belge önce bir alanda durmuşsa başkası da alıntılamış olabilir |
| Kişinin bir bilgi alanına yüklediği belgeler | **Kalır, raporlanır** | Kuruma aittir (ADR-0028); yönetici tek tek karar verir (`DELETE /v1/documents/{id}` + gerekirse cevap karartma) |
| Kişiye adıyla verilmiş alan erişimleri (`user:<sub>`), kişinin oluşturduğu alanlar | **Kalır, raporlanır** | Erişim kararı ve alan kurumundur; yönetici `/v1/collections` ile kaldırır |
| Günlük anonim sayaçlar (`qa.usage_daily*`) | Kalır | Hesap kimliği ve metin yok (ADR-0023) |
| Denetim satırları (`audit.event`) | **Kalır**, sayısı raporlanır; başvurunun kendisi yeni satır olarak yazılır | Bölüm 5 |
| Yedekler | Süre sonunda düşer (`BACKUP_RETENTION_DAYS`, varsayılan 7) | Geri yüklemede silme yeniden çalıştırılır (runbook) |

Kimlik sağlayıcıdaki hesap Verso'nun verisi değildir; IdP'de devre dışı bırakmak ya da silmek kurumun işidir (runbook adım 1).

### 4. Belge silmenin eksiksizliği ve cevap karartma

- **Kanıt testi:** `SubjectRequestApiTest.documentDelete_whenDone_leavesNoRowKeyedToTheDocumentOutsideTheKeptReferences` `information_schema`'dan `document`, `qa`, `audit` şemalarındaki **bütün** `uuid` ve `uuid[]` kolonlarını bulur ve silinen belgenin kimliğini her birinde arar. Sonuç yalnız bilinçli tutulan dört kolondur: `qa.turn_citation.document_id` (geçmişte "silinmiş" atıf, ADR-0023), `qa.usage_daily_document.document_id` (anonim sayaç), `audit.event.target_id` ve `audit.event.citation_document_ids` (değiştirilemez kayıt). Bu dört kolon yalnız kimlik taşır, içerik taşımaz. Yarın eklenen ve belgeye bağlı yeni bir tablo silinmezse test kırmızıya döner.
- **Önbellek ve ara çıktı:** Verso'da belge pasajı önbelleği yoktur; geçmiş pasaj tutmaz, alıntı okumada üretilir (ADR-0020, ADR-0023). Docling dönüşümü bellekte işlenir, ara çıktısı diske ya da veritabanına yazılmaz (ADR-0032; `DoclingClient`, sonuç ≤ 16 MB). Orijinal dosya `document_file`'dadır ve cascade ile gider.
- **Log:** uygulama log'u belge içeriği, soru, cevap ya da dosya adı taşımaz (llm-rules 2.2; testli); Loki 7 gün tutar. Log'da kimlikle arama gerekmez, çünkü log'da içerik yoktur.
- **Cevap metni karartma (karar):** Belge silmek turdaki cevap metnini otomatik değiştirmez (kullanıcının geçmişi, yöneticinin kararı olmadan yeniden yazılmaz; ADR-0023 bölüm 7 aynen kalır). Belge kişisel veri içeriyorsa yönetici `POST /v1/admin/documents/{id}/answer-redaction` ile o belgeyi alıntılayan **bütün hesapların** cevaplarını şu sabit metinle değiştirir: "Bu cevap, alıntı yaptığı belge silindiği için yönetici tarafından kaldırıldı." Soru, atıf referansı ("silinmiş" görünür), puan ve bütün sayaçlar kalır.
  - `svc_qa`'nın `qa.turn` üzerinde `UPDATE` yetkisi **yine yok**. Tek yol `qa.redact_answers_citing(uuid[], integer)` (`V2__answer_redaction.sql`): migration rolüne ait `SECURITY DEFINER` fonksiyon, yalnız `svc_qa` oturumundan çağrılır, 1–1000 kimlik ve 1–1000'lik parça kabul eder, **metni kendisi yazar** (uygulama metin seçemez, ele geçirilmiş bir uygulama geçmişi bununla yeniden yazamaz). `audit.purge_expired` ile aynı desen (ADR-0026). Notun Java sabiti `QaSubjects.REDACTED_ANSWER` ile eşitliği testlidir.
  - Belge hâlâ varken 409 `13021`: cevap okunabilir bir kaynağa dayanıyorsa karartma gereksizdir.
  - İdempotent: zaten not taşıyan cevap yeniden sayılmaz; eşzamanlı iki çağrı bir cevabı bir kez sayar.

### 5. Denetim kaydı: silinmez, başvuru kaydedilir

- `audit.event` satırları başvuruda silinmez; başvuru `SUBJECT_VIEWED`, `SUBJECT_EXPORTED` (`detailCode` `COMPLETE`/`TRUNCATED`), `SUBJECT_DELETED` (`COMPLETE`/`COLLECTION_DOCUMENTS_KEPT`), `ANSWERS_REDACTED` olarak **yeni** satır yazılır. Aktör yöneticidir; hedef `AuditTargetType.SUBJECT` ve `target_id = UUID.nameUUIDFromBytes("verso:subject:" + sub)`: kişinin `sub`'ı satırda yazılmaz, ama `sub`'dan yeniden hesaplanarak başvurunun ele alındığı gösterilebilir.
- **Hukuki dayanak (kurum hukuk birimi teyit etmeli):** denetim satırı yalnız kimlik ve sabit sözcük taşır; veri güvenliği ve hesap verilebilirlik için tutulur. Dayanak olarak KVKK md. 5/2-ç (veri sorumlusunun hukuki yükümlülüğü: md. 12 veri güvenliği tedbirleri; Kurul'un 2018/10 sayılı kararının erişim kayıtları beklentisi, [repo: ADR-0026]) ve md. 5/2-f (meşru menfaat: kötüye kullanımın tespiti) öngörülür; md. 7 ve Silme Yönetmeliği uyarınca saklama süresi (`VERSO_AUDIT_RETENTION_DAYS`, varsayılan 365) dolunca `audit.purge_expired` siler. Hangi bentlerin geçerli olduğu, sürenin politikayla uyumu ve başvuru yanıtında bu satırların nasıl anlatılacağı **kurum hukuk birimi teyit etmeli**.
- `audit.event (actor_sub, occurred_at, id)` indeksi (`audit` V2): bir kişinin satırları tam tablo taraması olmadan bulunur. İndeks yeni veri ya da yeni okuyucu eklemez.

### 6. Tutarlılık: saga yok, idempotent adımlar

- Mekanizma (operation-consistency 1): bütün yazmalar yereldir ama **üç şemaya** dağılır; uzak, geri alınabilir bir rezervasyon yoktur ve silme hiç geri alınmaz (telafi adımı yoktur). Bu yüzden saga tablosu kurulmaz. Her adım **tek şemada tek kısa transaction**dır ve idempotenttir; isteğin kendisi kurtarma yoludur.
- Sıra: (1) geçmiş (`qa`, 20 konuşmalık parçalar; 200 turluk konuşma bile rolün 10 sn ifade sınırına takılmaz), (2) her 100 kişisel belgelik parça için önce başka hesapların alıntılayan cevapları karartılır (`qa`), sonra belgeler silinir (`document`, tek transaction, `lock_timeout` 15 sn), liste boşalana kadar; (3) kalanlar raporlanır; (4) yalnız tam başarıda `SUBJECT_DELETED` yazılır. Karartma silmeden **önce**dir: kesinti, kimliği artık bilinmeyen bir belgeyi alıntılayan cevap bırakmaz.
- **Kısmi hata:** bir adım düşerse istek 503 (`SERVICE_UNAVAILABLE`, `Retry-After`) ya da 500 döner, `Subject deletion interrupted: step=<history|redaction|documents|report> outcome=partial` WARN satırı yazılır, `SUBJECT_DELETED` yazılmaz. Aynı istek tekrarlanınca kalanı siler ve yalnız ikinci seferde sildiğini raporlar. Runbook: `docs/runbooks/kvkk-basvurusu.md`.
- **Eşzamanlılık:** aynı kişi için iki istek aynı anda gelirse her parça `pg_advisory_xact_lock` ile (qa'da `qa.subject:<sub>`, document'ta yüklemelerin de kullandığı `<sub>` anahtarı) sıraya girer; her satır bir kez silinir, iki yanıtın toplamı gerçek sayıdır (`SubjectRequestApiTest.delete_whenFiredTwiceAtOnce_bothSucceedAndCountEachRowOnce`). Silme, kişinin o anki belge yüklemesiyle de sıraya girer.
- **Sınır:** silmeden sonra kişinin geçerli token'ı (en çok 300 sn) yeni soru sorabilir ya da belge yükleyebilir. Runbook önce IdP'de hesabı kapatmayı, token ömrü kadar bekleyip silmeyi ve gerekirse silmeyi tekrarlamayı söyler.

### 7. Kapsam dışı

- **Belge içinde kişinin adını serbest metin olarak arama yok.** Bir adın yazımı, çekimi ve kısaltmaları güvenilir biçimde bulunamaz (Türkçe ekler, OCR hataları, "A. Yılmaz"); yanlış negatif kişiyi "bulunmadı" diye yanıltır, yanlış pozitif başkasının belgesini açar. Verso kişiyi yalnız **hesap kimliğiyle** (`sub`) bulur: yüklediği belgeler, geçmişi, sayaçları, denetim satırları. Operasyonel prosedür (runbook adım 5): kurum, belgeyi tanıyan birimle (İK, hukuk) hangi belgelerin kişiye ait veri içerdiğini belirler; yönetici bu belgeleri `DELETE /v1/documents/{id}` ile siler ve `answer-redaction` ile alıntılayan cevapları karartır; yapılanlar başvuru dosyasına yazılır.
- Panel ekranı yok (ayrı iş). Uçlar yönetici API'sidir.
- IdP'deki hesabın silinmesi ve yedeklerin seçmeli silinmesi (crypto-shredding yok; yedek süre sonunda düşer).

## Sonuçlar

- Olumlu: VKED'deki silme ve erişim yolları artık uç olarak var; belge silmenin eksiksizliği bütün şemaları tarayan bir testle korunur; silinen belgeden alıntı yapan cevap için yöneticiye geri alınamaz ama dar bir araç var; denetim kaydı değişmez kalır ve başvuruyu kanıtlar.
- Olumsuz / risk: (1) karartma geri alınamaz (yedekten dönmek dışında); onay alanı ve 409 bunu zorlaştırır, engellemez. (2) Dışa aktarım sınırları (2 000 konuşma, 100 000 satır) aşılırsa yanıt keser ve başlıkta söyler; tek başvuruda tam kopya için kurumun sınırı yükseltmesi gerekir (sabit, ayar değil). (3) Yarım kalmış bir dışa aktarım geçersiz JSON'dur; yeniden indirilir. (4) `audit` V2 indeksi kurulurken `audit.event`'e yazma bekler (saniyeler; migrate mesai dışında). (5) Silmeden sonraki geçerli token aralığı (bölüm 6).
- Sıcak yol değişmedi: yeni uçlar yönetici işidir, `POST /v1/questions` yoluna adım eklenmedi.
- **Rollout (referans 18.4):** yeni uçlar ve yeni denetim değerleri eklemedir; mevcut istemci kırılmaz (`/v1/admin/audit` tüketicisi bilinmeyen `action`'ı ham gösterir). Migration'lar ileri uyumludur (fonksiyon ve indeks; eski image ikisini de kullanmaz). **Geri dönüş sınırı:** eski image `audit.event`'ten okurken bilinmeyen `action`/`target_type` değerini enum'a çeviremez; bu sürümden sonra bir `SUBJECT_*` ya da `ANSWERS_REDACTED` satırı yazıldıysa eski image'de `GET /v1/admin/audit` ve `/export` o satırları içeren pencerede 500 döner (yazım ve diğer uçlar etkilenmez). Geri dönülürse denetim okuması bu sürüme dönene kadar o pencereden kaçınılır.

## Yeniden değerlendirme koşulu

- Bir kurulumda kişi başına konuşma 2 000'i ya da denetim satırı 100 000'i geçerse (dışa aktarım kesilir) veya silme isteği 60 sn'yi aşarsa (o zaman arka plan işi + süreç tablosu).
- Panelde başvuru ekranı istenince (ayrı ADR değil, bu ADR'ye ek).
- Kurum hukuk birimi denetim satırlarının başvuruda anonimleştirilmesini isterse (o zaman `audit.purge_expired` desenine kişi bazlı, sınırlı ikinci bir fonksiyon).
