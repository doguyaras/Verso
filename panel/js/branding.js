// The installation's branding file (ADR-0021): /branding.json, served by the edge proxy from a read-only mount
// (deploy/branding/), read once at startup before the first render and merged over the defaults in config.js. The file
// is written by the installation team, but it is still input: it is checked strictly here (known keys only, capped
// strings, at most six suggestions, the showcase's shape, accent colours that keep their contrast) and on any problem
// the whole file is ignored with one console warning, so a typo never half-brands the panel or breaks it.
import { DEPLOYMENT_DEFAULTS, useDeployment } from './config.js';
import { PHASES } from './screens/setup.js';

export const BRANDING_URL = '/branding.json';
/** The file is a few hundred bytes; anything this large is a mistake, not branding. */
export const MAX_BYTES = 16 * 1024;
export const MAX_SUGGESTIONS = 6;
/** A slow edge must not keep the panel blank: after this the defaults are used. */
const TIMEOUT_MS = 3000;

/** Longest accepted text per field, in characters; the layouts were checked with these lengths in mind. */
export const LIMITS = Object.freeze({
  productName: 40, client: 80, team: 160, label: 80, question: 200, answer: 600, source: 120, suggestion: 200,
});

const KEYS = Object.freeze(['productName', 'client', 'logo', 'phase', 'pilotGroup', 'team', 'accent', 'showcase', 'suggestions']);
const SHOWCASE_KEYS = Object.freeze(['label', 'question', 'answer', 'source']);
const LANGS = Object.freeze(['tr', 'en']);
const THEMES = Object.freeze(['light', 'dark']);

// Only files the edge serves from the branding mount (deploy/edge/nginx.conf, the same pattern): a path on this origin
// (CSP img-src 'self'), no directories, no "..", one of the image types the proxy names.
export const LOGO_PATTERN = /^\/branding\/[A-Za-z0-9][A-Za-z0-9_-]{0,63}\.(svg|png|webp)$/;
const HEX = /^#[0-9a-f]{6}$/i;
// Control and format characters (bidi overrides and the like) could make a label read as something it is not.
const HIDDEN = /[\p{Cc}\p{Cf}]/u;

/**
 * The accent tokens an installation may override, and the contrast each must keep (WCAG 2.2: 4.5:1 for text, 3:1 for
 * UI parts). "against" lists the backgrounds it is drawn on; "label" a text colour drawn on top of it.
 */
export const ACCENT_TOKENS = Object.freeze({
  'fg-accent': { min: 4.5, against: ['bg-canvas', 'bg-surface', 'bg-sidebar', 'accent-bg-subtle', 'sidebar-surface', 'sidebar-subtle'] },
  'accent-fill': { min: 3, against: ['bg-canvas', 'bg-surface', 'bg-sidebar'] },
  'accent-emphasis': { min: 3, against: ['bg-canvas', 'bg-surface'], label: 'fg-on-emphasis' },
  'accent-emphasis-hover': { min: 3, against: ['bg-canvas', 'bg-surface'], label: 'fg-on-emphasis' },
  'focus-ring': { min: 3, against: ['bg-canvas', 'bg-surface', 'bg-sidebar'] },
});

/**
 * The resolved background tokens of panel.css per theme (the menu redefines its surface and subtle accent); the
 * branding test checks them against the stylesheet, so a palette change cannot leave this table behind.
 */
export const THEME_COLOURS = Object.freeze({
  light: Object.freeze({ 'bg-canvas': '#f4efe4', 'bg-surface': '#fbf8f1', 'bg-sidebar': '#ece4d3', 'accent-bg-subtle': '#f3e7c9',
    'sidebar-surface': '#f8f3e9', 'sidebar-subtle': '#fbf8f1', 'fg-on-emphasis': '#f3e3bc' }),
  dark: Object.freeze({ 'bg-canvas': '#131216', 'bg-surface': '#1d1c21', 'bg-sidebar': '#0d0c10', 'accent-bg-subtle': '#2e2716',
    'sidebar-surface': '#18171b', 'sidebar-subtle': '#222127', 'fg-on-emphasis': '#2a1e06' }),
});

/** WCAG relative luminance of #rrggbb. */
export function luminance(hex) {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** WCAG contrast ratio of two #rrggbb colours, 1 to 21. */
export function contrast(a, b) {
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
}

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

function unknownKeys(object, allowed, path, errors) {
  for (const key of Object.keys(object)) if (!allowed.includes(key)) errors.push(`${path}${key} is not a known key`);
}

function text(value, path, max, errors, { nullable = false } = {}) {
  if (value === null && nullable) return null;
  if (typeof value !== 'string') {
    errors.push(`${path} must be a string${nullable ? ' or null' : ''}`);
    return undefined;
  }
  const trimmed = value.trim();
  if (!trimmed) errors.push(`${path} is empty`);
  else if (trimmed.length > max) errors.push(`${path} is longer than ${max} characters`);
  else if (HIDDEN.test(trimmed)) errors.push(`${path} contains control or bidi characters`);
  else return trimmed;
  return undefined;
}

function accentOf(value, errors) {
  if (value === null) return null;
  if (!isObject(value)) {
    errors.push('accent must be an object with "light" and/or "dark", or null');
    return undefined;
  }
  unknownKeys(value, THEMES, 'accent.', errors);
  const result = {};
  for (const theme of THEMES) {
    if (value[theme] === undefined) continue;
    const tokens = value[theme];
    if (!isObject(tokens)) {
      errors.push(`accent.${theme} must be an object of accent tokens`);
      continue;
    }
    unknownKeys(tokens, Object.keys(ACCENT_TOKENS), `accent.${theme}.`, errors);
    const colours = THEME_COLOURS[theme];
    const accepted = {};
    for (const [token, rule] of Object.entries(ACCENT_TOKENS)) {
      const colour = tokens[token];
      if (colour === undefined) continue;
      const path = `accent.${theme}.${token}`;
      if (typeof colour !== 'string' || !HEX.test(colour)) {
        errors.push(`${path} must be a colour written as #rrggbb`);
        continue;
      }
      let ok = true;
      for (const background of rule.against) {
        const ratio = contrast(colour, colours[background]);
        if (ratio < rule.min) {
          errors.push(`${path} has ${ratio.toFixed(2)}:1 against ${background}, needs ${rule.min}:1`);
          ok = false;
        }
      }
      if (rule.label) {
        const ratio = contrast(colour, colours[rule.label]);
        if (ratio < 4.5) {
          errors.push(`${path} has ${ratio.toFixed(2)}:1 under its label (${rule.label}), needs 4.5:1`);
          ok = false;
        }
      }
      if (ok) accepted[token] = colour.toLowerCase();
    }
    result[theme] = Object.freeze(accepted);
  }
  return Object.freeze(result);
}

function showcaseOf(value, errors) {
  if (!isObject(value)) {
    errors.push('showcase must be an object with "tr" and "en"');
    return undefined;
  }
  unknownKeys(value, LANGS, 'showcase.', errors);
  const result = {};
  for (const lang of LANGS) {
    const example = value[lang];
    if (!isObject(example)) {
      errors.push(`showcase.${lang} must be an object with ${SHOWCASE_KEYS.join(', ')}`);
      continue;
    }
    unknownKeys(example, SHOWCASE_KEYS, `showcase.${lang}.`, errors);
    const entry = {};
    for (const key of SHOWCASE_KEYS) entry[key] = text(example[key], `showcase.${lang}.${key}`, LIMITS[key], errors);
    result[lang] = Object.freeze(entry);
  }
  return Object.freeze(result);
}

function suggestionsOf(value, errors) {
  if (!Array.isArray(value)) {
    errors.push('suggestions must be a list of questions');
    return undefined;
  }
  if (value.length === 0) errors.push('suggestions is empty');
  if (value.length > MAX_SUGGESTIONS) errors.push(`suggestions has ${value.length} questions, at most ${MAX_SUGGESTIONS}`);
  return Object.freeze(value.map((question, i) => text(question, `suggestions[${i}]`, LIMITS.suggestion, errors)));
}

/**
 * Checks a parsed branding file and merges it over the defaults. Returns { deployment } (frozen) when every field is
 * valid, or { errors } listing each problem by its path; the caller then keeps the defaults whole.
 */
export function validateBranding(raw, defaults = DEPLOYMENT_DEFAULTS) {
  const errors = [];
  if (!isObject(raw)) return { errors: ['the file must hold a JSON object'] };
  unknownKeys(raw, KEYS, '', errors);
  const merged = { ...defaults };
  const has = (key) => Object.hasOwn(raw, key);
  if (has('productName')) merged.productName = text(raw.productName, 'productName', LIMITS.productName, errors);
  if (has('client')) merged.client = text(raw.client, 'client', LIMITS.client, errors, { nullable: true });
  if (has('team')) merged.team = text(raw.team, 'team', LIMITS.team, errors, { nullable: true });
  if (has('logo')) {
    if (raw.logo === null) merged.logo = null;
    else if (typeof raw.logo !== 'string' || !LOGO_PATTERN.test(raw.logo)) {
      errors.push('logo must be a file under /branding/ (letters, digits, "-" and "_"; .svg, .png or .webp), or null');
    } else merged.logo = raw.logo;
  }
  if (has('phase')) {
    if (PHASES.some((p) => p.id === raw.phase)) merged.phase = raw.phase;
    else errors.push(`phase must be one of ${PHASES.map((p) => p.id).join(', ')}`);
  }
  if (has('pilotGroup')) {
    if (typeof raw.pilotGroup === 'boolean') merged.pilotGroup = raw.pilotGroup;
    else errors.push('pilotGroup must be true or false');
  }
  if (has('accent')) merged.accent = accentOf(raw.accent, errors);
  if (has('showcase')) merged.showcase = showcaseOf(raw.showcase, errors);
  if (has('suggestions')) merged.suggestions = suggestionsOf(raw.suggestions, errors);
  return errors.length ? { errors } : { deployment: Object.freeze(merged) };
}

/** Reads /branding.json: { deployment } or { problem } for the warning; a missing file is no problem. */
export async function readBranding(fetchImpl, timeoutMs = TIMEOUT_MS) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetchImpl(BRANDING_URL, { cache: 'no-cache', credentials: 'omit', redirect: 'error', signal: controller.signal });
    if (response.status === 404) return { deployment: DEPLOYMENT_DEFAULTS };
    if (!response.ok) return { problem: `the server answered ${response.status}` };
    if (!/^application\/json\b/i.test(response.headers.get('content-type') ?? '')) return { problem: 'it is not served as application/json' };
    if (Number(response.headers.get('content-length') ?? 0) > MAX_BYTES) return { problem: `it is larger than ${MAX_BYTES} bytes` };
    const body = await response.text();
    if (new TextEncoder().encode(body).length > MAX_BYTES) return { problem: `it is larger than ${MAX_BYTES} bytes` };
    let raw;
    try {
      raw = JSON.parse(body);
    } catch {
      return { problem: 'it is not valid JSON' };
    }
    const checked = validateBranding(raw);
    return checked.errors ? { problem: checked.errors.join('; ') } : { deployment: checked.deployment };
  } catch (error) {
    return { problem: error?.name === 'AbortError' ? `no answer within ${timeoutMs} ms` : 'it could not be fetched' };
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Loads the branding file into CONFIG.deployment before the first render. Never throws: on any problem the defaults
 * stay and one warning names the problems (the installation team reads it in the browser console).
 */
export async function loadBranding({ fetchImpl = globalThis.fetch, warn = (message) => console.warn(message), timeoutMs = TIMEOUT_MS } = {}) {
  let result;
  try {
    result = await readBranding(fetchImpl, timeoutMs);
  } catch {
    result = { problem: 'it could not be read' };
  }
  if (result.problem) warn(`Verso: ${BRANDING_URL} was ignored, the defaults are used (ADR-0021): ${result.problem}.`);
  const deployment = result.deployment ?? DEPLOYMENT_DEFAULTS;
  useDeployment(deployment);
  return deployment;
}

/**
 * Sets the installation's accent tokens for the theme on the root element's style through the CSSOM (the CSP allows no
 * style attribute in markup), and removes those of the other theme. Called on every theme change.
 */
export function applyAccent(style, theme, accent) {
  for (const token of Object.keys(ACCENT_TOKENS)) {
    const colour = accent?.[theme]?.[token];
    if (colour) style.setProperty(`--${token}`, colour);
    else style.removeProperty(`--${token}`);
  }
}
