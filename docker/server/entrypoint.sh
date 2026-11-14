#!/bin/sh
set -eu
if [ -n "${MODSYNC_DB_PASSWORD_FILE:-}" ]; then
  MODSYNC_DB_PASSWORD=$(cat "$MODSYNC_DB_PASSWORD_FILE")
  export MODSYNC_DB_PASSWORD
fi
# Optional single sign-on client secrets are read from files so they never appear in `docker inspect`.
if [ -n "${GOOGLE_CLIENT_SECRET_FILE:-}" ] && [ -s "$GOOGLE_CLIENT_SECRET_FILE" ]; then
  GOOGLE_CLIENT_SECRET=$(cat "$GOOGLE_CLIENT_SECRET_FILE")
  export GOOGLE_CLIENT_SECRET
fi
if [ -n "${GITHUB_CLIENT_SECRET_FILE:-}" ] && [ -s "$GITHUB_CLIENT_SECRET_FILE" ]; then
  GITHUB_CLIENT_SECRET=$(cat "$GITHUB_CLIENT_SECRET_FILE")
  export GITHUB_CLIENT_SECRET
fi
exec java -jar /app/server.jar "$@"
