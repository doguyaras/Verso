// Follow-up retrieval (ADR-0046, docs/evidence/takip-sorulari-olcumu.md), step 1:
//   node takip-set.mjs <repo root> <eval/results/followup-on-gpu.json> <out set.json>
// The retrieval queries of each arm as one eval-set shaped file for lex.mjs and olcum.mjs (bge-m3 only), then
// takip-rapor.mjs. ids: <id>|raw (as asked), <id>|rw (the server's rewritten query, from scripts/followup-eval.mjs),
// <id>|concat (naive baseline: first question + follow-up).
import { readFileSync, writeFileSync } from 'node:fs';
const [root, resultPath, outPath] = process.argv.slice(2);
const main = JSON.parse(readFileSync(`${root}/eval/eval-set.json`, 'utf8')).questions;
const byId = new Map(main.map((q) => [q.id, q]));
const fu = JSON.parse(readFileSync(`${root}/eval/followup-set.json`, 'utf8')).conversations;
const result = JSON.parse(readFileSync(resultPath, 'utf8'));
const conv = new Map(result.conversations.map((c) => [c.id, c]));
const st = new Map(result.standalone.map((s) => [s.id, s]));
const questions = [];
for (const c of fu) {
  const base = { answerable: true, document: c.document, pages: c.pages, tags: ['followup', ...c.tags] };
  const rw = conv.get(c.id)?.followUp?.contextQuery;
  questions.push({ id: `${c.id}|raw`, question: c.followUp, ...base });
  questions.push({ id: `${c.id}|rw`, question: rw || c.followUp, rewritten: Boolean(rw), ...base });
  questions.push({ id: `${c.id}|concat`, question: `${byId.get(c.first).question} ${c.followUp}`, ...base });
}
for (const q of main) {
  const base = { answerable: q.answerable, document: q.document, pages: q.pages, tags: ['standalone', ...(q.tags ?? [])] };
  const rw = st.get(q.id)?.contextQuery;
  questions.push({ id: `${q.id}|raw`, question: q.question, ...base });
  questions.push({ id: `${q.id}|rw`, question: rw || q.question, rewritten: Boolean(rw), ...base });
}
writeFileSync(outPath, JSON.stringify({ questions }, null, 1));
console.log(`set: ${questions.length} queries`);
