#!/usr/bin/env bash
# Self-test of backup.sh's failure path, without PostgreSQL: a fake psql answers the snapshot export and then hangs on
# the manifest query (as a stuck server would). backup.sh must give up after BACKUP_QUERY_TIMEOUT_SECONDS, send the
# session ROLLBACK and \q, wait for it, leave no .partial file and exit non-zero. Not part of CI; run it by hand after a
# change to backup.sh:  bash deploy/backup/selftest-abort.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin" "$work/secrets" "$work/backups" "$work/gnupg"
printf 'x\n' > "$work/secrets/SECRET_DB_BACKUP_PASSWORD"
printf 'x\n' > "$work/secrets/SECRET_BACKUP_ENCRYPTION_KEY"

# The fake psql: one snapshot id for the export, then silence; it records when it is asked to quit.
cat > "$work/bin/psql" <<EOF
#!/usr/bin/env bash
while IFS= read -r line; do
  case "\$line" in
    *pg_export_snapshot*) echo "00000003-00000001-1" ;;
    'ROLLBACK;') echo rollback >> "$work/psql.log" ;;
    '\\q') echo quit >> "$work/psql.log"; exit 0 ;;
  esac
done
EOF
chmod +x "$work/bin/psql"

set +e
PATH="$work/bin:$PATH" BACKUP_DIR="$work/backups" VERSO_SECRETS_DIR="$work/secrets" GNUPGHOME="$work/gnupg" \
  BACKUP_QUERY_TIMEOUT_SECONDS=1 \
  bash "$here/backup.sh" once > "$work/out.log" 2>&1
status=$?
set -e

fail() { echo "selftest-abort: FAILED: $*" >&2; cat "$work/out.log" >&2; exit 1; }
[ "$status" -ne 0 ] || fail "backup.sh reported success"
grep -q 'FAILED: manifest query' "$work/out.log" || fail "no manifest failure line"
[ -f "$work/psql.log" ] && grep -q '^rollback$' "$work/psql.log" && grep -q '^quit$' "$work/psql.log" \
  || fail "the snapshot session was not rolled back and quit"
[ -z "$(ls -A "$work/backups")" ] || fail "partial files left: $(ls -A "$work/backups")"
echo "selftest-abort: ok (exit=$status, session closed, no partial files)"
