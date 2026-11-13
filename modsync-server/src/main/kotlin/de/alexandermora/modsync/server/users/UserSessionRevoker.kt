package de.alexandermora.modsync.server.users

/**
 * Ends the live interactive sessions of an account.
 *
 * Declared by the user-management service so that it does not depend on how the dashboard stores its sessions.
 */
fun interface UserSessionRevoker {
    /**
     * Expires every live dashboard session of the account; the next request of each session is rejected.
     *
     * @param userId User UUID, which is also the authentication name of the dashboard principal.
     */
    fun expireSessions(userId: String)
}
