#!/bin/sh
set -eu
umask 077
[ "$#" -eq 2 ] || { echo "Usage: export.sh COMPOSE_FILE OUTPUT.sql" >&2; exit 2; }
[ ! -e "$2" ] && [ ! -L "$2" ] || { echo "Refusing to overwrite backup" >&2; exit 2; }
tmp="$2.partial"
[ ! -e "$tmp" ] && [ ! -L "$tmp" ] || { echo "Partial backup already exists; inspect it first" >&2; exit 2; }
trap 'rm -f "$tmp"' EXIT HUP INT TERM
db_script=$(cat <<'DATABASE_SCRIPT'
  cfg=$(mktemp)
  trap 'rm -f "$cfg"' EXIT
  chmod 600 "$cfg"
  password=$(cat /run/secrets/db_root_password)
  escaped=$(printf '%s' "$password" | sed 's/\\/\\\\/g; s/"/\\"/g')
  printf '[client]\nuser=root\npassword="%s"\n' "$escaped" > "$cfg"
  mariadb-dump --defaults-extra-file="$cfg" --single-transaction --quick --routines --events --triggers --hex-blob --databases modsync
DATABASE_SCRIPT
)
(set -C; docker compose -f "$1" exec -T "${MODSYNC_DATABASE_SERVICE:-mariadb}" sh -eu -c "$db_script" > "$tmp")
[ -s "$tmp" ]
mv "$tmp" "$2"
sha256sum "$2" > "$2.sha256"
echo "Database exported; restore drill is required to verify recovery"
