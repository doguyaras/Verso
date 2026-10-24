// The AI-interaction disclosure (EU AI Act Art. 50, ADR-0027): plain strings only, no DOM, so a test can pin them.
// The installation's product name is passed in and is never followed by a Turkish case suffix (ADR-0021).

/** The line that stays next to the question box on every visit. */
export function disclosureLine(productName) {
  return `Yapay zekâ ile yazışıyorsunuz (${productName}); cevaplar hatalı olabilir, kaynağını kontrol edin.`;
}

/** The first-use notice: a title and a body, shown once per browser session until it is dismissed. */
export function disclosureNotice(productName) {
  return {
    title: 'Yapay zekâ ile yazışıyorsunuz',
    body: `${productName} bir yapay zekâ asistanıdır, bir kişi değildir. Cevapları belgelerinizden üretir ve kaynağını gösterir; `
      + 'yine de hatalı olabilir, önemli bilgileri kaynağından kontrol edin. Gerekmedikçe soruya kişisel veri yazmayın.',
    dismiss: 'Anladım',
  };
}

/** The last line of an exported answer: says in plain text that a model wrote it. */
export function generatedFooter(productName) {
  return productName ? `Yapay zekâ ile üretildi · ${productName}` : 'Yapay zekâ ile üretildi';
}
