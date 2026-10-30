// The answerability gate's inputs per question (ADR-0022, ADR-0063), measured against the running stack: the
// cosine similarity of the most similar of the five fused passages and that passage's specific-term share, as
// QuestionServiceImpl reads them. eval.mjs calls it with GATE_PROBE=1 (next to the answers) or GATE_PROBE=only (the
// probe alone); eval/README.md "Eşikler ve karar".
//
// How: the question is embedded by the stack's own embedding server (VERSO_EVAL_OLLAMA_URL, as RetrievalEvalTest;
// deploy/compose.local.yaml publishes it on 127.0.0.1:11434) and the hybrid query of RetrievalRepository.hybrid runs in
// the stack's PostgreSQL through `docker exec ... psql`, restricted to the documents this run uploaded instead of the
// reader's principals. The SQL mirrors RetrievalRepository (hybrid, rarestSharedTermCounts) and
// DocumentRetrievalServiceImpl.termShares: change them together.
//
// GATE_PROBE_SPLIT=1 also embeds each top passage's own text and its context header alone (ADR-0061), so the same run
// shows what the gate would read from header-less vectors. Nothing of a document leaves the machine: both servers are
// the local stack's.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

const TERM_SHARE_LIMIT = 0.10; // DocumentRetrieval.TERM_SHARE_LIMIT
const VECTOR_CANDIDATES = 20; // verso.document.retrieval.vector-candidates
const LEXICAL_CANDIDATES = 20; // verso.document.retrieval.lexical-candidates
const RRF_K = 60; // verso.document.retrieval.rrf-k
const TOP_K = 5; // verso.qa.top-k
const EMBEDDING_MODEL = 'bge-m3:567m';

/**
 * The gate's thresholds as config/verso.yml sets them under verso.qa (a "${ENV:default}" placeholder reads its default),
 * each overridable as the application is: by Spring's relaxed environment name (VERSO_QA_MINSIMILARITY), which wins,
 * or by the variable the yml names. Give the probe the same environment as the app container.
 */
export function gateSettings(yml, env = {}) {
  const qa = yml.slice(yml.indexOf('\n  qa:\n'));
  const read = (key) => {
    const m = qa.match(new RegExp(`\\n    ${key}: (?:\\$\\{(\\w+):)?([0-9.]+)\\}?`));
    if (!m) throw new Error(`verso.qa.${key} not found in config/verso.yml`);
    const relaxed = env[`VERSO_QA_${key.replace(/-/g, '').toUpperCase()}`];
    if (relaxed !== undefined) return Number(relaxed);
    return Number(m[1] && env[m[1]] !== undefined ? env[m[1]] : m[2]);
  };
  return { minSimilarity: read('min-similarity'), lexicalMinSimilarity: read('lexical-min-similarity'),
    specificTermMaxShare: read('specific-term-max-share'), passageMinSimilarity: read('passage-min-similarity') };
}

/** AnswerabilityGate.decide on the probe's numbers. */
export function decide(best, share, gate) {
  if (best === null) return 'NONE';
  if (best >= gate.minSimilarity) return 'SIMILARITY';
  if (best >= gate.lexicalMinSimilarity && share <= gate.specificTermMaxShare) return 'SPECIFIC_TERM';
  return 'NONE';
}

/** DocumentRetrievalServiceImpl.termShares: no shared term, or one counted up to the cap, reads as 1. */
export function termShare(chunks, total) {
  if (chunks === null || chunks === undefined || total === 0) return 1;
  const cap = Math.ceil(TERM_SHARE_LIMIT * total);
  return chunks >= cap ? 1 : chunks / total;
}

export function cosine(a, b) {
  let dot = 0, na = 0, nb = 0;
  for (let i = 0; i < a.length; i++) {
    dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i];
  }
  return dot / Math.sqrt(na * nb);
}

const round4 = (x) => (x === null ? null : Math.round(x * 10_000) / 10_000);
const sqlText = (s) => `'${s.replace(/\u0000/g, ' ').replace(/'/g, "''")}'`;

/** RetrievalRepository.hybrid + rarestSharedTermCounts for one question, as one JSON line. */
function questionSql(text, vector, documentIds) {
  const q = `CAST('[${vector.join(',')}]' AS extensions.vector)`;
  const t = sqlText(text);
  const docs = `ARRAY[${documentIds.map((id) => `'${id}'`).join(',')}]::uuid[]`;
  const own = `d.id = ANY(${docs}) AND d.status = 'READY' AND c.embedding_model = '${EMBEDDING_MODEL}'`;
  const orQuery = `(SELECT string_agg('''' || replace(t.lexeme, '''', '''''') || '''', ' | ')
      FROM unnest(to_tsvector('turkish', ${t})) t WHERE strpos(t.lexeme, chr(92)) = 0)::tsquery`;
  return `BEGIN;
SET LOCAL search_path = document, extensions, public;
SET LOCAL hnsw.iterative_scan = 'relaxed_order';
SET LOCAL hnsw.ef_search = '${Math.max(40, VECTOR_CANDIDATES * 2)}';
WITH vec AS (
  SELECT v.id, row_number() OVER (ORDER BY v.distance, v.id) AS rank
  FROM (SELECT c.id, c.embedding <=> ${q} AS distance FROM document.document_chunk c
        JOIN document.document d ON d.id = c.document_id WHERE ${own}
        ORDER BY c.embedding <=> ${q} LIMIT ${VECTOR_CANDIDATES}) v
), lex AS (
  SELECT l.id, row_number() OVER (ORDER BY l.score DESC, l.id) AS rank
  FROM (SELECT c.id, ts_rank_cd(COALESCE(c.header_vector, ''::tsvector) || c.search_vector, query.terms) AS score
        FROM document.document_chunk c JOIN document.document d ON d.id = c.document_id
        CROSS JOIN (SELECT ${orQuery} AS terms) query
        WHERE ${own} AND (c.search_vector @@ query.terms OR c.header_vector @@ query.terms)
        ORDER BY score DESC, c.id LIMIT ${LEXICAL_CANDIDATES}) l
), fused AS (
  SELECT u.id, sum(1.0 / (${RRF_K} + u.rank)) AS score FROM (SELECT id, rank FROM vec UNION ALL SELECT id, rank FROM lex) u
  GROUP BY u.id
), top AS (
  SELECT c.id, d.file_name, c.page_number, f.score, 1 - (c.embedding <=> ${q}) AS sim, c.content, c.context_header,
         c.search_vector
  FROM fused f JOIN document.document_chunk c ON c.id = f.id JOIN document.document d ON d.id = c.document_id
  ORDER BY f.score DESC, sim DESC, c.id LIMIT ${TOP_K}
), total AS (
  SELECT COALESCE(sum(d.chunk_count), 0) AS n FROM document.document d
  WHERE d.id = ANY(${docs}) AND d.status = 'READY' AND d.embedding_model = '${EMBEDDING_MODEL}'
), terms AS (
  SELECT DISTINCT t.lexeme, ('''' || replace(t.lexeme, '''', '''''') || '''')::tsquery AS query
  FROM unnest(to_tsvector('turkish', ${t})) t WHERE strpos(t.lexeme, chr(92)) = 0
), shared AS (
  SELECT top.id, terms.lexeme FROM top JOIN terms ON top.search_vector @@ terms.query
), frequency AS (
  SELECT terms.lexeme, (SELECT count(*) FROM (SELECT 1 FROM document.document_chunk c
      JOIN document.document d ON d.id = c.document_id WHERE ${own} AND c.search_vector @@ terms.query
      LIMIT (SELECT ceil(${TERM_SHARE_LIMIT} * n)::bigint FROM total)) hits) AS chunks
  FROM terms WHERE terms.lexeme IN (SELECT lexeme FROM shared)
), rare AS (
  SELECT shared.id, min(frequency.chunks) AS chunks FROM shared JOIN frequency USING (lexeme) GROUP BY shared.id
)
SELECT json_build_object('total', (SELECT n FROM total), 'top', COALESCE(json_agg(json_build_object(
         'file', top.file_name, 'page', top.page_number, 'sim', top.sim, 'chunks', rare.chunks,
         'content', top.content, 'header', top.context_header) ORDER BY top.score DESC, top.sim DESC, top.id), '[]'))
FROM top LEFT JOIN rare ON rare.id = top.id;
COMMIT;
`;
}

async function embed(baseUrl, inputs) {
  const out = [];
  for (let i = 0; i < inputs.length; i += 32) {
    const res = await fetch(`${baseUrl}/api/embed`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ model: EMBEDDING_MODEL, input: inputs.slice(i, i + 32) }) });
    if (!res.ok) throw new Error(`embedding: ${res.status}`);
    out.push(...(await res.json()).embeddings);
  }
  return out;
}

/**
 * Per question id: best (cosine of the most similar fused passage), share (its specific-term share), decision (the
 * gate's, with `gate`), top (file#page and cosine of the five, fused order) and, with split, bestBody / bestHeader
 * (the same passage's own text / header alone against the question) and maxBody (the most similar own text of the
 * five).
 */
export async function probeGate({ questions, documentIds, gate, split = false,
  ollamaUrl = 'http://localhost:11434', postgres = 'verso-postgres-1' }) {
  const vectors = await embed(ollamaUrl, questions.map((q) => q.question));
  const sql = questions.map((q, i) => questionSql(q.question, vectors[i], documentIds)).join('');
  let output;
  try {
    output = execFileSync('docker', ['exec', '-i', postgres, 'psql', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1',
      '-U', 'postgres', '-d', 'verso'], { input: sql, maxBuffer: 1 << 30, stdio: ['pipe', 'pipe', 'ignore'] });
  } catch (e) {
    // psql's error quotes the failing statement (question text, document passages): only the exit status is reported.
    throw new Error(`probe: psql in ${postgres} failed (exit ${e.status})`);
  }
  const lines = output.toString().split('\n').filter(Boolean);
  if (lines.length !== questions.length) throw new Error(`probe: ${lines.length} results for ${questions.length} questions`);
  const results = new Map();
  const pending = [];
  questions.forEach((q, i) => {
    const { total, top } = JSON.parse(lines[i]);
    const best = top.length ? top.reduce((a, b) => (b.sim > a.sim ? b : a)) : null;
    const share = best ? termShare(best.chunks, total) : 1;
    const decision = decide(best?.sim ?? null, share, gate);
    // AnswerabilityGate.promptPassages: what the chat model is given when the gate is open.
    const prompt = decision === 'NONE' ? 0 : top.filter((p) => p === best || p.sim >= gate.passageMinSimilarity).length;
    const row = { best: round4(best?.sim ?? null), share: round4(share), decision, prompt,
      top: top.map((p) => `${p.file}#${p.page} ${round4(p.sim)}`) };
    results.set(q.id, row);
    if (split) pending.push({ row, vector: vectors[i], top, best });
  });
  if (split) {
    const texts = [...new Set(pending.flatMap(({ top }) => top.flatMap((p) => [p.content, p.header ?? ''])))]
      .filter(Boolean);
    const embedded = new Map((await embed(ollamaUrl, texts)).map((v, i) => [texts[i], v]));
    for (const { row, vector, top, best } of pending) {
      if (!best) continue;
      row.bestBody = round4(cosine(vector, embedded.get(best.content)));
      row.bestHeader = best.header ? round4(cosine(vector, embedded.get(best.header))) : null;
      row.topBody = top.map((p) => round4(cosine(vector, embedded.get(p.content))));
      row.maxBody = Math.max(...row.topBody);
    }
  }
  return results;
}

/** gateSettings from this checkout's config/verso.yml and the environment. */
export function currentGate(root, env = process.env) {
  return gateSettings(readFileSync(`${root}/verso-app/src/main/resources/config/verso.yml`, 'utf8'), env);
}
