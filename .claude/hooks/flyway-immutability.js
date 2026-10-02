#!/usr/bin/env node
/**
 * Claude Code PreToolUse hook (Edit|Write|MultiEdit): base'teki V*.sql dosyasina yazmayi engeller.
 * Kuralin tek kaynagi scripts/flyway-immutability.js'dir; bu dosya yalnizca adaptordur.
 *
 * Fail-closed: beklenmeyen hata veya bozuk girdi de engeller (exit 2). stderr Claude'a gider.
 * Base: FLYWAY_BASE_REF; yoksa sirayla origin/develop, origin/main, origin/master, develop, main, master;
 * hicbiri yoksa HEAD agacina gore korur (mesajda gercekten kullanilan ref yazilir).
 */
'use strict';
const path = require('node:path');

function fail(msg) {
  process.stderr.write(`[flyway-immutability hook] ${msg}\n`);
  process.exit(2);
}

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (c) => { raw += c; });
process.stdin.on('end', () => {
  let input;
  try {
    input = JSON.parse(raw);
  } catch (e) {
    fail(`hook girdisi JSON degil: ${e.message}`);
  }
  const filePath = input && input.tool_input && input.tool_input.file_path;
  if (!filePath) fail('tool_input.file_path yok; fail-closed');

  const root = process.env.CLAUDE_PROJECT_DIR || process.cwd();
  let script;
  try {
    script = require(path.join(root, 'scripts', 'flyway-immutability.js'));
  } catch (e) {
    fail(`scripts/flyway-immutability.js yuklenemedi: ${e.message}`);
  }

  try {
    const r = script.checkFile(filePath, { baseRef: process.env.FLYWAY_BASE_REF, onMissingBase: 'head', cwd: root });
    if (r.protected) {
      const where = r.fallback ? 'HEAD agacinda (base branch bulunamadi, FLYWAY_BASE_REF ayarlanabilir)' : `base'te (${r.base})`;
      fail(`${r.file} ${where} mevcut bir Flyway migration'i; degistirilemez/silinemez. Degisikligi yeni bir V<sonraki>__*.sql dosyasiyla yap.`);
    }
    process.exit(0);
  } catch (e) {
    fail(`dogrulanamadi (fail-closed): ${e.message}`);
  }
});
