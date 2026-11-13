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

## Administration dashboard

- Separate, session-based filter chain for `/ui`, `/login` and `/logout`; the bearer-token API chain is unchanged and stays stateless.
- Only `ADMIN` accounts are admitted. Passwords use the same Argon2 hashes; login posts are limited to 10 per minute per address.
- CSRF protection is enabled for every state-changing request; session cookies are HTTP-only and the session id is rotated at login.
- Content-Security-Policy forbids scripts and foreign resources; frames are denied. All dynamic text is HTML-escaped by Thymeleaf.
- State changes are audited and rejected in maintenance mode. Terminate TLS in front of the server so the session cookie is only sent over HTTPS.
- The session cookie is `HttpOnly`, `SameSite=Strict` and `Secure` by default; set `MODSYNC_SESSION_COOKIE_SECURE=false` only for a plain-HTTP test setup. Sessions time out after 30 minutes and are tracked in an in-memory registry (lost on restart, like the sessions themselves).

## User management and account status

Administrators manage accounts at `/ui/users`; there is no self-registration.

| Operation | ADMIN (dashboard) | CLIENT | AGENT |
|---|---|---|---|
| Open `/ui/**` | yes | 403 | not applicable (bearer-only API chain) |
| List and view accounts | yes | no | no |
| Create a CLIENT account | yes | no | no |
| Disable, enable, reset password, revoke sessions or devices of a CLIENT | yes | no | no |
| Change an ADMIN account, change roles, delete accounts | not supported | no | no |

- Only CLIENT accounts can be changed from the dashboard. Administrator rows are read-only, so privilege escalation and removing the last administrator are impossible from the UI; the service enforces this regardless of what the page shows. Accounts are disabled, never deleted, so devices, profiles and synchronization history keep their references.
- `users.enabled` is checked on every authentication path: API login, refresh, every bearer-token request and the dashboard login. A disabled account gets the same 401 (API) or `/login?error` (dashboard) as a wrong password, and only after the password has been verified, so the response does not reveal the account state.
- Disabling or resetting a password deletes the account's access and refresh tokens and expires its live dashboard sessions after the transaction commits. Disabling keeps the account's devices registered, so enabling it again restores access. "Revoke sessions" ends tokens and sessions only. "Revoke devices" is permanent: a revoked device id can never log in again, so the client must register with a new one; the dashboard requires an explicit confirmation for it.
- Password reset takes a password and its confirmation (16-256 characters, Argon2id). The administrator hands the password over out of band. Generated temporary passwords and forced first-login changes are not implemented, because they need a persisted must-change flag and a client change-password endpoint.
- Audit events (`user-created`, `user-disabled`, `user-enabled`, `user-password-reset`, `user-sessions-revoked`, `user-devices-revoked`) record the acting administrator and the target id only, never a password, hash or token, and are written in the same transaction as the change. Changes lock the account row, so concurrent logins, resets and status changes serialize.
- Known limits: dashboard login throttling is per address, not per username; failed logins are not audited; there is no per-device revocation list in the UI.

