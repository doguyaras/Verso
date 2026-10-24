// "Bağlayıcılar" (ADR-0038, ADR-0042, ADR-0044): verso-admin keeps a folder of an SMB file share or Confluence spaces in
// sync with a collection (a type selector picks the form's fields). The
// list shows health (derived from the last successful sync, so an index that silently stopped reads "Gecikmiş"), the
// form creates and edits a connector, and the actions request a sync, page through runs and files, approve deletions
// the deletion safety held back and delete a connector with a typed confirmation. The password never passes through
// the panel: the form names the secret file on the server (credentialRef), and the API never returns a password.
// Nothing goes into the URL hash or browser storage. Everything is built with el(): text, never markup (CSP, Trusted
// Types). The rules of the form live in ../connectors.js.
import { api, ApiError } from '../api.js';
import { approvalConfirmed, codeText, CONNECTOR_PAGE_SIZE, connectorForm, connectorRequest, deleteConfirmed, DEPLOYMENTS, DOCS,
  errorField, healthBadge, PAGE_SIZE_MAX, TYPES, ITEM_FAILURES, ITEM_PAGE_SIZE, ITEM_STATES, MAX_FILE_MB, pageState, pendingApproval, RUN_PAGE_SIZE, RUN_STATUS,
  runSummary, SCHEDULE_MAX, SCHEDULE_MIN, scheduleText, trackedCount } from '../connectors.js';
import { icon } from '../icons.js';
import { el, fill, formatDate, formatSize, plainName } from '../render.js';
import { emptyState, formatNumber, relativeTime, skeletonRows, toast } from '../ui.js';

/** Fields that hold host names, paths and file names: no autofill history, no spell check, no capitalisation. */
const PLAIN = { autocomplete: 'off', spellcheck: 'false', autocapitalize: 'off' };

const pill = (meta) => el('span', { class: `pill ${meta.tone}` }, el('span', { class: 'dot' }), meta.label);

export function renderConnectors(ctx) {
  const { main, withAuth } = ctx;
  let state = { page: 0, pageInfo: null, connectors: [], latest: new Map(), collections: new Map(), selected: null };

  const blocked = el('div', {});
  const list = el('div', {});
  const formSlot = el('div', { class: 'grid connector-slot' });
  const detail = el('div', { class: 'grid connector-slot' });
  const newButton = el('button', { class: 'button', type: 'button', onclick: () => drawForm(null) }, icon('plus'), 'Yeni bağlayıcı');

  main.replaceChildren(
    el('div', { class: 'page-head' },
      el('div', {}, el('h1', {}, 'Bağlayıcılar'),
        el('p', {}, 'Kurumun dosya sunucusundaki (SMB) bir klasörü ya da Confluence alanlarını bir bilgi alanıyla eşitleyin: yeni ve değişen '
          + 'dosyalar ve sayfalar kendiliğinden alınır, kaynakta silinen buradan da bütün türevleriyle silinir. Her değişiklik denetim kaydına yazılır.')),
      el('div', { class: 'page-actions' }, newButton)),
    blocked,
    el('section', { class: 'card', 'aria-labelledby': 'connectors-list' },
      el('div', { class: 'card-head' }, el('h2', { id: 'connectors-list' }, 'Bağlayıcı listesi')), list),
    formSlot,
    detail,
    helpCard());

  /** An API call: 403 shows the admin-only state, 401 starts the sign-in, a connector code lands under its field. */
  async function call(action, fields = null, type = 'SMB') {
    try {
      return await withAuth(action);
    } catch (error) {
      if (!ctx.isCurrent()) return undefined;
      if (error instanceof ApiError && error.status === 403) {
        fill(blocked, emptyState('lock', 'Bu ekran yöneticiler içindir', 'Bağlayıcıları yalnız verso-admin rolü yönetir.'));
        fill(list);
        fill(formSlot);
        fill(detail);
        newButton.disabled = true;
      } else if (error instanceof ApiError && error.status === 401) {
        // the sign-in flow takes over
      } else if (error instanceof ApiError && fields && fields[errorField(error.code, type)]) {
        fields[errorField(error.code, type)].show(error.message);
        toast(error.message);
      } else {
        toast(error instanceof ApiError ? error.message : 'Bağlantı kurulamadı.');
      }
      return undefined;
    }
  }

  // ---------- list ----------
  async function load(page = state.page) {
    fill(list, skeletonRows(3));
    const [connectors, collections] = await Promise.all([
      call(() => api.listConnectors(page, CONNECTOR_PAGE_SIZE)),
      call(() => api.listCollections()),
    ]);
    if (!ctx.isCurrent() || !connectors) return;
    state.page = page;
    state.pageInfo = connectors.page;
    state.connectors = Array.isArray(connectors.data) ? connectors.data : [];
    state.collections = new Map((collections?.data ?? []).map((c) => [c.id, c]));
    state.latest = new Map();
    drawList();
    // The newest run of each connector, a few at a time: the list's "Son koşu" column and the pending approvals.
    const queue = [...state.connectors];
    const worker = async () => {
      for (let next = queue.shift(); next; next = queue.shift()) {
        const runs = await call(() => api.listConnectorRuns(next.id, 0, 1));
        if (!ctx.isCurrent()) return;
        state.latest.set(next.id, runs?.data?.[0] ?? null);
      }
    };
    await Promise.all([worker(), worker(), worker(), worker()]);
    if (!ctx.isCurrent()) return;
    drawList();
    if (state.selected) {
      const fresh = state.connectors.find((c) => c.id === state.selected.id);
      if (fresh) drawDetail(fresh);
      else fill(detail);
    }
  }

  const collectionName = (id) => (state.collections.get(id) ? plainName(state.collections.get(id).name) : 'Bilinmeyen alan');

  function drawList() {
    if (!state.connectors.length) {
      fill(list, el('div', { class: 'card-body' }, emptyState('plug', 'Henüz bağlayıcı yok',
        'Önce "Bilgi alanları"nda hedef alanı oluşturup erişimleri verin, sonra "Yeni bağlayıcı" ile paylaşım klasörünü bağlayın.')));
      return;
    }
    const paging = pageState(state.pageInfo);
    fill(list, el('div', { class: 'table-wrap' }, el('table', { class: 'table connector-table' },
      el('caption', { class: 'sr-only' }, 'Bağlayıcılar, sağlık durumu ve son eşitleme'),
      el('thead', {}, el('tr', {}, ['Ad', 'Hedef bilgi alanı', 'Sağlık', 'Son başarılı eşitleme', 'Dosyalar', 'Son koşu', '']
        .map((h) => el('th', { scope: 'col' }, h)))),
      el('tbody', {}, state.connectors.map((connector) => {
        const health = healthBadge(connector.health);
        const latest = state.latest.get(connector.id);
        const pending = pendingApproval(connector, latest);
        const items = connector.items ?? {};
        return el('tr', {},
          el('td', {}, el('strong', {}, plainName(connector.name)),
            el('div', { class: 'muted' }, TYPES[connector.type] ?? TYPES.SMB)),
          el('td', {}, collectionName(connector.collectionId)),
          el('td', {}, el('span', { title: health.help }, pill(health)),
            pending ? el('div', {}, el('span', { class: 'pill wait' }, icon('alert', 'sm'), 'Silme onayı bekliyor')) : null),
          el('td', { class: 'when' }, connector.lastSuccessAt
            ? el('time', { datetime: connector.lastSuccessAt, title: formatDate(connector.lastSuccessAt) }, relativeTime(connector.lastSuccessAt))
            : 'Hiç'),
          el('td', { class: 'tabular' }, `${formatNumber(items.synced)} eşitlendi`,
            items.failed ? el('div', { class: 'muted' }, `${formatNumber(items.failed)} başarısız`) : null,
            items.skipped ? el('div', { class: 'muted' }, `${formatNumber(items.skipped)} atlandı`) : null),
          el('td', {}, latest === undefined ? el('span', { class: 'muted' }, 'Yükleniyor…') : runSummary(latest),
            latest?.errorCode ? el('div', { class: 'muted' }, codeText(latest.errorCode)) : null),
          el('td', {}, el('button', { class: 'button secondary', type: 'button', onclick: () => select(connector) },
            'Yönet', el('span', { class: 'sr-only' }, `: ${plainName(connector.name)}`))));
      })))),
    paging.total > 1 ? pager(paging, (page) => load(page)) : null);
  }

  function pager(paging, go) {
    return el('div', { class: 'table-foot' }, el('span', {}, paging.label),
      el('div', { class: 'page-actions' },
        el('button', { class: 'button secondary', type: 'button', disabled: !paging.prev, onclick: () => go(paging.number - 1) }, 'Önceki'),
        el('button', { class: 'button secondary', type: 'button', disabled: !paging.next, onclick: () => go(paging.number + 1) }, 'Sonraki')));
  }

  function select(connector) {
    state.selected = connector;
    fill(formSlot);
    drawDetail(connector);
    detail.querySelector('h2')?.focus();
  }

  // ---------- create / edit ----------
  function field(id, label, control, hint) {
    const error = el('p', { class: 'field-error', id: `${id}-error`, role: 'alert' });
    control.setAttribute('id', id);
    control.setAttribute('aria-describedby', `${hint ? `${id}-hint ` : ''}${id}-error`);
    const node = el('div', { class: 'connector-field' },
      el('label', { class: 'field-label', for: id }, label),
      hint ? el('p', { class: 'muted', id: `${id}-hint` }, hint) : null, control, error);
    return {
      node, control,
      show(text) {
        error.textContent = text ?? '';
        control.setAttribute('aria-invalid', String(Boolean(text)));
      },
    };
  }

  /** A checkbox row of the form (44 px touch target via .connector-check). */
  function check(id, checked, text) {
    const box = el('input', { type: 'checkbox', id, checked });
    return { box, node: el('label', { class: 'connector-check', for: id }, box, el('span', {}, text)) };
  }

  function drawForm(original, chosenType = 'SMB') {
    fill(detail);
    const type = original?.type === 'CONFLUENCE' ? 'CONFLUENCE' : original ? 'SMB' : chosenType;
    const values = connectorForm(original, type);
    // The type is fixed after creation (409 14015); switching it on a new connector redraws the form with its fields.
    const typePicker = el('select', { class: 'input', id: 'connector-type', disabled: Boolean(original),
      onchange: (event) => drawForm(null, event.target.value) },
    Object.entries(TYPES).map(([value, label]) => el('option', { value, selected: value === type }, label)));
    const input = (name, extra = {}) => el('input', { class: 'input', type: 'text', ...PLAIN, value: values[name], ...extra });
    const area = (name) => {
      const node = el('textarea', { class: 'textarea', rows: 3, ...PLAIN });
      node.value = values[name];
      return node;
    };
    const picker = el('select', { class: 'input', disabled: Boolean(original) },
      el('option', { value: '' }, 'Bilgi alanı seçin'),
      [...state.collections.values()].map((c) => el('option', { value: c.id, selected: c.id === values.collectionId }, plainName(c.name))));
    if (original && !state.collections.has(original.collectionId)) {
      picker.append(el('option', { value: original.collectionId, selected: true }, 'Bilinmeyen alan'));
    }
    const enabled = el('input', { type: 'checkbox', id: 'connector-enabled', checked: values.enabled });
    const attachments = check('connector-attachments', values.includeAttachments,
      'Ekleri de al: sayfaların PDF ve DOCX ekleri (boyut sınırı aynı; ekler sayfanın kısıtına uyar)');
    const skipRestricted = check('connector-skip-restricted', values.skipRestricted,
      'Kısıtlı sayfaları alma (önerilen): okuma kısıtı olan (kendisi ya da üst sayfası) hiçbir sayfa eşitlenmez');
    const deployment = el('select', { class: 'input' },
      Object.entries(DEPLOYMENTS).map(([value, label]) => el('option', { value, selected: value === values.deployment }, label)));
    const confluenceFields = type !== 'CONFLUENCE' ? null : {
      name: field('connector-name', 'Ad', input('name', { maxlength: 100 }), 'Listede görünen ad; kurulumda tekildir.'),
      baseUrl: field('connector-base-url', 'Confluence adresi', input('baseUrl', { maxlength: 2048, placeholder: 'Örn. https://wiki.kurum.local ya da https://kurum.atlassian.net/wiki' }),
        'Yalnız https. Data Center adresi kurulumun izinli iç ağında olmalı; Cloud yalnız kurulum dış bağlantıya izin verdiyse (ör. *.atlassian.net) '
        + 've sıkı local modda hiç kullanılamaz.'),
      deployment: field('connector-deployment', 'Kurulum türü', deployment,
        'Data Center/Server: kişisel erişim belirteci. Cloud: hesabın e-postası ve API belirteci.'),
      username: field('connector-username', 'Hesap e-postası (yalnız Cloud)', input('username', { maxlength: 104, placeholder: 'Örn. verso-okuyucu@kurum.com' }),
        'Data Center için boş bırakın. Yalnız eşitlenecek alanları okuyabilen, bu iş için açılmış hesap.'),
      credentialRef: field('connector-credential', 'Belirteç dosyasının adı', input('credentialRef', { maxlength: 77 }),
        'Belirteç bu ekrana yazılmaz ve hiçbir yerde gösterilmez. Kurulum ekibi onu sunucudaki secrets klasöründe bu adla bir dosyaya '
        + 'yazar; burada yalnız dosyanın adını seçersiniz (SECRET_CONNECTOR_ ile başlar, ör. SECRET_CONNECTOR_CONFLUENCE_TOKEN).'),
      collectionId: field('connector-collection', 'Hedef bilgi alanı', picker, original
        ? 'Hedef alan sonradan değiştirilemez: taşıma, belgeleri eski erişimlerle geride bırakırdı. Başka alan için yeni bağlayıcı oluşturun.'
        : 'Bu alana erişimi olan herkes eşitlenen her sayfayı görür; Confluence alan ve sayfa izinleri aktarılmaz. Alanları bilgi alanının okuyucularına göre seçin.'),
      spaceKeys: field('connector-spaces', 'Alan (space) anahtarları', area('spaceKeys'),
        'Satır başına ya da virgülle, en çok 20: IK, POLITIKA. Hesabın göremediği alan listelenmez.'),
      label: field('connector-label', 'Yalnız bu etiket (isteğe bağlı)', input('label', { maxlength: 255, placeholder: 'Örn. yayinda' }),
        'Boş: alanlardaki bütün sayfalar.'),
      ancestorId: field('connector-ancestor', 'Yalnız bu sayfanın altı (isteğe bağlı)', input('ancestorId', { inputmode: 'numeric', maxlength: 18, placeholder: 'Sayfa kimliği, örn. 123456' }),
        'Boş: alanın tamamı.'),
      maxFileMb: field('connector-max', 'En büyük sayfa ya da ek (MB)', input('maxFileMb', { inputmode: 'decimal', maxlength: 6, placeholder: String(MAX_FILE_MB) }),
        `Boş bırakılırsa ${MAX_FILE_MB} MB (belge sınırı, aynı zamanda üst sınır). Daha büyükler atlanır.`),
      pageSize: field('connector-page-size', 'İstek başına sonuç (isteğe bağlı)', input('pageSize', { inputmode: 'numeric', maxlength: 3, placeholder: '50' }),
        `1 ile ${PAGE_SIZE_MAX} arası; boş: 50. Confluence istek sınırına (429) takılıyorsa küçültün.`),
      scheduleMinutes: field('connector-schedule', 'Eşitleme aralığı (dakika)', input('scheduleMinutes', { inputmode: 'numeric', maxlength: 5 }),
        `${SCHEDULE_MIN} dakika ile ${SCHEDULE_MAX} dakika (1 hafta) arası. Aralıkta yalnız değişen sayfalar okunur; günde bir tam liste silinen, `
        + 'arşivlenen ve sonradan kısıtlanan sayfaları bulur.'),
    };
    const fields = confluenceFields ?? {
      name: field('connector-name', 'Ad', input('name', { maxlength: 100 }), 'Listede görünen ad; kurulumda tekildir.'),
      host: field('connector-host', 'Dosya sunucusu', input('host', { maxlength: 253, placeholder: 'Örn. 10.20.30.40 ya da dosya01.kurum.local' }),
        'Yalnız ad ya da IP adresi. Adres kurulumun izinli iç ağlarında olmalı; en güvenlisi IP adresi.'),
      port: field('connector-port', 'Port', input('port', { inputmode: 'numeric', maxlength: 5, placeholder: '445' }), 'Boş bırakılırsa 445.'),
      share: field('connector-share', 'Paylaşım', input('share', { maxlength: 80, placeholder: 'Örn. Kurumsal' }), 'Eğik çizgi olmadan, yalnız paylaşımın adı.'),
      pathPrefix: field('connector-prefix', 'Klasör (paylaşım içinde)', input('pathPrefix', { maxlength: 1024, placeholder: 'Örn. IK/Politikalar' }),
        'Boş bırakılırsa bütün paylaşım eşitlenir. Bu klasörün altındaki her şey hedef alanın okuyucularına açılır.'),
      domain: field('connector-domain', 'Etki alanı', input('domain', { maxlength: 253, placeholder: 'Örn. CORP' }), 'Yerel hesapta boş bırakın.'),
      username: field('connector-username', 'Servis hesabı', input('username', { maxlength: 104, placeholder: 'Örn. svc-verso' }),
        'Yalnız okuma izni olan, bu iş için açılmış hesap.'),
      credentialRef: field('connector-credential', 'Parola dosyasının adı', input('credentialRef', { maxlength: 77 }),
        'Parola bu ekrana yazılmaz ve hiçbir yerde gösterilmez. Kurulum ekibi parolayı sunucudaki secrets klasöründe bu adla bir dosyaya '
        + 'yazar; burada yalnız dosyanın adını seçersiniz (SECRET_CONNECTOR_ ile başlar, ör. SECRET_CONNECTOR_SMB_PASSWORD).'),
      collectionId: field('connector-collection', 'Hedef bilgi alanı', picker, original
        ? 'Hedef alan sonradan değiştirilemez: taşıma, belgeleri eski erişimlerle geride bırakırdı. Başka alan için yeni bağlayıcı oluşturun.'
        : 'Bu alana erişimi olan herkes eşitlenen her dosyayı görür; paylaşımdaki NTFS izinleri aktarılmaz. Klasörü alanın okuyucularına göre seçin.'),
      includeGlobs: field('connector-include', 'Yalnız bu dosyalar (isteğe bağlı)', area('includeGlobs'),
        'Satır başına bir desen, en çok 20: *.pdf, raporlar/**/*.docx, {a,b}. Boş: hepsi. Ne yazılırsa yazılsın yalnız PDF, DOCX, TXT ve MD alınır.'),
      excludeGlobs: field('connector-exclude', 'Bunları alma (isteğe bağlı)', area('excludeGlobs'),
        'Satır başına bir desen: arsiv/**, *taslak*. Gizli alt klasörleri burada dışarıda bırakın.'),
      maxFileMb: field('connector-max', 'En büyük dosya (MB)', input('maxFileMb', { inputmode: 'decimal', maxlength: 6, placeholder: String(MAX_FILE_MB) }),
        `Boş bırakılırsa ${MAX_FILE_MB} MB (belge sınırı, aynı zamanda üst sınır). Daha büyük dosyalar atlanır.`),
      scheduleMinutes: field('connector-schedule', 'Eşitleme aralığı (dakika)', input('scheduleMinutes', { inputmode: 'numeric', maxlength: 5 }),
        `${SCHEDULE_MIN} dakika ile ${SCHEDULE_MAX} dakika (1 hafta) arası. Kaydettikten sonra ilk eşitleme hemen başlar.`),
    };
    const save = el('button', { class: 'button', type: 'submit' }, icon('check'), original ? 'Değişiklikleri kaydet' : 'Bağlayıcıyı oluştur');
    fill(formSlot, el('section', { class: 'card', 'aria-labelledby': 'connector-form-title' },
      el('div', { class: 'card-head' }, el('h2', { id: 'connector-form-title', tabindex: '-1' },
        original ? `Düzenle: ${plainName(original.name)}` : 'Yeni bağlayıcı')),
      el('form', { class: 'card-body grid', novalidate: true, onsubmit: async (event) => {
        event.preventDefault();
        const form = Object.fromEntries(Object.entries(fields).map(([key, f]) => [key, f.control.value]));
        form.type = type;
        form.enabled = enabled.checked;
        form.includeAttachments = attachments.box.checked;
        form.skipRestricted = skipRestricted.box.checked;
        const { problems, body } = connectorRequest(form, original);
        for (const [key, f] of Object.entries(fields)) f.show(problems[key]);
        if (!body) {
          fields[Object.keys(problems)[0]]?.control.focus();
          return;
        }
        save.disabled = true;
        const saved = await call(() => (original ? api.updateConnector(original.id, body) : api.createConnector(body)), fields, type);
        save.disabled = false;
        if (!saved || !ctx.isCurrent()) return;
        toast(original ? 'Bağlayıcı kaydedildi; sonraki eşitleme hemen başlar.' : 'Bağlayıcı oluşturuldu; ilk eşitleme birkaç saniye içinde başlar.', 'ok');
        fill(formSlot);
        state.selected = saved;
        await load();
      } },
      el('div', { class: 'connector-field' }, el('label', { class: 'field-label', for: 'connector-type' }, 'Kaynak türü'),
        el('p', { class: 'muted' }, original ? 'Tür sonradan değiştirilemez.' : 'Önce türü seçin; form o türün alanlarını gösterir.'), typePicker),
      el('div', { class: 'connector-form' }, Object.values(fields).map((f) => f.node)),
      type === 'CONFLUENCE' ? attachments.node : null,
      type === 'CONFLUENCE' ? skipRestricted.node : null,
      type === 'CONFLUENCE' && !values.skipRestricted ? el('p', { class: 'notice bad' }, icon('alert', 'sm'),
        'Kısıtlı sayfalar da alınıyor: sayfa kısıtları aktarılmadığı için, kısıtlı bir sayfa bu bilgi alanının bütün okuyucularına açılır.') : null,
      el('label', { class: 'connector-check', for: 'connector-enabled' }, enabled,
        el('span', {}, 'Zamanlama açık (kapalıyken belgeler olduğu gibi kalır, yeni eşitleme olmaz)')),
      el('div', { class: 'page-actions' }, save,
        el('button', { class: 'button secondary', type: 'button', onclick: () => fill(formSlot) }, 'Vazgeç')))));
    formSlot.querySelector('h2')?.focus();
  }

  // ---------- one connector ----------
  function drawDetail(connector) {
    const health = healthBadge(connector.health);
    const latest = state.latest.get(connector.id);
    const pending = pendingApproval(connector, latest);
    const deleting = connector.health === 'DELETING';
    const runsSlot = el('div', {});
    const itemsSlot = el('div', {});
    const syncButton = el('button', { class: 'button', type: 'button', disabled: deleting, onclick: async () => {
      syncButton.disabled = true;
      const done = await call(() => api.syncConnector(connector.id, false));
      syncButton.disabled = false;
      if (done === undefined || !ctx.isCurrent()) return;
      toast('Eşitleme istendi; birkaç saniye içinde başlar. Koşan bir eşitleme varsa o bittikten sonra.', 'ok');
    } }, icon('refresh'), 'Şimdi eşitle');

    fill(detail,
      el('section', { class: 'card', 'aria-labelledby': 'connector-detail' },
        el('div', { class: 'card-head' }, el('h2', { id: 'connector-detail', tabindex: '-1' }, plainName(connector.name)), pill(health)),
        el('div', { class: 'card-body grid' },
          health.help ? el('p', { class: 'muted' }, health.help) : null,
          connector.lastErrorCode ? el('div', { class: `notice ${connector.health === 'FAILING' ? 'bad' : 'wait'}` }, icon('alert', 'sm'),
            el('div', {}, el('strong', {}, 'Son hata'), el('div', {}, codeText(connector.lastErrorCode)))) : null,
          pending ? approvalCard(connector, pending) : null,
          el('dl', { class: 'facts' },
            fact('Tür', TYPES[connector.type] ?? TYPES.SMB),
            ...(connector.type === 'CONFLUENCE' ? confluenceFacts(connector) : [
              fact('Sunucu', `${connector.host}${connector.port && connector.port !== 445 ? `:${connector.port}` : ''}`),
              fact('Paylaşım ve klasör', `${connector.share}${connector.pathPrefix ? ` / ${connector.pathPrefix}` : ' (bütün paylaşım)'}`),
              fact('Servis hesabı', `${connector.domain ? `${connector.domain}\\` : ''}${connector.username}`),
              fact('Parola dosyası', el('span', { class: 'code' }, String(connector.credentialRef ?? '')))]),
            fact('Hedef bilgi alanı', collectionName(connector.collectionId)),
            fact('Aralık', `${scheduleText(connector.scheduleMinutes)}${connector.enabled === false ? ' (kapalı)' : ''}`),
            fact('Son başarılı eşitleme', connector.lastSuccessAt ? formatDate(connector.lastSuccessAt) : 'Hiç'),
            fact('Sonraki eşitleme', connector.nextRunAt ? formatDate(connector.nextRunAt) : '–'),
            fact('İzlenen dosya', formatNumber(trackedCount(connector))),
            fact('Son koşu', latest === undefined ? 'Yükleniyor…' : runSummary(latest))),
          el('div', { class: 'page-actions' }, syncButton,
            el('button', { class: 'button secondary', type: 'button', disabled: deleting, onclick: () => drawForm(connector) }, icon('edit'), 'Düzenle'),
            el('button', { class: 'button secondary', type: 'button', onclick: () => loadRuns(connector, runsSlot, 0) }, icon('history'), 'Koşuları göster'),
            el('button', { class: 'button secondary', type: 'button', onclick: () => loadItems(connector, itemsSlot, '', 0) }, icon('files'), 'Dosyaları göster'),
            el('button', { class: 'button secondary', type: 'button', onclick: () => fill(detail) }, 'Kapat')),
          runsSlot, itemsSlot)),
      deleting ? null : deleteCard(connector));
  }

  function fact(label, value) {
    return [el('dt', {}, label), el('dd', {}, value)];
  }

  /** The Confluence settings of a connector; the token is only named, never shown. */
  function confluenceFacts(connector) {
    const c = connector.confluence ?? {};
    return [
      fact('Site', `${String(c.baseUrl ?? '')} (${DEPLOYMENTS[c.deployment] ?? String(c.deployment ?? '')})`),
      fact('Alanlar', (c.spaceKeys ?? []).join(', ')
        + (c.label ? ` · etiket ${c.label}` : '') + (c.ancestorId ? ` · sayfa ${c.ancestorId} altı` : '')),
      c.deployment === 'CLOUD' ? fact('Hesap', String(connector.username ?? '')) : null,
      fact('Belirteç dosyası', el('span', { class: 'code' }, String(connector.credentialRef ?? ''))),
      fact('Kısıtlı sayfalar', c.skipRestricted === false ? 'Alınıyor (bütün okuyuculara açılır)' : 'Alınmıyor (v1 güvenlik kuralı)'),
      fact('Ekler', c.includeAttachments ? 'PDF ve DOCX ekleri alınıyor' : 'Alınmıyor'),
      fact('Son tam liste ve kısıt taraması', c.lastFullListingAt ? formatDate(c.lastFullListingAt) : 'Henüz yok'),
    ];
  }

  /** The deletion safety held deletions back: count and share, and an explicit typed approval for one run. */
  function approvalCard(connector, pending) {
    const typed = el('input', { class: 'input', type: 'text', inputmode: 'numeric', ...PLAIN, id: 'connector-approve',
      'aria-describedby': 'connector-approve-hint' });
    const approve = el('button', { class: 'button danger', type: 'submit', disabled: true }, icon('trash'), 'Silmeleri onayla ve eşitle');
    typed.addEventListener('input', () => {
      approve.disabled = !approvalConfirmed(pending, typed.value);
    });
    return el('form', { class: 'notice bad grow', onsubmit: async (event) => {
      event.preventDefault();
      if (!approvalConfirmed(pending, typed.value)) return;
      approve.disabled = true;
      const done = await call(() => api.syncConnector(connector.id, true));
      if (!ctx.isCurrent()) return;
      typed.value = '';
      if (done === undefined) return;
      toast('Onay verildi; sonraki koşu bekletilen silmeleri yapar. Onay yalnız o koşu içindir.', 'ok');
      await load();
    } },
    icon('alert', 'sm'),
    el('div', { class: 'grid' },
      el('strong', {}, 'Silme onayı bekliyor'),
      el('div', {}, `Son koşu, kaynakta artık görünmeyen ${pending.exact ? '' : 'en çok '}${formatNumber(pending.count)} dosyayı (izlenen ${formatNumber(pending.tracked)} dosyanın `
        + `yaklaşık %${pending.percent}'i) silecekti. Bir koşu izlenen dosyaların %20'sinden ve 20 dosyadan fazlasını silecekse silme bekletilir: `
        + 'yeniden adlandırılmış bir klasör ya da kaybolan bir izin, bir bilgi alanını tek başına boşaltamaz.'),
      el('div', {}, 'Onaylarsanız bu dosyaların belgeleri sayfaları, parçaları ve vektörleriyle birlikte kalıcı olarak silinir. Önce dosya sunucusunda '
        + 'klasörün gerçekten boşaltıldığını doğrulayın; klasör taşındıysa onaylamak yerine "Düzenle" ile klasör yolunu düzeltin.'),
      el('label', { class: 'field-label', for: 'connector-approve' }, `Onay: dosya sayısını yazın (${pending.count})`),
      el('p', { id: 'connector-approve-hint' }, 'Onay yalnız bir sonraki koşu içindir; sonraki toplu silmeler yeniden onay ister.'),
      typed, el('div', { class: 'page-actions' }, approve)));
  }

  function deleteCard(connector) {
    const typed = el('input', { class: 'input', type: 'text', ...PLAIN, maxlength: 100, id: 'connector-delete-confirm',
      'aria-describedby': 'connector-delete-hint' });
    const remove = el('button', { class: 'button danger', type: 'submit', disabled: true }, icon('trash'), 'Bağlayıcıyı ve belgelerini sil');
    typed.addEventListener('input', () => {
      remove.disabled = !deleteConfirmed(connector, typed.value);
    });
    return el('section', { class: 'card', 'aria-labelledby': 'connector-delete' },
      el('div', { class: 'card-head' }, el('h2', { id: 'connector-delete' }, 'Bağlayıcıyı sil')),
      el('form', { class: 'card-body grid', onsubmit: async (event) => {
        event.preventDefault();
        if (!deleteConfirmed(connector, typed.value)) return;
        remove.disabled = true;
        const done = await call(() => api.deleteConnector(connector.id));
        if (!ctx.isCurrent()) return;
        typed.value = '';
        if (done === undefined) return;
        toast('Bağlayıcı durduruldu; eşitlediği belgeler arka planda siliniyor.', 'ok');
        await load();
      } },
      el('div', { class: 'notice bad' }, icon('alert', 'sm'), el('div', {},
        el('strong', {}, 'Geri alınamaz.'),
        el('div', {}, `Bağlayıcı hemen durur ve eşitlediği ${formatNumber(connector.items?.synced)} belge, sayfaları, parçaları ve vektörleriyle `
          + 'birlikte bilgi alanından silinir. Dosya sunucusundaki dosyalara dokunulmaz. Bu belgelerden alıntı yapan geçmiş cevaplar kendiliğinden '
          + 'değişmez; gerekirse "KVKK başvuruları" ekranından karartın.'))),
      el('label', { class: 'field-label', for: 'connector-delete-confirm' }, 'Onay: bağlayıcının adını yazın'),
      el('p', { class: 'muted', id: 'connector-delete-hint' }, `Listede göründüğü gibi, birebir: ${plainName(connector.name)}`),
      typed, el('div', { class: 'page-actions' }, remove)));
  }

  // ---------- runs and files ----------
  async function loadRuns(connector, slot, page) {
    fill(slot, skeletonRows(2));
    const runs = await call(() => api.listConnectorRuns(connector.id, page, RUN_PAGE_SIZE));
    if (!ctx.isCurrent()) return;
    if (!runs) {
      fill(slot);
      return;
    }
    const rows = runs.data ?? [];
    fill(slot, el('h3', {}, 'Koşular (en yeni önce, son 100)'),
      rows.length ? el('div', { class: 'table-wrap' }, el('table', { class: 'table connector-table' },
        el('caption', { class: 'sr-only' }, 'Eşitleme koşuları'),
        el('thead', {}, el('tr', {}, ['Başlangıç', 'Durum', 'Yeni', 'Değişen', 'Silinen', 'Değişmeyen', 'Başarısız', 'Atlanan', 'Bekleyen', 'Not']
          .map((h) => el('th', { scope: 'col' }, h)))),
        el('tbody', {}, rows.map((run) => el('tr', {},
          el('td', { class: 'when' }, run.startedAt ? formatDate(run.startedAt) : '–'),
          el('td', {}, pill(RUN_STATUS[run.status] ?? { label: String(run.status), tone: 'neutral' })),
          ...['added', 'updated', 'deleted', 'unchanged', 'failed', 'skipped', 'deferred'].map((key) => el('td', { class: 'num' }, formatNumber(run[key]))),
          el('td', {}, [codeText(run.errorCode), run.listingComplete === false ? 'Liste eksik: hiçbir şey silinmedi.' : null]
            .filter(Boolean).join(' '))))))) : el('p', { class: 'muted' }, 'Henüz koşu yok.'),
      pageState(runs.page).total > 1 ? pager(pageState(runs.page), (next) => loadRuns(connector, slot, next)) : null);
  }

  async function loadItems(connector, slot, filter, page) {
    fill(slot, skeletonRows(2));
    const items = await call(() => api.listConnectorItems(connector.id, filter, page, ITEM_PAGE_SIZE));
    if (!ctx.isCurrent()) return;
    if (!items) {
      fill(slot);
      return;
    }
    const picker = el('select', { class: 'input', id: 'connector-item-filter', onchange: () => loadItems(connector, slot, picker.value, 0) },
      [['', 'Hepsi'], ...Object.entries(ITEM_STATES).map(([key, meta]) => [key, meta.label])]
        .map(([value, label]) => el('option', { value, selected: value === filter }, label)));
    const rows = items.data ?? [];
    fill(slot, el('h3', {}, 'İzlenen dosyalar'),
      el('div', { class: 'connector-field' }, el('label', { class: 'field-label', for: 'connector-item-filter' }, 'Duruma göre'), picker),
      el('p', { class: 'muted' }, 'Dosya yolları kişisel veri içerebilir; yalnız bu ekranda gösterilir.'),
      rows.length ? el('div', { class: 'table-wrap' }, el('table', { class: 'table connector-table' },
        el('caption', { class: 'sr-only' }, 'İzlenen dosyalar'),
        el('thead', {}, el('tr', {}, ['Yol', 'Durum', 'Neden', 'Deneme', 'Boyut', 'Değişiklik'].map((h) => el('th', { scope: 'col' }, h)))),
        el('tbody', {}, rows.map((item) => el('tr', {},
          el('td', { class: 'connector-path' }, plainName(item.path ?? '')),
          el('td', {}, pill(ITEM_STATES[item.state] ?? { label: String(item.state), tone: 'neutral' })),
          el('td', {}, codeText(item.failureCode, ITEM_FAILURES),
            item.nextAttemptAt && item.state === 'FAILED' ? el('div', { class: 'muted' }, `Yeniden deneme: ${formatDate(item.nextAttemptAt)}`) : null),
          el('td', { class: 'num' }, formatNumber(item.attempts)),
          el('td', { class: 'num' }, Number.isFinite(item.sizeBytes) ? formatSize(item.sizeBytes) : '–'),
          el('td', { class: 'when' }, item.modifiedAt ? formatDate(item.modifiedAt) : '–')))))) : el('p', { class: 'muted' }, 'Bu durumda dosya yok.'),
      pageState(items.page).total > 1 ? pager(pageState(items.page), (next) => loadItems(connector, slot, filter, next)) : null);
  }

  // ---------- setup help ----------
  function helpCard() {
    return el('section', { class: 'card', 'aria-labelledby': 'connector-help' },
      el('div', { class: 'card-head' }, el('h2', { id: 'connector-help' }, 'Kurulum (kurulum ekibi için)')),
      el('div', { class: 'card-body' },
        el('ol', { class: 'steps-list' },
          el('li', {}, 'Servis hesabı: etki alanında yalnız bu iş için bir hesap açın ve paylaşımda yalnız okuma izni verin (yazma, silme, sahiplik yok). '
            + 'Parolası sunucudaki secrets klasöründe SECRET_CONNECTOR_ ile başlayan bir dosyada durur; panele hiç yazılmaz.'),
          el('li', {}, 'İzinli ağ: .env dosyasında VERSO_CONNECTOR_ALLOWED_NETWORKS ile yalnız dosya sunucusunu izin verin (en dar hâli tek adres, ör. '
            + '10.20.30.40/32). Liste boşsa hiçbir bağlayıcı bağlanamaz; yalnız özel iç ağlar kabul edilir.'),
          el('li', {}, 'Güvenlik duvarı: yığını SMB eki (overlay) ile açın ve host güvenlik duvarında (Linux, DOCKER-USER zinciri) yalnız dosya '
            + 'sunucusunun 445 portuna izin verin; komutlar overlay dosyasının başındadır.'),
          el('li', {}, 'Dosya sunucusunda SMB imzalama açık kalsın; Samba\'da "wide links = no" olsun.'),
          el('li', {}, 'Confluence Data Center/Server: yalnız eşitlenecek alanları okuyabilen bir hesabın kişisel erişim belirtecini secrets klasöründe '
            + 'SECRET_CONNECTOR_CONFLUENCE_TOKEN dosyasına yazın; sunucunun adresini VERSO_CONNECTOR_ALLOWED_NETWORKS ile izin verin; yığını '
            + 'Confluence eki (overlay) ile açın ve güvenlik duvarında yalnız sunucunun 443 portuna izin verin.'),
          el('li', {}, 'Confluence Cloud internet bağlantısı ister: VERSO_CONNECTOR_EXTERNAL_EGRESS=true ve VERSO_CONNECTOR_EXTERNAL_HOSTS=*.atlassian.net. '
            + 'Sıkı local modda (yerel model) uygulama bu ayarla açılmaz; Cloud yalnız bulut modunda kullanılır ve VKED\'de yeni veri kaynağı ve aktarım olarak yazılır.')),
        el('p', { class: 'muted' }, 'Ayrıntılar: ', el('code', {}, DOCS.readme), ', ', el('code', {}, DOCS.overlay), ', ', el('code', {}, DOCS.api),
          ', ', el('code', {}, DOCS.adr), '; Confluence için ', el('code', {}, DOCS.confluenceReadme), ', ', el('code', {}, DOCS.confluenceOverlay),
          ', ', el('code', {}, DOCS.confluenceAdr), '; gecikme alarmı için ', el('code', {}, DOCS.runbook), '.')));
  }

  load(0);

  // Leaving the screen drops the loaded paths, names and form values.
  return () => {
    state = { page: 0, pageInfo: null, connectors: [], latest: new Map(), collections: new Map(), selected: null };
    for (const node of main.querySelectorAll('input, textarea')) node.value = '';
    fill(formSlot);
    fill(detail);
  };
}
