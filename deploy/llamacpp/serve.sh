#!/bin/sh
# Shared-tier chat model server (ADR-0051): llama.cpp's llama-server with N parallel slots and continuous batching,
# serving the same GGUF file Ollama serves. The file is not downloaded here: ollama-pull fetched it and verified its
# pinned layer digest (ADR-0033); this script reads it read-only from the ollama-models volume and checks the SHA-256 of
# its content against the same pin before the server starts. A mismatch or a missing file stops the container.
set -eu
: "${LLAMA_MODEL_DIGEST:?}" "${LLAMA_ALIAS:?}" "${LLAMA_PARALLEL:?}" "${LLAMA_CTX_PER_SLOT:?}"
KEY_FILE="${LLAMA_API_KEY_FILE:-/run/secrets/SECRET_LOCAL_MODEL_API_KEY}"
MODELS="${LLAMA_MODELS_DIR:-/models/models/blobs}"
SERVER="${LLAMA_SERVER:-/app/llama-server}"

hex="${LLAMA_MODEL_DIGEST#sha256:}"
case "$hex" in
  *[!0-9a-f]* | "") echo "llama-serve: LLAMA_MODEL_DIGEST must be sha256:<64 hex>" >&2; exit 1 ;;
esac
[ "${#hex}" -eq 64 ] || { echo "llama-serve: LLAMA_MODEL_DIGEST must be sha256:<64 hex>" >&2; exit 1; }
case "$LLAMA_PARALLEL$LLAMA_CTX_PER_SLOT" in
  *[!0-9]* | "") echo "llama-serve: LLAMA_PARALLEL and LLAMA_CTX_PER_SLOT must be positive integers" >&2; exit 1 ;;
esac
[ "$LLAMA_PARALLEL" -ge 1 ] && [ "$LLAMA_CTX_PER_SLOT" -ge 1024 ] || {
  echo "llama-serve: LLAMA_PARALLEL >= 1 and LLAMA_CTX_PER_SLOT >= 1024" >&2; exit 1; }
[ -s "$KEY_FILE" ] || { echo "llama-serve: the API key file is missing (scripts/dev-secrets.sh)" >&2; exit 1; }

model="$MODELS/sha256-$hex"
[ -f "$model" ] || { echo "llama-serve: model file not found; run ollama-pull first" >&2; exit 1; }
start=$(date +%s)
actual=$(sha256sum "$model" | cut -d' ' -f1)
if [ "$actual" != "$hex" ]; then
  echo "llama-serve: the model file does not match the pinned digest" >&2
  exit 1
fi
echo "llama-serve: model verified in $(( $(date +%s) - start )) s; $LLAMA_PARALLEL slots x $LLAMA_CTX_PER_SLOT tokens" >&2

# --parallel: slots answered at once, batched together (continuous batching is on by default). The context is shared:
# every slot gets LLAMA_CTX_PER_SLOT tokens. All layers on the GPU. No web UI, no RPC backend (a remote-code advisory
# stream exists for --rpc, research notes), no slot save/restore files. The key (a /run/secrets file) makes the
# server answer the application only, although the "models" network already admits nobody else.
exec "$SERVER" \
  --model "$model" \
  --alias "$LLAMA_ALIAS" \
  --host 0.0.0.0 --port 8080 \
  --parallel "$LLAMA_PARALLEL" \
  --ctx-size $((LLAMA_PARALLEL * LLAMA_CTX_PER_SLOT)) \
  --n-gpu-layers 999 \
  --api-key-file "$KEY_FILE" \
  --no-webui \
  --reasoning-budget 0
