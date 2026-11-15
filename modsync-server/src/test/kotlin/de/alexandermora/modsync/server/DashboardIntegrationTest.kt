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
import de.alexandermora.modsync.server.common.ForwardedHeadersWarningFilter
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
        val database = MariaDBContainer<Nothing>("mariadb:11.4.13")
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
                "--server.servlet.session.cookie.secure=false",
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

    private fun admin(): TestBrowser = TestBrowser(base).also { assertEquals("/ui", it.signIn("admin", adminPassword).location()) }

    /** Anonymous browsers go to the login page, script clients get 401, the application shell is public and the client API stays bearer-protected. */
    @Test fun anonymousIsRedirected() {
        val browser = TestBrowser(base)
        assertEquals("/ui/login", browser.get("/ui").location())
        assertEquals(401, browser.json("GET", "/ui/api/overview").statusCode())
        assertEquals(200, browser.get("/ui/index.html").statusCode())
        assertEquals(426, browser.get("/api/v1/servers").statusCode())
    }

    /** Wrong credentials are rejected; the administrator reaches the overview and server data and the client-side routes. */
    @Test fun adminCanBrowse() {
        val browser = TestBrowser(base)
        assertEquals("/ui/login?error", browser.signIn("admin", "wrong-password-value").location())
        assertEquals("/ui", browser.signIn("admin", adminPassword).location())
        val overview = browser.json("GET", "/ui/api/overview")
        assertEquals(200, overview.statusCode())
        assertTrue(overview.body().contains("Survival"))
        assertEquals(200, browser.json("GET", "/ui/api/servers/$serverId").statusCode())
        assertEquals(404, browser.json("GET", "/ui/api/servers/${UUID.randomUUID()}").statusCode())
        assertEquals(200, browser.get("/ui").statusCode())
        assertEquals(200, browser.get("/ui/servers/$serverId").statusCode())
    }

    /** Server-controlled text is returned as JSON data with a strict type and security headers, never as markup. */
    @Test fun outputIsDataNotMarkup() {
        val response = admin().json("GET", "/ui/api/overview")
        assertTrue(
            response
                .headers()
                .firstValue("Content-Type")
                .get()
                .startsWith("application/json")
        )
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").get())
        assertTrue(response.body().contains("\"name\":\"<script>alert(1)</script>\""))
        assertTrue(
            response
                .headers()
                .firstValue("Content-Security-Policy")
                .get()
                .contains("script-src 'self'")
        )
    }

    /** State changes require a CSRF token and are applied and audited with it. */
    @Test fun policyChangeRequiresCsrf() {
        val browser = admin()
        val path = "/ui/api/servers/$serverId/publication-policy"
        val body = """{"policy":"TRUSTED_AUTO"}"""
        assertEquals(403, browser.json("PUT", path, body).statusCode())
        assertEquals("MANUAL", db.queryForObject("SELECT publication_policy FROM minecraft_servers WHERE id=?", String::class.java, serverId))
        assertEquals(200, browser.call("PUT", path, body).statusCode())
        assertEquals("TRUSTED_AUTO", db.queryForObject("SELECT publication_policy FROM minecraft_servers WHERE id=?", String::class.java, serverId))
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='publication-policy-set:TRUSTED_AUTO' AND target=?", Long::class.java, serverId))
    }

    /** Invalid enum input is rejected without changing state. */
    @Test fun invalidPolicyIsRejected() {
        assertEquals(400, admin().call("PUT", "/ui/api/servers/$hostileId/publication-policy", """{"policy":"ROOT"}""").statusCode())
    }

    /** Inventory, manifest, coverage matrix and history are returned from persisted data, with the unapproved mods marked. */
    @Test fun serverPageReturnsPersistedData() {
        val server = seedDataServer()
        val page = admin().json("GET", "/ui/api/servers/$server")
        assertEquals(200, page.statusCode())
        listOf("unreviewed-mod.jar", "client-mod.jar", "player-main", "MISSING_MODS", "\"unreviewed\":1").forEach { assertTrue(page.body().contains(it), it) }
    }

    /** Approving a classification and requesting re-evaluation work through CSRF-protected calls. */
    @Test fun classifyAndReevaluate() {
        val server = seedDataServer()
        val browser = admin()
        val path = "/ui/api/servers/$server/classification"
        val hash = "c".repeat(128)

        fun body(sha: String, reason: String) = """{"sha512":"$sha","classification":"SERVER_ONLY","reason":"$reason"}"""
        assertEquals(403, browser.json("PUT", path, body(hash, "x")).statusCode())
        assertEquals(200, browser.call("PUT", path, body(hash, "checked metadata")).statusCode())
        assertEquals("SERVER_ONLY", db.queryForObject("SELECT classification FROM classification_overrides WHERE server_id=?", String::class.java, server))
        assertEquals(200, browser.call("POST", "/ui/api/servers/$server/reevaluate").statusCode())
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='publication-reevaluation-requested' AND target=?", Long::class.java, server))
        assertEquals(400, browser.call("PUT", path, body("bad", "x")).statusCode())
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

    /** Non-administrator accounts cannot use the administration API. */
    @Test fun clientAccountIsForbidden() {
        val name = "player" + UUID.randomUUID().toString().take(8)
        val password = "a-sufficiently-long-password"
        context.getBean(AuthService::class.java).createUser("test", UserRegistration(name, password))
        val browser = TestBrowser(base)
        browser.signIn(name, password)
        assertEquals(403, browser.json("GET", "/ui/api/overview").statusCode())
    }

    /** A signed-in non-administrator is sent to the login page with a reason instead of a raw 403 page, while the API keeps its plain 403. */
    @Test fun clientPageRequestIsRedirectedWithAReason() {
        val name = "player" + UUID.randomUUID().toString().take(8)
        val password = "a-sufficiently-long-password"
        context.getBean(AuthService::class.java).createUser("test", UserRegistration(name, password))
        val browser = TestBrowser(base)
        browser.signIn(name, password)
        val page = browser.get("/ui")
        assertEquals(302, page.statusCode())
        assertEquals("/ui/login?denied", page.headers().firstValue("Location").get())
        assertEquals("/ui/login?denied", browser.get("/ui/servers/$serverId").location())
        assertEquals(403, browser.json("GET", "/ui/api/overview").statusCode())
        assertEquals(200, browser.get("/ui/login").statusCode())
    }

    /** A login form with a stale or missing CSRF token goes back to the login page with a reason instead of a bare 403, and signs nobody in. */
    @Test fun staleCsrfTokenOnLoginIsRedirected() {
        val browser = TestBrowser(base)
        browser.csrfOf("/ui/login")
        val credentials = mapOf("username" to "admin", "password" to adminPassword)
        val stale = browser.post("/login", credentials, "stale-token")
        assertEquals(302, stale.statusCode())
        assertEquals("/ui/login?csrf", stale.headers().firstValue("Location").get())
        assertEquals("/ui/login?csrf", TestBrowser(base).post("/login", credentials, null).location())
        assertEquals("/ui/login", browser.get("/ui").location())
    }

    /** The forwarded-headers warning is wired into the running application. */
    @Test fun forwardedHeadersWarningIsRegistered() {
        assertEquals(1, context.getBeansOfType(ForwardedHeadersWarningFilter::class.java).size)
    }

    /** A sign-out form with a stale token is sent to the login page with a reason and does not end the session. */
    @Test fun staleCsrfTokenOnLogoutKeepsTheSession() {
        val browser = admin()
        assertEquals("/ui/login?csrf", browser.post("/logout", emptyMap(), "stale-token").location())
        assertEquals(200, browser.get("/ui").statusCode())
    }

    /** The bare host and the root favicon redirect to the dashboard with relative targets, and every other unknown root path stays denied. */
    @Test fun rootRedirectsToTheDashboard() {
        val browser = TestBrowser(base)
        val root = browser.get("/")
        assertEquals(302, root.statusCode())
        assertEquals("/ui/", root.headers().firstValue("Location").get())
        val favicon = browser.get("/favicon.ico")
        assertEquals(302, favicon.statusCode())
        assertEquals("/ui/favicon.ico", favicon.headers().firstValue("Location").get())
        assertEquals(401, browser.get("/not-a-route").statusCode())
    }
}
