package de.alexandermora.modsync.server.agent

import kotlinx.serialization.Serializable
import de.alexandermora.modsync.protocol.Assignment
import de.alexandermora.modsync.protocol.Credentials
import de.alexandermora.modsync.protocol.Enrollment
import de.alexandermora.modsync.protocol.EnrollmentRequest
import de.alexandermora.modsync.protocol.EnrollmentToken
import de.alexandermora.modsync.protocol.Heartbeat
import de.alexandermora.modsync.protocol.Transfer
import de.alexandermora.modsync.server.common.acknowledged
import de.alexandermora.modsync.server.common.actor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Serializable
data class AgentView(val id: String, val heartbeatAt: String, val stale: String)

@RestController
@RequestMapping("/api/v1")
class AgentController(private val agents: AgentService) {
    @PostMapping("/enroll") fun enroll(@RequestBody request: Enrollment): Credentials = agents.enroll(request)

    @PostMapping("/admin/enrollments") fun createEnrollment(@RequestBody request: EnrollmentRequest): EnrollmentToken =
        agents.createEnrollment(actor(), request.ttlSeconds)

    @PostMapping("/admin/enrollments/revoke") fun revokeEnrollments(): Boolean =
        acknowledged { agents.revokePendingEnrollments(actor()) }

    @GetMapping("/admin/agents") fun activeAgents(): List<AgentView> = agents.activeAgents()

    @PostMapping("/admin/agents/{id}/revoke") fun revoke(@PathVariable id: String): Boolean =
        acknowledged { agents.revoke(actor(), id) }

    @PutMapping("/admin/servers/{id}/assignment") fun transfer(@PathVariable id: String, @RequestBody transfer: Transfer): Assignment =
        agents.transfer(actor(), id, transfer)

    @GetMapping("/agent/assignments") fun assignments(): List<Assignment> = agents.assignments(actor())

    @PostMapping("/agent/heartbeat") fun heartbeat(@RequestBody heartbeat: Heartbeat): Boolean =
        acknowledged { agents.heartbeat(actor(), heartbeat) }

    @PostMapping("/agent/credentials/rotate") fun rotate(): Credentials = agents.rotateCredentials(actor())
}
