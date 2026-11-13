# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Development prereleases are
published automatically from `main` as `v<version>-dev.<UTC timestamp>.<sha>` (see `RELEASES.md`).

## [Unreleased]

### Added
- Administrator user management at `/ui/users`: paged search and filtering, user creation (CLIENT role), disable/enable, password reset, session revocation and permanent device revocation, with flash feedback, a shared layout fragment, a skip link and focus/alert styles. Logic lives in `users.UserManagementService`; `UserManagementController` only renders and binds explicit parameters.
- `users.enabled` column (Flyway `V5__user_status.sql`, additive, existing accounts stay enabled). Disabled accounts are refused by API login, refresh, bearer authentication and the dashboard login with the same response as a wrong password.
- `auth.AccountPolicy` (shared username/password rules and role constants) and `common.MaintenanceMode` (shared maintenance switch used by the dashboard and user management).
- Dashboard sessions are tracked in a `SessionRegistry`, so disabling an account or resetting its password expires its live sessions; expired sessions are sent to `/login?expired`.
- Audit actions `user-disabled`, `user-enabled`, `user-password-reset`, `user-sessions-revoked`, `user-devices-revoked`.

### Changed
- Dashboard session cookies are now `HttpOnly`, `SameSite=Strict` and `Secure` by default with a 30 minute timeout. Plain-HTTP setups must set `MODSYNC_SESSION_COOKIE_SECURE=false`.
- `overview.html` and `server.html` use the shared header fragment; the "All servers" back link is replaced by the main navigation.

## [0.3.2] - 2026-11-07

### Changed
- KDoc unified on the `AutoSync.kt` style across all main sources: third-person summary, optional detail paragraph, then `@param`/`@return`/`@property` tags, including private functions. REST controllers (endpoint purpose, required role, parameters, returned DTO), Spring configuration and security classes are now documented. Comments only; no behavior change.
- Release version, tag prefix (`v0.3.2-dev.`), Docker defaults and OpenAPI `info.version` bumped to 0.3.2. Wire protocol 1 and minimum component compatibility are unchanged.

### Added
- KDoc style convention in `docs/development/CODE_QUALITY.md`.
- Unit tests for server services and helpers (`SupportTest`, `CredentialServiceTest`, `AuditServiceTest`, `AuthServiceTest`, `AgentServiceTest`, `InventoryServiceTest`, `ManifestServiceTest`, `ProfileServiceTest`, `CatalogServiceTest`), `ModrinthTest` and `FixturesTest`.

### Fixed
- IntelliJ Build/Rebuild no longer fails with `Dependency verification failed for org.apache.groovy:groovy:4.0.33` or `groovy-ant`/`groovy-bom` 4.0.33: IDEs resolve the Groovy bundled with Gradle 9.8.2 from the distribution `lib/` ("Gradle Libs"), and the set of modules varies, so `org.apache.groovy` at version 4.0.33 is trusted in `gradle/verification-metadata.xml` (other Groovy versions stay checksum-verified; documented in `docs/development/CODE_QUALITY.md`).
- Sonar new-code coverage: the bumped `User-Agent` lines in `Modrinth` and `Installer.download` are now exercised by tests (`ModrinthTest` against a local HTTP server, and `InstallerFailureTest` with an injectable HTTP client: `Installer` takes an optional `newHttp` factory, default behavior unchanged).
- Detekt baselines refreshed: entries for now-documented declarations removed; remaining findings re-keyed after reformatting.

## [0.3.1] - 2026-11-06

### Changed
- Release version, tag prefix (`v0.3.1-dev.`), Docker defaults, User-Agent and OpenAPI `info.version` bumped to 0.3.1. Wire protocol 1 and minimum component compatibility are unchanged.
- Shared Gradle configuration moved from the root `subprojects { apply(plugin = …) }` block into the `modsync.conventions` precompiled script plugin in the new `build-logic` included build; modules apply it with `id("modsync.conventions")`. This removes the deprecated `apply` plugin syntax warnings; the script uses imports and typed accessors for readability. Plugin versions now live in `build-logic/build.gradle.kts`, and dependency verification metadata and locks were refreshed.
- Line endings are enforced as LF (`.gitattributes` `* text=auto eol=lf`, `.editorconfig` `end_of_line = lf`).
- Diagnostics in the agent and client CLI (failure messages, agent scan-cycle status) and the server's loggers now use Kotlin Logging (`io.github.oshai:kotlin-logging-jvm` 8.0.6; `slf4j-simple` writes to stderr for the CLIs). Command results printed to stdout are unchanged.
- Catch-all `try/catch` blocks in the client (`AutoSync`, `Client`, `Installer`) and server (`ProfileService`, `AutoPublicationService`) now use `runCatching`; behavior is unchanged except that `runCatching` also catches `Error`s. `try/finally` blocks and selective catches are kept.

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
