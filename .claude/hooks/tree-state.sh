#!/usr/bin/env bash
# review-stamp.sh ve review-gate.sh'in ortak parcasi: calisma agacinin ICERIK kimligi.
# Gecici bir index'e tum (ignore edilmeyen) dosyalar eklenir ve `git write-tree` ile agac hash'i alinir.
# HEAD'den bagimsizdir: review sonrasi commit atmak damgayi bozmaz, icerik degistirmek bozar.
# Damga dosyasinin kendisi haric tutulur. Git yoksa "nogit".
tree_state() {
  local root="$1"
  if ! git -C "$root" rev-parse --git-dir >/dev/null 2>&1; then
    printf 'nogit'; return 0
  fi
  local idx
  idx="$(mktemp)"
  rm -f "$idx"
  GIT_INDEX_FILE="$idx" git -C "$root" add -A -- . ':(exclude).claude/.last-review-check' >/dev/null 2>&1 || true
  GIT_INDEX_FILE="$idx" git -C "$root" write-tree 2>/dev/null || printf 'unknown'
  rm -f "$idx"
}
