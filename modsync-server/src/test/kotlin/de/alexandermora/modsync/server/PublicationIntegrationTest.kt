package de.alexandermora.modsync.server

import de.alexandermora.modsync.domain.Artifact
import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.Manifest
import de.alexandermora.modsync.domain.ModEntry
import de.alexandermora.modsync.domain.PublicationPolicy
import de.alexandermora.modsync.protocol.PublishRequest
import de.alexandermora.modsync.protocol.SnapshotBatch
import de.alexandermora.modsync.protocol.wire
import de.alexandermora.modsync.server.common.AuditEventRepository
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.inventory.ClassificationOverrideRepository
import de.alexandermora.modsync.server.inventory.InventoryEntryRepository
import de.alexandermora.modsync.server.inventory.InventoryService
import de.alexandermora.modsync.server.inventory.InventorySnapshotRepository
import de.alexandermora.modsync.server.inventory.MinecraftServerRepository
import de.alexandermora.modsync.server.publication.AutoPublicationService
import de.alexandermora.modsync.server.publication.ManifestGateway
import de.alexandermora.modsync.server.publication.PublicationPersistence
import kotlinx.serialization.encodeToString
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies automatic publication end to end against MariaDB with a fake manifest gateway and artifact resolver. */
@Testcontainers
class PublicationIntegrationTest {
    companion object { @Container @JvmField val database = MariaDBContainer<Nothing>("mariadb:11.4.10") }

    private lateinit var db: JdbcTemplate
    private lateinit var persistence: PublicationPersistence
    private lateinit var inventory: InventoryService
    private val hashA = "a".repeat(128)
    private val hashB = "b".repeat(128)
    private val known = mutableMapOf<String, Artifact>()

    /** Gateway recording publications and optionally failing preview. */
    private class FakeManifests(var latest: Manifest? = null, var previewFailure: ResponseStatusException? = null) : ManifestGateway {
        val published = mutableListOf<Manifest>()
        override fun latest(serverId: String) = latest
        override fun preview(serverId: String, request: PublishRequest): Manifest {
            previewFailure?.let { throw it }
            return Manifest(serverId, (latest?.revision ?: 0) + 1, "1.21.1", "neoforge", null, request.artifacts, "2026-01-01T00:00:00Z", "d")
        }
        override fun publish(actor: String, manifest: Manifest, scanId: String): Manifest { published += manifest; latest = manifest; return manifest }
    }

    /** Migrates the schema and builds real persistence and inventory services. */
    @BeforeEach fun prepare() {
        val source = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        Flyway.configure().dataSource(source).load().migrate()
        val emf = LocalContainerEntityManagerFactoryBean().apply {
            dataSource = source; setPackagesToScan("de.alexandermora.modsync.server"); jpaVendorAdapter = HibernateJpaVendorAdapter()
            setJpaPropertyMap(mapOf("hibernate.type.preferred_instant_jdbc_type" to "TIMESTAMP")); afterPropertiesSet()
        }
        val factory = JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf.`object`!!))
        db = JdbcTemplate(source)
        persistence = PublicationPersistence(db)
        inventory = InventoryService(factory.getRepository(MinecraftServerRepository::class.java), factory.getRepository(InventorySnapshotRepository::class.java),
            factory.getRepository(InventoryEntryRepository::class.java), factory.getRepository(ClassificationOverrideRepository::class.java),
            AuditService(factory.getRepository(AuditEventRepository::class.java)))
        known.clear()
        tx = TransactionTemplate(JpaTransactionManager(emf.`object`!!))
    }

    /** Builds an identified artifact. */
    private fun artifact(project: String, hash: String, classification: Classification = Classification.UNKNOWN) = Artifact(
        project, "v1", "$project.jar", hash, 1, "https://cdn.modrinth.com/data/$project/x.jar", setOf("1.21.1"), setOf("neoforge"), classification
    ).also { known[hash] = it }

    /** Creates a server with a committed snapshot of [entries] and returns (serverId, scanId). */
    private fun serverWithSnapshot(policy: PublicationPolicy, vararg entries: ModEntry): Pair<String, String> {
        val host = UUID.randomUUID().toString(); val agent = UUID.randomUUID().toString(); val server = UUID.randomUUID().toString(); val scan = UUID.randomUUID().toString()
        db.update("INSERT INTO minecraft_hosts (id, name) VALUES (?,?)", host, "h")
        db.update("INSERT INTO scanner_agents (id, host_id, created_at) VALUES (?,?,?)", agent, host, Timestamp.from(Instant.now()))
        db.update("INSERT INTO minecraft_servers (id, name, minecraft_version, loader, publication_policy) VALUES (?,?,?,?,?)", server, "s", "1.21.1", "neoforge", policy.name)
        db.update("INSERT INTO inventory_snapshots (id, server_id, agent_id, generation, sequence, digest, entry_count, scanned_at, committed_at, payload) VALUES (?,?,?,?,?,?,?,?,?,?)",
            scan, server, agent, 0, 1, "d".repeat(128), entries.size, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), "{}")
        db.update("INSERT INTO inventory_entries (snapshot_id, batch_index, payload) VALUES (?,?,?)", scan, 0, wire.encodeToString(SnapshotBatch(0, entries.toList())))
        return server to scan
    }

    private lateinit var tx: TransactionTemplate

    /** Evaluates inside a transaction, as the Spring proxy provides for approvals in production. */
    private fun evaluate(service: AutoPublicationService, scan: String): String? = tx.execute { service.evaluate(scan) }

    /** Creates the service under test with the supplied manifests. */
    private fun service(manifests: FakeManifests) = AutoPublicationService(inventory, manifests, { known[it] }, persistence)

    /** MANUAL servers are never evaluated or recorded. */
    @Test fun manualPolicyDoesNothing() {
        val (server, scan) = serverWithSnapshot(PublicationPolicy.MANUAL, ModEntry("p.jar", hashA, 1))
        assertNull(evaluate(service(FakeManifests()), scan))
        assertTrue(persistence.history(server, 10).isEmpty())
    }

    /** A trusted project's new version is approved by inheritance and published with provenance. */
    @Test fun trustedUpdateIsPublished() {
        val (server, scan) = serverWithSnapshot(PublicationPolicy.TRUSTED_AUTO, ModEntry("p.jar", hashB, 1))
        artifact("p", hashB)
        val manifests = FakeManifests(Manifest(server, 1, "1.21.1", "neoforge", null, listOf(artifact("p", hashA, Classification.CLIENT_REQUIRED)), "t", "d"))
        assertEquals("PUBLISHED", evaluate(service(manifests), scan))
        assertEquals(2L, manifests.published.single().revision)
        assertEquals(Classification.CLIENT_REQUIRED.name, inventory.overridesFor(server).getValue(hashB).classification)
        val record = persistence.history(server, 10).single()
        assertEquals("system:auto-publish", record.actor)
        assertEquals(2L, record.revision)
    }

    /** Unclassified new projects block publication and are queued for review. */
    @Test fun unknownProjectNeedsReview() {
        val (server, scan) = serverWithSnapshot(PublicationPolicy.TRUSTED_AUTO, ModEntry("new.jar", hashA, 1))
        artifact("new", hashA)
        val manifests = FakeManifests()
        assertEquals("REVIEW_REQUIRED", evaluate(service(manifests), scan))
        assertTrue(manifests.published.isEmpty())
        assertTrue(persistence.history(server, 10).single().detail.contains("new.jar"))
    }

    /** Re-evaluating unchanged client requirements never creates a duplicate revision. */
    @Test fun unchangedRequirementsAreSkipped() {
        val (server, scan) = serverWithSnapshot(PublicationPolicy.TRUSTED_AUTO, ModEntry("p.jar", hashA, 1))
        val published = artifact("p", hashA, Classification.CLIENT_REQUIRED)
        val manifests = FakeManifests(Manifest(server, 4, "1.21.1", "neoforge", null, listOf(published), "t", "d"))
        assertEquals("SKIPPED_NO_CHANGE", evaluate(service(manifests), scan))
        assertTrue(manifests.published.isEmpty())
    }

    /** A rejected preview (dependency or compatibility conflict) becomes a review item, not a publication. */
    @Test fun previewConflictNeedsReview() {
        val (server, scan) = serverWithSnapshot(PublicationPolicy.TRUSTED_AUTO, ModEntry("p.jar", hashB, 1))
        artifact("p", hashB)
        val manifests = FakeManifests(Manifest(server, 1, "1.21.1", "neoforge", null, listOf(artifact("p", hashA, Classification.CLIENT_REQUIRED)), "t", "d"),
            ResponseStatusException(HttpStatus.CONFLICT, "Manifest has unresolved dependencies"))
        assertEquals("REVIEW_REQUIRED", evaluate(service(manifests), scan))
        assertTrue(manifests.published.isEmpty())
        assertTrue(persistence.history(server, 10).single().detail.contains("unresolved dependencies"))
    }

    /** Policy round-trips, rejects unknown servers and history is newest first. */
    @Test fun policyAndHistoryPersistence() {
        val (server, _) = serverWithSnapshot(PublicationPolicy.MANUAL)
        assertEquals(PublicationPolicy.MANUAL, persistence.policy(server))
        assertTrue(persistence.setPolicy(server, PublicationPolicy.REVIEW_UNKNOWN))
        assertEquals(PublicationPolicy.REVIEW_UNKNOWN, persistence.policy(server))
        assertEquals(false, persistence.setPolicy(UUID.randomUUID().toString(), PublicationPolicy.TRUSTED_AUTO))
        persistence.record(server, UUID.randomUUID().toString(), PublicationPolicy.REVIEW_UNKNOWN, "FIRST", null, "a", "x")
        persistence.record(server, UUID.randomUUID().toString(), PublicationPolicy.REVIEW_UNKNOWN, "SECOND", 3, "b", "x")
        assertEquals(listOf("SECOND", "FIRST"), persistence.history(server, 10).map { it.outcome })
    }
}
