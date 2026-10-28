// Line icons drawn for the panel (24×24, stroke only). Built with createElementNS: no HTML strings, which the CSP's
// Trusted Types rule would refuse anyway (ADR-0015).

import { mark } from './signature-data.js';

const SVG = 'http://www.w3.org/2000/svg';

const SHAPES = Object.freeze({
  home: [['path', { d: 'M3 10.5 12 3l9 7.5' }], ['path', { d: 'M5.5 9v11h13V9' }], ['path', { d: 'M10 20v-6h4v6' }]],
  files: [['path', { d: 'M7 3h7l5 5v11a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z' }], ['path', { d: 'M14 3v5h5' }],
    ['path', { d: 'M9 13h6M9 17h4' }]],
  file: [['path', { d: 'M6 2.5h8.5L19.5 7.5V20a1.5 1.5 0 0 1-1.5 1.5H6A1.5 1.5 0 0 1 4.5 20V4A1.5 1.5 0 0 1 6 2.5Z' }],
    ['path', { d: 'M14.5 2.5v5h5' }]],
  chat: [['path', { d: 'M20 14.5a2 2 0 0 1-2 2H9l-4.5 4v-4H6a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2Z' }],
    ['path', { d: 'M8.5 9.5h7M8.5 12.5h4.5' }]],
  server: [['rect', { x: 3.5, y: 4, width: 17, height: 7, rx: 2 }], ['rect', { x: 3.5, y: 13, width: 17, height: 7, rx: 2 }],
    ['path', { d: 'M7.5 7.5h.01M7.5 16.5h.01M11 7.5h5M11 16.5h5' }]],
  upload: [['path', { d: 'M12 15V4' }], ['path', { d: 'm7.5 8.5 4.5-4.5 4.5 4.5' }], ['path', { d: 'M4.5 15v3.5a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V15' }]],
  search: [['circle', { cx: 11, cy: 11, r: 6.5 }], ['path', { d: 'm16 16 4.5 4.5' }]],
  trash: [['path', { d: 'M4.5 7h15' }], ['path', { d: 'M9.5 7V4.5h5V7' }], ['path', { d: 'M6.5 7l1 13h9l1-13' }], ['path', { d: 'M10.5 11v5.5M13.5 11v5.5' }]],
  x: [['path', { d: 'M6 6l12 12M18 6 6 18' }]],
  check: [['path', { d: 'm5 12.5 4.5 4.5L19 7.5' }]],
  checkCircle: [['circle', { cx: 12, cy: 12, r: 8.5 }], ['path', { d: 'm8.5 12.2 2.4 2.4 4.6-4.9' }]],
  alert: [['path', { d: 'M12 3.5 21 19.5H3Z' }], ['path', { d: 'M12 10v4.5M12 17.3h.01' }]],
  info: [['circle', { cx: 12, cy: 12, r: 8.5 }], ['path', { d: 'M12 11v5.5M12 8h.01' }]],
  clock: [['circle', { cx: 12, cy: 12, r: 8.5 }], ['path', { d: 'M12 7.5V12l3 2' }]],
  sun: [['circle', { cx: 12, cy: 12, r: 4 }], ['path', { d: 'M12 2.5v2M12 19.5v2M4.6 4.6 6 6M18 18l1.4 1.4M2.5 12h2M19.5 12h2M4.6 19.4 6 18M18 6l1.4-1.4' }]],
  moon: [['path', { d: 'M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5Z' }]],
  menu: [['path', { d: 'M4 7h16M4 12h16M4 17h16' }]],
  logout: [['path', { d: 'M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3' }], ['path', { d: 'M10 16.5 14.5 12 10 7.5M14.5 12H4' }]],
  copy: [['rect', { x: 8.5, y: 8.5, width: 11, height: 11, rx: 2 }], ['path', { d: 'M15.5 8.5V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v7.5a2 2 0 0 0 2 2h2.5' }]],
  sparkles: [['path', { d: 'M12 3.5 13.8 9 19.5 10.8 13.8 12.6 12 18.5 10.2 12.6 4.5 10.8 10.2 9Z' }], ['path', { d: 'M19 3v3M17.5 4.5h3M5 17v3M3.5 18.5h3' }]],
  send: [['path', { d: 'M4.5 12 20 4.5 13.5 20l-2.5-6.5Z' }], ['path', { d: 'M11 13.5 20 4.5' }]],
  shield: [['path', { d: 'M12 3 19.5 6v5.5c0 4.5-3.2 8.2-7.5 9.5-4.3-1.3-7.5-5-7.5-9.5V6Z' }], ['path', { d: 'm9 12 2.2 2.2L15.5 10' }]],
  cloud: [['path', { d: 'M7 18.5a4.5 4.5 0 0 1-.6-9 6 6 0 0 1 11.4 1.6A3.8 3.8 0 0 1 17 18.5Z' }]],
  cpu: [['rect', { x: 6.5, y: 6.5, width: 11, height: 11, rx: 2 }], ['rect', { x: 9.5, y: 9.5, width: 5, height: 5, rx: 1 }],
    ['path', { d: 'M9.5 3v3.5M14.5 3v3.5M9.5 17.5V21M14.5 17.5V21M3 9.5h3.5M3 14.5h3.5M17.5 9.5H21M17.5 14.5H21' }]],
  layers: [['path', { d: 'm12 3.5 8.5 4.5-8.5 4.5L3.5 8Z' }], ['path', { d: 'm3.5 12 8.5 4.5 8.5-4.5' }], ['path', { d: 'm3.5 16 8.5 4.5 8.5-4.5' }]],
  user: [['circle', { cx: 12, cy: 8.5, r: 3.8 }], ['path', { d: 'M4.5 20a7.5 7.5 0 0 1 15 0' }]],
  key: [['circle', { cx: 8, cy: 15, r: 4 }], ['path', { d: 'm11 12 8.5-8.5M16.5 6.5l2.5 2.5M14 9l2 2' }]],
  chart: [['path', { d: 'M4 20V10M10 20V4M16 20v-7M21 20H3' }]],
  external: [['path', { d: 'M14 4h6v6M20 4l-9 9' }], ['path', { d: 'M18 14v4.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 4 18.5v-11A1.5 1.5 0 0 1 5.5 6H10' }]],
  refresh: [['path', { d: 'M20 11a8 8 0 0 0-14.6-4.5L4 8' }], ['path', { d: 'M4 4v4h4' }], ['path', { d: 'M4 13a8 8 0 0 0 14.6 4.5L20 16' }], ['path', { d: 'M20 20v-4h-4' }]],
  arrowRight: [['path', { d: 'M5 12h14M13 6l6 6-6 6' }]],
  quote: [['path', { d: 'M9.5 7.5H6a1.5 1.5 0 0 0-1.5 1.5v3.5A1.5 1.5 0 0 0 6 14h3.5v-3.5A6 6 0 0 1 6 16.5' }],
    ['path', { d: 'M19.5 7.5H16a1.5 1.5 0 0 0-1.5 1.5v3.5A1.5 1.5 0 0 0 16 14h3.5v-3.5A6 6 0 0 1 16 16.5' }]],
  book: [['path', { d: 'M4.5 5.5A2 2 0 0 1 6.5 3.5H19.5v15H6.5a2 2 0 0 0-2 2Z' }], ['path', { d: 'M4.5 20.5a2 2 0 0 1 2-2h13' }]],
  sidebar: [['rect', { x: 3.5, y: 4.5, width: 17, height: 15, rx: 2 }], ['path', { d: 'M9.5 4.5v15' }], ['path', { d: 'm15.5 10-2 2 2 2' }]],
  stop: [['rect', { x: 6.5, y: 6.5, width: 11, height: 11, rx: 2 }]],
  monitor: [['rect', { x: 3.5, y: 4.5, width: 17, height: 11.5, rx: 2 }], ['path', { d: 'M9 20h6M12 16v4' }]],
  edit: [['path', { d: 'M4.5 19.5h4l10-10a2.1 2.1 0 0 0-3-3l-10 10Z' }], ['path', { d: 'm13.5 8.5 3 3' }]],
  chevronRight: [['path', { d: 'm9.5 6 6 6-6 6' }]],
  chevronDown: [['path', { d: 'm6 9.5 6 6 6-6' }]],
  download: [['path', { d: 'M12 4v11' }], ['path', { d: 'm7.5 10.5 4.5 4.5 4.5-4.5' }], ['path', { d: 'M4.5 15v3.5a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V15' }]],
  lock: [['rect', { x: 5, y: 10.5, width: 14, height: 10, rx: 2 }], ['path', { d: 'M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5' }]],
  building: [['path', { d: 'M4.5 20.5V5.5a1 1 0 0 1 1-1h8a1 1 0 0 1 1 1v15' }], ['path', { d: 'M14.5 9.5h4a1 1 0 0 1 1 1v10' }],
    ['path', { d: 'M3 20.5h18M8 8.5h3M8 12h3M8 15.5h3' }]],
  command: [['path', { d: 'M9 6.5a2.5 2.5 0 1 0-2.5 2.5H9Zm0 0v11m0-11h6m-6 11a2.5 2.5 0 1 1-2.5-2.5H9Zm0 0h6m0 0V6.5m0 11a2.5 2.5 0 1 0 2.5-2.5H15Zm0-11a2.5 2.5 0 1 1 2.5 2.5H15' }]],
  enter: [['path', { d: 'M19.5 5v7a2 2 0 0 1-2 2H6' }], ['path', { d: 'm9.5 10.5-3.5 3.5 3.5 3.5' }]],
  flag: [['path', { d: 'M5 21V4' }], ['path', { d: 'M5 4.5h11l-1.8 4 1.8 4H5' }]],
  plug: [['path', { d: 'M9 7V3' }], ['path', { d: 'M15 7V3' }], ['path', { d: 'M6.5 7h11v3.5a5.5 5.5 0 0 1-11 0V7Z' }], ['path', { d: 'M12 16v5' }]],
  thumbUp: [['path', { d: 'M3.5 10.5h4v9h-4Z' }], ['path', { d: 'M7.5 10.5 11.2 4a1.8 1.8 0 0 1 3.3 1.3l-.9 4.2h4.6a2 2 0 0 1 2 2.4l-1.3 6a2 2 0 0 1-2 1.6H7.5' }]],
  thumbDown: [['path', { d: 'M3.5 13.5h4v-9h-4Z' }], ['path', { d: 'M7.5 13.5 11.2 20a1.8 1.8 0 0 0 3.3-1.3l-.9-4.2h4.6a2 2 0 0 0 2-2.4l-1.3-6a2 2 0 0 0-2-1.6H7.5' }]],
  history: [['path', { d: 'M4 12a8 8 0 1 0 2.3-5.6L4 8.5' }], ['path', { d: 'M4 4v4.5h4.5' }], ['path', { d: 'M12 8v4.5l3 1.8' }]],
  plus: [['path', { d: 'M12 5v14M5 12h14' }]],
  bell: [['path', { d: 'M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15Z' }], ['path', { d: 'M10 20.5a2 2 0 0 0 4 0' }]],
  pulse: [['path', { d: 'M3.5 12h4l2.5-6 4 12 2.5-6h4' }]],
  map: [['path', { d: 'M3.5 6.5 9 4l6 2.5L20.5 4v13.5L15 20l-6-2.5-5.5 2.5Z' }], ['path', { d: 'M9 4v13.5M15 6.5V20' }]],
  // the "Yönetim" entry: a gear (ADR-0058 Sadeleştirme)
  settings: [['circle', { cx: 12, cy: 12, r: 3 }],
    ['path', { d: 'M12 2.5v2.2M12 19.3v2.2M4.6 4.6l1.6 1.6M17.8 17.8l1.6 1.6M2.5 12h2.2M19.3 12h2.2M4.6 19.4l1.6-1.6M17.8 6.2l1.6-1.6' }],
    ['circle', { cx: 12, cy: 12, r: 6.3 }]],
});

/**
 * The Verso mark: the "V" of the signature on a tile (the favicon, the collapsed menu, the assistant). Colours come
 * from CSS classes (logo-bg, logo-fg), so the mark follows the theme.
 */
export function logo(size = 28) {
  const { d, transform } = mark(32, 4);
  const svg = document.createElementNS(SVG, 'svg');
  svg.setAttribute('viewBox', '0 0 32 32');
  svg.setAttribute('width', String(size));
  svg.setAttribute('height', String(size));
  svg.setAttribute('class', 'logo logo-mark');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('focusable', 'false');
  for (const [tag, attributes] of [
    ['rect', { class: 'logo-bg', x: 0, y: 0, width: 32, height: 32, rx: 7 }],
    ['path', { class: 'logo-fg', d, transform }],
  ]) {
    const shape = document.createElementNS(SVG, tag);
    for (const [key, value] of Object.entries(attributes)) shape.setAttribute(key, String(value));
    svg.append(shape);
  }
  return svg;
}

/** An inline SVG icon; size "sm", "lg" or default. */
export function icon(name, size = '') {
  const svg = document.createElementNS(SVG, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('class', `icon ${size}`.trim());
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('focusable', 'false');
  for (const [tag, attributes] of SHAPES[name] ?? SHAPES.info) {
    const shape = document.createElementNS(SVG, tag);
    for (const [key, value] of Object.entries(attributes)) shape.setAttribute(key, String(value));
    svg.append(shape);
  }
  return svg;
}

export const ICON_NAMES = Object.freeze(Object.keys(SHAPES));
