# Faz 6: Doğrulama ve kanıt kaydı

> Referans 19.6: yapısal doğrulama ile davranışsal doğrulama ayrı yazılır. "Yazılı ama koşulmamış" `PASS` sayılmaz.

- **Tarih:** 2026-10-03
- **Ortam:** Windows 11 + Docker Desktop (Docker Engine 29.6.1, 16 GB, 8 CPU); GitHub Actions `ubuntu-latest`. Temurin 25.0.4.1, Maven 3.9.16, Node 24.18, Ollama 0.35.1, gemma4:e2b; nginx-unprivileged 1.29.8.
- **Kapsam:**
  - Cloud modu (Anthropic varsayılan, OpenAI uyumlu ikinci sağlayıcı), mod tutarlılık kontrolü (`AiModeCheck`).
  - Boot istemcileri için iç adres filtresi.
  - Uygulama, veritabanı ve model sunucusunun yalnız iç ağlarda olması; kenar proxy (nginx); cloud overlay.
  - `scripts/prove-local-mode.sh`.
  - Kararlar: ADR-0013; ADR-0006'nın 1. kararının (local mod dışarı çıkmaz) üç katmanda uygulanması.
- **Davranışsal kapsam (seviye 2–3):** var.
  - Gerçek Anthropic ve OpenAI istemcileri sahte sağlayıcılara karşı uçtan uca denendi: gerçek PostgreSQL, gerçek token.
  - Local mod kanıtı çalışan yığında, uygulamanın kendi ağ ad alanından koşuldu.
  - Gerçek bir bulut sağlayıcısına (gerçek anahtarla) çağrı yapılmadı: anahtar yok ve gerekmiyor. Bu, kanıt dışı bir alan olarak aşağıda yazılı.

## 1. Makine kontrolleri

| Kontrol | Komut | Sonuç |
|---|---|---|
| Build ve testler | `./mvnw -B -ntp verify` | **PASS**: 342 Java testi (faz 5 sonunda 318) |
| Script ve hook testleri | `node --test scripts/*.test.js` | **PASS**: 8 suite |
| Local mod kanıtı | `bash scripts/prove-local-mode.sh` | **PASS** (Bölüm 2) |
| Mutasyon | `bash scripts/mutation-check.sh` | Bölüm 5 |

## 2. Canlı denemeler

| Deneme | Sonuç |
|---|---|
| `prove-local-mode.sh` (yeni ağ düzeni) | **PASS**: 15 kontrol. verso-app, ollama ve postgres yalnız iç ağlarda; uygulama ve model sunucusundan `1.1.1.1:443` erişilemez, `example.com` çözülmez. Pozitif kontroller: postgres çözülür ve bağlanılır. Uygulamanın 401'i, proxy'nin 413 (23 MB) ve 400 (büyük başlık) yanıtları `X-Rag-Mode: local` taşır ve zarflıdır (90014, 90004) |
| Kenar proxy üzerinden smoke'lar | **PASS**: `auth-smoke`, `ingest-smoke`, `qa-smoke` (gemma4:e2b, `found=true`, 1 atıf) |
| Uygulama kapalıyken edge | **PASS**: 503, `Retry-After: 5`, zarf 99997, `application/json` |
| Cloud overlay ile açılış (sahte anahtar dosyası) | **PASS**: `X-Rag-Mode: cloud`. Anahtar `/run/secrets/spring.ai.anthropic.api-key` olarak bağlı. Uygulama `backend`, `egress` ve `models` ağlarında. Overlay olmadan yeniden açılınca `local`'e döndü; sahte anahtar silindi ve sağlayıcıya istek yapılmadı |

## 3. Review'lar

İki ajan, sekiz skill, `1695920` üzerinde koştu.

| Skill | Karar | Öne çıkan bulgular |
|---|---|---|
| `verso-security-review` | REQUEST CHANGES | B1 (HIGH): nginx hata log'u istemci IP'sini ve ham istek satırını yazıyordu. B2: forwarding başlıkları. B3: anahtar ortam değişkeninden de kabul ediliyordu. B4: SDK log anahtarları |
| `verso-llm-review` | BLOCK | L1 (BLOCKER): proxy'nin kendi 413/400/503 yanıtlarında `X-Rag-Mode` yoktu. L2 (HIGH): OpenAI'ın gerçek timeout'u 60 sn. L3: restore provası internete açıktı. L4: SDK başlıkları. L5–L7 |
| `verso-environment-impact-review` | APPROVE WITH NON-BLOCKING COMMENTS | E1: dış IdP local modda erişilemez. E2: image etkisi ~78 MB (15 değil). E3/E4 |
| `verso-spring-code-review` | REQUEST CHANGES | F1: OpenAI timeout. F2: Claude'a `temperature` 400 döner. F4: Ollama kontrolü yalnız local'de. F10: alt çizgili host adı |
| `verso-test-writer` | FAIL | F3: açılış kontrolü bean'i silinince hiçbir test kırılmıyordu. F8: prove script'inde yanlış PASS ihtimali. F12: nginx.conf hiç test edilmiyordu. F13: faz 6 mutasyonları yoktu |
| `verso-resilience-review` | REQUEST CHANGES | OpenAI 60 sn; sağlayıcı 4xx'leri de devreyi açıyor (F2 ile birleşince) |
| `verso-api-contract-review` | APPROVE WITH NON-BLOCKING COMMENTS | F6: nginx'in HTML 413 sayfası sözleşmeyi bozuyordu |
| `verso-architecture-boundary-review` | APPROVE | Bulgu yok |

## 4. Bulgu → düzeltme

| Bulgu | Kanıt (önce) | Düzeltme | Kalıcı kontrol |
|---|---|---|---|
| Proxy yanıtlarında mod yok (L1/F6) | 413 HTML, `X-Rag-Mode` yok | Proxy'nin kendi 400/413/503'ü zarflı ve `X-Rag-Mode`'lu; mod dosyası overlay'e göre bağlanır; gövde sınırı 22 MB (uygulama 21 MB'a kadar kendi 413'ünü verir) | `ComposeConfigTest.edgeProxy_*`, prove script (413, 400), M189 |
| Proxy log'u (B1) | 413/502'de IP + istek satırı | `error_log crit` | `ComposeConfigTest`, M190 |
| Forwarding başlıkları (B2) | İstemci başlıkları uygulamaya geçiyordu | Proxy siler; `server.forward-headers-strategy: none` | `ComposeConfigTest` |
| OpenAI timeout (L2/F1) | 40 sn'lik cevap başarılı oldu, `X-Stainless-Timeout: 60` | Servis tarafında sağlayıcıdan bağımsız kesin sınır (cloud 30 sn, local 90 sn). İstemci ayarları hâlâ 60 sn'de kalıyor (ADR'de yazılı) | `OpenAiCloudModeTest.ask_whenTheProviderIsSlow_*`, `QuestionApiTest.ask_whenTheModelHangs_*`, M187, M188 |
| Claude örnekleme (F2) | `temperature` gönderiliyordu; doküman: varsayılan dışı değer 400 | Anthropic'te `temperature` yok; `max-tokens` 2048 (adaptive thinking) | `AnthropicCloudModeTest`, M195 |
| Açılış kontrolü bağlı değil (F3) | Bean silinince 61 test yeşil kalıyordu | `AiModeStartupTest`: yanlış yapılandırılmış uygulama başlamaz | M183 |
| Anahtar kaynağı (B3/F11), SDK log'u (B4) | Ortam değişkeni kabul ediliyordu | Ortam/sistem property'sinden gelen anahtar ve `ANTHROPIC_LOG`/`OPENAI_LOG` açılışı durdurur | `AiModeCheckTest`, M193 |
| Ollama kontrolü (F4), host adı (F10) | Cloud'da dış Ollama IP'si kabul; `my_ollama` reddediliyordu | Her iki modda kontrol; alt çizgili ad kabul | `AiModeCheckTest`, M194 |
| Restore provası (L3/F7) | `default` ağda, açık veriyle | `drill` iç ağı | `ComposeConfigTest`, M191 |
| Prove script (F8) | `getent` yoksa yanlış PASS; curl hatasında mesajsız çıkış | DNS pozitif kontrolü, postgres ağı, curl hatası FAIL satırı | Canlı koşu |
| Dokümanlar (E1/E2/E3/E4/L4/L5/L7) | – | ADR-0013 (IdP iç ağda, ~78 MB, SDK başlıkları, Docker sürümü), llm-rules 1.1, README (model adı sağlayıcıyla birlikte, anahtarı `read -rs` ile yazma), prod.env.example | – |

Kabul edilen açıklar:

- **OpenAI istemcisi:** istek başına 60 sn bekliyor. Kullanıcı 30 sn'de 503 alır, ama arka plandaki çağrı en çok 60 sn chat slotunu tutar. Spring AI 2.0.1'de bu ayarı geçerli kılan bir property bulunamadı.
- **Bulut SDK'larının egress'i:** adres filtresi bulut SDK'larını kapsamaz. Cloud modda `egress` ağı her adrese açıktır.
- **Gerçek sağlayıcı:** gerçek API'ye çağrı yapılmadı (anahtar yok). Claude'un örnekleme davranışı resmi dokümandan alındı.

## 5. Mutasyonlar

`bash scripts/mutation-check.sh` (`ONLY` ile faz 6 mutasyonları): baseline yeşil (342 Java testi, 8 node suite). Sonuç: **13/13 yakalandı** (M183–M195).

Ayrıca faz 2'nin M76 tanımı (uygulamaya migration parolası) yeni compose'a uymuyordu: `ports:` satırı artık uygulamada değil, edge'de. Tanım uyarlandı ve M76 yeniden yakalandı. Çıktı: [`faz-6-mutasyon-ciktisi.txt`](faz-6-mutasyon-ciktisi.txt).

## 6. CI

PR #8 (`1f5a979`): `ci` **success** (run 37107022844), `restore-drill` **success** (run 37107022736; local mod kanıtı `prove-local-mode.sh` dahil). Birleştirme sonrası develop push: success (run 37107303473, `4b89113`).
