package de.alexandermora.modsync.server

import de.alexandermora.modsync.protocol.UserRegistration
import de.alexandermora.modsync.protocol.wire
import de.alexandermora.modsync.server.auth.AuthService
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drives the user-management pages end to end: authorization, validation, account lifecycle, credential revocation, escaping and maintenance. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserManagementIntegrationTest {
    companion object {
        @Container @JvmField
        val database = MariaDBContainer<Nothing>("mariadb:11.4.13")
    }

    private val state: Path = Files.createTempDirectory("modsync-users-test")
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var base: String
    private lateinit var db: JdbcTemplate
    private lateinit var api: ApiProbe
    private val adminPassword = "fixture-only-bootstrap-password"
    private val password = "player-password-1234"
    private val newPassword = "a-brand-new-password-5678"

    /** Starts the full application against MariaDB. */
    @BeforeAll fun start() {
        context = startApplication(maintenance = false)
        base = baseUrl(context)
        db = context.getBean(JdbcTemplate::class.java)
        api = ApiProbe(base)
    }

    /** Stops the application. */
    @AfterAll fun stop() {
        context.close()
        state.toFile().deleteRecursively()
    }

    /** Starts another application instance on the shared database, optionally in maintenance mode. */
    private fun startApplication(maintenance: Boolean): ConfigurableApplicationContext {
        val passwordFile = state.resolve("bootstrap-password")
        Files.writeString(passwordFile, adminPassword)
        return SpringApplicationBuilder(ServerApplication::class.java).run(
            "--server.port=0",
            "--server.servlet.session.cookie.secure=false",
            "--spring.datasource.url=${database.jdbcUrl}",
            "--spring.datasource.username=${database.username}",
            "--spring.datasource.password=${database.password}",
            "--modsync.bootstrap-password-file=$passwordFile",
            "--modsync.maintenance=$maintenance"
        )
    }

    /** Returns the base URL of a running application. */
    private fun baseUrl(app: ConfigurableApplicationContext) = "http://127.0.0.1:${requireNotNull((app as WebServerApplicationContext).webServer).port}"

    /** One shared administrator session: the dashboard limits sign-ins to 10 per minute per address, so tests must not sign in repeatedly. */
    private val admin: TestBrowser by lazy { adminBrowser() }

    /** Signs in as the bootstrap administrator. */
    private fun adminBrowser(root: String = base): TestBrowser = TestBrowser(root).also { assertEquals("/ui", it.signIn("admin", adminPassword).location()) }

    /** Creates a CLIENT account through the shared service and returns its login name. */
    private fun newClient(prefix: String = "player"): String {
        val name = "$prefix-${UUID.randomUUID().toString().take(8)}"
        context.getBean(AuthService::class.java).createUser("test", UserRegistration(name, password))
        return name
    }

    /** Looks up the id of an account by login name. */
    private fun idOf(name: String): String = requireNotNull(db.queryForObject("SELECT id FROM users WHERE username = ?", String::class.java, name))

    /** Calls an account action as [browser], with the CSRF header the Angular client sends. */
    private fun act(
        browser: TestBrowser,
        id: String,
        action: String,
        body: String = "{}"
    ) = browser.call("POST", "/ui/api/users/$id/$action", body)

    private fun createBody(
        username: String,
        pass: String = password,
        confirmation: String = pass
    ) = """{"username":"$username","password":"$pass","confirmation":"$confirmation"}"""

    private fun userIdOf(body: String): String =
        wire
            .parseToJsonElement(body)
            .jsonObject
            .getValue("userId")
            .jsonPrimitive.content

    /** Anonymous visitors are redirected or get 401, CLIENT sessions get 403 and neither can write, while the client API chain is unchanged. */
    @Test fun nonAdministratorsAreDenied() {
        val anonymous = TestBrowser(base)
        assertEquals("/ui/login", anonymous.get("/ui/users").location())
        assertEquals(401, anonymous.json("GET", "/ui/api/users").statusCode())
        assertEquals(403, anonymous.json("POST", "/ui/api/users", createBody("x")).statusCode())

        val name = newClient()
        val id = idOf(name)
        val client = TestBrowser(base)
        assertEquals("/ui", client.signIn(name, password).location())
        assertEquals(403, client.json("GET", "/ui/api/users").statusCode())
        assertEquals(403, client.json("GET", "/ui/api/users/$id").statusCode())
        assertEquals(403, act(client, id, "disable").statusCode())
        assertEquals(403, client.call("POST", "/ui/api/users", createBody("evil")).statusCode())
        assertTrue(db.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean::class.java, id) == true)
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM users WHERE username = 'evil'", Int::class.java))
    }

    /** Writes without a CSRF token are refused and change nothing. */
    @Test fun postsRequireCsrf() {
        val id = idOf(newClient())
        assertEquals(403, admin.json("POST", "/ui/api/users/$id/disable", "{}").statusCode())
        assertEquals(403, admin.json("POST", "/ui/api/users", createBody("nocsrf")).statusCode())
        assertTrue(db.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean::class.java, id) == true)
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM users WHERE username = 'nocsrf'", Int::class.java))
    }

    /** The CSRF token changes at login and a session exists only afterwards, so a pre-login session cannot be fixated. */
    @Test fun sessionIsRotatedOnLogin() {
        val browser = TestBrowser(base)
        val csrf = browser.csrfOf("/ui/login")
        val tokenBefore = browser.cookie("XSRF-TOKEN")
        assertNotNull(tokenBefore)
        assertNull(browser.sessionId())
        assertEquals("/ui", browser.post("/login", mapOf("username" to "admin", "password" to adminPassword), csrf).location())
        assertNotNull(browser.sessionId())
        browser.get("/ui")
        assertNotEquals(tokenBefore, browser.cookie("XSRF-TOKEN"))
    }

    /** Creating accounts validates input, reports specific reasons, never echoes passwords and audits the creation. */
    @Test fun createValidatesAndAudits() {
        fun submit(username: String, pass: String, confirmation: String) = admin.call("POST", "/ui/api/users", createBody(username, pass, confirmation))

        val invalidName = submit("bad name", password, password)
        assertEquals(422, invalidName.statusCode())
        assertTrue(invalidName.body().contains("Usernames use 1 to 100"))
        assertEquals(422, submit("short-pass", "short", "short").statusCode())
        val mismatch = submit("mismatch", password, "$password!")
        assertEquals(422, mismatch.statusCode())
        assertFalse(mismatch.body().contains(password))

        val created = submit("created-${UUID.randomUUID().toString().take(8)}", password, password)
        assertEquals(201, created.statusCode())
        val id = userIdOf(created.body())
        val username = db.queryForObject("SELECT username FROM users WHERE id = ?", String::class.java, id)!!
        assertEquals(200, api.login(username, password).first)

        val duplicate = submit(username, password, password)
        assertEquals(409, duplicate.statusCode())
        assertTrue(duplicate.body().contains("already in use"))
        val actor = db.queryForObject("SELECT actor FROM audit_events WHERE action = 'user-created' AND target = ?", String::class.java, id)
        assertEquals(idOf("admin"), actor)
    }

    /** The list is searchable and filterable, treats `_` literally and pages at 25 accounts. */
    @Test fun listSearchesFiltersAndPages() {
        val prefix = "pg${UUID.randomUUID().toString().take(4)}"
        repeat(27) { newClient(prefix) }
        newClient("under_score")
        newClient("underXscore")
        assertEquals(3, db.update("UPDATE users SET enabled = FALSE WHERE username LIKE ? ORDER BY username LIMIT 3", "$prefix%"))

        val first = admin.json("GET", "/ui/api/users?q=$prefix").body()
        assertTrue(first.contains("\"totalPages\":2") && first.contains("\"total\":27"), first)
        assertTrue(admin.json("GET", "/ui/api/users?q=$prefix&page=1").body().contains("\"page\":1"))
        assertTrue(admin.json("GET", "/ui/api/users?q=under_score").body().contains("under_score-"))
        assertFalse(admin.json("GET", "/ui/api/users?q=under_score").body().contains("underXscore-"))
        assertTrue(admin.json("GET", "/ui/api/users?role=ADMIN").body().contains("\"username\":\"admin\""))
        assertFalse(admin.json("GET", "/ui/api/users?role=CLIENT").body().contains("\"username\":\"admin\""))
        assertEquals(200, admin.json("GET", "/ui/api/users?role=ROOT&status=bogus&page=-1").statusCode())
        val disabled = admin.json("GET", "/ui/api/users?q=$prefix&status=disabled").body()
        assertEquals(3, "\"enabled\":false".toRegex().findAll(disabled).count())
        assertFalse(disabled.contains("\"enabled\":true"))
    }

    /** Disabling ends API access and live dashboard sessions; enabling restores sign-in. */
    @Test fun disableAndEnableLifecycle() {
        val name = newClient()
        val id = idOf(name)
        val session = requireNotNull(api.login(name, password).second)
        val clientBrowser = TestBrowser(base)
        assertEquals("/ui", clientBrowser.signIn(name, password).location())
        assertEquals(403, clientBrowser.json("GET", "/ui/api/overview").statusCode())

        assertEquals(200, act(admin, id, "disable").statusCode())
        assertTrue(admin.json("GET", "/ui/api/users/$id").body().contains("\"enabled\":false"))
        assertEquals(401, api.status("GET", "/api/v1/devices", session.token))
        assertEquals(401, api.refresh(requireNotNull(session.refreshToken)))
        assertEquals(401, api.login(name, password).first)
        assertEquals("/ui/login?error", TestBrowser(base).signIn(name, password).location())
        assertEquals("/ui/login?expired", clientBrowser.get("/ui").location())

        assertEquals(200, act(admin, id, "enable").statusCode())
        assertEquals(200, api.login(name, password).first)
        assertEquals(listOf("user-disabled", "user-enabled"), db.queryForList("SELECT action FROM audit_events WHERE target = ? AND action LIKE 'user-%' AND action <> 'user-created' ORDER BY id", String::class.java, id))
    }

    /** A password reset invalidates the old password, tokens and sessions and never reaches the audit log. */
    @Test fun resetPasswordRevokesEverything() {
        val name = newClient()
        val id = idOf(name)
        val session = requireNotNull(api.login(name, password).second)

        val rejected = act(admin, id, "password", """{"password":"short","confirmation":"short"}""")
        assertEquals(422, rejected.statusCode())
        assertTrue(rejected.body().contains("Passwords need 16 to 256 characters."))
        assertEquals(200, api.status("GET", "/api/v1/devices", session.token))

        assertEquals(200, act(admin, id, "password", """{"password":"$newPassword","confirmation":"$newPassword"}""").statusCode())
        assertEquals(401, api.status("GET", "/api/v1/devices", session.token))
        assertEquals(401, api.login(name, password).first)
        assertEquals(200, api.login(name, newPassword).first)
        val audit = db.queryForList("SELECT CONCAT(actor, action, target) FROM audit_events", String::class.java).joinToString()
        assertFalse(audit.contains(newPassword) || audit.contains(password))
    }

    /** Revoking sessions keeps the device usable; revoking devices needs confirmation and blocks the device for good. */
    @Test fun revokeSessionsAndDevices() {
        val name = newClient()
        val id = idOf(name)
        val device = UUID.randomUUID().toString()
        val session = requireNotNull(api.login(name, password, device).second)

        assertEquals(200, act(admin, id, "revoke-sessions").statusCode())
        assertEquals(401, api.status("GET", "/api/v1/devices", session.token))
        assertEquals(200, api.login(name, password, device).first)

        assertEquals(422, act(admin, id, "revoke-devices", """{"confirm":false}""").statusCode())
        assertEquals(200, api.login(name, password, device).first)

        assertEquals(200, act(admin, id, "revoke-devices", """{"confirm":true}""").statusCode())
        assertEquals(409, api.login(name, password, device).first)
        assertEquals(200, api.login(name, password).first)
    }

    /** Administrator accounts and unknown ids cannot be changed from the dashboard. */
    @Test fun administratorsAreReadOnly() {
        val adminId = idOf("admin")
        assertTrue(admin.json("GET", "/ui/api/users/$adminId").body().contains("\"manageable\":false"))
        val refused = act(admin, adminId, "disable")
        assertEquals(403, refused.statusCode())
        assertTrue(refused.body().contains("Only CLIENT accounts can be changed"))
        assertEquals(true, db.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean::class.java, adminId))
        assertEquals(404, admin.json("GET", "/ui/api/users/${UUID.randomUUID()}").statusCode())
        assertEquals(404, act(admin, UUID.randomUUID().toString(), "disable").statusCode())
    }

    /** Usernames are returned as JSON data with a strict type, and no response ever contains a password hash. */
    @Test fun outputIsDataAndHashesNeverLeak() {
        val hostile = "<img src=x onerror=alert(1)>"
        val id = UUID.randomUUID().toString()
        db.update("INSERT INTO users (id, username, password_hash, role) VALUES (?,?,?,'CLIENT')", id, hostile, "\$argon2id\$secret-hash")
        listOf("/ui/api/users?q=img", "/ui/api/users/$id").forEach {
            val response = admin.json("GET", it)
            assertTrue(
                response
                    .headers()
                    .firstValue("Content-Type")
                    .get()
                    .startsWith("application/json"),
                it
            )
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").get())
            assertTrue(response.body().contains(hostile), it)
            assertFalse(response.body().contains("argon2id"), it)
        }
        assertTrue(
            admin
                .json("GET", "/ui/api/users/$id")
                .headers()
                .firstValue("Content-Security-Policy")
                .get()
                .contains("script-src 'self'")
        )
    }

    /** Concurrent disable, enable and password resets of one account settle in a consistent state with credentials ended. */
    @Test fun concurrentChangesStayConsistent() {
        val name = newClient()
        val id = idOf(name)
        val session = requireNotNull(api.login(name, password).second)
        admin.call("GET", "/ui/api/users/$id")
        val pool = Executors.newFixedThreadPool(4)
        try {
            val calls =
                listOf(
                    Callable { act(admin, id, "disable").statusCode() },
                    Callable { act(admin, id, "password", """{"password":"$newPassword","confirmation":"$newPassword"}""").statusCode() },
                    Callable { act(admin, id, "revoke-sessions").statusCode() },
                    Callable { act(admin, id, "disable").statusCode() }
                )
            assertEquals(listOf(200, 200, 200, 200), pool.invokeAll(calls).map { it.get() })
        } finally {
            pool.shutdownNow()
        }
        assertEquals(false, db.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean::class.java, id))
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM credentials WHERE subject_id = ?", Int::class.java, id))
        assertEquals(401, api.status("GET", "/api/v1/devices", session.token))
        assertEquals(401, api.login(name, newPassword).first)
    }

    /** During maintenance the data stays readable, every mutation is refused with 409 and nothing changes. */
    @Test fun maintenanceRejectsMutations() {
        val name = newClient()
        val id = idOf(name)
        val maintenance = startApplication(maintenance = true)
        try {
            val root = baseUrl(maintenance)
            val admin = adminBrowser(root)
            assertEquals(200, admin.json("GET", "/ui/api/users").statusCode())
            assertEquals(409, act(admin, id, "disable").statusCode())
            assertEquals(409, act(admin, id, "password", """{"password":"$newPassword","confirmation":"$newPassword"}""").statusCode())
            assertEquals(409, act(admin, id, "revoke-devices", """{"confirm":true}""").statusCode())
            assertEquals(409, admin.call("POST", "/ui/api/users", createBody("maint")).statusCode())
            assertEquals(true, db.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean::class.java, id))
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM users WHERE username = 'maint'", Int::class.java))
        } finally {
            maintenance.close()
        }
    }
}
