// Where the panel finds the API and the IdP. The panel is served by the edge proxy next to the API (same origin, no
// CORS); the demo IdP is published on 127.0.0.1:8180 (ADR-0010). Another IdP or port: change these two lines, the
// "verso-panel" client's redirect URI and web origin (deploy/keycloak/realm/verso-realm.json), and the proxy's CSP.
export const CONFIG = Object.freeze({
  api: '',
  issuer: 'http://localhost:8180/realms/verso',
  clientId: 'verso-panel',
  // globalThis.location: the module is also loaded by the node tests (scripts/panel.test.mjs), which have no window.
  redirectUri: `${globalThis.location?.origin ?? "http://localhost:8080"}/panel/`,
  grafana: 'http://localhost:3000',
});
