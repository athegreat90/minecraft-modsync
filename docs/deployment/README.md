See the maintained [guide](../../DOCKER.md).

## Automatic development prereleases

Every push to `main`, the hourly reconciliation schedule, and manual reconciliation
publish eligible development prereleases automatically. No repository-variable
opt-in is required. The latest eligible `main` commit is dispatched first, followed
by historical reachable commits (including merged branch commits), at most 20 per
pass. Active runs are skipped; canceled runs retry after a six-hour
cooldown measured from their last update, and failed or timed-out runs back off
exponentially (6h, 12h, 24h, ... up to 7 days). Reconciliation starts at
`RELEASE_FLOOR` (`7c29453`); older commits are not rebuilt. The reconciliation log reports failures,
invalid releases, dispatched commits and the remaining backlog.

Release jobs run in parallel after a fast `version` gate (`validate`, `integration` for the distributed TLS fixture, and the native builds), and `publish` waits for all of them. Each immutable `v0.4.3-dev.<UTC timestamp>.<full SHA>` release must pass JVM/database
checks, architecture and API checks, source security scanning, distributed TLS
integration, Linux x64/ARM64 and Windows x64 native builds, native smoke tests and
benchmarks. JVM distributions, native binaries and multi-platform images all use
the same generated version. Publication requires repository/package write access;
GHCR push authorization is checked before creating a tag or draft. Both pushed
image digests must pass the HIGH/CRITICAL vulnerability scan (Trivy v0.76.2) before publication.

Retries resume a draft only when its target and immutable tag resolve to the exact
source commit. Current publication tooling is retained when building historical
sources, so the `vX.Y.Z` prefix of a historical version may differ from the current
application version; only the `<UTC timestamp>.<full SHA>` part must match the source commit. Missing/empty artifacts fail before publication. Draft assets are replaced
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
