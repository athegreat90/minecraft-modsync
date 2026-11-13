package de.alexandermora.modsync.modrinth

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class ModrinthTest {
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Triple<String, String?, String>>()

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            requests += Triple(exchange.requestMethod + " " + exchange.requestURI.path, exchange.requestHeaders.getFirst("User-Agent"), body)
            val answer = (if (exchange.requestURI.path.endsWith("version_files")) "{}" else """{"id":"abc"}""").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        server.start()
    }

    @AfterTest
    fun stopServer() = server.stop(0)

    private fun client() = Modrinth(URI("http://127.0.0.1:${server.address.port}/"))

    /** Every API call identifies this component and version, for GETs and POSTs alike. */
    @Test fun `requests carry the component user agent`() {
        val modrinth = client()
        assertEquals("abc", modrinth.version("abc")["id"]?.jsonPrimitive?.content)
        assertTrue(modrinth.identify(listOf("a".repeat(128))).isEmpty())
        assertEquals(listOf("GET /version/abc", "POST /version_files"), requests.map { it.first })
        assertTrue(requests.all { it.second == "Minecraft-ModSync/0.4.0 (self-hosted mod inventory)" })
        assertTrue(requests.last().third.contains("sha512"))
    }

    /** A repeated call is answered from the cache without another request. */
    @Test fun `identical calls are cached`() {
        val modrinth = client()
        modrinth.project("abc")
        modrinth.project("abc")
        assertEquals(1, requests.size)
    }

    @Test fun `invalid hashes are rejected before any request`() {
        assertFailsWith<IllegalArgumentException> { Modrinth().identify(listOf("ABC")) }
    }

    @Test fun `identifiers cannot alter the request path`() {
        val client = Modrinth()
        listOf("../admin", "a/b", "", "a b").forEach {
            assertFailsWith<IllegalArgumentException> { client.version(it) }
            assertFailsWith<IllegalArgumentException> { client.project(it) }
            assertFailsWith<IllegalArgumentException> { client.compatibleVersions(it, "1.21.1", "neoforge") }
        }
    }
}
