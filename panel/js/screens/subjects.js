// "KVKK başvuruları" (ADR-0034, ADR-0036): verso-admin answers a data subject request (KVKK art. 11) by the person's
// account id (the token sub): what Verso holds, the JSON export, the deletion with a typed confirmation, and the answer
// redaction for a deleted document. The sub is personal data. It lives only in this screen's memory (never the URL,
// the hash, browser storage or a file name), travels in the API's JSON bodies, and is wiped when the screen is left.
// Everything is built with el(): text, never markup (CSP, Trusted Types). Plain Turkish copy; the runbook is the
// authority for the procedure.
import { api, ApiError } from '../api.js';
import { icon } from '../icons.js';
import { el, fill, formatDate, formatLabel, plainName } from '../render.js';
import { deleteConfirmed, deletionModel, exportFileName, redactionConfirmed, RUNBOOK, subProblem, summaryModel } from '../subjects.js';
import { emptyState, formatNumber, helpLine, statusPill, toast } from '../ui.js';

/** Fields that hold a sub or a document id: no autofill history, no spell check, no capitalisation. */
const PLAIN_INPUT = { class: 'input', type: 'text', autocomplete: 'off', spellcheck: 'false', autocapitalize: 'off' };

export function renderSubjects(ctx) {
  const { main, withAuth } = ctx;
  let subject = null; // the sub whose summary is shown; the only copy outside the inputs

  const subInput = el('input', { ...PLAIN_INPUT, maxlength: 255, id: 'subject-sub', 'aria-describedby': 'subject-sub-hint subject-sub-error',
    placeholder: 'Örn. 3f2a…-…' });
  const subError = el('p', { class: 'field-error', id: 'subject-sub-error', role: 'alert' });
  const findButton = el('button', { class: 'button', type: 'submit' }, icon('search'), 'Özeti getir');
  const result = el('div', { class: 'grid subject-result' });
  const blocked = el('div', {});

  const redactId = el('input', { ...PLAIN_INPUT, maxlength: 36, id: 'redact-id', placeholder: 'Silinmiş belgenin kimliği' });
  const redactConfirm = el('input', { ...PLAIN_INPUT, maxlength: 36, id: 'redact-confirm', placeholder: 'Kimliği yeniden yazın' });
  const redactButton = el('button', { class: 'button danger', type: 'submit', disabled: true }, icon('trash'), 'Cevapları karart');
  const redactResult = el('p', { class: 'muted', role: 'status', 'aria-live': 'polite' });
  const syncRedact = () => {
    redactButton.disabled = !redactionConfirmed(redactId.value, redactConfirm.value);
  };
  redactId.addEventListener('input', syncRedact);
  redactConfirm.addEventListener('input', syncRedact);

  main.replaceChildren(
    el('div', { class: 'page-head' },
      el('div', {}, el('h1', {}, 'KVKK ve veri'),
        helpLine('Bir kişinin verisini bulun, dışa aktarın ya da silin (KVKK md. 11).', 'Bir kişinin bu sistemde tutulan verisini hesap kimliğiyle bulun, JSON olarak dışa aktarın ya da silin (KVKK md. 11). '
          + 'Kişi yalnız hesap kimliğiyle bulunur; belge metninde ad araması yoktur. Her işlem denetim kaydına yazılır.'))),
    blocked,
    el('section', { class: 'card', 'aria-labelledby': 'subject-find' },
      el('div', { class: 'card-head' }, el('h2', { id: 'subject-find' }, 'Kişiyi bulun')),
      el('form', { class: 'card-body grid', onsubmit: (event) => {
        event.preventDefault();
        const value = subInput.value;
        const problem = subProblem(value);
        subError.textContent = problem ?? '';
        subInput.setAttribute('aria-invalid', String(Boolean(problem)));
        if (!problem) lookup(value);
      } },
      el('label', { class: 'field-label', for: 'subject-sub' }, 'Hesap kimliği (sub)'),
      el('p', { class: 'muted', id: 'subject-sub-hint' }, 'Kimlik sağlayıcıdaki kullanıcı kimliği (Keycloak: Kullanıcılar → kullanıcı → ID). '
        + 'Ad, e-posta ya da sicil numarası değil. Başvuranın kimliğini doğrulamak kurumun işidir.'),
      subInput, subError, el('div', { class: 'page-actions' }, findButton))),
    result,
    el('section', { class: 'card', 'aria-labelledby': 'redact-title' },
      el('div', { class: 'card-head' }, el('h2', { id: 'redact-title' }, 'Silinmiş belgeden alıntı yapan cevapları karart')),
      el('form', { class: 'card-body grid', onsubmit: async (event) => {
        event.preventDefault();
        const documentId = redactId.value;
        if (!redactionConfirmed(documentId, redactConfirm.value)) return;
        redactButton.disabled = true;
        const done = await call(() => api.redactAnswers(documentId, redactConfirm.value));
        syncRedact();
        if (!done || !ctx.isCurrent()) return;
        redactResult.textContent = done.answersRedacted
          ? `${formatNumber(done.answersRedacted)} cevap sabit notla değiştirildi.`
          : 'Değiştirilecek cevap yoktu (ya hiç alıntılanmamış ya da daha önce karartılmış).';
        redactId.value = '';
        redactConfirm.value = '';
        syncRedact();
      } },
      el('div', { class: 'notice bad' }, icon('alert', 'sm'), el('div', {},
        el('strong', {}, 'Geri alınamaz.'),
        el('div', {}, 'Bu belgeyi alıntılayan, bütün hesaplardaki cevap metinleri şu notla değiştirilir: "Bu cevap, alıntı yaptığı '
          + 'belge silindiği için yönetici tarafından kaldırıldı." Soru, kaynak ("silinmiş" görünür), puan ve sayaçlar kalır. '
          + 'Yalnız silinmiş bir belge için çalışır; belge duruyorsa önce "Bilgi kaynakları"ndan silin.'))),
      el('label', { class: 'field-label', for: 'redact-id' }, 'Belge kimliği'), redactId,
      el('label', { class: 'field-label', for: 'redact-confirm' }, 'Onay: aynı kimliği yeniden yazın'), redactConfirm,
      el('div', { class: 'page-actions' }, redactButton), redactResult)),
    el('section', { class: 'card', 'aria-labelledby': 'subject-runbook' },
      el('div', { class: 'card-head' }, el('h2', { id: 'subject-runbook' }, 'Adım adım')),
      el('div', { class: 'card-body' },
        el('ol', { class: 'steps-list' },
          el('li', {}, 'Silme başvurusunda önce kimlik sağlayıcıda hesabı kapatın ve 5 dakika bekleyin: açık oturum bu süre içinde soru sorabilir.'),
          el('li', {}, 'Özeti getirin; neyin silineceğini, neyin kalacağını görün.'),
          el('li', {}, 'Erişim başvurusunda dışa aktarımı indirin ve başvuru dosyasında kurumun kişisel veri kurallarıyla saklayın.'),
          el('li', {}, 'Silme başvurusunda kişinin hesap kimliğini onay alanına yeniden yazıp silin. Yarıda kalırsa aynı isteği tekrarlayın.'),
          el('li', {}, 'Kalan bilgi alanı belgeleri için belgeyi tanıyan birimle karar verin; gerekirse belgeyi silip cevapları karartın.'),
          el('li', {}, 'Yapılanları ve tarihleri başvuru dosyasına yazın; başvuru en geç 30 gün içinde yanıtlanır.')),
        el('p', { class: 'muted' }, 'Ayrıntılar ve hukuki notlar işletim belgesindedir: ', el('code', {}, RUNBOOK), '.'))));

  /** An API call: 403 shows the admin-only state, 401 starts the sign-in, any other error is a toast. */
  async function call(action) {
    try {
      return await withAuth(action);
    } catch (error) {
      if (!ctx.isCurrent()) return undefined;
      if (error instanceof ApiError && error.status === 403) {
        fill(blocked, emptyState('lock', 'Bu ekran yöneticiler içindir', 'KVKK başvurularını yalnız verso-admin rolü yürütür.'));
        fill(result);
      } else if (!(error instanceof ApiError && error.status === 401)) {
        toast(error instanceof ApiError ? error.message : 'Bağlantı kurulamadı.');
      }
      return undefined;
    }
  }

  async function lookup(sub) {
    findButton.disabled = true;
    const summary = await call(() => api.subjectSummary(sub));
    findButton.disabled = false;
    if (!summary || !ctx.isCurrent()) return;
    subject = sub;
    drawSummary(summaryModel(summary));
  }

  function rows(items) {
    return el('dl', { class: 'facts' }, items.flatMap((row) => [el('dt', {}, row.label), el('dd', { class: 'tabular' }, formatNumber(row.value))]));
  }

  function documentTable(docs, caption) {
    return el('div', { class: 'table-wrap' }, el('table', { class: 'table' },
      el('caption', { class: 'sr-only' }, caption),
      el('thead', {}, el('tr', {}, ['Dosya', 'Tür', 'Durum', 'Yüklenme', 'Belge kimliği'].map((h) => el('th', { scope: 'col' }, h)))),
      el('tbody', {}, docs.map((doc) => el('tr', {},
        el('td', {}, plainName(doc.fileName)), el('td', {}, formatLabel(doc.format)), el('td', {}, statusPill(doc.status)),
        el('td', {}, doc.createdAt ? formatDate(doc.createdAt) : '–'), el('td', {}, el('span', { class: 'code' }, String(doc.id))))))));
  }

  function drawSummary(model) {
    const confirm = el('input', { ...PLAIN_INPUT, maxlength: 255, id: 'subject-confirm', 'aria-describedby': 'subject-confirm-hint' });
    const remove = el('button', { class: 'button danger', type: 'submit', disabled: true }, icon('trash'), 'Kalıcı olarak sil');
    confirm.addEventListener('input', () => {
      remove.disabled = !deleteConfirmed(subject, confirm.value);
    });
    const outcome = el('div', { role: 'status', 'aria-live': 'polite' });
    fill(result,
      el('section', { class: 'card', 'aria-labelledby': 'subject-summary' },
        el('div', { class: 'card-head' }, el('h2', { id: 'subject-summary' }, 'Bulunanlar')),
        el('div', { class: 'card-body grid' },
          model.nothingFound ? el('div', { class: 'notice neutral' }, icon('info', 'sm'),
            'Bu hesap kimliğiyle kayıt bulunmadı. Kimliği kimlik sağlayıcıdan yeniden kopyalayın; kişi başka bir bilgiyle aranmaz.') : null,
          model.truncated ? el('div', { class: 'notice wait' }, icon('alert', 'sm'),
            'Belge listeleri 1 000 kayıtta kesildi; sayılar kesindir. Tam liste dışa aktarımdadır.') : null,
          el('h3', {}, 'Silmede silinir'), rows(model.deleted),
          model.personalDocuments.length ? documentTable(model.personalDocuments, 'Silinecek kişisel belgeler') : null,
          el('h3', {}, 'Silmede kalır ve raporlanır'), rows(model.kept),
          el('p', { class: 'muted' }, 'Kişinin bilgi alanlarına yüklediği belgeler kurumundur; tek tek karar verilir. Adıyla verilmiş erişimler '
            + 've oluşturduğu alanlar "Bilgi alanları" ekranından kaldırılır. Denetim kaydı değiştirilemez; başvuru da yeni bir satır olarak yazılır.'))),
      model.collectionDocuments.length ? el('section', { class: 'card', 'aria-labelledby': 'subject-kept' },
        el('div', { class: 'card-head' }, el('h2', { id: 'subject-kept' }, 'Kalan bilgi alanı belgeleri')),
        el('div', { class: 'card-body grid' },
          el('p', { class: 'muted' }, 'Bu belgeler silmede kalır. Kişiye ait veri içeren belgeyi "Bilgi kaynakları"ndan silin; sonra kimliğini '
            + 'aşağıdaki karartma alanına yazarak onu alıntılayan cevapları değiştirin.'),
          documentTable(model.collectionDocuments, 'Silmede kalan bilgi alanı belgeleri'))) : null,
      el('section', { class: 'card', 'aria-labelledby': 'subject-export' },
        el('div', { class: 'card-head' }, el('h2', { id: 'subject-export' }, 'Erişim başvurusu: dışa aktar')),
        el('div', { class: 'card-body grid' },
          el('p', { class: 'muted' }, 'Konuşmalar, sorular ve cevaplar, puanlar, belge listeleri ve denetim satırları tek bir JSON dosyasında. '
            + 'Dosya kişisel veri taşır; başvuru dosyasında saklayın, paylaşılan klasöre koymayın.'),
          el('div', { class: 'page-actions' }, el('button', { class: 'button secondary', type: 'button', onclick: (event) => exportNow(event.currentTarget) },
            icon('download'), 'JSON olarak indir')))),
      el('section', { class: 'card', 'aria-labelledby': 'subject-delete' },
        el('div', { class: 'card-head' }, el('h2', { id: 'subject-delete' }, 'Silme başvurusu')),
        el('form', { class: 'card-body grid', onsubmit: async (event) => {
          event.preventDefault();
          if (!deleteConfirmed(subject, confirm.value)) return;
          remove.disabled = true;
          const done = await call(() => api.deleteSubject(subject, confirm.value));
          if (!ctx.isCurrent()) return;
          confirm.value = '';
          if (!done) return;
          drawDeletion(outcome, deletionModel(done));
        } },
        el('div', { class: 'notice bad' }, icon('alert', 'sm'), el('div', {},
          el('strong', {}, 'Geri alınamaz. Silinecekler:'),
          el('ul', {}, model.deleted.map((row) => el('li', {}, `${row.label}: ${formatNumber(row.value)}`))),
          el('strong', {}, 'Kalacak ve raporlanacaklar:'),
          el('ul', {}, model.kept.map((row) => el('li', {}, `${row.label}: ${formatNumber(row.value)}`))),
          el('div', {}, 'Başka hesapların bu kişisel belgelerden alıntı yapan cevapları, belgeler silinmeden önce sabit notla değiştirilir. '
            + 'Önce kimlik sağlayıcıda hesabı kapatın.'))),
        el('label', { class: 'field-label', for: 'subject-confirm' }, 'Onay: kişinin hesap kimliğini yeniden yazın'),
        el('p', { class: 'muted', id: 'subject-confirm-hint' }, 'Yukarıda aradığınız kimlikle birebir aynı olmalı; büyük-küçük harf de.'),
        confirm, el('div', { class: 'page-actions' }, remove), outcome)));
  }

  function drawDeletion(slot, model) {
    fill(slot, el('div', { class: `notice ${model.repeated ? 'neutral' : 'info'}` }, icon(model.repeated ? 'info' : 'checkCircle', 'sm'), el('div', {},
      el('strong', {}, model.repeated ? 'Silinecek bir şey kalmamıştı.' : 'Silme tamamlandı.'),
      el('ul', {}, model.rows.map((row) => el('li', {}, `${row.label}: ${formatNumber(row.value)}`))),
      el('div', {}, `Kalan: ${model.kept.kept.map((row) => `${row.label.toLocaleLowerCase('tr')} ${formatNumber(row.value)}`).join(', ')}.`))));
    toast('Silme başvurusu işlendi.', 'ok');
  }

  async function exportNow(button) {
    if (!subject) return;
    button.disabled = true;
    const done = await call(() => api.exportSubject(subject));
    button.disabled = false;
    if (!done || !ctx.isCurrent()) return;
    const url = URL.createObjectURL(done.blob);
    const anchor = el('a', { href: url, download: exportFileName(), hidden: true });
    document.body.append(anchor);
    anchor.click();
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    if (done.truncated) toast('Dışa aktarım sınıra takıldı ve kesildi (2 000 konuşma ya da 100 000 denetim satırı). Runbook\'a bakın.', 'info');
    else toast('Dışa aktarım indirildi.', 'ok');
  }

  // Leaving the screen wipes the sub and every field that held it: nothing personal outlives the screen.
  return () => {
    subject = null;
    for (const input of main.querySelectorAll('input')) input.value = '';
    subInput.value = '';
    redactId.value = '';
    redactConfirm.value = '';
    fill(result);
  };
}
