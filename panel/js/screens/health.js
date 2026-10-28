// "Bilgi sağlığı" (ADR-0052): the company diagnostics of the learning knowledge layer, verso-admin only. Findings with
// recommendations, clusters of questions that found no sourced answer, stale and unused documents, contradicting
// documents, the hook rules and their notifications, and "Raporu indir": the quarterly report the consulting team takes
// to the customer. Every aggregate comes from the server already k-anonymous (at least 5 people per cell); no person is
// ever named, question texts appear only under the installation's policy, and a document is named only when this admin
// may read it. Everything is built with el(): text, never markup (CSP, Trusted Types); no inline styles.
import { api } from '../api.js';
import { actionLabel, bellBadge, exportFileName, notificationItem, percentText, quarterRange, reasonLabel, recentRange,
  severityLabel, severityTone, sideLabel, trendLabel, trendTone, valuesText } from '../health.js';
import { icon } from '../icons.js';
import { el, fill, formatDate, plainName } from '../render.js';
import { disclosure, emptyState, formatNumber, helpLine, MORE_LABEL, openMenu, toast } from '../ui.js';

/** The ranges the screen offers; the previous quarter is the consulting deliverable. */
const RANGES = Object.freeze([
  { id: 'recent', label: 'Son 30 gün', range: () => recentRange() },
  { id: 'quarter', label: 'Bu çeyrek', range: () => quarterRange() },
  { id: 'previous', label: 'Geçen çeyrek', range: () => quarterRange(new Date(), true) },
]);

/** How often the bell asks for unread notifications (the hooks fire from nightly jobs and outbox events). */
const BELL_INTERVAL_MS = 5 * 60 * 1000;

/** The export as a file the browser saves (Blob + <a download>: same document, no new CSP directive; ADR-0024). */
export function saveFile(blob, name) {
  const url = URL.createObjectURL(blob);
  const anchor = el('a', { href: url, download: name, hidden: true });
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function renderHealth(ctx) {
  const { main, guarded } = ctx;
  let rangeId = 'recent';
  const body = el('div', { class: 'grid' }, el('div', { class: 'skeleton line' }));
  const hooksSlot = el('div', { class: 'card-body grid' }, el('div', { class: 'skeleton line' }));
  const notificationsSlot = el('div', { class: 'card-body grid' }, el('div', { class: 'skeleton line' }));
  const runsSlot = el('div', { class: 'card-body grid' }, el('div', { class: 'skeleton line' }));
  const current = () => RANGES.find((r) => r.id === rangeId).range();
  const select = el('select', { class: 'input', id: 'health-range', 'aria-label': 'Dönem', onchange: () => {
    rangeId = select.value;
    refresh();
  } }, RANGES.map((r) => el('option', { value: r.id }, r.label)));
  const runButton = el('button', { class: 'button secondary', type: 'button', onclick: async () => {
    runButton.disabled = true;
    const run = await guarded(() => api.runKnowledgeHealth());
    runButton.disabled = false;
    if (!run || !ctx.isCurrent()) return;
    toast(run.status === 'SUCCEEDED' ? 'Teşhis tamamlandı.' : 'Teşhis tamamlanamadı.', run.status === 'SUCCEEDED' ? 'ok' : 'bad');
    refresh();
  } }, icon('refresh'), 'Şimdi teşhis et');
  const download = (format) => async () => {
    const range = current();
    const blob = await guarded(() => api.exportKnowledgeHealth(range.from, range.to, format));
    if (!blob || !ctx.isCurrent()) return;
    saveFile(blob, exportFileName(range, format));
  };

  main.replaceChildren(
    el('div', { class: 'page-head' },
      el('div', {}, el('h1', {}, 'Bilgi sağlığı'),
        helpLine('Bilginizin nerede eksik, eski ya da çelişkili olduğunu öneriyle gösterir.',
          'Cevapsız soru kümeleri, eski ama çok kullanılan belgeler, hiç kullanılmayan ve birbirini tutmayan belgeler. Üç ayda bir '
          + 'indirilen rapor, danışmanlık ekibinin yapay zekâ entegrasyonu için yol haritasıdır. Hiçbir toplam 5 kişiden azına '
          + 'dayanmaz; kişi bazında döküm yoktur.')),
      el('div', { class: 'page-actions' },
        el('label', { class: 'sr-only', for: 'health-range' }, 'Dönem'), select, runButton,
        el('button', { class: 'button', type: 'button', onclick: download('MARKDOWN') }, icon('download'), 'Raporu indir'))),
    body,
    // Sadeleştirme (ADR-0058): the hooks, notifications and runs are settings and history, opened on demand.
    disclosure('Kancalar, bildirimler ve teşhis çalışmaları', [
    el('section', { class: 'card', 'aria-labelledby': 'health-hooks' },
      el('div', { class: 'card-head' }, el('h2', { id: 'health-hooks' }, 'Kancalar')),
      el('p', { class: 'card-body muted' }, 'Kancalar yalnız bildirir, incelemeye işaretler, bir kartı kullanımdan çıkarır ya da '
        + 'kontrolü çalıştırır. Onaylamak, silmek ve erişim değiştirmek her zaman bir insanın kararıdır.'),
      hooksSlot),
    el('section', { class: 'card', 'aria-labelledby': 'health-notifications' },
      el('div', { class: 'card-head' }, el('h2', { id: 'health-notifications' }, 'Bildirimler')), notificationsSlot),
    el('section', { class: 'card', 'aria-labelledby': 'health-runs' },
      el('div', { class: 'card-head' }, el('h2', { id: 'health-runs' }, 'Teşhis çalışmaları')), runsSlot),
    el('button', { class: 'button ghost', type: 'button', onclick: download('JSON') }, icon('download'), 'Raporu JSON olarak indir')],
    'health-settings'));

  function pill(text, tone) {
    return el('span', { class: `pill ${tone}` }, text);
  }

  function figure(value, label) {
    return el('div', { class: 'card stat kpi' }, el('span', { class: 'kpi-value' }, value), el('span', { class: 'stat-label' }, label));
  }

  function section(id, title, ...children) {
    return el('section', { class: 'card', 'aria-labelledby': id },
      el('div', { class: 'card-head' }, el('h2', { id }, title)), el('div', { class: 'card-body grid' }, ...children));
  }

  function findingsView(report) {
    return section('health-findings', 'Bulgular ve öneriler', (report.findings ?? []).map((f) => el('article', { class: 'notice' },
      icon(f.severity === 'INFO' ? 'info' : 'alert', 'sm'),
      el('div', { class: 'grow' },
        el('strong', {}, plainName(f.title), ' '), pill(severityLabel(f.severity), severityTone(f.severity)),
        el('p', {}, plainName(f.finding)),
        el('p', { class: 'muted' }, `Öneri: ${plainName(f.recommendation)}`)))));
  }

  function clustersView(report) {
    const clusters = report.clusters ?? [];
    const note = report.questionTextShown ? null
      : el('p', { class: 'muted' }, 'Soru metinleri bu kurulumda gösterilmiyor; kümeler ortak sözcükleri ve sayılarıyla görünür.');
    return section('health-clusters', 'Cevapsız ve belirsiz soru kümeleri', note, clusters.length
      ? el('ul', { class: 'connectors', 'aria-label': 'Soru kümeleri' }, clusters.map((c) => el('li', { class: 'connector' },
        el('span', { class: 'connector-icon' }, icon('chat', 'sm')),
        el('div', { class: 'grow' },
          el('strong', {}, c.keywords?.length ? c.keywords.map(plainName).join(', ') : 'Ortak sözcük yok'),
          el('span', { class: 'sub' }, `${formatNumber(c.turns)} soru · ${formatNumber(c.askers)} kişi`
            + (Number.isFinite(c.previousTurns) ? ` · önceki ${formatNumber(c.previousTurns)}` : '')),
          ...(c.representativeQuestions ?? []).map((q) => el('span', { class: 'sub' }, `“${plainName(q)}”`))),
        pill(trendLabel(c.trend), trendTone(c.trend)))))
      : emptyState('chat', 'Eşiği geçen küme yok', 'En az 5 farklı kişinin cevapsız kaldığı bir konu olunca burada görünür.'));
  }

  function staleView(report) {
    const docs = report.staleDocuments ?? [];
    return section('health-stale', 'Eski ama sık kullanılan belgeler', docs.length
      ? el('ul', { class: 'connectors', 'aria-label': 'Eski belgeler' }, docs.map((d) => el('li', { class: 'connector' },
        el('span', { class: 'connector-icon' }, icon('file', 'sm')),
        el('div', { class: 'grow' },
          el('strong', {}, d.fileName ? plainName(d.fileName) : `Belge ${String(d.documentId).slice(0, 8)}… (okuma yetkiniz yok)`),
          el('span', { class: 'sub' }, `${plainName(d.collectionName ?? '')} · son değişiklik ${formatDate(d.lastChanged)} · `
            + `${formatNumber(d.citations)} atıf · ${formatNumber(d.askers)} kişi`
            + (d.staleCards ? ` · ${formatNumber(d.staleCards)} eskiyen kart` : ''))))))
      : emptyState('file', 'Eski belge yok', 'Uzun süredir değişmeyen ama sık kullanılan belge bulunmadı.'));
  }

  function coverageView(report) {
    const rows = report.coverage ?? [];
    return section('health-coverage', 'Kapsama', el('p', { class: 'muted' },
      `Hiç kullanılmayan paylaşılan belge: ${formatNumber(report.neverCitedDocuments ?? 0)}`),
    rows.length ? el('div', { class: 'table-wrap' }, el('table', { class: 'table' },
      el('thead', {}, el('tr', {}, ['Bilgi alanı', 'Belge', 'Kaynak olan', 'Kapsama', 'Hiç kullanılmayan'].map((h) => el('th', {}, h)))),
      el('tbody', {}, rows.map((r) => el('tr', {},
        el('td', {}, plainName(r.collectionName ?? '–')), el('td', {}, formatNumber(r.documents)),
        el('td', {}, Number.isFinite(r.citedInWindow) ? formatNumber(r.citedInWindow) : 'gizli'),
        el('td', {}, percentText(r.coverage)), el('td', {}, formatNumber(r.neverCited))))))) : null);
  }

  function contradictionsView(report) {
    const rows = report.contradictions ?? [];
    return section('health-contradictions', 'Çelişen belgeler', rows.length
      ? el('ul', { class: 'connectors', 'aria-label': 'Çelişki adayları' }, rows.map((c) => el('li', { class: 'connector' },
        el('span', { class: 'connector-icon' }, icon('alert', 'sm')),
        el('div', { class: 'grow' },
          el('strong', {}, `${plainName(sideLabel(c.first))} ↔ ${plainName(sideLabel(c.second))}`),
          el('span', { class: 'sub' }, plainName(valuesText(c)))))))
      : emptyState('checkCircle', 'Çelişki bulunmadı', 'Aynı konuda farklı sayı ya da kod veren belge çifti yok.'));
  }

  function cardsView(report) {
    const c = report.cards ?? {};
    const reasons = report.downRatingReasons ?? [];
    return section('health-cards', 'Onaylı bilgiler ve geri bildirim',
      el('p', {}, `${formatNumber(c.verified ?? 0)} onaylı · ${formatNumber(c.stale ?? 0)} eskimiş · ${formatNumber(c.disputed ?? 0)} `
        + `itirazlı · ${formatNumber(c.expiringSoon ?? 0)} onayı yakında dolacak`),
      reasons.length ? el('ul', { class: 'steps-list', 'aria-label': 'Olumsuz geri bildirim' }, reasons.map((r) => el('li', {},
        `${plainName(r.collectionName ?? '–')}: ${reasonLabel(r.reason)} · ${formatNumber(r.ratings)} cevap · ${formatNumber(r.askers)} kişi`)))
        : el('p', { class: 'muted' }, 'Eşiği geçen olumsuz geri bildirim yok.'));
  }

  async function refresh() {
    const range = current();
    const report = await guarded(() => api.knowledgeHealth(range.from, range.to));
    if (!report || !ctx.isCurrent()) return;
    const run = report.run;
    fill(body,
      el('p', { class: 'muted' }, run
        ? `Teşhis penceresi ${run.windowFrom} – ${run.windowTo} · ${formatNumber(run.questions)} belirsiz soru · `
          + `${formatNumber(report.openFlags ?? 0)} işaret inceleme bekliyor`
        : 'Bu dönemde tamamlanmış teşhis yok; "Şimdi teşhis et" ile başlatabilirsiniz.'),
      // Sadeleştirme (ADR-0058): three counts and the findings first; the lists behind "Ayrıntıları göster".
      el('div', { class: 'grid kpis health-headline' },
        figure(formatNumber((report.clusters ?? []).length), 'cevapsız soru kümesi'),
        figure(formatNumber((report.staleDocuments ?? []).length), 'eski ama kullanılan belge'),
        figure(formatNumber((report.contradictions ?? []).length), 'belgeler arası çelişki')),
      findingsView(report),
      disclosure(MORE_LABEL, [clustersView(report), staleView(report), coverageView(report), contradictionsView(report),
        cardsView(report)], 'health-more'));
    refreshNotifications();
    refreshRuns();
  }

  async function refreshHooks() {
    const rules = await guarded(() => api.listHookRules());
    if (!rules || !ctx.isCurrent()) return;
    fill(hooksSlot, el('ul', { class: 'connectors', 'aria-label': 'Kanca kuralları' }, rules.map((rule) => {
      const box = el('input', { type: 'checkbox', id: `hook-${rule.key}`, checked: rule.enabled, onchange: async () => {
        box.disabled = true;
        const done = await guarded(() => api.setHookRule(rule.key, box.checked));
        box.disabled = false;
        if (!done) {
          box.checked = !box.checked;
          return;
        }
        toast(done.enabled ? 'Kural açıldı.' : 'Kural kapatıldı.', 'ok');
      } });
      return el('li', { class: 'connector' }, box,
        el('label', { class: 'grow', for: `hook-${rule.key}` },
          el('strong', {}, plainName(rule.description)),
          el('span', { class: 'sub' }, `${actionLabel(rule.action)} · varsayılan ${rule.defaultEnabled ? 'açık' : 'kapalı'}`)));
    })));
  }

  async function refreshNotifications() {
    const page = await guarded(() => api.listNotifications(false, 0, 20));
    if (!page || !ctx.isCurrent()) return;
    const items = page.data ?? [];
    fill(notificationsSlot, items.length ? el('ul', { class: 'connectors', 'aria-label': 'Bildirimler' }, items.map((n) => {
      const view = notificationItem(n);
      return el('li', { class: 'connector' },
        el('span', { class: 'connector-icon' }, icon(view.glyph, 'sm')),
        el('div', { class: 'grow' }, el('strong', {}, plainName(view.label)), el('span', { class: 'sub' }, formatDate(n.createdAt))),
        view.unread ? el('button', { class: 'button ghost sm', type: 'button', onclick: async () => {
          if (await guarded(() => api.readNotification(n.id))) refreshNotifications();
        } }, icon('check', 'sm'), 'Okundu') : pill('Okundu', 'neutral'));
    })) : emptyState('bell', 'Bildirim yok', 'Kancalar bir şey bulduğunda burada görünür.'));
  }

  async function refreshRuns() {
    const page = await guarded(() => api.listKnowledgeHealthRuns(0, 5));
    if (!page || !ctx.isCurrent()) return;
    const items = page.data ?? [];
    fill(runsSlot, items.length ? el('ul', { class: 'steps-list', 'aria-label': 'Son teşhisler' }, items.map((r) => el('li', {},
      `${formatDate(r.startedAt)} · ${r.trigger === 'MANUAL' ? 'Elle' : 'Gece'} · `
      + `${{ RUNNING: 'Sürüyor', SUCCEEDED: 'Tamamlandı', FAILED: 'Başarısız' }[r.status] ?? 'Bilinmiyor'} · `
      + `${formatNumber(r.questions)} soru · ${formatNumber(r.clusters)} küme`)))
      : emptyState('pulse', 'Henüz teşhis yok', 'İlk teşhis bu gece çalışır.'));
  }

  refresh();
  refreshHooks();
  return () => {};
}

/**
 * The bell of the admins (ADR-0052): the unread hook notifications, as a menu. Two buttons (desktop header and the
 * phone bar) share one state; the list is read when the bell opens and every few minutes. Returns the buttons and a
 * function that stops the timer.
 */
export function notificationBell({ guarded, navigate }) {
  let unread = 0;
  const buttons = [0, 1].map(() => el('button', { class: 'icon-button bell', type: 'button', 'aria-haspopup': 'menu',
    'aria-expanded': 'false', 'aria-label': 'Bildirimler' }, icon('bell'), el('span', { class: 'bell-count', hidden: true })));
  const paint = () => {
    const badge = bellBadge(unread);
    for (const button of buttons) {
      button.setAttribute('aria-label', badge.label);
      const count = button.querySelector('.bell-count');
      count.textContent = badge.text;
      count.hidden = !badge.show;
    }
  };
  const load = async () => {
    const page = await guarded(() => api.listNotifications(true, 0, 10));
    if (!page) return null;
    unread = page.page?.totalElements ?? (page.data ?? []).length;
    paint();
    return page.data ?? [];
  };
  for (const button of buttons) {
    button.addEventListener('click', async () => {
      const items = await load();
      if (items === null) return;
      openMenu(button, [
        { heading: 'Bildirimler', sub: unread ? `${unread} okunmamış` : 'Okunmamış bildirim yok' },
        ...items.map((n) => {
          const view = notificationItem(n);
          return { label: view.label, glyph: view.glyph, onSelect: async () => {
            await guarded(() => api.readNotification(n.id));
            load();
            navigate('health');
          } };
        }),
        'separator',
        { label: 'Bilgi sağlığını aç', glyph: 'pulse', onSelect: () => navigate('health') },
      ], { placement: 'below' });
    });
  }
  load();
  const timer = setInterval(load, BELL_INTERVAL_MS);
  return { buttons, stop: () => clearInterval(timer) };
}
