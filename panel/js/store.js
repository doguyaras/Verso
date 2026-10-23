// State shared between screens for this tab only: the conversation (memory) and small preferences (sessionStorage).
// Nothing personal leaves the tab, and no token is ever stored (ADR-0015): questions and answers stay in memory, so a
// reload starts a new conversation; the preferences survive a reload of the same tab only.

/** This tab's questions and answers: { question, result | error | cancelled, ms, at }. */
export const conversation = [];

const KEYS = Object.freeze({
  theme: 'verso.panel.theme', // 'light' | 'dark' | absent = follow the system
  rail: 'verso.panel.rail', // '1' = the sidebar is collapsed to icons
  onboarding: 'verso.panel.onboarding', // JSON { asked, sawMode, dismissed }
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

export const prefs = {
  theme: () => read(KEYS.theme),
  setTheme: (value) => write(KEYS.theme, value === 'light' || value === 'dark' ? value : null),
  rail: () => read(KEYS.rail) === '1',
  setRail: (on) => write(KEYS.rail, on ? '1' : null),
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
