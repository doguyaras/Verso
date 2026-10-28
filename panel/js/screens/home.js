// "Ana sayfa" (ADR-0058, "Sadeleştirme": az göster, isteyince aç). A big composer (a question, or "/" for a skill when
// the skills screen is deployed), three suggestions, at most four skill cards and "Bilgide yeni" (three, "Tümü →").
// Admins get ONE attention line instead of a figure strip, and a slim setup line until setup is done. On the first run
// (no question asked, no document) a short welcome with three steps; it can be closed for the browser session.
// "Ekibinizin son çıktıları" opens on demand.
import { api } from '../api.js';
import { CONFIG } from '../config.js';
import { recentRange } from '../health.js';
import { attentionLine, composerHint, composerIntent, firstRun, homeTitle, newKnowledge, outputsModel, quickStarts, setupProgress,
  skillCards } from '../home.js';
import { slashPicker } from './ask.js';
import { icon } from '../icons.js';
import { canOpen, ROLES } from '../roles.js';
import { el, fill, formatLabel, plainName } from '../render.js';
import { conversation, prefs } from '../store.js';
import { setupSteps } from './setup.js';
import { disclosure, emptyState, fileIcon, relativeTime, statusPill } from '../ui.js';

function composer(ctx) {
  const field = el('textarea', { class: 'home-input', id: 'home-question', rows: 3, maxlength: 1000,
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
    ctx.skillsAvailable ? el('span', { class: 'muted home-hint' }, el('kbd', {}, '/'), ' beceriler') : null,
    el('button', { class: 'button icon-only home-send', type: 'submit', 'aria-label': 'Gönder' }, icon('send'))));
}

function quick(ctx) {
  const chips = quickStarts(CONFIG.deployment.suggestions);
  if (!chips.length) return null;
  return el('div', { class: 'home-quick', role: 'group', 'aria-label': 'Önerilen sorular' },
    chips.map((question) => el('button', { class: 'chip', type: 'button', onclick: () => ctx.navigate('ask', { question }) }, question)));
}

/** The admin's one line: "Her şey yolunda" or "N konu dikkat istiyor →" (home.js attentionLine). */
function attention(ctx, line) {
  if (!line) return null;
  const target = canOpen(line.screen, ctx.roles) ? line.screen : null;
  const body = [el('span', { class: `home-attention-dot ${line.state}`, 'aria-hidden': 'true' }),
    el('span', {}, line.state === 'ok' ? line.text : `${line.text} →`)];
  return el('p', { class: `home-attention ${line.state}` },
    target ? el('a', { href: `#/${target}` }, body) : body);
}

/** A slim setup line until every step is done; then nothing. */
function setupLine(ctx, steps) {
  const progress = setupProgress(steps);
  if (!progress.total || progress.complete) return null;
  return el('a', { class: 'home-setup', href: canOpen('setup', ctx.roles) ? '#/setup' : '#/home' },
    el('span', {}, `Kurulum · ${progress.label}`),
    el('progress', { max: progress.total, value: progress.done, 'aria-label': `Kurulum ilerlemesi ${progress.label}` }),
    el('span', { class: 'home-setup-go' }, 'Devam et →'));
}

/** The first-run welcome (home.js firstRun): three steps, closed for the browser session with "Kapat". */
function welcome(ctx, model, onClose) {
  if (!model) return null;
  const card = el('section', { class: 'home-welcome', 'aria-labelledby': 'home-welcome-title' },
    el('div', { class: 'home-welcome-head' }, el('h2', { id: 'home-welcome-title' }, model.title),
      el('button', { class: 'icon-button', type: 'button', 'aria-label': 'Karşılamayı kapat', onclick: () => {
        prefs.markOnboarding({ welcomeDismissed: true });
        card.remove();
        onClose?.();
      } }, icon('x', 'sm'))),
    el('p', { class: 'muted' }, model.text),
    el('ol', { class: 'home-steps' }, model.steps.map((step, index) => el('li', {},
      el('button', { class: 'home-step', type: 'button', onclick: () => ctx.navigate(step.screen, step.params) },
        el('span', { class: 'home-step-n', 'aria-hidden': 'true' }, String(index + 1)),
        el('span', { class: 'grow' }, el('strong', {}, step.title), el('span', { class: 'sub' }, step.text)),
        icon('arrowRight', 'sm'))))));
  return card;
}

function cards(ctx, skills, runs) {
  // "Beceriler" (ADR-0057): the caller's most used skills, then the library's order; at most four.
  const top = ctx.skillsAvailable ? skillCards(skills, runs) : [];
  if (!top.length) return null;
  return el('section', { class: 'home-section', 'aria-labelledby': 'home-skills-title' },
    el('div', { class: 'home-section-head' }, el('h2', { id: 'home-skills-title' }, 'Beceriler'),
      el('a', { href: `#/${ctx.skillsAvailable}` }, 'Tümü →')),
    el('div', { class: 'home-cards' }, top.map((skill) => el('button', { class: 'home-card', type: 'button',
      title: skill.description || skill.name, onclick: () => ctx.navigate(ctx.skillsAvailable, { skillId: skill.id }) },
    el('span', { class: 'home-card-icon' }, icon('sparkles', 'sm')), el('strong', {}, skill.name),
    skill.description ? el('span', { class: 'muted home-card-text' }, skill.description) : null))));
}

function fresh(ctx, docs) {
  const rows = newKnowledge(docs);
  return el('section', { class: 'home-section', 'aria-labelledby': 'home-new-title' },
    el('div', { class: 'home-section-head' }, el('h2', { id: 'home-new-title' }, 'Bilgide yeni'),
      rows.length ? el('a', { href: '#/documents' }, 'Tümü →') : null),
    rows.length
      ? el('ul', { class: 'list home-list' }, rows.map((d) => el('li', {}, fileIcon(d.format),
        el('div', { class: 'grow' }, el('span', { class: 'title' }, plainName(d.fileName)),
          el('span', { class: 'sub' }, [formatLabel(d.format), d.collection ? plainName(d.collection) : 'Kişisel', relativeTime(d.at)].join(' · '))),
        d.status === 'READY' ? null : statusPill(d.status))))
      : emptyState('upload', 'Henüz belge yok', 'Belge eklendikçe burada görünür.',
        el('button', { class: 'button', type: 'button', onclick: () => ctx.navigate('documents', { upload: true }) }, icon('upload', 'sm'), 'Belge ekleyin')));
}

/** "Ekibinizin son çıktıları": verified answers, procedures and the caller's approved skill outputs, on demand. */
function outputs(ctx, feed, runs) {
  const rows = outputsModel(feed, 5, runs);
  if (!rows.length) return null;
  return disclosure(`Ekibinizin son çıktıları (${rows.length})`, el('ul', { class: 'list home-list' }, rows.map((o) => el('li', {},
    el('span', { class: 'file-icon sm', 'aria-hidden': 'true' }, icon(o.screen === 'skills' ? 'flag' : o.screen === 'beceriler' ? 'sparkles' : 'checkCircle', 'sm')),
    el('div', { class: 'grow' },
      canOpen(o.screen, ctx.roles) ? el('button', { class: 'link-button title', type: 'button', onclick: () => ctx.navigate(o.screen, o.params) }, o.title)
        : el('span', { class: 'title' }, o.title),
      el('span', { class: 'sub' }, `${o.kind} · ${o.state} · ${relativeTime(o.at)}`))))), 'home-outputs');
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
  const onboarding = prefs.onboarding();
  const [docs, feed, skills, runs, history, summary, report, info, gateway] = await Promise.all([
    guarded(() => api.listAllDocuments()),
    guarded(() => api.home()),
    // ADR-0057: the skill library and the caller's own run history, only when "Beceriler" is deployed.
    ctx.skillsAvailable ? guarded(() => api.listTaskSkills()) : null,
    ctx.skillsAvailable ? guarded(() => api.listSkillRuns(0, 50)) : null,
    // The first-run check: one row of the caller's own history is enough; not read once a question was asked here.
    onboarding.asked || onboarding.welcomeDismissed || conversation.length ? null
      : ctx.withAuth(() => api.listConversations(0, 1)).catch(() => null),
    admin ? guarded(() => api.activitySummary()) : null,
    admin ? guarded(() => api.knowledgeHealth(health.from, health.to)) : null,
    admin ? guarded(() => api.info()) : null,
    // ADR-0056: the model gateway's budget; optional (no toast): an older server or a failure leaves it out.
    canOpen('providers', ctx.roles) ? ctx.withAuth(() => api.modelUsage()).catch(() => null) : null,
  ]);
  if (!ctx.isCurrent()) return;
  if (docs) ctx.setCount('documents', docs.length);
  const typed = main.querySelector('#home-question')?.value ?? '';
  const steps = admin ? setupSteps({ signedIn: true, mode: info?.mode ?? null,
    ready: (docs ?? []).filter((d) => d.status === 'READY').length,
    cited: conversation.some((entry) => entry.result?.citations?.length > 0),
    pilotGroup: Boolean(CONFIG.deployment.pilotGroup) }) : [];
  const first = firstRun({ documents: docs, conversations: Array.isArray(history?.data) ? history.data : Array.isArray(history?.items) ? history.items : null,
    asked: onboarding.asked || conversation.length > 0, dismissed: onboarding.welcomeDismissed, admin,
    skills: ctx.skillsAvailable || null });
  const news = el('div', { class: 'home-news' });
  const showNews = () => news.replaceChildren(fresh(ctx, docs ?? []));
  // On the first run the welcome already says "Belge ekleyin": the empty "Bilgide yeni" would repeat it.
  if (!first || (docs ?? []).length) showNews();
  fill(main, el('div', { class: 'home' }, head, composer(ctx), quick(ctx),
    admin ? attention(ctx, attentionLine({ summary, report, usage: gateway })) : null,
    admin ? setupLine(ctx, steps) : null,
    welcome(ctx, first, showNews),
    cards(ctx, skills, runs?.items),
    news,
    outputs(ctx, feed, runs?.items)));
  const field = main.querySelector('#home-question');
  field.value = typed;
}
