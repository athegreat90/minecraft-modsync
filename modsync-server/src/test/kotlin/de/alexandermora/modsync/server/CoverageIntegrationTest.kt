package de.alexandermora.modsync.server

import de.alexandermora.modsync.domain.Artifact
import de.alexandermora.modsync.domain.ClientActivity
import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.CoverageStatus
import de.alexandermora.modsync.domain.Manifest
import de.alexandermora.modsync.protocol.ClientInventoryReport
import de.alexandermora.modsync.protocol.ClientModEntry
import de.alexandermora.modsync.server.clientinventory.ClientInventoryPersistence
import de.alexandermora.modsync.server.clientinventory.CoverageService
import de.alexandermora.modsync.server.clientinventory.LatestManifestSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/** Verifies the server/client coverage matrix against MariaDB-backed inventories. */
@Testcontainers
class CoverageIntegrationTest {
    companion object { @Container @JvmField val database = MariaDBContainer<Nothing>("mariadb:11.4.10") }

    private lateinit var db: JdbcTemplate
    private lateinit var persistence: ClientInventoryPersistence
    private val hashA = "a".repeat(128)
    private val hashB = "b".repeat(128)
    private val server = UUID.randomUUID().toString()

    /** Migrates the schema, registers a server and a user. */
    @BeforeEach fun prepare() {
        val source = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        Flyway.configure().dataSource(source).load().migrate()
        db = JdbcTemplate(source)
        persistence = ClientInventoryPersistence(db)
        db.update("INSERT IGNORE INTO minecraft_servers (id, name, minecraft_version, loader) VALUES (?,?,?,?)", server, "s", "1.21.1", "neoforge")
    }

    /** Creates a device and stores one report for a fresh instance, returning the instance UUID. */
    private fun store(mods: List<ClientModEntry>, mc: String = "1.21.1", activity: ClientActivity = ClientActivity.IDLE, at: Instant = Instant.now()): String {
        val user = UUID.randomUUID().toString(); val device = UUID.randomUUID().toString(); val instance = UUID.randomUUID().toString()
        db.update("INSERT INTO users (id, username, password_hash, role) VALUES (?,?,?,?)", user, "u-$user", "x", "CLIENT")
        db.update("INSERT INTO client_devices (id, user_id) VALUES (?,?)", device, user)
        persistence.registerAndLock(instance, device, "main")
        persistence.replace(
            ClientInventoryReport(instance, "main", server, mc, "neoforge", sequence = 1, activity = activity, mods = mods), at
        )
        return instance
    }

    /** Source returning a manifest that requires one client mod. */
    private val source = LatestManifestSource {
        Manifest(it, 3, "1.21.1", "neoforge", null,
            listOf(Artifact("p1", "v1", "m.jar", hashA, 1, null, setOf("1.21.1"), setOf("neoforge"), Classification.CLIENT_REQUIRED)),
            "2026-01-01T00:00:00Z", "d")
    }

    /** Returns the status of [instance] in the matrix. */
    private fun status(instance: String, now: Instant = Instant.now()) =
        CoverageService(persistence, source).forServer(server, now).rows.single { it.instanceId == instance }.status

    /** Matches by hash, distinguishing up to date, wrong version, missing and incompatible clients. */
    @Test fun classifiesInstances() {
        val ok = store(listOf(ClientModEntry(hashA, "x.jar", "p1")))
        val wrong = store(listOf(ClientModEntry(hashB, "m.jar", "p1")))
        val missing = store(emptyList())
        val incompatible = store(listOf(ClientModEntry(hashA, "x.jar", "p1")), mc = "1.20.1")
        assertEquals(CoverageStatus.UP_TO_DATE, status(ok))
        assertEquals(CoverageStatus.WRONG_VERSION, status(wrong))
        assertEquals(CoverageStatus.MISSING_MODS, status(missing))
        assertEquals(CoverageStatus.INCOMPATIBLE, status(incompatible))
    }

    /** Old reports make a client offline, then stale; neither is reported as up to date. */
    @Test fun offlineAndStaleAreNotUpToDate() {
        val instance = store(listOf(ClientModEntry(hashA, "x.jar", "p1")))
        assertEquals(CoverageStatus.OFFLINE, status(instance, Instant.now().plus(Duration.ofHours(2))))
        assertEquals(CoverageStatus.INVENTORY_STALE, status(instance, Instant.now().plus(Duration.ofDays(2))))
    }

    /** In-flight synchronization wins over installed-file comparison. */
    @Test fun syncActivityIsReported() {
        assertEquals(CoverageStatus.SYNCING, status(store(emptyList(), activity = ClientActivity.SYNCING)))
    }
}
