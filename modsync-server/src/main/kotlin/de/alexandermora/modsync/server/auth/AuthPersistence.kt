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

@Entity @Table(name = "users")
class UserAccount(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) var id: String = "",
    @Column(nullable = false, length = 100, unique = true) var username: String = "",
    @Column(name = "password_hash", nullable = false) var passwordHash: String = "",
    @Column(nullable = false, length = 20) var role: String = "",
)

@Entity @Table(name = "credentials")
class CredentialEntity(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "token_hash", length = 128) var tokenHash: String = "",
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "subject_id", nullable = false, length = 36) var subjectId: String = "",
    @Column(nullable = false, length = 20) var role: String = "",
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "device_id", length = 36) var deviceId: String? = null,
    @Column(name = "expires_at", nullable = false) var expiresAt: Instant = Instant.EPOCH,
    @Column(nullable = false, length = 20) var kind: String = "access",
)

@Entity @Table(name = "client_devices")
class ClientDevice(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) var id: String = "",
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "user_id", nullable = false, length = 36) var userId: String = "",
    @Column(nullable = false) var revoked: Boolean = false,
)

@Repository
interface UserRepository : JpaRepository<UserAccount, String> {
    fun findByUsername(username: String): UserAccount?
}

@Repository
interface CredentialRepository : JpaRepository<CredentialEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CredentialEntity c where c.tokenHash = :hash and c.kind = 'refresh' and c.expiresAt > :now")
    fun lockRefresh(hash: String, now: Instant): CredentialEntity?

    @Modifying(flushAutomatically = true, clearAutomatically = true) @Query("update CredentialEntity c set c.expiresAt = least(c.expiresAt, :limit) where c.subjectId = :subject")
    fun shorten(subject: String, limit: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true) @Query("delete from CredentialEntity c where c.deviceId = :device")
    fun deleteByDevice(device: String): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true) @Query("delete from CredentialEntity c where c.deviceId = :device and c.subjectId = :subject")
    fun deleteByDeviceAndSubject(device: String, subject: String): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true) @Query("delete from CredentialEntity c where c.subjectId = :subject")
    fun deleteBySubject(subject: String): Int
}

@Repository
interface ClientDeviceRepository : JpaRepository<ClientDevice, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ClientDevice d where d.id = :id")
    fun lockById(id: String): ClientDevice?

    fun existsByIdAndRevokedFalse(id: String): Boolean
    fun findByUserIdAndRevokedFalse(userId: String): List<ClientDevice>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ClientDevice d set d.revoked = true where d.id = :id and d.userId = :user")
    fun revoke(id: String, user: String): Int
}
