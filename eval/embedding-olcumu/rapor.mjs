// Step 3: prints the markdown tables of docs/evidence/embedding-olcumu.md from the res-*.json files of olcum.mjs.
// Usage: node rapor.mjs <res-1.json> [res-2.json ...]; the first result named "bge-m3 (taban)" is the baseline.
import { readFileSync } from 'node:fs';

const all = process.argv.slice(2).flatMap((f) => JSON.parse(readFileSync(f, 'utf8')).results);
// Later files win for the same variant name (the timing re-runs).
const byName = new Map();
for (const r of all) if (!r.error) byName.set(r.name, r);
const list = [...byName.values()];
const base = byName.get('bge-m3 (taban)');
const f3 = (x) => String(x).replace('.', ',');
const f2 = (x) => x.toFixed(3).replace('.', ',');

console.log('\n### Retrieval (112 cevaplanabilir)\n');
console.log('| Varyant | Boyut | Vektör r@1 | Vektör r@5 | Vektör MRR | Hibrit r@1 | Hibrit r@5 | Hibrit MRR | paraphrase r@5 (vektör / hibrit) | hibrit r@1 +/- | hibrit r@5 +/- |');
console.log('|---|---|---|---|---|---|---|---|---|---|---|');
for (const r of list) {
  const pair = (key, k) => {
    let up = 0, down = 0;
    r.rows.forEach((row, i) => {
      if (!row.a) return;
      const b = base.rows[i][key];
      const x = k === 1 ? (row[key] === 1) : (row[key] >= 1);
      const y = k === 1 ? (b === 1) : (b >= 1);
      if (x && !y) up++; if (!x && y) down++;
    });
    return `+${up}/-${down}`;
  };
  const v = r.vector.all, h = r.hybrid.all;
  console.log(`| ${r.name} | ${r.dimensions} | ${f2(v.r1)} | ${f2(v.r5)} | ${f2(v.mrr)} | ${f2(h.r1)} | ${f2(h.r5)} | ${f2(h.mrr)} | ${f2(r.vector.paraphrase.r5)} / ${f2(r.hybrid.paraphrase.r5)} | ${pair('rh', 1)} | ${pair('rh', 5)} |`);
}

console.log('\n### En iyi pasajın cosine benzerliği (hibrit ilk 5; min / ortanca / maks) ve kapı\n');
console.log('| Varyant | Cevaplanabilir | Cevaplanamaz | Konu-içi cevaplanamaz | AUC | Mevcut eşiklerle (0,47 / 0,44 / 0,045): geçen / duran | Tek eşik T: geçen / duran | Izgara (kendi seti) T1 / T2 / pay: duran |');
console.log('|---|---|---|---|---|---|---|---|');
const d = (x) => `${f2(x.min)} / ${f2(x.median)} / ${f2(x.max)}`;
for (const r of list) {
  const g = r.gateGrid;
  console.log(`| ${r.name} | ${d(r.sim.answerable)} | ${d(r.sim.unanswerable)} | ${d(r.sim.ontopic)} | ${f2(r.auc)} | ${r.gateCurrent.answerablePass}/112 / ${r.gateCurrent.unanswerableStopped}/28 | ${f2(r.gateSingle.t1)}: ${r.gateSingle.answerablePass}/112 / ${r.gateSingle.unanswerableStopped}/28 | ${f2(g.t1)} / ${f2(g.t2)} / ${f2(g.share)}: ${g.unanswerableStopped}/28 |`);
}

console.log('\n### Gecikme (GPU, Ollama)\n');
console.log('| Varyant | Sorgu p50 (ms) | Sorgu p95 (ms) | Sorgu maks (ms) | İndeks, 75 chunk (ms; en hızlı / üç koşu) |');
console.log('|---|---|---|---|---|');
for (const r of list) {
  console.log(`| ${r.name} | ${f3(r.queryMs.p50)} | ${f3(r.queryMs.p95)} | ${f3(Math.round(r.queryMs.max))} | ${r.indexMs} / ${(r.indexRunsMs || [r.indexMs]).join(', ')} |`);
}

console.log('\n### Hibrit, soru türü başına recall@5 / recall@1\n');
const tags = ['version1', 'version2', 'abbreviation', 'number', 'table', 'disambiguation', 'paraphrase'];
console.log(`| Varyant | ${tags.join(' | ')} |`);
console.log(`|---|${tags.map(() => '---').join('|')}|`);
for (const r of list) console.log(`| ${r.name} | ${tags.map((t) => `${f2(r.hybrid[t].r5)} / ${f2(r.hybrid[t].r1)}`).join(' | ')} |`);
