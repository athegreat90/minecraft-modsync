# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Development prereleases are
published automatically from `main` as `v<version>-dev.<UTC timestamp>.<sha>` (see `RELEASES.md`).

## [Unreleased]

## [0.3.1] - 2026-11-06

### Changed
- Release version, tag prefix (`v0.3.1-dev.`), Docker defaults, User-Agent and OpenAPI `info.version` bumped to 0.3.1. Wire protocol 1 and minimum component compatibility are unchanged.
- Line endings are enforced as LF (`.gitattributes` `* text=auto eol=lf`, `.editorconfig` `end_of_line = lf`).

### Fixed
- IDE imports no longer fail dependency verification: source, javadoc and Gradle-source archives are trusted in `gradle/verification-metadata.xml`; all jars, poms and modules stay checksum-verified.
- Native builds no longer fail dependency verification for `graalvm-reachability-metadata-0.11.5-repository.zip`.
- Release reconciliation only dispatches commits from `d9f75f5` onward (older commits cannot build the current release pipeline) and recognizes any `vX.Y.Z-dev.` tag as released.
- Docker images upgrade `apt`/`libapt-pkg6.0t64` so the Trivy release scan no longer fails on CVE-2026-109518.

## [0.3.0] - 2026-11-06

### Added
- Client inventory: `POST /api/v1/client/inventory` stores a per-instance, revisioned and idempotent inventory (hashes, file names only, recognized Modrinth ids, managed ownership, applied revision, sync policy, activity, selected personal profile) in `client_instance_inventory` / `client_instance_mods` (migrations V2 and V4).
- Coverage matrix: `GET /api/v1/admin/servers/{id}/coverage` compares exact SHA-512 hashes and reports `UP_TO_DATE`, `MISSING_MODS`, `WRONG_VERSION`, `INCOMPATIBLE`, `SYNC_PENDING`, `SYNCING`, `PENDING_GAME_EXIT`, `SYNC_FAILED`, `OFFLINE`, `INVENTORY_STALE` and `UNKNOWN` with two queries per server.
- Automatic manifest publication per server (`MANUAL`, `REVIEW_UNKNOWN`, `TRUSTED_AUTO`) evaluated after each snapshot commit, with publication history, no duplicate revisions when client requirements are unchanged, `PUT /api/v1/admin/servers/{id}/publication-policy` and `GET /api/v1/admin/servers/{id}/publications` (migration V3).
- Client `modsync auto enable|disable|status|run|schedule`: opt-in per instance, install/update/managed-removal policies, deferral while Minecraft runs, personal-profile support, bounded jitter/backoff, systemd and Task Scheduler templates; reuses the existing resolver, installer, journal and rollback.
- Thymeleaf administration dashboard at `/ui` (server overview, inventory, manifest, coverage, publication history, policy and classification forms) with a separate session, CSRF and CSP protected filter chain.
- Quality tooling: JaCoCo, SonarScanner for Gradle, Detekt 2.0.0-alpha.6 (legacy baseline), Spotless/ktlint (changed files), Dokka with `failOnWarning`, Gradle dependency verification, and a SonarQube CI job that reaches the tailnet through the Tailscale GitHub Action.
- Distributed fixture stage covering automatic publication, Host C installation into the right instance and Host B coverage.

### Changed
- Docker images and workflows use Gradle 9.8.2 (matches the repository bootstrap and dependency checksums); shell scripts are forced to LF through `.gitattributes`.
- Release and commit validation also run Detekt, Spotless and Dokka.
- Release version, tag prefix (`v0.3.0-dev.`), Docker defaults and OpenAPI `info.version` bumped to 0.3.0. Minimum component compatibility stays 0.2.0; wire protocol 1 is unchanged.

### Fixed
- The API filter chain no longer re-authorizes error dispatches, so clients receive the real 404, 409 and 426 statuses instead of 401.
- Added `gradle/verification-metadata.xml`, resolving SonarQube finding `kotlin:S6474`.

### Documentation
- New `docs/development/` guides (code quality, SonarQube, Kotlin) and updates to README, ARCHITECTURE, DISTRIBUTED_ARCHITECTURE, DATABASE, DOCKER, SECURITY, API, USAGE, RELEASES, CLIENT_PERFORMANCE and MEMORY.
## [0.2.1] - 2026-11-02

### Changed
- Fixed all compiler warnings (redundant `!!` in tests).
- Use Kotlin multi-dollar string interpolation (`$$"..."`) for picocli/Spring placeholders instead of escaped `\$`.
- Release version, tag prefix (`v0.2.1-dev.`), Docker defaults and OpenAPI `info.version` bumped to 0.2.1. Minimum component compatibility stays 0.2.0.

## [0.2.0] - 2026-11-02

### Changed
- **Breaking (components):** the server now requires `X-ModSync-Version` >= `0.2.0`; older clients and agents receive HTTP 426. Wire protocol (1) and `/api/v1` routes are unchanged.
- Renamed the base package `net.modsync` to `de.alexandermora.modsync` in all modules, Gradle group, native-image resources, scripts and docs.
- Gave each module its own root package (`de.alexandermora.modsync.<module>`).
- Split the server god-classes (`Controllers.kt`, `Store.kt`, `Manifests.kt`) into feature packages (`auth`, `agent`, `inventory`, `manifest`, `profile`, `catalog`, `system`, `common`, `config`, `observability`) with one controller and service per feature, applying SOLID principles.
- Controllers take typed `@RequestBody` DTOs and return objects directly instead of `ResponseEntity`; JSON uses the kotlinx-serialization converter (`spring.http.converters.preferred-json-mapper`).
- `/admin/agents` returns a typed `AgentView`.
- Component version, release tag prefix (`v0.2.0-dev.`), Docker `RELEASE_VERSION` defaults and the OpenAPI `info.version` bumped to 0.2.0.

### Fixed
- `scripts/openapi.py` now scans all feature controllers (including class-level `@RequestMapping` prefixes) and reads the relocated `Domain.kt`/`Protocol.kt`.

### Documentation
- Updated `docs/GUIDE.md`, `ARCHITECTURE.md`, `docs/architecture/API.md` and `RELEASES.md` for the new package layout and version.

## [0.1.0] - 2026-10-31 to 2026-11-02

Initial development line (tagged as `v0.1.0-dev.*` prereleases).

### Added
- Initial distributed Minecraft ModSync implementation: protocol, domain, scanner, resolver, Modrinth integration, agent, client, and Spring Boot server with MariaDB/Flyway storage.
- Automatic, gated development prereleases from `main`, with draft/asset verification before publication and checks of effective GHCR push and release permissions.
- Native executable client and agent images (Picocli reflection metadata registered).
- Project guide, README overview, command-by-command usage runbook, client install details.
- Per-phase deploy scripts, support for an external MariaDB in `01-server`, and running deploy scripts from GHCR images without the repository.
- Branch policy, validated CI and pinned Trivy scanning.

### Fixed
- Build alignment: Kotlin 2.3.21 with Java 25, Picocli API exposure, Spring JDBC/security nullability.
- Boot 4 Flyway startup, client-only metadata read as string with retained validation errors.
- Release scripts preserve native version arguments across platforms.
- Reusable Modrinth fixture HTTP connections and isolated fixture/TLS tests.

### Security
- Upgraded Bouncy Castle and removed vulnerable components from release images; updated runtime image security packages.
