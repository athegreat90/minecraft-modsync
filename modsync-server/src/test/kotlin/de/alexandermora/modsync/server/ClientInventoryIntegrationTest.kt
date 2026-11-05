package de.alexandermora.modsync.server

import de.alexandermora.modsync.protocol.ClientInventoryReport
import de.alexandermora.modsync.protocol.ClientModEntry
import de.alexandermora.modsync.server.clientinventory.ClientInventoryPersistence
import de.alexandermora.modsync.server.clientinventory.ClientInventoryService
import de.alexandermora.modsync.server.common.AuditEventRepository
import de.alexandermora.modsync.server.common.AuditService
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies client inventory persistence, idempotency, staleness handling and ownership against MariaDB. */
@Testcontainers
class ClientInventoryIntegrationTest {
    companion object { @Container @JvmField val database = MariaDBContainer<Nothing>("mariadb:11.4.10") }

    private lateinit var db: JdbcTemplate
    private lateinit var service: ClientInventoryService
    private val hashA = "a".repeat(128)
    private val hashB = "b".repeat(128)

    /** Migrates the schema and wires the service with real persistence. */
    @BeforeEach fun prepare() {
        val source = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        Flyway.configure().dataSource(source).load().migrate()
        val emf = LocalContainerEntityManagerFactoryBean().apply {
            dataSource = source; setPackagesToScan("de.alexandermora.modsync.server"); jpaVendorAdapter = HibernateJpaVendorAdapter()
            setJpaPropertyMap(mapOf("hibernate.type.preferred_instant_jdbc_type" to "TIMESTAMP")); afterPropertiesSet()
        }
        val factory = JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf.`object`!!))
        db = JdbcTemplate(source)
        val audit = AuditService(factory.getRepository(AuditEventRepository::class.java))
        service = ClientInventoryService(ClientInventoryPersistence(db), audit)
        transactions = JpaTransactionManager(emf.`object`!!)
    }

    private lateinit var transactions: JpaTransactionManager

    /** Creates a user with one device and returns the device UUID. */
    private fun device(): String {
        val user = UUID.randomUUID().toString()
        val device = UUID.randomUUID().toString()
        db.update("INSERT INTO users (id, username, password_hash, role) VALUES (?,?,?,?)", user, "u-$user", "x", "CLIENT")
        db.update("INSERT INTO client_devices (id, user_id) VALUES (?,?)", user.let { device }, user)
        return device
    }

    /** Builds a report for [instance] with the supplied sequence and mods. */
    private fun report(instance: String, sequence: Long, vararg mods: ClientModEntry) = ClientInventoryReport(
        instanceId = instance, instanceName = "main", minecraftVersion = "1.21.1", loader = "neoforge",
        sequence = sequence, mods = mods.toList()
    )

    /** Runs the service inside a transaction, as the proxy would in production. */
    private fun <T> inTx(block: () -> T): T = org.springframework.transaction.support.TransactionTemplate(transactions).execute { block() }!!

    /** A first report is stored with its mods and acknowledged. */
    @Test fun storesInventory() {
        val device = device(); val instance = UUID.randomUUID().toString()
        val ack = inTx { service.report(device, report(instance, 1, ClientModEntry(hashA, "a.jar", "p1", "v1", true))) }
        assertTrue(ack.accepted)
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM client_instance_mods WHERE instance_id = ?", Int::class.java, instance))
    }

    /** Equal sequence is an idempotent retry; lower sequence cannot overwrite newer data. */
    @Test fun retryIsIdempotentAndStaleIsRejected() {
        val device = device(); val instance = UUID.randomUUID().toString()
        inTx { service.report(device, report(instance, 5, ClientModEntry(hashA, "a.jar"))) }
        assertTrue(inTx { service.report(device, report(instance, 5, ClientModEntry(hashB, "b.jar"))) }.accepted)
        val stale = inTx { service.report(device, report(instance, 4, ClientModEntry(hashB, "b.jar"))) }
        assertFalse(stale.accepted)
        assertEquals(5L, stale.sequence)
        assertEquals(listOf(hashA), db.queryForList("SELECT sha512 FROM client_instance_mods WHERE instance_id = ?", String::class.java, instance))
    }

    /** A newer sequence replaces the previous mod set. */
    @Test fun newerSequenceReplaces() {
        val device = device(); val instance = UUID.randomUUID().toString()
        inTx { service.report(device, report(instance, 1, ClientModEntry(hashA, "a.jar"))) }
        inTx { service.report(device, report(instance, 2, ClientModEntry(hashB, "b.jar"))) }
        assertEquals(listOf(hashB), db.queryForList("SELECT sha512 FROM client_instance_mods WHERE instance_id = ?", String::class.java, instance))
    }

    /** One device may own several instances independently. */
    @Test fun multipleInstancesPerDevice() {
        val device = device()
        val first = UUID.randomUUID().toString(); val second = UUID.randomUUID().toString()
        inTx { service.report(device, report(first, 1, ClientModEntry(hashA, "a.jar"))) }
        inTx { service.report(device, report(second, 1, ClientModEntry(hashB, "b.jar"))) }
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM client_instances WHERE device_id = ?", Int::class.java, device))
    }

    /** Another device cannot read or overwrite an instance it does not own. */
    @Test fun foreignDeviceIsRejected() {
        val owner = device(); val intruder = device(); val instance = UUID.randomUUID().toString()
        inTx { service.report(owner, report(instance, 1, ClientModEntry(hashA, "a.jar"))) }
        val failure = assertFailsWith<ResponseStatusException> { inTx { service.report(intruder, report(instance, 9, ClientModEntry(hashB, "b.jar"))) } }
        assertEquals(404, failure.statusCode.value())
    }

    /** Path-like file names and malformed hashes are rejected before persistence. */
    @Test fun invalidEntriesAreRejected() {
        val device = device(); val instance = UUID.randomUUID().toString()
        assertFailsWith<IllegalArgumentException> { inTx { service.report(device, report(instance, 1, ClientModEntry(hashA, "../evil.jar"))) } }
        assertFailsWith<IllegalArgumentException> { inTx { service.report(device, report(instance, 1, ClientModEntry("short", "a.jar"))) } }
        assertFailsWith<IllegalArgumentException> { inTx { service.report(device, report(instance, 1, ClientModEntry(hashA, "a.jar"), ClientModEntry(hashA, "b.jar"))) } }
    }

    /** Unknown servers are rejected. */
    @Test fun unknownServerIsRejected() {
        val device = device()
        val bad = report(UUID.randomUUID().toString(), 1).copy(serverId = UUID.randomUUID().toString())
        assertFailsWith<ResponseStatusException> { inTx { service.report(device, bad) } }
    }
}
