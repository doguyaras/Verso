// Step 2 of the embedding comparison (docs/evidence/embedding-olcumu.md). Offline harness: embeds the chunks and the
// 140 eval questions with a model served by Ollama, then reproduces the production retrieval on top of the
// model-independent lexical lists from lex.mjs: cosine top 20 (exact scan; the app uses HNSW, same result at 75 chunks),
// RRF k = 60 with the lexical top 20, top 5, the answerability gate (AnswerabilityGate: cosine >= T1, or >= T2 and
// specific-term share <= S). Nothing is written to any database.
// Usage: node olcum.mjs <lex.json> <eval-set.json> <out.json> <variants.json>   (OLLAMA_URL env, default http://localhost:11434)
import { readFileSync, writeFileSync } from 'node:fs';

const [lexPath, setPath, outPath, variantsPath] = process.argv.slice(2);
const OLLAMA = process.env.OLLAMA_URL || 'http://localhost:11434';
const lex = JSON.parse(readFileSync(lexPath, 'utf8'));
const set = JSON.parse(readFileSync(setPath, 'utf8')).questions;
const variants = JSON.parse(readFileSync(variantsPath, 'utf8'));
const lexById = new Map(lex.questions.map((q) => [q.id, q]));
const RRF_K = 60, CAND = 20, TOPK = 5;
const GATE = { t1: 0.47, t2: 0.44, share: 0.045 }; // config/verso.yml (ADR-0022)

async function embed(model, input, keepAlive = '30m') {
  const res = await fetch(`${OLLAMA}/api/embed`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ model, input, keep_alive: keepAlive }),
  });
  if (!res.ok) throw new Error(`embed ${model}: ${res.status} ${await res.text()}`);
  return (await res.json()).embeddings;
}
const norm = (v) => { const n = Math.sqrt(v.reduce((s, x) => s + x * x, 0)); return v.map((x) => x / n); };
const dot = (a, b) => { let s = 0; for (let i = 0; i < a.length; i++) s += a[i] * b[i]; return s; };
const pct = (a, p) => { const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.ceil(s.length * p) - 1)]; };
const med = (a) => { const s = [...a].sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };
const r3 = (x) => Math.round(x * 1000) / 1000;
const r4 = (x) => Math.round(x * 10000) / 10000;

const tmpl = (t, x) => t.replace('{x}', x);

async function runVariant(v) {
  const out = { name: v.name, model: v.model, queryTemplate: v.query, passageTemplate: v.passage };
  // Warm-up: load the model onto the GPU before any timing.
  await embed(v.model, [tmpl(v.query, 'ısınma')]);
  // Index time: the 75 chunks of the 11 sample documents, in batches (the worker's embeddingBatch).
  const docs = lex.chunks.map((c) => tmpl(v.passage, c.content));
  // Timed three times (the GPU is shared with other work and model loads cause spikes); the fastest run is reported.
  let dvec = [], indexRuns = [];
  for (let run = 0; run < 3; run++) {
    const t0 = performance.now();
    dvec = [];
    for (let i = 0; i < docs.length; i += 16) dvec.push(...(await embed(v.model, docs.slice(i, i + 16))));
    indexRuns.push(Math.round(performance.now() - t0));
  }
  out.indexMs = Math.min(...indexRuns);
  out.indexRunsMs = indexRuns;
  const D = dvec.map(norm);
  out.dimensions = D[0].length;
  // Query latency: one request per question, sequentially (like the app).
  // Three passes over the 140 questions; latencies are pooled (420 requests), vectors from the first pass.
  const lat = [], Q = [];
  for (let pass = 0; pass < 3; pass++) {
    for (const q of set) {
      const t = performance.now();
      const [e] = await embed(v.model, [tmpl(v.query, q.question)]);
      lat.push(performance.now() - t);
      if (pass === 0) Q.push(norm(e));
    }
  }
  out.queryMs = { p50: r3(med(lat)), p95: r3(pct(lat, 0.95)), max: r3(Math.max(...lat)) };

  const rows = set.map((q, qi) => {
    const sims = D.map((d) => dot(Q[qi], d));
    const order = sims.map((s, i) => [s, lex.chunks[i].id]).sort((a, b) => b[0] - a[0] || a[1] - b[1]);
    const vecTop = order.slice(0, CAND).map(([, id]) => id);
    const l = lexById.get(q.id);
    const score = new Map();
    vecTop.forEach((id, i) => score.set(id, (score.get(id) || 0) + 1 / (RRF_K + i + 1)));
    l.lex.forEach((id, i) => score.set(id, (score.get(id) || 0) + 1 / (RRF_K + i + 1)));
    const simOf = (id) => sims[id - 1];
    const fused = [...score.entries()].sort((a, b) => b[1] - a[1] || simOf(b[0]) - simOf(a[0]) || a[0] - b[0]).slice(0, TOPK).map(([id]) => id);
    const shareOf = (id) => { const n = l.share[id]; return n === undefined || n >= lex.cap ? 1 : n / lex.total; };
    const bestHybrid = fused.reduce((b, id) => (simOf(id) > simOf(b) ? id : b), fused[0]);
    const rankIn = (ids) => {
      if (!q.answerable) return 0;
      for (let i = 0; i < ids.length; i++) {
        const c = lex.chunks[ids[i] - 1];
        if (c.file === q.document && q.pages.includes(c.page)) return i + 1;
      }
      return 0;
    };
    return {
      id: q.id, answerable: q.answerable, tags: q.tags || null,
      rankVec: rankIn(vecTop.slice(0, TOPK)), rankHyb: rankIn(fused),
      simVec: sims[vecTop[0] - 1], simHyb: simOf(bestHybrid), share: shareOf(bestHybrid),
    };
  });

  const metrics = (list, key) => {
    const a = list.filter((r) => r.answerable);
    if (!a.length) return null;
    return { n: a.length, r1: r3(a.filter((r) => r[key] === 1).length / a.length), r5: r3(a.filter((r) => r[key] >= 1).length / a.length),
      hits5: a.filter((r) => r[key] >= 1).length, mrr: r3(a.reduce((s, r) => s + (r[key] ? 1 / r[key] : 0), 0) / a.length) };
  };
  const subsets = { all: () => true, version1: (r) => !r.tags, version2: (r) => !!r.tags };
  for (const t of ['paraphrase', 'abbreviation', 'number', 'table', 'disambiguation']) subsets[t] = (r) => r.tags && r.tags.includes(t);
  out.vector = {}; out.hybrid = {};
  for (const [name, f] of Object.entries(subsets)) {
    const sub = rows.filter(f);
    out.vector[name] = metrics(sub, 'rankVec');
    out.hybrid[name] = metrics(sub, 'rankHyb');
  }

  // Similarity distributions of the best passage (what the gate reads).
  const dist = (list, key) => (list.length ? { min: r4(Math.min(...list.map((r) => r[key]))), median: r4(med(list.map((r) => r[key]))), max: r4(Math.max(...list.map((r) => r[key]))) } : null);
  const ans = rows.filter((r) => r.answerable), una = rows.filter((r) => !r.answerable);
  out.sim = { answerable: dist(ans, 'simHyb'), unanswerable: dist(una, 'simHyb'),
    ontopic: dist(una.filter((r) => r.tags && r.tags.includes('on-topic')), 'simHyb'),
    offtopic: dist(una.filter((r) => !(r.tags && r.tags.includes('on-topic'))), 'simHyb') };
  // AUC of the best similarity: P(answerable question scores above an unanswerable one). Scale free.
  let win = 0;
  for (const a of ans) for (const u of una) win += a.simHyb > u.simHyb ? 1 : a.simHyb === u.simHyb ? 0.5 : 0;
  out.auc = r3(win / (ans.length * una.length));

  const decide = (r, g) => (r.simHyb >= g.t1 ? 'S' : r.simHyb >= g.t2 && r.share <= g.share ? 'T' : 'N');
  const gateStats = (g) => ({ ...g, answerablePass: ans.filter((r) => decide(r, g) !== 'N').length, unanswerableStopped: una.filter((r) => decide(r, g) === 'N').length });
  out.gateCurrent = gateStats(GATE); // the shipped thresholds applied to this model, uncalibrated
  // (a) single threshold: the highest T1 that lets every answerable question through.
  const t1 = Math.floor(Math.min(...ans.map((r) => r.simHyb)) * 1000) / 1000;
  out.gateSingle = gateStats({ t1, t2: t1, share: 0 });
  // (b) ADR-0022 family, grid search on this very set (in-sample): every answerable passes, most unanswerable stopped.
  let best = null;
  const shares = [...new Set(rows.map((r) => r.share))].sort((a, b) => a - b);
  const shareChoices = [0, ...shares.filter((s) => s < 1)];
  for (let a = 0.2; a <= 0.95; a += 0.005) {
    for (let b = 0.2; b <= a + 1e-9; b += 0.005) {
      for (const s of shareChoices) {
        const g = { t1: r3(a), t2: r3(b), share: s };
        if (ans.some((r) => decide(r, g) === 'N')) continue;
        const stopped = una.filter((r) => decide(r, g) === 'N').length;
        if (!best || stopped > best.unanswerableStopped) best = { ...g, answerablePass: ans.length, unanswerableStopped: stopped };
      }
    }
  }
  out.gateGrid = best;
  out.rows = rows.map((r) => ({ id: r.id, a: r.answerable, rv: r.rankVec, rh: r.rankHyb, sim: r4(r.simHyb), share: r4(r.share) }));
  return out;
}

const results = [];
for (const v of variants) {
  try {
    const r = await runVariant(v);
    results.push(r);
    console.log(`${v.name}: hybrid r1 ${r.hybrid.all.r1} r5 ${r.hybrid.all.r5} mrr ${r.hybrid.all.mrr} | vec r1 ${r.vector.all.r1} r5 ${r.vector.all.r5} | auc ${r.auc} | q p50 ${r.queryMs.p50}ms | index ${r.indexMs}ms | gate(current) ${r.gateCurrent.answerablePass}/${r.gateCurrent.unanswerableStopped}`);
  } catch (e) {
    console.log(`${v.name}: FAILED ${e.message}`);
    results.push({ name: v.name, model: v.model, error: String(e.message) });
  }
}
writeFileSync(outPath, JSON.stringify({ measuredAt: new Date().toISOString(), results }, null, 1));
