package de.alexandermora.modsync.server.sso

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Duration
import java.time.Instant

/** Session attribute that carries a pending request to link a provider to the signed-in account. */
const val LINK_INTENT = "modsync.sso.link-intent"

/** Session attribute set once a link was created, so the success handler can send the user to the account page. */
const val LINKED_MARKER = "modsync.sso.linked"

/** Name of the claim or attribute that carries the local account id in the principal built after sign-in. */
const val LOCAL_USER = "modsync_user"

private val LINK_WINDOW: Duration = Duration.ofMinutes(5)

/**
 * A pending request to link a provider, created by an authenticated administrator and consumed by the callback.
 *
 * @property userId Local account that asked to link.
 * @property provider Registration id the link was requested for.
 * @property expiresAt Moment after which the request is ignored.
 */
data class LinkIntent(
    val userId: String,
    val provider: String,
    val expiresAt: Instant
) {
    /** Factory for [LinkIntent]. */
    companion object {
        /**
         * Creates a request that is valid for a few minutes.
         *
         * @param userId Local account that asked to link.
         * @param provider Registration id the link is requested for.
         * @return The request.
         */
        fun of(
            userId: String,
            provider: String
        ) = LinkIntent(userId, provider, Instant.now().plus(LINK_WINDOW))
    }
}

/**
 * Turns provider logins into local principals.
 *
 * After the provider proves who the person is, the account is decided here: either the identity is already linked, or the
 * session holds an unexpired [LinkIntent] created by the signed-in administrator for exactly this provider. The resulting
 * principal is named after the local account id and carries only the stored role, so session tracking, revocation and
 * authorization work the same for local and provider sign-ins.
 *
 * @property accounts Mapping between provider identities and accounts.
 * @property oauth2 Delegate that loads user attributes for plain OAuth2 providers (GitHub).
 * @property oidc Delegate that validates and loads OpenID Connect users (Google).
 */
class SsoUserServices(
    private val accounts: SsoAccounts,
    private val oauth2: OAuth2UserService<OAuth2UserRequest, OAuth2User> = DefaultOAuth2UserService(),
    private val oidc: OAuth2UserService<OidcUserRequest, OidcUser> = OidcUserService()
) {
    /**
     * Loads a GitHub (OAuth2) user and maps it to a local account.
     *
     * @param request Authorized request carrying the provider registration and access token.
     * @return Principal named after the local account.
     * @throws SsoDenied when the identity is not linked, the account is disabled or the link is refused.
     */
    fun loadOAuth2(request: OAuth2UserRequest): OAuth2User {
        val user = oauth2.loadUser(request) ?: throw SsoDenied(INVALID_USER_INFO)
        val provider = request.clientRegistration.registrationId
        val attribute = request.clientRegistration.providerDetails.userInfoEndpoint.userNameAttributeName ?: throw SsoDenied(INVALID_USER_INFO)
        val subject = user.getAttribute<Any>(attribute)?.toString() ?: throw SsoDenied(INVALID_USER_INFO)
        val account = resolve(provider, subject)
        return DefaultOAuth2User(authorities(account), mapOf(LOCAL_USER to account.id), LOCAL_USER)
    }

    /**
     * Loads a Google (OpenID Connect) user and maps it to a local account.
     *
     * The ID token has already been validated by Spring Security (issuer, audience, signature, expiry and nonce) when this runs.
     *
     * @param request Authorized request carrying the validated ID token.
     * @return Principal named after the local account.
     * @throws SsoDenied when the identity is not linked, the account is disabled or the link is refused.
     */
    fun loadOidc(request: OidcUserRequest): OidcUser {
        val user = oidc.loadUser(request) ?: throw SsoDenied(INVALID_USER_INFO)
        val account = resolve(request.clientRegistration.registrationId, user.subject ?: throw SsoDenied(INVALID_USER_INFO))
        val original = request.idToken
        val token = OidcIdToken(original.tokenValue, original.issuedAt, original.expiresAt, original.claims + (LOCAL_USER to account.id))
        return DefaultOidcUser(authorities(account), token, LOCAL_USER)
    }

    /**
     * Decides which account a verified provider identity acts for.
     *
     * @param provider Registration id of the provider.
     * @param subject Immutable provider identifier.
     * @return The account to sign in.
     */
    private fun resolve(
        provider: String,
        subject: String
    ): LocalAccount {
        val session = currentRequest()?.getSession(false)
        val intent = session?.getAttribute(LINK_INTENT) as? LinkIntent
        session?.removeAttribute(LINK_INTENT)
        if (intent == null) return accounts.signIn(provider, subject)
        val current = SecurityContextHolder.getContext().authentication?.name
        if (intent.provider != provider || intent.userId != current || Instant.now().isAfter(intent.expiresAt)) throw SsoDenied(UNLINKED_IDENTITY)
        val account = accounts.link(intent.userId, provider, subject)
        session.setAttribute(LINKED_MARKER, true)
        return account
    }

    private fun authorities(account: LocalAccount) = listOf(SimpleGrantedAuthority("ROLE_${account.role}"))

    private fun currentRequest(): HttpServletRequest? = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
}
