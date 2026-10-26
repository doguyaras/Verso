'use strict';
// Verifies eval/multihop-set.json against the sample documents' source text.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..');
const set = JSON.parse(fs.readFileSync(path.join(root, 'eval', 'multihop-set.json'), 'utf8'));
const KINDS = new Set(['comparison', 'bridge', 'multi-part']);

// Pages are separated by a line consisting of exactly "---".
function pagesOf(base) {
  const text = fs.readFileSync(path.join(root, 'samples', 'src', base + '.txt'), 'utf8')
    .normalize('NFC').replace(/\r\n/g, '\n');
  return text.split(/^---$/m).map((p) => p.toLocaleLowerCase('tr'));
}

function escapeRegex(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

// Whole word/number match with number-separator awareness.
function occurs(alt, pageText) {
  const needle = alt.normalize('NFC').toLocaleLowerCase('tr').trim();
  if (!needle) return false;
  const re = new RegExp('(?<![\\p{L}\\p{N}]|\\p{N}[.,])' + escapeRegex(needle) + '(?![\\p{L}\\p{N}]|[.,]\\p{N})', 'u');
  return re.test(pageText);
}

test('set shape', () => {
  assert.equal(set.version, 1);
  assert.equal(set.questions.length, 20);
  const ids = set.questions.map((q) => q.id);
  assert.equal(new Set(ids).size, 20);
  ids.forEach((id, i) => assert.equal(id, 'mh-' + String(i + 1).padStart(2, '0')));
  for (const q of set.questions) {
    assert.ok(q.question && q.question.trim(), q.id + ' question');
    assert.ok(KINDS.has(q.kind), q.id + ' kind');
    assert.ok(q.sources.length >= 1 && q.sources.length <= 3, q.id + ' source count');
  }
  const twoDocs = set.questions.filter((q) => new Set(q.sources.map((s) => s.document)).size >= 2);
  assert.ok(twoDocs.length >= 14, 'two-document items: ' + twoDocs.length);
  const partial = set.questions.filter((q) => q.partial === true);
  assert.equal(partial.length, 2);
  for (const q of partial) assert.ok(typeof q.missing === 'string' && q.missing.trim(), q.id + ' missing');
  for (const q of set.questions.filter((x) => x.partial !== true)) assert.equal(q.missing, undefined, q.id);
});

for (const q of set.questions) {
  test(q.id + ' sources are grounded', () => {
    for (const s of q.sources) {
      assert.ok(fs.existsSync(path.join(root, 'samples', s.document)), 'missing pdf ' + s.document);
      const base = s.document.replace(/\.pdf$/, '');
      assert.ok(fs.existsSync(path.join(root, 'samples', 'src', base + '.txt')), 'missing txt ' + base);
      const pages = pagesOf(base);
      assert.ok(Array.isArray(s.pages) && s.pages.length > 0, q.id + ' pages');
      for (const p of s.pages) {
        assert.ok(Number.isInteger(p) && p >= 1 && p <= pages.length, q.id + ' page ' + p + ' out of range for ' + s.document);
      }
      const alts = String(s.expect).split('|');
      const found = alts.some((alt) => s.pages.some((p) => occurs(alt, pages[p - 1])));
      assert.ok(found, q.id + ': "' + s.expect + '" not on pages ' + s.pages.join(',') + ' of ' + s.document);
    }
  });
}
