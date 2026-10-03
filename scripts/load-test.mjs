#!/usr/bin/env node
// Load test of the running stack (phase 8, docs/capacity.md). Four scenarios, one after the other:
//   1. read:      GET /v1/documents, CONCURRENCY parallel clients for DURATION seconds (throughput, latency, errors);
//   2. upload:    UPLOADS sample PDFs at once (the per-instance upload limiter answers 503 10014 above 4 in flight),
//                 then the time until all are READY (ingestion throughput with the real embedding model);
//   3. retrieval: questions below the similarity threshold, CONCURRENCY parallel (embedding + vector search only; the
//                 question-embedding bulkhead answers 503 10030 above 4 in flight);
//   4. chat:      CHAT_QUESTIONS answerable questions, CHAT_CONCURRENCY parallel (the chat bulkhead of 2 answers
//                 503 11002 after 5 s of waiting).
// Writes eval/results/load.json. Removes its uploads at the end.
//
//   node scripts/load-test.mjs      # after: docker compose up -d --build --wait
//
// Environment: CONCURRENCY (20), DURATION (30), UPLOADS (12), CHAT_QUESTIONS (6), CHAT_CONCURRENCY (3).
import { readFileSync, writeFileSync, readdirSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const API = `http://localhost:${process.env.VERSO_HTTP_PORT ?? 8080}`;
const IDP = `http://localhost:${process.env.VERSO_KEYCLOAK_PORT ?? 8180}/realms/verso/protocol/openid-connect/token`;
const SECRET = readFileSync(join(ROOT, 'secrets/SECRET_KEYCLOAK_CI_CLIENT_SECRET'), 'utf8').trim();
const CONCURRENCY = Number(process.env.CONCURRENCY ?? 20);
const DURATION = Number(process.env.DURATION ?? 30);
const UPLOADS = Number(process.env.UPLOADS ?? 12);
const CHAT_QUESTIONS = Number(process.env.CHAT_QUESTIONS ?? 6);
const CHAT_CONCURRENCY = Number(process.env.CHAT_CONCURRENCY ?? 3);

let token = { value: null, until: 0 };
async function bearer() {
  if (Date.now() < token.until) return token.value;
  const res = await fetch(IDP, { method: 'POST', body: new URLSearchParams({
    grant_type: 'client_credentials', client_id: 'verso-ci', client_secret: SECRET }) });
  const json = await res.json();
  token = { value: `Bearer ${json.access_token}`, until: Date.now() + (json.expires_in - 30) * 1000 };
  return token.value;
}
async function timed(path, init = {}) {
  const started = performance.now();
  const res = await fetch(API + path, { ...init, headers: { Authorization: await bearer(), ...(init.headers ?? {}) } });
  const body = await res.text();
  let code;
  try { code = JSON.parse(body)?.error?.code; } catch { code = undefined; }
  return { status: res.status, code, ms: performance.now() - started, body };
}
const percentile = (values, p) => {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted.length ? Math.round(sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)]) : 0;
};
function summarize(results, seconds) {
  const byStatus = {};
  for (const r of results) {
    const key = r.code ? `${r.status}/${r.code}` : String(r.status);
    byStatus[key] = (byStatus[key] ?? 0) + 1;
  }
  const ok = results.filter((r) => r.status < 400).map((r) => r.ms);
  return { requests: results.length, perSecond: seconds ? Math.round((results.length / seconds) * 10) / 10 : undefined,
    byStatus, okLatencyMs: { p50: percentile(ok, 50), p95: percentile(ok, 95), p99: percentile(ok, 99),
      max: Math.round(Math.max(0, ...ok)) } };
}
async function pool(count, task) {
  const results = [];
  await Promise.all(Array.from({ length: count }, async (_, worker) => {
    for (let r; (r = await task(worker)) !== null;) results.push(r);
  }));
  return results;
}

const report = { measuredAt: new Date().toISOString(), host: { cpus: (await import('node:os')).cpus().length } };
const uploaded = [];
try {
  // 1. read
  const readUntil = Date.now() + DURATION * 1000;
  const reads = await pool(CONCURRENCY, async () => (Date.now() < readUntil ? timed('/v1/documents?size=20') : null));
  report.read = { concurrency: CONCURRENCY, seconds: DURATION, ...summarize(reads, DURATION) };
  console.log('load: read', JSON.stringify(report.read));

  // 2. upload + ingestion
  const samples = readdirSync(join(ROOT, 'samples')).filter((f) => f.endsWith('.pdf')).sort();
  const uploadStarted = performance.now();
  const uploads = await Promise.all(Array.from({ length: UPLOADS }, async (_, i) => {
    const file = samples[i % samples.length];
    const form = new FormData();
    form.append('file', new Blob([readFileSync(join(ROOT, 'samples', file))], { type: 'application/pdf' }), `load-${i}-${file}`);
    const r = await timed('/v1/documents', { method: 'POST', body: form });
    if (r.status === 201) uploaded.push(JSON.parse(r.body).id);
    return r;
  }));
  let ready = 0;
  const deadline = Date.now() + 15 * 60_000;
  while (Date.now() < deadline) {
    const states = await Promise.all(uploaded.map(async (id) => JSON.parse((await timed(`/v1/documents/${id}`)).body).status));
    ready = states.filter((s) => s === 'READY').length;
    if (ready === uploaded.length) break;
    await new Promise((r) => setTimeout(r, 2000));
  }
  const ingestSeconds = (performance.now() - uploadStarted) / 1000;
  report.upload = { parallel: UPLOADS, ...summarize(uploads), accepted: uploaded.length, ready,
    secondsUntilAllReady: Math.round(ingestSeconds), documentsPerMinute: Math.round((ready / ingestSeconds) * 60 * 10) / 10 };
  console.log('load: upload', JSON.stringify(report.upload));

  // 3. retrieval only (below the threshold: no chat call)
  const retrievalUntil = Date.now() + DURATION * 1000;
  const misses = ['Mars kaç uydusu var?', 'Bugün hava nasıl?', 'Futbol maçı kaç kaç bitti?', 'En iyi pizza tarifi nedir?'];
  const retrievals = await pool(CONCURRENCY, async (w) => (Date.now() < retrievalUntil
    ? timed('/v1/questions', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question: misses[w % misses.length] }) })
    : null));
  report.retrieval = { concurrency: CONCURRENCY, seconds: DURATION, ...summarize(retrievals, DURATION) };
  console.log('load: retrieval', JSON.stringify(report.retrieval));

  // 4. chat
  const asks = JSON.parse(readFileSync(join(ROOT, 'eval/eval-set.json'), 'utf8')).questions
    .filter((q) => q.answerable).slice(0, CHAT_QUESTIONS);
  let next = 0;
  const chatStarted = performance.now();
  const chats = await pool(CHAT_CONCURRENCY, async () => (next < asks.length
    ? timed('/v1/questions', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question: asks[next++].question }) })
    : null));
  report.chat = { concurrency: CHAT_CONCURRENCY, ...summarize(chats, (performance.now() - chatStarted) / 1000) };
  console.log('load: chat', JSON.stringify(report.chat));
} finally {
  for (const id of uploaded) await timed(`/v1/documents/${id}`, { method: 'DELETE' });
}
mkdirSync(join(ROOT, 'eval/results'), { recursive: true });
writeFileSync(join(ROOT, 'eval/results/load.json'), JSON.stringify(report, null, 2) + '\n');
console.log('load: written eval/results/load.json');
