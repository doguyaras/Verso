// State shared between screens for this tab only: the conversation (memory) and small preferences (sessionStorage).
// Nothing personal leaves the tab, and no token is ever stored (ADR-0015): questions and answers stay in memory, so a
// reload starts a new conversation; the preferences survive a reload of the same tab only.

/** This tab's questions and answers: { question, result | error | cancelled, ms, at }. */
export const conversation = [];

const KEYS = Object.freeze({
  theme: 'verso.panel.theme', // 'light' | 'dark' | 'system'; absent = light, the product's look (ADR-0018)
  rail: 'verso.panel.rail', // '0' = the user widened the menu; absent = the icon rail (ADR-0018)
  onboarding: 'verso.panel.onboarding', // JSON { asked, sawMode, dismissed }
  lang: 'verso.panel.lang', // 'en' | absent = Turkish; the intro screen and the IdP's sign-in page follow it
});

function read(key) {
  try {
    return sessionStorage.getItem(key);
  } catch {
    return null; // storage blocked: preferences then last for this page only
  }
}

function write(key, value) {
  try {
    if (value === null) sessionStorage.removeItem(key);
    else sessionStorage.setItem(key, value);
  } catch {
    // the choice then lasts for this page only
  }
}

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
      return { asked: false, sawMode: false, dismissed: false, ...JSON.parse(read(KEYS.onboarding) ?? '{}') };
    } catch {
      return { asked: false, sawMode: false, dismissed: false };
    }
  },
  markOnboarding(patch) {
    write(KEYS.onboarding, JSON.stringify({ ...prefs.onboarding(), ...patch }));
  },
};
