package de.alexandermora.modsync.server.users

import de.alexandermora.modsync.protocol.UserRegistration
import de.alexandermora.modsync.server.auth.ADMIN_ROLE
import de.alexandermora.modsync.server.auth.AuthService
import de.alexandermora.modsync.server.auth.CLIENT_ROLE
import de.alexandermora.modsync.server.auth.ClientDeviceRepository
import de.alexandermora.modsync.server.auth.CredentialRepository
import de.alexandermora.modsync.server.auth.UserAccount
import de.alexandermora.modsync.server.auth.UserRepository
import de.alexandermora.modsync.server.auth.isValidPassword
import de.alexandermora.modsync.server.auth.isValidUsername
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.common.MaintenanceMode
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Business rules of the administrator user-management area.
 *
 * Only CLIENT accounts can be changed; administrator rows are listed but never modified, which also rules out
 * privilege escalation and removing the last administrator. Every change is written under a row lock together
 * with its audit event, ends the account's API credentials, and expires its dashboard sessions once the
 * transaction has committed. Mutations are rejected during maintenance. Audit events carry the actor, the
 * action and the target id only, never a password or credential.
 *
 * @property users Account repository.
 * @property devices Device repository.
 * @property credentials Credential repository, used to end API sessions.
 * @property auth Authentication service that creates accounts.
 * @property passwords Argon2 password encoder.
 * @property audit Audit log writer.
 * @property maintenance Maintenance switch that rejects state changes.
 * @property sessions Expires live dashboard sessions of an account.
 */
@Service
class UserManagementService(
    private val users: UserRepository,
    private val devices: ClientDeviceRepository,
    private val credentials: CredentialRepository,
    private val auth: AuthService,
    private val passwords: Argon2PasswordEncoder,
    private val audit: AuditService,
    private val maintenance: MaintenanceMode,
    private val sessions: UserSessionRevoker
) {
    /**
     * Lists accounts matching the filter, one page at a time.
     *
     * Unknown role or status values are ignored and the text filter is matched literally and case-insensitively.
     *
     * @param filter Search and paging criteria.
     * @return The requested page, ordered by username.
     */
    @Transactional(readOnly = true)
    fun list(filter: UserFilter): UserPage {
        val role = filter.role?.takeIf { it == CLIENT_ROLE || it == ADMIN_ROLE }
        val enabled =
            when (filter.status) {
                "enabled" -> true
                "disabled" -> false
                else -> null
            }
        val result =
            users.search(
                filter.query
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::escapeLike),
                role,
                enabled,
                PageRequest.of(filter.page.coerceAtLeast(0), USERS_PAGE_SIZE)
            )
        return UserPage(result.content.map(::summary), result.number, result.totalPages, result.totalElements)
    }

    /**
     * Loads the details of one account.
     *
     * @param id User UUID.
     * @return The details, or null when the account does not exist.
     */
    @Transactional(readOnly = true)
    fun detail(id: String): UserDetail? = users.findById(id).map { UserDetail(summary(it), devices.countByUserIdAndRevokedFalse(it.id)) }.orElse(null)

    /**
     * Creates a CLIENT account.
     *
     * Not transactional itself: [AuthService.createUser] commits in its own transaction, so a concurrent duplicate
     * that slips past the pre-check surfaces here as a clean [UserProblem.USERNAME_TAKEN] instead of poisoning a
     * surrounding transaction.
     *
     * @param actor Administrator recorded in the audit log.
     * @param username Requested login name.
     * @param password Initial clear password.
     * @param confirmation Password typed a second time.
     * @return [UserCommandResult.Done] with the new id, or the reason for the refusal.
     */
    fun create(
        actor: String,
        username: String,
        password: String,
        confirmation: String
    ): UserCommandResult {
        maintenance.requireWritable()
        val problem =
            when {
                !isValidUsername(username) -> UserProblem.INVALID_USERNAME
                !isValidPassword(password) -> UserProblem.INVALID_PASSWORD
                password != confirmation -> UserProblem.PASSWORD_MISMATCH
                users.existsByUsername(username) -> UserProblem.USERNAME_TAKEN
                else -> null
            }
        if (problem != null) return UserCommandResult.Failed(problem)
        return try {
            UserCommandResult.Done(auth.createUser(actor, UserRegistration(username, password)))
        } catch (_: DataIntegrityViolationException) {
            UserCommandResult.Failed(UserProblem.USERNAME_TAKEN)
        }
    }

    /**
     * Disables an account: it can no longer log in, refresh or use issued tokens, and its sessions end.
     *
     * Devices stay registered, so [enable] restores access without re-registering clients.
     *
     * @param actor Administrator recorded in the audit log.
     * @param id User UUID.
     * @return The outcome of the command.
     */
    @Transactional
    fun disable(actor: String, id: String): UserCommandResult =
        change(actor, id, "user-disabled") {
            it.enabled = false
            endSessions(it)
        }

    /**
     * Re-enables a disabled account; clients log in again.
     *
     * @param actor Administrator recorded in the audit log.
     * @param id User UUID.
     * @return The outcome of the command.
     */
    @Transactional
    fun enable(actor: String, id: String): UserCommandResult = change(actor, id, "user-enabled") { it.enabled = true }

    /**
     * Replaces an account's password and ends all of its API credentials and dashboard sessions.
     *
     * @param actor Administrator recorded in the audit log.
     * @param id User UUID.
     * @param password New clear password.
     * @param confirmation Password typed a second time.
     * @return The outcome of the command.
     */
    @Transactional
    fun resetPassword(
        actor: String,
        id: String,
        password: String,
        confirmation: String
    ): UserCommandResult =
        when {
            !isValidPassword(password) -> {
                UserCommandResult.Failed(UserProblem.INVALID_PASSWORD)
            }

            password != confirmation -> {
                UserCommandResult.Failed(UserProblem.PASSWORD_MISMATCH)
            }

            else -> {
                change(actor, id, "user-password-reset") {
                    it.passwordHash = requireNotNull(passwords.encode(password))
                    endSessions(it)
                }
            }
        }

    /**
     * Ends the account's API credentials and dashboard sessions; its devices stay valid and clients log in again.
     *
     * @param actor Administrator recorded in the audit log.
     * @param id User UUID.
     * @return The outcome of the command.
     */
    @Transactional
    fun revokeSessions(actor: String, id: String): UserCommandResult = change(actor, id, "user-sessions-revoked") { endSessions(it) }

    /**
     * Permanently revokes every device of the account and ends its sessions.
     *
     * A revoked device id can never log in again, so each client must register with a new device id.
     *
     * @param actor Administrator recorded in the audit log.
     * @param id User UUID.
     * @return The outcome of the command.
     */
    @Transactional
    fun revokeDevices(actor: String, id: String): UserCommandResult =
        change(actor, id, "user-devices-revoked") {
            devices.revokeAllForUser(it.id)
            endSessions(it)
        }

    /**
     * Applies a change to a CLIENT account under a row lock and audits it in the same transaction.
     *
     * @param actor Administrator recorded in the audit log.
     * @param id User UUID.
     * @param action Audit action name.
     * @param mutation Change applied to the locked account.
     * @return [UserCommandResult.Done], or the reason why the account cannot be changed.
     */
    private fun change(
        actor: String,
        id: String,
        action: String,
        mutation: (UserAccount) -> Unit
    ): UserCommandResult {
        maintenance.requireWritable()
        val user = users.lockById(id) ?: return UserCommandResult.Failed(UserProblem.NOT_FOUND)
        if (user.role != CLIENT_ROLE) return UserCommandResult.Failed(UserProblem.NOT_MANAGEABLE)
        mutation(user)
        audit.record(actor, action, user.id)
        return UserCommandResult.Done(user.id)
    }

    /**
     * Deletes the account's API credentials now and expires its dashboard sessions after the transaction commits.
     *
     * Expiring after the commit avoids ending sessions for a change that is rolled back.
     *
     * @param user Locked account.
     */
    private fun endSessions(user: UserAccount) {
        credentials.deleteBySubject(user.id)
        afterCommit { sessions.expireSessions(user.id) }
    }

    /**
     * Runs [action] once the current transaction commits, or immediately when none is active.
     *
     * @param action Work to run after the commit.
     */
    private fun afterCommit(action: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = action()
                }
            )
        } else {
            action()
        }
    }

    /**
     * Projects an account to its renderable form.
     *
     * @param user Persisted account.
     * @return Summary without the password hash.
     */
    private fun summary(user: UserAccount) = UserSummary(user.id, user.username, user.role, user.enabled, user.role == CLIENT_ROLE)

    /**
     * Escapes the characters that `like` treats specially so a search is a literal substring match.
     *
     * @param text Text typed by the administrator.
     * @return Text safe to embed in a `like` pattern that uses `\` as escape character.
     */
    private fun escapeLike(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
