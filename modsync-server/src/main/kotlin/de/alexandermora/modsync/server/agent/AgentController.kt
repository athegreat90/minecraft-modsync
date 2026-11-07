package de.alexandermora.modsync.server.agent

import de.alexandermora.modsync.protocol.Assignment
import de.alexandermora.modsync.protocol.Credentials
import de.alexandermora.modsync.protocol.Enrollment
import de.alexandermora.modsync.protocol.EnrollmentRequest
import de.alexandermora.modsync.protocol.EnrollmentToken
import de.alexandermora.modsync.protocol.Heartbeat
import de.alexandermora.modsync.protocol.Transfer
import de.alexandermora.modsync.server.common.acknowledged
import de.alexandermora.modsync.server.common.actor
import kotlinx.serialization.Serializable
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Administrator-facing summary of one enrolled agent.
 *
 * @property id Agent UUID.
 * @property heartbeatAt ISO-8601 time of the last heartbeat, or `never`.
 * @property stale `true` when the agent was never seen or has been silent for 15 minutes.
 */
@Serializable
data class AgentView(val id: String, val heartbeatAt: String, val stale: String)

/**
 * REST endpoints for agent enrollment, credentials, heartbeats and server assignment.
 *
 * Administrator routes live under `/api/v1/admin`, agent routes under `/api/v1/agent`; the role
 * checks are enforced by the security filter chain.
 *
 * @property agents Service holding the enrollment and assignment logic.
 */
@RestController
@RequestMapping("/api/v1")
class AgentController(private val agents: AgentService) {
    /**
     * Redeems a single-use enrollment token for agent credentials.
     *
     * Public endpoint (no bearer token); rate limited per remote address and authorized by the token itself.
     *
     * @param request Enrollment with protocol version, agent UUID and clear token.
     * @return Credentials of the new agent; 401 for an unknown, expired or revoked token, 409 on conflicts.
     */
    @PostMapping("/enroll")
    fun enroll(
        @RequestBody request: Enrollment
    ): Credentials = agents.enroll(request)

    /**
     * Creates a single-use enrollment token for a new agent.
     *
     * Requires the ADMIN role.
     *
     * @param request Requested validity in seconds (60-3600).
     * @return The clear token and its expiry; the token cannot be recovered later.
     */
    @PostMapping("/admin/enrollments")
    fun createEnrollment(
        @RequestBody request: EnrollmentRequest
    ): EnrollmentToken =
        agents.createEnrollment(actor(), request.ttlSeconds)

    /**
     * Revokes all enrollment tokens that have not been used yet.
     *
     * Requires the ADMIN role.
     *
     * @return Always `true` once the tokens are revoked.
     */
    @PostMapping("/admin/enrollments/revoke")
    fun revokeEnrollments(): Boolean =
        acknowledged { agents.revokePendingEnrollments(actor()) }

    /**
     * Lists the agents currently enrolled and active.
     *
     * Requires the ADMIN role.
     *
     * @return Non-revoked agents with their heartbeat state.
     */
    @GetMapping("/admin/agents")
    fun activeAgents(): List<AgentView> = agents.activeAgents()

    /**
     * Revokes an agent and invalidates all of its credentials.
     *
     * Requires the ADMIN role.
     *
     * @param id Agent UUID (path).
     * @return Always `true` once the agent is revoked.
     */
    @PostMapping("/admin/agents/{id}/revoke")
    fun revoke(
        @PathVariable id: String
    ): Boolean =
        acknowledged { agents.revoke(actor(), id) }

    /**
     * Reassigns a server to another agent and mapping.
     *
     * Requires the ADMIN role. The server generation is incremented, which fences off uploads of the previous agent.
     *
     * @param id Server UUID (path).
     * @param transfer Target agent UUID and mapping name.
     * @return The new assignment; 404 for an unknown server or revoked agent.
     */
    @PutMapping("/admin/servers/{id}/assignment")
    fun transfer(
        @PathVariable id: String,
        @RequestBody transfer: Transfer
    ): Assignment =
        agents.transfer(actor(), id, transfer)

    /**
     * Lists the server assignments of the authenticated agent.
     *
     * Requires the AGENT role.
     *
     * @return Assignments with the generation that fences the agent's uploads.
     */
    @GetMapping("/agent/assignments")
    fun assignments(): List<Assignment> = agents.assignments(actor())

    /**
     * Records a heartbeat and the freshness of the scans the agent reports.
     *
     * Requires the AGENT role.
     *
     * @param heartbeat Heartbeat with at most 1000 scan digests.
     * @return Always `true` once recorded.
     */
    @PostMapping("/agent/heartbeat")
    fun heartbeat(
        @RequestBody heartbeat: Heartbeat
    ): Boolean =
        acknowledged { agents.heartbeat(actor(), heartbeat) }

    /**
     * Replaces the authenticated agent's credentials.
     *
     * Requires the AGENT role. The previous credentials stay valid for a short grace period.
     *
     * @return The new credentials.
     */
    @PostMapping("/agent/credentials/rotate")
    fun rotate(): Credentials = agents.rotateCredentials(actor())
}
