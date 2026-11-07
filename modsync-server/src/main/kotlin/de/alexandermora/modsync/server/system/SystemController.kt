package de.alexandermora.modsync.server.system

import de.alexandermora.modsync.protocol.Capabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST endpoints for public server capabilities and the OpenAPI document.
 *
 * Both routes are public (no bearer token).
 *
 * @property db JDBC access for the control-plane id.
 * @property json Wire-format JSON used to serve the OpenAPI document.
 */
@RestController
@RequestMapping("/api/v1")
class SystemController(private val db: JdbcTemplate, private val json: Json) {
    /**
     * Describes the control plane for client discovery and compatibility checks.
     *
     * @return Capabilities including the control-plane id.
     */
    @GetMapping("/info")
    fun info(): Capabilities =
        Capabilities(db.queryForObject("SELECT value FROM system_settings WHERE name='control_plane_id'", String::class.java)!!)

    /**
     * Serves the bundled OpenAPI description of the REST API.
     *
     * @return The OpenAPI document as JSON.
     */
    @GetMapping("/openapi.json")
    fun openapi(): JsonElement =
        ClassPathResource("openapi.json").inputStream.use { json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)) }
}
