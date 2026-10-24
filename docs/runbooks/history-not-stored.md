# HistoryNotStored

**Anlamı:** Son 10 dakikada en az bir cevap kullanıcıya döndü ama konuşma geçmişine yazılamadı (ADR-0023). Kullanıcı cevabı aldı; yanıtta `conversationId` ve `turnId` boştur, puanlama yapılamaz. Geçmiş ve kullanım metrikleri bu soruları saymaz.

**Kontrol:**

1. Hazırlık durumu: `qaDb` bileşeni `DOWN` ise `qa` havuzu veritabanına bağlanamıyor (`docker compose ps`, `verso-app` sağlıksız görünür).
2. Log: `{service="verso-app"} |= "Turn not stored"`. Satır yalnız istisna tipini taşır (`exceptionType=`); soru ya da cevap yoktur.
3. Rol ve şema var mı (yükseltilmemiş volume):

   ```bash
   docker compose exec -T -u postgres postgres psql -X -q -d verso -c "select rolname from pg_roles where rolname like 'svc_qa%'" -c "select nspname from pg_namespace where nspname = 'qa'"
   ```

**Çözümler:**

| Durum | Çözüm |
|---|---|
| Rol ya da şema yok (geçmişten önceki bir kurulum) | README "Konuşma geçmişinden önceki bir kurulumdan yükseltme": `40-modules.sh`, sonra `docker compose up -d --build --wait` |
| `svc_qa` parolası yanlış (`PSQLException`, kimlik doğrulama) | `secrets/SECRET_DB_QA_PASSWORD` ile rolün parolasını eşitle (`secrets/README.md` "Rotasyon"), `verso-app`'i yeniden başlat |
| Tablolar yok (uygulama log'unda `exceptionType=BadSqlGrammarException`; `docker compose exec -T -u postgres postgres psql -X -d verso -c '\dt qa.*'` boş) | `migrate` çalışmamış: `docker compose up -d migrate`, log'unu kontrol et |
| Veritabanı genel olarak yok | [database-unavailable.md](database-unavailable.md) |

**Kapanış:** `verso_qa_history_not_stored_total{reason="failed"}` artmıyor ve `qaDb` `UP`. Kaybolan turlar geri gelmez; cevaplar kullanıcıya ulaşmıştı.
