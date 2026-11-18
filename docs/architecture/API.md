# API v1

Protocol messages are defined in `modsync-protocol/.../Protocol.kt`; domain payloads in `modsync-domain/.../Domain.kt`. Requests require `X-ModSync-Protocol: 1` and `X-ModSync-Version: 0.2.0` (or newer), except public info. Authenticated calls use a bearer token. JSON unknown fields support compatible additions; incompatible protocol/minimum versions return 426. The checked [OpenAPI contract](openapi.json) is generated from domain/protocol DTOs and an explicit route registry with `python3 scripts/openapi.py`. CI checks it with `--check`; authenticated users can retrieve it at `/api/v1/openapi.json`. Framework-level contract execution remains unverified.

| Route | Authority | Purpose |
|---|---|---|
| GET `/api/v1/info` | public | Persistent control-plane UUID and compatibility |
| POST `/api/v1/auth/login` | public, throttled | Username/password/device UUID |
| POST `/api/v1/auth/refresh` | refresh token | Rotate client session |
| POST `/api/v1/enroll` | enrollment token | Enroll one stable agent UUID |
| POST `/api/v1/admin/users` | admin | Create a client user |
| POST `/api/v1/admin/enrollments` | admin | Issue bounded-lifetime enrollment |
| POST `/api/v1/admin/enrollments/revoke` | admin | Revoke pending enrollments |
| POST `/api/v1/admin/servers` | admin | Register a logical Minecraft server |
| PUT `/api/v1/admin/servers/{id}/assignment` | admin | Transfer ownership and increment generation |
| POST `/api/v1/admin/agents/{id}/revoke` | admin | Revoke agent credentials |
| GET `/api/v1/admin/agents` | admin | Heartbeat age/freshness information |
| GET `/api/v1/agent/assignments` | agent | Authorized mapping aliases |
| POST `/api/v1/agent/heartbeat` | agent | Heartbeat and unchanged-scan confirmations |
| POST `/api/v1/agent/credentials/rotate` | agent | Renew credential with short overlap |
| POST `/api/v1/agent/snapshots` | agent | Begin/retry a bounded versioned upload |
| PUT `/api/v1/agent/snapshots/{id}/batches` | assigned agent | Idempotent indexed entries |
| POST `/api/v1/agent/snapshots/{id}/commit` | assigned agent | Validate completeness and acknowledge |
| GET `/api/v1/servers` | user | Logical servers and inventory timestamps |
| GET `/api/v1/servers/{id}/manifest?revision=N` | user | Latest or historical immutable manifest |
| GET `/api/v1/admin/servers/{id}/inventory` | admin | Current accepted snapshot |
| GET `/api/v1/admin/servers/{id}/identified` | admin | Batch Modrinth hash identification |
| PUT `/api/v1/admin/servers/{id}/classification` | admin | Hash-specific classification and reason |
| POST `/api/v1/admin/servers/{id}/preview` | admin | Validate proposed complete artifact set |
| POST `/api/v1/admin/servers/{id}/publish` | admin | Validate and publish an immutable revision |
| GET `/api/v1/mods/{project}/updates?minecraft=V&loader=L` | user | Compatible version candidates |
| GET `/api/v1/personal` | owner | Portable profiles |
| PUT `/api/v1/personal/{id}` | owner | Canonical verified client-only profile import |
| GET `/api/v1/devices` | owner | Active device UUIDs |
| POST `/api/v1/devices/{id}/revoke` | owner | Explicit device revocation |
| POST `/api/v1/sync/reports` | device | Record installation outcome |
| GET `/api/v1/admin/servers/{id}/coverage` | admin | Coverage matrix of all client instances that selected the server against its latest manifest (hash-based, two queries, no N+1). Clients are online only if verified within 15 minutes and stale after 24 hours. |
| PUT `/api/v1/admin/servers/{id}/publication-policy` | admin | Set `MANUAL` (default), `REVIEW_UNKNOWN` (auto-publish only when every artifact's exact hash is already approved) or `TRUSTED_AUTO` (also inherit the classification of a project an earlier manifest already classified). Evaluated after each snapshot commit on a background thread; a file Modrinth cannot identify publishes only when the latest manifest already lists its exact hash as a manual artifact with a known classification (carried over unchanged); other unknown/unidentified artifacts, dependency or compatibility conflicts never publish and are queued as `REVIEW_REQUIRED`. |
| GET `/api/v1/admin/servers/{id}/publications` | admin | Publication history (outcome PUBLISHED, SKIPPED_NO_CHANGE, REVIEW_REQUIRED, FAILED), policy, revision and actor; automatic approvals are recorded as `system:auto-publish`. |
| POST `/api/v1/client/inventory` | device | Report a client instance's verified mod inventory. Per-instance `sequence`: higher replaces, equal is an idempotent retry, lower is returned with `accepted=false`. Instances owned by another device return 404. |

Snapshots allow at most 10,000 entries and 6 MiB staged encoded batches. Requests are limited to 2 MiB; the agent sends one entry per batch. Large metadata can therefore fail explicitly rather than publish incomplete state. UUID/count/hash/digest/generation/sequence checks apply transactionally. Stage cleanup/retention and general server-list pagination remain outstanding scalability work.

Publication requires every accepted inventory hash to have an explicit classification override. Submit `PublishRequest` with the latest scan UUID and complete pinned `Artifact` set. Official URLs/versions are reconstructed from Modrinth hashes; caller-supplied download URLs are not trusted. Additional exact dependencies must be included; unresolved requirements block publication. Manual artifacts use `url: null` and must match a scanned hash/size. No automatic publication occurs after a scan.

Use `scripts/admin.py --config CONFIG --method POST --data REQUEST.json --out RESPONSE.json admin/servers` after an administrator CLI login. It writes protected responses rather than printing enrollment tokens. `--secret-field token` can export an enrollment token alone to an explicitly selected protected output file. Transfer it securely to Host A, then remove it after enrollment. Do not store credentials in example requests or tracked files.

## Administration API (`/ui/api`)

Used only by the Angular administration interface; it is **not** part of the client/agent contract in `openapi.json`. Every route needs an administrator session cookie; writes also need the `X-XSRF-TOKEN` header (the value of the `XSRF-TOKEN` cookie). Anonymous calls answer `401`, non-administrators `403`, maintenance mode answers `409` for writes, and malformed bodies answer `400`. Refusals of account commands return `{"code","message"}`.

| Route | Purpose |
|---|---|
| GET `/ui/api/session` | Username and role of the signed-in administrator |
| GET `/ui/api/overview` | Servers with publication policy |
| GET `/ui/api/servers/{id}` | One server: inventory, manifest, coverage, history |
| PUT `/ui/api/servers/{id}/publication-policy` | `{"policy"}` |
| PUT `/ui/api/servers/{id}/classification` | `{"sha512","classification","reason"}` |
| POST `/ui/api/servers/{id}/reevaluate` | Re-run automatic publication |
| GET `/ui/api/users?q&role&status&page` | Search accounts (25 per page) |
| GET `/ui/api/users/{id}` | One account |
| POST `/ui/api/users` | Create a CLIENT account (201) |
| POST `/ui/api/users/{id}/disable`, `/enable`, `/revoke-sessions` | Account lifecycle |
| POST `/ui/api/users/{id}/password` | `{"password","confirmation"}` |
| POST `/ui/api/users/{id}/revoke-devices` | `{"confirm": true}` (required) |
| GET `/ui/api/sso/providers` | Public: configured providers (`{"providers":["github","google"]}`) |
| GET `/ui/api/me/identities` | Linked and available providers of the signed-in account |
| POST `/ui/api/me/identities/{provider}/link` | Prepare linking; returns `{"authorizationUrl"}` |
| DELETE `/ui/api/me/identities/{provider}` | Unlink (204) |
