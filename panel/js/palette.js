// The command palette (Ctrl+K / ⌘K): pages, actions and documents in one list with fuzzy, Turkish-folded search
// (render.js fuzzyScore). A visible "Ara" button opens it too; it adds to the sidebar, it does not replace it.
import { icon } from './icons.js';
import { el, fuzzyScore, plainName } from './render.js';
import { fileIcon } from './ui.js';

let open = null;

/**
 * Opens the palette. sources: { pages: [{label, glyph, hint, run}], actions: [...], documents: () => Promise<[...]> }.
 * Documents load in the background; the pages and actions are there at once.
 */
export function openPalette(sources) {
  if (open) return;
  const opener = document.activeElement;
  const input = el('input', { type: 'text', placeholder: 'Sayfa, eylem ya da belge arayın…', 'aria-label': 'Komut ara',
    role: 'combobox', 'aria-expanded': 'true', 'aria-controls': 'palette-list', 'aria-autocomplete': 'list', autocomplete: 'off', spellcheck: 'false' });
  const list = el('div', { class: 'palette-list', id: 'palette-list', role: 'listbox', 'aria-label': 'Sonuçlar' });
  const dialog = el('dialog', { class: 'palette', 'aria-label': 'Komut paleti' },
    el('div', { class: 'palette-input' }, icon('search'), input, el('kbd', {}, 'Esc')),
    list,
    el('div', { class: 'palette-foot' },
      el('span', {}, el('kbd', {}, '↑'), el('kbd', {}, '↓'), 'gezin'),
      el('span', {}, el('kbd', {}, 'Enter'), 'aç'),
      el('span', {}, el('kbd', {}, 'Esc'), 'kapat')));
  let documents = [];
  let items = [];
  let selected = 0;

  const close = () => {
    dialog.close();
    dialog.remove();
    open = null;
    if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
  };

  function ranked(entries, query) {
    return entries.map((entry) => ({ entry, score: fuzzyScore(query, `${entry.label} ${entry.keywords ?? ''}`) }))
      .filter((r) => r.score > 0).sort((a, b) => b.score - a.score).map((r) => r.entry);
  }

  function draw() {
    const query = input.value;
    const groups = [
      ['Sayfalar', ranked(sources.pages, query)],
      ['Eylemler', ranked(sources.actions, query)],
      ['Belgeler', ranked(documents, query).slice(0, query ? 8 : 5)],
    ].filter(([, entries]) => entries.length);
    items = groups.flatMap(([, entries]) => entries);
    selected = Math.min(selected, Math.max(0, items.length - 1));
    if (!items.length) {
      list.replaceChildren(el('div', { class: 'palette-empty' }, 'Eşleşen sonuç yok.'));
      input.removeAttribute('aria-activedescendant');
      return;
    }
    let index = 0;
    list.replaceChildren(...groups.flatMap(([title, entries]) => [
      el('div', { class: 'palette-group', role: 'presentation' }, title),
      ...entries.map((entry) => {
        const at = index++;
        return el('button', { class: 'palette-item', type: 'button', role: 'option', id: `palette-${at}`,
          'aria-selected': String(at === selected), tabindex: -1,
          onpointermove: () => {
            if (selected !== at) {
              selected = at;
              mark();
            }
          },
          onclick: () => run(entry) },
        entry.file ? fileIcon(entry.file, 'sm') : icon(entry.glyph ?? 'chevronRight', 'sm'),
        el('span', { class: 'label' }, entry.label), entry.hint ? el('span', { class: 'hint' }, entry.hint) : null);
      }),
    ]));
    mark();
  }

  function mark() {
    list.querySelectorAll('.palette-item').forEach((node, at) => node.setAttribute('aria-selected', String(at === selected)));
    const current = list.querySelector(`#palette-${selected}`);
    if (current) {
      input.setAttribute('aria-activedescendant', current.id);
      current.scrollIntoView({ block: 'nearest' });
    }
  }

  function run(entry) {
    close();
    entry.run();
  }

  input.addEventListener('input', () => {
    selected = 0;
    draw();
  });
  input.addEventListener('keydown', (event) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      if (!items.length) return;
      selected = (selected + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
      mark();
    } else if (event.key === 'Enter') {
      event.preventDefault();
      if (items[selected]) run(items[selected]);
    }
  });
  dialog.addEventListener('cancel', (event) => {
    event.preventDefault();
    close();
  });
  dialog.addEventListener('click', (event) => {
    if (event.target === dialog) close(); // a click on the backdrop
  });

  document.body.append(dialog);
  dialog.showModal();
  open = dialog;
  draw();
  input.focus();
  Promise.resolve(sources.documents?.()).then((loaded) => {
    if (open !== dialog || !Array.isArray(loaded)) return;
    documents = loaded.map((doc) => ({ label: plainName(doc.fileName), file: doc.format ?? 'PDF', hint: doc.hint, run: doc.run }));
    draw();
  });
}
