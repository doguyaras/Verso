# Beceri kütüphanesi — atıf ve lisans notları

Bu klasördeki beceri tanımları (`skills/*.json`) ve altın örnekler (`skills/golden/*.json`) Verso için yazılmıştır ve Verso'nun lisansıyla dağıtılır. Aşağıdaki beceriler, yapı ve fikir bakımından açık kaynaklı bir projeden **uyarlanmıştır**; metin kopyalanmamış, içerik Türkçe olarak yeniden yazılmıştır.

## anthropics/knowledge-work-plugins

- Kaynak: https://github.com/anthropics/knowledge-work-plugins
- Telif: Copyright Anthropic, PBC
- Lisans: Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0)
- Uyarlanan yapılar:
  - `toplanti-karar-aksiyon` — Productivity eklentisindeki toplantı notu → karar / aksiyon / açık soru ayrımı.
  - `ihale-yanit` — Sales eklentisindeki teklif ve RFP desteğinden gereksinim → kanıt eşleştirme fikri.
  - `sozlesme-on-inceleme` — Legal eklentisindeki şirket oyun kitabına (playbook) karşı madde madde sözleşme incelemesi.

Her uyarlanan becerinin `attribution` alanı kaynağı, lisansı ve neyin uyarlandığını taşır; çalışma zamanında bu alan panelde ve dışa aktarımda gösterilebilir.

## Dahil edilmeyenler

`anthropics/skills` deposundaki belge becerileri (docx, pdf, pptx, xlsx) "source-available" lisanslıdır, açık kaynak değildir; bu kütüphaneye **hiçbiri dahil edilmemiştir** ve uyarlanmamıştır. Doğrulayıcı (`scripts/skill-library.test.js`, `SkillDefinitionValidator`) `anthropics/skills` kaynağını gösteren bir atfı reddeder.

Çerçeve atıfları (PMI PMBOK, ISO 31000, ISO 9001, ISO 10002, ISO/IEC/IEEE 29148, INVEST, Klein pre-mortem) yalnız kaynak gösterimidir; standart metinlerinden alıntı yapılmamıştır.
