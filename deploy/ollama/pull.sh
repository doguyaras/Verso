#!/bin/sh
# One-shot model download and store upgrade (ADR-0011, ADR-0033, llm-rules 8.2): runs a private Ollama server on
# 127.0.0.1 inside this container, makes sure every model of OLLAMA_PULL ("name:tag@sha256:digest" pairs, separated by
# whitespace) is in the shared volume in the current store layout, and checks each model layer against its pinned
# digest. The long-running "ollama" service never pulls and never writes: it mounts the volume read-only and starts
# only after this container exited 0.
#
# Why a pull even for a model that is on disk already: Ollama 0.40 reads models from "manifests-v2/" and, when that
# folder is empty, tries to remove and re-create it at start, which fails on a read-only volume. A pull writes the v2
# manifest and reuses the blobs that are already there, so a volume written by 0.35 is migrated here, where the volume
# is writable, instead of at runtime. A model that is in v2 with the right digest is not downloaded again, so a
# restart works offline.
set -eu
: "${OLLAMA_PULL:?}"
# OLLAMA_MODELS is Ollama's own setting for the model folder (default under /root/.ollama); tests point it elsewhere.
models_dir="${OLLAMA_MODELS:-/root/.ollama/models}"
server=""

# The v2 manifest of a model (any registry host folder): "<store>/manifests-v2/<host>/library/<name>/<tag>".
has_v2_manifest() {
  model="$1"
  [ -n "$(find "$models_dir/manifests-v2" -type f -path "*/library/${model%%:*}/${model#*:}" 2>/dev/null | head -n 1)" ]
}

# The digest of the model layer Ollama will load for this model, read through Ollama itself so that it does not depend
# on the manifest layout (a v2 manifest can be a list that points to a per-runner manifest in blobs/). Prints nothing
# when the model is unknown.
layer_digest_of() {
  ollama show --modelfile "$1" 2>/dev/null \
    | sed -n 's|^FROM .*/sha256-\([0-9a-f]\{64\}\)$|sha256:\1|p' | head -n 1 || true
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
  # A full 64-hex digest, compared as a whole string: a prefix or a fragment must not pass (phase 5 review S3).
  if ! printf '%s' "$digest" | grep -Eq '^sha256:[0-9a-f]{64}$'; then
    echo "ollama-pull: $model has no pinned sha256 digest" >&2
    exit 1
  fi
  start_server
  if has_v2_manifest "$model" && [ "$(layer_digest_of "$model")" = "$digest" ]; then
    echo "ollama-pull: $model already present"
    continue
  fi
  # Progress bars (stderr) are noise in compose logs; a failure is reported in one line.
  if ! ollama pull "$model" >/dev/null 2>&1; then
    echo "ollama-pull: download of $model failed" >&2
    exit 1
  fi
  if ! has_v2_manifest "$model" || [ "$(layer_digest_of "$model")" != "$digest" ]; then
    echo "ollama-pull: $model does not match the pinned digest" >&2
    exit 1
  fi
  echo "ollama-pull: $model ready"
done
