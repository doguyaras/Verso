#!/usr/bin/env bash
# Claude Code PreToolUse hook (Bash): `git push` oncesi bir review skill'i BU agac uzerinde ve son 1 saatte calismis mi?
# Calismadiysa veya agacin icerigi damgadan sonra degistiyse kullaniciya sorar (permissionDecision: "ask").
# Push disi komutlarda sessizce gecer. Damga kanit degildir; zorunlu guvence CI'dir (referans Bolum 19.6).
#
# Neden ayri dosya: settings.json icine gomulu shell komutlarinda kacis hatasi kolay yapilir ve hook
# sessizce etkisiz kalir. Bu dosya `bash -n` ve ornek girdiyle test edilir:
#   echo '{"tool_input":{"command":"git push origin x"}}' | .claude/hooks/review-gate.sh
set -euo pipefail

HOOK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Push tespiti review-gate-detect.js'te (Bash veya PowerShell araci; ADR-0007 #25). Girdinin tamami node'a dosya
# gibi verilir, pipe ile kesilmez (ikinci tur B14: SIGPIPE). Tirnakli -C yolu, satir ici alias, git.cmd ve
# gh pr create/merge da push sayilir (ucuncu tur B23). Suphede SORAR (fail-safe).
DETECTED="$(node "$HOOK_DIR/review-gate-detect.js")" || DETECTED=push
if [[ "$DETECTED" != "push" ]]; then
  exit 0
fi

ROOT="${CLAUDE_PROJECT_DIR:-$PWD}"
STAMP="$ROOT/.claude/.last-review-check"
MAX_AGE=3600
NOW="$(date +%s)"
REASON="Son 1 saatte review skill'i (verso-*-review / verso-test-writer) calistirilmadi."

if [[ -f "$STAMP" ]]; then
  read -r LAST TREE_AT _ < "$STAMP" || true
  LAST="${LAST:-0}"; TREE_AT="${TREE_AT:-}"
  if [[ "$LAST" =~ ^[0-9]+$ ]] && (( NOW - LAST < MAX_AGE )); then
    # shellcheck disable=SC1091
    . "$ROOT/.claude/hooks/tree-state.sh"
    TREE_NOW="$(tree_state "$ROOT")"
    if [[ -n "$TREE_AT" && "$TREE_AT" == "$TREE_NOW" ]]; then
      exit 0
    fi
    # Eski bicim damga (yalniz epoch) veya icerik degismis
    REASON="Review skill'i calisti ama calisma agacinin icerigi o zamandan beri degisti; review damgasi bu icerik icin gecersiz."
  fi
fi

node -e 'const r=process.argv[1];process.stdout.write(JSON.stringify({hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"ask",permissionDecisionReason:r+" docs/ai/review-checklist.md\x27deki skill\x27leri calistirmadan push etmek istiyor musun?"}}))' "$REASON"
exit 0
