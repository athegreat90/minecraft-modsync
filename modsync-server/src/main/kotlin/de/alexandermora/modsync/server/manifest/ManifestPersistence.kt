package de.alexandermora.modsync.server.manifest

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.Length
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.time.Instant

/**
 * Head row of a server's manifest history.
 *
 * @property serverId Server UUID.
 * @property latestRevision Latest published revision number.
 */
@Entity
@Table(name = "server_manifests")
class ServerManifest(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "server_id", length = 36) var serverId: String = "",
    @Column(name = "latest_revision", nullable = false) var latestRevision: Long = 0
)

/**
 * Composite key of [ManifestRevision].
 *
 * @property serverId Server UUID.
 * @property revision Revision number.
 */
class ManifestRevisionId(var serverId: String = "", var revision: Long = 0) : Serializable {
    override fun equals(other: Any?) = other is ManifestRevisionId && other.serverId == serverId && other.revision == revision

    override fun hashCode() = 31 * serverId.hashCode() + revision.hashCode()
}

/**
 * Immutable published manifest revision.
 *
 * @property serverId Server UUID.
 * @property revision Revision number.
 * @property payload Serialized manifest.
 * @property createdAt Publication time.
 */
@Entity
@Table(name = "manifest_revisions")
@IdClass(ManifestRevisionId::class)
class ManifestRevision(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "server_id", length = 36) var serverId: String = "",
    @Id @Column(nullable = false) var revision: Long = 0,
    @Column(nullable = false, length = Length.LONG32) var payload: String = "",
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
)

/**
 * Outcome a client device reported for a manifest revision.
 *
 * @property id Report UUID.
 * @property deviceId Reporting device UUID.
 * @property serverId Server UUID.
 * @property revision Manifest revision.
 * @property status `SUCCESS`, `FAILED` or `ROLLED_BACK`.
 * @property createdAt Report time.
 */
@Entity
@Table(name = "synchronization_history")
class SynchronizationHistory(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) var id: String = "",
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "device_id", nullable = false, length = 36) var deviceId: String = "",
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "server_id", nullable = false, length = 36) var serverId: String = "",
    @Column(nullable = false) var revision: Long = 0,
    @Column(nullable = false, length = 30) var status: String = "",
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now()
)

/** Repository of [ServerManifest] rows. */
@Repository interface ServerManifestRepository : JpaRepository<ServerManifest, String>

/** Repository of [ManifestRevision] rows. */
@Repository interface ManifestRevisionRepository : JpaRepository<ManifestRevision, ManifestRevisionId>

/** Repository of [SynchronizationHistory] rows. */
@Repository interface SynchronizationHistoryRepository : JpaRepository<SynchronizationHistory, String>
