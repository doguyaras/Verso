// deploy/ollama/pull.sh (ADR-0011, llm-rules 8.1/8.2): the model is accepted only with the pinned layer digest, a model
// already in the volume is not downloaded again (offline restart), and a failed download stops the stack. A fake
// "ollama" on PATH plays the server and the registry. Run: node --test scripts/ollama-pull.test.js
'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const SCRIPT = path.join(__dirname, '..', 'deploy', 'ollama', 'pull.sh');
const PINNED = 'sha256:daec91ffb5dd0c27411bd71f29932917c49cf529a641d0168496c3a501e3062c';
const MODEL = 'bge-m3:567m';

// The fake CLI: "serve" idles, "list" succeeds, "pull" writes a manifest with $FAKE_DIGEST (or fails when unset)
// and records that a download happened.
const FAKE = `#!/bin/sh
case "$1" in
  serve) exec sleep 30 ;;
  list) exit 0 ;;
  pull)
    echo "pull $2" >> "$FAKE_LOG"
    [ -n "$FAKE_DIGEST" ] || { echo "pull failed" >&2; exit 1; }
    dir="$OLLAMA_MODELS/manifests/registry.ollama.ai/library/bge-m3"
    mkdir -p "$dir" && printf '{"layers":[{"digest":"%s"}]}' "$FAKE_DIGEST" > "$dir/567m" ;;
esac
`;

function setup() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'verso-pull-'));
  const bin = path.join(root, 'bin');
  fs.mkdirSync(bin);
  fs.writeFileSync(path.join(bin, 'ollama'), FAKE, { mode: 0o755 });
  return { root, bin, models: path.join(root, 'models'), log: path.join(root, 'calls.log') };
}

function run(env, fakeDigest) {
  const result = spawnSync('sh', [SCRIPT], {
    encoding: 'utf8',
    env: { ...process.env, PATH: `${env.bin}${path.delimiter}${process.env.PATH}`, OLLAMA_MODELS: env.models,
      OLLAMA_MODEL: MODEL, OLLAMA_MODEL_DIGEST: PINNED, FAKE_DIGEST: fakeDigest ?? '', FAKE_LOG: env.log },
  });
  const pulls = fs.existsSync(env.log) ? fs.readFileSync(env.log, 'utf8').trim().split('\n').filter(Boolean) : [];
  return { ...result, pulls };
}

test('ollama-pull: the pinned digest is accepted', () => {
  const env = setup();
  const result = run(env, PINNED);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /bge-m3:567m ready/);
  assert.deepEqual(result.pulls, [`pull ${MODEL}`]);
});

test('ollama-pull: another digest under the same tag stops the stack', () => {
  const env = setup();
  const result = run(env, 'sha256:0000000000000000000000000000000000000000000000000000000000000000');
  assert.equal(result.status, 1);
  assert.match(result.stderr, /does not match the pinned digest/);
});

test('ollama-pull: a model already in the volume is not downloaded again (offline restart)', () => {
  const env = setup();
  assert.equal(run(env, PINNED).status, 0);
  fs.rmSync(env.log);
  const again = run(env, ''); // a download now would fail: no network
  assert.equal(again.status, 0, again.stderr);
  assert.match(again.stdout, /already present/);
  assert.deepEqual(again.pulls, []);
});

test('ollama-pull: a failed download is reported in one line and exits non-zero', () => {
  const env = setup();
  const result = run(env, '');
  assert.equal(result.status, 1);
  assert.match(result.stderr, /download of bge-m3:567m failed/);
});
