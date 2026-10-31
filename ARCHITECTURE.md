# Architecture

`modsync-domain` defines UUID-compatible identities, evidence, classifications, dependency constraints, artifacts, immutable manifests and explainable operations. `modsync-protocol` owns versioned wire messages, HTTPS transport and atomic local configuration writes. Neither depends on Spring.

`modsync-scanner` reads ZIP/TOML/JSON metadata and hashes files sequentially. It never executes JARs, extracts archives, follows mod symlinks, or edits server storage. It rejects malformed metadata and observable directory/file changes rather than publishing partial results. Hashes are currently recomputed per scheduled scan; persistent hash caching remains an optimization.

`modsync-modrinth` batches SHA-512 identification, retrieves project/version/dependency metadata and lists compatible versions. Requests have timeouts, bounded response sizes, caching and bounded rate-limit retries. Cache state is currently in memory.

`modsync-resolver` merges exact required server artifacts with approved client-only profile artifacts. It checks exact pins, loader/game compatibility, required/incompatible dependencies, filename collisions and management ownership. Cycles terminate because the complete pinned set is validated without recursive execution. Embedded providers participate in dependency checks; `--include-optional` selects server optional artifacts. Missing dependency versions block the plan; the initial implementation does not choose arbitrary versions on behalf of an administrator.

`modsync-agent` provides enrollment, local mapping configuration, scans, scheduled outbound requests, staged uploads and committed acknowledgements. Pending snapshots and progress survive process failure. One state-directory lock prevents competing scan processes.

`modsync-server` owns Spring Security, JDBC transactions, Flyway, MariaDB, ownership fencing, profile authorization, immutable manifest publication, audit events and Actuator/Micrometer. The requested entity tables exist; catalog caching and instance-registration tables are currently reserved rather than fully populated.

`modsync-client` uses Picocli, Java HTTP and NIO. A per-instance lock, deterministic plan, staged downloads, SHA-512/CRC checks, backups and an installation journal support recovery. It verifies online authority immediately before apply. Only the latest transaction is directly rollback-addressable; retained backup directories require an eventual retention policy. Atomic file replacement is used; power-loss durability across all filesystems has not been established.

`modsync-client-tui` adds JLine as a separate JVM application. The standard client has no GUI/JLine dependency. `modsync-test-fixtures` provides deterministic synthetic JAR construction.

No component uses a shared cross-host mount, database access from a client, Docker socket, or an inbound scanner API. Initial protocol version is 1 and minimum component compatibility is 0.1.0. See API documentation for limits and failure behavior.

Instance registration requires explicit game/loader metadata, checked against manifests during planning/apply/verify. This metadata must match the actual launcher configuration; ModSync does not install the game/loader or claim to detect every launcher. Verified artifact hashes are recorded separately from managed ownership so pre-existing required files are checked without being adopted for later deletion.
