package de.alexandermora.modsync.server

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SESSION_BUDGET = 1
private const val OVERVIEW_BUDGET = 2
private const val USERS_BUDGET = 2
private const val SERVER_PAGE_BUDGET = 15

/**
 * Guards the number of database statements behind the slow admin API calls.
 *
 * Every statement is a network round trip to the database, which dominates latency when the database is not on the
 * application host. The statements are counted from MariaDB's own general log, so transaction control
 * (`SET autocommit`, `COMMIT`, ...) is included, and the budgets fail the build when a change adds round trips.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardRoundTripsIntegrationTest {
    companion object {
        @Container @JvmField
        val database = MariaDBContainer<Nothing>("mariadb:11.4.13")
    }

    private val state: Path = Files.createTempDirectory("modsync-roundtrips-test")
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var browser: TestBrowser
    private lateinit var log: Connection
    private val adminPassword = "fixture-only-bootstrap-password"
    private lateinit var serverId: String

    /** Starts the application, seeds one fully populated server, signs in and switches the general log to its table. */
    @BeforeAll fun start() {
        val passwordFile = state.resolve("bootstrap-password")
        Files.writeString(passwordFile, adminPassword)
        context =
            SpringApplicationBuilder(ServerApplication::class.java).run(
                "--server.port=0",
                "--server.servlet.session.cookie.secure=false",
                "--spring.datasource.url=${database.jdbcUrl}",
                "--spring.datasource.username=${database.username}",
                "--spring.datasource.password=${database.password}",
                "--modsync.bootstrap-password-file=$passwordFile"
            )
        val base = "http://127.0.0.1:${requireNotNull((context as WebServerApplicationContext).webServer).port}"
        serverId = seed(context.getBean(JdbcTemplate::class.java))
        browser = TestBrowser(base)
        assertEquals("/ui", browser.signIn("admin", adminPassword).location())
        log = DriverManager.getConnection(database.jdbcUrl, "root", database.password)
        log.createStatement().use {
            it.execute("SET GLOBAL log_output='TABLE'")
            it.execute("SET GLOBAL general_log=1")
        }
    }

    /** Stops the application and the logging connection. */
    @AfterAll fun stop() {
        log.close()
        context.close()
        state.toFile().deleteRecursively()
    }

    /** The server page reads the server once, the manifest once and each remaining section once, inside one transaction. */
    @Test fun serverPageStaysWithinItsBudget() = assertWithin("/ui/api/servers/$serverId", SERVER_PAGE_BUDGET)

    /** The session endpoint only reads the signed-in account. */
    @Test fun sessionStaysWithinItsBudget() = assertWithin("/ui/api/session", SESSION_BUDGET)

    /** The overview reads servers and policies in one transaction. */
    @Test fun overviewStaysWithinItsBudget() = assertWithin("/ui/api/overview", OVERVIEW_BUDGET)

    /** The first users page is one counted and one paged read. */
    @Test fun usersPageStaysWithinItsBudget() = assertWithin("/ui/api/users?page=0", USERS_BUDGET)

    private fun assertWithin(path: String, budget: Int) {
        browser.json("GET", path)
        val statements = statementsOf(path)
        println("ROUNDTRIPS $path = ${statements.size}\n" + statements.joinToString("\n") { "  $it" })
        assertTrue(statements.size <= budget, "$path sent ${statements.size} statements, budget $budget:\n" + statements.joinToString("\n"))
    }

    /** Requests [path] and returns the statements the application sent to the database for it, in order. */
    private fun statementsOf(path: String): List<String> {
        log.createStatement().use {
            it.execute("SET GLOBAL general_log=0")
            it.execute("TRUNCATE TABLE mysql.general_log")
            it.execute("SET GLOBAL general_log=1")
        }
        assertEquals(200, browser.json("GET", path).statusCode())
        return log.createStatement().use { statement ->
            statement
                .executeQuery(
                    "SELECT command_type, CAST(argument AS CHAR) FROM mysql.general_log " +
                        "WHERE thread_id <> CONNECTION_ID() AND command_type NOT IN ('Connect', 'Quit', 'Init DB')"
                ).use { rows ->
                    buildList {
                        while (rows.next()) add("${rows.getString(1)}: ${rows.getString(2)}")
                    }
                }
        }
    }

    /** Seeds a server with an inventory, a published manifest and one client instance; returns its id. */
    private fun seed(db: JdbcTemplate): String {
        val server = UUID.randomUUID().toString()
        val scan = UUID.randomUUID().toString()
        val host = UUID.randomUUID().toString()
        val agent = UUID.randomUUID().toString()
        val user = UUID.randomUUID().toString()
        val device = UUID.randomUUID().toString()
        val instance = UUID.randomUUID().toString()
        val now = java.sql.Timestamp.from(java.time.Instant.now())
        val digest = "d".repeat(128)
        db.update("INSERT INTO minecraft_servers (id, name, minecraft_version, loader, revision) VALUES (?,?,?,?,?)", server, "Data", "1.21.1", "neoforge", 1)
        db.update("INSERT INTO minecraft_hosts (id, name) VALUES (?,?)", host, "h")
        db.update("INSERT INTO scanner_agents (id, host_id, created_at) VALUES (?,?,?)", agent, host, now)
        db.update(
            "INSERT INTO inventory_snapshots (id, server_id, agent_id, generation, sequence, digest, entry_count, scanned_at, committed_at, payload) VALUES (?,?,?,?,?,?,?,?,?,?)",
            scan,
            server,
            agent,
            0,
            1,
            digest,
            1,
            now,
            now,
            """{"serverId":"$server","scanId":"$scan","generation":0,"sequence":1,"scannedAt":"2026-01-01T00:00:00Z","digest":"$digest","count":1}"""
        )
        db.update("INSERT INTO inventory_entries (snapshot_id, batch_index, payload) VALUES (?,?,?)", scan, 0, """{"index":0,"entries":[{"fileName":"unreviewed-mod.jar","sha512":"${"c".repeat(128)}","size":1}]}""")
        db.update("UPDATE minecraft_servers SET snapshot_id=?, inventory_at=? WHERE id=?", scan, now, server)
        db.update("INSERT INTO server_manifests (server_id, latest_revision) VALUES (?,?)", server, 1)
        db.update(
            "INSERT INTO manifest_revisions (server_id, revision, payload, created_at) VALUES (?,?,?,?)",
            server,
            1,
            """{"serverId":"$server","revision":1,"minecraftVersion":"1.21.1","loader":"neoforge","artifacts":[{"projectId":"proj","versionId":"ver","fileName":"client-mod.jar","sha512":"${"e".repeat(128)}","size":1,"minecraftVersions":["1.21.1"],"loaders":["neoforge"],"classification":"CLIENT_REQUIRED"}],"createdAt":"2026-01-01T00:00:00Z","inventoryDigest":"d"}""",
            now
        )
        db.update("INSERT INTO users (id, username, password_hash, role) VALUES (?,?,?,?)", user, "u-$user", "x", "CLIENT")
        db.update("INSERT INTO client_devices (id, user_id) VALUES (?,?)", device, user)
        db.update("INSERT INTO client_instances (id, device_id, name) VALUES (?,?,?)", instance, device, "player-main")
        db.update(
            "INSERT INTO client_instance_inventory (instance_id, server_id, minecraft_version, loader, sync_policy, activity, sequence, mod_count, verified_at) VALUES (?,?,?,?,?,?,?,?,?)",
            instance,
            server,
            "1.21.1",
            "neoforge",
            "MANUAL",
            "IDLE",
            1,
            0,
            now
        )
        return server
    }
}
