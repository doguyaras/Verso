// deploy/keycloak/start.sh fails closed (ADR-0010; phase 3 test review T8): a missing, empty or unreadable secret
// stops the container before Keycloak starts, and no secret value is ever printed. Run: node --test scripts/keycloak-start.test.js
'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const SCRIPT = path.join(__dirname, '..', 'deploy', 'keycloak', 'start.sh');
const NAMES = ['SECRET_DB_KEYCLOAK_PASSWORD', 'SECRET_KEYCLOAK_ADMIN_PASSWORD', 'SECRET_KEYCLOAK_CI_CLIENT_SECRET',
  'SECRET_KEYCLOAK_DEMO_USER_PASSWORD', 'SECRET_KEYCLOAK_PANEL_ADMIN_PASSWORD'];

function secretsDir(overrides = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'verso-kc-'));
  const values = {};
  for (const name of NAMES) {
    if (overrides[name] === null) continue; // missing
    values[name] = overrides[name] ?? `value-of-${name}-${Math.random().toString(36).slice(2)}`;
    fs.writeFileSync(path.join(dir, name), values[name]);
  }
  return { dir, values };
}

function run(dir) {
  // The script ends with exec /opt/keycloak/bin/kc.sh, which does not exist here: reaching it means exit 127.
  return spawnSync('bash', [SCRIPT], { env: { ...process.env, VERSO_SECRETS_DIR: dir }, encoding: 'utf8' });
}

function assertNoSecretPrinted(result, values) {
  for (const value of Object.values(values).filter(Boolean)) {
    assert.ok(!result.stdout.includes(value) && !result.stderr.includes(value), 'a secret value was printed');
  }
}

for (const name of NAMES) {
  test(`start.sh: missing ${name} stops before Keycloak`, () => {
    const { dir, values } = secretsDir({ [name]: null });
    const result = run(dir);
    assert.equal(result.status, 1, result.stderr);
    assert.match(result.stderr, new RegExp(`secret ${name} missing, empty or unreadable`));
    assert.doesNotMatch(result.stderr, /kc\.sh/);
    assertNoSecretPrinted(result, values);
  });
}

test('start.sh: an empty secret file stops before Keycloak', () => {
  const { dir, values } = secretsDir({ SECRET_KEYCLOAK_CI_CLIENT_SECRET: '' });
  const result = run(dir);
  assert.equal(result.status, 1, result.stderr);
  assert.match(result.stderr, /SECRET_KEYCLOAK_CI_CLIENT_SECRET missing, empty or unreadable/);
  assertNoSecretPrinted(result, values);
});

test('start.sh: with every secret present it hands over to kc.sh (positive control)', () => {
  const { dir, values } = secretsDir();
  const result = run(dir);
  assert.equal(result.status, 127, `expected the exec of the missing kc.sh, got ${result.status}: ${result.stderr}`);
  assert.match(result.stderr, /kc\.sh/);
  assertNoSecretPrinted(result, values);
});
