#!/usr/bin/env bash
# Claude Code hook — iki olayda calisir (settings.json):
#  - PostToolUse(Skill): model bir review skill'ini Skill araciyla cagirdiysa,
#  - UserPromptSubmit: kullanici prompt'a /verso-*-review gibi bir slash komutu yazdiysa (bu durumda Claude Code
#    Skill aracini CAGIRMAZ; komut kullanici turuna genisletilir — gercek oturum testinde goruldu, D1).
# Damga prompt gonderildigi anda yazilir; review'in bittigini degil, baslatildigini gosterir (damga kanit degildir).
# Damga calisma agacinin icerigine baglidir: "<epoch> <tree hash>". Icerik degisince damga gecersizdir
# (review-gate.sh karsilastirir); commit atmak icerigi degistirmedigi icin damgayi bozmaz. Damga KANIT DEGILDIR; yalnizca "bir review skill'i bu agac uzerinde calisti" der.
# Zorunlu guvence CI'dir (referans Bolum 19.6).
set -euo pipefail
INPUT="$(cat)"
NAME="$(printf '%s' "$INPUT" | node -e 'let s="";process.stdin.on("data",c=>s+=c).on("end",()=>{try{const j=JSON.parse(s);const t=j.tool_input&&(j.tool_input.skill||j.tool_input.name);const m=!t&&typeof j.prompt==="string"&&j.prompt.trim().match(/^\/([\w.-]+)/);process.stdout.write(String(t||(m&&m[1])||""))}catch(e){process.stdout.write("")}})')"
case "$NAME" in
  *-review|*test-writer|*integration-doc)
    ROOT="${CLAUDE_PROJECT_DIR:-$PWD}"
    mkdir -p "$ROOT/.claude"
    # shellcheck disable=SC1091
    . "$ROOT/.claude/hooks/tree-state.sh"
    printf '%s %s\n' "$(date +%s)" "$(tree_state "$ROOT")" > "$ROOT/.claude/.last-review-check"
    ;;
esac
exit 0
