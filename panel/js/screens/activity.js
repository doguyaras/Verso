// "Etkinlik merkezi" (ADR-0058): one place for whoever audits. The status strip ("Her şey yolunda" / "N iş dikkat
// istiyor"), tabs per category, filters, a timeline grouped by day, the approval box on the side and a drawer with the
// steps, sources and model-call metadata of one entry and its audit reference. verso-admin only; the server sends no
// account id (a team, a role or a system is the actor) and names only what this admin may read.
import { api, ApiError } from '../api.js';
import { activityQuery, auditText, filtersActive, FIRST_VIEW, groupByDay, itemModel, RANGES, STATUS_FILTERS, statusLabel, statusStrip,
  TABS, visibleItems } from '../activity.js';
import { icon } from '../icons.js';
import { canOpen } from '../roles.js';
import { el, fill } from '../render.js';
import { disclosure, emptyState, helpLine, openDrawer, skeletonRows } from '../ui.js';

const RANGE_LABELS = Object.freeze({ 1: 'Son 24 saat', 7: 'Son 7 gün', 30: 'Son 30 gün', 90: 'Son 90 gün' });

function strip(summary) {
  const model = statusStrip(summary);
  return el('div', { class: `activity-strip ${model.calm ? 'calm' : 'attention'}`, role: 'status' },
    icon(model.calm ? 'checkCircle' : 'alert', 'sm'),
    el('div', { class: 'grow' }, el('strong', {}, model.title), model.reasons ? el('span', {}, ` ${model.reasons}`) : null,
      model.partial ? el('span', { class: 'muted' }, ' · bazı kaynaklar yanıt vermedi') : null),
    el('div', { class: 'activity-strip-meta' }, el('span', {}, 'Son 24 saat:'), model.counts.map((c) => el('span', {}, c))));
}

function line(ctx, item, open) {
  const m = itemModel(item);
  return el('li', { class: `activity-line${m.attention ? ' attention' : ''}` },
    el('button', { class: 'activity-row', type: 'button', 'aria-haspopup': 'dialog', onclick: (event) => open(item, event.currentTarget) },
      el('span', { class: `activity-dot ${m.tone}`, 'aria-hidden': 'true' }),
      el('span', { class: 'activity-kind' }, m.category),
      el('span', { class: 'activity-title' }, el('strong', {}, m.title),
        el('span', { class: 'sub' }, [m.actor, m.detail].filter(Boolean).join(' · '))),
      el('span', { class: `pill ${m.tone}` }, m.status),
      el('span', { class: 'activity-time' }, m.time)));
}

function approvalBox(ctx, items, open) {
  // Sadeleştirme: the box shows only while something waits; "Bekleyen onay yok" is no news.
  if (!items.length) return null;
  return el('section', { class: 'card activity-inbox', 'aria-labelledby': 'inbox-title' },
    el('div', { class: 'card-head' }, el('h2', { id: 'inbox-title' }, 'Onay kutusu'), el('span', { class: 'spacer' }),
      el('span', { class: 'pill neutral tabular' }, String(items.length))),
    el('div', { class: 'card-body' }, items.length
      ? el('ul', { class: 'list' }, items.map((item) => {
        const m = itemModel(item);
        return el('li', {}, el('div', { class: 'grow' }, el('span', { class: 'sub' }, m.type), el('span', { class: 'title' }, m.title),
          el('span', { class: 'sub' }, m.detail)),
        el('button', { class: 'button secondary sm', type: 'button', 'aria-haspopup': 'dialog', onclick: (event) => open(item, event.currentTarget) }, 'İncele'));
      }))
      : el('p', { class: 'muted' }, 'Bekleyen onay yok.')));
}

/** The drawer: the entry, then its facts, steps, sources and model calls when the source has them. */
async function openDetail(ctx, item) {
  const m = itemModel(item);
  const body = el('div', { class: 'activity-detail' }, skeletonRows(3));
  const actions = [];
  if (m.link && canOpen(m.link.screen, ctx.roles)) {
    actions.push(el('button', { class: 'button secondary', type: 'button', onclick: () => {
      close();
      ctx.navigate(m.link.screen, m.link.id ? { open: m.link.id } : null);
    } }, icon('arrowRight', 'sm'), 'Kaynağına git'));
  }
  if (item.audit && canOpen('trust', ctx.roles)) {
    actions.push(el('button', { class: 'button ghost', type: 'button', onclick: () => {
      close();
      ctx.navigate('trust', { audit: { targetType: item.audit.targetType, action: item.audit.action ?? null, at: item.startedAt } });
    } }, icon('shield', 'sm'), 'Denetim kaydı'));
  }
  const close = openDrawer({ title: m.title, subtitle: `${m.category} · ${m.type} · ${m.time}`, body, actions, className: 'activity-drawer' });
  let detail = null;
  if (m.id) {
    try {
      detail = await ctx.withAuth(() => api.activityDetail(m.id));
    } catch (error) {
      detail = error instanceof ApiError && error.status === 404 ? null : undefined;
    }
  }
  const audit = auditText(item.audit);
  const facts = [['Durum', statusLabel(item.status)], ['Başlatan', m.actor], ['Süre', m.duration ?? '—'],
    ...(m.detail ? [['Ayrıntı', m.detail]] : []), ...(audit ? [['Denetim', audit]] : []),
    ...((detail?.facts ?? []).map((f) => [f.label, f.value]))];
  fill(body,
    el('dl', { class: 'kv' }, facts.flatMap(([k, v]) => [el('dt', {}, k), el('dd', {}, String(v))])),
    detail?.steps?.length ? el('section', {}, el('h3', { class: 'sub-head' }, 'Adımlar'),
      el('ol', { class: 'activity-steps' }, detail.steps.map((s) => el('li', {},
        el('span', { class: `activity-dot ${itemModel({ status: s.status }).tone}`, 'aria-hidden': 'true' }),
        el('span', { class: 'grow' }, s.label), el('span', { class: 'muted tabular' }, s.detail ?? ''))))) : null,
    detail?.sources?.length ? el('section', {}, el('h3', { class: 'sub-head' }, 'Kullanılan kaynaklar'),
      el('ul', { class: 'list' }, detail.sources.map((s) => el('li', {},
        el('span', { class: 'title' }, s.readable ? s.name : 'Erişiminiz olmayan kaynak'),
        s.citations != null ? el('span', { class: 'sub' }, `${s.citations} atıf`) : null)))) : null,
    detail?.modelCalls?.length ? el('section', {}, el('h3', { class: 'sub-head' }, 'Model çağrıları'),
      el('ul', { class: 'list' }, detail.modelCalls.map((c) => el('li', {}, el('span', { class: 'title' }, c.purpose),
        el('span', { class: 'sub' }, [c.local ? 'Yerel' : 'Bulut', c.provider, c.inputTokens != null ? `${c.inputTokens} girdi token` : null]
          .filter(Boolean).join(' · ')))))) : null,
    detail === undefined ? el('p', { class: 'muted' }, 'Ayrıntı alınamadı.') : null,
    el('p', { class: 'muted small' }, 'Yalnız üst veri gösterilir. Soru, istem ve cevap metni geçmişte ve denetim kaydında, yetkili rolle açılır.'));
}

export async function renderActivity(ctx) {
  const { main, guarded } = ctx;
  const state = { category: '', status: '', days: 7, page: 0, expanded: false };
  const stripSlot = el('div', { class: 'activity-strip-slot' }, el('div', { class: 'skeleton line' }));
  const tabs = el('div', { class: 'activity-tabs', role: 'tablist', 'aria-label': 'Etkinlik türü' });
  const filters = el('div', { class: 'activity-filters' });
  // Sadeleştirme (ADR-0058): the status line and the newest five entries first; the tabs and filters behind "Filtrele".
  const filterBox = disclosure('Filtrele', [tabs, filters], 'filters');
  const filterLabel = filterBox.querySelector('summary span');
  const timeline = el('div', { class: 'activity-timeline', 'aria-live': 'polite' }, skeletonRows(FIRST_VIEW));
  const inbox = el('div', { class: 'activity-side' });
  fill(main,
    el('div', { class: 'page-head' }, el('div', {}, el('h1', {}, 'Etkinlik merkezi'),
      helpLine('Çalışmalar, arka plan işleri, kancalar ve onaylar tek yerde.',
        'Bilgi değişiklikleri de burada. Kişi değil ekip ya da rol görünür; bir satır ayrıntısını, adımlarını ve denetim referansını açar.'))),
    stripSlot,
    el('div', { class: 'activity-layout' }, el('div', { class: 'activity-main' }, filterBox, timeline), inbox));

  const open = (item, opener) => {
    opener?.focus();
    openDetail(ctx, item);
  };

  let request = 0;
  async function load() {
    const mine = ++request;
    fill(tabs, TABS.map((t) => el('button', { type: 'button', role: 'tab', 'aria-selected': String(t.id === state.category),
      class: 'activity-tab', onclick: () => {
        state.category = t.id;
        state.page = 0;
        load();
      } }, t.label)));
    const status = el('select', { class: 'input sm', 'aria-label': 'Durum', onchange: (event) => {
      state.status = event.target.value;
      state.page = 0;
      load();
    } }, STATUS_FILTERS.map((s) => el('option', { value: s, selected: s === state.status }, s ? statusLabel(s) : 'Durum: tümü')));
    const range = el('select', { class: 'input sm', 'aria-label': 'Tarih', onchange: (event) => {
      state.days = Number(event.target.value);
      state.page = 0;
      load();
    } }, RANGES.map((d) => el('option', { value: String(d), selected: d === state.days }, RANGE_LABELS[d])));
    fill(filters, status, range);
    filterLabel.textContent = filtersActive(state) ? 'Filtrele · süzgeç açık' : 'Filtrele';
    timeline.setAttribute('aria-busy', 'true');
    const [page, approvals] = await Promise.all([
      guarded(() => api.activity(activityQuery(state))),
      guarded(() => api.activity(activityQuery({ category: 'APPROVAL', days: 90 }))),
    ]);
    if (!ctx.isCurrent() || mine !== request) return;
    timeline.removeAttribute('aria-busy');
    fill(inbox, approvalBox(ctx, approvals?.items ?? [], open));
    if (!page) {
      fill(timeline, el('div', { class: 'notice bad' }, icon('alert', 'sm'), el('div', { class: 'grow' }, 'Etkinlikler alınamadı.')));
      return;
    }
    const items = page.items.filter((i) => i.category !== 'APPROVAL' || state.category === 'APPROVAL');
    const groups = groupByDay(visibleItems(items, state.expanded));
    const hidden = items.length - visibleItems(items, state.expanded).length;
    // An empty list has one way out: back to every category and status over the longest range or, with no filter on,
    // to the assistant whose answers fill it.
    const widen = el('button', { class: 'button secondary', type: 'button', onclick: () => {
      Object.assign(state, { category: '', status: '', days: RANGES.at(-1), page: 0 });
      load();
    } }, 'Tüm etkinliklere bak');
    fill(timeline,
      page.partial ? el('div', { class: 'notice wait' }, icon('alert', 'sm'), el('div', { class: 'grow' }, 'Bazı modüller yanıt vermedi; liste eksik olabilir.')) : null,
      groups.length ? groups.map((g) => el('section', { class: 'activity-day' }, el('h2', { class: 'activity-day-head' }, g.label),
        el('ul', { class: 'activity-lines' }, g.items.map((item) => line(ctx, item, open)))))
        : emptyState('clock', 'Bu aralıkta etkinlik yok', state.category === 'RUN' && !page.sources.includes('work')
          ? 'Beceri çalışmaları, beceri modülü kurulduğunda burada görünür.'
          : filtersActive(state) ? 'Seçili süzgeçlerle eşleşen bir şey yok.' : 'Asistana bir soru sorduğunuzda ilk etkinlik burada görünür.',
          filtersActive(state) ? widen : el('a', { class: 'button secondary', href: '#/ask' }, 'Asistana sor')),
      hidden > 0 ? el('button', { class: 'button ghost activity-more', type: 'button', onclick: () => {
        state.expanded = true;
        load();
      } }, `Daha fazla göster (${hidden})`) : null,
      state.expanded || state.page > 0 ? el('div', { class: 'activity-pager' },
        state.page > 0 ? el('button', { class: 'button secondary sm', type: 'button', onclick: () => {
          state.page -= 1;
          load();
        } }, 'Daha yeni') : null,
        page.hasMore ? el('button', { class: 'button secondary sm', type: 'button', onclick: () => {
          state.page += 1;
          load();
        } }, 'Daha eski') : null) : null);
  }

  const summary = guarded(() => api.activitySummary()).then((s) => {
    if (ctx.isCurrent() && s) fill(stripSlot, strip(s));
    else if (ctx.isCurrent()) stripSlot.replaceChildren();
  });
  await Promise.all([load(), summary]);
}
