#!/usr/bin/env node
// Before "docker compose up": every secret file the compose files mount exists, is a regular file and is not empty.
// A missing file is not an error in compose: Docker creates an empty DIRECTORY in its place, the container then reads
// a directory as its password and fails far from the cause (2026-10, a stack that would not start). This check names
// the file and the fix instead. It never prints a secret value.
//
//   node scripts/secrets-check.js                                  # compose.yaml
//   node scripts/secrets-check.js compose.yaml deploy/compose.smb.yaml
'use strict';
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '..');

/** The secret names a compose file mounts from ./secrets (its top-level "secrets:" block). */
function secretNames(composeText) {
  return [...composeText.matchAll(/^\s+file:\s*\.\/secrets\/([A-Za-z0-9_]+)\s*$/gm)].map((m) => m[1]);
}

/** One problem per bad file: missing, a directory (Docker made it) or empty. */
function check(names, dir) {
  const problems = [];
  for (const name of [...new Set(names)]) {
    const file = path.join(dir, name);
    let stat;
    try {
      stat = fs.statSync(file);
    } catch {
      problems.push({ name, kind: 'missing' });
      continue;
    }
    if (stat.isDirectory()) problems.push({ name, kind: 'directory' });
    else if (stat.size === 0) problems.push({ name, kind: 'empty' });
  }
  return problems;
}

const TEXT = {
  missing: 'yok',
  directory: 'dosya değil, dizin (eksik dosya yerine Docker oluşturmuş)',
  empty: 'boş',
};

function main(argv) {
  const files = argv.length ? argv : ['compose.yaml'];
  const names = files.flatMap((f) => secretNames(fs.readFileSync(path.resolve(ROOT, f), 'utf8')));
  const problems = check(names, path.join(ROOT, 'secrets'));
  if (!problems.length) {
    console.log(`secrets-check: OK (${new Set(names).size} secret dosyası)`);
    return 0;
  }
  for (const p of problems) console.error(`secrets-check: secrets/${p.name} ${TEXT[p.kind]}`);
  console.error('secrets-check: düzeltmek için: bash scripts/dev-secrets.sh (boş dizinleri kaldırır, eksikleri üretir;'
    + ' var olan dosyalara dokunmaz). Bağlayıcı ve bulut anahtarlarını kendiniz yazın.');
  return 1;
}

if (require.main === module) process.exit(main(process.argv.slice(2)));

module.exports = { secretNames, check };
