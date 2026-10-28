'use strict';
// The skill library (ADR-0057): every skills/*.json validates against the format rules (a JS mirror of the work
// module's SkillDefinitionValidator; the Java RepoSkillsTest checks the same files with the real validator), every
// golden set fits its skill, and the pure functions of scripts/skill-eval.mjs behave.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const root = path.join(__dirname, '..');
const skillsDir = path.join(root, 'skills');
const goldenDir = path.join(skillsDir, 'golden');
const read = (file) => JSON.parse(fs.readFileSync(file, 'utf8'));
const skillFiles = fs.readdirSync(skillsDir).filter((f) => f.endsWith('.json')).sort();
const skills = skillFiles.map((f) => ({ file: f, def: read(path.join(skillsDir, f)) }));
const evalModule = () => import(pathToFileURL(path.join(__dirname, 'skill-eval.mjs')).href);

const CATEGORIES = ['PRODUCTIVITY', 'SALES', 'LEGAL_COMPLIANCE', 'PROJECT', 'QUALITY_CUSTOMER', 'PROCESS'];
const LICENSES = ['Apache-2.0', 'MIT', 'CC-BY-4.0'];
const KEY = /^[a-z][a-zA-Z0-9]{1,31}$/;
const PLACEHOLDER = /\{\{([^}]*)\}\}/g;

const len = (s) => (typeof s === 'string' ? [...s].length : -1);
const between = (s, min, max) => len(s) >= min && len(s) <= max;
const intIn = (n, min, max) => Number.isInteger(n) && n >= min && n <= max;

/** Mirror of the Java validator: returns "path: message" lines, empty when valid. */
function validate(d, fileId) {
  const e = [];
  const req = (cond, where, msg) => { if (!cond) e.push(`${where}: ${msg}`); };
  req(d.formatVersion === 1, 'formatVersion', 'must be 1');
  req(typeof d.id === 'string' && /^[a-z][a-z0-9-]{2,63}$/.test(d.id), 'id', 'format');
  if (fileId) req(d.id === fileId, 'id', 'must equal the file name');
  req(typeof d.version === 'string' && /^\d+\.\d+\.\d+$/.test(d.version), 'version', 'semantic version');
  req(between(d.name, 3, 80), 'name', '3..80');
  req(between(d.description, 20, 600), 'description', '20..600');
  req(CATEGORIES.includes(d.category), 'category', 'unknown');
  req(typeof d.slash === 'string' && /^[a-z0-9-]{2,32}$/.test(d.slash), 'slash', 'format');
  const inputs = Array.isArray(d.inputs) ? d.inputs : [];
  req(inputs.length >= 1 && inputs.length <= 10, 'inputs', '1..10');
  const byKey = new Map();
  inputs.forEach((i, n) => {
    const w = `inputs[${n}]`;
    req(typeof i.key === 'string' && KEY.test(i.key), `${w}.key`, 'format');
    req(!byKey.has(i.key), `${w}.key`, 'duplicate');
    byKey.set(i.key, i);
    req(between(i.label, 1, 80), `${w}.label`, '1..80');
    req(typeof i.required === 'boolean', `${w}.required`, 'boolean');
    req(i.help == null || len(i.help) <= 300, `${w}.help`, '<=300');
    req(i.placeholder == null || len(i.placeholder) <= 200, `${w}.placeholder`, '<=200');
    switch (i.type) {
      case 'TEXT': req(intIn(i.maxLength, 1, 500), `${w}.maxLength`, '1..500'); break;
      case 'LONG_TEXT': req(intIn(i.maxLength, 1, 30000), `${w}.maxLength`, '1..30000'); break;
      case 'SELECT': {
        const o = Array.isArray(i.options) ? i.options : [];
        req(o.length >= 2 && o.length <= 20, `${w}.options`, '2..20');
        o.forEach((opt, k) => {
          req(typeof opt.value === 'string' && /^[a-z0-9-]{1,40}$/.test(opt.value), `${w}.options[${k}].value`, 'format');
          req(between(opt.label, 1, 80), `${w}.options[${k}].label`, '1..80');
        });
        req(new Set(o.map((x) => x.value)).size === o.length, `${w}.options`, 'duplicate value');
        break;
      }
      case 'DOCUMENTS': case 'COLLECTIONS': req(intIn(i.maxItems, 1, 20), `${w}.maxItems`, '1..20'); break;
      default: e.push(`${w}.type: unknown`);
    }
  });
  req(inputs.some((i) => i.required === true), 'inputs', 'at least one required');
  const textual = (k) => ['TEXT', 'LONG_TEXT', 'SELECT'].includes(byKey.get(k)?.type);
  const placeholders = (s, w) => { for (const m of String(s).matchAll(PLACEHOLDER)) req(textual(m[1].trim()), w, `unknown placeholder {{${m[1]}}}`); };
  if (d.grounding != null) {
    const g = d.grounding;
    const q = Array.isArray(g.queries) ? g.queries : [];
    req(q.length >= 1 && q.length <= 3, 'grounding.queries', '1..3');
    q.forEach((t, n) => { req(between(t, 1, 300), `grounding.queries[${n}]`, '1..300'); placeholders(t, `grounding.queries[${n}]`); });
    req(intIn(g.maxPassages, 1, 12), 'grounding.maxPassages', '1..12');
    req(g.documentsInput == null || byKey.get(g.documentsInput)?.type === 'DOCUMENTS', 'grounding.documentsInput', 'must name a DOCUMENTS input');
    req(g.collectionsInput == null || byKey.get(g.collectionsInput)?.type === 'COLLECTIONS', 'grounding.collectionsInput', 'must name a COLLECTIONS input');
    req(g.scopeCollections == null, 'grounding.scopeCollections', 'only customer-authored skills');
  }
  const steps = Array.isArray(d.steps) ? d.steps : [];
  req(steps.length >= 1 && steps.length <= 4, 'steps', '1..4');
  const stepKeys = new Set();
  steps.forEach((s, n) => {
    const w = `steps[${n}]`;
    req(typeof s.key === 'string' && /^[a-z][a-z0-9-]{1,31}$/.test(s.key) && !stepKeys.has(s.key), `${w}.key`, 'format or duplicate');
    stepKeys.add(s.key);
    req(between(s.title, 1, 80), `${w}.title`, '1..80');
    req(between(s.instruction, 20, 4000), `${w}.instruction`, '20..4000');
    placeholders(s.instruction, `${w}.instruction`);
    if (n < steps.length - 1) req(intIn(s.maxChars, 200, 6000), `${w}.maxChars`, '200..6000');
    else req(s.maxChars == null, `${w}.maxChars`, 'null on the last step');
  });
  const sections = Array.isArray(d.output?.sections) ? d.output.sections : [];
  req(sections.length >= 1 && sections.length <= 8, 'output.sections', '1..8');
  const sectionKeys = new Set();
  sections.forEach((s, n) => {
    const w = `output.sections[${n}]`;
    req(typeof s.key === 'string' && KEY.test(s.key) && !sectionKeys.has(s.key), `${w}.key`, 'format or duplicate');
    sectionKeys.add(s.key);
    req(between(s.title, 1, 80), `${w}.title`, '1..80');
    if (s.kind === 'TEXT') {
      req(intIn(s.maxLength, 1, 4000), `${w}.maxLength`, '1..4000');
      req(s.columns == null && s.minRows == null && s.maxRows == null, w, 'TEXT has no columns or rows');
    } else if (s.kind === 'ROWS') {
      const c = Array.isArray(s.columns) ? s.columns : [];
      req(c.length >= 1 && c.length <= 8, `${w}.columns`, '1..8');
      const ck = new Set();
      c.forEach((col, k) => {
        const cw = `${w}.columns[${k}]`;
        req(typeof col.key === 'string' && KEY.test(col.key) && col.key !== 'evidence' && !ck.has(col.key), `${cw}.key`, 'format, duplicate or reserved');
        ck.add(col.key);
        req(between(col.label, 1, 80), `${cw}.label`, '1..80');
        req(intIn(col.maxLength, 1, 2000), `${cw}.maxLength`, '1..2000');
        if (col.allowed != null) {
          req(Array.isArray(col.allowed) && col.allowed.length >= 1 && col.allowed.length <= 10
            && col.allowed.every((a) => between(a, 1, 40)), `${cw}.allowed`, '1..10 values of 1..40');
        }
      });
      req(intIn(s.maxRows, 1, 50), `${w}.maxRows`, '1..50');
      req(intIn(s.minRows, 0, 50) && s.minRows <= s.maxRows, `${w}.minRows`, '0..maxRows');
      req(s.maxLength == null, `${w}.maxLength`, 'null for ROWS');
    } else e.push(`${w}.kind: unknown`);
  });
  req(intIn(d.maxOutputChars, 500, 30000), 'maxOutputChars', '500..30000');
  const rubric = Array.isArray(d.rubric) ? d.rubric : [];
  req(rubric.length >= 3 && rubric.length <= 8, 'rubric', '3..8');
  const rk = new Set();
  rubric.forEach((r, n) => {
    req(typeof r.key === 'string' && /^[a-z][a-z-]{1,31}$/.test(r.key) && !rk.has(r.key), `rubric[${n}].key`, 'format or duplicate');
    rk.add(r.key);
    req(between(r.name, 1, 80), `rubric[${n}].name`, '1..80');
    req(between(r.description, 10, 400), `rubric[${n}].description`, '10..400');
  });
  req(rk.has('groundedness') && rk.has('length'), 'rubric', 'needs groundedness and length');
  req(d.framework && between(d.framework.name, 2, 120) && between(d.framework.reference, 2, 300), 'framework', 'name 2..120, reference 2..300');
  req(between(d.disclaimer, 10, 200), 'disclaimer', '10..200');
  req(typeof d.compliance === 'boolean', 'compliance', 'boolean');
  if (d.category === 'LEGAL_COMPLIANCE') req(d.compliance === true, 'compliance', 'true for LEGAL_COMPLIANCE');
  const forbidden = d.forbiddenPhrases ?? [];
  req(Array.isArray(forbidden) && forbidden.length <= 20 && forbidden.every((p) => between(p, 1, 80)), 'forbiddenPhrases', '<=20 of 1..80');
  if (d.compliance === true) {
    req(typeof d.disclaimer === 'string' && d.disclaimer.toLocaleLowerCase('tr').includes('inceleme'), 'disclaimer', 'a compliance skill names its review');
    req(forbidden.length >= 1, 'forbiddenPhrases', 'a compliance skill forbids at least one verdict');
  }
  if (d.attribution != null) {
    const a = d.attribution;
    req(between(a.source, 2, 200) && LICENSES.includes(a.license) && /^https:\/\//.test(a.url ?? '') && between(a.note, 1, 400),
      'attribution', 'source, license (Apache-2.0|MIT|CC-BY-4.0), https url, note');
    req(!/anthropics\/skills/.test(`${a.source} ${a.url}`), 'attribution', 'anthropics/skills is source-available, not adaptable');
  }
  return e;
}

test('the library ships the ten P1 skills', () => {
  assert.deepEqual(skills.map((s) => s.def.id).sort(), ['gereksinim-kalitesi', 'ihale-yanit', 'kaynakli-rapor',
    'musteri-sikayet-analizi', 'premortem', 'proje-saglik', 'sop-taslagi', 'sozlesme-on-inceleme',
    'toplanti-karar-aksiyon', 'yazisma-ozeti']);
});

for (const { file, def } of skills) {
  test(`${file}: valid definition`, () => {
    assert.deepEqual(validate(def, file.replace(/\.json$/, '')), []);
  });
}

test('slash commands and names are unique', () => {
  const slashes = skills.map((s) => s.def.slash);
  assert.equal(new Set(slashes).size, slashes.length);
  const names = skills.map((s) => s.def.name);
  assert.equal(new Set(names).size, names.length);
});

test('validator mirror: rejects what the format forbids', () => {
  const base = structuredClone(skills.find((s) => s.def.id === 'sozlesme-on-inceleme').def);
  assert.deepEqual(validate(base, base.id), []);
  const broken = (mutate) => { const d = structuredClone(base); mutate(d); return validate(d, d.id); };
  assert.ok(broken((d) => { d.disclaimer = 'Taslak — kontrol için hazırlanmıştır.'; }).some((x) => x.startsWith('disclaimer')));
  assert.ok(broken((d) => { d.compliance = false; }).some((x) => x.startsWith('compliance')));
  assert.ok(broken((d) => { d.forbiddenPhrases = []; }).some((x) => x.startsWith('forbiddenPhrases')));
  assert.ok(broken((d) => { d.steps = [...d.steps, ...d.steps, ...d.steps]; }).some((x) => x.startsWith('steps')));
  assert.ok(broken((d) => { d.steps[0].maxChars = null; }).some((x) => x.startsWith('steps[0].maxChars')));
  assert.ok(broken((d) => { d.output.sections[0].columns[0].key = 'evidence'; }).length > 0);
  assert.ok(broken((d) => { d.maxOutputChars = 100; }).some((x) => x.startsWith('maxOutputChars')));
  assert.ok(broken((d) => { d.rubric = d.rubric.filter((r) => r.key !== 'groundedness'); }).some((x) => x.startsWith('rubric')));
  assert.ok(broken((d) => { d.steps[0].instruction += ' {{nope}}'; }).some((x) => x.includes('{{nope}}')));
  assert.ok(broken((d) => { d.inputs.forEach((i) => { i.required = false; }); }).some((x) => x.startsWith('inputs')));
  assert.ok(broken((d) => { d.grounding.scopeCollections = ['00000000-0000-0000-0000-000000000001']; }).length > 0);
  assert.ok(broken((d) => { d.attribution = { source: 'anthropics/skills', license: 'Apache-2.0', url: 'https://github.com/anthropics/skills', note: 'x' }; }).length > 0);
});

const goldenFiles = fs.readdirSync(goldenDir).filter((f) => f.endsWith('.json')).sort();

test('every skill has one golden set and every golden set names a skill', () => {
  assert.deepEqual(goldenFiles.map((f) => f.replace(/\.json$/, '')), skillFiles.map((f) => f.replace(/\.json$/, '')));
});

for (const file of goldenFiles) {
  test(`golden ${file}: 2-3 cases that fit the skill`, () => {
    const golden = read(path.join(goldenDir, file));
    const def = skills.find((s) => s.def.id === golden.skillId)?.def;
    assert.ok(def, `skill ${golden.skillId}`);
    assert.equal(golden.skillId, file.replace(/\.json$/, ''));
    assert.ok(golden.cases.length >= 2 && golden.cases.length <= 3, 'cases');
    const sectionKeys = new Set(def.output.sections.map((s) => s.key));
    const rowSections = new Set(def.output.sections.filter((s) => s.kind === 'ROWS').map((s) => s.key));
    const names = new Set();
    for (const c of golden.cases) {
      assert.ok(c.name && !names.has(c.name), `case name ${c.name}`);
      names.add(c.name);
      for (const doc of c.documents) assert.ok(fs.existsSync(path.join(root, 'samples', doc)), `${c.name}: samples/${doc}`);
      for (const [key, value] of Object.entries(c.inputs)) {
        const input = def.inputs.find((i) => i.key === key);
        assert.ok(input, `${c.name}: unknown input ${key}`);
        if (['TEXT', 'LONG_TEXT'].includes(input.type)) {
          assert.equal(typeof value, 'string');
          assert.ok([...value].length <= input.maxLength, `${c.name}.${key} length`);
        } else if (input.type === 'SELECT') {
          assert.ok(input.options.some((o) => o.value === value), `${c.name}.${key} option ${value}`);
        } else {
          assert.ok(Array.isArray(value) && value.length === 0, `${c.name}.${key}: ids are installation-specific`);
        }
      }
      for (const input of def.inputs.filter((i) => i.required)) {
        assert.ok(typeof c.inputs[input.key] === 'string' && c.inputs[input.key].trim(), `${c.name}: required ${input.key}`);
      }
      for (const key of c.expect.sections) assert.ok(sectionKeys.has(key), `${c.name}: section ${key}`);
      for (const [key, n] of Object.entries(c.expect.minRows ?? {})) {
        assert.ok(rowSections.has(key), `${c.name}: minRows on ${key}`);
        const max = def.output.sections.find((s) => s.key === key).maxRows;
        assert.ok(Number.isInteger(n) && n >= 0 && n <= max, `${c.name}: minRows ${key}`);
      }
      for (const doc of c.expect.citesDocuments) assert.ok(c.documents.includes(doc), `${c.name}: cites ${doc} not uploaded`);
      for (const alts of c.expect.mentionsAny) assert.ok(Array.isArray(alts) && alts.length >= 1);
      assert.ok(Array.isArray(c.expect.forbids));
      assert.equal(typeof c.expect.notAssessableAllowed, 'boolean');
    }
  });
}

// ---------------------------------------------------------------- skill-eval.mjs pure functions

const definition = { name: 'Test', maxOutputChars: 1000, forbiddenPhrases: ['tamamen uyumlu'], framework: { name: 'F' },
  rubric: [{ key: 'groundedness', name: 'Dayanaklılık', description: 'Kaynağa dayanıyor.' }] };
const goodRun = () => ({
  id: 'r1', status: 'DRAFT', modelId: 'llama3.1:8b',
  checks: { disclaimerPresent: true, withinLength: true },
  output: { sections: [
    { key: 'summary', text: 'Günlük yemek limiti 900 TL.', evidence: [1], notAssessable: false, rows: null },
    { key: 'findings', text: null, evidence: null, notAssessable: null, rows: [
      { cells: { finding: 'Limit 900 TL' }, evidence: [1], notAssessable: false },
      { cells: { finding: 'Araç politikası' }, evidence: [], notAssessable: true },
    ] },
  ] },
  citations: [{ number: 1, kind: 'PASSAGE', fileName: 'masraf-politikasi.pdf', excerpt: '900 TL' }],
});
const expect = { sections: ['summary', 'findings'], minRows: { findings: 2 }, citesDocuments: ['masraf-politikasi.pdf'],
  mentionsAny: [['900 TL'], ['ARAÇ', 'kasko']], forbids: ['kesinlikle'], notAssessableAllowed: true };

test('checkExpectations: a run that meets every property passes', async () => {
  const { checkExpectations } = await evalModule();
  assert.deepEqual(checkExpectations(goodRun(), expect, definition), { pass: true, failures: [] });
});

test('checkExpectations: each broken property is reported', async () => {
  const { checkExpectations } = await evalModule();
  const failed = (mutate, e = expect) => { const run = goodRun(); mutate(run); return checkExpectations(run, e, definition).failures; };
  assert.match(failed((r) => { r.status = 'FAILED'; r.failureCode = 'OUTPUT_INVALID'; }).join(), /status FAILED \(OUTPUT_INVALID\)/);
  assert.match(failed((r) => { r.checks.disclaimerPresent = false; }).join(), /disclaimer/);
  assert.match(failed((r) => { r.checks.withinLength = false; }).join(), /length/);
  assert.match(failed((r) => { r.output.sections.pop(); }).join(), /section findings missing/);
  assert.match(failed((r) => { r.output.sections[1].rows.pop(); }).join(), /1 rows < 2/);
  assert.match(failed((r) => { r.output.sections[0].evidence = []; r.output.sections[1].rows[0].evidence = []; }).join(), /not cited/);
  assert.match(failed((r) => { r.output.sections[1].rows[1].cells.finding = 'x'; }).join(), /mentions none of/);
  assert.match(failed((r) => { r.output.sections[0].text += ' Tamamen uyumlu.'; }).join(), /forbidden phrase: tamamen uyumlu/);
  assert.match(failed(() => {}, { ...expect, notAssessableAllowed: false }).join(), /değerlendirilemez/);
  assert.deepEqual(checkExpectations({ status: 'FAILED' }, expect, null).pass, false);
});

test('parseArgs: defaults, flags and refusals', async () => {
  const { parseArgs } = await evalModule();
  assert.deepEqual({ ...parseArgs([]), timeoutMs: 0 }, { base: 'http://localhost:8080', skill: null, judge: false,
    upload: true, timeoutMs: 0, pollMs: 2000, out: null });
  const o = parseArgs(['--base', 'https://verso.test/', '--skill', 'premortem', '--judge', '--no-upload', '--timeout-min', '2']);
  assert.equal(o.base, 'https://verso.test');
  assert.equal(o.skill, 'premortem');
  assert.equal(o.judge, true);
  assert.equal(o.upload, false);
  assert.equal(o.timeoutMs, 120_000);
  assert.throws(() => parseArgs(['--bogus']), /unknown option/);
  assert.throws(() => parseArgs(['--base']), /needs a value/);
  assert.throws(() => parseArgs(['--base', 'ftp://x']), /http/);
});

test('summarize: pass rates per skill and per judged dimension', async () => {
  const { summarize } = await evalModule();
  const s = summarize([
    { skillId: 'a', deterministic: { pass: true }, judge: [{ dimension: 'groundedness', verdict: 'PASS' }] },
    { skillId: 'a', deterministic: { pass: false }, judge: [{ dimension: 'groundedness', verdict: 'FAIL' }, { dimension: 'length', verdict: 'UNKNOWN' }] },
    { skillId: 'b', deterministic: { pass: true }, judge: [] },
  ]);
  assert.equal(s.cases, 3);
  assert.equal(s.passed, 2);
  assert.equal(s.passRate, 0.667);
  assert.deepEqual(s.perSkill.a, { cases: 2, passed: 1, passRate: 0.5 });
  assert.deepEqual(s.perDimension.groundedness, { pass: 1, fail: 1, unknown: 0, passRate: 0.5 });
  assert.deepEqual(s.perDimension.length, { pass: 0, fail: 0, unknown: 1, passRate: 0 });
});

test('judge: one binary, length-controlled question per dimension; verdict parsing; family check', async () => {
  const { judgePrompt, parseVerdict, modelFamily } = await evalModule();
  const messages = judgePrompt(definition, definition.rubric[0], goodRun());
  assert.equal(messages.length, 2);
  assert.match(messages[0].content, /PASS, FAIL or UNKNOWN/);
  assert.match(messages[0].content, /Do not reward length/);
  assert.match(messages[1].content, /Dayanaklılık/);
  assert.match(messages[1].content, /\[1\] masraf-politikasi\.pdf/);
  assert.deepEqual(parseVerdict('PASS\nKaynaklara dayanıyor.'), { verdict: 'PASS', critique: 'Kaynaklara dayanıyor.' });
  assert.equal(parseVerdict('fail.').verdict, 'FAIL');
  assert.equal(parseVerdict('maybe').verdict, 'UNKNOWN');
  assert.equal(modelFamily('claude-sonnet-4-5'), 'claude');
  assert.equal(modelFamily('ollama/llama3.1:8b'), 'llama');
});

test('skill-eval.mjs: never prints the token or run text to the console', () => {
  const source = fs.readFileSync(path.join(__dirname, 'skill-eval.mjs'), 'utf8');
  const logs = [...source.matchAll(/console\.(log|warn|error)\(([^;]*)\);/g)].map((m) => m[2]);
  assert.ok(logs.length > 0);
  for (const line of logs) assert.doesNotMatch(line, /token|inputs|output|excerpt|\.text/i, line);
  assert.doesNotMatch(source, /createObjectURL|download=/, 'no browser download');
});
