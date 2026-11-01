# Continuity record

## Decisions

- Monorepo is independent of neighboring `olp-server`. User created https://github.com/athegreat90/minecraft-modsync; source was published through the connected GitHub integration. Local origin now points there.
- Separate Host A outbound scanner, Host B Spring/MariaDB control plane, Host C standalone client.
- Local Argon2id accounts; UUID identities; assignment-generation fencing; staged idempotent inventory commits; explicit immutable manifest approval.
- Private/unrecognized artifacts use exact local manual resolution, not binary hosting.
- Java/GraalVM 25 target, Kotlin 2.3.21, Spring Boot 4.0.9, MariaDB 11.4 LTS. Patch/image compatibility still requires builds and scans.
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
