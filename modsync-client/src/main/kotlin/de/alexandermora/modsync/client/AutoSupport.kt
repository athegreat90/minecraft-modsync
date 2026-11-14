package de.alexandermora.modsync.client

import de.alexandermora.modsync.domain.Artifact
import de.alexandermora.modsync.domain.Manifest
import de.alexandermora.modsync.domain.PersonalProfile
import de.alexandermora.modsync.domain.SyncPolicy
import de.alexandermora.modsync.domain.uuid
import de.alexandermora.modsync.protocol.Api
import de.alexandermora.modsync.protocol.ClientInventoryReport
import de.alexandermora.modsync.protocol.ServerInfo
import de.alexandermora.modsync.protocol.SyncReport
import de.alexandermora.modsync.protocol.secureWrite
import de.alexandermora.modsync.protocol.wire
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path

/**
 * Per-instance automatic synchronization settings, created only after explicit user consent.
 *
 * @property instanceId Stable instance UUID; the central inventory never identifies an instance by path.
 * @property serverId Selected Minecraft server UUID.
 * @property policy Consented synchronization policy.
 * @property consentedAt ISO-8601 time of the consent.
 * @property lastRevision Last manifest revision known to be applied.
 * @property lastFullCheckEpoch Epoch seconds of the last full local verification.
 * @property lastSequence Last inventory sequence sent.
 * @property lastOutcome Short result name of the last pass.
 * @property profileId Personal profile UUID whose client-only mods are kept and installed, or null for none.
 * @property modsFingerprint Fingerprint of the mods folder after the last successful pass; a different folder forces a full pass.
 */
@Serializable
data class AutoSettings(
    val instanceId: String,
    val serverId: String,
    val policy: SyncPolicy,
    val consentedAt: String,
    val lastRevision: Long? = null,
    val lastFullCheckEpoch: Long = 0,
    val lastSequence: Long = 0,
    val lastOutcome: String = "NONE",
    val profileId: String? = null,
    val modsFingerprint: String? = null
)

/** Persists [AutoSettings] per instance name in the client state directory. */
class AutoStore(stateDir: Path) {
    private val file = stateDir.resolve("auto.json")

    /**
     * Loads all settings.
     *
     * @return Settings keyed by instance name, empty when none exist.
     */
    fun load(): Map<String, AutoSettings> =
        if (Files.exists(file)) wire.decodeFromString(Files.readString(file)) else emptyMap()

    /**
     * Stores the settings of one instance atomically.
     *
     * @param name Instance name.
     * @param settings Settings to persist.
     */
    fun save(name: String, settings: AutoSettings) = secureWrite(file, wire.encodeToString(load() + (name to settings)))

    /**
     * Removes the settings of one instance.
     *
     * @param name Instance name.
     * @return True when settings existed.
     */
    fun remove(name: String): Boolean {
        val current = load()
        if (name !in current) return false
        secureWrite(file, wire.encodeToString(current - name))
        return true
    }
}

/**
 * HTTPS-backed [AutoSyncGateway].
 *
 * @property api Authenticated API client with control-plane identity already verified.
 */
class ApiGateway(private val api: Api) : AutoSyncGateway {
    /**
     * Reads the published revision from the server list, avoiding a manifest download.
     *
     * @param serverId Server UUID.
     * @return Published revision or null.
     */
    override fun publishedRevision(serverId: String): Long? =
        wire.decodeFromString<List<ServerInfo>>(api.request("/api/v1/servers")).firstOrNull { it.id == uuid(serverId) }?.publishedRevision

    /**
     * Downloads the latest manifest.
     *
     * @param serverId Server UUID.
     * @return Manifest.
     */
    override fun manifest(serverId: String): Manifest = wire.decodeFromString(api.request("/api/v1/servers/${uuid(serverId)}/manifest"))

    /**
     * Uploads an inventory report.
     *
     * @param report Report.
     */
    override fun reportInventory(report: ClientInventoryReport) {
        api.request("/api/v1/client/inventory", "POST", wire.encodeToString(report))
    }

    /**
     * Uploads an installation outcome.
     *
     * @param report Outcome.
     */
    override fun reportSync(report: SyncReport) {
        api.request("/api/v1/sync/reports", "POST", wire.encodeToString(report))
    }

    /**
     * Loads a personal profile's artifacts as currently authorized by the server.
     *
     * @param profileId Personal profile UUID.
     * @return The profile's artifacts.
     */
    override fun personalArtifacts(profileId: String): List<Artifact> =
        wire.decodeFromString<List<PersonalProfile>>(api.request("/api/v1/personal")).first { it.id == uuid(profileId) }.artifacts
}

/** Generates OS scheduler definitions that run `modsync auto run --once` periodically without a permanent service. */
object SchedulerTemplates {
    /**
     * Renders a systemd user service and timer pair.
     *
     * @param executable Absolute path of the modsync executable.
     * @param intervalMinutes Check interval in minutes.
     * @return Service and timer unit files separated by a marker line.
     */
    fun systemd(executable: String, intervalMinutes: Int): String {
        require(intervalMinutes in 5..1440 && executable.none { it == '\n' || it == '"' })
        return """
            # modsync-auto.service
            [Unit]
            Description=Minecraft ModSync automatic synchronization

            [Service]
            Type=oneshot
            ExecStart="$executable" auto run --once

            # modsync-auto.timer
            [Unit]
            Description=Run Minecraft ModSync periodically

            [Timer]
            OnBootSec=2min
            OnUnitActiveSec=${intervalMinutes}min
            RandomizedDelaySec=60

            [Install]
            WantedBy=timers.target
            """.trimIndent()
    }

    /**
     * Renders a Windows Task Scheduler registration command.
     *
     * @param executable Absolute path of the modsync executable.
     * @param intervalMinutes Check interval in minutes.
     * @return A `schtasks` command line creating a per-user task.
     */
    fun windows(executable: String, intervalMinutes: Int): String {
        require(intervalMinutes in 5..1439 && executable.none { it == '\n' || it == '"' })
        return "schtasks /Create /TN \"ModSync Auto\" /SC MINUTE /MO $intervalMinutes /TR \"\\\"$executable\\\" auto run --once\" /F"
    }
}
