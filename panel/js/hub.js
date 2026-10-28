// The "Yönetim" hub's small status hints (ADR-0058 Sadeleştirme): one short phrase per card, from reads the hub makes
// once. Pure: a source that failed (null) gives no hint rather than a wrong one. Tones: ok, wait, bad, neutral.
import { budgetState, money } from './dashboard.js';

const count = (value) => (Array.isArray(value) ? value.length : 0);

/**
 * { [screenId]: { text, tone } } from the activity summary, the knowledge-health report, the gateway usage, the
 * connector list and the setup progress (home.js setupProgress).
 */
export function hubHints({ summary, report, usage, connectors, setup } = {}) {
  const hints = {};
  if (Number.isInteger(summary?.attention)) {
    hints.activity = summary.attention > 0 ? { text: `${summary.attention} iş dikkat istiyor`, tone: 'wait' } : { text: 'Her şey yolunda', tone: 'ok' };
  }
  if (report && Array.isArray(report.clusters)) {
    const n = count(report.clusters) + count(report.staleDocuments) + count(report.contradictions);
    hints.health = n > 0 ? { text: `${n} bulgu`, tone: 'wait' } : { text: 'Sorun yok', tone: 'ok' };
  }
  const budget = budgetState(usage);
  if (budget) {
    hints.providers = budget.state === 'HARD' ? { text: 'Bütçe doldu', tone: 'bad' }
      : budget.state === 'SOFT' ? { text: 'Bütçe uyarı sınırında', tone: 'wait' }
        : { text: `${money(budget.spent)} / ${money(budget.limit)} bu ay`, tone: 'neutral' };
  }
  if (Array.isArray(connectors)) {
    const failing = connectors.filter((c) => c?.health === 'FAILING' || c?.health === 'STALE').length;
    hints.connectors = !connectors.length ? { text: 'Henüz bağlayıcı yok', tone: 'neutral' }
      : failing ? { text: `${failing} bağlayıcı dikkat istiyor`, tone: 'wait' } : { text: `${connectors.length} bağlayıcı çalışıyor`, tone: 'ok' };
  }
  if (setup && setup.total > 0) {
    hints.setup = setup.complete ? { text: 'Tamamlandı', tone: 'ok' } : { text: setup.label, tone: 'wait' };
  }
  return hints;
}
