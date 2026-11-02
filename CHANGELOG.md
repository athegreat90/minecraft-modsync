# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Development prereleases are
published automatically from `main` as `v<version>-dev.<UTC timestamp>.<sha>` (see `RELEASES.md`).

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
