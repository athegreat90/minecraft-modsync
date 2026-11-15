# Continuity record

## Decisions

- Monorepo is independent of neighboring `olp-server`. User created https://github.com/athegreat90/minecraft-modsync; source was published through the connected GitHub integration. Local origin now points there.
- Separate Host A outbound scanner, Host B Spring/MariaDB control plane, Host C standalone client.
- Local Argon2id accounts; UUID identities; assignment-generation fencing; staged idempotent inventory commits; explicit immutable manifest approval.
- Private/unrecognized artifacts use exact local manual resolution, not binary hosting.
- Java/GraalVM 25 target, Kotlin 2.4.21, Spring Boot 4.1.2, MariaDB 11.4 LTS. Patch/image compatibility still requires builds and scans.
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

At the initial local-only checkpoint, no Kotlin test, migration, container, TLS fixture, native executable, release publication or performance benchmark had executed. Subsequent GitHub CI evidence recorded below supersedes that initial build/test limitation. Java 26 is available locally; JDK 25/GraalVM/Gradle are unavailable. The managed runtime status tool and network-policy file are absent. Do not bypass these restrictions.

## Remaining gates and limitations

1. Obtain an authorized runner with JDK 25, Gradle/Maven network access and Docker; compile/fix all modules and execute unit/MariaDB/TLS fixtures.
2. Generate official Gradle wrapper JAR, dependency locks/verification metadata; pin verified image digests/action SHAs; run supply-chain scans.
3. Execute native CLI and agent operations on all supported architectures, including real synchronization rather than help-only smoke tests.
4. Complete Windows state ACL handling, crash/power-loss fault injection, exhaustive authorization tests and security review.
5. Verify generated OpenAPI against live endpoints; implement bounded catalog persistence, upload/backup retention, pagination and configurable freshness thresholds.
6. Embedded providers and opt-in optional mods are implemented but unverified. Automatic compatible dependency expansion remains conservative: missing exact pins block plans.
7. `modsync interactive --tui-home DIR` launches the separate JVM TUI without adding JLine to the standard CLI; verified on Windows in 0.4.7 (banner shown, clean exit), earlier releases failed with `InvalidPathException` and a wrong main class.
8. No Crafty API adapter is implemented; official v2 documentation was located, but filesystem scanning remains the supported integration.
9. Representative CPU/RSS/throughput/sync benchmarks are not measured. Hashes are recomputed each scheduled scan.
10. Configure GitHub branch protections/registry. Development prereleases publish automatically on main after all workflow gates; stable releases and deployment remain separate. No application release is claimed until a visible prerelease and its assets/digests are verified.

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

Follow-up run 41247750936 passed runner/action setup but failed at compileKotlin: Kotlin 2.2.21 only supported JVM 24 while the Java toolchain targeted 25. Kotlin plugins/test dependencies/catalog are upgraded to 2.3.21, a runtime BOM aligns Kotlin libraries, and JVM_25 is explicit. Official Kotlin 2.3 documentation confirms Java 25 bytecode support. CI evidence uploads now include Gradle problem reports and the explicitly selected hidden integration results file; fixture secrets are not selected. Runtime validation is pending the next run.

Run 41248030743 advanced beyond JVM targeting and reported three compile errors: TUI could not access the Picocli type returned by the client API, actor() ignored nullable Spring authentication, and JDBC query maps declared non-null values despite nullable SQL columns. Picocli is now an API dependency of the client library; missing actor authentication returns 401; JDBC maps accurately allow nullable values. Further CI validation is pending.

Run 41248284463 compiled application sources and passed 21 scanner/protocol/resolver/installer tests. Server test compilation found conflicting wildcard imports of JUnit Test and kotlin.test.Test; JUnit imports are now explicit. Review also found first snapshot acknowledgements retained nanoseconds while MariaDB TIMESTAMP(6) only stores microseconds: commit timestamps now truncate before persistence/response, and the existing idempotency integration test additionally checks precision. Database execution is still pending.

Run 41248471822 passed Gradle/package/database/static checks and reached the distributed fixture. Startup dependency review against official Spring Boot 4 documentation found that Flyway core alone no longer supplies auto-configuration and Spring Kotlin requires kotlin-reflect. The server now uses spring-boot-starter-flyway plus kotlin-reflect; a fresh-database application startup test checks actual auto-migration and administrator bootstrap. Fixture subprocess failures/readiness timeouts now retain bounded diagnostics, and Gradle emits full test failure exceptions.

The distributed failure in run 41248471822 occurred at Docker port lookup after backend exit; captured process output was previously discarded. Port-lookup failures now include the bounded backend log tail. That run successfully executed all existing Gradle checks, including MariaDB tests, and all Python/contract/boundary checks before the fixture failure.

Run 41248930631 passed 26 Kotlin/database tests, including the fresh backend auto-migration test, then revealed a fixture networking error: Docker did not publish port 8443 for a backend attached exclusively to internal bridges. The fixture now gives only backend instances an additional ordinary ingress bridge for their localhost-published administration port. Agent/client/data/metadata networks remain separate and internal. Port discovery has a bounded startup retry. No production networking assumptions or TLS checks were relaxed.

Run 41249375173 passed the complete JVM build/package checks, 26 Kotlin/MariaDB tests, 11 Python tests, API/boundary checks and the entire distributed HTTPS fixture: initial/repeated synchronization, server-only filtering, personal profile portability, old-agent fencing, network interruption, backend/DNS migration, MariaDB export/import and rollback. The final security scan failed solely on `.local/integration/pki/key.pem`, a disposable certificate key generated by that fixture. CI and release validation now scan source before fixture key generation; secret/vulnerability scanners, severity and failure exit status are unchanged, and no scan exclusions were added. A fresh full CI run is pending. Native/ARM64 execution, production image scans, releases and performance benchmarks remain unverified.


## Automatic development publication follow-up

Removed the release-enable repository-variable condition from reconciliation and
validation. Latest eligible main is selected first, then reachable historical
commits, including merged commits; active jobs are skipped and failed/canceled
attempts retain a six-hour cooldown from their last update. Native builds now
receive the same generated release version as JVM distributions and images.
Current publication tooling is used even for historical source builds.

Publication checks repository write and actual GHCR push authorization before
creating drafts/tags. Draft retries validate the source/tag, require all platform
and JVM assets, preserve image vulnerability gates and verify downloaded artifact
checksums, manifest and resolvable image digests before and after publication.
Visible releases are verified without mutation; reconciliation reports invalid
releases and failed runs rather than assuming every visible tag is complete.
See docs/deployment/README.md for operation and remaining gates.

Local release tests pass, including ordering/backfill/merge/cooldown/active-run
selection and mocked draft retry, tag mismatch, missing assets, permission denial,
image publication failure and full manifest/registry verification. Live publication
is still pending: terminal gh cannot reach api.github.com; the connected GitHub app
can publish source/PRs but has no new-workflow-dispatch tool. No prerelease has yet
been verified in this follow-up. Do not equate green CI or a draft with publication.


PR #2 (2083cdb7b5905932ae5b68c0a2addd1d5b18a922) CI run 41252836384
passed JVM/database, 20 release tests, repository/backup tests, API/boundary and
source security checks, then failed decoding an empty/non-JSON personal-profile
response in the distributed fixture. Application source matches the previously
passing main tree. A failed-job retry was requested; fixture diagnostics now report
status/response length/correlation and backend log tails on failure, without
printing response bodies or tokens. Root RELEASES.md also documents automatic
publication. Live release validation remains pending.


PR #2 merged as 8c72eab788c45e03c5e56a05ad868e1454dfdb00 after CI
41255153019 passed all JVM/database/Python/source-security/distributed gates.
The full fixture exposed a personal-profile metadata comparison; reading the
JSON string value directly preserved the client-only policy and passed the full
HTTPS/migration/restore/rollback fixture. HTTP regression and bounded fixture
failure diagnostics were added; synchronization DTOs/routes are unchanged.

Reconciliation run 41256231427 dispatched all 18 reachable commits with the new
main head first. A live rerun dispatched nothing, retained active jobs and
reported historical failures in cooldown. Head release run 41256251830 passed
validation, then Windows PowerShell split the unquoted dotted release-version
argument. The follow-up quotes the property and uses Bash consistently for the
native command. Native matrix fail-fast is disabled so all required platforms
report results; publication still requires every platform to succeed. First
visible prerelease and image publication remain pending.


Follow-up PR #3 merged as ae6d401f3d9070b3f511d08999b636e5254c32fa.
Reconciliation dispatched only the two newly reachable commits, latest main first;
earlier attempts remained in cooldown. Head release run 41257085759 passed full
validation and native compilation on Linux x64, Linux ARM64 and Windows x64.
Packaging then failed because the java-library plugin made GraalVM produce shared
libraries (.so/.dll), leaving no CLI executables to smoke-test. Native options now
explicitly select executable images and bind the application main class, retaining
no-fallback compilation and every platform startup/benchmark gate. First visible
prerelease remains pending.


PR #4 merged as c93d74061e3c3eb77476369f46cfa78048d9e125 after its JVM
build/database/configuration checks passed. Its later TLS fixture failure exposed
the actual intermittent profile problem: java.io.IOException, HTTP/1.1 header
parser received no bytes, during a metadata POST after a successful project GET.
The earlier JSON string-value change clarified the invariant but was not the
transport fix. The Python fake Modrinth server used HTTP/1.0 connection closure;
it now serves HTTP/1.1 with its existing explicit Content-Length framing so Java
can safely reuse the connection. TLS validation and all assertions are preserved.
Normal profile checkpoint logs are removed; exceptional metadata failures retain
diagnostics. Native executable builds and first visible publication are pending.


Executable native compilation reached startup on Linux ARM64 in release run
41258213876. The mandatory help smoke test exposed missing reflection metadata
for picocli.CommandLine$AutoHelpMixin, which is instantiated by standard --help
and --version options. Both application reflection configurations now register
this built-in class alongside their existing command classes. Native smoke tests
and benchmarks remain mandatory; no release was finalized from the failed run.

Publication preflight now tests the effective GitHub Actions contents permission by
creating and deleting a uniquely named temporary ref, then verifies push access for
both GHCR repositories. Repository metadata's permissions.push flag was a false
negative for the installation token. The first live probe confirmed the temporary
ref was deleted; GHCR access remains to be checked with the corrected preflight.

On main 8e0c6e0098d6a8df395fd9b24fc940f1a8576152, JVM/database, source-security,
and distributed integration validation passed, but native packaging still failed:
the Gradle Java Library plugin selected Graal shared-library output despite the
per-binary `sharedLibrary=false` setting. The CLI projects now use the Java plugin
and keep their APIs through the normal application/JVM distributions; executable
native outputs remain required and all smoke/benchmark gates remain enabled.

The effective GHCR permission probe obtained push-scoped upload sessions for both
images. GHCR returns HTTP 405 for DELETE on those upload sessions, so the probe now
uses successful scoped session creation as its authorization result and lets empty
sessions expire. No image layer or manifest is uploaded by the probe.

The first main image publication reached the immutable image scan but failed the
unchanged HIGH/CRITICAL gate. Trivy found stale Noble `libssl3t64`/OpenSSL and
`libbrotli1` packages in the pinned Temurin runtime image, plus four Bouncy Castle
findings in `bcprov` 1.82. An initial removal attempt broke Spring Security Argon2
startup because that encoder needs Bouncy Castle indirectly; the dependency remains
and is upgraded to official release 1.86, which includes the flagged fixes. Both
runtime Dockerfiles upgrade those installed OS packages from the current Noble
security repositories. The failed build left an unpublished zero-asset draft; the
patched validation, image scan and visible release are pending.

Both patched images passed the HIGH/CRITICAL Trivy scan and the historical 8b77bb6
publisher uploaded all 15 assets, but GitHub's release-by-tag endpoint returned 404
for its still-private draft. The workflow therefore stopped before checksum, manifest
and digest verification. The publisher now resolves both drafts and visible releases
from the paginated release list, which includes drafts, and tests the draft retry
path before publication. No draft has been made visible yet.

Draft asset verification also avoids `gh release download <tag>`: it downloads each
asset through the authenticated release-asset API by ID, then checks local bytes
against `SHA256SUMS` and the compatibility manifest. This keeps draft retry checks
working even though GitHub's release-by-tag endpoint does not resolve drafts.

## Automatic mod management and quality tooling (branch feat/auto-mod-management)

- Kotlin 2.4.21 / JVM 25 / Spring Boot 4.1.2 / Gradle 9.8.2 verified together; Docker images and workflows now use Gradle 9.8.2 because dependency verification metadata is Gradle-version specific.
- Added: client inventory (V2, V4), publication policy/history (V3), coverage matrix endpoint, auto publication, `modsync auto` (policies, game-running deferral, personal profile support), Thymeleaf dashboard (session + CSRF chain), Detekt 2.0.0-alpha.6 (stable 1.23.8 fails on JDK 25) with legacy baselines, ratcheted Spotless, Dokka, SonarScanner.
- Verified: `./gradlew spotlessApply check dokkaGenerate`; distributed fixture (`scripts/integration/distributed.py`, needs openssl/sh from Git for Windows on PATH and LF shell scripts) incl. the automatic publication -> Host C install -> coverage stage; SonarQube analysis against sonarqube.alexandermora.de with quality gate OK at the last run.
- Not verified: CI workflows themselves (including the Tailscale-based Sonar job, which needs the TS_OAUTH_CLIENT_ID/TS_AUDIENCE/SONAR_TOKEN secrets and a tag:ci ACL grant), native-image builds, Trivy, Sonar branch/PR analysis, performance of auto passes. `scripts/backup` tests fail on this Windows host only because Git's sh cannot find `python`.
- Known limits: Detekt baseline holds 349 legacy findings; private-function KDoc is by convention; auto mode detects a running game only via Java command lines containing the instance path.

## KDoc unification and IDE rebuild fix (0.3.2)

- Decision: `AutoSync.kt` is the KDoc reference style (summary, optional detail paragraph, `@param`/`@return`/`@property`; private functions documented; one-liners only for trivial types). Recorded in `docs/development/CODE_QUALITY.md`. REST controllers document purpose, required role, parameters and returned DTO while keeping the `@XMapping` annotation style that `scripts/openapi.py` parses by regex; configuration and security classes document what they wire.
- Decision: classes touched without a test class receive one (server services use Mockito unit tests; Modrinth/fixtures got `ModrinthTest`/`FixturesTest`). Sonar runs after `check` (`clean check sonar ... -Dsonar.qualitygate.wait=true`).
- Detekt baselines were regenerated with `detektBaseline` only because KDoc and Spotless changed the baseline signatures; entries for now-documented declarations were removed, and no new finding categories were hidden except pre-existing scanner complexity/lambda-name entries that were re-keyed.
- IDE rebuild failure `Dependency verification failed for org.apache.groovy:groovy:4.0.33` (then `groovy-ant`/`groovy-bom` 4.0.33): IDEs resolve every Groovy module from the Gradle distribution `lib/` ("Gradle Libs"), so per-artifact pins fail one module at a time. Decision: trust `org.apache.groovy` at 4.0.33 as a group in `gradle/verification-metadata.xml`; update that version when Gradle is upgraded. Command-line builds never resolve it. Not reproducible locally; the user re-syncs the IDE to confirm.
- Sonar new-code coverage was 75% (2 of 8 lines): only the version-bumped `User-Agent` lines in `Modrinth.call` and `Installer.download` were uncovered. Decision: cover them with tests (local `HttpServer` for Modrinth; `Installer` got an optional `newHttp` client factory so a `FakeHttpClient` can answer CDN downloads offline — a mid-line exception, e.g. a refused connection, does not count as line coverage for Sonar/Jacoco). Per-file new-code coverage can be read with `GET /api/measures/component?component=<key>:<path>&metricKeys=new_uncovered_lines,new_lines_to_cover` (the token cannot call `component_tree`).
- Decision: version bumped 0.3.1 -> 0.3.2 (patch: comments, docs, tests, build-integrity; no API or behavior change). Locations: `build.gradle.kts`, Dockerfiles, release scripts/tests, `scripts/openapi.py` + regenerated `openapi.json`, docs, User-Agent strings. `ApiFlowIntegrationTest` keeps its fixed component version `0.3.1` because the server only requires >= 0.2.0.
- Process note: do not run overlapping Gradle builds in the same checkout; a concurrent run deleted test result files and failed `:modsync-server:test`.

## Administrator user management (branch feature/ui-user-management)

- Decision: scope is CLIENT-only. The dashboard lists ADMIN rows read-only and the service rejects any change to a non-CLIENT row, so privilege escalation and "last active administrator" are unreachable by construction. No CLIENT web area (`/ui/me`), no self-registration, no ADMIN creation in the UI.
- Decision: accounts are disabled, never deleted (devices, profiles and history keep their references). `users.enabled` (V5, additive, default TRUE) is enforced in `AuthService.login`/`refresh`, the API `Guard` (join on `users`) and the dashboard authentication provider. Disabled accounts fail with the same 401 / `/login?error` as a wrong password and only after the password check; the dashboard uses a post-authentication check because Spring's default pre-check would answer faster for disabled accounts.
- Decision: dashboard sessions are tracked with Spring's `SessionRegistry` (unlimited concurrency) so `UserSessionRevoker` can expire them; expiry runs after the transaction commits. Expired sessions redirect to `/login?expired`. The registry is in memory, consistent with the single-instance deployment and the in-memory throttles.
- Decision: "disable" deletes credentials but keeps devices registered (enable restores access); "revoke devices" is permanent because `AuthService.login` answers 409 for a revoked device id, so it needs an explicit confirmation that the controller also enforces. Password reset is admin-typed with confirmation; generated temporary passwords and forced first-login change were deferred (they need a must-change column and a client change-password endpoint).
- Decision: session cookie defaults changed to `HttpOnly`, `SameSite=Strict`, `Secure=true` (override `MODSYNC_SESSION_COOKIE_SECURE=false` for plain HTTP) with an explicit 30 minute timeout. Tests set it to false because `java.net.CookieManager` drops Secure cookies over HTTP.
- Decision: tests follow the repository pattern (real server, `HttpClient`, MariaDB Testcontainers) instead of adding MockMvc or spring-security-test; shared helpers are `TestBrowser` and `ApiProbe` in `TestSupport.kt`.
- Verified: new tests pass against MariaDB: `UserManagementServiceTest` (10), `UserManagementIntegrationTest` (12), `AccountStatusIntegrationTest` (2), `UserStatusMigrationTest` (1); the complete `:modsync-server:test` suite passed (10 min 30 s), as did `detekt` and `spotlessCheck` for the server, `scripts/check-architecture.py` and `scripts/openapi.py --check`. A mutation check (making `RegistryUserSessionRevoker` a no-op) made `disableAndEnableLifecycle` fail, so the live-session expiry assertion is meaningful.
- Process notes: `UserManagementIntegrationTest` first failed in the full run because the dashboard limits sign-ins to 10 per minute per address and each test signed in separately (HTTP 429, empty `Location`); it now shares one administrator session. `:modsync-agent:test` failed once with `Cannot add default access-filter.json` (GraalVM agent options) in a first full run, passed on an identical rerun on this branch and on `origin/main`, and the agent module is untouched; treated as transient. Run Gradle through `python scripts/gradle.py` on this Windows machine (`./gradlew` calls `python3`, which is the Microsoft Store stub).
- Not verified: SonarQube (the CI `sonar` job runs only on `main`, needs Tailscale and `SONAR_TOKEN`), CI workflows, a manual browser pass with a screen reader or Lighthouse accessibility audit, timing parity of disabled versus wrong-password dashboard logins (designed for, asserted only through identical redirects), and behavior of real clients when their account is disabled.
- Known limits: dashboard login throttling is per address, not per username; failed logins are not audited; `login.html` still has an inline `style=` that the CSP blocks (pre-existing); no per-device revocation list in the UI.

## Release 0.4.0, merge and post-merge findings

- Decision: feature PR #21 was marked ready and squash-merged as `cd6c32d` because the repository history uses one squash commit per PR with a `(#N)` suffix; no AI attribution lines were added to commits or the PR body (user's global rule overrides the harness default). The feature branch was kept, not deleted.
- Decision: version bump 0.3.2 -> 0.4.0 (minor). Reasons: a new user-facing capability, an additive Flyway migration (V5) and a behavior change in session cookie defaults justify more than a patch; nothing is removed or incompatible, so not a major. Wire protocol 1 and `COMPONENT_VERSION` (minimum component compatibility 0.2.0) are unchanged because no API or DTO changed. Version locations changed: `build.gradle.kts`, both Dockerfiles, `scripts/openapi.py` plus regenerated `docs/architecture/openapi.json` (generated with `python scripts/openapi.py`, then `--check`), `scripts/release/reconcile.py` and `test_reconcile.py`, `Modrinth` and `Installer` `User-Agent` strings with their tests, `RELEASES.md`, `docs/deployment/README.md`, `docs/GUIDE.md`.
- Finding: post-merge CI on `main` failed. The `validate` workflow's Sonar job and the dev-release `validate` job for `cd6c32d` both failed on `UserManagementIntegrationTest.listSearchesFiltersAndPages` (line 185, `assertTrue(disabled.contains("badge bad"))`). Root cause: the test disabled users with `LIKE 'prefix%0'` over random hex suffixes, so with 27 users there is a (15/16)^27, about 17.5%, chance that none matches and the disabled filter returns nothing. It passed locally by luck. The PR's own `validate` run (which does not run Sonar) passed before the merge.
- Decision: fixed on branch `fix/user-list-test-flake` through a PR, never directly on `main`: the test now runs `UPDATE ... ORDER BY username LIMIT 3` and asserts the update count (3) and exactly three `badge bad` badges. It was run three times in a row locally before pushing. Lesson: integration tests must not derive their fixtures from random data that decides which rows match; assert counts that the test itself sets up.
- Finding (not caused by this work): the two dev-release runs on `3282a3c` failed in the `publish` job with `Release version does not identify source commit` (`scripts/release`), after `validate` and the three native builds succeeded. It predates this change and was not investigated or fixed here; it needs its own look before the next dev release can publish.
- Finding: the `sonar` job is skipped on pull requests and only runs on `main`, so SonarQube results for this feature become available only after a merge; this is why the flake surfaced late. Sonar's own quality-gate result for `cd6c32d` was not obtained because the job failed at the test step before analysis.
- Process notes: after the merge, `reconcile-main-releases` succeeded and dispatched the release run for `cd6c32d`, which failed at `validate` because of the flake. A scratch worktree of `origin/main` used to reproduce the agent-test failure could not be removed by git (`Filename too long`); it was deleted with `cmd /c rmdir /s /q` and pruned.

## Duplicate mod versions on the client (branch fix/client-mod-version-deduplication, release 0.4.1)

- Finding (root cause): `Resolver.plan` matched installed files to artifacts by file name only. An older jar with another file name was `SKIP` ("Preserve unmanaged") next to the freshly installed version, `AutoSyncRunner` returned `UpToDate` for plans without mutating operations, and an unchanged revision skipped scanning for 24 hours. Managed older jars were `REMOVE_MANAGED`, which the install/update policies reject.
- Decision: identity is the mod id from verified JAR metadata. `ModInfo(id, version, minecraftRange, loaderRange)` is read by the scanner (`ModMetadataParser`: neoforge.mods.toml, mods.toml, fabric.mod.json; `${file.jarVersion}` resolved from `MANIFEST.MF`) into `ModEntry.mods`, and the server copies the scanned mods of the hash-identical inventory file onto `Artifact.mods` (`withInventoryMods`). Both fields are additive. Modrinth `versionId` is never used for ordering.
- Decision: `ModVersion`/`VersionRange` (domain) order versions and return `INCOMPARABLE` instead of guessing; unknown qualifier words, placeholders, blank and 19+ digit segments are unparseable. Known limit: versions with a Minecraft prefix (`1.21.1-3.2.0`) compare correctly only against the same shape.
- Decision (policy): a pinned artifact always wins; older or equal duplicates are `REMOVE_MANAGED` when ModSync owns the file, `MANUAL_ACTION_REQUIRED` otherwise, and `QUARANTINE_DUPLICATE` after `sync plan --approve-removal FILE` (approval recorded in `SyncPlan.approvedRemovals`, so `Installer.apply` re-derivation matches). A newer installed version than the server pin, unestablished order, multi-mod jars and two selected artifacts with one mod id are `CONFLICT`. Without a pin the highest version that verifiably supports Minecraft (and the loader when the instance pins one) is kept; no verifiable compatibility means conflict. Automatic mode never removes unowned files.
- Decision: `Installer.apply` scans the downloaded jars and refuses, before any change, a result with two files for one mod id, so legacy manifests without `Artifact.mods` cannot install a second version. Removed files stay recoverable in the session backup and `sync rollback` restores them.
- Decision: `AutoSettings.modsFingerprint` (names, sizes, mtimes, no hashing) gates the unchanged-revision fast path; settings without it force one full pass. Duplicates among installed files (`duplicateMods`) block `UpToDate` even when the manifest cannot link them; the outcome is `Pending` and the inventory activity `SYNC_PENDING`.
- Decision: the CLI hides exception messages by design, so `sync verify` prints `DUPLICATE ...` lines to stdout before failing.
- Process: the repository `gradlew` needs `python3`; on this Windows machine use `python scripts/gradle.py <tasks> --offline`. Do not run two Gradle builds at once in one checkout.
- Verified: full `check` (JDK 25, Docker available so the MariaDB integration tests ran): 284 tests, 0 failures, 1 skipped (pre-existing), Detekt and Spotless clean on every module; `scripts/openapi.py --check`, `scripts/check-architecture.py` and the 10 release reconciliation tests pass. New tests: `ModVersionTest` (13), `ScannerModInfoTest` (6), `ModInventoryTest` (3), `ArtifactModsTest` (2), `DuplicateResolutionTest` (20), `InstallerDuplicateTest` (6), `AutoSyncDuplicateTest` (6), `ClientCommandsTest.verifyRejectsDuplicateMods`.
- Not verified / limits: manifests published before 0.4.1 carry no `Artifact.mods`, so the planner cannot link their artifacts to older installed files until a new revision is published (the installer safety net and `duplicateMods` still block); personal-profile artifacts carry no mod identity either; a failure in the middle of `apply` was not injected (the existing journal rollback tests cover it, plus a rollback after an approved replacement); the Gradle wrapper still depends on `python3`.
- Outcome (supersedes the earlier "Not verified: SonarQube" and "Sonar quality-gate result not obtained" notes): after PR #22 merged as `7b79a59`, `validate` passed including the Sonar job with `sonar.qualitygate.wait=true`, `reconcile-main-releases` passed, and the dispatched release run passed `validate`, the three native builds and `publish`, creating the prerelease `v0.4.0-dev.20261113150247.7b79a591f0307b82f15a242158a46525c84ee4fb`. The `Release version does not identify source commit` publish failure seen on `3282a3c` did not recur; its cause was never investigated, so it is unexplained rather than fixed.
- Still not verified: a manual browser or accessibility pass of the new pages, timing parity of disabled versus wrong-password dashboard logins, and the behavior of real clients when their account is disabled.

## TUI launcher fix and Sonar-gated releases (PR #31, branch fix/interactive-launcher, release 0.4.7)

- Changes: `modsync interactive` now builds the child JVM command in `interactiveCommand()` (class path `lib<sep>*` as a plain string, because `*` is illegal in a Windows `Path`; main class `de.alexandermora.modsync.client.tui.InteractiveKt`; `--enable-native-access=ALL-UNNAMED`). `modsync-client-tui` gained its first tests (9, via the extracted `runSession`). `reconcile.yaml` now starts from `workflow_run` of `validate` (success only) instead of the push; hourly/manual runs require a successful `validate` run for the tip of `main`. `gradle.properties` enables `org.gradle.tooling.parallel=true`.
- Verified: PR checks `validate` and `integration` passed (`sonar` is skipped on PRs by design). After the squash merge (`5f74e49`) `validate` passed including `sonar`; `reconcile-main-releases` did not run on the push itself and started only afterwards via `workflow_run`; it dispatched `development-release` for `5f74e49`, which passed `version`, `validate`, `integration`, the three `native` jobs and `publish`. Published prerelease: `v0.4.7-dev.20261114143733.5f74e499400cd7dd80d1a0b3b110a4b46fe53c4c`. Asset contents and image digests were not inspected separately.
- Manual check on Windows: the built client's `interactive --tui-home` showed the banner and exited 0. `doctor` inside the TUI was only run against an empty state directory (`NoSuchFileException`), so it has not been seen succeeding against a real configuration.
- IDE build failure (`Unable to delete directory ... plugin-spec-builders`): reproduced from the CLI in the OneDrive checkout; fixed by `gradlew --stop` and deleting `build-logic/build`. Root cause is file locking by OneDrive or a stale daemon. Durable fix is keeping the clone outside OneDrive (documented in `README.md`).
- Limit: the Sonar gate covers the tip of `main` only. Historical commits released by backfill are not individually gated.

## Angular UI, Google/GitHub sign-in and the Norse design (0.5.0, branch feat/angular-valhalla-ui-sso)

Decisions and why:
- **One artifact.** `frontend/` is built by npm inside Gradle (`installFrontend`/`buildFrontend`, `-PwithoutFrontend` skips them) and copied into `static/ui` of `modsync-server.jar`. Docker builds the frontend in a Node stage and passes `-x installFrontend -x buildFrontend`; the runtime image is still JRE only. Release workflow runs `setup-node` without a lockfile cache because historical commits have no `frontend/`.
- **Routes.** `/ui/**` SPA (explicit allow-list in `SpaController`, so it can never capture `/ui/api`), `/ui/api/**` JSON on the existing dashboard chain, `/api/v1/**` untouched. Thymeleaf removed after the parity suites were rewritten to the JSON API.
- **CSRF/cookies.** `CookieCsrfTokenRepository.withHttpOnlyFalse()` with the plain request handler (token never rendered in HTML) plus `CsrfCookieFilter`; side effect: no anonymous session before login (the rotation test now checks the token rotates). `SameSite=Lax` because Strict drops the cookie holding the OAuth state on the cross-site return. CSP: `script-src 'self'`, `style-src 'self' 'unsafe-inline'` (Angular runtime styles; nonce-based CSP would need dynamic `index.html`).
- **401 vs redirect.** `DelegatingAuthenticationEntryPoint`: `/ui/api/**` gets 401, browsers go to `/ui/login`. The first attempt (`defaultAuthenticationEntryPointFor`) silently turned every browser redirect into a 401; the integration suite caught it.
- **SSO model.** `external_identities(provider, subject)` -> user; sign-in only for linked identities; link intent (5 minutes, bound to the signed-in user and provider) stored in the session by a CSRF-protected POST; `SsoUserServices` returns a principal named after the local user id with the stored role, so `SessionRegistry` revocation works (`RegistryUserSessionRevoker` now also matches `AuthenticatedPrincipal`). GitHub scope `read:user` only (email unused); PKCE for both providers (GitHub documents S256 support). Registrations exist only when client ids are set (`@ConditionalOnExpression`), so deployments without credentials are unchanged.
- **Kotlin placeholders** use the multi-dollar form (`$$"${...}"`) as in the existing code; no escaped `\${`.
- **Frontend tests.** Vitest via the Angular builder, 43 tests, 94% line coverage; axe-core checks on four pages. A component-level `ASSIGN_LOCATION` token makes the provider redirect testable.
- **Sonar/CI.** Root Gradle module analyses `frontend/src/app`; the `sonar` job runs `npm run test:coverage` and rewrites LCOV paths; separate `frontend` job; `org.gradle.caching=true`. Docs link check runs in `validate`.

Verification actually executed (Windows, JDK 25, Docker Desktop running):
- `./gradlew check dokkaGenerate :modsync-server:bootJar` green after the cutover (including every Testcontainers suite, 3m18s); jar contains `static/ui/index.html`.
- `npm run lint`, `npm test` (43), `npm run test:coverage` (94.2% lines), `npm run build` (initial ~74 kB gzipped).
- `docker build -f docker/server/Dockerfile .` succeeded; compose smoke with `compose.sso.yaml` and empty provider ids: `/api/v1/info` 200, `/ui/index.html` 200, `/ui` 302 to `/ui/login`, `/ui/api/sso/providers` `{"providers":[]}`, `/ui/api/overview` 401. (Docker Desktop on Windows rejects the `tmpfs: /tmp:...mode=1777` entry of `compose.yaml`; a temporary override was used. Pre-existing.)
- `scripts/openapi.py --check`, `check-architecture.py`, release/repository/docs unit tests pass; `scripts/backup` tests fail on Windows only (shell paths), unrelated.

Not verified / outstanding:
- Real Google and GitHub sign-in (needs the maintainer's credentials); the provider consoles were checked only against official documentation.
- (Resolved in 0.5.1, see the follow-up section below.) Sonar analysis of the frontend and the quality gate: the scanner cannot bootstrap outside the tailnet; the first CI run on `main` decides. CI wall-clock was **not** measured after the change (baseline before: validate 423-460 s, integration 310-545 s, sonar 712-976 s, critical path = sonar, 12-16 min); the added `frontend` job runs in parallel and Gradle now also runs `npm ci` and `ng build`, so compare the first runs against that baseline before claiming no regression.
- Browser-level viewport tests (320-1440 px), contrast measurement and screen-reader checks are not automated; mobile and WCAG 2.2 AA are therefore only partly evidenced (stacked tables, touch targets and axe rules are implemented and unit-tested).
- `docker/server/.env.example` was not updated (the tool session could not read that directory); variables are documented in `compose.sso.yaml` and `docs/security/SSO_SETUP.md`.
- Known limits: no administrator screen for other administrators' provider links; per-address throttling only; sessions in memory.

## Quality gate, releases and CI timing after 0.5.0 (0.5.1)

- **Gate failure and fix.** The first `main` run of 0.5.0 (`44f27dc`) failed the SonarQube gate on one condition: `new_violations` 6 > 0 (coverage on new code 85.3% >= 80%, duplication 1.06% <= 3% both passed, no hotspots). All six were `typescript:S2699` in frontend tests that only called `http.expectOne(...)`. Fixed in PR #34 by asserting the request method or body; no rule was suppressed. The same PR removed the single duplicated block Sonar found (17 lines of command/notice/error handling shared by the server, user and account pages) with `core/api/run-command.ts`. The second `main` run (`009d053`) passed the gate.
- **How the gate was read.** The Sonar API needs a token, so the conditions and issues were read through the maintainer's logged-in Chrome session (`/api/qualitygates/project_status`, `/api/issues/search?inNewCodePeriod=true`, `/api/measures/component_tree`). The extension disconnected repeatedly; the fallback is `! curl -u <TOKEN>:` run by the maintainer.
- **Release gate behaved as designed.** While `44f27dc` failed `validate`, its `workflow_run` reconcile was skipped and the scheduled reconcile failed; after `009d053` passed, one reconcile dispatched both unreleased commits and two release runs passed all seven jobs. Prereleases: `v0.5.0-dev.20261114184241.44f27dcde08ea1818a755c595b66de7dc415a468` (PR #33, original 0.5.0 code) and `v0.5.0-dev.20261114192712.009d05340f91f6c98c68a613ecdb11a9e750b778` (PR #34, the latest; `44f27dc` is its ancestor). Assets and image digests were not inspected.
- **CI timing (measured, `main`).** `validate` 348-406 s (baseline 423-460 s, helped by the Gradle build cache), `integration` 440-452 s (baseline 310-545 s), `frontend` 27-31 s (new, parallel), `sonar` 1015-1078 s (baseline 712-976 s); after PR #35 (`test jacocoTestReport sonar --info` instead of `check`) the next `main` run took 696 s with `validate` 339 s and `integration` 471 s, back inside the baseline (one run, so not yet a trend). `sonar` is the critical path and is about 100 s over the baseline, so the 10% target is **not met**. Breakdown of the 1078 s run: Gradle build and server tests about 3 minutes (`npm ci` and Angular coverage 9 s), the `:sonar` task 13.6 minutes (scanner analysis plus the quality-gate wait, `sonar.qualitygate.timeout` 300). Removing duplicated builds therefore cannot close the gap; the analysis does.
- **Done in 0.5.1.** The `sonar` job runs `test jacocoTestReport sonar --info` (no repeated lint/Dokka/boundary checks) so the next `main` run logs per-sensor timings; deprecated API uses were replaced. Gradle build cache stays enabled. Sonar runs only on `main` and its tailnet identity trusts only `refs/heads/main`, so every Sonar experiment needs a merge.
- **Next step.** Read the `--info` sensor timings, then target the dominant sensor (candidates: the JavaScript/TypeScript sensor over `frontend/src/app` with three tsconfig files, the Kotlin sensor over ten modules, or the server-side compute-engine wait). Do not claim the target met before a run shows it.
- **Release retention (0.5.1).** The repository had 30 development prereleases for 15 versions (for example 10 for 0.1.0). Deleting old releases is not enough because `reconcile.py` derives "released" from the existing releases and would rebuild every deleted commit (about 25 minutes each). Decision: only the newest build of each version is kept; the reconciler marks a commit superseded when a later commit of the same version has a verified release (version read from the commit's own `reconcile.py`, unknown versions never skipped), and `prune.py` deletes the older published releases and tags (dry run by default). Executed after the reconciler change was merged (PR #35) and its `main` run was green: the dry run listed 9 deletions and `prune.py --apply` removed them with the maintainer's own `gh` credentials (30 -> 21 releases): four 0.1.0, one each of 0.2.1, 0.3.1, 0.4.0, 0.4.7 and one 0.5.0 (`44f27dc`). The 7 remaining drafts (five 0.1.0, one 0.2.1, one 0.3.0, all with 0 assets, leftovers of failed publish attempts) were then deleted by hand with `gh release delete <tag> --cleanup-tag --yes` at the maintainer's request, leaving 15 releases, one per version. The 0.3.0 version has no published release (its only release was a draft on the release-floor commit, which the reconciler skips as unpublishable). GHCR image tags for the deleted versions are not touched (needs a `delete:packages` token). Order matters: merge the reconciler change first, then prune.
- **Windows client upgrade (2026-11).** The client install is `%LOCALAPPDATA%\ModSyncDeploy` (`bin\modsync.exe` plus versioned copies, and `tui\bin` + `tui\lib`). Upgrading from a release: download `modsync-windows-x64.exe`, the TUI zip and `SHA256SUMS`, verify the hashes, rename a running `modsync.exe` before copying the new one (Windows allows renaming a running exe), and swap the TUI folder only after the TUI's `java.exe` (classpath `tui\lib\*`) has ended. An in-place `rm` of locked jars deleted the launcher scripts once before failing; always take a backup copy first and never delete before the replacement is in place. `modsync --version` prints nothing in the native build, so verify by checksum.
- **Still outstanding:** browser-level viewport tests, real Google/GitHub sign-in with credentials, `docker/server/.env.example` (not readable by the tooling session), no administrator screen for other administrators' provider links.

## Login redirect and Classify focus (0.5.2)

- **Reported:** "Classify does nothing" and "after login nothing happens until I type https://modsync.alexandermora.de/ui". Both were reproduced in the maintainer's signed-in Chrome session and with curl.
- **Classify:** the form for the selected mod renders at the top of the "Server mods" section while the clicked button can be thousands of pixels lower (94 mods): measured form top 927 px, button 7007 px. Fix: `afterNextRender` scrolls the form into view and focuses the reason field; unit test added (`scrollIntoView` is stubbed in the jsdom specs).
- **Login:** the production deployment sits behind a TLS proxy and runs with the default `MODSYNC_FORWARD_HEADERS=none`. A wrong login answers `Location: http://modsync.alexandermora.de/ui/login?error` and an anonymous `/ui` answers `http://.../ui/login`; Tomcat and Spring build the absolute URL from the plain-HTTP connection. The session cookie is `Secure`, so the browser is sent to an origin that does not carry it. Typing the https URL works because the cookie is sent there. Fix is configuration, not code: `MODSYNC_FORWARD_HEADERS=framework` on Host B (the compose file already passes it through). Two integration tests prove `framework` yields `https://<forwarded host>/ui/login` and that the headers are ignored by default (so reaching port 8080 directly cannot steer redirects). Making redirects relative in code was rejected: Tomcat absolutizes `sendRedirect` anyway, and the correct behaviour behind a proxy needs the forwarded headers regardless.
- **Why it was missed:** the new SSO guide called the setting necessary for SSO callbacks, but password sign-in needs it too; the docs now say so and list the symptom.
- **Server state found while diagnosing (Survival):** publication policy had been `MANUAL`; the maintainer asked for `TRUSTED_AUTO` and `oreharvester` as `BOTH_REQUIRED` (both applied through the dashboard API). `TRUSTED_AUTO` cannot publish this server because six files (`autopickupmod`, `craftlocator`, two `*-local.jar`, one `*-dev.jar`, `sethome`) are not identified by Modrinth and auto-publication always requires review for those; revision 5 therefore still needs the manual preview/publish route with an administrator token (`scripts/admin.py`, `docs/USAGE.md` section 3.4). The client auto-sync fails until then because the manifest pins 15 older mod versions that clash with newer unmanaged copies (`Installer.requireNoDuplicateMods`).

## Historical releases cannot be published by the workflow token (branch fix/reconcile-unreleasable-commits, release 0.4.6)

- Result of re-dispatching `7c29453` and `cd6c32d` after #27: `version`, `validate`, `integration` and the three `native` jobs all passed (so the per-source Gradle fix and the version-prefix fix from #25 work), then `publish` failed in `preflight()`: `POST git/refs` -> `403 Resource not accessible by integration`. Nothing was published and no tag or draft was created.
- Experiment (throwaway PR #28, closed and deleted; a `pull_request` workflow with `contents: write` that tried `POST git/refs` for a branch and a tag at the `main` tip and at both historical SHAs): tip -> 201 for branch and tag; `7c29453` and `cd6c32d` -> 403 for branch and tag. All probe refs were deleted (0 left).
- Cause (strongest explanation, not isolated): the commits' `.github/workflows` differ from `main`'s, and the default `GITHUB_TOKEN` cannot create refs at such commits (it lacks the `workflows` permission). I did not separate "differs in workflows" from "is not the tip" because no non-tip commit with identical workflows exists. All releases published so far were the tip at the time, consistent with this.
- Decision: the reconciler skips commits whose `.github/workflows` git tree differs from the tip's (`unpublishable()`), reports them and excludes them from `remaining`. Rejected: a PAT or GitHub App token with `workflows` scope (new long-lived credential for low value), running the probe at the main tip only (the tag creation at the old commit would fail next), and moving `RELEASE_FLOOR` (would have to move after every workflow change).
- Consequence to remember: after any PR that changes the workflows, older unreleased commits become permanently unpublishable. The tip is always releasable, and the reconciler runs on every push, so this only affects backfill of intermediate commits. `7c29453` still has an empty draft release from 2026-11-06.

## Dependency updates (branch chore/update-dependencies, release 0.4.5)

- Inventory: dependencies are declared inline in each module's `build.gradle.kts` and in `build-logic` (`gradle/libs.versions.toml` is referenced by nothing and had drifted, Kotlin 2.3.21 versus the real 2.4.21; it now mirrors the versions in use). Already latest stable: Kotlin 2.4.21, Spring Boot 4.1.2, Spotless 8.11.0, Dokka 2.3.0, JUnit 6.1.3, picocli 4.7.7, Detekt 2.0.0-alpha.6 (only alpha published).
- Updated: kotlinx-serialization-json 1.13.0, JLine 4.7.0, tomlj 2.2.0, MariaDB JDBC 4.0.0 (kept off the 3.x line: the integration tests and the distributed fixture exercise it), Testcontainers 1.21.4, kotlin-logging 8.0.7, slf4j-simple 2.0.20, SonarScanner for Gradle 7.6.0.18749, temurin 25.0.5_8, MariaDB image 11.4.13, GitHub Action majors (checkout v7, setup-java v6, setup-gradle v6, upload v7, download v8, docker login/buildx/qemu v4).
- Deliberately not updated: GraalVM Native Build Tools 1.x (a major that only the release `native` jobs on three platforms can validate; do it as its own PR) and Testcontainers 2.x (renamed artifacts, API changes).
- Mechanics: the (gitignored) `gradle.lockfile`s enforce the old versions locally, so the first build failed with "strictly 1.9.0 ... Dependency Locking"; they were regenerated with `--write-locks` (CI has no lockfiles). Checksums: `--write-verification-metadata sha256` added 32 entries, every one cross-checked against the companion `.sha512/.sha256/.sha1/.md5` published on Maven Central or the plugin portal (0 unverified). Old entries are not pruned because older sources still need them. One Spotless violation appeared because `mariadb:11.4.13` made a one-line `companion object` one character longer; fixed with `spotlessApply`.
- Verified locally: full `check dokkaGenerate bootJar installDist` with Docker (Testcontainers MariaDB 11.4.13 with the 4.0.0 driver) - BUILD SUCCESSFUL in 6m11s. Verified by CI on the PR: `validate` and `integration` (distributed fixture, including the new Docker images). Verified only after merge: the release job with the new action majors (`download-artifact`, `docker/*` are exercised only there).

## Per-source Gradle in releases (branch fix/release-gradle-per-source, release 0.4.4)

- Finding (regression of my own Gradle 9.9.1 change, found by dispatching `release.yaml` for `7c29453` and `cd6c32d` as the end-to-end check of #25): both runs failed in `validate` and the three `native` jobs with dependency verification errors, `publish` skipped, nothing published. `release.yaml` comes from `main` (hard-coded `gradle-version: 9.9.1`) but builds the old source, and that source's `gradle/verification-metadata.xml` was generated for 9.8.2 (kotlin-dsl 6.7.6, Kotlin 2.4.10 build-logic artifacts). `main` itself was never affected (the 0.4.3 release passed in 14 min).
- Decision: build each source with the Gradle it pins. The `version` job extracts it from the source's `gradle-wrapper.properties` (tested on `7c29453` and `cd6c32d` -> 9.8.2, current -> 9.9.1), validates it against `^[0-9]+\.[0-9]+(\.[0-9]+)?$` and exposes it as a job output used by `validate` and `native`. Rejected: adding both Gradle versions' checksums to old commits (would rewrite immutable history) and building historical sources with the current Gradle (breaks whenever the toolchain moves).
- Lesson: any toolchain change in `release.yaml` must be checked against a historical SHA, not only against the head; the PR checks cannot see it because `release.yaml` only runs for reachable `main` SHAs after merge.
- Verified locally: extraction on three sources, actionlint, release unit tests. Verified after merge: see the release run results recorded below once re-dispatched.

## Pipeline speed and Gradle 9.9.1 (branch ci/faster-pipeline, release 0.4.3)

- Measured (runs 43538790454, 43538582086, 43579810024): release 72 min wall = `validate` 13 + `native` 5 (parallel) + `publish` 53; validation workflow 27 min = `validate` 14.6 then `sonar` 12.3 sequentially. Inside `publish` the arm64 Gradle build under QEMU took 1637 s (server) and about 1406 s (agent) versus 159 s for amd64; server and agent images are built one after the other.
- Decision: pin the Dockerfile build stage to `--platform=$BUILDPLATFORM` (artifact is JVM bytecode/scripts, so identical on both targets). Rejected: prebuilt-JAR Dockerfiles, buildx GHA cache, self-hosted ARM runners (more moving parts for a few minutes), and concurrent image builds in `publish.py` (security-sensitive script, small gain after the fix; revisit only if publish is still slow).
- Decision: split `release.yaml` into `version` -> (`validate`, `integration`, `native`) -> `publish` and `ci.yaml` into `validate` + `integration` (+ `sonar` without `needs`). The fixture now runs in its own workspace, so it can never scan its own disposable TLS keys; `publish` still needs every gate and the full re-validation of the exact SHA is kept.
- Gradle 9.8.2 -> 9.9.1 (latest stable, built 2026-11-10; 8.14.7 is only a backport of the old line). The first full `check` failed at configuration time with dependency verification errors, as expected: the kotlin-dsl plugin version is tied to the Gradle version (6.7.6 -> 6.7.13) and pulls Kotlin 2.4.21 `kotlin-assignment` / `kotlin-sam-with-receiver` build-logic artifacts. I regenerated the metadata with `--write-verification-metadata sha256` and kept only entries I verified (9 artifacts: Maven Central `.sha1`/`.md5` companions matched; the three plugin-portal artifacts via the portal's content-addressed URLs, the `.pom` only by one source). These are trust-on-first-use like every other "Generated by Gradle" entry.
- Verified locally before committing: full `check dokkaGenerate bootJar installDist` on 9.9.1 (BUILD SUCCESSFUL, tests included), `check-architecture`, `openapi --check`, release unit tests, actionlint on both workflows (one pre-existing SC2193 warning in `release.yaml`, unchanged), and multi-arch `docker buildx build` of both Dockerfiles: a single Gradle step on linux/amd64, agent for both platforms in 171 s, and the arm64 agent image runs (`aarch64`, Java 25).
- Not completed locally: `scripts/integration/distributed.py` (the background run was stopped by the host running low on memory) and the `scripts/repository` unit tests (they were queued after it); both are covered by the new `integration`/`validate` jobs on the PR.
- Not verifiable locally: the real GitHub job timings (the release workflow only runs for reachable `main` SHAs and publishes) and `scripts/backup` tests, which need a working `python3` on Windows (2 failures identical on `main`; they pass on the Linux runners).

## Historical release publication failure (branch fix/release-historical-version-check, release 0.4.2)

- Finding (root cause): runs 43567237196 (`cd6c32d`) and 43535610893 (`7c29453`) passed `validate` and all three `native` jobs and failed only in `publish` with `ValueError: Release version does not identify source commit`. `validate` checks out the historical commit and computes the version with that commit's own `reconcile.version()` (hardcoded prefix: `v0.3.2-dev`, `v0.3.0-dev`), while `publish` intentionally runs main's tooling (`release-tools` copy) and compared the entire string with main's `v0.4.1-dev...`. Each version bump therefore broke publication of every unreleased older commit, and the hourly reconciler (6h cooldown) kept re-dispatching them. Head commits worked because tooling and source agree.
- Decision: keep running main's publication tooling for historical sources (unchanged), and relax only the prefix. `publish.identifies_source(version, expected)` requires the tag to match `TAG_PATTERN` and the `<UTC stamp>.<full sha>` suffix to equal the one derived from the checked-out commit. The semver prefix is the source's own version and is correct for a historical build; the suffix is what binds a version to its commit, so the security intent of the check is kept. Rejected alternative: compute the version in `validate` with main's tooling, which would label 0.3.x sources as `v0.4.x-dev`.
- Verified: RED observed first (`test_historical_source_keeps_its_own_version_prefix`), then 24 release tests pass, including rejection of a different SHA, a different timestamp, a short SHA, a missing `-dev` suffix and a prefixed or suffixed tag. `scripts/openapi.py --check` passes after the bump.
- Not verified: an end-to-end publish of a historical commit; that needs a dispatch of `release.yaml` on `main` after merge (the fix only takes effect on main because `publish` copies main's tooling).
- Audit of all 130 failed runs among the last 200 (GitHub keeps no older ones): (1) ~50 early-November runs (picocli classpath/AutoHelpMixin in native-image, `distributed.py`, `ModuleNotFoundError: reconcile`, `mkdir -p artifacts`) are historical commits below `RELEASE_FLOOR`, fixed at head, and no longer dispatched; (2) `publish` version mismatch: 45 runs on 8 commits since Nov 4, fixed above; (2b) 3 `publish` runs (Nov 5-6, `aa21374`, `7c29453`) failed the Trivy HIGH/CRITICAL scan of the pushed image (CVE-2026-109518, fixed by the apt upgrade in #16); this is the intended fail-closed gate and can recur whenever a new CVE is published, so it is not changed; (3) `d9f75f5`: 21 runs, all `Dependency verification failed ... graalvm-reachability-metadata-0.11.5-repository.zip` on all three native runners. The checksum was added by `7c29453`, so that immutable source can never build; (4) Sonar/validate: Tailscale 403 and `EAI_AGAIN` DNS (Nov 6, environment), a quality gate failure (Nov 6, fixed in #17) and the flaky `UserManagementIntegrationTest.listSearchesFiltersAndPages` (Nov 13, fixed by `7b79a59`); the last three pushes to `main` pass; (5) one `setup-graalvm` download failure (transient, self-healed by the retry).
- Decision: `RELEASE_FLOOR` moves from `d9f75f5` to `7c29453` (an explicit, reviewable exclusion of the one commit that cannot build), and reconcile retries use exponential backoff by failure count (6h doubling, 7-day cap; cancelled runs excluded). Rejected: a hard attempt cap, because `7c29453` already has 21 failed attempts from the version bug and would be abandoned right when it became fixable.
- Decision: Trivy pinned to v0.76.2 (latest release, published 2026-11-05) through the action `version:` input in `ci.yaml` and `release.yaml` and the `aquasec/trivy:0.76.2` image in `publish.py` (multi-arch tag verified on Docker Hub). `aquasecurity/trivy-action` v0.36.0 is already the latest release and its pinned SHA `ed142fd` matches the tag, so it is unchanged. Previously the action used its default v0.70.0 and the image scan used 0.67.2, so the two scans were on different versions. Verified locally: `aquasec/trivy:0.76.2 fs --severity HIGH,CRITICAL` on a clean `git archive HEAD` export exits 0 (the report lists nothing as scanned because the repository tracks no lockfile or manifest Trivy recognises, so this source scan has low detection value; the image scan is the real gate). A scan of the live working tree timed out on the Windows bind mount and is not evidence. Not verified: the image scan and the action run with v0.76.2 have not executed in CI yet; a new Trivy release can report findings the old one did not, which would fail the first source scan or image publication (fail-closed by design).
- Not verified: the backoff and floor change only run on `main` (the reconcile workflow checks out `main`); first real proof is the next scheduled run after merge. `7c29453` will wait up to 7 days for its automatic retry because of its past failures; dispatch `release.yaml -f sha=7c2945334787ca9f28d15bde513de55134fdf4ed` manually after merge.


## Session 2026-11-15: scheduler window, 0.6.3 deploy, duplicate-mod reporting (PRs #49, #50)

- Finding (root cause): the Windows `ModSync Auto` task created by `schtasks /TR` runs the console `modsync.exe` with logon type `Interactive`, so a console window opens on every run. Decision (PR #49, 0.6.3): `auto schedule --platform windows` prints a `Register-ScheduledTask` command with the `S4U` logon type.
- Verified on the Windows host: the task re-registered as `S4U` runs (`LastTaskResult` is the sync result, not a scheduler error). Not verified: that no window appears; the user was asked to watch the 22:36 run.
- Correction (my earlier claim was wrong): registering an `S4U` task needs an elevated session ("Access is denied" otherwise), and `DOMAIN\user` failed with "No mapping between account names and security IDs"; the bare `$env:USERNAME` worked. The 0.6.3 template still prints `DOMAIN\user` and the docs do not mention elevation. Open follow-up: fix the template and docs, or test `conhost.exe --headless modsync.exe auto run --once` as an unelevated alternative (untested).
- Deployment (authorized by the user for host 192.168.68.79, user `athegreat90`, key `C:\Users\alexa\.ssh\id_rsa`, path `/home/athegreat90/docker/modsync/{server,agent}`): upgraded server and agent to `v0.6.3-dev.20261115195620.07b681c` by backing up `.env` (`.env.bak-deploy-063`, `.env.bak-0.6.0`), pinning `MODSYNC_SERVER_IMAGE`/`MODSYNC_AGENT_IMAGE` to `tag@sha256` digests from the release's `release-manifest.json`, then `docker compose --env-file .env -f compose.yaml -f compose.sso.yaml up -d` for the server (the running server uses both compose files; the agent uses `compose.yaml` only). The remote shell is zsh: wrap compose in a function, a variable holding the command is not word-split. Both containers healthy; `/api/v1/info` answers with protocol 1.
- Windows host client (this machine) upgraded by replacing `%LOCALAPPDATA%\ModSyncDeploy\bin\modsync.exe` with the checksum-verified release exe; the previous one is kept as `modsync-prev-backup.exe` next to the versioned copies.
- Finding (root cause) of the failing automatic sync: server manifest revision 4 lists older versions of about 15 mods than the client folder holds, so every install would leave two JARs for one mod and `Installer.requireNoDuplicateMods` refuses after the downloads (`sync apply --dry-run` never reaches it). The guard is correct; the data is stale. Not done: republish the server manifest so it matches the server mods.
- Decision (PR #50, 0.6.4): the refusal throws `DuplicateModsException` (an `IllegalArgumentException`) and the automatic pass reports `Pending` with the message, with no FAILED sync report and the revision unrecorded. Verified: new test `duplicateModRefusalIsPendingNotFailed`, full `:modsync-client:test`, `spotlessKotlinCheck`. Not run locally: the multi-module CI build.
- Convention followed: each fix PR carries a patch bump (same files as the 0.6.2 bump, CHANGELOG heading with the date), squash merge with `(#NN)`, delete the branch, wait for the `validate`, `reconcile-main-releases` and `development-release` runs on `main`.
- Environment: the repo lives under OneDrive, which locks `build/` directories ("Unable to delete directory"). Run Gradle from a copy outside OneDrive; spotless also needs a real git checkout (use a scratch `git worktree`, not a copy without `.git`). Deleting a scratch worktree can fail with "Filename too long" under the scratchpad path.
- Security note: a device `refreshToken` from `~/.modsync/config.json` was printed in the session log by a redaction that missed it; the user may want to revoke that device's credentials.
- User instruction: always record my choices in this file.

## Session 2026-11-15 (cont.): 0.6.4 released and deployed (PR #50)

- Process followed: merged #50 after PR checks passed (squash `208f67a`, branch deleted), waited for `validate`, `reconcile-main-releases` and `development-release` on `main` (all success), then deployed `v0.6.4-dev.20261115212145.208f67a` using the same digest-pinning steps as 0.6.3 (backups `.env.bak-deploy-064` in `server/` and `agent/`).
- Verified: server and agent containers healthy on the new digests, `/api/v1/info` answers protocol 1 with the same control-plane id; Windows `modsync.exe` replaced from the checksum-verified release asset (previous kept as `modsync-prev-backup.exe`), `doctor` and `connection test` pass.
- Verified (the #50 fix on the real host): `auto run --once` now prints `Pending(reason=Installation would leave several files for the same mod: ...)` and exits 0 instead of `Failed(reason=IllegalArgumentException)` and exit 1.
- Not verified: the `ModSync Auto` task showed `LastTaskResult` 1 for the 22:36 run, which used the 0.6.3 exe; the next scheduled run should report 0. The absence of the console window was not confirmed by the user yet.
- Still open: the server manifest (revision 4) is older than the client's mods, so the sync stays pending until it is republished; the 0.6.3 scheduler template still prints `DOMAIN\user` and the docs do not say `S4U` needs elevation.
