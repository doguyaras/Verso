#!/usr/bin/env node
/**
 * Config lint — secret hijyeni (referans Bolum 15.3, 19.5). Bagimliliksiz; CI ve elle kullanim ayni kodu cagirir.
 *
 * Kurallar:
 *  (a) secret-fallback: secret gorunumlu bir key'de (ya da secret gorunumlu bir ${ENV} adinda) Spring placeholder'i
 *      literal fallback tasiyamaz: ${SECRET_DB_PASSWORD:changeme}, ${X:} (bos fallback da fallback'tir),
 *      ${A:${B:lit}}. Neden: env eksikse uygulama sahte/bos secret ile ayaga kalkar; fail-fast yerine sessiz hata.
 *      ${A:${B}} serbesttir (fallback yine env'den gelir, literal yok).
 *      Secret dosya yolu key'leri (private-key-path gibi) icin tek izinli fallback /run/secrets/... yoludur.
 *  (b) literal-secret: local profil disindaki dosyalarda secret key'e duz deger yazilamaz. Izinli degerler:
 *      bos, ${...} iceren deger, /run/secrets/... yolu. Block scalar (| veya >) literal sayilir (PEM govdesi).
 *  (c) Local profildeki bir satirda "# lint:allow-secret-fallback <gerekce>" varsa o satir muaftir (local sahte
 *      degerler icin). Gerekce zorunlu; baska bir yorum muafiyet saglamaz; local disi dosyada isaret yok sayilir.
 *
 * Local profil: dosya adinda "local" parcasi (application-local.yml, .env.local, local.env) ya da YAML belgesinde
 * spring.config.activate.on-profile: local / spring.profiles: local.
 *
 * Kullanim:  node scripts/config-lint.js <dosya>...     (.yml/.yaml, .properties, *.env*)
 * Cikis kodlari: 0 uygun · 1 ihlal · 3 dogrulanamadi (okunamayan/desteklenmeyen dosya ya da dosya verilmedi; fail-closed).
 * Cikti "dosya:satir: [kural] key" bicimindedir; literal degerler log'a YAZILMAZ (CI log'u da secret sizdirmasin).
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

/** Parca icinde gecmesi yeterli olan secret kelimeleri (dbpassword, clientsecret, privatekey...). */
const SECRET_SUBSTRINGS = ['password', 'passwd', 'secret', 'pepper', 'private', 'credential', 'apikey', 'accesskey'];
/** Kisa kelimeler yalniz TAM parca olarak eslesir (monkey, keyspace, tokenizer, salty yanlis pozitif olmasin). */
const SECRET_EXACT = new Set(['key', 'token', 'salt', 'pwd', 'pw', 'pass']);
/** Son parca bunlardan biriyse key secret'in KENDISI degil, dosyasina isaret eder (secret-ref). */
const REF_DESCRIPTORS = new Set(['path', 'file', 'location', 'dir', 'directory']);
/** Son parca bunlardan biriyse key secret'in niteligidir, degeri secret degildir (key-store-type, token-ttl...). */
const NON_SECRET_DESCRIPTORS = new Set([
  'type', 'prefix', 'suffix', 'header', 'name', 'id', 'alias', 'algorithm', 'alg', 'size', 'length', 'ttl',
  'expiry', 'expiration', 'expires', 'validity', 'lifetime', 'duration', 'timeout', 'enabled', 'issuer', 'audience',
  'format', 'count', 'mode', 'strategy', 'version', 'kid', 'uri', 'url', 'seconds', 'minutes', 'hours', 'days',
  'policy', 'provider', 'required',
]);

const ALLOW_RE = /#\s*lint:allow-secret-fallback\s+\S/;
const RUN_SECRETS_RE = /^\/run\/secrets\/[^\s]+$/;

/** Key/env adini parcalara boler: spring.datasource.password, SECRET_DB_PASSWORD, clientSecret, key-store[0]. */
function tokens(name) {
  return String(name)
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter((t) => t.length > 0);
}

/** 'secret' | 'secret-ref' | 'none' */
function classify(name) {
  const t = tokens(name);
  if (t.length === 0) return 'none';
  // Sondaki rakam atilir: key1/token2 (anahtar rotasyonu listeleri) de secret'tir.
  const hit = t.some((p) => SECRET_EXACT.has(p.replace(/\d+$/, '')) || SECRET_SUBSTRINGS.some((s) => p.includes(s)));
  if (!hit) return 'none';
  const last = t[t.length - 1];
  if (REF_DESCRIPTORS.has(last)) return 'secret-ref';
  if (NON_SECRET_DESCRIPTORS.has(last)) return 'none';
  return 'secret';
}

/** Iki siniflandirmadan daha siki olani (secret > secret-ref > none). */
function stricter(a, b) {
  const rank = { none: 0, 'secret-ref': 1, secret: 2 };
  return rank[a] >= rank[b] ? a : b;
}

/**
 * Metindeki ust duzey ${...} placeholder'larini ic ice destekli cozer.
 * Donus: [{ start, end, name, fallback }] — fallback: null (yok) ya da ham fallback metni ('' dahil).
 */
function placeholders(text) {
  const out = [];
  let i = 0;
  while ((i = text.indexOf('${', i)) !== -1) {
    let depth = 0;
    let j = i;
    for (; j < text.length; j++) {
      if (text.startsWith('${', j)) { depth++; j++; continue; }
      if (text[j] === '}') { depth--; if (depth === 0) break; }
    }
    if (depth !== 0) break; // kapanmamis placeholder: Spring de cozemez, burada da atlanir
    const inner = text.slice(i + 2, j);
    const colon = topLevelColon(inner);
    out.push({
      start: i,
      end: j + 1,
      name: colon === -1 ? inner : inner.slice(0, colon),
      fallback: colon === -1 ? null : inner.slice(colon + 1),
    });
    i = j + 1;
  }
  return out;
}

function topLevelColon(s) {
  let depth = 0;
  for (let k = 0; k < s.length; k++) {
    if (s.startsWith('${', k)) { depth++; k++; continue; }
    if (s[k] === '}') depth--;
    else if (s[k] === ':' && depth === 0) return k;
  }
  return -1;
}

/**
 * Fallback zincirinde literal var mi? ${A:${B}} → yok; ${A:${B:x}} → var; ${A:} → var (bos string).
 * Donus: literal varsa { name } (literal'i tasiyan placeholder adi), yoksa null.
 */
function literalFallback(ph) {
  if (ph.fallback === null) return null;
  const nested = placeholders(ph.fallback);
  let rest = ph.fallback;
  for (let k = nested.length - 1; k >= 0; k--) rest = rest.slice(0, nested[k].start) + rest.slice(nested[k].end);
  if (nested.length === 0 || rest.trim().length > 0) return { name: ph.name, literal: ph.fallback };
  for (const n of nested) {
    const inner = literalFallback(n);
    if (inner) return inner;
  }
  return null;
}

/** Placeholder zincirindeki tum env adlari (ic ice fallback'ler dahil). */
function placeholderNames(ph) {
  const names = [ph.name];
  if (ph.fallback !== null) for (const n of placeholders(ph.fallback)) names.push(...placeholderNames(n));
  return names;
}

function formatOf(file) {
  const base = path.basename(file).toLowerCase();
  if (/\.ya?ml$/.test(base)) return 'yaml';
  if (/\.properties$/.test(base)) return 'properties';
  if (/(^|\.)env(\.|$)/.test(base)) return 'env';
  return null;
}

function isLocalFile(file) {
  return /(^|[-_.])local([-_.]|$)/i.test(path.basename(file));
}

/** Tirnak disindaki satir ici yorumu (bosluk + #) atar; tirnak icindeki # korunur. */
function stripInlineComment(s) {
  let q = null;
  for (let k = 0; k < s.length; k++) {
    const c = s[k];
    if (q) { if (c === q) q = null; continue; }
    if (c === '"' || c === "'") q = c;
    else if (c === '#' && (k === 0 || /\s/.test(s[k - 1]))) return s.slice(0, k);
  }
  return s;
}

function unquote(v) {
  const t = v.trim();
  if (t.length >= 2 && ((t[0] === '"' && t.endsWith('"')) || (t[0] === "'" && t.endsWith("'")))) return t.slice(1, -1);
  return t;
}

/** Flow mapping { a: b, c: "d" } → [[a, b], [c, d]] (tek seviye; tirnak ve ${} icindeki virgul bolmez). */
function flowPairs(v) {
  const t = v.trim();
  if (!(t.startsWith('{') && t.endsWith('}'))) return null;
  const inner = t.slice(1, -1);
  const parts = [];
  let depth = 0; let q = null; let cur = '';
  for (let k = 0; k < inner.length; k++) {
    const c = inner[k];
    if (q) { if (c === q) q = null; cur += c; continue; }
    if (c === '"' || c === "'") q = c;
    else if (c === '{' || c === '[') depth++;
    else if (c === '}' || c === ']') depth--;
    else if (c === ',' && depth === 0) { parts.push(cur); cur = ''; continue; }
    cur += c;
  }
  if (cur.trim()) parts.push(cur);
  return parts.map((p) => {
    const m = /^\s*([^:]+?)\s*:\s*(.*)$/.exec(p);
    return m ? [unquote(m[1]), m[2].trim()] : null;
  }).filter(Boolean);
}

/**
 * Tek bir (key, deger) ciftini kurallara karsi denetler.
 * keyClass: key'in kendi siniflandirmasi; local: literal-secret kurali uygulanmaz.
 */
function checkPair({ file, line, key, value, local, allowed, violations }) {
  // Muafiyet yalniz local profilde: deploy config'i kendi kendini muaf tutamasin (yanlis pozitif kuralda duzeltilir).
  if (allowed && local) return;
  const note = allowed ? ' (allow isareti yalniz local profilde gecerli)' : '';
  const keyClass = classify(key);
  const phs = placeholders(value);
  for (const ph of phs) {
    const lit = literalFallback(ph);
    if (!lit) continue;
    const cls = placeholderNames(ph).map(classify).reduce(stricter, keyClass);
    if (cls === 'none') continue;
    // secret dosya yolu icin /run/secrets/... fallback'i kabul: yol secret degildir, standart mount noktasidir.
    if (cls === 'secret-ref' && RUN_SECRETS_RE.test(lit.literal.trim())) continue;
    violations.push({ file, line, rule: 'secret-fallback', key, detail: `\${${lit.name}:<redacted>}${note}` });
  }
  if (local || keyClass !== 'secret') return;
  const v = unquote(value);
  if (v === '' || v === '~' || v === 'null' || phs.length > 0 || RUN_SECRETS_RE.test(v)) return;
  if (/^(true|false)$/i.test(v)) return; // bayrak; secret degil
  violations.push({ file, line, rule: 'literal-secret', key, detail: `<redacted>${note}` });
}

function lintYaml(text, file) {
  const violations = [];
  const docs = [[]];
  text.split(/\r?\n/).forEach((raw, idx) => {
    if (/^---(\s|$)/.test(raw)) docs.push([]);
    else docs[docs.length - 1].push({ raw, no: idx + 1 });
  });
  for (const doc of docs) {
    const local = isLocalFile(file) || doc.some(({ raw }) =>
      /^\s*(on-profile|profiles|spring\.config\.activate\.on-profile|spring\.profiles)\s*:\s*["']?local["']?\s*$/.test(raw));
    const stack = []; // { indent, key }
    let blockIndent = -1; // | veya > block scalar icerigi: key olarak ayrisilmaz
    for (const { raw, no } of doc) {
      const indent = raw.search(/\S/);
      if (indent === -1) continue;
      if (blockIndent >= 0) {
        if (indent > blockIndent) continue;
        blockIndent = -1;
      }
      const trimmed = raw.trim();
      if (trimmed.startsWith('#')) continue;
      const allowed = ALLOW_RE.test(raw);
      const body = stripInlineComment(raw);
      // "- key: value" liste elemani: key'in girintisi "- " sonrasidir
      const m = /^(\s*(?:-\s+)?)([^\s#'"{}[\],][^:#{}]*?|"[^"]+"|'[^']+')\s*:(?:\s+(.*)|\s*)$/.exec(body);
      if (!m) {
        // anahtarsiz satir (liste skaleri vb.): mevcut yolun degeri gibi denetlenir
        const key = stack.map((s) => s.key).join('.');
        checkPair({ file, line: no, key, value: trimmed.replace(/^-\s+/, ''), local, allowed, violations });
        continue;
      }
      const keyIndent = m[1].length;
      while (stack.length && stack[stack.length - 1].indent >= keyIndent) stack.pop();
      const key = unquote(m[2]);
      const value = (m[3] || '').trim();
      const full = [...stack.map((s) => s.key), key].join('.');
      if (value === '') { stack.push({ indent: keyIndent, key }); continue; }
      if (/^[|>][+-]?\d*$/.test(value)) {
        // block scalar: icerik literal'dir (PEM gibi) — secret key'de literal-secret
        checkPair({ file, line: no, key: full, value: 'block-scalar', local, allowed, violations });
        blockIndent = keyIndent;
        continue;
      }
      const pairs = flowPairs(value);
      if (pairs) {
        for (const [k, v] of pairs) checkPair({ file, line: no, key: `${full}.${k}`, value: v, local, allowed, violations });
      } else {
        checkPair({ file, line: no, key: full, value, local, allowed, violations });
      }
    }
  }
  return violations;
}

function lintKeyValue(text, file, format) {
  const violations = [];
  const local = isLocalFile(file);
  text.split(/\r?\n/).forEach((raw, idx) => {
    const trimmed = raw.trim();
    if (trimmed === '' || trimmed.startsWith('#') || (format === 'properties' && trimmed.startsWith('!'))) return;
    const allowed = ALLOW_RE.test(raw);
    let key; let value;
    if (format === 'env') {
      const m = /^(?:export\s+)?([A-Za-z_][A-Za-z0-9_.-]*)\s*=(.*)$/.exec(trimmed);
      if (!m) return;
      key = m[1];
      value = stripInlineComment(m[2]);
    } else {
      const m = /^([^=:\s]+)\s*[=:\s]\s*(.*)$/.exec(trimmed);
      if (!m) return;
      key = m[1];
      // .properties satir ici yorum tanimaz; yalniz lint isaretini degerden ayiririz
      value = m[2].replace(/\s#\s*lint:allow-secret-fallback.*$/, '');
    }
    checkPair({ file, line: idx + 1, key, value: value.trim(), local, allowed, violations });
  });
  return violations;
}

/** Metni verilen bicimde denetler (test ve dosya yolu icin ortak). */
function lintText(text, file, format = formatOf(file)) {
  if (format === 'yaml') return lintYaml(text, file);
  if (format === 'env' || format === 'properties') return lintKeyValue(text, file, format);
  throw new Error(`desteklenmeyen dosya turu: ${file}`);
}

/** Dosyalari toplu denetler; okunamayan/desteklenmeyen dosyalar errors'a gider (fail-closed). */
function lintFiles(files) {
  const violations = [];
  const errors = [];
  for (const file of files) {
    const format = formatOf(file);
    if (!format) { errors.push({ file, message: 'desteklenmeyen dosya turu (.yml/.yaml/.properties/*.env*)' }); continue; }
    let text;
    try {
      text = fs.readFileSync(file, 'utf8');
    } catch (e) {
      errors.push({ file, message: e.code || e.message });
      continue;
    }
    violations.push(...lintText(text, file, format));
  }
  return { violations, errors };
}

function main(argv) {
  const files = argv.filter((a) => a.length > 0);
  if (files.length === 0) {
    console.error('Kullanim: node scripts/config-lint.js <dosya>...   (0 uygun, 1 ihlal, 3 dogrulanamadi)');
    return 3;
  }
  const { violations, errors } = lintFiles(files);
  for (const v of violations) console.error(`${v.file}:${v.line}: [${v.rule}] ${v.key} = ${v.detail}`);
  for (const e of errors) console.error(`${e.file}: DOGRULANAMADI — ${e.message}`);
  if (errors.length > 0) {
    console.error(`config-lint: DOGRULANAMADI — ${errors.length} dosya okunamadi, ${violations.length} ihlal`);
    return 3;
  }
  if (violations.length > 0) {
    console.error(`config-lint: IHLAL — ${violations.length} bulgu (${files.length} dosya). Duzeltme: fallback'i kaldir `
      + '(${ENV} fail-fast) ya da degeri /run/secrets/<ad> uzerinden ver; yalniz local sahte deger icin '
      + '"# lint:allow-secret-fallback <gerekce>".');
    return 1;
  }
  console.log(`config-lint: OK (${files.length} dosya)`);
  return 0;
}

module.exports = { classify, tokens, placeholders, literalFallback, formatOf, isLocalFile, lintText, lintFiles, main, ALLOW_RE };

if (require.main === module) {
  process.exit(main(process.argv.slice(2)));
}
