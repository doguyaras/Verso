// State shared between screens for this tab only: the open conversation (memory) and small preferences
// (sessionStorage). No token is ever stored (ADR-0015). Questions and answers are kept by the server in the user's own
// history (ADR-0023); in the browser they stay in memory only, never in sessionStorage or localStorage, so a reload
// starts a new conversation and an earlier one is opened again from "Geçmiş" (ADR-0025). The preferences survive a
// reload of the same tab only.

/**
 * The open conversation's questions and answers: { question, result | error | cancelled, ms, at, feedback, restored,
 * notice }. A result carries conversationId and turnId when the server stored the turn.
 */
export const conversation = [];

/**
 * Which server conversation the next question continues (null: a new one), its title when it was opened from the
 * history, and a generation that changes whenever another conversation is put in place: an answer that arrives for
 * the previous one is then dropped instead of being appended to the wrong thread.
 */
export const thread = { conversationId: null, title: null, generation: 0 };

/**
 * The question still being answered, kept outside the screen: a visit that leaves "Asistan" and comes back while the
 * answer is written still shows the question, can stop it, and cannot send a second one meanwhile.
 * { question, controller, started } or null; cleared when the answer (or its failure) is in.
 */
export const pending = { current: null };

export function setPending(value) {
  pending.current = value;
}

/** The name of the document event the ask screen redraws on: an answer arrived for a visit that has been replaced. */
export const CONVERSATION_EVENT = 'verso:conversation';

/** An answer still on its way belongs to the conversation being left: it is dropped with the conversation. */
function dropPending() {
  pending.current?.controller?.abort();
  pending.current = null;
}

/** "Yeni konuşma": the next question starts a new conversation on the server. */
export function startNewConversation() {
  dropPending();
  conversation.length = 0;
  thread.conversationId = null;
  thread.title = null;
  thread.generation += 1;
}

/** Puts a conversation opened from the history in place; the next question continues it. */
export function restoreConversation(id, title, entries) {
  dropPending();
  conversation.splice(0, conversation.length, ...entries);
  thread.conversationId = id;
  thread.title = title;
  thread.generation += 1;
}

const KEYS = Object.freeze({
  theme: 'verso.panel.theme', // 'light' | 'dark' | 'system'; absent = light, the product's look (ADR-0018)
  rail: 'verso.panel.rail', // '0' = the user widened the menu; absent = the icon rail (ADR-0018)
  onboarding: 'verso.panel.onboarding', // JSON { asked, sawMode, dismissed, aiNoticeSeen, welcomeDismissed }
  lang: 'verso.panel.lang', // 'en' | absent = Turkish; the intro screen and the IdP's sign-in page follow it
});

/** sessionStorage read that never throws (a browser with site data blocked throws on access): null instead. */
export function readStorage(key) {
  try {
    return sessionStorage.getItem(key);
  } catch {
    return null; // storage blocked: preferences then last for this page only
  }
}

/** sessionStorage write that never throws; null removes the key. */
export function writeStorage(key, value) {
  try {
    if (value === null) sessionStorage.removeItem(key);
    else sessionStorage.setItem(key, value);
  } catch {
    // the choice then lasts for this page only
  }
}

const read = readStorage;
const write = writeStorage;

/**
 * Whether the dark theme applies. The ivory light theme is the default whatever the system says; the dark theme comes
 * only when the user picks it, or picks "follow the system" on a dark system.
 */
export function isDark(chosen, systemDark) {
  if (chosen === 'dark') return true;
  if (chosen === 'system') return Boolean(systemDark);
  return false;
}

export const prefs = {
  theme: () => read(KEYS.theme),
  setTheme: (value) => write(KEYS.theme, ['light', 'dark', 'system'].includes(value) ? value : null),
  lang: () => (read(KEYS.lang) === 'en' ? 'en' : 'tr'),
  setLang: (value) => write(KEYS.lang, value === 'en' ? 'en' : null),
  rail: () => read(KEYS.rail) !== '0',
  setRail: (on) => write(KEYS.rail, on ? null : '0'),
  onboarding() {
    try {
      return { asked: false, sawMode: false, dismissed: false, aiNoticeSeen: false, welcomeDismissed: false, ...JSON.parse(read(KEYS.onboarding) ?? '{}') };
    } catch {
      return { asked: false, sawMode: false, dismissed: false, aiNoticeSeen: false, welcomeDismissed: false };
    }
  },
  markOnboarding(patch) {
    write(KEYS.onboarding, JSON.stringify({ ...prefs.onboarding(), ...patch }));
  },
};
