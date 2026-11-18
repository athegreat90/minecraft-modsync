# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Development prereleases are
published automatically from `main` as `v<version>-dev.<UTC timestamp>.<sha>` (see `RELEASES.md`).

## [Unreleased]

## [0.6.5] - 2026-11-18

### Fixed
- Automatic publication no longer stalls on files Modrinth cannot identify. One unidentifiable jar (a private or `-local` build such as `immersive_aircraft`, `sethome`) made the whole snapshot `REVIEW_REQUIRED` on every evaluation, so no new revision was ever published and clients stayed `Sync pending` against a manifest older than the server's mods (revision 4 vs the updated jars). Such a file is now carried over unchanged when the latest manifest already lists its exact hash as a manual artifact (no download URL) with a classification other than `UNKNOWN`; under `REVIEW_UNKNOWN` it also needs an approval of that hash. A new unidentifiable file, or one classified `UNKNOWN`, still requires review.

### Changed
- A failed client command now says what is wrong and what to run next, instead of only `Command failed: NoSuchFileException; check arguments, connectivity and local state`: a missing configuration says to run `modsync setup` then `modsync login`, an unknown instance says how to list and add one, a server that cannot be reached names the connection error and `modsync config show`, and the client's own validation messages (for example `Invalid UUID string: x`, `Plan is blocked`) are shown. Exit codes are unchanged. A JSON decoding error, which can quote a local file, and any other exception are still reduced to a fixed sentence or the exception name, so no token, response body or file content is printed.
- Command usage shows real placeholders (`--instance=<instance>`, `--out=<output>`) instead of `<arg0>`, because the build now keeps parameter names (`-java-parameters`).

### Added
- Diagnostic logs: the server logs each automatic evaluation (snapshot, policy, scanned artifact count, latest revision), the reason every file needs review, the decision summary and every recorded outcome; the client logs each automatic pass outcome with the manifest revision, and the exception behind a failed pass instead of only its class name.
- Version bumped 0.6.4 -> 0.6.5 (patch; wire protocol 1 and persisted formats are unchanged).

## [0.6.4] - 2026-11-15

### Fixed
- Automatic synchronization reports an install refused because it would leave two files for the same mod as pending, with the mod and file names, instead of `Failed(reason=IllegalArgumentException)`. The pass no longer exits 1 or reports a FAILED sync for it; the refusal still changes nothing on disk.

## [0.6.3] - 2026-11-15

### Changed
- `modsync auto schedule --platform windows` now prints a `Register-ScheduledTask` command with the S4U logon type, so the scheduled run no longer flashes an empty console window (the old `schtasks /TR` form opened one every interval). Re-register the task to pick this up.
- The server page shows timestamps in local time to the second (not ISO text with microseconds) and writes policy, outcome, coverage status and classification names in sentence case (`Server only`, `Up to date`, `Unreviewed`). Values sent to the server are unchanged.
- The sign-in card sits a fixed distance from the top of the page instead of being centred, so the Google and GitHub buttons that arrive after the form no longer move it (layout shift).

## [0.6.2] - 2026-11-15

### Changed
- The administration session call (`GET /ui/api/session`, made on every page load) sends 1 database statement instead of 7, because it reads only the account's name and role in a single query instead of loading the full user detail with its device count inside a transaction. On a database host that answers slowly (the live one adds about 153 ms per exchange) this saves about 0.9 s on every page load. Results are unchanged, and a session whose account was deleted still answers 404. The user detail page keeps its device count.
- Version bumped 0.6.1 -> 0.6.2 (patch: performance of one administration call; wire protocol 1 and minimum component compatibility are unchanged).

## [0.6.1] - 2026-11-15

### Changed
- The administration server page sends 14 database statements instead of 49 and the users page 1 instead of 6, because the page now reads in one read-only transaction, looks the server up by id and loads the manifest once. On a database host that answers slowly (the live one adds about 153 ms per exchange) the server page drops from about 7.5 s to about 2.1 s. Results are unchanged. A statement budget test per endpoint guards against regressions. The session and overview calls are unchanged.
- Version bumped 0.6.0 -> 0.6.1 (patch: performance of two administration calls and documentation; wire protocol 1 and minimum component compatibility are unchanged).

### Documentation
- Deployment docs cover a reverse proxy on another machine: bind `MODSYNC_BIND_ADDRESS` to the single LAN or Tailscale address the proxy reaches (never `0.0.0.0`), restrict port 8080, and define the variable once in `.env` because Compose uses the last duplicate line. `docs/USAGE.md` troubleshooting gained the `502 Bad Gateway` after a recreate row.
- `docs/review/WEB_UI_UX_AUDIT.md` records the live re-test after 0.6.0: the redirect, SSO callback, cookie and HSTS findings are fixed on the deployment once forwarded headers were honoured.

## [0.6.0] - 2026-11-15

### Added
- Server page: the Server mods table has a file-name filter, a **Needs review** toggle, paging of 25 and a live match count, and the Latest manifest section is collapsed by default. On the same data the page is about 2,800 px tall instead of 11,700.
- Users page: the search, role, status and page are kept in the URL, the count reads "1 account" / "N accounts" in a live region, and the filter fields have an id and name.
- Start-up diagnostics: the server logs one `WARN` the first time a request carries `X-Forwarded-Proto` while `MODSYNC_FORWARD_HEADERS` is not set.
- `MODSYNC_COMPRESSION` (default `true`) compresses the UI text files and JSON; set it to `false` when the reverse proxy already compresses. The fingerprinted UI files (`main-*.js`, `chunk-*.js`, `styles-*.css`) are cached for a year as immutable.
- Both security chains send `Referrer-Policy: same-origin` and a `Permissions-Policy` that turns off camera, microphone and geolocation. The `XSRF-TOKEN` cookie is `SameSite=Lax`, and the UI shell asks search engines not to index it.

### Fixed
- A signed-in account without the ADMIN role is sent to `/ui/login?denied` with a message instead of a raw 403 page, a login form with a stale CSRF token is sent to `/ui/login?csrf`, and the bare host and `/favicon.ico` redirect to the dashboard instead of answering 401. The `/ui/api/*` calls keep their plain 403 and 401.
- Closing the Classify form returns focus to the row's **Classify** button, **Disable account** and **End sessions and tokens** ask for confirmation before anything is sent, sign-in failures are announced as alerts, the login card is centred without a scrollbar, and the server list no longer shows a stray bullet.

### Changed
- Version bumped 0.5.2 -> 0.6.0 (minor: new UI features; wire protocol 1 and minimum component compatibility are unchanged).

### Documentation
- `docs/review/WEB_UI_UX_AUDIT.md` records the real-browser audit of the live administration UI with 22 findings, their evidence and the remediation status. Not addressed in this release: the server page API takes about 7.5 s on the live deployment (cause not yet profiled), HTTP/2 at the reverse proxy, the layout shift when the single sign-on buttons appear (F-08) and readable timestamps and enum labels (F-20).
- `DOCKER.md`, `docs/deployment/README.md` and `SECURITY.md` describe `MODSYNC_COMPRESSION`, asset caching and the new response headers; `docs/security/SSO_SETUP.md` mentions the start-up warning; `docs/USAGE.md` troubleshooting gained the denied and stale-page sign-in messages.

## [0.5.2] - 2026-11-15

### Fixed
- Clicking **Classify** on the server page appeared to do nothing on long mod lists: the form opened at the top of the section, far from the clicked row. The form now scrolls into view and focuses the reason field.
- Signing in behind a TLS reverse proxy could appear to do nothing. Without `MODSYNC_FORWARD_HEADERS` the server builds redirects from its own plain-HTTP connection, so the post-login redirect pointed at `http://<host>/ui`, which does not carry the `Secure` session cookie; opening `https://<host>/ui` by hand worked. Tests now prove that `MODSYNC_FORWARD_HEADERS=framework` makes redirects use the public `https` address and that the headers are ignored by default. The setting was already documented for single sign-on; the docs now list this symptom explicitly.

### Changed
- Version bumped 0.5.1 -> 0.5.2 (patch: bug fixes and documentation; wire protocol 1 and minimum component compatibility are unchanged).

### Documentation
- `docs/security/SSO_SETUP.md`, `DOCKER.md` and `docs/deployment/README.md` state that password sign-in, not only SSO, needs `MODSYNC_FORWARD_HEADERS=framework` behind a TLS proxy, with the symptom and a one-line check. `MEMORY.md` records the diagnosis. `docs/USAGE.md` troubleshooting gained rows for a failed automatic sync caused by older pinned mod versions, the Classify and login symptoms, and replacing `modsync.exe` or the TUI folder on Windows; `RELEASES.md` explains how to remove abandoned draft releases.

## [0.5.1] - 2026-11-14

### Fixed
- Six frontend tests only matched their HTTP requests and had no assertion, which failed the SonarQube quality gate on `main` (`new_violations`, rule `typescript:S2699`). They now assert the request method or body. No rule was suppressed.

### Changed
- The Angular pages share one `runCommand` helper (`frontend/src/app/core/api/run-command.ts`) instead of three copies of the same command/notice/error code, which removes the only duplicated block SonarQube reported on new code. Behaviour is unchanged; the helper has its own tests (45 frontend tests, 95% line coverage).
- Deprecated build API uses were replaced: `tasks.register<Exec>(...)` for the frontend tasks, `project(path)` for the Dokka aggregation dependency and `isUnprocessableContent` in a server test. The compiler reports no warnings. The remaining Gradle deprecation notices come from third-party plugins when they are applied.
- The `sonar` CI job runs `test jacocoTestReport sonar` instead of `check`, because SonarQube reads only the JaCoCo reports and the compiled classes and `validate` already runs the lint, Dokka and boundary checks. It also logs per-sensor timings (`--info`). Measured on the first `main` run after 0.5.0, the repeated build was about 3 of 17 minutes and the `:sonar` task itself (analysis and quality-gate wait) 13.6 minutes, so this is a first, small reduction.
- Release retention is one development prerelease per application version. The reconciler no longer builds a commit when a later commit of the same version already has a verified release (`superseded` in its report), and `scripts/release/prune.py` (dry run by default, `--apply` to delete) removes the older published releases and their tags without ever touching drafts or the last complete release of a version. See `RELEASES.md`.
- Version bumped 0.5.0 -> 0.5.1 (patch: tests, refactoring and CI only; wire protocol 1 and minimum component compatibility are unchanged), including `frontend/package.json`.

### Documentation
- `MEMORY.md` records the quality-gate failure and fix, the published 0.5.0 prereleases and the measured CI timings; `docs/development/FRONTEND.md` and `docs/development/SONARQUBE.md` describe the shared helper, the test count and the `sonar` job.

## [0.5.0] - 2026-11-14

### Added
- Angular administration interface (`frontend/`, Angular 22, standalone components, signals, `OnPush`, lazy routes, typed forms) served by the server under `/ui` and packaged inside `modsync-server.jar`. It covers sign-in, the server overview, server details (publication policy, re-evaluation, history, client coverage, mod classification, latest manifest) and user management (search and filter, create, disable and enable, password reset, session and device revocation). Layout is responsive: top bar and navigation drawer on narrow screens, sidebar on wide ones, tables stacked into cards, 44 px touch targets, `prefers-reduced-motion` honoured. Original Norse-inspired design tokens live in `frontend/src/styles/_tokens.scss`.
- JSON administration API under `/ui/api` over the existing services (`ui.api.UiApiController`), documented in `docs/architecture/API.md`; it is separate from the unchanged `/api/v1` client/agent contract.
- Google (OpenID Connect) and GitHub (OAuth2) sign-in, enabled per provider only when `GOOGLE_CLIENT_ID` / `GITHUB_CLIENT_ID` are set. Accounts are linked by the provider's immutable subject (never by e-mail) from the new Account page; nothing is auto-created and no role is ever granted by a provider. Migration `V6__external_identities.sql`.
- `docker/server/compose.sso.yaml` (optional override) and entrypoint support for `GOOGLE_CLIENT_SECRET_FILE` / `GITHUB_CLIENT_SECRET_FILE`, so provider secrets stay in files; `MODSYNC_FORWARD_HEADERS` for deployments behind a reverse proxy.
- `docs/security/SSO_SETUP.md`: step-by-step Google and GitHub setup, Docker and reverse-proxy configuration, account linking, local development, production checklist, troubleshooting matrix and credential rotation. Provider steps were checked against the official documentation on 2026-11-14.
- `docs/development/FRONTEND.md`, a Markdown link checker (`scripts/docs/check_links.py`, run in CI) and a `frontend` CI job (lint and unit tests, 43 tests, 94% line coverage).
- Unit and integration tests for the JSON API, the single sign-on mapping rules, provider session revocation and the authorization request sent to each provider.

### Changed
- The administration UI is no longer rendered by Thymeleaf; the `spring-boot-starter-thymeleaf` dependency, the templates and the HTML controllers were removed. The sign-in page is now `/ui/login` and `/login` redirects to it; form logins that fail return to `/ui/login?error`.
- Content-Security-Policy now allows scripts from the server's own origin and inline **styles** (Angular injects component styles at runtime); inline scripts remain forbidden.
- CSRF tokens are issued in the `XSRF-TOKEN` cookie and echoed in `X-XSRF-TOKEN` (no HTML rendering of the token); no anonymous session exists before sign-in.
- Docker image build compiles the frontend in a Node stage; CI and release workflows set up Node. SonarQube analyses the Angular code through LCOV (`sonar.javascript.lcov.reportPaths`); the Gradle build cache is enabled.
- `scripts/openapi.py` checks only `/api/v1` routes; the `/ui/api` routes are documented in `docs/architecture/API.md`.
- Version bumped 0.4.7 -> 0.5.0 (minor; wire protocol 1 and minimum component compatibility are unchanged), including `frontend/package.json`.

### Fixed
- The shared validation error mapping failed with a 500 for requests served outside the API chain (no correlation id); it now answers 400.

### Security
- Provider sign-in uses the authorization-code flow with `state`, PKCE (S256) for both providers and an OIDC `nonce` for Google; the callback paths are served by the session chain and cannot reach the stateless API chain (covered by tests). Disabled accounts are refused, and sessions created through a provider are expired together with password sessions when an account is disabled or its password reset.
- Session cookie `SameSite` is `Lax` instead of `Strict`, required for the cross-site return from the provider; CSRF tokens protect every write.

### Documentation
- Added `docs/security/SSO_SETUP.md` and `docs/development/FRONTEND.md`; updated `SECURITY.md`, `ARCHITECTURE.md`, `DATABASE.md`, `DOCKER.md`, `README.md`, `docs/USAGE.md`, `docs/architecture/API.md`, `docs/deployment/README.md`, `docs/development/SONARQUBE.md` and `.github/copilot-instructions.md`.

### Breaking Changes
- Server-rendered pages and their form posts (`/ui/servers/{id}/...`, `/ui/users/...`) no longer exist; use the Angular interface or `/ui/api`. Bookmarks to `/login` are redirected to `/ui/login`.
- Deployments that terminate TLS in a proxy and want provider sign-in must set `MODSYNC_FORWARD_HEADERS=framework`; clients and agents need no change.

## [0.4.7] - 2026-11-14

### Fixed
- `modsync interactive` failed with `InvalidPathException` on Windows and launched a non-existent main class. The class path wildcard is now built as a plain string (`*` is illegal in a Windows `Path`) and the child JVM starts `de.alexandermora.modsync.client.tui.InteractiveKt`; `interactiveCommand` is covered by tests, including a guard that keeps the class name equal to the `mainClass` of `modsync-client-tui`. The child JVM also passes `--enable-native-access=ALL-UNNAMED` to silence the JLine restricted-method warning on Java 25.

### Changed
- Release reconciliation (`reconcile.yaml`) now starts from the `validate` workflow (`workflow_run`) and only when it succeeded, including the SonarQube quality gate, instead of from the push itself. Hourly and manual runs first require a successful `validate` run for the tip of `main`.
- `gradle.properties` enables `org.gradle.tooling.parallel=true` (parallel IDE sync, Gradle 9.4+).
- `modsync-client-tui` gained its first unit tests: the prompt loop is extracted into `runSession` (behavior unchanged) and covered for the banner, `quit`, end of input, Ctrl+C, blank lines, quoted arguments and failing commands.
- Version bumped 0.4.6 -> 0.4.7 (patch; wire protocol 1 and minimum component compatibility are unchanged). Updated in the same files as the previous bump.

## [0.4.6] - 2026-11-14

### Fixed
- The reconciler kept dispatching releases that can never be published. Re-running the releases of `7c29453` and `cd6c32d` after the Gradle fix got through `validate`, `integration` and all three `native` jobs and then failed in `publish` with `Resource not accessible by integration (HTTP 403)` when the permission probe created a temporary branch at the old commit. A throwaway experiment (a pull request workflow using the same `GITHUB_TOKEN`, closed and deleted afterwards) showed that creating a branch **or a tag** succeeds at the tip of `main` and is refused at both historical commits. The reconciler now skips commits whose `.github/workflows` tree differs from the tip's (`unpublishable()`), reports them as `unpublishable` in its log and excludes them from `remaining`, so they are no longer retried with exponential backoff forever. The head commit is unaffected (it always matches itself), and a commit that is already released is never checked.

### Changed
- Version bumped 0.4.5 -> 0.4.6 (patch: release tooling only; wire protocol 1 and minimum component compatibility are unchanged). Updated in the same files as the previous bump.

## [0.4.5] - 2026-11-14

### Changed
- Dependencies updated to the latest stable releases: `kotlinx-serialization-json` 1.9.0 -> 1.13.0, JLine 3.30.6 -> 4.7.0 (TUI), TOML parser `tomlj` 1.1.1 -> 2.2.0 (scanner), MariaDB JDBC driver 3.5.6 -> 4.0.0, Testcontainers 1.21.3 -> 1.21.4, `kotlin-logging-jvm` 8.0.6 -> 8.0.7, `slf4j-simple` 2.0.17 -> 2.0.20, SonarScanner for Gradle 7.5.0.8588 -> 7.6.0.18749. Kotlin 2.4.21, Spring Boot 4.1.2, Spotless 8.11.0, Dokka 2.3.0, JUnit 6.1.3, picocli 4.7.7 and Detekt 2.0.0-alpha.6 were already the latest stable (or latest published) releases. `gradle/libs.versions.toml` (not referenced by any build file) was brought in line with the versions actually used.
- Container images: `eclipse-temurin` 25.0.1_8 -> 25.0.5_8 (`jre-noble`, both Dockerfiles) and `mariadb` 11.4.10 -> 11.4.13 (compose files, integration fixture, Testcontainers tests, docs).
- GitHub Actions: `actions/checkout` v4 -> v7, `actions/setup-java` v4 -> v6, `gradle/actions/setup-gradle` v4 -> v6, `actions/upload-artifact` v4 -> v7, `actions/download-artifact` v4 -> v8, `docker/login-action`, `docker/setup-buildx-action` and `docker/setup-qemu-action` v3 -> v4. The breaking changes of these majors are the Node 24 runtime (provided by GitHub-hosted runners), removal of deprecated inputs the workflows do not use, and stricter artifact digest checks; the Node 20 deprecation warnings in the release logs should disappear.
- `gradle/verification-metadata.xml` gains 32 artifacts for the new versions; each checksum was verified against the companion checksums published next to the artifact on Maven Central or the Gradle plugin portal. Entries of the previous versions are kept (they are still needed to build older sources).
- Not updated on purpose: GraalVM Native Build Tools 0.11.5 -> 1.1.18 (a major step that can only be validated by the release `native` builds on three platforms) and Testcontainers 1.x -> 2.x (new artifact names and API).
- Version bumped 0.4.4 -> 0.4.5 (patch: dependency and tooling updates only; wire protocol 1 and minimum component compatibility are unchanged). Updated in the same files as the previous bump.

## [0.4.4] - 2026-11-14

### Fixed
- Releases of historical `main` commits (`7c29453`, `cd6c32d`) failed after the Gradle 9.9.1 upgrade: `validate` and all three `native` jobs stopped on dependency verification (`kotlin-dsl` plugin 6.7.13 and `kotlin-stdlib-common-2.4.21.module`). `release.yaml` is always the version from `main`, but it builds the old source, whose `gradle/verification-metadata.xml` only knows the plugins of the Gradle it was written for (9.8.2). The `version` job now reads the Gradle version from the checked-out source's `gradle/wrapper/gradle-wrapper.properties` (strictly validated) and `validate` and `native` pass it to `setup-gradle`, so every commit is built with its own pinned Gradle while `main` keeps using the current one.

### Changed
- Version bumped 0.4.3 -> 0.4.4 (patch: release workflow fix only; wire protocol 1 and minimum component compatibility are unchanged). Updated in the same files as the previous bump.

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
