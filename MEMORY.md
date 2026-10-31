# Continuity record

## Decisions

- New local monorepo; no relationship to neighboring `olp-server` and no remote repository creation.
- Separate Host A outbound scanner, Host B Spring/MariaDB control plane, Host C standalone client.
- Local Argon2id accounts; UUID identities; assignment-generation fencing; staged idempotent inventory commits; explicit immutable manifest approval.
- Private/unrecognized artifacts use exact local manual resolution, not binary hosting.
- Java/GraalVM 25 target, Kotlin 2.2.21, Spring Boot 4.0.9, MariaDB 11.4 LTS. Patch/image compatibility still requires builds and scans.
- Standard CLI excludes JLine; TUI is a separate JVM distribution. No optional GUI.

## Implemented source

Ten Gradle modules; domain/protocol; sequential ZIP/TOML/JSON scanner; exact-pin resolver; bounded Modrinth integration; outbound agent with enrollment/retry state and ownership confirmations; JDBC/Flyway schema and role-protected API; profiles/devices; client planning/staging/hash/CRC/journal/rollback; separate Docker stacks; backup/import scripts; TLS distributed fixture; native/release workflow definitions and per-master-commit reconciliation.

## Verification actually executed

- Repository and toolchain inspection; neighboring repository left untouched.
- Python architecture checks: passed.
- Four Python release reconciliation tests: passed.
- Three backup transport tests with a fake Docker/MariaDB executable: passed, including quoted secrets, moved backup paths, corruption rejection and overwrite refusal. These do not constitute a database restore drill.
- Python source compilation: passed.
- Shell syntax checks: passed.
- Java health probe compiled with `javac --release 25`: passed using the available JDK 26 compiler.
- Server and agent Compose configuration validation: passed with non-secret placeholder paths.
- Local Markdown link checks and `git diff --check`: passed at the recorded review stage.
- `./gradlew check`: blocked before Gradle download by DNS resolution failure for services.gradle.org.
- Docker daemon check: denied by sandbox socket permissions.

No Kotlin test, migration, container, TLS fixture, native executable, release publication or performance benchmark is reported as passed. Java 26 is available locally; JDK 25/GraalVM/Gradle are unavailable. The managed runtime status tool and network-policy file are absent. Do not bypass these restrictions.

## Remaining gates and limitations

1. Obtain an authorized runner with JDK 25, Gradle/Maven network access and Docker; compile/fix all modules and execute unit/MariaDB/TLS fixtures.
2. Generate official Gradle wrapper JAR, dependency locks/verification metadata; pin verified image digests/action SHAs; run supply-chain scans.
3. Execute native CLI and agent operations on all supported architectures, including real synchronization rather than help-only smoke tests.
4. Complete Windows state ACL handling, crash/power-loss fault injection, exhaustive authorization tests and security review.
5. Verify generated OpenAPI against live endpoints; implement bounded catalog persistence, upload/backup retention, pagination and configurable freshness thresholds.
6. Embedded providers and opt-in optional mods are implemented but unverified. Automatic compatible dependency expansion remains conservative: missing exact pins block plans.
7. `modsync interactive --tui-home DIR` launches the separate JVM TUI without adding JLine to the standard CLI; runtime verification remains outstanding.
8. No Crafty API adapter is implemented; official v2 documentation was located, but filesystem scanning remains the supported integration.
9. Representative CPU/RSS/throughput/sync benchmarks are not measured. Hashes are recomputed each scheduled scan.
10. Set up approved GitHub remote/protections/registry and run publication only after explicit authorization/configuration. No PR URL can exist without a remote.

## Next steps

Run the documented build on a capable runner, fix concrete failures, execute the restoration drill and platform tests, then update this file and the MVP checklist with evidence. Do not call the MVP production-ready while those gates remain open.

Review follow-up: instance metadata is now explicit and validated against manifests; verified hashes are separate from managed ownership. Backup shell quoting was corrected and exercised by the three passing transport tests. Kotlin behavior remains unverified.

Scanner review: normalized metadata is capped globally at 2 MiB to bound memory; duplicate content/mod IDs now carry explicit evidence. Oversized scans fail without replacing prior inventory.

API contract: generated OpenAPI 3.1 schemas from shared DTOs plus explicit routes; the generator checks controller route drift. Generation/check passed; live endpoint conformance remains unverified.
