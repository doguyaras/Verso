#!/bin/sh
# One-shot model download (ADR-0011, llm-rules 8.2): runs a private Ollama server on 127.0.0.1 inside this container,
# pulls every model of OLLAMA_PULL ("name:tag@sha256:digest" pairs, separated by whitespace) into the shared volume, and
# checks each model layer against its pinned digest. The long-running "ollama" service never pulls; it starts only
# after this container exited 0. A model already in the volume with the right digest is not downloaded again, so a
# restart works offline.
set -eu
: "${OLLAMA_PULL:?}"
# OLLAMA_MODELS is Ollama's own setting for the model folder (default under /root/.ollama); tests point it elsewhere.
models_dir="${OLLAMA_MODELS:-/root/.ollama/models}"
server=""

manifest_of() {
  model="$1"
  echo "$models_dir/manifests/registry.ollama.ai/library/${model%%:*}/${model#*:}"
}

start_server() {
  [ -n "$server" ] && return 0
  ollama serve >/dev/null 2>&1 &
  server=$!
  trap 'kill "$server" 2>/dev/null || true' EXIT
  i=0
  until ollama list >/dev/null 2>&1; do
    i=$((i + 1))
    if [ "$i" -gt 60 ]; then echo "ollama-pull: local server did not start" >&2; exit 1; fi
    sleep 1
  done
}

for entry in $OLLAMA_PULL; do
  model="${entry%@*}"
  digest="${entry#*@}"
  case "$digest" in sha256:*) ;; *) echo "ollama-pull: $model has no pinned sha256 digest" >&2; exit 1 ;; esac
  manifest="$(manifest_of "$model")"
  if [ -f "$manifest" ] && grep -q "$digest" "$manifest"; then
    echo "ollama-pull: $model already present"
    continue
  fi
  start_server
  # Progress bars (stderr) are noise in compose logs; a failure is reported in one line.
  if ! ollama pull "$model" >/dev/null 2>&1; then
    echo "ollama-pull: download of $model failed" >&2
    exit 1
  fi
  if ! grep -q "$digest" "$manifest"; then
    echo "ollama-pull: $model does not match the pinned digest" >&2
    exit 1
  fi
  echo "ollama-pull: $model ready"
done
