#!/bin/sh
set -eu
if [ -n "${MODSYNC_DB_PASSWORD_FILE:-}" ]; then
  MODSYNC_DB_PASSWORD=$(cat "$MODSYNC_DB_PASSWORD_FILE")
  export MODSYNC_DB_PASSWORD
fi
exec java -jar /app/server.jar "$@"
