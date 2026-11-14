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
7. `modsync interactive --tui-home DIR` launches the separate JVM TUI without adding JLine to the standard CLI; runtime verification remains outstanding.
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

## Historical release publication failure (branch fix/release-historical-version-check, release 0.4.2)

- Finding (root cause): runs 43567237196 (`cd6c32d`) and 43535610893 (`7c29453`) passed `validate` and all three `native` jobs and failed only in `publish` with `ValueError: Release version does not identify source commit`. `validate` checks out the historical commit and computes the version with that commit's own `reconcile.version()` (hardcoded prefix: `v0.3.2-dev`, `v0.3.0-dev`), while `publish` intentionally runs main's tooling (`release-tools` copy) and compared the entire string with main's `v0.4.1-dev...`. Each version bump therefore broke publication of every unreleased older commit, and the hourly reconciler (6h cooldown) kept re-dispatching them. Head commits worked because tooling and source agree.
- Decision: keep running main's publication tooling for historical sources (unchanged), and relax only the prefix. `publish.identifies_source(version, expected)` requires the tag to match `TAG_PATTERN` and the `<UTC stamp>.<full sha>` suffix to equal the one derived from the checked-out commit. The semver prefix is the source's own version and is correct for a historical build; the suffix is what binds a version to its commit, so the security intent of the check is kept. Rejected alternative: compute the version in `validate` with main's tooling, which would label 0.3.x sources as `v0.4.x-dev`.
- Verified: RED observed first (`test_historical_source_keeps_its_own_version_prefix`), then 24 release tests pass, including rejection of a different SHA, a different timestamp, a short SHA, a missing `-dev` suffix and a prefixed or suffixed tag. `scripts/openapi.py --check` passes after the bump.
- Not verified: an end-to-end publish of a historical commit; that needs a dispatch of `release.yaml` on `main` after merge (the fix only takes effect on main because `publish` copies main's tooling).
- Audit of all 130 failed runs among the last 200 (GitHub keeps no older ones): (1) ~50 early-November runs (picocli classpath/AutoHelpMixin in native-image, `distributed.py`, `ModuleNotFoundError: reconcile`, `mkdir -p artifacts`) are historical commits below `RELEASE_FLOOR`, fixed at head, and no longer dispatched; (2) `publish` version mismatch: 45 runs on 8 commits since Nov 4, fixed above; (2b) 3 `publish` runs (Nov 5-6, `aa21374`, `7c29453`) failed the Trivy HIGH/CRITICAL scan of the pushed image (CVE-2026-109518, fixed by the apt upgrade in #16); this is the intended fail-closed gate and can recur whenever a new CVE is published, so it is not changed; (3) `d9f75f5`: 21 runs, all `Dependency verification failed ... graalvm-reachability-metadata-0.11.5-repository.zip` on all three native runners. The checksum was added by `7c29453`, so that immutable source can never build; (4) Sonar/validate: Tailscale 403 and `EAI_AGAIN` DNS (Nov 6, environment), a quality gate failure (Nov 6, fixed in #17) and the flaky `UserManagementIntegrationTest.listSearchesFiltersAndPages` (Nov 13, fixed by `7b79a59`); the last three pushes to `main` pass; (5) one `setup-graalvm` download failure (transient, self-healed by the retry).
- Decision: `RELEASE_FLOOR` moves from `d9f75f5` to `7c29453` (an explicit, reviewable exclusion of the one commit that cannot build), and reconcile retries use exponential backoff by failure count (6h doubling, 7-day cap; cancelled runs excluded). Rejected: a hard attempt cap, because `7c29453` already has 21 failed attempts from the version bug and would be abandoned right when it became fixable.
- Decision: Trivy pinned to v0.76.2 (latest release, published 2026-11-05) through the action `version:` input in `ci.yaml` and `release.yaml` and the `aquasec/trivy:0.76.2` image in `publish.py` (multi-arch tag verified on Docker Hub). `aquasecurity/trivy-action` v0.36.0 is already the latest release and its pinned SHA `ed142fd` matches the tag, so it is unchanged. Previously the action used its default v0.70.0 and the image scan used 0.67.2, so the two scans were on different versions. Verified locally: `aquasec/trivy:0.76.2 fs --severity HIGH,CRITICAL` on a clean `git archive HEAD` export exits 0 (the report lists nothing as scanned because the repository tracks no lockfile or manifest Trivy recognises, so this source scan has low detection value; the image scan is the real gate). A scan of the live working tree timed out on the Windows bind mount and is not evidence. Not verified: the image scan and the action run with v0.76.2 have not executed in CI yet; a new Trivy release can report findings the old one did not, which would fail the first source scan or image publication (fail-closed by design).
- Not verified: the backoff and floor change only run on `main` (the reconcile workflow checks out `main`); first real proof is the next scheduled run after merge. `7c29453` will wait up to 7 days for its automatic retry because of its past failures; dispatch `release.yaml -f sha=7c2945334787ca9f28d15bde513de55134fdf4ed` manually after merge.

