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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the hardening headers of both security chains and the `XSRF-TOKEN` cookie attributes.
 *
 * The application honours forwarded headers, so a request carrying `X-Forwarded-Proto: https` counts as secure while a plain request
 * does not, like behind a TLS proxy.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityHeadersIntegrationTest {
    companion object {
        @Container @JvmField
        val database = MariaDBContainer<Nothing>("mariadb:11.4.13")

        private const val REFERRER_POLICY = "same-origin"
        private const val PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=()"
        private const val CONTENT_SECURITY_POLICY = "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; frame-ancestors 'none'; form-action 'self'"
    }

    private val state: Path = Files.createTempDirectory("modsync-headers-test")
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var base: String
    private val adminPassword = "fixture-only-bootstrap-password"
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    /** Starts the application against MariaDB. */
    @BeforeAll fun start() {
        val passwordFile = state.resolve("bootstrap-password")
        Files.writeString(passwordFile, adminPassword)
        context =
            SpringApplicationBuilder(ServerApplication::class.java).run(
                "--server.port=0",
                "--server.forward-headers-strategy=framework",
                "--server.servlet.session.cookie.secure=false",
                "--spring.datasource.url=${database.jdbcUrl}",
                "--spring.datasource.username=${database.username}",
                "--spring.datasource.password=${database.password}",
                "--modsync.bootstrap-password-file=$passwordFile"
            )
        base = "http://127.0.0.1:${requireNotNull((context as WebServerApplicationContext).webServer).port}"
    }

    /** Stops the application. */
    @AfterAll fun stop() {
        context.close()
        state.toFile().deleteRecursively()
    }

    private fun get(
        base: String,
        path: String,
        secure: Boolean = false
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("$base$path"))
        if (secure) request.header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "modsync.example.test")
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun HttpResponse<*>.header(name: String): String? = headers().firstValue(name).orElse(null)

    private fun HttpResponse<*>.cookie(name: String): String? = headers().allValues("Set-Cookie").firstOrNull { it.startsWith("$name=") }

    /** Referrer-Policy and Permissions-Policy are sent on the login page, on an administrator API call and on the bearer API. */
    @Test fun hardeningHeadersAreSentOnBothChains() {
        val admin = TestBrowser(base)
        assertEquals(302, admin.signIn("admin", adminPassword).statusCode())
        val responses =
            mapOf(
                "dashboard page" to get(base, "/ui/login"),
                "dashboard api" to admin.json("GET", "/ui/api/overview"),
                "bearer api" to TestBrowser(base).json("GET", "/api/v1/info")
            )
        for ((name, response) in responses) {
            assertEquals(200, response.statusCode(), name)
            assertEquals(REFERRER_POLICY, response.header("Referrer-Policy"), name)
            assertEquals(PERMISSIONS_POLICY, response.header("Permissions-Policy"), name)
        }
    }

    /** Spring's own HSTS is untouched: a request seen as HTTPS gets one year with subdomains, on both chains. */
    @Test fun hstsIsStillSentForRequestsSeenAsHttps() {
        for (path in listOf("/ui/login", "/api/v1/info")) {
            assertEquals("max-age=31536000 ; includeSubDomains", get(base, path, secure = true).header("Strict-Transport-Security"), path)
        }
    }

    /** A plain-HTTP request gets no Strict-Transport-Security. */
    @Test fun hstsIsNotSentOverPlainHttp() {
        for (path in listOf("/ui/login", "/api/v1/info")) {
            assertNull(get(base, path).header("Strict-Transport-Security"), path)
        }
    }

    /** The hardening headers are also sent on requests seen as HTTPS. */
    @Test fun hardeningHeadersAreSentOnSecureRequests() {
        for (path in listOf("/ui/login", "/api/v1/info")) {
            val response = get(base, path, secure = true)
            assertEquals(REFERRER_POLICY, response.header("Referrer-Policy"), path)
            assertEquals(PERMISSIONS_POLICY, response.header("Permissions-Policy"), path)
        }
    }

    /** The XSRF cookie is lax and readable by the page script; it is Secure only for requests the server sees as HTTPS. */
    @Test fun xsrfCookieIsLaxAndReadableByScript() {
        val plain = requireNotNull(get(base, "/ui/login").cookie("XSRF-TOKEN"), { "no XSRF-TOKEN cookie" })
        assertTrue(plain.contains("SameSite=Lax"), plain)
        assertTrue(plain.contains("Path=/"), plain)
        assertFalse(plain.contains("HttpOnly", ignoreCase = true), plain)
        assertFalse(plain.contains("Secure"), plain)

        val secure = requireNotNull(get(base, "/ui/login", secure = true).cookie("XSRF-TOKEN"), { "no XSRF-TOKEN cookie" })
        assertTrue(secure.contains("SameSite=Lax"), secure)
        assertTrue(secure.contains("Secure"), secure)
        assertFalse(secure.contains("HttpOnly", ignoreCase = true), secure)
    }

    /** The session cookie keeps the attributes configured in application.yaml. */
    @Test fun sessionCookieAttributesAreUnchanged() {
        val login = TestBrowser(base)
        val csrf = login.csrfOf("/ui/login")
        val response = login.post("/login", mapOf("username" to "admin", "password" to adminPassword), csrf)
        val session = requireNotNull(response.cookie("JSESSIONID"), { "no JSESSIONID cookie" })
        assertTrue(session.contains("HttpOnly"), session)
        assertTrue(session.contains("SameSite=Lax"), session)
    }

    /** The previous headers of a dashboard response are untouched. */
    @Test fun existingDashboardHeadersAreUnchanged() {
        val response = get(base, "/ui/login")
        assertEquals(CONTENT_SECURITY_POLICY, response.header("Content-Security-Policy"))
        assertEquals("DENY", response.header("X-Frame-Options"))
        assertEquals("nosniff", response.header("X-Content-Type-Options"))
    }

    /** A login form with a stale CSRF token still goes back to the login page with a reason. */
    @Test fun staleCsrfTokenStillRedirects() {
        val browser = TestBrowser(base)
        browser.csrfOf("/ui/login")

        val stale = browser.post("/login", mapOf("username" to "admin", "password" to adminPassword), "stale-token")

        assertEquals(302, stale.statusCode())
        assertEquals("/ui/login?csrf", stale.header("Location"))
    }
}
