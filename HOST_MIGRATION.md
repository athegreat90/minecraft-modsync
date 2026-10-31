# Independent host migration

## A: Crafty and Minecraft storage

1. Use Crafty's supported world/configuration backup and restore procedures; ModSync never moves or deletes world data.
2. Restore storage on A2, preserving its contents and validating Minecraft separately.
3. Deploy a **new** agent with a new protected state directory and enroll it with B.
4. Configure local aliases against A2's actual paths; do not reuse Crafty's container-only paths blindly.
5. Record existing logical server UUIDs and previous inventory digests. Use the administrator assignment endpoint to transfer each server to the replacement agent/alias.
6. Transfer increments ownership generation; old-agent writes are now rejected. Re-scan and compare the new inventory with the previous accepted snapshot. Differences require review before publishing a new manifest.
7. Confirm unchanged logical IDs, historical revisions and classification overrides; revoke the old agent after cutover. Rollback requires another explicit assignment transfer, not restoring an old generation counter.

There is no automatic inventory-similarity merge. If discovery cannot establish identity, create or transfer a logical server explicitly. Enrollment/assignment, not an IP/path/container name, defines authority.

## B: ModSync and MariaDB

1. Record version/digests, schema history, control-plane UUID and health. Prepare the same application/database versions on B2.
2. Place B1 in maintenance mode and stop API writers. Freeze migration/deployment jobs.
3. Export and checksum MariaDB using [DATABASE.md](DATABASE.md). Back up deployment settings, secret sources, proxy/TLS keys and CA trust. Verify a restore before retiring anything.
4. Restore into B2's isolated database. Preserve the database UUIDs, credentials, manifests and user profiles. Start one application writer and let Flyway validate.
5. Confirm existing login/agent credentials and historical data on B2. Stop or fence B1 before moving DNS/proxy routing.
6. Update routing; agents/clients retain their configured stable hostname and reconnect securely. If the hostname must change, update endpoints while preserving local device/agent state and verify control-plane UUID.
7. Keep B1 stopped for rollback until validation completes. After B2 accepts writes, reverting B1 without data reconciliation is unsafe. This procedure does not promise zero downtime.

For an unavoidable trust/credential compromise, revoke affected registrations, rotate secrets/certificates and re-enroll agents explicitly. Do not fabricate a matching UUID to disguise an unrelated replacement deployment.

## C: Client computer

1. Install a supported native build or JVM fallback on C2; C1 need not be available.
2. Configure B's HTTPS endpoint and verify its control-plane identity.
3. Login as the same user. C2 receives a new device UUID; C1 remains registered.
4. Discover/register the new launcher game directory, retrieve servers and personal profiles, and select the intended configuration.
5. Run `sync plan --instance game --server UUID --profile PROFILE_UUID --out plan.json`, review, then apply and verify.
6. Report missing game/loader installations or private artifacts explicitly. ModSync installs mods, not Minecraft/NeoForge itself.
7. Revoke C1 through the authenticated device API when desired. Paths and previous mod folders do not need to be copied.
