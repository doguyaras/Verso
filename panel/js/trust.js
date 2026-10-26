// "Güven" (ADR-0050): the rendering model and the report builder of the trust screen, pure functions over the answer of
// GET /v1/admin/trust so they are tested without a browser. The words are plain Turkish; the product's name comes from
// the installation's branding (the caller passes it), never a literal. The report is built from the same answer the
// screen shows: nothing is added that the server did not say.

/** The scripts the customer's IT runs, and what each output means. */
export const VERIFY_STEPS = Object.freeze([
  { id: 'prove', script: 'scripts/prove-local-mode.sh',
    command: 'bash scripts/prove-local-mode.sh --report guven-kaniti.json',
    meaning: 'Çalışan kurulumun içinden dışarıya çıkılamadığını sınar: ağlar "internal" mı, 1.1.1.1:443 yanıt vermiyor mu, '
      + 'example.com çözülmüyor mu, her yanıt X-Rag-Mode başlığı taşıyor mu. Her satır PASS ya da FAIL yazar; sonda '
      + '"prove-local-mode: OK" görürseniz hepsi geçmiştir. --report ile aynı sonuç, saklayabileceğiniz bir JSON dosyası olur.' },
  { id: 'verify', script: 'scripts/verify-release.sh',
    command: 'VERSO_IMAGE=<kayıt>/<ad>@sha256:<özet> bash scripts/verify-release.sh',
    meaning: 'Kurduğunuz imajın bizim sürüm hattımızca imzalandığını ve SBOM kaydının aynı imzayla bağlı olduğunu cosign ile '
      + 'doğrular. Çıktıda iki PASS satırı (imza, SBOM) beklenir; FAIL ya da sıfırdan farklı çıkış kodu varsa imajı kurmayın.' },
]);

/** The compliance pack documents (docs/uyum), by file name and what each is for. */
export const COMPLIANCE_DOCS = Object.freeze([
  { file: 'docs/uyum/01-vked-sablonu.md', title: 'VKED şablonu' },
  { file: 'docs/uyum/02-aydinlatma-metni-calisan.md', title: 'Çalışan aydınlatma metni' },
  { file: 'docs/uyum/03-uretken-yz-kullanim-politikasi.md', title: 'Üretken YZ kullanım politikası' },
  { file: 'docs/uyum/04-veri-isleyen-sozlesmesi.md', title: 'Veri işleyen sözleşmesi' },
  { file: 'docs/uyum/05-egress-kaniti.md', title: 'Dışarı çıkış kanıtı (egress)' },
  { file: 'docs/uyum/06-sektor-notlari.md', title: 'Sektör notları' },
  { file: 'docs/uyum/07-almanya-eki-betrvg.md', title: 'Almanya eki (BetrVG)' },
]);

const CONNECTOR_TYPES = Object.freeze({ SMB: 'Dosya paylaşımı (SMB)', CONFLUENCE: 'Confluence', SHAREPOINT: 'SharePoint / OneDrive' });

/** What turning cloud mode on would change, in the order a data protection officer asks. */
export const CLOUD_CHANGES = Object.freeze([
  'Soru ve seçilen en çok 5 pasaj, seçilen model sağlayıcısına gider (yurt dışına aktarım olabilir, KVKK md. 9).',
  'Belgelerin kendisi ve embedding bu sunucuda kalır.',
  'Sağlayıcıya çıkış için ayrı bir ağ yolu açılır; "Dışarı açık bağlantılar" kartı bu yolları listeler.',
  'Bağlayıcılar (Confluence Cloud, SharePoint Online) ancak bu modda ve açık izinle internete çıkabilir.',
]);

const when = (iso) => {
  const date = iso ? new Date(iso) : null;
  return date && !Number.isNaN(date.getTime()) ? date.toISOString().replace('T', ' ').slice(0, 19) + ' UTC' : null;
};

/** A duration in seconds as "5 sn", "3 dk", "2 sa" or "4 gün". */
export function ageText(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) return null;
  if (seconds < 60) return `${Math.floor(seconds)} sn`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} dk`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} sa`;
  return `${Math.floor(seconds / 86400)} gün`;
}

/** Every path that lets data leave this server in the current setup, one row each; empty when none is open. */
export function externalPaths(t) {
  const paths = [];
  if (t?.ai?.mode === 'cloud') {
    paths.push({ id: 'model', label: 'Model sağlayıcısı', detail: `${t.ai.chatProvider ?? 'bulut'} (${t.ai.chatModel}): soru ve seçilen pasajlar` });
  }
  if (t?.egress?.externalConnectorEgress) {
    const hosts = t.egress.allowedHosts ?? [];
    paths.push({ id: 'connectors', label: 'Bağlayıcıların internet çıkışı', detail: hosts.length ? `İzinli adresler: ${hosts.join(', ')}` : 'Adres listesi boş' });
  }
  if (t?.connectors?.cloudConnectorPresent) {
    paths.push({ id: 'cloud-connector', label: 'Bulut bağlayıcı', detail: 'Kaynağı internette olan en az bir bağlayıcı tanımlı' });
  }
  if (t?.mcp?.enabled) {
    paths.push({ id: 'mcp', label: 'MCP sunucusu açık', detail: `Yetkili istemciler /mcp üzerinden pasaj okuyabilir (hedef kitle: ${t.mcp.audience ?? '–'})` });
  }
  return paths;
}

/** The plain facts of the screen: what to say and how to colour it. Null input (a failed call) gives null. */
export function trustModel(t, productName) {
  if (!t || typeof t !== 'object') return null;
  const local = t.ai?.mode !== 'cloud';
  const paths = externalPaths(t);
  const types = (t.connectors?.types ?? []).map((type) => CONNECTOR_TYPES[type] ?? type);
  const lastEvent = when(t.audit?.lastEventAt);
  const outbox = ageText(t.audit?.outboxOldestAgeSeconds);
  return {
    local,
    hero: {
      title: local ? 'Çalışma modu: Yerel — soru, belge ve cevaplar bu sunucudan çıkmaz' : 'Çalışma modu: Bulut — soru ve seçilen pasajlar sağlayıcıya gider',
      text: t.guarantee ?? '',
      models: [`Sohbet modeli: ${t.ai?.chatModel ?? '–'}`, `Embedding modeli: ${t.ai?.embeddingModel ?? '–'} (her modda yerel)`],
    },
    modeChanges: local ? CLOUD_CHANGES : [],
    modeChangesTitle: local ? `Bulut moduna geçilirse ${productName} kurulumunda şunlar değişir` : null,
    paths,
    pathsText: paths.length ? 'Şu anda açık olan dış yollar:' : 'Şu anda dışarı açık bir bağlantı yok.',
    connectorText: t.connectors?.known === false ? 'Bağlayıcı bilgisi okunamadı; "yok" anlamına gelmez, Bağlayıcılar ekranına bakın.' : types.length ? `Tanımlı bağlayıcı türleri: ${types.join(', ')}` : 'Tanımlı bağlayıcı yok.',
    identity: (t.identity?.issuers ?? []).map((i) => `${i.name}: ${i.host ?? '–'}`),
    audit: {
      text: lastEvent ? `Denetim kaydı: son olay ${lastEvent}` : 'Denetim kaydı: henüz olay yok ya da okunamadı',
      outbox: outbox ? `Teslim bekleyen en eski kayıt ${outbox} önce yazıldı` : 'Teslim bekleyen kayıt yok',
      healthy: outbox == null || t.audit.outboxOldestAgeSeconds < 300,
    },
    build: [
      `Sürüm: ${t.build?.version ?? 'bilinmiyor'}`,
      `İmaj özeti: ${t.build?.imageDigest ?? 'uygulama bilmiyor; verify-release.sh ile doğrulayın'}`,
    ],
    backup: when(t.backup?.lastSuccessAt) ?? `Son yedek: ${t.backup?.verification ?? 'kurulum betiğiyle doğrulanır'}`,
  };
}

/** The report as Markdown, for a data protection officer or an auditor. */
export function reportMarkdown(t, { productName = 'Asistan', at = new Date().toISOString() } = {}) {
  const m = trustModel(t, productName);
  if (!m) return '';
  const list = (items) => (items.length ? items.map((i) => `- ${i}`).join('\n') : '- yok');
  return [
    `# ${productName} güven raporu`,
    '',
    `Oluşturulma: ${when(at) ?? at}. Bu rapor çalışan uygulamanın kendisi hakkında bildirdiği bilgidir; bağımsız kanıt için `
      + '`scripts/prove-local-mode.sh --report` ve `scripts/verify-release.sh` çıktılarını ekleyin.',
    '',
    `## ${m.hero.title}`,
    '',
    m.hero.text,
    '',
    list(m.hero.models),
    '',
    '## Dışarı açık bağlantılar',
    '',
    m.paths.length ? list(m.paths.map((p) => `${p.label}: ${p.detail}`)) : m.pathsText,
    '',
    m.connectorText,
    '',
    '## Kimlik sağlayıcılar',
    '',
    list(m.identity),
    '',
    '## Denetim kaydı',
    '',
    list([m.audit.text, m.audit.outbox]),
    '',
    '## Sürüm ve yedek',
    '',
    list([...m.build, m.backup]),
    '',
  ].join('\n');
}

/** The report as JSON: the server's answer with the time it was saved; nothing else. */
export function reportJson(t, at = new Date().toISOString()) {
  return JSON.stringify({ savedAt: at, trust: t }, null, 2);
}

/** "guven-raporu-2026-10-26.md": ASCII only. */
export function reportFileName(extension, at = new Date().toISOString()) {
  return `guven-raporu-${String(at).slice(0, 10).replace(/[^0-9-]/g, '')}.${extension === 'json' ? 'json' : 'md'}`;
}
