# SonarQube analysis

- **Server:** `https://sonarqube.alexandermora.de` (verified reachable; SonarQube 26.10.0.133078).
- **Project key:** `athegreat90_minecraft-modsync` (verified: analyses upload to it).
- **Scanner:** SonarScanner for Gradle `org.sonarqube` 7.5.0.8588, configured once at the root `build.gradle.kts`.
- **Authentication:** only the `SONAR_TOKEN` environment variable (locally) or the `SONAR_TOKEN` GitHub secret. Never put a token in files, command lines or logs.

## Local scan

PowerShell (token already set in the environment):

```
.\gradlew.bat clean check sonar "-Dsonar.host.url=https://sonarqube.alexandermora.de" "-Dsonar.qualitygate.wait=true"
```

Quote `-D` arguments in PowerShell, otherwise Gradle parses them as task names. `check` must run first so the JaCoCo XML reports exist; they are picked up from each module's default `build/reports/jacoco/test/jacocoTestReport.xml` (an explicit `sonar.coverage.jacoco.xmlReportPaths` made the importer reject every file, so it is deliberately not set).

## CI (GitHub Actions over Tailscale)

SonarQube is reachable only on the LAN or the tailnet, so the `sonar` job in `.github/workflows/ci.yaml` runs on a GitHub-hosted `ubuntu-24.04` runner that joins the tailnet for the duration of the job with `tailscale/github-action` (pinned by commit SHA), using workload identity federation: an ephemeral node tagged `tag:ci`, no long-lived Tailscale secret. The job analyses against the SonarQube node's tailnet address (`SONAR_HOST_URL`, default `http://x.y.z.a:9000`). That URL is plain HTTP, but it only travels inside the WireGuard tunnel; do not use it outside the tailnet. Local runs keep `https://sonarqube.alexandermora.de`. TLS verification is never disabled.

The job runs only for pushes to `main` and manual runs, never for pull requests, and needs no GraalVM (only the release workflow's native job does): JDK 25, Docker (Testcontainers) and about 4 GB of RAM.

### One-time Tailscale setup
1. Access controls: add `"tagOwners": {"tag:ci": ["autogroup:admin"]}` and a grant `{"src": ["tag:ci"], "dst": ["x.y.z.a"], "ip": ["tcp:9000"]}`.
2. Settings > Trust credentials > Credential > OpenID Connect: issuer **GitHub**, subject `repo:athegreat90/minecraft-modsync:ref:refs/heads/main`, scope **Auth Keys: Write**, tag `tag:ci`. Generate it, then copy the **Client ID** and the **Audience**. Neither is secret; the Client ID can be copied again from the same page.

### One-time GitHub setup (Settings > Secrets and variables > Actions)
- Secrets: `TS_OAUTH_CLIENT_ID` (the federated identity Client ID), `TS_AUDIENCE`, `SONAR_TOKEN`.
- Variable (optional override): `SONAR_HOST_URL` = `http://x.y.z.a:bbbb`. The tailnet IP stays stable unless the node is re-registered; change the variable if it does.

### Alternative: self-hosted runner
Put the runner host itself on Tailscale (`tailscale up`), set `runs-on` to its labels and remove the Tailscale step. You then own the host's RAM, disk, patching and isolation, and it must never run pull-request code.

### Troubleshooting CI
- Tailscale step fails to authenticate: the subject must match the running ref exactly (`refs/heads/main`); manual runs from other branches fail by design. Check that the tag exists in the ACL and the credential has `auth_keys` write.
- `curl` to `/api/system/status` times out: the ACL grant is missing or wrong (port 9000, destination IP) or the SonarQube node is offline.

## Quality gate and baseline

- Verified at the first analysis: the project's gate has no conditions configured (it reports OK without enforcing anything). Configure a gate with the targets below in SonarQube; this repository cannot do it with the available token (administration endpoints return 403).
- Targets: 0 new issues, 0 new vulnerabilities/bugs, 0 unreviewed hotspots, new-code coverage >= 80 %, duplication <= 3 %, A ratings.
- Branch and pull-request analysis depend on the installed edition and were not tested; only the main branch is analysed.
- Baseline before this work: 1 vulnerability (`kotlin:S6474`, missing Gradle dependency verification, fixed by `gradle/verification-metadata.xml`), 0 bugs, 0 code smells.

## Troubleshooting

- "analysis has failed" with `Invalid value of sonar.tests`: a module lacks `src/test/kotlin`; the root build only sets `sonar.tests` when the directory exists.
- Coverage missing: run `check` first, then confirm the XML reports exist.
- 403 on API reads: the token lacks Browse/administer permissions for that endpoint.
