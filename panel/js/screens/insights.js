// "Değer panosu" (ADR-0025): the installation's use and answer quality for verso-admin, from GET /v1/admin/metrics
// (ADR-0023 §6). One filter row (7 / 30 / 90 days) scopes everything below it; KPI tiles, two daily trend charts, the
// mode split, the reasons of 👎, the most and the never cited documents, and the unanswered questions only when the
// installation turned them on (k-anonymous). Document names follow the installation's policy; an "estimated time
// saved" is not shown because the API gives nothing it could honestly be derived from. The server enforces the role
// (403 for everyone else); the menu only hides the screen.
import { api, ApiError } from '../api.js';
import { barList, lineChart, splitBar } from '../chart.js';
import { CONFIG } from '../config.js';
import { icon } from '../icons.js';
import { dailySeries, dayLabel, DEFAULT_PRESET, documentLabel, formatCount, formatPercent, isEmpty, kpis, PRESETS, rangeFor,
  rangeLabel, reasonRows } from '../metrics.js';
import { usageSummary } from '../providers.js';
import { el, fill, formatDate } from '../render.js';
import { emptyState, relativeTime } from '../ui.js';

let preset = DEFAULT_PRESET; // kept while the tab lives, like the documents filter

function tile(kpi) {
  return el('div', { class: `card stat kpi kpi-${kpi.id}` },
    el('span', { class: 'stat-label' }, kpi.label),
    el('span', { class: 'kpi-value' }, kpi.value),
    el('span', { class: 'stat-note' }, kpi.note));
}

function card(title, ...body) {
  const id = `insight-${title.toLocaleLowerCase('tr').replace(/[^a-z0-9ığüşöç]+/g, '-')}`;
  return el('section', { class: 'card', 'aria-labelledby': id },
    el('div', { class: 'card-head' }, el('h2', { id }, title)),
    el('div', { class: 'card-body' }, ...body));
}

function trends(data) {
  const series = dailySeries(data.daily);
  const percent = (v) => formatPercent(v);
  return el('div', { class: 'grid halves' },
    card('Günlük soru', lineChart({ title: 'Günlük soru sayısı', days: series.days, dayLabel, format: formatCount,
      series: [{ id: 'questions', label: 'Soru', values: series.questions, tone: 'c1' }] })),
    card('Günlük sonuç oranı', lineChart({ title: 'Günlük kaynaklı cevap ve bulunamadı oranı', days: series.days, dayLabel, top: 1,
      format: percent, axisFormat: percent,
      series: [{ id: 'cited', label: 'Kaynaklı', values: series.cited, tone: 'c1' },
        { id: 'notFound', label: 'Bulunamadı', values: series.notFound, tone: 'c2' }] }),
    el('p', { class: 'stat-note' }, 'Soru sorulmayan günlerde oran yoktur; çizgi o günlerde kesilir.')));
}

function modesAndReasons(totals) {
  const reasons = reasonRows(totals.feedback);
  const downs = totals.feedback?.down ?? 0;
  return el('div', { class: 'grid halves' },
    card('Çalışma modu', splitBar([{ label: 'Yerel', value: totals.modes?.local ?? 0, tone: 'c1' },
      { label: 'Bulut', value: totals.modes?.cloud ?? 0, tone: 'c2' }], { format: formatCount }),
    el('p', { class: 'stat-note' }, 'Bulut modunda soru ve seçilen pasajlar sağlayıcıya gider.')),
    card('👎 nedenleri', downs
      ? barList(reasons.map((r) => ({ label: r.label, value: r.value, muted: r.id === 'NONE' })), { format: formatCount, label: '👎 nedenleri' })
      : el('p', { class: 'muted' }, 'Bu aralıkta 👎 verilmedi.')));
}

const namesNote = (names) => (names ? null : el('p', { class: 'stat-note' }, 'Belge adları kurulum ayarıyla gizli; belgeler kısa kimlikle gösterilir.'));

function documents(data) {
  const names = data.documentNamesShown;
  const top = data.topCitedDocuments ?? [];
  return el('div', { class: 'grid halves' },
    card('En çok atıf alan belgeler', top.length
      ? barList(top.map((d) => ({ label: documentLabel(d, names), value: d.citations, muted: d.documentDeleted })),
        { format: formatCount, label: 'Belge başına atıf' })
      : el('p', { class: 'muted' }, 'Bu aralıkta atıf yok.'),
    namesNote(names)),
    neverCited(data));
}

/** Ready documents no stored answer ever cited ("dead content"); also shown on a fresh installation. */
function neverCited(data) {
  const names = data.documentNamesShown;
  const never = data.neverCitedDocuments ?? { total: 0, complete: true, documents: [] };
  return card('Hiç atıf almamış belgeler',
    el('p', { class: 'kpi-inline' }, el('strong', {}, formatCount(never.total)), ' hazır belge hiçbir cevapta kaynak olmadı.'),
    never.documents.length ? el('ul', { class: 'list' }, never.documents.map((d) => el('li', {},
      el('span', { class: 'grow title' }, documentLabel(d, names)),
      el('span', { class: 'sub', title: formatDate(d.createdAt) }, relativeTime(d.createdAt))))) : null,
    never.total > never.documents.length ? el('p', { class: 'stat-note' }, `En eski ${never.documents.length} belge gösteriliyor.`) : null,
    never.complete ? null : el('p', { class: 'stat-note' }, 'Liste kesin değil: çok sayıda belgeye atıf yapıldığı için atıf almış belgeler de görünebilir.'),
    never.documents.length ? namesNote(names) : null);
}

function unanswered(list) {
  if (!list?.enabled) return null;
  return card('Cevaplanamayan sorular',
    el('p', { class: 'stat-note' }, `Yalnız en az ${list.minDistinctUsers} farklı kişinin sorduğu "bulunamadı" soruları gösterilir; `
      + 'kimin sorduğu hiçbir yerde yoktur ve metin küçük harfe indirilmiştir (k-anonimlik).'),
    list.questions.length
      ? el('ol', { class: 'list unanswered' }, list.questions.map((q) => el('li', {}, el('span', { class: 'grow' }, q.question),
        el('span', { class: 'sub' }, `${formatCount(q.distinctUsers)} kişi · ${formatCount(q.asked)} kez`))))
      : el('p', { class: 'muted' }, 'Bu aralıkta eşiği geçen soru yok.'));
}

function problem(error) {
  if (error instanceof ApiError && error.status === 403) {
    return emptyState('lock', 'Bu pano yöneticilere açık', 'Değer panosu yönetici rolü ister. Rolünüz yeni verildiyse çıkış yapıp yeniden girin.');
  }
  return el('div', { class: 'notice bad' }, icon('alert', 'sm'), el('div', { class: 'grow' },
    el('strong', {}, 'Sayılar alınamadı.'), el('div', {}, error instanceof ApiError ? error.message : 'Bağlantı kurulamadı.')));
}

/**
 * ADR-0056: this month's model calls, tokens and cost by connection, model and task, and the budgets; teams only above
 * the k-anonymity floor, never a person. Absent when the gateway has nothing to say (no call yet).
 */
function modelUsage(usage) {
  const summary = usageSummary(usage);
  if (!summary || !usage.requests) return null;
  return card('Model kullanımı ve maliyet',
    el('p', { class: 'stat-note' }, `${summary.month} ayı · fiyatlar Yapay zekâ sağlayıcıları ekranındaki tablodan`),
    el('div', { class: 'grid kpis' }, summary.totals.map((t) => tile({ label: t.label, value: t.value, note: '' }))),
    el('ul', { class: 'plain-list' }, summary.rows.map((r) => el('li', {}, el('strong', {}, r.label), ` (${r.task}): ${r.text}`))),
    summary.budgets.length ? el('ul', { class: 'plain-list' }, summary.budgets.map((b) =>
      el('li', {}, el('span', { class: `pill ${b.tone}` }, el('span', { class: 'dot' }), b.state), ` ${b.label}: ${b.text}`))) : null,
    summary.teams.length ? el('ul', { class: 'plain-list' }, summary.teams.map((t) => el('li', {}, t))) : null,
    el('p', { class: 'stat-note' }, summary.teamNote));
}

export async function renderInsights(ctx) {
  const { main, withAuth } = ctx;
  const content = el('div', { class: 'insights' });
  const rangeText = el('span', { class: 'range-label' });
  const presets = el('div', { class: 'segmented', role: 'group', 'aria-label': 'Tarih aralığı' });
  const filters = el('div', { class: 'filter-row' }, presets, rangeText);
  const status = el('div', { class: 'sr-only', role: 'status', 'aria-live': 'polite' });
  // ADR-0058: the former "Değer panosu" is the "Değer ve kullanım" tab of "Komuta panosu" (ctx.embedded): no own head.
  fill(main,
    ctx.embedded ? el('p', { class: 'muted' }, `${CONFIG.deployment.productName} kurulumunun kullanımı ve cevap kalitesi. Sayılar günlük sayaçlardan gelir; kişi ve cevap metni içermez.`)
      : el('div', { class: 'page-head' }, el('div', {}, el('h1', {}, 'Değer panosu'),
        el('p', {}, `${CONFIG.deployment.productName} kurulumunun kullanımı ve cevap kalitesi. Sayılar günlük sayaçlardan gelir; kişi ve cevap metni içermez.`))),
    filters, status, content);
  fill(content, el('div', { class: 'grid kpis' }, Array.from({ length: 7 }, () => el('div', { class: 'skeleton block' }))));

  let request = 0;
  async function load() {
    const mine = ++request;
    const range = rangeFor(preset);
    rangeText.textContent = rangeLabel(range);
    fill(presets, PRESETS.map((days) => el('button', { type: 'button', 'aria-pressed': String(days === preset),
      onclick: () => {
        if (days === preset) return;
        preset = days;
        load();
      } }, `${days} gün`)));
    // Refetch keeps the frame: the previous numbers stay, dimmed, until the new ones arrive.
    content.classList.add('refreshing');
    content.setAttribute('aria-busy', 'true');
    let data = null;
    let failure = null;
    try {
      data = await withAuth(() => api.metrics(range.from, range.to));
    } catch (error) {
      failure = error;
    }
    if (!ctx.isCurrent() || mine !== request) return;
    content.classList.remove('refreshing');
    content.removeAttribute('aria-busy');
    if (failure) {
      if (failure instanceof ApiError && failure.status === 401) return;
      fill(content, problem(failure));
      return;
    }
    rangeText.textContent = `${rangeLabel(range)} · ${data.timeZone}`;
    status.textContent = `${rangeLabel(range)} aralığı yüklendi.`;
    if (isEmpty(data)) {
      fill(content,
        el('section', { class: 'card' }, emptyState('chart', 'Bu aralıkta soru yok',
          'Kullanıcılar soru sordukça sayılar burada görünür. Yeni bir kurulumda ilk sorular sorulduğunda pano dolar.')),
        (data.neverCitedDocuments?.total ?? 0) > 0 ? neverCited(data) : null);
      return;
    }
    // The gateway's usage is optional here: a failure (or an older server) leaves the card out, never the board.
    const usage = await withAuth(() => api.modelUsage()).catch(() => null);
    if (!ctx.isCurrent() || mine !== request) return;
    fill(content,
      el('div', { class: 'grid kpis' }, kpis(data.totals).map(tile)),
      trends(data),
      modesAndReasons(data.totals),
      modelUsage(usage),
      documents(data),
      unanswered(data.unansweredQuestions));
  }
  await load();
}
