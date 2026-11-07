package de.alexandermora.modsync.server.auth

import de.alexandermora.modsync.protocol.Credentials
import de.alexandermora.modsync.protocol.Login
import de.alexandermora.modsync.protocol.RefreshRequest
import de.alexandermora.modsync.protocol.UserRegistration
import de.alexandermora.modsync.server.common.acknowledged
import de.alexandermora.modsync.server.common.actor
import kotlinx.serialization.json.JsonPrimitive
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST endpoints for login, token refresh, user creation and device management.
 *
 * @property auth Service holding the authentication logic.
 */
@RestController
@RequestMapping("/api/v1")
class AuthController(private val auth: AuthService) {
    /**
     * Authenticates a user and issues credentials bound to a device.
     *
     * Public endpoint, rate limited per remote address.
     *
     * @param request Login with protocol version, username, password and device UUID.
     * @return Access and refresh credentials; 401 for wrong credentials, 409 for a foreign or revoked device.
     */
    @PostMapping("/auth/login")
    fun login(
        @RequestBody request: Login
    ): Credentials = auth.login(request)

    /**
     * Exchanges a refresh token for a fresh credential pair.
     *
     * Public endpoint, rate limited per remote address; the refresh token is single-use.
     *
     * @param request Request carrying the refresh token.
     * @return New credentials; 401 for an unknown or expired token, 404 for a revoked device.
     */
    @PostMapping("/auth/refresh")
    fun refresh(
        @RequestBody request: RefreshRequest
    ): Credentials = auth.refresh(request.token)

    /**
     * Creates a CLIENT-role user.
     *
     * Requires the ADMIN role. The protocol answers with a bare JSON string, hence [JsonPrimitive].
     *
     * @param request Username and password (16-256 characters).
     * @return The new user's UUID as a JSON string.
     */
    @PostMapping("/admin/users")
    fun createUser(
        @RequestBody request: UserRegistration
    ): JsonPrimitive =
        JsonPrimitive(auth.createUser(actor(), request))

    /**
     * Lists the active devices of the authenticated actor.
     *
     * Requires the CLIENT or ADMIN role.
     *
     * @return UUIDs of the caller's non-revoked devices.
     */
    @GetMapping("/devices")
    fun devices(): List<String> = auth.activeDevices(actor())

    /**
     * Revokes one of the caller's devices and its credentials.
     *
     * Requires the CLIENT or ADMIN role; a device owned by someone else is left untouched.
     *
     * @param id Device UUID (path).
     * @return Always `true`.
     */
    @PostMapping("/devices/{id}/revoke")
    fun revokeDevice(
        @PathVariable id: String
    ): Boolean =
        acknowledged { auth.revokeDevice(actor(), id) }
}
