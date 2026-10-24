// The admin value dashboard's numbers (ADR-0023 §6, ADR-0025), without the DOM: the date range of a preset, the KPI
// tiles in Turkish number and percent formats, the daily series of the trend charts, the feedback reasons and the
// document names under the installation's name policy. Nothing here invents a number: a value the API does not
// return (an "estimated time saved", for one) is not derived, and a missing rate shows "–".
import { REASONS } from './feedback.js';
import { plainName } from './render.js';

/** The presets of the date range, in days, both ends included. */
export const PRESETS = Object.freeze([7, 30, 90]);
export const DEFAULT_PRESET = 30;
/** The API's longest range (11030 METRICS_RANGE_INVALID above it). */
export const MAX_RANGE_DAYS = 366;

const pad = (n) => String(n).padStart(2, '0');
const utc = (iso) => {
  const [y, m, d] = String(iso).split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
};
const isoOf = (date) => `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`;

/** The browser's calendar day as YYYY-MM-DD (local time, not UTC: near midnight the two differ). */
export function isoDay(date = new Date()) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** A calendar day moved by `days`, month and year ends and leap days included (UTC arithmetic, no DST jumps). */
export function addDays(iso, days) {
  const date = utc(iso);
  date.setUTCDate(date.getUTCDate() + days);
  return isoOf(date);
}

/** Days from `from` to `to`, both included. */
export function daysBetween(from, to) {
  return Math.round((utc(to) - utc(from)) / 86400000) + 1;
}

/** The last `days` days up to today, today included: { from, to } for GET /v1/admin/metrics. */
export function rangeFor(days, today = new Date()) {
  const to = typeof today === 'string' ? today : isoDay(today);
  return { from: addDays(to, -(Math.max(1, Math.trunc(days)) - 1)), to };
}

/** Whether the API accepts the range: from ≤ to, at most 366 days. */
export function validRange({ from, to }) {
  return /^\d{4}-\d{2}-\d{2}$/.test(from) && /^\d{4}-\d{2}-\d{2}$/.test(to) && from <= to && daysBetween(from, to) <= MAX_RANGE_DAYS;
}

const DAY = new Intl.DateTimeFormat('tr-TR', { day: 'numeric', month: 'short', timeZone: 'UTC' });
const FULL = new Intl.DateTimeFormat('tr-TR', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });

/** "24 Eki" for an axis or a tooltip. */
export function dayLabel(iso) {
  return DAY.format(utc(iso));
}

/** "25 Eyl – 24 Eki 2026"; the year on both ends when it changes. */
export function rangeLabel({ from, to }) {
  const sameYear = from.slice(0, 4) === to.slice(0, 4);
  return `${(sameYear ? DAY : FULL).format(utc(from))} – ${FULL.format(utc(to))}`;
}

const COUNT = new Intl.NumberFormat('tr-TR');
const PERCENT = new Intl.NumberFormat('tr-TR', { style: 'percent', maximumFractionDigits: 1 });
const DECIMAL = new Intl.NumberFormat('tr-TR', { maximumFractionDigits: 1 });
const finite = (value) => value !== null && value !== undefined && Number.isFinite(Number(value));

/** A count in Turkish digits ("1.284"); "–" when there is none. */
export function formatCount(value) {
  return finite(value) ? COUNT.format(Number(value)) : '–';
}

/** A 0–1 share as a Turkish percent ("%73,1"); "–" when the API returned null (no question in the range). */
export function formatPercent(ratio) {
  return finite(ratio) ? PERCENT.format(Number(ratio)) : '–';
}

/** A server answer time: "800 ms", "2 sn", "2,5 sn", "3 dk"; "–" without answers. */
export function formatLatency(ms) {
  if (!finite(ms)) return '–';
  const value = Number(ms);
  if (value < 1000) return `${COUNT.format(value)} ms`;
  if (value < 60000) return `${DECIMAL.format(value / 1000)} sn`;
  return `${DECIMAL.format(value / 60000)} dk`;
}

/** 👍 among all votes, or null without votes. */
export function upRatio(feedback) {
  const votes = (feedback?.up ?? 0) + (feedback?.down ?? 0);
  return votes ? feedback.up / votes : null;
}

/** The KPI tiles of the dashboard, in order: [{ id, label, value, note }]. */
export function kpis(totals) {
  const outcomes = totals.outcomes ?? {};
  const rates = totals.outcomeRates ?? {};
  const votes = (totals.feedback?.up ?? 0) + (totals.feedback?.down ?? 0);
  return [
    { id: 'questions', label: 'Soru', value: formatCount(totals.questions), note: 'Cevaplanıp kaydedilen sorular' },
    { id: 'users', label: 'Aktif kullanıcı', value: formatCount(totals.activeUsers),
      note: totals.activeUsersComplete === false ? 'Yalnız saklanan günler; aralığın başı saklama süresinden eski'
        : 'En az bir soru soran kişi' },
    { id: 'cited', label: 'Kaynaklı cevap', value: formatPercent(rates.answered), note: `${formatCount(outcomes.answered ?? 0)} cevap` },
    { id: 'notFound', label: 'Bulunamadı', value: formatPercent(rates.notFound), note: `${formatCount(outcomes.notFound ?? 0)} soru` },
    { id: 'uncited', label: 'Kaynak gösterilemedi', value: formatPercent(rates.uncited), note: `${formatCount(outcomes.uncited ?? 0)} cevap` },
    { id: 'latency', label: 'Süre (p50 / p95)', value: `${formatLatency(totals.latency?.p50Ms)} / ${formatLatency(totals.latency?.p95Ms)}`,
      note: 'Yaklaşık; sunucudaki cevap süresi' },
    { id: 'up', label: '👍 oranı', value: formatPercent(upRatio(totals.feedback)), note: votes ? `${formatCount(votes)} oy` : 'Henüz oy yok' },
  ];
}

/** The daily series of the trend charts: questions, and the shares answered with sources and not found (null on a day without questions). */
export function dailySeries(daily) {
  const share = (n, d) => (d.questions ? n / d.questions : null);
  return {
    days: daily.map((d) => d.day),
    questions: daily.map((d) => d.questions),
    cited: daily.map((d) => share(d.outcomes?.answered ?? 0, d)),
    notFound: daily.map((d) => share(d.outcomes?.notFound ?? 0, d)),
  };
}

/** 👎 per reason in the API's order, then the ones without a reason. */
export function reasonRows(feedback) {
  return [...REASONS.map((r) => ({ id: r.id, label: r.label, value: feedback?.downReasons?.[r.id] ?? 0 })),
    { id: 'NONE', label: 'Neden belirtilmedi', value: feedback?.downWithoutReason ?? 0 }];
}

/**
 * A document of the dashboard by the installation's name policy (VERSO_SHOW_DOCUMENT_NAMES, off by default): its
 * name only when names are shown, otherwise a short id; a deleted one says so.
 */
export function documentLabel(doc, namesShown) {
  if (doc.documentDeleted) return 'Belge silinmiş';
  if (namesShown && doc.fileName) return plainName(doc.fileName);
  return `Belge #${String(doc.documentId ?? '').slice(0, 8)}`;
}

/** No stored answer in the range: the dashboard shows its empty state instead of zeros. */
export function isEmpty(response) {
  return !response?.totals?.questions;
}
