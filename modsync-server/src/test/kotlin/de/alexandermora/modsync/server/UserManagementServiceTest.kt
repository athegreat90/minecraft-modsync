package de.alexandermora.modsync.server

import de.alexandermora.modsync.protocol.UserRegistration
import de.alexandermora.modsync.server.auth.AuthService
import de.alexandermora.modsync.server.auth.ClientDeviceRepository
import de.alexandermora.modsync.server.auth.CredentialRepository
import de.alexandermora.modsync.server.auth.UserAccount
import de.alexandermora.modsync.server.auth.UserRepository
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.common.MaintenanceMode
import de.alexandermora.modsync.server.users.UserCommandResult
import de.alexandermora.modsync.server.users.UserFilter
import de.alexandermora.modsync.server.users.UserManagementService
import de.alexandermora.modsync.server.users.UserProblem
import de.alexandermora.modsync.server.users.UserSessionRevoker
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.web.server.ResponseStatusException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Verifies the user-management rules without a database: who may be changed, what each change does and what it leaves untouched. */
class UserManagementServiceTest {
    private val passwords = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
    private val users = Mockito.mock(UserRepository::class.java)
    private val devices = Mockito.mock(ClientDeviceRepository::class.java)
    private val credentials = Mockito.mock(CredentialRepository::class.java)
    private val auth = Mockito.mock(AuthService::class.java)
    private val audit = Mockito.mock(AuditService::class.java)
    private val sessions = Mockito.mock(UserSessionRevoker::class.java)
    private val strongPassword = "correct-horse-battery"
    private val admin = "00000000-0000-0000-0000-000000000001"
    private val clientId = "00000000-0000-0000-0000-000000000002"

    /** Builds the service under test with the given maintenance state. */
    private fun service(maintenance: Boolean = false) = UserManagementService(users, devices, credentials, auth, passwords, audit, MaintenanceMode(maintenance), sessions)

    /** Makes [id] resolve to a locked account with [role]. */
    private fun account(id: String = clientId, role: String = "CLIENT"): UserAccount =
        UserAccount(id, "player", passwords.encode(strongPassword)!!, role).also { Mockito.`when`(users.lockById(id)).thenReturn(it) }

    /** Invalid or duplicate input is refused with a specific reason and nothing is created. */
    @Test
    fun createRefusesBadInput() {
        Mockito.`when`(users.existsByUsername("taken")).thenReturn(true)
        val cases =
            listOf(
                Triple("bad name", strongPassword, strongPassword) to UserProblem.INVALID_USERNAME,
                Triple("alice", "short", "short") to UserProblem.INVALID_PASSWORD,
                Triple("alice", strongPassword, strongPassword + "x") to UserProblem.PASSWORD_MISMATCH,
                Triple("taken", strongPassword, strongPassword) to UserProblem.USERNAME_TAKEN
            )
        cases.forEach { (input, expected) ->
            assertEquals(UserCommandResult.Failed(expected), service().create(admin, input.first, input.second, input.third))
        }
        Mockito.verifyNoInteractions(auth)
    }

    /** A valid request is delegated to the existing creation flow and a concurrent duplicate becomes a clean refusal. */
    @Test
    fun createDelegatesAndHandlesRaces() {
        val registration = UserRegistration("alice", strongPassword)
        Mockito.`when`(auth.createUser(admin, registration)).thenReturn(clientId)
        assertEquals(UserCommandResult.Done(clientId), service().create(admin, "alice", strongPassword, strongPassword))

        Mockito.`when`(auth.createUser(admin, UserRegistration("racer", strongPassword))).thenThrow(DataIntegrityViolationException("duplicate"))
        assertEquals(UserCommandResult.Failed(UserProblem.USERNAME_TAKEN), service().create(admin, "racer", strongPassword, strongPassword))
    }

    /** Disabling flags the account, ends its credentials and sessions, and is audited with the target only. */
    @Test
    fun disableEndsAccess() {
        val user = account()
        assertEquals(UserCommandResult.Done(clientId), service().disable(admin, clientId))
        assertFalse(user.enabled)
        verify(credentials).deleteBySubject(clientId)
        verify(sessions).expireSessions(clientId)
        verify(audit).record(admin, "user-disabled", clientId)
        verify(devices, never()).revokeAllForUser(Mockito.anyString())
    }

    /** Enabling restores the flag without touching credentials or devices. */
    @Test
    fun enableRestoresAccount() {
        val user = account().also { it.enabled = false }
        assertEquals(UserCommandResult.Done(clientId), service().enable(admin, clientId))
        assertTrue(user.enabled)
        verify(audit).record(admin, "user-enabled", clientId)
        verify(credentials, never()).deleteBySubject(Mockito.anyString())
    }

    /** A reset stores a new hash, ends credentials and sessions, and never records the password. */
    @Test
    fun resetReplacesPassword() {
        val user = account()
        val old = user.passwordHash
        val newPassword = "an-entirely-new-password"
        assertEquals(UserCommandResult.Done(clientId), service().resetPassword(admin, clientId, newPassword, newPassword))
        assertNotEquals(old, user.passwordHash)
        assertTrue(passwords.matches(newPassword, user.passwordHash))
        verify(credentials).deleteBySubject(clientId)
        verify(sessions).expireSessions(clientId)
        verify(audit).record(admin, "user-password-reset", clientId)
    }

    /** A weak or mismatched reset is refused before the account is even locked. */
    @Test
    fun resetValidatesPassword() {
        assertEquals(UserCommandResult.Failed(UserProblem.INVALID_PASSWORD), service().resetPassword(admin, clientId, "short", "short"))
        assertEquals(UserCommandResult.Failed(UserProblem.PASSWORD_MISMATCH), service().resetPassword(admin, clientId, strongPassword, "$strongPassword!"))
        Mockito.verifyNoInteractions(users)
    }

    /** Revoking sessions keeps devices valid, while revoking devices revokes them all. */
    @Test
    fun revokeSessionsAndDevices() {
        account()
        service().revokeSessions(admin, clientId)
        verify(devices, never()).revokeAllForUser(Mockito.anyString())
        verify(audit).record(admin, "user-sessions-revoked", clientId)

        service().revokeDevices(admin, clientId)
        verify(devices).revokeAllForUser(clientId)
        verify(audit).record(admin, "user-devices-revoked", clientId)
    }

    /** Administrator rows and unknown ids cannot be changed by any command and leave no trace. */
    @Test
    fun onlyClientAccountsAreManageable() {
        account(role = "ADMIN")
        val commands: List<() -> UserCommandResult> =
            listOf(
                { service().disable(admin, clientId) },
                { service().enable(admin, clientId) },
                { service().resetPassword(admin, clientId, strongPassword, strongPassword) },
                { service().revokeSessions(admin, clientId) },
                { service().revokeDevices(admin, clientId) }
            )
        commands.forEach { assertEquals(UserCommandResult.Failed(UserProblem.NOT_MANAGEABLE), it()) }
        assertEquals(UserCommandResult.Failed(UserProblem.NOT_FOUND), service().disable(admin, "00000000-0000-0000-0000-0000000000ff"))
        Mockito.verifyNoInteractions(audit, credentials, sessions)
    }

    /** Every mutation is rejected with 409 during maintenance, before anything is read or written. */
    @Test
    fun maintenanceRejectsMutations() {
        val maintenance = service(maintenance = true)
        val commands: List<() -> Unit> =
            listOf(
                { maintenance.create(admin, "alice", strongPassword, strongPassword) },
                { maintenance.disable(admin, clientId) },
                { maintenance.enable(admin, clientId) },
                { maintenance.resetPassword(admin, clientId, strongPassword, strongPassword) },
                { maintenance.revokeSessions(admin, clientId) },
                { maintenance.revokeDevices(admin, clientId) }
            )
        commands.forEach { assertEquals(HttpStatus.CONFLICT, assertFailsWith<ResponseStatusException> { it() }.statusCode) }
        Mockito.verifyNoInteractions(users, audit, credentials, sessions)
    }

    /** Search text is matched literally, unknown filter values are ignored and the page index is never negative. */
    @Test
    fun listNormalizesTheFilter() {
        Mockito
            .doReturn(PageImpl(listOf(UserAccount(clientId, "player", "hash", "CLIENT"))))
            .`when`(users)
            .search(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(Pageable::class.java) ?: Pageable.unpaged())
        val page = service().list(UserFilter(query = "  50%_off\\ ", role = "ROOT", status = "bogus", page = -3))
        assertEquals(listOf("player"), page.users.map { it.username })
        assertTrue(page.users.single().manageable)
        val pageable = org.mockito.ArgumentCaptor.forClass(Pageable::class.java)
        verify(users).search(Mockito.eq("50\\%\\_off\\\\"), Mockito.isNull(), Mockito.isNull(), pageable.capture() ?: Pageable.unpaged())
        assertEquals(0, pageable.value.pageNumber)
    }
}
