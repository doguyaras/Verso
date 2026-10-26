// deploy/ollama/serve.sh (docs/capacity.md "Soğuk açılış"): after the server starts, both models are loaded once with
// keep_alive -1, so the first question does not pay the model load; a failed warm-up is retried and never stops the
// server; "docker stop" reaches the server. A fake "ollama" on PATH plays the server process, a local HTTP server
// plays its API. Run: node --test scripts/ollama-serve.test.js
'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');

const SCRIPT = path.join(__dirname, '..', 'deploy', 'ollama', 'serve.sh');

// "serve" idles until it is killed and records that; "list" answers at once (the server is "up"). The idle loop ends
// by itself after 30 s: on Windows a killed bash leaves its children running.
const FAKE = `#!/bin/sh
case "$1" in
  serve) trap 'echo stopped >> "$FAKE_LOG"; exit 0' TERM; for _ in $(seq 1 30); do sleep 1; done ;;
  list) exit 0 ;;
esac
`;

function setup() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'verso-serve-'));
  const bin = path.join(root, 'bin');
  fs.mkdirSync(bin);
  fs.writeFileSync(path.join(bin, 'ollama'), FAKE, { mode: 0o755 });
  return { bin, log: path.join(root, 'calls.log') };
}

/** A fake Ollama API; status(n) gives the HTTP status of the n-th request (0-based). */
function api(status = () => 200) {
  const requests = [];
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      const code = status(requests.length);
      requests.push({ path: req.url, body: JSON.parse(body) });
      res.writeHead(code, { 'Content-Type': 'application/json' }).end('{}');
    });
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve({ server, requests, port: server.address().port })));
}

function start(env, port, extra = {}) {
  const child = spawn('bash', [SCRIPT], {
    env: { ...process.env, PATH: `${env.bin}${path.delimiter}${process.env.PATH}`, FAKE_LOG: env.log,
      OLLAMA_HOST: `0.0.0.0:${port}`, OLLAMA_WARM_EMBEDDING: 'bge-m3:567m', OLLAMA_WARM_CHAT: 'gemma4:e2b',
      OLLAMA_WARM_RETRY_SECONDS: '0', ...extra },
  });
  let stderr = '';
  child.stderr.on('data', (chunk) => { stderr += chunk; });
  const exited = new Promise((resolve) => child.on('exit', (code) => resolve(code)));
  // Let the test process end even if an orphaned fake server still holds the pipes (Windows).
  const release = () => {
    child.stdout.destroy();
    child.stderr.destroy();
    child.unref();
  };
  return { child, exited, stderr: () => stderr, release };
}

async function until(condition, ms = 10000) {
  const end = Date.now() + ms;
  while (!condition()) {
    if (Date.now() > end) throw new Error('timed out');
    await new Promise((r) => setTimeout(r, 50));
  }
}

test('ollama-serve: both models are loaded once, embedding first, and kept loaded (keep_alive -1)', async () => {
  const env = setup();
  const { server, requests, port } = await api();
  const run = start(env, port);
  try {
    await until(() => requests.length === 2);
    assert.deepEqual(requests.map((r) => r.path), ['/api/embed', '/api/generate']);
    assert.equal(requests[0].body.model, 'bge-m3:567m');
    assert.equal(requests[1].body.model, 'gemma4:e2b');
    assert.equal(requests[1].body.prompt, '', 'an empty prompt loads the model without generating');
    for (const r of requests) assert.equal(r.body.keep_alive, -1);
    await until(() => /gemma4:e2b loaded in \d+ ms/.test(run.stderr()));
    assert.equal(run.child.exitCode, null, 'the server keeps running after the warm-up');
  } finally {
    run.child.kill('SIGTERM');
    await run.exited;
    run.release();
    server.close();
  }
});

test('ollama-serve: a failed warm-up is retried and never stops the server', async () => {
  const env = setup();
  // The embedding model fails twice, then loads; the chat model loads at once.
  const { server, requests, port } = await api((n) => (n < 2 ? 500 : 200));
  const run = start(env, port);
  try {
    await until(() => requests.length === 4);
    assert.deepEqual(requests.map((r) => r.path), ['/api/embed', '/api/embed', '/api/embed', '/api/generate']);
    assert.match(run.stderr(), /bge-m3:567m not loaded \(HTTP 500, attempt 1\)/);
    await until(() => /gemma4:e2b loaded/.test(run.stderr()));
    assert.equal(run.child.exitCode, null);
  } finally {
    run.child.kill('SIGTERM');
    await run.exited;
    run.release();
    server.close();
  }
});

test('ollama-serve: a stop signal reaches the model server and the script exits 0', { skip: process.platform === 'win32' && 'POSIX signals' }, async () => {
  const env = setup();
  const { server, requests, port } = await api();
  const run = start(env, port);
  try {
    await until(() => requests.length === 2);
    run.child.kill('SIGTERM');
    assert.equal(await run.exited, 0);
    assert.match(fs.readFileSync(env.log, 'utf8'), /stopped/);
  } finally {
    server.close();
  }
});

test('ollama-serve: OLLAMA_WARM_CHAT=none warms the embedding model only (ADR-0051 shared tier)', async () => {
  const env = setup();
  const { server, requests, port } = await api();
  const run = start(env, port, { OLLAMA_WARM_CHAT: 'none' });
  try {
    await until(() => /bge-m3:567m loaded in \d+ ms/.test(run.stderr()));
    await new Promise((r) => setTimeout(r, 300));
    assert.deepEqual(requests.map((r) => r.path), ['/api/embed']);
    assert.equal(run.child.exitCode, null, 'the server keeps running');
  } finally {
    run.child.kill('SIGTERM');
    await run.exited;
    run.release();
    server.close();
  }
});

test('ollama-serve: the models to warm are required', async () => {
  const env = setup();
  const run = start(env, 1, { OLLAMA_WARM_CHAT: '' });
  assert.notEqual(await run.exited, 0);
  assert.match(run.stderr(), /OLLAMA_WARM_CHAT/);
});
