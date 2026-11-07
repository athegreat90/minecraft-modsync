package de.alexandermora.modsync.server

import de.alexandermora.modsync.protocol.ServerRegistration
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.inventory.ClassificationOverrideRepository
import de.alexandermora.modsync.server.inventory.InventoryEntryRepository
import de.alexandermora.modsync.server.inventory.InventoryService
import de.alexandermora.modsync.server.inventory.InventorySnapshotRepository
import de.alexandermora.modsync.server.inventory.MinecraftServer
import de.alexandermora.modsync.server.inventory.MinecraftServerRepository
import org.mockito.Mockito
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Verifies inventory service rules that do not need a database. */
class InventoryServiceTest {
    private val servers = Mockito.mock(MinecraftServerRepository::class.java)
    private val service =
        InventoryService(
            servers,
            Mockito.mock(InventorySnapshotRepository::class.java),
            Mockito.mock(InventoryEntryRepository::class.java),
            Mockito.mock(ClassificationOverrideRepository::class.java),
            Mockito.mock(AuditService::class.java)
        )

    /** Names and versions must be non-empty and bounded. */
    @Test
    fun registrationValidatesLengths() {
        assertFailsWith<IllegalArgumentException> { service.registerServer("admin", ServerRegistration("", "1.21")) }
        assertFailsWith<IllegalArgumentException> { service.registerServer("admin", ServerRegistration("a".repeat(201), "1.21")) }
        assertFailsWith<IllegalArgumentException> { service.registerServer("admin", ServerRegistration("srv", "1.21", loader = "")) }
    }

    /** Inventory is stale when never committed or older than 15 minutes, and unpublished servers have no revision. */
    @Test
    fun listServersFlagsStaleInventory() {
        val fresh = MinecraftServer(id = "fresh", name = "a", minecraftVersion = "1.21", loader = "neoforge", inventoryAt = Instant.now(), revision = 3)
        val old = MinecraftServer(id = "old", name = "b", minecraftVersion = "1.21", loader = "neoforge", inventoryAt = Instant.now().minusSeconds(3600))
        val never = MinecraftServer(id = "never", name = "c", minecraftVersion = "1.21", loader = "neoforge")
        Mockito.`when`(servers.findAllByOrderByName()).thenReturn(listOf(fresh, old, never))
        val listed = service.listServers().associateBy { it.id }
        assertEquals(false, listed.getValue("fresh").inventoryStale)
        assertEquals(3L, listed.getValue("fresh").publishedRevision)
        assertEquals(true, listed.getValue("old").inventoryStale)
        assertEquals(true, listed.getValue("never").inventoryStale)
        assertEquals(null, listed.getValue("never").publishedRevision)
    }
}
