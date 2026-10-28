// "Beceriler" (ADR-0057): the task-skill library. Every user picks a ready workflow (meeting notes → decisions and
// actions, a sourced report, a tender response, a contract pre-review, a pre-mortem …) from a gallery, fills the guided
// form generated from the skill's definition, and gets a structured DRAFT whose every row cites the user's own
// documents (or the user's input), or says "değerlendirilemez". The draft can be edited, marked "Onaylandı", exported
// as Markdown or JSON and rated. verso-admin also switches skills on or off for the installation and keeps the
// customer-authored skills (a JSON editor; draft → approved). Not "Prosedürler" (ADR-0053): those are approved how-to
// procedures drawn from documents. Built with el(): text, never markup (CSP, Trusted Types); no inline styles; form
// inputs and outputs stay in this screen's memory only (personal data: no browser storage).
import { api, ApiError } from '../api.js';
import { icon } from '../icons.js';
import { ROLES } from '../roles.js';
import { el, fill, formatDate, plainName } from '../render.js';
import { closePageDrawer, openPageDrawer } from '../drawer.js';
import { StreamUnavailable } from '../stream.js';
import { confirmDialog, disclosure, emptyState, helpLine, openDrawer, toast } from '../ui.js';
import { adminRows, CATEGORIES, categoriesOf, categoryLabel, counterText, editBody, EXPORTS, emptyValues, exportRun, filterSkills,
  formModel, markNotAssessable, NOT_ASSESSABLE, parseDefinition, RATINGS, ratingBody, ratingLabel, runBody, runView, applyEvent,
  setCell, setText, statusLabel, statusTone, validateForm, CUSTOM_STATES } from '../skilllib.js';
import { saveFile } from './health.js';

/** How often a running run is read again when no event stream gets through (ADR-0057). */
export const POLL_MS = 1500;

export function renderBeceriler(ctx) {
  const { main, guarded, withAuth } = ctx;
  const admin = (ctx.roles ?? []).includes(ROLES.ADMIN);
  let skills = [];
  let category = '';
  let query = '';
  let disposeRun = () => {};

  const body = el('div', { class: 'grid' }, el('div', { class: 'skeleton line' }));
  main.replaceChildren(
    el('div', { class: 'page-head' },
      el('div', {}, el('h1', {}, 'Beceriler'),
        helpLine('Formu doldurun; sonuç kaynaklarıyla birlikte düzenlenebilir bir taslak olarak gelsin.',
          'Her satır belgelerinizden ya da girdinizden bir kaynağa dayanır; dayanağı olmayan satır "değerlendirilemez" der. '
          + 'Taslağı siz kontrol eder, onaylar ve dışa aktarırsınız. Onaylı "nasıl yapılır" adımları için Prosedürler ekranı ayrıdır.')),
      el('div', { class: 'page-actions' },
        el('button', { class: 'button secondary', type: 'button', onclick: () => showGallery() }, icon('sparkles'), 'Galeri'))),
    body);

  // ---------- gallery and history ----------
  async function showGallery() {
    disposeRun();
    const list = await guarded(() => api.listTaskSkills());
    if (!ctx.isCurrent()) return;
    skills = Array.isArray(list) ? list : [];
    const cards = el('div', { class: 'skill-lib-cards' });
    const chips = el('div', { class: 'skill-lib-chips', role: 'group', 'aria-label': 'Kategori' });
    const search = el('input', { class: 'input', type: 'search', id: 'skill-lib-search', placeholder: 'Ara: ad, açıklama ya da /komut',
      autocomplete: 'off', value: query, oninput: () => {
        query = search.value;
        drawCards();
      } });
    const drawChips = () => fill(chips, ['', ...categoriesOf(skills)].map((key) => el('button', { class: 'chip', type: 'button',
      'aria-pressed': String(category === key), onclick: () => {
        category = key;
        drawChips();
        drawCards();
      } }, key ? CATEGORIES[key] : 'Tümü')));
    const drawCards = () => {
      const shown = filterSkills(skills, { category, query });
      fill(cards, shown.length ? shown.map(card) : skills.length
        ? emptyState('search', 'Beceri bulunamadı', 'Aramayı ya da kategoriyi değiştirin.',
          el('button', { class: 'button secondary', type: 'button', onclick: () => {
            category = '';
            query = '';
            search.value = '';
            drawChips();
            drawCards();
          } }, 'Filtreyi temizle'))
        : emptyState('sparkles', 'Bu kurulumda açık beceri yok', 'Yöneticiniz becerileri açtığında burada görünür.',
          el('button', { class: 'button secondary', type: 'button', onclick: () => ctx.navigate('ask', { focus: true }) }, icon('chat'), 'Asistana sorun')));
    };
    drawChips();
    drawCards();
    const history = el('div', { class: 'card-body' }, el('div', { class: 'skeleton line' }));
    fill(body,
      el('section', { class: 'card', 'aria-labelledby': 'skill-lib-gallery' },
        el('div', { class: 'card-head' }, el('h2', { id: 'skill-lib-gallery' }, 'Galeri')),
        el('div', { class: 'card-body grid' },
          el('label', { class: 'sr-only', for: 'skill-lib-search' }, 'Becerilerde ara'), search, chips, cards)),
      // Sadeleştirme (ADR-0058): the gallery first; the run history and the admin's settings open on demand.
      disclosure('Son çalıştırmalarım', el('section', { class: 'card', 'aria-labelledby': 'skill-lib-history' },
        el('div', { class: 'card-head' }, el('h2', { id: 'skill-lib-history' }, 'Son çalıştırmalarım')), history)),
      admin ? disclosure('Becerileri yönet', adminCard()) : null);
    loadHistory(history);
  }

  function card(skill) {
    // A compact card (Sadeleştirme): the name, one line of description, the command; the category is the filter chip, and
    // only the compliance warning stays as a pill. The rest is in the card's title.
    const notes = [categoryLabel(skill.category), skill.grounded ? 'Belgelerinizde arar' : null,
      skill.origin === 'CUSTOM' ? 'Kuruma özel' : null].filter(Boolean).join(' · ');
    return el('article', { class: 'skill-lib-card compact', title: notes },
      el('h3', {}, skill.name),
      el('p', { class: 'muted skill-lib-line' }, skill.description),
      el('div', { class: 'skill-lib-meta' },
        el('code', {}, `/${skill.slash}`),
        skill.compliance ? el('span', { class: 'pill wait' }, icon('alert', 'sm'), 'Uzman incelemesi gerekir') : null),
      el('button', { class: 'button', type: 'button', onclick: () => showForm(skill.id) }, 'Başla', icon('arrowRight')));
  }

  async function loadHistory(slot) {
    const page = await guarded(() => api.listSkillRuns(0, 20));
    if (!ctx.isCurrent()) return;
    const items = page?.items ?? [];
    if (!items.length) {
      fill(slot, el('p', { class: 'muted' }, 'Henüz bir beceri çalıştırmadınız. Çalıştırmalar konuşma geçmişi gibi saklama süresi sonunda silinir.'));
      return;
    }
    fill(slot, el('div', { class: 'table-wrap' }, el('table', { class: 'table' },
      el('thead', {}, el('tr', {}, el('th', { scope: 'col' }, 'Beceri'), el('th', { scope: 'col' }, 'Durum'),
        el('th', { scope: 'col' }, 'Tarih'), el('th', { scope: 'col' }, 'Puan'))),
      el('tbody', {}, items.map((run) => el('tr', {},
        el('td', {}, el('button', { class: 'button ghost sm', type: 'button', onclick: () => showRun(run.id) }, run.skillName ?? run.skillId)),
        el('td', {}, el('span', { class: `pill ${statusTone(run.status)}` }, statusLabel(run.status), run.edited ? ' · düzenlendi' : '')),
        el('td', { class: 'tabular' }, formatDate(run.createdAt)),
        el('td', { class: 'tabular' }, ratingLabel(run.rating))))))));
  }

  // ---------- the guided form ----------
  async function showForm(skillId, prefill = '') {
    disposeRun();
    fill(body, el('div', { class: 'skeleton line' }));
    const definition = await guarded(() => api.getTaskSkill(skillId));
    if (!ctx.isCurrent()) return;
    if (!definition) {
      showGallery();
      return;
    }
    const model = formModel(definition);
    const values = emptyValues(model, prefill);
    const errors = new Map();
    const fields = model.fields.map((field) => fieldControl(field, values, errors));
    const status = el('p', { class: 'muted', role: 'status', 'aria-live': 'polite' });
    const submit = el('button', { class: 'button', type: 'submit' }, icon('sparkles'), 'Çalıştır');
    const form = el('form', { class: 'grid skill-lib-form', novalidate: true, onsubmit: async (event) => {
      event.preventDefault();
      const problems = validateForm(model, values);
      for (const [key, node] of errors) node.textContent = problems[key] ?? '';
      const first = Object.keys(problems)[0];
      if (first) {
        status.textContent = 'Formda düzeltilmesi gereken alanlar var.';
        main.querySelector(`#skill-field-${first}`)?.focus();
        return;
      }
      submit.setAttribute('aria-busy', 'true');
      submit.disabled = true;
      try {
        const run = await withAuth(() => api.startSkillRun(skillId, runBody(model, values)));
        if (ctx.isCurrent() && run?.id) showRun(run.id, run, definition);
      } catch (error) {
        if (error instanceof ApiError && error.status !== 401) status.textContent = error.message;
        else if (!(error instanceof ApiError)) status.textContent = 'Bağlantı kurulamadı.';
      } finally {
        submit.removeAttribute('aria-busy');
        submit.disabled = false;
      }
    } }, ...fields, el('div', { class: 'skill-lib-actions' }, submit,
      el('button', { class: 'button secondary', type: 'button', onclick: () => showGallery() }, 'Vazgeç')), status);
    fill(body, el('section', { class: 'card', 'aria-labelledby': 'skill-lib-form-title' },
      el('div', { class: 'card-head' }, el('h2', { id: 'skill-lib-form-title' }, definition.name),
        el('span', { class: 'pill neutral' }, categoryLabel(definition.category))),
      el('div', { class: 'card-body grid' },
        el('p', {}, definition.description),
        aboutSkill(definition),
        form)));
    main.querySelector(`#skill-field-${model.fields[0]?.key}`)?.focus();
  }

  /** The framework, the disclaimer and any attribution: shown before the user runs a skill. */
  function aboutSkill(definition) {
    return el('div', { class: 'notice info skill-lib-about' }, icon('info', 'sm'),
      el('div', {},
        definition.framework ? el('p', {}, 'Dayanak çerçeve: ', definition.framework.name,
          definition.framework.reference ? ` (${definition.framework.reference})` : '') : null,
        definition.disclaimer ? el('p', {}, el('strong', {}, definition.disclaimer)) : null,
        definition.grounding ? el('p', {}, 'Okuma yetkiniz olan belgelerde arar; başka kimsenin belgesine erişmez.') : null,
        definition.attribution ? el('p', { class: 'muted' }, `Yapı uyarlaması: ${definition.attribution.source} (${definition.attribution.license}).`) : null));
  }

  function fieldControl(field, values, errors) {
    const id = `skill-field-${field.key}`;
    const errorId = `${id}-error`;
    const helpId = `${id}-help`;
    const error = el('p', { class: 'field-error', id: errorId, role: 'alert' });
    errors.set(field.key, error);
    const counter = el('span', { class: 'tabular muted' }, counterText(field, values[field.key]));
    const update = (value) => {
      values[field.key] = value;
      counter.textContent = counterText(field, value);
      error.textContent = '';
    };
    const described = [field.help ? helpId : null, errorId].filter(Boolean).join(' ');
    let control;
    if (field.type === 'LONG_TEXT') {
      control = el('textarea', { class: 'textarea', id, rows: 8, maxlength: field.maxLength ?? undefined, placeholder: field.placeholder || undefined,
        required: field.required, 'aria-describedby': described, oninput: () => update(control.value) });
      control.value = values[field.key];
    } else if (field.type === 'TEXT') {
      control = el('input', { class: 'input', id, type: 'text', maxlength: field.maxLength ?? undefined, placeholder: field.placeholder || undefined,
        required: field.required, autocomplete: 'off', 'aria-describedby': described, oninput: () => update(control.value) });
      control.value = values[field.key];
    } else if (field.type === 'SELECT') {
      control = el('select', { class: 'input', id, required: field.required, 'aria-describedby': described, onchange: () => update(control.value) },
        el('option', { value: '' }, field.required ? 'Seçin…' : '(Seçilmedi)'), field.options.map((o) => el('option', { value: o.value }, o.label)));
    } else {
      control = el('div', { class: 'skill-lib-picker', id, role: 'group', tabindex: -1, 'aria-describedby': described },
        el('p', { class: 'muted' }, 'Yükleniyor…'));
      loadPicker(field, control, values, update);
    }
    return el('div', { class: 'skill-lib-field' },
      el('div', { class: 'skill-lib-label' },
        field.picker ? el('span', { id: `${id}-label` }, field.label, field.required ? ' *' : '')
          : el('label', { for: id }, field.label, field.required ? ' *' : ''), counter),
      field.help ? el('p', { class: 'muted', id: helpId }, field.help) : null,
      control, error);
  }

  async function loadPicker(field, slot, values, update) {
    const items = field.picker === 'documents'
      ? ((await guarded(() => api.listAllDocuments())) ?? []).filter((d) => d.status === 'READY').map((d) => ({ id: d.id, name: plainName(d.fileName) }))
      : (((await guarded(() => api.listCollections()))?.data) ?? []).filter((c) => c.readable !== false).map((c) => ({ id: c.id, name: plainName(c.name) }));
    if (!ctx.isCurrent()) return;
    slot.setAttribute('aria-labelledby', `${slot.id}-label`);
    if (!items.length) {
      fill(slot, el('p', { class: 'muted' }, field.picker === 'documents' ? 'Okuyabildiğiniz hazır belge yok.' : 'Okuyabildiğiniz bilgi alanı yok.'));
      return;
    }
    fill(slot, items.map((item) => {
      const box = el('input', { type: 'checkbox', value: item.id, id: `${slot.id}-${item.id}`, onchange: () => {
        const chosen = new Set(values[field.key]);
        if (box.checked) chosen.add(item.id);
        else chosen.delete(item.id);
        update([...chosen]);
      } });
      return el('label', { class: 'connector-check', for: box.id }, box, el('span', {}, item.name));
    }));
  }

  // ---------- the run ----------
  async function showRun(runId, first = null, knownDefinition = null) {
    disposeRun();
    let stopped = false;
    let timer = null;
    const controller = new AbortController();
    disposeRun = () => {
      stopped = true;
      clearTimeout(timer);
      controller.abort();
      closePageDrawer();
      disposeRun = () => {};
    };
    let run = first ?? await guarded(() => api.getSkillRun(runId));
    if (!run || stopped || !ctx.isCurrent()) return;
    const definition = knownDefinition ?? await withAuth(() => api.getTaskSkill(run.skillId)).catch(() => null);
    if (stopped || !ctx.isCurrent()) return;
    let editing = null; // the output being edited, or null
    const slot = el('section', { class: 'card', 'aria-labelledby': 'skill-run-title' });
    fill(body, slot);
    const draw = () => fill(slot, ...runParts(run, definition, editing, {
      edit: () => {
        editing = run.output;
        draw();
      },
      change: (output) => {
        editing = output;
      },
      cancel: () => {
        editing = null;
        draw();
      },
      save: async () => {
        const saved = await guarded(() => api.editSkillRun(run.id, editBody(editing)));
        if (!saved || stopped) return;
        run = saved;
        editing = null;
        toast('Taslak kaydedildi.', 'ok');
        draw();
      },
      approve: async () => {
        const approved = await guarded(() => api.approveSkillRun(run.id));
        if (!approved || stopped) return;
        run = approved;
        toast('Taslak onaylandı.', 'ok');
        draw();
      },
      exportAs: async (format) => {
        const blob = await guarded(() => api.exportSkillRun(run.id, format));
        if (blob) exportRun(blob, run.skillId, format, saveFile);
      },
      rate: async (value) => {
        const request = ratingBody(value);
        if (!request) return;
        const result = await guarded(() => api.rateSkillRun(run.id, request).then(() => true));
        if (!result || stopped) return;
        run = { ...run, rating: request.rating };
        draw();
      },
      remove: async () => {
        const ok = await confirmDialog({ title: 'Çalıştırma silinsin mi?', message: 'Girdileriniz ve çıktı kalıcı olarak silinir.', confirm: 'Sil' });
        if (!ok) return;
        const done = await guarded(() => api.deleteSkillRun(run.id).then(() => true));
        if (done) showGallery();
      },
      again: () => showForm(run.skillId),
    }));
    draw();
    if (run.status !== 'RUNNING') return;

    const reload = async () => {
      const next = await withAuth(() => api.getSkillRun(runId)).catch(() => null);
      if (stopped || !ctx.isCurrent()) return;
      if (next) run = next;
      draw();
      if (run.status === 'RUNNING') timer = setTimeout(reload, POLL_MS);
    };
    // The event stream first (stage, done); any failure to stream falls back to reading the run every POLL_MS.
    try {
      await withAuth(() => api.skillRunEvents(runId, controller.signal, (name, value) => {
        if (stopped) return;
        run = applyEvent(run, name, value);
        draw();
      }));
    } catch (error) {
      if (stopped || error?.name === 'AbortError') return;
      if (!(error instanceof StreamUnavailable) && error instanceof ApiError && error.status !== 404) toast(error.message);
    }
    if (!stopped) reload();
  }

  ctx.params?.skillId ? showForm(ctx.params.skillId, ctx.params.prefill ?? '')
    : ctx.params?.runId ? showRun(ctx.params.runId) : showGallery();
  return () => disposeRun();
}

/** The parts of the run card; actions are callbacks so the drawing stays free of API calls. */
function runParts(run, definition, editing, actions) {
  const view = runView(editing ? { ...run, output: editing } : run, definition);
  const head = el('div', { class: 'card-head' },
    el('h2', { id: 'skill-run-title' }, view.title),
    el('span', { class: `pill ${view.tone}` }, view.running ? el('span', { class: 'dot' }) : null, view.statusLabel),
    view.edited ? el('span', { class: 'pill neutral' }, 'Düzenlendi') : null);
  const disclaimer = view.disclaimer ? el('div', { class: 'notice wait skill-lib-disclaimer', role: 'note' }, icon('alert', 'sm'), view.disclaimer) : null;
  if (view.running) {
    return [head, el('div', { class: 'card-body grid' }, disclaimer,
      el('p', { role: 'status', 'aria-live': 'polite' }, view.stage),
      el('ol', { class: 'skill-lib-stages' }, view.steps.map((s) => el('li', { class: s.state, 'aria-current': s.state === 'current' ? 'step' : null }, s.label))),
      el('p', { class: 'muted' }, 'Bu sayfadan ayrılabilirsiniz; sonuç "Son çalıştırmalarım" listesinde olacak.'))];
  }
  if (run.status === 'FAILED') {
    return [head, el('div', { class: 'card-body grid' },
      el('div', { class: 'notice bad', role: 'alert' }, icon('alert', 'sm'), view.failure),
      el('div', { class: 'skill-lib-actions' }, el('button', { class: 'button', type: 'button', onclick: actions.again }, 'Yeniden dene'),
        el('button', { class: 'button ghost danger-text', type: 'button', onclick: actions.remove }, icon('trash', 'sm'), 'Sil')))];
  }
  let output = editing;
  const change = (next) => {
    output = next;
    actions.change(next);
  };
  const sections = view.sections.map((section) => sectionPart(section, editing ? { get: () => output, change } : null));
  const toolbar = editing
    ? el('div', { class: 'skill-lib-actions' },
      el('button', { class: 'button', type: 'button', onclick: actions.save }, icon('check', 'sm'), 'Kaydet'),
      el('button', { class: 'button secondary', type: 'button', onclick: actions.cancel }, 'Vazgeç'))
    : el('div', { class: 'skill-lib-actions' },
      view.canEdit ? el('button', { class: 'button secondary', type: 'button', onclick: actions.edit }, icon('edit', 'sm'), 'Düzenle') : null,
      view.canApprove ? el('button', { class: 'button', type: 'button', onclick: actions.approve }, icon('checkCircle', 'sm'), 'Onayla') : null,
      view.canExport ? Object.entries(EXPORTS).map(([format, meta]) => el('button', { class: 'button secondary', type: 'button',
        onclick: () => actions.exportAs(format) }, icon('download', 'sm'), meta.label)) : null,
      el('button', { class: 'button ghost danger-text', type: 'button', onclick: actions.remove }, icon('trash', 'sm'), 'Sil'));
  const rating = view.canRate && !editing ? el('div', { class: 'skill-lib-rating', role: 'group', 'aria-label': 'Taslağı puanlayın' },
    el('span', { class: 'muted' }, 'Puan:'), RATINGS.map((value) => el('button', { class: 'button secondary sm', type: 'button',
      'aria-pressed': String(view.rating === value), 'aria-label': `${value} puan`, onclick: () => actions.rate(value) }, String(value)))) : null;
  const checks = view.checks.length ? el('ul', { class: 'skill-lib-checks', 'aria-label': 'Otomatik kontroller' },
    view.checks.map((c) => el('li', { class: c.ok ? 'ok' : 'bad' }, icon(c.ok ? 'check' : 'alert', 'sm'), c.label, c.note ? ` (${c.note})` : ''))) : null;
  const sources = view.citations.length ? el('section', { 'aria-labelledby': 'skill-run-sources' },
    el('h3', { id: 'skill-run-sources' }, 'Kaynaklar'), el('ul', { class: 'skill-lib-sources' },
      view.citations.map((c) => el('li', {}, citationChip(c), c.kind === 'INPUT' ? el('span', { class: 'muted' }, ` ${c.excerpt.slice(0, 160)}`) : null)))) : null;
  return [head, el('div', { class: 'card-body grid' }, disclaimer, toolbar, ...sections, sources, checks, rating)];
}

function sectionPart(section, edit) {
  if (section.kind === 'TEXT') {
    const text = edit
      ? el('textarea', { class: 'textarea', rows: 5, 'aria-label': section.title, oninput: (event) => edit.change(setText(edit.get(), section.key, event.target.value)) })
      : el('p', {}, section.notAssessable ? el('span', { class: 'pill neutral' }, NOT_ASSESSABLE) : section.text);
    if (edit) text.value = section.text;
    return el('section', { class: 'skill-lib-section' }, el('h3', {}, section.title), text,
      section.evidence.length ? el('div', { class: 'skill-lib-meta' }, section.evidence.map(citationChip)) : null);
  }
  const header = el('tr', {}, section.columns.map((c) => el('th', { scope: 'col' }, c.label)), el('th', { scope: 'col' }, 'Kaynak'),
    edit ? el('th', { scope: 'col' }, NOT_ASSESSABLE) : null);
  const rows = section.rows.map((row) => el('tr', { class: row.notAssessable ? 'skill-lib-na' : null },
    row.cells.map((cell) => el('td', {}, edit
      ? cellInput(cell, (value) => edit.change(setCell(edit.get(), section.key, row.index, cell.key, value)))
      : cell.value)),
    el('td', {}, row.notAssessable ? el('span', { class: 'pill neutral' }, NOT_ASSESSABLE) : row.evidence.map(citationChip),
      row.unverified.length ? el('span', { class: 'pill bad', title: 'Bu sayı ya da kod alıntılanan kaynakta geçmiyor.' },
        `Kaynakta yok: ${row.unverified.join(', ')}`) : null),
    edit ? el('td', {}, notAssessableBox(row, (flag) => edit.change(markNotAssessable(edit.get(), section.key, row.index, flag,
      row.evidence.map((e) => e.number))))) : null));
  return el('section', { class: 'skill-lib-section' }, el('h3', {}, section.title),
    section.rows.length ? el('div', { class: 'table-wrap' }, el('table', { class: 'table skill-lib-table' }, el('thead', {}, header), el('tbody', {}, rows)))
      : el('p', { class: 'muted' }, 'Bu bölümde satır yok.'));
}

function cellInput(cell, onChange) {
  const input = el('textarea', { class: 'textarea', rows: 2, 'aria-label': cell.label, oninput: () => onChange(input.value) });
  input.value = cell.value;
  return input;
}

function notAssessableBox(row, onChange) {
  const box = el('input', { type: 'checkbox', 'aria-label': `Satır ${row.index + 1}: ${NOT_ASSESSABLE}`, onchange: () => onChange(box.checked) });
  box.checked = row.notAssessable;
  return box;
}

/** A source chip: a document passage opens the page drawer, the user's own input opens its excerpt. */
function citationChip(citation) {
  return el('button', { class: `chip skill-lib-cite${citation.accessible ? '' : ' muted'}`, type: 'button', disabled: !citation.accessible,
    onclick: (event) => {
      if (citation.drawer) openPageDrawer(citation.drawer, { opener: event.currentTarget });
      else openDrawer({ title: 'Girdinizden alıntı', body: el('p', { class: 'skill-lib-excerpt' }, citation.excerpt) });
    } }, citation.label);
}

// ---------- admin: the installation's switch per skill and the customer-authored skills ----------
function adminCard() {
  const table = el('div', { class: 'card-body' }, el('div', { class: 'skeleton line' }));
  const editor = el('textarea', { class: 'textarea skill-lib-json', id: 'skill-lib-json', rows: 16, spellcheck: 'false', autocomplete: 'off',
    'aria-describedby': 'skill-lib-json-help' });
  const problems = el('ul', { class: 'skill-lib-problems', role: 'alert' });
  const picker = el('select', { class: 'input', id: 'skill-lib-custom', 'aria-label': 'Kuruma özel beceri' });
  let custom = [];
  const section = el('section', { class: 'card', 'aria-labelledby': 'skill-lib-admin' },
    el('div', { class: 'card-head' }, el('h2', { id: 'skill-lib-admin' }, 'Beceri yönetimi')),
    el('p', { class: 'card-body muted' }, 'Bu kurulumda hangi becerilerin açık olduğunu seçin. Kuruma özel beceriyi danışmanlık ekibi '
      + 'JSON olarak yazar; taslak bir yöneticinin onayıyla kullanıma açılır. Biçim: skills/README.md.'),
    table,
    el('div', { class: 'card-body grid' },
      el('label', { for: 'skill-lib-custom' }, 'Kuruma özel beceri'), picker,
      el('label', { for: 'skill-lib-json' }, 'Tanım (JSON)'),
      el('p', { class: 'muted', id: 'skill-lib-json-help' }, 'Yeni beceri için boş bir tanımla başlayın ya da var olanı seçip yeni sürüm kaydedin.'),
      editor, problems,
      el('div', { class: 'skill-lib-actions' },
        el('button', { class: 'button secondary', type: 'button', onclick: () => validate() }, icon('check', 'sm'), 'Doğrula'),
        el('button', { class: 'button', type: 'button', onclick: () => save() }, 'Taslak kaydet'),
        el('button', { class: 'button secondary', type: 'button', onclick: () => transition('approve') }, icon('checkCircle', 'sm'), 'Onayla'),
        el('button', { class: 'button ghost danger-text', type: 'button', onclick: () => transition('archive') }, 'Arşivle'))));

  const showProblems = (list) => fill(problems, (list ?? []).map((p) => el('li', {}, p)));
  const current = () => custom.find((c) => c.id === picker.value) ?? null;
  const definition = () => {
    const parsed = parseDefinition(editor.value);
    if (parsed.error) showProblems([parsed.error]);
    return parsed.definition ?? null;
  };

  async function load() {
    let denied = false;
    const quiet = (error) => {
      // The server's word on the role: a 403 hides the admin part's data (the panel only guessed the role).
      if (error instanceof ApiError && error.status === 403) denied = true;
      return null;
    };
    const [list, customList] = await Promise.all([api.listAdminTaskSkills().catch(quiet), api.listCustomSkills().catch(quiet)]);
    if (denied) {
      fill(section, el('p', { class: 'card-body muted' }, 'Bu bölüm yöneticiler içindir.'));
      return;
    }
    custom = Array.isArray(customList) ? customList : [];
    fill(picker, el('option', { value: '' }, 'Yeni beceri'), custom.map((c) => el('option', { value: c.id },
      `${c.skillId} · s${c.version} · ${CUSTOM_STATES[c.state] ?? c.state}`)));
    const rows = adminRows(list);
    fill(table, rows.length ? el('div', { class: 'table-wrap' }, el('table', { class: 'table' },
      el('thead', {}, el('tr', {}, el('th', { scope: 'col' }, 'Beceri'), el('th', { scope: 'col' }, 'Kategori'), el('th', { scope: 'col' }, 'Kaynak'),
        el('th', { scope: 'col' }, 'Durum'), el('th', { scope: 'col' }, 'Açık'))),
      el('tbody', {}, rows.map((row) => el('tr', {}, el('td', {}, row.name), el('td', {}, row.category), el('td', {}, row.origin),
        el('td', {}, row.state || '—'), el('td', {}, toggle(row))))))) : el('p', { class: 'muted' }, 'Beceri listesi okunamadı.'));
  }

  function toggle(row) {
    const box = el('input', { type: 'checkbox', role: 'switch', 'aria-label': `${row.name} açık`, onchange: async () => {
      try {
        await api.setTaskSkillEnabled(row.id, box.checked);
      } catch (error) {
        box.checked = !box.checked;
        toast(error instanceof ApiError ? error.message : 'Bağlantı kurulamadı.');
      }
    } });
    box.checked = row.enabled;
    return box;
  }

  picker.addEventListener('change', () => {
    const chosen = current();
    editor.value = chosen ? JSON.stringify(chosen.definition, null, 2) : '';
    showProblems([]);
  });

  async function call(action, success) {
    try {
      const result = await action();
      if (success) toast(success, 'ok');
      return result;
    } catch (error) {
      toast(error instanceof ApiError ? error.message : 'Bağlantı kurulamadı.');
      return null;
    }
  }

  async function validate() {
    const value = definition();
    if (!value) return;
    const result = await call(() => api.validateCustomSkill(value));
    if (result) showProblems(result.valid ? ['Tanım geçerli.'] : result.errors);
  }

  async function save() {
    const value = definition();
    if (!value) return;
    const chosen = current();
    const saved = await call(() => (chosen ? api.updateCustomSkill(chosen.id, value) : api.createCustomSkill(value)), 'Taslak kaydedildi.');
    if (saved) {
      await load();
      picker.value = saved.id;
    }
  }

  async function transition(kind) {
    const chosen = current();
    if (!chosen) {
      showProblems(['Önce listeden kayıtlı bir beceri seçin.']);
      return;
    }
    const done = await call(() => (kind === 'approve' ? api.approveCustomSkill(chosen.id) : api.archiveCustomSkill(chosen.id)),
      kind === 'approve' ? 'Beceri onaylandı.' : 'Beceri arşivlendi.');
    if (done) await load();
  }

  load();
  return section;
}
