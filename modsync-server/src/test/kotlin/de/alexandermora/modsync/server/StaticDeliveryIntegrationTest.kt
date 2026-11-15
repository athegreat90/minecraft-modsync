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

/** Verifies how the administration UI files are delivered: text compression and the cache policy of fingerprinted versus mutable files. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaticDeliveryIntegrationTest {
    companion object {
        @Container @JvmField
        val database = MariaDBContainer<Nothing>("mariadb:11.4.13")

        private val hashedAsset = Regex("""(?:src|href)="((?:main|chunk|styles)-[A-Za-z0-9_-]{8}\.(?:js|css))"""")
    }

    private val state: Path = Files.createTempDirectory("modsync-static-test")
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var base: String
    private val adminPassword = "fixture-only-bootstrap-password"
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

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
        path: String,
        acceptEncoding: String? = null
    ): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI("$base$path"))
        acceptEncoding?.let { request.header("Accept-Encoding", it) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun HttpResponse<*>.header(name: String): String? = headers().firstValue(name).orElse(null)

    /** The fingerprinted files the application shell references, discovered from the shell itself. */
    private fun fingerprinted(): List<String> {
        val shell = String(get("/ui/index.html").body(), Charsets.UTF_8)
        val found = hashedAsset.findAll(shell).map { it.groupValues[1] }.toList()
        assertTrue(found.any { it.endsWith(".js") } && found.any { it.endsWith(".css") }, "no fingerprinted script and style in $found")
        return found
    }

    /** Fingerprinted scripts and styles are cached for a year as immutable. */
    @Test fun fingerprintedAssetsAreImmutable() {
        for (name in fingerprinted()) {
            val response = get("/ui/$name")
            assertEquals(200, response.statusCode(), name)
            val cache = requireNotNull(response.header("Cache-Control"), { "no Cache-Control for $name" })
            assertTrue(cache.contains("max-age=31536000"), "$name: $cache")
            assertTrue(cache.contains("immutable"), "$name: $cache")
            assertTrue(cache.contains("public"), "$name: $cache")
            assertFalse(cache.contains("no-cache"), "$name: $cache")
        }
    }

    /** The shell and every file without a fingerprint keep revalidating on each use. */
    @Test fun mutableFilesStillRevalidate() {
        for (path in listOf("/ui/index.html", "/ui/favicon.ico")) {
            val response = get(path)
            assertEquals(200, response.statusCode(), path)
            val cache = requireNotNull(response.header("Cache-Control"), { "no Cache-Control for $path" })
            assertTrue(cache.contains("no-cache"), "$path: $cache")
            assertFalse(cache.contains("immutable"), "$path: $cache")
        }
        // The single-page-application fallback serves the same shell through a forward and must not become immutable either.
        val fallback = requireNotNull(get("/ui/login").header("Cache-Control"))
        assertFalse(fallback.contains("immutable"), fallback)
    }

    /**
     * Text files above the minimum response size are gzip-compressed for clients that accept it, with Vary so shared caches keep both variants.
     * The prerendered shell of a production build is far larger than the threshold; a small development shell is correctly sent as is.
     */
    @Test fun textIsCompressedWhenAccepted() {
        var compressed = 0
        for (path in listOf("/ui/index.html") + fingerprinted().map { "/ui/$it" }) {
            if (get(path).body().size < 1024) continue
            val response = get(path, acceptEncoding = "gzip")
            assertEquals(200, response.statusCode(), path)
            assertEquals("gzip", response.header("Content-Encoding"), path)
            assertTrue(requireNotNull(response.header("Vary"), { "no Vary for $path" }).contains("Accept-Encoding", ignoreCase = true), path)
            compressed++
        }
        assertTrue(compressed >= 2, "expected the bundles to be compressed, only $compressed were")
    }

    /** Clients that do not ask for compression receive the plain bytes. */
    @Test fun textIsPlainWithoutAcceptEncoding() {
        for (path in listOf("/ui/index.html") + fingerprinted().map { "/ui/$it" }) {
            assertNull(get(path).header("Content-Encoding"), path)
        }
    }

    /** The authenticated JSON API and the bearer API keep their previous behaviour. */
    @Test fun apisAreUnchanged() {
        val browser = TestBrowser(base)
        assertEquals("/ui", browser.signIn("admin", adminPassword).location())
        val overview = browser.json("GET", "/ui/api/overview")
        assertEquals(200, overview.statusCode())
        assertTrue(
            overview
                .headers()
                .firstValue("Cache-Control")
                .get()
                .contains("no-store")
        )
        assertNull(overview.headers().firstValue("Content-Encoding").orElse(null))
        assertEquals(200, TestBrowser(base).json("GET", "/api/v1/info").statusCode())
    }
}
