#!/usr/bin/env zsh
# Phase 1 (Host B): create secrets, write .env, start the API (+ bundled MariaDB), verify health.
# Usage: 01-server.zsh [--secrets-dir DIR] [--bind ADDR] [--url BASE_URL] [--maintenance true|false]
#          [--db-mode bundled|external]
#   External MariaDB (the database and user must already exist; nothing is created for you):
#          --db-host HOST [--db-port 3306] [--db-name modsync] [--db-user modsync]
#          [--db-password-file FILE] [--db-ssl-mode disable|trust|verify-ca|verify-full]
#          [--db-url JDBC_URL] [--skip-db-check]
# --db-host implies --db-mode external. The DB password comes from --db-password-file, an existing
# secrets/db_password, or a silent prompt. Secrets are never printed; existing files are kept.
setopt err_exit no_unset pipe_fail no_function_argzero

usage() { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

root=${0:A:h:h:h}
srv=$root/docker/server
secrets=$root/.local/secrets
bind=127.0.0.1
url=http://127.0.0.1:8080
maintenance=false
dbmode=bundled
dbhost=; dbport=3306; dbname=modsync; dbuser=modsync; dbpwfile=; dbssl=; dburl=; skipcheck=0

while (( $# )); do
  case $1 in
    --secrets-dir) secrets=$2; shift 2 ;;
    --bind) bind=$2; shift 2 ;;
    --url) url=${2%/}; shift 2 ;;
    --maintenance) maintenance=$2; shift 2 ;;
    --db-mode) dbmode=$2; shift 2 ;;
    --db-host) dbhost=$2; dbmode=external; shift 2 ;;
    --db-port) dbport=$2; shift 2 ;;
    --db-name) dbname=$2; shift 2 ;;
    --db-user) dbuser=$2; shift 2 ;;
    --db-password-file) dbpwfile=$2; shift 2 ;;
    --db-ssl-mode) dbssl=$2; shift 2 ;;
    --db-url) dburl=$2; dbmode=external; shift 2 ;;
    --skip-db-check) skipcheck=1; shift ;;
    -h|--help) usage 0 ;;
    *) print -u2 "Unknown option: $1"; usage 1 ;;
  esac
done

[[ $dbmode == (bundled|external) ]] || { print -u2 -- "--db-mode must be bundled or external"; exit 1; }
[[ $dbssl == (|disable|trust|verify-ca|verify-full) ]] || { print -u2 -- "--db-ssl-mode must be disable, trust, verify-ca or verify-full"; exit 1; }
[[ $dbname =~ '^[A-Za-z0-9_]+$' && $dbuser =~ '^[A-Za-z0-9_.-]+$' ]] || { print -u2 -- "--db-name/--db-user may only contain letters, digits, _ . -"; exit 1; }
[[ $dbport =~ '^[0-9]+$' ]] || { print -u2 -- "--db-port must be numeric"; exit 1; }
if [[ $dbmode == external && -z $dbhost && -z $dburl ]]; then print -u2 "External mode needs --db-host (or --db-url)"; exit 1; fi
if [[ -n $dburl && $dburl != jdbc:mariadb://* ]]; then print -u2 -- "--db-url must start with jdbc:mariadb://"; exit 1; fi
if [[ -n $dburl && $skipcheck -eq 0 && -z $dbhost ]]; then
  print -u2 "With --db-url also pass --db-host/--db-port/--db-name for the pre-flight check, or use --skip-db-check"; exit 1
fi

for tool in docker openssl curl; do
  command -v $tool >/dev/null || { print -u2 "Missing required tool: $tool"; exit 1; }
done
docker compose version >/dev/null || { print -u2 "Docker Compose v2 is required"; exit 1; }
if [[ $dbmode == external ]]; then
  cv=$(docker compose version --short | sed 's/^v//')
  autoload -Uz is-at-least
  is-at-least 2.24 "$cv" || { print -u2 "External mode needs Docker Compose >= 2.24 (found $cv)"; exit 1; }
fi

print "==> Preparing secrets in $secrets"
mkdir -p "$secrets"; chmod 700 "$secrets"
secrets=${secrets:A}
gen() { # name
  if [[ -s $secrets/$1 ]]; then print "    keeping existing $1"
  else ( umask 077; openssl rand -hex 32 > "$secrets/$1" ); print "    created $1"; fi
}
gen bootstrap_password
if [[ $dbmode == bundled ]]; then
  gen db_password; gen db_root_password
else
  if [[ -n $dbpwfile ]]; then
    [[ -s $dbpwfile ]] || { print -u2 "Cannot read --db-password-file"; exit 1; }
    ( umask 077; tr -d '\r\n' < "$dbpwfile" > "$secrets/db_password" )
    print "    stored db_password from file"
  elif [[ -s $secrets/db_password ]]; then
    print "    keeping existing db_password"
  elif [[ -t 0 ]]; then
    read -rs "?MariaDB password for user '$dbuser': " pw; print
    [[ -n $pw ]] || { print -u2 "Empty password"; exit 1; }
    ( umask 077; print -rn -- "$pw" > "$secrets/db_password" ); unset pw
    print "    stored db_password"
  else
    print -u2 "Provide --db-password-file (no terminal for a prompt)"; exit 1
  fi
fi
# The server container runs as UID 10001 and must read the secrets.
if [[ $EUID -eq 0 ]]; then
  chown -R 10001:10001 "$secrets"
else
  print "    NOTE: ensure UID 10001 can read the files, e.g. sudo chown -R 10001:10001 $secrets"
fi

[[ $dbhost == (localhost|127.0.0.1|::1) ]] && islocal=1 || islocal=0
# A container cannot reach the host's loopback; use the Docker host alias instead.
connect_host=$dbhost
(( islocal )) && connect_host=host.docker.internal
if [[ -z $dbssl ]]; then (( islocal )) && dbssl=disable || dbssl=verify-full; fi
[[ -n $dburl ]] || dburl="jdbc:mariadb://$connect_host:$dbport/$dbname?sslMode=$dbssl"

if [[ $dbmode == external ]]; then
  print "\n==> External MariaDB ($connect_host:$dbport/$dbname as $dbuser, sslMode=$dbssl)"
  print "    The DBA must have run (use a real password; never paste it into shell history):"
  print "      CREATE DATABASE $dbname CHARACTER SET utf8mb4 COLLATE utf8mb4_uca1400_ai_ci;"
  print "      CREATE USER '$dbuser'@'%' IDENTIFIED BY '<password>';"
  print "      GRANT ALL PRIVILEGES ON $dbname.* TO '$dbuser'@'%';"
  if (( skipcheck )); then
    print "    pre-flight check skipped"
  else
    print "==> Pre-flight check (throwaway mariadb:11.4.10 client container)"
    case $dbssl in
      disable) sslflags=(--skip-ssl) ;;
      trust) sslflags=(--ssl) ;;
      *) sslflags=(--ssl --ssl-verify-server-cert) ;;
    esac
    probe="SELECT VERSION(); CREATE TABLE modsync_preflight_probe (id INT) ENGINE=InnoDB; DROP TABLE modsync_preflight_probe;"
    if ! out=$(docker run --rm --add-host host.docker.internal:host-gateway \
        -v "$secrets/db_password:/pw:ro" \
        -e H="$connect_host" -e P="$dbport" -e U="$dbuser" -e N="$dbname" \
        mariadb:11.4.10 sh -c 'MYSQL_PWD=$(cat /pw) exec mariadb -h"$H" -P"$P" -u"$U" "$N" -N -B '"${sslflags[*]}"' -e "$0"' "$probe" 2>&1); then
      print -u2 "Pre-flight failed:\n$out"
      print -u2 "Check host, port, credentials, TLS (--db-ssl-mode), firewall and that the user may CREATE/DROP tables in $dbname."
      exit 1
    fi
    version=${${(f)out}[1]}
    print "    login and CREATE/DROP privileges OK (server $version)"
    [[ $version == 11.4.* ]] || print "    WARNING: ModSync is tested with MariaDB 11.4.x; found $version"
  fi
fi

print "==> Writing $srv/.env"
[[ -f $srv/.env ]] || cp "$srv/.env.example" "$srv/.env"
set_env() {
  local tmp=$srv/.env.tmp
  ( umask 077; grep -v "^$1=" "$srv/.env" > "$tmp" || true; print -r -- "$1=$2" >> "$tmp" )
  mv "$tmp" "$srv/.env"
}
set_env MODSYNC_SECRETS_DIR "$secrets"
set_env MODSYNC_BIND_ADDRESS "$bind"
set_env MODSYNC_MAINTENANCE "$maintenance"
if [[ $dbmode == external ]]; then
  set_env MODSYNC_DB_URL "$dburl"
  set_env MODSYNC_DB_USER "$dbuser"
fi

print "==> Starting containers ($dbmode database)"
cd "$srv"
if [[ $dbmode == external ]]; then
  compose=(docker compose --env-file .env -f compose.yaml -f compose.external-db.yaml)
  services=(modsync-server)
else
  compose=(docker compose --env-file .env -f compose.yaml)
  services=()
fi
$compose up -d --build $services
$compose ps

print "==> Waiting for health at $url/actuator/health"
ok=0
for i in {1..60}; do
  if curl -fsS "$url/actuator/health" >/dev/null 2>&1; then ok=1; break; fi
  sleep 3
done
(( ok )) || { print -u2 "Server did not become healthy. Check: $compose logs modsync-server"; exit 1; }

print "==> /api/v1/info"
curl -fsS "$url/api/v1/info"; print
print "\nDone. Record controlPlaneId (pinned by every client and agent)."
print "Admin password file: $secrets/bootstrap_password (user: admin)."
[[ $dbmode == external ]] && print "Backups: scripts/backup/*.sh target the bundled DB; back up the external database with mariadb-dump."
print "Next: put a TLS reverse proxy in front of 127.0.0.1:8080, then run 02-admin."
