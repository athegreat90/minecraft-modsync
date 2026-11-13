package de.alexandermora.modsync.server

import de.alexandermora.modsync.protocol.UserRegistration
import de.alexandermora.modsync.server.auth.AuthService
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
import kotlin.test.assertTrue

/** Drives the user-management pages end to end: authorization, validation, account lifecycle, credential revocation, escaping and maintenance. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserManagementIntegrationTest {
    companion object {
        @Container @JvmField
        val database = MariaDBContainer<Nothing>("mariadb:11.4.10")
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

    /** Posts a form to an account action, using a fresh CSRF token from the account page. */
    private fun act(
        browser: TestBrowser,
        id: String,
        action: String,
        form: Map<String, String> = emptyMap()
    ) = browser.post("/ui/users/$id/$action", form, browser.csrfOf("/ui/users/$id"))

    /** Anonymous visitors are redirected, CLIENT sessions get 403 and neither can post, while the API chain is unchanged. */
    @Test fun nonAdministratorsAreDenied() {
        val anonymous = TestBrowser(base)
        assertEquals("/login", anonymous.get("/ui/users").location())
        assertEquals(403, anonymous.post("/ui/users", mapOf("username" to "x", "password" to password, "confirmation" to password), null).statusCode())

        val name = newClient()
        val id = idOf(name)
        val client = TestBrowser(base)
        assertEquals("/ui", client.signIn(name, password).location())
        assertEquals(403, client.get("/ui/users").statusCode())
        assertEquals(403, client.get("/ui/users/$id").statusCode())
        val csrf = client.csrfOf("/login")
        assertEquals(403, client.post("/ui/users/$id/disable", emptyMap(), csrf).statusCode())
        assertEquals(403, client.post("/ui/users", mapOf("username" to "evil", "password" to password, "confirmation" to password), csrf).statusCode())
        assertTrue(db.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean::class.java, id) == true)
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM users WHERE username = 'evil'", Int::class.java))
    }

    /** Posts without a CSRF token are refused and change nothing. */
    @Test fun postsRequireCsrf() {
        val id = idOf(newClient())
        assertEquals(403, admin.post("/ui/users/$id/disable", emptyMap(), null).statusCode())
        assertEquals(403, admin.post("/ui/users", mapOf("username" to "nocsrf", "password" to password, "confirmation" to password), null).statusCode())
        assertTrue(db.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean::class.java, id) == true)
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM users WHERE username = 'nocsrf'", Int::class.java))
    }

    /** The session id changes at login, so a pre-login session cannot be fixated. */
    @Test fun sessionIsRotatedOnLogin() {
        val browser = TestBrowser(base)
        val csrf = browser.csrfOf("/login")
        val before = browser.sessionId()
        assertNotNull(before)
        assertEquals("/ui", browser.post("/login", mapOf("username" to "admin", "password" to adminPassword), csrf).location())
        assertNotEquals(before, browser.sessionId())
    }

    /** Creating accounts validates input, reports specific reasons, never echoes passwords and audits the creation. */
    @Test fun createValidatesAndAudits() {
        val form = admin.get("/ui/users/new")
        assertEquals(200, form.statusCode())

        fun submit(username: String, pass: String, confirmation: String) = admin.post("/ui/users", mapOf("username" to username, "password" to pass, "confirmation" to confirmation), admin.csrf(form.body()))

        val invalidName = submit("bad name", password, password)
        assertEquals(422, invalidName.statusCode())
        assertTrue(invalidName.body().contains("Usernames use 1 to 100"))
        assertEquals(422, submit("short-pass", "short", "short").statusCode())
        val mismatch = submit("mismatch", password, "$password!")
        assertEquals(422, mismatch.statusCode())
        assertFalse(mismatch.body().contains(password))

        val created = submit("created-${UUID.randomUUID().toString().take(8)}", password, password)
        assertEquals(302, created.statusCode())
        val page = admin.get(created.location())
        assertTrue(page.body().contains("Account created."))
        val id = created.location().substringAfterLast('/')
        assertEquals(200, api.login(db.queryForObject("SELECT username FROM users WHERE id = ?", String::class.java, id)!!, password).first)

        val duplicate = submit(db.queryForObject("SELECT username FROM users WHERE id = ?", String::class.java, id)!!, password, password)
        assertEquals(422, duplicate.statusCode())
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
        db.update("UPDATE users SET enabled = FALSE WHERE username LIKE ?", "$prefix%0")

        val first = admin.get("/ui/users?q=$prefix").body()
        assertTrue(first.contains("Page 1 of 2 (27 accounts)"))
        assertTrue(admin.get("/ui/users?q=$prefix&page=1").body().contains("Page 2 of 2"))
        assertTrue(admin.get("/ui/users?q=under_score").body().contains("under_score-"))
        assertFalse(admin.get("/ui/users?q=under_score").body().contains("underXscore-"))
        assertTrue(admin.get("/ui/users?role=ADMIN").body().contains(">admin<"))
        assertFalse(admin.get("/ui/users?role=CLIENT").body().contains(">admin<"))
        assertEquals(200, admin.get("/ui/users?role=ROOT&status=bogus&page=-1").statusCode())
        val disabled = admin.get("/ui/users?q=$prefix&status=disabled").body()
        assertTrue(disabled.contains("badge bad"))
        assertFalse(disabled.contains("badge ok"))
    }

    /** Disabling ends API access and live dashboard sessions; enabling restores sign-in. */
    @Test fun disableAndEnableLifecycle() {
        val name = newClient()
        val id = idOf(name)
        val session = requireNotNull(api.login(name, password).second)
        val clientBrowser = TestBrowser(base)
        assertEquals("/ui", clientBrowser.signIn(name, password).location())
        assertEquals(403, clientBrowser.get("/ui").statusCode())

        val disabled = act(admin, id, "disable")
        assertEquals("/ui/users/$id", disabled.location())
        assertTrue(admin.get("/ui/users/$id").body().contains("badge bad"))
        assertEquals(401, api.status("GET", "/api/v1/devices", session.token))
        assertEquals(401, api.refresh(requireNotNull(session.refreshToken)))
        assertEquals(401, api.login(name, password).first)
        assertEquals("/login?error", TestBrowser(base).signIn(name, password).location())
        assertEquals("/login?expired", clientBrowser.get("/ui").location())

        assertEquals("/ui/users/$id", act(admin, id, "enable").location())
        assertEquals(200, api.login(name, password).first)
        assertEquals(listOf("user-disabled", "user-enabled"), db.queryForList("SELECT action FROM audit_events WHERE target = ? AND action LIKE 'user-%' AND action <> 'user-created' ORDER BY id", String::class.java, id))
    }

    /** A password reset invalidates the old password, tokens and sessions and never reaches the audit log. */
    @Test fun resetPasswordRevokesEverything() {
        val name = newClient()
        val id = idOf(name)
        val session = requireNotNull(api.login(name, password).second)

        assertEquals("/ui/users/$id", act(admin, id, "password", mapOf("password" to "short", "confirmation" to "short")).location())
        assertTrue(admin.get("/ui/users/$id").body().contains("Passwords need 16 to 256 characters."))
        assertEquals(200, api.status("GET", "/api/v1/devices", session.token))

        act(admin, id, "password", mapOf("password" to newPassword, "confirmation" to newPassword))
        assertTrue(admin.get("/ui/users/$id").body().contains("Password changed."))
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

        act(admin, id, "revoke-sessions")
        assertEquals(401, api.status("GET", "/api/v1/devices", session.token))
        assertEquals(200, api.login(name, password, device).first)

        act(admin, id, "revoke-devices")
        assertTrue(admin.get("/ui/users/$id").body().contains("Tick the confirmation box"))
        assertEquals(200, api.login(name, password, device).first)

        act(admin, id, "revoke-devices", mapOf("confirm" to "yes"))
        assertEquals(409, api.login(name, password, device).first)
        assertEquals(200, api.login(name, password).first)
    }

    /** Administrator accounts and unknown ids cannot be changed from the dashboard. */
    @Test fun administratorsAreReadOnly() {
        val adminId = idOf("admin")
        assertTrue(admin.get("/ui/users/$adminId").body().contains("read-only"))
        val csrf = admin.csrfOf("/ui/users/$adminId")
        assertEquals("/ui/users/$adminId", admin.post("/ui/users/$adminId/disable", emptyMap(), csrf).location())
        assertTrue(admin.get("/ui/users/$adminId").body().contains("Only CLIENT accounts can be changed"))
        assertEquals(true, db.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean::class.java, adminId))
        assertEquals(404, admin.get("/ui/users/${UUID.randomUUID()}").statusCode())
        assertEquals(404, admin.post("/ui/users/${UUID.randomUUID()}/disable", emptyMap(), csrf).statusCode())
    }

    /** Usernames are escaped, and no page ever contains a password hash. */
    @Test fun outputIsEscapedAndHashesNeverLeak() {
        val hostile = "<img src=x onerror=alert(1)>"
        val id = UUID.randomUUID().toString()
        db.update("INSERT INTO users (id, username, password_hash, role) VALUES (?,?,?,'CLIENT')", id, hostile, "\$argon2id\$secret-hash")
        listOf("/ui/users?q=img", "/ui/users/$id").forEach {
            val body = admin.get(it).body()
            assertTrue(body.contains("&lt;img src=x onerror=alert(1)&gt;"), it)
            assertFalse(body.contains("<img src=x"), it)
            assertFalse(body.contains("argon2id"), it)
        }
        assertTrue(
            admin
                .get("/ui/users/$id")
                .headers()
                .firstValue("Content-Security-Policy")
                .get()
                .contains("script-src 'none'")
        )
    }

    /** Concurrent disable, enable and password resets of one account settle in a consistent state with credentials ended. */
    @Test fun concurrentChangesStayConsistent() {
        val name = newClient()
        val id = idOf(name)
        val session = requireNotNull(api.login(name, password).second)
        val csrf = admin.csrfOf("/ui/users/$id")
        val pool = Executors.newFixedThreadPool(4)
        try {
            val calls =
                listOf(
                    Callable { admin.post("/ui/users/$id/disable", emptyMap(), csrf).statusCode() },
                    Callable { admin.post("/ui/users/$id/password", mapOf("password" to newPassword, "confirmation" to newPassword), csrf).statusCode() },
                    Callable { admin.post("/ui/users/$id/revoke-sessions", emptyMap(), csrf).statusCode() },
                    Callable { admin.post("/ui/users/$id/disable", emptyMap(), csrf).statusCode() }
                )
            assertEquals(listOf(302, 302, 302, 302), pool.invokeAll(calls).map { it.get() })
        } finally {
            pool.shutdownNow()
        }
        assertEquals(false, db.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean::class.java, id))
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM credentials WHERE subject_id = ?", Int::class.java, id))
        assertEquals(401, api.status("GET", "/api/v1/devices", session.token))
        assertEquals(401, api.login(name, newPassword).first)
    }

    /** During maintenance the pages stay readable, every mutation is refused with 409 and nothing changes. */
    @Test fun maintenanceRejectsMutations() {
        val name = newClient()
        val id = idOf(name)
        val maintenance = startApplication(maintenance = true)
        try {
            val root = baseUrl(maintenance)
            val admin = adminBrowser(root)
            assertEquals(200, admin.get("/ui/users").statusCode())
            val csrf = admin.csrfOf("/ui/users/$id")
            assertEquals(409, admin.post("/ui/users/$id/disable", emptyMap(), csrf).statusCode())
            assertEquals(409, admin.post("/ui/users/$id/password", mapOf("password" to newPassword, "confirmation" to newPassword), csrf).statusCode())
            assertEquals(409, admin.post("/ui/users/$id/revoke-devices", mapOf("confirm" to "yes"), csrf).statusCode())
            assertEquals(409, admin.post("/ui/users", mapOf("username" to "maint", "password" to password, "confirmation" to password), csrf).statusCode())
            assertEquals(true, db.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean::class.java, id))
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM users WHERE username = 'maint'", Int::class.java))
        } finally {
            maintenance.close()
        }
    }
}
