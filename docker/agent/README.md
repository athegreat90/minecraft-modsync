# Host A

Deploy beside existing Crafty storage. Do not modify the Crafty installation or expose an agent port. Copy `.env.example` to `.env`, set actual host storage/state paths and grant UID 10001 read-only access to intended Minecraft data plus read/write access to its own state.

The administrator on B creates a short-lived enrollment and transfers the token securely into the agent state directory as an enrollment-only file. Do not place the token in shell history or Compose environment examples. Verify B's control-plane UUID independently.

```sh
docker compose --env-file .env build
docker compose --env-file .env run --rm agent setup --server-url https://modsync.example.net --trust-server-id CONTROL_PLANE_UUID
docker compose --env-file .env run --rm agent register --enrollment-token-file /app/state/enrollment-token
docker compose --env-file .env run --rm agent map survival /minecraft/SERVER_DIRECTORY/mods
```

Delete the enrollment-only file securely after acknowledgement. The administrator creates/assigns the logical server to this agent's UUID and alias `survival`. Then:

```sh
docker compose --env-file .env run --rm agent scan --server SERVER_UUID
docker compose --env-file .env up -d
```

The resident agent polls at a configurable interval, adds bounded jitter and stops after eight consecutive failed cycles so an operator/restart policy can handle prolonged failure. Progress, pending snapshots and revocable credentials live in the protected state mount. Credentials rotate before expiry during successful cycles. An agent offline beyond its credential lifetime needs controlled re-enrollment with a new agent assignment.

Standalone: extract the agent JVM distribution, use its `bin/modsync-agent` launcher and the same commands with `--state-dir /protected/path` before the subcommand. A verified native executable is optional. One-shot `scan` needs no resident process or Docker.

`MODSYNC_SERVER_URL` supplies setup's endpoint default; setup records the endpoint/control-plane identity in persistent state. Changing this environment variable alone does not replace enrolled state: rerun setup with the verified UUID. If progress state is lost while credentials remain, explicitly reassign the logical server to increment generation before resuming; never guess a higher sequence.
