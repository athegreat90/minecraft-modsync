package net.modsync
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.flywaydb.core.Flyway
import java.util.UUID
import kotlin.test.*

@Testcontainers
class InventoryIntegrationTest {
    companion object { @Container @JvmField val database=MariaDBContainer<Nothing>("mariadb:11.4.10") }
    private lateinit var store: Store
    private lateinit var tx: TransactionTemplate
    @BeforeEach fun prepare() {
        val source=DriverManagerDataSource(database.jdbcUrl,database.username,database.password)
        Flyway.configure().dataSource(source).load().migrate()
        store=Store(JdbcTemplate(source),Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()); tx=TransactionTemplate(DataSourceTransactionManager(source))
    }
    private fun agent(): String { val id=UUID.randomUUID().toString(); val token=tx.execute { store.enrollment("test",600) }!!; tx.execute { store.enroll(Enrollment(token.token,id)) }; return id }
    private fun server(): String { val id=UUID.randomUUID().toString(); store.db.update("INSERT INTO minecraft_servers(id,name,minecraft_version,loader) VALUES (?,'test','1.21.1','neoforge')",id); return id }
    private fun snapshot(id: String, generation: Long, sequence: Long) = SnapshotBegin(id,UUID.randomUUID().toString(),generation,sequence,"2026-01-01T00:00:00Z",inventoryDigest(emptyList()),0)
    @Test fun `retry is idempotent and older snapshot cannot overwrite`() {
        val agent=agent(); val server=server(); val assignment=tx.execute { store.assign("test",server,Transfer(agent,"mods")) }!!
        val first=snapshot(server,assignment.generation,1); tx.execute { store.begin(agent,first) }; val ack=tx.execute { store.commit(agent,first.scanId) }!!
        assertEquals(0,java.time.Instant.parse(ack.committedAt).nano % 1000)
        assertEquals(ack,tx.execute { store.commit(agent,first.scanId) })
        val next=snapshot(server,assignment.generation,3); tx.execute { store.begin(agent,next) }; tx.execute { store.commit(agent,next.scanId) }
        assertFails { tx.execute { store.begin(agent,snapshot(server,assignment.generation,2)) } }
        assertEquals(next.scanId,store.one("SELECT snapshot_id FROM minecraft_servers WHERE id=?",server)["snapshot_id"])
    }
    @Test fun `migration fences old agent and preserves server identity`() {
        val old=agent(); val replacement=agent(); val server=server(); val assignment=tx.execute { store.assign("test",server,Transfer(old,"mods")) }!!
        val scan=snapshot(server,assignment.generation,1); tx.execute { store.begin(old,scan) }; tx.execute { store.commit(old,scan.scanId) }
        val newer=tx.execute { store.assign("test",server,Transfer(replacement,"newmods")) }!!
        assertTrue(newer.generation>assignment.generation); assertFails { tx.execute { store.begin(old,snapshot(server,assignment.generation,2)) } }
        assertEquals(scan.scanId,store.one("SELECT snapshot_id FROM minecraft_servers WHERE id=?",server)["snapshot_id"])
        val replacementScan=snapshot(server,newer.generation,1); tx.execute { store.begin(replacement,replacementScan) }; tx.execute { store.commit(replacement,replacementScan.scanId) }
        assertEquals(server,store.one("SELECT id FROM minecraft_servers WHERE id=?",server)["id"])
    }
    @Test fun `incomplete scan never changes authoritative inventory`() {
        val a=agent(); val s=server(); val assignment=tx.execute { store.assign("test",s,Transfer(a,"mods")) }!!
        val scan=snapshot(s,assignment.generation,1).copy(count=1); tx.execute { store.begin(a,scan) }; assertFails { tx.execute { store.commit(a,scan.scanId) } }
        assertNull(store.one("SELECT snapshot_id FROM minecraft_servers WHERE id=?",s)["snapshot_id"])
    }
    @Test fun `enrollment is single use`() { val token=tx.execute { store.enrollment("test",600) }!!; tx.execute { store.enroll(Enrollment(token.token,UUID.randomUUID().toString())) }; assertFails { tx.execute { store.enroll(Enrollment(token.token,UUID.randomUUID().toString())) } } }
}
