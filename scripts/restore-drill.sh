#!/usr/bin/env bash
# Restore drill (reference 10.5: monthly and automatic; CI runs it weekly, .github/workflows/restore-drill.yml).
# Restores a backup into a throwaway PostgreSQL, checks row counts and grants, then runs Flyway validate against it.
#
#   bash scripts/restore-drill.sh                 # newest backup in the "backups" volume
#   BACKUP_FILE=verso-20261002T120000Z.dump.gpg bash scripts/restore-drill.sh
#
# Exit code 0 = the backup restores and matches; anything else is an alarm. The throwaway database is removed in
# every case; the running stack is not touched.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
DC=(docker compose --profile drill)
started=$(date +%s)

cleanup() { "${DC[@]}" rm --force --stop --volumes restore-db >/dev/null 2>&1 || true; }
trap cleanup EXIT
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 143' TERM

cleanup
"${DC[@]}" up --detach --wait restore-db >/dev/null
"${DC[@]}" run --rm --no-TTY restore-runner
"${DC[@]}" run --rm --no-TTY restore-flyway
echo "restore-drill: OK seconds=$(( $(date +%s) - started ))"
