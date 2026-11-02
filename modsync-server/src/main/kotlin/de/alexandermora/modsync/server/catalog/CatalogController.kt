package de.alexandermora.modsync.server.catalog

import kotlinx.serialization.json.JsonObject
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class CatalogController(private val catalog: CatalogService) {
    @GetMapping("/admin/servers/{id}/identified") fun identified(@PathVariable id: String): Map<String, JsonObject> = catalog.identified(id)

    @GetMapping("/mods/{project}/updates")
    fun updates(@PathVariable project: String, @RequestParam minecraft: String, @RequestParam loader: String): List<JsonObject> =
        catalog.compatibleVersions(project, minecraft, loader)
}
