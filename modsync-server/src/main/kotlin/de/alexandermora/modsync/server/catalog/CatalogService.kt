package de.alexandermora.modsync.server.catalog

import kotlinx.serialization.json.JsonObject
import de.alexandermora.modsync.modrinth.Modrinth
import de.alexandermora.modsync.server.inventory.InventoryService
import org.springframework.stereotype.Service

@Service
class CatalogService(private val modrinth: Modrinth, private val inventory: InventoryService) {
    fun identified(serverId: String): Map<String, JsonObject> =
        modrinth.identify(inventory.entries(inventory.acceptedSnapshot(serverId)).map { it.sha512 })

    fun compatibleVersions(project: String, minecraft: String, loader: String): List<JsonObject> =
        modrinth.compatibleVersions(project, minecraft, loader)
}
