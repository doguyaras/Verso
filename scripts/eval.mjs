#!/usr/bin/env node
// End-to-end answer quality on the Turkish eval set (phase 8, llm-rules 7.1), through the running API with the real
// models: uploads samples/*.pdf, waits until READY, asks every question of eval/eval-set.json once, and measures
//   - answerable questions: found rate, citation hit (a citation on the expected document and page), citation
//     precision (citations on an expected page, of all citations), and whether the answer contains the expected fact
//     (as a whole word or number, citation markers removed);
//   - unanswerable questions: not-found accuracy (the answer is the fixed "bulunamadı" sentence; an uncited answer
//     is wrong), split into those the answerability gate stopped (eval/results/retrieval.json) and those the model
//     decided; next to it, a lenient count that also accepts the model's own uncited refusal ("... bilgi
//     bulunmamaktadır"), which the strict metric counts as wrong;
//   - latency p50/p95.
// Writes eval/results/e2e-<chat model>.json (or eval/results/<OUT>) and prints a summary. Removes its uploads at the
// end. OUT keeps a run on other hardware (e2e-gemma4_e2b-gpu.json) from overwriting the CPU baseline.
//
// It works on the CI client's account (verso-ci) and DELETES EVERY DOCUMENT IN IT first, so earlier uploads cannot be
// cited. Run it only against a local demo stack.
//
//   node scripts/eval.mjs        # after: docker compose up -d --build --wait (CPU: about 30-40 min for 33 questions)
//
// Multi-hop set (ADR-0055, eval/multihop-set.json, 20 comparison and two-document questions): every expected source
// (document and page) and fact is scored on its own, partial questions must state the missing part ("bulunamadı"),
// and the answer's "reasoning" field tells whether it was decomposed. With --compare the app container is recreated
// twice (VERSO_QA_REASONING_ENABLED=false, then true; the uploads stay) and both arms are written next to a comparison:
//
//   node scripts/eval.mjs --set multihop             # against the stack as it runs
//   node scripts/eval.mjs --set multihop --compare   # off vs on: eval/results/multihop-compare-<model>.json
//
// No dependencies (Node 24 fetch). Tokens come from the bundled Keycloak's CI client (client credentials).
import { readFileSync, writeFileSync, readdirSync, mkdirSync } from 'node:fs';
import { join, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const API = `http://localhost:${process.env.VERSO_HTTP_PORT ?? 8080}`;
const IDP = `http://localhost:${process.env.VERSO_KEYCLOAK_PORT ?? 8180}/realms/verso/protocol/openid-connect/token`;
// Another checkout's secrets (a worktree has none of its own) and another set of the same eleven PDFs (ADR-0032: the
// ruled-table and scanned variants SamplePdfsTest writes) can be named; the defaults are this checkout's.
const SECRET = readFileSync(process.env.CI_CLIENT_SECRET_FILE ?? join(ROOT, 'secrets/SECRET_KEYCLOAK_CI_CLIENT_SECRET'), 'utf8').trim();
const SAMPLES_DIR = process.env.SAMPLES_DIR || join(ROOT, 'samples');
const SAMPLES = process.env.SAMPLES_DIR ? basename(SAMPLES_DIR) : 'samples';
const tr = (s) => s.normalize('NFC').toLocaleLowerCase('tr');
const NOT_FOUND = 'Belgelerde bu sorunun cevabı bulunamadı';
const saysNotFound = (answer) => answer.trim().replace(/\.$/, '').trim() === NOT_FOUND;
/** A refusal in the model's own words: no citation, and it says the documents do not hold the information. */
const ownRefusal = (answer, citations) => citations.length === 0
  && /bulun(a|ma)m|belirtilme|yer alm(a|ı)m/.test(tr(answer));
/** The expected fact as a whole word or number: "2" must not match "[2]", "30.000" or "200" (phase 8 review L2). */
function containsFact(answer, fact) {
  const text = tr(answer.replace(/\[\d+\]/g, ' '));
  const escaped = tr(fact).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  // A boundary is neither a letter nor a digit, nor a digit group separator between digits ("30" is not in "30.000").
  return new RegExp(`(?<![\\p{L}\\p{N}]|\\p{N}[.,])${escaped}(?![\\p{L}\\p{N}]|[.,]\\p{N})`, 'u').test(text);
}

let token = { value: null, until: 0 };
async function bearer() {
  if (Date.now() < token.until) return token.value;
  const res = await fetch(IDP, { method: 'POST', body: new URLSearchParams({
    grant_type: 'client_credentials', client_id: 'verso-ci', client_secret: SECRET }) });
  if (!res.ok) throw new Error(`token: ${res.status}`);
  const json = await res.json();
  token = { value: `Bearer ${json.access_token}`, until: Date.now() + (json.expires_in - 30) * 1000 };
  return token.value;
}
async function api(path, init = {}) {
  return fetch(API + path, { ...init, headers: { Authorization: await bearer(), ...(init.headers ?? {}) } });
}

async function upload(file) {
  const form = new FormData();
  form.append('file', new Blob([readFileSync(join(SAMPLES_DIR, file))], { type: 'application/pdf' }), file);
  const res = await api('/v1/documents', { method: 'POST', body: form });
  if (res.status !== 201) throw new Error(`upload ${file}: ${res.status}`);
  return (await res.json()).id;
}

async function waitReady(ids) {
  const deadline = Date.now() + 10 * 60_000;
  while (Date.now() < deadline) {
    const states = await Promise.all(ids.map(async (id) => (await (await api(`/v1/documents/${id}`)).json()).status));
    if (states.every((s) => s === 'READY')) return;
    if (states.includes('FAILED')) throw new Error('a sample failed to ingest');
    await new Promise((r) => setTimeout(r, 3000));
  }
  throw new Error('samples not READY in 10 minutes');
}

const percentile = (values, p) => {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted.length ? sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)] : 0;
};
const ratio = (part, whole) => (whole ? Math.round((part / whole) * 1000) / 1000 : 0);

const args = process.argv.slice(2);
const SET = args.includes('--set') ? args[args.indexOf('--set') + 1] : 'default';
const COMPARE = args.includes('--compare');
if (!['default', 'multihop'].includes(SET)) throw new Error(`unknown --set ${SET} (default | multihop)`);
if (COMPARE && SET !== 'multihop') throw new Error('--compare needs --set multihop');
const set = JSON.parse(readFileSync(join(ROOT, SET === 'multihop' ? 'eval/multihop-set.json' : 'eval/eval-set.json'), 'utf8'));
/** An explicit "not in the documents" for one part of the answer (ADR-0055 writes "… için belgelerde bilgi bulunamadı"). */
const statesMissing = (answer) => /bulunamad|bulunmamakta|yer alm(a|ı)m/.test(tr(answer));

/** ADR-0055 --compare: recreates the app container with the reasoning flag; the database and the uploads stay. */
async function restartWithReasoning(on) {
  console.log(`eval: recreating verso-app with VERSO_QA_REASONING_ENABLED=${on}`);
  execFileSync('docker', ['compose', 'up', '-d', '--wait', '--force-recreate', '--no-deps', 'verso-app'],
    { cwd: ROOT, stdio: 'inherit', env: { ...process.env, VERSO_QA_REASONING_ENABLED: String(on) } });
  const deadline = Date.now() + 5 * 60_000;
  while (Date.now() < deadline) {
    try {
      if ((await api('/v1/documents?size=1')).ok) return;
    } catch { /* starting */ }
    await new Promise((r) => setTimeout(r, 3000));
  }
  throw new Error('verso-app not answering 5 minutes after the restart');
}

/** One multi-hop question scored per expected source and fact (eval/multihop-set.json). */
function scoreMultihop(q, row, body) {
  row.partial = Boolean(q.partial);
  row.sources = q.sources.length;
  row.sourceHits = q.sources.filter((s) => body.citations.some((c) => c.fileName === s.document && s.pages.includes(c.page))).length;
  row.factHits = q.sources.filter((s) => s.expect.split('|').some((e) => containsFact(body.answer, e))).length;
  row.steps = body.reasoning?.steps?.length ?? 0;
  row.notFoundSteps = body.reasoning?.steps?.filter((s) => !s.found).length ?? 0;
  row.contradictions = body.reasoning?.contradictions?.length ?? 0;
  if (row.partial) row.statesMissing = statesMissing(body.answer);
}

function multihopSummary(rows, arm) {
  const ok = rows.filter((r) => r.status === 200);
  const partial = rows.filter((r) => r.partial);
  const sources = ok.reduce((n, r) => n + r.sources, 0);
  return {
    mode, chatModel: model, arm, questions: rows.length, samples: SAMPLES,
    errors: rows.length - ok.length,
    decomposedRate: ratio(ok.filter((r) => r.steps > 0).length, rows.length),
    sourceHitRate: ratio(ok.reduce((n, r) => n + r.sourceHits, 0), sources),
    allSourcesCitedRate: ratio(ok.filter((r) => r.sourceHits === r.sources).length, rows.length),
    factRate: ratio(ok.reduce((n, r) => n + r.factHits, 0), sources),
    allFactsRate: ratio(ok.filter((r) => r.factHits === r.sources).length, rows.length),
    partialStatesMissing: ratio(partial.filter((r) => r.statesMissing).length, partial.length),
    contradictionNotes: ok.reduce((n, r) => n + r.contradictions, 0),
    latencyMs: { p50: percentile(rows.map((r) => r.ms), 50), p95: percentile(rows.map((r) => r.ms), 95),
      max: Math.max(...rows.map((r) => r.ms)) },
  };
}

const files = readdirSync(SAMPLES_DIR).filter((f) => f.endsWith('.pdf')).sort();
// Leftovers of an earlier run would be cited too: this account starts empty.
for (const doc of (await (await api("/v1/documents?size=100")).json()).data ?? []) {
  await api(`/v1/documents/${doc.id}`, { method: 'DELETE' });
}
const ids = [];
for (const f of files) ids.push(await upload(f));
console.log(`eval: uploaded ${ids.length} samples, waiting for READY`);
const ingestionStarted = performance.now();
await waitReady(ids);
// Upload to READY for all samples (parse + chunk + embed), to the 3 s polling step (ADR-0032 ingestion cost).
const ingestionWallMs = Math.round(performance.now() - ingestionStarted);

let rows = [];
let model = 'unknown';
let mode = 'unknown';
const arms = {};
try {
  for (const arm of COMPARE ? ['off', 'on'] : [null]) {
  if (arm) await restartWithReasoning(arm === 'on');
  rows = [];
  for (const q of set.questions) {
    const started = performance.now();
    const res = await api('/v1/questions', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question: q.question }) });
    const ms = Math.round(performance.now() - started);
    const body = await res.json();
    const row = { id: q.id, answerable: q.answerable, status: res.status, ms };
    if (res.ok) {
      model = body.model; mode = body.mode;
      row.found = body.found;
      row.citations = body.citations.map((c) => `${c.fileName}#${c.page}`);
      row.answer = body.answer;
      if (SET === 'multihop') {
        scoreMultihop(q, row, body);
      } else if (q.answerable) {
        row.citationHit = body.citations.some((c) => c.fileName === q.document && q.pages.includes(c.page));
        row.citationsOnPage = body.citations.filter((c) => c.fileName === q.document && q.pages.includes(c.page)).length;
        row.factFound = q.expect.split('|').some((e) => containsFact(body.answer, e));
      } else {
        row.saysNotFound = saysNotFound(body.answer);
        row.ownRefusal = !row.saysNotFound && ownRefusal(body.answer, body.citations);
      }
    } else {
      row.error = body?.error?.code;
    }
    rows.push(row);
    console.log(SET === 'multihop'
      ? `eval: ${arm ?? ''} ${q.id} ${res.status} ${ms} ms steps=${row.steps ?? '-'} sources=${row.sourceHits ?? '-'}/${q.sources.length} facts=${row.factHits ?? '-'}/${q.sources.length}`
      : `eval: ${q.id} ${res.status} ${ms} ms found=${row.found} hit=${row.citationHit ?? '-'} fact=${row.factFound ?? '-'}`);
  }
  arms[arm ?? 'current'] = rows;
  }
} finally {
  for (const id of ids) await api(`/v1/documents/${id}`, { method: 'DELETE' });
  // --compare leaves the stack as it found it: the flag back to its default (off).
  if (COMPARE) await restartWithReasoning(false);
}

if (SET === 'multihop') {
  // ADR-0055: one file per arm, and with --compare the two summaries side by side with per-question differences.
  let head = 'unknown';
  try {
    head = execFileSync('git', ['rev-parse', '--short=12', 'HEAD'], { cwd: ROOT }).toString().trim();
  } catch { /* not a git checkout */ }
  mkdirSync(join(ROOT, 'eval/results'), { recursive: true });
  const name = model.replace(/[^A-Za-z0-9.-]/g, '_');
  const summaries = {};
  for (const [arm, armRows] of Object.entries(arms)) {
    summaries[arm] = multihopSummary(armRows, arm);
    const file = join(ROOT, 'eval/results', `multihop-${name}-${arm}.json`);
    writeFileSync(file, JSON.stringify({ measuredAt: new Date().toISOString(), commit: head, summary: summaries[arm],
      questions: armRows }, null, 2) + '\n');
    console.log(`eval: ${arm} summary`, JSON.stringify(summaries[arm]));
    console.log(`eval: written ${file}`);
  }
  if (COMPARE) {
    const byId = (armRows) => new Map(armRows.map((r) => [r.id, r]));
    const off = byId(arms.off);
    const on = byId(arms.on);
    const changed = set.questions.map((q) => ({ id: q.id,
      sourceHits: [off.get(q.id)?.sourceHits ?? null, on.get(q.id)?.sourceHits ?? null],
      factHits: [off.get(q.id)?.factHits ?? null, on.get(q.id)?.factHits ?? null],
      ms: [off.get(q.id)?.ms ?? null, on.get(q.id)?.ms ?? null] }))
      .filter((d) => d.sourceHits[0] !== d.sourceHits[1] || d.factHits[0] !== d.factHits[1]);
    const file = join(ROOT, 'eval/results', `multihop-compare-${name}.json`);
    writeFileSync(file, JSON.stringify({ measuredAt: new Date().toISOString(), commit: head, off: summaries.off,
      on: summaries.on, changed }, null, 2) + '\n');
    console.log(`eval: written ${file}`);
  }
  process.exit(0);
}

const answerable = rows.filter((r) => r.answerable);
const unanswerable = rows.filter((r) => !r.answerable);
const citations = answerable.reduce((n, r) => n + (r.citations?.length ?? 0), 0);
const summary = {
  mode, chatModel: model, questions: rows.length, samples: SAMPLES, ingestionWallMs,
  errors: rows.filter((r) => r.status !== 200).length,
  answerable: answerable.length,
  foundRate: ratio(answerable.filter((r) => r.found).length, answerable.length),
  citationHitRate: ratio(answerable.filter((r) => r.citationHit).length, answerable.length),
  citationPrecision: ratio(answerable.reduce((n, r) => n + (r.citationsOnPage ?? 0), 0), citations),
  factAccuracy: ratio(answerable.filter((r) => r.factFound).length, answerable.length),
  unanswerable: unanswerable.length,
  notFoundAccuracy: ratio(unanswerable.filter((r) => r.status === 200 && r.saysNotFound).length, unanswerable.length),
  notFoundOrOwnRefusal: ratio(unanswerable.filter((r) => r.status === 200 && (r.saysNotFound || r.ownRefusal)).length,
    unanswerable.length),
  // From the retrieval measurement: how many unanswerable questions the gate stopped before the model.
  ...notFoundSplit(unanswerable),
  latencyMs: { p50: percentile(rows.map((r) => r.ms), 50), p95: percentile(rows.map((r) => r.ms), 95),
    max: Math.max(...rows.map((r) => r.ms)) },
};
function notFoundSplit(unanswerableRows) {
  let retrieval;
  try {
    retrieval = JSON.parse(readFileSync(join(ROOT, 'eval/results/retrieval.json'), 'utf8'));
  } catch {
    return {};
  }
  // ADR-0022: the gate's own decision per question; files from before it carry a single threshold instead.
  const stopped = (q) => (q.decision ? q.decision === 'NONE' : q.bestSimilarity < retrieval.summary.threshold);
  const below = new Set(retrieval.questions.filter((q) => !q.answerable && stopped(q)).map((q) => q.id));
  const byModel = unanswerableRows.filter((r) => !below.has(r.id));
  return { stoppedByThreshold: unanswerableRows.length - byModel.length, decidedByModel: byModel.length,
    modelNotFoundAccuracy: ratio(byModel.filter((r) => r.status === 200 && r.saysNotFound).length, byModel.length) };
}

let commit = 'unknown';
try {
  commit = execFileSync('git', ['rev-parse', '--short=12', 'HEAD'], { cwd: ROOT }).toString().trim();
} catch { /* not a git checkout */ }

mkdirSync(join(ROOT, 'eval/results'), { recursive: true });
const out = join(ROOT, 'eval/results', process.env.OUT ?? `e2e-${model.replace(/[^A-Za-z0-9.-]/g, '_')}.json`);
writeFileSync(out, JSON.stringify({ measuredAt: new Date().toISOString(), commit, summary, questions: rows }, null, 2) + '\n');
console.log('eval: summary', JSON.stringify(summary));
console.log(`eval: written ${out}`);
