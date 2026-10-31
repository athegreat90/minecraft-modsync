# Security model

Protect user/device/agent credentials, server inventories, profile preferences and local mod directories. Trust the authenticated administrator, explicit classifications, TLS CA roots, approved metadata and exact artifact hashes. Server paths never become remote shell commands. Downloaded JARs are never executed during scanning or validation.

Local accounts use Argon2id. Bootstrap reads a protected file only when the database has no users. Access credentials are random 256-bit opaque tokens stored hashed in MariaDB. Client access expires after one hour; refresh tokens expire after 30 days and rotate. Agent credentials expire after 30 days and rotate with a short overlap. Agent/device revocation prevents future authenticated access. A lost client refresh acknowledgement requires login again.

Enrollments expire within 60–3600 seconds, are revocable, and are restricted to agent enrollment. A retry for the same UUID may recover a lost acknowledgement before expiry; another UUID cannot reuse a consumed enrollment. Protect enrollment tokens even after the first request until they expire.

Security roles distinguish ADMIN, AGENT and CLIENT. Agents may submit only for current assignments/generations; profiles are scoped to their user; devices can be revoked by their owner. Initial server manifests are visible to all authenticated users on that deployment. Per-server user ACLs and MFA/OIDC are future work.

HTTPS transport verifies certificates and never follows redirects carrying bearer tokens. Setup pins the persistent control-plane UUID in addition to TLS. Unknown replacement identity requires explicit confirmation and clears prior credentials. Custom CA support uses trusted stores, never a bypass. Python administration can take `--ca-file`.

Backend CORS is disabled, sessions are stateless, public authentication routes have bounded per-address throttling, bodies are capped at 2 MiB, and the server protects Actuator metrics. Do not trust forwarded client addresses from arbitrary sources; add proxy-side throttling for internet exposure. HTTP header limits are configured; a reverse proxy must additionally enforce slow-request/time limits.

Artifact downloads are limited to HTTPS `cdn.modrinth.com/data/`, have time/size limits, and require exact SHA-512 and archive CRC checks. Required unknown artifacts block automatic downloads. Manual artifacts need an authorized exact local file. Personal import currently requires Modrinth projects explicitly declaring unsupported server environments; ambiguous libraries are rejected.

Filesystem defenses reject unsafe cross-platform names, mod symlinks, observable scan races and unmanaged collisions. Locks serialize local operations. Journals/backups protect against interrupted processes and refuse to overwrite later local edits. A hostile local user with write access to the instance/state can defeat local trust; protect these directories. POSIX config files are created mode 0600; Windows ACL hardening and complete power-loss durability remain release gates.

Audit records exclude token/password values and local paths. Structured server logs are enabled; request correlation headers are returned. Operational audit retention, exhaustive failure-event coverage and security penetration testing remain outstanding. Report security issues privately once a repository contact is configured; no public reporting address has been invented.
