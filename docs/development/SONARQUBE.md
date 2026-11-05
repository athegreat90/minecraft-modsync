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

## CI

`.github/workflows/ci.yaml` job `sonar` runs on a self-hosted runner labelled `sonarqube-access`, only for pushes and manual runs, never for pull requests. Runner requirements: network path to the SonarQube host (VPN/private network), no other secrets, ephemeral or isolated workspace. GitHub-hosted runners were not tested against this server and are not assumed to reach it. TLS verification is never disabled.

## Quality gate and baseline

- Verified at the first analysis: the project's gate has no conditions configured (it reports OK without enforcing anything). Configure a gate with the targets below in SonarQube; this repository cannot do it with the available token (administration endpoints return 403).
- Targets: 0 new issues, 0 new vulnerabilities/bugs, 0 unreviewed hotspots, new-code coverage >= 80 %, duplication <= 3 %, A ratings.
- Branch and pull-request analysis depend on the installed edition and were not tested; only the main branch is analysed.
- Baseline before this work: 1 vulnerability (`kotlin:S6474`, missing Gradle dependency verification, fixed by `gradle/verification-metadata.xml`), 0 bugs, 0 code smells.

## Troubleshooting

- "analysis has failed" with `Invalid value of sonar.tests`: a module lacks `src/test/kotlin`; the root build only sets `sonar.tests` when the directory exists.
- Coverage missing: run `check` first, then confirm the XML reports exist.
- 403 on API reads: the token lacks Browse/administer permissions for that endpoint.
