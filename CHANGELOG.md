# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Development prereleases are
published automatically from `main` as `v<version>-dev.<UTC timestamp>.<sha>` (see `RELEASES.md`).

## [Unreleased]

## [0.4.3] - 2026-11-14

### Changed
- Pipeline speed. A release took about 72 minutes, 53 of them in `publish`: the Gradle build stage of the server and agent Dockerfiles ran once per target platform, and the linux/arm64 copy compiled under QEMU emulation (27 min for the server image, about 23 min for the agent). Both Dockerfiles now pin the build stage to `--platform=$BUILDPLATFORM`, so the platform-independent JVM artifact is built once natively; a local multi-arch build of the agent image takes 171 s and the arm64 image starts correctly.
- `release.yaml` is a job graph instead of one long chain: a fast `version` job validates the SHA and derives the version, then `validate`, `integration` (the distributed TLS fixture, previously the last step of `validate`) and the three `native` builds run in parallel, and `publish` needs all of them. No gate was removed.
- `ci.yaml` runs the distributed fixture as its own `integration` job beside `validate`, and the Sonar job no longer waits for `validate` (it still never runs for pull requests).
- Gradle upgraded from 9.8.2 to 9.9.1 (latest stable) in the bootstrap, wrapper properties, workflows and Dockerfiles. Gradle 9.9.1 embeds `kotlin-dsl` 6.7.13 and Kotlin 2.4.21 build-logic plugins, so `gradle/verification-metadata.xml` gains nine artifacts and the three `kotlin-dsl` entries move from 6.7.6; every new checksum was cross-checked against Maven Central companion checksums or the plugin portal's content-addressed URLs.
- Version bumped 0.4.2 -> 0.4.3 (patch: build and delivery tooling only; wire protocol 1 and minimum component compatibility are unchanged). Updated in the same files as the previous bump.

## [0.4.2] - 2026-11-14

### Fixed
- Release runs for historical `main` commits (for example `cd6c32d`, `7c29453`) built and tested successfully, then failed in `publish` with `Release version does not identify source commit`. `validate` derives the version from the checked-out historical source (`v0.3.2-dev...`), while `publish` deliberately runs the current publication tooling and compared the whole string with its own `v0.4.1-dev...`, so every version bump made all unreleased older commits unpublishable and the hourly reconciler kept retrying them. `publish.py` now compares only the commit-identifying `<UTC stamp>.<full sha>` suffix and still requires a well-formed `v<X.Y.Z>-dev.<14 digits>.<40 hex>` tag; a different SHA, timestamp or malformed tag is still rejected.

- The release reconciler retried `d9f75f5` every 6 hours forever (21 failed runs on three runners each). Its native build can never pass because Gradle dependency verification lacks the `graalvm-reachability-metadata` checksum, which was only added by the next commit. `RELEASE_FLOOR` now starts at `7c29453`.
- A commit that keeps failing is no longer retried at a fixed 6 hour interval: the cooldown doubles for each failed or timed-out attempt (6h, 12h, 24h, ...) up to 7 days. Cancelled runs do not count, and the reported `failed` list is unchanged.

### Changed
- Trivy is pinned to the latest release, v0.76.2 (previously the `trivy-action` default v0.70.0 for the source scan and `aquasec/trivy:0.67.2` for the image scan) in `ci.yaml`, `release.yaml` and `scripts/release/publish.py`. `aquasecurity/trivy-action` was already at its latest release, v0.36.0, pinned by commit SHA.
- Version bumped 0.4.1 -> 0.4.2 (patch: release tooling fix only; wire protocol 1 and minimum component compatibility are unchanged). Updated in `build.gradle.kts`, both Dockerfiles, `scripts/openapi.py` (and the regenerated `docs/architecture/openapi.json`), `scripts/release/reconcile.py` and its test, the User-Agent strings (`Installer.kt`, `Modrinth.kt` and their tests), `RELEASES.md`, `docs/deployment/README.md` and `docs/GUIDE.md`.

## [0.4.1] - 2026-11-14

### Fixed
- Activating ModSync on a client could leave several versions of the same mod installed. The planner matched installed files to manifest artifacts by file name only, so an older jar with a different file name was skipped ("Preserve unmanaged") and the new version was installed next to it; automatic synchronization then reported `UpToDate` because a plan of `SKIP` operations has no changes, and an unchanged manifest revision skipped scanning entirely. Mods are now identified by the mod ids declared in their metadata.

### Added
- `ModVersion` / `VersionRange` (domain): Minecraft-mod-aware version ordering (numeric segments, `1.21.1-3.2.0` prefixes, `alpha`/`beta`/`rc`/`patch` qualifiers, build metadata ignored) and Maven-style ranges. Ordering that cannot be established is `INCOMPARABLE` and becomes a conflict, never a guess.
- `ModInfo` (id, version, Minecraft and loader ranges) on `ModEntry.mods` and `Artifact.mods`. The scanner reads them from `neoforge.mods.toml`, `mods.toml` and `fabric.mod.json` and resolves `${file.jarVersion}` from `MANIFEST.MF`; the server copies the scanned mods onto published artifacts. Both fields are additive; manifests published earlier simply carry no identity.
- `OperationKind.QUARANTINE_DUPLICATE` and `SyncPlan.approvedRemovals`; `modsync sync plan --approve-removal FILE` (repeatable) approves moving an unmanaged older duplicate to a recoverable backup. The plan explains the kept version, the older file and whether approval is required.
- `modsync sync verify` fails and lists `DUPLICATE mod <id> is provided by ...` when several installed files provide the same mod.

### Changed
- Older duplicates owned by ModSync are removed with the existing journal, backup and rollback; unmanaged ones require approval and are never removed by automatic synchronization. A newer installed version than the one the server pins is a `CONFLICT` (no silent downgrade). Duplicates without a manifest pin keep the highest version that verifiably supports the instance's Minecraft/NeoForge version, otherwise they are conflicts. Multi-mod jars, missing versions and unknown version formats are always conflicts.
- `Installer.apply` inspects the downloaded jars and refuses, before changing any file, an installation that would leave two files for the same mod id, whatever the manifest says.
- Automatic synchronization stores a cheap fingerprint of the mods folder (names, sizes, modification times) and rescans when it changes, even at an unchanged manifest revision; settings without a fingerprint force one full pass. A pass reports `Pending` (inventory activity `SYNC_PENDING`) naming the blocking files instead of `UpToDate` while duplicates remain.
- Version bumped 0.4.0 -> 0.4.1 (patch: bug fix with additive fields and one additive CLI option; wire protocol 1 and minimum component compatibility are unchanged). Updated in `build.gradle.kts`, both Dockerfiles, `scripts/openapi.py` with the regenerated `docs/architecture/openapi.json`, `scripts/release/reconcile.py` and its test, the `User-Agent` strings of `Modrinth` and `Installer` with their tests, and the version examples in `RELEASES.md`, `docs/deployment/README.md` and `docs/GUIDE.md`.

## [0.4.0] - 2026-11-13

### Added
- Administrator user management at `/ui/users`: paged search and filtering, user creation (CLIENT role), disable/enable, password reset, session revocation and permanent device revocation, with flash feedback, a shared layout fragment, a skip link and focus/alert styles. Logic lives in `users.UserManagementService`; `UserManagementController` only renders and binds explicit parameters.
- `users.enabled` column (Flyway `V5__user_status.sql`, additive, existing accounts stay enabled). Disabled accounts are refused by API login, refresh, bearer authentication and the dashboard login with the same response as a wrong password.
- `auth.AccountPolicy` (shared username/password rules and role constants) and `common.MaintenanceMode` (shared maintenance switch used by the dashboard and user management).
- Dashboard sessions are tracked in a `SessionRegistry`, so disabling an account or resetting its password expires its live sessions; expired sessions are sent to `/login?expired`.
- Audit actions `user-disabled`, `user-enabled`, `user-password-reset`, `user-sessions-revoked`, `user-devices-revoked`.

### Changed
- Dashboard session cookies are now `HttpOnly`, `SameSite=Strict` and `Secure` by default with a 30 minute timeout. Plain-HTTP setups must set `MODSYNC_SESSION_COOKIE_SECURE=false`.
- `overview.html` and `server.html` use the shared header fragment; the "All servers" back link is replaced by the main navigation.
- Version bumped 0.3.2 -> 0.4.0 (minor: new user-facing feature, one additive migration and a session-cookie default change; wire protocol 1 and minimum component compatibility 0.2.0 are unchanged). Updated in `build.gradle.kts`, both Dockerfiles, `scripts/openapi.py` with the regenerated `docs/architecture/openapi.json`, `scripts/release/reconcile.py` and its test, the `User-Agent` strings of `Modrinth` and `Installer` with their tests, and the version examples in `RELEASES.md`, `docs/deployment/README.md` and `docs/GUIDE.md`.

### Fixed
- `UserManagementIntegrationTest.listSearchesFiltersAndPages` was nondeterministic: it disabled users whose random username suffix ended in `0`, so about 17% of runs disabled nobody and failed the disabled-filter assertion. It now disables exactly three users deterministically and asserts the exact count. The flake failed the post-merge `validate` (Sonar) job and the dev-release validation of `cd6c32d` on `main`.

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
