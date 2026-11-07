package de.alexandermora.modsync.server

import de.alexandermora.modsync.modrinth.Modrinth
import de.alexandermora.modsync.server.catalog.CatalogService
import de.alexandermora.modsync.server.inventory.InventoryService
import kotlinx.serialization.json.JsonObject
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals

/** Verifies the catalog delegates to Modrinth with the right inputs. */
class CatalogServiceTest {
    private val modrinth = Mockito.mock(Modrinth::class.java)
    private val inventory = Mockito.mock(InventoryService::class.java)
    private val service = CatalogService(modrinth, inventory)

    /** Compatible versions are looked up for exactly the requested project, version and loader. */
    @Test
    fun compatibleVersionsDelegates() {
        val expected = listOf(JsonObject(emptyMap()))
        Mockito.`when`(modrinth.compatibleVersions("sodium", "1.21", "fabric")).thenReturn(expected)
        assertEquals(expected, service.compatibleVersions("sodium", "1.21", "fabric"))
    }

    /** Identification covers the hashes of the accepted snapshot only. */
    @Test
    fun identifiedUsesAcceptedSnapshotHashes() {
        Mockito.`when`(inventory.acceptedSnapshot("srv")).thenReturn("scan")
        Mockito.`when`(inventory.entries("scan")).thenReturn(
            listOf(
                de.alexandermora.modsync.domain
                    .ModEntry("a.jar", "a".repeat(128), 1)
            )
        )
        val expected = mapOf("a".repeat(128) to JsonObject(emptyMap()))
        Mockito.`when`(modrinth.identify(listOf("a".repeat(128)))).thenReturn(expected)
        assertEquals(expected, service.identified("srv"))
    }
}
