// The Verso signature as an SVG element, and its "being signed" animation (ADR-0017). The outline is revealed through
// a mask along the pen strokes, one stroke after the other, while a gold pen tip moves at the end of the ink. Built with
// createElementNS (no markup strings, Trusted Types); the animation sets SVG attributes only, so the CSP is untouched.
import { SIGNATURE, timeline } from './signature-data.js';

const SVG = 'http://www.w3.org/2000/svg';
let ids = 0;

function node(tag, attributes) {
  const element = document.createElementNS(SVG, tag);
  for (const [key, value] of Object.entries(attributes)) element.setAttribute(key, String(value));
  return element;
}

/**
 * The signature, `height` px tall. `ink` and `tip` are CSS colours (custom properties welcome). `animated` builds the
 * mask and the pen tip; without it the signature is a plain outline (menus, small sizes).
 */
export function signature({ height = 32, ink = 'currentColor', tip = 'var(--ink-gold, #e9d6a6)', animated = false, label = 'Verso' } = {}) {
  const [x, y, w, h] = SIGNATURE.box;
  const svg = node('svg', { viewBox: `${x} ${y} ${w} ${h}`, height, width: Math.round((height * w) / h), class: 'signature',
    role: 'img', 'aria-label': label, focusable: 'false' });
  const outline = node('path', { d: SIGNATURE.outline, fill: ink });
  if (!animated) {
    svg.append(outline);
    return svg;
  }
  const id = `signature-mask-${++ids}`;
  // The mask region is given explicitly: its default (-10%..120% of the viewport) would cut the lower letters.
  const mask = node('mask', { id, maskUnits: 'userSpaceOnUse', x, y, width: w, height: h });
  for (const d of SIGNATURE.pen) {
    mask.append(node('path', { d, fill: 'none', stroke: '#fff', 'stroke-width': SIGNATURE.thickness * 1.25,
      'stroke-linecap': 'round', 'stroke-linejoin': 'round' }));
  }
  outline.setAttribute('mask', `url(#${id})`);
  const glow = node('circle', { r: SIGNATURE.thickness * 1.4, fill: tip, opacity: 0, class: 'signature-glow' });
  const nib = node('circle', { r: SIGNATURE.thickness * 0.3, fill: tip, opacity: 0, class: 'signature-tip' });
  svg.append(node('defs', {}), outline, glow, nib);
  svg.querySelector('defs').append(mask);
  svg.dataset.signed = 'false';
  return svg;
}

const ease = (t) => (t < 0.5 ? 2 * t * t : 1 - (-2 * t + 2) ** 2 / 2); // a hand speeds up and slows down per stroke

/**
 * Plays the signing once (about 1.6 s). With "reduce motion" the finished signature is shown at once. Resolves when
 * the ink is complete.
 */
export function sign(svg, { total = 1600, delay = 0 } = {}) {
  const strokes = [...svg.querySelectorAll('mask path')];
  const glow = svg.querySelector('.signature-glow');
  const nib = svg.querySelector('.signature-tip');
  const plan = timeline(total).map((stroke, i) => ({ ...stroke, path: strokes[i], real: strokes[i].getTotalLength() }));
  for (const s of plan) {
    s.path.setAttribute('stroke-dasharray', `${s.real} ${s.real}`);
    s.path.setAttribute('stroke-dashoffset', s.real);
  }
  const reduced = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  return new Promise((resolve) => {
    const finish = () => {
      for (const s of plan) s.path.setAttribute('stroke-dashoffset', 0);
      glow.setAttribute('opacity', 0);
      nib.setAttribute('opacity', 0);
      svg.dataset.signed = 'true';
      resolve();
    };
    if (reduced) {
      finish();
      return;
    }
    const start = performance.now() + delay;
    const frame = (now) => {
      const t = now - start;
      let point = null;
      for (const s of plan) {
        const raw = Math.min(1, Math.max(0, (t - s.delay) / s.duration));
        const done = ease(raw);
        s.path.setAttribute('stroke-dashoffset', s.real * (1 - done));
        if (raw > 0 && raw < 1) point = s.path.getPointAtLength(s.real * done);
      }
      for (const dot of [glow, nib]) {
        if (point) {
          dot.setAttribute('cx', point.x);
          dot.setAttribute('cy', point.y);
        }
        dot.setAttribute('opacity', point ? (dot === glow ? 0.35 : 1) : 0);
      }
      if (t < total) requestAnimationFrame(frame);
      else finish();
    };
    requestAnimationFrame(frame);
  });
}
