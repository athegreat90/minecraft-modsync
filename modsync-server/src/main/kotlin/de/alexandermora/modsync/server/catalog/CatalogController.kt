package de.alexandermora.modsync.server.catalog

import kotlinx.serialization.json.JsonObject
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * REST endpoints exposing Modrinth catalog data.
 *
 * @property catalog Service that queries Modrinth.
 */
@RestController
@RequestMapping("/api/v1")
class CatalogController(private val catalog: CatalogService) {
    /**
     * Returns the Modrinth projects identified for a server's accepted inventory.
     *
     * Requires the ADMIN role.
     *
     * @param id Server UUID (path).
     * @return Modrinth project objects keyed by SHA-512; unknown hashes are absent.
     */
    @GetMapping("/admin/servers/{id}/identified")
    fun identified(
        @PathVariable id: String
    ): Map<String, JsonObject> = catalog.identified(id)

    /**
     * Lists compatible versions of a mod for a Minecraft version and loader.
     *
     * Requires the CLIENT or ADMIN role.
     *
     * @param project Modrinth project id or slug (path).
     * @param minecraft Minecraft version (query).
     * @param loader Mod loader name (query).
     * @return Modrinth version objects.
     */
    @GetMapping("/mods/{project}/updates")
    fun updates(
        @PathVariable project: String,
        @RequestParam minecraft: String,
        @RequestParam loader: String
    ): List<JsonObject> =
        catalog.compatibleVersions(project, minecraft, loader)
}
