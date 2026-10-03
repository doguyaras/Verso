// Where the panel finds the API and the IdP. The panel is served by the edge proxy next to the API (same origin, no
// CORS); the demo IdP is published on 127.0.0.1:8180 and named localhost (ADR-0010). The panel works on the default
// ports only: with another VERSO_HTTP_PORT, VERSO_KEYCLOAK_PORT or VERSO_GRAFANA_PORT, or another IdP, change these
// lines, the "verso-panel" client's redirect URI, web origin and logout URI (deploy/keycloak/realm/verso-realm.json,
// then scripts/keycloak-reimport.sh) and the proxy's CSP (deploy/edge/nginx.conf) together.
export const CONFIG = Object.freeze({
  api: '',
  issuer: 'http://localhost:8180/realms/verso',
  clientId: 'verso-panel',
  // globalThis.location: the module is also loaded by the node tests (scripts/panel.test.mjs), which have no window.
  redirectUri: `${globalThis.location?.origin ?? "http://localhost:8080"}/panel/`,
  grafana: 'http://localhost:3000',
});
