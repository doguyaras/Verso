// "Ana sayfa" (ADR-0058, "Sadeleştirme": az göster, isteyince aç): a big composer, three suggestions, at most four
// skill cards, "Bilgide yeni" (three), for admins ONE attention line and, until setup is done, a slim progress line; a
// short welcome on the first run. "Ekibinizin son çıktıları" opens on demand. Pure view models over the APIs the caller
// may read; the server filters every list by the caller's grants (ADR-0028).
import { budgetState } from './dashboard.js';

/** How much the home shows at most: the first view stays calm whatever the data. */
export const HOME_LIMITS = Object.freeze({ chips: 3, skills: 4, news: 3 });

/** The first name to greet with: the token's given name, else the user name before any "@" or ".". */
export function firstName(identity) {
  const given = typeof identity?.given_name === 'string' ? identity.given_name.trim() : '';
  if (given) return given.split(/\s+/)[0];
  const user = typeof identity?.preferred_username === 'string' ? identity.preferred_username.trim() : '';
  const base = user.split('@')[0].split(/[._]/)[0];
  return base ? base.charAt(0).toLocaleUpperCase('tr') + base.slice(1) : '';
}

export function homeTitle(identity) {
  const name = firstName(identity);
  return name ? `Bugün ne yapalım, ${name}?` : 'Bugün ne yapalım?';
}

/** The composer's placeholder: "/" for skills only when the skills feature is deployed (nav.js slot), else a hint. */
export function composerHint(skillsAvailable) {
  return skillsAvailable
    ? 'Bir soru sorun ya da / ile bir beceri başlatın…'
    : 'Bir soru sorun; cevap kaynaklarınızla birlikte gelir…';
}

/**
 * What Enter sends when the slash picker (ask.js, ADR-0057) did not take it: always a question, exactly like the
 * question box of "Asistan" (after Escape, or when no skill matches, a "/" text is asked as it is).
 */
export function composerIntent(text) {
  const value = String(text ?? '').trim();
  return value ? { kind: 'question', question: value.slice(0, 1000) } : null;
}

/**
 * The skill cards of the home screen ("Beceriler", ADR-0057; not "Prosedürler"): the caller's most used skills first,
 * counted from the caller's own run history (GET /v1/skill-runs), then the library's order; at most `limit`. Each card
 * opens the skill's guided form. Nothing here is about another person: the run list is the caller's own.
 */
export function skillCards(skills, runs, limit = HOME_LIMITS.skills) {
  const list = (Array.isArray(skills) ? skills : []).filter((s) => typeof s?.id === 'string' && typeof s?.name === 'string');
  const used = new Map();
  for (const run of Array.isArray(runs) ? runs : []) {
    if (typeof run?.skillId === 'string') used.set(run.skillId, (used.get(run.skillId) ?? 0) + 1);
  }
  return list.map((s, index) => ({ s, index, uses: used.get(s.id) ?? 0 }))
    .sort((a, b) => b.uses - a.uses || a.index - b.index).slice(0, Math.min(limit, HOME_LIMITS.skills))
    .map(({ s, uses }) => ({ id: s.id, name: s.name, description: typeof s.description === 'string' ? s.description : '',
      slash: typeof s.slash === 'string' ? s.slash : null, uses }));
}

/** The suggestion chips: the installation's suggested questions (config.js), at most three. */
export function quickStarts(suggestions) {
  return (Array.isArray(suggestions) ? suggestions : []).filter((s) => typeof s === 'string' && s.trim()).slice(0, HOME_LIMITS.chips);
}

const day = (iso) => {
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? null : at;
};

/** "Ekibinizin son çıktıları" from GET /v1/home: kind label, title, date; at most `limit`, newest first. */
export function outputsModel(feed, limit = 5, runs = []) {
  const rows = Array.isArray(feed?.outputs) ? feed.outputs : [];
  const shared = rows.filter((o) => typeof o?.title === 'string' && day(o.at))
    .map((o) => ({ kind: o.kind === 'PROCEDURE' ? 'Prosedür' : 'Onaylı bilgi', state: o.kind === 'PROCEDURE' ? 'Onaylı prosedür' : 'Doğrulandı',
      title: o.title, description: typeof o.description === 'string' ? o.description : null, at: o.at,
      screen: o.kind === 'PROCEDURE' ? 'skills' : 'knowledge', params: null }));
  // The caller's own approved skill outputs (ADR-0057): the run list is the caller's, so nothing of another person.
  const own = (Array.isArray(runs) ? runs : []).filter((r) => r?.status === 'APPROVED' && typeof r.skillName === 'string'
    && day(r.finishedAt ?? r.createdAt))
    .map((r) => ({ kind: 'Beceri çıktısı', state: 'Onaylandı', title: r.skillName, description: null,
      at: r.finishedAt ?? r.createdAt, screen: 'beceriler', params: { runId: r.id } }));
  return [...shared, ...own].sort((a, b) => day(b.at) - day(a.at)).slice(0, limit);
}

/** "Bilgide yeni": the caller's newest documents (the list is already filtered by the caller's grants). */
export function newKnowledge(documents, limit = HOME_LIMITS.news) {
  return (Array.isArray(documents) ? documents : []).filter((d) => d && day(d.createdAt))
    .sort((a, b) => day(b.createdAt) - day(a.createdAt)).slice(0, Math.min(limit, HOME_LIMITS.news))
    .map((d) => ({ id: d.id, fileName: d.fileName, format: d.format, collection: d.collectionName ?? null, at: d.createdAt,
      status: d.status }));
}

/**
 * The admin's one line (replaces the former five-figure strip; the figures live in "Komuta panosu"): "Her şey yolunda"
 * or "N konu dikkat istiyor →". Sources: the activity summary's attention count, the knowledge-health findings and the
 * model budget. The link goes to the one source that has something, else to "Etkinlik merkezi". Null when every source
 * failed: nothing is claimed that was not read.
 */
export function attentionLine({ summary, report, usage } = {}) {
  const parts = [];
  if (Number.isInteger(summary?.attention)) parts.push({ n: Math.max(0, summary.attention), screen: 'activity' });
  if (report && Array.isArray(report.clusters)) {
    const n = [report.clusters, report.staleDocuments, report.contradictions].reduce((sum, list) => sum + (Array.isArray(list) ? list.length : 0), 0);
    parts.push({ n, screen: 'health' });
  }
  const budget = budgetState(usage);
  if (budget) parts.push({ n: budget.state === 'HARD' || budget.state === 'SOFT' ? 1 : 0, screen: 'providers' });
  if (!parts.length) return null;
  const total = parts.reduce((sum, p) => sum + p.n, 0);
  if (total === 0) return { state: 'ok', count: 0, text: 'Her şey yolunda', screen: 'activity' };
  const busy = parts.filter((p) => p.n > 0);
  return { state: 'attention', count: total, text: `${total} konu dikkat istiyor`, screen: busy.length === 1 ? busy[0].screen : 'activity' };
}

/**
 * The first-run welcome: when the user has never asked a question, can read no document and has not closed it in this
 * browser session (store.js prefs, sessionStorage). Three steps; "Belge ekleyin" for admins only, "Bir beceri deneyin"
 * only when the skills screen is deployed. Unknown inputs (a failed read: null) never show it.
 */
export function firstRun({ documents, conversations, asked = false, dismissed = false, admin = false, skills = null } = {}) {
  if (dismissed || asked || !Array.isArray(documents) || !Array.isArray(conversations)) return null;
  if (documents.length > 0 || conversations.length > 0) return null;
  const steps = [{ id: 'ask', title: 'Bir soru sorun', text: 'Cevap kaynağıyla birlikte gelir.', screen: 'ask', params: { focus: true } }];
  if (skills) steps.push({ id: 'skill', title: 'Bir beceri deneyin', text: 'Hazır formlarla özet, rapor, kontrol listesi.', screen: skills, params: null });
  if (admin) steps.push({ id: 'upload', title: 'Belge ekleyin', text: 'PDF, Word ya da metin; yalnız bu sunucuda işlenir.', screen: 'documents', params: { upload: true } });
  return { title: 'Hoş geldiniz', text: 'Üç adımda başlayın; gerisini ihtiyaç oldukça açarsınız.', steps };
}

/** The setup progress of the home's slim line and the hub's hint: done steps of setup.js's list. */
export function setupProgress(steps) {
  const list = Array.isArray(steps) ? steps : [];
  const done = list.filter((s) => s?.done === true).length;
  return { done, total: list.length, complete: list.length > 0 && done === list.length, label: `${done}/${list.length} adım` };
}
