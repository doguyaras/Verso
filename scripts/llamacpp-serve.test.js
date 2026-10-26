// deploy/llamacpp/serve.sh (ADR-0051): the shared-tier llama-server starts only on the GGUF file whose SHA-256 is the
// pinned chat model digest (the same pin ollama-pull verified, ADR-0033), with its key file, N parallel slots and a
// context of N x per-slot tokens, without the web UI. A fake server binary records its arguments.
// Run: node --test scripts/llamacpp-serve.test.js
'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const SCRIPT = path.join(__dirname, '..', 'deploy', 'llamacpp', 'serve.sh');
const MODEL = 'fake gguf bytes';
const DIGEST = crypto.createHash('sha256').update(MODEL).digest('hex');

function setup({ model = MODEL, key = 'k'.repeat(40) } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'verso-llama-'));
  const blobs = path.join(root, 'blobs');
  fs.mkdirSync(blobs);
  fs.writeFileSync(path.join(blobs, `sha256-${DIGEST}`), model);
  const keyFile = path.join(root, 'key');
  if (key !== null) fs.writeFileSync(keyFile, key);
  const server = path.join(root, 'llama-server');
  const args = path.join(root, 'args.log');
  fs.writeFileSync(server, `#!/bin/sh\nfor a in "$@"; do echo "$a"; done > "${args.replace(/\\/g, '/')}"\n`, { mode: 0o755 });
  return { root, blobs, keyFile, server, args };
}

function run(env, extra = {}) {
  // POSIX paths for the shell (Git Bash on Windows understands C:/...).
  const posix = (p) => p.replace(/\\/g, '/');
  return spawnSync('sh', [SCRIPT], {
    encoding: 'utf8',
    env: { ...process.env, LLAMA_MODEL_DIGEST: `sha256:${DIGEST}`, LLAMA_ALIAS: 'gemma4:e2b', LLAMA_PARALLEL: '4',
      LLAMA_CTX_PER_SLOT: '4096', LLAMA_MODELS_DIR: posix(env.blobs), LLAMA_API_KEY_FILE: posix(env.keyFile),
      LLAMA_SERVER: posix(env.server), ...extra },
  });
}

test('llamacpp-serve: a verified model starts the server with slots, shared context, key file and no web UI', () => {
  const env = setup();
  const result = run(env);
  assert.equal(result.status, 0, result.stderr);
  const args = fs.readFileSync(env.args, 'utf8').trim().split('\n');
  const value = (flag) => args[args.indexOf(flag) + 1];
  assert.match(value('--model'), new RegExp(`sha256-${DIGEST}$`));
  assert.equal(value('--alias'), 'gemma4:e2b');
  assert.equal(value('--parallel'), '4');
  assert.equal(value('--ctx-size'), '16384');
  assert.equal(value('--host'), '0.0.0.0');
  assert.match(value('--api-key-file'), /key$/);
  assert.ok(args.includes('--no-webui'));
  assert.ok(!args.some((a) => a.startsWith('--rpc')), 'no RPC backend');
  assert.ok(!args.includes('--api-key'), 'the key never travels as an argument (ps would show it)');
  assert.match(result.stderr, /model verified/);
});

test('llamacpp-serve: a model file that does not match the pinned digest stops the start', () => {
  const env = setup({ model: 'tampered' });
  const result = run(env);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /does not match the pinned digest/);
  assert.ok(!fs.existsSync(env.args), 'the server never ran');
});

test('llamacpp-serve: a missing model, a malformed digest, bad sizes or no key file stop the start', () => {
  const env = setup();
  assert.match(run(env, { LLAMA_MODEL_DIGEST: `sha256:${'0'.repeat(64)}` }).stderr, /model file not found/);
  assert.match(run(env, { LLAMA_MODEL_DIGEST: 'sha256:abc' }).stderr, /must be sha256:<64 hex>/);
  assert.match(run(env, { LLAMA_MODEL_DIGEST: `sha256:${DIGEST.toUpperCase()}` }).stderr, /must be sha256:<64 hex>/);
  assert.match(run(env, { LLAMA_PARALLEL: '0' }).stderr, /LLAMA_PARALLEL >= 1/);
  assert.match(run(env, { LLAMA_CTX_PER_SLOT: '4k' }).stderr, /positive integers/);
  const noKey = setup({ key: null });
  assert.match(run(noKey).stderr, /API key file is missing/);
  assert.ok(!fs.existsSync(env.args) && !fs.existsSync(noKey.args), 'the server never ran');
});
