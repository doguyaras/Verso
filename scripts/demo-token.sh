#!/usr/bin/env bash
# Gets an access token for the "demo" user from the bundled Keycloak with the device authorization grant (RFC 8628)
# and PKCE S256 (ADR-0010): the script prints a link, you sign in in a browser, the token is written to stdout.
#
#   TOKEN="$(bash scripts/demo-token.sh)"
#   curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/v1/...
#
# The demo user's password is in secrets/SECRET_KEYCLOAK_DEMO_USER_PASSWORD. Only the access token goes to stdout,
# on purpose; instructions go to stderr. The device code and the PKCE verifier are never printed and never put on a
# command line (another local user could read them with ps during the up to 10 minutes of polling): they live in a
# private temporary folder and reach curl as files (--data-urlencode name@file).
set -euo pipefail
IDP="http://localhost:${VERSO_KEYCLOAK_PORT:-8180}/realms/verso/protocol/openid-connect"
CLIENT=verso-cli

b64url() { base64 | tr '+/' '-_' | tr -d '=\n'; }
field() { sed -n "s/.*\"$1\" *: *\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p"; }

umask 077
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
# Relative file names from here on: a native Windows curl (Git Bash) cannot open /tmp/... paths.
cd "$WORK"
head -c 48 /dev/urandom | b64url > verifier
challenge="$(openssl dgst -sha256 -binary < verifier | b64url)"

device="$(curl --silent --show-error --max-time 10 "$IDP/auth/device" --data client_id=$CLIENT \
  --data code_challenge_method=S256 --data-urlencode "code_challenge=$challenge")"
printf '%s' "$(printf '%s' "$device" | field device_code)" > device_code  # builtin printf: not in ps
[ -s device_code ] || { echo "demo-token: the IdP refused the device request: $(printf '%s' "$device" | field error)" >&2; exit 1; }
interval="$(printf '%s' "$device" | field interval)"; interval="${interval:-5}"
expires="$(printf '%s' "$device" | field expires_in)"; expires="${expires:-600}"

echo "Open this link and sign in as \"demo\" (code $(printf '%s' "$device" | field user_code)):" >&2
echo "  $(printf '%s' "$device" | field verification_uri_complete)" >&2

deadline=$(( $(date +%s) + expires ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  sleep "$interval"
  response="$(curl --silent --max-time 10 "$IDP/token" --data client_id=$CLIENT \
    --data grant_type=urn:ietf:params:oauth:grant-type:device_code \
    --data-urlencode "device_code@device_code" --data-urlencode "code_verifier@verifier")"
  token="$(printf '%s' "$response" | field access_token)"
  if [ -n "$token" ]; then printf '%s\n' "$token"; exit 0; fi
  case "$(printf '%s' "$response" | field error)" in
    authorization_pending) ;;
    slow_down) interval=$((interval + 5)) ;;
    *) echo "demo-token: $(printf '%s' "$response" | field error)" >&2; exit 1 ;;
  esac
done
echo "demo-token: the device code expired before sign-in" >&2
exit 1
