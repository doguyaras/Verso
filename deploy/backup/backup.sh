#!/usr/bin/env bash
# Encrypted logical backups (reference 10.5, ADR-0009). Runs in the compose "backup" service.
#
#   backup.sh loop    # default: one backup now, then every BACKUP_INTERVAL_SECONDS (RPO)
#   backup.sh once    # one backup, exit code = result (CI, manual runs)
#
# Each backup is four files in BACKUP_DIR:
#   verso-<UTC>.dump.gpg        pg_dump custom format, gpg symmetric AES256 (SECRET_BACKUP_ENCRYPTION_KEY)
#   verso-<UTC>.dump.gpg.sha256 checksum of the encrypted file
#   verso-<UTC>.manifest        exact row count per table, taken in the SAME snapshot as the dump, so the restore
#                               drill can compare without false alarms from concurrent writes
#   verso-<UTC>.acl             privilege fingerprint (owners, ACLs, default privileges), same snapshot: the drill
#                               proves the grants came back, not only the rows (review D1)
# The dump runs as verso_backup (pg_read_all_data, read-only). Logs carry file names, sizes and timings only: never
# table contents (llm-rules 2.1).
set -euo pipefail

MODE="${1:-loop}"
BACKUP_DIR="${BACKUP_DIR:-/backups}"
INTERVAL="${BACKUP_INTERVAL_SECONDS:-86400}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-7}"
RETRY_SECONDS="${BACKUP_RETRY_SECONDS:-300}"
SECRETS="${VERSO_SECRETS_DIR:-/run/secrets}"
KEY_FILE="$SECRETS/SECRET_BACKUP_ENCRYPTION_KEY"

for f in SECRET_DB_BACKUP_PASSWORD SECRET_BACKUP_ENCRYPTION_KEY; do
  [ -r "$SECRETS/$f" ] && [ -s "$SECRETS/$f" ] || { echo "backup: secret $f missing or unreadable" >&2; exit 2; }
done
export PGUSER=verso_backup
PGPASSWORD="$(cat "$SECRETS/SECRET_DB_BACKUP_PASSWORD")"
export PGPASSWORD
export GNUPGHOME=/tmp/gnupg
mkdir -p -m 700 "$GNUPGHOME" "$BACKUP_DIR"

# MANIFEST_SQL and ACL_SQL: one definition for backup and restore check.
# shellcheck source=queries.sh
. "$(dirname "${BASH_SOURCE[0]}")/queries.sh"

log() { printf '%s backup: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

# read_until_end <sql> <file>: runs a query in the snapshot session and writes its rows to <file>.
read_until_end() {
  local line
  printf '%s\nSELECT %s;\n' "$1" "'__END__'" >&"${SNAP[1]}"
  : > "$2"
  while IFS= read -r -t 600 line <&"${SNAP[0]}"; do
    [ "$line" = "__END__" ] && return 0
    printf '%s\n' "$line" >> "$2"
  done
  log "FAILED: query output ended early"
  return 1
}

backup_once() {
  local started stamp base partial snapshot
  started=$(date +%s)
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  base="$BACKUP_DIR/verso-$stamp"
  partial="$base.dump.gpg.partial"

  # One REPEATABLE READ transaction exports its snapshot; pg_dump, the row counts and the privilege fingerprint all
  # read that snapshot.
  coproc SNAP { psql -X -q -t -A -v ON_ERROR_STOP=1; }
  printf 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSELECT pg_export_snapshot();\n' >&"${SNAP[1]}"
  IFS= read -r -t 60 snapshot <&"${SNAP[0]}" || { log "FAILED: no snapshot"; return 1; }
  read_until_end "$MANIFEST_SQL" "$base.manifest.partial" || return 1
  read_until_end "$ACL_SQL" "$base.acl.partial" || return 1

  if ! pg_dump --snapshot="$snapshot" --format=custom --no-password \
      | gpg --batch --yes --quiet --pinentry-mode loopback --passphrase-file "$KEY_FILE" \
            --symmetric --cipher-algo AES256 --compress-algo none --output "$partial"; then
    printf 'ROLLBACK;\n\\q\n' >&"${SNAP[1]}"; wait "$SNAP_PID" || true
    rm -f "$partial" "$base.manifest.partial" "$base.acl.partial"
    log "FAILED: pg_dump or encryption"
    return 1
  fi
  printf 'COMMIT;\n\\q\n' >&"${SNAP[1]}"; wait "$SNAP_PID" || true

  mv "$partial" "$base.dump.gpg"
  mv "$base.manifest.partial" "$base.manifest"
  mv "$base.acl.partial" "$base.acl"
  (cd "$BACKUP_DIR" && sha256sum "$(basename "$base.dump.gpg")" > "$(basename "$base.dump.gpg").sha256")
  date -u +%Y-%m-%dT%H:%M:%SZ > "$BACKUP_DIR/last-success"

  prune
  log "ok file=$(basename "$base.dump.gpg") bytes=$(stat -c %s "$base.dump.gpg") tables=$(wc -l < "$base.manifest") acl_entries=$(wc -l < "$base.acl") seconds=$(( $(date +%s) - started ))"
}

# Retention by age, but the newest backup always stays: stopped backups must not delete the last good one.
prune() {
  local newest
  newest=$(ls -1 "$BACKUP_DIR"/verso-*.dump.gpg 2>/dev/null | sort | tail -n 1)
  find "$BACKUP_DIR" -maxdepth 1 -name 'verso-*.dump.gpg' -mtime +"$RETENTION_DAYS" -print | while IFS= read -r old; do
    [ "$old" = "$newest" ] && continue
    rm -f "$old" "$old.sha256" "${old%.dump.gpg}.manifest" "${old%.dump.gpg}.acl"
    log "pruned file=$(basename "$old")"
  done
  rm -f "$BACKUP_DIR"/*.partial
}

case "$MODE" in
  once) backup_once ;;
  loop)
    log "schedule interval=${INTERVAL}s retention=${RETENTION_DAYS}d"
    while :; do
      if backup_once; then sleep "$INTERVAL"; else sleep "$RETRY_SECONDS"; fi
    done
    ;;
  *) echo "usage: backup.sh loop|once" >&2; exit 64 ;;
esac
