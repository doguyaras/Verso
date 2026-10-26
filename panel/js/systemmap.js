// The "Sistem haritası" (ADR-0054) without the DOM: the Turkish words of the entity types and relations, how an
// entity's links are grouped for the detail view, how a source is named, the stats line, the suggestion lines and the
// admin API paths. The light system model is extracted from documents by rules; it never holds a person's name, only a
// job title or a department. The server reads everything with the caller's own access; these only arrange what came back.

export const TYPES = Object.freeze({
  SYSTEM: { label: 'Sistem', plural: 'Sistemler', icon: 'server' },
  SCREEN: { label: 'Ekran', plural: 'Ekranlar', icon: 'monitor' },
  CODE: { label: 'Kod', plural: 'Kodlar', icon: 'command' },
  PROCESS: { label: 'Süreç', plural: 'Süreçler', icon: 'flag' },
  TERM: { label: 'Terim', plural: 'Terimler', icon: 'book' },
  ROLE: { label: 'Rol', plural: 'Roller', icon: 'user' },
  STEP: { label: 'Adım', plural: 'Adımlar', icon: 'chevronRight' },
});

/** The tabs of the screen, in order. A STEP is reached from its process, never listed on its own (the server agrees). */
export const TABS = Object.freeze(['SYSTEM', 'SCREEN', 'CODE', 'PROCESS', 'TERM', 'ROLE']);

export const SUGGESTION_STATES = Object.freeze(['DRAFT', 'ACCEPTED', 'REJECTED']);

/** The server's limits: entity list size and the length of a key, a prefix or a lookup text. */
export const LIMITS = Object.freeze({ list: 200, listDefault: 100, text: 200 });

/**
 * The link groups of the detail view, in display order: relation and direction (outgoing = this entity -> the other).
 * DEFINES never appears here; definitions are their own list.
 */
export const RELATION_GROUPS = Object.freeze([
  { relation: 'CODE_OF', outgoing: true, label: 'Sistem' },
  { relation: 'PART_OF', outgoing: true, label: 'Sistem' },
  { relation: 'STEP_OF', outgoing: true, label: 'Süreç' },
  { relation: 'OWNED_BY', outgoing: true, label: 'Sorumlu' },
  { relation: 'PART_OF', outgoing: false, label: 'Ekranlar' },
  { relation: 'CODE_OF', outgoing: false, label: 'Kodlar' },
  { relation: 'STEP_OF', outgoing: false, label: 'Adımlar', ordered: true },
  { relation: 'NEXT_STEP', outgoing: false, label: 'Önceki adım' },
  { relation: 'NEXT_STEP', outgoing: true, label: 'Sonraki adım' },
  { relation: 'OWNED_BY', outgoing: false, label: 'Sorumlu olduğu süreç ve adımlar' },
]);

/** A suggested relation in words: "source <relation> target". */
export const RELATIONS = Object.freeze({
  STEP_OF: 'şu sürecin adımı',
  NEXT_STEP: 'sonraki adımı',
  CODE_OF: 'şu sistemin kodu',
  PART_OF: 'şu sistemin ekranı',
  OWNED_BY: 'sorumlusu',
  DEFINES: 'tanımı',
});

export function typeLabel(type) {
  return TYPES[type]?.label ?? 'Kayıt';
}

export function typePlural(type) {
  return TYPES[type]?.plural ?? 'Kayıtlar';
}

export function typeIcon(type) {
  return TYPES[type]?.icon ?? 'layers';
}

/**
 * The links of an entity detail in labelled groups, in RELATION_GROUPS order; the steps of a process by their ordinal
 * (a step without one goes last). A relation this panel does not know gets its own group under its code, so nothing
 * the server sent is silently dropped.
 */
export function relationGroups(detail) {
  const links = (detail?.links ?? []).filter((l) => l && l.relation !== 'DEFINES');
  const groups = [];
  const used = new Set();
  for (const group of RELATION_GROUPS) {
    const items = links.filter((l) => l.relation === group.relation && Boolean(l.outgoing) === group.outgoing);
    items.forEach((l) => used.add(l));
    if (!items.length) continue;
    const sorted = group.ordered ? [...items].sort((a, b) => ordinalOf(a) - ordinalOf(b)) : items;
    groups.push({ id: `${group.relation}:${group.outgoing ? 'out' : 'in'}`, label: group.label,
      items: sorted.map((l) => linkItem(l, group.ordered)) });
  }
  const rest = links.filter((l) => !used.has(l));
  for (const l of rest) {
    const id = `${l.relation}:${l.outgoing ? 'out' : 'in'}`;
    let group = groups.find((g) => g.id === id);
    if (!group) {
      group = { id, label: String(l.relation ?? 'İlişki'), items: [] };
      groups.push(group);
    }
    group.items.push(linkItem(l, false));
  }
  return groups;
}

function ordinalOf(link) {
  return Number.isFinite(link?.ordinal) ? link.ordinal : Number.MAX_SAFE_INTEGER;
}

function linkItem(link, numbered) {
  const name = link.label || link.key || '';
  return { type: link.type, key: link.key, text: numbered && Number.isFinite(link.ordinal) ? `${link.ordinal}. ${name}` : name };
}

/** "depo.txt, s. 2" or "Belge 1a2b3c4d, bölüm 3": the file name when the server sent one, else a short id. */
export function sourceLabel(source) {
  const name = source?.fileName || `Belge ${String(source?.documentId ?? '').slice(0, 8)}`;
  const where = `${source?.unit === 'SECTION' ? 'bölüm' : 's.'} ${source?.pageNumber ?? '?'}`;
  return `${name}, ${where}`;
}

/** The stats strip: one count per tab, the documents line and whether search enrichment is on. */
export function statsSummary(stats) {
  const entities = stats?.entities ?? {};
  const count = (value) => (Number.isFinite(value) ? value : 0);
  return {
    counts: TABS.map((type) => ({ type, label: typePlural(type), count: count(entities[type]) })),
    steps: count(entities.STEP),
    documents: `${count(stats?.documents)} belge çıkarıldı · ${count(stats?.pendingDocuments)} belge bekliyor`,
    enrichment: stats?.enrichmentEnabled === true ? 'Arama zenginleştirme açık' : 'Arama zenginleştirme kapalı',
    enrichmentOn: stats?.enrichmentEnabled === true,
    extractor: stats?.extractorVersion ? `Çıkarıcı sürümü ${stats.extractorVersion}` : null,
  };
}

/** One list line of an entity: its key, and its label when that says more. */
export function entityTitle(entity) {
  const key = String(entity?.key ?? '');
  const label = String(entity?.label ?? '');
  return label && label !== key ? `${key} · ${label}` : key || label;
}

/** "3 belge · 7 geçiş". */
export function entityCounts(entity) {
  const n = (value) => (Number.isFinite(value) ? value : 0);
  return `${n(entity?.documents)} belge · ${n(entity?.mentions)} geçiş`;
}

/** A DRAFT suggestion as the review list shows it. */
export function suggestionText(suggestion) {
  const side = (type, key) => `${String(key ?? '')} (${typeLabel(type)})`;
  return `${side(suggestion?.sourceType, suggestion?.sourceKey)} → ${RELATIONS[suggestion?.relation] ?? suggestion?.relation}`
    + ` → ${side(suggestion?.targetType, suggestion?.targetKey)}`;
}

/** The suggestions card is shown when the pass is on, or when earlier suggestions are still there. */
export function showSuggestions(settings, suggestions) {
  return settings?.enabled === true || (Array.isArray(suggestions) && suggestions.length > 0);
}

function text(value) {
  return String(value ?? '').trim().slice(0, LIMITS.text);
}

/** GET entities: the type only when it is a tab, the prefix encoded (or left out), the limit clamped to 1..200. */
export function entitiesPath(type, prefix, limit = LIMITS.listDefault) {
  const params = [];
  if (TABS.includes(type)) params.push(`type=${type}`);
  const p = text(prefix);
  if (p) params.push(`prefix=${encodeURIComponent(p)}`);
  const n = Math.trunc(Number(limit));
  params.push(`limit=${Math.min(LIMITS.list, Math.max(1, Number.isFinite(n) ? n : LIMITS.listDefault))}`);
  return `/v1/admin/system-map/entities?${params.join('&')}`;
}

/** GET entity: a known type (STEP included) and the key encoded; null when either is unusable. */
export function entityPath(type, key) {
  const k = String(key ?? '');
  if (!TYPES[type] || !k || k.length > LIMITS.text) return null;
  return `/v1/admin/system-map/entity?type=${type}&key=${encodeURIComponent(k)}`;
}

/** GET lookup (any signed-in user): the text trimmed to 1..200 characters; null when empty. */
export function lookupPath(query) {
  const q = text(query);
  return q ? `/v1/system-model/lookup?q=${encodeURIComponent(q)}` : null;
}

/** GET suggestions: only the server's own state names; anything else asks for the drafts. */
export function suggestionsPath(state) {
  return `/v1/admin/system-map/suggestions?state=${SUGGESTION_STATES.includes(state) ? state : 'DRAFT'}`;
}
