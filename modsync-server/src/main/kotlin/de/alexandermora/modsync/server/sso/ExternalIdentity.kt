package de.alexandermora.modsync.server.sso

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Link between a local account and one identity at an external provider.
 *
 * The immutable subject identifier is the only thing that identifies the person; e-mail addresses are never stored or
 * compared, because providers let their users change them.
 *
 * @property id Surrogate key.
 * @property userId Local account the identity belongs to.
 * @property provider Registration id of the provider, `google` or `github`.
 * @property subject Immutable identifier assigned by the provider (Google `sub`, GitHub numeric id).
 * @property linkedAt When the link was created.
 */
@Entity
@Table(name = "external_identities")
class ExternalIdentity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "user_id", nullable = false, length = 36) var userId: String = "",
    @Column(nullable = false, length = 20) var provider: String = "",
    @Column(nullable = false, length = 255) var subject: String = "",
    @Column(name = "linked_at", nullable = false) var linkedAt: Instant = Instant.EPOCH
)

/** Repository of [ExternalIdentity] rows. */
@Repository
interface ExternalIdentityRepository : JpaRepository<ExternalIdentity, Long> {
    /**
     * Finds the link for one provider identity.
     *
     * @param provider Registration id of the provider.
     * @param subject Immutable provider identifier.
     * @return The link, or null when the identity is not linked to any account.
     */
    fun findByProviderAndSubject(
        provider: String,
        subject: String
    ): ExternalIdentity?

    /**
     * Lists the identities linked to an account.
     *
     * @param userId Local account id.
     * @return The links, possibly empty.
     */
    fun findByUserId(userId: String): List<ExternalIdentity>

    /**
     * Finds the link of one provider for an account.
     *
     * @param userId Local account id.
     * @param provider Registration id of the provider.
     * @return The link, or null.
     */
    fun findByUserIdAndProvider(
        userId: String,
        provider: String
    ): ExternalIdentity?
}
