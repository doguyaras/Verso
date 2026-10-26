#!/usr/bin/env node
/**
 * Lists the third-party images the monthly SBOM scan covers (ADR-0030): every `image:` in compose.yaml (including the
 * `x-*-image` YAML anchors) plus the runtime base of the Dockerfile. Our own build (`verso:`) is covered by the Maven
 * SBOM and by the release workflow, and YAML aliases repeat an anchor. Every image must be pinned by digest; an unpinned
 * one is an error, because a scan of a movable tag proves nothing about what runs later (security-rules 9).
 *
 * Usage: node scripts/sbom-images.js [repo-root]      → {"include":[{"image":"…","name":"…"}]} on stdout (GitHub matrix)
 * Exit codes: 0 ok · 1 unpinned image · 3 could not verify (file unreadable or no image found).
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const COMPOSE_IMAGE = /^\s*(?:image:|x-[a-z0-9-]+-image: &[a-z0-9-]+)\s*([^\s#]+)/gm;
const DOCKERFILE_FROM = /^FROM\s+(\S+)/gm;
const DIGEST = /@sha256:[0-9a-f]{64}$/;

function matchAll(re, text) {
  return [...text.matchAll(re)].map((m) => m[1]);
}

/**
 * Opt-in overlays whose images are scanned too (security review, ADR-0051). Only this one so far; the other overlays
 * (rerank: an 8.2 GB image) are an open item, not a decision.
 */
const OVERLAYS = ['deploy/compose.llamacpp.yaml'];

function collect(root) {
  const compose = [fs.readFileSync(path.join(root, 'compose.yaml'), 'utf8'),
    ...OVERLAYS.map((file) => fs.readFileSync(path.join(root, file), 'utf8'))].join('\n');
  const dockerfile = fs.readFileSync(path.join(root, 'Dockerfile'), 'utf8');
  const froms = matchAll(DOCKERFILE_FROM, dockerfile);
  const images = [...matchAll(COMPOSE_IMAGE, compose), froms[froms.length - 1]]
    .filter((image) => image && !image.startsWith('*') && !image.startsWith('verso:'));
  return [...new Set(images)];
}

/** "grafana/grafana:13.2.3@sha256:…" → "grafana-grafana-13.2.3": safe for an artifact name. */
function artifactName(image) {
  return image.split('@')[0].replace(/[^A-Za-z0-9.]+/g, '-').replace(/^-|-$/g, '');
}

function main(root) {
  let images;
  try { images = collect(root); } catch (e) { console.error(`could not read: ${e.message}`); return 3; }
  if (images.length < 5) { console.error(`could not verify: only ${images.length} images found`); return 3; }
  const unpinned = images.filter((image) => !DIGEST.test(image));
  if (unpinned.length) { console.error(`not pinned by digest: ${unpinned.join(', ')}`); return 1; }
  process.stdout.write(`${JSON.stringify({ include: images.map((image) => ({ image, name: artifactName(image) })) })}\n`);
  return 0;
}

if (require.main === module) process.exit(main(process.argv[2] ?? path.join(__dirname, '..')));

module.exports = { collect, artifactName, main };
