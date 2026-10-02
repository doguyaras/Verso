#!/usr/bin/env node
/**
 * Flyway migration degismezligi — TEK KAYNAK.
 * CI (workflow), AI hook (.claude/hooks/flyway-immutability.js) ve elle kullanim ayni kodu cagirir.
 *
 * Kural: base branch'e girmis (merge-base'te var olan) her `db/migration/**\/V*.sql` dosyasi
 * degistirilemez, silinemez, yeniden adlandirilamaz. Branch'te yeni eklenen V* dosyalari ve
 * tum R__* dosyalari serbesttir.
 *
 * Kullanim:
 *   node scripts/flyway-immutability.js check [--staged] [--base <ref>] [--on-missing-base fail|head]
 *     --staged: yalniz commit edilecek icerik (index) incelenir; pre-commit bunu kullanir. Calisma agaci geri
 *     alinmis ama degisikligi stage'de kalan bir base migration'i boylece yakalanir (ucuncu tur review N6).
 *   node scripts/flyway-immutability.js check-file <yol> [--base <ref>] [--on-missing-base fail|head]
 *
 * Cikis kodlari: 0 uygun · 1 ihlal · 3 dogrulanamadi (git hatasi; fail-closed).
 * Base cozumleme sirasi: --base → FLYWAY_BASE_REF → origin/develop. '-' ile baslayan ref reddedilir.
 */
'use strict';

const { execFileSync } = require('node:child_process');
const path = require('node:path');

const MIGRATION_RE = /(^|\/)db\/migration\/(.+\/)?V[^/]*\.sql$/i;
const DEFAULT_BASE = 'origin/develop';

function normalize(p) {
  return String(p).replace(/\\/g, '/');
}

function isMigrationPath(p) {
  return MIGRATION_RE.test(normalize(p));
}

function git(args, opts = {}) {
  return execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], ...opts });
}

function assertSafeRef(ref) {
  if (typeof ref !== 'string' || ref.length === 0 || ref.startsWith('-')) {
    throw new Error(`Gecersiz base ref: ${JSON.stringify(ref)}`);
  }
  return ref;
}

/** Acik ref (arguman/env) verilmisse yalniz o; verilmemisse sirayla denenecek adaylar (ilk bulunan kazanir). */
const FALLBACK_BASES = ['origin/develop', 'origin/main', 'origin/master', 'develop', 'main', 'master'];

function resolveBase(explicit) {
  return assertSafeRef(explicit || process.env.FLYWAY_BASE_REF || DEFAULT_BASE);
}

function mergeBaseOf(ref, cwd) {
  try { return git(['merge-base', ref, 'HEAD'], { cwd }).trim(); } catch (e) { return null; }
}

/**
 * Karsilastirma noktasi: acik ref varsa onun merge-base'i; yoksa FALLBACK_BASES sirayla denenir.
 * Hicbiri yoksa: 'fail' → hata (exit 3), 'head' → HEAD agacina gore koru (base='HEAD').
 * Donus: { point, base } — base, GERCEKTEN kullanilan ref'tir (hata mesajlari yaniltmasin diye).
 */
function resolveComparePoint({ baseRef, onMissingBase = 'fail', cwd, explicit = true }) {
  const candidates = explicit ? [baseRef] : [baseRef, ...FALLBACK_BASES.filter((b) => b !== baseRef)];
  for (const ref of candidates) {
    const point = mergeBaseOf(ref, cwd);
    if (point) return { point, base: ref, fallback: false };
  }
  if (onMissingBase === 'head') {
    try {
      return { point: git(['rev-parse', 'HEAD'], { cwd }).trim(), base: 'HEAD', fallback: true };
    } catch (e2) {
      throw new Error(`HEAD cozumlenemedi: ${e2.message}`);
    }
  }
  throw new Error(`merge-base bulunamadi (denenen: ${candidates.join(', ')})`);
}

function repoRoot(cwd) {
  return git(['rev-parse', '--show-toplevel'], { cwd }).trim();
}

function toRepoRelative(filePath, cwd) {
  const root = repoRoot(cwd);
  const abs = path.isAbsolute(filePath) ? filePath : path.resolve(cwd || process.cwd(), filePath);
  return normalize(path.relative(root, abs));
}

/**
 * Tum degisiklikleri (working tree + staged + branch commit'leri) merge-base'e gore inceler.
 * --no-renames: yeniden adlandirma D + A olarak gorunur; D ihlaldir.
 */
function check({ baseRef, onMissingBase = 'fail', cwd, staged = false } = {}) {
  const explicit = Boolean(baseRef || process.env.FLYWAY_BASE_REF);
  const { point, base } = resolveComparePoint({ baseRef: resolveBase(baseRef), onMissingBase, cwd, explicit });
  const out = git(['diff', ...(staged ? ['--cached'] : []), '--name-status', '--no-renames', '-z', point], { cwd });
  const parts = out.split('\0').filter((s) => s.length > 0);
  const violations = [];
  for (let i = 0; i + 1 < parts.length; i += 2) {
    const status = parts[i].trim();
    const file = normalize(parts[i + 1]);
    if (!isMigrationPath(file)) continue;
    if (status === 'A') continue; // branch'te yeni eklenen V* serbest
    violations.push({ status, file });
  }
  return { base, point, violations };
}

/** Tek dosya: base agacinda varsa korunur (hook bu fonksiyonu yazma aninda cagirir). */
function checkFile(filePath, { baseRef, onMissingBase = 'fail', cwd } = {}) {
  const rel = toRepoRelative(filePath, cwd);
  if (!isMigrationPath(rel)) return { protected: false, file: rel };
  const explicit = Boolean(baseRef || process.env.FLYWAY_BASE_REF);
  const { point, base, fallback } = resolveComparePoint({ baseRef: resolveBase(baseRef), onMissingBase, cwd, explicit });
  // ls-tree pathspec'i calisilan dizine goredir: proje koku repo kokunun alt klasoruyse (monorepo) yanlis yol
  // aranir ve base dosyasi "yok" sanilir (fail-open). Bu yuzden git her zaman repo kokunde calistirilir.
  const root = repoRoot(cwd);
  const listed = git(['ls-tree', '-r', '--name-only', point, '--', rel], { cwd: root }).trim();
  return { protected: listed.length > 0, file: rel, base, point, fallback };
}

function parseArgs(argv) {
  const args = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--base') args.base = argv[++i];
    else if (a === '--on-missing-base') args.onMissingBase = argv[++i];
    else if (a === '--staged') args.staged = true;
    else args._.push(a);
  }
  return args;
}

function main(argv) {
  const args = parseArgs(argv);
  const cmd = args._[0];
  try {
    if (cmd === 'check') {
      const r = check({ baseRef: args.base, onMissingBase: args.onMissingBase, staged: Boolean(args.staged) });
      if (r.violations.length === 0) {
        console.log(`flyway-immutability: OK (base=${r.base}, merge-base=${r.point.slice(0, 12)})`);
        return 0;
      }
      console.error(`flyway-immutability: IHLAL — base'teki migration dosyalarina dokunulmus (base=${r.base}):`);
      for (const v of r.violations) console.error(`  ${v.status}\t${v.file}`);
      console.error('Duzeltme: dosyayi geri al; degisikligi yeni bir V<sonraki>__*.sql ile yap.');
      return 1;
    }
    if (cmd === 'check-file') {
      const file = args._[1];
      if (!file) throw new Error('check-file icin dosya yolu gerekli');
      const r = checkFile(file, { baseRef: args.base, onMissingBase: args.onMissingBase });
      if (r.protected) {
        console.error(`flyway-immutability: IHLAL — ${r.file} base'te (${r.base}) mevcut; degistirilemez. Yeni V<sonraki>__*.sql ac.`);
        return 1;
      }
      console.log(`flyway-immutability: OK (${r.file})`);
      return 0;
    }
    console.error('Kullanim: check [--staged] [--base <ref>] [--on-missing-base fail|head] | check-file <yol> [--base <ref>]');
    return 3;
  } catch (err) {
    console.error(`flyway-immutability: DOGRULANAMADI — ${err.message}`);
    return 3;
  }
}

module.exports = { MIGRATION_RE, FALLBACK_BASES, isMigrationPath, resolveBase, resolveComparePoint, check, checkFile, main };

if (require.main === module) {
  process.exit(main(process.argv.slice(2)));
}
