# ADR-0033: Ollama 0.40.10 ve model deposunun v2 düzeni

- **Durum:** Kabul edildi; canlı yığında doğrulandı (aşağıdaki "Canlı doğrulama sonucu"). Ollama allowlist kayıtları silindi.
- **Tarih:** 2026-10-24
- **Karar verenler:** doguyaras (güvenlik taraması bulguları: ADR-0030 madde 8)
- **İlgili eşik (referans Bölüm 24):** yok (sayısal eşik değişmiyor)
- **Genişlettiği:** ADR-0011 (model indirme ve digest), ADR-0013 (local modda dışarı yol yok), ADR-0030 (tarama ve allowlist)

## Bağlam

- 0.35.1 imajı 10 CRITICAL ve 53 HIGH bulgu taşır; 0.40.10 daha önceki denemede 0 / 0 / 83 taradı. Kazanç, Ollama allowlist'inin tamamıdır.
- İlk deneme (ADR-0030 madde 8) geri alındı: 0.40.x model deposunu yeni `manifests-v2` düzenine taşır ve salt okunur `ollama-models` volume'ünde çalışmıyordu (`/api/embed` ve `/api/generate` 500, sağlık denetimi yeşermedi).
- Korunacak güvenceler (zorunlu güvence, `llm-rules.md` 8.2):
  - Çalışan Ollama'nın internete yolu yok ve model çekemez.
  - Modeller sabitlenmiş digest ile doğrulanır (`pull.sh`).
  - Çalışan container model deposunu değiştiremez.

## Araştırma (2026-10-24, volume kopyaları üzerinde; canlı volume'e yazılmadı)

Kanıt: 0.40.10 imajı (`sha256:4c5b2787…5e35`) ve `verso_ollama-models` volume'ünün kopyası; sürüm notları github.com/ollama/ollama/releases (v0.40.2, v0.40.3, v0.40.4).

1. **Depo düzeni.** 0.35.x: `models/manifests/registry.ollama.ai/library/<ad>/<etiket>`. 0.40.x ek olarak `models/manifests-v2/ollama.com/library/<ad>/<etiket>` kullanır. `bge-m3:567m` için v2 dosyası düz manifesttir (model katmanı digest'i içinde). `gemma4:e2b` için v2 dosyası manifest listesidir (`manifest.list.v2`, `runner: llamacpp` ve `mlx`): katman digest'i dosyada değil, listenin işaret ettiği manifestte (`blobs/sha256-b37049…`) yazar. `pull.sh`'in eski `grep` denetimi bu yüzden yeni düzende çalışmaz.
2. **Blob'lar yeniden kullanılır.** `ollama pull` mevcut blob'ları indirmez; yalnız manifesti alır ve v2 dosyasını (ve geri uyumluluk için v1 manifestini) yazar. Model katmanı blob'ları (`6dd7ac24…`, `daec91ff…`) değişmedi, yeni dosyalar yalnız küçük manifest/config blob'larıydı.
3. **Arka plan yükseltmesi çalışmadı.** Sürüm notlarına göre (0.40.2) eski modeller ilk çalıştırmada arka planda yükseltilir; 0.40.3 gömme (embedding) isteği sırasında durdurur. Yazılabilir kopyada embedding ve `gemma4:e2b` üretimi sonrası yaklaşık 2 dakika beklendi: `manifests-v2` boş kaldı (daha uzun süre denenmedi). Yükseltmeye güvenilemez; açık bir `pull` gerekir.
4. **Salt okunur hata kaynağı.** Sunucu açılışında `manifests-v2` boşsa kaldırmaya ve yeniden oluşturmaya çalışır: `Error: remove …/manifests-v2: read-only file system`. Önceki denemenin hatası budur; yazma gerektiren çalışma zamanı işi değil, boş v2 klasörüdür.
5. **Dolu v2 ile salt okunur çalışır.** `manifests-v2` iki modelle doluyken kopya volume `:ro` ve `--network none` ile 0.40.10'a bağlandı: sunucu açıldı (`total unused blobs removed: 0`), `bge-m3:567m` `/api/embed` 200 döndü. Aynı metnin vektörü 0.35.1'inkiyle kosinüs 1,000000 (1024 boyut; yazılabilir 0.40.10 ile de 1,000000). `ollama show --modelfile` her iki modeli çözdü.
6. **Katman digest'i yoldan bağımsız okunabilir.** `ollama show --modelfile <model>` çıktısındaki `FROM …/blobs/sha256-<digest>` satırı Ollama'nın yükleyeceği model katmanıdır; düz manifest de manifest listesi de aynı biçimde çözülür.
7. **Çevrimdışı v1→v2 taşıma yok.** `ollama cp` yalnız yeni ad için v2 dosyası yazar; var olan adları taşımaz. Taşıma `pull` ile olur (registry'ye manifest için bağlanır).

## Seçenekler

| Seçenek | Artı | Eksi |
|---|---|---|
| A. Çalışan Ollama'ya yazılabilir volume | En basit | Çalışan container depoyu değiştirebilir (zorunlu güvence ihlali); reddedildi |
| B. Çalışan Ollama'ya ayrı yazılabilir volume (yalnız v2 ve önbellek) | Blob'lar salt okunur kalır | İki volume'ü doğru birleştirmek gerekir (`OLLAMA_MODELS` tek klasör); gereksiz karmaşa, 5. bulgu bunu gereksiz kılıyor |
| **C. Taşımayı `ollama-pull` yapar; çalışan Ollama salt okunur kalır** | Yazan tek süreç zaten internet ve yazma yetkisi olan pull container'ı; çalışan container ve volume bağlama değişmez | `pull.sh` yeniden yazılır; ilk taşıma bir kez internet ister |
| D. 0.35.1'de kal | Değişiklik yok | 10 CRITICAL / 53 HIGH bulgu sürer |

## Karar

**C.** Çalışan Ollama aynen kalır: `ollama-models:/root/.ollama:ro`, `models` ağı (`internal: true`), `OLLAMA_NO_CLOUD=true`, aynı sağlık denetimi (`ollama ps` iki modeli bellekte görür). Değişenler:

- **Imaj:** `ollama/ollama:0.40.10@sha256:4c5b2787…5e35`; `ollama` ve `ollama-pull` aynı çıpa (`x-ollama-image`) ile aynı derlemeyi kullanır (`ComposeConfigTest` bunu doğrular).
- **`deploy/ollama/pull.sh`:** her model için özel sunucuyu başlatır; model v2 deposunda varsa ve `ollama show --modelfile` çıktısındaki katman digest'i sabitlenmiş digest ile tam eşleşiyorsa indirmez (çevrimdışı yeniden başlatma çalışır). Aksi halde `ollama pull` yapar (v1'de olup v2'de olmayan eski kurulum burada taşınır) ve digest'i yeniden okur; eşleşmezse çıkış 1, yığın açılmaz. Digest 64 onaltılık karakterin tamamı olmalıdır (faz 5 S3 kuralı değişmedi).
- **Güvenceler:**

| Güvence | Nasıl korunur |
|---|---|
| Çalışan Ollama internete çıkamaz, çekemez | Değişmedi: `models` ağı `internal: true`, `OLLAMA_NO_CLOUD=true`; çekme yalnız `ollama-pull`'da |
| Digest ile doğrulama | `pull.sh` katman digest'ini Ollama'nın kendi çözümüyle okur ve tam dizgi olarak karşılaştırır; blob içeriğini indirirken Ollama sha256 ile doğrular |
| Çalışan container depoyu değiştiremez | Değişmedi: `:ro` bağlama; boş v2 klasörü sorunu pull aşamasında çözüldüğü için gevşetme gerekmedi |

## Sonuçlar

- Rollback (0.35.1): v1 manifestleri ve blob'lar yerinde kalır; `manifests-v2/` 0.35.1 tarafından okunmaz. `pull` bge-m3 için v1 manifestine ek bir küçük katman yazdı (0.40 uyumluluğu); 0.35.1'in bunu okuması **ölçülmedi**. Geri dönüşte `ollama-pull` 0.35.1 ile bir kez çalışır ve gerekirse çeker.
- Disk: v2 manifestleri ve birkaç küçük blob; model blob'ları çoğalmaz.
- Çevrimdışı yeniden başlatma değişmedi; yalnız 0.35'ten gelen ilk açılış registry'ye manifest için bağlanır.
- Tarama (sabitlenmiş Syft v1.55.0 / Grype v0.121.0, aynı komutlar): 0.35.1 10 / 53 / 194 → 0.40.10 0 / 0 / 83 (2925 bileşen; düzeltmesi olan bulgu 0). `vuln-gate.js` Ollama'nın id'ye özel kayıtları olmadan geçer; bu kayıtlar silindi. `CVE-2025-63389` ve takma adı (tasarım gereği kabul) kaldı.
- Hâlâ ölçülmedi: 0.35.1'in 0.40'ın yazdığı v1 manifestini okuyup okumadığı (rollback yolu volume yedeğine dayanır, yukarıdaki madde).

## Canlı doğrulama sonucu (2026-10-24, çalışan yığın, GPU bindirmesi)

Yedek (`backup once`) ve model volume kopyası (rollback için) alındı; hepsi geçince volume kopyası silindi.

1. `ollama-pull` ilk koşuda `bge-m3:567m does not match the pinned digest` ile **düştü**. Neden: 0.40, `manifests-v2/…/<etiket>` girdisini düz dosya değil, manifest blob'una **sembolik bağ** olarak yazar; `pull.sh`'in `find -type f` araması bulamadı. Düzeltme: `! -type d`; sahte `ollama` testi de artık sembolik bağ yazıyor. (Volume kopyasındaki erken gözlemde `find -type f`'in v2'yi göstermemesi aynı nedendendi.)
2. `docker compose up ollama-pull`: `bge-m3:567m already present`, `gemma4:e2b ready`, çıkış 0. 512 MB sınırı yetti.
3. `up -d --wait ollama` (0.40.10, GPU): healthy; logda `read-only` yok; `ollama ps` iki modeli `100% GPU`, `Forever` gösterir; volume bağlama `RW: false`.
4. Embedding: aynı metin için 0.35.1 vektörüne kosinüs 1,000000. Chat (`gemma4:e2b`, `/api/generate`): 200, `pong`.
5. `verso-app` healthy; `bash scripts/qa-smoke.sh` OK (belge yüklendi, ilgili soru `found=true`, atıf 1); `bash scripts/prove-local-mode.sh` OK (egress yok).
6. Tarama: yukarıdaki 0 / 0 / 83; geçit geçti.

`scripts/eval.mjs` (33 soru) koşulmadı: alt küme seçeneği yok ve `verso-ci` belgelerini siler; uçtan uca kanıt `qa-smoke.sh`.

Geçmezse geri dönüş: eski imaj (`ollama/ollama:0.35.1@sha256:292ee794…278c`) ve volume'ü yedekten geri yükle.

## Yeniden değerlendirme koşulu

- Ollama v2 düzeninde başka bir yükseltme (0.41+) ya da çalışma zamanı yazması gerektiren bir özellik gelirse (ör. boş v2 klasörünü artık kaldırmaması ya da yeni bir önbellek zorunluluğu) çalışan container'ın `:ro` bağlaması yeniden sınanır.
