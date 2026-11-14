package de.alexandermora.modsync.server.sso

import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.common.MaintenanceMode
import de.alexandermora.modsync.server.common.actor
import de.alexandermora.modsync.server.common.notFound
import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.Serializable
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Providers that can be used to sign in.
 *
 * @property providers Registration ids of the configured providers, empty when single sign-on is not configured.
 */
@Serializable
data class ProvidersView(
    val providers: List<String>
)

/**
 * Provider links of the signed-in account.
 *
 * @property linked Providers already linked.
 * @property available Providers that are configured and can be linked.
 */
@Serializable
data class IdentitiesView(
    val linked: List<String>,
    val available: List<String>
)

/**
 * Where the browser must go to start linking a provider.
 *
 * @property authorizationUrl Same-origin path of the provider's authorization endpoint.
 */
@Serializable
data class LinkStart(
    val authorizationUrl: String
)

/**
 * Single sign-on endpoints used by the Angular interface.
 *
 * Listing providers is public (the login page needs it). Everything else needs an administrator session, and the state
 * changing calls additionally need a CSRF token because they live under `/ui/api`.
 *
 * @property registrations Configured providers, absent when single sign-on is not configured.
 * @property accounts Mapping between provider identities and accounts.
 * @property audit Audit log writer.
 * @property maintenance Maintenance switch that rejects state changes.
 */
@RestController
@RequestMapping("/ui/api")
class SsoController(
    private val registrations: ObjectProvider<ClientRegistrationRepository>,
    private val accounts: SsoAccounts,
    private val audit: AuditService,
    private val maintenance: MaintenanceMode
) {
    /**
     * Lists the configured providers.
     *
     * @return Registration ids, possibly empty.
     */
    @GetMapping("/sso/providers")
    fun providers(): ProvidersView = ProvidersView(enabledProviders())

    /**
     * Lists the providers linked to the signed-in account and those that can still be linked.
     *
     * @return The account's links.
     */
    @GetMapping("/me/identities")
    fun identities(): IdentitiesView = IdentitiesView(accounts.providersOf(actor()), enabledProviders())

    /**
     * Prepares linking a provider to the signed-in account.
     *
     * The intent is stored in the server-side session; the browser then follows the returned path, signs in at the provider and
     * returns to the callback, which creates the link only for this account and this provider.
     *
     * @param provider Registration id (path).
     * @param request Current request, whose session receives the intent.
     * @return The authorization path to open; 404 for providers that are not configured, 409 during maintenance.
     */
    @PostMapping("/me/identities/{provider}/link")
    fun startLink(
        @PathVariable provider: String,
        request: HttpServletRequest
    ): LinkStart {
        maintenance.requireWritable()
        if (provider !in enabledProviders()) notFound()
        request.getSession(true).setAttribute(LINK_INTENT, LinkIntent.of(actor(), provider))
        audit.record(actor(), "sso-link-requested:$provider", actor())
        return LinkStart("/oauth2/authorization/$provider")
    }

    /**
     * Removes a provider link from the signed-in account. The local password keeps working.
     *
     * @param provider Registration id (path).
     * @return Nothing; 404 when the account had no such link, 409 during maintenance.
     */
    @DeleteMapping("/me/identities/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unlink(
        @PathVariable provider: String
    ) {
        maintenance.requireWritable()
        if (!accounts.unlink(actor(), provider)) notFound()
        audit.record(actor(), "sso-unlinked:$provider", actor())
    }

    /**
     * Lists the registration ids of the configured providers.
     *
     * @return Sorted ids, empty when single sign-on is not configured.
     */
    private fun enabledProviders(): List<String> =
        registrations.ifAvailable
            ?.let { repository -> (repository as Iterable<*>).filterIsInstance<ClientRegistration>() }
            .orEmpty()
            .map(ClientRegistration::getRegistrationId)
            .sorted()
}
