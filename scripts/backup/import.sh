#!/bin/sh
set -eu
[ "$#" -eq 2 ] || { echo "Usage: import.sh COMPOSE_FILE VERIFIED_BACKUP.sql" >&2; exit 2; }
[ "${MODSYNC_RESTORE_CONFIRMED:-}" = "fresh-database" ] || { echo "Set MODSYNC_RESTORE_CONFIRMED=fresh-database only for an isolated restore target" >&2; exit 2; }
expected=$(awk 'NR==1 {print $1}' "$2.sha256")
actual=$(sha256sum "$2" | awk '{print $1}')
[ "$expected" = "$actual" ] || { echo "Backup checksum mismatch" >&2; exit 2; }
db_script=$(cat <<'DATABASE_SCRIPT'
  cfg=$(mktemp)
  trap 'rm -f "$cfg"' EXIT
  chmod 600 "$cfg"
  password=$(cat /run/secrets/db_root_password)
  escaped=$(printf '%s' "$password" | sed 's/\\/\\\\/g; s/"/\\"/g')
  printf '[client]\nuser=root\npassword="%s"\n' "$escaped" > "$cfg"
  mariadb --defaults-extra-file="$cfg"
DATABASE_SCRIPT
)
docker compose -f "$1" exec -T "${MODSYNC_DATABASE_SERVICE:-mariadb}" sh -eu -c "$db_script" < "$2"
echo "Import completed; validate Flyway, identities, manifests and profiles before routing traffic"
