package de.alexandermora.modsync.server.inventory

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.hibernate.Length
import org.hibernate.annotations.DynamicUpdate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.time.Instant

@Entity @Table(name = "minecraft_servers") @DynamicUpdate
class MinecraftServer(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) var id: String = "",
    @Column(nullable = false, length = 200) var name: String = "",
    @Column(name = "minecraft_version", nullable = false, length = 80) var minecraftVersion: String = "",
    @Column(nullable = false, length = 80) var loader: String = "",
    @Column(name = "loader_version", length = 80) var loaderVersion: String? = null,
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "agent_id", length = 36) var agentId: String? = null,
    @Column(nullable = false) var generation: Long = 0,
    @Column(name = "mapping", length = 100) var mappingName: String? = null,
    @Column(name = "sequence", nullable = false) var sequenceNo: Long = 0,
    @Column(name = "policy_revision", nullable = false) var policyRevision: Long = 0,
    @Column(name = "inventory_at") var inventoryAt: Instant? = null,
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "snapshot_id", length = 36) var snapshotId: String? = null,
    @Column(nullable = false) var revision: Long = 0,
)

@Entity @Table(name = "inventory_snapshots") @DynamicUpdate
class InventorySnapshot(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) var id: String = "",
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "server_id", nullable = false, length = 36) var serverId: String = "",
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "agent_id", nullable = false, length = 36) var agentId: String = "",
    @Column(nullable = false) var generation: Long = 0,
    @Column(name = "sequence", nullable = false) var sequenceNo: Long = 0,
    @JdbcTypeCode(SqlTypes.CHAR) @Column(nullable = false, length = 128) var digest: String = "",
    @Column(name = "entry_count", nullable = false) var entryCount: Int = 0,
    @Column(name = "scanned_at", nullable = false) var scannedAt: Instant = Instant.EPOCH,
    @Column(name = "committed_at") var committedAt: Instant? = null,
    @Column(nullable = false, length = Length.LONG32) var payload: String = "",
)

class InventoryEntryId(var snapshotId: String = "", var batchIndex: Int = 0) : Serializable {
    override fun equals(other: Any?) = other is InventoryEntryId && other.snapshotId == snapshotId && other.batchIndex == batchIndex
    override fun hashCode() = 31 * snapshotId.hashCode() + batchIndex
}

@Entity @Table(name = "inventory_entries") @IdClass(InventoryEntryId::class)
class InventoryEntry(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "snapshot_id", length = 36) var snapshotId: String = "",
    @Id @Column(name = "batch_index") var batchIndex: Int = 0,
    @Column(nullable = false, length = Length.LONG32) var payload: String = "",
)

class ClassificationOverrideId(var serverId: String = "", var sha512: String = "") : Serializable {
    override fun equals(other: Any?) = other is ClassificationOverrideId && other.serverId == serverId && other.sha512 == sha512
    override fun hashCode() = 31 * serverId.hashCode() + sha512.hashCode()
}

@Entity @Table(name = "classification_overrides") @IdClass(ClassificationOverrideId::class)
class ClassificationOverride(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "server_id", length = 36) var serverId: String = "",
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 128) var sha512: String = "",
    @Column(nullable = false, length = 30) var classification: String = "",
    @Column(nullable = false, length = 1000) var reason: String = "",
)

@Repository
interface MinecraftServerRepository : JpaRepository<MinecraftServer, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MinecraftServer s where s.id = :id")
    fun lockById(id: String): MinecraftServer?

    fun findByAgentId(agentId: String): List<MinecraftServer>
    fun findAllByOrderByName(): List<MinecraftServer>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE minecraft_servers s JOIN inventory_snapshots i ON s.snapshot_id=i.id SET s.inventory_at=CURRENT_TIMESTAMP(6) WHERE s.id=:server AND s.agent_id=:agent AND s.generation=:generation AND i.digest=:digest", nativeQuery = true)
    fun markSeen(server: String, agent: String, generation: Long, digest: String): Int
}

@Repository
interface InventorySnapshotRepository : JpaRepository<InventorySnapshot, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventorySnapshot i where i.id = :id")
    fun lockById(id: String): InventorySnapshot?
}

@Repository
interface InventoryEntryRepository : JpaRepository<InventoryEntry, InventoryEntryId> {
    fun findBySnapshotIdAndBatchIndex(snapshotId: String, batchIndex: Int): InventoryEntry?
    fun findBySnapshotIdOrderByBatchIndex(snapshotId: String): List<InventoryEntry>

    @Query(value = "SELECT COALESCE(SUM(OCTET_LENGTH(payload)),0) FROM inventory_entries WHERE snapshot_id=:snapshot", nativeQuery = true)
    fun payloadBytes(snapshot: String): Number
}

@Repository interface ClassificationOverrideRepository : JpaRepository<ClassificationOverride, ClassificationOverrideId> {
    fun findByServerId(serverId: String): List<ClassificationOverride>
}
