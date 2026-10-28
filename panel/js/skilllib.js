// "Beceriler" (ADR-0057) without the DOM: the task-skill library's gallery filter, the guided form generated from a
// skill definition, the slash commands of the ask box, the run view model (progress stages, structured output with
// evidence, "değerlendirilemez" rows, checks), the edit model, the export file name and the rating. Not skills.js:
// that file is the approved procedures of ADR-0053 ("Prosedürler"), a different thing. Inputs and outputs are the
// user's own data: nothing here stores or logs them (no browser storage, no console).

/** Gallery groups; the keys are the server's SkillCategory values. */
export const CATEGORIES = Object.freeze({
  PRODUCTIVITY: 'Verimlilik ve yazışma',
  SALES: 'Teklif ve ihale',
  LEGAL_COMPLIANCE: 'Hukuk ve uyum',
  PROJECT: 'Proje yönetimi',
  QUALITY_CUSTOMER: 'Kalite ve müşteri',
  PROCESS: 'Süreç ve prosedür',
});

/** The only allowed answer of a row nothing in the sources supports (SkillOutput.NOT_ASSESSABLE). */
export const NOT_ASSESSABLE = 'değerlendirilemez';

export const STATUSES = Object.freeze({
  RUNNING: { label: 'Çalışıyor', tone: 'wait' },
  DRAFT: { label: 'Taslak', tone: 'info' },
  APPROVED: { label: 'Onaylandı', tone: 'ok' },
  FAILED: { label: 'Başarısız', tone: 'bad' },
});

/** The failure words of a run (SkillRunResponse.failureCode), in Turkish. */
export const FAILURE_WORDS = Object.freeze({
  MODEL_UNAVAILABLE: 'Model şu an yanıt vermiyor; birazdan yeniden deneyin.',
  MODEL_BUSY: 'Model meşgul; birkaç dakika sonra yeniden deneyin.',
  OUTPUT_INVALID: 'Modelin çıktısı beklenen biçime uymadı (düzeltme denemesinden sonra da).',
  RETRIEVAL_FAILED: 'Belgelerinizde arama yapılamadı.',
  INTERRUPTED: 'Çalışma yarıda kaldı (sunucu yeniden başladı ya da süre doldu).',
});

/** Progress stages in order (SkillRunStage). */
export const STAGES = Object.freeze(['QUEUED', 'RETRIEVING', 'GENERATING', 'REPAIRING', 'CHECKING', 'FINISHED']);

/** Ratings a user can give (SkillRatingRequest: 1-5). */
export const RATINGS = Object.freeze([1, 2, 3, 4, 5]);

const LOCALE = 'tr';
const fold = (text) => String(text ?? '').toLocaleLowerCase(LOCALE);

export function categoryLabel(category) {
  return CATEGORIES[category] ?? 'Diğer';
}

export function statusLabel(status) {
  return STATUSES[status]?.label ?? String(status ?? '');
}

export function statusTone(status) {
  return STATUSES[status]?.tone ?? 'neutral';
}

export function failureText(code) {
  if (!code) return '';
  return FAILURE_WORDS[code] ?? 'Çalışma tamamlanamadı.';
}

// ---------- gallery ----------

/** The cards of one category (or all with '') whose name, description or slash command contains the query. */
export function filterSkills(skills, { category = '', query = '' } = {}) {
  const wanted = fold(query).trim().replace(/^\//, '');
  return (skills ?? []).filter((skill) => {
    if (category && skill.category !== category) return false;
    if (!wanted) return true;
    return [skill.name, skill.description, skill.slash].some((text) => fold(text).includes(wanted));
  });
}

/** The categories present in a list, in the CATEGORIES order (the chips of the gallery). */
export function categoriesOf(skills) {
  const present = new Set((skills ?? []).map((skill) => skill.category));
  return Object.keys(CATEGORIES).filter((key) => present.has(key));
}

// ---------- the guided form ----------

/**
 * The form of a definition: one field per input, in order. picker says which list feeds a DOCUMENTS or COLLECTIONS
 * field; multiline marks LONG_TEXT.
 */
export function formModel(definition) {
  const fields = (definition?.inputs ?? []).map((input) => ({
    key: input.key,
    label: input.label,
    type: input.type,
    required: input.required === true,
    maxLength: Number.isInteger(input.maxLength) ? input.maxLength : null,
    maxItems: Number.isInteger(input.maxItems) ? input.maxItems : null,
    options: Array.isArray(input.options) ? input.options.map((o) => ({ value: o.value, label: o.label })) : [],
    help: input.help ?? '',
    placeholder: input.placeholder ?? '',
    multiline: input.type === 'LONG_TEXT',
    picker: input.type === 'DOCUMENTS' ? 'documents' : input.type === 'COLLECTIONS' ? 'collections' : null,
  }));
  return { skillId: definition?.id ?? '', fields };
}

const isList = (field) => field.type === 'DOCUMENTS' || field.type === 'COLLECTIONS';

/** The first text field a slash command fills: a required LONG_TEXT or TEXT, else any text field. */
export function prefillKey(model) {
  const text = model.fields.filter((f) => f.type === 'LONG_TEXT' || f.type === 'TEXT');
  return (text.find((f) => f.required) ?? text[0])?.key ?? null;
}

/** Empty values for every field (lists for pickers); prefill goes into prefillKey's field, cut at its limit. */
export function emptyValues(model, prefill = '') {
  const values = {};
  for (const field of model.fields) values[field.key] = isList(field) ? [] : '';
  const key = prefillKey(model);
  if (key && prefill) {
    const field = model.fields.find((f) => f.key === key);
    values[key] = field.maxLength ? String(prefill).slice(0, field.maxLength) : String(prefill);
  }
  return values;
}

/** The counter under a field: "12 / 500" for text, "1 / 5" for a picker, '' otherwise. */
export function counterText(field, value) {
  if (isList(field)) return field.maxItems ? `${(value ?? []).length} / ${field.maxItems}` : '';
  if (field.maxLength) return `${String(value ?? '').length} / ${field.maxLength}`;
  return '';
}

/** Problems of the values, field key to a Turkish message; empty when the form may be sent. */
export function validateForm(model, values) {
  const problems = {};
  for (const field of model.fields) {
    const value = values?.[field.key];
    if (isList(field)) {
      const list = Array.isArray(value) ? value : [];
      if (field.required && list.length === 0) problems[field.key] = 'En az bir seçim yapın.';
      else if (field.maxItems && list.length > field.maxItems) problems[field.key] = `En çok ${field.maxItems} seçim yapılabilir.`;
      continue;
    }
    const text = String(value ?? '').trim();
    if (field.required && !text) problems[field.key] = 'Bu alan zorunlu.';
    else if (field.maxLength && String(value ?? '').length > field.maxLength) problems[field.key] = `En çok ${field.maxLength} karakter.`;
    else if (field.type === 'SELECT' && text && !field.options.some((o) => o.value === text)) problems[field.key] = 'Listeden bir seçenek seçin.';
  }
  return problems;
}

/** The POST body ({ inputs }): trimmed text, empty optional fields left out, id lists as strings. */
export function runBody(model, values) {
  const inputs = {};
  for (const field of model.fields) {
    const value = values?.[field.key];
    if (isList(field)) {
      const list = (Array.isArray(value) ? value : []).map(String);
      if (list.length) inputs[field.key] = list;
      continue;
    }
    const text = field.multiline ? String(value ?? '').trim() : String(value ?? '').trim();
    if (text) inputs[field.key] = text;
  }
  return { inputs };
}

// ---------- slash commands in the ask box ----------

/** "/topl" or "/toplanti notlar…" → { command, rest }; anything not starting with "/" → null. */
export function slashQuery(text) {
  const match = /^\/([^\s/]*)(?:\s+([\s\S]*))?$/.exec(String(text ?? ''));
  if (!match) return null;
  return { command: fold(match[1]), rest: (match[2] ?? '').trim() };
}

/** The skills whose slash command starts with the typed command (then those containing it), at most limit. */
export function slashMatches(skills, text, limit = 8) {
  const query = slashQuery(text);
  if (!query) return [];
  const list = skills ?? [];
  const starts = list.filter((s) => fold(s.slash).startsWith(query.command));
  const contains = list.filter((s) => !starts.includes(s) && query.command && (fold(s.slash).includes(query.command)
    || fold(s.name).includes(query.command)));
  return [...starts, ...contains].slice(0, limit);
}

/** What choosing a skill from the picker opens: its form, with the text after the command prefilled. */
export function slashTarget(skill, text) {
  const query = slashQuery(text);
  return { skillId: skill.id, prefill: query?.rest ?? '' };
}

// ---------- the run view ----------

/** The screen text of a progress event or of a run's stage fields. */
export function stageLabel(event) {
  switch (event?.stage) {
    case 'QUEUED': return 'Sırada';
    case 'RETRIEVING': return 'Belgeler aranıyor';
    case 'GENERATING': {
      const title = event.stepTitle ? `: ${event.stepTitle}` : '';
      return event.step && event.stepCount ? `Adım ${event.step}/${event.stepCount}${title}` : `Yazılıyor${title}`;
    }
    case 'REPAIRING': return 'Çıktı düzeltiliyor';
    case 'CHECKING': return 'Kontroller';
    case 'FINISHED': return 'Tamamlandı';
    default: return 'Hazırlanıyor';
  }
}

/** The stage list with each item's state (done, current, todo) for the progress strip. */
export function stageSteps(stage) {
  const at = STAGES.indexOf(stage);
  return STAGES.filter((s) => s !== 'REPAIRING' || stage === 'REPAIRING').map((s) => {
    const index = STAGES.indexOf(s);
    return { stage: s, label: stageLabel({ stage: s }), state: index < at ? 'done' : index === at ? 'current' : 'todo' };
  });
}

/** Applies a "stage" or "done" stream event to a run (a new object). */
export function applyEvent(run, name, event) {
  if (!run || !event) return run;
  if (name === 'done') return { ...run, status: event.status ?? run.status, failureCode: event.failureCode ?? run.failureCode, stage: 'FINISHED' };
  if (name === 'stage') return { ...run, stage: event.stage ?? run.stage, step: event.step ?? null, stepCount: event.stepCount ?? run.stepCount,
    stepTitle: event.stepTitle ?? null };
  return run;
}

const numbers = (list) => (Array.isArray(list) ? list.filter((n) => Number.isInteger(n) && n > 0) : []);

/** The display of one numbered source: a passage of a document (page drawer) or a part of the user's input. */
export function citationView(citation) {
  if (!citation) return null;
  const passage = citation.kind === 'PASSAGE';
  const unit = citation.unit === 'SECTION' ? 'bölüm' : 's.';
  return {
    number: citation.number,
    kind: citation.kind,
    accessible: citation.accessible !== false,
    label: passage
      ? (citation.accessible === false ? `[${citation.number}] erişilemiyor` : `[${citation.number}] ${citation.fileName ?? 'belge'}${citation.page ? ` · ${unit} ${citation.page}` : ''}`)
      : `[${citation.number}] Girdiniz`,
    excerpt: citation.excerpt ?? '',
    drawer: passage && citation.accessible !== false && citation.documentId
      ? { documentId: citation.documentId, fileName: citation.fileName, page: citation.page, unit: citation.unit, excerpt: citation.excerpt ?? '' }
      : null,
  };
}

/**
 * Everything the run screen shows, in the definition's order: status, stage, failure, the disclaimer, the sections
 * (TEXT with its evidence, ROWS with labelled cells), each row's evidence as citation views, "değerlendirilemez" and
 * unverified numbers or codes flagged, the checks, and which actions are allowed. Without the definition (a skill
 * switched off since) the columns come from the output itself.
 */
export function runView(run, definition) {
  const byNumber = new Map((run?.citations ?? []).map((c) => [c.number, citationView(c)]));
  const evidence = (list) => numbers(list).map((n) => byNumber.get(n) ?? { number: n, label: `[${n}]`, accessible: false, drawer: null, excerpt: '' });
  const outputSections = new Map((run?.output?.sections ?? []).map((s) => [s.key, s]));
  const schema = definition?.output?.sections ?? (run?.output?.sections ?? []).map((s) => ({ key: s.key, title: s.key,
    kind: Array.isArray(s.rows) ? 'ROWS' : 'TEXT',
    columns: Array.isArray(s.rows) ? [...new Set(s.rows.flatMap((r) => Object.keys(r.cells ?? {})))].map((k) => ({ key: k, label: k })) : null }));
  const sections = run?.output ? schema.map((section) => {
    const value = outputSections.get(section.key) ?? {};
    if (section.kind === 'ROWS') {
      const columns = (section.columns ?? []).map((c) => ({ key: c.key, label: c.label }));
      const rows = (value.rows ?? []).map((row, index) => ({
        index,
        cells: columns.map((c) => ({ key: c.key, label: c.label, value: row.cells?.[c.key] ?? '' })),
        notAssessable: row.notAssessable === true,
        evidence: row.notAssessable === true ? [] : evidence(row.evidence),
        unverified: Array.isArray(row.unverified) ? row.unverified : [],
      }));
      return { key: section.key, title: section.title, kind: 'ROWS', columns, rows };
    }
    return { key: section.key, title: section.title, kind: 'TEXT', text: value.text ?? '', notAssessable: value.notAssessable === true,
      evidence: value.notAssessable === true ? [] : evidence(value.evidence) };
  }) : [];
  const finished = run?.status === 'DRAFT' || run?.status === 'APPROVED';
  const checks = run?.checks ? [
    { label: 'Çıktı şemaya uyuyor', ok: true, note: run.checks.repaired ? 'bir düzeltme denemesiyle' : '' },
    { label: 'Her satırın kaynağı var ya da "değerlendirilemez"', ok: true, note: `${run.checks.rows} satır, ${run.checks.notAssessable} değerlendirilemez` },
    { label: 'Sayı ve kodlar kaynaklarda geçiyor', ok: run.checks.unverified === 0, note: run.checks.unverified ? `${run.checks.unverified} satırda doğrulanamadı` : '' },
    { label: 'Alıntılanan kaynaklar okunabiliyor', ok: run.checks.unreadableCitation === 0,
      note: run.checks.unreadableCitation ? `${run.checks.unreadableCitation} kaynak okunamadı` : '' },
    { label: 'Uzunluk sınırında', ok: run.checks.withinLength === true, note: '' },
    { label: 'Uyarı etiketi ekli', ok: run.checks.disclaimerPresent === true, note: '' },
  ] : [];
  return {
    id: run?.id,
    skillId: run?.skillId,
    title: run?.skillName ?? definition?.name ?? '',
    status: run?.status,
    statusLabel: statusLabel(run?.status),
    tone: statusTone(run?.status),
    running: run?.status === 'RUNNING',
    stage: stageLabel(run),
    steps: stageSteps(run?.status === 'RUNNING' ? run?.stage : 'FINISHED'),
    failure: run?.status === 'FAILED' ? failureText(run?.failureCode) : '',
    disclaimer: run?.disclaimer ?? definition?.disclaimer ?? '',
    edited: run?.edited === true,
    rating: Number.isInteger(run?.rating) ? run.rating : null,
    sections,
    citations: [...byNumber.values()],
    checks,
    canEdit: finished,
    canApprove: run?.status === 'DRAFT',
    canExport: finished,
    canRate: finished,
  };
}

// ---------- editing ----------

const clone = (output) => ({ sections: (output?.sections ?? []).map((s) => ({ ...s,
  evidence: Array.isArray(s.evidence) ? [...s.evidence] : s.evidence,
  rows: Array.isArray(s.rows) ? s.rows.map((r) => ({ ...r, cells: { ...(r.cells ?? {}) }, evidence: Array.isArray(r.evidence) ? [...r.evidence] : [] })) : s.rows })) });

const findSection = (output, key) => output.sections.find((s) => s.key === key);

/** A new output with one cell changed. */
export function setCell(output, sectionKey, rowIndex, columnKey, value) {
  const next = clone(output);
  const row = findSection(next, sectionKey)?.rows?.[rowIndex];
  if (row) row.cells[columnKey] = String(value ?? '');
  return next;
}

/** A new output with one TEXT section's paragraph changed. */
export function setText(output, sectionKey, value) {
  const next = clone(output);
  const section = findSection(next, sectionKey);
  if (section) section.text = String(value ?? '');
  return next;
}

/**
 * A new output with a row (or a TEXT section when rowIndex is null) marked "değerlendirilemez" (evidence cleared) or
 * given back its evidence list.
 */
export function markNotAssessable(output, sectionKey, rowIndex, flag, evidence = []) {
  const next = clone(output);
  const section = findSection(next, sectionKey);
  const target = rowIndex === null || rowIndex === undefined ? section : section?.rows?.[rowIndex];
  if (target) {
    target.notAssessable = flag === true;
    target.evidence = flag === true ? [] : numbers(evidence);
  }
  return next;
}

/** A new output without one row. */
export function removeRow(output, sectionKey, rowIndex) {
  const next = clone(output);
  const section = findSection(next, sectionKey);
  if (section?.rows) section.rows.splice(rowIndex, 1);
  return next;
}

/** The PUT body: the edited output; the server's unverified marks are its own and are not sent back. */
export function editBody(output) {
  return { output: { sections: clone(output).sections.map((s) => ({
    key: s.key,
    text: s.text ?? null,
    evidence: Array.isArray(s.evidence) ? numbers(s.evidence) : null,
    notAssessable: s.notAssessable ?? null,
    rows: Array.isArray(s.rows) ? s.rows.map((r) => ({ cells: r.cells, evidence: numbers(r.evidence), notAssessable: r.notAssessable === true, unverified: null })) : null,
  })) } };
}

// ---------- export and rating ----------

export const EXPORTS = Object.freeze({
  MARKDOWN: { extension: 'md', type: 'text/markdown;charset=utf-8', label: 'Markdown' },
  JSON: { extension: 'json', type: 'application/json;charset=utf-8', label: 'JSON' },
});

/** "premortem-2026-10-28.md": the skill id and the day, only safe characters. */
export function exportFileName(skillId, format, date = new Date()) {
  const safe = String(skillId ?? 'beceri').toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 64) || 'beceri';
  const day = Number.isNaN(date.getTime()) ? 'tarihsiz' : date.toISOString().slice(0, 10);
  return `${safe}-${day}.${(EXPORTS[format] ?? EXPORTS.MARKDOWN).extension}`;
}

/** Saves a Blob with the given saver (the screen passes health.js saveFile; tests pass a recorder). */
export function exportRun(blob, skillId, format, save, date = new Date()) {
  const name = exportFileName(skillId, format, date);
  save(blob, name);
  return name;
}

export function ratingLabel(value) {
  return Number.isInteger(value) && value >= 1 && value <= 5 ? `${value} / 5` : 'Puanlanmadı';
}

export function ratingBody(value) {
  const rating = Number(value);
  return Number.isInteger(rating) && rating >= 1 && rating <= 5 ? { rating } : null;
}

// ---------- admin ----------

/** The admin's JSON editor text → { definition } or { error } (a Turkish message). Nothing is evaluated. */
export function parseDefinition(text) {
  try {
    const value = JSON.parse(String(text ?? ''));
    if (!value || typeof value !== 'object' || Array.isArray(value)) return { error: 'Tanım bir JSON nesnesi olmalı.' };
    return { definition: value };
  } catch {
    return { error: 'JSON okunamadı; virgül, tırnak ve parantezleri kontrol edin.' };
  }
}

export const CUSTOM_STATES = Object.freeze({ DRAFT: 'Taslak', APPROVED: 'Onaylı', ARCHIVED: 'Arşivde' });

/** The admin table: built-in and customer-authored skills with their switch and state. */
export function adminRows(list) {
  return (list ?? []).map((item) => ({
    id: item.skill?.id,
    name: item.skill?.name ?? '',
    category: categoryLabel(item.skill?.category),
    origin: item.skill?.origin === 'CUSTOM' ? 'Kuruma özel' : 'Hazır',
    enabled: item.enabled === true,
    state: item.state ? (CUSTOM_STATES[item.state] ?? item.state) : '',
    customSkillId: item.customSkillId ?? null,
    version: item.customVersion ?? null,
  }));
}
