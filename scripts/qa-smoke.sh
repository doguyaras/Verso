#!/usr/bin/env bash
# End-to-end question answering on the running compose stack (ADR-0006, ADR-0008): a real token, a real upload, the
# worker with the real embedding model, then two questions to the real local chat model.
#   - a question about the document: 200, X-Rag-Mode local, and every citation points at the uploaded document;
#   - an unrelated question: 200 with found=false and no citation (the chat model is not asked below the threshold).
# Answer quality is not judged here (the eval set does that, phase 8): CI runs with a tiny chat model.
#
#   bash scripts/qa-smoke.sh      # after: bash scripts/dev-secrets.sh && docker compose up -d --build --wait
#
# Prints ids, statuses and counts only; never a token, a secret, a question or an answer.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"   # relative paths: a native Windows curl (Git Bash) cannot open /c/... paths
IDP="http://localhost:${VERSO_KEYCLOAK_PORT:-8180}/realms/verso/protocol/openid-connect"
API="http://localhost:${VERSO_HTTP_PORT:-8080}"
TIMEOUT="${QA_SMOKE_TIMEOUT:-240}"

field() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p"; }
die() { echo "qa-smoke: FAIL $1" >&2; exit 1; }
token() {
  curl --silent --show-error --max-time 10 "$IDP/token" --data grant_type=client_credentials --data client_id=verso-ci \
    --data-urlencode "client_secret@secrets/SECRET_KEYCLOAK_CI_CLIENT_SECRET" | field access_token
}
# A fresh token per call: the access token lives 300 s, a slow CPU model can take longer than that in total.
call() { local t; t="$(token)"; curl --silent --max-time 120 --header @- "$@" <<<"Authorization: Bearer $t"; }

created="$(call --form "file=@scripts/fixtures/smoke.pdf;type=application/pdf" --write-out '\n%{http_code}' "$API/v1/documents")"
[ "${created##*$'\n'}" = 201 ] || die "upload: expected 201, got ${created##*$'\n'}"
id="$(printf '%s' "${created%$'\n'*}" | field id)"
echo "qa-smoke: uploaded documentId=$id"

deadline=$(( $(date +%s) + TIMEOUT ))
status=PENDING
while [ "$(date +%s)" -lt "$deadline" ]; do
  status="$(call "$API/v1/documents/$id" | field status)"
  case "$status" in READY|FAILED) break ;; esac
  sleep 3
done
[ "$status" = READY ] || die "document did not become READY (status=$status)"

ask() { call --header 'Content-Type: application/json' --dump-header qa-headers.tmp --data "{\"question\":\"$1\"}" \
  --write-out '\n%{http_code}' "$API/v1/questions"; }
trap 'rm -f qa-headers.tmp' EXIT

answer="$(ask 'How many days of annual leave does the leave policy grant per year?')"
code="${answer##*$'\n'}"; body="${answer%$'\n'*}"
[ "$code" = 200 ] || die "question: expected 200, got $code"
grep -qi '^x-rag-mode: local' qa-headers.tmp || die "question: X-Rag-Mode local missing"
printf '%s' "$body" | grep -q '"mode":"local"' || die "question: mode is not local"
cited="$(printf '%s' "$body" | grep -o '"documentId":"[^"]*"' | sort -u || true)"
if [ -n "$cited" ] && [ "$cited" != "\"documentId\":\"$id\"" ]; then die "question: a citation points elsewhere"; fi
echo "qa-smoke: related question found=$(printf '%s' "$body" | field found) citations=$(printf '%s' "$cited" | grep -c . || true)"

unrelated="$(ask 'Which planet has the most moons in the solar system?')"
code="${unrelated##*$'\n'}"; body="${unrelated%$'\n'*}"
[ "$code" = 200 ] || die "unrelated question: expected 200, got $code"
printf '%s' "$body" | grep -q '"found":false' || die "unrelated question: expected found=false"
printf '%s' "$body" | grep -q '"citations":\[\]' || die "unrelated question: expected no citation"

code="$(call --output /dev/null --write-out '%{http_code}' -X DELETE "$API/v1/documents/$id")"
[ "$code" = 204 ] || die "delete: expected 204, got $code"
echo "qa-smoke: OK"
