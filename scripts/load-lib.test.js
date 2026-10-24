'use strict';
const test = require('node:test');
const assert = require('node:assert');

const lib = () => import('./load-lib.mjs');

test('percentile uses the nearest rank and 0 for no values', async () => {
  const { percentile } = await lib();
  assert.strictEqual(percentile([], 99), 0);
  assert.strictEqual(percentile([5], 99), 5);
  const hundred = Array.from({ length: 100 }, (_, i) => i + 1);
  assert.strictEqual(percentile(hundred, 50), 50);
  assert.strictEqual(percentile(hundred, 95), 95);
  assert.strictEqual(percentile(hundred, 99), 99);
});

test('summarizeQa separates answers, 503/429 refusals and real errors', async () => {
  const { summarizeQa } = await lib();
  const s = summarizeQa([
    { status: 200, ms: 1000, ttftMs: 200, firstStageMs: 20 },
    { status: 200, ms: 3000, ttftMs: 400, firstStageMs: 30 },
    { status: 503, code: 11002, ms: 5100 },
    { status: 429, ms: 4 },
    { status: 500, ms: 9 },
    { status: 0, ms: 1 },
    { status: 200, code: 11001, ms: 50 }, // a stream that opened and then failed
  ], 10);
  assert.strictEqual(s.answered, 2);
  assert.strictEqual(s.rejected503, 1);
  assert.strictEqual(s.rejected429, 1);
  assert.strictEqual(s.errors, 3);
  assert.deepStrictEqual(s.answeredLatencyMs, { p50: 1000, p95: 3000, p99: 3000, max: 3000 });
  assert.strictEqual(s.ttftMs.max, 400);
  assert.strictEqual(s.firstStageMs.p50, 20);
  assert.strictEqual(s.byStatus['503/11002'], 1);
});

test('buildMix is deterministic and takes one unanswerable question in five', async () => {
  const { buildMix } = await lib();
  const qs = [{ question: 'a', answerable: true }, { question: 'b', answerable: true }, { question: 'x', answerable: false }];
  assert.deepStrictEqual(buildMix(qs, 10), buildMix(qs, 10));
  const mix = buildMix(qs, 10);
  assert.strictEqual(mix.filter((q) => q === 'x').length, 2);
  assert.strictEqual(mix[4], 'x');
  assert.throws(() => buildMix([{ question: 'x', answerable: false }], 3), /answerable/);
});

test('sseParser handles chunk boundaries, CRLF and keep-alive comments', async () => {
  const { sseParser } = await lib();
  const seen = [];
  const feed = sseParser((e) => seen.push(e));
  feed('event: stage\ndata: {"stage":"RETR');
  feed('IEVING"}\n\n: keep-alive\n\nevent: delta\r\ndata: {"text":"Mer"}\r\n\r\nevent: done\ndata: {}\n\n');
  assert.deepStrictEqual(seen.map((e) => e.event), ['stage', 'delta', 'done']);
  assert.strictEqual(seen[0].data, '{"stage":"RETRIEVING"}');
  assert.strictEqual(JSON.parse(seen[1].data).text, 'Mer');
});

test('parseMetrics and diffMetrics give mean milliseconds per call and pool timeouts', async () => {
  const { parseMetrics, diffMetrics } = await lib();
  const text = (n, sum, chat, acq, use, to) => [
    `verso_qa_retrieval_duration_seconds_count ${n}`, `verso_qa_retrieval_duration_seconds_sum ${sum}`,
    `verso_qa_chat_duration_seconds_count ${n}`, `verso_qa_chat_duration_seconds_sum ${chat}`,
    `hikaricp_connections_acquire_seconds_count{pool="verso-db"} ${n}`,
    `hikaricp_connections_acquire_seconds_sum{pool="verso-db"} ${acq}`,
    `hikaricp_connections_usage_seconds_count{pool="verso-db"} ${n}`,
    `hikaricp_connections_usage_seconds_sum{pool="verso-db"} ${use}`,
    `hikaricp_connections_timeout_total{pool="verso-db"} ${to}`,
    'something_else 1'].join('\n');
  const diff = diffMetrics(parseMetrics(text(2, 1, 4, 0.2, 0.4, 1)), parseMetrics(text(12, 6, 24, 0.4, 0.9, 3)));
  assert.deepStrictEqual(diff.retrieval, { calls: 10, meanMs: 500 });
  assert.deepStrictEqual(diff.chat, { calls: 10, meanMs: 2000 });
  assert.deepStrictEqual(diff.pools['verso-db'].acquire, { calls: 10, meanMs: 20 });
  assert.deepStrictEqual(diff.pools['verso-db'].usage, { calls: 10, meanMs: 50 });
  assert.strictEqual(diff.pools['verso-db'].timeouts, 2);
});
