package de.alexandermora.modsync.server

import de.alexandermora.modsync.modrinth.Modrinth
import de.alexandermora.modsync.protocol.SyncReport
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.inventory.InventoryService
import de.alexandermora.modsync.server.inventory.MinecraftServerRepository
import de.alexandermora.modsync.server.manifest.ManifestRevisionRepository
import de.alexandermora.modsync.server.manifest.ManifestService
import de.alexandermora.modsync.server.manifest.ServerManifestRepository
import de.alexandermora.modsync.server.manifest.SynchronizationHistoryRepository
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Verifies manifest lookups and client reports without a database. */
class ManifestServiceTest {
    private val serverId = "11111111-1111-1111-1111-111111111111"
    private val service =
        ManifestService(
            Mockito.mock(InventoryService::class.java),
            Mockito.mock(MinecraftServerRepository::class.java),
            Mockito.mock(ServerManifestRepository::class.java),
            Mockito.mock(ManifestRevisionRepository::class.java),
            Mockito.mock(SynchronizationHistoryRepository::class.java),
            Mockito.mock(Modrinth::class.java),
            Mockito.mock(AuditService::class.java)
        )

    /** Only known result statuses are accepted. */
    @Test
    fun reportRejectsUnknownStatus() {
        assertFailsWith<IllegalArgumentException> { service.report("device", SyncReport(serverId, 1, "DONE")) }
    }

    /** Reporting against a revision that was never published is a 404. */
    @Test
    fun reportRequiresPublishedRevision() {
        val error = assertFailsWith<ResponseStatusException> { service.report("device", SyncReport(serverId, 1, "SUCCESS")) }
        assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
    }

    /** A server without any published manifest has nothing to load. */
    @Test
    fun missingManifestIsNotFound() {
        val error = assertFailsWith<ResponseStatusException> { service.manifest(serverId, null) }
        assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
    }
}
