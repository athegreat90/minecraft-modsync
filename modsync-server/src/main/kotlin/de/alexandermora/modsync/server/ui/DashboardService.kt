package de.alexandermora.modsync.server.ui

import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.Manifest
import de.alexandermora.modsync.domain.PublicationPolicy
import de.alexandermora.modsync.domain.uuid
import de.alexandermora.modsync.protocol.PublicationRecord
import de.alexandermora.modsync.protocol.ServerCoverage
import de.alexandermora.modsync.protocol.ServerInfo
import de.alexandermora.modsync.server.clientinventory.CoverageService
import de.alexandermora.modsync.server.common.notFound
import de.alexandermora.modsync.server.inventory.InventoryService
import de.alexandermora.modsync.server.publication.ManifestGateway
import de.alexandermora.modsync.server.publication.PublicationPersistence
import org.springframework.stereotype.Service

private const val HISTORY_LIMIT = 20
private const val SHORT_HASH = 12

/** One row of the server overview. */
data class ServerRow(val info: ServerInfo, val policy: PublicationPolicy)

/** One scanned server mod with its approval state. */
data class EntryRow(val fileName: String, val sha512: String, val shortHash: String, val classification: String?)

/** One client-relevant artifact of the latest manifest. */
data class ManifestRow(val fileName: String, val projectId: String, val versionId: String, val classification: Classification)

/** Everything shown on a server page. */
data class ServerPage(
    val info: ServerInfo,
    val policy: PublicationPolicy,
    val entries: List<EntryRow>,
    val unreviewed: Int,
    val manifestRevision: Long?,
    val manifest: List<ManifestRow>,
    val coverage: ServerCoverage?,
    val history: List<PublicationRecord>,
)

/** Assembles dashboard view models from persisted data only. */
@Service
class DashboardService(
    private val inventory: InventoryService,
    private val manifests: ManifestGateway,
    private val coverage: CoverageService,
    private val persistence: PublicationPersistence,
) {
    /**
     * Lists all servers with their publication policies, using two queries.
     *
     * @return Rows ordered by server name.
     */
    fun overview(): List<ServerRow> {
        val policies = persistence.policies()
        return inventory.listServers().map { ServerRow(it, policies[it.id] ?: PublicationPolicy.MANUAL) }
    }

    /**
     * Builds the page of one server.
     *
     * Servers without an accepted inventory or published manifest yield empty sections instead of errors.
     *
     * @param serverId Server UUID.
     * @return The page model.
     * @throws org.springframework.web.server.ResponseStatusException with 404 when the server does not exist.
     */
    fun server(serverId: String): ServerPage {
        val id = uuid(serverId)
        val row = inventory.listServers().firstOrNull { it.id == id } ?: notFound()
        val approvals = inventory.overridesFor(id)
        val entries = if (inventory.server(id).snapshotId == null) emptyList() else inventory.inventory(id).entries.map {
            EntryRow(it.fileName, it.sha512, it.sha512.take(SHORT_HASH), approvals[it.sha512]?.classification)
        }
        val manifest: Manifest? = manifests.latest(id)
        return ServerPage(
            row, persistence.policy(id) ?: PublicationPolicy.MANUAL, entries, entries.count { it.classification == null },
            manifest?.revision,
            manifest?.artifacts.orEmpty().map { ManifestRow(it.fileName, it.projectId, it.versionId, it.classification) },
            manifest?.let { coverage.forServer(id) }, persistence.history(id, HISTORY_LIMIT)
        )
    }
}
