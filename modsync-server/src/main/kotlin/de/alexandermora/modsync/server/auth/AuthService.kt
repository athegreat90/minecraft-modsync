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

@Service
class AuthService(
    private val users: UserRepository,
    private val devices: ClientDeviceRepository,
    private val credentials: CredentialRepository,
    private val credentialService: CredentialService,
    private val passwords: Argon2PasswordEncoder,
    private val audit: AuditService,
) {
    private val dummyPassword by lazy { passwords.encode(UUID.randomUUID().toString()) }

    @Transactional fun login(request: Login): Credentials {
        require(request.protocol == PROTOCOL && request.username.length <= 100 && request.password.length <= 256)
        val user = users.findByUsername(request.username)
        // Always perform the expensive comparison to reduce account enumeration timing differences.
        val encoded = user?.passwordHash ?: dummyPassword
        if (!passwords.matches(request.password, encoded) || user == null) unauthorized()
        val deviceId = uuid(request.deviceId)
        val device = devices.lockById(deviceId)
        if (device == null) devices.saveAndFlush(ClientDevice(deviceId, user.id))
        else requireState(device.userId == user.id && !device.revoked, "Device belongs to another user or is revoked")
        audit.record(user.id, "login", deviceId)
        return credentialService.issue(user.id, user.role, deviceId)
    }

    @Transactional fun refresh(token: String): Credentials {
        require(token.length <= 128)
        val row = credentials.lockRefresh(sha512(token.toByteArray()), Instant.now()) ?: unauthorized()
        val device = row.deviceId.toString()
        found(devices.existsByIdAndRevokedFalse(device))
        credentials.deleteByDevice(device)
        audit.record(row.subjectId, "session-refreshed", device)
        return credentialService.issue(row.subjectId, row.role, device)
    }

    @Transactional fun createUser(actor: String, registration: UserRegistration): String {
        require(registration.username.matches(USERNAME_PATTERN) && registration.password.length in 16..256)
        val id = UUID.randomUUID().toString()
        users.saveAndFlush(UserAccount(id, registration.username, passwords.encode(registration.password)!!, CLIENT_ROLE))
        audit.record(actor, "user-created", id)
        return id
    }

    fun activeDevices(user: String): List<String> = devices.findByUserIdAndRevokedFalse(user).map { it.id }

    @Transactional fun revokeDevice(user: String, id: String) {
        devices.revoke(uuid(id), user)
        credentials.deleteByDeviceAndSubject(id, user)
        audit.record(user, "device-revoked", id)
    }
}
