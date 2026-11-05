package de.alexandermora.modsync.server.clientinventory

import de.alexandermora.modsync.domain.ClientInventoryView
import de.alexandermora.modsync.domain.Manifest
import de.alexandermora.modsync.domain.CoverageStatus
import de.alexandermora.modsync.domain.computeCoverage
import de.alexandermora.modsync.domain.missingClientArtifacts
import de.alexandermora.modsync.domain.uuid
import de.alexandermora.modsync.protocol.CoverageRow
import de.alexandermora.modsync.protocol.ServerCoverage
import de.alexandermora.modsync.server.manifest.ManifestService
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

private val ONLINE_WINDOW: Duration = Duration.ofMinutes(15)
private val MAX_INVENTORY_AGE: Duration = Duration.ofHours(24)

/** Supplies the latest published manifest of a server. */
fun interface LatestManifestSource {
    /**
     * Loads the latest published manifest.
     *
     * @param serverId Server UUID.
     * @return The latest manifest revision.
     */
    fun latest(serverId: String): Manifest
}

/** [LatestManifestSource] backed by the manifest service. */
@Component
class ManifestServiceSource(private val manifests: ManifestService) : LatestManifestSource {
    /**
     * Loads the latest published manifest through [ManifestService].
     *
     * @param serverId Server UUID.
     * @return The latest manifest revision.
     */
    override fun latest(serverId: String): Manifest = manifests.manifest(serverId, null)
}

/** Computes which client instances satisfy the latest published manifest of a server. */
@Service
class CoverageService(
    private val persistence: ClientInventoryPersistence,
    private val manifests: LatestManifestSource,
) {
    /**
     * Builds the compatibility matrix for one server using exactly two database reads for all instances.
     *
     * A client counts as online only when its last verified report is recent; it is never reported as
     * up to date from historical results alone.
     *
     * @param serverId Server UUID.
     * @param now Current time, injectable for deterministic tests.
     * @return Coverage of every instance that selected the server against the latest manifest revision.
     * @throws org.springframework.web.server.ResponseStatusException with 404 when the server has no manifest.
     */
    fun forServer(serverId: String, now: Instant = Instant.now()): ServerCoverage {
        val id = uuid(serverId)
        val manifest = manifests.latest(id)
        val mods = persistence.modsForServer(id)
        val rows = persistence.instancesForServer(id).map { instance ->
            val installed = mods[instance.instanceId].orEmpty()
            val view = ClientInventoryView(
                minecraftVersion = instance.minecraftVersion, loader = instance.loader,
                installedHashes = installed.map { it.sha512 }.toSet(), verifiedAtEpochSeconds = instance.verifiedAt.epochSecond,
                online = Duration.between(instance.verifiedAt, now) <= ONLINE_WINDOW, activity = instance.activity,
                installedProjects = installed.mapNotNull { it.projectId }.toSet()
            )
            val status: CoverageStatus = computeCoverage(manifest, view, now.epochSecond, MAX_INVENTORY_AGE.seconds)
            CoverageRow(
                instance.instanceId, instance.name, status, instance.appliedRevision, instance.verifiedAt.toString(),
                missingClientArtifacts(manifest, view).size
            )
        }
        return ServerCoverage(id, manifest.revision, rows)
    }
}
