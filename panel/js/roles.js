// Role matrix of the panel, in one file (reference 17). A screen is shown when the user holds one of its roles; the
// API enforces the real rules (every request carries the token, ownership is checked on the server, ADR-0005). The
// panel only hides what a role cannot use. The panel roles come from the access token's realm_access.roles (Keycloak
// realm roles) or roles (Entra ID app roles, ADR-0041); a signed-in user without any Verso role is a "verso-user". The admin role is taken from GET /v1/info
// only (ADR-0019, ADR-0025): the server reads it through its configurable claim paths, so the panel shows the value
// dashboard exactly when the server would serve it, whatever IdP the installation uses.

export const ROLES = Object.freeze({
  USER: 'verso-user',
  OPERATOR: 'verso-operator',
  ADMIN: 'verso-admin',
});

/** The roles read from the token; verso-admin is not among them (it comes from /v1/info, withServerRoles). */
const TOKEN_ROLES = Object.freeze([ROLES.USER, ROLES.OPERATOR]);

// The screens (ADR-0058). Where each sits in the menu is nav.js's job; "group" names the menu group a screen belongs
// to ("work", "knowledge", "insight", "admin"), "key" is the second key of the "g" shortcut ("g g" opens the history
// drawer). A screen of another branch adds one entry here and one renderer in app.js; nav.js places it by its group.
// The API enforces the real rules: "Onaylı bilgiler", "Prosedürler" and "Sistem haritası" sit in "Bilgi" but their
// endpoints are verso-admin only (ADR-0049, ADR-0053, ADR-0054), so a user does not see them.
export const SCREENS = Object.freeze({
  home: { title: 'Ana sayfa', short: 'Ana sayfa', icon: 'home', key: 'o', roles: [ROLES.USER, ROLES.OPERATOR] },
  ask: { title: 'Asistan', short: 'Asistan', icon: 'chat', key: 's', roles: [ROLES.USER, ROLES.OPERATOR], group: 'work' },
  // ADR-0057: the task-skill library (guided forms, structured cited drafts) for every user; "Çalış" → "Beceriler"
  // (nav.js slot). Not "Prosedürler" (ADR-0053, verso-admin, in "Bilgi" below).
  beceriler: { title: 'Beceriler', short: 'Beceri', icon: 'sparkles', key: 'c', roles: [ROLES.USER, ROLES.OPERATOR], group: 'work' },
  documents: { title: 'Kaynaklar ve alanlar', short: 'Bilgi', icon: 'files', key: 'b', roles: [ROLES.USER, ROLES.OPERATOR], group: 'knowledge' },
  // ADR-0049: the answer memory's expert-verified answers and their nightly golden-set check.
  knowledge: { title: 'Onaylı bilgiler', short: 'Onaylı', icon: 'checkCircle', key: 'n', roles: [ROLES.ADMIN], group: 'knowledge' },
  // ADR-0053: the approved procedures: drafts from recurring questions, approval, SKILL.md export.
  skills: { title: 'Prosedürler', short: 'Prosedür', icon: 'flag', key: 'r', roles: [ROLES.ADMIN], group: 'knowledge' },
  // ADR-0054: the light system model (systems, screens, codes, processes, terms, roles) and its relation suggestions.
  systemmap: { title: 'Sistem haritası', short: 'Harita', icon: 'map', key: 'm', roles: [ROLES.ADMIN], group: 'knowledge' },
  // "İçgörü" (ADR-0058): the activity centre, the command dashboard (with the former value dashboard as a tab) and the
  // company diagnostics (ADR-0052).
  activity: { title: 'Etkinlik merkezi', short: 'Etkinlik', icon: 'clock', key: 'i', roles: [ROLES.ADMIN], group: 'insight' },
  dashboard: { title: 'Komuta panosu', short: 'Pano', icon: 'chart', key: 'd', roles: [ROLES.ADMIN], group: 'insight' },
  health: { title: 'Bilgi sağlığı', short: 'Sağlık', icon: 'pulse', key: 'h', roles: [ROLES.ADMIN], group: 'insight' },
  // "Yönetim" (ADR-0036): verso-admin only; the operator keeps its system screen and the setup status.
  connectors: { title: 'Bağlayıcılar', short: 'Bağlayıcı', icon: 'plug', key: 'e', roles: [ROLES.ADMIN], group: 'admin' },
  collections: { title: 'Erişim ve roller', short: 'Erişim', icon: 'key', key: 'a', roles: [ROLES.ADMIN], group: 'admin' },
  subjects: { title: 'KVKK ve veri', short: 'KVKK', icon: 'shield', key: 'v', roles: [ROLES.ADMIN], group: 'admin' },
  trust: { title: 'Güven ve denetim', short: 'Güven', icon: 'lock', key: 'u', roles: [ROLES.ADMIN], group: 'admin' },
  // ADR-0056: the model gateway (connections and keys, routes, prices, budgets); Yönetim's first item (nav.js slot).
  providers: { title: 'Yapay zekâ sağlayıcıları', short: 'Sağlayıcı', icon: 'cpu', key: 'z', roles: [ROLES.ADMIN], group: 'admin' },
  system: { title: 'Sistem durumu', short: 'Sistem', icon: 'server', key: 'y', roles: [ROLES.OPERATOR], group: 'admin' },
  setup: { title: 'Kurulum', short: 'Kurulum', icon: 'flag', key: 'k', roles: [ROLES.OPERATOR, ROLES.ADMIN], group: 'admin' },
  // Reached from "Kaynaklar ve alanlar", the palette and "g p"; no menu entry of its own.
  chunks: { title: 'Parçaları incele', short: 'Parçalar', icon: 'search', key: 'p', roles: [ROLES.ADMIN], group: 'admin' },
});

/** The heading of the admin group (ADR-0036). */
export const ADMIN_GROUP = 'Yönetim';

/** The verso-admin-only screens this user may open (44 px touch targets, ADR-0036); empty without verso-admin. */
export function adminScreens(roles) {
  return screensFor(roles).filter((id) => SCREENS[id].roles.length === 1 && SCREENS[id].roles[0] === ROLES.ADMIN);
}

/** The roles of a decoded access token payload; every authenticated user is at least a verso-user. */
export function rolesOf(claims) {
  // Keycloak realm roles, or Entra ID app roles ("roles", ADR-0041); only the panel's own role names count.
  const realm = claims?.realm_access?.roles;
  const app = claims?.roles;
  const all = [...(Array.isArray(realm) ? realm : []), ...(Array.isArray(app) ? app : [])];
  const known = all.filter((role) => TOKEN_ROLES.includes(role));
  return known.length ? known : [ROLES.USER];
}

/**
 * The token's panel roles plus verso-admin when GET /v1/info says the server grants it. Any other value of the
 * response is ignored (docs/api-questions-integration-v1.md: clients ignore roles they do not know), and a failed or
 * malformed response adds nothing: the dashboard then stays hidden, and the server answers 403 anyway.
 */
export function withServerRoles(roles, info) {
  const server = Array.isArray(info?.roles) ? info.roles : [];
  return server.includes(ROLES.ADMIN) && !roles.includes(ROLES.ADMIN) ? [...roles, ROLES.ADMIN] : [...roles];
}

/** The screens a user with these roles may open, in menu order. */
export function screensFor(roles) {
  return Object.entries(SCREENS).filter(([, screen]) => screen.roles.some((r) => roles.includes(r))).map(([id]) => id);
}

export function canOpen(screen, roles) {
  return Boolean(SCREENS[screen]?.roles.some((r) => roles.includes(r)));
}
