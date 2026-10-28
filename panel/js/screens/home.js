// "Ana sayfa" (ADR-0058, concept A "Çalışma alanı"): a work bench for everyone. The composer asks a question (or, when
// the skills feature is deployed, starts a skill with "/"), quick starts, skill cards or the knowledge actions, the
// team's recent verified outputs and what is new in the knowledge the caller may read. Admins also get "Bu hafta
// şirketinizde" (five figures, each linked to its action) and, until setup is done, a compact checklist.
import { api } from '../api.js';
import { CONFIG } from '../config.js';
import { recentRange } from '../health.js';
import { composerHint, composerIntent, homeTitle, knowledgeActions, newKnowledge, outputsModel, quickStarts, setupProgress,
  skillCards, weekFigures } from '../home.js';
import { slashPicker } from './ask.js';
import { icon } from '../icons.js';
import { rangeFor } from '../metrics.js';
import { canOpen, ROLES } from '../roles.js';
import { el, fill, formatLabel, plainName } from '../render.js';
import { conversation } from '../store.js';
import { setupSteps } from './setup.js';
import { emptyState, fileIcon, relativeTime, statusPill } from '../ui.js';

function composer(ctx) {
  const field = el('textarea', { class: 'home-input', id: 'home-question', rows: 2, maxlength: 1000,
    placeholder: composerHint(ctx.skillsAvailable), 'aria-label': ctx.skillsAvailable ? 'Soru ya da beceri komutu' : 'Soru' });
  const submit = () => {
    const intent = composerIntent(field.value);
    if (intent) ctx.navigate('ask', { question: intent.question });
  };
  // ADR-0057's "/" picker of "Asistan", the same component: it opens the chosen skill's form in "Beceriler".
  const slash = ctx.skillsAvailable ? slashPicker(field, ctx) : null;
  field.addEventListener('keydown', (event) => {
    if (slash?.handleKey(event)) return;
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      submit();
    }
  });
  return el('form', { class: 'home-composer', onsubmit: (event) => {
    event.preventDefault();
    submit();
  } }, field, slash ? slash.list : null,
  el('div', { class: 'home-composer-bar' },
    el('span', { class: 'pill neutral', title: 'Arama, erişiminiz olan bütün alanlarda yapılır.' }, icon('layers', 'sm'), 'Kapsam: erişiminiz olan alanlar'),
    ctx.skillsAvailable ? el('span', { class: 'muted home-hint' }, el('kbd', {}, '/'), ' beceriler') : null,
    el('button', { class: 'button icon-only home-send', type: 'submit', 'aria-label': 'Gönder' }, icon('send'))));
}

function quick(ctx) {
  const chips = quickStarts(CONFIG.deployment.suggestions);
  if (!chips.length) return null;
  return el('div', { class: 'home-quick', role: 'group', 'aria-label': 'Hızlı başlat' }, el('span', { class: 'muted' }, 'Hızlı başlat:'),
    chips.map((question) => el('button', { class: 'chip', type: 'button', onclick: () => ctx.navigate('ask', { question }) }, question)));
}

function week(ctx, figures) {
  if (!figures.length) return null;
  return el('section', { class: 'home-week', 'aria-labelledby': 'home-week-title' },
    el('div', { class: 'home-week-head' }, el('h2', { id: 'home-week-title' }, 'Bu hafta şirketinizde'),
      el('span', { class: 'muted' }, 'yalnız yöneticiler görür'),
      canOpen('activity', ctx.roles) ? el('a', { href: '#/activity' }, 'Etkinlik merkezi →') : null),
    el('div', { class: 'home-week-grid' }, figures.map((f) => el('div', { class: 'home-figure' },
      el('span', { class: 'home-figure-value' }, String(f.value)),
      el('span', { class: 'home-figure-label' }, f.label),
      canOpen(f.screen, ctx.roles)
        ? el('button', { class: 'link-button', type: 'button', onclick: () => ctx.navigate(f.screen) }, `${f.action} →`) : null))));
}

function checklist(ctx, steps) {
  const progress = setupProgress(steps);
  if (progress.complete) return null;
  return el('section', { class: 'card home-checklist', 'aria-labelledby': 'home-setup-title' },
    el('div', { class: 'card-head' }, el('h2', { id: 'home-setup-title' }, 'Kurulum'), el('span', { class: 'spacer' }),
      el('span', { class: 'pill neutral tabular' }, progress.label),
      canOpen('setup', ctx.roles) ? el('a', { href: '#/setup' }, 'Ayrıntı') : null),
    el('div', { class: 'card-body' }, el('ul', { class: 'checklist compact' }, steps.map((s) => el('li', { class: s.done ? 'done' : '' },
      el('span', { class: 'check' }, s.done ? icon('check') : null), el('span', {}, s.label))))));
}

function cards(ctx, skills, runs) {
  // "Beceriler" (ADR-0057): the caller's most used skills, then the library's order; each opens its guided form.
  const top = ctx.skillsAvailable ? skillCards(skills, runs) : [];
  if (top.length) {
    return el('section', { class: 'home-section', 'aria-labelledby': 'home-skills-title' },
      el('div', { class: 'home-section-head' }, el('h2', { id: 'home-skills-title' }, 'Sık kullandığınız beceriler'),
        el('a', { href: `#/${ctx.skillsAvailable}` }, 'Galeri →')),
      el('div', { class: 'home-cards' }, top.map((skill) => el('button', { class: 'card home-card', type: 'button',
        onclick: () => ctx.navigate(ctx.skillsAvailable, { skillId: skill.id }) },
      el('span', { class: 'home-card-icon' }, icon('sparkles')), el('strong', {}, skill.name),
      el('span', { class: 'muted' }, skill.description),
      el('span', { class: 'sub' }, [skill.slash ? `/${skill.slash}` : null, skill.uses ? `${skill.uses} kez kullandınız` : null]
        .filter(Boolean).join(' · '))))));
  }
  const actions = knowledgeActions((id) => canOpen(id, ctx.roles) && ctx.available.has(id));
  return el('section', { class: 'home-section', 'aria-labelledby': 'home-actions-title' },
    el('div', { class: 'home-section-head' }, el('h2', { id: 'home-actions-title' }, 'Bilgiyle başlayın')),
    el('div', { class: 'home-cards' }, actions.map((a) => el('button', { class: 'card home-card', type: 'button',
      onclick: () => ctx.navigate(a.id, a.params ?? null) },
    el('span', { class: 'home-card-icon' }, icon(a.icon)), el('strong', {}, a.title), el('span', { class: 'muted' }, a.text)))));
}

function outputs(ctx, feed, runs) {
  const rows = outputsModel(feed, 5, runs);
  return el('section', { class: 'card', 'aria-labelledby': 'home-outputs-title' },
    el('div', { class: 'card-head' }, el('h2', { id: 'home-outputs-title' }, 'Ekibinizin son çıktıları'), el('span', { class: 'spacer' }),
      el('button', { class: 'link-button', type: 'button', onclick: ctx.openHistory }, 'Geçmiş ve çıktılar →')),
    el('div', { class: 'card-body' }, rows.length
      ? el('ul', { class: 'list' }, rows.map((o) => el('li', {},
        el('span', { class: 'file-icon sm', 'aria-hidden': 'true' }, icon(o.screen === 'skills' ? 'flag' : o.screen === 'beceriler' ? 'sparkles' : 'checkCircle', 'sm')),
        el('div', { class: 'grow' },
          canOpen(o.screen, ctx.roles) ? el('button', { class: 'link-button title', type: 'button', onclick: () => ctx.navigate(o.screen, o.params) }, o.title)
            : el('span', { class: 'title' }, o.title),
          el('span', { class: 'sub' }, `${o.kind} · ${relativeTime(o.at)}`)),
        el('span', { class: 'pill ok' }, o.state))))
      : el('p', { class: 'muted' }, 'Erişebildiğiniz alanlarda henüz onaylı bilgi ya da prosedür yok. Uzmanlar doğruladıkça burada görünür.')));
}

function fresh(ctx, docs) {
  const rows = newKnowledge(docs);
  return el('section', { class: 'card', 'aria-labelledby': 'home-new-title' },
    el('div', { class: 'card-head' }, el('h2', { id: 'home-new-title' }, 'Bilgide yeni'), el('span', { class: 'spacer' }),
      el('a', { href: '#/documents' }, 'Kaynaklar →')),
    el('div', { class: 'card-body' }, rows.length
      ? el('ul', { class: 'list' }, rows.map((d) => el('li', {}, fileIcon(d.format),
        el('div', { class: 'grow' }, el('span', { class: 'title' }, plainName(d.fileName)),
          el('span', { class: 'sub' }, [formatLabel(d.format), d.collection ? plainName(d.collection) : 'Kişisel', relativeTime(d.at)].join(' · '))),
        statusPill(d.status))))
      : emptyState('upload', 'Henüz belge yok', 'Erişebildiğiniz alanlara belge eklendikçe burada görünür.')));
}

export async function renderHome(ctx) {
  const { main, guarded } = ctx;
  const admin = ctx.roles.includes(ROLES.ADMIN);
  const now = new Date();
  const head = el('header', { class: 'home-head' },
    el('p', { class: 'home-date' }, now.toLocaleDateString('tr-TR', { weekday: 'long', day: 'numeric', month: 'long' })),
    el('h1', { class: 'home-title' }, homeTitle(ctx.identity)));
  fill(main, el('div', { class: 'home' }, head, composer(ctx), quick(ctx), el('div', { class: 'skeleton block' })));

  const health = recentRange(now, 30);
  const usage = rangeFor(7, now);
  const [docs, feed, skills, runs, summary, report, metrics, info, gateway] = await Promise.all([
    guarded(() => api.listAllDocuments()),
    guarded(() => api.home()),
    // ADR-0057: the skill library and the caller's own run history, only when "Beceriler" is deployed.
    ctx.skillsAvailable ? guarded(() => api.listTaskSkills()) : null,
    ctx.skillsAvailable ? guarded(() => api.listSkillRuns(0, 50)) : null,
    admin ? guarded(() => api.activitySummary()) : null,
    admin ? guarded(() => api.knowledgeHealth(health.from, health.to)) : null,
    admin ? guarded(() => api.metrics(usage.from, usage.to)) : null,
    admin ? guarded(() => api.info()) : null,
    // ADR-0056: the model gateway's budget; optional (no toast): an older server or a failure leaves the figure out.
    canOpen('providers', ctx.roles) ? ctx.withAuth(() => api.modelUsage()).catch(() => null) : null,
  ]);
  if (!ctx.isCurrent()) return;
  if (docs) ctx.setCount('documents', docs.length);
  const typed = main.querySelector('#home-question')?.value ?? '';
  const steps = admin ? setupSteps({ signedIn: true, mode: info?.mode ?? null,
    ready: (docs ?? []).filter((d) => d.status === 'READY').length,
    cited: conversation.some((entry) => entry.result?.citations?.length > 0),
    pilotGroup: Boolean(CONFIG.deployment.pilotGroup) }) : [];
  fill(main, el('div', { class: 'home' }, head, composer(ctx), quick(ctx),
    admin ? week(ctx, weekFigures({ report, summary, metrics, usage: gateway })) : null,
    admin ? checklist(ctx, steps) : null,
    cards(ctx, skills, runs?.items),
    el('div', { class: 'home-cols' }, outputs(ctx, feed, runs?.items), fresh(ctx, docs ?? []))));
  const field = main.querySelector('#home-question');
  field.value = typed;
}
