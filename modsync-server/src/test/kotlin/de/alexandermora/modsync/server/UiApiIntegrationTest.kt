package de.alexandermora.modsync.server

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Proves the security contract of the JSON administration API: anonymous 401, CSRF cookie and header, and an untouched client API. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UiApiIntegrationTest {
    companion object {
        @Container @JvmField
        val database = MariaDBContainer<Nothing>("mariadb:11.4.13")
    }

    private val state: Path = Files.createTempDirectory("modsync-uiapi-test")
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var base: String
    private val adminPassword = "fixture-only-bootstrap-password"

    /** Starts the full application against MariaDB. */
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
                "--modsync.bootstrap-password-file=$passwordFile",
                "--modsync.maintenance=false"
            )
        base = "http://127.0.0.1:${requireNotNull((context as WebServerApplicationContext).webServer).port}"
    }

    /** Stops the application. */
    @AfterAll fun stop() {
        context.close()
        state.toFile().deleteRecursively()
    }

    /** Script clients get a plain 401, not a redirect to the login page. */
    @Test fun anonymousCallsAreUnauthorized() {
        assertEquals(401, TestBrowser(base).json("GET", "/ui/api/session").statusCode())
    }

    /** A signed-in administrator can read the session and receives the XSRF cookie. */
    @Test fun signedInAdministratorGetsSessionAndCsrfCookie() {
        val browser = TestBrowser(base)
        assertEquals(302, browser.signIn("admin", adminPassword).statusCode())

        val session = browser.json("GET", "/ui/api/session")

        assertEquals(200, session.statusCode())
        assertTrue(session.body().contains("\"username\":\"admin\""), session.body())
        assertNotNull(browser.cookie("XSRF-TOKEN"))
    }

    /** Writes fail without the CSRF header and pass the check with it. */
    @Test fun writesNeedTheCsrfHeader() {
        val browser = TestBrowser(base)
        browser.signIn("admin", adminPassword)
        browser.json("GET", "/ui/api/session")
        val token = requireNotNull(browser.cookie("XSRF-TOKEN"))

        assertEquals(403, browser.json("POST", "/ui/api/users/unknown/disable").statusCode())
        assertEquals(404, browser.json("POST", "/ui/api/users/unknown/disable", xsrf = token).statusCode())
    }

    /** The bearer-token API keeps answering without any session or cookie. */
    @Test fun clientApiIsUnaffected() {
        val response = TestBrowser(base).json("GET", "/api/v1/info")

        assertEquals(200, response.statusCode())
    }
}
