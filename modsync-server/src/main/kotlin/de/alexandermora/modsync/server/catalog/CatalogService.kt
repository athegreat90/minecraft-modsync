package de.alexandermora.modsync.server.catalog

import de.alexandermora.modsync.modrinth.Modrinth
import de.alexandermora.modsync.server.inventory.InventoryService
import kotlinx.serialization.json.JsonObject
import org.springframework.stereotype.Service

/**
 * Answers catalog questions from Modrinth for accepted inventories.
 *
 * @property modrinth Modrinth API client.
 * @property inventory Inventory service providing the accepted snapshot.
 */
@Service
class CatalogService(private val modrinth: Modrinth, private val inventory: InventoryService) {
    /**
     * Identifies the projects for the hashes of a server's accepted inventory.
     *
     * @param serverId Server UUID.
     * @return Modrinth projects keyed by SHA-512 (unknown hashes are absent).
     */
    fun identified(serverId: String): Map<String, JsonObject> =
        modrinth.identify(inventory.entries(inventory.acceptedSnapshot(serverId)).map { it.sha512 })

    /**
     * Lists Modrinth versions of a project compatible with a Minecraft version and loader.
     *
     * @param project Modrinth project id or slug.
     * @param minecraft Minecraft version.
     * @param loader Mod loader name.
     * @return Matching version objects.
     */
    fun compatibleVersions(project: String, minecraft: String, loader: String): List<JsonObject> =
        modrinth.compatibleVersions(project, minecraft, loader)
}
