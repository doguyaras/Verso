// The knowledge-health report (ADR-0052) without the DOM: the Turkish words of severities, trends and hook actions,
// the quarter a consulting report covers, the export file name, and the bell's view of the hook notifications. The
// server decides every rule again (k-anonymity, access filtering, the question-text policy); these only format.

export const SEVERITIES = Object.freeze({
  CRITICAL: { label: 'Kritik', tone: 'bad' },
  WARNING: { label: 'Uyarı', tone: 'wait' },
  INFO: { label: 'Bilgi', tone: 'neutral' },
});

export const TRENDS = Object.freeze({
  NEW: { label: 'Yeni', tone: 'wait' },
  GROWING: { label: 'Büyüyor', tone: 'bad' },
  STABLE: { label: 'Durağan', tone: 'neutral' },
  SHRINKING: { label: 'Küçülüyor', tone: 'ok' },
});

/** What a hook rule may do: the complete list (ADR-0048 principle 5); nothing promotes, deletes or changes access. */
export const ACTIONS = Object.freeze({
  NOTIFY: 'Bildirir',
  FLAG: 'İncelemeye işaretler',
  DEMOTE: 'Kullanımdan çıkarır',
  RUN_EVAL: 'Kontrolü çalıştırır',
});

export const FEEDBACK_REASONS = Object.freeze({
  WRONG_SOURCE: 'Yanlış kaynak',
  INCORRECT_OR_MISSING: 'Yanlış ya da eksik',
  OUTDATED: 'Güncel değil',
  OTHER: 'Diğer',
});

export function severityLabel(severity) {
  return SEVERITIES[severity]?.label ?? 'Bilgi';
}

export function severityTone(severity) {
  return SEVERITIES[severity]?.tone ?? 'neutral';
}

export function trendLabel(trend) {
  return TRENDS[trend]?.label ?? '–';
}

export function trendTone(trend) {
  return TRENDS[trend]?.tone ?? 'neutral';
}

export function actionLabel(action) {
  return ACTIONS[action] ?? 'Bilinmiyor';
}

export function reasonLabel(reason) {
  return reason ? FEEDBACK_REASONS[reason] ?? 'Diğer' : 'Gerekçe yok';
}

function pad(n) {
  return String(n).padStart(2, '0');
}

function iso(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/**
 * The calendar quarter of {@code today} as an inclusive day range; {@code previous} gives the last finished quarter
 * (the consulting deliverable). Local calendar days, the same days the server counts in.
 */
export function quarterRange(today = new Date(), previous = false) {
  let year = today.getFullYear();
  let quarter = Math.floor(today.getMonth() / 3);
  if (previous) {
    quarter -= 1;
    if (quarter < 0) {
      quarter = 3;
      year -= 1;
    }
  }
  const from = new Date(year, quarter * 3, 1);
  const to = new Date(year, quarter * 3 + 3, 0);
  return { from: iso(from), to: iso(to), label: `${year} Ç${quarter + 1}` };
}

/** The last 30 days ending today (the default view). */
export function recentRange(today = new Date(), days = 30) {
  const from = new Date(today.getFullYear(), today.getMonth(), today.getDate() - (days - 1));
  return { from: iso(from), to: iso(today) };
}

/** The download name: the dates only (it ends up in download lists and synced folders). */
export function exportFileName(range, format = 'MARKDOWN') {
  return `bilgi-sagligi-${range.from}-${range.to}.${format === 'JSON' ? 'json' : 'md'}`;
}

/** One side of a contradiction: its name only when the admin may read it, else the id. */
export function sideLabel(side) {
  if (!side) return '–';
  const page = `s. ${side.page}`;
  return side.readable && side.fileName ? `${side.fileName}, ${page}` : `Belge ${String(side.documentId).slice(0, 8)}…, ${page}`;
}

/** The differing values, or why they are not shown. */
export function valuesText(contradiction) {
  const values = Array.isArray(contradiction?.values) ? contradiction.values : [];
  if (!values.length) return 'Değerler gizli: iki belgeyi de okuma yetkiniz yok.';
  return values.map((v) => `${v.first} ↔ ${v.second}`).join(', ');
}

/** A ratio as Turkish percent text, or a dash when suppressed (k-anonymity) or unknown. */
export function percentText(value) {
  if (!Number.isFinite(value)) return 'gizli';
  return `%${Math.round(value * 100)}`;
}

/** The bell: the unread count as shown ("9+" above nine) and whether to show the badge at all. */
export function bellBadge(unread) {
  const n = Number.isFinite(unread) ? unread : 0;
  return { show: n > 0, text: n > 9 ? '9+' : String(n), label: n ? `Bildirimler, ${n} okunmamış` : 'Bildirimler' };
}

/** A notification as the bell lists it; the title comes from the server's fixed templates. */
export function notificationItem(notification) {
  const flag = notification?.action === 'FLAG';
  return {
    label: notification?.title ?? 'Bildirim',
    glyph: flag ? 'flag' : notification?.severity === 'CRITICAL' ? 'alert' : 'bell',
    tone: severityTone(notification?.severity),
    unread: !notification?.readAt,
  };
}
