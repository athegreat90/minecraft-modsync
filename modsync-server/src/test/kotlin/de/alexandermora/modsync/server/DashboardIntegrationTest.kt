package de.alexandermora.modsync.server

import de.alexandermora.modsync.domain.CoverageStatus
import de.alexandermora.modsync.domain.PublicationPolicy
import de.alexandermora.modsync.protocol.ClientInventoryAck
import de.alexandermora.modsync.protocol.ClientInventoryReport
import de.alexandermora.modsync.protocol.ClientModEntry
import de.alexandermora.modsync.protocol.Credentials
import de.alexandermora.modsync.protocol.Login
import de.alexandermora.modsync.protocol.PublicationPolicyRequest
import de.alexandermora.modsync.protocol.ServerCoverage
import de.alexandermora.modsync.protocol.UserRegistration
import de.alexandermora.modsync.protocol.wire
import de.alexandermora.modsync.server.auth.AuthService
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
import java.net.CookieManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies dashboard authentication, authorization, CSRF protection, output escaping and rendered pages end to end. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardIntegrationTest {
    companion object {
        @Container @JvmField
        val database = MariaDBContainer<Nothing>("mariadb:11.4.10")
    }

    private val state: Path = Files.createTempDirectory("modsync-ui-test")
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var base: String
    private lateinit var db: JdbcTemplate
    private val adminPassword = "fixture-only-bootstrap-password"
    private val serverId = UUID.randomUUID().toString()
    private val hostileId = UUID.randomUUID().toString()

    /** Starts the full application against MariaDB and seeds two servers. */
    @BeforeAll fun start() {
        val passwordFile = state.resolve("bootstrap-password")
        Files.writeString(passwordFile, adminPassword)
        context =
            SpringApplicationBuilder(ServerApplication::class.java).run(
                "--server.port=0",
                "--spring.datasource.url=${database.jdbcUrl}",
                "--spring.datasource.username=${database.username}",
                "--spring.datasource.password=${database.password}",
                "--modsync.bootstrap-password-file=$passwordFile"
            )
        base = "http://127.0.0.1:${requireNotNull((context as WebServerApplicationContext).webServer).port}"
        db = context.getBean(JdbcTemplate::class.java)
        db.update("INSERT INTO minecraft_servers (id, name, minecraft_version, loader) VALUES (?,?,?,?)", serverId, "Survival", "1.21.1", "neoforge")
        db.update("INSERT INTO minecraft_servers (id, name, minecraft_version, loader) VALUES (?,?,?,?)", hostileId, "<script>alert(1)</script>", "1.21.1", "neoforge")
    }

    /** Stops the application. */
    @AfterAll fun stop() {
        context.close()
        state.toFile().deleteRecursively()
    }

    /** Minimal browser: cookie jar, no automatic redirects. */
    private inner class Browser {
        private val http =
            HttpClient
                .newBuilder()
                .cookieHandler(CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()

        /** Sends a GET and returns the response. */
        fun get(path: String): HttpResponse<String> =
            http.send(HttpRequest.newBuilder(URI("$base$path")).build(), HttpResponse.BodyHandlers.ofString())

        /** Sends a form POST, adding the CSRF token when [csrf] is not null. */
        fun post(path: String, form: Map<String, String>, csrf: String?): HttpResponse<String> {
            val fields = form + (csrf?.let { mapOf("_csrf" to it) } ?: emptyMap())
            val body = fields.entries.joinToString("&") { "${URLEncoder.encode(it.key, Charsets.UTF_8)}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" }
            return http.send(
                HttpRequest
                    .newBuilder(URI("$base$path"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
        }

        /** Extracts the CSRF token rendered in a page. */
        fun csrf(page: String): String = Regex("name=\"_csrf\" value=\"([^\"]+)\"").find(page)?.groupValues?.get(1) ?: error("No CSRF token in page")

        /** Signs in and returns the login response. */
        fun login(user: String, password: String): HttpResponse<String> =
            post("/login", mapOf("username" to user, "password" to password), csrf(get("/login").body()))
    }

    /** Anonymous requests are redirected to the login page and the API stays bearer-protected. */
    @Test fun anonymousIsRedirected() {
        val browser = Browser()
        val response = browser.get("/ui")
        assertEquals(302, response.statusCode())
        assertTrue(
            response
                .headers()
                .firstValue("Location")
                .get()
                .endsWith("/login")
        )
        assertEquals(426, browser.get("/api/v1/servers").statusCode())
        assertEquals(200, browser.get("/ui/assets/ui.css").statusCode())
    }

    /** Wrong credentials are rejected; the administrator reaches the overview and server page. */
    @Test fun adminCanBrowse() {
        val browser = Browser()
        assertTrue(
            browser
                .login("admin", "wrong-password-value")
                .headers()
                .firstValue("Location")
                .get()
                .contains("error")
        )
        assertTrue(
            browser
                .login("admin", adminPassword)
                .headers()
                .firstValue("Location")
                .get()
                .endsWith("/ui")
        )
        val overview = browser.get("/ui")
        assertEquals(200, overview.statusCode())
        assertTrue(overview.body().contains("Survival"))
        assertEquals(200, browser.get("/ui/servers/$serverId").statusCode())
        assertEquals(404, browser.get("/ui/servers/${UUID.randomUUID()}").statusCode())
    }

    /** Server-controlled text is escaped and never rendered as markup. */
    @Test fun outputIsEscaped() {
        val browser = Browser()
        browser.login("admin", adminPassword)
        val body = browser.get("/ui").body()
        assertFalse(body.contains("<script>alert(1)</script>"))
        assertTrue(body.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(
            browser
                .get("/ui")
                .headers()
                .firstValue("Content-Security-Policy")
                .get()
                .contains("script-src 'none'")
        )
    }

    /** State changes require a CSRF token and are applied and audited with it. */
    @Test fun policyChangeRequiresCsrf() {
        val browser = Browser()
        browser.login("admin", adminPassword)
        val path = "/ui/servers/$serverId/publication-policy"
        assertEquals(403, browser.post(path, mapOf("policy" to "TRUSTED_AUTO"), null).statusCode())
        assertEquals("MANUAL", db.queryForObject("SELECT publication_policy FROM minecraft_servers WHERE id=?", String::class.java, serverId))
        val token = browser.csrf(browser.get("/ui/servers/$serverId").body())
        assertEquals(302, browser.post(path, mapOf("policy" to "TRUSTED_AUTO"), token).statusCode())
        assertEquals("TRUSTED_AUTO", db.queryForObject("SELECT publication_policy FROM minecraft_servers WHERE id=?", String::class.java, serverId))
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='publication-policy-set:TRUSTED_AUTO' AND target=?", Long::class.java, serverId))
    }

    /** Invalid enum input is rejected without changing state. */
    @Test fun invalidPolicyIsRejected() {
        val browser = Browser()
        browser.login("admin", adminPassword)
        val token = browser.csrf(browser.get("/ui/servers/$hostileId").body())
        assertEquals(400, browser.post("/ui/servers/$hostileId/publication-policy", mapOf("policy" to "ROOT"), token).statusCode())
    }

    /** Inventory, manifest, coverage matrix and history render from persisted data, with review forms for unapproved mods. */
    @Test fun serverPageRendersPersistedData() {
        val server = seedDataServer()
        val browser = Browser()
        browser.login("admin", adminPassword)
        val page = browser.get("/ui/servers/$server")
        assertEquals(200, page.statusCode())
        listOf("unreviewed-mod.jar", "UNREVIEWED", "client-mod.jar", "player-main", "MISSING_MODS", "1 unreviewed").forEach { assertTrue(page.body().contains(it), it) }
        assertTrue(page.body().contains("/ui/servers/$server/classify"))
    }

    /** Approving a classification and requesting re-evaluation work through CSRF-protected forms. */
    @Test fun classifyAndReevaluateForms() {
        val server = seedDataServer()
        val browser = Browser()
        browser.login("admin", adminPassword)
        val token = browser.csrf(browser.get("/ui/servers/$server").body())
        assertEquals(403, browser.post("/ui/servers/$server/classify", mapOf("sha512" to "c".repeat(128), "classification" to "SERVER_ONLY", "reason" to "x"), null).statusCode())
        assertEquals(302, browser.post("/ui/servers/$server/classify", mapOf("sha512" to "c".repeat(128), "classification" to "SERVER_ONLY", "reason" to "checked metadata"), token).statusCode())
        assertEquals("SERVER_ONLY", db.queryForObject("SELECT classification FROM classification_overrides WHERE server_id=?", String::class.java, server))
        assertEquals(302, browser.post("/ui/servers/$server/reevaluate", emptyMap(), token).statusCode())
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='publication-reevaluation-requested' AND target=?", Long::class.java, server))
        assertEquals(400, browser.post("/ui/servers/$server/classify", mapOf("sha512" to "bad", "classification" to "SERVER_ONLY", "reason" to "x"), token).statusCode())
    }

    /** Bearer-token API endpoints for inventory reporting, coverage and publication policy work end to end. */
    @Test fun apiEndpointsWork() {
        val server = seedDataServer()
        val http = HttpClient.newHttpClient()

        fun call(method: String, path: String, body: String?, token: String?): HttpResponse<String> {
            val builder =
                HttpRequest
                    .newBuilder(URI("$base/api/v1/$path"))
                    .header("Content-Type", "application/json")
                    .header("X-ModSync-Protocol", "1")
                    .header("X-ModSync-Version", "0.2.0")
                    .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            token?.let { builder.header("Authorization", "Bearer $it") }
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        val login = call("POST", "auth/login", wire.encodeToString(Login("admin", adminPassword, UUID.randomUUID().toString())), null)
        val token = wire.decodeFromString<Credentials>(login.body()).token
        assertEquals(200, call("PUT", "admin/servers/$server/publication-policy", wire.encodeToString(PublicationPolicyRequest(PublicationPolicy.REVIEW_UNKNOWN)), token).statusCode())
        assertEquals("[]", call("GET", "admin/servers/$server/publications", null, token).body())
        assertEquals(404, call("PUT", "admin/servers/${UUID.randomUUID()}/publication-policy", wire.encodeToString(PublicationPolicyRequest(PublicationPolicy.MANUAL)), token).statusCode())

        val instance = UUID.randomUUID().toString()

        fun report(sequence: Long) =
            wire.encodeToString(
                ClientInventoryReport(instance, "main", server, "1.21.1", "neoforge", sequence = sequence, mods = listOf(ClientModEntry("e".repeat(128), "client-mod.jar", "proj", "ver", true)))
            )
        assertTrue(wire.decodeFromString<ClientInventoryAck>(call("POST", "client/inventory", report(5), token).body()).accepted)
        assertFalse(wire.decodeFromString<ClientInventoryAck>(call("POST", "client/inventory", report(4), token).body()).accepted)
        assertEquals(401, call("POST", "client/inventory", report(6), null).statusCode())
        val coverage = wire.decodeFromString<ServerCoverage>(call("GET", "admin/servers/$server/coverage", null, token).body())
        assertTrue(coverage.rows.any { it.instanceId == instance && it.status == CoverageStatus.UP_TO_DATE })
    }

    /** Seeds a server with an unreviewed mod, a published manifest and one client instance; returns its id. */
    private fun seedDataServer(): String {
        val server = UUID.randomUUID().toString()
        val scan = UUID.randomUUID().toString()
        val hash = "c".repeat(128)
        val host = UUID.randomUUID().toString()
        val agent = UUID.randomUUID().toString()
        val user = UUID.randomUUID().toString()
        val device = UUID.randomUUID().toString()
        val instance = UUID.randomUUID().toString()
        val now = java.sql.Timestamp.from(java.time.Instant.now())
        db.update("INSERT INTO minecraft_servers (id, name, minecraft_version, loader, snapshot_id, inventory_at, revision) VALUES (?,?,?,?,?,?,?)", server, "Data", "1.21.1", "neoforge", null, null, 1)
        db.update("INSERT INTO minecraft_hosts (id, name) VALUES (?,?)", host, "h")
        db.update("INSERT INTO scanner_agents (id, host_id, created_at) VALUES (?,?,?)", agent, host, now)
        db.update("INSERT INTO inventory_snapshots (id, server_id, agent_id, generation, sequence, digest, entry_count, scanned_at, committed_at, payload) VALUES (?,?,?,?,?,?,?,?,?,?)", scan, server, agent, 0, 1, "d".repeat(128), 1, now, now, """{"serverId":"$server","scanId":"$scan","generation":0,"sequence":1,"scannedAt":"2026-01-01T00:00:00Z","digest":"${"d".repeat(128)}","count":1}""")
        db.update("INSERT INTO inventory_entries (snapshot_id, batch_index, payload) VALUES (?,?,?)", scan, 0, """{"index":0,"entries":[{"fileName":"unreviewed-mod.jar","sha512":"$hash","size":1}]}""")
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
        db.update("INSERT INTO client_instance_inventory (instance_id, server_id, minecraft_version, loader, sync_policy, activity, sequence, mod_count, verified_at) VALUES (?,?,?,?,?,?,?,?,?)", instance, server, "1.21.1", "neoforge", "MANUAL", "IDLE", 1, 0, now)
        return server
    }

    /** Non-administrator accounts cannot use the dashboard. */
    @Test fun clientAccountIsForbidden() {
        val name = "player" + UUID.randomUUID().toString().take(8)
        val password = "a-sufficiently-long-password"
        context.getBean(AuthService::class.java).createUser("test", UserRegistration(name, password))
        val browser = Browser()
        browser.login(name, password)
        assertEquals(403, browser.get("/ui").statusCode())
    }
}
