#!/usr/bin/env node
// End-to-end answer quality on the Turkish eval set (phase 8, llm-rules 7.1), through the running API with the real
// models: uploads samples/*.pdf, waits until READY, asks every question of eval/eval-set.json once, and measures
//   - answerable questions: found rate, citation hit (a citation on the expected document and page), citation
//     precision (citations on an expected page, of all citations), and whether the answer contains the expected fact
//     (as a whole word or number, citation markers removed);
//   - unanswerable questions: not-found accuracy (the answer is the fixed "bulunamadı" sentence; an uncited answer
//     is wrong), split into those the threshold stopped (eval/results/retrieval.json) and those the model decided;
//   - latency p50/p95.
// Writes eval/results/e2e-<chat model>.json and prints a summary. Removes its uploads at the end.
//
// It works on the CI client's account (verso-ci) and DELETES EVERY DOCUMENT IN IT first, so earlier uploads cannot be
// cited. Run it only against a local demo stack.
//
//   node scripts/eval.mjs        # after: docker compose up -d --build --wait (CPU: about 30-40 min for 33 questions)
//
// No dependencies (Node 24 fetch). Tokens come from the bundled Keycloak's CI client (client credentials).
import { readFileSync, writeFileSync, readdirSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const API = `http://localhost:${process.env.VERSO_HTTP_PORT ?? 8080}`;
const IDP = `http://localhost:${process.env.VERSO_KEYCLOAK_PORT ?? 8180}/realms/verso/protocol/openid-connect/token`;
const SECRET = readFileSync(join(ROOT, 'secrets/SECRET_KEYCLOAK_CI_CLIENT_SECRET'), 'utf8').trim();
const tr = (s) => s.normalize('NFC').toLocaleLowerCase('tr');
const NOT_FOUND = 'Belgelerde bu sorunun cevabı bulunamadı';
const saysNotFound = (answer) => answer.trim().replace(/\.$/, '').trim() === NOT_FOUND;
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
  form.append('file', new Blob([readFileSync(join(ROOT, 'samples', file))], { type: 'application/pdf' }), file);
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

const set = JSON.parse(readFileSync(join(ROOT, 'eval/eval-set.json'), 'utf8'));
const files = readdirSync(join(ROOT, 'samples')).filter((f) => f.endsWith('.pdf')).sort();
// Leftovers of an earlier run would be cited too: this account starts empty.
for (const doc of (await (await api("/v1/documents?size=100")).json()).data ?? []) {
  await api(`/v1/documents/${doc.id}`, { method: 'DELETE' });
}
const ids = [];
for (const f of files) ids.push(await upload(f));
console.log(`eval: uploaded ${ids.length} samples, waiting for READY`);
await waitReady(ids);

const rows = [];
let model = 'unknown';
let mode = 'unknown';
try {
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
      if (q.answerable) {
        row.citationHit = body.citations.some((c) => c.fileName === q.document && q.pages.includes(c.page));
        row.citationsOnPage = body.citations.filter((c) => c.fileName === q.document && q.pages.includes(c.page)).length;
        row.factFound = q.expect.split('|').some((e) => containsFact(body.answer, e));
      } else {
        row.saysNotFound = saysNotFound(body.answer);
      }
    } else {
      row.error = body?.error?.code;
    }
    rows.push(row);
    console.log(`eval: ${q.id} ${res.status} ${ms} ms found=${row.found} hit=${row.citationHit ?? '-'} fact=${row.factFound ?? '-'}`);
  }
} finally {
  for (const id of ids) await api(`/v1/documents/${id}`, { method: 'DELETE' });
}

const answerable = rows.filter((r) => r.answerable);
const unanswerable = rows.filter((r) => !r.answerable);
const citations = answerable.reduce((n, r) => n + (r.citations?.length ?? 0), 0);
const summary = {
  mode, chatModel: model, questions: rows.length,
  errors: rows.filter((r) => r.status !== 200).length,
  answerable: answerable.length,
  foundRate: ratio(answerable.filter((r) => r.found).length, answerable.length),
  citationHitRate: ratio(answerable.filter((r) => r.citationHit).length, answerable.length),
  citationPrecision: ratio(answerable.reduce((n, r) => n + (r.citationsOnPage ?? 0), 0), citations),
  factAccuracy: ratio(answerable.filter((r) => r.factFound).length, answerable.length),
  unanswerable: unanswerable.length,
  notFoundAccuracy: ratio(unanswerable.filter((r) => r.status === 200 && r.saysNotFound).length, unanswerable.length),
  // From the retrieval measurement: how many unanswerable questions the threshold stopped before the model.
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
  const below = new Set(retrieval.questions.filter((q) => !q.answerable && q.bestSimilarity < retrieval.summary.threshold)
    .map((q) => q.id));
  const byModel = unanswerableRows.filter((r) => !below.has(r.id));
  return { stoppedByThreshold: unanswerableRows.length - byModel.length, decidedByModel: byModel.length,
    modelNotFoundAccuracy: ratio(byModel.filter((r) => r.status === 200 && r.saysNotFound).length, byModel.length) };
}

let commit = 'unknown';
try {
  commit = execFileSync('git', ['rev-parse', '--short=12', 'HEAD'], { cwd: ROOT }).toString().trim();
} catch { /* not a git checkout */ }

mkdirSync(join(ROOT, 'eval/results'), { recursive: true });
const out = join(ROOT, `eval/results/e2e-${model.replace(/[^A-Za-z0-9.-]/g, '_')}.json`);
writeFileSync(out, JSON.stringify({ measuredAt: new Date().toISOString(), commit, summary, questions: rows }, null, 2) + '\n');
console.log('eval: summary', JSON.stringify(summary));
console.log(`eval: written ${out}`);
