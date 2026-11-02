package de.alexandermora.modsync.server

import de.alexandermora.modsync.domain.inventoryDigest
import de.alexandermora.modsync.protocol.Enrollment
import de.alexandermora.modsync.protocol.SnapshotBegin
import de.alexandermora.modsync.protocol.Transfer
import de.alexandermora.modsync.server.agent.AgentEnrollmentRepository
import de.alexandermora.modsync.server.agent.AgentHeartbeatRepository
import de.alexandermora.modsync.server.agent.AgentService
import de.alexandermora.modsync.server.agent.MinecraftHostRepository
import de.alexandermora.modsync.server.agent.ScannerAgentRepository
import de.alexandermora.modsync.server.auth.CredentialRepository
import de.alexandermora.modsync.server.auth.CredentialService
import de.alexandermora.modsync.server.common.AuditEventRepository
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.inventory.ClassificationOverrideRepository
import de.alexandermora.modsync.server.inventory.InventoryEntryRepository
import de.alexandermora.modsync.server.inventory.InventoryService
import de.alexandermora.modsync.server.inventory.InventorySnapshotRepository
import de.alexandermora.modsync.server.inventory.MinecraftServerRepository
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class InventoryIntegrationTest {
    companion object { @Container @JvmField val database = MariaDBContainer<Nothing>("mariadb:11.4.10") }

    private lateinit var db: JdbcTemplate
    private lateinit var agents: AgentService
    private lateinit var inventory: InventoryService
    private lateinit var tx: TransactionTemplate

    @BeforeEach fun prepare() {
        val source = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        Flyway.configure().dataSource(source).load().migrate()
        val emf = LocalContainerEntityManagerFactoryBean().apply {
            dataSource = source; setPackagesToScan("de.alexandermora.modsync.server"); jpaVendorAdapter = HibernateJpaVendorAdapter()
            setJpaPropertyMap(mapOf("hibernate.type.preferred_instant_jdbc_type" to "TIMESTAMP")); afterPropertiesSet()
        }
        val factory = JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf.`object`!!))
        val audit = AuditService(factory.getRepository(AuditEventRepository::class.java))
        val credentials = factory.getRepository(CredentialRepository::class.java)
        val servers = factory.getRepository(MinecraftServerRepository::class.java)
        agents = AgentService(factory.getRepository(ScannerAgentRepository::class.java), factory.getRepository(MinecraftHostRepository::class.java),
            factory.getRepository(AgentEnrollmentRepository::class.java), factory.getRepository(AgentHeartbeatRepository::class.java),
            servers, credentials, CredentialService(credentials), audit)
        inventory = InventoryService(servers, factory.getRepository(InventorySnapshotRepository::class.java),
            factory.getRepository(InventoryEntryRepository::class.java), factory.getRepository(ClassificationOverrideRepository::class.java), audit)
        db = JdbcTemplate(source)
        tx = TransactionTemplate(JpaTransactionManager(emf.`object`!!))
    }

    private fun agent(): String {
        val id = UUID.randomUUID().toString()
        val token = tx.execute { agents.createEnrollment("test", 600) }!!
        tx.execute { agents.enroll(Enrollment(token.token, id)) }
        return id
    }

    private fun server(): String {
        val id = UUID.randomUUID().toString()
        db.update("INSERT INTO minecraft_servers(id,name,minecraft_version,loader) VALUES (?,'test','1.21.1','neoforge')", id)
        return id
    }

    private fun snapshot(id: String, generation: Long, sequence: Long) =
        SnapshotBegin(id, UUID.randomUUID().toString(), generation, sequence, "2026-01-01T00:00:00Z", inventoryDigest(emptyList()), 0)

    private fun acceptedSnapshot(server: String) = db.queryForObject("SELECT snapshot_id FROM minecraft_servers WHERE id=?", String::class.java, server)

    @Test fun `retry is idempotent and older snapshot cannot overwrite`() {
        val agent = agent(); val server = server(); val assignment = tx.execute { agents.transfer("test", server, Transfer(agent, "mods")) }!!
        val first = snapshot(server, assignment.generation, 1); tx.execute { inventory.begin(agent, first) }
        val ack = tx.execute { inventory.commit(agent, first.scanId) }!!
        assertEquals(0, java.time.Instant.parse(ack.committedAt).nano % 1000)
        assertEquals(ack, tx.execute { inventory.commit(agent, first.scanId) })
        val next = snapshot(server, assignment.generation, 3); tx.execute { inventory.begin(agent, next) }; tx.execute { inventory.commit(agent, next.scanId) }
        assertFails { tx.execute { inventory.begin(agent, snapshot(server, assignment.generation, 2)) } }
        assertEquals(next.scanId, acceptedSnapshot(server))
    }

    @Test fun `migration fences old agent and preserves server identity`() {
        val old = agent(); val replacement = agent(); val server = server()
        val assignment = tx.execute { agents.transfer("test", server, Transfer(old, "mods")) }!!
        val scan = snapshot(server, assignment.generation, 1); tx.execute { inventory.begin(old, scan) }; tx.execute { inventory.commit(old, scan.scanId) }
        val newer = tx.execute { agents.transfer("test", server, Transfer(replacement, "newmods")) }!!
        assertTrue(newer.generation > assignment.generation); assertFails { tx.execute { inventory.begin(old, snapshot(server, assignment.generation, 2)) } }
        assertEquals(scan.scanId, acceptedSnapshot(server))
        val replacementScan = snapshot(server, newer.generation, 1); tx.execute { inventory.begin(replacement, replacementScan) }; tx.execute { inventory.commit(replacement, replacementScan.scanId) }
        assertEquals(server, db.queryForObject("SELECT id FROM minecraft_servers WHERE id=?", String::class.java, server))
    }

    @Test fun `incomplete scan never changes authoritative inventory`() {
        val a = agent(); val s = server(); val assignment = tx.execute { agents.transfer("test", s, Transfer(a, "mods")) }!!
        val scan = snapshot(s, assignment.generation, 1).copy(count = 1); tx.execute { inventory.begin(a, scan) }
        assertFails { tx.execute { inventory.commit(a, scan.scanId) } }
        assertNull(acceptedSnapshot(s))
    }

    @Test fun `enrollment is single use`() {
        val token = tx.execute { agents.createEnrollment("test", 600) }!!
        tx.execute { agents.enroll(Enrollment(token.token, UUID.randomUUID().toString())) }
        assertFails { tx.execute { agents.enroll(Enrollment(token.token, UUID.randomUUID().toString())) } }
    }
}
