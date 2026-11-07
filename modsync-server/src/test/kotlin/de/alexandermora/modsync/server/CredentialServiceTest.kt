package de.alexandermora.modsync.server

import de.alexandermora.modsync.domain.sha512
import de.alexandermora.modsync.server.auth.CredentialEntity
import de.alexandermora.modsync.server.auth.CredentialRepository
import de.alexandermora.modsync.server.auth.CredentialService
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Verifies credential issuing without a database. */
class CredentialServiceTest {
    private val repository = Mockito.mock(CredentialRepository::class.java) { invocation: InvocationOnMock -> invocation.arguments.firstOrNull() }
    private val service = CredentialService(repository)

    /** Returns the entities persisted so far. */
    private fun saved() =
        Mockito
            .mockingDetails(repository)
            .invocations
            .filter { it.method.name == "saveAndFlush" }
            .map { it.arguments[0] as CredentialEntity }

    /** Secrets are URL-safe, unpadded, 32 bytes and unique. */
    @Test
    fun secretsAreRandomAndUrlSafe() {
        val a = service.newSecret()
        assertEquals(43, a.length)
        assertNotEquals(a, service.newSecret())
        assertEquals(a, a.filter { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    /** Agents get one access token and no refresh token; only the hash is stored. */
    @Test
    fun agentsGetNoRefreshToken() {
        val credentials = service.issue("agent-1", "AGENT")
        assertNull(credentials.refreshToken)
        val stored = saved().single()
        assertEquals(sha512(credentials.token.toByteArray()), stored.tokenHash)
        assertEquals("access", stored.kind)
    }

    /** Clients get an access and a longer-lived refresh credential bound to the device. */
    @Test
    fun clientsGetRefreshToken() {
        val credentials = service.issue("user-1", "CLIENT", "device-1")
        val refresh = assertNotNull(credentials.refreshToken)
        val (access, refreshRow) = saved().let { it[0] to it[1] }
        assertEquals(sha512(refresh.toByteArray()), refreshRow.tokenHash)
        assertEquals("refresh", refreshRow.kind)
        assertEquals("device-1", access.deviceId)
        assertEquals(true, refreshRow.expiresAt.isAfter(access.expiresAt))
    }
}
