// Where the panel finds the API and the IdP. The panel is served by the edge proxy next to the API (same origin, no
// CORS); the demo IdP is published on 127.0.0.1:8180 and named localhost (ADR-0010). The panel works on the default
// ports only: with another VERSO_HTTP_PORT, VERSO_KEYCLOAK_PORT or VERSO_GRAFANA_PORT, or another IdP, change these
// lines, the "verso-panel" client's redirect URI, web origin and logout URI (deploy/keycloak/realm/verso-realm.json,
// then scripts/keycloak-reimport.sh) and the proxy's CSP (deploy/edge/nginx.conf) together.

// The installation, set by the team that installs Verso at a company (ADR-0018). These are the defaults: the team
// overrides them in deploy/branding/branding.json, which the panel reads at startup and validates (branding.js,
// ADR-0021), so no installation edits this file. Nothing here is a secret; the file is public before sign-in.
export const DEPLOYMENT_DEFAULTS = Object.freeze({
  // The product's name in the page title and the labels; the signature stays the Verso mark.
  productName: 'Verso',
  // The client's name under the signature in the menu, and its logo next to it (a file under /branding/); null hides.
  client: null,
  logo: null,
  // The engagement's phase and whether a pilot group is defined, for the "Kurulum durumu" screen.
  phase: 'pilot', // discovery | pilot | rollout | integrations
  pilotGroup: false,
  team: null, // e.g. 'Kurulum ekibi · destek@ornek.com.tr'
  // Accent tokens per theme ({ light: { 'fg-accent': '#rrggbb', … }, dark: { … } }); null keeps panel.css.
  accent: null,
  // The example on the intro screen and the questions the ask screen suggests. The installation team sets them from
  // the client's own systems and documents: Verso answers questions about how a company's systems work, not only
  // policy look-ups. The showcase is labelled as an example; the suggestions are asked for real, so they must be
  // answerable from the indexed documents (the defaults fit samples/).
  showcase: Object.freeze({
    tr: Object.freeze({
      label: 'Örnek · depo yönetim sistemi',
      question: 'Sevkiyat emri onaylandıktan sonra stok neden hemen düşmüyor?',
      answer: 'Stok, emir onaylandığında değil, toplama tamamlanıp koli kapatıldığında düşer. Aradaki kalemler “rezerve” görünür ve başka siparişe ayrılamaz.',
      source: 'depo-yonetim-kilavuzu.pdf · bölüm 3.4',
    }),
    en: Object.freeze({
      label: 'Example · warehouse management system',
      question: 'Why doesn’t stock drop as soon as a shipment order is approved?',
      answer: 'Stock drops when picking is finished and the parcel is closed, not when the order is approved. Items in between show as “reserved” and cannot be allocated to another order.',
      source: 'warehouse-management-guide.pdf · section 3.4',
    }),
  }),
  suggestions: Object.freeze([
    'Satın alma onay matrisi tutara göre nasıl işliyor?',
    '500.000 TL\'yi aşan alımlarda süreç nasıl ilerler?',
    'Yeni tedarikçi çalışmaya başlamadan önce hangi belgeleri vermeli?',
  ]),
});

let deployment = DEPLOYMENT_DEFAULTS;

export const CONFIG = Object.freeze({
  api: '',
  issuer: 'http://localhost:8180/realms/verso',
  clientId: 'verso-panel',
  // globalThis.location: the module is also loaded by the node tests (scripts/panel.test.mjs), which have no window.
  redirectUri: `${globalThis.location?.origin ?? "http://localhost:8080"}/panel/`,
  grafana: 'http://localhost:3000',
  // Read when a screen renders, never kept at module load: the branding file arrives after the modules are loaded.
  get deployment() {
    return deployment;
  },
});

/** Sets the installation's block once, before the first render; branding.js passes a validated, frozen copy. */
export function useDeployment(next) {
  deployment = next;
}
