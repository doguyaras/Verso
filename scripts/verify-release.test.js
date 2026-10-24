// scripts/verify-release.sh (ADR-0030, ADR-0040): the release image is verified by digest, keyless, before an install or
// upgrade; a tag, a missing cosign or a failing check stops it. A fake "cosign" on PATH records the calls.
// Run: node --test scripts/verify-release.test.js
'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const SCRIPT = path.join(__dirname, '..', 'scripts', 'verify-release.sh');
const DIGEST = 'a'.repeat(64);
const IMAGE = `ghcr.io/doguyaras/verso@sha256:${DIGEST}`;

// Fails the subcommand named in FAKE_FAIL; logs every call.
const FAKE = `#!/bin/sh
echo "$*" >> "$FAKE_LOG"
[ "$1" = "$FAKE_FAIL" ] && { echo "no matching signatures" >&2; exit 1; }
exit 0
`;

function setup() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'verso-verify-'));
  const bin = path.join(root, 'bin');
  fs.mkdirSync(bin);
  fs.writeFileSync(path.join(bin, 'cosign'), FAKE, { mode: 0o755 });
  return { root, bin, log: path.join(root, 'calls.log') };
}

function run(args, env, withCosign = true) {
  const s = setup();
  const result = spawnSync('bash', [SCRIPT, ...args], {
    encoding: 'utf8',
    env: {
      PATH: withCosign ? `${s.bin}${path.delimiter}${process.env.PATH}` : process.env.PATH,
      HOME: s.root,
      FAKE_LOG: s.log,
      ...env,
    },
  });
  const calls = fs.existsSync(s.log) ? fs.readFileSync(s.log, 'utf8').trim().split('\n').filter(Boolean) : [];
  fs.rmSync(s.root, { recursive: true, force: true });
  return { ...result, calls };
}

test('digest reference: signature and cyclonedx attestation are verified with the release.yml identity', () => {
  const r = run([IMAGE], {});
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.calls.length, 2);
  assert.match(r.calls[0], /^verify --certificate-identity-regexp /);
  assert.match(r.calls[1], /^verify-attestation --type cyclonedx /);
  for (const call of r.calls) {
    assert.ok(call.includes('^https://github\\.com/doguyaras/Verso/\\.github/workflows/release\\.yml@refs/tags/v'), call);
    assert.ok(call.includes('--certificate-oidc-issuer https://token.actions.githubusercontent.com'), call);
    assert.ok(call.endsWith(IMAGE), call);
    assert.ok(!call.includes('--offline'), call);
  }
});

test('the identity follows VERSO_RELEASE_REPO and VERSO_IMAGE works as well as the argument', () => {
  const r = run([], { VERSO_IMAGE: IMAGE, VERSO_RELEASE_REPO: 'acme/verso-fork' });
  assert.equal(r.status, 0, r.stderr);
  assert.ok(r.calls[0].includes('github\\.com/acme/verso-fork/'), r.calls[0]);
});

test('a tag is refused before cosign is asked anything', () => {
  for (const ref of ['ghcr.io/doguyaras/verso:0.1.0', 'ghcr.io/doguyaras/verso:latest', 'ghcr.io/doguyaras/verso',
    `ghcr.io/doguyaras/verso:0.1.0@sha256:${DIGEST}x`, 'ghcr.io/doguyaras/verso@sha256:abc', 'verso:local']) {
    const r = run([ref], {});
    assert.notEqual(r.status, 0, ref);
    assert.match(r.stderr, /full digest reference/, ref);
    assert.deepEqual(r.calls, [], ref);
  }
});

test('no image at all is refused', () => {
  const r = run([], {});
  assert.notEqual(r.status, 0);
  assert.match(r.stderr, /no image/);
});

test('a failing signature check stops: no attestation check, non-zero exit', () => {
  const r = run([IMAGE], { FAKE_FAIL: 'verify' });
  assert.notEqual(r.status, 0);
  assert.match(r.stderr, /signature does not verify/);
  assert.equal(r.calls.length, 1);
});

test('a failing attestation check stops with non-zero exit', () => {
  const r = run([IMAGE], { FAKE_FAIL: 'verify-attestation' });
  assert.notEqual(r.status, 0);
  assert.match(r.stderr, /attestation does not verify/);
  assert.doesNotMatch(r.stdout, /PASS cyclonedx/);
});

test('missing cosign is an error, not a pass', () => {
  const r = run([IMAGE], { COSIGN: 'cosign-that-does-not-exist' });
  assert.notEqual(r.status, 0);
  assert.match(r.stderr, /cosign not found/);
});

test('offline mode adds --offline and the trusted root', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'verso-root-'));
  const trusted = path.join(root, 'trusted_root.json');
  fs.writeFileSync(trusted, '{}');
  const r = run([IMAGE], { VERSO_VERIFY_OFFLINE: '1', COSIGN_TRUSTED_ROOT: trusted });
  fs.rmSync(root, { recursive: true, force: true });
  assert.equal(r.status, 0, r.stderr);
  for (const call of r.calls) {
    assert.ok(call.includes('--offline=true'), call);
    assert.ok(call.includes('--trusted-root'), call);
  }
});

test('an unreadable trusted root is an error', () => {
  const r = run([IMAGE], { COSIGN_TRUSTED_ROOT: '/nonexistent/trusted_root.json' });
  assert.notEqual(r.status, 0);
  assert.match(r.stderr, /COSIGN_TRUSTED_ROOT/);
});
