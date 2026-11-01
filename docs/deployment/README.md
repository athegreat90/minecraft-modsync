See the maintained [guide](../../DOCKER.md).

## Automatic development prereleases

Every push to `main`, the hourly reconciliation schedule, and manual reconciliation
publish eligible development prereleases automatically. No repository-variable
opt-in is required. The latest eligible `main` commit is dispatched first, followed
by historical reachable commits (including merged branch commits), at most 20 per
pass. Active runs are skipped; failed and canceled runs retry after a six-hour
cooldown measured from their last update. The reconciliation log reports failures,
invalid releases, dispatched commits and the remaining backlog.

Each immutable `v0.1.0-dev.<UTC timestamp>.<full SHA>` release must pass JVM/database
checks, architecture and API checks, source security scanning, distributed TLS
integration, Linux x64/ARM64 and Windows x64 native builds, native smoke tests and
benchmarks. JVM distributions, native binaries and multi-platform images all use
the same generated version. Publication requires repository/package write access;
GHCR push authorization is checked before creating a tag or draft. Both pushed
image digests must pass the HIGH/CRITICAL vulnerability scan before publication.

Retries resume a draft only when its target and immutable tag resolve to the exact
source commit. Current publication tooling is retained when building historical
sources. Missing/empty artifacts fail before publication. Draft assets are replaced
on retry, then downloaded and checked against the compatibility manifest and
`SHA256SUMS`; registry digests must resolve before the draft becomes visible.
Existing visible releases are verified without mutation. A mismatched tag, source,
checksum, missing asset or inaccessible digest is reported as an error; investigate
it rather than moving immutable tags. Reconciliation counts only verified releases
as complete.

Stable releases and deployment remain separate actions. Automatic development
publication does not close the production-readiness items in
[MVP_CHECKLIST.md](MVP_CHECKLIST.md), including real native synchronization tests,
wrapper/dependency verification, platform/security review and restore drills.
