// Role matrix of the panel, in one file (reference 17). A screen is shown when the user holds one of its roles; the
// API enforces the real rules (every request carries the token, ownership is checked on the server, ADR-0005). The
// panel only hides what a role cannot use. The panel roles come from the access token's realm_access.roles (Keycloak
// realm roles); a signed-in user without any Verso role is a "verso-user". The admin role is taken from GET /v1/info
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
// (ADR-0015); an admin also sees the value dashboard (ADR-0025). "key" is the second key of the "g" shortcut ("g g"
// opens the history drawer); "tab: false" keeps a screen out of the mobile tab bar (four tabs at most).
export const SCREENS = Object.freeze({
  overview: { title: 'Genel bakış', short: 'Özet', icon: 'home', key: 'o', roles: [ROLES.USER, ROLES.OPERATOR] },
  ask: { title: 'Soru sor', short: 'Sor', icon: 'chat', key: 's', roles: [ROLES.USER, ROLES.OPERATOR] },
  documents: { title: 'Bilgi kaynakları', short: 'Kaynaklar', icon: 'files', key: 'b', roles: [ROLES.USER, ROLES.OPERATOR] },
  setup: { title: 'Kurulum durumu', short: 'Kurulum', icon: 'flag', key: 'k', roles: [ROLES.USER, ROLES.OPERATOR], tab: false },
  system: { title: 'Sistem ve güvenlik', short: 'Sistem', icon: 'server', key: 'y', roles: [ROLES.OPERATOR] },
  insights: { title: 'Değer panosu', short: 'Pano', icon: 'chart', key: 'd', roles: [ROLES.ADMIN], tab: false },
});

/** The roles of a decoded access token payload; every authenticated user is at least a verso-user. */
export function rolesOf(claims) {
  const realm = claims?.realm_access?.roles ?? [];
  const known = realm.filter((role) => TOKEN_ROLES.includes(role));
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
