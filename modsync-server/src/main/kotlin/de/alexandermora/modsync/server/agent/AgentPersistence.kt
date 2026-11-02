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

@Entity @Table(name = "minecraft_hosts")
class MinecraftHost(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) var id: String = "",
    @Column(nullable = false, length = 200) var name: String = "",
)

@Entity @Table(name = "scanner_agents")
class ScannerAgent(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) var id: String = "",
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "host_id", nullable = false, length = 36) var hostId: String = "",
    @Column(nullable = false) var revoked: Boolean = false,
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now(),
)

@Entity @Table(name = "agent_enrollments")
class AgentEnrollment(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "token_hash", length = 128) var tokenHash: String = "",
    @Column(name = "expires_at", nullable = false) var expiresAt: Instant = Instant.EPOCH,
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "used_by", length = 36) var usedBy: String? = null,
    @Column(nullable = false) var revoked: Boolean = false,
)

@Entity @Table(name = "agent_heartbeats")
class AgentHeartbeat(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "agent_id", length = 36) var agentId: String = "",
    @Column(name = "seen_at", nullable = false) var seenAt: Instant = Instant.EPOCH,
)

@Repository
interface MinecraftHostRepository : JpaRepository<MinecraftHost, String>

@Repository
interface ScannerAgentRepository : JpaRepository<ScannerAgent, String> {
    fun existsByIdAndRevokedFalse(id: String): Boolean
    fun findByRevokedFalse(): List<ScannerAgent>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ScannerAgent a set a.revoked = true where a.id = :id")
    fun revoke(id: String): Int
}

@Repository
interface AgentEnrollmentRepository : JpaRepository<AgentEnrollment, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from AgentEnrollment e where e.tokenHash = :hash and e.expiresAt > :now and e.revoked = false")
    fun lockUsable(hash: String, now: Instant): AgentEnrollment?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AgentEnrollment e set e.revoked = true where e.usedBy is null")
    fun revokePending(): Int
}

@Repository
interface AgentHeartbeatRepository : JpaRepository<AgentHeartbeat, String> {
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT INTO agent_heartbeats(agent_id,seen_at) VALUES (:agent,CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE seen_at=VALUES(seen_at)", nativeQuery = true)
    fun touch(agent: String): Int
}
