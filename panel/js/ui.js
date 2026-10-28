// Shared pieces of the screens: toasts, a confirm dialog, a popover menu, the side drawer, status pills, file badges,
// empty states and skeletons. Every text goes in as a text node (render.js el()); nothing is written as HTML.
import { icon } from './icons.js';
import { el, FAILURES, formatLabel, STATUS } from './render.js';

/**
 * A toast. Successes and notes close by themselves after 4.5 s; errors stay until closed, so they are not missed
 * (NN/g). Errors are announced as alerts, the rest politely.
 */
export function toast(message, tone = 'bad') {
  const glyph = tone === 'ok' ? 'checkCircle' : tone === 'info' ? 'info' : 'alert';
  const node = el('div', { class: `toast ${tone}`, role: tone === 'bad' ? 'alert' : 'status' }, icon(glyph), el('p', {}, message),
    el('button', { class: 'icon-button', type: 'button', 'aria-label': 'Kapat', onclick: () => node.remove() }, icon('x', 'sm')));
  document.querySelector('#toasts').append(node);
  if (tone !== 'bad') setTimeout(() => node.remove(), 4500);
}

/**
 * A modal confirmation (native <dialog>); resolves true when the user confirms. The buttons say what they do and the
 * safe choice ("Vazgeç") has the focus, so Enter never deletes by accident (NN/g confirmation dialogs).
 */
export function confirmDialog({ title, message, confirm = 'Onayla', danger = true }) {
  return new Promise((resolve) => {
    const dialog = el('dialog', { class: 'modal', 'aria-labelledby': 'modal-title', 'aria-describedby': 'modal-text' });
    const done = (value) => {
      dialog.close();
      dialog.remove();
      resolve(value);
    };
    const cancel = el('button', { class: 'button secondary', type: 'button', onclick: () => done(false) }, 'Vazgeç');
    dialog.append(
      el('div', { class: 'modal-body' }, el('span', { class: `modal-icon ${danger ? '' : 'info'}` }, icon(danger ? 'trash' : 'info', 'lg')),
        el('h2', { id: 'modal-title' }, title), el('p', { id: 'modal-text' }, message)),
      el('div', { class: 'modal-actions' }, cancel,
        el('button', { class: `button ${danger ? 'danger' : ''}`, type: 'button', onclick: () => done(true) }, confirm)));
    dialog.addEventListener('cancel', (event) => {
      event.preventDefault();
      done(false);
    });
    document.body.append(dialog);
    dialog.showModal();
    cancel.focus();
  });
}

/**
 * A popover menu next to an anchor (fixed position, so it is never clipped by the sidebar). Items: { label, glyph,
 * onSelect, checked } or 'separator' or { heading }. Escape, a click outside or a choice closes it; the anchor gets the
 * focus back.
 */
export function openMenu(anchor, items, { placement = 'above' } = {}) {
  document.querySelector('.menu')?.remove();
  const menu = el('div', { class: 'menu', role: 'menu' });
  const close = () => {
    menu.remove();
    removeEventListener('pointerdown', outside, true);
    removeEventListener('keydown', keys, true);
    anchor.setAttribute('aria-expanded', 'false');
  };
  const outside = (event) => {
    if (!menu.contains(event.target) && !anchor.contains(event.target)) close();
  };
  const keys = (event) => {
    const buttons = [...menu.querySelectorAll('button')];
    const at = buttons.indexOf(document.activeElement);
    if (event.key === 'Escape') {
      event.preventDefault();
      close();
      anchor.focus();
    } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const step = event.key === 'ArrowDown' ? 1 : -1;
      buttons[(at + step + buttons.length) % buttons.length]?.focus();
    }
  };
  for (const item of items) {
    if (item === 'separator') menu.append(el('hr'));
    else if (item.heading) menu.append(el('div', { class: 'menu-head' }, el('strong', {}, item.heading), el('span', {}, item.sub ?? '')));
    else if (item.label === undefined) menu.append(el('div', { class: 'menu-label' }, item.group));
    else {
      menu.append(el('button', { type: 'button', role: item.checked === undefined ? 'menuitem' : 'menuitemradio',
        'aria-checked': item.checked === undefined ? undefined : String(item.checked), onclick: () => {
          close();
          item.onSelect();
        } }, icon(item.glyph, 'sm'), item.label));
    }
  }
  document.body.append(menu);
  const box = anchor.getBoundingClientRect();
  const width = menu.offsetWidth;
  menu.style.left = `${Math.max(8, Math.min(box.left, innerWidth - width - 8))}px`;
  if (placement === 'above') menu.style.bottom = `${innerHeight - box.top + 6}px`;
  else menu.style.top = `${box.bottom + 6}px`;
  anchor.setAttribute('aria-expanded', 'true');
  addEventListener('pointerdown', outside, true);
  addEventListener('keydown', keys, true);
  menu.querySelector('button')?.focus();
  return close;
}

/**
 * A panel sliding in from the right; returns a function that closes it. The opener gets the focus back. Escape closes
 * it, except while a confirmation dialog on top of it is open: that Escape belongs to the dialog.
 */
export function openDrawer({ title, subtitle, body, actions = [], className = '' }) {
  const opener = document.activeElement;
  const scrim = el('div', { class: 'drawer-scrim' });
  const close = () => {
    scrim.remove();
    drawer.remove();
    removeEventListener('keydown', onKey);
    if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
  };
  const onKey = (event) => {
    if (event.key === 'Escape' && !document.querySelector('dialog[open]')) close();
  };
  const drawer = el('aside', { class: `drawer ${className}`.trim(), role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'drawer-title' },
    el('div', { class: 'drawer-head' },
      el('div', { class: 'grow' }, el('h2', { id: 'drawer-title' }, title), subtitle ? el('div', { class: 'sub' }, subtitle) : null),
      el('button', { class: 'icon-button', type: 'button', 'aria-label': 'Kapat', onclick: close }, icon('x'))),
    el('div', { class: 'drawer-body' }, body),
    actions.length ? el('div', { class: 'drawer-foot' }, actions) : null);
  scrim.addEventListener('click', close);
  addEventListener('keydown', onKey);
  document.body.append(scrim, drawer);
  drawer.querySelector('button').focus();
  return close;
}

export function statusPill(status) {
  const meta = STATUS[status] ?? { label: 'İşleniyor', tone: 'wait' };
  return el('span', { class: `pill ${meta.tone}` }, el('span', { class: 'dot' }), meta.label);
}

export function failureText(reason) {
  return FAILURES[reason] ?? 'Belge işlenemedi';
}

const FILE_TYPES = ['pdf', 'docx', 'txt', 'md'];

/** A file type badge: the extension on a tile, coloured by type; the type name is its accessible title. */
export function fileIcon(format, size = '') {
  const type = String(format ?? 'PDF').toLowerCase();
  const known = FILE_TYPES.includes(type) ? type : 'txt';
  return el('span', { class: `file-icon ${known} ${size}`.trim(), title: formatLabel(format), 'aria-hidden': 'true' },
    FILE_TYPES.includes(type) ? type.toUpperCase() : 'DOC');
}

/** The label of a closed disclosure ("az göster, isteyince aç", ADR-0058 Sadeleştirme). */
export const MORE_LABEL = 'Ayrıntıları göster';

/**
 * A native disclosure, closed by default: the first view shows a few key facts, the rest opens on demand. <details> and
 * <summary> are keyboard-accessible by themselves (Enter and Space toggle); the chevron turns when open (panel.css).
 * `children` is a node or a list of nodes; `className` adds a class (e.g. 'filters').
 */
export function disclosure(label = MORE_LABEL, children = [], className = '') {
  return el('details', { class: `more ${className}`.trim() },
    el('summary', {}, icon('chevronDown', 'sm'), el('span', {}, label)),
    el('div', { class: 'more-body' }, children));
}

/** The one line of help a screen shows; anything longer sits behind a small, closed info toggle. */
export function helpLine(text, more = null) {
  return el('div', { class: 'help-line' }, el('span', {}, text),
    more ? el('details', { class: 'info-toggle' },
      el('summary', { 'aria-label': 'Daha fazla bilgi', title: 'Daha fazla bilgi' }, icon('info', 'sm')), el('p', {}, more)) : null);
}

export function emptyState(glyph, title, text, action) {
  return el('div', { class: 'empty' }, el('span', { class: 'empty-art' }, icon(glyph, 'lg')), el('strong', {}, title),
    el('p', {}, text), action ?? null);
}

export function skeletonRows(count) {
  return el('div', { class: 'card-body grid' }, Array.from({ length: count }, (_, i) =>
    el('div', { class: `skeleton line ${i % 3 === 2 ? 'short' : ''}`.trim() })));
}

/** "3 dakika önce" style time; the exact time goes into the title. */
export function relativeTime(iso) {
  const then = new Date(iso).getTime();
  const seconds = Math.round((then - Date.now()) / 1000);
  const format = new Intl.RelativeTimeFormat('tr', { numeric: 'auto' });
  const steps = [[60, 'second'], [60, 'minute'], [24, 'hour'], [7, 'day'], [4.35, 'week'], [12, 'month'], [Infinity, 'year']];
  let value = seconds;
  for (const [size, unit] of steps) {
    if (Math.abs(value) < size) return format.format(Math.round(value), unit);
    value /= size;
  }
  return '';
}

export function formatNumber(value) {
  return new Intl.NumberFormat('tr-TR').format(value ?? 0);
}

/** The first letters of a name for the avatar. */
export function initials(name) {
  const parts = String(name ?? '?').trim().split(/[\s._-]+/).filter(Boolean);
  return (parts.length > 1 ? parts[0][0] + parts[1][0] : (parts[0] ?? '?').slice(0, 2)).toLocaleUpperCase('tr');
}
