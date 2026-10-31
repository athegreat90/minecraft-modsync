# Distributed Minecraft mod inventory and client synchronization foundation

Adds independently packaged scanner-agent, Spring/MariaDB backend and lightweight CLI/TUI around shared Kotlin models. Inventory uploads are staged and committed idempotently, server assignments fence previous agents, immutable manifests require administrator approval, and client installs preserve unmanaged files with verified staging and journaled rollback.

Includes independent deployment stacks, migration/backup procedures, a deterministic TLS three-host restoration fixture, and release reconciliation for reachable master commits.

Validation: seven Python release/backup transport tests plus static architecture, syntax, Compose, local-link checks and Java health-probe compilation passed. Kotlin/MariaDB/container/native execution remains unverified because dependency download DNS and Docker socket access are blocked. Production readiness, dependency/image pins and platform/security gates are tracked in MEMORY.md and the MVP checklist. No remote publication is included.
