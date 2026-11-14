package de.alexandermora.modsync.server.sso

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository

/** Registration id of the Google provider; it appears in `/oauth2/authorization/google` and in the callback path. */
const val GOOGLE = "google"

/** Registration id of the GitHub provider; it appears in `/oauth2/authorization/github` and in the callback path. */
const val GITHUB = "github"

/**
 * Registers Google and GitHub as sign-in providers, but only for the providers whose credentials are configured.
 *
 * Without any credentials this configuration is inactive, no OAuth2 login is added to the dashboard chain and the
 * application behaves exactly as before single sign-on existed.
 */
@Configuration
@ConditionalOnExpression($$"!'${modsync.sso.google.client-id:}'.isEmpty() or !'${modsync.sso.github.client-id:}'.isEmpty()")
class SsoConfiguration {
    /**
     * Builds the registrations from `modsync.sso.*` properties.
     *
     * Scopes are the minimum needed to identify a person: `openid`, `profile` and `email` for Google, and `read:user` for
     * GitHub. The redirect URI is the Spring Security default, `{baseUrl}/login/oauth2/code/{registrationId}`.
     *
     * @param googleId Google client id, or empty to disable Google.
     * @param googleSecret Google client secret.
     * @param githubId GitHub client id, or empty to disable GitHub.
     * @param githubSecret GitHub client secret.
     * @return The repository holding every configured provider.
     */
    @Bean
    fun clientRegistrationRepository(
        @Value($$"${modsync.sso.google.client-id:}") googleId: String,
        @Value($$"${modsync.sso.google.client-secret:}") googleSecret: String,
        @Value($$"${modsync.sso.github.client-id:}") githubId: String,
        @Value($$"${modsync.sso.github.client-secret:}") githubSecret: String
    ): ClientRegistrationRepository {
        val registrations = mutableListOf<ClientRegistration>()
        if (googleId.isNotBlank()) {
            registrations +=
                CommonOAuth2Provider.GOOGLE
                    .getBuilder(GOOGLE)
                    .clientId(googleId)
                    .clientSecret(googleSecret)
                    .scope("openid", "profile", "email")
                    .build()
        }
        if (githubId.isNotBlank()) {
            registrations +=
                CommonOAuth2Provider.GITHUB
                    .getBuilder(GITHUB)
                    .clientId(githubId)
                    .clientSecret(githubSecret)
                    .scope("read:user")
                    .build()
        }
        return InMemoryClientRegistrationRepository(registrations)
    }
}
