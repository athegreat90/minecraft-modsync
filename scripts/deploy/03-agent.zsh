#!/usr/bin/env zsh
# Phase 3 (Host A): configure and start the scanning agent in Docker.
# Usage: 03-agent.zsh --server-url https://HOST --control-plane-id UUID --token-file FILE \
#          --state-path DIR --servers-path DIR --alias NAME --mods-dir SERVER_DIR/mods \
#          [--server-uuid UUID] [--interval 300]
#   --servers-path is the Crafty servers root (mounted read-only at /minecraft).
#   --mods-dir is relative to --servers-path. --server-uuid triggers a first scan.
# The token file is consumed (copied into the state dir, then deleted by this script).
setopt err_exit no_unset pipe_fail no_function_argzero

usage() { sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

root=${0:A:h:h:h}
dir=$root/docker/agent
url=; cpid=; token=; state=; servers=; alias_name=; mods=; sid=; interval=300

while (( $# )); do
  case $1 in
    --server-url) url=$2; shift 2 ;;
    --control-plane-id) cpid=$2; shift 2 ;;
    --token-file) token=$2; shift 2 ;;
    --state-path) state=$2; shift 2 ;;
    --servers-path) servers=$2; shift 2 ;;
    --alias) alias_name=$2; shift 2 ;;
    --mods-dir) mods=$2; shift 2 ;;
    --server-uuid) sid=$2; shift 2 ;;
    --interval) interval=$2; shift 2 ;;
    -h|--help) usage 0 ;;
    *) print -u2 "Unknown option: $1"; usage 1 ;;
  esac
done
for v in url cpid token state servers alias_name mods; do
  [[ -n ${(P)v} ]] || { print -u2 "Missing required option for: $v"; usage 1; }
done
(( interval >= 30 && interval <= 86400 )) || { print -u2 -- "--interval must be 30..86400"; exit 1; }
[[ -r $token ]] || { print -u2 "Cannot read token file"; exit 1; }
[[ -d $servers ]] || { print -u2 "Servers path not found: $servers"; exit 1; }
[[ -d $servers/$mods ]] || { print -u2 "Mods directory not found: $servers/$mods"; exit 1; }
command -v docker >/dev/null || { print -u2 "Docker is required"; exit 1; }

mkdir -p "$state"; chmod 700 "$state"
state=${state:A}; servers=${servers:A}
if [[ $EUID -eq 0 ]]; then chown 10001:10001 "$state"
else print "NOTE: the container runs as UID 10001; ensure it can write $state (e.g. sudo chown 10001:10001)."; fi

print "==> Writing $dir/.env"
[[ -f $dir/.env ]] || cp "$dir/.env.example" "$dir/.env"
set_env() {
  if grep -q "^$1=" "$dir/.env"; then sed -i.bak "s|^$1=.*|$1=$2|" "$dir/.env" && rm -f "$dir/.env.bak"
  else print "$1=$2" >> "$dir/.env"; fi
}
set_env MODSYNC_STATE_PATH "$state"
set_env MINECRAFT_SERVERS_PATH "$servers"
set_env MODSYNC_SCAN_INTERVAL "$interval"
set_env MODSYNC_SERVER_URL "$url"

cd "$dir"
dc() { docker compose --env-file .env "$@"; }
print "==> Building the agent image"; dc build
print "==> Pinning the server"
dc run --rm agent setup --server-url "$url" --trust-server-id "$cpid"

print "==> Registering with the enrollment token"
( umask 077; cp "$token" "$state/enrollment-token" )
[[ $EUID -eq 0 ]] && chown 10001:10001 "$state/enrollment-token" || chmod 644 "$state/enrollment-token"
trap 'rm -f "$state/enrollment-token"' EXIT
dc run --rm agent register --enrollment-token-file /app/state/enrollment-token
rm -f "$state/enrollment-token" "$token"; trap - EXIT

print "==> Mapping alias '$alias_name'"
dc run --rm agent map "$alias_name" "/minecraft/$mods"

print "==> Agent status"
dc run --rm agent status || true

if [[ -n $sid ]]; then
  print "==> First scan for $sid (requires the admin to have assigned this agent)"
  dc run --rm agent scan --server "$sid" || print "Scan failed; assign the agent first (docs/USAGE.md 3.3) and rerun."
fi

print "==> Starting the agent"
dc up -d
print "\nDone. Report the agent id to the administrator (GET admin/agents) so it can be assigned with mapping '$alias_name'."
