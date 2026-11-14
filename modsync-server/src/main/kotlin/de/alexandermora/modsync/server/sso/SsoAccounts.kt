package de.alexandermora.modsync.server.sso

import de.alexandermora.modsync.server.auth.UserRepository
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Error code used when a provider identity is not linked to any account. */
const val UNLINKED_IDENTITY = "unlinked_identity"

/** Error code used when the linked account is disabled. */
const val DISABLED_ACCOUNT = "disabled_account"

/** Error code used when a provider identity is already linked to a different account. */
const val IDENTITY_IN_USE = "identity_in_use"

/** Error code used when the provider returned no usable identity. */
const val INVALID_USER_INFO = "invalid_user_info"

/**
 * A refused single sign-on attempt.
 *
 * @param code Stable code stored in the audit log; it never contains provider data.
 */
class SsoDenied(
    code: String
) : OAuth2AuthenticationException(OAuth2Error(code))

/**
 * The local account a provider identity resolved to.
 *
 * @property id Local account id, which becomes the authentication name.
 * @property role Stored role; single sign-on never grants or changes it.
 */
data class LocalAccount(
    val id: String,
    val role: String
)

/**
 * Maps provider identities to local accounts.
 *
 * Authorization is always the local account's: this service only answers which existing account a person is, and never
 * creates accounts, changes roles or matches by e-mail.
 *
 * @property identities Stored provider links.
 * @property users Local accounts.
 */
@Service
class SsoAccounts(
    private val identities: ExternalIdentityRepository,
    private val users: UserRepository
) {
    /**
     * Resolves an already linked identity for sign-in.
     *
     * @param provider Registration id of the provider.
     * @param subject Immutable provider identifier.
     * @return The linked, enabled account.
     * @throws SsoDenied when the identity is not linked or the account is disabled.
     */
    @Transactional(readOnly = true)
    fun signIn(
        provider: String,
        subject: String
    ): LocalAccount {
        val link = identities.findByProviderAndSubject(provider, subject) ?: throw SsoDenied(UNLINKED_IDENTITY)
        return enabled(link.userId)
    }

    /**
     * Links an identity to an account that proved its own identity with a local sign-in.
     *
     * Linking the same identity to the same account again is accepted; an identity already linked to another account is refused.
     *
     * @param userId Local account id taken from the authenticated session.
     * @param provider Registration id of the provider.
     * @param subject Immutable provider identifier.
     * @return The enabled account.
     * @throws SsoDenied when the account is disabled or the identity belongs to another account.
     */
    @Transactional
    fun link(
        userId: String,
        provider: String,
        subject: String
    ): LocalAccount {
        val account = enabled(userId)
        val existing = identities.findByProviderAndSubject(provider, subject)
        if (existing != null && existing.userId != userId) throw SsoDenied(IDENTITY_IN_USE)
        if (existing == null) {
            identities.findByUserIdAndProvider(userId, provider)?.let(identities::delete)
            identities.flush()
            identities.save(ExternalIdentity(userId = userId, provider = provider, subject = subject, linkedAt = Instant.now()))
        }
        return account
    }

    /**
     * Removes the link of one provider from an account.
     *
     * @param userId Local account id.
     * @param provider Registration id of the provider.
     * @return True when a link existed and was removed.
     */
    @Transactional
    fun unlink(
        userId: String,
        provider: String
    ): Boolean {
        val link = identities.findByUserIdAndProvider(userId, provider) ?: return false
        identities.delete(link)
        return true
    }

    /**
     * Lists the providers linked to an account.
     *
     * @param userId Local account id.
     * @return Provider registration ids, sorted.
     */
    @Transactional(readOnly = true)
    fun providersOf(userId: String): List<String> = identities.findByUserId(userId).map { it.provider }.sorted()

    /**
     * Loads an account and requires it to be enabled.
     *
     * @param userId Local account id.
     * @return The account.
     * @throws SsoDenied when the account does not exist or is disabled.
     */
    private fun enabled(userId: String): LocalAccount {
        val user = users.findById(userId).orElseThrow { SsoDenied(UNLINKED_IDENTITY) }
        if (!user.enabled) throw SsoDenied(DISABLED_ACCOUNT)
        return LocalAccount(user.id, user.role)
    }
}
