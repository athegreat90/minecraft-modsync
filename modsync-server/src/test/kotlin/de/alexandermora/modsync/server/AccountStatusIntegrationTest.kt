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
import org.springframework.security.crypto.password.PasswordEncoder
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
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
    private lateinit var api: ApiProbe
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
        api = ApiProbe(base)
    }

    /** Stops the application. */
    @AfterAll fun stop() {
        context.close()
        state.toFile().deleteRecursively()
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
        val session = requireNotNull(api.login(name, password).second)
        assertEquals(200, api.status("GET", "/api/v1/devices", session.token))

        db.update("UPDATE users SET enabled = FALSE WHERE username = ?", name)
        assertEquals(401, api.status("GET", "/api/v1/devices", session.token))
        assertEquals(401, api.refresh(requireNotNull(session.refreshToken)))
        assertEquals(401, api.login(name, password).first)

        db.update("UPDATE users SET enabled = TRUE WHERE username = ?", name)
        assertEquals(200, api.status("GET", "/api/v1/devices", session.token))
        assertEquals(200, api.login(name, password).first)
    }

    /** A disabled administrator is refused at the dashboard login with the same redirect as a wrong password. */
    @Test fun disabledAdministratorCannotSignIn() {
        val name = "admin-${UUID.randomUUID().toString().take(8)}"
        val encoder = context.getBean(PasswordEncoder::class.java)
        db.update("INSERT INTO users (id, username, password_hash, role, enabled) VALUES (?,?,?,'ADMIN',FALSE)", UUID.randomUUID().toString(), name, encoder.encode(password))

        val wrong = TestBrowser(base).signIn(name, "definitely-the-wrong-password")
        val disabled = TestBrowser(base).signIn(name, password)
        assertEquals(302, disabled.statusCode())
        assertEquals(wrong.location(), disabled.location())
        assertEquals("/login?error", disabled.location())

        db.update("UPDATE users SET enabled = TRUE WHERE username = ?", name)
        assertEquals("/ui", TestBrowser(base).signIn(name, password).location())
    }
}
