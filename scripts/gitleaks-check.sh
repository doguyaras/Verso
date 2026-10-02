#!/usr/bin/env bash
# gitleaks sarmalayicisi — secret taramasi (referans Bolum 15.3, 19.5). CI, pre-commit ve elle kullanim ayni komutu cagirir.
#
# Kullanim:
#   bash scripts/gitleaks-check.sh [all|history|tree|staged] [yol]  (varsayilan: all .)
#     history : gitleaks git --no-banner --redact --exit-code 1 <yol>   — TUM commit gecmisi (silinmis secret dahil)
#     tree    : gitleaks dir --no-banner --redact --exit-code 1 <yol>   — calisma agaci (commit'lenmemis dosyalar dahil)
#     all     : ikisi birden; biri bile bulursa 1
#     staged  : gitleaks git --staged <yol>  — yalniz index (commit'e girecek icerik). Pre-commit icin: calisma agaci
#               temizlenmis ama stage'lenmis secret'i yakalar, gitignore'lu dosyayi taramaz (Verso eki, ADR-0007)
#   Ortam:
#     GITLEAKS           gitleaks ikilisinin yolu (varsayilan: PATH'teki gitleaks; surum 8.19+ — git/dir alt komutlari)
#     GITLEAKS_LOG_OPTS  history modunda --log-opts (ornek: "origin/develop..HEAD"); bos = tum gecmis
#     GITLEAKS_CONFIG    gitleaks'in kendi degiskeni; verilmezse proje kokundeki .gitleaks.toml kullanilir (varsa)
#
# Cikis kodlari: 0 temiz · 1 secret bulundu · 3 dogrulanamadi (ikili yok, git deposu degil, gitleaks hatasi).
# Neden rapor dosyasina bakiliyor: gitleaks hata durumunda da (ornek: "not a git repository", kismi tarama) exit 1
# doner ve "no leaks found in partial scan" yazar. Exit kodu tek basina "secret var" ile "tarama yapilamadi"yi
# ayirmaz; bulgu JSON raporunda yoksa sonuc "dogrulanamadi" (fail-closed) sayilir, "temiz" sayilmaz.
# --redact: bulunan secret CI log'una yazilmaz (log'lar genis erisimlidir).
set -uo pipefail

MODE="${1:-all}"
TARGET="${2:-.}"
BIN="${GITLEAKS:-gitleaks}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # proje koku (scripts/..)

case "$MODE" in
  all|history|tree|staged) ;;
  *) echo "gitleaks-check: kullanim: [all|history|tree|staged] [yol]" >&2; exit 3 ;;
esac
if ! command -v "$BIN" >/dev/null 2>&1; then
  echo "gitleaks-check: DOGRULANAMADI — gitleaks bulunamadi (GITLEAKS=$BIN)" >&2
  exit 3
fi
if [ ! -e "$TARGET" ]; then
  echo "gitleaks-check: DOGRULANAMADI — yol yok: $TARGET" >&2
  exit 3
fi

COMMON=(--no-banner --redact --exit-code 1)
# Allowlist tek yerde: proje kokundeki .gitleaks.toml (alt klasor taranirken gitleaks onu kendiliginden bulmaz).
if [ -z "${GITLEAKS_CONFIG:-}" ] && [ -f "$ROOT/.gitleaks.toml" ]; then COMMON+=(--config "$ROOT/.gitleaks.toml"); fi
if [ -f "$ROOT/.gitleaksignore" ]; then COMMON+=(--gitleaks-ignore-path "$ROOT/.gitleaksignore"); fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# run <ad> <gitleaks argumanlari...> → 0 temiz, 1 bulgu, 3 dogrulanamadi
run() {
  local name="$1"; shift
  local report="$TMP/$name.json" rc
  "$BIN" "$@" "${COMMON[@]}" --report-format json --report-path "$report"
  rc=$?
  if [ "$rc" -eq 0 ]; then echo "gitleaks-check: $name OK"; return 0; fi
  if [ "$rc" -eq 1 ] && [ -s "$report" ] && grep -q '"RuleID"' "$report"; then
    echo "gitleaks-check: $name IHLAL — $(grep -c '"RuleID"' "$report") bulgu:" >&2
    grep -o '"RuleID": *"[^"]*"\|"File": *"[^"]*"\|"StartLine": *[0-9]*\|"Commit": *"[^"]*"' "$report" \
      | paste -d' ' - - - - >&2 || true
    return 1
  fi
  echo "gitleaks-check: $name DOGRULANAMADI — gitleaks exit $rc, raporda bulgu yok (tarama tamamlanmadi)" >&2
  return 3
}

worst=0
note() { if [ "$1" -eq 3 ] || { [ "$1" -eq 1 ] && [ "$worst" -ne 3 ]; }; then worst="$1"; fi; }

if [ "$MODE" = all ] || [ "$MODE" = history ]; then
  args=(git "$TARGET")
  if [ -n "${GITLEAKS_LOG_OPTS:-}" ]; then args+=(--log-opts "$GITLEAKS_LOG_OPTS"); fi
  run history "${args[@]}"; note $?
fi
if [ "$MODE" = all ] || [ "$MODE" = tree ]; then
  run tree dir "$TARGET"; note $?
fi
if [ "$MODE" = staged ]; then
  run staged git --staged "$TARGET"; note $?
fi
exit "$worst"
