#!/usr/bin/env node
// End-to-end answer quality on the Turkish eval set (phase 8, llm-rules 7.1), through the running API with the real
// models: uploads samples/*.pdf, waits until READY, asks every question of eval/eval-set.json once, and measures
//   - answerable questions: found rate, citation hit (a citation on the expected document and page), citation
//     precision (citations on the expected document), and whether the answer contains the expected fact;
//   - unanswerable questions: not-found accuracy (found=false);
//   - latency p50/p95.
// Writes eval/results/e2e-<chat model>.json and prints a summary. Removes its uploads at the end.
//
//   node scripts/eval.mjs        # after: docker compose up -d --build --wait (CPU: about 30-40 min for 33 questions)
//
// No dependencies (Node 24 fetch). Tokens come from the bundled Keycloak's CI client (client credentials).
import { readFileSync, writeFileSync, readdirSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const API = `http://localhost:${process.env.VERSO_HTTP_PORT ?? 8080}`;
const IDP = `http://localhost:${process.env.VERSO_KEYCLOAK_PORT ?? 8180}/realms/verso/protocol/openid-connect/token`;
const SECRET = readFileSync(join(ROOT, 'secrets/SECRET_KEYCLOAK_CI_CLIENT_SECRET'), 'utf8').trim();
const tr = (s) => s.toLocaleLowerCase('tr');

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
        row.citationsOnDocument = body.citations.filter((c) => c.fileName === q.document).length;
        row.factFound = q.expect.split('|').some((e) => tr(body.answer).includes(tr(e)));
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
  citationPrecision: ratio(answerable.reduce((n, r) => n + (r.citationsOnDocument ?? 0), 0), citations),
  factAccuracy: ratio(answerable.filter((r) => r.factFound).length, answerable.length),
  unanswerable: unanswerable.length,
  notFoundAccuracy: ratio(unanswerable.filter((r) => r.status === 200 && !r.found).length, unanswerable.length),
  latencyMs: { p50: percentile(rows.map((r) => r.ms), 50), p95: percentile(rows.map((r) => r.ms), 95),
    max: Math.max(...rows.map((r) => r.ms)) },
};
mkdirSync(join(ROOT, 'eval/results'), { recursive: true });
const out = join(ROOT, `eval/results/e2e-${model.replace(/[^A-Za-z0-9.-]/g, '_')}.json`);
writeFileSync(out, JSON.stringify({ measuredAt: new Date().toISOString(), summary, questions: rows }, null, 2) + '\n');
console.log('eval: summary', JSON.stringify(summary));
console.log(`eval: written ${out}`);
