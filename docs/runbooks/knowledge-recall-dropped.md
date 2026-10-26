# KnowledgeRecallDropped

**Anlamı:** Onaylı bilgilerin gecelik altın set kontrolü (ADR-0049) bir uyarı üretti. İki nedenden biri:

- **Geri çağırma düştü:** onaylı soruların arama sonucunda alıntıladıkları parça ilk k (varsayılan 5) içinde artık daha seyrek çıkıyor. Düşüş bir önceki başarılı koşuya göre `verso.qa.knowledge.eval.alert-drop`'tan (0,02) büyük.
- **Kaynak kayması:** onaylı cevapların `alert-source-drift`'ten (yüzde 10) fazlası alıntıladığı metni kaybetti (belge silindi, değişti, yeniden parçalandı).

Kullanıcıya etkisi doğrudan değildir: değişen kaynaklı kartlar zaten `STALE` olur ve sunulmaz. Uyarı, onaylı bilginin ve aramanın kalitesinin geriye gittiğini söyler.

**Kontrol:**

1. Panel → Yönetim → "Onaylı bilgiler" → "Kontroller": son koşunun `recall@k`, `önceki`, `kaynak kayması` değerleri. API: `GET /v1/admin/knowledge-eval/runs`.
2. Kaynak kayması yüksekse: aynı ekranda durum filtresi "Eskimiş" → `staleReason` dağılımı. Toplu `SOURCE_REPLACED` / `SOURCE_DELETED` bir bağlayıcının (ADR-0038) dosyaları yeniden yüklediğini gösterir.
3. Geri çağırma düştüyse son değişiklikleri düşün:
   - embedding modeli ya da sürümü değişti mi (`OLLAMA_EMBEDDING_MODEL`)? Değiştiyse kartlar eşleşmez, vakalar `skipped` sayılır;
   - büyük bir belge yüklemesi oldu mu (aynı konuda çok benzer yeni belgeler sıralamayı itebilir)?
   - Verso sürümü yükseltildi mi (parçalama, arama ayarları)?
4. Log: `{service="verso-app"} |= "Golden-set check finished"` → `found`, `missed`, `sourceLost`, `skipped`.

**Çözümler:**

| Durum | Çözüm |
|---|---|
| Kaynaklar değişti | Eskimiş kartları gözden geçir: belge güncellendiyse yeni cevaptan yeni kart oluştur, eskisini arşivle |
| Model değişti | Belgeleri yeniden indeksle; kartları yeni cevaplardan yeniden oluştur (eski vektör yeni modelle karşılaştırılamaz) |
| Yeni belgeler sıralamayı itti | Danışmanlık işi: kopya ya da çelişen belgeleri bilgi sahibiyle temizle; gerekirse `verso.qa.top-k` ölçümle |
| Sürüm yükseltmesi | Sürüm notlarındaki arama değişikliğini geri al ya da eşikleri ölç (eval/README.md) |

**Kapanış:** sonraki koşu uyarısız biter (`verso_qa_knowledge_eval_alert` 0). Hemen denemek için `POST /v1/admin/knowledge-eval/runs`.
