package de.alexandermora.modsync.server

import de.alexandermora.modsync.server.common.AuditEvent
import de.alexandermora.modsync.server.common.AuditEventRepository
import de.alexandermora.modsync.server.common.AuditService
import org.mockito.Mockito
import org.slf4j.MDC
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Verifies audit events carry the request correlation id. */
class AuditServiceTest {
    private val repository = Mockito.mock(AuditEventRepository::class.java)

    @AfterTest
    fun clearMdc() = MDC.clear()

    /** The recorded event copies actor, action, target and the MDC correlation id. */
    @Test
    fun recordsCorrelationId() {
        MDC.put("correlationId", "corr-7")
        AuditService(repository).record("alice", "login", "device-1")
        val event =
            Mockito
                .mockingDetails(repository)
                .invocations
                .single { it.method.name == "saveAndFlush" }
                .arguments[0] as AuditEvent
        assertEquals(Triple("alice", "login", "device-1"), Triple(event.actor, event.action, event.target))
        assertEquals("corr-7", event.correlationId)
    }
}
