// Role matrix of the panel, in one file (reference 17). A screen is shown when the user holds one of its roles; the
// API enforces the real rules (every request carries the token, ownership is checked on the server, ADR-0005). The
// panel only hides what a role cannot use. Roles come from the access token's realm_access.roles (Keycloak realm
// roles); a signed-in user without any Verso role is a "verso-user".

export const ROLES = Object.freeze({
  USER: 'verso-user',
  OPERATOR: 'verso-operator',
});

export const SCREENS = Object.freeze({
  overview: { title: 'Genel bakış', icon: 'home', roles: [ROLES.USER, ROLES.OPERATOR] },
  documents: { title: 'Belgeler', icon: 'files', roles: [ROLES.USER, ROLES.OPERATOR] },
  ask: { title: 'Soru sor', icon: 'chat', roles: [ROLES.USER, ROLES.OPERATOR] },
  system: { title: 'Sistem', icon: 'server', roles: [ROLES.OPERATOR] },
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
