#!/usr/bin/env bash
# Encrypted logical backups (reference 10.5, ADR-0009). Runs in the compose "backup" service.
#
#   backup.sh loop    # default: one backup now, then every BACKUP_INTERVAL_SECONDS (RPO)
#   backup.sh once    # one backup, exit code = result (CI, manual runs)
#
# Each backup is three files in BACKUP_DIR:
#   verso-<UTC>.dump.gpg        pg_dump custom format, gpg symmetric AES256 (SECRET_BACKUP_ENCRYPTION_KEY)
#   verso-<UTC>.dump.gpg.sha256 checksum of the encrypted file
#   verso-<UTC>.manifest        exact row count per table, taken in the SAME snapshot as the dump, so the restore
#                               drill can compare without false alarms from concurrent writes
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

export PGUSER=verso_backup
PGPASSWORD="$(cat "$SECRETS/SECRET_DB_BACKUP_PASSWORD")"
export PGPASSWORD
export GNUPGHOME=/tmp/gnupg
mkdir -p -m 700 "$GNUPGHOME" "$BACKUP_DIR"
[ -s "$KEY_FILE" ] || { echo "backup: missing $KEY_FILE" >&2; exit 2; }

log() { printf '%s backup: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

# Exact counts of every user table (catalog and extension schemas excluded), "schema.table|rows", sorted.
MANIFEST_SQL="SELECT n.nspname || '.' || c.relname,
       (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', n.nspname, c.relname),
                                            false, true, '')))[1]::text
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind IN ('r', 'p')
   AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast', 'extensions')
   AND n.nspname NOT LIKE 'pg_temp%'
 ORDER BY 1;"

backup_once() {
  local started stamp base partial snapshot line
  started=$(date +%s)
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  base="$BACKUP_DIR/verso-$stamp"
  partial="$base.dump.gpg.partial"

  # One REPEATABLE READ transaction exports its snapshot; pg_dump and the row counts both read that snapshot.
  coproc SNAP { psql -X -q -t -A -v ON_ERROR_STOP=1; }
  printf 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSELECT pg_export_snapshot();\n' >&"${SNAP[1]}"
  IFS= read -r -t 60 snapshot <&"${SNAP[0]}" || { log "FAILED: no snapshot"; return 1; }
  printf '%s\nSELECT %s;\n' "$MANIFEST_SQL" "'__END__'" >&"${SNAP[1]}"
  : > "$base.manifest.partial"
  while IFS= read -r -t 600 line <&"${SNAP[0]}"; do
    [ "$line" = "__END__" ] && break
    printf '%s\n' "$line" >> "$base.manifest.partial"
  done

  if ! pg_dump --snapshot="$snapshot" --format=custom --no-password \
      | gpg --batch --yes --quiet --pinentry-mode loopback --passphrase-file "$KEY_FILE" \
            --symmetric --cipher-algo AES256 --compress-algo none --output "$partial"; then
    printf 'ROLLBACK;\n\\q\n' >&"${SNAP[1]}"; wait "$SNAP_PID" || true
    rm -f "$partial" "$base.manifest.partial"
    log "FAILED: pg_dump or encryption"
    return 1
  fi
  printf 'COMMIT;\n\\q\n' >&"${SNAP[1]}"; wait "$SNAP_PID" || true

  mv "$partial" "$base.dump.gpg"
  mv "$base.manifest.partial" "$base.manifest"
  (cd "$BACKUP_DIR" && sha256sum "$(basename "$base.dump.gpg")" > "$(basename "$base.dump.gpg").sha256")
  date -u +%Y-%m-%dT%H:%M:%SZ > "$BACKUP_DIR/last-success"

  prune
  log "ok file=$(basename "$base.dump.gpg") bytes=$(stat -c %s "$base.dump.gpg") tables=$(wc -l < "$base.manifest") seconds=$(( $(date +%s) - started ))"
}

# Retention by age, but the newest backup always stays: stopped backups must not delete the last good one.
prune() {
  local newest
  newest=$(ls -1 "$BACKUP_DIR"/verso-*.dump.gpg 2>/dev/null | sort | tail -n 1)
  find "$BACKUP_DIR" -maxdepth 1 -name 'verso-*.dump.gpg' -mtime +"$RETENTION_DAYS" -print | while IFS= read -r old; do
    [ "$old" = "$newest" ] && continue
    rm -f "$old" "$old.sha256" "${old%.dump.gpg}.manifest"
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
