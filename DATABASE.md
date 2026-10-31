# MariaDB and disaster recovery

The stack pins MariaDB 11.4.10 in the 11.4 LTS series. Review security updates before enabling publication; image digest pinning and scanner results are release gates. Connector/J and Flyway's MySQL/MariaDB support are included. Only MariaDB Testcontainers is used for database integration tests; H2 is not a substitute.

Schema migrations use InnoDB, utf8mb4, UUID identities, foreign keys and transactional ownership/snapshot operations. The application pool is limited to five connections. Flyway runs on startup and must validate successfully after restore. Never edit an applied migration; add a new numbered migration.

## Consistent export and import

Stop application writes (maintenance mode plus stopping writers is recommended). Freeze schema/deployment changes during export. `--single-transaction` provides a consistent InnoDB snapshot; it does not make concurrent DDL safe.

```sh
scripts/backup/export.sh docker/server/compose.yaml /secure/backup/modsync.sql
# On an isolated destination with the same MariaDB major version:
MODSYNC_RESTORE_CONFIRMED=fresh-database scripts/backup/import.sh docker/server/compose.yaml /secure/backup/modsync.sql
```

Keep the `.sha256` file beside the export. Checksums detect accidental corruption, not malicious replacement: protect or sign backup storage independently. Utilities run inside the MariaDB container and obtain credentials from mounted secret files. For another service name set `MODSYNC_DATABASE_SERVICE`. Dumps contain password hashes, device/agent credential hashes and personal data: encrypt backups and restrict access. Verify database secret file permissions allow the container process to read them.

Retain Compose configuration, secret sources, trusted CA configuration, reverse-proxy certificates/keys and any authentication key material introduced later. This initial opaque-token design has no separate token-signing key; control-plane identity and credential hashes live in the database. Never copy live MariaDB files as the sole backup. Persistent volumes survive normal `compose down`; `down --volumes` destroys them and is only used by disposable tests.

Restoration validation must check Flyway history, control-plane UUID, user login, an existing agent credential, server/assignment UUIDs, historical manifests, profiles and device access. The distributed harness exports into an isolated second MariaDB container and switches to a replacement backend with the restored database. This drill is not yet executed here.

Keep major-version upgrades separate from relocation. Roll back by stopping destination writers before restoring routing to the original database/application pair. If destination writes occurred, reconcile or restore those changes first; never let two unfenced control planes accept writes independently.
