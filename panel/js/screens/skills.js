// "Prosedürler" (ADR-0053): the procedures of the learning layer. verso-admin turns recurring "how do I" questions into
// approved step lists: a draft from a diagnostics cluster (the verified cards around it) or from chosen verified cards,
// assembled by templates from the cards' answers and cited passages. The admin edits a draft into a new version
// (reword, reorder, remove steps; example questions; 2-5 golden cases), approves it, archives it, compares versions and
// downloads it as a SKILL.md bundle for Claude/Copilot. Approved procedures appear above the answer in "Soru sor" and
// over MCP (verso_skills, verso_skill), only to people who read every source. The server enforces the role (403 for
// everyone else). Everything is built with el(): text, never markup (CSP, Trusted Types); no inline styles.
import { api } from '../api.js';
import { icon } from '../icons.js';
import { stateLabel as cardStateLabel } from '../knowledge.js';
import { approvalProblems, approvalText, canApprove, canArchive, canEdit, canExport, diffText, eventText, exportFileName,
  LIMITS, sourceLabel, sourcePages, staleText, STATE_FILTERS, STATES, stateLabel, stateTone, suggestionText,
  versionBody } from '../skills.js';
import { el, fill, formatDate, plainName } from '../render.js';
import { confirmDialog, emptyState, toast } from '../ui.js';
import { saveFile } from './health.js';

/** ADR-0028: an admin without a grant on every source sees the skill's state, not its text (the server sends none). */
export const HIDDEN = 'Kaynaklarından en az birini okuma yetkiniz yok; metin gösterilmiyor.';
const PLAIN = { autocomplete: 'off', spellcheck: 'false' };

export function renderSkills(ctx) {
  const { main, guarded } = ctx;
  let state = '';
  const list = el('div', { class: 'card-body grid' }, el('div', { class: 'skeleton line' }));
  const suggestions = el('div', { class: 'card-body grid' }, el('div', { class: 'skeleton line' }));
  const cards = el('div', { class: 'card-body grid' }, el('div', { class: 'skeleton line' }));
  const condense = el('input', { type: 'checkbox', id: 'skill-condense' });
  const filter = el('select', { class: 'input', id: 'skill-state', 'aria-label': 'Duruma göre süz', onchange: () => {
    state = filter.value;
    refresh();
  } }, STATE_FILTERS.map((s) => el('option', { value: s }, s ? stateLabel(s) : 'Bütün durumlar')));

  main.replaceChildren(
    el('div', { class: 'page-head' },
      el('div', {}, el('h1', {}, 'Prosedürler'),
        el('p', {}, 'Sık sorulan "nasıl yaparım" sorularını uzmanlarınızın onayladığı adım adım prosedürlere çevirin. Taslak, '
          + 'onaylı bilgi kartlarının cevaplarından ve alıntıladıkları sayfalardan şablonla kurulur; her adım kaynağına bağlıdır. '
          + 'Onaylanan prosedür, kaynaklarının hepsini okuyabilen kişiye cevabın üstünde "Bu bir prosedür" olarak görünür, '
          + 'Claude ya da Copilot gibi asistanlara MCP üzerinden kişinin kendi yetkisiyle sunulur ve SKILL.md olarak indirilebilir. '
          + 'Kaynak değişince prosedür kendiliğinden kullanımdan çıkar.')),
      el('div', { class: 'page-actions' },
        el('button', { class: 'button secondary', type: 'button', onclick: () => refreshAll() }, icon('refresh'), 'Yenile'))),
    el('section', { class: 'card', 'aria-labelledby': 'skill-suggestions' },
      el('div', { class: 'card-head' }, el('h2', { id: 'skill-suggestions' }, 'Tekrarlayan sorulardan öneriler')),
      el('p', { class: 'card-body muted' }, 'Bilgi sağlığı teşhisinin soru kümeleri: en az 5 farklı kişinin sorduğu konular ve '
        + 'çevresindeki onaylı kartlar. Kişi ya da soru metni gösterilmez.'),
      el('label', { class: 'connector-check', for: 'skill-condense' }, condense,
        el('span', {}, 'Yerel modelle sırala ve kısalt (her adım kaynağına karşı yeniden denetlenir)')),
      suggestions),
    el('section', { class: 'card', 'aria-labelledby': 'skill-manual' },
      el('div', { class: 'card-head' }, el('h2', { id: 'skill-manual' }, 'Onaylı kartlardan taslak')),
      cards),
    el('section', { class: 'card', 'aria-labelledby': 'skill-list' },
      el('div', { class: 'card-head' }, el('h2', { id: 'skill-list' }, 'Prosedürler'), el('span', { class: 'spacer' }),
        el('label', { class: 'sr-only', for: 'skill-state' }, 'Durum'), filter),
      list));

  async function draft(body) {
    const created = await guarded(() => api.draftSkill({ ...body, condense: condense.checked === true }));
    if (!created || !ctx.isCurrent()) return;
    toast('Taslak oluşturuldu; düzenleyip onaylayabilirsiniz.', 'ok');
    refresh();
  }

  async function refreshSuggestions() {
    const found = await guarded(() => api.skillSuggestions());
    if (!found || !ctx.isCurrent()) return;
    fill(suggestions, found.length ? el('ul', { class: 'connectors', 'aria-label': 'Öneriler' }, found.map((s) =>
      el('li', { class: 'connector' },
        el('span', { class: 'connector-icon' }, icon(s.procedural ? 'flag' : 'layers', 'sm')),
        el('div', { class: 'grow' }, el('strong', {}, suggestionText(s)),
          el('span', { class: 'sub' }, s.procedural ? 'Muhtemelen bir prosedür' : 'Bilgi sorusu olabilir')),
        el('button', { class: 'button sm', type: 'button', disabled: s.verifiedCards < 1,
          onclick: () => draft({ clusterId: s.clusterId }) }, icon('plus', 'sm'), 'Taslak oluştur'))))
      : emptyState('layers', 'Henüz öneri yok', 'Bilgi sağlığı teşhisi çalışınca tekrarlayan soru kümeleri burada görünür.'));
  }

  async function refreshCards() {
    const page = await guarded(() => api.listKnowledgeCards('VERIFIED', 0, 50));
    if (!page || !ctx.isCurrent()) return;
    const verified = (page.data ?? []).filter((c) => c.readable !== false);
    if (!verified.length) {
      fill(cards, emptyState('checkCircle', 'Onaylı kart yok', 'Önce "Soru sor" ekranında kaynaklı cevapları onaylayın.'));
      return;
    }
    const boxes = verified.map((card) => ({ card, box: el('input', { type: 'checkbox', id: `skill-card-${card.id}` }) }));
    fill(cards,
      el('ul', { class: 'connectors', 'aria-label': 'Onaylı kartlar' }, boxes.map(({ card, box }) => el('li', { class: 'connector' },
        el('label', { class: 'connector-check grow', for: box.id }, box,
          el('span', {}, `${plainName(card.question)} · ${cardStateLabel(card.state)}`))))),
      el('div', { class: 'page-actions' }, el('button', { class: 'button', type: 'button', onclick: () => {
        const chosen = boxes.filter((b) => b.box.checked).map((b) => b.card.id).slice(0, LIMITS.steps);
        if (!chosen.length) {
          toast('En az bir kart seçin.', 'wait');
          return;
        }
        draft({ cardIds: chosen });
      } }, icon('plus', 'sm'), 'Seçilenlerden taslak')));
  }

  function statePill(skill) {
    return el('span', { class: `pill ${stateTone(skill.state)}`, title: STATES[skill.state]?.hint ?? '' }, stateLabel(skill.state));
  }

  async function details(skill, slot) {
    const full = await guarded(() => api.getSkill(skill.id));
    if (!full || !ctx.isCurrent()) return;
    const problems = full.state === 'DRAFT' ? approvalProblems(full) : [];
    const versions = full.versions ?? [];
    fill(slot,
      full.readable === false ? el('p', {}, HIDDEN) : el('p', {}, plainName(full.description)),
      el('ol', { class: 'steps-list', 'aria-label': 'Adımlar' }, (full.steps ?? []).map((step) => el('li', {},
        el('span', {}, full.readable === false ? `Adım ${step.position}` : plainName(step.instruction)),
        step.grounded === false ? el('span', { class: 'pill bad', title: 'Kaynaklarında olmayan bir sayı ya da kod var.' },
          'Doğrulanamadı') : null,
        el('span', { class: 'sub' }, ` (${(step.sources ?? []).map(sourceLabel).join('; ')})`)))),
      full.triggers?.length ? el('p', { class: 'muted' }, `Örnek sorular: ${full.triggers.map(plainName).join(' · ')}`) : null,
      full.goldenCases?.length ? el('ul', { class: 'steps-list', 'aria-label': 'Kontrol soruları' }, full.goldenCases.map((g) =>
        el('li', {}, `${plainName(g.question)} → ${sourceLabel({ documentId: g.documentId, page: g.page })}`
          + `${g.fact ? ` · "${plainName(g.fact)}"` : ''}`))) : null,
      problems.length ? el('div', { class: 'notice wait' }, icon('alert', 'sm'),
        el('div', { class: 'grow' }, problems.map((p) => el('div', {}, p)))) : null,
      el('ol', { class: 'steps-list', 'aria-label': 'Geçmiş' }, (full.events ?? []).map((e) =>
        el('li', {}, `${formatDate(e.at)} · sürüm ${e.version} · ${eventText(e.reason)}`))),
      versions.length > 1 ? el('div', { class: 'page-actions' }, versions.slice(1).map((v) =>
        el('button', { class: 'button ghost sm', type: 'button', onclick: async (event) => {
          const diff = await guarded(() => api.skillDiff(full.id, v.version - 1, v.version));
          if (!diff || !ctx.isCurrent()) return;
          event.currentTarget.replaceWith(el('ul', { class: 'steps-list', 'aria-label': `Sürüm ${v.version} farkı` },
            (diff.changes ?? []).length ? diff.changes.map((c) => el('li', {}, diffText(c)
              + (c.after ? `: ${plainName(c.after)}` : ''))) : el('li', {}, 'Fark yok')));
        } }, icon('history', 'sm'), `Sürüm ${v.version - 1} → ${v.version}`))) : null,
      el('div', { class: 'page-actions' },
        canEdit(full) ? el('button', { class: 'button secondary sm', type: 'button', onclick: () => editForm(full, slot) },
          icon('edit', 'sm'), 'Düzenle') : null,
        canApprove(full) ? el('button', { class: 'button sm', type: 'button', onclick: async () => {
          const done = await guarded(() => api.approveSkill(full.id));
          if (!done) return;
          toast(done.state === 'APPROVED' ? 'Onaylandı.' : 'Onayınız kaydedildi; ikinci bir yöneticinin onayı bekleniyor.', 'ok');
          refresh();
        } }, icon('check', 'sm'), 'Onayla') : null,
        canExport(full) ? el('button', { class: 'button secondary sm', type: 'button', onclick: async () => {
          const blob = await guarded(() => api.exportSkill(full.id));
          if (!blob || !ctx.isCurrent()) return;
          saveFile(blob, exportFileName(full.name));
        } }, icon('download', 'sm'), 'SKILL.md indir') : null));
  }

  /** The edit form of a new version: the steps keep their sources; their order, wording and number can change. */
  function editForm(full, slot) {
    const pages = sourcePages(full);
    const name = el('input', { class: 'input', type: 'text', ...PLAIN, maxlength: LIMITS.name, 'aria-label': 'Ad' });
    name.value = full.name;
    const description = el('textarea', { class: 'textarea', rows: 2, maxlength: LIMITS.description, 'aria-label': 'Açıklama' });
    description.value = full.description ?? '';
    const stepList = el('ol', { class: 'steps-list', 'aria-label': 'Adımlar' });
    let steps = (full.steps ?? []).map((s) => ({ from: s.position, instruction: s.instruction ?? '' }));
    const drawSteps = () => fill(stepList, steps.map((step, index) => {
      const text = el('textarea', { class: 'textarea', rows: 2, maxlength: 1000, 'aria-label': `Adım ${index + 1}`,
        oninput: (event) => { step.instruction = event.target.value; } });
      text.value = step.instruction;
      const move = (delta) => {
        const target = index + delta;
        if (target < 0 || target >= steps.length) return;
        [steps[index], steps[target]] = [steps[target], steps[index]];
        drawSteps();
      };
      return el('li', {}, text, el('div', { class: 'page-actions' },
        el('button', { class: 'button ghost sm', type: 'button', 'aria-label': 'Yukarı taşı', onclick: () => move(-1) }, '↑'),
        el('button', { class: 'button ghost sm', type: 'button', 'aria-label': 'Aşağı taşı', onclick: () => move(1) }, '↓'),
        el('button', { class: 'button ghost sm', type: 'button', onclick: () => {
          steps = steps.filter((s) => s !== step);
          drawSteps();
        } }, icon('trash', 'sm'), 'Çıkar')));
    }));
    drawSteps();
    const triggers = el('textarea', { class: 'textarea', rows: 3, 'aria-label': 'Örnek sorular (her satıra bir soru)' });
    triggers.value = (full.triggers ?? []).join('\n');
    const golden = (full.goldenCases ?? []).map((g) => ({ ...g }));
    const goldenList = el('div', { class: 'grid' });
    const drawGolden = () => fill(goldenList, golden.map((g, index) => {
      const question = el('input', { class: 'input', type: 'text', ...PLAIN, maxlength: 1000, 'aria-label': `Kontrol sorusu ${index + 1}`,
        oninput: (event) => { g.question = event.target.value; } });
      question.value = g.question ?? '';
      const page = el('select', { class: 'input', 'aria-label': 'Beklenen kaynak sayfa', onchange: (event) => {
        const chosen = pages[Number(event.target.value)];
        g.documentId = chosen?.documentId;
        g.page = chosen?.page;
      } }, pages.map((p, i) => el('option', { value: String(i), selected: p.documentId === g.documentId && p.page === g.page },
        sourceLabel(p))));
      const fact = el('input', { class: 'input', type: 'text', ...PLAIN, maxlength: 200, placeholder: 'Sayfada geçen bir bilgi (isteğe bağlı)',
        'aria-label': 'Beklenen bilgi', oninput: (event) => { g.fact = event.target.value; } });
      fact.value = g.fact ?? '';
      if (!g.documentId && pages[0]) {
        g.documentId = pages[0].documentId;
        g.page = pages[0].page;
      }
      return el('div', { class: 'connector' }, el('div', { class: 'grow grid' }, question, page, fact),
        el('button', { class: 'button ghost sm', type: 'button', onclick: () => {
          golden.splice(index, 1);
          drawGolden();
        } }, icon('trash', 'sm'), 'Çıkar'));
    }));
    drawGolden();
    fill(slot,
      el('label', {}, 'Ad (küçük harf, rakam, tire)'), name,
      el('label', {}, 'Açıklama (ne zaman kullanılır)'), description,
      el('h3', {}, 'Adımlar'), stepList,
      el('label', {}, 'Örnek sorular'), triggers,
      el('h3', {}, `Kontrol soruları (${LIMITS.goldenMin}-${LIMITS.goldenMax})`), goldenList,
      el('div', { class: 'page-actions' },
        el('button', { class: 'button ghost sm', type: 'button', disabled: !pages.length, onclick: () => {
          if (golden.length >= LIMITS.goldenMax) return;
          golden.push({ question: '', documentId: pages[0]?.documentId, page: pages[0]?.page, fact: '' });
          drawGolden();
        } }, icon('plus', 'sm'), 'Kontrol sorusu ekle'),
        el('button', { class: 'button sm', type: 'button', onclick: async () => {
          const body = versionBody({ name: name.value, description: description.value, steps,
            triggers: triggers.value.split('\n'), goldenCases: golden });
          const saved = await guarded(() => api.updateSkill(full.id, body));
          if (!saved || !ctx.isCurrent()) return;
          toast('Yeni sürüm kaydedildi; yeniden onay gerekir.', 'ok');
          refresh();
        } }, icon('check', 'sm'), 'Yeni sürümü kaydet')));
  }

  function skillView(skill) {
    const slot = el('div', {});
    const stale = staleText(skill.staleReason);
    return el('article', { class: 'card' },
      el('div', { class: 'card-head' }, el('h2', {}, plainName(skill.name)), el('span', { class: 'spacer' }), statePill(skill)),
      el('div', { class: 'card-body' },
        el('p', {}, skill.readable === false ? HIDDEN : plainName(skill.description ?? '')),
        el('p', { class: 'muted' }, [`sürüm ${skill.version}`, `${skill.steps} adım`, `${skill.goldenCases} kontrol sorusu`,
          approvalText(skill), skill.approvedAt ? `onay ${formatDate(skill.approvedAt)}` : null].filter(Boolean).join(' · ')),
        stale ? el('div', { class: 'notice wait' }, icon('alert', 'sm'), el('div', { class: 'grow' }, stale)) : null,
        el('div', { class: 'page-actions' },
          el('button', { class: 'button ghost sm', type: 'button', onclick: (event) => {
            event.currentTarget.remove();
            details(skill, slot);
          } }, icon('info', 'sm'), 'Ayrıntılar'),
          canArchive(skill) ? el('button', { class: 'button ghost sm', type: 'button', onclick: async () => {
            const ok = await confirmDialog({ title: 'Prosedür arşivlensin mi?', confirm: 'Arşivle',
              message: 'Bu prosedür artık hiçbir soruda, MCP\'de ya da indirmede kullanılmaz. Aynı ad için yeni bir taslak '
                + 'oluşturabilirsiniz.' });
            if (!ok) return;
            const done = await guarded(() => api.archiveSkill(skill.id));
            if (!done) return;
            toast('Prosedür arşivlendi.', 'ok');
            refresh();
          } }, icon('trash', 'sm'), 'Arşivle') : null),
        slot));
  }

  async function refresh() {
    const page = await guarded(() => api.listSkills(state, 0, 50));
    if (!page || !ctx.isCurrent()) return;
    const skills = page.data ?? [];
    fill(list, skills.length ? skills.map(skillView) : emptyState('flag', 'Bu durumda prosedür yok',
      'Bir öneriden ya da onaylı kartlardan taslak oluşturun.'));
  }

  function refreshAll() {
    refresh();
    refreshSuggestions();
    refreshCards();
  }

  refreshAll();
  return () => {};
}
