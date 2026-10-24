#!/usr/bin/env bash
# Verifies the signed release image before an install or an upgrade (ADR-0030, ADR-0040, reference 18.1). Refuses to
# continue (exit non-zero) when the reference is not a digest, cosign is missing, or either check fails:
#   1. cosign verify              the signature, made keyless by release.yml of this repository for a v* tag
#   2. cosign verify-attestation  the CycloneDX SBOM attestation (--type cyclonedx), same signer
#
#   VERSO_IMAGE=ghcr.io/<owner>/verso@sha256:<64 hex> bash scripts/verify-release.sh
#   bash scripts/verify-release.sh ghcr.io/<owner>/verso@sha256:<64 hex>
#
# Settings (all optional):
#   VERSO_RELEASE_REPO     GitHub "owner/repo" whose release.yml signs (default: doguyaras/Verso). A fork that
#                          publishes its own images sets its own.
#   VERSO_VERIFY_OFFLINE   1 = cosign --offline: no Rekor lookup; the signed entry timestamp inside the signature is
#                          checked instead. Needs COSIGN_TRUSTED_ROOT (below) when the host cannot reach the TUF mirror.
#   COSIGN_TRUSTED_ROOT    path of a Sigstore trusted_root.json fetched on a connected host and carried over;
#                          passed as --trusted-root.
#   COSIGN                 the cosign binary (default: cosign on PATH).
# Air-gapped hosts: the image, its signature and its attestation are separate OCI artifacts. Mirror them together
# (cosign copy, or cosign save + cosign load) and verify against the mirror by the same digest; the signature stays
# valid because it signs the digest. See docs/runbooks/surum-yukseltme-ve-geri-alma.md "Air-gapped".
# Prints the image reference and PASS/FAIL lines only.
set -euo pipefail

IMAGE="${1:-${VERSO_IMAGE:-}}"
REPO="${VERSO_RELEASE_REPO:-doguyaras/Verso}"
COSIGN="${COSIGN:-cosign}"
# Same identity and issuer as release.yml (SIGNER_IDENTITY, OIDC_ISSUER): only that workflow, run for a v* tag.
REPO_RE="$(printf '%s' "$REPO" | sed 's/\./\\./g')"   # a dot in the repo name must not match any character
IDENTITY="^https://github\\.com/${REPO_RE}/\\.github/workflows/release\\.yml@refs/tags/v"
ISSUER="https://token.actions.githubusercontent.com"

die() { echo "verify-release: FAIL $1" >&2; exit 1; }

[ -n "$IMAGE" ] || die "no image: set VERSO_IMAGE or pass ghcr.io/<owner>/verso@sha256:<digest>"
[[ "$IMAGE" =~ ^[a-z0-9.-]+(:[0-9]+)?(/[a-z0-9._-]+)+@sha256:[0-9a-f]{64}$ ]] \
  || die "the image must be a full digest reference (name@sha256:<64 hex>), not a tag: $IMAGE"
[[ "$REPO" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]] || die "VERSO_RELEASE_REPO must be owner/repo: $REPO"
command -v "$COSIGN" >/dev/null 2>&1 || die "cosign not found (install v3.x from sigstore/cosign, or set COSIGN)"

flags=(--certificate-identity-regexp "$IDENTITY" --certificate-oidc-issuer "$ISSUER")
if [ "${VERSO_VERIFY_OFFLINE:-0}" = "1" ]; then flags+=(--offline=true); fi
if [ -n "${COSIGN_TRUSTED_ROOT:-}" ]; then
  [ -r "$COSIGN_TRUSTED_ROOT" ] || die "COSIGN_TRUSTED_ROOT is not readable"
  flags+=(--trusted-root "$COSIGN_TRUSTED_ROOT")
fi

echo "verify-release: $IMAGE (signer: $REPO release.yml, v* tags)"
"$COSIGN" verify "${flags[@]}" "$IMAGE" >/dev/null || die "signature does not verify; do not install or upgrade"
echo "verify-release: PASS signature"
"$COSIGN" verify-attestation --type cyclonedx "${flags[@]}" "$IMAGE" >/dev/null \
  || die "CycloneDX attestation does not verify; do not install or upgrade"
echo "verify-release: PASS cyclonedx attestation"
