# Deploy scripts

One script per deployment phase, each in **zsh** (`.zsh`) and **PowerShell** (`.ps1`) with the same options. They never build anything: images come from GHCR and the client from the GitHub Release. Each script is fail-fast, never prints secrets and supports `--help` (`Get-Help .\script.ps1` in PowerShell).

## Phases

| Phase | Host | Scripts | Result |
|---|---|---|---|
| 1 | B (server) | `01-server.zsh` / `.ps1` | Secrets, `server/.env`, running API (+ bundled MariaDB), health check, prints `controlPlaneId` |
| 2 | Admin machine | `02-admin.zsh` / `.ps1` | Pinned server, admin login, registered Minecraft server, `enrollment-token` file |
| 3 | A (Minecraft host) | `03-agent.zsh` / `.ps1` | Running scanning agent (token consumed and deleted) |
| 4 | C (player) | `04-client.zsh` / `.ps1` | Native client, pinned server, login, registered instance |

Data flow: phase 1 prints `controlPlaneId` and writes `secrets/bootstrap_password`; phase 2 needs both and writes `enrollment-token`; phase 3 consumes that token; phase 4 needs `controlPlaneId` and a user password.

Requirements: Docker (phases 1 and 3), Python 3 (phase 2), `curl` or PowerShell 7+/5.1. If GHCR packages are private run `docker login ghcr.io` first. If PowerShell blocks scripts: `Set-ExecutionPolicy -Scope Process Bypass`.

## Shared options

| zsh | PowerShell | Meaning |
|---|---|---|
| `--version TAG` | `-Version TAG` | Release tag (default: newest release) |
| `--repo OWNER/REPO` | `-Repo OWNER/REPO` | Publisher, for forks (default `athegreat90/minecraft-modsync`) |
| `--work-dir DIR` | `-WorkDir DIR` | Compose files, `.env`, secrets, client (default `./modsync-deploy`) |

Compose files next to the script (`server/`, `agent/`) are used if present, otherwise downloaded from the release tag (fallback `main`). Existing secrets are kept on re-run.

## Usage

### Phase 1: server (Host B)

```zsh
./01-server.zsh --url http://127.0.0.1:8080
# external MariaDB (database and user must already exist)
./01-server.zsh --db-host db.example.org --db-password-file ./modsync-db.pw --db-ssl-mode verify-full
```
```powershell
.\01-server.ps1 -Url http://127.0.0.1:8080
```
Options: `--secrets-dir`, `--bind` (default `127.0.0.1`), `--url`, `--maintenance true|false`, `--db-mode bundled|external`, `--db-host/--db-port/--db-name/--db-user/--db-password-file/--db-ssl-mode/--db-url`, `--skip-db-check` (PowerShell: `-SecretsDir -Bind -Url -Maintenance -DbMode -DbHost ... -SkipDbCheck`).

### Phase 2: admin machine

```zsh
./02-admin.zsh --server-url https://modsync.example.org --control-plane-id CONTROL_PLANE_UUID \
  --password-file modsync-deploy/secrets/bootstrap_password --name survival --minecraft-version 1.21.1
```
Required: `--server-url`, `--control-plane-id`, `--password-file`, `--name`, `--minecraft-version`. Optional: `--loader` (default `neoforge`), `--loader-version`, `--state-dir`, `--out-dir` (default `./admin-out`), `--ttl` (default 600), `--ca-file`. Outputs `server-response.json` and `enrollment-token`; give only the token to the agent operator.

### Phase 3: agent (Host A)

```zsh
./03-agent.zsh --server-url https://modsync.example.org --control-plane-id CONTROL_PLANE_UUID \
  --token-file ./enrollment-token --state-path /srv/modsync/agent --servers-path /srv/crafty/servers \
  --alias survival --mods-dir survival/mods
```
Required: `--server-url`, `--control-plane-id`, `--token-file`, `--state-path`, `--servers-path` (mounted read-only at `/minecraft`), `--alias`, `--mods-dir` (relative to `--servers-path`). Optional: `--server-uuid` (triggers a first scan), `--interval` (30..86400, default 300).

### Phase 4: client (player machine)

```zsh
./04-client.zsh --server-url https://modsync.example.org --control-plane-id CONTROL_PLANE_UUID \
  --username alex --password-file ~/alex.pw --instance-name survival --instance-path ~/mc/survival \
  --minecraft-version 1.21.1 --loader neoforge --add-to-path
```
Required: `--server-url`, `--control-plane-id`, `--username`. Optional: `--password-file`, `--state-dir`, `--add-to-path`. With `--instance-path` also give `--instance-name`, `--minecraft-version`, `--loader` (`--loader-version` optional); without it the script runs `modsync instances discover`.

PowerShell equivalents use PascalCase parameters (`-ServerUrl`, `-ControlPlaneId`, `-TokenFile`, ...); see `Get-Help .\NN-name.ps1 -Full`.

Run without the repository:

```bash
curl -fsSLO https://raw.githubusercontent.com/athegreat90/minecraft-modsync/main/scripts/deploy/01-server.zsh && chmod +x 01-server.zsh
```

See also [docs/USAGE.md](../../docs/USAGE.md#11-deploy-scripts-one-per-phase).

## Prompts for AI coding agents

Paste one of these into Claude Code, Codex or Antigravity from the repository root (or an empty folder for the no-repo flow). Replace the `<...>` placeholders; never paste passwords or tokens into the prompt.

### Common rules (included in every prompt)

```text
Rules: read scripts/deploy/README.md and run the script with --help (or Get-Help) before using it.
Ask me for any missing input, one question at a time. Never print, log, commit or read the contents of
secret files (bootstrap_password, db_password, enrollment-token, *.pw, .env); pass them only by file path.
Before touching any remote host, Docker daemon or paid/shared resource, tell me the exact command and
wait for my confirmation. Do not edit scripts or repository files. Stop on the first failure and report it.
```

### Phase 1: deploy the server

```text
Deploy the ModSync server (phase 1) on this machine using scripts/deploy/01-server.<zsh|ps1>.
Public URL: <https://modsync.example.org or http://127.0.0.1:8080>. Database: <bundled | external host + password file path>.
Then verify GET <url>/api/v1/info works and report the controlPlaneId and where bootstrap_password was written (path only).
<common rules>
```

### Phase 2: register the Minecraft server (admin)

```text
Run scripts/deploy/02-admin.<zsh|ps1> with server URL <url>, controlPlaneId <uuid>, password file <path>,
Minecraft server name <name>, Minecraft version <version>, loader <neoforge|fabric|...>.
Confirm that enrollment-token and server-response.json exist in the output directory and report the server id
(not the token). Remind me to transfer the token file to the agent host over a secure channel.
<common rules>
```

### Phase 3: start the agent

```text
Run scripts/deploy/03-agent.<zsh|ps1> on this host with server URL <url>, controlPlaneId <uuid>,
token file <path>, state path <dir>, servers path <crafty servers root>, alias <name>, mods dir <relative path>,
server UUID <uuid from phase 2>. Verify the agent container is running and that the first scan completed
(docker compose logs, no secrets). Confirm the token file was consumed.
<common rules>
```

### Phase 4: set up a player client

```text
Run scripts/deploy/04-client.<zsh|ps1> with server URL <url>, controlPlaneId <uuid>, username <name>,
password file <path>, instance name <name>, instance path <dir>, Minecraft version <version>, loader <loader>.
Verify the connection with `modsync` (connection test) and list the registered instance.
<common rules>
```

### Full deployment (single machine, all phases)

```text
Deploy ModSync end to end on this machine following scripts/deploy/README.md: phase 1, 2, 3, then 4, in order.
Collect all inputs first and show me a summary table (without secrets) for approval. After each phase verify its
result before continuing, and finish with a report: URL, controlPlaneId, server id, agent status, client instance.
<common rules>
```

### Tool notes

- **Claude Code**: run `claude` in the repo and paste the prompt, or `claude -p "<prompt>"` for non-interactive runs (keep permission prompts on so confirmations are honored).
- **Codex**: run `codex` in the repo and paste the prompt, or `codex exec "<prompt>"`; use the default approval mode so remote and Docker commands ask first.
- **Antigravity**: open the repo as a workspace, paste the prompt in the agent panel and keep terminal commands set to "request review".
