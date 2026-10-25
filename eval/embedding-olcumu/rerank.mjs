// Reranker measurement (ADR-0043, docs/evidence/rerank-olcumu.md). Offline: reproduces the production hybrid retrieval
// (cosine top 20 + lexical top 20 from lex.mjs, RRF k = 60, ties by cosine) from stored bge-m3 vectors, then sends the
// fused top N to a cross-encoder served by HF TEI (POST /rerank) and keeps the best 5. Reports recall@1/@5 and MRR per
// question type, what the answerability gate decides (ADR-0022: it keeps reading cosine; see "gate" below) and the
// added latency of each rerank call. Nothing is written to any database; passage text only travels to the reranker.
// Usage: node rerank.mjs <lex.json> <emb.json> <eval-set.json> <out.json> <label>   (RERANK_URL, default http://127.0.0.1:18089)
//   emb.json: {docs: [[...75 vectors]], qs: [[...140 vectors]]}, bge-m3:567m, same order as lex.json chunks and the set.
import { readFileSync, writeFileSync } from 'node:fs';

const [lexPath, embPath, setPath, outPath, label] = process.argv.slice(2);
const URL_ = process.env.RERANK_URL || 'http://127.0.0.1:18089';
const PASSES = Number(process.env.PASSES || 3);
const lex = JSON.parse(readFileSync(lexPath, 'utf8'));
const emb = JSON.parse(readFileSync(embPath, 'utf8'));
const set = JSON.parse(readFileSync(setPath, 'utf8')).questions;
const lexById = new Map(lex.questions.map((q) => [q.id, q]));
const RRF_K = 60, CAND = 20, TOPK = 5;
const GATE = { t1: 0.47, t2: 0.44, share: 0.045 }; // config/verso.yml (ADR-0022)
const norm = (v) => { const n = Math.sqrt(v.reduce((s, x) => s + x * x, 0)); return v.map((x) => x / n); };
const dot = (a, b) => { let s = 0; for (let i = 0; i < a.length; i++) s += a[i] * b[i]; return s; };
const pct = (a, p) => { const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.ceil(s.length * p) - 1)]; };
const r3 = (x) => Math.round(x * 1000) / 1000;
const D = emb.docs.map(norm), Q = emb.qs.map(norm);

async function rerank(query, texts) {
  const t = performance.now();
  const res = await fetch(`${URL_}/rerank`, { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ query, texts, truncate: true }) });
  if (!res.ok) throw new Error(`rerank ${res.status} ${await res.text()}`);
  const body = await res.json(); // [{index, score}], best first
  return { ms: performance.now() - t, order: body.map((r) => r.index), scores: body };
}

function fusedOf(q, qi) {
  const sims = D.map((d) => dot(Q[qi], d));
  const order = sims.map((s, i) => [s, lex.chunks[i].id]).sort((a, b) => b[0] - a[0] || a[1] - b[1]);
  const vecTop = order.slice(0, CAND).map(([, id]) => id);
  const l = lexById.get(q.id);
  const score = new Map();
  vecTop.forEach((id, i) => score.set(id, (score.get(id) || 0) + 1 / (RRF_K + i + 1)));
  l.lex.forEach((id, i) => score.set(id, (score.get(id) || 0) + 1 / (RRF_K + i + 1)));
  const simOf = (id) => sims[id - 1];
  const fused = [...score.entries()].sort((a, b) => b[1] - a[1] || simOf(b[0]) - simOf(a[0]) || a[0] - b[0]).map(([id]) => id);
  const shareOf = (id) => { const n = l.share[id]; return n === undefined || n >= lex.cap ? 1 : n / lex.total; };
  return { fused, simOf, shareOf };
}

const rankIn = (q, ids) => {
  if (!q.answerable) return 0;
  for (let i = 0; i < ids.length; i++) {
    const c = lex.chunks[ids[i] - 1];
    if (c.file === q.document && q.pages.includes(c.page)) return i + 1;
  }
  return 0;
};
const decide = (sim, share) => (sim >= GATE.t1 ? 'S' : sim >= GATE.t2 && share <= GATE.share ? 'T' : 'N');
const best = (ids, simOf) => ids.reduce((b, id) => (simOf(id) > simOf(b) ? id : b), ids[0]);

const out = { label, measuredAt: new Date().toISOString(), url: URL_, configs: {} };
// Warm-up (model load, CUDA graphs) before timing.
await rerank('ısınma', ['a', 'b']);
// CANDIDATES: the top-N to try, e.g. "20" for a slow CPU run (0 = the hybrid order alone, always measured).
const SIZES = [0, ...(process.env.CANDIDATES || '20,40').split(',').map(Number).filter((n) => n > 0)];
for (const N of SIZES) {
  const lat = [], rows = [];
  for (let pass = 0; pass < (N ? PASSES : 1); pass++) {
    for (let qi = 0; qi < set.length; qi++) {
      const q = set[qi];
      const { fused, simOf, shareOf } = fusedOf(q, qi);
      const base = fused.slice(0, TOPK);
      let top = base, scores = null, lastOrder = null;
      if (N) {
        const pool = fused.slice(0, N);
        const r = await rerank(q.question, pool.map((id) => lex.chunks[id - 1].content));
        lat.push(r.ms);
        lastOrder = r.order;
        top = r.order.slice(0, TOPK).map((i) => pool[i]);
        scores = r.scores.slice(0, TOPK).map((s) => s.score);
      }
      if (pass > 0) continue;
      // Gate as shipped (evidence = most similar passage of the unreranked top 5) vs. gate over the reranked top 5.
      const ev = best(base, simOf), rb = best(top, simOf);
      // What the application returns (DocumentRetrievalServiceImpl): the reranked pool without passages at least as
      // similar as the gate's evidence (the most similar passage of the unreranked top 5), cut to 5, with the evidence
      // in place of the last one when the reranker put it lower. The gate then reads the same passage as without rerank.
      let kept = top;
      if (N) {
        const pool = fused.slice(0, N);
        const ordered = lastOrder.map((i) => pool[i]).filter((id) => id === ev || simOf(id) < simOf(ev)).slice(0, TOPK);
        kept = ordered.includes(ev) ? ordered : [...ordered.slice(0, TOPK - 1), ev];
      }
      const kb = best(kept, simOf);
      rows.push({ id: q.id, a: q.answerable, tags: q.tags || null, rank: rankIn(q, top), rankKept: rankIn(q, kept),
        gateBase: decide(simOf(ev), shareOf(ev)), gateReranked: decide(simOf(rb), shareOf(rb)),
        gateKept: decide(simOf(kb), shareOf(kb)),
        evidenceKept: top.includes(ev), topScore: scores ? r3(scores[0]) : null });
    }
  }
  const metrics = (list) => {
    const a = list.filter((r) => r.a);
    if (!a.length) return null;
    return { n: a.length, r1: r3(a.filter((r) => r.rank === 1).length / a.length), r5: r3(a.filter((r) => r.rank >= 1).length / a.length),
      hits1: a.filter((r) => r.rank === 1).length, hits5: a.filter((r) => r.rank >= 1).length,
      mrr: r3(a.reduce((s, r) => s + (r.rank ? 1 / r.rank : 0), 0) / a.length) };
  };
  const subsets = { all: () => true, version1: (r) => !r.tags, version2: (r) => !!r.tags };
  for (const t of ['paraphrase', 'abbreviation', 'number', 'table', 'disambiguation']) subsets[t] = (r) => r.tags && r.tags.includes(t);
  const c = { metrics: {} };
  for (const [name, f] of Object.entries(subsets)) c.metrics[name] = metrics(rows.filter(f));
  c.metricsKept = {};
  for (const [name, f] of Object.entries(subsets)) c.metricsKept[name] = metrics(rows.filter(f).map((r) => ({ ...r, rank: r.rankKept })));
  const ans = rows.filter((r) => r.a), una = rows.filter((r) => !r.a);
  c.gate = {
    shipped: { answerablePass: ans.filter((r) => r.gateBase !== 'N').length, unanswerableStopped: una.filter((r) => r.gateBase === 'N').length },
    overReranked: { answerablePass: ans.filter((r) => r.gateReranked !== 'N').length, unanswerableStopped: una.filter((r) => r.gateReranked === 'N').length },
    keptEvidence: { answerablePass: ans.filter((r) => r.gateKept !== 'N').length, unanswerableStopped: una.filter((r) => r.gateKept === 'N').length },
    changedWithEvidence: rows.filter((r) => (r.gateBase === 'N') !== (r.gateKept === 'N')).map((r) => r.id),
    changed: rows.filter((r) => (r.gateBase === 'N') !== (r.gateReranked === 'N')).map((r) => r.id),
    evidenceDropped: rows.filter((r) => !r.evidenceKept).length,
  };
  // AUC of the reranker's top score as an answerability signal (for a possible re-calibration; not used by the gate).
  if (N) {
    let win = 0;
    for (const a of ans) for (const u of una) win += a.topScore > u.topScore ? 1 : a.topScore === u.topScore ? 0.5 : 0;
    c.topScoreAuc = r3(win / (ans.length * una.length));
    c.latencyMs = { n: lat.length, p50: r3(pct(lat, 0.5)), p95: r3(pct(lat, 0.95)), max: r3(Math.max(...lat)) };
  }
  c.rows = rows.map(({ id, rank, rankKept, gateBase, gateReranked, gateKept, topScore }) => ({ id, rank, rankKept, gateBase, gateReranked, gateKept, topScore }));
  out.configs[N ? `rerank-top${N}` : 'hybrid'] = c;
  const m = c.metrics.all;
  console.log(`${N ? `rerank top-${N}` : 'hybrid (no rerank)'}: r1 ${m.r1} (${m.hits1}) r5 ${m.r5} (${m.hits5}) mrr ${m.mrr}` +
    ` | paraphrase r1 ${c.metrics.paraphrase.r1} r5 ${c.metrics.paraphrase.r5} | table r1 ${c.metrics.table.r1} r5 ${c.metrics.table.r5}` +
    ` | gate shipped ${c.gate.shipped.answerablePass}/${c.gate.shipped.unanswerableStopped} over-reranked ${c.gate.overReranked.answerablePass}/${c.gate.overReranked.unanswerableStopped}` +
    ` kept ${c.gate.keptEvidence.answerablePass}/${c.gate.keptEvidence.unanswerableStopped} changed ${c.gate.changedWithEvidence.length}` +
    ` | kept r1 ${c.metricsKept.all.r1} r5 ${c.metricsKept.all.r5} mrr ${c.metricsKept.all.mrr}` +
    (N ? ` | auc ${c.topScoreAuc} | ms p50 ${c.latencyMs.p50} p95 ${c.latencyMs.p95}` : ''));
}
writeFileSync(outPath, JSON.stringify(out, null, 1));
