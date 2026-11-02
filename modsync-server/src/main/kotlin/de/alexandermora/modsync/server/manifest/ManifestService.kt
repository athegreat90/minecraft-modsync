package de.alexandermora.modsync.server.manifest

import kotlinx.serialization.encodeToString
import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.Evidence
import de.alexandermora.modsync.domain.Manifest
import de.alexandermora.modsync.domain.ModEntry
import de.alexandermora.modsync.domain.safeFileName
import de.alexandermora.modsync.domain.uuid
import de.alexandermora.modsync.domain.validHash
import de.alexandermora.modsync.modrinth.Modrinth
import de.alexandermora.modsync.protocol.PublishRequest
import de.alexandermora.modsync.protocol.SyncReport
import de.alexandermora.modsync.protocol.wire
import de.alexandermora.modsync.resolver.Resolver
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.common.notFound
import de.alexandermora.modsync.server.common.requireState
import de.alexandermora.modsync.server.inventory.InventoryService
import de.alexandermora.modsync.server.inventory.MinecraftServerRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

private val REPORT_STATUSES = setOf("SUCCESS", "FAILED", "ROLLED_BACK")

@Service
class ManifestService(
    private val inventory: InventoryService,
    private val servers: MinecraftServerRepository,
    private val manifests: ServerManifestRepository,
    private val revisions: ManifestRevisionRepository,
    private val history: SynchronizationHistoryRepository,
    private val modrinth: Modrinth,
    private val audit: AuditService,
) {
    fun manifest(serverId: String, revision: Long?): Manifest {
        val id = uuid(serverId)
        val number = revision ?: (manifests.findById(id).orElse(null) ?: notFound()).latestRevision
        val stored = revisions.findById(ManifestRevisionId(id, number)).orElse(null) ?: notFound()
        return wire.decodeFromString<Manifest>(stored.payload)
    }

    fun preview(serverId: String, request: PublishRequest): Manifest {
        val server = inventory.server(serverId)
        requireState(server.snapshotId == request.scanId, "Publish requires latest committed snapshot")
        val snapshot = inventory.committedSnapshot(request.scanId)
        val entries = inventory.entries(request.scanId)
        val overrides = inventory.overridesFor(serverId)
        requireState(entries.all { overrides[it.sha512] != null }, "Every inventory artifact needs an explicit classification approval")
        val artifacts = request.artifacts.map { artifact ->
            require(validHash(artifact.sha512) && safeFileName(artifact.fileName) && artifact.classification != Classification.UNKNOWN)
            val scanned = entries.find { it.sha512 == artifact.sha512 }
            val approval = scanned?.let { overrides.getValue(it.sha512) }
            val classification = approval?.let { Classification.valueOf(it.classification) } ?: artifact.classification
            val evidence = listOf(Evidence("administrator", approval?.reason ?: "Explicit dependency approval"))
            if (artifact.url != null) modrinth.artifact(artifact.sha512, classification, evidence)
            else {
                requireState(scanned != null && scanned.size == artifact.size, "Manual artifact must match scanned inventory")
                artifact.copy(classification = classification, evidence = evidence)
            }
        }
        requireState(entries.all { e -> artifacts.any { it.sha512 == e.sha512 } }, "Manifest must account for every scanned artifact")
        requireState(artifacts.map { it.projectId }.distinct().size == artifacts.size, "Duplicate project pins")
        val manifest = Manifest(serverId, server.revision + 1, server.minecraftVersion, server.loader, server.loaderVersion, artifacts,
            Instant.now().toString(), snapshot.digest, server.policyRevision)
        val validation = Resolver().plan(manifest, emptyList(), artifacts.map { ModEntry(it.fileName, it.sha512, it.size) }, emptyMap(), includeOptional = true)
        requireState(!validation.blocked, "Manifest has unresolved dependencies or compatibility conflicts")
        return manifest
    }

    @Transactional fun publish(actor: String, candidate: Manifest, scanId: String): Manifest {
        val server = servers.lockById(candidate.serverId) ?: notFound()
        requireState(server.policyRevision == candidate.policyRevision, "Classification policy changed; preview again")
        requireState(server.snapshotId == scanId && server.revision + 1 == candidate.revision, "Inventory or manifest changed; preview again")
        val head = manifests.findById(candidate.serverId).orElse(null) ?: ServerManifest(candidate.serverId)
        head.latestRevision = candidate.revision
        manifests.saveAndFlush(head)
        revisions.saveAndFlush(ManifestRevision(candidate.serverId, candidate.revision, wire.encodeToString(candidate), Instant.parse(candidate.createdAt)))
        server.revision = candidate.revision
        servers.saveAndFlush(server)
        audit.record(actor, "manifest-published", "${candidate.serverId}:${candidate.revision}")
        return candidate
    }

    @Transactional fun report(deviceId: String, report: SyncReport) {
        require(report.status in REPORT_STATUSES)
        val server = uuid(report.serverId)
        if (!revisions.existsById(ManifestRevisionId(server, report.revision))) notFound()
        history.saveAndFlush(SynchronizationHistory(UUID.randomUUID().toString(), deviceId, report.serverId, report.revision, report.status))
    }
}
