package de.alexandermora.modsync.server.ui

import de.alexandermora.modsync.server.users.UserSessionRevoker
import org.springframework.security.core.session.SessionRegistry
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Component

/**
 * Expires dashboard sessions through the [SessionRegistry] wired in [DashboardSecurityConfiguration].
 *
 * @property registry Registry that tracks every live dashboard session.
 */
@Component
class RegistryUserSessionRevoker(
    private val registry: SessionRegistry
) : UserSessionRevoker {
    /**
     * Expires every live session whose principal is the given account.
     *
     * @param userId User UUID, matching the principal name built by [DashboardUserDetailsService].
     */
    override fun expireSessions(userId: String) {
        registry.allPrincipals
            .filter { (it as? UserDetails)?.username == userId }
            .flatMap { registry.getAllSessions(it, false) }
            .forEach { it.expireNow() }
    }
}
