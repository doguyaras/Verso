// The activity centre's pure logic (ADR-0058): words and tones for the server's fixed values, the status strip, the
// tabs and filters, the query string and the day groups of the timeline. The server sends no account id; this module
// never adds one, and every text it builds is shown as text (render.js el), never as markup.

/** The tabs: a category of GET /v1/admin/activity, or all of them. "Onay kutusu" is a column of its own. */
export const TABS = Object.freeze([
  Object.freeze({ id: '', label: 'Tümü' }),
  Object.freeze({ id: 'RUN', label: 'Çalışmalar' }),
  Object.freeze({ id: 'BACKGROUND', label: 'Arka plan işleri' }),
  Object.freeze({ id: 'HOOK', label: 'Kancalar' }),
  Object.freeze({ id: 'KNOWLEDGE', label: 'Bilgi değişiklikleri' }),
]);

export const STATUSES = Object.freeze({
  RUNNING: Object.freeze({ label: 'Sürüyor', tone: 'info' }),
  SUCCEEDED: Object.freeze({ label: 'Tamamlandı', tone: 'ok' }),
  PARTIAL: Object.freeze({ label: 'Kısmen', tone: 'wait' }),
  FAILED: Object.freeze({ label: 'Hata', tone: 'bad' }),
  REGRESSION: Object.freeze({ label: 'Gerileme', tone: 'bad' }),
  PENDING: Object.freeze({ label: 'Onay bekliyor', tone: 'wait' }),
  INFO: Object.freeze({ label: 'Bilgi', tone: 'neutral' }),
});

export const CATEGORIES = Object.freeze({
  RUN: 'Çalışma', BACKGROUND: 'Arka plan', HOOK: 'Kanca', KNOWLEDGE: 'Bilgi', APPROVAL: 'Onay',
});

export const TYPES = Object.freeze({
  SKILL_RUN: 'Beceri çalışması', REVIEW_RUN: 'İnceleme çalışması', CONNECTOR_SYNC: 'Bağlayıcı senkronu',
  GOLDEN_EVAL: 'Gece altın değerlendirmesi', KNOWLEDGE_HEALTH: 'Bilgi sağlığı taraması',
  SYSTEM_MODEL: 'Sistem modeli çıkarımı', HOOK_ACTION: 'Kanca', DOCUMENTS_ADDED: 'Belge eklendi',
  DOCUMENTS_CHANGED: 'Belge değişti', DOCUMENTS_DELETED: 'Belge silindi', CARD_STALE: 'Onaylı bilgi eskidi',
  PROCEDURE_STALE: 'Prosedür eskidi', CARD_TO_VERIFY: 'Onaylı bilgi önerisi', CARD_DISPUTED: 'İtiraz edilen bilgi',
  PROCEDURE_APPROVAL: 'Prosedür', BULK_DELETION: 'Toplu silme', CONTRADICTION: 'Çelişki',
});

/** The status filter's choices, in the order of the dropdown. */
export const STATUS_FILTERS = Object.freeze(['', 'FAILED', 'REGRESSION', 'PARTIAL', 'RUNNING', 'PENDING', 'SUCCEEDED', 'INFO']);
/** The date filter's choices in days (the server allows at most 90). */
export const RANGES = Object.freeze([1, 7, 30, 90]);
export const PAGE_SIZE = 25;
const SCREEN = /^[a-z][a-z0-9-]{1,30}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const ID = /^[a-z][a-z0-9-]{1,30}\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export function statusLabel(status) {
  return STATUSES[status]?.label ?? String(status ?? '');
}

export function statusTone(status) {
  return STATUSES[status]?.tone ?? 'neutral';
}

/** A known type in words; an unknown one (a newer server, another branch's source) as it is. */
export function typeLabel(type) {
  return TYPES[type] ?? String(type ?? '');
}

export function categoryLabel(category) {
  return CATEGORIES[category] ?? String(category ?? '');
}

/**
 * The status strip: calm ("Her şey yolunda") without anything needing a person, else "N iş dikkat istiyor" with the
 * server's reasons joined. Unknown or missing values count as nothing; a partial answer says so.
 */
export function statusStrip(summary) {
  const attention = Number.isInteger(summary?.attention) && summary.attention > 0 ? summary.attention : 0;
  const reasons = (Array.isArray(summary?.reasons) ? summary.reasons : []).filter((r) => typeof r === 'string').slice(0, 3);
  const last = summary?.last24h ?? {};
  const n = (v) => (Number.isInteger(v) && v >= 0 ? v : 0);
  return {
    calm: attention === 0,
    title: attention === 0 ? 'Her şey yolunda' : `${attention} iş dikkat istiyor`,
    reasons: reasons.join(' · '),
    counts: [`${n(last.runs)} çalışma`, `${n(last.background)} arka plan işi`, `${n(last.hooks)} kanca`,
      `${n(last.knowledge)} bilgi değişikliği`],
    partial: summary?.partial === true,
    approvals: n(summary?.pendingApprovals),
  };
}

/** The ISO day (yyyy-mm-dd) of a date in the browser's time zone. */
function isoDay(date) {
  const pad = (v) => String(v).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/**
 * The query string of one request: only known words, a day range of 1..90 days ending today, a UUID collection, the
 * page. Anything else is left out rather than sent: the server would refuse it anyway (400 11110).
 */
export function activityQuery({ category = '', status = '', days = 7, collection = '', page = 0 } = {}, today = new Date()) {
  const params = new URLSearchParams();
  if (TABS.some((t) => t.id === category) && category) params.set('category', category);
  if (category === 'APPROVAL') params.set('category', 'APPROVAL');
  if (STATUS_FILTERS.includes(status) && status) params.set('status', status);
  const span = RANGES.includes(days) ? days : 7;
  const from = new Date(today.getFullYear(), today.getMonth(), today.getDate() - (span - 1));
  params.set('from', isoDay(from));
  params.set('to', isoDay(today));
  if (UUID.test(collection)) params.set('collection', collection);
  params.set('page', String(Number.isInteger(page) && page >= 0 && page < 20 ? page : 0));
  params.set('size', String(PAGE_SIZE));
  return params.toString();
}

/** An entry id for GET /v1/admin/activity/{id}, or null when it is not "{source}.{uuid}". */
export function detailId(id) {
  return typeof id === 'string' && ID.test(id) ? id : null;
}

/** Day headings: "Bugün", "Dün", else the date in Turkish; items keep their order (newest first). */
/** How many entries "Etkinlik merkezi" shows before "Daha fazla göster" (ADR-0058 Sadeleştirme). */
export const FIRST_VIEW = 5;

/** The entries the timeline draws: the newest FIRST_VIEW until the user asks for the whole page. */
export function visibleItems(items, expanded = false) {
  const list = Array.isArray(items) ? items : [];
  return expanded ? list : list.slice(0, FIRST_VIEW);
}

/** Whether any filter differs from the defaults (category all, status all, 7 days): the "Filtrele" label shows it. */
export function filtersActive(state) {
  return Boolean(state?.category) || Boolean(state?.status) || (state?.days ?? 7) !== 7;
}

export function groupByDay(items, today = new Date()) {
  const groups = [];
  const todayKey = isoDay(today);
  const yesterday = isoDay(new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1));
  for (const item of Array.isArray(items) ? items : []) {
    const at = new Date(item?.startedAt);
    if (Number.isNaN(at.getTime())) continue;
    const key = isoDay(at);
    const label = key === todayKey ? 'Bugün' : key === yesterday ? 'Dün'
      : at.toLocaleDateString('tr-TR', { day: 'numeric', month: 'long', weekday: 'long' });
    const last = groups.at(-1);
    if (last?.key === key) last.items.push(item);
    else groups.push({ key, label, items: [item] });
  }
  return groups;
}

/** The time of a line: hh:mm, or "—". */
export function timeOf(iso) {
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? '—' : at.toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit' });
}

/** How long it took, in Turkish words; null while running or without an end. */
export function durationText(startedAt, finishedAt) {
  const ms = new Date(finishedAt) - new Date(startedAt);
  if (!Number.isFinite(ms) || ms < 0) return null;
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s} sn`;
  const m = Math.floor(s / 60);
  return m < 60 ? `${m} dk ${String(s % 60).padStart(2, '0')} sn` : `${Math.floor(m / 60)} sa ${m % 60} dk`;
}

/** The actor in words: never a person (the server sends a team, a role or a system component). */
export function actorText(actor) {
  if (!actor || typeof actor.label !== 'string') return 'Sistem';
  return actor.kind === 'TEAM' ? actor.label : actor.kind === 'ROLE' ? actor.label : `${actor.label} (sistem)`;
}

/** The panel route a line opens: a known screen word and, when valid, its id as a parameter; else null. */
export function linkOf(item) {
  const link = item?.link;
  if (!link || typeof link.screen !== 'string' || !SCREEN.test(link.screen)) return null;
  return { screen: link.screen, id: UUID.test(String(link.targetId ?? '')) ? link.targetId : null };
}

/** The audit reference in words, for the drawer ("Denetim kaydı: CONNECTOR · CONNECTOR_SYNC_FINISHED"). */
export function auditText(audit) {
  if (!audit || typeof audit.targetType !== 'string') return null;
  return [audit.targetType, audit.action].filter((v) => typeof v === 'string' && v).join(' · ');
}

/** The line's view model. */
export function itemModel(item) {
  return {
    id: detailId(item?.id),
    category: categoryLabel(item?.category),
    type: typeLabel(item?.type),
    title: typeof item?.title === 'string' ? item.title : typeLabel(item?.type),
    detail: typeof item?.detail === 'string' ? item.detail : '',
    actor: actorText(item?.actor),
    status: statusLabel(item?.status),
    tone: statusTone(item?.status),
    time: timeOf(item?.startedAt),
    duration: durationText(item?.startedAt, item?.finishedAt),
    attention: item?.attention === true,
    link: linkOf(item),
  };
}
