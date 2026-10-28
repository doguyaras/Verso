// "Onaylı bilgiler" (ADR-0049): the answer memory of the installation. verso-admin sees the expert-verified answers,
// their state (why a card is stale or disputed), verifies or archives them, and runs the golden-set check that tells
// whether retrieval still finds what was verified. Cards are made from the admin's own answers with "Onayla" on the
// "Soru sor" screen. Dispute counts are per day only: no user is ever named (works councils). The server enforces the
// role (403 for everyone else). Everything is built with el(): text, never markup (CSP, Trusted Types).
import { api } from '../api.js';
import { icon } from '../icons.js';
import { canArchive, canVerify, eventText, runModel, staleText, STATE_FILTERS, STATES, stateLabel, stateTone,
  verifierText } from '../knowledge.js';
import { el, fill, formatDate, plainName } from '../render.js';
import { confirmDialog, emptyState, formatNumber, helpLine, toast } from '../ui.js';

/** ADR-0028: an admin without a grant on every source sees the card's state, not its text (the server sends none). */
export const HIDDEN = 'Kaynaklarından en az birini okuma yetkiniz yok; metin gösterilmiyor.';

export function renderKnowledge(ctx) {
  const { main, guarded } = ctx;
  let state = '';
  const list = el('div', { class: 'card-body grid' }, el('div', { class: 'skeleton line' }));
  const runs = el('div', { class: 'card-body grid' }, el('div', { class: 'skeleton line' }));
  const filter = el('select', { class: 'input', id: 'knowledge-state', 'aria-label': 'Duruma göre süz', onchange: () => {
    state = filter.value;
    refresh();
  } }, STATE_FILTERS.map((s) => el('option', { value: s }, s ? stateLabel(s) : 'Bütün durumlar')));
  const runButton = el('button', { class: 'button secondary', type: 'button', onclick: async () => {
    runButton.disabled = true;
    const run = await guarded(() => api.runKnowledgeEval());
    runButton.disabled = false;
    if (!run || !ctx.isCurrent()) return;
    toast(run.alert ? 'Kontrol bitti: dikkat isteyen bir düşüş var.' : 'Kontrol bitti.', run.alert ? 'bad' : 'ok');
    refreshRuns();
  } }, icon('refresh'), 'Şimdi kontrol et');

  main.replaceChildren(
    el('div', { class: 'page-head' },
      el('div', {}, el('h1', {}, 'Onaylı bilgiler'),
        helpLine('Uzmanlarınızın onayladığı cevaplar; aynı soru yeniden gelince önce bunlar gösterilir.', 'Uzmanlarınızın onayladığı cevaplar şirketinizin öğrenen bilgi katmanıdır. Aynı soru yeniden sorulduğunda, '
          + 'kaynak belgelerin hepsini okuyabilen kişiye model yerine onaylı cevap "uzman onaylı" işaretiyle gösterilir. '
          + 'Kaynak değişince, onay süresi dolunca ya da birkaç kullanıcı yanlış bulunca kart kendiliğinden kullanımdan çıkar; '
          + 'yeniden onaylamak ve arşivlemek her zaman bir insanın kararıdır.')),
      el('div', { class: 'page-actions' },
        el('button', { class: 'button secondary', type: 'button', onclick: () => refresh() }, icon('refresh'), 'Yenile'))),
    el('section', { class: 'card', 'aria-labelledby': 'knowledge-runs' },
      el('div', { class: 'card-head' }, el('h2', { id: 'knowledge-runs' }, 'Kontroller'), el('span', { class: 'spacer' }), runButton),
      el('p', { class: 'card-body muted' }, 'Her gece onaylı her soru yeniden aranır: alıntılanan parça hâlâ ilk sonuçlarda mı, '
        + 'kaynak metin değişti mi? Model çağrılmaz. Düşüş olursa bir uyarı açılır.'),
      runs),
    el('section', { class: 'card', 'aria-labelledby': 'knowledge-cards' },
      el('div', { class: 'card-head' }, el('h2', { id: 'knowledge-cards' }, 'Bilgi kartları'), el('span', { class: 'spacer' }),
        el('label', { class: 'sr-only', for: 'knowledge-state' }, 'Durum'), filter),
      list));

  function statePill(card) {
    return el('span', { class: `pill ${stateTone(card.state)}`, title: STATES[card.state]?.hint ?? '' }, stateLabel(card.state));
  }

  async function details(card, slot) {
    const full = await guarded(() => api.getKnowledgeCard(card.id));
    if (!full || !ctx.isCurrent()) return;
    fill(slot,
      el('p', {}, full.readable === false ? HIDDEN : plainName(full.answer)),
      el('ul', { class: 'connectors', 'aria-label': 'Kaynaklar' }, (full.citations ?? []).map((c) => el('li', { class: 'connector' },
        el('span', { class: 'connector-icon' }, icon('file', 'sm')),
        el('div', { class: 'grow' }, el('strong', {}, `[${c.number}] ${c.documentDeleted ? 'Silinmiş belge'
          : c.documentAccessible ? plainName(c.fileName) : 'Erişiminiz olmayan belge'}`),
        el('span', { class: 'sub' }, `${c.unit === 'SECTION' ? 'Bölüm' : 'Sayfa'} ${c.page}`))))),
      full.keyFacts?.length ? el('p', { class: 'muted' }, `Ana bilgiler: ${full.keyFacts.map(plainName).join(' · ')}`) : null,
      full.disputesByDay?.length ? el('p', { class: 'muted' }, 'İtirazlar: '
        + full.disputesByDay.map((d) => `${d.day}: ${formatNumber(d.disputes)}`).join(', ')) : null,
      el('ol', { class: 'steps-list', 'aria-label': 'Geçmiş' }, (full.events ?? []).map((e) =>
        el('li', {}, `${formatDate(e.at)} · ${eventText(e.reason)}`))));
  }

  function cardView(card) {
    const slot = el('div', {});
    const question = card.readable === false ? HIDDEN : plainName(card.question);
    const stale = staleText(card.staleReason);
    return el('article', { class: 'card' },
      el('div', { class: 'card-head' }, el('h2', {}, question), el('span', { class: 'spacer' }), statePill(card)),
      el('div', { class: 'card-body' },
        el('p', { class: 'muted' }, [verifierText(card),
          card.verifiedAt ? `onay ${formatDate(card.verifiedAt)}` : null,
          card.expiresAt ? `geçerlilik ${formatDate(card.expiresAt)}` : null,
          card.disputes ? `${formatNumber(card.disputes)} itiraz` : null].filter(Boolean).join(' · ')),
        stale ? el('div', { class: 'notice wait' }, icon('alert', 'sm'), el('div', { class: 'grow' }, stale)) : null,
        el('div', { class: 'page-actions' },
          el('button', { class: 'button ghost sm', type: 'button', onclick: (event) => {
            event.currentTarget.remove();
            details(card, slot);
          } }, icon('info', 'sm'), 'Ayrıntılar'),
          canVerify(card) ? el('button', { class: 'button sm', type: 'button', onclick: async () => {
            const done = await guarded(() => api.verifyKnowledgeCard(card.id));
            if (!done) return;
            toast(done.state === 'VERIFIED' ? 'Onaylandı.' : 'Onayınız kaydedildi; ikinci bir yöneticinin onayı bekleniyor.', 'ok');
            refresh();
          } }, icon('check', 'sm'), 'Onayla') : null,
          canArchive(card) ? el('button', { class: 'button ghost sm', type: 'button', onclick: async () => {
            const ok = await confirmDialog({ title: 'Kart arşivlensin mi?', confirm: 'Arşivle',
              message: 'Bu onaylı cevap artık hiçbir soruya gösterilmez ve gece kontrolünden çıkar. Aynı soru için yeni bir kart '
                + 'oluşturabilirsiniz.' });
            if (!ok) return;
            const done = await guarded(() => api.archiveKnowledgeCard(card.id));
            if (!done) return;
            toast('Kart arşivlendi.', 'ok');
            refresh();
          } }, icon('trash', 'sm'), 'Arşivle') : null),
        slot));
  }

  async function refreshRuns() {
    const page = await guarded(() => api.listKnowledgeRuns(0, 5));
    if (!page || !ctx.isCurrent()) return;
    const items = page.data ?? [];
    fill(runs, items.length ? el('ul', { class: 'connectors', 'aria-label': 'Son kontroller' }, items.map((run) => {
      const m = runModel(run);
      return el('li', { class: 'connector' },
        el('span', { class: 'connector-icon' }, icon(m.alert ? 'alert' : 'chart', 'sm')),
        el('div', { class: 'grow' },
          el('strong', {}, `${formatDate(run.startedAt)} · ${m.trigger} · ${m.status}`),
          el('span', { class: 'sub' }, `İsabet ${m.recall} (önceki ${m.baseline}) · kaynak kayması ${m.drift} · ${m.counts}`)),
        m.alert ? el('span', { class: 'pill bad' }, 'Uyarı') : null);
    })) : emptyState('chart', 'Henüz kontrol yok', 'İlk kontrol bu gece çalışır; hemen görmek için "Şimdi kontrol et".'));
  }

  async function refresh() {
    const page = await guarded(() => api.listKnowledgeCards(state, 0, 50));
    if (!page || !ctx.isCurrent()) return;
    const cards = page.data ?? [];
    fill(list, cards.length ? cards.map(cardView) : emptyState('checkCircle', 'Bu durumda kart yok',
      'Kart oluşturmak için "Soru sor" ekranında kaynaklı bir cevabın altındaki "Onayla" düğmesini kullanın.'));
  }

  refresh();
  refreshRuns();
  return () => {};
}
