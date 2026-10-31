# MVP verification gates

An unchecked item can have implementation source; it remains unchecked until runtime evidence exists.

- [x] Kotlin modules compile and unit tests pass.
- [x] MariaDB migrations and database integration tests succeed.
- [x] Spring Boot and the independent agent execute in Docker.
- [x] Crafty-compatible read-only storage scanning is verified.
- [x] Outbound HTTPS agent enrollment, snapshots and acknowledgements execute.
- [ ] Multiple logical servers, ownership fencing and preserved histories execute.
- [ ] Modrinth lookup and unknown/manual artifact policies execute.
- [ ] Immutable manifests and portable personal profiles execute.
- [ ] Lightweight CLI and separate interactive distribution execute.
- [x] Cross-host synchronization, hash/CRC verification and rollback execute.
- [x] Repeated sync is idempotent and preserves unmanaged mods.
- [x] Agent/backend/client migration and database restoration drill pass.
- [x] DNS changes, network interruptions and stale snapshots pass.
- [ ] amd64/arm64 images build and execute.
- [ ] Windows/Linux native artifacts execute complete workflows, or verified fallbacks are documented.
- [ ] CI/security scans and per-commit release reconciliation execute on GitHub.
- [ ] Backup/key/CA recovery procedures are validated.
- [ ] Representative performance measurements are recorded.
- [ ] Security review, documentation review and remaining-limitations review pass.

See MEMORY.md for local static checks that actually passed. No runtime gate is claimed complete by this checklist.

Checked runtime items are backed by GitHub Actions run 41249375173 on Linux amd64 with deterministic synthetic Minecraft/Modrinth fixtures. This is not live Crafty/Minecraft gameplay or native/ARM64 verification. The overall run failed at the subsequent source scan because the fixture generated a temporary TLS key; scan ordering is corrected in the next revision.
