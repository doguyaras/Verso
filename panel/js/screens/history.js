// "Geçmiş" (ADR-0025): the user's own conversations from the server (ADR-0023) in a drawer that every screen can open
// (the menu entry, "g g", the ask screen, the command palette). A paginated list, newest activity first, filtered on
// the loaded pages with Turkish letters folded; a conversation opens into the ask screen and the next question
// continues it. Rename inline, delete one with the panel's own confirmation dialog, "Tümünü sil" only after the word
// SİL is typed. Titles are the user's text: shown as text nodes, never logged, never stored in the browser.
import { api, ApiError } from '../api.js';
import { DELETE_ALL_WORD, deleteAllConfirmed, filterConversations, hasMore, HISTORY_PAGE_SIZE, restoreEntries, TITLE_MAX,
  titleProblem, turnCountLabel } from '../conversations.js';
import { icon } from '../icons.js';
import { el, fill, formatDate, plainName } from '../render.js';
import { restoreConversation, startNewConversation, thread } from '../store.js';
import { confirmDialog, emptyState, openDrawer, relativeTime, toast } from '../ui.js';

let openNow = null;

/**
 * Opens the history drawer. ctx: { guarded, navigate } from app.js. A second call while it is open does nothing.
 * The retention is not exposed by the API, so the drawer says only that the installation's period applies.
 */
export function openHistory(ctx) {
  if (openNow) return;
  const { guarded, navigate } = ctx;
  const state = { items: [], page: null, query: '', loading: true, failed: false, renaming: null };
  const search = el('input', { class: 'input', type: 'search', placeholder: 'Başlıklarda ara…', 'aria-label': 'Konuşmalarda ara',
    autocomplete: 'off', spellcheck: 'false', oninput: () => {
      state.query = search.value;
      draw();
    } });
  const list = el('ul', { class: 'history-list', 'aria-label': 'Konuşmalar' });
  const more = el('button', { class: 'button secondary sm', type: 'button', hidden: true, onclick: () => load(state.page.number + 1) },
    icon('chevronDown', 'sm'), 'Daha eski konuşmaları yükle');
  const note = el('p', { class: 'history-note muted' });
  const status = el('div', { class: 'sr-only', role: 'status', 'aria-live': 'polite' });
  const foot = el('div', { class: 'history-foot' });
  const body = el('div', { class: 'history' },
    el('div', { class: 'input-icon' }, icon('search', 'sm'), search), note, list, more, status);

  const close = openDrawer({ title: 'Geçmiş', className: 'history-drawer',
    subtitle: 'Konuşmalarınız yalnız size görünür ve kurulumun saklama süresi dolunca kendiliğinden silinir.',
    body, actions: [foot] });
  openNow = close;
  const observer = new MutationObserver(() => {
    if (!body.isConnected) {
      openNow = null;
      observer.disconnect();
    }
  });
  observer.observe(document.body, { childList: true });
  search.focus();

  const onAsk = () => location.hash === '#/ask';

  function footDefault() {
    fill(foot,
      el('button', { class: 'button ghost danger-text sm', type: 'button', disabled: !state.items.length,
        onclick: confirmAll }, icon('trash', 'sm'), 'Tümünü sil'),
      el('span', { class: 'spacer' }),
      el('button', { class: 'button sm', type: 'button', onclick: () => {
        startNewConversation();
        close();
        navigate('ask', { focus: true });
      } }, icon('plus', 'sm'), 'Yeni konuşma'));
  }

  // "Tümünü sil": a second step in the drawer itself; the button works only once the word is typed.
  function confirmAll() {
    const typed = el('input', { class: 'input', type: 'text', autocomplete: 'off', spellcheck: 'false', 'aria-describedby': 'delete-all-text',
      'aria-label': `Onaylamak için ${DELETE_ALL_WORD} yazın` });
    const go = el('button', { class: 'button danger sm', type: 'button', 'aria-disabled': 'true', onclick: () => {
      if (go.getAttribute('aria-disabled') !== 'true') removeAll();
    } }, 'Tümünü kalıcı olarak sil');
    const back = () => {
      footDefault();
      foot.querySelector('.danger-text')?.focus();
    };
    typed.addEventListener('input', () => go.setAttribute('aria-disabled', String(!deleteAllConfirmed(typed.value))));
    typed.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        back();
      } else if (event.key === 'Enter' && deleteAllConfirmed(typed.value)) removeAll();
    });
    fill(foot, el('div', { class: 'confirm-all', role: 'group', 'aria-labelledby': 'delete-all-text' },
      el('p', { id: 'delete-all-text' }, icon('alert', 'sm'),
        `Bütün konuşmalarınız, cevapları ve puanlarınız kalıcı olarak silinir. Onaylamak için ${DELETE_ALL_WORD} yazın.`),
      typed,
      el('div', { class: 'confirm-actions' },
        el('button', { class: 'button secondary sm', type: 'button', onclick: back }, 'Vazgeç'), go)));
    typed.focus();
  }

  async function removeAll() {
    const done = await guarded(() => api.deleteAllConversations());
    if (done === undefined) return;
    toast('Bütün konuşmalarınız silindi.', 'ok');
    state.items = [];
    state.page = { number: 0, size: HISTORY_PAGE_SIZE, totalElements: 0, totalPages: 0 };
    if (thread.conversationId) forgetCurrent();
    footDefault();
    draw();
    search.focus();
  }

  /** The open conversation was deleted: the ask screen starts a new one. */
  function forgetCurrent() {
    startNewConversation();
    if (onAsk()) navigate('ask');
  }

  async function load(page) {
    state.loading = true;
    state.failed = false;
    if (page === 0) draw();
    more.setAttribute('aria-busy', 'true');
    let result;
    try {
      result = await ctx.withAuth(() => api.listConversations(page));
    } catch (error) {
      result = null;
      if (!(error instanceof ApiError && error.status === 401)) state.failed = true;
    }
    more.removeAttribute('aria-busy');
    state.loading = false;
    if (result) {
      state.items = page === 0 ? result.data : [...state.items, ...result.data];
      state.page = result.page;
      status.textContent = `${state.items.length} konuşma yüklendi.`;
    }
    footDefault();
    draw();
  }

  async function open(item) {
    let conversation;
    try {
      conversation = await ctx.withAuth(() => api.getConversation(item.id));
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) return;
      toast(error instanceof ApiError ? error.message : 'Bağlantı kurulamadı.');
      if (error instanceof ApiError && error.code === 11020) {
        // Gone meanwhile (another tab, retention): drop it from the list.
        state.items = state.items.filter((c) => c.id !== item.id);
        draw();
      }
      return;
    }
    restoreConversation(conversation.id, conversation.title, restoreEntries(conversation));
    close();
    navigate('ask', { restored: true });
  }

  async function remove(item) {
    const ok = await confirmDialog({ title: `"${plainName(item.title)}" silinsin mi?`, confirm: 'Konuşmayı sil',
      message: 'Konuşmanın bütün soruları, cevapları ve puanları kalıcı olarak silinir. Bu geri alınamaz.' });
    if (!ok) return;
    const index = state.items.findIndex((c) => c.id === item.id);
    const done = await guarded(() => api.deleteConversation(item.id));
    if (done === undefined) return;
    state.items = state.items.filter((c) => c.id !== item.id);
    if (state.page) state.page = { ...state.page, totalElements: Math.max(0, state.page.totalElements - 1) };
    status.textContent = 'Konuşma silindi.';
    if (thread.conversationId === item.id) forgetCurrent();
    draw();
    footDefault();
    const rows = list.querySelectorAll('.history-open');
    (rows[Math.min(index, rows.length - 1)] ?? search).focus();
  }

  async function rename(item, value, row) {
    const problem = titleProblem(value);
    if (problem) {
      row.querySelector('.field-error').textContent = problem;
      row.querySelector('input').setAttribute('aria-invalid', 'true');
      return;
    }
    const saved = await guarded(() => api.renameConversation(item.id, value.trim()));
    if (!saved) return;
    Object.assign(item, saved);
    if (thread.conversationId === item.id) thread.title = saved.title;
    state.renaming = null;
    status.textContent = 'Başlık kaydedildi.';
    draw();
    list.querySelector(`[data-id="${CSS.escape(item.id)}"] .rename`)?.focus();
  }

  function renameRow(item) {
    const input = el('input', { class: 'input', type: 'text', value: item.title, maxlength: TITLE_MAX, 'aria-label': 'Yeni başlık',
      'aria-describedby': `title-error-${item.id}` });
    const row = el('li', { class: 'history-item editing', 'data-id': item.id });
    const cancel = () => {
      state.renaming = null;
      draw();
      list.querySelector(`[data-id="${CSS.escape(item.id)}"] .rename`)?.focus();
    };
    input.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') {
        event.stopPropagation(); // Esc leaves the edit, not the drawer
        cancel();
      }
    });
    row.append(el('form', { class: 'rename-form', onsubmit: (event) => {
      event.preventDefault();
      rename(item, input.value, row);
    } }, input,
    el('span', { class: 'field-error', id: `title-error-${item.id}`, role: 'alert' }),
    el('div', { class: 'confirm-actions' },
      el('button', { class: 'button secondary sm', type: 'button', onclick: cancel }, 'Vazgeç'),
      el('button', { class: 'button sm', type: 'submit' }, 'Kaydet'))));
    queueMicrotask(() => {
      input.focus();
      input.select();
    });
    return row;
  }

  function row(item) {
    if (state.renaming === item.id) return renameRow(item);
    const title = plainName(item.title);
    const current = thread.conversationId === item.id;
    return el('li', { class: `history-item${current ? ' current' : ''}`, 'data-id': item.id },
      el('button', { class: 'history-open', type: 'button', 'aria-current': current ? 'true' : null, onclick: () => open(item) },
        el('span', { class: 'title' }, title),
        el('span', { class: 'sub', title: formatDate(item.updatedAt) },
          `${relativeTime(item.updatedAt)} · ${turnCountLabel(item.turnCount)}${current ? ' · açık' : ''}`)),
      el('div', { class: 'history-actions' },
        el('button', { class: 'icon-button rename', type: 'button', 'aria-label': `Yeniden adlandır: ${title}`, title: 'Yeniden adlandır',
          onclick: () => {
            state.renaming = item.id;
            draw();
          } }, icon('edit', 'sm')),
        el('button', { class: 'icon-button', type: 'button', 'aria-label': `Sil: ${title}`, title: 'Sil', onclick: () => remove(item) },
          icon('trash', 'sm'))));
  }

  function draw() {
    more.hidden = state.loading || !hasMore(state.page);
    if (state.loading && !state.items.length) {
      fill(list, Array.from({ length: 4 }, () => el('li', { class: 'history-item' }, el('div', { class: 'skeleton line' }))));
      note.textContent = '';
      return;
    }
    if (state.failed && !state.items.length) {
      fill(list, el('li', {}, el('div', { class: 'notice bad' }, icon('alert', 'sm'), el('div', { class: 'grow' },
        el('strong', {}, 'Geçmiş yüklenemedi.'),
        el('div', { class: 'actions' }, el('button', { class: 'button secondary sm', type: 'button', onclick: () => load(0) },
          icon('refresh', 'sm'), 'Tekrar dene'))))));
      return;
    }
    if (!state.items.length) {
      note.textContent = '';
      fill(list, el('li', {}, emptyState('history', 'Henüz konuşma yok', 'Sorduğunuz sorular burada konuşma olarak saklanır; açıp kaldığınız yerden devam edebilirsiniz.')));
      return;
    }
    const shown = filterConversations(state.items, state.query);
    const total = state.page?.totalElements ?? state.items.length;
    note.textContent = state.query.trim()
      ? `Yüklenen ${state.items.length} konuşmada ${shown.length} sonuç.${hasMore(state.page) ? ' Daha eskiler için aşağıdan yükleyin.' : ''}`
      : `${total} konuşma`;
    fill(list, shown.length ? shown.map(row) : el('li', { class: 'muted history-none' }, 'Bu başlıkta konuşma yok.'));
  }

  footDefault();
  load(0);
}
