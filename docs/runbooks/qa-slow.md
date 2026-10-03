# QaSlow

**Anlamı:** Son 15 dakikada chat çağrılarının %95'i 60 saniyeden uzun sürdü. Bu, ADR-0008'deki local-CPU bütçesinin üstüdür. Kullanıcılar uzun bekliyor ve 11002 (meşgul) alabilir.

**Kontrol:**

1. Grafana → "Chat ve retrieval p95". Retrieval de yavaşsa Ollama genel olarak yüklüdür (worker embedding'leri de aynı Ollama'yı kullanır).
2. Aynı anda belge işleme sürüyor mu ("Belge işleme" panosu)? Embedding ve chat aynı CPU'yu paylaşır.
3. `verso_qa_questions_total{outcome="model_busy"}` artıyorsa kuyruk oluşuyor.

**Çözümler:**

| Durum | Çözüm |
|---|---|
| CPU yetersiz | `OLLAMA_CPUS`, daha küçük model (`VERSO_CHAT_MODEL`), GPU |
| Uzun cevaplar | `spring.ai.ollama.chat.num-predict` (512) düşürülebilir |
| Kalite için büyük model şart | Cloud modu (ADR-0013); KVKK değerlendirmesiyle |

**Kapanış:** p95 60 sn'nin altına iner. Kalıcı çözümün ölçümü faz 8 yük testinde yapılır.
