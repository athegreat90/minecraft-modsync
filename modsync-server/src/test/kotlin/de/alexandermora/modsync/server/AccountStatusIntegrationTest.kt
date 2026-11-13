package de.alexandermora.modsync.server

import de.alexandermora.modsync.protocol.Credentials
import de.alexandermora.modsync.protocol.Login
import de.alexandermora.modsync.protocol.RefreshRequest
import de.alexandermora.modsync.protocol.UserRegistration
import de.alexandermora.modsync.protocol.wire
import de.alexandermora.modsync.server.auth.AuthService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
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

/** Verifies that a disabled account is refused by every authentication path and that re-enabling restores access. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountStatusIntegrationTest {
    companion object {
        @Container @JvmField
        val database = MariaDBContainer<Nothing>("mariadb:11.4.10")
    }

    private val state: Path = Files.createTempDirectory("modsync-status-test")
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var base: String
    private lateinit var db: JdbcTemplate
    private val http = HttpClient.newHttpClient()
    private val password = "player-password-1234"

    /** Starts the full application against MariaDB. */
    @BeforeAll fun start() {
        val passwordFile = state.resolve("bootstrap-password")
        Files.writeString(passwordFile, "fixture-only-bootstrap-password")
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
    }

    /** Stops the application. */
    @AfterAll fun stop() {
        context.close()
        state.toFile().deleteRecursively()
    }

    /** Sends an API request with the protocol headers and returns the status code. */
    private fun api(method: String, path: String, token: String? = null, body: String? = null): Int {
        val builder =
            HttpRequest
                .newBuilder(URI("$base$path"))
                .header("X-ModSync-Protocol", "1")
                .header("X-ModSync-Version", "0.3.1")
                .header("Content-Type", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        return http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    /** Logs in through the API and returns the issued credentials, or null when the login is refused. */
    private fun login(user: String, pass: String): Credentials? {
        val body = wire.encodeToString(Login.serializer(), Login(user, pass, UUID.randomUUID().toString()))
        val builder =
            HttpRequest
                .newBuilder(URI("$base/api/v1/auth/login"))
                .header("X-ModSync-Protocol", "1")
                .header("X-ModSync-Version", "0.3.1")
                .header("Content-Type", "application/json")
        val response = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        return if (response.statusCode() == 200) wire.decodeFromString(Credentials.serializer(), response.body()) else null.also { assertEquals(401, response.statusCode()) }
    }

    /** Creates a CLIENT account through the same service the administrator API uses. */
    private fun newClient(): String {
        val name = "player-${UUID.randomUUID().toString().take(8)}"
        context.getBean(AuthService::class.java).createUser("test", UserRegistration(name, password))
        return name
    }

    /** Disabling an account blocks login, refresh and already-issued access tokens; enabling restores them. */
    @Test fun disabledClientIsRefusedEverywhere() {
        val name = newClient()
        val session = requireNotNull(login(name, password))
        assertEquals(200, api("GET", "/api/v1/devices", session.token))

        db.update("UPDATE users SET enabled = FALSE WHERE username = ?", name)
        assertEquals(401, api("GET", "/api/v1/devices", session.token))
        assertEquals(401, api("POST", "/api/v1/auth/refresh", body = wire.encodeToString(RefreshRequest.serializer(), RefreshRequest(requireNotNull(session.refreshToken)))))
        assertEquals(null, login(name, password))

        db.update("UPDATE users SET enabled = TRUE WHERE username = ?", name)
        assertEquals(200, api("GET", "/api/v1/devices", session.token))
        requireNotNull(login(name, password))
    }

    /** A disabled administrator is refused at the dashboard login with the same redirect as a wrong password. */
    @Test fun disabledAdministratorCannotSignIn() {
        val name = "admin-${UUID.randomUUID().toString().take(8)}"
        val encoder = context.getBean(PasswordEncoder::class.java)
        db.update("INSERT INTO users (id, username, password_hash, role, enabled) VALUES (?,?,?,'ADMIN',FALSE)", UUID.randomUUID().toString(), name, encoder.encode(password))

        val wrong = signIn(name, "definitely-the-wrong-password")
        val disabled = signIn(name, password)
        assertEquals(302, disabled.statusCode())
        assertEquals(wrong.headers().firstValue("Location").get(), disabled.headers().firstValue("Location").get())
        assertEquals("/login?error", URI(disabled.headers().firstValue("Location").get()).let { it.path + "?" + it.query })

        db.update("UPDATE users SET enabled = TRUE WHERE username = ?", name)
        assertEquals("/ui", URI(signIn(name, password).headers().firstValue("Location").get()).path)
    }

    /** Submits the dashboard login form with a fresh browser session. */
    private fun signIn(user: String, pass: String): HttpResponse<String> {
        val browser =
            HttpClient
                .newBuilder()
                .cookieHandler(CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
        val page = browser.send(HttpRequest.newBuilder(URI("$base/login")).build(), HttpResponse.BodyHandlers.ofString()).body()
        val csrf = Regex("name=\"_csrf\" value=\"([^\"]+)\"").find(page)?.groupValues?.get(1) ?: error("No CSRF token in page")
        val form = mapOf("username" to user, "password" to pass, "_csrf" to csrf).entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" }
        val request =
            HttpRequest
                .newBuilder(URI("$base/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
        return browser.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
