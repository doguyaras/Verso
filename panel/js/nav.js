// The panel's information architecture (ADR-0058, "Sadeleştirme"): "az göster, isteyince aç". The menu has at most five
// entries for everyone — Ana sayfa, Asistan, Beceriler, Bilgi, Geçmiş — and, for admins and operators, one "Yönetim"
// entry at the bottom that opens a hub of grouped cards (İçgörü, Bağlantılar, Erişim ve uyum, Kurulum). "Bilgi" is one
// entry; inside it, tabs lead to the knowledge screens the user may open. No group headings. Every screen keeps its own
// route, redirect and "g" shortcut, and the Ctrl+K palette lists them all.
// Screens of other branches slot in by feature detection: a screen is used only when its SCREENS entry and its
// renderer both exist. Pure functions: app.js draws what they return; scripts/panel.test.mjs checks them.
import { SCREENS } from './roles.js';

/** Whether these roles open the screen of this registry (roles.js canOpen, for any registry: tests pass their own). */
const allowed = (id, roles, screens) => Boolean(screens[id]?.roles.some((r) => roles.includes(r)));

/** The id of the hub screen behind the "Yönetim" entry. */
export const HUB_ID = 'yonetim';

/**
 * The menu in order. An item is a screen id, a slot (`{ slot, ids }`: the first id that exists is placed there), a
 * section (`{ section, title, icon, ids }`: one entry that opens the first screen of `ids` the user may open; the others
 * are its tabs) or an action (`{ action }`: a button, not a route).
 */
export const NAV = Object.freeze([
  'home',
  'ask',
  Object.freeze({ slot: 'skillGallery', ids: Object.freeze(['beceriler', 'skill-gallery', 'gallery']) }),
  Object.freeze({ section: 'bilgi', title: 'Bilgi', icon: 'files', ids: Object.freeze(['documents', 'knowledge', 'skills', 'systemmap']) }),
  Object.freeze({ action: 'history' }),
]);

/** The actions of the menu: they open something over the current screen. */
export const ACTIONS = Object.freeze({
  history: Object.freeze({ title: 'Geçmiş', icon: 'history', hint: 'G G' }),
});

/**
 * The "Yönetim" hub: grouped cards, each with a one-line description. Slots work as in NAV. A registered screen of the
 * group "insight" or "admin" that no card names is added to the group of the same id ("admin" → "Diğer").
 */
export const HUB = Object.freeze([
  Object.freeze({ id: 'insight', title: 'İçgörü', items: Object.freeze(['activity', 'dashboard', 'health']) }),
  Object.freeze({ id: 'connections', title: 'Bağlantılar', items: Object.freeze([
    Object.freeze({ slot: 'providers', ids: Object.freeze(['providers', 'saglayicilar', 'gateway']) }), 'connectors']) }),
  Object.freeze({ id: 'access', title: 'Erişim ve uyum', items: Object.freeze(['collections', 'subjects', 'trust']) }),
  Object.freeze({ id: 'setup', title: 'Kurulum', items: Object.freeze(['setup', 'system']) }),
  Object.freeze({ id: 'admin', title: 'Diğer', items: Object.freeze([]) }),
]);

/** One line per hub card: what the screen is for, in the user's words. */
export const DESCRIPTIONS = Object.freeze({
  activity: 'Çalışmalar, arka plan işleri ve onay bekleyenler tek akışta.',
  dashboard: 'Bilgi sağlığı, kaynaklı cevap oranı ve benimseme bir bakışta.',
  health: 'Cevapsız sorular, eskiyen belgeler ve çelişkiler.',
  providers: 'Model bağlantıları, görev yönlendirme ve bütçe.',
  connectors: 'Dosya sunucusu, SharePoint ve diğer kaynaklardan eşitleme.',
  collections: 'Bilgi alanları ve kimin neyi görebileceği.',
  subjects: 'Kişisel veri talepleri, saklama ve silme.',
  trust: 'Çalışma modu, denetim kaydı ve güven raporu.',
  setup: 'Kurulumun adımları ve kalan işler.',
  system: 'Çalışma modu ve sistemin durumu.',
});

/** The phone tab bar holds the menu's screen entries (at most four); "Yönetim" and "Geçmiş" are in the account menu. */
export const TAB_LIMIT = 4;

/** Old routes keep working (bookmarks, links in exported reports): they are replaced by the new ones. */
export const REDIRECTS = Object.freeze({
  overview: Object.freeze({ to: 'home' }),
  insights: Object.freeze({ to: 'dashboard', params: Object.freeze({ tab: 'value' }) }),
  bilgi: Object.freeze({ to: 'documents' }),
});

/** Screens that exist but have no menu, tab or hub entry of their own (reached from another screen, the palette or "g"). */
export const HIDDEN = Object.freeze(['chunks']);

const isSection = (item) => typeof item === 'object' && 'section' in item;

/** Whether a screen id is usable: described in roles.js and drawn by a renderer app.js knows. */
export function exists(id, available, screens = SCREENS) {
  return Boolean(screens[id]) && available.has(id);
}

/** The id that fills a slot, or null when no branch has registered one. */
export function slotId(item, available, screens = SCREENS) {
  return item.ids.find((id) => exists(id, available, screens)) ?? null;
}

const named = () => new Set([
  ...NAV.flatMap((item) => (typeof item === 'string' ? [item] : item.ids ?? [])),
  ...HUB.flatMap((g) => g.items.flatMap((item) => (typeof item === 'string' ? [item] : item.ids))),
  HUB_ID,
]);

/** Registered screens no item names, usable by these roles, whose `group` is one of `groups`. */
function unplaced(groups, roles, available, screens) {
  const known = named();
  return Object.keys(screens).filter((id) => !known.has(id) && !HIDDEN.includes(id) && exists(id, available, screens)
    && allowed(id, roles, screens) && groups.includes(screens[id].group ?? 'work'));
}

/** The screens of a section these roles may open, in order (plus unplaced screens of the "knowledge" group). */
function sectionIds(section, roles, available, screens) {
  return [...section.ids, ...unplaced(['knowledge'], roles, available, screens)]
    .filter((id) => exists(id, available, screens) && allowed(id, roles, screens));
}

/**
 * The hub for these roles: groups with the cards the user may open; empty groups are left out. Each card:
 * { id, title, icon, description }.
 */
export function hubModel(roles, available, screens = SCREENS) {
  const card = (id) => ({ id, title: screens[id].title, icon: screens[id].icon, description: DESCRIPTIONS[id] ?? '' });
  return HUB.map((group) => {
    const ids = group.items.map((item) => (typeof item === 'string' ? item : slotId(item, available, screens)))
      .filter((id) => id && exists(id, available, screens) && allowed(id, roles, screens));
    ids.push(...unplaced([group.id], roles, available, screens));
    return { id: group.id, title: group.title, cards: ids.map(card) };
  }).filter((group) => group.cards.length > 0);
}

/** The screen ids of the hub for these roles, in order. */
export function hubScreens(roles, available, screens = SCREENS) {
  return hubModel(roles, available, screens).flatMap((g) => g.cards.map((c) => c.id));
}

/**
 * The menu for these roles: { id: 'main', entries } and, for whoever has a hub, { id: 'manage', entries: [Yönetim] }.
 * Each entry: { kind: 'screen', id, title, icon, key, covers } (covers: the screen ids that mark it as current) or
 * { kind: 'action', id, title, icon, hint }. `available` is the set of screen ids app.js can render.
 */
export function navModel(roles, available, screens = SCREENS) {
  const screen = (id, extra = {}) => ({ kind: 'screen', id, title: screens[id].title, icon: screens[id].icon, key: screens[id].key,
    covers: [id], ...extra });
  const entries = [];
  for (const item of NAV) {
    if (typeof item === 'string') {
      if (exists(item, available, screens) && allowed(item, roles, screens)) entries.push(screen(item));
    } else if (item.slot) {
      const id = slotId(item, available, screens);
      if (id && allowed(id, roles, screens)) entries.push(screen(id));
    } else if (isSection(item)) {
      const ids = sectionIds(item, roles, available, screens);
      if (ids.length) entries.push(screen(ids[0], { title: item.title, icon: item.icon, covers: ids }));
    } else if (item.action) {
      // Unplaced "work" screens of another branch go before the history action.
      for (const id of unplaced(['work'], roles, available, screens)) entries.push(screen(id));
      entries.push({ kind: 'action', id: item.action, ...ACTIONS[item.action] });
    }
  }
  const groups = [{ id: 'main', entries }];
  const hub = hubScreens(roles, available, screens);
  if (hub.length && exists(HUB_ID, available, screens) && allowed(HUB_ID, roles, screens)) {
    groups.push({ id: 'manage', entries: [screen(HUB_ID, { covers: [HUB_ID, ...hub] })] });
  }
  return groups.filter((g) => g.entries.some((e) => e.kind === 'screen'));
}

/** The menu's screen entries in order (the targets of its links). */
export function menuScreens(roles, available, screens = SCREENS) {
  return navModel(roles, available, screens).flatMap((g) => g.entries.filter((e) => e.kind === 'screen').map((e) => e.id));
}

/**
 * Every screen these roles can reach, in menu order: the menu's entries with their section tabs, the hub, then the
 * screens without an entry. The palette's pages and the "g" shortcuts come from here, so nothing is lost by hiding it.
 */
export function reachableScreens(roles, available, screens = SCREENS) {
  const ids = [
    ...navModel(roles, available, screens).flatMap((g) => g.entries.filter((e) => e.kind === 'screen').flatMap((e) => (e.id === HUB_ID ? [e.id] : e.covers))),
    ...hubScreens(roles, available, screens),
    ...HIDDEN.filter((id) => exists(id, available, screens) && allowed(id, roles, screens)),
  ];
  return [...new Set(ids)];
}

/** The phone tab bar: the menu's main screen entries (Ana sayfa, Asistan, Beceriler, Bilgi), at most TAB_LIMIT. */
export function tabModel(roles, available, screens = SCREENS) {
  const main = navModel(roles, available, screens).find((g) => g.id === 'main')?.entries ?? [];
  return main.filter((e) => e.kind === 'screen').slice(0, TAB_LIMIT)
    .map((e) => ({ id: e.id, label: e.title, icon: e.icon, covers: e.covers }));
}

/** The tabs of the section a screen belongs to (null when it has no section or only one tab): [{ id, title }]. */
export function sectionTabs(screen, roles, available, screens = SCREENS) {
  for (const item of NAV.filter(isSection)) {
    const ids = sectionIds(item, roles, available, screens);
    if (ids.includes(screen) && ids.length > 1) return ids.map((id) => ({ id, title: screens[id].title }));
  }
  return null;
}

/** Whether a screen is one of the hub's (its page then offers "← Yönetim"). */
export function inHub(screen, roles, available, screens = SCREENS) {
  return hubScreens(roles, available, screens).includes(screen);
}

/**
 * The screen a hash opens: a redirect first (with its params), then the screen when this user may open it, else the
 * home. `redirected` tells app.js to replace the hash, so "back" does not loop.
 */
export function resolveRoute(hash, roles, available, screens = SCREENS) {
  const wanted = String(hash ?? '').replace(/^#\/?/, '').split(/[?/]/)[0] || 'home';
  const redirect = REDIRECTS[wanted];
  const target = redirect ? redirect.to : wanted;
  const fallback = menuScreens(roles, available, screens)[0] ?? 'home';
  const ok = exists(target, available, screens) && allowed(target, roles, screens);
  return { screen: ok ? target : fallback, params: ok && redirect?.params ? { ...redirect.params } : null,
    redirected: Boolean(redirect) || !ok };
}

/** The screen the "g" + key shortcut opens, among every screen this user can reach. */
export function shortcutTarget(key, roles, available, screens = SCREENS) {
  return reachableScreens(roles, available, screens).find((id) => screens[id].key === key) ?? null;
}
