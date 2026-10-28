// The panel's information architecture (ADR-0058): a standalone "Ana sayfa", then four groups. "Çalış" and "Bilgi"
// are for everyone (a screen inside still needs its role); "İçgörü" and "Yönetim" are for verso-admin (and the
// operator's own screens). Screens of other branches slot in by feature detection: a screen is shown only when its
// SCREENS entry and its renderer both exist, so whichever branch merges first works and none has to edit this file.
// Pure functions: app.js draws what they return; scripts/panel.test.mjs checks them.
import { SCREENS } from './roles.js';

/** Whether these roles open the screen of this registry (roles.js canOpen, for any registry: tests pass their own). */
const allowed = (id, roles, screens) => Boolean(screens[id]?.roles.some((r) => roles.includes(r)));

/**
 * The menu groups in order. An item is a screen id, a slot (`{ slot, ids }`: the first id that exists is placed
 * there) or an action (`{ action }`: a button, not a route). A registered screen that no item names is appended to
 * the group its `group` says ("work" when it has none): the skills gallery lands under "Çalış", a provider screen with
 * `group: 'admin'` under "Yönetim".
 */
export const NAV = Object.freeze([
  Object.freeze({ id: 'home', title: null, items: Object.freeze(['home']) }),
  Object.freeze({ id: 'work', title: 'Çalış', items: Object.freeze(['ask',
    Object.freeze({ slot: 'skillGallery', ids: Object.freeze(['beceriler', 'skill-gallery', 'gallery']) }),
    Object.freeze({ action: 'history' })]) }),
  Object.freeze({ id: 'knowledge', title: 'Bilgi', items: Object.freeze(['documents', 'knowledge', 'skills', 'systemmap']) }),
  Object.freeze({ id: 'insight', title: 'İçgörü', items: Object.freeze(['activity', 'dashboard', 'health']) }),
  Object.freeze({ id: 'admin', title: 'Yönetim', items: Object.freeze([
    Object.freeze({ slot: 'providers', ids: Object.freeze(['providers', 'saglayicilar', 'gateway']) }),
    'connectors', 'collections', 'subjects', 'trust', 'system', 'setup']) }),
]);

/** The actions of the menu: they open something over the current screen. */
export const ACTIONS = Object.freeze({
  history: Object.freeze({ title: 'Geçmiş ve çıktılar', icon: 'history', hint: 'G G' }),
});

/** The phone tab bar: three screens and "Daha" (everything else, grouped like the menu). */
export const TABS = Object.freeze([
  Object.freeze({ id: 'home', label: 'Ana sayfa', icon: 'home' }),
  Object.freeze({ id: 'ask', label: 'Asistan', icon: 'chat' }),
  Object.freeze({ id: 'documents', label: 'Bilgi', icon: 'files' }),
]);
export const MORE = Object.freeze({ label: 'Daha', icon: 'menu' });

/** Old routes keep working (bookmarks, links in exported reports): they are replaced by the new ones. */
export const REDIRECTS = Object.freeze({
  overview: Object.freeze({ to: 'home' }),
  insights: Object.freeze({ to: 'dashboard', params: Object.freeze({ tab: 'value' }) }),
});

/** Screens that exist but have no menu entry of their own (reached from another screen, the palette or a shortcut). */
export const HIDDEN = Object.freeze(['chunks']);

const placedIds = () => new Set(NAV.flatMap((g) => g.items.flatMap((item) => (typeof item === 'string' ? [item]
  : item.slot ? item.ids : []))));

/** Whether a screen id is usable: described in roles.js and drawn by a renderer app.js knows. */
export function exists(id, available, screens = SCREENS) {
  return Boolean(screens[id]) && available.has(id);
}

/** The id that fills a slot, or null when no branch has registered one. */
export function slotId(item, available, screens = SCREENS) {
  return item.ids.find((id) => exists(id, available, screens)) ?? null;
}

/**
 * The menu for these roles: groups with their visible entries; empty groups are left out. `available` is the set of
 * screen ids app.js can render. Each entry: { kind: 'screen', id, title, icon, key } or { kind: 'action', id, title,
 * icon, hint }.
 */
export function navModel(roles, available, screens = SCREENS) {
  const placed = placedIds();
  const screen = (id) => ({ kind: 'screen', id, title: screens[id].title, icon: screens[id].icon, key: screens[id].key });
  return NAV.map((group) => {
    const entries = [];
    for (const item of group.items) {
      if (typeof item === 'string') {
        if (exists(item, available, screens) && allowed(item, roles, screens)) entries.push(screen(item));
      } else if (item.slot) {
        const id = slotId(item, available, screens);
        if (id && allowed(id, roles, screens)) entries.push(screen(id));
      } else if (item.action) {
        entries.push({ kind: 'action', id: item.action, ...ACTIONS[item.action] });
      }
    }
    // Registered screens no item names: appended to their group (feature detection for other branches).
    for (const id of Object.keys(screens)) {
      if (placed.has(id) || HIDDEN.includes(id) || !exists(id, available, screens) || !allowed(id, roles, screens)) continue;
      if ((screens[id].group ?? 'work') === group.id) entries.push(screen(id));
    }
    return { id: group.id, title: group.title, entries };
  }).filter((group) => group.entries.some((e) => e.kind === 'screen'));
}

/** The screens of the menu in order (palette pages, "g" shortcuts): every screen entry of navModel. */
export function menuScreens(roles, available, screens = SCREENS) {
  return navModel(roles, available, screens).flatMap((g) => g.entries.filter((e) => e.kind === 'screen').map((e) => e.id));
}

/** The phone tab bar: the TABS this user may open, then "Daha". */
export function tabModel(roles, available, screens = SCREENS) {
  return TABS.filter((tab) => exists(tab.id, available, screens) && allowed(tab.id, roles, screens));
}

/** What "Daha" lists: every menu entry that is not a tab, grouped like the menu. */
export function moreModel(roles, available, screens = SCREENS) {
  const tabs = new Set(TABS.map((t) => t.id));
  return navModel(roles, available, screens).map((g) => ({ ...g, entries: g.entries.filter((e) => !(e.kind === 'screen' && tabs.has(e.id))) }))
    .filter((g) => g.entries.length > 0);
}

/**
 * The screen a hash opens: a redirect first (with its params), then the screen when this user may open it, else the
 * first screen of the menu. `redirected` tells app.js to replace the hash, so "back" does not loop.
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

/** The screen the "g" + key shortcut opens, among the screens this user may open. */
export function shortcutTarget(key, roles, available, screens = SCREENS) {
  const all = [...menuScreens(roles, available, screens), ...HIDDEN.filter((id) => exists(id, available, screens) && allowed(id, roles, screens))];
  return all.find((id) => screens[id].key === key) ?? null;
}
