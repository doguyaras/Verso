// scripts/prove-local-mode.sh --report (ADR-0050): the JSON evidence file. A fake "docker" and "curl" on PATH stand in
// for the running stack (no container is started): the happy path passes, a reachable public address fails, a stack
// that is not running is an "error", and a bad argument is refused. Run: node --test scripts/prove-local-mode.test.js
'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const SCRIPT = path.join(__dirname, 'prove-local-mode.sh');

// compose ps -q <name> prints an id unless FAKE_DOWN; networks are internal; a probe to 1.1.1.1 answers only when
// FAKE_LEAK is set (the script must then FAIL).
const DOCKER = `#!/usr/bin/env bash
case "$1" in
  compose) [ -n "$FAKE_DOWN" ] || echo "id-$4" ;;
  inspect) case "$*" in *Networks*) echo "stack_internal " ;; *) echo "img:test" ;; esac ;;
  network) echo true ;;
  run) cmd="\${@: -1}"
       case "$cmd" in *1.1.1.1*) [ -n "$FAKE_LEAK" ] && exit 0 || exit 1 ;; *example.com*) exit 1 ;; *) exit 0 ;; esac ;;
esac
`;
const CURL = `#!/usr/bin/env bash
case "$*" in
  *"-X POST"*) printf 'HTTP/1.1 413 Payload Too Large\\r\\nX-Rag-Mode: local\\r\\n\\r\\n{"code":90014}' ;;
  *X-Probe*) printf 'HTTP/1.1 400 Bad Request\\r\\nX-Rag-Mode: local\\r\\n\\r\\n{"code":90004}' ;;
  *) printf 'HTTP/1.1 401 Unauthorized\\r\\nX-Rag-Mode: local\\r\\n\\r\\n{}' ;;
esac
`;

function run(args, env = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'verso-prove-'));
  const bin = path.join(root, 'bin');
  fs.mkdirSync(bin);
  fs.writeFileSync(path.join(bin, 'docker'), DOCKER, { mode: 0o755 });
  fs.writeFileSync(path.join(bin, 'curl'), CURL, { mode: 0o755 });
  const report = path.join(root, 'kanit.json');
  const result = spawnSync('bash', [SCRIPT, ...args.map((a) => (a === '<report>' ? report : a))], {
    encoding: 'utf8',
    env: { ...process.env, PATH: `${bin}${path.delimiter}${process.env.PATH}`, ...env },
  });
  const json = fs.existsSync(report) ? JSON.parse(fs.readFileSync(report, 'utf8')) : null;
  fs.rmSync(root, { recursive: true, force: true });
  return { ...result, json };
}

test('the script parses', () => {
  assert.equal(spawnSync('bash', ['-n', SCRIPT]).status, 0);
});

test('--report: a passing run writes result pass, a timestamp and every check', () => {
  const r = run(['--report', '<report>']);
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /prove-local-mode: OK/);
  assert.equal(r.json.tool, 'prove-local-mode');
  assert.equal(r.json.result, 'pass');
  assert.match(r.json.timestamp, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
  assert.equal(r.json.counts.fail, 0);
  assert.equal(r.json.counts.pass, r.json.checks.length);
  assert.ok(r.json.checks.length >= 12);
  assert.ok(r.json.checks.every((c) => c.status === 'pass' && c.name.length > 0));
  assert.ok(r.json.checks.some((c) => /1\.1\.1\.1:443 unreachable/.test(c.name)));
});

test('--report: a reachable public address is a FAIL in the file and a non-zero exit', () => {
  const r = run(['--report', '<report>'], { FAKE_LEAK: '1' });
  assert.equal(r.status, 1);
  assert.equal(r.json.result, 'fail');
  assert.ok(r.json.counts.fail >= 2);
  assert.ok(r.json.checks.some((c) => c.status === 'fail' && /1\.1\.1\.1:443 answered/.test(c.name)));
});

test('--report: a stack that is not running is "error", never "pass"', () => {
  const r = run(['--report', '<report>'], { FAKE_DOWN: '1' });
  assert.equal(r.status, 3);
  assert.equal(r.json.result, 'error');
  assert.deepEqual(r.json.checks, []);
});

test('without --report no file is written and the old output is unchanged', () => {
  const r = run([]);
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.json, null);
  assert.match(r.stdout, /prove-local-mode: PASS .*internal/);
});

test('--report needs a file name; other arguments are refused', () => {
  assert.equal(run(['--report']).status, 2);
  assert.equal(run(['--nope']).status, 2);
});
