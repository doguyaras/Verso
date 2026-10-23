// The two switches at the top right (ADR-0018): the theme (a sliding thumb that turns from sun to moon) and the
// language (TR / EN with a sliding marker). Built with createElement; the motion is CSS and stops under "reduce motion".
import { icon } from './icons.js';
import { el } from './render.js';

/** A switch for the dark theme; `onChange(dark)` is called with the new state. */
export function themeSwitch({ dark, label, onChange }) {
  const button = el('button', { class: 'theme-switch', type: 'button', role: 'switch', 'aria-checked': String(dark), 'aria-label': label,
    onclick: () => {
      const next = button.getAttribute('aria-checked') !== 'true';
      button.setAttribute('aria-checked', String(next));
      onChange(next);
    } },
  el('span', { class: 'thumb', 'aria-hidden': 'true' }, icon('sun', 'sm'), icon('moon', 'sm')));
  return button;
}

/** The TR / EN switch; `onChange(lang)` is called with "tr" or "en". */
export function langSwitch({ lang, label, onChange }) {
  const group = el('div', { class: 'lang-switch', role: 'group', 'aria-label': label, 'data-lang': lang });
  const buttons = ['tr', 'en'].map((value) => el('button', { type: 'button', lang: value, 'aria-pressed': String(value === lang),
    onclick: () => {
      if (group.dataset.lang === value) return;
      group.dataset.lang = value;
      for (const b of buttons) b.setAttribute('aria-pressed', String(b.lang === value));
      onChange(value);
    } }, value.toUpperCase()));
  group.append(el('span', { class: 'slide', 'aria-hidden': 'true' }), ...buttons);
  return group;
}
