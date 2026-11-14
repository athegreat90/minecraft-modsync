package de.alexandermora.modsync.server

import de.alexandermora.modsync.server.auth.UserAccount
import de.alexandermora.modsync.server.auth.UserRepository
import de.alexandermora.modsync.server.sso.DISABLED_ACCOUNT
import de.alexandermora.modsync.server.sso.ExternalIdentity
import de.alexandermora.modsync.server.sso.ExternalIdentityRepository
import de.alexandermora.modsync.server.sso.IDENTITY_IN_USE
import de.alexandermora.modsync.server.sso.LINKED_MARKER
import de.alexandermora.modsync.server.sso.LINK_INTENT
import de.alexandermora.modsync.server.sso.LinkIntent
import de.alexandermora.modsync.server.sso.SsoAccounts
import de.alexandermora.modsync.server.sso.SsoDenied
import de.alexandermora.modsync.server.sso.SsoUserServices
import de.alexandermora.modsync.server.sso.UNLINKED_IDENTITY
import org.junit.jupiter.api.AfterEach
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Instant
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Verifies who a provider identity may sign in as, without a database or a real provider. */
class SsoServicesTest {
    private val identities = Mockito.mock(ExternalIdentityRepository::class.java)
    private val users = Mockito.mock(UserRepository::class.java)
    private val accounts = SsoAccounts(identities, users)
    private val oauth2 = Mockito.mock(OAuth2UserService::class.java)

    @AfterEach
    fun cleanUp() {
        RequestContextHolder.resetRequestAttributes()
        SecurityContextHolder.clearContext()
    }

    private fun user(
        id: String,
        role: String = "ADMIN",
        enabled: Boolean = true
    ) = UserAccount(id = id, username = "u-$id", passwordHash = "x", role = role, enabled = enabled)

    private fun githubRequest(): OAuth2UserRequest {
        val registration =
            ClientRegistration
                .withRegistrationId("github")
                .clientId("client")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/github")
                .authorizationUri("https://github.example/authorize")
                .tokenUri("https://github.example/token")
                .userInfoUri("https://github.example/user")
                .userNameAttributeName("id")
                .build()
        val token = OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "token", Instant.now(), Instant.now().plusSeconds(60))
        return OAuth2UserRequest(registration, token)
    }

    @Suppress("UNCHECKED_CAST")
    private fun services() = SsoUserServices(accounts, oauth2 as OAuth2UserService<OAuth2UserRequest, OAuth2User>)

    private fun providerUser(
        id: Int,
        email: String
    ): OAuth2User = DefaultOAuth2User(emptyList(), mapOf("id" to id, "email" to email), "id")

    private fun session(): MockHttpSession {
        val request = MockHttpRequestWithSession()
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request.request))
        return request.session
    }

    private class MockHttpRequestWithSession {
        val session = MockHttpSession()
        val request = MockHttpServletRequest().also { it.setSession(session) }
    }

    /** An identity nobody linked is refused, even when the provider reports the e-mail address of an existing administrator. */
    @Test
    fun unlinkedIdentityIsDeniedEvenWithAMatchingEmail() {
        Mockito.`when`(oauth2.loadUser(Mockito.any())).thenReturn(providerUser(7, "admin@example.test"))

        val denied = assertFailsWith<SsoDenied> { services().loadOAuth2(githubRequest()) }

        assertEquals(UNLINKED_IDENTITY, denied.error.errorCode)
        verify(users, never()).findByUsername(Mockito.anyString())
    }

    /** A linked identity signs in as the local account, with the stored role and the account id as its name. */
    @Test
    fun linkedIdentitySignsInAsTheLocalAccount() {
        Mockito.`when`(oauth2.loadUser(Mockito.any())).thenReturn(providerUser(7, "someone@else.test"))
        Mockito.`when`(identities.findByProviderAndSubject("github", "7")).thenReturn(ExternalIdentity(userId = "u1", provider = "github", subject = "7"))
        Mockito.`when`(users.findById("u1")).thenReturn(Optional.of(user("u1", role = "CLIENT")))

        val principal = services().loadOAuth2(githubRequest())

        assertEquals("u1", principal.name)
        assertEquals(listOf("ROLE_CLIENT"), principal.authorities.map { it.authority })
    }

    /** A disabled account cannot sign in through a provider either. */
    @Test
    fun disabledAccountIsDenied() {
        Mockito.`when`(oauth2.loadUser(Mockito.any())).thenReturn(providerUser(7, "a@b.test"))
        Mockito.`when`(identities.findByProviderAndSubject("github", "7")).thenReturn(ExternalIdentity(userId = "u1", provider = "github", subject = "7"))
        Mockito.`when`(users.findById("u1")).thenReturn(Optional.of(user("u1", enabled = false)))

        val denied = assertFailsWith<SsoDenied> { services().loadOAuth2(githubRequest()) }

        assertEquals(DISABLED_ACCOUNT, denied.error.errorCode)
    }

    /** A signed-in administrator who asked to link GitHub links exactly the identity that signed in, once. */
    @Test
    fun linkIntentCreatesTheLinkForTheSignedInAccount() {
        Mockito.`when`(oauth2.loadUser(Mockito.any())).thenReturn(providerUser(7, "a@b.test"))
        Mockito.`when`(users.findById("u1")).thenReturn(Optional.of(user("u1")))
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken("u1", "n/a", "ROLE_ADMIN")
        val session = session()
        session.setAttribute(LINK_INTENT, LinkIntent.of("u1", "github"))

        val principal = services().loadOAuth2(githubRequest())

        assertEquals("u1", principal.name)
        assertNotNull(session.getAttribute(LINKED_MARKER))
        assertNull(session.getAttribute(LINK_INTENT))
        verify(identities).save(Mockito.any(ExternalIdentity::class.java))
    }

    /** A link intent created for another account or another provider is never honoured. */
    @Test
    fun linkIntentOfAnotherAccountIsRefused() {
        Mockito.`when`(oauth2.loadUser(Mockito.any())).thenReturn(providerUser(7, "a@b.test"))
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken("u2", "n/a", "ROLE_ADMIN")
        session().setAttribute(LINK_INTENT, LinkIntent.of("u1", "github"))

        assertFailsWith<SsoDenied> { services().loadOAuth2(githubRequest()) }

        verify(identities, never()).save(Mockito.any(ExternalIdentity::class.java))
    }

    /** An expired link intent is ignored and the identity must already be linked. */
    @Test
    fun expiredLinkIntentIsRefused() {
        Mockito.`when`(oauth2.loadUser(Mockito.any())).thenReturn(providerUser(7, "a@b.test"))
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken("u1", "n/a", "ROLE_ADMIN")
        session().setAttribute(LINK_INTENT, LinkIntent("u1", "github", Instant.now().minusSeconds(1)))

        assertFailsWith<SsoDenied> { services().loadOAuth2(githubRequest()) }

        verify(identities, never()).save(Mockito.any(ExternalIdentity::class.java))
    }

    /** An identity that already belongs to another account cannot be linked to this one. */
    @Test
    fun identityOfAnotherAccountCannotBeLinked() {
        Mockito.`when`(users.findById("u1")).thenReturn(Optional.of(user("u1")))
        Mockito.`when`(identities.findByProviderAndSubject("github", "7")).thenReturn(ExternalIdentity(userId = "u2", provider = "github", subject = "7"))

        val denied = assertFailsWith<SsoDenied> { accounts.link("u1", "github", "7") }

        assertEquals(IDENTITY_IN_USE, denied.error.errorCode)
        verify(identities, never()).save(Mockito.any(ExternalIdentity::class.java))
    }

    /** Unlinking removes only the requested provider of the requested account. */
    @Test
    fun unlinkRemovesOnlyThatProvider() {
        val link = ExternalIdentity(userId = "u1", provider = "github", subject = "7")
        Mockito.`when`(identities.findByUserIdAndProvider("u1", "github")).thenReturn(link)

        assertEquals(true, accounts.unlink("u1", "github"))
        assertEquals(false, accounts.unlink("u1", "google"))

        verify(identities).delete(link)
    }
}
