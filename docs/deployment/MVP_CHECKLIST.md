# MVP verification gates

An unchecked item can have implementation source; it remains unchecked until runtime evidence exists.

- [ ] Kotlin modules compile and unit tests pass.
- [ ] MariaDB migrations and database integration tests succeed.
- [ ] Spring Boot and the independent agent execute in Docker.
- [ ] Crafty-compatible read-only storage scanning is verified.
- [ ] Outbound HTTPS agent enrollment, snapshots and acknowledgements execute.
- [ ] Multiple logical servers, ownership fencing and preserved histories execute.
- [ ] Modrinth lookup and unknown/manual artifact policies execute.
- [ ] Immutable manifests and portable personal profiles execute.
- [ ] Lightweight CLI and separate interactive distribution execute.
- [ ] Cross-host synchronization, hash/CRC verification and rollback execute.
- [ ] Repeated sync is idempotent and preserves unmanaged mods.
- [ ] Agent/backend/client migration and database restoration drill pass.
- [ ] DNS changes, network interruptions and stale snapshots pass.
- [ ] amd64/arm64 images build and execute.
- [ ] Windows/Linux native artifacts execute complete workflows, or verified fallbacks are documented.
- [ ] CI/security scans and per-commit release reconciliation execute on GitHub.
- [ ] Backup/key/CA recovery procedures are validated.
- [ ] Representative performance measurements are recorded.
- [ ] Security review, documentation review and remaining-limitations review pass.

See MEMORY.md for local static checks that actually passed. No runtime gate is claimed complete by this checklist.
