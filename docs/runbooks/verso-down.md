# VersoDown

**Anlamı:** Prometheus 2 dakikadır uygulamanın metrik ucuna (`verso-app:8081/actuator/prometheus`) ulaşamıyor. API büyük olasılıkla cevap vermiyor.

**Kontrol:**

1. `docker compose ps verso-app`: container ayakta mı, `healthy` mi?
2. `docker compose logs --tail 100 verso-app`: açılış hatası var mı? Örnek: `AiModeCheck` mesajı, veritabanı bağlantısı, eksik secret.
3. `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/v1/documents`: 401 dönüyorsa API ayaktadır; sorun scrape'tedir (ağ, management portu).

**Sık nedenler ve çözümler:**

| Neden | Çözüm |
|---|---|
| Veritabanı yok | Bu durumda genellikle metrik ucu cevap verir ve bu alarm değil [database-unavailable.md](database-unavailable.md) çalar. Yine de `docker compose ps postgres` |
| Mod/sağlayıcı tutarsız (`AiModeCheck`) | Log'daki ayar adını düzelt (ADR-0013), `docker compose up -d verso-app` |
| Bellek (OOM, `ExitOnOutOfMemoryError`) | `docker inspect verso-verso-app-1 --format '{{.State.OOMKilled}}'`; `VERSO_MEM_LIMIT` |

**Kapanış:** `up{job="verso"} == 1` ve API 401/200 döner.
