// Shared pieces of the screens: toasts, a confirm dialog, the side drawer, status pills, file icons, empty states and
// skeletons. Every text goes in as a text node (render.js el()); nothing is written as HTML.
import { icon } from './icons.js';
import { el, FAILURES, formatLabel, STATUS } from './render.js';

export function toast(message, tone = 'bad') {
  const glyph = tone === 'ok' ? 'checkCircle' : tone === 'info' ? 'info' : 'alert';
  const node = el('div', { class: `toast ${tone}`, role: 'status' }, icon(glyph), el('p', {}, message),
    el('button', { class: 'icon-button', type: 'button', 'aria-label': 'Kapat', onclick: () => node.remove() }, icon('x', 'sm')));
  document.querySelector('#toasts').append(node);
  setTimeout(() => node.remove(), tone === 'bad' ? 8000 : 4500);
}

/** A modal confirmation (native <dialog>); resolves true when the user confirms. */
export function confirmDialog({ title, message, confirm = 'Onayla', danger = true }) {
  return new Promise((resolve) => {
    const dialog = el('dialog', { class: 'modal', 'aria-labelledby': 'modal-title' });
    const done = (value) => {
      dialog.close();
      dialog.remove();
      resolve(value);
    };
    dialog.append(
      el('div', { class: 'modal-body' }, el('span', { class: 'modal-icon' }, icon(danger ? 'trash' : 'info', 'lg')),
        el('h3', { id: 'modal-title' }, title), el('p', {}, message)),
      el('div', { class: 'modal-actions' },
        el('button', { class: 'button secondary', type: 'button', onclick: () => done(false) }, 'Vazgeç'),
        el('button', { class: `button ${danger ? 'danger' : ''}`, type: 'button', onclick: () => done(true) }, confirm)));
    dialog.addEventListener('cancel', (event) => {
      event.preventDefault();
      done(false);
    });
    document.body.append(dialog);
    dialog.showModal();
  });
}

/** A panel sliding in from the right; returns a function that closes it. */
export function openDrawer({ title, subtitle, body, actions = [] }) {
  const scrim = el('div', { class: 'drawer-scrim' });
  const close = () => {
    scrim.remove();
    drawer.remove();
    removeEventListener('keydown', onKey);
  };
  const onKey = (event) => {
    if (event.key === 'Escape') close();
  };
  const drawer = el('aside', { class: 'drawer', role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
    el('div', { class: 'drawer-head' },
      el('div', { class: 'grow' }, el('h3', {}, title), subtitle ? el('div', { class: 'sub' }, subtitle) : null),
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

/** A file glyph with the type written on it. */
export function fileIcon(format) {
  const type = String(format ?? 'PDF').toLowerCase();
  return el('span', { class: `file-icon ${['pdf', 'docx', 'txt', 'md'].includes(type) ? type : 'txt'}`, title: formatLabel(format) },
    icon('file'), el('b', {}, ['pdf', 'docx', 'txt', 'md'].includes(type) ? type.toUpperCase() : 'DOC'));
}

export function emptyState(glyph, title, text, action) {
  return el('div', { class: 'empty' }, el('span', { class: 'empty-art' }, icon(glyph)), el('strong', {}, title),
    el('p', {}, text), action ?? null);
}

export function skeletonRows(count) {
  return el('div', { class: 'card-body grid' }, Array.from({ length: count }, (_, i) =>
    el('div', { class: `skeleton line ${i % 3 === 2 ? 'short' : ''}` })));
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
