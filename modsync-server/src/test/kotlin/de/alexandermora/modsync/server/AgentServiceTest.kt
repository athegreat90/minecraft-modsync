package de.alexandermora.modsync.server

import de.alexandermora.modsync.protocol.Enrollment
import de.alexandermora.modsync.protocol.Heartbeat
import de.alexandermora.modsync.protocol.ScanConfirmation
import de.alexandermora.modsync.protocol.Transfer
import de.alexandermora.modsync.server.agent.AgentEnrollmentRepository
import de.alexandermora.modsync.server.agent.AgentHeartbeatRepository
import de.alexandermora.modsync.server.agent.AgentService
import de.alexandermora.modsync.server.agent.MinecraftHostRepository
import de.alexandermora.modsync.server.agent.ScannerAgentRepository
import de.alexandermora.modsync.server.auth.CredentialRepository
import de.alexandermora.modsync.server.auth.CredentialService
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.inventory.MinecraftServerRepository
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Verifies agent input validation, which happens before any persistence. */
class AgentServiceTest {
    private val service =
        AgentService(
            Mockito.mock(ScannerAgentRepository::class.java),
            Mockito.mock(MinecraftHostRepository::class.java),
            Mockito.mock(AgentEnrollmentRepository::class.java),
            Mockito.mock(AgentHeartbeatRepository::class.java),
            Mockito.mock(MinecraftServerRepository::class.java),
            Mockito.mock(CredentialRepository::class.java),
            Mockito.mock(CredentialService::class.java),
            Mockito.mock(AuditService::class.java)
        )

    /** Enrollment tokens live between one minute and one hour. */
    @Test
    fun enrollmentLifetimeIsBounded() {
        assertFailsWith<IllegalArgumentException> { service.createEnrollment("admin", 59) }
        assertFailsWith<IllegalArgumentException> { service.createEnrollment("admin", 3601) }
    }

    /** Oversized tokens and wrong protocol versions never reach the token lookup. */
    @Test
    fun enrollmentRejectsMalformedRequests() {
        assertFailsWith<IllegalArgumentException> { service.enroll(Enrollment("t".repeat(129), "11111111-1111-1111-1111-111111111111")) }
        assertFailsWith<IllegalArgumentException> { service.enroll(Enrollment("t", "11111111-1111-1111-1111-111111111111", protocol = 99)) }
    }

    /** Heartbeats must use the current protocol, stay under 1000 scans and carry valid digests. */
    @Test
    fun heartbeatValidatesScans() {
        val server = "11111111-1111-1111-1111-111111111111"
        assertFailsWith<IllegalArgumentException> { service.heartbeat("agent", Heartbeat(protocol = 99)) }
        assertFailsWith<IllegalArgumentException> { service.heartbeat("agent", Heartbeat(scans = List(1001) { ScanConfirmation(server, 1, "a".repeat(128)) })) }
        assertFailsWith<IllegalArgumentException> { service.heartbeat("agent", Heartbeat(scans = listOf(ScanConfirmation(server, 1, "not-a-hash")))) }
    }

    /** Mapping names are restricted to a safe character set. */
    @Test
    fun transferValidatesMapping() {
        assertFailsWith<IllegalArgumentException> { service.transfer("admin", "11111111-1111-1111-1111-111111111111", Transfer("agent", "../escape")) }
    }
}
