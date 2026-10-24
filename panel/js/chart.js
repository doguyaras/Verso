// Small charts for the value dashboard (ADR-0025), drawn as inline SVG with createElementNS: no chart library, no
// markup strings (CSP Trusted Types). The marks follow the dataviz rules: 2 px lines with an end dot ringed in the
// surface colour, a 10 % area wash for a single series, hairline solid grid, one y axis, text in text tokens (never the
// series colour), a legend for two series, a crosshair with one tooltip for every series, the same on keyboard focus,
// and a table twin for every chart. Colours are the --chart-* tokens of panel.css (validated in both themes).
import { el } from './render.js';

const SVG = 'http://www.w3.org/2000/svg';
const round = (n) => Math.round(n * 10) / 10;

/** A y scale with clean ticks: { top, step } where top ≥ max and step is 1, 2, 2.5 or 5 × 10^k (at least 1 for counts). */
export function niceScale(max, ticks = 5, { integer = true } = {}) {
  if (!(max > 0)) return { top: ticks, step: 1 };
  const raw = max / ticks;
  const magnitude = 10 ** Math.floor(Math.log10(raw));
  let step = [1, 2, 2.5, 5, 10].map((m) => m * magnitude).find((s) => s >= raw - 1e-9);
  if (integer) step = Math.max(1, Math.ceil(step));
  return { top: step * Math.ceil(max / step - 1e-9), step };
}

/** The tick values 0, step, … top. */
export function tickValues({ top, step }) {
  const values = [];
  for (let v = 0; v <= top + step / 1000; v += step) values.push(round(v * 1000) / 1000);
  return values;
}

/** x of the i-th of n evenly spaced days; a single day sits in the middle. */
export function xAt(i, n, left, right) {
  return n <= 1 ? (left + right) / 2 : left + (i * (right - left)) / (n - 1);
}

/** y of a value on a scale whose top is `top` (the plot's top edge) and 0 is the baseline. */
export function yAt(value, top, plotTop, plotBottom) {
  return plotBottom - (top > 0 ? value / top : 0) * (plotBottom - plotTop);
}

const points = (values, frame) => values.map((v, i) => (v == null ? null
  : [round(xAt(i, values.length, frame.left, frame.right)), round(yAt(v, frame.max, frame.top, frame.bottom))]));

/** Runs of consecutive non-null points; a null (a day without data) breaks the line instead of drawing it at 0. */
function segments(values, frame) {
  const runs = [];
  let run = [];
  for (const p of points(values, frame)) {
    if (p) run.push(p);
    else if (run.length) {
      runs.push(run);
      run = [];
    }
  }
  if (run.length) runs.push(run);
  return runs;
}

/** The d of a line through the values; frame = { left, right, top, bottom, max }. */
export function linePath(values, frame) {
  return segments(values, frame).map((run) => run.map(([x, y], i) => `${i ? 'L' : 'M'}${x},${y}`).join('')).join('');
}

/** The d of the wash under a single series, closed on the baseline for every run. */
export function areaPath(values, frame) {
  const base = round(frame.bottom);
  return segments(values, frame).filter((run) => run.length > 1)
    .map((run) => `M${run[0][0]},${base}${run.map(([x, y]) => `L${x},${y}`).join('')}L${run.at(-1)[0]},${base}Z`).join('');
}

/** The indexes of points with no neighbour (a line alone would not show them): drawn as dots. */
export function isolatedPoints(values) {
  return values.map((v, i) => (v != null && values[i - 1] == null && values[i + 1] == null ? i : -1)).filter((i) => i >= 0);
}

/** The day under a pointer at x. */
export function nearestIndex(x, n, left, right) {
  if (n <= 1) return 0;
  return Math.min(n - 1, Math.max(0, Math.round(((x - left) / (right - left)) * (n - 1))));
}

/** The indexes labelled on the x axis: first, middle and last (no crowding at 375 px). */
export function axisDays(n) {
  return [...new Set([0, Math.floor((n - 1) / 2), n - 1])].filter((i) => i >= 0 && i < n);
}

function svg(tag, attributes = {}) {
  const node = document.createElementNS(SVG, tag);
  for (const [key, value] of Object.entries(attributes)) {
    if (value !== null && value !== undefined) node.setAttribute(key, String(value));
  }
  return node;
}

function text(x, y, value, attributes = {}) {
  const node = svg('text', { x: round(x), y: round(y), ...attributes });
  node.textContent = value;
  return node;
}

const HEIGHT = 200;

/**
 * A line chart over days. series: [{ id, label, values, tone: 'c1' | 'c2' }]; top fixes the scale (1 for shares);
 * format(value) for values and the axis; dayLabel(iso) for dates. The figure holds the plot (focusable: ←/→ move the
 * crosshair, Home/End jump, Esc hides), a legend for two series and a table with every value.
 */
export function lineChart({ title, days, series, top, format, axisFormat = format, dayLabel }) {
  const n = days.length;
  const max = Math.max(0, ...series.flatMap((s) => s.values.filter((v) => v != null)));
  const scale = top ? { top, step: top / 4 } : niceScale(max);
  const plot = el('div', { class: 'chart-plot', tabindex: 0, role: 'group',
    'aria-label': `${title}: ←/→ ile günler arasında gezin. Bütün değerler aşağıdaki tabloda.` });
  const tip = el('div', { class: 'chart-tip', hidden: true, 'aria-hidden': 'true' });
  const live = el('div', { class: 'sr-only', 'aria-live': 'polite', 'aria-atomic': 'true' });
  const graphic = svg('svg', { class: 'chart-svg', height: HEIGHT, 'aria-hidden': 'true', focusable: 'false' });
  plot.append(graphic, tip);
  let frame = null;
  let cross = null;
  let marks = [];
  let shown = -1;

  function draw(width) {
    const w = Math.max(240, Math.round(width));
    frame = { left: 40, right: w - 16, top: 14, bottom: HEIGHT - 26, max: scale.top };
    graphic.setAttribute('viewBox', `0 0 ${w} ${HEIGHT}`);
    graphic.setAttribute('width', String(w));
    graphic.replaceChildren();
    const grid = svg('g', { class: 'chart-grid' });
    for (const tick of tickValues(scale)) {
      const y = round(yAt(tick, scale.top, frame.top, frame.bottom));
      grid.append(svg('line', { x1: frame.left, x2: frame.right, y1: y, y2: y }), text(frame.left - 8, y + 4, axisFormat(tick), { class: 'chart-axis', 'text-anchor': 'end' }));
    }
    for (const i of axisDays(n)) {
      const anchor = n === 1 ? 'middle' : i === 0 ? 'start' : i === n - 1 ? 'end' : 'middle';
      grid.append(text(xAt(i, n, frame.left, frame.right), HEIGHT - 6, dayLabel(days[i]), { class: 'chart-axis', 'text-anchor': anchor }));
    }
    graphic.append(grid);
    if (series.length === 1) graphic.append(svg('path', { class: `chart-area ${series[0].tone}`, d: areaPath(series[0].values, frame) }));
    for (const s of series) {
      graphic.append(svg('path', { class: `chart-line ${s.tone}`, d: linePath(s.values, frame) }));
      for (const i of isolatedPoints(s.values)) {
        graphic.append(svg('circle', { class: `chart-dot ${s.tone}`, r: 4, cx: round(xAt(i, n, frame.left, frame.right)), cy: round(yAt(s.values[i], scale.top, frame.top, frame.bottom)) }));
      }
      const last = s.values.findLastIndex((v) => v != null);
      if (last >= 0) {
        const x = xAt(last, n, frame.left, frame.right);
        const y = yAt(s.values[last], scale.top, frame.top, frame.bottom);
        graphic.append(svg('circle', { class: `chart-dot ${s.tone}`, r: 4, cx: round(x), cy: round(y) }));
        // One series: its latest value at the end of the line (selective direct label); two: the legend and tooltip.
        if (series.length === 1) graphic.append(text(x - 8, Math.max(frame.top + 10, y - 10), format(s.values[last]), { class: 'chart-value', 'text-anchor': 'end' }));
      }
    }
    cross = svg('line', { class: 'chart-cross', y1: frame.top, y2: frame.bottom, visibility: 'hidden' });
    marks = series.map((s) => svg('circle', { class: `chart-dot ${s.tone}`, r: 5, visibility: 'hidden' }));
    graphic.append(cross, ...marks);
    if (shown >= 0) show(shown, false);
  }

  function show(i, announce) {
    shown = i;
    const x = xAt(i, n, frame.left, frame.right);
    cross.setAttribute('x1', String(round(x)));
    cross.setAttribute('x2', String(round(x)));
    cross.setAttribute('visibility', 'visible');
    series.forEach((s, k) => {
      const v = s.values[i];
      marks[k].setAttribute('visibility', v == null ? 'hidden' : 'visible');
      if (v != null) {
        marks[k].setAttribute('cx', String(round(x)));
        marks[k].setAttribute('cy', String(round(yAt(v, scale.top, frame.top, frame.bottom))));
      }
    });
    tip.replaceChildren(el('span', { class: 'chart-tip-day' }, dayLabel(days[i])), ...series.map((s) =>
      el('span', { class: 'chart-tip-row' }, el('span', { class: `chart-key line ${s.tone}` }), el('strong', {}, s.values[i] == null ? '–' : format(s.values[i])), s.label)));
    tip.hidden = false;
    tip.classList.toggle('flip', x > frame.right - 150);
    tip.style.setProperty('--x', `${round(x)}px`);
    if (announce) live.textContent = `${dayLabel(days[i])}: ${series.map((s) => `${s.label} ${s.values[i] == null ? 'yok' : format(s.values[i])}`).join(', ')}`;
  }

  function hide() {
    shown = -1;
    tip.hidden = true;
    cross?.setAttribute('visibility', 'hidden');
    for (const m of marks) m.setAttribute('visibility', 'hidden');
  }

  plot.addEventListener('pointermove', (event) => {
    if (!frame) return;
    const box = graphic.getBoundingClientRect();
    show(nearestIndex(event.clientX - box.left, n, frame.left, frame.right), false);
  });
  plot.addEventListener('pointerleave', () => {
    if (document.activeElement !== plot) hide();
  });
  plot.addEventListener('focus', () => frame && show(shown >= 0 ? shown : n - 1, true));
  plot.addEventListener('blur', hide);
  plot.addEventListener('keydown', (event) => {
    const moves = { ArrowLeft: -1, ArrowRight: 1, Home: -Infinity, End: Infinity };
    if (event.key === 'Escape') {
      hide();
      return;
    }
    if (!(event.key in moves) || !frame) return;
    event.preventDefault();
    const step = moves[event.key];
    const next = Number.isFinite(step) ? Math.min(n - 1, Math.max(0, (shown < 0 ? n - 1 : shown) + step)) : step < 0 ? 0 : n - 1;
    show(next, true);
  });

  if (typeof ResizeObserver === 'function') {
    new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect.width;
      if (width) draw(width);
    }).observe(plot);
  } else draw(640);

  const legend = series.length > 1
    ? el('ul', { class: 'chart-legend' }, series.map((s) => el('li', {}, el('span', { class: `chart-key line ${s.tone}` }), s.label)))
    : null;
  const table = el('details', { class: 'chart-table' }, el('summary', {}, 'Tablo olarak göster'),
    el('div', { class: 'table-wrap' }, el('table', { class: 'table compact' },
      el('thead', {}, el('tr', {}, el('th', { scope: 'col' }, 'Gün'), series.map((s) => el('th', { scope: 'col', class: 'num' }, s.label)))),
      el('tbody', {}, days.map((day, i) => el('tr', {}, el('th', { scope: 'row' }, dayLabel(day)),
        series.map((s) => el('td', { class: 'num' }, s.values[i] == null ? '–' : format(s.values[i])))))))));
  return el('figure', { class: 'chart' }, legend, plot, live, table);
}

/**
 * Horizontal bars of one series (reasons, documents): the label, a thin bar from one baseline and the value at its tip
 * in text colour. rows: [{ label, value, muted }]; format(value) for the number.
 */
export function barList(rows, { format, label }) {
  const max = Math.max(0, ...rows.map((r) => r.value));
  return el('ul', { class: 'bars', 'aria-label': label }, rows.map((row) => {
    const fill = el('span', { class: 'bar-fill c1' });
    fill.style.setProperty('--w', `${max ? Math.max(row.value ? 1 : 0, (row.value / max) * 100) : 0}%`);
    return el('li', { class: row.muted ? 'muted-row' : null },
      el('span', { class: 'bar-label', title: row.label }, row.label),
      el('span', { class: 'bar-track', 'aria-hidden': 'true' }, fill),
      el('span', { class: 'bar-value' }, format(row.value)));
  }));
}

/** A part-to-whole bar of two or three parts with a 2 px surface gap and a legend that carries the numbers. */
export function splitBar(parts, { format }) {
  const total = parts.reduce((n, p) => n + p.value, 0);
  const bar = el('div', { class: 'split-bar', 'aria-hidden': 'true' }, parts.filter((p) => p.value > 0).map((p) => {
    const part = el('span', { class: p.tone });
    part.style.setProperty('--w', `${(p.value / total) * 100}%`);
    return part;
  }));
  return el('div', { class: 'split' }, total ? bar : null,
    el('ul', { class: 'chart-legend' }, parts.map((p) => el('li', {}, el('span', { class: `chart-key box ${p.tone}` }),
      `${p.label}: `, el('strong', {}, format(p.value)), total ? ` (${new Intl.NumberFormat('tr-TR', { style: 'percent', maximumFractionDigits: 1 }).format(p.value / total)})` : ''))));
}
