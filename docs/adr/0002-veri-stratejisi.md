# ADR-0002: Veri stratejisi (PostgreSQL + pgvector)

- **Durum:** Kabul edildi
- **Tarih:** 2026-10-02
- **Karar verenler:** doguyaras
- **İlgili eşik (referans Bölüm 24):**
  - PostgreSQL satırı: bağlantı sayısı ≥ `max_connections` × 0,7 ya da yedek/restore penceresinin SLO'yu aşması.
  - Geo/arama satırı: vektör sayısı ve sorgu gecikmesi.

## Bağlam

Verso'nun verisi şunlardır: belge metadata'sı, sayfa bazlı metin parçaları (chunk), bu parçaların embedding vektörleri ve ingestion iş durumu. Veri müşteri altyapısından çıkmamalıdır. Kurulum `docker compose up` ile ayağa kalkmalıdır.

Referans tek PostgreSQL, modül başına şema ve iki DB rolü (migrate/app), Flyway, UUIDv7 ve `ddl-auto: validate` ister (Bölüm 10).

## Seçenekler

| Seçenek | Artı | Eksi | Maliyet |
|---|---|---|---|
| A. PostgreSQL 18 + pgvector (tek veritabanı) | Metadata ve vektör tek transaction'da; yedek/restore tek araçla; referansın veri kurallarının tamamı uygulanır; Spring AI `PgVectorStore` desteği | Çok büyük vektör hacminde ayrı arama motoru gerekebilir | Düşük |
| B. Ayrı vektör veritabanı (Qdrant, Milvus, OpenSearch) | Büyük ölçekte arama özellikleri | İkinci veri deposu; tutarlılık için outbox gerekir; ek yedek/restore | Yüksek |

## Karar

**A** seçildi: PostgreSQL 18 ve pgvector extension'ı (`pgvector/pgvector:pg18` image'ı).

- Her domain modülü kendi şemasında ve kendi iki rolüyle çalışır: `svc_<şema>_migrate` (şema sahibi, DDL, yalnız Flyway) ve `svc_<şema>` (uygulama, yalnız DML). Cross-schema erişim GRANT ile engellenir. Altyapı script'i ve `afterMigrate` REVOKE adımı faz 2'de eklenir.
- Embedding tablosu Spring AI `PgVectorStore`'un beklediği yapıdadır, ama tablo **Flyway migration'ıyla** yaratılır (`initialize-schema=false`). Uygulama açılışta DDL çalıştırmaz.
- Vektör boyutu embedding modeline bağlıdır: `bge-m3` için 1024. Model değişikliği bir migration ve yeniden indeksleme işidir (ADR-0006 ve `docs/ai/llm-rules.md`).
- **B reddedildi:** tek host'ta ikinci bir veri deposu, faydası ölçülmeden işletim ve tutarlılık yükü getirir.

Faz 4'te ayrıca karara bağlanacaklar (bu ADR'yi güncelleyen yeni bir ADR ile):

- Orijinal PDF dosyasının ingestion sonrası saklanıp saklanmayacağı (veri minimizasyonu ile yeniden indeksleme arasındaki denge).
- Dosyanın nerede tutulacağı.

## Sonuçlar

- **Olumlu:**
  - Belge silme tek transaction'da metadata ve vektörleri birlikte siler.
  - Tek yedek/restore prosedürü yeterlidir.
  - KVKK silme talebi tek yerde karşılanır.
- **Olumsuz / kabul edilen risk:**
  - pgvector HNSW index'i bellek kullanır.
  - Çok büyük belge hacminde arama gecikmesi artar. Ölçüm faz 8'deki eval ve yük testiyle yapılır.
- **Etkilenen dosyalar:** `deploy/` (compose, Postgres init), `db/migration`, `config/verso.yml`.
- **Geri alma yolu:** Vektörler kaynak değildir; belgelerden yeniden üretilebilir. Ayrı bir vektör deposuna geçiş, yeniden indeksleme ile yapılır.

## Yeniden değerlendirme koşulu

- Chunk sayısı 10 milyonu geçerse.
- Retrieval p99 değeri 300 ms'yi 3 gün üst üste aşarsa.
- Yedek/restore süresi RTO'yu aşarsa.
