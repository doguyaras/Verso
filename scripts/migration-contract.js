#!/usr/bin/env node
/**
 * Expand/contract isareti (ADR-0040, referans 18.4). Bir PR'da yeni eklenen her `db/migration/**\/V*.sql` dosyasi
 * DROP ya da RENAME iceriyorsa, onceki uygulama surumu yeni semayla calisamayabilir (geri alma = yedekten donus).
 * Bu degisiklik bilincli olsun diye dosyada bir isaret satiri zorunludur:
 *
 *   -- contract: <neden geriye uyumsuz; hangi surumden once yayinlanmamali>
 *
 * Yorumlar ve tirnakli metinler taranmaz ("-- Rollback: DROP INDEX ..." yorumu ihlal degildir). Base'te var olan
 * dosyalar bu kontrolun konusu degildir (degismezlik: flyway-immutability.js).
 *
 *   node scripts/migration-contract.js check [--base <ref>]
 * Cikis: 0 uygun · 1 isaretsiz DROP/RENAME · 3 dogrulanamadi (fail-closed).
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { isMigrationPath, resolveBase, resolveComparePoint } = require('./flyway-immutability.js');

const MARKER_RE = /^[ \t]*--[ \t]*contract:[ \t]*\S.{9,}$/m;
const DESTRUCTIVE_RE = /\b(DROP|RENAME)\b/gi;

function git(args, cwd) {
  return execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
}

/** Satir ve blok yorumlari ile tek tirnakli metinleri atar; kalan SQL'de DROP/RENAME aranir. */
function executableSql(sql) {
  return sql
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/--[^\n]*/g, ' ')
    .replace(/'(?:[^']|'')*'/g, "''");
}

/** @returns {{destructive: string[], marked: boolean}} */
function inspect(sql) {
  const found = new Set((executableSql(sql).match(DESTRUCTIVE_RE) || []).map((w) => w.toUpperCase()));
  return { destructive: [...found].sort(), marked: MARKER_RE.test(sql) };
}

function check({ baseRef, onMissingBase = 'fail', cwd } = {}) {
  const root = git(['rev-parse', '--show-toplevel'], cwd).trim();
  const explicit = Boolean(baseRef || process.env.FLYWAY_BASE_REF);
  const { point, base } = resolveComparePoint({ baseRef: resolveBase(baseRef), onMissingBase, cwd, explicit });
  const out = git(['diff', '--name-status', '--no-renames', '-z', point], cwd);
  const parts = out.split('\0').filter((s) => s.length > 0);
  const violations = [];
  let scanned = 0;
  for (let i = 0; i + 1 < parts.length; i += 2) {
    const file = parts[i + 1].replace(/\\/g, '/');
    if (parts[i].trim() !== 'A' || !isMigrationPath(file)) continue;
    scanned++;
    const r = inspect(fs.readFileSync(path.join(root, file), 'utf8'));
    if (r.destructive.length > 0 && !r.marked) violations.push({ file, words: r.destructive });
  }
  return { base, point, scanned, violations };
}

function main(argv) {
  const baseAt = argv.indexOf('--base');
  const baseRef = baseAt >= 0 ? argv[baseAt + 1] : undefined;
  if (argv[0] !== 'check') {
    console.error('Kullanim: check [--base <ref>]');
    return 3;
  }
  try {
    const r = check({ baseRef });
    if (r.violations.length === 0) {
      console.log(`migration-contract: OK (${r.scanned} yeni migration, base=${r.base})`);
      return 0;
    }
    console.error(`migration-contract: ISARETSIZ geriye uyumsuz degisiklik (base=${r.base}):`);
    for (const v of r.violations) console.error(`  ${v.file}\t${v.words.join(', ')}`);
    console.error('Duzeltme: expand/contract ile bolun (once ekle, sonraki surumde sil) ya da bilincliyse dosyaya ekle:');
    console.error('  -- contract: <neden geriye uyumsuz>   (docs/runbooks/surum-yukseltme-ve-geri-alma.md)');
    return 1;
  } catch (err) {
    console.error(`migration-contract: DOGRULANAMADI — ${err.message}`);
    return 3;
  }
}

module.exports = { inspect, executableSql, check, main };

if (require.main === module) {
  process.exit(main(process.argv.slice(2)));
}
