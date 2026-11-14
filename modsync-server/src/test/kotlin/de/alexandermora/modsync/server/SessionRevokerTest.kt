package de.alexandermora.modsync.server

import de.alexandermora.modsync.server.ui.RegistryUserSessionRevoker
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.session.SessionRegistryImpl
import org.springframework.security.core.userdetails.User
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies that ending an account's sessions reaches sessions opened with a password and sessions opened through a provider. */
class SessionRevokerTest {
    private val registry = SessionRegistryImpl()
    private val revoker = RegistryUserSessionRevoker(registry)

    private fun passwordPrincipal(id: String) =
        User
            .withUsername(id)
            .password("x")
            .roles("ADMIN")
            .build()

    private fun providerPrincipal(id: String) = DefaultOAuth2User(listOf(SimpleGrantedAuthority("ROLE_ADMIN")), mapOf("modsync_user" to id), "modsync_user")

    /** Both kinds of principal are expired for the targeted account only. */
    @Test
    fun expiresPasswordAndProviderSessionsOfTheAccount() {
        registry.registerNewSession("password-session", passwordPrincipal("u1"))
        registry.registerNewSession("provider-session", providerPrincipal("u1"))
        registry.registerNewSession("other-session", providerPrincipal("u2"))

        revoker.expireSessions("u1")

        assertTrue(registry.getSessionInformation("password-session")!!.isExpired)
        assertTrue(registry.getSessionInformation("provider-session")!!.isExpired)
        assertFalse(registry.getSessionInformation("other-session")!!.isExpired)
    }
}
