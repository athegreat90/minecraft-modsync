package de.alexandermora.modsync.domain

import kotlinx.serialization.Serializable

/** Synchronization state of one client instance relative to one server manifest. */
@Serializable
enum class CoverageStatus {
    UP_TO_DATE, MISSING_MODS, WRONG_VERSION, INCOMPATIBLE, SYNC_PENDING, SYNCING,
    PENDING_GAME_EXIT, SYNC_FAILED, OFFLINE, INVENTORY_STALE, UNKNOWN
}

/**
 * Last verified view of a client instance.
 *
 * @property minecraftVersion Minecraft version recorded in the instance metadata.
 * @property loader Loader name, for example `neoforge`.
 * @property installedHashes SHA-512 hashes observed in the instance during the last verification.
 * @property verifiedAtEpochSeconds Time of the last verified inventory, or null if none exists.
 * @property online Whether the client checked in recently.
 * @property activity Current synchronization activity reported by the client.
 * @property installedProjects Recognized Modrinth project ids, used to tell wrong versions from missing mods.
 */
@Serializable
data class ClientInventoryView(
    val minecraftVersion: String,
    val loader: String,
    val installedHashes: Set<String>,
    val verifiedAtEpochSeconds: Long?,
    val online: Boolean,
    val activity: ClientActivity = ClientActivity.IDLE,
    val installedProjects: Set<String> = emptySet()
)

/**
 * Computes the coverage status of a client against the artifacts a manifest requires from clients.
 *
 * Identity is decided by verified SHA-512 hashes, never by file names. Status precedence is:
 * unknown inventory, stale inventory, incompatible platform, in-flight/failed sync activity,
 * offline, wrong version, missing mods, up to date.
 *
 * @param manifest Published manifest the client should satisfy.
 * @param client Last known client inventory, or null when nothing was ever reported.
 * @param nowEpochSeconds Current time used to judge staleness.
 * @param maxInventoryAgeSeconds Maximum accepted age of a verified inventory.
 * @return The single [CoverageStatus] describing the client.
 */
fun computeCoverage(
    manifest: Manifest,
    client: ClientInventoryView?,
    nowEpochSeconds: Long,
    maxInventoryAgeSeconds: Long = 24 * 3600
): CoverageStatus {
    val verifiedAt = client?.verifiedAtEpochSeconds
    return when {
        client == null || verifiedAt == null -> CoverageStatus.UNKNOWN
        client.minecraftVersion != manifest.minecraftVersion || !client.loader.equals(manifest.loader, ignoreCase = true) ->
            CoverageStatus.INCOMPATIBLE
        client.activity != ClientActivity.IDLE -> activityStatus(client.activity)
        !client.online -> CoverageStatus.OFFLINE
        nowEpochSeconds - verifiedAt > maxInventoryAgeSeconds -> CoverageStatus.INVENTORY_STALE
        else -> installedCoverage(manifest, client)
    }
}

/**
 * Maps a non-idle client activity to its coverage status.
 *
 * @param activity A non-idle activity.
 * @return Matching [CoverageStatus].
 */
private fun activityStatus(activity: ClientActivity): CoverageStatus = when (activity) {
    ClientActivity.IDLE -> CoverageStatus.UNKNOWN
    ClientActivity.SYNC_PENDING -> CoverageStatus.SYNC_PENDING
    ClientActivity.SYNCING -> CoverageStatus.SYNCING
    ClientActivity.PENDING_GAME_EXIT -> CoverageStatus.PENDING_GAME_EXIT
    ClientActivity.SYNC_FAILED -> CoverageStatus.SYNC_FAILED
}

/**
 * Compares installed hashes with required artifacts.
 *
 * An artifact whose hash is absent but whose file name is present under a different hash counts
 * as a wrong version; an artifact absent altogether counts as missing.
 *
 * @param manifest Manifest whose client-relevant artifacts are required.
 * @param client Verified client inventory.
 * @return [CoverageStatus.MISSING_MODS], [CoverageStatus.WRONG_VERSION] or [CoverageStatus.UP_TO_DATE].
 */
private fun installedCoverage(manifest: Manifest, client: ClientInventoryView): CoverageStatus {
    val required = manifest.artifacts.filter { it.classification in clientRequiredClasses }
    val absent = required.filter { it.sha512 !in client.installedHashes }
    return when {
        absent.isEmpty() -> CoverageStatus.UP_TO_DATE
        absent.any { it.projectId in client.installedProjects } -> CoverageStatus.WRONG_VERSION
        else -> CoverageStatus.MISSING_MODS
    }
}

private val clientRequiredClasses = setOf(Classification.CLIENT_REQUIRED, Classification.BOTH_REQUIRED)
