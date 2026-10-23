// Role matrix of the panel, in one file (reference 17). A screen is shown when the user holds one of its roles; the
// API enforces the real rules (every request carries the token, ownership is checked on the server, ADR-0005). The
// panel only hides what a role cannot use. Roles come from the access token's realm_access.roles (Keycloak realm
// roles); a signed-in user without any Verso role is a "verso-user".

export const ROLES = Object.freeze({
  USER: 'verso-user',
  OPERATOR: 'verso-operator',
});

// Menu order: the most used task right after the overview (ADR-0017); at most five screens (ADR-0015). "key" is the
// second key of the "g" shortcut; "tab: false" keeps a screen out of the mobile tab bar (four tabs at most).
export const SCREENS = Object.freeze({
  overview: { title: 'Genel bakış', short: 'Özet', icon: 'home', key: 'o', roles: [ROLES.USER, ROLES.OPERATOR] },
  ask: { title: 'Soru sor', short: 'Sor', icon: 'chat', key: 's', roles: [ROLES.USER, ROLES.OPERATOR] },
  documents: { title: 'Bilgi kaynakları', short: 'Kaynaklar', icon: 'files', key: 'b', roles: [ROLES.USER, ROLES.OPERATOR] },
  setup: { title: 'Kurulum durumu', short: 'Kurulum', icon: 'flag', key: 'k', roles: [ROLES.USER, ROLES.OPERATOR], tab: false },
  system: { title: 'Sistem ve güvenlik', short: 'Sistem', icon: 'server', key: 'y', roles: [ROLES.OPERATOR] },
});

/** The roles of a decoded access token payload; every authenticated user is at least a verso-user. */
export function rolesOf(claims) {
  const realm = claims?.realm_access?.roles ?? [];
  const known = realm.filter((role) => Object.values(ROLES).includes(role));
  return known.length ? known : [ROLES.USER];
}

/** The screens a user with these roles may open, in menu order. */
export function screensFor(roles) {
  return Object.entries(SCREENS).filter(([, screen]) => screen.roles.some((r) => roles.includes(r))).map(([id]) => id);
}

export function canOpen(screen, roles) {
  return Boolean(SCREENS[screen]?.roles.some((r) => roles.includes(r)));
}
