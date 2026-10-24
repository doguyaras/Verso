# ADR-0034 doğrulaması: KVKK ilgili kişi başvurusu ve eksiksiz silme

Tarih 2026-10-24. Dal `feature/doguyaras-silme-basvuru`, taban `origin/develop` `287cbbb`. Docker bu makinede bilinçli olarak kapalıydı (kullanıcı kararı: makine yükü); Testcontainers ile koşan testler yerelde **koşmadı**, kapı GitHub CI'dır.

## Makine kontrolleri (yerel)

| Kontrol | Sonuç |
|---|---|
| `./mvnw -B -ntp -T 1 -DskipTests install` (JDK 25; enforcer dahil) | BUILD SUCCESS |
| `ArchitectureRulesTest`, `ConfigDriftTest`, `ErrorCodeUniquenessTest`, `ModuleStructureTest` (Modulith: `document`, `qa`, `audit`, `privacy`), `ModuleConsistencyTest`, `TransactionBoundaryRulesTest`, `MigrationConventionsTest` | yeşil (18 + 5) |
| Modül kuralları: `PrivacyArchitectureTest` (3), `DocumentArchitectureTest`, `QaArchitectureTest`, `AuditArchitectureTest` | yeşil |
| Sözleşme testleri: `PrivacyApiContractTest` (4), `DocumentApiContractTest`, `QaApiContractTest`, `AuditApiContractTest` | yeşil |
| Birim: `SubjectIdsTest` (25), `SubjectRequestServiceImplTest` (8), `QaSubjectsServiceImplTest` (3), `AccessDeniedAuditListenerTest` | yeşil |
| `node scripts/flyway-immutability.js check --base origin/develop` | `flyway-immutability: OK (base=origin/develop, merge-base=287cbbb42a5b)` |
| `GITLEAKS=… node --test scripts/*.test.js scripts/panel.test.mjs` | 179 test, 178 geçti, 0 kaldı (1 atlandı) |
| `node --test scripts/uyum-paketi.test.js` | 9/9 (yeni uçlar `/v1/admin/subjects`, `/v1/admin/documents` controller tabanıyla eşleşir) |

## CI'da koşacak gerçek veritabanı testleri (seviye 2)

| Senaryo | Test | Durum |
|---|---|---|
| Belge silme bütün şemalarda belgeye bağlı satır bırakmıyor (katalog taraması; yalnız `qa.turn_citation`, `qa.usage_daily_document`, `audit.event.target_id`, `audit.event.citation_document_ids` kalır) | `SubjectRequestApiTest#documentDelete_whenDone_leavesNoRowKeyedToTheDocumentOutsideTheKeptReferences` | CI bekliyor |
| Karartma: belge varken 409, yanlış onay 400, rolsüz 403, bütün hesaplarda not, sayaçlar aynı, tekrar 0, iki `ANSWERS_REDACTED`, log'da içerik yok | `SubjectRequestApiTest#redaction_whenTheDocumentIsDeleted_replacesTheAnswersOfEveryAccountAndIsIdempotent` | CI bekliyor |
| Dışa aktarım yalnız kişinin verisi; `no-store`, sabit dosya adı, kesilme başlığı; `SUBJECT_VIEWED`/`SUBJECT_EXPORTED`; log privacy | `SubjectRequestApiTest#export_whenAdmin_holdsExactlyTheSubjectsDataWithNoStore` | CI bekliyor |
| Silme: geçmiş, puan, hesap-gün, kişisel belge ve türevleri gider; alan belgesi, erişim, denetim satırları kalır ve raporlanır; başka hesap etkilenmez; tekrar sıfır; `SUBJECT_DELETED` hedefi `sub`'dan türetilmiş kimlik | `SubjectRequestApiTest#delete_whenConfirmed_removesTheSubjectKeepsCollectionAndAuditAndIsIdempotent` | CI bekliyor |
| Aynı kişi için eşzamanlı iki silme: ikisi 200, her satır bir kez sayılır | `SubjectRequestApiTest#delete_whenFiredTwiceAtOnce_bothSucceedAndCountEachRowOnce` | CI bekliyor |
| Onay yok/boş/farklı/büyük harf/256 karakter/JSON değil: 400, hiçbir şey silinmez; bozuk `sub` 13001 | `SubjectRequestApiTest#delete_whenTheConfirmationIsMissingOrWrong_isRejectedAndDeletesNothing` | CI bekliyor |
| 403 matrisi (`verso-user`, `verso-operator`; dört uç), kimliksiz 401, reddin denetim kaydı | `SubjectRequestApiTest#endpoints_whenCallerIsNotAnAdmin_areDenied` | CI bekliyor |
| `qa.redact_answers_citing` dar: `svc_qa`'da `UPDATE` yok, sınırlar 22023, `svc_qa_migrate`/`verso_backup`/`svc_document` 42501, yalnız sabit not, idempotent | `QaSchemaRolesTest#redactionFunction_whenCalled_isNarrowAndWritesOnlyTheNotice` | CI bekliyor |

Kısmi hata ve tekrar (seviye 1, yerel PASS): `SubjectRequestServiceImplTest#delete_whenAStepFails_stopsWithoutTheAuditRowAndCanBeRepeated` (karartma düşünce belge silinmez, `SUBJECT_DELETED` yazılmaz, aynı istek tamamlar), `#delete_whenDocumentsExist_redactsBeforeDeletingAndAuditsOnce` (sıra: geçmiş → karartma → belge → denetim).

CI sonucu ve SHA bu dosyaya push sonrası eklenmez; PR'sız akışta kanıt, dalın GitHub Actions `backend` işidir.

## Review skill'leri

| Skill | Karar | Not |
|---|---|---|
| verso-security-review | APPROVE WITH NON-BLOCKING COMMENTS | Yalnız `verso-admin` (controller + sahip arayüzleri, `@EnableMethodSecurity` `PlatformSecurityAutoConfiguration:122`); `sub` biçimi doğrulanır, log'a yazılmaz; `no-store`. LOW: `qa.redact_answers_citing` `SET search_path` taşımaz (convention yasaklar; `audit.purge_expired` ile aynı; `svc_qa`'nın hiçbir şemada CREATE'i yok). LOW: ele geçirilmiş uygulama var olan bir belgenin cevaplarını da karartabilir (409 uygulama katmanında); yalnız sabit not yazılabilir |
| verso-operation-consistency-review | saga unnecessary; davranış kanıtı CI'da | Uzak/geri alınabilir adım yok; şema başına kısa idempotent transaction + advisory lock; tekrar kurtarma yolu (ADR-0034 bölüm 6). Seviye-2 senaryolar CI koşana kadar BLOCKED |
| verso-db-migration-review | APPROVE WITH NON-BLOCKING COMMENTS | MEDIUM: `audit` V2 indeksi `CONCURRENTLY` değil (convention `.conf` sidecar'ı kabul etmiyor; `document` V3 emsali); yazmalar indeks kurulurken bekler, migrate mesai dışında |
| verso-api-contract-review | APPROVE WITH NON-BLOCKING COMMENTS | Breaking yok; yeni enum değerleri için eski image'e dönüşte denetim okuması sınırı ADR'ye yazıldı; bozuk UUID path 400 testi eklendi |
| verso-spring-code-review | APPROVE WITH NON-BLOCKING COMMENTS | READ COMMITTED altında "tek anlık görüntü" diyen yorumlar düzeltildi |
| verso-test-writer | PASS (yerel), CI bekliyor | Log privacy argüman, MDC ve istisna metnini de kapsar; kesilme planı birim testi eklendi |
| verso-architecture-boundary-review | APPROVE | `privacy` yalnız üç `*-api`'yi görür; her SQL kendi şemasında; GRANT yok |
