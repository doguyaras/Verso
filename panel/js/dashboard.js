// "Komuta panosu" (ADR-0058, concept B's dashboard): the knowledge-health score, issues ranked by impact, the cited
// answer rate, adoption, local/cloud use. Pure functions over the existing admin APIs (ADR-0023 metrics, ADR-0052
// knowledge health, the activity summary); nothing here counts or names one person.

/** The k of the dashboard: an adoption figure from fewer accounts is not shown (ADR-0048's floor). */
export const MIN_ACCOUNTS = 5;

/** How each part of the health score is computed; the panel shows these texts as the tooltips. */
export const HEALTH_FORMULA = Object.freeze({
  coverage: 'Kapsama: paylaşılan alanlarda son 30 günde en az bir cevapta kaynak gösterilen belgelerin payı '
    + '(alıntılanan belge ÷ belge). Beş kişiden az soran alanlar hesaba katılmaz.',
  freshness: 'Tazelik: 100 × (1 − eskime payı). Eskime payı, eski sayılan ama hâlâ alıntılanan belgelerin belgeye oranı '
    + 'ile eskimiş onaylı kartların onaylı kartlara oranının ortalamasıdır.',
  consistency: 'Tutarlılık: 100 × (1 − çelişkili belge payı). Çelişkili belge, bilgi sağlığı taramasının başka bir '
    + 'belgeyle farklı değer bulduğu belgedir.',
  score: 'Bilgi sağlığı skoru: hesaplanabilen bileşenlerin (kapsama, tazelik, tutarlılık) ortalaması, 0–100.',
});

const ratio = (num, den) => (den > 0 ? Math.max(0, Math.min(1, num / den)) : null);
const pct = (value) => (value == null ? null : Math.round(value * 100));
const count = (v) => (Number.isFinite(v) && v > 0 ? v : 0);

/**
 * The knowledge-health score from GET /v1/admin/knowledge-health: { score, coverage, freshness, consistency }, each
 * 0..100 or null when it cannot be computed (no run, no shared document). The formula is HEALTH_FORMULA's, in words.
 */
export function healthScore(report) {
  const collections = Array.isArray(report?.coverage) ? report.coverage : [];
  const documents = collections.reduce((n, c) => n + count(c?.documents), 0);
  const measured = collections.filter((c) => Number.isFinite(c?.citedInWindow) && count(c?.documents) > 0);
  const coverage = measured.length
    ? ratio(measured.reduce((n, c) => n + count(c.citedInWindow), 0), measured.reduce((n, c) => n + count(c.documents), 0))
    : null;
  const staleDocs = Array.isArray(report?.staleDocuments) ? report.staleDocuments.length : 0;
  const cards = report?.cards ?? {};
  const shares = [ratio(staleDocs, documents), ratio(count(cards.stale), count(cards.verified) + count(cards.stale))]
    .filter((v) => v != null);
  const freshness = shares.length ? 1 - shares.reduce((a, b) => a + b, 0) / shares.length : null;
  const conflicted = new Set();
  for (const c of Array.isArray(report?.contradictions) ? report.contradictions : []) {
    if (c?.first?.documentId) conflicted.add(c.first.documentId);
    if (c?.second?.documentId) conflicted.add(c.second.documentId);
  }
  const conflictShare = ratio(conflicted.size, documents);
  const consistency = conflictShare == null ? null : 1 - conflictShare;
  const parts = [coverage, freshness, consistency].filter((v) => v != null);
  return {
    score: parts.length ? pct(parts.reduce((a, b) => a + b, 0) / parts.length) : null,
    coverage: pct(coverage), freshness: pct(freshness), consistency: pct(consistency),
  };
}

/** The tone of a score: good from 75, watch from 50. */
export function scoreTone(score) {
  if (score == null) return 'neutral';
  return score >= 75 ? 'ok' : score >= 50 ? 'wait' : 'bad';
}

/**
 * The cited-answer rate of GET /v1/admin/metrics: the share of answered (cited and checked) questions, and the not
 * found and uncited shares; null without questions.
 */
export function citedRate(metrics) {
  const rates = metrics?.totals?.outcomeRates;
  if (!rates || !Number.isFinite(rates.answered)) return null;
  return { answered: pct(rates.answered), notFound: pct(rates.notFound ?? 0), uncited: pct(rates.uncited ?? 0),
    questions: count(metrics.totals.questions) };
}

/**
 * Adoption without per-user data: distinct active accounts (a count) only from MIN_ACCOUNTS on. Team rows come from the
 * model gateway's monthly usage (ADR-0056, GET /v1/admin/model-gateway/usage): the gateway itself lists only teams with
 * at least `minTeamUsers` users (never below MIN_ACCOUNTS here); a team's share is its part of the month's model calls.
 */
export function adoption(metrics, usage = null) {
  const active = count(metrics?.totals?.activeUsers);
  const total = count(usage?.requests);
  const k = Number.isFinite(usage?.minTeamUsers) ? usage.minTeamUsers : 0;
  const teams = total && k >= MIN_ACCOUNTS ? (Array.isArray(usage.teams) ? usage.teams : [])
    .filter((t) => typeof t?.team === 'string' && count(t?.requests) > 0)
    .map((t) => ({ name: t.team, share: pct(t.requests / total) }))
    .sort((a, b) => b.share - a.share).slice(0, 5) : [];
  return { active: active >= MIN_ACCOUNTS ? active : null, suppressed: active > 0 && active < MIN_ACCOUNTS,
    questions: count(metrics?.totals?.questions), teams, hiddenTeams: count(usage?.hiddenTeams) };
}

/** Local versus cloud answers (ADR-0023 metrics); null (the tile hides) when nothing was answered. */
export function usageSplit(metrics) {
  const local = count(metrics?.totals?.modes?.local);
  const cloud = count(metrics?.totals?.modes?.cloud);
  if (local + cloud === 0) return null;
  return { local: pct(local / (local + cloud)), cloud: pct(cloud / (local + cloud)), total: local + cloud };
}

/**
 * This month's model cost split into local and cloud (ADR-0056). A row of a connection counts as cloud when the
 * connection's locality is EXTERNAL; the built-in model (no connection) follows the installation's mode. Null (the
 * tile hides) when the gateway reports no call or no cost.
 */
export function gatewayCost(usage, connections, mode) {
  const rows = Array.isArray(usage?.rows) ? usage.rows : [];
  const total = Number(usage?.cost ?? 0);
  if (!count(usage?.requests) || !Number.isFinite(total) || total <= 0) return null;
  const external = new Map((Array.isArray(connections) ? connections : []).map((c) => [c?.id, c?.locality === 'EXTERNAL']));
  let cloud = 0;
  for (const r of rows) {
    const cost = Number(r?.cost ?? 0);
    if (!Number.isFinite(cost)) continue;
    if (r.connectionId ? external.get(r.connectionId) === true : mode === 'cloud') cloud += cost;
  }
  cloud = Math.min(cloud, total);
  return { month: usage.month, total, cloud, local: total - cloud, cloudShare: pct(cloud / total),
    localShare: 100 - pct(cloud / total), requests: count(usage.requests) };
}

/**
 * The budget of the month (ADR-0056): spent against the hard limits of every budget, and the worst state (HARD, SOFT,
 * OK). Null without a budget.
 */
export function budgetState(usage) {
  const budgets = (Array.isArray(usage?.budgets) ? usage.budgets : []).filter((b) => Number.isFinite(Number(b?.hardLimit)));
  if (!budgets.length) return null;
  const spent = budgets.reduce((n, b) => n + Number(b.spent ?? 0), 0);
  const limit = budgets.reduce((n, b) => n + Number(b.hardLimit), 0);
  const state = budgets.some((b) => b.state === 'HARD') ? 'HARD' : budgets.some((b) => b.state === 'SOFT') ? 'SOFT' : 'OK';
  return { spent, limit, state, share: limit > 0 ? pct(spent / limit) : null };
}

/** A money amount in Turkish digits (the gateway's prices carry no currency; the installation sets them). */
export function money(value) {
  return Number(value ?? 0).toLocaleString('tr-TR', { maximumFractionDigits: 2 });
}

/**
 * The open issues, most impactful first, at most `limit`. Impact is what a person would weigh: the questions behind an
 * unanswered cluster, the citations of a stale document, a failing connector (it stops everything it feeds), the
 * approvals waiting. Every issue links to the screen that resolves it.
 */
export function issuesByImpact({ report, summary, failingConnectors = 0 } = {}, limit = 5) {
  const issues = [];
  for (const c of Array.isArray(report?.clusters) ? report.clusters : []) {
    if (!count(c?.turns)) continue;
    const words = Array.isArray(c.keywords) ? c.keywords.filter((w) => typeof w === 'string').slice(0, 3).join(', ') : '';
    issues.push({ kind: 'Cevapsız', label: words ? `Cevapsız soru kümesi: ${words}` : 'Cevapsız soru kümesi',
      detail: `${c.turns} soru · ${c.askers ?? '5+'} kişi`, impact: c.turns, screen: 'health', action: 'Bilgi sağlığı' });
  }
  for (const d of Array.isArray(report?.staleDocuments) ? report.staleDocuments : []) {
    issues.push({ kind: 'Eski', label: d?.fileName ? `Eski belge: ${d.fileName}` : 'Eski belge (adı gizli)',
      detail: `${count(d?.citations)} atıf aldı`, impact: count(d?.citations), screen: 'health', action: 'Güncelleme iste' });
  }
  const contradictions = Array.isArray(report?.contradictions) ? report.contradictions.length : 0;
  if (contradictions) {
    issues.push({ kind: 'Çelişki', label: `${contradictions} belge çifti çelişiyor`, detail: 'Bilgi sağlığı taraması',
      impact: 10 * contradictions, screen: 'health', action: 'Karara bağla' });
  }
  if (count(failingConnectors)) {
    issues.push({ kind: 'Bağlayıcı', label: `${failingConnectors} bağlayıcı senkronu hata veriyor`,
      detail: 'Yeni belgeler gelmiyor', impact: 50 * failingConnectors, screen: 'connectors', action: 'İncele' });
  }
  const approvals = count(summary?.pendingApprovals);
  if (approvals) {
    issues.push({ kind: 'Onay', label: `${approvals} onay bekliyor`, detail: 'Etkinlik merkezi · Onay kutusu',
      impact: 2 * approvals, screen: 'activity', action: 'İncele' });
  }
  return issues.sort((a, b) => b.impact - a.impact).slice(0, limit);
}

/** Procedures by state from GET /v1/admin/skills pages: what the "Beceriler" tile shows until skill runs exist. */
export function procedureCounts(skills) {
  const rows = Array.isArray(skills) ? skills : [];
  const by = (state) => rows.filter((s) => s?.state === state).length;
  return { approved: by('APPROVED'), draft: by('DRAFT'), stale: by('STALE') };
}
