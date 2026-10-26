// Procedures / skills (ADR-0053) without the DOM: the Turkish words of the states, stale reasons and history, what an
// admin may do with a skill, how a version is edited, the download name of the SKILL.md bundle, and the "Bu bir
// prosedür" box above an answer. The server decides every rule again; these only keep the panel from offering what it
// would refuse.

export const STATES = Object.freeze({
  DRAFT: { label: 'Taslak', tone: 'neutral', hint: 'Onay bekliyor; hiçbir soruda, MCP\'de ya da indirmede kullanılmaz.' },
  APPROVED: { label: 'Onaylı', tone: 'ok', hint: 'Kaynaklarının hepsini okuyabilen herkese prosedür olarak gösterilir.' },
  STALE: { label: 'Eskimiş', tone: 'wait', hint: 'Bir kaynağı değişti, silindi ya da taşındı; kullanılmaz, yeni taslak gerekir.' },
  ARCHIVED: { label: 'Arşivde', tone: 'neutral', hint: 'Geri çekildi; kullanılmaz.' },
});

/** The state filter of the list, in menu order ('' is every state). */
export const STATE_FILTERS = Object.freeze(['', 'APPROVED', 'DRAFT', 'STALE', 'ARCHIVED']);

export const STALE_REASONS = Object.freeze({
  SOURCE_DELETED: 'Kaynak belge silindi',
  SOURCE_REPLACED: 'Kaynak belgenin yeni sürümü geldi',
  SOURCE_MOVED: 'Kaynak belge başka bir bilgi alanına taşındı',
  SOURCE_CHANGED: 'Kaynak sayfanın metni değişti',
});

export const EVENT_REASONS = Object.freeze({
  DRAFTED: 'Taslak oluşturuldu',
  EDITED: 'Yeni sürüm (yeniden onay gerekir)',
  APPROVAL_ADDED: 'Bir onay eklendi (ikinci onay bekleniyor)',
  APPROVED: 'Onaylandı',
  ARCHIVED: 'Arşivlendi',
  ...STALE_REASONS,
});

/** At most this many steps, triggers and golden cases (the server's own limits). */
export const LIMITS = Object.freeze({ steps: 12, triggers: 10, goldenMin: 2, goldenMax: 5, name: 64, description: 1024 });
export const NAME_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;

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

/** Whether the server would accept this admin's approval now (a draft, not yet approved by them, complete). */
export function canApprove(skill) {
  if (!skill || skill.state !== 'DRAFT' || skill.approvedByYou) return false;
  return approvalProblems(skill).length === 0;
}

/** What still blocks the approval, as the admin should read it; empty when nothing does. */
export function approvalProblems(skill) {
  if (!skill || skill.readable === false) return ['Kaynaklarından en az birini okuma yetkiniz yok.'];
  const problems = [];
  const golden = skill.goldenCases?.length ?? 0;
  if (golden < LIMITS.goldenMin || golden > LIMITS.goldenMax) {
    problems.push(`Onay için ${LIMITS.goldenMin}-${LIMITS.goldenMax} kontrol sorusu gerekir (şu an ${golden}).`);
  }
  if (!skill.triggers?.length) problems.push('En az bir örnek soru gerekir.');
  if ((skill.steps ?? []).some((s) => s.grounded === false)) {
    problems.push('Bir adım, kaynaklarında olmayan bir sayı ya da kod içeriyor.');
  }
  return problems;
}

export function canEdit(skill) {
  return Boolean(skill) && skill.readable !== false && (skill.state === 'DRAFT' || skill.state === 'APPROVED');
}

export function canArchive(skill) {
  return Boolean(skill) && skill.state !== 'ARCHIVED';
}

export function canExport(skill) {
  return Boolean(skill) && skill.state === 'APPROVED' && skill.readable !== false;
}

/** "1 / 2 onay" while the two-person rule waits. */
export function approvalText(skill) {
  const have = Number.isFinite(skill?.approvals) ? skill.approvals : 0;
  const need = Number.isFinite(skill?.requiredApprovals) ? skill.requiredApprovals : 1;
  return `${have} / ${need} onay`;
}

/** The download name of the bundle: the skill's own name (already spec-safe), never anything else. */
export function exportFileName(name) {
  return NAME_PATTERN.test(name ?? '') && name.length <= LIMITS.name ? `${name}.zip` : 'prosedur.zip';
}

/**
 * The PUT body of a new version from the edited form: each step keeps the position it came from (its sources), texts
 * are trimmed to one line, empty lines dropped, the limits applied.
 */
export function versionBody(form) {
  const line = (text) => String(text ?? '').replace(/[\u0000-\u001f\u007f-\u009f​-‏‪-‮⁠-⁤﻿]/g, ' ')
    .replace(/\s+/g, ' ').trim();
  return {
    name: line(form.name).slice(0, LIMITS.name),
    description: line(form.description).slice(0, LIMITS.description),
    steps: (form.steps ?? []).map((s) => ({ from: Math.trunc(Number(s.from)), instruction: line(s.instruction) }))
      .filter((s) => Number.isInteger(s.from) && s.from >= 1 && s.instruction).slice(0, LIMITS.steps),
    triggers: [...new Set((form.triggers ?? []).map(line).filter(Boolean))].slice(0, LIMITS.triggers),
    goldenCases: (form.goldenCases ?? []).map((g) => ({ question: line(g.question), documentId: g.documentId,
      page: Math.trunc(Number(g.page)), ...(line(g.fact) ? { fact: line(g.fact) } : {}) }))
      .filter((g) => g.question && g.documentId && g.page >= 1).slice(0, LIMITS.goldenMax),
  };
}

/** The pages a golden case may expect: the skill's own source pages, each once. */
export function sourcePages(skill) {
  const seen = new Map();
  for (const step of skill?.steps ?? []) {
    for (const source of step.sources ?? []) {
      const key = `${source.documentId}/${source.page}`;
      if (!seen.has(key)) seen.set(key, source);
    }
  }
  return [...seen.values()];
}

/** A source as the screens name it: the file name when the reader may read it, else a short id. */
export function sourceLabel(source) {
  const where = `${source?.unit === 'SECTION' ? 'bölüm' : 's.'} ${source?.page ?? '?'}`;
  const name = source?.fileName ?? `Belge ${String(source?.documentId ?? '').slice(0, 8)}…`;
  return `${name}, ${where}`;
}

/** One change of the version diff in Turkish. */
export function diffText(change) {
  const field = { name: 'Ad', description: 'Açıklama', step: 'Adım', trigger: 'Örnek soru', goldenCase: 'Kontrol sorusu' }[change?.field]
    ?? change?.field;
  const kind = { ADDED: 'eklendi', REMOVED: 'çıkarıldı', CHANGED: 'değişti' }[change?.kind] ?? change?.kind;
  return `${field}${change?.position ? ` ${change.position}` : ''} ${kind}`;
}

/** A cluster suggestion as the screen lists it: k-anonymous keywords and counts only. */
export function suggestionText(suggestion) {
  const words = (suggestion?.keywords ?? []).join(', ') || 'anahtar sözcük yok';
  return `${words} · ${suggestion?.askers ?? 0} kişi · ${suggestion?.verifiedCards ?? 0} onaylı kart`;
}

/** The "Bu bir prosedür" box of an answer, or null when no approved skill matched (ADR-0053). */
export function procedureModel(result) {
  const procedure = result?.procedure;
  if (!procedure?.skillId || !Array.isArray(procedure.steps) || !procedure.steps.length) return null;
  return {
    title: `Bu bir prosedür: ${procedure.name}`,
    description: procedure.description ?? '',
    steps: procedure.steps.map((step) => ({ text: step.instruction,
      sources: (step.sources ?? []).map((source) => sourceLabel(source)) })),
  };
}
