package de.alexandermora.modsync.server.inventory

import kotlinx.serialization.encodeToString
import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.ModEntry
import de.alexandermora.modsync.domain.inventoryDigest
import de.alexandermora.modsync.domain.safeFileName
import de.alexandermora.modsync.domain.uuid
import de.alexandermora.modsync.domain.validHash
import de.alexandermora.modsync.protocol.OverrideRequest
import de.alexandermora.modsync.protocol.PROTOCOL
import de.alexandermora.modsync.protocol.ServerInfo
import de.alexandermora.modsync.protocol.ServerRegistration
import de.alexandermora.modsync.protocol.SnapshotAck
import de.alexandermora.modsync.protocol.SnapshotBatch
import de.alexandermora.modsync.protocol.SnapshotBegin
import de.alexandermora.modsync.protocol.SnapshotView
import de.alexandermora.modsync.protocol.wire
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.common.notFound
import de.alexandermora.modsync.server.common.reject
import de.alexandermora.modsync.server.common.requireState
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val MAX_SNAPSHOT_ENTRIES = 10000
private const val MAX_BATCH_INDEX = 9999
private const val MAX_BATCH_ENTRIES = 100
private const val MAX_FILE_SIZE = 536870912L
private const val MAX_SNAPSHOT_BYTES = 6 * 1024 * 1024
private const val FUTURE_TOLERANCE_SECONDS = 300L
private const val STALE_AFTER_SECONDS = 900L

@Service
class InventoryService(
    private val servers: MinecraftServerRepository,
    private val snapshots: InventorySnapshotRepository,
    private val batches: InventoryEntryRepository,
    private val overrides: ClassificationOverrideRepository,
    private val audit: AuditService,
) {
    @Transactional fun registerServer(actor: String, request: ServerRegistration): String {
        require(request.name.length in 1..200 && request.minecraftVersion.length in 1..80 && request.loader.length in 1..80)
        val id = UUID.randomUUID().toString()
        servers.saveAndFlush(MinecraftServer(id = id, name = request.name, minecraftVersion = request.minecraftVersion, loader = request.loader, loaderVersion = request.loaderVersion))
        audit.record(actor, "server-created", id)
        return id
    }

    fun listServers(): List<ServerInfo> = servers.findAllByOrderByName().map { server ->
        val at = server.inventoryAt
        ServerInfo(server.id, server.name, server.minecraftVersion, server.loader, server.loaderVersion, at?.toString(),
            server.revision.takeIf { it > 0 }, inventoryStale = at == null || at.isBefore(Instant.now().minusSeconds(STALE_AFTER_SECONDS)))
    }

    fun server(id: String): MinecraftServer = servers.findById(uuid(id)).orElse(null) ?: notFound()

    /** Id of the latest accepted snapshot, or a conflict when the agent has not delivered one yet. */
    fun acceptedSnapshot(serverId: String): String = server(serverId).snapshotId ?: reject("No accepted inventory")

    fun inventory(serverId: String): SnapshotView {
        val scan = acceptedSnapshot(serverId)
        return SnapshotView(beginOf(scan), entries(scan))
    }

    fun committedSnapshot(scan: String): InventorySnapshot =
        snapshots.findById(uuid(scan)).orElse(null)?.takeIf { it.committedAt != null } ?: notFound()

    fun overridesFor(serverId: String): Map<String, ClassificationOverride> =
        overrides.findByServerId(serverId).associateBy { it.sha512 }

    @Transactional fun classify(actor: String, serverId: String, request: OverrideRequest) {
        require(validHash(request.sha512) && request.reason.length in 1..1000 && request.classification != Classification.UNKNOWN)
        val server = servers.findById(uuid(serverId)).orElse(null) ?: notFound()
        val key = ClassificationOverrideId(serverId, request.sha512)
        val override = overrides.findById(key).orElse(null) ?: ClassificationOverride(serverId, request.sha512)
        override.classification = request.classification.name
        override.reason = request.reason
        overrides.saveAndFlush(override)
        server.policyRevision += 1
        servers.saveAndFlush(server)
        audit.record(actor, "classification-approved", serverId)
    }

    private fun ownership(agent: String, begin: SnapshotBegin): MinecraftServer {
        val server = servers.lockById(begin.serverId) ?: notFound()
        requireState(server.agentId == agent && server.generation == begin.generation, "Agent assignment is no longer authoritative")
        return server
    }

    private fun beginOf(scan: String): SnapshotBegin =
        wire.decodeFromString<SnapshotBegin>((snapshots.findById(scan).orElse(null) ?: notFound()).payload)

    @Transactional fun begin(agent: String, begin: SnapshotBegin): SnapshotBegin {
        uuid(begin.scanId); uuid(begin.serverId)
        require(begin.protocol == PROTOCOL && begin.count in 0..MAX_SNAPSHOT_ENTRIES && begin.sequence > 0 && validHash(begin.digest))
        require(Instant.parse(begin.scannedAt).isBefore(Instant.now().plusSeconds(FUTURE_TOLERANCE_SECONDS)))
        val server = ownership(agent, begin)
        val existing = snapshots.findById(begin.scanId).orElse(null)
        if (existing != null) {
            requireState(wire.decodeFromString<SnapshotBegin>(existing.payload) == begin, "Conflicting scan retry")
            return begin
        }
        requireState(begin.sequence > server.sequenceNo, "Outdated scan")
        snapshots.saveAndFlush(InventorySnapshot(begin.scanId, begin.serverId, agent, begin.generation, begin.sequence, begin.digest, begin.count,
            Instant.parse(begin.scannedAt), null, wire.encodeToString(begin)))
        return begin
    }

    @Transactional fun batch(agent: String, scan: String, batch: SnapshotBatch) {
        val snapshot = lockedSnapshot(agent, uuid(scan))
        require(batch.index in 0..MAX_BATCH_INDEX && batch.entries.size in 1..MAX_BATCH_ENTRIES &&
            batch.entries.all { safeFileName(it.fileName) && validHash(it.sha512) && it.size in 0..MAX_FILE_SIZE })
        val payload = wire.encodeToString(batch)
        val existing = batches.findBySnapshotIdAndBatchIndex(scan, batch.index)
        if (existing != null) {
            requireState(existing.payload == payload, "Conflicting batch retry")
            return
        }
        requireState(snapshot.committedAt == null, "Snapshot already committed")
        val total = batches.payloadBytes(scan).toLong()
        require(total + payload.toByteArray().size < MAX_SNAPSHOT_BYTES) { "Snapshot payload exceeds 6 MiB" }
        batches.saveAndFlush(InventoryEntry(scan, batch.index, payload))
    }

    fun entries(scan: String): List<ModEntry> {
        val stored = batches.findBySnapshotIdOrderByBatchIndex(scan).map { wire.decodeFromString<SnapshotBatch>(it.payload) }
        requireState(stored.map { it.index } == stored.indices.toList(), "Missing snapshot batches")
        return stored.flatMap { it.entries }
    }

    @Transactional fun commit(agent: String, scan: String): SnapshotAck {
        val snapshot = lockedSnapshot(agent, uuid(scan))
        val begin = wire.decodeFromString<SnapshotBegin>(snapshot.payload)
        snapshot.committedAt?.let { return SnapshotAck(scan, begin.sequence, begin.digest, it.toString()) }
        val entries = entries(scan)
        requireState(entries.size == begin.count && entries.map { it.fileName.lowercase() }.distinct().size == entries.size && inventoryDigest(entries) == begin.digest, "Incomplete or invalid inventory")
        val server = servers.findById(begin.serverId).orElse(null) ?: notFound()
        requireState(begin.sequence > server.sequenceNo, "Outdated inventory")
        val committed = Instant.now().truncatedTo(ChronoUnit.MICROS)
        snapshot.committedAt = committed
        snapshots.saveAndFlush(snapshot)
        server.snapshotId = scan
        server.sequenceNo = begin.sequence
        server.inventoryAt = committed
        servers.saveAndFlush(server)
        audit.record(agent, "inventory-committed", scan)
        return SnapshotAck(scan, begin.sequence, begin.digest, committed.toString())
    }

    // The server is locked before the snapshot, matching the transfer/begin lock order.
    private fun lockedSnapshot(agent: String, scan: String): InventorySnapshot {
        ownership(agent, beginOf(scan))
        val snapshot = snapshots.lockById(scan) ?: notFound()
        requireState(snapshot.agentId == agent, "Agent mismatch")
        return snapshot
    }
}
