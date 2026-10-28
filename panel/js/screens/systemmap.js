// "Sistem haritası" (ADR-0054): the light system model of the learning layer, verso-admin only. Rules extract systems,
// screens, codes, processes with their steps, terms and roles (a job title or a department, never a person) from the
// documents; this screen lists them, shows one entity with its definitions, its links and where it is mentioned, and
// reviews the relations the optional local-model pass suggested (a suggestion is used nowhere until it is accepted).
// The server reads everything with this admin's own access, so an entity appears only through readable documents.
// Everything is built with el(): text, never markup (CSP, Trusted Types); no inline styles.
import { api } from '../api.js';
import { icon } from '../icons.js';
import { el, fill, formatDate, plainName } from '../render.js';
import { emptyState, formatNumber, helpLine, toast } from '../ui.js';
import { entityCounts, entityTitle, relationGroups, showSuggestions, sourceLabel, statsSummary, suggestionText, TABS,
  typeIcon, typeLabel, typePlural } from '../systemmap.js';

/** How long the search box waits after the last key before asking the server. */
const SEARCH_DELAY_MS = 250;

export function renderSystemMap(ctx) {
  const { main, guarded } = ctx;
  let type = TABS[0];
  let stats = null;
  let settings = null;
  let searchTimer = null;
  let listRequest = 0;
  /** The entities opened before the current one, for "Geri". */
  const trail = [];
  const statsSlot = el('div', { class: 'card-body grid' }, el('div', { class: 'skeleton line' }));
  const tabs = el('div', { class: 'segmented wrap', role: 'group', 'aria-label': 'Kayıt türü' });
  const search = el('input', { class: 'input', type: 'search', id: 'systemmap-search', autocomplete: 'off', spellcheck: 'false',
    maxlength: 200, placeholder: 'Kod ya da terim ara', 'aria-label': 'Kod ya da terim ara', oninput: () => {
      clearTimeout(searchTimer);
      searchTimer = setTimeout(refreshList, SEARCH_DELAY_MS);
    } });
  const list = el('div', { class: 'card-body grid' }, el('div', { class: 'skeleton line' }));
  const detail = el('div', { class: 'card-body grid' },
    el('p', { class: 'muted' }, 'Ayrıntısını görmek için listeden bir kayıt seçin.'));
  const suggestionsCard = el('section', { class: 'card', 'aria-labelledby': 'systemmap-suggestions', hidden: true });

  main.replaceChildren(
    el('div', { class: 'page-head' },
      el('div', {}, el('h1', {}, 'Sistem haritası'),
        helpLine('Belgelerinizde geçen sistemler, ekranlar, kodlar ve süreçler.', 'Belgelerinizden kurallarla çıkarılan sistemler, ekranlar, kodlar, süreçler ve terimler; aralarındaki '
          + 'ilişkiler ve her birinin hangi belgede geçtiği. Kişi adı tutulmaz, sorumlular yalnız unvan ya da birim olarak '
          + 'görünür. Her kayıt yalnız okuma yetkiniz olan belgeler üzerinden gösterilir.')),
      el('div', { class: 'page-actions' },
        el('button', { class: 'button secondary', type: 'button', onclick: () => refreshAll() }, icon('refresh'), 'Yenile'))),
    el('section', { class: 'card', 'aria-labelledby': 'systemmap-stats' },
      el('div', { class: 'card-head' }, el('h2', { id: 'systemmap-stats' }, 'Özet')), statsSlot),
    el('section', { class: 'card', 'aria-labelledby': 'systemmap-list' },
      el('div', { class: 'card-head' }, el('h2', { id: 'systemmap-list' }, 'Kayıtlar')),
      el('div', { class: 'card-body grid' }, tabs,
        el('label', { class: 'sr-only', for: 'systemmap-search' }, 'Kod ya da terim ara'), search),
      list),
    el('section', { class: 'card', 'aria-labelledby': 'systemmap-detail' },
      el('div', { class: 'card-head' }, el('h2', { id: 'systemmap-detail' }, 'Ayrıntı')), detail),
    suggestionsCard);

  function pill(text, tone) {
    return el('span', { class: `pill ${tone}` }, text);
  }

  function drawTabs() {
    const counts = stats ? statsSummary(stats).counts : [];
    fill(tabs, TABS.map((t) => {
      const count = counts.find((c) => c.type === t)?.count;
      return el('button', { type: 'button', 'aria-pressed': String(t === type), onclick: () => {
        if (type === t) return;
        type = t;
        drawTabs();
        refreshList();
      } }, typePlural(t), Number.isFinite(count) ? el('span', { class: 'n' }, formatNumber(count)) : null);
    }));
  }

  async function refreshStats() {
    const found = await guarded(() => api.systemMapStats());
    if (!found || !ctx.isCurrent()) return;
    stats = found;
    const summary = statsSummary(found);
    fill(statsSlot,
      el('p', {}, summary.counts.map((c) => `${formatNumber(c.count)} ${c.label.toLocaleLowerCase('tr')}`).join(' · ')
        + ` · ${formatNumber(summary.steps)} süreç adımı`),
      el('p', { class: 'muted' }, summary.documents, summary.extractor ? ` · ${summary.extractor}` : null),
      el('div', {}, pill(summary.enrichment, summary.enrichmentOn ? 'ok' : 'neutral')));
    drawTabs();
  }

  async function refreshList() {
    const request = ++listRequest;
    const found = await guarded(() => api.systemEntities(type, search.value, 100));
    if (!found || !ctx.isCurrent() || request !== listRequest) return;
    fill(list, found.length ? el('ul', { class: 'connectors', 'aria-label': typePlural(type) }, found.map((entity) =>
      el('li', { class: 'connector' },
        el('span', { class: 'connector-icon' }, icon(typeIcon(entity.type), 'sm')),
        el('div', { class: 'grow' },
          el('button', { class: 'link-button', type: 'button', onclick: () => open(entity.type, entity.key, false) },
            plainName(entityTitle(entity))),
          el('span', { class: 'sub' }, `${typeLabel(entity.type)} · ${entityCounts(entity)}`)))))
      : emptyState(typeIcon(type), search.value.trim() ? 'Eşleşen kayıt yok' : 'Bu türde kayıt yok',
        'Belgeler işlendikçe kurallarla çıkarılan kayıtlar burada görünür.'));
  }

  /** Opens an entity in the detail card; from a link it remembers the current one for "Geri". */
  async function open(entityType, key, fromLink) {
    const found = await guarded(() => api.systemEntity(entityType, key));
    if (!found || !ctx.isCurrent()) return;
    if (!fromLink) trail.length = 0;
    drawDetail(found);
  }

  function entityButton(item, current) {
    return el('button', { class: 'link-button', type: 'button', onclick: () => {
      trail.push({ type: current.type, key: current.key });
      open(item.type, item.key, true);
    } }, plainName(item.text));
  }

  function drawDetail(entity) {
    const groups = relationGroups(entity);
    const definitions = entity.definitions ?? [];
    const sources = entity.sources ?? [];
    const asked = new Set();
    fill(detail,
      trail.length ? el('div', { class: 'page-actions' }, el('button', { class: 'button ghost sm', type: 'button', onclick: () => {
        const previous = trail.pop();
        if (previous) open(previous.type, previous.key, true);
      } }, 'Geri')) : null,
      el('h3', {}, plainName(entityTitle(entity)), ' ', pill(typeLabel(entity.type), 'neutral')),
      definitions.length ? el('div', { class: 'grid' }, el('strong', {}, 'Tanım'),
        el('ul', { class: 'plain-list', 'aria-label': 'Tanımlar' }, definitions.map((d) => el('li', {},
          el('span', {}, plainName(d.value ?? '')),
          d.source ? el('span', { class: 'sub' }, ` (${plainName(sourceLabel(d.source))})`) : null)))) : null,
      groups.map((g) => el('div', { class: 'grid' }, el('strong', {}, g.label),
        el(g.id === 'STEP_OF:in' ? 'ol' : 'ul', { class: 'plain-list', 'aria-label': g.label },
          g.items.map((item) => el('li', {}, entityButton(item, entity),
            el('span', { class: 'sub' }, ` · ${typeLabel(item.type)}`)))))),
      el('div', { class: 'grid' }, el('strong', {}, 'Nerede geçiyor'),
        sources.length ? el('ul', { class: 'plain-list', 'aria-label': 'Nerede geçiyor' }, sources.map((s) => {
          const first = !asked.has(s.documentId);
          asked.add(s.documentId);
          return el('li', {},
            el('span', {}, plainName(sourceLabel(s))),
            s.collectionName ? el('span', { class: 'sub' }, ` · ${plainName(s.collectionName)}`) : null,
            s.defining ? el('span', {}, ' ', pill('tanım', 'ok')) : null,
            settings?.enabled === true && first ? el('button', { class: 'button ghost sm', type: 'button', onclick: (event) =>
              suggestFor(s.documentId, event.currentTarget) }, icon('sparkles', 'sm'), 'Öneri iste') : null);
        })) : el('p', { class: 'muted' }, 'Okuyabildiğiniz bir belgede geçmiyor.')),
      entity.truncated ? el('p', { class: 'muted' }, 'Liste kısaltıldı; yalnız ilk kayıtlar gösteriliyor.') : null);
  }

  async function suggestFor(documentId, button) {
    button.disabled = true;
    const run = await guarded(() => api.suggestSystemRelations(documentId));
    button.disabled = false;
    if (!run || !ctx.isCurrent()) return;
    toast(`${formatNumber(run.stored ?? 0)} öneri eklendi, ${formatNumber(run.rejected ?? 0)} öneri elendi.`, 'ok');
    refreshSuggestions();
  }

  async function review(suggestion, accept) {
    const done = await guarded(() => (accept ? api.acceptSystemSuggestion(suggestion.id) : api.rejectSystemSuggestion(suggestion.id)));
    if (!done || !ctx.isCurrent()) return;
    toast(accept ? 'Öneri kabul edildi.' : 'Öneri reddedildi.', 'ok');
    refreshSuggestions();
  }

  async function refreshSuggestions() {
    const found = await guarded(() => api.systemSuggestionSettings());
    if (!found || !ctx.isCurrent()) return;
    settings = found;
    const drafts = await guarded(() => api.systemSuggestions('DRAFT'));
    if (!ctx.isCurrent()) return;
    const items = Array.isArray(drafts) ? drafts : [];
    suggestionsCard.hidden = !showSuggestions(settings, items);
    fill(suggestionsCard,
      el('div', { class: 'card-head' }, el('h2', { id: 'systemmap-suggestions' }, 'Öneriler (taslak)')),
      el('div', { class: 'card-body grid' },
        el('p', { class: 'muted' }, 'Yerel modelin önerdiği ilişkiler; onaylanmadan hiçbir yerde kullanılmaz.'),
        settings.enabled === true ? null
          : el('p', { class: 'muted' }, 'Öneri geçişi kapalı (VERSO_QA_SYSTEM_MODEL_SUGGESTIONS_ENABLED).'),
        items.length ? el('ul', { class: 'connectors', 'aria-label': 'Taslak öneriler' }, items.map((s) => el('li', { class: 'connector' },
          el('span', { class: 'connector-icon' }, icon('sparkles', 'sm')),
          el('div', { class: 'grow' }, el('strong', {}, plainName(suggestionText(s))),
            el('span', { class: 'sub' }, s.createdAt ? formatDate(s.createdAt) : '')),
          el('div', { class: 'row-actions' },
            el('button', { class: 'button sm', type: 'button', onclick: () => review(s, true) }, icon('check', 'sm'), 'Kabul et'),
            el('button', { class: 'button ghost sm', type: 'button', onclick: () => review(s, false) }, icon('x', 'sm'), 'Reddet')))))
          : el('p', { class: 'muted' }, 'İncelenecek öneri yok.')));
  }

  function refreshAll() {
    refreshStats();
    refreshList();
    refreshSuggestions();
  }

  drawTabs();
  refreshAll();
  return () => clearTimeout(searchTimer);
}
