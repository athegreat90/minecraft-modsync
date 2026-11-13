package de.alexandermora.modsync.server.common

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Process-wide maintenance switch shared by the dashboard and the user-management service.
 *
 * The flag is read once at startup from `modsync.maintenance`; the API chain enforces the same switch with 503
 * before authentication, while server-rendered mutations call [requireWritable] and fail with 409.
 *
 * @property active Whether state changes are currently rejected.
 */
@Component
class MaintenanceMode(
    @Value($$"${modsync.maintenance}") private val active: Boolean
) {
    /**
     * Rejects state changes while the system is in maintenance mode.
     *
     * @throws org.springframework.web.server.ResponseStatusException with 409 during maintenance.
     */
    fun requireWritable() = requireState(!active, "Maintenance mode is active")
}
