package de.alexandermora.modsync.server.agent

import de.alexandermora.modsync.domain.sha512
import de.alexandermora.modsync.domain.uuid
import de.alexandermora.modsync.domain.validHash
import de.alexandermora.modsync.protocol.Assignment
import de.alexandermora.modsync.protocol.Credentials
import de.alexandermora.modsync.protocol.Enrollment
import de.alexandermora.modsync.protocol.EnrollmentToken
import de.alexandermora.modsync.protocol.Heartbeat
import de.alexandermora.modsync.protocol.PROTOCOL
import de.alexandermora.modsync.protocol.Transfer
import de.alexandermora.modsync.server.auth.CredentialRepository
import de.alexandermora.modsync.server.auth.CredentialService
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.common.found
import de.alexandermora.modsync.server.common.notFound
import de.alexandermora.modsync.server.common.requireState
import de.alexandermora.modsync.server.common.unauthorized
import de.alexandermora.modsync.server.inventory.MinecraftServerRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

private val MAPPING_PATTERN = Regex("[A-Za-z0-9_-]{1,100}")
private const val AGENT_ROLE = "AGENT"
private const val MAX_TOKEN_LENGTH = 128
private const val MAX_HEARTBEAT_SCANS = 1000
private const val ROTATION_GRACE_SECONDS = 300L
private const val STALE_AFTER_SECONDS = 900L

/**
 * Manages agent enrollment, credentials, heartbeats and server assignments.
 *
 * Enrollment tokens are stored only as hashes and consumed under a row lock; every state change is audited.
 *
 * @property agents Agent repository.
 * @property hosts Host repository.
 * @property enrollments Enrollment token repository.
 * @property heartbeats Heartbeat repository.
 * @property servers Minecraft server repository, locked for transfers.
 * @property credentials Credential repository.
 * @property credentialService Issuer of new credentials.
 * @property audit Audit log writer.
 */
@Service
class AgentService(
    private val agents: ScannerAgentRepository,
    private val hosts: MinecraftHostRepository,
    private val enrollments: AgentEnrollmentRepository,
    private val heartbeats: AgentHeartbeatRepository,
    private val servers: MinecraftServerRepository,
    private val credentials: CredentialRepository,
    private val credentialService: CredentialService,
    private val audit: AuditService
) {
    /**
     * Creates a single-use enrollment token; only its hash is stored.
     *
     * @param actor Administrator recorded in the audit log.
     * @param seconds Validity, limited to 60-3600.
     * @return The clear token, which cannot be recovered later.
     */
    @Transactional fun createEnrollment(actor: String, seconds: Long): EnrollmentToken {
        require(seconds in 60..3600)
        val token = credentialService.newSecret()
        val expires = Instant.now().plusSeconds(seconds)
        enrollments.saveAndFlush(AgentEnrollment(sha512(token.toByteArray()), expires))
        audit.record(actor, "enrollment-created", "agent")
        return EnrollmentToken(token, expires.toString())
    }

    /**
     * Revokes all enrollment tokens that were not yet used.
     *
     * @param actor Administrator recorded in the audit log.
     */
    @Transactional fun revokePendingEnrollments(actor: String) {
        enrollments.revokePending()
        audit.record(actor, "enrollments-revoked", "pending")
    }

    /**
     * Redeems an enrollment token for an agent identity and its credentials.
     *
     * The token row is locked and consumed once. Retrying with the same agent id re-delivers fresh credentials
     * (the previous ones are deleted) so a lost response is recoverable; another agent id is rejected.
     *
     * @param request Enrollment with protocol version, agent id and clear token.
     * @return Credentials of the enrolled agent.
     */
    @Transactional fun enroll(request: Enrollment): Credentials {
        require(request.protocol == PROTOCOL && request.token.length <= MAX_TOKEN_LENGTH)
        val id = uuid(request.agentId)
        val enrollment = enrollments.lockUsable(sha512(request.token.toByteArray()), Instant.now()) ?: unauthorized()
        if (enrollment.usedBy != null) return retryDelivery(enrollment, id)
        requireState(!agents.existsById(id), "Agent already registered")
        val host = UUID.randomUUID().toString()
        hosts.saveAndFlush(MinecraftHost(host, "Host $host"))
        agents.saveAndFlush(ScannerAgent(id, host))
        enrollment.usedBy = id
        enrollments.saveAndFlush(enrollment)
        audit.record(id, "agent-enrolled", host)
        return credentialService.issue(id, AGENT_ROLE)
    }

    /**
     * Re-delivers fresh credentials for an already consumed enrollment token.
     *
     * Only the agent that consumed the token may retry; its previous credentials are deleted first.
     *
     * @param enrollment Locked, already consumed enrollment.
     * @param id Agent id presented by the retrying caller.
     * @return Fresh credentials.
     */
    private fun retryDelivery(enrollment: AgentEnrollment, id: String): Credentials {
        requireState(enrollment.usedBy == id, "Enrollment already consumed by another agent")
        found(agents.existsByIdAndRevokedFalse(id))
        credentials.deleteBySubject(id)
        audit.record(id, "enrollment-delivery-retried", id)
        return credentialService.issue(id, AGENT_ROLE)
    }

    /**
     * Lists non-revoked agents with their last heartbeat.
     *
     * Agents never seen or silent for 15 minutes are flagged stale.
     *
     * @return Agent views.
     */
    fun activeAgents(): List<AgentView> {
        val now = Instant.now()
        return agents.findByRevokedFalse().map { agent ->
            val seen = heartbeats.findById(agent.id).map { it.seenAt }.orElse(null)
            AgentView(agent.id, seen?.toString() ?: "never", (seen?.isBefore(now.minusSeconds(STALE_AFTER_SECONDS)) ?: true).toString())
        }
    }

    /**
     * Revokes an agent and deletes all its credentials, so its tokens stop working immediately.
     *
     * @param actor Administrator recorded in the audit log.
     * @param id Agent UUID.
     */
    @Transactional fun revoke(actor: String, id: String) {
        agents.revoke(uuid(id))
        credentials.deleteBySubject(id)
        audit.record(actor, "agent-revoked", id)
    }

    /**
     * Records an agent heartbeat and refreshes the freshness of the reported scans.
     *
     * A scan only counts as seen when it matches the server's current assignment (agent, generation) and accepted
     * snapshot digest; stale or foreign reports are silently ignored by [MinecraftServerRepository.markSeen].
     *
     * @param agent Authenticated agent UUID.
     * @param heartbeat Heartbeat with at most 1000 scan digests.
     */
    @Transactional fun heartbeat(agent: String, heartbeat: Heartbeat) {
        require(heartbeat.protocol == PROTOCOL && heartbeat.scans.size <= MAX_HEARTBEAT_SCANS)
        heartbeat.scans.forEach { scan ->
            require(validHash(scan.digest))
            servers.markSeen(uuid(scan.serverId), agent, scan.generation, scan.digest)
        }
        heartbeats.touch(agent)
    }

    /**
     * Replaces an agent's credentials.
     *
     * The old ones stay valid only for a 5-minute grace period so the agent can switch over.
     *
     * @param agent Authenticated agent UUID.
     * @return The new credentials.
     */
    @Transactional fun rotateCredentials(agent: String): Credentials {
        credentials.shorten(agent, Instant.now().plusSeconds(ROTATION_GRACE_SECONDS))
        audit.record(agent, "agent-credential-rotated", agent)
        return credentialService.issue(agent, AGENT_ROLE)
    }

    /**
     * Lists the servers currently assigned to the agent, with the generation that fences its uploads.
     *
     * @param agent Agent UUID.
     * @return The agent's assignments.
     */
    fun assignments(agent: String): List<Assignment> =
        servers.findByAgentId(agent).map { Assignment(it.id, agent, it.generation, it.mappingName.toString()) }

    /**
     * Reassigns a server to another agent and mapping.
     *
     * Locks the server and increments its generation and resets the sequence, which fences off in-flight uploads
     * of the previous agent (see InventoryService.ownership). Lock order: server first, matching snapshot begin.
     *
     * @param actor Administrator recorded in the audit log.
     * @param serverId Server UUID.
     * @param transfer Target agent UUID and mapping name.
     * @return The new assignment.
     */
    @Transactional fun transfer(actor: String, serverId: String, transfer: Transfer): Assignment {
        require(transfer.mapping.matches(MAPPING_PATTERN))
        val server = servers.lockById(uuid(serverId)) ?: notFound()
        found(agents.existsByIdAndRevokedFalse(uuid(transfer.agentId)))
        server.agentId = transfer.agentId
        server.mappingName = transfer.mapping
        server.generation += 1
        server.sequenceNo = 0
        servers.saveAndFlush(server)
        audit.record(actor, "assignment-transferred", serverId)
        return Assignment(serverId, transfer.agentId, server.generation, transfer.mapping)
    }
}
