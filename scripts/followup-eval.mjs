#!/usr/bin/env node
// Follow-up questions end to end (ADR-0046), through the running API with the real models, like scripts/eval.mjs:
//   1. eval/followup-set.json: every two-turn conversation (the first question from eval/eval-set.json in a new
//      conversation, then the follow-up in the same one): found rate, citation hit, expected fact, for the follow-up
//      and, separately, for the first turn;
//   2. the 140 questions of eval/eval-set.json asked one after another in conversations (so each has earlier turns,
//      at most max-turns-per-conversation per conversation): the eval.mjs metrics, to see standalone questions are not
//      hurt by the rewrite;
//   3. latency p50/p95 of both, and the contextQuery the server returned (the rewritten search query, ADR-0046).
// With APP_CONTAINER (e.g. verso-verso-app-1) it also reads that container's log lines of the run ("Question
// answered: ... followUp=... rewriteMs=..."; no content in them, llm-rules 2.1) and joins them to the requests in
// order, for the heuristic's precision and the rewrite time. Writes eval/results/<OUT> (default followup.json).
//
// The arm (rewrite on or off, answer prompt with or without context) is the running app's configuration
// (VERSO_QA_FOLLOWUP_ENABLED, verso.qa.followup.*); ARM only labels the result.
//
// Like eval.mjs it works on the CI client's account (verso-ci) and DELETES EVERY DOCUMENT IN IT first; REUSE=1 keeps
// the eleven samples when they are already there and READY, KEEP_DOCS=1 leaves them at the end (several arms in a
// row). Run it only against a local demo stack.
//
//   ARM=on OUT=followup-on.json APP_CONTAINER=verso-verso-app-1 KEEP_DOCS=1 node scripts/followup-eval.mjs
import { readFileSync, writeFileSync, readdirSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const API = `http://localhost:${process.env.VERSO_HTTP_PORT ?? 8080}`;
const IDP = `http://localhost:${process.env.VERSO_KEYCLOAK_PORT ?? 8180}/realms/verso/protocol/openid-connect/token`;
const SECRET = readFileSync(process.env.CI_CLIENT_SECRET_FILE ?? join(ROOT, 'secrets/SECRET_KEYCLOAK_CI_CLIENT_SECRET'), 'utf8').trim();
const SAMPLES_DIR = join(ROOT, 'samples');
const ARM = process.env.ARM ?? 'unknown';
const PARTS = new Set((process.env.PARTS ?? 'followup,standalone').split(','));
const tr = (s) => s.normalize('NFC').toLocaleLowerCase('tr');
const NOT_FOUND = 'Belgelerde bu sorunun cevabı bulunamadı';
const saysNotFound = (answer) => answer.trim().replace(/\.$/, '').trim() === NOT_FOUND;
/** As eval.mjs: the expected fact as a whole word or number, citation markers removed. */
function containsFact(answer, fact) {
  const text = tr(answer.replace(/\[\d+\]/g, ' '));
  const escaped = tr(fact).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
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

async function samplesReady(files) {
  const docs = (await (await api('/v1/documents?size=100')).json()).data ?? [];
  return docs.length === files.length && docs.every((d) => d.status === 'READY') ? docs.map((d) => d.id) : null;
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

/** One question; the row carries what eval.mjs measures plus the server's contextQuery. */
async function ask(question, conversationId, expected) {
  const started = performance.now();
  const res = await api('/v1/questions', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(conversationId ? { question, conversationId } : { question }) });
  const ms = Math.round(performance.now() - started);
  const body = await res.json();
  const row = { status: res.status, ms };
  if (!res.ok) {
    row.error = body?.error?.code;
    return { row, body };
  }
  row.found = body.found;
  row.contextQuery = body.contextQuery ?? null;
  row.citations = body.citations.map((c) => `${c.fileName}#${c.page}`);
  row.answer = body.answer;
  if (expected.answerable) {
    row.citationHit = body.citations.some((c) => c.fileName === expected.document && expected.pages.includes(c.page));
    row.citationsOnPage = body.citations.filter((c) => c.fileName === expected.document && expected.pages.includes(c.page)).length;
    row.factFound = expected.expect.split('|').some((e) => containsFact(body.answer, e));
  } else {
    row.saysNotFound = saysNotFound(body.answer);
  }
  return { row, body };
}

function quality(rows) {
  const answerable = rows.filter((r) => r.answerable !== false);
  const unanswerable = rows.filter((r) => r.answerable === false);
  return {
    n: rows.length, errors: rows.filter((r) => r.status !== 200).length,
    answerable: answerable.length,
    foundRate: ratio(answerable.filter((r) => r.found).length, answerable.length),
    citationHitRate: ratio(answerable.filter((r) => r.citationHit).length, answerable.length),
    factAccuracy: ratio(answerable.filter((r) => r.factFound).length, answerable.length),
    unanswerable: unanswerable.length,
    notFoundAccuracy: ratio(unanswerable.filter((r) => r.status === 200 && r.saysNotFound).length, unanswerable.length),
    contextQueries: rows.filter((r) => r.contextQuery).length,
    latencyMs: { p50: percentile(rows.map((r) => r.ms), 50), p95: percentile(rows.map((r) => r.ms), 95) },
  };
}

const main = JSON.parse(readFileSync(join(ROOT, 'eval/eval-set.json'), 'utf8')).questions;
const byId = new Map(main.map((q) => [q.id, q]));
const followUpSet = JSON.parse(readFileSync(join(ROOT, 'eval/followup-set.json'), 'utf8')).conversations;
const files = readdirSync(SAMPLES_DIR).filter((f) => f.endsWith('.pdf')).sort();

let ids = process.env.REUSE === '1' ? await samplesReady(files) : null;
if (!ids) {
  for (const doc of (await (await api('/v1/documents?size=100')).json()).data ?? []) {
    await api(`/v1/documents/${doc.id}`, { method: 'DELETE' });
  }
  ids = [];
  for (const f of files) ids.push(await upload(f));
  console.log(`followup-eval: uploaded ${ids.length} samples, waiting for READY`);
  await waitReady(ids);
}
// A warm-up answer outside the measurement (the first model call loads the model).
await ask('Yıllık izin kaç gün?', null, { answerable: false });

const runStarted = new Date();
const requests = []; // every 200 answer in order, to join the log lines
const conversations = [];
if (PARTS.has('followup')) {
  for (const c of followUpSet) {
    const first = byId.get(c.first);
    const one = await ask(first.question, null, first);
    if (one.row.status === 200) requests.push({ part: 'first', id: c.id });
    const conversation = one.body?.conversationId;
    const two = conversation ? await ask(c.followUp, conversation, { answerable: true, ...c }) : { row: { status: 0 } };
    if (two.row.status === 200) requests.push({ part: 'followUp', id: c.id });
    conversations.push({ id: c.id, tags: c.tags, first: { answerable: true, ...one.row }, followUp: { answerable: true, ...two.row } });
    console.log(`followup-eval: ${c.id} first=${one.row.status}/${one.row.citationHit} follow=${two.row.status}/${two.row.citationHit}/${two.row.factFound} q=${two.row.contextQuery ?? '-'}`);
  }
}
const standalone = [];
if (PARTS.has('standalone')) {
  let conversation = null;
  for (const q of main) {
    let asked = await ask(q.question, conversation, q);
    if (asked.row.status === 409 || asked.row.status === 404) asked = await ask(q.question, null, q);
    if (asked.row.status === 200) {
      requests.push({ part: 'standalone', id: q.id });
      conversation = asked.body.conversationId;
    }
    standalone.push({ id: q.id, answerable: q.answerable, tags: q.tags ?? null, ...asked.row });
    console.log(`followup-eval: ${q.id} ${asked.row.status} ${asked.row.ms} ms hit=${asked.row.citationHit ?? '-'} q=${asked.row.contextQuery ?? '-'}`);
  }
}

// Like eval.mjs the uploads are removed at the end, unless KEEP_DOCS=1 keeps them for the next arm (REUSE=1).
if (process.env.KEEP_DOCS !== '1') {
  for (const id of ids) await api(`/v1/documents/${id}`, { method: 'DELETE' });
}

// The server's own account of each answer, from its content-free log line, joined in order.
let logJoin = null;
if (process.env.APP_CONTAINER) {
  const log = execFileSync('docker', ['logs', '--since', runStarted.toISOString(), process.env.APP_CONTAINER],
    { encoding: 'utf8', maxBuffer: 1 << 28, stdio: ['ignore', 'pipe', 'pipe'] });
  const lines = log.split(/\r?\n/).filter((l) => l.includes('Question answered:'));
  const fields = lines.map((l) => ({ followUp: /followUp=(\w+)/.exec(l)?.[1] ?? null,
    rewriteMs: Number(/rewriteMs=(\d+)/.exec(l)?.[1] ?? 0) }));
  logJoin = { lines: fields.length, requests: requests.length, joined: fields.length === requests.length };
  if (logJoin.joined) {
    requests.forEach((r, i) => {
      const target = r.part === 'standalone' ? standalone.find((s) => s.id === r.id)
        : conversations.find((c) => c.id === r.id)[r.part];
      Object.assign(target, fields[i]);
    });
  }
}

const tagged = (tag) => conversations.filter((c) => c.tags.includes(tag)).map((c) => c.followUp);
const heuristic = (() => {
  if (!logJoin?.joined) return null;
  // Positive: the heuristic sent the question to the rewrite (rewritten, unchanged or fallback); negative: standalone.
  const fired = (r) => ['rewritten', 'unchanged', 'fallback'].includes(r.followUp);
  const dependent = conversations.map((c) => c.followUp).filter((r) => r.followUp);
  const independent = standalone.filter((r) => r.followUp && r.followUp !== 'none' && r.followUp !== 'no_history');
  const tp = dependent.filter(fired).length;
  const fp = independent.filter(fired).length;
  return { followUpsFired: tp, followUps: dependent.length, standaloneFired: fp, standalone: independent.length,
    precision: ratio(tp, tp + fp), recall: ratio(tp, dependent.length),
    standaloneFiredIds: independent.filter(fired).map((r) => r.id),
    results: Object.fromEntries(['rewritten', 'unchanged', 'fallback', 'standalone', 'no_history', 'none'].map((k) =>
      [k, [...dependent, ...independent].filter((r) => r.followUp === k).length])) };
})();
const rewriteTimes = [...conversations.map((c) => c.followUp), ...standalone].filter((r) => r.rewriteMs > 0).map((r) => r.rewriteMs);

const summary = {
  arm: ARM,
  followUp: PARTS.has('followup') ? {
    followUps: quality(conversations.map((c) => c.followUp)),
    firstTurns: quality(conversations.map((c) => c.first)),
    byTag: Object.fromEntries(['ellipsis', 'pronoun', 'implicit', 'disambiguation'].map((t) => [t, quality(tagged(t))])),
  } : null,
  standalone: PARTS.has('standalone') ? quality(standalone) : null,
  rewriteMs: rewriteTimes.length ? { n: rewriteTimes.length, p50: percentile(rewriteTimes, 50), p95: percentile(rewriteTimes, 95),
    max: Math.max(...rewriteTimes) } : null,
  heuristic,
  logJoin,
};

let commit = 'unknown';
try {
  commit = execFileSync('git', ['rev-parse', '--short=12', 'HEAD'], { cwd: ROOT }).toString().trim();
} catch { /* not a git checkout */ }
mkdirSync(join(ROOT, 'eval/results'), { recursive: true });
const out = join(ROOT, 'eval/results', process.env.OUT ?? 'followup.json');
writeFileSync(out, JSON.stringify({ measuredAt: new Date().toISOString(), commit, summary, conversations, standalone }, null, 2) + '\n');
console.log('followup-eval: summary', JSON.stringify(summary));
console.log(`followup-eval: written ${out}`);
