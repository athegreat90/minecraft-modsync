#!/usr/bin/env zsh
# Phase 2 (admin machine): log in as admin (downloads the client and admin.py from the GitHub Release; no repository needed), register a Minecraft server, create an enrollment token.
# Usage: 02-admin.zsh --server-url https://HOST --control-plane-id UUID --password-file FILE \
#          --name NAME --minecraft-version VER [--loader neoforge] [--loader-version V] \
#          [--state-dir DIR] [--out-dir DIR] [--ttl 600] [--ca-file CA.pem] \
#          [--version TAG (default: latest release)] [--repo OWNER/REPO] [--work-dir DIR (default ./modsync-deploy)]
# Outputs in --out-dir: server-response.json and enrollment-token (give only the token to the agent operator).
setopt err_exit no_unset pipe_fail no_function_argzero

usage() { sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

here=${0:A:h}
repo=athegreat90/minecraft-modsync; version=; work=$PWD/modsync-deploy
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
    --version) version=$2; shift 2 ;;
    --repo) repo=$2; shift 2 ;;
    --work-dir) work=$2; shift 2 ;;
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

resolve_version() {
  if [[ -z $version ]]; then
    for ep in releases/latest "releases?per_page=1"; do  # /latest ignores pre-releases
      version=$(curl -fsS "https://api.github.com/repos/$repo/$ep" 2>/dev/null | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -n1) || true
      [[ -n $version ]] && break
    done
    [[ -n $version ]] || { print -u2 "Cannot resolve the latest release of $repo; pass --version TAG"; exit 1; }
  fi
  print "==> Using $repo release $version"
}
sha256_of() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi }
get_client() { # sets $client: native binary from the GitHub Release, or the Java zip where none is published
  local bin=$work/bin plat= base=https://github.com/$repo/releases/download/$version
  mkdir -p "$bin"
  case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) plat=linux-x64 ;;
    Linux-aarch64|Linux-arm64) plat=linux-arm64 ;;
  esac
  if [[ -n $plat ]]; then
    client=$bin/modsync-$version-$plat
    if [[ ! -x $client ]]; then
      print "==> Downloading the client (modsync-$plat)"
      curl -fsSL "$base/modsync-$plat" -o "$client.part" || { print -u2 "Cannot download modsync-$plat from $base"; exit 1; }
      want=$(curl -fsSL "$base/SHA256SUMS" | awk -v n="modsync-$plat" '$2==n {print $1}')
      [[ -n $want && $want == $(sha256_of "$client.part") ]] || { rm -f "$client.part"; print -u2 "Checksum mismatch or missing for modsync-$plat"; exit 1; }
      chmod +x "$client.part"; mv "$client.part" "$client"
    fi
    ln -sf "$client" "$bin/modsync"
  else
    command -v unzip >/dev/null && command -v java >/dev/null || { print -u2 "No native build for this platform: install unzip and Java 25+ to use the client zip"; exit 1; }
    if [[ ! -d $bin/client-$version ]]; then
      print "==> Downloading the client zip (requires Java 25+)"
      curl -fsSL "$base/modsync-client-$version.zip" -o "$bin/client.zip" || { print -u2 "Cannot download the client zip"; exit 1; }
      mkdir -p "$bin/client-$version"; unzip -q -o "$bin/client.zip" -d "$bin/client-$version"; rm -f "$bin/client.zip"
    fi
    client=$(find "$bin/client-$version" -path '*/bin/modsync-client' | head -n1)
    [[ -n $client ]] || { print -u2 "Launcher not found in the client zip"; exit 1; }
    chmod +x "$client"; ln -sf "$client" "$bin/modsync"
  fi
}
[[ $repo =~ '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$' ]] || { print -u2 -- "--repo must be OWNER/REPO"; exit 1; }
command -v curl >/dev/null || { print -u2 "curl is required"; exit 1; }
mkdir -p "$work"; work=${work:A}
resolve_version
if [[ -n ${MODSYNC_CLIENT:-} ]]; then client=$MODSYNC_CLIENT; else get_client; fi
if [[ -f $here/../admin.py ]]; then adminpy=$here/../admin.py
else
  adminpy=$work/admin-$version.py
  [[ -f $adminpy ]] || curl -fsSL "https://raw.githubusercontent.com/$repo/$version/scripts/admin.py" -o "$adminpy" \
    || { rm -f "$adminpy"; print -u2 "Cannot download admin.py for $version"; exit 1; }
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
  python3 "$adminpy" "${args[@]}" "$@" "$r"
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
