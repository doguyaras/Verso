// The "Bağlayıcılar" screen (ADR-0038, ADR-0042, ADR-0044) without the DOM: the form rules that mirror the server's
// (ConnectorRequest, SourcePaths, GlobSet; docs/api-connectors-integration-v1.md), the health badge, the run and file
// texts, the deletion-safety approval and the typed confirmations. Nothing here touches a password: the form only names
// the secret file (credentialRef) that holds it on the server, and the API never returns a password either.

/** Where the setup steps and the API are written down (the panel quotes them, the files are the authority). */
export const DOCS = Object.freeze({
  readme: 'README.md → "Dosya paylaşımı bağlayıcısı (SMB)"',
  api: 'docs/api-connectors-integration-v1.md',
  adr: 'docs/adr/0038-smb-baglayicisi.md',
  overlay: 'deploy/compose.smb.yaml',
  runbook: 'docs/runbooks/connector-sync-stale.md',
  confluenceReadme: 'README.md → "Confluence bağlayıcısı"',
  confluenceAdr: 'docs/adr/0044-confluence-baglayicisi.md',
  confluenceOverlay: 'deploy/compose.confluence.yaml',
  sharePointReadme: 'README.md → "SharePoint Online / OneDrive bağlayıcısı"',
  sharePointAdr: 'docs/adr/0045-sharepoint-baglayicisi.md',
  sharePointOverlay: 'deploy/compose.sharepoint.yaml',
});

/** Connector types (ConnectorType) and Confluence deployments (ConfluenceDeployment) in plain Turkish. */
export const TYPES = Object.freeze({ SMB: 'Dosya paylaşımı (SMB)', CONFLUENCE: 'Confluence', SHAREPOINT: 'SharePoint Online / OneDrive' });
export const DEPLOYMENTS = Object.freeze({ DATA_CENTER: 'Data Center / Server (kurum içi)', CLOUD: 'Cloud (atlassian.net)' });
/** How the SharePoint connector's app registration signs in (SharePointAuthMethod). */
export const AUTH_METHODS = Object.freeze({ CERTIFICATE: 'Sertifika (önerilen)', CLIENT_SECRET: 'İstemci gizlisi (client secret)' });

/** The secret file a new connector of each type names by default. */
export const DEFAULT_CREDENTIAL = Object.freeze({
  SMB: 'SECRET_CONNECTOR_SMB_PASSWORD', CONFLUENCE: 'SECRET_CONNECTOR_CONFLUENCE_TOKEN',
  SHAREPOINT: 'SECRET_CONNECTOR_SHAREPOINT_CERT',
});

/** SharePointRequest's limits: at most 10 libraries; GUIDs for the tenant and the app. */
export const MAX_DRIVES = 10;
const DRIVE_HIDDEN = /[\p{Cc}‪-‮⁦-⁩]/u;
const SITE_SEGMENT = /^[A-Za-z0-9._~-]{1,128}$/;
const SITE_ID = /^[A-Za-z0-9.-]{1,253},[0-9A-Fa-f-]{36},[0-9A-Fa-f-]{36}$/;

/** ConfluenceRequest's limits: 1-20 space keys, page size 1-250 (default 50), a label of letters, digits and _ . : -. */
export const MAX_SPACES = 20;
export const PAGE_SIZE_MAX = 250;
const SPACE_KEY = /^[A-Za-z0-9_~.-]{1,100}$/;
const LABEL = /^[\p{L}\p{N}_.:-]{1,255}$/u;
const EMAIL = /^[A-Za-z0-9._+-]{1,64}@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$/;

/** Page sizes within the API's caps (connectors 1-200, runs and items as documented). */
export const CONNECTOR_PAGE_SIZE = 50;
export const RUN_PAGE_SIZE = 20;
export const ITEM_PAGE_SIZE = 50;

/** The document pipeline's file limit (ADR-0016, verso.document.max-file-size): the default and the cap. */
export const MAX_FILE_MB = 20;
export const SCHEDULE_MIN = 5;
export const SCHEDULE_MAX = 10080;
export const MAX_GLOBS = 20;
export const MAX_GLOB_LENGTH = 200;
const MAX_GLOB_STARS = 6;

/** The deletion safety of the server (verso.connector.deletion-safety-percent / -minimum, both 20 by default). */
export const DELETION_SAFETY = Object.freeze({ percent: 20, minimum: 20 });

export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const NAME_HIDDEN = /[\p{Cc}‪-‮⁦-⁩]/u;
const HOST = /^[A-Za-z0-9.:-]+$/;
const SHARE = /^[A-Za-z0-9_.$ -]+$/;
const DOMAIN = /^[A-Za-z0-9.-]*$/;
const ACCOUNT = /^[A-Za-z0-9._@$ -]+$/;
export const CREDENTIAL_REF = /^SECRET_CONNECTOR_[A-Z0-9_]{1,60}$/;
const SEGMENT_FORBIDDEN = /[\\/:*?"<>|\p{Cc}‪-‮⁦-⁩]/u;

/**
 * health as a badge: colour (tone) plus words, never colour alone. STALE is the "silently stopped" case of ADR-0038:
 * the last successful sync is older than three intervals, whatever the last run says. A value this panel does not
 * know is shown raw (integration document: unknown enum values are shown as they are).
 */
export const HEALTH = Object.freeze({
  OK: { label: 'Güncel', tone: 'ok' },
  STALE: { label: 'Gecikmiş', tone: 'wait' },
  FAILING: { label: 'Başarısız', tone: 'bad' },
  NEVER_SYNCED: { label: 'Henüz eşitlenmedi', tone: 'neutral' },
  DISABLED: { label: 'Kapalı', tone: 'neutral' },
  DELETING: { label: 'Siliniyor', tone: 'neutral' },
});

export const HEALTH_HELP = Object.freeze({
  OK: 'Son başarılı eşitleme zamanında.',
  STALE: 'Son başarılı eşitleme üç eşitleme aralığından eski: indeks güncel değil.',
  FAILING: 'Son eşitlemeler kaynağa ulaşamadı; hiçbir belge silinmedi.',
  NEVER_SYNCED: 'İlk eşitleme bekleniyor.',
  DISABLED: 'Zamanlama kapalı; belgeler olduğu gibi duruyor.',
  DELETING: 'Bağlayıcı ve eşitlediği belgeler arka planda siliniyor.',
});

/** The badge of a health value: { label, tone, help }. */
export function healthBadge(health) {
  const known = HEALTH[health];
  if (known) return { ...known, help: HEALTH_HELP[health] };
  return { label: health ? String(health) : 'Bilinmiyor', tone: 'neutral', help: '' };
}

export const RUN_STATUS = Object.freeze({
  RUNNING: { label: 'Sürüyor', tone: 'info' },
  SUCCEEDED: { label: 'Başarılı', tone: 'ok' },
  PARTIAL: { label: 'Kısmen', tone: 'wait' },
  FAILED: { label: 'Başarısız', tone: 'bad' },
  ABANDONED: { label: 'Yarıda kaldı', tone: 'neutral' },
});

/** The fixed codes of a run (SyncRunResponse.errorCode, ConnectorResponse.lastErrorCode) in plain Turkish. */
export const RUN_CODES = Object.freeze({
  FILES_FAILED: 'Bazı dosyalar okunamadı; sonra yeniden denenecek.',
  RUN_LIMIT: 'Koşu sınırına ulaşıldı (500 dosya ya da 2 GB); devamı birkaç dakika sonra.',
  BACKLOG: 'Belge kuyruğu dolu; devamı birkaç dakika sonra.',
  DELETION_SAFETY: 'Silmeler emniyet için bekletildi; onayınız gerekiyor.',
  DIRECTORY_UNREADABLE: 'Bir klasör okunamadı; liste eksik olduğu için hiçbir şey silinmedi.',
  LINK_NOT_FOLLOWED: 'Bir bağlantı (kısayol, junction) izlenmedi; liste eksik olduğu için hiçbir şey silinmedi.',
  LISTING_TOO_DEEP: 'Klasör ağacı çok derin; liste eksik olduğu için hiçbir şey silinmedi.',
  LISTING_TOO_LARGE: 'Çok fazla dosya; liste eksik olduğu için hiçbir şey silinmedi.',
  INTERRUPTED: 'Uygulama kapandı; eşitleme kaldığı yerden sürecek.',
  EGRESS_DENIED: 'Sunucu adresi izinli iç ağların dışında.',
  SOURCE_UNREACHABLE: 'Dosya sunucusuna ulaşılamadı.',
  SOURCE_AUTH_FAILED: 'Servis hesabıyla oturum açılamadı (hesap adı ya da parola dosyası).',
  CREDENTIAL_MISSING: 'Parola dosyası sunucuda bulunamadı.',
  SHARE_NOT_FOUND: 'Paylaşım bulunamadı.',
  PATH_NOT_FOUND: 'Klasör bulunamadı (yeniden adlandırılmış olabilir); hiçbir şey silinmedi.',
  PATH_UNREADABLE: 'Klasör okunamadı (servis hesabının okuma izni yok).',
  COLLECTION_MISSING: 'Hedef bilgi alanı artık yok.',
  CONFIG_INVALID: 'Bağlayıcı ayarı geçersiz; düzenleyip kaydedin.',
  SYNC_ERROR: 'Beklenmeyen bir eşitleme hatası.',
  INSTANCE_STOPPED: 'Eşitleyen sunucu durdu; iş başka bir koşuda tamamlandı.',
  RESTRICTION_CHECK_FAILED: 'Bir sayfanın kısıtları okunamadı; o sayfa alınmadı ve liste eksik olduğu için hiçbir şey silinmedi.',
  LISTING_LINK_REFUSED: 'Sunucu listenin devamını başka bir adrese yönlendirdi; izlenmedi, hiçbir şey silinmedi.',
  ATTACHMENTS_UNREADABLE: 'Bir sayfanın ekleri listelenemedi; liste eksik olduğu için hiçbir şey silinmedi.',
  RATE_LIMITED: 'Kaynak (Confluence ya da Microsoft Graph) istek sınırına takıldı (429); bağlayıcı bir süre sonra yeniden dener.',
  SOURCE_UNAVAILABLE: 'Kaynak (Confluence ya da Microsoft Graph) geçici olarak hizmet veremiyor (503); bağlayıcı bir süre sonra yeniden dener.',
  DRIVE_NOT_FOUND: 'Belge kitaplığı bulunamadı ya da uygulamanın bu siteye erişimi yok (Sites.Selected izni verildi mi?).',
  PARENT_UNKNOWN: 'Bir dosyanın klasörü listede yoktu; liste eksik olduğu için hiçbir şey silinmedi.',
  CREDENTIAL_INVALID: 'Sertifika ya da anahtar dosyası okunamadı (PKCS#8 özel anahtar ve sertifika, eşleşmeli).',
  CREDENTIAL_EXPIRED: 'Uygulama sertifikasının süresi dolmuş ya da henüz geçerli değil; yenisini yükleyin.',
  REDIRECT_REFUSED: 'Sunucu başka bir adrese yönlendirdi; belirteç başka sunucuya gönderilmedi.',
  SITE_NOT_FOUND: 'Site adresi ya da arama erişimi bulunamadı (adres veya hesap izni).',
  INVALID_RESPONSE: 'Sunucunun yanıtı okunamadı.',
  SOURCE_ERROR: 'Sunucu beklenmeyen bir hata döndü.',
});

export const ITEM_STATES = Object.freeze({
  SYNCED: { label: 'Eşitlendi', tone: 'ok' },
  FAILED: { label: 'Başarısız', tone: 'bad' },
  SKIPPED: { label: 'Atlandı', tone: 'neutral' },
});

export const ITEM_FAILURES = Object.freeze({
  READ_FAILED: 'Okunamadı',
  READ_TIMEOUT: 'Okuma zaman aşımına uğradı',
  ACCESS_DENIED: 'Servis hesabının izni yok',
  FILE_VANISHED: 'Okunurken kayboldu',
  COLLECTION_FULL: 'Bilgi alanının belge sınırı dolu',
  TOO_LARGE: 'Boyut sınırından büyük',
  EMPTY: 'Boş dosya',
  UNSUPPORTED: 'Desteklenmeyen tür ya da yapı',
  UNSAFE_LINK: 'Bağlantı olduğu için atlandı',
  RESTRICTED: 'Okuma kısıtı ya da kitaplıktan farklı izni var (v1 kuralı: alınmaz)',
  RESTRICTION_CHECK_FAILED: 'İzinleri okunamadı; alınmadı, sonra yeniden denenecek',
  NOT_FOUND: 'Kaynakta bulunamadı',
  REDIRECT_REFUSED: 'Başka adrese yönlendirildi, izlenmedi',
});

/** A fixed code as words; an unknown one is shown raw. */
export function codeText(code, table = RUN_CODES) {
  if (!code) return '';
  return table[code] ?? String(code);
}

/** A run in one line: "Başarılı · 3 yeni, 1 değişen, 0 silinen" plus the reason when there is one. */
export function runSummary(run) {
  if (!run) return 'Henüz koşu yok';
  const status = RUN_STATUS[run.status]?.label ?? String(run.status ?? '');
  const n = (v) => (Number.isFinite(v) ? v : 0);
  const counts = `${n(run.added)} yeni, ${n(run.updated)} değişen, ${n(run.deleted)} silinen`;
  const extra = [n(run.failed) ? `${n(run.failed)} başarısız` : null, n(run.deferred) ? `${n(run.deferred)} bekleyen` : null]
    .filter(Boolean).join(', ');
  return `${status} · ${counts}${extra ? `, ${extra}` : ''}`;
}

/** The files the connector tracks (ConnectorResponse.items). */
export function trackedCount(connector) {
  const items = connector?.items ?? {};
  return ['synced', 'failed', 'skipped'].reduce((sum, key) => sum + (Number.isFinite(items[key]) ? items[key] : 0), 0);
}

/** The server's rule (SyncPlanner.deletionAllowed): held back when more than 20% and more than 20 files would go. */
export function deletionNeedsApproval(deletions, tracked, safety = DELETION_SAFETY) {
  if (deletions <= safety.minimum || safety.percent >= 100) return false;
  return deletions * 100 > safety.percent * tracked;
}

/**
 * The approval a run waits for, or null: the newest run held deletions back and the connector is not being deleted.
 * count is the run's pendingDeletions, the exact number (exact: true). A run recorded before that field existed
 * (pendingDeletions null) falls back to "deferred", an upper bound (exact: false, the screen says "en çok"), and then
 * needs errorCode DELETION_SAFETY. The percentage is of the tracked files.
 */
export function pendingApproval(connector, latestRun) {
  if (!latestRun || latestRun.status !== 'PARTIAL' || connector?.health === 'DELETING') return null;
  const exact = Number.isInteger(latestRun.pendingDeletions);
  if (!exact && latestRun.errorCode !== 'DELETION_SAFETY') return null;
  const count = exact ? latestRun.pendingDeletions : (Number.isFinite(latestRun.deferred) ? latestRun.deferred : 0);
  if (count <= 0) return null;
  const tracked = Math.max(trackedCount(connector), count);
  return { count, exact, tracked, percent: Math.round((count * 100) / tracked), runId: latestRun.id };
}

/** The approval is sent only when the admin typed the number of files shown, exactly. */
export function approvalConfirmed(pending, typed) {
  return Boolean(pending) && String(typed ?? '').trim() === String(pending.count);
}

/** Deleting a connector goes ahead only when its name is typed exactly as it is listed. */
export function deleteConfirmed(connector, typed) {
  return typeof connector?.name === 'string' && connector.name !== '' && typed === connector.name;
}

/** The glob list of a textarea: one pattern per line (a comma belongs to "{a,b}"), blank lines dropped. */
export function parseGlobs(text) {
  return String(text ?? '').split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
}

/** Why a glob is unusable, or null: GlobSet's rules (length, "..", at most six "*", one level of closed braces). */
export function globProblem(raw) {
  if (raw.length > MAX_GLOB_LENGTH) return `Desen en çok ${MAX_GLOB_LENGTH} karakterdir.`;
  let glob = raw.replace(/\\/g, '/');
  while (glob.startsWith('/')) glob = glob.slice(1);
  if (!glob) return 'Desen boş olamaz.';
  if (glob.includes('..')) return 'Desende ".." olamaz.';
  if ((glob.match(/\*/g) ?? []).length > MAX_GLOB_STARS) return `Desende en çok ${MAX_GLOB_STARS} yıldız olabilir.`;
  let open = false;
  for (const c of glob) {
    if (c === '{') {
      if (open) return 'İç içe süslü parantez kullanılamaz.';
      open = true;
    } else if (c === '}') {
      if (!open) return 'Kapanan süslü parantezin açılışı yok.';
      open = false;
    }
  }
  return open ? 'Süslü parantez kapanmamış.' : null;
}

function globsProblem(list) {
  if (list.length > MAX_GLOBS) return `En çok ${MAX_GLOBS} desen yazılabilir.`;
  for (const glob of list) {
    const problem = globProblem(glob);
    if (problem) return `"${glob.slice(0, 40)}": ${problem}`;
  }
  return null;
}

/** The prefix as the server stores it (SourcePaths.normalizePrefix), or a problem. */
export function normalizePrefix(raw) {
  const value = String(raw ?? '');
  if (!value.trim()) return { value: '', problem: null };
  if (value.length > 1024) return { value: null, problem: 'Klasör yolu en çok 1024 karakterdir.' };
  let unified = value.trim().replace(/\\/g, '/');
  if (unified.startsWith('//')) return { value: null, problem: 'Paylaşımın içinde göreli bir klasör yazın (\\\\sunucu\\ ile başlamaz).' };
  unified = unified.replace(/^\/+/, '').replace(/\/+$/, '');
  if (!unified) return { value: '', problem: null };
  for (const segment of unified.split('/')) {
    if (!segment || segment === '.' || segment === '..' || segment.length > 255 || /[. ]$/.test(segment)
      || SEGMENT_FORBIDDEN.test(segment)) {
      return { value: null, problem: 'Klasör yolunda boş parça, ".", "..", ":" ya da * ? " < > | olamaz; parça nokta ya da boşlukla bitemez.' };
    }
  }
  return { value: unified, problem: null };
}

function ipv4(text) {
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(text);
  if (!m) return null;
  const parts = m.slice(1).map(Number);
  return parts.every((p) => p <= 255) ? parts : null;
}

/** Only private ranges may be allowed (10/8, 172.16/12, 192.168/16); loopback and the rest never (ADR-0038 5). */
function privateIpv4([a, b]) {
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

/**
 * Why a host cannot be a file server, or null: a DNS name or an IP, no scheme, port or path; an IP literal must be a
 * private address (the server also checks the installation's allowlist, 14004, and resolves names at every sync).
 */
export function hostProblem(raw) {
  const host = String(raw ?? '').trim();
  if (!host) return 'Dosya sunucusunun adını ya da IP adresini yazın.';
  if (host.length > 253) return 'Sunucu adı en çok 253 karakterdir.';
  if (!HOST.test(host)) return 'Yalnız sunucu adı ya da IP adresi yazın (smb://, \\\\, port ya da klasör olmadan).';
  const lower = host.toLowerCase();
  if (lower === 'localhost' || lower.endsWith('.localhost')) return 'Bu makinenin kendisi bağlanamaz; dosya sunucusunu yazın.';
  const v4 = ipv4(host) ?? (lower.startsWith('::ffff:') ? ipv4(lower.slice(7)) : null);
  if (v4) return privateIpv4(v4) ? null : 'IP adresi özel bir iç ağda olmalı (10.x, 172.16–31.x, 192.168.x).';
  if (/^\d+(\.\d+)*$/.test(host)) return 'IP adresi geçersiz.';
  if (host.includes(':')) return /^f[cd][0-9a-f]{0,2}:/.test(lower) ? null : 'IPv6 adresi yalnız özel aralıkta (fc00::/7) olabilir.';
  return null;
}

const blank = (value) => String(value ?? '').trim();

/**
 * A form as POST/PUT /v1/admin/connectors takes it: { problems: { field: text }, body } with body null while any
 * problem stands. form holds the raw input values (strings, enabled a boolean); original is the connector being
 * edited, whose target collection cannot change (409 14006), so the body keeps it.
 */
export function connectorRequest(form, original = null) {
  const type = original?.type ?? form.type ?? 'SMB';
  if (type === 'CONFLUENCE') return confluenceRequest(form, original);
  if (type === 'SHAREPOINT') return sharePointRequest(form, original);
  const problems = {};
  const name = blank(form.name);
  if (!name) problems.name = 'Ad boş olamaz.';
  else if (name.length > 100) problems.name = 'Ad en çok 100 karakterdir.';
  else if (NAME_HIDDEN.test(name)) problems.name = 'Ad görünmeyen denetim karakteri içeremez.';

  const host = blank(form.host);
  const hostIssue = hostProblem(host);
  if (hostIssue) problems.host = hostIssue;

  let port = null;
  if (blank(form.port)) {
    port = Number(blank(form.port));
    if (!Number.isInteger(port) || port < 1 || port > 65535) problems.port = 'Port 1 ile 65535 arasında bir tam sayıdır.';
  }

  const share = blank(form.share);
  if (!share) problems.share = 'Paylaşım adını yazın.';
  else if (share.length > 80 || !SHARE.test(share)) problems.share = 'Paylaşım adı eğik çizgi olmadan yazılır (harf, rakam, _ . $ - ve boşluk).';

  const prefix = normalizePrefix(form.pathPrefix);
  if (prefix.problem) problems.pathPrefix = prefix.problem;

  const domain = blank(form.domain);
  if (domain.length > 253 || !DOMAIN.test(domain)) problems.domain = 'Etki alanı yalnız harf, rakam, nokta ve tire içerir (ör. CORP).';

  const username = blank(form.username);
  if (!username) problems.username = 'Servis hesabının adını yazın.';
  else if (username.length > 104 || !ACCOUNT.test(username)) problems.username = 'Hesap adı yalnız harf, rakam ve . _ @ $ - içerir (etki alanı ayrı alanda).';

  const credentialRef = blank(form.credentialRef);
  if (!CREDENTIAL_REF.test(credentialRef)) {
    problems.credentialRef = 'SECRET_CONNECTOR_ ile başlayan, büyük harf, rakam ve _ içeren bir dosya adı yazın. Parolanın kendisini değil.';
  }

  const collectionId = original ? original.collectionId : blank(form.collectionId);
  if (!UUID_PATTERN.test(String(collectionId ?? ''))) problems.collectionId = 'Hedef bilgi alanını seçin.';

  const includeGlobs = parseGlobs(form.includeGlobs);
  const excludeGlobs = parseGlobs(form.excludeGlobs);
  const includeIssue = globsProblem(includeGlobs);
  const excludeIssue = globsProblem(excludeGlobs);
  if (includeIssue) problems.includeGlobs = includeIssue;
  if (excludeIssue) problems.excludeGlobs = excludeIssue;

  let maxFileBytes = null;
  if (blank(form.maxFileMb)) {
    const mb = Number(blank(form.maxFileMb).replace(',', '.'));
    if (!Number.isFinite(mb) || mb <= 0 || mb > MAX_FILE_MB) problems.maxFileMb = `En büyük dosya 0 ile ${MAX_FILE_MB} MB arasında olmalı.`;
    else maxFileBytes = Math.max(1, Math.round(mb * 1024 * 1024));
  }

  const schedule = Number(blank(form.scheduleMinutes));
  if (!Number.isInteger(schedule) || schedule < SCHEDULE_MIN || schedule > SCHEDULE_MAX) {
    problems.scheduleMinutes = `Eşitleme aralığı ${SCHEDULE_MIN} ile ${SCHEDULE_MAX} dakika (1 hafta) arasında bir tam sayıdır.`;
  }

  if (Object.keys(problems).length) return { problems, body: null };
  return {
    problems,
    body: {
      name, host, port: port ?? 445, share, pathPrefix: prefix.value, domain: domain || null, username, credentialRef,
      collectionId, includeGlobs, excludeGlobs, maxFileBytes, scheduleMinutes: schedule, enabled: form.enabled !== false,
    },
  };
}

/**
 * The site URL as ConfluenceUrl accepts it: https, a host, an optional port and context path; no user info (a
 * credential in a URL), query or fragment. Returns the problem text or null.
 */
export function baseUrlProblem(raw) {
  const text = String(raw ?? '').trim();
  if (!text) return 'Confluence sitesinin adresini yazın (ör. https://wiki.kurum.local ya da https://kurum.atlassian.net/wiki).';
  if (text.length > 2048) return 'Adres en çok 2048 karakterdir.';
  let url;
  try {
    url = new URL(text);
  } catch {
    return 'Geçerli bir adres yazın (https:// ile başlar).';
  }
  if (url.protocol !== 'https:') return 'Adres https:// ile başlamalı; belirteç şifresiz bağlantıda gönderilmez.';
  if (url.username || url.password) return 'Adrese kullanıcı adı ya da parola yazılmaz; belirteç secrets dosyasındadır.';
  if (url.search || url.hash || text.includes('?') || text.includes('#')) return 'Adres soru işareti ya da # içermez; yalnız site ve bağlam yolu.';
  if (url.pathname.split('/').some((s) => s === '.' || s === '..')) return 'Adres . ya da .. içeremez.';
  const host = url.hostname.toLowerCase();
  if (host === 'localhost' || host.endsWith('.localhost')) return 'Bu makinenin kendisi bağlanamaz; Confluence sunucusunu yazın.';
  return null;
}

/** Space keys of a textarea: one per line or comma-separated, blanks dropped, duplicates removed. */
export function parseSpaceKeys(text) {
  return [...new Set(String(text ?? '').split(/[\r\n,]+/).map((key) => key.trim()).filter(Boolean))];
}

/** The Confluence form as POST/PUT /v1/admin/connectors takes it (ADR-0044); same contract as connectorRequest. */
function confluenceRequest(form, original) {
  const problems = {};
  const name = blank(form.name);
  if (!name) problems.name = 'Ad boş olamaz.';
  else if (name.length > 100) problems.name = 'Ad en çok 100 karakterdir.';
  else if (NAME_HIDDEN.test(name)) problems.name = 'Ad görünmeyen denetim karakteri içeremez.';

  const baseUrl = blank(form.baseUrl).replace(/\/+$/, '');
  const urlIssue = baseUrlProblem(baseUrl);
  if (urlIssue) problems.baseUrl = urlIssue;

  const deployment = form.deployment === 'CLOUD' ? 'CLOUD' : form.deployment === 'DATA_CENTER' ? 'DATA_CENTER' : null;
  if (!deployment) problems.deployment = 'Kurulum türünü seçin.';

  const username = blank(form.username);
  if (deployment === 'CLOUD') {
    if (!EMAIL.test(username)) problems.username = 'Cloud için hesabın e-posta adresini yazın (API belirteci bu hesabındır).';
  } else if (username) {
    problems.username = 'Data Center\'da kişisel erişim belirteci hesabı zaten tanır; bu alan boş kalmalı.';
  }

  const spaceKeys = parseSpaceKeys(form.spaceKeys);
  if (!spaceKeys.length) problems.spaceKeys = 'En az bir alan (space) anahtarı yazın, ör. IK.';
  else if (spaceKeys.length > MAX_SPACES) problems.spaceKeys = `En çok ${MAX_SPACES} alan anahtarı.`;
  else if (spaceKeys.some((key) => !SPACE_KEY.test(key))) problems.spaceKeys = 'Alan anahtarı yalnız harf, rakam ve _ ~ . - içerir.';

  const label = blank(form.label);
  if (label && !LABEL.test(label)) problems.label = 'Etiket boşluksuz tek sözcüktür (harf, rakam, _ . : -).';

  let ancestorId = null;
  if (blank(form.ancestorId)) {
    ancestorId = Number(blank(form.ancestorId));
    if (!Number.isSafeInteger(ancestorId) || ancestorId <= 0) problems.ancestorId = 'Üst sayfanın kimliği pozitif bir sayıdır.';
  }

  let pageSize = null;
  if (blank(form.pageSize)) {
    pageSize = Number(blank(form.pageSize));
    if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > PAGE_SIZE_MAX) problems.pageSize = `İstek başına sonuç 1 ile ${PAGE_SIZE_MAX} arasında olmalı.`;
  }

  const credentialRef = blank(form.credentialRef);
  if (!CREDENTIAL_REF.test(credentialRef)) {
    problems.credentialRef = 'SECRET_CONNECTOR_ ile başlayan, büyük harf, rakam ve _ içeren bir dosya adı yazın. Belirtecin kendisini değil.';
  }

  const collectionId = original ? original.collectionId : blank(form.collectionId);
  if (!UUID_PATTERN.test(String(collectionId ?? ''))) problems.collectionId = 'Hedef bilgi alanını seçin.';

  let maxFileBytes = null;
  if (blank(form.maxFileMb)) {
    const mb = Number(blank(form.maxFileMb).replace(',', '.'));
    if (!Number.isFinite(mb) || mb <= 0 || mb > MAX_FILE_MB) problems.maxFileMb = `En büyük dosya 0 ile ${MAX_FILE_MB} MB arasında olmalı.`;
    else maxFileBytes = Math.max(1, Math.round(mb * 1024 * 1024));
  }

  const schedule = Number(blank(form.scheduleMinutes));
  if (!Number.isInteger(schedule) || schedule < SCHEDULE_MIN || schedule > SCHEDULE_MAX) {
    problems.scheduleMinutes = `Eşitleme aralığı ${SCHEDULE_MIN} ile ${SCHEDULE_MAX} dakika (1 hafta) arasında bir tam sayıdır.`;
  }

  if (Object.keys(problems).length) return { problems, body: null };
  return {
    problems,
    body: {
      type: 'CONFLUENCE', name, username: deployment === 'CLOUD' ? username : null, credentialRef, collectionId, maxFileBytes,
      scheduleMinutes: schedule, enabled: form.enabled !== false,
      confluence: {
        baseUrl, deployment, spaceKeys, label: label || null, ancestorId, includeAttachments: form.includeAttachments === true,
        skipRestricted: form.skipRestricted !== false, pageSize,
      },
    },
  };
}

/**
 * The SharePoint site as SharePointSite accepts it: https, a *.sharepoint.com host, no port, user info, query or
 * fragment, plain path segments (e.g. /sites/ik or a OneDrive's /personal/...). Returns the problem text or null.
 */
export function siteUrlProblem(raw) {
  const text = String(raw ?? '').trim();
  if (!text) return null;
  if (text.length > 2048) return 'Adres en çok 2048 karakterdir.';
  let url;
  try {
    url = new URL(text);
  } catch {
    return 'Geçerli bir adres yazın (https:// ile başlar).';
  }
  if (url.protocol !== 'https:') return 'Adres https:// ile başlamalı.';
  if (url.username || url.password) return 'Adrese kullanıcı adı ya da parola yazılmaz.';
  if (url.port) return 'Adres port içermez.';
  if (url.search || url.hash || text.includes('?') || text.includes('#')) return 'Adres soru işareti ya da # içermez; yalnız site yolu.';
  if (!url.hostname.toLowerCase().endsWith('.sharepoint.com')) return 'SharePoint Online adresi yazın (…sharepoint.com).';
  const segments = url.pathname.replace(/\/+$/, '').split('/').slice(1).filter((s) => s !== '');
  if (segments.some((s) => !SITE_SEGMENT.test(s) || s === '.' || s === '..')) {
    return 'Site yolu yalnız harf, rakam ve . _ ~ - içerir (ör. /sites/ik); boşluklu ya da kodlanmış adres kullanmayın.';
  }
  return null;
}

/** Library names of a textarea: one per line, blanks dropped, duplicates removed. */
export function parseDrives(text) {
  return [...new Set(String(text ?? '').split(/\r?\n/).map((name) => name.trim()).filter(Boolean))];
}

/** The SharePoint form as POST/PUT /v1/admin/connectors takes it (ADR-0045); same contract as connectorRequest. */
function sharePointRequest(form, original) {
  const problems = {};
  const name = blank(form.name);
  if (!name) problems.name = 'Ad boş olamaz.';
  else if (name.length > 100) problems.name = 'Ad en çok 100 karakterdir.';
  else if (NAME_HIDDEN.test(name)) problems.name = 'Ad görünmeyen denetim karakteri içeremez.';

  const siteUrl = blank(form.siteUrl).replace(/\/+$/, '');
  const siteId = blank(form.siteId);
  if (!siteUrl && !siteId) problems.siteUrl = 'Sitenin adresini yazın (ör. https://kurum.sharepoint.com/sites/ik) ya da site kimliğini.';
  else if (siteProblem(siteUrl)) problems.siteUrl = siteProblem(siteUrl);
  if (siteId && !SITE_ID.test(siteId)) problems.siteId = 'Site kimliği "host,guid,guid" biçimindedir (Graph\'taki id).';

  const drives = parseDrives(form.drives);
  if (drives.length > MAX_DRIVES) problems.drives = `En çok ${MAX_DRIVES} belge kitaplığı.`;
  else if (drives.some((d) => d.length > 255 || DRIVE_HIDDEN.test(d))) problems.drives = 'Kitaplık adı en çok 255 karakterdir, denetim karakteri içermez.';

  const prefix = normalizePrefix(form.pathPrefix);
  if (prefix.problem) problems.pathPrefix = prefix.problem;

  const includeGlobs = parseGlobs(form.includeGlobs);
  const excludeGlobs = parseGlobs(form.excludeGlobs);
  const includeIssue = globsProblem(includeGlobs);
  const excludeIssue = globsProblem(excludeGlobs);
  if (includeIssue) problems.includeGlobs = includeIssue;
  if (excludeIssue) problems.excludeGlobs = excludeIssue;

  const tenantId = blank(form.tenantId);
  if (tenantId && !UUID_PATTERN.test(tenantId)) problems.tenantId = 'Dizin (tenant) kimliği bir GUID\'dir; boş bırakılırsa kurulumun Entra kiracısı kullanılır.';
  const clientId = blank(form.clientId);
  if (!UUID_PATTERN.test(clientId)) problems.clientId = 'Bağlayıcının uygulama kaydının (application/client) kimliğini yazın; bir GUID.';

  const authMethod = form.authMethod === 'CLIENT_SECRET' ? 'CLIENT_SECRET' : form.authMethod === 'CERTIFICATE' ? 'CERTIFICATE' : null;
  if (!authMethod) problems.authMethod = 'Kimlik doğrulama yöntemini seçin.';

  const credentialRef = blank(form.credentialRef);
  if (!CREDENTIAL_REF.test(credentialRef)) {
    problems.credentialRef = 'SECRET_CONNECTOR_ ile başlayan, büyük harf, rakam ve _ içeren bir dosya adı yazın. Anahtarın ya da gizlinin kendisini değil.';
  }

  const collectionId = original ? original.collectionId : blank(form.collectionId);
  if (!UUID_PATTERN.test(String(collectionId ?? ''))) problems.collectionId = 'Hedef bilgi alanını seçin.';

  let maxFileBytes = null;
  if (blank(form.maxFileMb)) {
    const mb = Number(blank(form.maxFileMb).replace(',', '.'));
    if (!Number.isFinite(mb) || mb <= 0 || mb > MAX_FILE_MB) problems.maxFileMb = `En büyük dosya 0 ile ${MAX_FILE_MB} MB arasında olmalı.`;
    else maxFileBytes = Math.max(1, Math.round(mb * 1024 * 1024));
  }

  const schedule = Number(blank(form.scheduleMinutes));
  if (!Number.isInteger(schedule) || schedule < SCHEDULE_MIN || schedule > SCHEDULE_MAX) {
    problems.scheduleMinutes = `Eşitleme aralığı ${SCHEDULE_MIN} ile ${SCHEDULE_MAX} dakika (1 hafta) arasında bir tam sayıdır.`;
  }

  if (Object.keys(problems).length) return { problems, body: null };
  return {
    problems,
    body: {
      type: 'SHAREPOINT', name, pathPrefix: prefix.value, includeGlobs, excludeGlobs, credentialRef, collectionId, maxFileBytes,
      scheduleMinutes: schedule, enabled: form.enabled !== false,
      sharePoint: {
        siteUrl: siteUrl || null, siteId: siteId || null, drives, tenantId: tenantId || null, clientId, authMethod,
        skipUniquePermissions: form.skipUnique !== false,
      },
    },
  };
}

function siteProblem(siteUrl) {
  return siteUrl ? siteUrlProblem(siteUrl) : null;
}

/** The form values of a connector being edited (the inverse of connectorRequest). */
export function connectorForm(connector, type = 'SMB') {
  const confluence = connector?.confluence ?? null;
  const confluenceValues = {
    baseUrl: confluence?.baseUrl ?? '', deployment: confluence?.deployment ?? 'DATA_CENTER',
    spaceKeys: (confluence?.spaceKeys ?? []).join('\n'), label: confluence?.label ?? '',
    ancestorId: confluence?.ancestorId ? String(confluence.ancestorId) : '', includeAttachments: confluence?.includeAttachments === true,
    skipRestricted: confluence ? confluence.skipRestricted !== false : true,
    pageSize: confluence?.pageSize && confluence.pageSize !== 50 ? String(confluence.pageSize) : '',
  };
  const sharePoint = connector?.sharePoint ?? null;
  const sharePointValues = {
    siteUrl: sharePoint?.siteUrl ?? '', siteId: sharePoint?.siteId ?? '', drives: (sharePoint?.drives ?? []).join('\n'),
    tenantId: sharePoint?.tenantId ?? '', clientId: sharePoint?.clientId ?? '', authMethod: sharePoint?.authMethod ?? 'CERTIFICATE',
    skipUnique: sharePoint ? sharePoint.skipUniquePermissions !== false : true,
  };
  const known = (value) => (value === 'CONFLUENCE' || value === 'SHAREPOINT' ? value : 'SMB');
  if (!connector) {
    const kind = known(type);
    return { type: kind, name: '', host: '', port: '', share: '', pathPrefix: '', domain: '', username: '', credentialRef: DEFAULT_CREDENTIAL[kind],
      collectionId: '', includeGlobs: '', excludeGlobs: '', maxFileMb: '', scheduleMinutes: '60', enabled: true, ...confluenceValues,
      ...sharePointValues };
  }
  const mb = Number.isFinite(connector.maxFileBytes) ? connector.maxFileBytes / 1024 / 1024 : null;
  return {
    type: known(connector.type), ...confluenceValues, ...sharePointValues,
    name: connector.name ?? '', host: connector.host ?? '', port: connector.port && connector.port !== 445 ? String(connector.port) : '',
    share: connector.share ?? '', pathPrefix: connector.pathPrefix ?? '', domain: connector.domain ?? '', username: connector.username ?? '',
    credentialRef: connector.credentialRef ?? '', collectionId: connector.collectionId ?? '',
    includeGlobs: (connector.includeGlobs ?? []).join('\n'), excludeGlobs: (connector.excludeGlobs ?? []).join('\n'),
    maxFileMb: mb === null ? '' : String(Math.round(mb * 10) / 10), scheduleMinutes: String(connector.scheduleMinutes ?? 60),
    enabled: connector.enabled !== false,
  };
}

/** The form field an error code points to, so the message lands under the right input; null for a screen-wide one. */
export const ERROR_FIELDS = Object.freeze({
  14002: 'name', 14003: 'pathPrefix', 14004: 'host', 14005: 'collectionId', 14006: 'collectionId', 14009: 'includeGlobs',
  14010: 'maxFileMb', 14013: 'baseUrl', 14014: 'baseUrl',
});

/**
 * A Confluence connector has no host field: the site URL carries it (14004 lands there). A SharePoint connector's site
 * URL takes 14004, 14013 and 14014.
 */
export function errorField(code, type = 'SMB') {
  if (type === 'CONFLUENCE' && Number(code) === 14004) return 'baseUrl';
  if (type === 'SHAREPOINT' && [14004, 14013, 14014].includes(Number(code))) return 'siteUrl';
  return ERROR_FIELDS[code] ?? null;
}

/** "Sayfa 2 / 5" and whether there is a page before or after (ConnectorPageResponse.page). */
export function pageState(page) {
  const number = Number.isInteger(page?.number) ? page.number : 0;
  const total = Number.isInteger(page?.totalPages) ? page.totalPages : 0;
  return { number, total, prev: number > 0, next: number + 1 < total, label: total ? `Sayfa ${number + 1} / ${total}` : 'Kayıt yok' };
}

/** A schedule in words: "60 dakikada bir", "6 saatte bir", "günde bir". */
export function scheduleText(minutes) {
  if (!Number.isFinite(minutes)) return '';
  if (minutes % 1440 === 0) return minutes === 1440 ? 'günde bir' : `${minutes / 1440} günde bir`;
  if (minutes % 60 === 0) return `${minutes / 60} saatte bir`;
  return `${minutes} dakikada bir`;
}
