#!/usr/bin/env bash
# End-to-end ingestion check on the running compose stack (ADR-0011): a real token from the bundled Keycloak, a real
# upload, the worker with the real Ollama bge-m3 model, then deletion. Checks in the database that the PDF was dropped
# after parsing, that every vector has 1024 dimensions and names its model, and that deletion leaves nothing behind.
#
#   bash scripts/ingest-smoke.sh      # after: bash scripts/dev-secrets.sh && docker compose up -d --build --wait
#
# Prints ids, statuses and counts only; never a token, a secret or document text.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Relative paths from here on: a native Windows curl (Git Bash) cannot open /c/... paths.
cd "$ROOT"
IDP="http://localhost:${VERSO_KEYCLOAK_PORT:-8180}/realms/verso/protocol/openid-connect"
API="http://localhost:${VERSO_HTTP_PORT:-8080}"
PDF="scripts/fixtures/smoke.pdf"
MODEL="bge-m3:567m"
TIMEOUT="${INGEST_SMOKE_TIMEOUT:-300}"

field() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p"; }
psql_value() { docker compose exec -T postgres psql -X -q -t -A -U postgres -d verso -c "$1" | tr -d '\r'; }
die() { echo "ingest-smoke: FAIL $1" >&2; exit 1; }

token() {
  curl --silent --show-error --max-time 10 "$IDP/token" --data grant_type=client_credentials --data client_id=verso-ci \
    --data-urlencode "client_secret@secrets/SECRET_KEYCLOAK_CI_CLIENT_SECRET" | field access_token
}
[ -n "$(token)" ] || die "no access token from the IdP"
# A fresh token per call (the access token lives 300 s, as long as the wait below); it reaches curl on stdin
# (--header @-), never in an argument list or a file (test review T19).
call() { local t; t="$(token)"; curl --silent --max-time 30 --header @- "$@" <<<"Authorization: Bearer $t"; }
uuid() { head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n' | sed -E 's/(.{8})(.{4})(.{4})(.{4})(.{12})/\1-\2-\3-\4-\5/'; }

created="$(call --header "X-Idempotency-Key: $(uuid)" --form "file=@$PDF;type=application/pdf" \
  --write-out '\n%{http_code}' "$API/v1/documents")"
code="${created##*$'\n'}"
[ "$code" = 201 ] || die "upload: expected 201, got $code"
id="$(printf '%s' "${created%$'\n'*}" | field id)"
[ -n "$id" ] || die "upload: no document id"
echo "ingest-smoke: uploaded documentId=$id"

deadline=$(( $(date +%s) + TIMEOUT ))
status=PENDING
while [ "$(date +%s)" -lt "$deadline" ]; do
  body="$(call "$API/v1/documents/$id")"
  status="$(printf '%s' "$body" | field status)"
  case "$status" in READY|FAILED) break ;; esac
  sleep 3
done
echo "ingest-smoke: status=$status pages=$(printf '%s' "$body" | field pageCount) chunks=$(printf '%s' "$body" | field chunkCount)"
[ "$status" = READY ] || die "document did not become READY (status=$status, reason=$(printf '%s' "$body" | field failureReason))"
[ "$(printf '%s' "$body" | field pageCount)" = 2 ] || die "expected 2 pages"

[ "$(psql_value "SELECT count(*) FROM document.document_file WHERE document_id = '$id'")" = 0 ] \
  || die "the PDF is still stored after parsing"
[ "$(psql_value "SELECT count(*) FROM document.document_chunk WHERE document_id = '$id' AND (extensions.vector_dims(embedding) <> 1024 OR embedding_model <> '$MODEL')")" = 0 ] \
  || die "a chunk has another dimension or model"
[ "$(psql_value "SELECT count(*) FROM document.document_chunk WHERE document_id = '$id'")" -ge 2 ] \
  || die "expected at least one chunk per page"

code="$(call --output /dev/null --write-out "%{http_code}" -X DELETE "$API/v1/documents/$id")"
[ "$code" = 204 ] || die "delete: expected 204, got $code"
code="$(call --output /dev/null --write-out "%{http_code}" "$API/v1/documents/$id")"
[ "$code" = 404 ] || die "after delete: expected 404, got $code"
[ "$(psql_value "SELECT count(*) FROM document.document_page WHERE document_id = '$id'") $(psql_value "SELECT count(*) FROM document.document_chunk WHERE document_id = '$id'")" = "0 0" ] \
  || die "pages or chunks left after delete"
echo "ingest-smoke: OK"
