# Code quality tooling

All checks run through Gradle (`./gradlew.bat` on Windows) and are part of `check` unless noted.

| Tool | Task | Scope and policy |
|---|---|---|
| Tests + JaCoCo | `test`, `jacocoTestReport` | XML reports at `<module>/build/reports/jacoco/test/jacocoTestReport.xml`; imported by Sonar from that default location. |
| Detekt `2.0.0-alpha.6` (`dev.detekt`) | `detekt` | Config in `config/detekt/detekt.yml`, `maxIssues` 0. Requires KDoc on public classes and functions. |
| Spotless + ktlint | `spotlessCheck` / `spotlessApply` | Ratcheted: only files changed against `origin/main` (override with `-Pspotless.ratchetFrom=<ref>`). Settings in `.editorconfig`. |
| Dokka `2.3.0` | `dokkaGenerate` (not in `check`; CI runs it) | `failOnWarning` per module, so broken KDoc links fail the build. HTML at `build/dokka/html`. |
| Architecture / OpenAPI | `scripts/check-architecture.py`, `scripts/openapi.py --check` | See `ARCHITECTURE.md`. |

## Decisions and known limits

- **Detekt 2.0 alpha.** The stable Detekt 1.23.8 fails on JDK 25 (`Invalid value (25) passed to --jvm-target`, then a parse failure on the `25.0.2` version string). The 2.0 alpha line is the only one that runs here; revisit when 2.0 is stable.
- **Detekt baseline.** Each module has a `detekt-baseline.xml` with the findings that existed in code before this work (349: undocumented public declarations, long lines, a few generic catches). New or rewritten code must be clean. Do not regenerate the baseline to hide new findings; shrink it when you touch a file.
- **KDoc coverage.** Detekt checks public classes and functions with the compiler AST, Dokka verifies links. Private functions are not machine-checked; the project convention (and review) requires KDoc for every new function, including private ones.
- **Formatting scope.** `Domain.kt`, `Protocol.kt`, `Client.kt`, `Installer.kt`, `Security.kt` and `InventoryController.kt` are excluded from Spotless: they are legacy dense files and the OpenAPI generator parses the DTO files line by line.
- **Dependency integrity.** `gradle/verification-metadata.xml` pins checksums; after changing dependencies run `./gradlew.bat check dokkaGenerate :modsync-server:bootJar --write-verification-metadata sha256 --write-locks`.

## Local run

```
./gradlew check dokkaGenerate
python3 scripts/check-architecture.py && python3 scripts/openapi.py --check
```
