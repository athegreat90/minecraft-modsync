package de.alexandermora.modsync.server.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Dashboard and API user account.
 *
 * @property id User UUID.
 * @property username Unique login name.
 * @property passwordHash Argon2 hash of the password.
 * @property role Role name (`ADMIN` or `CLIENT`).
 */
@Entity
@Table(name = "users")
class UserAccount(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) var id: String = "",
    @Column(nullable = false, length = 100, unique = true) var username: String = "",
    @Column(name = "password_hash", nullable = false) var passwordHash: String = "",
    @Column(nullable = false, length = 20) var role: String = ""
)

/**
 * Hashed access or refresh credential.
 *
 * @property tokenHash SHA-512 of the clear token; clear tokens are never stored.
 * @property subjectId UUID of the user or agent.
 * @property role Role granted to the token.
 * @property deviceId Device UUID for client credentials, null for agents.
 * @property expiresAt Expiry time.
 * @property kind `access` or `refresh`.
 */
@Entity
@Table(name = "credentials")
class CredentialEntity(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "token_hash", length = 128) var tokenHash: String = "",
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "subject_id", nullable = false, length = 36) var subjectId: String = "",
    @Column(nullable = false, length = 20) var role: String = "",
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "device_id", length = 36) var deviceId: String? = null,
    @Column(name = "expires_at", nullable = false) var expiresAt: Instant = Instant.EPOCH,
    @Column(nullable = false, length = 20) var kind: String = "access"
)

/**
 * Client device registered by its owner's first login.
 *
 * @property id Device UUID.
 * @property userId Owning user UUID.
 * @property revoked Whether the device was revoked.
 */
@Entity
@Table(name = "client_devices")
class ClientDevice(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) var id: String = "",
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "user_id", nullable = false, length = 36) var userId: String = "",
    @Column(nullable = false) var revoked: Boolean = false
)

/** Repository of [UserAccount] rows. */
@Repository
interface UserRepository : JpaRepository<UserAccount, String> {
    /**
     * Finds the account with this exact username.
     *
     * @param username Login name.
     * @return The account, or null.
     */
    fun findByUsername(username: String): UserAccount?
}

/** Repository of [CredentialEntity] rows. */
@Repository
interface CredentialRepository : JpaRepository<CredentialEntity, String> {
    /**
     * Row-locks an unexpired refresh credential so that concurrent refreshes cannot both redeem the same token.
     *
     * @param hash SHA-512 of the presented token (tokens are never stored in clear).
     * @param now Reference time for the expiry check.
     * @return The locked credential, or null when unknown, expired or not a refresh token.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CredentialEntity c where c.tokenHash = :hash and c.kind = 'refresh' and c.expiresAt > :now")
    fun lockRefresh(hash: String, now: Instant): CredentialEntity?

    /**
     * Caps the expiry of all of a subject's credentials at [limit] (never extends them), used for rotation grace periods.
     *
     * @param subject User or agent UUID.
     * @param limit Latest allowed expiry.
     * @return Number of rows updated.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CredentialEntity c set c.expiresAt = least(c.expiresAt, :limit) where c.subjectId = :subject")
    fun shorten(subject: String, limit: Instant): Int

    /**
     * Deletes every credential issued to a device, regardless of owner.
     *
     * @param device Device UUID.
     * @return Number of rows removed.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CredentialEntity c where c.deviceId = :device")
    fun deleteByDevice(device: String): Int

    /**
     * Deletes a device's credentials only when they belong to [subject], so users cannot revoke each other's sessions.
     *
     * @param device Device UUID.
     * @param subject Owner UUID.
     * @return Number of rows removed.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CredentialEntity c where c.deviceId = :device and c.subjectId = :subject")
    fun deleteByDeviceAndSubject(device: String, subject: String): Int

    /**
     * Deletes all credentials (access and refresh) of a subject, e.g. when an agent is revoked or re-enrolled.
     *
     * @param subject User or agent UUID.
     * @return Number of rows removed.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CredentialEntity c where c.subjectId = :subject")
    fun deleteBySubject(subject: String): Int
}

/** Repository of [ClientDevice] rows. */
@Repository
interface ClientDeviceRepository : JpaRepository<ClientDevice, String> {
    /**
     * Row-locks a device so concurrent logins cannot register or claim the same device id twice.
     *
     * @param id Device UUID.
     * @return The locked device, or null when unknown.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ClientDevice d where d.id = :id")
    fun lockById(id: String): ClientDevice?

    /**
     * Checks that a device exists and may still authenticate.
     *
     * @param id Device UUID.
     * @return True when the device exists and has not been revoked.
     */
    fun existsByIdAndRevokedFalse(id: String): Boolean

    /**
     * Lists a user's devices that have not been revoked.
     *
     * @param userId Owner UUID.
     * @return Active devices.
     */
    fun findByUserIdAndRevokedFalse(userId: String): List<ClientDevice>

    /**
     * Marks a device revoked only when it belongs to [user].
     *
     * @param id Device UUID.
     * @param user Owner UUID.
     * @return Number of rows changed (0 if not owned).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ClientDevice d set d.revoked = true where d.id = :id and d.userId = :user")
    fun revoke(id: String, user: String): Int
}
