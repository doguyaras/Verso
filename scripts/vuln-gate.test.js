// Vulnerability gate and supply-chain pins (ADR-0030). Run: node --test scripts/vuln-gate.test.js
// Deliberately NOT part of the required ci.yml checks: the allowlist-expiry test depends on the calendar, so it would turn
// unrelated pull requests red the day an entry expires. The supply-chain-scan workflow runs it instead.
'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { parseAllowlist, evaluate, main } = require('./vuln-gate.js');

const ROOT = path.join(__dirname, '..');
const read = (file) => fs.readFileSync(path.join(ROOT, file), 'utf8');
const TODAY = '2026-10-24';

function match(id, severity, fixState, extra = {}) {
  return {
    vulnerability: { id, severity, fix: { state: fixState, versions: fixState === 'fixed' ? ['9.9.9'] : [] } },
    relatedVulnerabilities: extra.related ?? [],
    artifact: { name: extra.name ?? 'lib', version: '1.0.0' },
  };
}
const gate = (matches, entries = [], label = 'img') => evaluate({ label, grype: { matches }, entries, today: TODAY });
const entry = (over = {}) => ({ id: 'CVE-2025-63389', reason: 'no fix; internal network only', expires: '2027-01-31', ...over });

test('policy: critical fails with or without a fix', () => {
  assert.equal(gate([match('CVE-2026-1', 'Critical', 'fixed')]).violations.length, 1);
  assert.equal(gate([match('CVE-2026-2', 'Critical', 'not-fixed')]).violations.length, 1);
  assert.equal(gate([match('CVE-2026-3', 'Critical', 'wont-fix')]).violations.length, 1);
});

test('policy: high fails only when a fix exists', () => {
  assert.equal(gate([match('CVE-2026-4', 'High', 'fixed')]).violations.length, 1);
  assert.equal(gate([match('CVE-2026-5', 'High', 'not-fixed')]).violations.length, 0);
  assert.equal(gate([match('CVE-2026-6', 'High', 'unknown')]).violations.length, 0);
});

test('policy: medium, low and unknown never fail, severity is case-insensitive', () => {
  const result = gate([match('CVE-2026-7', 'Medium', 'fixed'), match('CVE-2026-8', 'Low', 'fixed'), match('CVE-2026-9', 'Unknown', 'fixed')]);
  assert.equal(result.violations.length, 0);
  assert.equal(result.fixable, 3);
  assert.equal(gate([match('CVE-2026-10', 'CRITICAL', 'fixed')]).violations.length, 1);
});

test('allowlist: a live entry waives the finding by its id or by a related id', () => {
  assert.equal(gate([match('CVE-2025-63389', 'Critical', 'not-fixed')], [entry()]).violations.length, 0);
  const viaAdvisory = match('GHSA-f6mr-38g8-39rg', 'Critical', 'not-fixed', { related: [{ id: 'CVE-2025-63389' }] });
  const result = gate([viaAdvisory], [entry()]);
  assert.equal(result.violations.length, 0);
  assert.equal(result.waived.length, 1);
});

test('allowlist: scope and package narrow the waiver', () => {
  const m = match('CVE-2025-63389', 'Critical', 'not-fixed', { name: 'github.com/ollama/ollama' });
  assert.equal(gate([m], [entry({ scope: 'ollama' })], 'ollama-ollama-0.35.1').violations.length, 0);
  assert.equal(gate([m], [entry({ scope: 'ollama' })], 'grafana-grafana-13').violations.length, 1);
  assert.equal(gate([m], [entry({ package: 'other' })], 'ollama').violations.length, 1);
});

test('allowlist: a wildcard id waives one package of one image and nothing else', () => {
  const wild = entry({ id: '*', scope: 'pgvector', package: 'stdlib' });
  const std = match('GO-2026-4341', 'High', 'fixed', { name: 'stdlib' });
  const other = match('CVE-2026-6653', 'Critical', 'fixed', { name: 'libxml2' });
  assert.equal(gate([std], [wild], 'pgvector-0.8').violations.length, 0);
  assert.equal(gate([other], [wild], 'pgvector-0.8').violations.length, 1, 'another package is not covered');
  assert.equal(gate([std], [wild], 'keycloak').violations.length, 1, 'another image is not covered');
  const glob = entry({ id: '*', scope: 'pgvector', package: 'golang.org/x/*' });
  const xnet = match('GO-2026-6612', 'Critical', 'fixed', { name: 'golang.org/x/net' });
  assert.equal(gate([xnet], [glob], 'pgvector-0.8').violations.length, 0, 'a trailing * is a prefix glob');
  assert.equal(gate([std], [glob], 'pgvector-0.8').violations.length, 1, 'the prefix does not reach other packages');
  const noPackage ='entries:\n  - id: "*"\n    scope: x\n    reason: long enough reason text here\n    expires: 2027-01-31\n';
  assert.ok(parseAllowlist(noPackage).errors.length > 0, 'package is required');
});

test('allowlist: an expired entry waives nothing and is reported; the expiry day itself is still live', () => {
  const m = match('CVE-2025-63389', 'Critical', 'not-fixed');
  const expired = gate([m], [entry({ expires: '2026-10-23' })]);
  assert.equal(expired.violations.length, 1);
  assert.equal(expired.expired.length, 1);
  assert.equal(gate([m], [entry({ expires: TODAY })]).violations.length, 0);
});

test('parseAllowlist: reads entries, accepts an empty list, rejects malformed input', () => {
  const ok = parseAllowlist(`# comment
entries:
  - id: CVE-2025-63389
    scope: ollama
    reason: "No fix upstream; reachable only on the internal network"
    expires: 2027-01-31
`);
  assert.deepEqual(ok.errors, []);
  assert.equal(ok.entries[0].scope, 'ollama');
  assert.deepEqual(parseAllowlist('entries: []\n'), { entries: [], errors: [] });
  const bad = (text) => parseAllowlist(text).errors.length > 0;
  assert.ok(bad('entries:\n  - id: CVE-2025-63389\n    reason: too short\n    expires: 2027-01-31\n'));
  assert.ok(bad('entries:\n  - id: CVE-2025-63389\n    reason: long enough reason text here\n'));
  assert.ok(bad('entries:\n  - id: CVE-2025-63389\n    reason: long enough reason text here\n    expires: soon\n'));
  assert.ok(bad('entries:\n  - id: not-an-id\n    reason: long enough reason text here\n    expires: 2027-01-31\n'));
  assert.ok(bad('entries:\n  - id: CVE-2025-63389\n    reasn: typo in the key name for the reason\n    expires: 2027-01-31\n'));
  assert.ok(bad('- id: CVE-2025-63389\n'));
});

function withFiles(grype, sbom, fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'verso-gate-'));
  const g = path.join(dir, 'g.json');
  const s = path.join(dir, 's.json');
  fs.writeFileSync(g, JSON.stringify(grype));
  fs.writeFileSync(s, JSON.stringify(sbom));
  const realOut = process.stdout.write;
  const realErr = console.error;
  process.stdout.write = () => true; // keep the gate's report and its error lines out of the test output
  console.error = () => {};
  try { return fn(g, s); } finally { process.stdout.write = realOut; console.error = realErr; fs.rmSync(dir, { recursive: true, force: true }); }
}

test('main: exit 0 clean, 1 on a violation, 3 when it cannot verify (fail-closed)', () => {
  const sbom = { components: [{ name: 'a' }, { name: 'b' }] };
  const run = (grype, extra = []) => withFiles(grype, sbom, (g, s) => main(['--label', 'x', '--grype', g, '--sbom', s, ...extra]));
  assert.equal(run({ matches: [] }), 0);
  assert.equal(run({ matches: [match('CVE-2026-1', 'Critical', 'fixed')] }), 1);
  assert.equal(run({}), 3, 'a report without a matches array is not a clean scan');
  assert.equal(run({ matches: [] }, ['--min-components', '3']), 3, 'an SBOM with too few components is not a clean scan');
  assert.equal(main(['--label', 'x']), 3);
  assert.equal(withFiles({ matches: [] }, { components: [] }, (g, s) => main(['--label', 'x', '--grype', g, '--sbom', s])), 3);
});

// ---- the real files -------------------------------------------------------------------------------------------------

test('vuln-allowlist.yaml: parses, every entry is justified, none has expired, none runs further out than ~6 months', () => {
  const { entries, errors } = parseAllowlist(read('.github/vuln-allowlist.yaml'));
  assert.deepEqual(errors, []);
  const today = new Date().toISOString().slice(0, 10);
  const horizon = new Date(Date.now() + 190 * 86400000).toISOString().slice(0, 10);
  const wildcardHorizon = new Date(Date.now() + 70 * 86400000).toISOString().slice(0, 10);
  for (const e of entries) {
    if (e.id === '*') assert.ok(e.expires <= wildcardHorizon, `${e.scope}/${e.package}: a package-wide waiver is reviewed every two months`);
    assert.ok(e.expires >= today, `${e.id} expired on ${e.expires}: fix it, or renew with a new justification`);
    assert.ok(e.expires <= horizon, `${e.id} expires ${e.expires}: an accepted risk is reviewed at least every six months`);
  }
  const ids = entries.map((e) => `${e.id}|${e.scope ?? ''}|${e.package ?? ''}`);
  assert.equal(new Set(ids).size, ids.length, 'duplicate allowlist entry');
});

test('workflows: every action is pinned to a 40-hex commit SHA and the scan workflow is read-only', () => {
  const dir = path.join(ROOT, '.github', 'workflows');
  for (const file of fs.readdirSync(dir).filter((f) => /\.ya?ml$/.test(f))) {
    for (const m of read(`.github/workflows/${file}`).matchAll(/^\s*-?\s*uses:\s*(\S+)/gm)) {
      assert.match(m[1], /@[0-9a-f]{40}$/, `${file}: ${m[1]} is not pinned to a commit SHA`);
    }
  }
  const scan = read('.github/workflows/supply-chain-scan.yml');
  assert.doesNotMatch(scan, /:\s*write\b/, 'the scan workflow must not request any write permission');
  assert.match(scan, /^permissions:\s*\n\s+contents: read/m);
});

test('scan workflow: scanner images are pinned by digest and the Maven plugin version is pinned in the pom', () => {
  const scan = read('.github/workflows/supply-chain-scan.yml');
  for (const name of ['SYFT', 'GRYPE']) {
    assert.match(scan, new RegExp(`^  ${name}: \\S+:v\\d+\\.\\d+\\.\\d+@sha256:[0-9a-f]{64}$`, 'm'), `${name} image`);
  }
  assert.match(read('pom.xml'), /<cyclonedx-maven-plugin\.version>\d+\.\d+\.\d+<\/cyclonedx-maven-plugin\.version>/);
});

test('release workflow: images are signed keyless before the version tag moves, with a pinned cosign', () => {
  const release = read('.github/workflows/release.yml');
  assert.match(release, /id-token: write/);
  assert.match(release, /cosign-release: 'v\d+\.\d+\.\d+'/);
  const sign = release.indexOf('cosign sign ');
  const attest = release.indexOf('cosign attest ');
  const verify = release.indexOf('cosign verify ');
  const tag = release.indexOf('imagetools create -t');
  assert.ok(sign > 0 && attest > sign && verify > attest, 'sign, then attest, then verify');
  assert.ok(tag > verify, 'the version tag points at the digest only after the signature verified');
  assert.doesNotMatch(release, /cosign sign[^\n]*--key/, 'keyless only: no long-lived signing key');
});
