# Copilot instructions

Kotlin/JVM 25 Gradle monorepo (Kotlin 2.4, Spring Boot 4 for the server only). Three independently deployed artifacts: **agent** (Host A), **server** (Host B, MariaDB), **client** CLI (Host C).

## Build, test, lint

`./gradlew` (`gradlew.bat` on Windows) is a Python bootstrap (`scripts/gradle.py`) that downloads a checksum-pinned Gradle and runs it with `--no-daemon`; it is not the official wrapper. Requires JDK 25 and Docker (Testcontainers/MariaDB for server tests).

```sh
./gradlew check :modsync-server:bootJar :modsync-agent:installDist :modsync-client:installDist :modsync-client-tui:installDist   # CI build
./gradlew :modsync-resolver:test                                   # one module
./gradlew :modsync-client:test --tests "de.alexandermora.modsync.client.InstallerTest"   # one class (append .method for one test)
python3 scripts/check-architecture.py       # module boundary check
python3 scripts/openapi.py --check          # docs/architecture/openapi.json must match DTOs + controllers (run without --check to regenerate)
python3 -m unittest discover -s scripts/release -p 'test_*.py'     # also scripts/repository and scripts/backup
python3 scripts/integration/distributed.py  # three-host HTTPS/migration/restore fixture
```

There is no separate linter; CI (`.github/workflows/ci.yaml`) runs the commands above plus a Trivy scan. Gradle dependency locking is enabled (`lockAllConfigurations()`).

## Architecture

Modules (`modsync-<name>`, base package `de.alexandermora.modsync.<name>`):

- `domain` (identities, manifests, operations) and `protocol` (versioned wire DTOs, HTTPS transport, atomic config writes) are shared by everything and must stay Spring-free.
- `scanner` (read-only JAR/metadata hashing), `modrinth` (hash identification, bounded/rate-limited API client), `resolver` (merges server manifest with personal profiles, validates pins/deps/collisions) are pure libraries.
- `agent` scans local `mods/` and uploads fenced inventories outbound-only; `client` (Picocli) plans, downloads with SHA-512/CRC verification, applies atomically with backups and a journal; `client-tui` is a separate JLine distribution; `server` is the only component touching MariaDB (Flyway migrations in `src/main/resources/db/migration`).
- `test-fixtures` builds synthetic JARs for tests.
- Server code is split into feature packages (`auth`, `agent`, `inventory`, `manifest`, `profile`, `catalog`, `system`, `common`, `config`, `observability`), each with a `*Controller`, `*Service` and (often) `*Persistence` (plain JDBC).

Big-picture flow: agent uploads a snapshot (invisible until `commit`) → admin classifies artifacts and publishes an immutable numbered manifest → client builds a deterministic plan, re-verifies online authority right before apply, then applies/rolls back. Offline commands (`mods scan`, `doctor`, `sync cached`) never authenticate or claim "current".

## Conventions

- Boundaries are enforced by `scripts/check-architecture.py`: domain/protocol/agent/client/scanner/resolver sources must not reference `org.springframework`, `org.mariadb` or the server module; agent/client containers must not mount the Docker socket; agent compose is read-only with no published ports.
- Controllers are thin, take typed request bodies and return DTOs directly (not `ResponseEntity`). Adding/changing a route or shared DTO in `domain/Domain.kt` or `protocol/Protocol.kt` requires regenerating `docs/architecture/openapi.json` via `scripts/openapi.py` (it parses controller `@RequestMapping`/`@Get|Post|PutMapping` annotations by regex, so keep that annotation style).
- Security posture is part of the design: never execute or extract JARs, refuse symlinks, never disable TLS verification, never delete unmanaged player files, and keep ownership (managed) separate from verified hashes. Components only make outbound HTTPS calls; A and C never talk to each other.
- Protocol version is 1; the server rejects `X-ModSync-Version` below 0.2.0 with 426.
- Client instance names (`instances add`) must match `[A-Za-z0-9_-]{1,80}`; the instance's Minecraft/loader metadata in `<instance>/.modsync/instance.json` must equal the manifest's or planning stops.
- Native-image reflection config lives in `src/main/resources/META-INF/native-image/` of `agent` and `client`; update it when adding reflectively-accessed types.
- Docs are extensive (`docs/GUIDE.md`, `ARCHITECTURE.md`, `docs/USAGE.md`, `docs/architecture/API.md`); update the relevant one when changing commands, API or deployment behavior.
