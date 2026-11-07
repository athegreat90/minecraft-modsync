package de.alexandermora.modsync.server

import de.alexandermora.modsync.protocol.Login
import de.alexandermora.modsync.protocol.UserRegistration
import de.alexandermora.modsync.server.auth.AuthService
import de.alexandermora.modsync.server.auth.ClientDeviceRepository
import de.alexandermora.modsync.server.auth.CredentialRepository
import de.alexandermora.modsync.server.auth.CredentialService
import de.alexandermora.modsync.server.auth.UserAccount
import de.alexandermora.modsync.server.auth.UserRepository
import de.alexandermora.modsync.server.common.AuditService
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.web.server.ResponseStatusException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Verifies authentication rules that do not need a database. */
class AuthServiceTest {
    private val passwords = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
    private val users = Mockito.mock(UserRepository::class.java)
    private val service =
        AuthService(
            users,
            Mockito.mock(ClientDeviceRepository::class.java),
            Mockito.mock(CredentialRepository::class.java),
            Mockito.mock(CredentialService::class.java),
            passwords,
            Mockito.mock(AuditService::class.java)
        )
    private val device = "11111111-1111-1111-1111-111111111111"

    /** Wrong password for an existing user and an unknown user both end in 401. */
    @Test
    fun badCredentialsAreUnauthorized() {
        Mockito.`when`(users.findByUsername("alice")).thenReturn(UserAccount("u1", "alice", passwords.encode("correct-password-123")!!, "CLIENT"))
        val wrong = assertFailsWith<ResponseStatusException> { service.login(Login("alice", "wrong-password", device)) }
        val unknown = assertFailsWith<ResponseStatusException> { service.login(Login("nobody", "wrong-password", device)) }
        assertEquals(HttpStatus.UNAUTHORIZED, wrong.statusCode)
        assertEquals(HttpStatus.UNAUTHORIZED, unknown.statusCode)
    }

    /** Oversized login input is rejected before any password hashing. */
    @Test
    fun oversizedLoginIsRejected() {
        assertFailsWith<IllegalArgumentException> { service.login(Login("a".repeat(101), "x", device)) }
        assertFailsWith<IllegalArgumentException> { service.refresh("t".repeat(129)) }
    }

    /** New users need a safe username and a password of 16-256 characters. */
    @Test
    fun registrationValidatesInput() {
        assertFailsWith<IllegalArgumentException> { service.createUser("admin", UserRegistration("bad name", "long-enough-password")) }
        assertFailsWith<IllegalArgumentException> { service.createUser("admin", UserRegistration("bob", "short")) }
    }
}
