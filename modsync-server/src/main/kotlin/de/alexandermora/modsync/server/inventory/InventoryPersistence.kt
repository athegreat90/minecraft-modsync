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

/**
 * Registered Minecraft server with its assignment and accepted-inventory pointers.
 *
 * @property id Server UUID.
 * @property name Display name.
 * @property minecraftVersion Minecraft version.
 * @property loader Loader name.
 * @property loaderVersion Loader version, if known.
 * @property agentId Assigned agent UUID, or null.
 * @property generation Assignment generation that fences uploads of previous agents.
 * @property mappingName Agent-side directory mapping name.
 * @property sequenceNo Highest accepted snapshot sequence.
 * @property policyRevision Counter bumped on every classification change.
 * @property inventoryAt Time of the latest accepted or confirmed inventory.
 * @property snapshotId Accepted snapshot UUID, or null.
 * @property revision Latest published manifest revision, 0 when none.
 */
@Entity
@Table(name = "minecraft_servers")
@DynamicUpdate
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
    @Column(nullable = false) var revision: Long = 0
)

/**
 * Uploaded inventory snapshot header; invisible until [committedAt] is set.
 *
 * @property id Snapshot (scan) UUID.
 * @property serverId Server UUID.
 * @property agentId Uploading agent UUID.
 * @property generation Assignment generation at upload time.
 * @property sequenceNo Agent-supplied sequence.
 * @property digest Declared inventory digest.
 * @property entryCount Declared number of entries.
 * @property scannedAt Scan time reported by the agent.
 * @property committedAt Commit time, or null while uploading.
 * @property payload Serialized snapshot header.
 */
@Entity
@Table(name = "inventory_snapshots")
@DynamicUpdate
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
    @Column(nullable = false, length = Length.LONG32) var payload: String = ""
)

/**
 * Composite key of [InventoryEntry].
 *
 * @property snapshotId Snapshot UUID.
 * @property batchIndex Batch position within the snapshot.
 */
class InventoryEntryId(var snapshotId: String = "", var batchIndex: Int = 0) : Serializable {
    override fun equals(other: Any?) = other is InventoryEntryId && other.snapshotId == snapshotId && other.batchIndex == batchIndex

    override fun hashCode() = 31 * snapshotId.hashCode() + batchIndex
}

/**
 * One uploaded batch of snapshot entries.
 *
 * @property snapshotId Snapshot UUID.
 * @property batchIndex Batch position within the snapshot.
 * @property payload Serialized batch.
 */
@Entity
@Table(name = "inventory_entries")
@IdClass(InventoryEntryId::class)
class InventoryEntry(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "snapshot_id", length = 36) var snapshotId: String = "",
    @Id @Column(name = "batch_index") var batchIndex: Int = 0,
    @Column(nullable = false, length = Length.LONG32) var payload: String = ""
)

/**
 * Composite key of [ClassificationOverride].
 *
 * @property serverId Server UUID.
 * @property sha512 Artifact SHA-512.
 */
class ClassificationOverrideId(var serverId: String = "", var sha512: String = "") : Serializable {
    override fun equals(other: Any?) = other is ClassificationOverrideId && other.serverId == serverId && other.sha512 == sha512

    override fun hashCode() = 31 * serverId.hashCode() + sha512.hashCode()
}

/**
 * Administrator-approved classification of an artifact on one server.
 *
 * @property serverId Server UUID.
 * @property sha512 Artifact SHA-512.
 * @property classification Classification name.
 * @property reason Mandatory justification.
 */
@Entity
@Table(name = "classification_overrides")
@IdClass(ClassificationOverrideId::class)
class ClassificationOverride(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "server_id", length = 36) var serverId: String = "",
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 128) var sha512: String = "",
    @Column(nullable = false, length = 30) var classification: String = "",
    @Column(nullable = false, length = 1000) var reason: String = ""
)

/** Repository of [MinecraftServer] rows. */
@Repository
interface MinecraftServerRepository : JpaRepository<MinecraftServer, String> {
    /**
     * Row-locks a server so assignment, snapshot begin and transfer serialize on it.
     *
     * @param id Server UUID.
     * @return The locked server, or null.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MinecraftServer s where s.id = :id")
    fun lockById(id: String): MinecraftServer?

    /**
     * Lists servers currently assigned to the agent.
     *
     * @param agentId Agent UUID.
     * @return Assigned servers.
     */
    fun findByAgentId(agentId: String): List<MinecraftServer>

    /**
     * Lists all servers sorted by name.
     *
     * @return All servers.
     */
    fun findAllByOrderByName(): List<MinecraftServer>

    /**
     * Refreshes a server's inventory freshness timestamp, fenced by agent, generation and accepted snapshot digest.
     *
     * @param server Server UUID.
     * @param agent Reporting agent UUID.
     * @param generation Assignment generation the agent believes in.
     * @param digest Digest of the snapshot the agent still sees as current.
     * @return 1 when the report matched the authoritative state, 0 when it was stale or foreign and was ignored.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE minecraft_servers s JOIN inventory_snapshots i ON s.snapshot_id=i.id SET s.inventory_at=CURRENT_TIMESTAMP(6) WHERE s.id=:server AND s.agent_id=:agent AND s.generation=:generation AND i.digest=:digest", nativeQuery = true)
    fun markSeen(server: String, agent: String, generation: Long, digest: String): Int
}

/** Repository of [InventorySnapshot] rows. */
@Repository
interface InventorySnapshotRepository : JpaRepository<InventorySnapshot, String> {
    /**
     * Row-locks a snapshot so batch uploads and commit cannot interleave; always lock the server first.
     *
     * @param id Snapshot UUID.
     * @return The locked snapshot, or null.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventorySnapshot i where i.id = :id")
    fun lockById(id: String): InventorySnapshot?
}

/** Repository of [InventoryEntry] rows. */
@Repository
interface InventoryEntryRepository : JpaRepository<InventoryEntry, InventoryEntryId> {
    /**
     * Finds one uploaded batch, used to make batch retries idempotent.
     *
     * @param snapshotId Snapshot UUID.
     * @param batchIndex Batch index.
     * @return The batch, or null.
     */
    fun findBySnapshotIdAndBatchIndex(snapshotId: String, batchIndex: Int): InventoryEntry?

    /**
     * Loads all batches of a snapshot in upload index order.
     *
     * @param snapshotId Snapshot UUID.
     * @return Batches ordered by index.
     */
    fun findBySnapshotIdOrderByBatchIndex(snapshotId: String): List<InventoryEntry>

    /**
     * Sums the stored payload size of a snapshot, used to enforce the snapshot size cap.
     *
     * @param snapshot Snapshot UUID.
     * @return Total payload bytes (0 when empty).
     */
    @Query(value = "SELECT COALESCE(SUM(OCTET_LENGTH(payload)),0) FROM inventory_entries WHERE snapshot_id=:snapshot", nativeQuery = true)
    fun payloadBytes(snapshot: String): Number
}

/** Repository of [ClassificationOverride] rows. */
@Repository interface ClassificationOverrideRepository : JpaRepository<ClassificationOverride, ClassificationOverrideId> {
    /**
     * Lists the administrator's classification overrides for a server.
     *
     * @param serverId Server UUID.
     * @return Overrides of the server.
     */
    fun findByServerId(serverId: String): List<ClassificationOverride>
}
