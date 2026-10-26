// The answer memory (ADR-0049) without the DOM: the Turkish words of the card states and stale reasons, what an admin
// may do with a card, how a golden-set run reads, and the "uzman onaylı" badge and the "Onayla" action of an answer.
// The server decides every rule again; these only keep the panel from offering what it would refuse.

export const STATES = Object.freeze({
  DRAFT: { label: 'Taslak', tone: 'neutral', hint: 'Onay bekliyor; sorulara cevap olarak kullanılmaz.' },
  VERIFIED: { label: 'Onaylı', tone: 'ok', hint: 'Kaynaklarını okuyabilen herkese "uzman onaylı" cevap olarak gösterilir.' },
  STALE: { label: 'Eskimiş', tone: 'wait', hint: 'Kaynak değişti ya da onay süresi doldu; kullanılmaz.' },
  DISPUTED: { label: 'İtirazlı', tone: 'bad', hint: 'Birden fazla kullanıcı yanlış buldu; yeniden onaylanana kadar kullanılmaz.' },
  ARCHIVED: { label: 'Arşivde', tone: 'neutral', hint: 'Geri çekildi; kullanılmaz ve kontrol setinden çıktı.' },
});

/** The state filter of the list, in menu order ('' is every state). */
export const STATE_FILTERS = Object.freeze(['', 'VERIFIED', 'DRAFT', 'STALE', 'DISPUTED', 'ARCHIVED']);

export const STALE_REASONS = Object.freeze({
  SOURCE_DELETED: 'Alıntılanan belge silindi',
  SOURCE_REPLACED: 'Alıntılanan belgenin yeni sürümü geldi',
  SOURCE_MOVED: 'Alıntılanan belge başka bir bilgi alanına taşındı',
  SOURCE_CHANGED: 'Alıntılanan sayfanın metni değişti',
  EXPIRED: 'Onay süresi doldu',
  CONTRADICTION: 'Başka bir belgeyle çelişen bir parçaya dayanıyor',
});

/** What the event reasons of a card's history say. */
export const EVENT_REASONS = Object.freeze({
  CREATED: 'Oluşturuldu',
  VERIFICATION_ADDED: 'Bir onay eklendi (ikinci onay bekleniyor)',
  VERIFIED: 'Onaylandı',
  NEW_CYCLE: 'Yeniden onay için açıldı',
  DISPUTE_THRESHOLD: 'İtiraz eşiği aşıldı',
  ARCHIVED: 'Arşivlendi',
  ...STALE_REASONS,
});

export function stateLabel(state) {
  return STATES[state]?.label ?? 'Bilinmiyor';
}

export function stateTone(state) {
  return STATES[state]?.tone ?? 'neutral';
}

export function staleText(reason) {
  return reason ? STALE_REASONS[reason] ?? 'Kaynak değişti' : null;
}

export function eventText(reason) {
  return EVENT_REASONS[reason] ?? reason;
}

/**
 * Whether the server would accept this admin's verification now: a draft (not already verified by this admin), a
 * disputed card, or one that only expired. A card whose source changed needs a new answer, not a new approval.
 */
export function canVerify(card) {
  if (!card) return false;
  if (card.state === 'DRAFT') return !card.verifiedByYou;
  if (card.state === 'DISPUTED') return true;
  return card.state === 'STALE' && card.staleReason === 'EXPIRED';
}

export function canArchive(card) {
  return Boolean(card) && card.state !== 'ARCHIVED';
}

/** "1 / 2 onay" while the two-person rule waits. */
export function verifierText(card) {
  const have = Number.isFinite(card?.verifiers) ? card.verifiers : 0;
  const need = Number.isFinite(card?.requiredVerifiers) ? card.requiredVerifiers : 1;
  return `${have} / ${need} onay`;
}

/** A ratio as Turkish percent text ("%92,5"), or a dash when there was nothing to compare. */
export function percent(value) {
  if (!Number.isFinite(value)) return '–';
  return `%${(value * 100).toFixed(1).replace('.', ',').replace(/,0$/, '')}`;
}

/** One golden-set run as the screen shows it. */
export function runModel(run) {
  const status = { RUNNING: 'Sürüyor', SUCCEEDED: 'Tamamlandı', FAILED: 'Başarısız' }[run?.status] ?? 'Bilinmiyor';
  return {
    status,
    trigger: { MANUAL: 'Elle', HOOK: 'Kanca' }[run?.trigger] ?? 'Gece',
    recall: percent(run?.recallAtK),
    baseline: percent(run?.baselineRecall),
    drift: percent(run?.sourceDrift),
    alert: Boolean(run?.alert),
    counts: `${run?.found ?? 0} bulundu · ${run?.missed ?? 0} kaçtı · ${run?.sourceLost ?? 0} kaynak kayboldu · ${run?.skipped ?? 0} atlandı`,
  };
}

/** The badge of an answer served from the answer memory, or null for a model answer. */
export function memoryBadge(result) {
  if (!result?.memory?.cardId) return null;
  return { label: 'Uzman onaylı', title: 'Bu cevap, yetkili bir uzmanın onayladığı bilgi kartından geldi; model yazmadı.' };
}

/**
 * Whether the "Onayla" action fits this answer: only for an admin, only for a fresh sourced answer that was stored
 * (the card is made from the stored turn) and that did not itself come from a card.
 */
export function canApprove(result, isAdmin) {
  return Boolean(isAdmin) && result?.outcome === 'ANSWERED' && Array.isArray(result.citations) && result.citations.length > 0
    && Boolean(result.conversationId) && Boolean(result.turnId) && !result.memory;
}
