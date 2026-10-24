// Pure helpers of scripts/load-test.mjs (no network, no files), so they can be unit-tested (load-lib.test.js).

/** Nearest-rank percentile in whole milliseconds; 0 for no values. */
export function percentile(values, p) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted.length ? Math.round(sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)]) : 0;
}

export const latency = (values) => ({ p50: percentile(values, 50), p95: percentile(values, 95), p99: percentile(values, 99),
  max: Math.round(Math.max(0, ...values)) });

/** Counts per "status" or "status/code", and the latency of the successful ones (status < 400). */
export function summarize(results, seconds) {
  const byStatus = {};
  for (const r of results) {
    const key = r.code ? `${r.status}/${r.code}` : String(r.status);
    byStatus[key] = (byStatus[key] ?? 0) + 1;
  }
  const ok = results.filter((r) => r.status < 400).map((r) => r.ms);
  return { requests: results.length, perSecond: seconds ? Math.round((results.length / seconds) * 10) / 10 : undefined,
    byStatus, okLatencyMs: latency(ok) };
}

/** A question-answering run: answers, deliberate refusals (503/429) and real errors apart, plus time to first token. */
export function summarizeQa(results, seconds) {
  const answered = results.filter((r) => r.status === 200 && !r.code);
  const count = (pred) => results.filter(pred).length;
  const out = { ...summarize(results, seconds), answered: answered.length,
    answeredLatencyMs: latency(answered.map((r) => r.ms)),
    rejected503: count((r) => r.status === 503), rejected429: count((r) => r.status === 429),
    errors: count((r) => (r.status !== 200 && r.status !== 503 && r.status !== 429) || (r.status === 200 && r.code)) };
  const ttft = answered.filter((r) => r.ttftMs !== undefined).map((r) => r.ttftMs);
  if (ttft.length) out.ttftMs = latency(ttft);
  const stage = answered.filter((r) => r.firstStageMs !== undefined).map((r) => r.firstStageMs);
  if (stage.length) out.firstStageMs = latency(stage);
  return out;
}

/** Deterministic mix of eval questions: every `unanswerableEvery`-th one is unanswerable (default 1 of 5). */
export function buildMix(questions, count, unanswerableEvery = 5) {
  const yes = questions.filter((q) => q.answerable).map((q) => q.question);
  const no = questions.filter((q) => !q.answerable).map((q) => q.question);
  if (!yes.length) throw new Error('no answerable questions');
  return Array.from({ length: count }, (_, i) =>
    (no.length && (i + 1) % unanswerableEvery === 0 ? no[Math.floor(i / unanswerableEvery) % no.length] : yes[i % yes.length]));
}

/** Server-sent events: feed it text chunks, it calls onEvent({event, data}) for every complete event. */
export function sseParser(onEvent) {
  let buffer = '';
  return (chunk) => {
    buffer += chunk.replace(/\r\n/g, '\n');
    for (let end; (end = buffer.indexOf('\n\n')) >= 0;) {
      const block = buffer.slice(0, end);
      buffer = buffer.slice(end + 2);
      let event = 'message';
      const data = [];
      for (const line of block.split('\n')) {
        if (line.startsWith(':')) continue; // keep-alive comment
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      }
      if (data.length || event !== 'message') onEvent({ event, data: data.join('\n') });
    }
  };
}

/** Sums and counts of the metrics a load run compares before and after (Prometheus text format). */
const WANTED = [
  [/^verso_qa_retrieval_duration_seconds_(count|sum) (\S+)/, 'retrieval'],
  [/^verso_qa_chat_duration_seconds_(count|sum) (\S+)/, 'chat'],
  [/^hikaricp_connections_acquire_seconds_(count|sum)\{pool="([^"]+)"\} (\S+)/, 'acquire'],
  [/^hikaricp_connections_usage_seconds_(count|sum)\{pool="([^"]+)"\} (\S+)/, 'usage'],
  [/^hikaricp_connections_timeout_total\{pool="([^"]+)"\} (\S+)/, 'timeout'],
];
export function parseMetrics(text) {
  const m = {};
  for (const line of text.split('\n')) {
    for (const [re, name] of WANTED) {
      const hit = re.exec(line);
      if (!hit) continue;
      if (name === 'timeout') m[`timeout:${hit[1]}`] = Number(hit[2]);
      else if (name === 'retrieval' || name === 'chat') m[`${name}:${hit[1]}`] = Number(hit[2]);
      else m[`${name}:${hit[2]}:${hit[1]}`] = Number(hit[3]);
    }
  }
  return m;
}

/** Mean milliseconds per call (sum/count delta) for retrieval, chat and each pool's acquire and usage; timeouts delta. */
export function diffMetrics(before, after) {
  const d = (k) => (after[k] ?? 0) - (before[k] ?? 0);
  const mean = (prefix) => {
    const n = d(`${prefix}:count`);
    return n > 0 ? { calls: n, meanMs: Math.round((d(`${prefix}:sum`) / n) * 1000 * 10) / 10 } : { calls: 0 };
  };
  const out = { retrieval: mean('retrieval'), chat: mean('chat'), pools: {} };
  for (const key of Object.keys(after)) {
    const hit = /^acquire:([^:]+):count$/.exec(key);
    if (!hit) continue;
    const pool = hit[1];
    out.pools[pool] = { acquire: mean(`acquire:${pool}`), usage: mean(`usage:${pool}`), timeouts: d(`timeout:${pool}`) };
  }
  return out;
}
