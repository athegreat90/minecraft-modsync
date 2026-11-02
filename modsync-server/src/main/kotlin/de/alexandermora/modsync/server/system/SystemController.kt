package de.alexandermora.modsync.server.system

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import de.alexandermora.modsync.protocol.Capabilities
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class SystemController(private val db: JdbcTemplate, private val json: Json) {
    @GetMapping("/info") fun info(): Capabilities =
        Capabilities(db.queryForObject("SELECT value FROM system_settings WHERE name='control_plane_id'", String::class.java)!!)

    @GetMapping("/openapi.json") fun openapi(): JsonElement =
        ClassPathResource("openapi.json").inputStream.use { json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)) }
}
