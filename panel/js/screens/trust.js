// "Güven" (ADR-0050): verso-admin sees, in plain Turkish, where the data of this installation can and cannot go, from
// GET /v1/admin/trust, with the scripts that prove it from outside and a report to keep. The server enforces the role
// (403 for everyone else); the menu only hides the screen. Everything is built with el(): text, never markup.
import { api } from '../api.js';
import { CONFIG } from '../config.js';
import { icon } from '../icons.js';
import { el, fill } from '../render.js';
import { COMPLIANCE_DOCS, reportFileName, reportJson, reportMarkdown, trustModel, VERIFY_STEPS } from '../trust.js';
import { disclosure, emptyState, helpLine, MORE_LABEL } from '../ui.js';

/** Hands a text to the browser as a file (Blob + <a download>, no new CSP directive); the seams are injectable for tests. */
export function saveReport(text, name, type, { doc = document, urls = URL, wait = setTimeout } = {}) {
  const url = urls.createObjectURL(new Blob([text], { type }));
  const anchor = el('a', { href: url, download: name, hidden: true });
  doc.body.append(anchor);
  anchor.click();
  anchor.remove();
  wait(() => urls.revokeObjectURL(url), 1000);
}

function card(id, title, ...body) {
  return el('section', { class: 'card', 'aria-labelledby': id },
    el('div', { class: 'card-head' }, el('h2', { id }, title)),
    el('div', { class: 'card-body' }, ...body));
}

const TRUST_MORE = 'Bu ekran uygulamanın kendi bildirdiğidir; dışarı açık bağlantılar, denetim kaydı, bağımsız doğrulama betikleri ve uyum paketi "Ayrıntıları göster" altında.';

const list = (items) => el('ul', { class: 'plain-list' }, items.map((text) => el('li', {}, text)));

export async function renderTrust(ctx) {
  const { main, guarded } = ctx;
  const productName = CONFIG.deployment.productName;
  fill(main, el('div', { class: 'page-head' }, el('div', {}, el('h1', {}, 'Güven ve denetim'),
    helpLine('Verinizin nereye gidebildiği ve bunu nasıl doğrulayacağınız.', TRUST_MORE))),
  el('div', { class: 'skeleton block' }));
  const trust = await guarded(() => api.trust());
  if (!ctx.isCurrent()) return;
  const model = trustModel(trust, productName);
  if (!model) {
    fill(main, el('div', { class: 'page-head' }, el('div', {}, el('h1', {}, 'Güven ve denetim'))),
      emptyState('alert', 'Güven bilgisi alınamadı', 'Sürerse sistem yöneticinize başvurun.',
        el('button', { class: 'button', type: 'button', onclick: () => renderTrust(ctx) }, icon('refresh', 'sm'), 'Yeniden dene')));
    return;
  }
  const download = (extension) => {
    const at = new Date().toISOString();
    if (extension === 'json') saveReport(reportJson(trust, at), reportFileName('json', at), 'application/json;charset=utf-8');
    else saveReport(reportMarkdown(trust, { productName, at }), reportFileName('md', at), 'text/markdown;charset=utf-8');
  };

  fill(main,
    el('div', { class: 'page-head' }, el('div', {}, el('h1', {}, 'Güven ve denetim'),
      helpLine('Verinizin nereye gidebildiği ve bunu nasıl doğrulayacağınız.', TRUST_MORE)),
    el('div', { class: 'page-actions' },
      el('button', { class: 'button lg', type: 'button', onclick: () => download('md') }, icon('download'), 'Güven raporu indir'))),
    el('section', { class: `card mode-hero ${model.local ? 'local' : 'cloud'}`, 'aria-labelledby': 'trust-mode' },
      el('span', { class: 'big-icon' }, icon(model.local ? 'shield' : 'cloud', 'lg')),
      el('div', {}, el('h2', { id: 'trust-mode' }, model.hero.title),
        el('p', {}, model.hero.text),
        list(model.hero.models),
        model.modeChanges.length ? el('div', {}, el('h3', {}, model.modeChangesTitle), list(model.modeChanges)) : null)),
    // Sadeleştirme (ADR-0058): the mode and the report first; connections, audit, verification and compliance on demand.
    disclosure(MORE_LABEL, [
    el('div', { class: 'grid halves' },
      card('trust-paths', 'Dışarı açık bağlantılar',
        el('p', {}, model.pathsText),
        model.paths.length ? list(model.paths.map((p) => `${p.label}: ${p.detail}`)) : null,
        el('p', { class: 'stat-note' }, model.connectorText),
        el('p', { class: 'stat-note' }, `Kimlik sağlayıcılar: ${model.identity.join('; ') || '–'}`)),
      card('trust-audit', 'Denetim kaydı',
        el('p', {}, model.audit.text),
        el('p', { class: 'stat-note' }, model.audit.outbox),
        list(model.build),
        el('p', { class: 'stat-note' }, `Son yedek: ${model.backup.replace(/^Son yedek: /, '')}`))),
    card('trust-verify', 'Kurulum doğrulaması',
      el('p', {}, 'Bu ekran uygulamanın kendi bildirdiğidir. Bağımsız kanıt için kurulum sunucusunda şu betikleri çalıştırın.'),
      VERIFY_STEPS.map((step) => el('div', { class: 'verify-step' },
        el('h3', {}, step.script), el('pre', { class: 'code-block' }, el('code', {}, step.command)), el('p', {}, step.meaning)))),
    card('trust-compliance', 'Uyum paketi',
      el('p', {}, 'Depodaki docs/uyum klasöründe, kurumunuzun doldurup imzalayacağı belgeler bulunur:'),
      list(COMPLIANCE_DOCS.map((d) => `${d.title} (${d.file})`))),
    el('button', { class: 'button ghost', type: 'button', onclick: () => download('json') }, icon('download'), 'Raporu JSON olarak indir')],
    'trust-more'));
}
