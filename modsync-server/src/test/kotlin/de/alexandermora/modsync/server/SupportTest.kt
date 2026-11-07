package de.alexandermora.modsync.server

import de.alexandermora.modsync.server.common.Errors
import de.alexandermora.modsync.server.common.acknowledged
import de.alexandermora.modsync.server.common.actor
import de.alexandermora.modsync.server.common.found
import de.alexandermora.modsync.server.common.notFound
import de.alexandermora.modsync.server.common.reject
import de.alexandermora.modsync.server.common.requireState
import de.alexandermora.modsync.server.common.unauthorized
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Verifies the HTTP error helpers, principal lookup and the validation error mapping. */
class SupportTest {
    @AfterTest
    fun clearContext() = SecurityContextHolder.clearContext()

    /** Runs [block] and returns the HTTP status of the resulting exception. */
    private fun statusOf(block: () -> Unit) = assertFailsWith<ResponseStatusException> { block() }.statusCode

    /** Each helper maps to its documented status code. */
    @Test
    fun helpersMapToStatuses() {
        assertEquals(HttpStatus.CONFLICT, statusOf { reject("x") })
        assertEquals(HttpStatus.CONFLICT, statusOf { requireState(false, "x") })
        assertEquals(HttpStatus.NOT_FOUND, statusOf { notFound() })
        assertEquals(HttpStatus.NOT_FOUND, statusOf { found(false) })
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf { unauthorized() })
    }

    /** Satisfied conditions pass through without throwing. */
    @Test
    fun satisfiedConditionsDoNotThrow() {
        requireState(true, "x")
        found(true)
        assertTrue(acknowledged { })
    }

    /** The actor is the authenticated name and anonymous callers get 401. */
    @Test
    fun actorRequiresAuthentication() {
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf { actor() })
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken("alice", "n/a")
        assertEquals("alice", actor())
    }

    /** Invalid payloads produce a generic failure that does not echo the exception message. */
    @Test
    fun invalidPayloadsDoNotLeakDetails() {
        val request = MockHttpServletRequest().apply { setAttribute("correlationId", "corr-1") }
        val failure = Errors().invalid(request)
        assertEquals("INVALID_REQUEST", failure.code)
        assertEquals("Invalid request payload", failure.message)
        assertEquals("corr-1", failure.correlationId)
    }
}
