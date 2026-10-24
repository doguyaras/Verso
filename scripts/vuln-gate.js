#!/usr/bin/env node
/**
 * Vulnerability gate for the monthly SBOM scan (ADR-0030, security-rules 9). Dependency-free; CI and local runs use
 * the same code. It reads a Grype JSON report (Grype scanned a CycloneDX SBOM) and fails on:
 *   - every CRITICAL finding, fixed or not, and
 *   - every HIGH finding that has a fix available (fix.state == "fixed").
 * A finding is waived only by a live entry in the allowlist (.github/vuln-allowlist.yaml). An entry needs a reason and
 * an expiry date; an expired entry waives nothing and is itself reported as an error, so an accepted risk cannot
 * silently outlive its review.
 *
 * Usage:
 *   node scripts/vuln-gate.js --label <name> --grype <grype.json> --sbom <cyclonedx.json>
 *        [--allowlist <file>] [--min-components <n>] [--today <YYYY-MM-DD>]
 * Markdown summary goes to stdout (the workflow appends it to the job summary).
 * Exit codes: 0 pass, 1 violation, 3 could not verify (unreadable input, empty SBOM; fail-closed, never "pass").
 */
'use strict';

const fs = require('node:fs');

const ALLOWLIST_KEYS = new Set(['id', 'scope', 'package', 'reason', 'expires']);
const ID_PATTERN = /^(CVE-\d{4}-\d{4,}|GHSA(-[a-z0-9]{4}){3}|[A-Z][A-Z0-9]+-[A-Za-z0-9._:-]+)$/;
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const SEVERITIES = ['Critical', 'High', 'Medium', 'Low', 'Negligible', 'Unknown'];

function unquote(value) {
  const v = value.trim();
  if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) return v.slice(1, -1);
  return v;
}

/**
 * Parses the one YAML shape the allowlist uses: a top-level "entries:" list of flat key/value maps. Anything else is
 * an error, so a typo cannot turn into a silently ignored entry.
 */
function parseAllowlist(text) {
  const entries = [];
  const errors = [];
  let current = null;
  let sawRoot = false;
  text.split(/\r?\n/).forEach((raw, index) => {
    const line = raw.replace(/\s+#.*$/, '').replace(/^#.*$/, '');
    if (!line.trim()) return;
    const at = `line ${index + 1}`;
    if (/^entries:\s*(\[\])?\s*$/.test(line)) { sawRoot = true; return; }
    const item = /^\s{2}- ([a-z]+):\s*(.*)$/.exec(line);
    const cont = /^\s{4}([a-z]+):\s*(.*)$/.exec(line);
    const match = item || cont;
    if (!match || !sawRoot) { errors.push(`${at}: unsupported syntax`); return; }
    if (item) { current = {}; entries.push(current); }
    if (!current) { errors.push(`${at}: key before the first "- " item`); return; }
    const [, key, value] = match;
    if (!ALLOWLIST_KEYS.has(key)) { errors.push(`${at}: unknown key "${key}"`); return; }
    if (key in current) { errors.push(`${at}: duplicate key "${key}"`); return; }
    current[key] = unquote(value);
  });
  entries.forEach((entry, i) => {
    const name = `entry ${i + 1}${entry.id ? ` (${entry.id})` : ''}`;
    if (entry.id === '*') {
      // Package-wide waiver for an upstream binary we cannot patch (a stale Go toolchain inside a third-party image):
      // it must name both the image and the package, so it can never reach beyond one component.
      if (!entry.scope || !entry.package) errors.push(`${name}: "id: *" needs both "scope" and "package"`);
    } else if (!entry.id || !ID_PATTERN.test(entry.id)) {
      errors.push(`${name}: "id" must be a CVE, GHSA or Go advisory identifier (or "*" with scope and package)`);
    }
    if (!entry.reason || entry.reason.length < 20) errors.push(`${name}: "reason" is required (at least 20 characters)`);
    if (!entry.expires || !DATE_PATTERN.test(entry.expires) || Number.isNaN(Date.parse(entry.expires))) {
      errors.push(`${name}: "expires" must be a YYYY-MM-DD date`);
    }
  });
  return { entries, errors };
}

function isExpired(entry, today) {
  return entry.expires < today; // ISO dates compare as strings; the entry is live on its expiry day
}

function findingIds(match) {
  const ids = new Set([match.vulnerability.id]);
  for (const related of match.relatedVulnerabilities ?? []) ids.add(related.id);
  return ids;
}

/** Exact name, or a prefix glob with one trailing "*" ("netty-*", "golang.org/x/*", or "*" for the whole image). */
function packageMatches(pattern, name) {
  return pattern.endsWith('*') ? name.startsWith(pattern.slice(0, -1)) : pattern === name;
}

function waivedBy(match, label, entries, today) {
  const ids = findingIds(match);
  return entries.find((e) => !isExpired(e, today) && (e.id === '*' || ids.has(e.id))
    && (!e.scope || label.includes(e.scope))
    && (!e.package || packageMatches(e.package, match.artifact.name)));
}

function severityOf(match) {
  const s = String(match.vulnerability.severity ?? 'Unknown').toLowerCase();
  return SEVERITIES.find((x) => x.toLowerCase() === s) ?? 'Unknown';
}

function hasFix(match) {
  return match.vulnerability.fix?.state === 'fixed';
}

/** Pure policy: returns counts and the lists the report needs. */
function evaluate({ label, grype, entries, today }) {
  const matches = grype.matches ?? [];
  const counts = Object.fromEntries(SEVERITIES.map((s) => [s, 0]));
  const violations = [];
  const waived = [];
  let fixable = 0;
  for (const match of matches) {
    const severity = severityOf(match);
    counts[severity] += 1;
    if (hasFix(match)) fixable += 1;
    const blocking = severity === 'Critical' || (severity === 'High' && hasFix(match));
    if (!blocking) continue;
    const entry = waivedBy(match, label, entries, today);
    const row = {
      id: match.vulnerability.id,
      severity,
      package: `${match.artifact.name}@${match.artifact.version}`,
      fix: hasFix(match) ? match.vulnerability.fix.versions?.join(', ') || 'yes' : 'none',
    };
    if (entry) waived.push({ ...row, expires: entry.expires }); else violations.push(row);
  }
  const expired = entries.filter((e) => isExpired(e, today));
  return { total: matches.length, counts, fixable, violations, waived, expired };
}

function report(label, result, componentCount) {
  const { counts, violations, waived, expired } = result;
  const lines = [`### ${label}`, ''];
  lines.push(`SBOM components: ${componentCount}. Findings: ${result.total} (` +
    SEVERITIES.filter((s) => counts[s]).map((s) => `${counts[s]} ${s.toLowerCase()}`).join(', ') +
    `${result.total ? '' : 'none'}); with a fix available: ${result.fixable}.`, '');
  if (violations.length) {
    // One row per package keeps the job summary readable when a stale Go toolchain drags in dozens of advisories;
    // the full list is in the grype JSON artifact.
    const byPackage = new Map();
    for (const v of violations) {
      const row = byPackage.get(v.package) ?? { critical: 0, high: 0, ids: new Set() };
      row[v.severity === 'Critical' ? 'critical' : 'high'] += 1;
      row.ids.add(v.id);
      byPackage.set(v.package, row);
    }
    lines.push(`**Blocking** (critical, or high with a fix): ${violations.length} findings in ${byPackage.size} packages`, '',
      '| Package | Critical | High | Advisories (first 6) |', '|---|---|---|---|');
    for (const [pkg, row] of byPackage) {
      lines.push(`| ${pkg} | ${row.critical} | ${row.high} | ${[...row.ids].slice(0, 6).join(', ')} |`);
    }
    lines.push('');
  }
  if (waived.length) {
    const ids = new Map(waived.map((w) => [w.id, w]));
    lines.push(`Allowlisted (.github/vuln-allowlist.yaml): ${waived.length} findings, ${ids.size} advisories`, '');
    for (const w of [...ids.values()].slice(0, 10)) lines.push(`- ${w.id} (${w.severity}), expires ${w.expires}`);
    if (ids.size > 10) lines.push(`- … and ${ids.size - 10} more`);
    lines.push('');
  }
  if (expired.length) {
    lines.push('**Expired allowlist entries** (renew with a new justification or remove):', '');
    for (const e of expired) lines.push(`- ${e.id}, expired ${e.expires}`);
    lines.push('');
  }
  if (!violations.length && !expired.length) lines.push('Result: pass.', '');
  return lines.join('\n');
}

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i];
    if (!key.startsWith('--') || argv[i + 1] === undefined) throw new Error(`bad argument: ${key}`);
    args[key.slice(2)] = argv[i + 1];
  }
  return args;
}

function main(argv) {
  let args;
  try { args = parseArgs(argv); } catch (e) { console.error(e.message); return 3; }
  const need = ['label', 'grype', 'sbom'].filter((k) => !args[k]);
  if (need.length) { console.error(`missing: ${need.map((k) => `--${k}`).join(' ')}`); return 3; }
  const today = args.today ?? new Date().toISOString().slice(0, 10);
  const minComponents = Number(args['min-components'] ?? 1);
  let grype; let sbom; let entries = [];
  try {
    grype = JSON.parse(fs.readFileSync(args.grype, 'utf8'));
    sbom = JSON.parse(fs.readFileSync(args.sbom, 'utf8'));
    if (args.allowlist) {
      const parsed = parseAllowlist(fs.readFileSync(args.allowlist, 'utf8'));
      if (parsed.errors.length) { console.error(`allowlist invalid:\n${parsed.errors.join('\n')}`); return 3; }
      entries = parsed.entries;
    }
  } catch (e) {
    console.error(`could not read input: ${e.message}`);
    return 3;
  }
  const components = Array.isArray(sbom.components) ? sbom.components.length : 0;
  if (!Array.isArray(grype.matches) || components < minComponents) {
    console.error(`could not verify: ${components} SBOM components (minimum ${minComponents}), matches ${Array.isArray(grype.matches) ? 'present' : 'missing'}`);
    return 3;
  }
  const result = evaluate({ label: args.label, grype, entries, today });
  process.stdout.write(`${report(args.label, result, components)}\n`);
  return result.violations.length || result.expired.length ? 1 : 0;
}

if (require.main === module) process.exit(main(process.argv.slice(2)));

module.exports = { parseAllowlist, isExpired, evaluate, report, main };
