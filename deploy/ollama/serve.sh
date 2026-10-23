#!/usr/bin/env bash
# Model server with warm models (ADR-0011, docs/capacity.md "Soğuk açılış"). Starts "ollama serve", then loads the
# embedding and the chat model into memory once, so the first question after a start does not pay the model load:
# measured 2-9 s for the embedding model and 14-60 s for the chat model, depending on whether the model files are
# still in the page cache. The question embedding has a 10 s budget (ADR-0008), so a cold load could fail the first
# question. OLLAMA_KEEP_ALIVE=-1 (compose) keeps them loaded afterwards; the healthcheck reports healthy only when both
# are in memory. A failed warm-up does not stop the server: models then load on first use, as before.
# bash, not sh: the image has no curl or wget, and /dev/tcp is how this script talks to the local server.
set -u
: "${OLLAMA_WARM_EMBEDDING:?}" "${OLLAMA_WARM_CHAT:?}"
# The port Ollama listens on (OLLAMA_HOST is "0.0.0.0:11434" in compose); the retry pause is shortened by the tests.
port="${OLLAMA_HOST##*:}"
port="${port:-11434}"
retry_seconds="${OLLAMA_WARM_RETRY_SECONDS:-5}"

ollama serve &
server=$!
# Stop the server cleanly on "docker stop" (the script is PID 1 and would otherwise not pass the signal on).
trap 'kill -TERM "$server" 2>/dev/null; wait "$server"; exit 0' TERM INT

# One POST to the local server; prints the HTTP status. Short JSON bodies only (no user data ever goes through here).
post() {
  # LC_ALL=C: ${#body} counts bytes, which Content-Length needs, not characters.
  local LC_ALL=C body="$2" status
  exec 3<>"/dev/tcp/127.0.0.1/$port" || return 1
  printf 'POST %s HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s' \
    "$1" "${#body}" "$body" >&3
  status=$(head -n 1 <&3 | cut -d' ' -f2)
  cat <&3 >/dev/null
  exec 3<&-
  echo "$status"
}

warm() { # name path body
  local attempt start status
  for attempt in 1 2 3; do
    start=$(date +%s%N)
    status=$(post "$2" "$3" 2>/dev/null || echo 000)
    if [ "$status" = 200 ]; then
      echo "ollama-warm: $1 loaded in $(( ($(date +%s%N) - start) / 1000000 )) ms" >&2
      return 0
    fi
    echo "ollama-warm: $1 not loaded (HTTP $status, attempt $attempt)" >&2
    sleep $((attempt * retry_seconds))
  done
  return 1
}

for _ in $(seq 1 60); do
  ollama list >/dev/null 2>&1 && break
  sleep 1
done

# keep_alive -1 on the request as well: the model stays loaded even if the server default changes.
warm "$OLLAMA_WARM_EMBEDDING" /api/embed "{\"model\":\"$OLLAMA_WARM_EMBEDDING\",\"input\":\"warm\",\"keep_alive\":-1}"
# An empty prompt loads the chat model without generating anything (Ollama API: /api/generate).
warm "$OLLAMA_WARM_CHAT" /api/generate "{\"model\":\"$OLLAMA_WARM_CHAT\",\"prompt\":\"\",\"keep_alive\":-1}"

wait "$server"
