// The page viewer drawer (ADR-0035): opened from a source of an answer, it shows the stored text of the cited page or
// section with the cited excerpt highlighted (CSS Custom Highlight API, <mark> where it is missing; highlight.js),
// and moves to the previous or next page within the document. A modal dialog: the focus stays inside, Esc closes it,
// and the focus goes back to the button that opened it. Text only: no HTML string, no inline style (CSP, Trusted Types).
import { api } from './api.js';
import { icon } from './icons.js';
import { prune, writeMarked } from './highlight.js';
import { excerptRange, pageHeading, pageNav } from './pageview.js';
import { el, plainName } from './render.js';
import { reducedMotion } from './motion.js';

let current = null;

/** Closes the open drawer, if any (a screen change). */
export function closePageDrawer() {
  current?.close();
}

/**
 * Opens the drawer on `citation` ({ documentId, fileName, page, unit, excerpt }). withAuth wraps the API call (a
 * refreshed token, the sign-in on 401); opener gets the focus back on close.
 */
export function openPageDrawer(citation, { withAuth = (call) => call(), opener = document.activeElement } = {}) {
  closePageDrawer();
  const titleId = `drawer-title-${Date.now()}`;
  let shown = citation.page;
  let count = null;
  let closed = false;

  const where = el('span', { class: 'drawer-where tabular' }, pageHeading(citation.unit, citation.page, null));
  const status = el('p', { class: 'drawer-status', role: 'status', 'aria-live': 'polite' });
  const cut = el('p', { class: 'drawer-note', hidden: true }, 'Sayfa çok uzun; yalnız başı gösteriliyor.');
  const text = el('div', { class: 'page-text', tabindex: 0, role: 'document', 'aria-label': 'Sayfa metni' });
  const prev = el('button', { class: 'button secondary sm', type: 'button', disabled: true, onclick: () => show(shown - 1) }, 'Önceki');
  const next = el('button', { class: 'button secondary sm', type: 'button', disabled: true, onclick: () => show(shown + 1) },
    'Sonraki', icon('arrowRight', 'sm'));
  // The look of the panel's other drawers (ui.js openDrawer), plus the focus trap a page viewer needs.
  const closeButton = el('button', { class: 'icon-button', type: 'button', 'aria-label': 'Kapat', onclick: () => close() }, icon('x'));
  const panel = el('aside', { class: 'drawer page-drawer', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': titleId },
    el('div', { class: 'drawer-head' },
      el('div', { class: 'grow' }, el('h2', { id: titleId }, plainName(citation.fileName)), el('div', { class: 'sub' }, where)),
      closeButton),
    el('nav', { class: 'drawer-nav', 'aria-label': citation.unit === 'SECTION' ? 'Bölümler' : 'Sayfalar' }, prev, next),
    el('div', { class: 'drawer-body' }, status, cut, text));
  const backdrop = el('div', { class: 'drawer-scrim', onclick: () => close() });

  function focusables() {
    return [...panel.querySelectorAll('button, [tabindex="0"]')].filter((node) => !node.disabled && !node.hidden);
  }

  // Esc closes; Tab and Shift+Tab wrap around inside the drawer (WAI-ARIA dialog pattern).
  panel.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      close();
      return;
    }
    if (event.key !== 'Tab') return;
    const items = focusables();
    if (!items.length) return;
    const first = items[0];
    const last = items[items.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  });

  function close() {
    if (closed) return;
    closed = true;
    panel.remove();
    backdrop.remove();
    prune();
    current = null;
    if (opener?.isConnected) opener.focus();
  }

  function navigation() {
    const { prev: before, next: after } = pageNav(shown, count);
    prev.disabled = before === null;
    next.disabled = after === null;
  }

  async function show(number) {
    if (number < 1 || (Number.isInteger(count) && number > count)) return;
    shown = number;
    prev.disabled = true;
    next.disabled = true;
    status.textContent = citation.unit === 'SECTION' ? 'Bölüm yükleniyor…' : 'Sayfa yükleniyor…';
    let page;
    try {
      page = await withAuth(() => api.getPage(citation.documentId, number));
    } catch (error) {
      if (closed || shown !== number) return;
      text.replaceChildren();
      status.textContent = error?.message || 'Sayfa açılamadı.';
      navigation();
      return;
    }
    if (closed || shown !== number) return;
    count = Number.isInteger(page.pageCount) ? page.pageCount : count;
    where.textContent = pageHeading(page.unit, page.number, count);
    const cited = number === citation.page && Boolean(citation.excerpt);
    const range = cited ? excerptRange(page.text, citation.excerpt) : null;
    writeMarked(text, page.text, range ? [range] : []);
    cut.hidden = !page.truncated;
    status.textContent = range ? 'Alıntı vurgulandı.' : cited ? 'Alıntı bu sayfada birebir bulunamadı.' : '';
    navigation();
    text.scrollTop = 0;
    if (range) reveal(range);
  }

  /** Scrolls the page text so the highlighted excerpt is in view (a Range works with and without the API). */
  function reveal({ start }) {
    const node = text.querySelector('mark') ?? text.firstChild;
    if (!node) return;
    if (node.nodeName === 'MARK') {
      node.scrollIntoView({ block: 'center', behavior: reducedMotion() ? 'auto' : 'smooth' });
      return;
    }
    const range = document.createRange();
    range.setStart(node, start);
    range.setEnd(node, start);
    const box = range.getBoundingClientRect();
    text.scrollTop += box.top - text.getBoundingClientRect().top - text.clientHeight / 3;
  }

  current = { close };
  document.body.append(backdrop, panel);
  closeButton.focus();
  show(citation.page);
  return current;
}
