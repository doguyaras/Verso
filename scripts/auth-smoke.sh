#!/usr/bin/env bash
# End-to-end check of the identity setup on the running compose stack (ADR-0005, ADR-0010): the bundled Keycloak
# issues an ES256 at+jwt access token for the verso-ci client, the API accepts it and refuses everything else.
#
#   bash scripts/auth-smoke.sh        # after: bash scripts/dev-secrets.sh && docker compose up --detach --wait
#
# Never prints a token or a secret: only HTTP status codes and the token header (algorithm and type, public data).
# The client secret goes to curl as a file (--data-urlencode name@file), so it is in no argument list.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# Relative: a native Windows curl (Git Bash) cannot open /c/... paths.
SECRET_FILE="secrets/SECRET_KEYCLOAK_CI_CLIENT_SECRET"
IDP="http://localhost:${VERSO_KEYCLOAK_PORT:-8180}/realms/verso/protocol/openid-connect"
API="http://localhost:${VERSO_HTTP_PORT:-8080}"
failures=0

fail() { echo "auth-smoke: FAIL $1" >&2; failures=$((failures + 1)); }
status() { curl --silent --output /dev/null --write-out '%{http_code}' --max-time 10 "$@"; }
b64url_decode() {
  local s="${1//-/+}"; s="${s//_//}"
  while [ $(( ${#s} % 4 )) -ne 0 ]; do s="$s="; done
  printf '%s' "$s" | base64 -d 2>/dev/null
}

[ -s "$SECRET_FILE" ] || { echo "auth-smoke: $SECRET_FILE missing; run scripts/dev-secrets.sh" >&2; exit 1; }

response="$(curl --silent --show-error --max-time 10 "$IDP/token" \
  --data grant_type=client_credentials --data client_id=verso-ci \
  --data-urlencode "client_secret@$SECRET_FILE")"
token="$(printf '%s' "$response" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
[ -n "$token" ] || { echo "auth-smoke: no access token from the IdP" >&2; exit 1; }

header="$(b64url_decode "${token%%.*}")"
echo "auth-smoke: token header $header"
printf '%s' "$header" | grep -Eq '"alg" *: *"ES256"' || fail "token is not ES256"
printf '%s' "$header" | grep -Eq '"typ" *: *"at[+]jwt"' || fail "token type is not at+jwt (RFC 9068)"

# The API: no token and a tampered token are 401, a valid token reaches the application (unknown path: 404).
code="$(status "$API/v1/smoke")"
[ "$code" = 401 ] || fail "no token: expected 401, got $code"
code="$(status --header @- "$API/v1/smoke" <<<"Authorization: Bearer $token")"
[ "$code" = 404 ] || fail "valid token: expected 404, got $code"
# Flip the first signature character: the last one partly encodes padding bits and may decode to the same bytes.
head="${token%.*}"; sig="${token##*.}"; [ "${sig:0:1}" = A ] && swap=B || swap=A
code="$(status --header @- "$API/v1/smoke" <<<"Authorization: Bearer $head.$swap${sig:1}")"
[ "$code" = 401 ] || fail "tampered signature: expected 401, got $code"

# No password grant (ADR-0010): the public CLI client may only use the device flow.
code="$(status "$IDP/token" --data grant_type=password --data client_id=verso-cli \
  --data username=demo --data password=not-the-password)"
[ "$code" = 400 ] || [ "$code" = 401 ] || fail "password grant: expected 400/401, got $code"
body="$(curl --silent --max-time 10 "$IDP/token" --data grant_type=password --data client_id=verso-cli \
  --data username=demo --data password=not-the-password)"
printf '%s' "$body" | grep -q 'unauthorized_client' || fail "password grant is not refused as unauthorized_client"

if [ "$failures" -ne 0 ]; then
  echo "auth-smoke: $failures check(s) failed" >&2
  exit 1
fi
echo "auth-smoke: OK"
