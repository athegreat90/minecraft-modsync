#!/usr/bin/env zsh
# Phase 2 (admin machine): log in as admin, register a Minecraft server, create an enrollment token.
# Usage: 02-admin.zsh --server-url https://HOST --control-plane-id UUID --password-file FILE \
#          --name NAME --minecraft-version VER [--loader neoforge] [--loader-version V] \
#          [--state-dir DIR] [--out-dir DIR] [--ttl 600] [--ca-file CA.pem]
# Outputs in --out-dir: server-response.json and enrollment-token (give only the token to the agent operator).
setopt err_exit no_unset pipe_fail no_function_argzero

usage() { sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

root=${0:A:h:h:h}
state=$HOME/.modsync-admin
out=$PWD/admin-out
loader=neoforge; loaderv=; ttl=600; ca=
url=; cpid=; pwfile=; name=; mcv=

while (( $# )); do
  case $1 in
    --server-url) url=$2; shift 2 ;;
    --control-plane-id) cpid=$2; shift 2 ;;
    --password-file) pwfile=$2; shift 2 ;;
    --name) name=$2; shift 2 ;;
    --minecraft-version) mcv=$2; shift 2 ;;
    --loader) loader=$2; shift 2 ;;
    --loader-version) loaderv=$2; shift 2 ;;
    --state-dir) state=$2; shift 2 ;;
    --out-dir) out=$2; shift 2 ;;
    --ttl) ttl=$2; shift 2 ;;
    --ca-file) ca=$2; shift 2 ;;
    -h|--help) usage 0 ;;
    *) print -u2 "Unknown option: $1"; usage 1 ;;
  esac
done
for v in url cpid pwfile name mcv; do
  [[ -n ${(P)v} ]] || { print -u2 "Missing required option for: $v"; usage 1; }
done
[[ -r $pwfile ]] || { print -u2 "Cannot read password file"; exit 1; }
(( ttl >= 60 && ttl <= 3600 )) || { print -u2 -- "--ttl must be 60..3600"; exit 1; }
command -v python3 >/dev/null || { print -u2 "python3 is required"; exit 1; }

client=${MODSYNC_CLIENT:-$root/modsync-client/build/install/modsync-client/bin/modsync-client}
if [[ ! -x $client ]]; then
  print "==> Building the client"
  (cd "$root" && ./gradlew :modsync-client:installDist)
fi

mkdir -p "$out"; chmod 700 "$out"
print "==> Pinning server and logging in as admin"
"$client" --state-dir "$state" setup --server-url "$url" --trust-server-id "$cpid"
"$client" --state-dir "$state" login --username admin --password-file "$pwfile"

admin() { # method route data out [extra...]
  local m=$1 r=$2 d=$3 o=$4; shift 4
  local args=(--config "$state/config.json" --method $m --out "$o")
  [[ -n $d ]] && args+=(--data "$d")
  [[ -n $ca ]] && args+=(--ca-file "$ca")
  python3 "$root/scripts/admin.py" "${args[@]}" "$@" "$r"
}

print "==> Registering server '$name'"
python3 - "$out/server-request.json" "$name" "$mcv" "$loader" "$loaderv" <<'PY'
import json, sys
path, name, mc, loader, lv = sys.argv[1:]
body = {"name": name, "minecraftVersion": mc, "loader": loader}
if lv: body["loaderVersion"] = lv
open(path, "w").write(json.dumps(body))
PY
admin POST admin/servers "$out/server-request.json" "$out/server-response.json"

print "==> Creating enrollment token (TTL ${ttl}s)"
print -n "{\"ttlSeconds\":$ttl}" > "$out/enroll-request.json"
( umask 077; admin POST admin/enrollments "$out/enroll-request.json" "$out/enrollment-token" --secret-field token )

sid=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("id",""))' "$out/server-response.json" 2>/dev/null || true)
print "\nDone."
[[ -n $sid ]] && print "SERVER_UUID: $sid" || print "Find SERVER_UUID in $out/server-response.json"
print "Enrollment token file: $out/enrollment-token (expires in ${ttl}s; single use)"
print "Next: run 03-agent on Host A, then assign the agent (see docs/USAGE.md section 3.3)."
