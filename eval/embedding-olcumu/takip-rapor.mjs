// Follow-up retrieval (ADR-0046), last step: node takip-rapor.mjs <olcum.json> <set.json>: hybrid recall@1/@5/MRR and the gate per arm (raw, rw, concat) for the
// follow-ups (and per tag) and the standalone questions; per-question wins and losses rw vs raw.
import { readFileSync } from 'node:fs';
const [olcumPath, setPath] = process.argv.slice(2);
const rows = JSON.parse(readFileSync(olcumPath, 'utf8')).results[0].rows;
const set = new Map(JSON.parse(readFileSync(setPath, 'utf8')).questions.map((q) => [q.id, q]));
const GATE = { t1: 0.47, t2: 0.44, share: 0.045 };
const pass = (r) => r.sim >= GATE.t1 || (r.sim >= GATE.t2 && r.share <= GATE.share);
const r3 = (x) => Math.round(x * 1000) / 1000;
const by = new Map(rows.map((r) => [r.id, r]));
function stats(ids) {
  const list = ids.map((id) => by.get(id)).filter((r) => r && r.a);
  const n = list.length;
  return { n, r1: r3(list.filter((r) => r.rh === 1).length / n), r5: r3(list.filter((r) => r.rh >= 1).length / n),
    hits5: list.filter((r) => r.rh >= 1).length, mrr: r3(list.reduce((s, r) => s + (r.rh ? 1 / r.rh : 0), 0) / n),
    gatePass: list.filter(pass).length };
}
const ids = [...set.keys()];
const base = (suffix, tag) => ids.filter((id) => id.endsWith(`|${suffix}`) && set.get(id).tags.includes(tag));
const out = {};
for (const arm of ['raw', 'rw', 'concat']) out[`followup ${arm}`] = stats(base(arm, 'followup'));
for (const t of ['ellipsis', 'pronoun', 'implicit', 'disambiguation']) {
  for (const arm of ['raw', 'rw', 'concat']) out[`followup ${t} ${arm}`] = stats(base(arm, t).filter((id) => set.get(id).tags.includes('followup')));
}
for (const arm of ['raw', 'rw']) out[`standalone ${arm}`] = stats(base(arm, 'standalone'));
const rewrittenStandalone = ids.filter((id) => id.endsWith('|rw') && set.get(id).tags.includes('standalone') && set.get(id).rewritten);
out['standalone rewritten-only raw'] = stats(rewrittenStandalone.map((id) => id.replace('|rw', '|raw')));
out['standalone rewritten-only rw'] = stats(rewrittenStandalone);
// unanswerable standalone: gate stops
const una = (arm) => ids.filter((id) => id.endsWith(`|${arm}`) && set.get(id).tags.includes('standalone') && !set.get(id).answerable)
  .map((id) => by.get(id)).filter((r) => !pass(r)).length;
out['standalone unanswerable stopped raw/rw'] = `${una('raw')} / ${una('rw')}`;
console.log(JSON.stringify(out, null, 1));
const diff = (tag) => {
  const wins = [], losses = [];
  for (const id of base('raw', tag)) {
    const a = by.get(id), b = by.get(id.replace('|raw', '|rw'));
    if (!a.a) continue;
    if (!(a.rh >= 1) && b.rh >= 1) wins.push(id.split('|')[0]);
    if (a.rh >= 1 && !(b.rh >= 1)) losses.push(id.split('|')[0]);
  }
  return { wins, losses };
};
console.log('followup rw vs raw @5', JSON.stringify(diff('followup')));
console.log('standalone rw vs raw @5', JSON.stringify(diff('standalone')));
