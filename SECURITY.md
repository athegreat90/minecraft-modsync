# Security model

Protect user/device/agent credentials, server inventories, profile preferences and local mod directories. Trust the authenticated administrator, explicit classifications, TLS CA roots, approved metadata and exact artifact hashes. Server paths never become remote shell commands. Downloaded JARs are never executed during scanning or validation.

Local accounts use Argon2id. Bootstrap reads a protected file only when the database has no users. Access credentials are random 256-bit opaque tokens stored hashed in MariaDB. Client access expires after one hour; refresh tokens expire after 30 days and rotate. Agent credentials expire after 30 days and rotate with a short overlap. Agent/device revocation prevents future authenticated access. A lost client refresh acknowledgement requires login again.

Enrollments expire within 60–3600 seconds, are revocable, and are restricted to agent enrollment. A retry for the same UUID may recover a lost acknowledgement before expiry; another UUID cannot reuse a consumed enrollment. Protect enrollment tokens even after the first request until they expire.

Security roles distinguish ADMIN, AGENT and CLIENT. Agents may submit only for current assignments/generations; profiles are scoped to their user; devices can be revoked by their owner. Initial server manifests are visible to all authenticated users on that deployment. Administrators can also sign in with Google or GitHub after an administrator links that provider identity to an existing account (see *Single sign-on* below). Per-server user ACLs and MFA are future work.

HTTPS transport verifies certificates and never follows redirects carrying bearer tokens. Setup pins the persistent control-plane UUID in addition to TLS. Unknown replacement identity requires explicit confirmation and clears prior credentials. Custom CA support uses trusted stores, never a bypass. Python administration can take `--ca-file`.

Backend CORS is disabled, sessions are stateless, public authentication routes have bounded per-address throttling, bodies are capped at 2 MiB, and the server protects Actuator metrics. Do not trust forwarded client addresses from arbitrary sources; add proxy-side throttling for internet exposure. HTTP header limits are configured; a reverse proxy must additionally enforce slow-request/time limits.

Artifact downloads are limited to HTTPS `cdn.modrinth.com/data/`, have time/size limits, and require exact SHA-512 and archive CRC checks. Required unknown artifacts block automatic downloads. Manual artifacts need an authorized exact local file. Personal import currently requires Modrinth projects explicitly declaring unsupported server environments; ambiguous libraries are rejected.

Filesystem defenses reject unsafe cross-platform names, mod symlinks, observable scan races and unmanaged collisions. Locks serialize local operations. Journals/backups protect against interrupted processes and refuse to overwrite later local edits. A hostile local user with write access to the instance/state can defeat local trust; protect these directories. POSIX config files are created mode 0600; Windows ACL hardening and complete power-loss durability remain release gates.

Audit records exclude token/password values and local paths. Structured server logs are enabled; request correlation headers are returned. Operational audit retention, exhaustive failure-event coverage and security penetration testing remain outstanding. Report security issues privately once a repository contact is configured; no public reporting address has been invented.

## Administration interface

The Angular application (`frontend/`) is served by the server under `/ui`; the server alone decides every authentication and authorization question. Angular only renders data and calls the JSON API under `/ui/api`.

- Separate, session-based filter chain for `/ui`, `/login`, `/logout`, `/oauth2/**` and `/login/oauth2/**`; the bearer-token API chain (`/api/v1/**`) is unchanged and stays stateless, so clients and agents are unaffected.
- Only `ADMIN` accounts are admitted. Passwords use the same Argon2 hashes; password sign-ins and provider callbacks are limited to 10 per minute per address. Browsers without a session are redirected to `/ui/login`; script calls to `/ui/api/**` get a plain `401`.
- CSRF protection is enabled for every state-changing request. The token is issued in the `XSRF-TOKEN` cookie (readable by the page script) and echoed in the `X-XSRF-TOKEN` header; the token is never rendered into HTML. The session id is rotated at login, and no anonymous session exists before it.
- Content-Security-Policy: `default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; frame-ancestors 'none'; form-action 'self'`. Scripts come only from the server's own origin. `unsafe-inline` is allowed for **styles only**, because Angular inserts component styles at runtime; scripts are never inline. Frames are denied. The JSON API sends `X-Content-Type-Options: nosniff`.
- State changes are audited and rejected in maintenance mode (409). Terminate TLS in front of the server so the session cookie is only sent over HTTPS.
- The session cookie is `HttpOnly`, `SameSite=Lax` and `Secure` by default; set `MODSYNC_SESSION_COOKIE_SECURE=false` only for a plain-HTTP test setup. `Lax` replaced `Strict` in 0.5.0 because the browser returns from Google/GitHub in a cross-site redirect and `Strict` would drop the session cookie that holds the OAuth `state`; CSRF tokens still protect every write. Sessions time out after 30 minutes and are tracked in an in-memory registry (lost on restart, like the sessions themselves).
- Static files (`/ui/*.js`, `/ui/*.css`, `index.html`) and `/ui/login` are public; everything else under `/ui` needs an administrator session. Client-side routes are served by an explicit allow-list (`SpaController`), so the fallback never captures `/ui/api/**`.

## Single sign-on (Google and GitHub)

Optional; active only for providers whose client id is configured. Full setup, troubleshooting and a checklist: [SSO setup guide](docs/security/SSO_SETUP.md).

- Spring Security OAuth2 Login with the authorization-code flow: `state` validation, PKCE (S256) for both providers and an OIDC `nonce` for Google. Scopes are the minimum: `openid profile email` (Google) and `read:user` (GitHub). GitHub is plain OAuth2 and returns no ID token.
- **Account linking policy:** a provider identity signs in only if it was linked beforehand to an existing account, using the provider's immutable subject (Google `sub`, GitHub numeric id) stored in `external_identities`. E-mail addresses are never stored or compared, there is no self-registration, and a provider can never create an account or change a role. The role always comes from the local account; an unlinked identity is refused with a generic `/ui/login?sso=denied`.
- Linking is done by a signed-in administrator from the Account page: the server stores a five-minute link intent in the session (a CSRF-protected request), and the callback links exactly that provider to exactly that account. An identity already linked to another account is refused. A provider identity maps to one account; an account holds one identity per provider.
- Disabled accounts are refused at the provider sign-in too. Ending an account's sessions (disable, password reset, revoke sessions) expires provider-created sessions the same way as password sessions.
- Provider credentials live only in the server environment or in secret files read by the container entrypoint; they never reach the browser or the logs. Audit events `sso-login`, `sso-linked`, `sso-link-requested`, `sso-unlinked` and `sso-denied:<code>` carry no tokens.
- Limits: there is no administrator screen for other administrators' links; users cannot be forced to re-authenticate with the provider; MFA remains the provider's responsibility. Behind a reverse proxy set `MODSYNC_FORWARD_HEADERS=framework` only when port 8080 is reachable solely through that proxy.

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
- `users.enabled` is checked on every authentication path: API login, refresh, every bearer-token request and the dashboard login. A disabled account gets the same 401 (API) or `/ui/login?error` (dashboard) as a wrong password, and only after the password has been verified, so the response does not reveal the account state.
- Disabling or resetting a password deletes the account's access and refresh tokens and expires its live dashboard sessions after the transaction commits. Disabling keeps the account's devices registered, so enabling it again restores access. "Revoke sessions" ends tokens and sessions only. "Revoke devices" is permanent: a revoked device id can never log in again, so the client must register with a new one; the dashboard requires an explicit confirmation for it.
- Password reset takes a password and its confirmation (16-256 characters, Argon2id). The administrator hands the password over out of band. Generated temporary passwords and forced first-login changes are not implemented, because they need a persisted must-change flag and a client change-password endpoint.
- Audit events (`user-created`, `user-disabled`, `user-enabled`, `user-password-reset`, `user-sessions-revoked`, `user-devices-revoked`) record the acting administrator and the target id only, never a password, hash or token, and are written in the same transaction as the change. Changes lock the account row, so concurrent logins, resets and status changes serialize.
- Known limits: dashboard login throttling is per address, not per username; failed logins are not audited; there is no per-device revocation list in the UI.

