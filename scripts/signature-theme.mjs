#!/usr/bin/env node
// Generates the signature parts that cannot run the panel's JavaScript (ADR-0017), from panel/js/signature-data.js:
//   - the Keycloak login theme: the SVG markup in template.ftl and its CSS-only "being signed" animation (the theme adds
//     no scripts), between "signature:start" and "signature:end" markers;
//   - panel/img/favicon.svg: the "V" of the signature on a charcoal tile.
//
//   node scripts/signature-theme.mjs          # writes the files
//   node scripts/signature-theme.mjs --check  # exit 1 when a generated part is out of date (test, CI)
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { mark, SIGNATURE, timeline } from '../panel/js/signature-data.js';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const TEMPLATE = join(ROOT, 'deploy/keycloak/themes/verso/login/template.ftl');
const CSS = join(ROOT, 'deploy/keycloak/themes/verso/login/resources/css/verso-fildisi-2.css');
const FAVICON = join(ROOT, 'panel/img/favicon.svg');
const r = (v) => Math.round(v * 10) / 10;

export function themeMarkup() {
  const [x, y, w, h] = SIGNATURE.box;
  const strokes = timeline().map((s, i) => `<path class="vs-s${i}" d="${SIGNATURE.pen[i]}" stroke-dasharray="${r(s.length)} ${r(s.length)}" stroke-dashoffset="${r(s.length)}"/>`);
  return [
    `<svg class="verso-signature" viewBox="${x} ${y} ${w} ${h}" role="img" aria-label="Verso" focusable="false">`,
    `  <defs><mask id="verso-signature-mask" maskUnits="userSpaceOnUse" x="${x}" y="${y}" width="${w}" height="${h}" fill="none" stroke="#fff" stroke-width="${r(SIGNATURE.thickness * 1.25)}" stroke-linecap="round" stroke-linejoin="round">`,
    ...strokes.map((s) => `    ${s}`),
    '  </mask></defs>',
    `  <path class="verso-signature-ink" d="${SIGNATURE.outline}" mask="url(#verso-signature-mask)"/>`,
    '</svg>',
  ].join('\n');
}

export function themeCss() {
  const rules = timeline().map((s, i) => `.verso-signature .vs-s${i} { animation: verso-sign ${Math.round(s.duration)}ms cubic-bezier(.45,.05,.55,.95) ${Math.round(s.delay + 250)}ms forwards; }`);
  return [
    '@keyframes verso-sign { to { stroke-dashoffset: 0; } }',
    ...rules,
    '@media (prefers-reduced-motion: reduce) { .verso-signature mask path { animation: none !important; stroke-dashoffset: 0 !important; } }',
  ].join('\n');
}

export function favicon() {
  const { d, transform } = mark(32, 4);
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="7" fill="#2a2d33"/><path fill="#d9bc7c" transform="${transform}" d="${d}"/></svg>\n`;
}

function between(text, start, end, content, file) {
  const a = text.indexOf(start), b = text.indexOf(end);
  if (a < 0 || b < a) throw new Error(`${file}: markers ${start} / ${end} not found`);
  return text.slice(0, a + start.length) + '\n' + content + '\n' + text.slice(b);
}

const check = process.argv.includes('--check');
const outputs = [
  [TEMPLATE, (t) => between(t, '<#-- signature:start -->', '<#-- signature:end -->', themeMarkup(), TEMPLATE)],
  [CSS, (t) => between(t, '/* signature:start */', '/* signature:end */', themeCss(), CSS)],
  [FAVICON, () => favicon()],
];
let stale = 0;
for (const [file, make] of outputs) {
  let current = '';
  try {
    current = readFileSync(file, 'utf8');
  } catch {
    current = '';
  }
  const next = make(current);
  if (next === current) continue;
  if (check) {
    console.error(`signature-theme: ${file} is out of date; run node scripts/signature-theme.mjs`);
    stale++;
  } else {
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, next);
    console.log(`signature-theme: wrote ${file}`);
  }
}
process.exit(stale ? 1 : 0);
