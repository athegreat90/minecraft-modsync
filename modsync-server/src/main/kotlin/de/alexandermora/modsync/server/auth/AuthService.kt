package de.alexandermora.modsync.server.auth

import de.alexandermora.modsync.domain.sha512
import de.alexandermora.modsync.domain.uuid
import de.alexandermora.modsync.protocol.Credentials
import de.alexandermora.modsync.protocol.Login
import de.alexandermora.modsync.protocol.PROTOCOL
import de.alexandermora.modsync.protocol.UserRegistration
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.common.found
import de.alexandermora.modsync.server.common.requireState
import de.alexandermora.modsync.server.common.unauthorized
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

private val USERNAME_PATTERN = Regex("[A-Za-z0-9_.-]{1,100}")
private const val CLIENT_ROLE = "CLIENT"

/**
 * Authenticates users, rotates sessions and manages devices.
 *
 * @property users User repository.
 * @property devices Device repository, locked on login.
 * @property credentials Credential repository.
 * @property credentialService Issuer of new credentials.
 * @property passwords Argon2 password encoder.
 * @property audit Audit log writer.
 */
@Service
class AuthService(
    private val users: UserRepository,
    private val devices: ClientDeviceRepository,
    private val credentials: CredentialRepository,
    private val credentialService: CredentialService,
    private val passwords: Argon2PasswordEncoder,
    private val audit: AuditService
) {
    private val dummyPassword by lazy { passwords.encode(UUID.randomUUID().toString()) }

    /**
     * Authenticates a user and issues credentials bound to a device.
     *
     * A device id is claimed on first login (under a row lock) and can then only be used by the same, non-revoked user.
     * Unknown users still pay for a password comparison and fail with the same 401 as a wrong password. Disabled accounts
     * fail with that same 401, and only after the comparison, so the response does not reveal the account state.
     *
     * @param request Login with protocol version, username, password and device UUID.
     * @return Access and refresh credentials for the device.
     */
    @Transactional fun login(request: Login): Credentials {
        require(request.protocol == PROTOCOL && request.username.length <= 100 && request.password.length <= 256)
        val user = users.findByUsername(request.username)
        // Always perform the expensive comparison to reduce account enumeration timing differences.
        val encoded = user?.passwordHash ?: dummyPassword
        if (!passwords.matches(request.password, encoded) || user == null || !user.enabled) unauthorized()
        val deviceId = uuid(request.deviceId)
        val device = devices.lockById(deviceId)
        if (device == null) {
            devices.saveAndFlush(ClientDevice(deviceId, user.id))
        } else {
            requireState(device.userId == user.id && !device.revoked, "Device belongs to another user or is revoked")
        }
        audit.record(user.id, "login", deviceId)
        return credentialService.issue(user.id, user.role, deviceId)
    }

    /**
     * Exchanges a refresh token for a fresh credential pair (rotation).
     *
     * The refresh row is locked, so a token can be redeemed once; all of the device's old credentials are deleted
     * and a revoked device gets 404. A token whose owner no longer exists or has been disabled gets 401.
     *
     * @param token Clear refresh token.
     * @return A fresh credential pair for the same device.
     */
    @Transactional fun refresh(token: String): Credentials {
        require(token.length <= 128)
        val row = credentials.lockRefresh(sha512(token.toByteArray()), Instant.now()) ?: unauthorized()
        if (users.findById(row.subjectId).map { it.enabled }.orElse(false) != true) unauthorized()
        val device = row.deviceId.toString()
        found(devices.existsByIdAndRevokedFalse(device))
        credentials.deleteByDevice(device)
        audit.record(row.subjectId, "session-refreshed", device)
        return credentialService.issue(row.subjectId, row.role, device)
    }

    /**
     * Creates a CLIENT-role user on behalf of an administrator.
     *
     * @param actor Administrator recorded in the audit log.
     * @param registration Username (letters, digits, `_.-`) and password of 16-256 characters.
     * @return Id of the new user.
     */
    @Transactional fun createUser(actor: String, registration: UserRegistration): String {
        require(registration.username.matches(USERNAME_PATTERN) && registration.password.length in 16..256)
        val id = UUID.randomUUID().toString()
        users.saveAndFlush(UserAccount(id, registration.username, passwords.encode(registration.password)!!, CLIENT_ROLE))
        audit.record(actor, "user-created", id)
        return id
    }

    /**
     * Lists the user's devices that have not been revoked.
     *
     * @param user User UUID.
     * @return Active device ids.
     */
    fun activeDevices(user: String): List<String> = devices.findByUserIdAndRevokedFalse(user).map { it.id }

    /**
     * Revokes one of the user's devices and deletes its credentials.
     *
     * A device owned by someone else is left untouched.
     *
     * @param user Owner UUID taken from the authentication.
     * @param id Device UUID.
     */
    @Transactional fun revokeDevice(user: String, id: String) {
        devices.revoke(uuid(id), user)
        credentials.deleteByDeviceAndSubject(id, user)
        audit.record(user, "device-revoked", id)
    }
}
