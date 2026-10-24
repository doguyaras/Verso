// Setup status (ADR-0018): what the team that installs Verso at a company has done and what comes next, shown to the
// client. The phases and the pilot group come from the installation's configuration (config.js); the other steps are
// read from the running system (sign-in, model mode, searchable documents, a cited answer in this tab). Connectors are
// listed as planned: nothing on this screen claims more than the system does today.
import { api } from '../api.js';
import { CONFIG } from '../config.js';
import { icon } from '../icons.js';
import { el, fill } from '../render.js';
import { conversation } from '../store.js';

export const PHASES = Object.freeze([
  { id: 'discovery', title: 'Keşif', text: 'İhtiyaçlar, belgeler ve kimlik sistemi incelenir.' },
  { id: 'pilot', title: 'Pilot', text: 'Bir iki birim, seçili belgeler ve şirket girişi ile denenir.' },
  { id: 'rollout', title: 'Yaygınlaştırma', text: 'Bütün kurum ve yeni bilgi kaynakları eklenir.' },
  { id: 'integrations', title: 'Entegrasyonlar', text: 'Dosya paylaşımı, talep yönetimi ve ERP bağlantıları.' },
]);

export const CONNECTORS = Object.freeze([
  { title: 'SharePoint ve dosya paylaşımları', text: 'Klasörlerdeki belgeler kendiliğinden eklenir.' },
  { title: 'Talep yönetimi', text: 'Destek kayıtları ve çözümleri sorulabilir olur.' },
  { title: 'ERP', text: 'Prosedür ve süreç kayıtları cevaplara katılır.' },
]);

/** The steps of the checklist, from what the system reports; `null` means "not known yet". */
export function setupSteps({ signedIn, mode, ready, cited, pilotGroup }) {
  return [
    { done: signedIn, label: 'Şirket hesabıyla giriş (OIDC)', note: 'Bu oturum kurumunuzun kimlik sistemiyle açıldı.' },
    { done: mode != null, label: mode === 'cloud' ? 'Model modu: bulut' : 'Model modu: yerel',
      note: mode === 'cloud' ? 'Sorular ve ilgili pasajlar seçilen sağlayıcıya gider.' : 'Sorular, belgeler ve cevaplar bu sunucudan çıkmaz.' },
    { done: ready > 0, label: 'Belgeler sorulabilir durumda', note: ready > 0 ? `${ready} belge hazır.` : 'Henüz hazır belge yok.' },
    { done: cited, label: 'Kaynaklı ilk cevap', note: cited ? 'Bu sekmede kaynaklı bir cevap alındı.' : 'Bu sekmede henüz soru sorulmadı.' },
    { done: pilotGroup, label: 'Pilot kullanıcı grubu', note: pilotGroup ? 'Kurulum ekibiyle tanımlandı.' : 'Kurulum ekibiyle birlikte tanımlanır.' },
  ];
}

export async function renderSetup(ctx) {
  const { main, guarded } = ctx;
  const deployment = CONFIG.deployment;
  const current = Math.max(0, PHASES.findIndex((p) => p.id === deployment.phase));
  // The product's name without a Turkish case ending: the suffix of an installation's name cannot be known.
  const intro = `Kurumunuzdaki ${deployment.productName} kurulumunda nerede olduğumuz, neyin hazır olduğu ve sırada ne olduğu.`;
  fill(main, el('div', { class: 'page-head' }, el('div', {}, el('h1', {}, 'Kurulum durumu'),
    el('p', {}, intro))),
  el('div', { class: 'skeleton block' }));
  const [docs, info] = await Promise.all([guarded(() => api.listAllDocuments()), guarded(() => api.info())]);
  if (!ctx.isCurrent()) return;
  const steps = setupSteps({
    signedIn: true,
    mode: info?.mode ?? null,
    ready: (docs ?? []).filter((d) => d.status === 'READY').length,
    cited: conversation.some((entry) => entry.result?.citations?.length > 0),
    pilotGroup: Boolean(deployment.pilotGroup),
  });
  const done = steps.filter((s) => s.done).length;

  fill(main,
    el('div', { class: 'page-head' }, el('div', {}, el('h1', {}, 'Kurulum durumu'),
      el('p', {}, deployment.client
        ? `${deployment.client} için ${deployment.productName} kurulumunun aşamaları ve kontrol listesi.`
        : intro))),
    el('section', { class: 'card', 'aria-labelledby': 'phases-title' },
      el('div', { class: 'card-head' }, el('h2', { id: 'phases-title' }, 'Aşamalar')),
      el('ol', { class: 'phases' }, PHASES.map((phase, i) => el('li', {
        class: i < current ? 'done' : i === current ? 'now' : '', 'aria-current': i === current ? 'step' : null },
      el('span', { class: 'phase-mark' }, i < current ? icon('check', 'sm') : String(i + 1)),
      el('strong', {}, phase.title),
      el('span', { class: 'phase-state' }, i < current ? 'Tamamlandı' : i === current ? 'Sürüyor' : 'Planlandı'),
      el('span', { class: 'phase-text' }, phase.text))))),
    el('div', { class: 'grid split' },
      el('section', { class: 'card', 'aria-labelledby': 'checklist-title' },
        el('div', { class: 'card-head' }, el('h2', { id: 'checklist-title' }, 'Kontrol listesi'), el('span', { class: 'spacer' }),
          el('span', { class: 'pill neutral tabular' }, `${done} / ${steps.length}`)),
        el('div', { class: 'card-body' }, el('ul', { class: 'checklist' }, steps.map((s) => el('li', { class: s.done ? 'done' : '' },
          el('span', { class: 'check' }, s.done ? icon('check') : null),
          el('div', {}, el('strong', {}, s.label), el('span', { class: 'sub' }, s.note))))))),
      el('div', { class: 'grid' },
        el('section', { class: 'card', 'aria-labelledby': 'connectors-title' },
          el('div', { class: 'card-head' }, el('h2', { id: 'connectors-title' }, 'Bağlantılar')),
          el('div', { class: 'card-body' }, el('ul', { class: 'list' }, CONNECTORS.map((c) => el('li', {},
            el('span', { class: 'connector-icon' }, icon('plug', 'sm')),
            el('div', { class: 'grow' }, el('span', { class: 'title' }, c.title), el('span', { class: 'sub' }, c.text)),
            el('span', { class: 'pill neutral' }, 'Planlandı')))))),
        deployment.team ? el('section', { class: 'card', 'aria-labelledby': 'team-title' },
          el('div', { class: 'card-head' }, el('h2', { id: 'team-title' }, 'Kurulum ekibiniz')),
          el('div', { class: 'card-body' }, el('p', { class: 'muted' }, deployment.team))) : null)));
}
