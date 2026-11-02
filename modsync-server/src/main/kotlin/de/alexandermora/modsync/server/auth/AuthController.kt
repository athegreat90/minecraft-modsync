package de.alexandermora.modsync.server.auth

import kotlinx.serialization.json.JsonPrimitive
import de.alexandermora.modsync.protocol.Credentials
import de.alexandermora.modsync.protocol.Login
import de.alexandermora.modsync.protocol.RefreshRequest
import de.alexandermora.modsync.protocol.UserRegistration
import de.alexandermora.modsync.server.common.acknowledged
import de.alexandermora.modsync.server.common.actor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class AuthController(private val auth: AuthService) {
    @PostMapping("/auth/login") fun login(@RequestBody request: Login): Credentials = auth.login(request)

    @PostMapping("/auth/refresh") fun refresh(@RequestBody request: RefreshRequest): Credentials = auth.refresh(request.token)

    // The protocol answers with a bare JSON string, hence JsonPrimitive instead of a wrapper object.
    @PostMapping("/admin/users") fun createUser(@RequestBody request: UserRegistration): JsonPrimitive =
        JsonPrimitive(auth.createUser(actor(), request))

    @GetMapping("/devices") fun devices(): List<String> = auth.activeDevices(actor())

    @PostMapping("/devices/{id}/revoke") fun revokeDevice(@PathVariable id: String): Boolean =
        acknowledged { auth.revokeDevice(actor(), id) }
}
