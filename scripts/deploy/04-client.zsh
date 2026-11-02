#!/usr/bin/env zsh
# Phase 4 (player machine): build/install the client, pin the server, log in, register an instance.
# Usage: 04-client.zsh --server-url https://HOST --control-plane-id UUID --username NAME \
#          [--password-file FILE] [--instance-name NAME --instance-path DIR \
#           --minecraft-version VER --loader LOADER [--loader-version V]] \
#          [--state-dir DIR] [--add-to-path]
# Without --instance-path the script tries `modsync instances discover`.
# --add-to-path appends the launcher directory to ~/.zshrc (opt-in).
setopt err_exit no_unset pipe_fail no_function_argzero

usage() { sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

root=${0:A:h:h:h}
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
    -h|--help) usage 0 ;;
    *) print -u2 "Unknown option: $1"; usage 1 ;;
  esac
done
for v in url cpid user; do [[ -n ${(P)v} ]] || { print -u2 "Missing required option for: $v"; usage 1; }; done
if [[ -n $ipath ]]; then
  for v in iname mcv loader; do [[ -n ${(P)v} ]] || { print -u2 "--instance-path needs --instance-name, --minecraft-version and --loader"; exit 1; }; done
  [[ -d $ipath ]] || { print -u2 "Instance path not found: $ipath"; exit 1; }
fi

print "==> Checking Java"
command -v java >/dev/null || { print -u2 "Java 25 is required (java not found)"; exit 1; }
major=$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')
(( ${major:-0} >= 25 )) || { print -u2 "Java 25+ required, found ${major:-unknown}"; exit 1; }

launcher=$root/modsync-client/build/install/modsync-client/bin/modsync-client
if [[ ! -x $launcher ]]; then
  print "==> Building the client"
  (cd "$root" && ./gradlew :modsync-client:installDist)
fi
sf=(); [[ -n $state ]] && sf=(--state-dir "$state")

if (( addpath )); then
  line="export PATH=\"${launcher:h}:\$PATH\""
  grep -qxF "$line" ~/.zshrc 2>/dev/null || print "$line" >> ~/.zshrc
  print "==> Added ${launcher:h} to PATH in ~/.zshrc (open a new shell to use 'modsync-client')."
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
