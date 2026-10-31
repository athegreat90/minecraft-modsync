# Continuity record

## Decisions

- Monorepo is independent of neighboring `olp-server`. User created https://github.com/athegreat90/minecraft-modsync; source was published through the connected GitHub integration. Local origin now points there.
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
10. Configure GitHub branch protections/registry and run application release publication only after runtime validation and explicit release configuration. Source publication is complete; no application release is claimed.

## Next steps

Run the documented build on a capable runner, fix concrete failures, execute the restoration drill and platform tests, then update this file and the MVP checklist with evidence. Do not call the MVP production-ready while those gates remain open.

Review follow-up: instance metadata is now explicit and validated against manifests; verified hashes are separate from managed ownership. Backup shell quoting was corrected and exercised by the three passing transport tests. Kotlin behavior remains unverified.

Scanner review: normalized metadata is capped globally at 2 MiB to bound memory; duplicate content/mod IDs now carry explicit evidence. Oversized scans fail without replacing prior inventory.

API contract: generated OpenAPI 3.1 schemas from shared DTOs plus explicit routes; the generator checks controller route drift. Generation/check passed; live endpoint conformance remains unverified.

## GitHub source publication

The user created the public `athegreat90/minecraft-modsync` repository and explicitly requested uploading its contents. Terminal Git remains unable to resolve github.com, so the GitHub integration initialized the repository and imported all 97 tracked files. Remote import commit `430c6274f0dc0e84547bf69ab2dfb3c83786c1c6` has tree `6e5be5a03afa8591511e230b036b69e03f026930`, exactly matching local source commit `63d1ccaa7f56de025c953daa8f03460d7ee18561`. Executable modes were preserved.

Remote branches `main`, `master`, and `feature/distributed-mvp` contain the source. GitHub's default remains `main`; workflows use `master` as the canonical release branch. No release-enable variable, stable release or deployment was activated. CI status is not claimed. A documentation follow-up records this publication.

The API import has a different commit history from the four original local commits, which remain intact. Do not force-push the local history over it. Once terminal network access is restored, fetch and reconcile the histories or use a fresh clone for subsequent Git pushes. No upstream tracking was set against an unavailable local remote object.

## Main branch policy follow-up

User changed the canonical branch requirement to `main` and requested branch protection plus deletion of `master` if identical. GitHub confirmed `main` is already default, and the comparison reported identical commits at `198ba186ff6ef5a8d9b0c3af14f313848f613d60` with zero file differences. Release workflows/reconciliation now target `main`; CI validates main pushes and pull requests.

The current connector cannot update repository administration, protection or delete refs, and terminal `gh` cannot reach api.github.com. Protection and remote master deletion are therefore NOT reported complete. A reviewed administration helper can apply baseline protection and retire the legacy ref after verifying no unique commits or application-code differences. Existing protections are retained rather than overwritten. Publication uses a pull request; do not bypass protections or force-push.

Branch-policy validation: four new repository-policy tests and the four existing release-reconciliation tests passed; Python compilation and OpenAPI drift checks passed. Live administration helper stopped on the initial GitHub read due to network failure, before any settings change or branch deletion.

## CI action resolution correction

Linked job 124672880701 (run 41247166000) and PR job 124674002739 (run 41247543629) both failed during runner setup because `aquasecurity/trivy-action@0.33.1` could not be resolved. CI and release workflows now pin the verified upstream v0.36.0 commit `ed142fd0673e97e23eac54620cfb913e5ce36c25`. Its action.yaml and official release commit were verified through GitHub. Build/test steps never ran in the failed jobs; a new PR run is required to verify later stages.
