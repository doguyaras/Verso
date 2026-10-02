#!/bin/sh
# One-shot model download (ADR-0011, llm-rules 8.2): runs a private Ollama server on 127.0.0.1 inside this container,
# pulls the embedding model into the shared volume, and checks the model layer against the pinned digest. The
# long-running "ollama" service never pulls; it starts only after this container exited 0. When the model is already
# in the volume with the right digest nothing is downloaded, so a restart works offline.
set -eu
: "${OLLAMA_MODEL:?}" "${OLLAMA_MODEL_DIGEST:?}"
name="${OLLAMA_MODEL%%:*}"
tag="${OLLAMA_MODEL#*:}"
manifest="/root/.ollama/models/manifests/registry.ollama.ai/library/$name/$tag"

if [ -f "$manifest" ] && grep -q "$OLLAMA_MODEL_DIGEST" "$manifest"; then
  echo "ollama-pull: $OLLAMA_MODEL already present"
  exit 0
fi

ollama serve >/dev/null 2>&1 &
server=$!
trap 'kill "$server" 2>/dev/null || true' EXIT
i=0
until ollama list >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "$i" -gt 60 ]; then echo "ollama-pull: local server did not start" >&2; exit 1; fi
  sleep 1
done
# Progress bars (stderr) are noise in compose logs; a failure is reported in one line.
if ! ollama pull "$OLLAMA_MODEL" >/dev/null 2>&1; then
  echo "ollama-pull: download of $OLLAMA_MODEL failed" >&2
  exit 1
fi
if ! grep -q "$OLLAMA_MODEL_DIGEST" "$manifest"; then
  echo "ollama-pull: $OLLAMA_MODEL does not match the pinned digest" >&2
  exit 1
fi
echo "ollama-pull: $OLLAMA_MODEL ready"
