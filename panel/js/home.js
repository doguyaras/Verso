// "Ana sayfa" (ADR-0058, concept A "Çalışma alanı"): the composer, quick starts, skill cards or knowledge actions,
// "Ekibinizin son çıktıları", "Bilgide yeni" and, for admins, "Bu hafta şirketinizde". Pure view models over the APIs
// the caller may read; the server filters every list by the caller's grants (ADR-0028).
import { budgetState, money } from './dashboard.js';

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
export function skillCards(skills, runs, limit = 4) {
  const list = (Array.isArray(skills) ? skills : []).filter((s) => typeof s?.id === 'string' && typeof s?.name === 'string');
  const used = new Map();
  for (const run of Array.isArray(runs) ? runs : []) {
    if (typeof run?.skillId === 'string') used.set(run.skillId, (used.get(run.skillId) ?? 0) + 1);
  }
  return list.map((s, index) => ({ s, index, uses: used.get(s.id) ?? 0 }))
    .sort((a, b) => b.uses - a.uses || a.index - b.index).slice(0, limit)
    .map(({ s, uses }) => ({ id: s.id, name: s.name, description: typeof s.description === 'string' ? s.description : '',
      slash: typeof s.slash === 'string' ? s.slash : null, uses }));
}

/** The quick-start chips: the installation's suggested questions (config.js), at most six. */
export function quickStarts(suggestions) {
  return (Array.isArray(suggestions) ? suggestions : []).filter((s) => typeof s === 'string' && s.trim()).slice(0, 6);
}

/**
 * The cards under the composer when there is no skill gallery yet: what this user can do with the knowledge layer,
 * only the ones the user may open.
 */
export function knowledgeActions(canOpen) {
  return [
    { id: 'documents', title: 'Belge ekleyin', text: 'PDF, Word ya da metin; yalnız bu sunucuda işlenir.', icon: 'upload',
      params: { upload: true } },
    { id: 'ask', title: 'Kaynaklı soru sorun', text: 'Her cevap belgedeki yerine bağlanır.', icon: 'chat', params: { focus: true } },
    { id: 'knowledge', title: 'Onaylı bilgiler', text: 'Uzmanların doğruladığı cevaplar önce gelir.', icon: 'checkCircle' },
    { id: 'skills', title: 'Prosedürler', text: 'Sık sorulan işleri adım adım prosedüre çevirin.', icon: 'flag' },
  ].filter((a) => canOpen(a.id)).slice(0, 4);
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
export function newKnowledge(documents, limit = 4) {
  return (Array.isArray(documents) ? documents : []).filter((d) => d && day(d.createdAt))
    .sort((a, b) => day(b.createdAt) - day(a.createdAt)).slice(0, limit)
    .map((d) => ({ id: d.id, fileName: d.fileName, format: d.format, collection: d.collectionName ?? null, at: d.createdAt,
      status: d.status }));
}

/**
 * "Bu hafta şirketinizde", for admins: at most five figures, each with the action that resolves it (concept A's risk:
 * the strip must not become a dashboard). A figure whose source failed is left out rather than shown as 0.
 */
export function weekFigures({ report, summary, metrics, usage } = {}) {
  const figures = [];
  if (Array.isArray(report?.clusters)) {
    figures.push({ value: report.clusters.length, label: 'cevapsız soru kümesi', action: 'Bilgi sağlığı', screen: 'health' });
  }
  if (Array.isArray(report?.staleDocuments)) {
    figures.push({ value: report.staleDocuments.length, label: 'eski ama alıntılanan belge', action: 'Sahiplere sor', screen: 'health' });
  }
  if (Array.isArray(report?.contradictions)) {
    figures.push({ value: report.contradictions.length, label: 'belgeler arası çelişki', action: 'Karara bağla', screen: 'health' });
  }
  if (Number.isInteger(summary?.pendingApprovals)) {
    figures.push({ value: summary.pendingApprovals, label: 'onay bekliyor', action: 'Onay kutusu', screen: 'activity' });
  }
  const questions = metrics?.totals?.questions;
  if (Number.isFinite(questions)) {
    const answered = metrics.totals.outcomeRates?.answered;
    figures.push({ value: questions, label: Number.isFinite(answered) ? `soru, %${Math.round(answered * 100)}'i kaynaklı` : 'soru',
      action: 'Komuta panosu', screen: 'dashboard' });
  }
  // ADR-0056: the model gateway's budget of the month takes the usage figure's place when one is set.
  const budget = budgetState(usage);
  if (budget) {
    const figure = { value: `${money(budget.spent)} / ${money(budget.limit)}`, tone: budget.state,
      label: budget.state === 'HARD' ? 'bütçe doldu, model çağrıları durdu' : budget.state === 'SOFT' ? 'bütçe uyarı sınırında' : 'model bütçesi, bu ay',
      action: 'Sağlayıcılar', screen: 'providers' };
    if (figures.length && figures.at(-1).screen === 'dashboard') figures[figures.length - 1] = figure;
    else figures.push(figure);
  }
  return figures.slice(0, 5);
}

/** The setup progress of the menu chip and the admin checklist: done steps of setup.js's list. */
export function setupProgress(steps) {
  const list = Array.isArray(steps) ? steps : [];
  const done = list.filter((s) => s?.done === true).length;
  return { done, total: list.length, complete: list.length > 0 && done === list.length, label: `${done}/${list.length} adım` };
}
