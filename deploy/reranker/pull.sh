#!/bin/sh
# One-shot model download for the reranker sidecar (ADR-0043), run by the reranker-pull service of
# deploy/compose.reranker.yaml. Fetches the files TEI needs for BAAI/bge-reranker-v2-m3 (Apache-2.0) at one pinned
# revision from huggingface.co over https only, checks each against its SHA-256 and moves it into the volume only then.
# A file already present with the right checksum is not downloaded again; the TEI container itself never downloads
# (HF_HUB_OFFLINE, internal network). Needs internet only on the first run or after a revision change.
set -eu

REVISION=953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e
BASE="https://huggingface.co/BAAI/bge-reranker-v2-m3/resolve/${REVISION}"
DEST=/data/bge-reranker-v2-m3
mkdir -p "$DEST"

# file sha256 (model.safetensors and tokenizer.json: the Hub's LFS ids; the small files: measured on 2026-10-24)
while read -r file sum; do
  [ -n "$file" ] || continue
  if [ -f "$DEST/$file" ] && echo "$sum  $DEST/$file" | sha256sum -c -s; then
    echo "reranker-pull: ok $file"
    continue
  fi
  echo "reranker-pull: downloading $file"
  curl -fsSL --proto '=https' --tlsv1.2 --retry 3 -o "$DEST/.$file.part" "$BASE/$file"
  if ! echo "$sum  $DEST/.$file.part" | sha256sum -c -s; then
    rm -f "$DEST/.$file.part"
    echo "reranker-pull: checksum mismatch for $file" >&2
    exit 1
  fi
  mv "$DEST/.$file.part" "$DEST/$file"
done <<'EOF'
config.json 13dcd6c31d9fec9d1d8e158702072f62d7fa7d312a64b9fe057bec9a08cfe41a
special_tokens_map.json 8c785abebea9ae3257b61681b4e6fd8365ceafde980c21970d001e834cf10835
tokenizer.json 69564b696052886ed0ac63fa393e928384e0f8caada38c1f4864a9bfbf379c15
tokenizer_config.json 7e4c1cc848840aeccdd763458c18dd525eb0f795c992e00ebe9c28554e7db2d4
model.safetensors d9e3e081faff1eefb84019509b2f5558fd74c1a05a2c7db22f74174fcedb5286
EOF
echo "reranker-pull: done"
