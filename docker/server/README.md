# Host B

Prerequisites: Docker/Compose, a stable DNS name, a trusted TLS reverse proxy, and a verified build. Copy `.env.example` to `.env`; choose an absolute secrets directory. Generate distinct random secrets in files, never shell command arguments containing their values.

```sh
umask 077
mkdir -p ../../.local/secrets
openssl rand -hex 32 > ../../.local/secrets/db_password
openssl rand -hex 32 > ../../.local/secrets/db_root_password
openssl rand -hex 32 > ../../.local/secrets/bootstrap_password
```

Set `MODSYNC_SECRETS_DIR` to that absolute directory. Make the database password and bootstrap password files readable by application UID 10001, for example with a narrowly scoped file ACL or ownership change; keep other users excluded. The database container must also be able to read its mounted secrets. Do not add secrets to Git.

```sh
docker compose --env-file .env -f compose.yaml up -d --build
docker compose --env-file .env -f compose.yaml ps
```

Configure HTTPS routing to `127.0.0.1:8080`. Verify `/actuator/health` and `/api/v1/info` through TLS. First startup creates `admin` only if users are absent, reading `bootstrap_password`; subsequent starts preserve users and never reset a password. Login using a console or `--password-file`. Remove bootstrap material from routine operations after a controlled Compose update; never lose database/proxy/CA backups.

Administer with `scripts/admin.py` and [API.md](../../docs/architecture/API.md). No web administration UI is included.

## External MariaDB

To use an existing MariaDB 11.4.x, pre-create the database and a user with DDL rights on it (Flyway migrates at startup), put the password in the `db_password` secret, set `MODSYNC_DB_URL` (`jdbc:mariadb://HOST:3306/modsync?sslMode=verify-full`) and `MODSYNC_DB_USER` in `.env`, then:

```sh
docker compose --env-file .env -f compose.yaml -f compose.external-db.yaml up -d --build modsync-server
```n
Requires Docker Compose >= 2.24. `scripts/deploy/01-server.*` automates this and verifies the connection (see [USAGE.md](../../docs/USAGE.md)). `scripts/backup/*` target the bundled database only; use `mariadb-dump` instead.

