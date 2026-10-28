// "Komuta panosu" (ADR-0058, concept B's dashboard as a page of "İçgörü"): the knowledge-health score with its formula,
// the cited-answer rate, adoption (k ≥ 5, never per user), local/cloud use, issues ranked by impact, the setup journey
// and procedure use. The former "Değer panosu" is its second tab. Everything comes from the existing admin APIs.
import { activityQuery } from '../activity.js';
import { api } from '../api.js';
import { CONFIG } from '../config.js';
import { adoption, budgetState, citedRate, gatewayCost, HEALTH_FORMULA, healthScore, issuesByImpact, MIN_ACCOUNTS, money,
  procedureCounts, scoreTone, usageSplit } from '../dashboard.js';
import { recentRange } from '../health.js';
import { icon } from '../icons.js';
import { rangeFor } from '../metrics.js';
import { canOpen } from '../roles.js';
import { el, fill } from '../render.js';
import { disclosure, helpLine } from '../ui.js';
import { PHASES } from './setup.js';
import { renderInsights } from './insights.js';

const TABS = Object.freeze([{ id: 'command', label: 'Komuta' }, { id: 'value', label: 'Değer ve kullanım' }]);

function part(label, value, formula) {
  return el('div', { class: 'score-part', title: formula },
    el('span', { class: 'score-part-label' }, label, el('span', { class: 'sr-only' }, `. ${formula}`)),
    el('span', { class: 'score-bar', 'aria-hidden': 'true' }, el('meter', { min: 0, max: 100, value: value ?? 0 })),
    el('span', { class: 'tabular' }, value == null ? '—' : String(value)));
}

function healthTile(score) {
  return el('section', { class: 'card kpi-tile score-tile', 'aria-labelledby': 'score-title' },
    el('h2', { id: 'score-title', class: 'kpi-label', title: HEALTH_FORMULA.score }, icon('pulse', 'sm'), 'Bilgi sağlığı'),
    el('div', { class: 'score-row' },
      el('span', { class: `score-value ${scoreTone(score.score)}` }, score.score == null ? '—' : String(score.score)),
      el('div', { class: 'score-parts' },
        part('Kapsama', score.coverage, HEALTH_FORMULA.coverage),
        part('Tazelik', score.freshness, HEALTH_FORMULA.freshness),
        part('Tutarlılık', score.consistency, HEALTH_FORMULA.consistency))),
    el('p', { class: 'kpi-note' }, score.score == null ? 'Henüz bir bilgi sağlığı taraması yok.' : HEALTH_FORMULA.score));
}

function tile(label, glyph, value, note) {
  return el('section', { class: 'card kpi-tile' }, el('h2', { class: 'kpi-label' }, icon(glyph, 'sm'), label),
    el('span', { class: 'kpi-big' }, value), el('p', { class: 'kpi-note' }, note));
}

function issues(ctx, rows) {
  return el('section', { class: 'card', 'aria-labelledby': 'issues-title' },
    el('div', { class: 'card-head' }, el('h2', { id: 'issues-title' }, 'Öncelikli sorunlar'), el('span', { class: 'hint' }, 'etkiye göre sıralı')),
    el('div', { class: 'card-body' }, rows.length
      ? el('ol', { class: 'list issue-list' }, rows.map((r) => el('li', {},
        el('span', { class: 'pill neutral' }, r.kind),
        el('div', { class: 'grow' }, el('span', { class: 'title' }, r.label), el('span', { class: 'sub' }, r.detail)),
        canOpen(r.screen, ctx.roles) ? el('button', { class: 'button secondary sm', type: 'button', onclick: () => ctx.navigate(r.screen) }, r.action) : null)))
      : el('p', { class: 'muted' }, 'Açık sorun yok.')));
}

function journey() {
  const current = Math.max(0, PHASES.findIndex((p) => p.id === CONFIG.deployment.phase));
  return el('section', { class: 'card', 'aria-labelledby': 'journey-title' },
    el('div', { class: 'card-head' }, el('h2', { id: 'journey-title' }, 'Kurulum yolculuğu'), el('span', { class: 'spacer' }),
      el('a', { href: '#/setup' }, 'Kurulum →')),
    el('div', { class: 'card-body' }, el('ol', { class: 'journey' }, PHASES.map((p, i) => el('li', {
      class: i < current ? 'done' : i === current ? 'now' : '', 'aria-current': i === current ? 'step' : null }, p.title)))));
}

function procedures(ctx, counts, skillsAvailable) {
  return el('section', { class: 'card', 'aria-labelledby': 'proc-title' },
    el('div', { class: 'card-head' }, el('h2', { id: 'proc-title' }, skillsAvailable ? 'Beceriler ve prosedürler' : 'Prosedürler'),
      el('span', { class: 'spacer' }), canOpen('skills', ctx.roles) ? el('a', { href: '#/skills' }, 'Prosedürler →') : null),
    el('div', { class: 'card-body' }, counts
      ? el('dl', { class: 'kv' }, el('dt', {}, 'Onaylı'), el('dd', {}, String(counts.approved)), el('dt', {}, 'Taslak'),
        el('dd', {}, String(counts.draft)), el('dt', {}, 'Eskimiş'), el('dd', {}, String(counts.stale)))
      : el('p', { class: 'muted' }, 'Prosedür sayıları alınamadı.'),
    el('p', { class: 'muted small' }, skillsAvailable ? 'Beceri çalışmaları Etkinlik merkezinde.'
      : 'Beceri kullanımı, beceri modülü kurulduğunda burada görünür.')));
}

async function renderCommand(ctx, root) {
  const { guarded } = ctx;
  fill(root, el('div', { class: 'grid kpis' }, Array.from({ length: 4 }, () => el('div', { class: 'skeleton block' }))));
  const health = recentRange(new Date(), 30);
  const usage = rangeFor(30);
  // ADR-0056: the model gateway's month (cost, teams, budgets) and its connections' locality. Optional: a failure or a
  // server without the gateway leaves the cost tile out, never the board (withAuth, no toast).
  const optional = (call) => (canOpen('providers', ctx.roles) ? ctx.withAuth(call).catch(() => null) : Promise.resolve(null));
  const [report, metrics, summary, failing, skills, gateway, connections, info] = await Promise.all([
    guarded(() => api.knowledgeHealth(health.from, health.to)),
    guarded(() => api.metrics(usage.from, usage.to)),
    guarded(() => api.activitySummary()),
    guarded(() => api.activity(activityQuery({ category: 'BACKGROUND', status: 'FAILED', days: 7 }))),
    canOpen('skills', ctx.roles) ? guarded(() => api.listSkills('', 0, 100)) : null,
    optional(() => api.modelUsage()),
    optional(() => api.modelConnections()),
    ctx.withAuth(() => api.info()).catch(() => null),
  ]);
  if (!ctx.isCurrent()) return;
  const score = healthScore(report);
  const rate = citedRate(metrics);
  const people = adoption(metrics, gateway);
  const split = usageSplit(metrics);
  const cost = gatewayCost(gateway, Array.isArray(connections) ? connections : connections?.data, info?.mode);
  const budget = budgetState(gateway);
  const failingConnectors = new Set((failing?.items ?? []).filter((i) => i.type === 'CONNECTOR_SYNC')
    .map((i) => i.link?.targetId ?? i.id)).size;
  // Sadeleştirme (ADR-0058): three headline tiles first; cost, issues, journey and procedures open on demand.
  const ranked = issuesByImpact({ report, summary, failingConnectors });
  fill(root,
    el('div', { class: 'grid kpis dashboard-kpis headline' },
      healthTile(score),
      tile('Kaynaklı cevap oranı', 'checkCircle', rate ? `%${rate.answered}` : '—',
        rate ? `Bulunamadı %${rate.notFound} · kaynak gösterilemedi %${rate.uncited} · ${rate.questions} soru, son 30 gün` : 'Son 30 günde soru yok.'),
      tile('Benimseme', 'user', people.active != null ? String(people.active) : people.suppressed ? `<${MIN_ACCOUNTS}` : '—',
        people.teams.length
          ? `Model çağrılarında ekip payı: ${people.teams.map((t) => `${t.name} %${t.share}`).join(' · ')}${people.hiddenTeams
            ? ` · ${people.hiddenTeams} küçük ekip gösterilmiyor` : ''}. Kişi bazında sayı yok.`
          : `etkin hesap, son 30 gün · ${people.questions} soru. Ekip kırılımı model geçidi verisiyle gelir; kişi bazında sayı yok.`)),
    disclosure(ranked.length ? `Ayrıntılar · ${ranked.length} öncelikli sorun` : 'Ayrıntılar', [
      // ADR-0056: the month's model cost, local vs cloud, and the budget; hidden while the gateway reports none.
      cost ? tile('Yerel ve bulut maliyet', 'cpu', money(cost.total),
        `%${cost.localShare} yerel (${money(cost.local)}) · %${cost.cloudShare} bulut (${money(cost.cloud)}) · ${cost.requests} çağrı, ${cost.month}`
        + (budget ? ` · bütçe ${money(budget.spent)} / ${money(budget.limit)}${budget.state === 'HARD' ? ' (sınırda, çağrılar durdu)'
          : budget.state === 'SOFT' ? ' (uyarı sınırında)' : ''}` : ''))
        : split ? tile('Yerel ve bulut', 'cpu', `%${split.local} yerel`, `%${split.cloud} bulut · ${split.total} cevap, son 30 gün`) : null,
      el('div', { class: 'grid split' }, issues(ctx, ranked),
        el('div', { class: 'grid' }, journey(), procedures(ctx, skills ? procedureCounts(skills.data) : null, ctx.skillsAvailable)))],
    'dashboard-more'));
}

export async function renderDashboard(ctx) {
  const { main } = ctx;
  let current = ctx.params?.tab === 'value' ? 'value' : 'command';
  const tabs = el('div', { class: 'segmented', role: 'tablist', 'aria-label': 'Pano' });
  const panel = el('div', { class: 'dashboard-panel', role: 'tabpanel' });
  fill(main, el('div', { class: 'page-head' }, el('div', {}, el('h1', {}, 'Komuta panosu'),
    helpLine('Bilgi sağlığı, kullanım ve açık işler tek bakışta.',
      'Kişi değil ekip ya da toplam görünür; benimseme en az 5 hesapla gösterilir. Maliyet, öncelikli sorunlar, kurulum yolculuğu ve prosedürler "Ayrıntılar" altında.'))),
  tabs, panel);
  async function show() {
    fill(tabs, TABS.map((t) => el('button', { type: 'button', role: 'tab', 'aria-selected': String(t.id === current),
      'aria-pressed': String(t.id === current), onclick: () => {
        if (t.id === current) return;
        current = t.id;
        show();
      } }, t.label)));
    if (current === 'value') await renderInsights({ ...ctx, main: panel, embedded: true });
    else await renderCommand(ctx, panel);
  }
  await show();
}
