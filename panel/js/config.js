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
  // The installation, filled in by the team that sets Verso up at a company (ADR-0018): the client's name in the menu,
  // the phase of the engagement and whether a pilot group is defined, for the "Kurulum durumu" screen. Nothing here
  // is a secret; null hides the line.
  deployment: Object.freeze({
    client: null,
    phase: 'pilot', // discovery | pilot | rollout | integrations
    pilotGroup: false,
    team: null, // e.g. 'Kurulum ekibi · destek@ornek.com.tr'
    // The example on the intro screen and the questions the ask screen suggests. The installation team sets them from
    // the client's own systems and documents: Verso answers questions about how a company's systems work, not only
    // policy look-ups. The showcase is labelled as an example; the suggestions are asked for real, so they must be
    // answerable from the indexed documents (the defaults fit samples/).
    showcase: Object.freeze({
      tr: Object.freeze({
        label: 'Örnek · e-fatura entegrasyonu',
        question: 'Toplu gönderimde zaman aşımı alırsak entegrasyon nasıl davranır?',
        answer: 'Gönderim kuyruğa alınır ve artan aralıklarla üç kez yeniden denenir. Üçüncü denemeden sonra fatura “beklemede” durumuna düşer ve operatöre bildirim gider.',
        source: 'entegrasyon-teknik-kilavuzu.pdf · bölüm 4.2',
      }),
      en: Object.freeze({
        label: 'Example · e-invoicing integration',
        question: 'What does the integration do when a bulk submission times out?',
        answer: 'The submission is queued and retried three times at growing intervals. After the third attempt the invoice moves to “on hold” and the operator is notified.',
        source: 'integration-technical-guide.pdf · section 4.2',
      }),
    }),
    suggestions: Object.freeze([
      'Satın alma onay matrisi tutara göre nasıl işliyor?',
      '500.000 TL\'yi aşan alımlarda süreç nasıl ilerler?',
      'Yeni tedarikçi çalışmaya başlamadan önce hangi belgeleri vermeli?',
    ]),
  }),
});
