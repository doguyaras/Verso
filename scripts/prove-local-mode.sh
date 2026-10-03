#!/usr/bin/env bash
# Proves the local-mode promise on the running compose stack (ADR-0006 decision 1, ADR-0013): nothing of Verso can
# reach the internet, and the application says so on every response.
#   1. The application and the model server are attached to internal networks only (no gateway).
#   2. From the application's own network namespace: a public address does not answer, a public name does not resolve,
#      while the database next to it does answer (the probe itself works). The same for the model server.
#   3. Responses carry X-Rag-Mode: local, the application's (401) and the edge proxy's own (400, 413) alike, and the
#      proxy's own refusals are the common error envelope.
# The startup check (a cloud provider or a public model address in local mode stops the application) and the outbound
# address filter are proven by AiModeCheckTest and OllamaChatClientTest.
#
#   bash scripts/prove-local-mode.sh      # after: bash scripts/dev-secrets.sh && docker compose up -d --build --wait
#
# The probe runs in a throwaway container from the stack's own Ollama image (bash, getent), joined to the network
# namespace of the container under test; nothing is installed and nothing is pulled.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export MSYS_NO_PATHCONV=1   # Git Bash on Windows would rewrite /dev/tcp in the probe's arguments
API="http://localhost:${VERSO_HTTP_PORT:-8080}"
failed=0
pass() { echo "prove-local-mode: PASS $1"; }
fail() { echo "prove-local-mode: FAIL $1" >&2; failed=1; }

container() { docker compose ps -q "$1" | head -n 1; }
app="$(container verso-app)"; ollama="$(container ollama)"
[ -n "$app" ] && [ -n "$ollama" ] || { echo "prove-local-mode: the stack is not running" >&2; exit 3; }
probe_image="$(docker inspect -f '{{.Config.Image}}' "$ollama")"

# 1. Networks: every network of the container is internal (the database holds every document's text).
for name in verso-app ollama postgres; do
  id="$(container "$name")"
  networks="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$id")"
  for network in $networks; do
    if [ "$(docker network inspect -f '{{.Internal}}' "$network")" = true ]; then
      pass "$name: network $network is internal"
    else
      fail "$name: network $network has a gateway to the outside"
    fi
  done
done

# 2. Reachability from inside: probe <container> <command>; exit 0 = reachable.
probe() {
  docker run --rm --network "container:$1" --read-only --cap-drop ALL --security-opt no-new-privileges:true \
    --entrypoint bash "$probe_image" -c "$2" >/dev/null 2>&1
}
tcp() { echo "timeout 5 bash -c 'exec 3<>/dev/tcp/$1/$2'"; }
for target in "verso-app:$app" "ollama:$ollama"; do
  name="${target%%:*}"; id="${target#*:}"
  if probe "$id" "$(tcp 1.1.1.1 443)"; then fail "$name: 1.1.1.1:443 answered"; else pass "$name: 1.1.1.1:443 unreachable"; fi
  if probe "$id" "timeout 5 getent hosts example.com"; then fail "$name: example.com resolved"; else pass "$name: example.com does not resolve"; fi
done
# Positive controls: the probe can connect and resolve where the stack allows it, so a failure above is the network's
# doing, not a broken probe (an image without getent would otherwise "prove" anything; phase 6 review F8).
if probe "$app" "timeout 5 getent hosts postgres"; then pass "verso-app: control, postgres resolves"; else fail "verso-app: control probe could not resolve postgres"; fi
if probe "$app" "$(tcp postgres 5432)"; then pass "verso-app: control, postgres:5432 answers"; else fail "verso-app: control probe could not reach postgres"; fi
if probe "$ollama" "$(tcp 127.0.0.1 11434)"; then pass "ollama: control, its own port answers"; else fail "ollama: control probe could not reach its own port"; fi

# 3. The mode on the wire (no token needed: the header is on the 401 too). curl failing is a FAIL line, not an exit.
check() { # check <label> <expected status> <expected code or -> <curl arguments...>
  local label="$1" want="$2" code="$3"; shift 3
  local out; out="$(curl --silent --max-time 30 --dump-header - "$@" "$API/v1/documents" 2>/dev/null | tr -d '\r' || true)"
  local status mode; status="$(printf '%s' "$out" | sed -n '1s/^HTTP\/[0-9.]* \([0-9]*\).*/\1/p')"
  mode="$(printf '%s' "$out" | sed -n 's/^[Xx]-[Rr]ag-[Mm]ode: *//p' | head -n 1)"
  if [ "$status" != "$want" ]; then fail "$label: status ${status:-none}, expected $want"; return; fi
  if [ "$mode" != local ]; then fail "$label: X-Rag-Mode is '${mode:-missing}'"; return; fi
  if [ "$code" != - ] && ! printf '%s' "$out" | grep -q "\"code\":$code"; then fail "$label: no envelope with code $code"; return; fi
  pass "$label: $status, X-Rag-Mode local"
}
check "application 401" 401 -
check "edge proxy 413 (23 MB body)" 413 90014 -X POST -H "Content-Type: application/octet-stream" \
  --data-binary @<(head -c 24117248 /dev/zero)
check "edge proxy 400 (oversized header)" 400 90004 -H "X-Probe: $(head -c 20000 /dev/zero | tr '\0' 'a')"

[ "$failed" = 0 ] && echo "prove-local-mode: OK" || { echo "prove-local-mode: FAILED" >&2; exit 1; }
