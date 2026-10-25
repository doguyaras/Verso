// Step 1 of the embedding comparison (docs/evidence/embedding-olcumu.md): the model-independent half of the hybrid
// search. Loads the chunks into a scratch PostgreSQL (pgvector image; only to_tsvector('turkish') is used) and, for
// every eval question, computes with the production SQL (RetrievalRepository.OR_QUERY / hybrid / rarestSharedTermCounts):
//   lex   - the lexical candidate list (ts_rank_cd, terms OR-ed, top 20, ties by chunk id), best first;
//   share - for each chunk, the document frequency of its rarest question term in chunks (min over shared terms).
// Output: JSON for olcum.mjs. Usage: node lex.mjs <chunks.jsonl> <eval-set.json> <out.json> <scratch-container>
import { readFileSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';

const [chunksPath, setPath, outPath, container] = process.argv.slice(2);
const chunks = readFileSync(chunksPath, 'utf8').replace(/^﻿/, '').split(/\r?\n/).filter(Boolean).map((l) => JSON.parse(l));
const questions = JSON.parse(readFileSync(setPath, 'utf8')).questions;

const lit = (s, tag) => {
  if (s.includes(`$${tag}$`)) throw new Error('dollar tag clash');
  return `$${tag}$${s}$${tag}$`;
};
// A session-only table: the script also runs against the stack's own PostgreSQL without leaving anything behind.
let sql = `DROP TABLE IF EXISTS pg_temp.chunk;
CREATE TEMP TABLE chunk (id serial PRIMARY KEY, file text, page int, idx int, content text,
  sv tsvector GENERATED ALWAYS AS (to_tsvector('turkish', content)) STORED);
CREATE INDEX ON chunk USING gin (sv);\n`;
for (const c of chunks) {
  sql += `INSERT INTO chunk (file, page, idx, content) VALUES (${lit(c.file, 'f')}, ${c.page}, ${c.idx}, ${lit(c.content, 'c')});\n`;
}
const total = chunks.length;
const header = `\\echo @@BEGIN\n`;
for (const q of questions) {
  const Q = lit(q.question.replace(/\u0000/g, ' '), 'q');
  sql += `
WITH query AS (
  SELECT (SELECT string_agg('''' || replace(t.lexeme, '''', '''''') || '''', ' | ')
          FROM unnest(to_tsvector('turkish', ${Q})) t WHERE strpos(t.lexeme, chr(92)) = 0)::tsquery AS terms
), lex AS (
  SELECT COALESCE(json_agg(l.id ORDER BY l.score DESC, l.id), '[]') AS ids
  FROM (SELECT c.id, ts_rank_cd(c.sv, query.terms) AS score FROM chunk c, query
        WHERE c.sv @@ query.terms ORDER BY score DESC, c.id LIMIT 20) l
), terms AS (
  SELECT DISTINCT t.lexeme, ('''' || replace(t.lexeme, '''', '''''') || '''')::tsquery AS tq
  FROM unnest(to_tsvector('turkish', ${Q})) t WHERE strpos(t.lexeme, chr(92)) = 0
), freq AS (
  SELECT terms.lexeme, terms.tq, (SELECT count(*) FROM chunk c WHERE c.sv @@ terms.tq) AS n FROM terms
), shared AS (
  SELECT c.id, min(freq.n) AS n FROM chunk c JOIN freq ON c.sv @@ freq.tq GROUP BY c.id
)
SELECT json_build_object('id', ${lit(q.id, 'i')}, 'lex', (SELECT ids FROM lex),
  'share', COALESCE((SELECT json_object_agg(id, n) FROM shared), '{}'::json));\n`;
}
const r = spawnSync('docker', ['exec', '-i', container, 'psql', '-U', 'postgres', '-At', '-v', 'ON_ERROR_STOP=1'],
  { input: sql, encoding: 'utf8', maxBuffer: 1 << 28 });
if (r.status !== 0) { console.error(r.stderr); process.exit(1); }
const rows = r.stdout.split(/\r?\n/).filter((l) => l.startsWith('{')).map((l) => JSON.parse(l));
if (rows.length !== questions.length) throw new Error(`expected ${questions.length} rows, got ${rows.length}`);
writeFileSync(outPath, JSON.stringify({ total, cap: Math.ceil(0.10 * total), chunks: chunks.map((c, i) => ({ id: i + 1, file: c.file, page: c.page, idx: c.idx, content: c.content })), questions: rows }));
console.log(`lex ok: ${rows.length} questions, ${total} chunks`);
