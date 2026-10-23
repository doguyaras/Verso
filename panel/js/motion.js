// Motion for the panel (ADR-0017): small, purposeful animations that show where things come from and where they go.
// Every one is skipped under "reduce motion"; none carries information of its own (the end state is always the plain
// content). CSSOM only (element.style, Web Animations): the CSP allows no inline style attributes from markup.

export const reducedMotion = () => globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;

const easeOutExpo = (t) => (t >= 1 ? 1 : 1 - 2 ** (-10 * t));

/** Counts a number up from 0 (ease-out-expo, about 0.9 s); `format` turns the running value into text. */
export function countUp(node, value, format = String, duration = 900) {
  if (reducedMotion() || !Number.isFinite(value) || value <= 0) {
    node.textContent = format(value);
    return;
  }
  const start = performance.now();
  const frame = (now) => {
    const t = Math.min(1, (now - start) / duration);
    node.textContent = format(Math.round(value * easeOutExpo(t)));
    if (t < 1 && node.isConnected) requestAnimationFrame(frame);
    else node.textContent = format(value);
  };
  node.textContent = format(0);
  requestAnimationFrame(frame);
}

/**
 * Text as one span per word, so a fresh answer appears word by word (CSS, delay from --i). The words stay in reading
 * order and the spaces between them stay text, so copying and screen readers see the plain sentence.
 */
export function wordSpans(text, counter = { i: 0 }) {
  return text.split(/(\s+)/).filter(Boolean).map((part) => {
    if (/^\s+$/.test(part)) return part;
    const span = document.createElement('span');
    span.className = 'w';
    span.textContent = part;
    span.style.setProperty('--i', String(Math.min(counter.i++, 120)));
    return span;
  });
}

/** The active-page marker of a nav that slides to the current link instead of jumping. */
export function navIndicator(nav) {
  const bar = document.createElement('span');
  bar.className = 'nav-indicator';
  bar.setAttribute('aria-hidden', 'true');
  nav.prepend(bar);
  let placed = false;
  return () => {
    const current = nav.querySelector('a[aria-current="page"]');
    if (!current) {
      bar.hidden = true;
      return;
    }
    bar.hidden = false;
    bar.classList.toggle('moving', placed && !reducedMotion());
    bar.style.setProperty('--y', `${current.offsetTop}px`);
    bar.style.setProperty('--h', `${current.offsetHeight}px`);
    placed = true;
  };
}

/** A soft light that follows the pointer over cards (mice only): sets --mx/--my on the card under the pointer. */
export function cardSpotlight(root) {
  if (!globalThis.matchMedia?.('(pointer: fine)').matches || reducedMotion()) return;
  root.addEventListener('pointermove', (event) => {
    const card = event.target instanceof Element ? event.target.closest('.card') : null;
    if (!card) return;
    const box = card.getBoundingClientRect();
    card.style.setProperty('--mx', `${event.clientX - box.left}px`);
    card.style.setProperty('--my', `${event.clientY - box.top}px`);
  });
}

/** A short pulse on an element (a source card a citation points to). */
export function pulse(node) {
  if (reducedMotion() || typeof node.animate !== 'function') return;
  node.animate([
    { transform: 'scale(1)', boxShadow: '0 0 0 0 rgba(217, 188, 124, .55)' },
    { transform: 'scale(1.015)', boxShadow: '0 0 0 6px rgba(217, 188, 124, .25)', offset: 0.35 },
    { transform: 'scale(1)', boxShadow: '0 0 0 12px rgba(217, 188, 124, 0)' },
  ], { duration: 700, easing: 'cubic-bezier(.2, 0, 0, 1)' });
}

/** Runs `update` inside a view transition where the browser has one (a soft cross-fade between screens). */
export function transition(update) {
  if (reducedMotion() || typeof document.startViewTransition !== 'function') {
    update();
    return;
  }
  document.startViewTransition(update);
}
