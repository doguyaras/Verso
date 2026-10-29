// "Yapay zekâ sağlayıcıları" (ADR-0056): verso-admin adds the company's own model provider connections (OpenAI, Azure
// OpenAI, Anthropic, Bedrock, Google, Mistral, Ollama, any OpenAI-compatible server) with their API keys, tests them,
// routes every task to a primary model and fallbacks, prices the models, sets monthly budgets and allows (or not) a
// model choice for users. The key is typed into a password field, sent once and cleared; no response carries it, the
// list shows only its last four characters. Everything is built with el(): text, never markup; no inline style. The
// server enforces the role (403 for everyone else) and the local mode (cloud types are refused there).
import { api, ApiError, lastMode } from '../api.js';
import { icon } from '../icons.js';
import { connectionRequest, EMBEDDING_NOTE, FIELD_LABELS, formProblems, keyText, localityText, PROVIDER_TYPES,
  routeEntries, routeOptions, statusText, TASKS, typesFor, usageSummary } from '../providers.js';
import { el, fill } from '../render.js';
import { disclosure, emptyState, helpLine, skeletonRows, toast } from '../ui.js';

/** Fields that hold addresses and names: no autofill history, no spell check. */
const PLAIN = { autocomplete: 'off', spellcheck: 'false', autocapitalize: 'off' };

const pill = (meta) => el('span', { class: `pill ${meta.tone}` }, el('span', { class: 'dot' }), meta.label);

function card(id, title, ...body) {
  return el('section', { class: 'card', 'aria-labelledby': id },
    el('div', { class: 'card-head' }, el('h2', { id }, title)),
    el('div', { class: 'card-body' }, ...body));
}

function field(id, label, control, hint) {
  const error = el('p', { class: 'field-error', id: `${id}-error`, role: 'alert' });
  control.setAttribute('id', id);
  control.setAttribute('aria-describedby', `${hint ? `${id}-hint ` : ''}${id}-error`);
  return {
    node: el('div', { class: 'connector-field' }, el('label', { class: 'field-label', for: id }, label),
      hint ? el('p', { class: 'muted', id: `${id}-hint` }, hint) : null, control, error),
    control,
    show(text) {
      error.textContent = text ?? '';
      control.setAttribute('aria-invalid', text ? 'true' : 'false');
    },
  };
}

export async function renderProviders(ctx) {
  const { main, withAuth } = ctx;
  const mode = lastMode === 'cloud' ? 'cloud' : 'local';
  const list = el('div', {});
  const formSlot = el('div', { class: 'grid connector-slot' });
  const routing = el('div', {});
  const costs = el('div', {});
  const newButton = el('button', { class: 'button', type: 'button', onclick: () => drawForm(null) }, icon('plus'), 'Yeni bağlantı');
  let connections = [];

  fill(main,
    el('div', { class: 'page-head' },
      el('div', {}, el('h1', {}, 'Yapay zekâ sağlayıcıları'),
        helpLine('Kurumunuzun model sağlayıcılarını bağlayın.', 'Her görev için hangi modelin çalışacağını ve yedeklerini '
          + 'seçin, maliyeti ve bütçeyi izleyin. Anahtarlar şifreli saklanır ve hiçbir ekranda gösterilmez.')),
      el('div', { class: 'page-actions' }, newButton)),
    el('div', { class: `notice ${mode === 'local' ? 'good' : 'warn'}` }, icon(mode === 'local' ? 'shield' : 'cloud', 'sm'),
      el('div', { class: 'grow' }, mode === 'local'
        ? 'Yerel mod: yalnız iç ağdaki model sunucuları (Ollama, OpenAI uyumlu) eklenebilir; bulut sağlayıcıları reddedilir.'
        : 'Bulut modu: eklenen her dış bağlantının adresi çıkış izin listesine girer ve Güven ekranında listelenir. '
          + 'Yurt dışına aktarım için kurum hukuk birimi değerlendirmesi gerekir.')),
    card('providers-list', 'Bağlantılar', list),
    formSlot,
    // Sadeleştirme (ADR-0058): the list first; routing, prices and budgets open on demand.
    disclosure('Görev yönlendirme', card('providers-routing', 'Görev yönlendirme', routing), 'providers-more'),
    disclosure('Fiyat, bütçe ve kullanım', card('providers-costs', 'Fiyat, bütçe ve kullanım', costs), 'providers-more'));
  fill(list, skeletonRows(3));

  /** 403: the admin-only state; 401: the sign-in takes over; anything else: a toast with the server's message. */
  async function call(action) {
    try {
      return await withAuth(action);
    } catch (error) {
      if (!ctx.isCurrent()) return undefined;
      if (error instanceof ApiError && error.status === 403) {
        fill(main, emptyState('lock', 'Bu ekran yöneticiler içindir', 'Sağlayıcıları yalnız verso-admin rolü yönetir.'));
      } else if (!(error instanceof ApiError && error.status === 401)) {
        toast(error instanceof ApiError ? error.message : 'Bağlantı kurulamadı.');
      }
      return undefined;
    }
  }

  async function load() {
    const [loaded, routes] = await Promise.all([call(() => api.modelConnections()), call(() => api.modelRoutes())]);
    if (!ctx.isCurrent() || !loaded) return;
    connections = loaded;
    drawList();
    if (routes) drawRouting(routes);
    drawCosts();
  }

  function drawList() {
    if (!connections.length) {
      fill(list, emptyState('cpu', 'Henüz bağlantı yok', 'Bağlantı eklenene kadar kurulumun yerleşik modeli çalışır.',
        el('button', { class: 'button', type: 'button', onclick: () => drawForm(null) }, icon('plus'), 'Bağlantı ekleyin')));
      return;
    }
    fill(list, el('ul', { class: 'plain-list' }, connections.map((c) => el('li', { class: 'provider-row' },
      el('div', { class: 'grow' },
        el('strong', {}, c.name), ' ', el('span', { class: 'muted' }, PROVIDER_TYPES[c.type]?.label ?? c.type),
        el('div', { class: 'stat-note' }, localityText(c)),
        el('div', { class: 'stat-note' }, `${keyText(c)} · Modeller: ${(c.models ?? []).join(', ')}`)),
      pill(c.enabled ? statusText(c.validationStatus) : { label: 'Kapalı', tone: 'neutral' }),
      el('button', { class: 'button secondary', type: 'button', onclick: () => test(c) }, 'Dene'),
      el('button', { class: 'button secondary', type: 'button', onclick: () => drawForm(c) }, icon('edit'), 'Düzenle')))));
  }

  async function test(c) {
    const result = await call(() => api.testModelConnection(c.id));
    if (!result) return;
    toast(`${c.name}: ${statusText(result.status).label} (${result.latencyMs} ms)`, result.ok ? 'good' : 'bad');
    await load();
  }

  function drawForm(existing) {
    const types = typesFor(mode);
    const typeSelect = el('select', { disabled: existing ? true : undefined },
      types.map((t) => el('option', { value: t, selected: existing ? existing.type === t : undefined }, PROVIDER_TYPES[t].label)));
    const inputs = {
      name: el('input', { ...PLAIN, maxlength: 80, value: existing?.name ?? '' }),
      models: el('textarea', { ...PLAIN, rows: 2 }, (existing?.models ?? []).join('\n')),
      baseUrl: el('input', { ...PLAIN, maxlength: 500, value: existing?.baseUrl ?? '' }),
      region: el('input', { ...PLAIN, maxlength: 64, value: existing?.region ?? '' }),
      deployment: el('input', { ...PLAIN, maxlength: 200, value: existing?.deployment ?? '' }),
      project: el('input', { ...PLAIN, maxlength: 200, value: existing?.project ?? '' }),
      // A password field: not shown, not remembered by the browser, cleared after every attempt.
      apiKey: el('input', { type: 'password', autocomplete: 'new-password', spellcheck: 'false', maxlength: 8192 }),
      enabled: el('input', { type: 'checkbox', checked: existing ? existing.enabled : true }),
    };
    const fields = {
      type: field('provider-type', 'Sağlayıcı türü', typeSelect),
      name: field('provider-name', 'Ad', inputs.name, 'Listede görünen ad; kurulumda tekildir.'),
      models: field('provider-models', 'Modeller', inputs.models, 'Satır başına bir model adı (ör. gpt-5-mini, claude-sonnet-5-5).'),
      baseUrl: field('provider-base-url', FIELD_LABELS.baseUrl, inputs.baseUrl),
      region: field('provider-region', FIELD_LABELS.region, inputs.region),
      deployment: field('provider-deployment', FIELD_LABELS.deployment, inputs.deployment),
      project: field('provider-project', FIELD_LABELS.project, inputs.project),
      apiKey: field('provider-key', existing ? 'Yeni API anahtarı (boş bırakılırsa kayıtlı anahtar kalır)' : 'API anahtarı',
        inputs.apiKey, existing ? keyText(existing) : 'Kaydedilmeden önce sağlayıcıda küçük bir deneme çağrısıyla doğrulanır.'),
    };
    const hint = el('p', { class: 'muted' });
    const shown = () => {
      const type = PROVIDER_TYPES[typeSelect.value];
      for (const name of ['baseUrl', 'region', 'deployment', 'project']) fields[name].node.hidden = !type.fields.includes(name);
      fields.apiKey.node.hidden = type.key === 'none';
      hint.textContent = type.hint;
    };
    typeSelect.addEventListener('change', shown);
    shown();
    const save = el('button', { class: 'button', type: 'submit' }, existing ? 'Kaydet' : 'Doğrula ve ekle');
    const form = el('form', { class: 'connector-form', novalidate: true, onsubmit: async (event) => {
      event.preventDefault();
      const values = { type: typeSelect.value, enabled: inputs.enabled.checked };
      for (const name of ['name', 'models', 'baseUrl', 'region', 'deployment', 'project', 'apiKey']) values[name] = inputs[name].value;
      Object.values(fields).forEach((f) => f.show(''));
      const problems = formProblems(values, { creating: !existing });
      const first = Object.keys(problems)[0];
      if (first) {
        Object.entries(problems).forEach(([name, text]) => fields[name]?.show(text));
        fields[first]?.control.focus();
        return;
      }
      const body = connectionRequest(values);
      inputs.apiKey.value = '';
      save.disabled = true;
      const saved = await call(() => (existing ? api.updateModelConnection(existing.id, body) : api.createModelConnection(body)));
      save.disabled = false;
      if (!saved) return;
      toast(existing ? 'Bağlantı kaydedildi.' : 'Bağlantı doğrulandı ve eklendi.', 'good');
      fill(formSlot);
      await load();
    } },
    Object.values(fields).map((f) => f.node), hint,
    el('label', { class: 'toggle-line' }, inputs.enabled, ' Açık (rotalarda kullanılabilir)'),
    el('div', { class: 'page-actions' }, save,
      existing ? el('button', { class: 'button danger', type: 'button', onclick: async () => {
        // A connection still in a route is refused (409 11097): the toast says so and the form stays.
        await call(() => api.deleteModelConnection(existing.id));
        if (!ctx.isCurrent()) return;
        await load();
        if (!connections.some((c) => c.id === existing.id)) fill(formSlot);
      } }, icon('trash'), 'Sil') : null,
      el('button', { class: 'button secondary', type: 'button', onclick: () => fill(formSlot) }, 'Vazgeç')));
    fill(formSlot, card('provider-form', existing ? `${existing.name} bağlantısı` : 'Yeni bağlantı', form));
    inputs.name.focus();
  }

  function drawRouting(routes) {
    const options = routeOptions(connections, routes.builtInModel);
    const rows = TASKS.map((task) => {
      const current = (routes.routes?.[task.id] ?? []).map((e) => (e.connectionId ? `${e.connectionId}/${e.model}` : ''));
      const selects = [0, 1, 2].map((step) => el('select', { 'aria-label': `${task.label}, ${step === 0 ? 'birincil' : `${step}. yedek`}` },
        step > 0 ? el('option', { value: '-' }, '—') : null,
        options.map((o) => el('option', { value: o.value, selected: (current[step] ?? (step === 0 ? '' : '-')) === o.value ? true : undefined }, o.label))));
      const apply = el('button', { class: 'button secondary', type: 'button', onclick: async () => {
        const entries = routeEntries(selects.map((s) => s.value).filter((v) => v !== '-'), routes.builtInModel);
        const updated = await call(() => api.setModelRoute(task.id, entries));
        if (updated) toast(`${task.label} rotası kaydedildi.`, 'good');
      } }, 'Kaydet');
      return el('div', { class: 'route-row' }, el('strong', {}, task.label), selects, apply);
    });
    const choice = el('input', { type: 'checkbox', checked: routes.userModelChoice ? true : undefined,
      onchange: async (event) => {
        const done = await call(() => api.setModelSettings(event.target.checked));
        if (done) toast(event.target.checked ? 'Kullanıcılar cevap modelini seçebilir.' : 'Model seçimi kapatıldı.', 'good');
      } });
    // ADR-0059: Turkish personal data is replaced by placeholders before a cloud call; on unless the admin turns it off.
    const masking = el('input', { type: 'checkbox', checked: routes.piiMasking !== false ? true : undefined,
      onchange: async (event) => {
        const done = await call(() => api.setModelMasking(event.target.checked));
        if (done) toast(event.target.checked ? 'Kişisel veriler bulut çağrılarından önce maskelenecek.' : 'Maskeleme kapatıldı: bulut çağrıları kişisel veriyi olduğu gibi taşır.', event.target.checked ? 'good' : 'bad');
      } });
    fill(routing,
      el('p', {}, 'Her görev önce birincil modeli dener; sağlayıcı 5xx ya da 429 dönerse sıradaki yedeğe geçer. '
        + 'Rota boşsa kurulumun yerleşik modeli çalışır.'),
      rows,
      el('p', { class: 'stat-note' }, EMBEDDING_NOTE),
      el('label', { class: 'toggle-line' }, choice, ' Kullanıcılar cevap rotasındaki modeller arasından seçebilsin'),
      el('label', { class: 'toggle-line' }, masking, ' Bulut çağrılarında kişisel verileri maskele (TCKN, VKN, IBAN, kart, telefon, e-posta, plaka)'),
      routes.keyStoreReady ? null : el('div', { class: 'notice bad' }, icon('alert', 'sm'),
        el('div', { class: 'grow' }, 'Anahtar deposu kurulu değil (SECRET_MODEL_GATEWAY_KEK). Anahtarlı bağlantı eklenemez.')));
  }

  async function drawCosts() {
    const usage = await call(() => api.modelUsage());
    if (!ctx.isCurrent()) return;
    const summary = usageSummary(usage);
    const connectionSelect = el('select', { 'aria-label': 'Bağlantı' },
      connections.map((c) => el('option', { value: c.id }, c.name)));
    const model = el('input', { ...PLAIN, placeholder: 'Model adı', 'aria-label': 'Model adı' });
    const input = el('input', { inputmode: 'decimal', placeholder: 'Girdi / 1M token', 'aria-label': 'Girdi fiyatı' });
    const output = el('input', { inputmode: 'decimal', placeholder: 'Çıktı / 1M token', 'aria-label': 'Çıktı fiyatı' });
    const currency = el('input', { maxlength: 3, value: 'USD', 'aria-label': 'Para birimi' });
    const hard = el('input', { inputmode: 'decimal', placeholder: 'Aylık üst sınır', 'aria-label': 'Aylık üst sınır' });
    const soft = el('input', { inputmode: 'decimal', placeholder: 'Uyarı sınırı', 'aria-label': 'Uyarı sınırı' });
    const number = (node) => Number.parseFloat(String(node.value).replace(',', '.'));
    fill(costs,
      summary ? el('div', { class: 'grid kpis' }, summary.totals.map((t) => el('div', { class: 'card kpi' },
        el('span', { class: 'stat-label' }, t.label), el('span', { class: 'kpi-value' }, t.value)))) : null,
      summary?.budgets.length ? el('ul', { class: 'plain-list' }, summary.budgets.map((b) =>
        el('li', {}, pill({ label: b.state, tone: b.tone }), ` ${b.label}: ${b.text}`))) : null,
      summary ? el('p', { class: 'stat-note' }, summary.teamNote) : null,
      summary?.maskedNote ? el('p', { class: 'stat-note' }, summary.maskedNote) : null,
      connections.length ? el('form', { class: 'inline-form', onsubmit: async (event) => {
        event.preventDefault();
        const saved = await call(() => api.setModelPrice({ connectionId: connectionSelect.value, model: model.value.trim(),
          inputPerMillion: number(input), outputPerMillion: number(output), currency: currency.value.trim().toUpperCase() }));
        if (saved) toast('Fiyat kaydedildi.', 'good');
      } }, connectionSelect, model, input, output, currency, el('button', { class: 'button secondary', type: 'submit' }, 'Fiyatı kaydet')) : null,
      connections.length ? el('form', { class: 'inline-form', onsubmit: async (event) => {
        event.preventDefault();
        const budget = { scope: 'CONNECTION', scopeKey: connectionSelect.value, hardLimit: number(hard) };
        if (Number.isFinite(number(soft))) budget.softLimit = number(soft);
        const saved = await call(() => api.setModelBudget(budget));
        if (saved) toast('Bütçe kaydedildi.', 'good');
      } }, hard, soft, el('button', { class: 'button secondary', type: 'submit' }, 'Bağlantı bütçesini kaydet')) : null);
  }

  await load();
}
