// "Bilgi alanları" (ADR-0028): verso-admin creates shared collections and grants them to IdP groups (by the group
// claim value, e.g. a Keycloak full path) or to single users (by the token's sub). Documents are uploaded into a
// collection from the "Bilgi kaynakları" screen. The admin manages every collection but reads (and asks) only the ones
// granted to the admin's own groups or sub: least privilege, decided on the server. The server enforces the role (403
// for everyone else); the menu only hides the screen. Everything is built with el(): text, never markup (CSP).
import { api } from '../api.js';
import { icon } from '../icons.js';
import { el, fill, formatDate, plainName } from '../render.js';
import { confirmDialog, emptyState, formatNumber, toast } from '../ui.js';

/** The grant a form describes, or null when it cannot be one (the server checks the same: 1-256 characters). */
export function grantInput(type, value) {
  const principal = String(value ?? '');
  if (!['GROUP', 'USER'].includes(type) || !principal.trim() || principal.length > 256) return null;
  return { principalType: type, principal };
}

/** How a grant reads in the list: what kind and the exact value, never trimmed or case-folded. */
export function grantLabel(grant) {
  return `${grant.principalType === 'GROUP' ? 'Grup' : 'Kullanıcı'}: ${plainName(grant.principal)}`;
}

/** Control and format characters (bidi overrides among them): the server refuses them in names and descriptions. */
const HIDDEN_CHARACTERS = /[\p{Cc}\p{Cf}]/u;

/**
 * A rename or description change as PUT /v1/collections/{id} takes it, or the reason it cannot be sent: the server's
 * rules (name 1-100 after trimming, description at most 500, no control or bidi characters), and an unchanged form is
 * not sent at all. An empty description clears it.
 */
export function collectionEdit(original, name, description) {
  const newName = String(name ?? '').trim();
  const newDescription = String(description ?? '').trim();
  if (!newName) return { problem: 'Ad boş olamaz.', body: null };
  if (newName.length > 100) return { problem: 'Ad en çok 100 karakterdir.', body: null };
  if (newDescription.length > 500) return { problem: 'Açıklama en çok 500 karakterdir.', body: null };
  if (HIDDEN_CHARACTERS.test(newName) || HIDDEN_CHARACTERS.test(newDescription)) {
    return { problem: 'Ad ve açıklama görünmeyen denetim karakteri içeremez.', body: null };
  }
  if (newName === (original?.name ?? '') && newDescription === (original?.description ?? '')) {
    return { problem: 'Değişiklik yok.', body: null };
  }
  return { problem: null, body: { name: newName, description: newDescription || null } };
}

export function renderCollections(ctx) {
  const { main, guarded } = ctx;
  const list = el('div', { class: 'card-body grid' }, el('div', { class: 'skeleton line' }));
  const name = el('input', { class: 'input', type: 'text', maxlength: 100, required: true, 'aria-label': 'Bilgi alanı adı',
    placeholder: 'Örn. Hukuk sözleşmeleri' });
  const description = el('input', { class: 'input', type: 'text', maxlength: 500, 'aria-label': 'Açıklama (isteğe bağlı)',
    placeholder: 'Açıklama (isteğe bağlı)' });

  main.replaceChildren(
    el('div', { class: 'page-head' },
      el('div', {}, el('h1', {}, 'Erişim ve roller'),
        el('p', {}, 'Paylaşılan belgeler bir bilgi alanında durur. Bir alanı okuyabilenler, alana eklediğiniz gruplar ve kullanıcılardır; '
          + 'izin her soruda kimlik sağlayıcının verdiği gruplarla denetlenir. Grup değişiklikleri kullanıcının bir sonraki oturum '
          + 'anahtarında (en çok 5 dakika) geçerli olur. Yönetici alanları yönetir; içeriği sormak için kendisinin de yetkili olması gerekir.')),
      el('div', { class: 'page-actions' },
        el('button', { class: 'button secondary', type: 'button', onclick: () => refresh() }, icon('refresh'), 'Yenile'))),
    el('section', { class: 'card', 'aria-labelledby': 'collection-new' },
      el('div', { class: 'card-head' }, el('h2', { id: 'collection-new' }, 'Yeni bilgi alanı')),
      el('form', { class: 'card-body grid', onsubmit: async (event) => {
        event.preventDefault();
        if (!name.value.trim()) return;
        const created = await guarded(() => api.createCollection(name.value.trim(), description.value.trim()));
        if (!created) return;
        name.value = '';
        description.value = '';
        toast('Bilgi alanı oluşturuldu.', 'ok');
        refresh();
      } }, name, description, el('button', { class: 'button', type: 'submit' }, icon('plus'), 'Oluştur'))),
    el('section', { class: 'card', 'aria-label': 'Bilgi alanları' }, list));

  function grantForm(collection, grants) {
    const type = el('select', { class: 'input', 'aria-label': 'Erişim türü' },
      el('option', { value: 'GROUP' }, 'Grup (tam yol)'), el('option', { value: 'USER' }, 'Kullanıcı (sub)'));
    const value = el('input', { class: 'input', type: 'text', maxlength: 256, 'aria-label': 'Grup yolu ya da kullanıcı kimliği',
      placeholder: '/hukuk/sozlesmeler' });
    return el('form', { class: 'page-actions', onsubmit: async (event) => {
      event.preventDefault();
      const input = grantInput(type.value, value.value);
      if (!input) return;
      const added = await guarded(() => api.addGrant(collection.id, input.principalType, input.principal));
      if (!added) return;
      value.value = '';
      grants.append(grantRow(collection, added, grants));
    } }, type, value, el('button', { class: 'button secondary', type: 'submit' }, icon('plus'), 'Erişim ver'));
  }

  function grantRow(collection, grant, container) {
    const row = el('li', { class: 'connector' },
      el('span', { class: 'connector-icon' }, icon(grant.principalType === 'GROUP' ? 'building' : 'user', 'sm')),
      el('div', { class: 'grow' }, el('strong', {}, grantLabel(grant)), el('span', { class: 'sub' }, formatDate(grant.createdAt))),
      el('button', { class: 'icon-button', type: 'button', title: 'Erişimi kaldır', 'aria-label': `${grantLabel(grant)} erişimini kaldır`,
        onclick: async () => {
          const ok = await confirmDialog({ title: 'Erişim kaldırılsın mı?', confirm: 'Kaldır',
            message: 'Bu gruptaki ya da bu kullanıcıdaki kişiler, bir sonraki isteklerinden itibaren bu alanın belgelerini göremez ve soramaz.' });
          if (!ok) return;
          const done = await guarded(async () => {
            await api.removeGrant(collection.id, grant.id);
            return true;
          });
          if (done) row.remove();
        } }, icon('trash', 'sm')));
    container?.append?.(row);
    return row;
  }

  async function details(collection, slot) {
    const grants = await guarded(() => api.listGrants(collection.id));
    if (!grants || !ctx.isCurrent()) return;
    const items = el('ul', { class: 'connectors', 'aria-label': `${plainName(collection.name)} erişimleri` });
    grants.forEach((g) => items.append(grantRow(collection, g)));
    fill(slot, grants.length ? null : el('p', { class: 'muted' }, 'Henüz kimseye erişim verilmedi; bu alanın belgeleri kimseye görünmez.'),
      items, grantForm(collection, items));
  }

  function card(collection) {
    const slot = el('div', {});
    const editSlot = el('div', {});
    const title = plainName(collection.name);
    return el('article', { class: 'card' },
      el('div', { class: 'card-head' }, el('h2', {}, title), el('span', { class: 'spacer' }),
        el('span', { class: 'pill neutral' }, `${formatNumber(collection.documentCount)} belge`),
        collection.readable ? el('span', { class: 'pill ok', title: 'Size de erişim verilmiş' }, 'Okuyabiliyorsunuz') : null,
        el('button', { class: 'icon-button', type: 'button', title: 'Bilgi alanını sil', 'aria-label': `${title} bilgi alanını sil`,
          onclick: async () => {
            const ok = await confirmDialog({ title: `"${title}" silinsin mi?`, confirm: 'Sil',
              message: 'Alan ve erişimleri silinir. Alanda belge varsa silinmez; önce belgeleri taşıyın ya da silin.' });
            if (!ok) return;
            const done = await guarded(async () => {
              await api.deleteCollection(collection.id);
              return true;
            });
            if (!done) return;
            toast('Bilgi alanı silindi.', 'ok');
            refresh();
          } }, icon('trash', 'sm'))),
      el('div', { class: 'card-body' },
        collection.description ? el('p', { class: 'muted' }, plainName(collection.description)) : null,
        editSlot,
        el('div', { class: 'page-actions' },
          el('button', { class: 'button ghost sm', type: 'button', 'aria-expanded': 'false', onclick: (event) => {
            const open = event.currentTarget.getAttribute('aria-expanded') !== 'true';
            event.currentTarget.setAttribute('aria-expanded', String(open));
            fill(editSlot, open ? editForm(collection) : null);
          } }, icon('edit', 'sm'), 'Adı ve açıklamayı düzenle'),
          el('button', { class: 'button ghost sm', type: 'button', onclick: (event) => {
            event.currentTarget.remove();
            details(collection, slot);
          } }, icon('key', 'sm'), 'Erişimleri göster')), slot));
  }

  /** Rename and description (PUT /v1/collections/{id}); a taken name is 409 10040, shown as a toast. */
  function editForm(collection) {
    const title = plainName(collection.name);
    const newName = el('input', { class: 'input', type: 'text', maxlength: 100, required: true, value: collection.name,
      'aria-label': `${title} için yeni ad` });
    const newDescription = el('input', { class: 'input', type: 'text', maxlength: 500, value: collection.description ?? '',
      'aria-label': `${title} için açıklama (boş bırakılırsa silinir)`, placeholder: 'Açıklama (isteğe bağlı)' });
    const problem = el('p', { class: 'field-error', role: 'alert' });
    return el('form', { class: 'grid', onsubmit: async (event) => {
      event.preventDefault();
      const edit = collectionEdit(collection, newName.value, newDescription.value);
      problem.textContent = edit.problem ?? '';
      if (!edit.body) return;
      const saved = await guarded(() => api.updateCollection(collection.id, edit.body.name, edit.body.description));
      if (!saved) return;
      toast('Bilgi alanı güncellendi.', 'ok');
      refresh();
    } }, newName, newDescription, problem,
    el('div', { class: 'page-actions' }, el('button', { class: 'button', type: 'submit' }, icon('check'), 'Kaydet')));
  }

  async function refresh() {
    const page = await guarded(() => api.listCollections());
    if (!page || !ctx.isCurrent()) return;
    // A non-admin who opens the route anyway sees only granted collections and cannot manage them (server: 403).
    if (!(page.data ?? []).every((c) => c.manageable)) {
      fill(list, emptyState('lock', 'Bu ekran yöneticiler içindir', 'Bilgi alanlarını yalnız verso-admin rolü yönetir.'));
      return;
    }
    const collections = page.data ?? [];
    fill(list, collections.length ? collections.map(card)
      : emptyState('layers', 'Henüz bilgi alanı yok', 'Yukarıdan bir alan oluşturun, sonra bir gruba erişim verin.'));
  }

  refresh();
  return () => {};
}
