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
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the single sign-on contract against the running application, without contacting any provider.
 *
 * The authorization request is built locally, so its parameters can be inspected; a callback with an invented state must be
 * rejected before any provider is asked anything.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SsoIntegrationTest {
    companion object {
        @Container @JvmField
        val database = MariaDBContainer<Nothing>("mariadb:11.4.13")
    }

    private val state: Path = Files.createTempDirectory("modsync-sso-test")
    private lateinit var configured: ConfigurableApplicationContext
    private lateinit var plain: ConfigurableApplicationContext
    private val adminPassword = "fixture-only-bootstrap-password"

    private fun start(vararg extra: String): ConfigurableApplicationContext {
        val passwordFile = state.resolve("bootstrap-password")
        Files.writeString(passwordFile, adminPassword)
        return SpringApplicationBuilder(ServerApplication::class.java).run(
            "--server.port=0",
            "--server.servlet.session.cookie.secure=false",
            "--spring.datasource.url=${database.jdbcUrl}",
            "--spring.datasource.username=${database.username}",
            "--spring.datasource.password=${database.password}",
            "--modsync.bootstrap-password-file=$passwordFile",
            "--modsync.maintenance=false",
            *extra
        )
    }

    private fun urlOf(app: ConfigurableApplicationContext) = "http://127.0.0.1:${requireNotNull((app as WebServerApplicationContext).webServer).port}"

    /** Starts one instance with both providers configured and one without any. */
    @BeforeAll fun startBoth() {
        configured =
            start(
                "--modsync.sso.google.client-id=test-google-id",
                "--modsync.sso.google.client-secret=test-google-secret",
                "--modsync.sso.github.client-id=test-github-id",
                "--modsync.sso.github.client-secret=test-github-secret"
            )
        plain = start()
    }

    /** Stops both instances. */
    @AfterAll fun stopBoth() {
        configured.close()
        plain.close()
        state.toFile().deleteRecursively()
    }

    private fun query(location: String): Map<String, String> =
        location
            .substringAfter('?')
            .split('&')
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) }

    /** The login page can learn which providers exist without signing in. */
    @Test fun providersAreListedAnonymously() {
        val response = TestBrowser(urlOf(configured)).json("GET", "/ui/api/sso/providers")

        assertEquals(200, response.statusCode())
        assertEquals("""{"providers":["github","google"]}""", response.body())
        assertEquals("""{"providers":[]}""", TestBrowser(urlOf(plain)).json("GET", "/ui/api/sso/providers").body())
    }

    /** Google gets the authorization-code flow with state, nonce, PKCE and only identity scopes. */
    @Test fun googleAuthorizationRequestIsHardened() {
        val response = TestBrowser(urlOf(configured)).get("/oauth2/authorization/google")

        val location = response.headers().firstValue("Location").get()
        assertEquals(302, response.statusCode())
        assertTrue(location.startsWith("https://accounts.google.com/"), location)
        val params = query(location)
        assertEquals("code", params["response_type"])
        assertEquals("openid profile email", params["scope"])
        assertTrue(params.getValue("state").length >= 20)
        assertTrue(params.containsKey("nonce"))
        assertEquals("S256", params["code_challenge_method"])
        assertTrue(params.getValue("redirect_uri").endsWith("/login/oauth2/code/google"))
    }

    /** GitHub gets the same flow with the single read:user scope and no OpenID Connect parameters. */
    @Test fun githubAuthorizationRequestIsMinimal() {
        val response = TestBrowser(urlOf(configured)).get("/oauth2/authorization/github")

        val params = query(response.headers().firstValue("Location").get())
        assertEquals(302, response.statusCode())
        assertEquals("read:user", params["scope"])
        assertTrue(params.getValue("state").length >= 20)
        assertFalse(params.containsKey("nonce"))
        assertTrue(params.getValue("redirect_uri").endsWith("/login/oauth2/code/github"))
    }

    /** A callback with an invented state is refused and leaves the browser signed out. */
    @Test fun callbackWithUnknownStateIsRefused() {
        val browser = TestBrowser(urlOf(configured))

        val callback = browser.get("/login/oauth2/code/google?code=invented&state=invented")

        assertEquals(302, callback.statusCode())
        assertEquals("/login?sso=denied", callback.location())
        assertEquals(401, browser.json("GET", "/ui/api/session").statusCode())
    }

    /** Without credentials no provider endpoint exists. */
    @Test fun unconfiguredInstanceHasNoProviderEndpoints() {
        val response = TestBrowser(urlOf(plain)).get("/oauth2/authorization/google")

        assertEquals(404, response.statusCode())
    }

    /** Linking needs a signed-in administrator, a CSRF token and a configured provider. */
    @Test fun linkingNeedsSessionCsrfAndAConfiguredProvider() {
        val anonymous = TestBrowser(urlOf(configured))
        assertEquals(401, anonymous.json("GET", "/ui/api/me/identities").statusCode())
        assertEquals(403, anonymous.json("POST", "/ui/api/me/identities/github/link").statusCode())

        val browser = TestBrowser(urlOf(configured))
        browser.signIn("admin", adminPassword)
        browser.json("GET", "/ui/api/session")
        val token = requireNotNull(browser.cookie("XSRF-TOKEN"))

        assertEquals(403, browser.json("POST", "/ui/api/me/identities/github/link").statusCode())
        assertEquals(404, browser.json("POST", "/ui/api/me/identities/gitlab/link", xsrf = token).statusCode())
        val started = browser.json("POST", "/ui/api/me/identities/github/link", xsrf = token)
        assertEquals(200, started.statusCode())
        assertEquals("""{"authorizationUrl":"/oauth2/authorization/github"}""", started.body())
    }
}
