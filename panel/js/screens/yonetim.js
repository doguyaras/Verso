// "Yönetim" (ADR-0058 Sadeleştirme): the one menu entry for admins and operators. A calm grid of grouped cards —
// İçgörü, Bağlantılar, Erişim ve uyum, Kurulum — each with a one-line description and, when a cheap read answers, a
// small status hint (hub.js). Which cards a role sees is nav.js hubModel's job; every screen keeps its own route.
import { api } from '../api.js';
import { CONFIG } from '../config.js';
import { recentRange } from '../health.js';
import { setupProgress } from '../home.js';
import { hubHints } from '../hub.js';
import { icon } from '../icons.js';
import { hubModel } from '../nav.js';
import { canOpen, ROLES } from '../roles.js';
import { el, fill } from '../render.js';
import { conversation } from '../store.js';
import { setupSteps } from './setup.js';
import { helpLine } from '../ui.js';

const TONES = Object.freeze({ ok: 'ok', wait: 'wait', bad: 'bad', neutral: 'neutral' });

export async function renderYonetim(ctx) {
  const { main, roles } = ctx;
  const groups = hubModel(roles, ctx.available);
  const hints = new Map();
  const card = (c) => {
    const hint = el('span', { class: 'hub-hint', 'data-hint': c.id });
    hints.set(c.id, hint);
    return el('li', {}, el('a', { class: 'hub-card', href: `#/${c.id}` },
      el('span', { class: 'hub-card-icon', 'aria-hidden': 'true' }, icon(c.icon, 'sm')),
      el('span', { class: 'grow' }, el('strong', {}, c.title), el('span', { class: 'sub' }, c.description), hint),
      icon('chevronRight', 'sm')));
  };
  fill(main, el('div', { class: 'page-head' }, el('div', {}, el('h1', {}, 'Yönetim'),
    helpLine('Kurulumun ayarları ve içgörüleri; her kart kendi sayfasını açar.'))),
  el('div', { class: 'hub' }, groups.map((g) => el('section', { class: 'hub-group', 'aria-labelledby': `hub-${g.id}` },
    el('h2', { id: `hub-${g.id}` }, g.title),
    el('ul', { class: 'hub-cards' }, g.cards.map(card))))));

  // The hints: optional reads, no toast on failure (the card then shows its description only).
  const quiet = (call) => ctx.withAuth(call).catch(() => null);
  const shown = new Set(groups.flatMap((g) => g.cards.map((c) => c.id)));
  const admin = roles.includes(ROLES.ADMIN);
  const health = recentRange(new Date(), 30);
  const [summary, report, usage, connectors, docs, info] = await Promise.all([
    shown.has('activity') ? quiet(() => api.activitySummary()) : null,
    shown.has('health') ? quiet(() => api.knowledgeHealth(health.from, health.to)) : null,
    shown.has('providers') ? quiet(() => api.modelUsage()) : null,
    shown.has('connectors') ? quiet(() => api.listConnectors(0, 50)) : null,
    shown.has('setup') ? quiet(() => api.listAllDocuments()) : null,
    shown.has('setup') ? quiet(() => api.info()) : null,
  ]);
  if (!ctx.isCurrent()) return;
  const setup = docs && canOpen('setup', roles) ? setupProgress(setupSteps({ signedIn: true, mode: info?.mode ?? null,
    ready: docs.filter((d) => d.status === 'READY').length,
    cited: conversation.some((entry) => entry.result?.citations?.length > 0),
    pilotGroup: Boolean(CONFIG.deployment.pilotGroup) })) : null;
  const found = hubHints({ summary: admin ? summary : null, report, usage,
    connectors: Array.isArray(connectors?.data) ? connectors.data : null, setup });
  for (const [id, hint] of Object.entries(found)) {
    const slot = hints.get(id);
    if (!slot) continue;
    slot.className = `hub-hint ${TONES[hint.tone] ?? 'neutral'}`;
    slot.textContent = hint.text;
  }
}
