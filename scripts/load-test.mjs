#!/usr/bin/env node
// Load test of the running stack (phase 8, docs/capacity.md). Scenarios, one after the other:
//   1. read:      GET /v1/documents, CONCURRENCY parallel clients for DURATION seconds (throughput, latency, errors);
//   2. upload:    UPLOADS sample PDFs at once (the per-instance upload limiter answers 503 10014 above 4 in flight),
//                 then the time until all are READY (ingestion throughput with the real embedding model);
//   3. retrieval: questions below the similarity threshold, CONCURRENCY parallel (embedding + vector search only; the
//                 question-embedding bulkhead answers 503 10030 above 4 in flight). Clients behave: on a 503 they wait
//                 the Retry-After seconds before the next question, as docs/api-questions-integration-v1.md asks;
//   4. chat:      CHAT_QUESTIONS answerable questions, CHAT_CONCURRENCY parallel (the chat bulkhead,
//                 verso.qa.chat-concurrency, answers 503 11002 after 5 s of waiting), after the model server has
//                 drained the previous scenario.
//   5. qa:        (ADR-0001 split trigger) question answering at QA_CLIENTS (1,2,4) concurrent clients with a mix of eval
//                 questions, alone and, for each QA_INGEST value above 0, while that many sample PDFs are ingested at the
//                 same time (3 uploads in flight); QA_STREAM=true asks through /v1/questions/stream and records the time
//                 to the first stage event and to the first text token. Every run records p50/p95/p99, 503/429/errors
//                 and, when METRICS_CONTAINER can scrape the management port, the mean retrieval (embedding + DB) and
//                 chat (model) time and the connection pools' acquire/usage time. Uploads a base corpus (the sample
//                 PDFs) first. Opt-in: SCENARIOS=qa.
// The load is closed-loop: each client waits for its answer before the next request, so latencies under overload
// look better than an open arrival rate would make them. The read scenario lists the account as it is: run it on an
// account with documents (eval.mjs empties the CI account) to measure a realistic page.
// Writes eval/results/<OUT>. Removes its uploads at the end.
//
//   node scripts/load-test.mjs      # after: docker compose up -d --build --wait
//
// Environment: CONCURRENCY (20), DURATION (30), UPLOADS (12), CHAT_QUESTIONS (6), CHAT_CONCURRENCY (3),
// SCENARIOS (read,upload,retrieval,chat; qa is opt-in), QA_CLIENTS (1,2,4), QA_INGEST (0,30), QA_SECONDS (120: the
// minimum length of a run; a run with ingestion lasts until the batch is READY, at most QA_MAX_SECONDS, 480),
// QA_STREAM (false), METRICS_CONTAINER (verso-edge-1: a container on the app's network with wget) and METRICS_URL
// (http://verso-app:8081/actuator/prometheus), OUT (load.json), OLLAMA_CPUS and OLLAMA_GPU (recorded only: what compose
// was given).
import { readFileSync, writeFileSync, readdirSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { cpus } from 'node:os';
import { execFileSync } from 'node:child_process';
import { summarize, summarizeQa, buildMix, sseParser, parseMetrics, diffMetrics } from './load-lib.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const API = `http://localhost:${process.env.VERSO_HTTP_PORT ?? 8080}`;
const IDP = `http://localhost:${process.env.VERSO_KEYCLOAK_PORT ?? 8180}/realms/verso/protocol/openid-connect/token`;
const SECRET = readFileSync(join(ROOT, 'secrets/SECRET_KEYCLOAK_CI_CLIENT_SECRET'), 'utf8').trim();
const CONCURRENCY = Number(process.env.CONCURRENCY ?? 20);
const DURATION = Number(process.env.DURATION ?? 30);
const UPLOADS = Number(process.env.UPLOADS ?? 12);
const CHAT_QUESTIONS = Number(process.env.CHAT_QUESTIONS ?? 6);
const CHAT_CONCURRENCY = Number(process.env.CHAT_CONCURRENCY ?? 3);
const SCENARIOS = (process.env.SCENARIOS ?? 'read,upload,retrieval,chat').split(',');
const QA_CLIENTS = (process.env.QA_CLIENTS ?? '1,2,4').split(',').map(Number);
const QA_INGEST = (process.env.QA_INGEST ?? '0,30').split(',').map(Number);
const QA_SECONDS = Number(process.env.QA_SECONDS ?? 120);
const QA_MAX_SECONDS = Number(process.env.QA_MAX_SECONDS ?? 480);
const QA_STREAM = process.env.QA_STREAM === 'true';
const METRICS_CONTAINER = process.env.METRICS_CONTAINER ?? 'verso-edge-1';
const METRICS_URL = process.env.METRICS_URL ?? 'http://verso-app:8081/actuator/prometheus';
const OUT = process.env.OUT ?? 'load.json';
const MISSES = ['Mars kaç uydusu var?', 'Bugün hava nasıl?', 'Futbol maçı kaç kaç bitti?', 'En iyi pizza tarifi nedir?'];
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));
function gitCommit() {
  try {
    return execFileSync('git', ['rev-parse', '--short=12', 'HEAD'], { cwd: ROOT }).toString().trim();
  } catch {
    return 'unknown';
  }
}

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
  let res;
  let body;
  try {
    res = await fetch(API + path, { ...init, headers: { Authorization: await bearer(), ...(init.headers ?? {}) } });
    body = await res.text();
  } catch (error) { // a dropped connection is a result of the run, not the end of it
    return { status: 0, ms: performance.now() - started, body: String(error) };
  }
  let code;
  try { code = JSON.parse(body)?.error?.code; } catch { code = undefined; }
  return { status: res.status, code, ms: performance.now() - started, body,
    retryAfter: Number(res.headers.get('Retry-After')) || undefined };
}
const question = (text) => timed('/v1/questions', { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ question: text }) });
async function pool(count, task) {
  const results = [];
  await Promise.all(Array.from({ length: count }, async (_, worker) => {
    for (let r; (r = await task(worker)) !== null;) results.push(r);
  }));
  return results;
}

async function askStream(text) {
  const started = performance.now();
  const out = { status: 0, ms: 0 };
  try {
    const res = await fetch(`${API}/v1/questions/stream`, { method: 'POST', body: JSON.stringify({ question: text }),
      headers: { Authorization: await bearer(), 'Content-Type': 'application/json',
        Accept: 'text/event-stream, application/json' } });
    out.status = res.status;
    out.retryAfter = Number(res.headers.get('Retry-After')) || undefined;
    if (!(res.headers.get('content-type') ?? '').includes('text/event-stream')) {
      try { out.code = JSON.parse(await res.text())?.error?.code; } catch { /* no envelope */ }
    } else {
      const decoder = new TextDecoder();
      const feed = sseParser(({ event, data }) => {
        const at = performance.now() - started;
        if (event === 'stage' && out.firstStageMs === undefined) out.firstStageMs = at;
        if (event === 'delta' && out.ttftMs === undefined) out.ttftMs = at;
        if (event === 'error') { // a stream that failed after it opened: counted like the 503 of the JSON endpoint
          try { const e = JSON.parse(data); out.code = e.code; out.retryAfter = e.retryAfter; } catch { /* keep */ }
          out.status = 503;
        }
      });
      for await (const chunk of res.body) feed(decoder.decode(chunk, { stream: true }));
    }
  } catch (error) {
    out.error = String(error);
  }
  out.ms = performance.now() - started;
  return out;
}
function scrape() {
  try {
    return parseMetrics(execFileSync('docker', ['exec', METRICS_CONTAINER, 'wget', '-qO-', METRICS_URL],
      { timeout: 15_000 }).toString());
  } catch {
    return undefined;
  }
}
async function upload(file, name) {
  for (let attempt = 0; attempt < 30; attempt++) {
    const form = new FormData();
    form.append('file', new Blob([readFileSync(join(ROOT, 'samples', file))], { type: 'application/pdf' }), name);
    const r = await timed('/v1/documents', { method: 'POST', body: form });
    if (r.status === 201) return { id: JSON.parse(r.body).id, retries: attempt };
    if (r.status !== 503 && r.status !== 429) throw new Error(`upload ${name}: ${r.status} ${r.body.slice(0, 120)}`);
    await sleep((r.retryAfter ?? 2) * 1000); // the upload limiter (503, 4 in flight) and the backlog limit (429) ask the client to come back
  }
  throw new Error(`upload ${name}: still busy`);
}
async function untilReady(ids, minutes) {
  const deadline = Date.now() + minutes * 60_000;
  let ready = 0;
  while (Date.now() < deadline) {
    const states = await Promise.all(ids.map(async (id) => JSON.parse((await timed(`/v1/documents/${id}`)).body).status));
    ready = states.filter((s) => s === 'READY').length;
    if (ready === ids.length) break;
    await sleep(2000);
  }
  return ready;
}
/** Uploads `count` sample PDFs, three at a time, and waits until they are READY. */
async function ingestBatch(count, prefix, ids) {
  const samples = readdirSync(join(ROOT, 'samples')).filter((f) => f.endsWith('.pdf')).sort();
  const started = performance.now();
  let next = 0;
  let retries = 0;
  await Promise.all(Array.from({ length: 3 }, async () => {
    for (let i; (i = next++) < count;) {
      const file = samples[i % samples.length];
      const r = await upload(file, `${prefix}-${i}-${file}`);
      ids.push(r.id);
      uploaded.push(r.id);
      retries += r.retries;
    }
  }));
  const ready = await untilReady(ids, 8);
  const seconds = (performance.now() - started) / 1000;
  return { documents: count, ready, upload503Retries: retries, secondsUntilReady: Math.round(seconds),
    documentsPerMinute: Math.round((ready / seconds) * 60 * 10) / 10 };
}
async function removeDocuments(ids) {
  for (const id of ids.splice(0)) {
    await timed(`/v1/documents/${id}`, { method: 'DELETE' });
    const at = uploaded.indexOf(id);
    if (at >= 0) uploaded.splice(at, 1);
  }
}
/** One question-answering run: `clients` closed-loop clients; with `ingest` > 0 a batch of that many PDFs goes in at the same time. */
async function qaRun(questions, { clients, ingest }) {
  const mix = buildMix(questions, 5000);
  const ask = QA_STREAM ? askStream : question;
  const ingestIds = [];
  const state = { endedAt: ingest > 0 ? Infinity : 0, summary: undefined };
  const before = scrape();
  const started = Date.now();
  const minEnd = started + QA_SECONDS * 1000;
  const hardEnd = started + QA_MAX_SECONDS * 1000;
  const batch = ingest > 0
    ? ingestBatch(ingest, `lt-ing-${clients}`, ingestIds).then((s) => { state.endedAt = Date.now(); state.summary = s; })
    : Promise.resolve();
  let next = 0;
  const results = await pool(clients, async () => {
    if (Date.now() >= hardEnd || (Date.now() >= minEnd && state.endedAt !== Infinity)) return null;
    const at = Date.now();
    const r = await ask(mix[next++]);
    r.at = at;
    if (r.status === 503) await sleep((r.retryAfter ?? 5) * 1000); // as the integration document asks
    return r;
  });
  await batch.catch((error) => { state.summary = { error: String(error) }; });
  const seconds = (Date.now() - started) / 1000;
  const after = scrape();
  const run = { clients, ingestDocuments: ingest, stream: QA_STREAM, seconds: Math.round(seconds),
    ...summarizeQa(results, seconds) };
  if (ingest > 0) {
    run.ingestion = state.summary;
    run.duringIngestion = summarizeQa(results.filter((r) => r.at < state.endedAt));
    run.afterIngestion = summarizeQa(results.filter((r) => r.at >= state.endedAt));
  }
  if (before && after) run.metrics = diffMetrics(before, after);
  await removeDocuments(ingestIds);
  return run;
}

const report = { measuredAt: new Date().toISOString(), host: { cpus: cpus().length },
  settings: { CONCURRENCY, DURATION, UPLOADS, CHAT_QUESTIONS, CHAT_CONCURRENCY, SCENARIOS,
    qa: SCENARIOS.includes('qa') ? { QA_CLIENTS, QA_INGEST, QA_SECONDS, QA_MAX_SECONDS, QA_STREAM } : undefined,
    ollamaCpus: process.env.OLLAMA_CPUS ?? '2 (compose default)',
    ollamaGpu: process.env.OLLAMA_GPU ?? 'none (deploy/compose.gpu.yaml not used)', commit: gitCommit(),
    // What the server ran with: set them when the stack was started differently from config/verso.yml.
    serverChatConcurrency: process.env.VERSO_QA_CHAT_CONCURRENCY ?? '1 (config/verso.yml)',
    serverMinSimilarity: process.env.VERSO_QA_MIN_SIMILARITY ?? '0.50 (config/verso.yml)' } };
const uploaded = [];
try {
  if (SCENARIOS.includes('read')) {
    const until = Date.now() + DURATION * 1000;
    const reads = await pool(CONCURRENCY, async () => (Date.now() < until ? timed('/v1/documents?size=20') : null));
    report.read = { concurrency: CONCURRENCY, seconds: DURATION, ...summarize(reads, DURATION) };
    console.log('load: read', JSON.stringify(report.read));
  }

  if (SCENARIOS.includes('upload')) {
    const samples = readdirSync(join(ROOT, 'samples')).filter((f) => f.endsWith('.pdf')).sort();
    const started = performance.now();
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
      await sleep(2000);
    }
    const seconds = (performance.now() - started) / 1000;
    report.upload = { parallel: UPLOADS, ...summarize(uploads), accepted: uploaded.length, ready,
      secondsUntilAllReady: Math.round(seconds), documentsPerMinute: Math.round((ready / seconds) * 60 * 10) / 10 };
    console.log('load: upload', JSON.stringify(report.upload));
  }

  if (SCENARIOS.includes('retrieval')) {
    const until = Date.now() + DURATION * 1000;
    const retrievals = await pool(CONCURRENCY, async (w) => {
      if (Date.now() >= until) return null;
      const r = await question(MISSES[w % MISSES.length]);
      if (r.status === 503) await sleep((r.retryAfter ?? 5) * 1000); // as the integration document asks
      return r;
    });
    report.retrieval = { concurrency: CONCURRENCY, seconds: DURATION, ...summarize(retrievals, DURATION) };
    console.log('load: retrieval', JSON.stringify(report.retrieval));
  }

  if (SCENARIOS.includes('chat')) {
    // Once a probe question is answered quickly again: the model server drained the previous scenario.
    const drainUntil = Date.now() + 5 * 60_000;
    while (Date.now() < drainUntil) {
      const probe = await question(MISSES[0]);
      if (probe.status === 200 && probe.ms < 5000) break;
      await sleep(5000);
    }
    const asks = JSON.parse(readFileSync(join(ROOT, 'eval/eval-set.json'), 'utf8')).questions
      .filter((q) => q.answerable).slice(0, CHAT_QUESTIONS);
    let next = 0;
    const started = performance.now();
    const chats = await pool(CHAT_CONCURRENCY, async () => (next < asks.length ? question(asks[next++].question) : null));
    report.chat = { concurrency: CHAT_CONCURRENCY, ...summarize(chats, (performance.now() - started) / 1000) };
    console.log('load: chat', JSON.stringify(report.chat));
  }

  if (SCENARIOS.includes('qa')) {
    const questions = JSON.parse(readFileSync(join(ROOT, 'eval/eval-set.json'), 'utf8')).questions;
    const baseIds = [];
    const samples = readdirSync(join(ROOT, 'samples')).filter((f) => f.endsWith('.pdf')).sort();
    for (const [i, file] of samples.entries()) {
      const r = await upload(file, `lt-base-${i}-${file}`);
      baseIds.push(r.id);
      uploaded.push(r.id);
    }
    const ready = await untilReady(baseIds, 8);
    report.qa = { corpusDocuments: baseIds.length, corpusReady: ready, runs: [] };
    console.log(`load: qa corpus ${ready}/${baseIds.length} READY`);
    for (const ingest of QA_INGEST) {
      for (const clients of QA_CLIENTS) {
        await sleep(5000); // let the model server settle between runs
        const run = await qaRun(questions, { clients, ingest });
        report.qa.runs.push(run);
        console.log('load: qa', JSON.stringify(run));
      }
    }
  }
} finally {
  for (const id of uploaded) await timed(`/v1/documents/${id}`, { method: 'DELETE' });
}
mkdirSync(join(ROOT, 'eval/results'), { recursive: true });
writeFileSync(join(ROOT, 'eval/results', OUT), JSON.stringify(report, null, 2) + '\n');
console.log(`load: written eval/results/${OUT}`);
