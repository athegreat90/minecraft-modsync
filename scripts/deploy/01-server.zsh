#!/usr/bin/env zsh
# Phase 1 (Host B): create secrets, write .env, start API + MariaDB, verify health.
# Usage: 01-server.zsh [--secrets-dir DIR] [--bind ADDR] [--url BASE_URL] [--maintenance true|false]
# Secrets are generated once and never printed; existing files are kept.
setopt err_exit no_unset pipe_fail no_function_argzero

usage() { sed -n '2,4p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

root=${0:A:h:h:h}
srv=$root/docker/server
secrets=$root/.local/secrets
bind=127.0.0.1
url=http://127.0.0.1:8080
maintenance=false

while (( $# )); do
  case $1 in
    --secrets-dir) secrets=$2; shift 2 ;;
    --bind) bind=$2; shift 2 ;;
    --url) url=${2%/}; shift 2 ;;
    --maintenance) maintenance=$2; shift 2 ;;
    -h|--help) usage 0 ;;
    *) print -u2 "Unknown option: $1"; usage 1 ;;
  esac
done

for tool in docker openssl curl; do
  command -v $tool >/dev/null || { print -u2 "Missing required tool: $tool"; exit 1; }
done
docker compose version >/dev/null || { print -u2 "Docker Compose v2 is required"; exit 1; }

print "==> Creating secrets in $secrets"
mkdir -p "$secrets"; chmod 700 "$secrets"
secrets=${secrets:A}
for name in db_password db_root_password bootstrap_password; do
  if [[ -s $secrets/$name ]]; then
    print "    keeping existing $name"
  else
    ( umask 077; openssl rand -hex 32 > "$secrets/$name" )
    print "    created $name"
  fi
done
# The server container runs as UID 10001 and must read the secrets.
if [[ $EUID -eq 0 ]]; then
  chown -R 10001:10001 "$secrets"
else
  print "    NOTE: ensure UID 10001 can read the files, e.g. sudo chown -R 10001:10001 $secrets"
fi

print "==> Writing $srv/.env"
[[ -f $srv/.env ]] || cp "$srv/.env.example" "$srv/.env"
set_env() {
  if grep -q "^$1=" "$srv/.env"; then
    sed -i.bak "s|^$1=.*|$1=$2|" "$srv/.env" && rm -f "$srv/.env.bak"
  else
    print "$1=$2" >> "$srv/.env"
  fi
}
set_env MODSYNC_SECRETS_DIR "$secrets"
set_env MODSYNC_BIND_ADDRESS "$bind"
set_env MODSYNC_MAINTENANCE "$maintenance"

print "==> Starting containers"
cd "$srv"
docker compose --env-file .env -f compose.yaml up -d --build
docker compose --env-file .env -f compose.yaml ps

print "==> Waiting for health at $url/actuator/health"
ok=0
for i in {1..60}; do
  if curl -fsS "$url/actuator/health" >/dev/null 2>&1; then ok=1; break; fi
  sleep 3
done
(( ok )) || { print -u2 "Server did not become healthy. Check: docker compose logs"; exit 1; }

print "==> /api/v1/info"
curl -fsS "$url/api/v1/info"; print
print "\nDone. Record controlPlaneId (pinned by every client and agent)."
print "Admin password file: $secrets/bootstrap_password (user: admin)."
print "Next: put a TLS reverse proxy in front of 127.0.0.1:8080, then run 02-admin."
