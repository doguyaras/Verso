'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');

const lib = () => import('./gate-probe.mjs');
const GATE = { minSimilarity: 0.47, lexicalMinSimilarity: 0.44, specificTermMaxShare: 0.045 };

test('decide mirrors AnswerabilityGate: similarity, specific term, none', async () => {
  const { decide } = await lib();
  assert.strictEqual(decide(0.47, 1, GATE), 'SIMILARITY');
  assert.strictEqual(decide(0.4699, 0.045, GATE), 'SPECIFIC_TERM');
  assert.strictEqual(decide(0.4699, 0.046, GATE), 'NONE');
  assert.strictEqual(decide(0.4399, 0, GATE), 'NONE');
  assert.strictEqual(decide(null, 0, GATE), 'NONE');
});

test('termShare reads no shared term and a capped count as 1', async () => {
  const { termShare } = await lib();
  assert.strictEqual(termShare(null, 1000), 1);
  assert.strictEqual(termShare(5, 0), 1);
  assert.strictEqual(termShare(100, 1000), 1); // cap = ceil(0.10 * 1000)
  assert.strictEqual(termShare(45, 1000), 0.045);
});

test('gateSettings reads verso.qa from the yml, placeholders and overrides included', async () => {
  const { gateSettings } = await lib();
  const yml = '\n  mcp:\n    min-similarity: 0.9\n  qa:\n    top-k: 5\n    min-similarity: ${VERSO_QA_MIN:0.52}\n'
    + '    lexical-min-similarity: 0.44\n    specific-term-max-share: 0.045\n    passage-min-similarity: 0.50\n';
  assert.deepStrictEqual(gateSettings(yml), { minSimilarity: 0.52, lexicalMinSimilarity: 0.44, specificTermMaxShare: 0.045,
    passageMinSimilarity: 0.5 });
  assert.strictEqual(gateSettings(yml, { VERSO_QA_MIN: '0.55' }).minSimilarity, 0.55);
  // Spring's relaxed name overrides the property itself, placeholder or not (ADR-0063 runs).
  assert.strictEqual(gateSettings(yml, { VERSO_QA_MIN: '0.55', VERSO_QA_MINSIMILARITY: '0.6' }).minSimilarity, 0.6);
  assert.strictEqual(gateSettings(yml, { VERSO_QA_PASSAGEMINSIMILARITY: '0.55' }).passageMinSimilarity, 0.55);
  assert.throws(() => gateSettings('\n  qa:\n    top-k: 5\n'), /min-similarity/);
});

test('gateSettings reads this checkout\'s config/verso.yml', async () => {
  const { gateSettings } = await lib();
  const yml = readFileSync(join(__dirname, '..', 'verso-app/src/main/resources/config/verso.yml'), 'utf8');
  const gate = gateSettings(yml, {});
  assert.ok(gate.lexicalMinSimilarity <= gate.minSimilarity);
  assert.ok(gate.specificTermMaxShare < 0.10);
  // The yml and QaProperties' defaults must not drift apart: the probe reads one, a test context the other.
  const java = readFileSync(join(__dirname, '..', 'services/qa/qa-core/src/main/java/com/verso/qa/config/QaProperties.java'), 'utf8');
  const javaDefault = (name) => Number(java.match(new RegExp(`@DefaultValue\\("([0-9.]+)"\\) double ${name}\\b`))[1]);
  for (const name of Object.keys(gate)) assert.strictEqual(javaDefault(name), gate[name], name);
  assert.strictEqual(gate.passageMinSimilarity, 0.55); // ADR-0063
});

test('cosine of parallel, orthogonal and scaled vectors', async () => {
  const { cosine } = await lib();
  assert.strictEqual(cosine([1, 0], [2, 0]), 1);
  assert.strictEqual(cosine([1, 0], [0, 3]), 0);
  assert.ok(Math.abs(cosine([1, 1], [1, 0]) - Math.SQRT1_2) < 1e-12);
});
