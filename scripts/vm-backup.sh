#!/usr/bin/env bash
# Nightly logical backup of the bejo database. Runs ON THE VM, from cron (installed by
# scripts/init-vm.sh). Talks to the bejo-postgres container directly, so it needs nothing
# but docker access.
#
# A backup that only lives on the VM does not survive losing the VM, and an unencrypted one is a
# full copy of every merchant's data. So, optionally, in ~/bejo/backup.env (or the file named by
# BACKUP_ENV_FILE) — kept on the VM, never in git:
#
#   BACKUP_PASSPHRASE_FILE=/home/deploy/bejo/backup.pass   # encrypt with AES-256 (openssl, PBKDF2);
#                                                          # chmod 600, and keep a copy OFF the VM
#   BACKUP_RCLONE_REMOTE=cos:my-bucket/bejo-db             # also copy it off the VM with rclone
#                                                          # (needs rclone installed + `rclone config`)
#   KEEP_DAYS=7                                            # local retention
#
# Restore (plain):
#   gunzip -c bejo-<ts>.sql.gz | docker exec -i bejo-postgres \
#     sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
# Restore (encrypted):
#   openssl enc -d -aes-256-cbc -pbkdf2 -pass file:backup.pass -in bejo-<ts>.sql.gz.enc \
#     | gunzip | docker exec -i bejo-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
#
# Environment: BACKUP_DIR (default ~/bejo/backups).

set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-$HOME/bejo/backups}"
BACKUP_ENV_FILE="${BACKUP_ENV_FILE:-$(dirname "$BACKUP_DIR")/backup.env}"
if [[ -f "$BACKUP_ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$BACKUP_ENV_FILE"
fi
KEEP_DAYS="${KEEP_DAYS:-7}"
PASSPHRASE_FILE="${BACKUP_PASSPHRASE_FILE:-}"
RCLONE_REMOTE="${BACKUP_RCLONE_REMOTE:-}"

if [[ -n "$PASSPHRASE_FILE" && ! -s "$PASSPHRASE_FILE" ]]; then
  echo "ERROR: BACKUP_PASSPHRASE_FILE=$PASSPHRASE_FILE is missing or empty - refusing to write an unencrypted backup" >&2
  exit 1
fi

mkdir -p "$BACKUP_DIR"
stamp="$(date +%Y%m%d-%H%M%S)"
out="$BACKUP_DIR/bejo-$stamp.sql.gz"
[[ -n "$PASSPHRASE_FILE" ]] && out="$out.enc"
tmp="$out.partial"
trap 'rm -f "$tmp"' EXIT

# User/database come from the container's own environment, so this follows .env automatically.
dump() {
  docker exec bejo-postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --no-privileges' | gzip
}
if [[ -n "$PASSPHRASE_FILE" ]]; then
  dump | openssl enc -aes-256-cbc -pbkdf2 -salt -pass "file:$PASSPHRASE_FILE" > "$tmp"
else
  dump > "$tmp"
fi
mv "$tmp" "$out"
chmod 600 "$out"

find "$BACKUP_DIR" -name 'bejo-*.sql.gz*' -mtime +"$KEEP_DAYS" -delete
echo "$(date '+%F %T') backup ok: $out ($(du -h "$out" | cut -f1))"

# Off-box copy. A failure here is loud (non-zero exit -> cron mail/log) but the local backup stays.
if [[ -n "$RCLONE_REMOTE" ]]; then
  if ! command -v rclone >/dev/null 2>&1; then
    echo "ERROR: BACKUP_RCLONE_REMOTE is set but rclone is not installed" >&2
    exit 1
  fi
  rclone copyto "$out" "$RCLONE_REMOTE/$(basename "$out")"
  echo "$(date '+%F %T') copied off-box: $RCLONE_REMOTE/$(basename "$out")"
fi
