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

// Menu order: the most used task right after the overview (ADR-0017); at most five screens for a user or an operator
// (ADR-0015); an admin also sees the "Yönetim" group (ADR-0025, ADR-0028, ADR-0036). "key" is the second key of the "g" shortcut ("g g"
// opens the history drawer); "tab: false" keeps a screen out of the mobile tab bar (four tabs at most).
export const SCREENS = Object.freeze({
  overview: { title: 'Genel bakış', short: 'Özet', icon: 'home', key: 'o', roles: [ROLES.USER, ROLES.OPERATOR] },
  ask: { title: 'Soru sor', short: 'Sor', icon: 'chat', key: 's', roles: [ROLES.USER, ROLES.OPERATOR] },
  documents: { title: 'Bilgi kaynakları', short: 'Kaynaklar', icon: 'files', key: 'b', roles: [ROLES.USER, ROLES.OPERATOR] },
  setup: { title: 'Kurulum durumu', short: 'Kurulum', icon: 'flag', key: 'k', roles: [ROLES.USER, ROLES.OPERATOR], tab: false },
  system: { title: 'Sistem ve güvenlik', short: 'Sistem', icon: 'server', key: 'y', roles: [ROLES.OPERATOR] },
  // The "Yönetim" group (ADR-0036): verso-admin only, listed under its own heading in the menu column and the account
  // menu, never in the tab bar; a user or an operator sees none of it.
  insights: { title: 'Değer panosu', short: 'Pano', icon: 'chart', key: 'd', roles: [ROLES.ADMIN], tab: false, group: 'admin' },
  collections: { title: 'Bilgi alanları', short: 'Alanlar', icon: 'layers', key: 'a', roles: [ROLES.ADMIN], tab: false, group: 'admin' },
  chunks: { title: 'Parçaları incele', short: 'Parçalar', icon: 'search', key: 'p', roles: [ROLES.ADMIN], tab: false, group: 'admin' },
  subjects: { title: 'KVKK başvuruları', short: 'KVKK', icon: 'shield', key: 'v', roles: [ROLES.ADMIN], tab: false, group: 'admin' },
  connectors: { title: 'Bağlayıcılar', short: 'Bağlayıcı', icon: 'plug', key: 'e', roles: [ROLES.ADMIN], tab: false, group: 'admin' },
  trust: { title: 'Güven', short: 'Güven', icon: 'lock', key: 'u', roles: [ROLES.ADMIN], tab: false, group: 'admin' },
  // ADR-0049: the answer memory's expert-verified answers and their nightly golden-set check.
  knowledge: { title: 'Onaylı bilgiler', short: 'Onaylı', icon: 'checkCircle', key: 'n', roles: [ROLES.ADMIN], tab: false, group: 'admin' },
  // ADR-0052: the company diagnostics ("bilgi sağlığı"), its quarterly report and the hook rules.
  health: { title: 'Bilgi sağlığı', short: 'Sağlık', icon: 'pulse', key: 'h', roles: [ROLES.ADMIN], tab: false, group: 'admin' },
});

/** The heading of the admin group (ADR-0036). */
export const ADMIN_GROUP = 'Yönetim';

/** The admin screens this user may open, in menu order; empty for everyone without verso-admin. */
export function adminScreens(roles) {
  return screensFor(roles).filter((id) => SCREENS[id].group === 'admin');
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
