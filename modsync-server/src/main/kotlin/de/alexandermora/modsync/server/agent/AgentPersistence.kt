package de.alexandermora.modsync.server.agent

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Minecraft host that groups the servers scanned by one agent.
 *
 * @property id Host UUID.
 * @property name Display name.
 */
@Entity
@Table(name = "minecraft_hosts")
class MinecraftHost(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) var id: String = "",
    @Column(nullable = false, length = 200) var name: String = ""
)

/**
 * Enrolled scanner agent.
 *
 * @property id Agent UUID.
 * @property hostId UUID of the host created at enrollment.
 * @property revoked Whether the agent has been revoked and can no longer authenticate.
 * @property createdAt Enrollment time.
 */
@Entity
@Table(name = "scanner_agents")
class ScannerAgent(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) var id: String = "",
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "host_id", nullable = false, length = 36) var hostId: String = "",
    @Column(nullable = false) var revoked: Boolean = false,
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now()
)

/**
 * Single-use agent enrollment token; only the token hash is stored.
 *
 * @property tokenHash SHA-512 of the clear token.
 * @property expiresAt Time after which the token is unusable.
 * @property usedBy UUID of the agent that consumed the token, or null while unused.
 * @property revoked Whether an administrator revoked the token.
 */
@Entity
@Table(name = "agent_enrollments")
class AgentEnrollment(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "token_hash", length = 128) var tokenHash: String = "",
    @Column(name = "expires_at", nullable = false) var expiresAt: Instant = Instant.EPOCH,
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "used_by", length = 36) var usedBy: String? = null,
    @Column(nullable = false) var revoked: Boolean = false
)

/**
 * Last time an agent was seen.
 *
 * @property agentId Agent UUID.
 * @property seenAt Time of the latest heartbeat.
 */
@Entity
@Table(name = "agent_heartbeats")
class AgentHeartbeat(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "agent_id", length = 36) var agentId: String = "",
    @Column(name = "seen_at", nullable = false) var seenAt: Instant = Instant.EPOCH
)

/** Repository of [MinecraftHost] rows. */
@Repository
interface MinecraftHostRepository : JpaRepository<MinecraftHost, String>

/** Repository of [ScannerAgent] rows. */
@Repository
interface ScannerAgentRepository : JpaRepository<ScannerAgent, String> {
    /**
     * Checks that an agent exists and may still authenticate.
     *
     * @param id Agent UUID.
     * @return True when the agent exists and has not been revoked.
     */
    fun existsByIdAndRevokedFalse(id: String): Boolean

    /**
     * Lists agents that have not been revoked.
     *
     * @return Active agents.
     */
    fun findByRevokedFalse(): List<ScannerAgent>

    /**
     * Marks an agent revoked.
     *
     * @param id Agent UUID.
     * @return Number of rows changed.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ScannerAgent a set a.revoked = true where a.id = :id")
    fun revoke(id: String): Int
}

/** Repository of [AgentEnrollment] rows. */
@Repository
interface AgentEnrollmentRepository : JpaRepository<AgentEnrollment, String> {
    /**
     * Locks a usable (unexpired, unrevoked) enrollment token hash so it can be consumed by exactly one enrollment at a time.
     *
     * @param hash SHA-512 of the presented token.
     * @param now Reference time for the expiry check.
     * @return The locked enrollment, or null when unknown, expired or revoked.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from AgentEnrollment e where e.tokenHash = :hash and e.expiresAt > :now and e.revoked = false")
    fun lockUsable(hash: String, now: Instant): AgentEnrollment?

    /**
     * Revokes all enrollment tokens that have not been used yet; consumed ones are kept for retry delivery.
     *
     * @return Number of rows changed.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AgentEnrollment e set e.revoked = true where e.usedBy is null")
    fun revokePending(): Int
}

/** Repository of [AgentHeartbeat] rows. */
@Repository
interface AgentHeartbeatRepository : JpaRepository<AgentHeartbeat, String> {
    /**
     * Upserts the agent's last-seen time using the database clock (atomic, no read-modify-write).
     *
     * @param agent Agent UUID.
     * @return Number of rows affected.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT INTO agent_heartbeats(agent_id,seen_at) VALUES (:agent,CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE seen_at=VALUES(seen_at)", nativeQuery = true)
    fun touch(agent: String): Int
}
