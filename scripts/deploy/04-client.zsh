#!/usr/bin/env zsh
# Phase 4 (player machine): download the client, pin the server, log in, register an instance.
# Usage: 04-client.zsh --server-url https://HOST --control-plane-id UUID --username NAME \
#          [--password-file FILE] [--instance-name NAME --instance-path DIR \
#           --minecraft-version VER --loader LOADER [--loader-version V]] \
#          [--state-dir DIR] [--add-to-path] [--version TAG (default: latest release)]
#          [--repo OWNER/REPO] [--work-dir DIR (default ./modsync-deploy)]
# Without --instance-path the script tries `modsync instances discover`.
# --add-to-path appends the launcher directory to ~/.zshrc (opt-in).
setopt err_exit no_unset pipe_fail no_function_argzero

usage() { sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

repo=athegreat90/minecraft-modsync; version=; work=$PWD/modsync-deploy
url=; cpid=; user=; pwfile=; iname=; ipath=; mcv=; loader=; loaderv=; state=; addpath=0

while (( $# )); do
  case $1 in
    --server-url) url=$2; shift 2 ;;
    --control-plane-id) cpid=$2; shift 2 ;;
    --username) user=$2; shift 2 ;;
    --password-file) pwfile=$2; shift 2 ;;
    --instance-name) iname=$2; shift 2 ;;
    --instance-path) ipath=$2; shift 2 ;;
    --minecraft-version) mcv=$2; shift 2 ;;
    --loader) loader=$2; shift 2 ;;
    --loader-version) loaderv=$2; shift 2 ;;
    --state-dir) state=$2; shift 2 ;;
    --add-to-path) addpath=1; shift ;;
    --version) version=$2; shift 2 ;;
    --repo) repo=$2; shift 2 ;;
    --work-dir) work=$2; shift 2 ;;
    -h|--help) usage 0 ;;
    *) print -u2 "Unknown option: $1"; usage 1 ;;
  esac
done
for v in url cpid user; do [[ -n ${(P)v} ]] || { print -u2 "Missing required option for: $v"; usage 1; }; done
if [[ -n $ipath ]]; then
  for v in iname mcv loader; do [[ -n ${(P)v} ]] || { print -u2 -- "--instance-path needs --instance-name, --minecraft-version and --loader"; exit 1; }; done
  [[ -d $ipath ]] || { print -u2 "Instance path not found: $ipath"; exit 1; }
fi

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
get_client
launcher=$client
sf=(); [[ -n $state ]] && sf=(--state-dir "$state")

if (( addpath )); then
  line="export PATH=\"$work/bin:\$PATH\""
  grep -qxF "$line" ~/.zshrc 2>/dev/null || print "$line" >> ~/.zshrc
  print "==> Added $work/bin to PATH in ~/.zshrc (open a new shell to use 'modsync')."
fi

print "==> Pinning the server"
"$launcher" $sf setup --server-url "$url" --trust-server-id "$cpid"
print "==> Logging in"
if [[ -n $pwfile ]]; then "$launcher" $sf login --username "$user" --password-file "$pwfile"
else "$launcher" $sf login --username "$user"; fi
print "==> Testing the connection"
"$launcher" $sf connection test
"$launcher" $sf servers list

if [[ -n $ipath ]]; then
  print "==> Registering instance '$iname'"
  extra=(); [[ -n $loaderv ]] && extra=(--loader-version "$loaderv")
  "$launcher" $sf instances add "$iname" "$ipath" --minecraft-version "$mcv" --loader "$loader" $extra
else
  print "==> Discovering instances"
  "$launcher" $sf instances discover || true
fi
print "\nDone. Next: close Minecraft, then:"
print "  modsync sync plan --instance NAME --server SERVER_UUID --out plan.json"
print "  modsync sync apply --instance NAME --plan plan.json"
